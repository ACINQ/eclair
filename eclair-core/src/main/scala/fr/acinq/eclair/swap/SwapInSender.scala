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

package fr.acinq.eclair.swap

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
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchTxConfirmedTriggered
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{DATA_NORMAL, RES_GET_CHANNEL_DATA}
import fr.acinq.eclair.payment.receive.MultiPartHandler.{CreateInvoiceActor, ReceivePayment}
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentReceived}
import fr.acinq.eclair.swap.SwapCommands._
import fr.acinq.eclair.swap.SwapEvents._
import fr.acinq.eclair.swap.SwapHelpers._
import fr.acinq.eclair.swap.SwapResponses.{Error, Fail, InternalError, InvalidMessage, PeerCanceled, SwapError, SwapInStatus, UserCanceled}
import fr.acinq.eclair.swap.SwapScripts.claimByCsvDelta
import fr.acinq.eclair.swap.SwapTransactions.{claimByInvoiceTxWeight, makeSwapClaimByCoopTx, makeSwapClaimByCsvTx, makeSwapOpeningInputInfo}
import fr.acinq.eclair.transactions.Transactions.{SwapClaimByCoopTx, SwapClaimByCsvTx}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{NodeParams, TimestampSecond}
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt

object SwapInSender {
  /*
                         SwapInSender                                  SwapInReceiver

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
                                     |            CoopClose           | [sendCoopClose]
                                     |<-------------------------------|
     (claim_by_coop) [claimSwapCoop] |                                |

                                             "Refund After Csv"
                           [waitCsv] |                                |
                                     |                                |
       (claim_by_csv) [claimSwapCsv] |                                |

  */

  def apply(nodeParams: NodeParams, watcher: ActorRef[ZmqWatcher.Command], register: actor.ActorRef, wallet: OnChainWallet): Behavior[SwapCommands.SwapCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial {
        case StartSwapInSender(amount, swapId, channelId) =>
          new SwapInSender(amount, swapId, channelId, nodeParams, watcher, register, wallet, context)
            .createSwap()
        case RestoreSwapInSender(d) =>
          new SwapInSender(d.request.amount.sat, d.request.swapId, d.channelId, nodeParams, watcher, register, wallet, context)
            .awaitOpeningTxConfirmed(d.request, d.agreement, d.invoice, d.openingTxBroadcasted)
        case AbortSwapInSender => Behaviors.stopped
      }
    }
}

private class SwapInSender(amount: Satoshi, swapId: String, channelId: ByteVector32, nodeParams: NodeParams, watcher: ActorRef[ZmqWatcher.Command], register: actor.ActorRef, wallet: OnChainWallet, implicit val context: ActorContext[SwapCommands.SwapCommand]) {
  val protocolVersion = 1
  val noAsset = ""
  implicit val timeout: Timeout = 30 seconds
  private val keyManager: SwapKeyManager = nodeParams.swapKeyManager
  private implicit val feeRatePerKw: FeeratePerKw = nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
  private val maxPremium = (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong // TODO: how should swap sender calculate an acceptable premium?

  private def makerPrivkey(): PrivateKey = keyManager.openingPrivateKey(SwapKeyManager.keyPath(swapId)).privateKey
  private def makerPubkey(): PublicKey = makerPrivkey().publicKey
  private def takerPubkey(agreement: SwapInAgreement): PublicKey = PublicKey(ByteVector.fromValidHex(agreement.pubkey))

  private def createSwap(): Behavior[SwapCommand] = {
    // a finalized scid must exist for the channel to create a swap
    queryChannelData(register, channelId)
    receiveSwapMessage[CreateSwapMessages](context, "createSwap") {
      case ChannelDataFailure(e) => swapCanceled(InternalError(swapId, s"channel data query failure: ${e.fwd}."))
      case ChannelDataResult(RES_GET_CHANNEL_DATA(channelData)) if channelData.isInstanceOf[DATA_NORMAL] =>
        val shortChannelId = channelData.asInstanceOf[DATA_NORMAL].shortIds.real.toOption.get.toString
        awaitAgreement(SwapInRequest(protocolVersion, swapId, noAsset, NodeParams.chainFromHash(nodeParams.chainHash), shortChannelId, amount.toLong, makerPubkey().toHex))
      case ChannelDataResult(channelData) => swapCanceled(InternalError(swapId, s"invalid channel: $channelData."))
      case CancelReceived(c) if c.swapId == swapId => swapCanceled(PeerCanceled(swapId))
      case CancelReceived(_) => Behaviors.same
      case StateTimeout => swapCanceled(InternalError(swapId, "timeout during createSwap"))
      case CancelRequested(replyTo) => replyTo ! UserCanceled(swapId)
        swapCanceled(UserCanceled(swapId))
      case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "createSwap", channelId, SwapInRequest(protocolVersion, swapId, noAsset, NodeParams.chainFromHash(nodeParams.chainHash), "unknown", amount.toLong, makerPubkey().toHex))
        Behaviors.same
    }
  }

  private def awaitAgreement(request: SwapInRequest): Behavior[SwapCommand] = {
    send(register, channelId)(request)

    receiveSwapMessage[AwaitAgreementMessages](context, "awaitAgreement") {
      case SwapMessageReceived(agreement: SwapInAgreement) if agreement.protocolVersion != protocolVersion =>
        swapCanceled(InternalError(swapId, s"protocol version must be $protocolVersion."))
      case SwapMessageReceived(agreement: SwapInAgreement) if agreement.premium > maxPremium =>
        swapCanceled(InternalError(swapId, "unacceptable premium requested."))
      case SwapMessageReceived(agreement: SwapInAgreement) => createOpeningTx(request, agreement)
      case CancelReceived(c) if c.swapId == swapId => swapCanceled(PeerCanceled(swapId))
      case CancelReceived(_) => Behaviors.same
      case StateTimeout => swapCanceled(InternalError(swapId, "timeout during awaitAgreement"))
      case ForwardFailureAdapter(_) => swapCanceled(InternalError(swapId, s"could not forward swap request to peer."))
      case SwapMessageReceived(m) => swapCanceled(InvalidMessage(swapId, "awaitAgreement", m))
      case CancelRequested(replyTo) => replyTo ! UserCanceled(swapId)
        swapCanceled(UserCanceled(swapId))
      case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "awaitAgreement", channelId, request)
        Behaviors.same
    }
  }

  def createOpeningTx(request: SwapInRequest, agreement: SwapInAgreement): Behavior[SwapCommand] = {
    val receivePayment = ReceivePayment(Some(toMilliSatoshi(Satoshi(request.amount))), Left("send-swap-in"))
    val createInvoice = context.spawnAnonymous(CreateInvoiceActor(nodeParams))
    createInvoice ! CreateInvoiceActor.CreateInvoice(context.messageAdapter[Bolt11Invoice](InvoiceResponse).toClassic, receivePayment)

    receiveSwapMessage[CreateOpeningTxMessages](context, "createOpeningTx") {
      case InvoiceResponse(invoice: Bolt11Invoice) => fundOpening(wallet, feeRatePerKw)(request, agreement, invoice)
        Behaviors.same
      // TODO: checkpoint PersistentSwapData for this swap to a database before committing the opening tx
      case OpeningTxFunded(invoice, fundingResponse) => commitOpening(wallet)(swapId, invoice, fundingResponse, "swap-in-sender-opening")
        Behaviors.same
      case OpeningTxCommitted(invoice, openingTxBroadcasted) => awaitOpeningTxConfirmed(request, agreement, invoice, openingTxBroadcasted)
      case OpeningTxFailed(error, None) => swapCanceled(InternalError(swapId, s"failed to fund swap open tx, error: $error"))
      case OpeningTxFailed(error, Some(r)) => rollback(wallet)(error, r.fundingTx)
        Behaviors.same
      case RollbackSuccess(error, value) => swapCanceled(InternalError(swapId, s"rollback: Success($value), error: $error"))
      case RollbackFailure(error, t) => swapCanceled(InternalError(swapId, s"rollback exception: $t, error: $error"))
      case CancelReceived(_) => Behaviors.same // ignore
      case StateTimeout =>
        // TODO: are we sure the opening transaction has not yet been committed? should we rollback locked funding outputs?
        swapCanceled(InternalError(swapId, "timeout during CreateOpeningTx"))
      case CancelRequested(replyTo) => replyTo ! SwapError(swapId, "Can not cancel swap after opening tx committed.")
        Behaviors.same // ignore
      case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "createOpeningTx", channelId, request, Some(agreement))
        Behaviors.same
    }
  }

  def awaitOpeningTxConfirmed(request: SwapInRequest, agreement: SwapInAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted): Behavior[SwapCommand] = {
    def openingConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](OpeningTxConfirmed)
    watchForTxConfirmation(watcher)(openingConfirmedAdapter, ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId)), nodeParams.channelConf.minDepthBlocks) // watch for opening tx to be confirmed

    Behaviors.withTimers { timers =>
      timers.startSingleTimer(swapInvoiceExpiredTimer(swapId), InvoiceExpired, invoice.createdAt + invoice.relativeExpiry.toSeconds - TimestampSecond.now())
      receiveSwapMessage[AwaitOpeningTxConfirmedMessages](context, "awaitOpeningTxConfirmed") {
        case OpeningTxConfirmed(_) =>
          awaitClaimPayment(request, agreement, invoice, openingTxBroadcasted)
        case SwapMessageReceived(coopClose: CoopClose) if coopClose.swapId == swapId =>
          claimSwapCoop(request, agreement, invoice, openingTxBroadcasted, coopClose)
        case SwapMessageReceived(_) => Behaviors.same
        case CancelReceived(c) if c.swapId == swapId => waitCsv(request, agreement, invoice, openingTxBroadcasted)
        case CancelReceived(_) => Behaviors.same
        case InvoiceExpired =>
          waitCsv(request, agreement, invoice, openingTxBroadcasted)
        case CancelRequested(replyTo) => replyTo ! SwapError(swapId, "Can not cancel swap after opening tx committed.")
          Behaviors.same
        case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "awaitOpeningTxConfirmed", channelId, request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
          Behaviors.same
      }
    }
  }

  def awaitClaimPayment(request: SwapInRequest, agreement: SwapInAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted): Behavior[SwapCommand] = {
    // TODO: query payment database for received payment
    watchForPayment(watch = true) // subscribe to be notified of payment events
    send(register, channelId)(openingTxBroadcasted) // send message to peer about opening tx broadcast

    Behaviors.withTimers { timers =>
      timers.startSingleTimer(swapInvoiceExpiredTimer(swapId), InvoiceExpired, invoice.createdAt + invoice.relativeExpiry.toSeconds - TimestampSecond.now())
      receiveSwapMessage[AwaitClaimPaymentMessages](context, "awaitClaimPayment") {
        case PaymentEventReceived(payment: PaymentReceived) if payment.paymentHash == invoice.paymentHash && payment.amount >= request.amount.sat && payment.parts.forall(p => p.fromChannelId == channelId) =>
          swapCompleted(ClaimByInvoicePaid(swapId, payment))
        case SwapMessageReceived(coopClose: CoopClose) if coopClose.swapId == swapId =>
          claimSwapCoop(request, agreement, invoice, openingTxBroadcasted, coopClose)
        case PaymentEventReceived(_) => Behaviors.same
        case SwapMessageReceived(_) => Behaviors.same
        case InvoiceExpired =>
          waitCsv(request, agreement, invoice, openingTxBroadcasted)
        case CancelRequested(replyTo) => replyTo ! SwapError(swapId, "Can not cancel swap after opening tx committed.")
          Behaviors.same
        case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "awaitClaimPayment", channelId, request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
          Behaviors.same
      }
    }
  }

  def claimSwapCoop(request: SwapInRequest, agreement: SwapInAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted, coopClose: CoopClose): Behavior[SwapCommand] = {
    val takerPrivkey = PrivateKey(ByteVector.fromValidHex(coopClose.privkey))
    val openingTxId = ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId))
    val claimByCoopTx = makeSwapClaimByCoopTx(request.amount.sat, makerPrivkey(), takerPrivkey, invoice.paymentHash, feeRatePerKw, openingTxId, openingTxBroadcasted.scriptOut.toInt)
    val inputInfo = makeSwapOpeningInputInfo(openingTxId, openingTxBroadcasted.scriptOut.toInt, request.amount.sat, makerPubkey(), takerPrivkey.publicKey, invoice.paymentHash)
    def claimByCoopConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](ClaimTxConfirmed)

    watchForPayment(watch = false)
    commitClaim(wallet)(swapId, SwapClaimByCoopTx(inputInfo, claimByCoopTx), "swap-in-sender-claimbycoop")

    Behaviors.withTimers { timers =>
      timers.startSingleTimer(swapInvoiceExpiredTimer(swapId), InvoiceExpired, invoice.createdAt + invoice.relativeExpiry.toSeconds - TimestampSecond.now())
      receiveSwapMessage[ClaimSwapCoopMessages](context, "claimSwapCoop") {
        case ClaimTxCommitted => watchForTxConfirmation(watcher)(claimByCoopConfirmedAdapter, claimByCoopTx.txid, nodeParams.channelConf.minDepthBlocks)
          Behaviors.same
        case ClaimTxConfirmed(confirmedTriggered) => swapCompleted(ClaimByCoopConfirmed(swapId, confirmedTriggered))
        case ClaimTxFailed(error) => context.log.error(s"swap $swapId coop claim tx failed, error: $error")
          waitCsv(request, agreement, invoice, openingTxBroadcasted)
        case ClaimTxInvalid(e) => context.log.error(s"swap $swapId coop claim tx is invalid: $e, tx: $claimByCoopTx")
          waitCsv(request, agreement, invoice, openingTxBroadcasted)
        case InvoiceExpired =>
          waitCsv(request, agreement, invoice, openingTxBroadcasted)
        case CancelRequested(replyTo) => replyTo ! SwapError(swapId, "Can not cancel swap after opening tx committed.")
          Behaviors.same
        case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "claimSwapCoop", channelId, request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
          Behaviors.same
      }
    }
  }

  def waitCsv(request: SwapInRequest, agreement: SwapInAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted): Behavior[SwapCommand] = {
    // TODO: are we sure the opening transaction has been committed? should we rollback locked funding outputs?
    def csvDelayConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](CsvDelayConfirmed)
    watchForTxConfirmation(watcher)(csvDelayConfirmedAdapter, ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId)), claimByCsvDelta.toInt) // watch for opening tx to be buried enough that it can be claimed by csv

    receiveSwapMessage[WaitCsvMessages](context, "waitCsv") {
      case CsvDelayConfirmed(_) =>
        claimSwapCsv(request, agreement, invoice, openingTxBroadcasted)
      case StateTimeout =>
        // TODO: problem with the blockchain monitor?
        Behaviors.same
      case CancelRequested(replyTo) => replyTo ! SwapError(swapId, "Can not cancel swap after opening tx committed.")
        Behaviors.same
      case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "waitCsv", channelId, request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def claimSwapCsv(request: SwapInRequest, agreement: SwapInAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted): Behavior[SwapCommand] = {
    val openingTxId = ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId))
    val claimByCsvTx = makeSwapClaimByCsvTx(request.amount.sat, makerPrivkey(), takerPubkey(agreement), invoice.paymentHash, feeRatePerKw, openingTxId, openingTxBroadcasted.scriptOut.toInt)
    val inputInfo = makeSwapOpeningInputInfo(openingTxId, openingTxBroadcasted.scriptOut.toInt, request.amount.sat, makerPubkey(), takerPubkey(agreement), invoice.paymentHash)
    def claimByCsvConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](ClaimTxConfirmed)

    watchForPayment(watch = false)
    commitClaim(wallet)(swapId, SwapClaimByCsvTx(inputInfo, claimByCsvTx), "swap-in-sender-claimByCsvTx")

    receiveSwapMessage[ClaimSwapCsvMessages](context, "claimSwapCsv") {
      case ClaimTxCommitted => watchForTxConfirmation(watcher)(claimByCsvConfirmedAdapter, claimByCsvTx.txid, nodeParams.channelConf.minDepthBlocks)
        Behaviors.same
      case ClaimTxConfirmed(confirmedTriggered) => swapCompleted(ClaimByCsvConfirmed(swapId, confirmedTriggered))
      case ClaimTxFailed(error) => context.log.error(s"swap $swapId csv claim tx failed, error: $error")
        waitCsv(request, agreement, invoice, openingTxBroadcasted)
      case ClaimTxInvalid(e) => context.log.error(s"swap $swapId csv claim tx is invalid: $e, tx: $claimByCsvTx")
        waitCsv(request, agreement, invoice, openingTxBroadcasted)
      case StateTimeout =>
        // TODO: handle when claim tx not confirmed, resubmit the tx?
        Behaviors.same
      case CancelRequested(replyTo) => replyTo ! SwapError(swapId, "Can not cancel swap after opening tx committed.")
        Behaviors.same
      case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "claimSwapCsv", channelId, request, Some(agreement), Some(invoice), Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def swapCompleted(event: SwapEvent): Behavior[SwapCommand] = {
    context.system.eventStream ! Publish(event)
    context.log.info(s"completed swap: $event.")
    Behaviors.stopped
  }

  def swapCanceled(failure: Fail): Behavior[SwapCommand] = {
    context.system.eventStream ! Publish(Canceled(swapId))
    if (!failure.isInstanceOf[PeerCanceled]) send(register, channelId)(CancelSwap(swapId, failure.toString))
    failure match {
      case e: Error => context.log.error(s"canceled swap: $e")
      case f: Fail => context.log.info(s"canceled swap: $f")
      case _ => context.log.error(s"canceled swap $swapId, reason: unknown.")
    }
    Behaviors.stopped
  }

}