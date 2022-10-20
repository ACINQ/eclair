/*
 * Copyright 2022 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.plugins.peerswap

import akka.actor
import akka.actor.typed.eventstream.EventStream.Publish
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong}
import fr.acinq.eclair.MilliSatoshi.toMilliSatoshi
import fr.acinq.eclair.blockchain.OnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchFundingDeeplyBuriedTriggered, WatchTxConfirmedTriggered}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.payment.receive.MultiPartHandler.{CreateInvoiceActor, ReceivePayment}
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentReceived}
import fr.acinq.eclair.plugins.peerswap.SwapCommands._
import fr.acinq.eclair.plugins.peerswap.SwapEvents._
import fr.acinq.eclair.plugins.peerswap.SwapHelpers._
import fr.acinq.eclair.plugins.peerswap.SwapResponses.{CreateFailed, Error, Fail, InternalError, InvalidMessage, PeerCanceled, SwapError, SwapStatus, UserCanceled}
import fr.acinq.eclair.plugins.peerswap.SwapRole.Maker
import fr.acinq.eclair.plugins.peerswap.SwapScripts.claimByCsvDelta
import fr.acinq.eclair.plugins.peerswap.db.SwapsDb
import fr.acinq.eclair.plugins.peerswap.transactions.SwapTransactions._
import fr.acinq.eclair.plugins.peerswap.wire.protocol._
import fr.acinq.eclair.{NodeParams, ShortChannelId, TimestampSecond}
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object SwapMaker {
  /*
                             SwapMaker                                SwapTaker

                                                "Swap Out"
                                 RESPONDER                        INITIATOR
                                     |                                | [createSwap]
                                     |        SwapOutRequest          |
                                     |<-------------------------------|
                   [validateRequest] |                                | [awaitAgreement]
                                     |                                |
                                     |        SwapOutAgreement        |
                                     |------------------------------->|
                   [awaitFeePayment] |                                | [validateFeeInvoice]
                                     |                                |
                                     |       <pay fee invoice>        | [payFeeInvoice]
                                     |<------------------------------>|
                   [createOpeningTx] |                                |
                                     |                                |
           [awaitOpeningTxConfirmed] |                                |
                                     |       OpeningTxBroadcasted     |
                                     |------------------------------->|
                                     |                                | [awaitOpeningTxConfirmed]

                                                 "Swap In"
                                 INITIATOR                        RESPONDER
                        [createSwap] |                                |
                                     |         SwapInRequest          |
                                     |------------------------------->|
                    [awaitAgreement] |                                | [validateRequest]
                                     |                                |
                                     |        SwapInAgreement         | [sendAgreement]
                                     |<-------------------------------|
                   [createOpeningTx] |                                |
                                     |                                |
           [awaitOpeningTxConfirmed] |                                |
                                     |       OpeningTxBroadcasted     |
                                     |------------------------------->|
                                     |                                | [awaitOpeningTxConfirmed]

                                            "Claim With Preimage"
                 [awaitClaimPayment] |                                | [validateOpeningTx]
                                     |                                |
                                     |       <pay claim invoice>      | [payClaimInvoice]
                                     |<------------------------------>|
                                     |                                | [claimSwap] (claim_by_invoice)

                                            "Refund Cooperatively"
                                     |                                |
                                     |            CoopClose           | [sendCoopClose]
                                     |<-------------------------------|
     (claim_by_coop) [claimSwapCoop] |                                |

                                             "Refund After Csv"
                           [waitCsv] |                                |
                                     |                                |
       (claim_by_csv) [claimSwapCsv] |                                |

  */

  def apply(nodeParams: NodeParams, watcher: ActorRef[ZmqWatcher.Command], register: actor.ActorRef, wallet: OnChainWallet, keyManager: SwapKeyManager, db: SwapsDb): Behavior[SwapCommands.SwapCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial {
        case StartSwapInSender(amount, swapId, shortChannelId) =>
          new SwapMaker(shortChannelId, nodeParams, watcher, register, wallet, keyManager, db, context)
            .createSwap(amount, swapId)
        case StartSwapOutReceiver(request: SwapOutRequest) =>
          ShortChannelId.fromCoordinates(request.scid) match {
            case Success(shortChannelId) => new SwapMaker(shortChannelId, nodeParams, watcher, register, wallet, keyManager, db, context)
              .validateRequest(request)
            case Failure(e) => context.log.error(s"received swap request with invalid shortChannelId: $request, $e")
              Behaviors.stopped
          }
        case RestoreSwap(d) =>
          ShortChannelId.fromCoordinates(d.request.scid) match {
            case Success(shortChannelId) => new SwapMaker(shortChannelId, nodeParams, watcher, register, wallet, keyManager, db, context)
              .awaitClaimPayment(d.request, d.agreement, d.invoice, d.openingTxBroadcasted, d.isInitiator)
            case Failure(e) => context.log.error(s"could not restore swap sender with invalid shortChannelId: $d, $e")
              Behaviors.stopped
          }
        case AbortSwap => Behaviors.stopped
      }
    }
}

private class SwapMaker(shortChannelId: ShortChannelId, nodeParams: NodeParams, watcher: ActorRef[ZmqWatcher.Command], register: actor.ActorRef, wallet: OnChainWallet, keyManager: SwapKeyManager, db: SwapsDb, implicit val context: ActorContext[SwapCommands.SwapCommand]) {
  val protocolVersion = 2
  val noAsset = ""
  implicit val timeout: Timeout = 30 seconds
  private implicit val feeRatePerKw: FeeratePerKw = nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
  private val openingFee = (feeRatePerKw * openingTxWeight / 1000).toLong // TODO: how should swap out initiator calculate an acceptable swap opening tx fee?
  private val maxPremium = (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong // TODO: how should swap sender calculate an acceptable premium?

  private def makerPrivkey(swapId: String): PrivateKey = keyManager.openingPrivateKey(SwapKeyManager.keyPath(swapId)).privateKey
  private def makerPubkey(swapId: String): PublicKey = makerPrivkey(swapId).publicKey

  private def takerPubkey(request: SwapRequest, agreement: SwapAgreement, isInitiator: Boolean): PublicKey =
    PublicKey(ByteVector.fromValidHex(
      if (isInitiator) {
        agreement.pubkey
      } else {
        request.pubkey
      }))

  private def createSwap(amount: Satoshi, swapId: String): Behavior[SwapCommand] = {
    awaitAgreement(SwapInRequest(protocolVersion, swapId, noAsset, NodeParams.chainFromHash(nodeParams.chainHash), shortChannelId.toString, amount.toLong, makerPubkey(swapId).toHex))
  }

  def validateRequest(request: SwapOutRequest): Behavior[SwapCommand] = {
    // fail if swap out request is invalid, otherwise respond with agreement
    if (request.protocolVersion != protocolVersion || request.asset != noAsset || request.network != NodeParams.chainFromHash(nodeParams.chainHash)) {
      swapCanceled(InternalError(request.swapId, s"incompatible request: $request."))
    } else {
      createInvoice(nodeParams, openingFee.sat, "receive-swap-out") match {
        case Success(invoice) => awaitFeePayment(request, SwapOutAgreement(protocolVersion, request.swapId, makerPubkey(request.swapId).toHex, invoice.toString), invoice)
        case Failure(exception) => swapCanceled(CreateFailed(request.swapId, "could not create invoice"))
      }
    }
  }

  private def awaitFeePayment(request: SwapOutRequest, agreement: SwapOutAgreement, invoice: Bolt11Invoice): Behavior[SwapCommand] = {
    watchForPayment(watch = true) // subscribe to be notified of payment events
    sendShortId(register, shortChannelId)(agreement)

    Behaviors.withTimers { timers =>
      timers.startSingleTimer(swapFeeExpiredTimer(request.swapId), InvoiceExpired, invoice.createdAt + invoice.relativeExpiry.toSeconds - TimestampSecond.now())
      receiveSwapMessage[AwaitFeePaymentMessages](context, "sendAgreement") {
        case PaymentEventReceived(payment: PaymentReceived) if payment.paymentHash == invoice.paymentHash && payment.amount >= invoice.amount_opt.get =>
          createOpeningTx(request, agreement, isInitiator = false)
        case PaymentEventReceived(_) => Behaviors.same
        case SwapMessageReceived(cancel: CancelSwap) => swapCanceled(PeerCanceled(request.swapId, cancel.message))
        case SwapMessageReceived(m) => swapCanceled(InvalidMessage(request.swapId, "awaitFeePayment", m))
        case StateTimeout => swapCanceled(InternalError(request.swapId, "timeout during awaitFeePayment"))
        case InvoiceExpired => swapCanceled(InternalError(request.swapId, "fee payment invoice expired"))
        case ForwardShortIdFailureAdapter(_) => swapCanceled(InternalError(request.swapId, s"could not forward swap agreement to peer."))
        case CancelRequested(replyTo) => replyTo ! UserCanceled(request.swapId)
          swapCanceled(UserCanceled(request.swapId))
        case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "awaitFeePayment", request, Some(agreement))
          Behaviors.same
      }
    }
  }

  private def awaitAgreement(request: SwapInRequest): Behavior[SwapCommand] = {
    sendShortId(register, shortChannelId)(request)

    receiveSwapMessage[AwaitAgreementMessages](context, "awaitAgreement") {
      case SwapMessageReceived(agreement: SwapInAgreement) if agreement.protocolVersion != protocolVersion =>
        swapCanceled(InternalError(request.swapId, s"protocol version must be $protocolVersion."))
      case SwapMessageReceived(agreement: SwapInAgreement) if agreement.premium > maxPremium =>
        swapCanceled(InternalError(request.swapId, "unacceptable premium requested."))
      case SwapMessageReceived(agreement: SwapInAgreement) => createOpeningTx(request, agreement, isInitiator = true)
      case SwapMessageReceived(cancel: CancelSwap) => swapCanceled(PeerCanceled(request.swapId, cancel.message))
      case StateTimeout => swapCanceled(InternalError(request.swapId, "timeout during awaitAgreement"))
      case ForwardFailureAdapter(_) => swapCanceled(InternalError(request.swapId, s"could not forward swap request to peer."))
      case SwapMessageReceived(m) => swapCanceled(InvalidMessage(request.swapId, "awaitAgreement", m))
      case CancelRequested(replyTo) => replyTo ! UserCanceled(request.swapId)
        swapCanceled(UserCanceled(request.swapId))
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "awaitAgreement", request)
        Behaviors.same
    }
  }

  def createOpeningTx(request: SwapRequest, agreement: SwapAgreement, isInitiator: Boolean): Behavior[SwapCommand] = {
    val receivePayment = ReceivePayment(Some(toMilliSatoshi(Satoshi(request.amount))), Left("send-swap-in"))
    val createInvoice = context.spawnAnonymous(CreateInvoiceActor(nodeParams))
    createInvoice ! CreateInvoiceActor.CreateInvoice(context.messageAdapter[Bolt11Invoice](InvoiceResponse).toClassic, receivePayment)

    receiveSwapMessage[CreateOpeningTxMessages](context, "createOpeningTx") {
      case InvoiceResponse(invoice: Bolt11Invoice) => fundOpening(wallet, feeRatePerKw)((request.amount + agreement.premium).sat, makerPubkey(request.swapId), takerPubkey(request, agreement, isInitiator), invoice)
        Behaviors.same
      // TODO: checkpoint PersistentSwapData for this swap to a database before committing the opening tx
      case OpeningTxFunded(invoice, fundingResponse) =>
        commitOpening(wallet)(request.swapId, invoice, fundingResponse, "swap-in-sender-opening")
        Behaviors.same
      case OpeningTxCommitted(invoice, openingTxBroadcasted) =>
        db.add(SwapData(request, agreement, invoice, openingTxBroadcasted, Maker, isInitiator))
        awaitClaimPayment(request, agreement, invoice, openingTxBroadcasted, isInitiator)
      case OpeningTxFailed(error, None) => swapCanceled(InternalError(request.swapId, s"failed to fund swap open tx, error: $error"))
      case OpeningTxFailed(error, Some(r)) => rollback(wallet)(error, r.fundingTx)
        Behaviors.same
      case RollbackSuccess(error, value) => swapCanceled(InternalError(request.swapId, s"rollback: Success($value), error: $error"))
      case RollbackFailure(error, t) => swapCanceled(InternalError(request.swapId, s"rollback exception: $t, error: $error"))
      case SwapMessageReceived(_) => Behaviors.same // ignore
      case StateTimeout =>
        // TODO: are we sure the opening transaction has not yet been committed? should we rollback locked funding outputs?
        swapCanceled(InternalError(request.swapId, "timeout during CreateOpeningTx"))
      case CancelRequested(replyTo) => replyTo ! SwapError(request.swapId, "Can not cancel swap after opening tx committed.")
        Behaviors.same // ignore
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "createOpeningTx", request, Some(agreement))
        Behaviors.same
    }
  }

  def awaitClaimPayment(request: SwapRequest, agreement: SwapAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted, isInitiator: Boolean): Behavior[SwapCommand] = {
    // TODO: query payment database for received payment
    watchForPayment(watch = true) // subscribe to be notified of payment events
    sendShortId(register, shortChannelId)(openingTxBroadcasted) // send message to peer about opening tx broadcast

    Behaviors.withTimers { timers =>
      timers.startSingleTimer(swapInvoiceExpiredTimer(request.swapId), InvoiceExpired, invoice.createdAt + invoice.relativeExpiry.toSeconds - TimestampSecond.now())
      receiveSwapMessage[AwaitClaimPaymentMessages](context, "awaitClaimPayment") {
        // TODO: do we need to check that all payment parts were on our given channel? eg. payment.parts.forall(p => p.fromChannelId == channelId)
        case PaymentEventReceived(payment: PaymentReceived) if payment.paymentHash == invoice.paymentHash && payment.amount >= request.amount.sat =>
          swapCompleted(ClaimByInvoicePaid(request.swapId, payment))
        case SwapMessageReceived(coopClose: CoopClose) => claimSwapCoop(request, agreement, invoice, openingTxBroadcasted, coopClose, isInitiator)
        case PaymentEventReceived(_) => Behaviors.same
        case SwapMessageReceived(_) => Behaviors.same
        case InvoiceExpired =>
          waitCsv(request, agreement, invoice, openingTxBroadcasted, isInitiator)
        case CancelRequested(replyTo) => replyTo ! SwapError(request.swapId, "Can not cancel swap after opening tx committed.")
          Behaviors.same
        case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "awaitClaimPayment", request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
          Behaviors.same
      }
    }
  }

  def claimSwapCoop(request: SwapRequest, agreement: SwapAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted, coopClose: CoopClose, isInitiator: Boolean): Behavior[SwapCommand] = {
    val takerPrivkey = PrivateKey(ByteVector.fromValidHex(coopClose.privkey))
    val openingTxId = ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId))
    val claimByCoopTx = makeSwapClaimByCoopTx(request.amount.sat + agreement.premium.sat, makerPrivkey(request.swapId), takerPrivkey, invoice.paymentHash, feeRatePerKw, openingTxId, openingTxBroadcasted.scriptOut.toInt)
    val inputInfo = makeSwapOpeningInputInfo(openingTxId, openingTxBroadcasted.scriptOut.toInt, request.amount.sat + agreement.premium.sat, makerPubkey(request.swapId), takerPrivkey.publicKey, invoice.paymentHash)
    def claimByCoopConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](ClaimTxConfirmed)
    def openingConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](OpeningTxConfirmed)

    watchForPayment(watch = false)
    watchForTxConfirmation(watcher)(openingConfirmedAdapter, openingTxId, 1) // watch for opening tx to be confirmed

    receiveSwapMessage[ClaimSwapCoopMessages](context, "claimSwapCoop") {
      case OpeningTxConfirmed(_) => watchForTxConfirmation(watcher)(claimByCoopConfirmedAdapter, claimByCoopTx.txid, nodeParams.channelConf.minDepthBlocks)
        commitClaim(wallet)(request.swapId, SwapClaimByCoopTx(inputInfo, claimByCoopTx), "swap-in-sender-claimbycoop")
        Behaviors.same
      case ClaimTxCommitted => Behaviors.same
      case ClaimTxConfirmed(confirmedTriggered) =>
        swapCompleted(ClaimByCoopConfirmed(request.swapId, confirmedTriggered))
      case ClaimTxFailed(_) => waitCsv(request, agreement, invoice, openingTxBroadcasted, isInitiator)
      case ClaimTxInvalid(_) => waitCsv(request, agreement, invoice, openingTxBroadcasted, isInitiator)
      case CancelRequested(replyTo) => replyTo ! SwapError(request.swapId, "Can not cancel swap after opening tx committed.")
        Behaviors.same
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "claimSwapCoop", request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def waitCsv(request: SwapRequest, agreement: SwapAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted, isInitiator: Boolean): Behavior[SwapCommand] = {
    // TODO: are we sure the opening transaction has been committed? should we rollback locked funding outputs?
    def csvDelayConfirmedAdapter: ActorRef[WatchFundingDeeplyBuriedTriggered] = context.messageAdapter[WatchFundingDeeplyBuriedTriggered](CsvDelayConfirmed)
    watchForPayment(watch = false)
    watchForTxCsvConfirmation(watcher)(csvDelayConfirmedAdapter, ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId)), claimByCsvDelta.toInt) // watch for opening tx to be buried enough that it can be claimed by csv

    receiveSwapMessage[WaitCsvMessages](context, "waitCsv") {
      case CsvDelayConfirmed(_) =>
        claimSwapCsv(request, agreement, invoice, openingTxBroadcasted, isInitiator)
      case StateTimeout =>
        // TODO: problem with the blockchain monitor?
        Behaviors.same
      case CancelRequested(replyTo) => replyTo ! SwapError(request.swapId, "Can not cancel swap after opening tx committed.")
        Behaviors.same
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "waitCsv", request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def claimSwapCsv(request: SwapRequest, agreement: SwapAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted, isInitiator: Boolean): Behavior[SwapCommand] = {
    val openingTxId = ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId))
    val claimByCsvTx = makeSwapClaimByCsvTx(request.amount.sat + agreement.premium.sat, makerPrivkey(request.swapId), takerPubkey(request, agreement, isInitiator), invoice.paymentHash, feeRatePerKw, openingTxId, openingTxBroadcasted.scriptOut.toInt)
    val inputInfo = makeSwapOpeningInputInfo(openingTxId, openingTxBroadcasted.scriptOut.toInt, request.amount.sat + agreement.premium.sat, makerPubkey(request.swapId), takerPubkey(request, agreement, isInitiator), invoice.paymentHash)
    def claimByCsvConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](ClaimTxConfirmed)

    commitClaim(wallet)(request.swapId, SwapClaimByCsvTx(inputInfo, claimByCsvTx), "swap-in-sender-claimByCsvTx")

    receiveSwapMessage[ClaimSwapCsvMessages](context, "claimSwapCsv") {
      case ClaimTxCommitted => watchForTxConfirmation(watcher)(claimByCsvConfirmedAdapter, claimByCsvTx.txid, nodeParams.channelConf.minDepthBlocks)
        Behaviors.same
      case ClaimTxConfirmed(confirmedTriggered) => swapCompleted(ClaimByCsvConfirmed(request.swapId, confirmedTriggered))
      case ClaimTxFailed(_) => waitCsv(request, agreement, invoice, openingTxBroadcasted, isInitiator)
      case ClaimTxInvalid(_) => waitCsv(request, agreement, invoice, openingTxBroadcasted, isInitiator)
      case StateTimeout =>
        // TODO: handle when claim tx not confirmed, resubmit the tx?
        Behaviors.same
      case CancelRequested(replyTo) => replyTo ! SwapError(request.swapId, "Can not cancel swap after opening tx committed.")
        Behaviors.same
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "claimSwapCsv", request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def swapCompleted(event: SwapEvent): Behavior[SwapCommand] = {
    context.system.eventStream ! Publish(event)
    context.log.info(s"completed swap: $event.")
    db.addResult(event)
    Behaviors.stopped
  }

  def swapCanceled(failure: Fail): Behavior[SwapCommand] = {
    val swapEvent = Canceled(failure.swapId, failure.toString)
    context.system.eventStream ! Publish(swapEvent)
    if (!failure.isInstanceOf[PeerCanceled]) sendShortId(register, shortChannelId)(CancelSwap(failure.swapId, failure.toString))
    failure match {
      case e: Error => context.log.error(s"canceled swap: $e")
      case f: Fail => context.log.info(s"canceled swap: $f")
      case _ => context.log.error(s"canceled swap $failure.swapId, reason: unknown.")
    }
    Behaviors.stopped
  }

}