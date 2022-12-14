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
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong}
import fr.acinq.eclair.MilliSatoshi.toMilliSatoshi
import fr.acinq.eclair.blockchain.OnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchFundingDeeplyBuriedTriggered, WatchTxConfirmedTriggered}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.db.IncomingPaymentStatus.Received
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.payment.receive.MultiPartHandler.{CreateInvoiceActor, ReceiveStandardPayment}
import fr.acinq.eclair.plugins.peerswap.SwapCommands._
import fr.acinq.eclair.plugins.peerswap.SwapEvents._
import fr.acinq.eclair.plugins.peerswap.SwapHelpers._
import fr.acinq.eclair.plugins.peerswap.SwapResponses._
import fr.acinq.eclair.plugins.peerswap.SwapRole.Maker
import fr.acinq.eclair.plugins.peerswap.db.SwapsDb
import fr.acinq.eclair.plugins.peerswap.transactions.SwapScripts.claimByCsvDelta
import fr.acinq.eclair.plugins.peerswap.transactions.SwapTransactions._
import fr.acinq.eclair.plugins.peerswap.wire.protocol._
import fr.acinq.eclair.{NodeParams, ShortChannelId, TimestampSecond}
import scodec.bits.ByteVector

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

  def apply(remoteNodeId: PublicKey, nodeParams: NodeParams, watcher: ActorRef[ZmqWatcher.Command], switchboard: actor.ActorRef, wallet: OnChainWallet, keyManager: SwapKeyManager, db: SwapsDb): Behavior[SwapCommands.SwapCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial {
        case StartSwapInSender(amount, swapId, shortChannelId) => new SwapMaker(remoteNodeId, shortChannelId, nodeParams, watcher, switchboard, wallet, keyManager, db, context)
            .createSwap(amount, swapId)
        case StartSwapOutReceiver(request: SwapOutRequest) =>
          ShortChannelId.fromCoordinates(request.scid) match {
            case Success(shortChannelId) => new SwapMaker(remoteNodeId, shortChannelId, nodeParams, watcher, switchboard, wallet, keyManager, db, context)
              .validateRequest(request)
            case Failure(e) => context.log.error(s"received swap request with invalid shortChannelId: $request, $e")
              Behaviors.stopped
          }
        case RestoreSwap(d) =>
          ShortChannelId.fromCoordinates(d.request.scid) match {
            case Success(shortChannelId) => new SwapMaker(remoteNodeId, shortChannelId, nodeParams, watcher, switchboard, wallet, keyManager, db, context)
              .awaitClaimPayment(d.request, d.agreement, d.invoice, d.openingTxBroadcasted, d.isInitiator)
            case Failure(e) =>
              context.log.error(s"Could not restore from a checkpoint with an invalid shortChannelId: $d, $e")
              db.addResult(CouldNotRestore(d.swapId, d))
              Behaviors.stopped
          }
      }
    }
}

private class SwapMaker(remoteNodeId: PublicKey, shortChannelId: ShortChannelId, nodeParams: NodeParams, watcher: ActorRef[ZmqWatcher.Command], switchboard: actor.ActorRef, wallet: OnChainWallet, keyManager: SwapKeyManager, db: SwapsDb, implicit val context: ActorContext[SwapCommands.SwapCommand]) {
  private val protocolVersion = 3
  private val noAsset = ""
  private implicit val feeRatePerKw: FeeratePerKw = nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
  // fee is the additional off-chain amount the Maker is asking for from the Taker to open the swap
  private val openingFee = (feeRatePerKw * openingTxWeight / 1000).toLong // TODO: how should swap out initiator calculate an acceptable swap opening tx fee?
  // premium is the additional on-chain amount the Taker is asking for from the Maker over the swap amount
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

  private def validateRequest(request: SwapOutRequest): Behavior[SwapCommand] = {
    // fail if swap out request is invalid, otherwise respond with agreement
    if (request.protocolVersion != protocolVersion || request.asset != noAsset || request.network != NodeParams.chainFromHash(nodeParams.chainHash)) {
      swapCanceled(IncompatibleRequest(request.swapId, request))
    } else {
      createInvoice(nodeParams, openingFee.sat, "receive-swap-out") match {
        case Success(invoice) => awaitFeePayment(request, SwapOutAgreement(protocolVersion, request.swapId, makerPubkey(request.swapId).toHex, invoice.toString), invoice)
        case Failure(exception) => swapCanceled(CreateInvoiceFailed(request.swapId, exception))
      }
    }
  }

  private def awaitFeePayment(request: SwapOutRequest, agreement: SwapOutAgreement, invoice: Bolt11Invoice): Behavior[SwapCommand] = {
    watchForPaymentReceived(watch = true)
    send(switchboard, remoteNodeId)(agreement)
    Behaviors.withTimers { timers =>
      timers.startSingleTimer(swapFeeExpiredTimer(request.swapId), InvoiceExpired, invoice.createdAt + invoice.relativeExpiry.toSeconds - TimestampSecond.now())
      receiveSwapMessage[AwaitFeePaymentMessages](context, "awaitFeePayment") {
        case WrappedPaymentReceived(p) if p.paymentHash == invoice.paymentHash && p.amount >= invoice.amount_opt.get =>
          createOpeningTx(request, agreement, isInitiator = false)
        case WrappedPaymentReceived(_) => Behaviors.same
        case SwapMessageReceived(cancel: CancelSwap) => swapCanceled(PeerCanceled(request.swapId, cancel.message))
        case SwapMessageReceived(m) => swapCanceled(InvalidMessage(request.swapId, "awaitFeePayment", m))
        case InvoiceExpired => swapCanceled(FeePaymentInvoiceExpired(request.swapId))
        case CancelRequested(replyTo) =>
          replyTo ! UserCanceled(request.swapId)
          swapCanceled(UserCanceled(request.swapId))
        case GetStatus(replyTo) =>
          logStatus(request.swapId, context.self.toString, "awaitFeePayment", request, Some(agreement))
          replyTo ! AwaitClaimPayment(request.swapId)
          Behaviors.same
      }
    }
  }

  private def awaitAgreement(request: SwapInRequest): Behavior[SwapCommand] = {
    send(switchboard, remoteNodeId)(request)
    receiveSwapMessage[AwaitAgreementMessages](context, "awaitAgreement") {
      case SwapMessageReceived(agreement: SwapInAgreement) if agreement.protocolVersion != protocolVersion =>
        swapCanceled(WrongVersion(request.swapId, protocolVersion))
      case SwapMessageReceived(agreement: SwapInAgreement) if agreement.premium > maxPremium =>
        swapCanceled(PremiumRejected(request.swapId))
      case SwapMessageReceived(agreement: SwapInAgreement) => createOpeningTx(request, agreement, isInitiator = true)
      case SwapMessageReceived(cancel: CancelSwap) => swapCanceled(PeerCanceled(request.swapId, cancel.message))
      case SwapMessageReceived(m) => swapCanceled(InvalidMessage(request.swapId, "awaitAgreement", m))
      case CancelRequested(replyTo) => replyTo ! UserCanceled(request.swapId)
        swapCanceled(UserCanceled(request.swapId))
      case GetStatus(replyTo) =>
        logStatus(request.swapId, context.self.toString, "awaitAgreement", request)
        replyTo ! AwaitClaimPayment(request.swapId)
        Behaviors.same
    }
  }

  private def createOpeningTx(request: SwapRequest, agreement: SwapAgreement, isInitiator: Boolean): Behavior[SwapCommand] = {
    val receivePayment = ReceiveStandardPayment(Some(toMilliSatoshi(Satoshi(request.amount))), Left(s"swap ${request.swapId}"))
    val createInvoice = context.spawnAnonymous(CreateInvoiceActor(nodeParams))
    createInvoice ! CreateInvoiceActor.CreateInvoice(context.messageAdapter[Bolt11Invoice](InvoiceResponse).toClassic, receivePayment)
    receiveSwapMessage[CreateOpeningTxMessages](context, "createOpeningTx") {
      case InvoiceResponse(invoice: Bolt11Invoice) => fundOpening(wallet, feeRatePerKw)((request.amount + agreement.premium).sat, makerPubkey(request.swapId), takerPubkey(request, agreement, isInitiator), invoice)
        Behaviors.same
      case OpeningTxFunded(invoice, fundingResponse) =>
        commitOpening(wallet)(request.swapId, invoice, fundingResponse, s"swap ${request.swapId} opening tx")
        Behaviors.same
      case OpeningTxCommitted(invoice, openingTxBroadcasted) =>
        db.add(SwapData(request, agreement, invoice, openingTxBroadcasted, Maker, isInitiator, remoteNodeId))
        awaitClaimPayment(request, agreement, invoice, openingTxBroadcasted, isInitiator)
      case OpeningTxFundingFailed(cause) => swapCanceled(OpeningFundingFailed(request.swapId, cause))
      case OpeningTxCommitFailed(r) => rollback(wallet)(r)
        Behaviors.same
      case RollbackSuccess(value, r) => swapCanceled(OpeningCommitFailed(request.swapId, value, r))
      case RollbackFailure(t, r) => swapCanceled(OpeningRollbackFailed(request.swapId, r, t))
      case SwapMessageReceived(_) => Behaviors.same // ignore
      case CancelRequested(replyTo) =>
        replyTo ! CancelAfterOpeningCommit(request.swapId)
        Behaviors.same // ignore
      case GetStatus(replyTo) =>
        logStatus(request.swapId, context.self.toString, "createOpeningTx", request, Some(agreement))
        replyTo ! AwaitClaimPayment(request.swapId)
        Behaviors.same
    }
  }

  private def awaitClaimPayment(request: SwapRequest, agreement: SwapAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted, isInitiator: Boolean): Behavior[SwapCommand] =
    nodeParams.db.payments.getIncomingPayment(invoice.paymentHash) match {
      case Some(payment) if payment.status.isInstanceOf[Received] && payment.status.asInstanceOf[Received].amount >= request.amount.sat =>
        swapCompleted(ClaimByInvoicePaid(request.swapId))
      case _ =>
        watchForPaymentReceived(watch = true)
        send(switchboard, remoteNodeId)(openingTxBroadcasted)
        Behaviors.withTimers { timers =>
          timers.startSingleTimer(swapInvoiceExpiredTimer(request.swapId), InvoiceExpired, invoice.createdAt + invoice.relativeExpiry.toSeconds - TimestampSecond.now())
          receiveSwapMessage[AwaitClaimPaymentMessages](context, "awaitClaimPayment") {
            // TODO: do we need to check that all payment parts were on our given channel? eg. payment.parts.forall(p => p.fromChannelId == channelId)
            case WrappedPaymentReceived(payment) if payment.paymentHash == invoice.paymentHash && payment.amount >= request.amount.sat =>
              swapCompleted(ClaimByInvoicePaid(request.swapId))
            case WrappedPaymentReceived(_) => Behaviors.same
            case SwapMessageReceived(coopClose: CoopClose) => claimSwapCoop(request, agreement, invoice, openingTxBroadcasted, coopClose, isInitiator)
            case SwapMessageReceived(_) => Behaviors.same
            case InvoiceExpired => waitCsv(request, agreement, invoice, openingTxBroadcasted, isInitiator)
            case CancelRequested(replyTo) =>
              replyTo ! CancelAfterOpeningCommit(request.swapId)
              Behaviors.same
            case GetStatus(replyTo) =>
              logStatus(request.swapId, context.self.toString, "awaitClaimPayment", request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
              replyTo ! AwaitClaimPayment(request.swapId)
              Behaviors.same
          }
        }
    }

  private def claimSwapCoop(request: SwapRequest, agreement: SwapAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted, coopClose: CoopClose, isInitiator: Boolean): Behavior[SwapCommand] = {
    val takerPrivkey = PrivateKey(ByteVector.fromValidHex(coopClose.privkey))
    val openingTxId = ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId))
    val claimByCoopTx = makeSwapClaimByCoopTx(request.amount.sat + agreement.premium.sat, makerPrivkey(request.swapId), takerPrivkey, invoice.paymentHash, feeRatePerKw, openingTxId, openingTxBroadcasted.scriptOut.toInt)
    val inputInfo = makeSwapOpeningInputInfo(openingTxId, openingTxBroadcasted.scriptOut.toInt, request.amount.sat + agreement.premium.sat, makerPubkey(request.swapId), takerPrivkey.publicKey, invoice.paymentHash)
    def claimByCoopConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](ClaimTxConfirmed)
    def openingConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](OpeningTxConfirmed)
    watchForPaymentReceived(watch = false)
    watchForTxConfirmation(watcher)(openingConfirmedAdapter, openingTxId, 1)
    receiveSwapMessage[ClaimSwapCoopMessages](context, "claimSwapCoop") {
      case OpeningTxConfirmed(_) =>
        watchForTxConfirmation(watcher)(claimByCoopConfirmedAdapter, claimByCoopTx.txid, nodeParams.channelConf.minDepthBlocks)
        commitClaim(wallet)(request.swapId, SwapClaimByCoopTx(inputInfo, claimByCoopTx), "claim_by_coop")
        Behaviors.same
      case ClaimTxCommitted => Behaviors.same
      case ClaimTxConfirmed(confirmedTriggered) => swapCompleted(ClaimByCoopConfirmed(request.swapId, confirmedTriggered))
      case ClaimTxFailed => waitCsv(request, agreement, invoice, openingTxBroadcasted, isInitiator)
      case ClaimTxInvalid => waitCsv(request, agreement, invoice, openingTxBroadcasted, isInitiator)
      case CancelRequested(replyTo) =>
        replyTo ! CancelAfterOpeningCommit(request.swapId)
        Behaviors.same
      case GetStatus(replyTo) =>
        logStatus(request.swapId, context.self.toString, "claimSwapCoop", request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
        replyTo ! AwaitClaimByCoopTxConfirmation(request.swapId)
        Behaviors.same
    }
  }

  private def waitCsv(request: SwapRequest, agreement: SwapAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted, isInitiator: Boolean): Behavior[SwapCommand] = {
    // TODO: are we sure the opening transaction has been committed? should we rollback locked funding outputs?
    def csvDelayConfirmedAdapter: ActorRef[WatchFundingDeeplyBuriedTriggered] = context.messageAdapter[WatchFundingDeeplyBuriedTriggered](CsvDelayConfirmed)
    watchForPaymentReceived(watch = false)
    watchForTxCsvConfirmation(watcher)(csvDelayConfirmedAdapter, ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId)), claimByCsvDelta.toInt) // watch for opening tx to be buried enough that it can be claimed by csv
    receiveSwapMessage[WaitCsvMessages](context, "waitCsv") {
      case CsvDelayConfirmed(_) => claimSwapCsv(request, agreement, invoice, openingTxBroadcasted, isInitiator)
      case CancelRequested(replyTo) =>
        replyTo ! CancelAfterOpeningCommit(request.swapId)
        Behaviors.same
      case GetStatus(replyTo) =>
        logStatus(request.swapId, context.self.toString, "waitCsv", request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
        replyTo ! AwaitCsv(request.swapId)
        Behaviors.same
    }
  }

  private def claimSwapCsv(request: SwapRequest, agreement: SwapAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted, isInitiator: Boolean): Behavior[SwapCommand] = {
    val openingTxId = ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId))
    val claimByCsvTx = makeSwapClaimByCsvTx(request.amount.sat + agreement.premium.sat, makerPrivkey(request.swapId), takerPubkey(request, agreement, isInitiator), invoice.paymentHash, feeRatePerKw, openingTxId, openingTxBroadcasted.scriptOut.toInt)
    val inputInfo = makeSwapOpeningInputInfo(openingTxId, openingTxBroadcasted.scriptOut.toInt, request.amount.sat + agreement.premium.sat, makerPubkey(request.swapId), takerPubkey(request, agreement, isInitiator), invoice.paymentHash)
    def claimByCsvConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](ClaimTxConfirmed)
    commitClaim(wallet)(request.swapId, SwapClaimByCsvTx(inputInfo, claimByCsvTx), "claim_by_csv")
    receiveSwapMessage[ClaimSwapCsvMessages](context, "claimSwapCsv") {
      case ClaimTxCommitted =>
        watchForTxConfirmation(watcher)(claimByCsvConfirmedAdapter, claimByCsvTx.txid, nodeParams.channelConf.minDepthBlocks)
        Behaviors.same
      case ClaimTxConfirmed(confirmedTriggered) => swapCompleted(ClaimByCsvConfirmed(request.swapId, confirmedTriggered))
      case ClaimTxFailed => waitCsv(request, agreement, invoice, openingTxBroadcasted, isInitiator)
      case ClaimTxInvalid => waitCsv(request, agreement, invoice, openingTxBroadcasted, isInitiator)
      case CancelRequested(replyTo) =>
        replyTo ! CancelAfterOpeningCommit(request.swapId)
        Behaviors.same
      case GetStatus(replyTo) =>
        logStatus(request.swapId, context.self.toString, "claimSwapCsv", request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
        replyTo ! AwaitClaimByCsvTxConfirmation(request.swapId)
        Behaviors.same
    }
  }

  private def swapCompleted(event: SwapEvent): Behavior[SwapCommand] = {
    context.system.eventStream ! Publish(event)
    context.log.info(s"completed swap: $event.")
    db.addResult(event)
    Behaviors.stopped
  }

  private def swapCanceled(failure: Fail): Behavior[SwapCommand] = {
    context.system.eventStream ! Publish(Canceled(failure.swapId, failure.toString))
    context.log.error(s"canceled swap: $failure")
    if (!failure.isInstanceOf[PeerCanceled]) send(switchboard, remoteNodeId)(CancelSwap(failure.swapId, failure.toString))
    Behaviors.stopped
  }

}