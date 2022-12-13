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
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.OnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchTxConfirmedTriggered
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.plugins.peerswap.SwapCommands._
import fr.acinq.eclair.plugins.peerswap.SwapEvents._
import fr.acinq.eclair.plugins.peerswap.SwapHelpers._
import fr.acinq.eclair.plugins.peerswap.SwapResponses._
import fr.acinq.eclair.plugins.peerswap.SwapRole.Taker
import fr.acinq.eclair.plugins.peerswap.db.SwapsDb
import fr.acinq.eclair.plugins.peerswap.transactions.SwapScripts.claimByCsvDelta
import fr.acinq.eclair.plugins.peerswap.transactions.SwapTransactions._
import fr.acinq.eclair.plugins.peerswap.wire.protocol._
import fr.acinq.eclair.{CltvExpiryDelta, NodeParams, ShortChannelId}
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object SwapTaker {
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

  def apply(remoteNodeId: PublicKey, nodeParams: NodeParams, paymentInitiator: actor.ActorRef, watcher: ActorRef[ZmqWatcher.Command], switchboard: actor.ActorRef, wallet: OnChainWallet, keyManager: SwapKeyManager, db: SwapsDb): Behavior[SwapCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial {
        case StartSwapOutSender(amount, swapId, shortChannelId) => new SwapTaker(remoteNodeId, shortChannelId, nodeParams, paymentInitiator, watcher, switchboard, wallet, keyManager, db, context)
            .createSwap(amount, swapId)
        case StartSwapInReceiver(request: SwapInRequest) =>
          ShortChannelId.fromCoordinates(request.scid) match {
            case Success(shortChannelId) => new SwapTaker(remoteNodeId, shortChannelId, nodeParams, paymentInitiator, watcher, switchboard, wallet, keyManager, db, context)
              .validateRequest(request)
            case Failure(e) =>
              context.log.error(s"received swap request with invalid shortChannelId: $request, $e")
              Behaviors.stopped
          }
        case RestoreSwap(d) =>
          ShortChannelId.fromCoordinates(d.request.scid) match {
            case Success(shortChannelId) => new SwapTaker(remoteNodeId, shortChannelId, nodeParams, paymentInitiator, watcher, switchboard, wallet, keyManager, db, context)
              .payClaimInvoice(d.request, d.agreement, d.openingTxBroadcasted, d.invoice, d.isInitiator)
            case Failure(e) =>
              context.log.error(s"Could not restore from a checkpoint with an invalid shortChannelId: $d, $e")
              db.addResult(CouldNotRestore(d.swapId, d))
              Behaviors.stopped
          }
      }
    }
}

private class SwapTaker(remoteNodeId: PublicKey, shortChannelId: ShortChannelId, nodeParams: NodeParams, paymentInitiator: actor.ActorRef, watcher: ActorRef[ZmqWatcher.Command], switchboard: actor.ActorRef, wallet: OnChainWallet, keyManager: SwapKeyManager, db: SwapsDb, implicit val context: ActorContext[SwapCommands.SwapCommand]) {
  private val protocolVersion = 3
  private val noAsset = ""
  private val feeRatePerKw: FeeratePerKw = nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
  // premium is the additional on-chain amount the Taker is asking for from the Maker over the swap amount
  private val premium = 0 // (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong.sat // TODO: how should swap receiver calculate an acceptable premium?
  // fee is the additional off-chain amount the Maker is asking for from the Taker to open the swap
  private val maxOpeningFee = (feeRatePerKw * openingTxWeight / 1000).toLong.sat // TODO: how should swap out initiator calculate an acceptable swap opening tx fee?
  private def takerPrivkey(swapId: String): PrivateKey = keyManager.openingPrivateKey(SwapKeyManager.keyPath(swapId)).privateKey
  private def takerPubkey(swapId: String): PublicKey = takerPrivkey(swapId).publicKey
  private def makerPubkey(request: SwapRequest, agreement: SwapAgreement, isInitiator: Boolean): PublicKey =
    PublicKey(ByteVector.fromValidHex(
      if (isInitiator) {
        agreement.pubkey
      } else {
        request.pubkey
      }))

  private def createSwap(amount: Satoshi, swapId: String): Behavior[SwapCommand] = {
    // a finalized scid must exist for the channel to create a swap
    val request = SwapOutRequest(protocolVersion, swapId, noAsset, NodeParams.chainFromHash(nodeParams.chainHash), shortChannelId.toString, amount.toLong, takerPubkey(swapId).toHex)
    awaitAgreement(request)
  }

  private def awaitAgreement(request: SwapOutRequest): Behavior[SwapCommand] = {
    send(switchboard, remoteNodeId)(request)
    receiveSwapMessage[AwaitAgreementMessages](context, "awaitAgreement") {
      case SwapMessageReceived(agreement: SwapOutAgreement) if agreement.protocolVersion != protocolVersion =>
        swapCanceled(WrongVersion(request.swapId, protocolVersion))
      case SwapMessageReceived(agreement: SwapOutAgreement) => validateFeeInvoice(request, agreement)
      case SwapMessageReceived(cancel: CancelSwap) => swapCanceled(PeerCanceled(request.swapId, cancel.message))
      case SwapMessageReceived(m) => swapCanceled(InvalidMessage(request.swapId, "awaitAgreement", m))
      case CancelRequested(replyTo) =>
        replyTo ! UserCanceled(request.swapId)
        swapCanceled(UserCanceled(request.swapId))
      case GetStatus(replyTo) =>
        replyTo ! SwapStatus(request.swapId, context.self.toString, "awaitAgreement", request)
        Behaviors.same
    }
  }

  private def validateFeeInvoice(request: SwapOutRequest, agreement: SwapOutAgreement): Behavior[SwapCommand] = {
    Bolt11Invoice.fromString(agreement.payreq) match {
      case Success(i) if i.amount_opt.isEmpty || i.amount_opt.get > maxOpeningFee =>
        swapCanceled(InvalidFeeInvoiceAmount(request.swapId, i.amount_opt, maxOpeningFee))
      case Success(i) if i.routingInfo.flatten.exists(hop => hop.shortChannelId != shortChannelId) =>
        swapCanceled(InvalidInvoiceChannel(request.swapId, shortChannelId, i.routingInfo, "fee"))
      case Success(i) if i.isExpired() =>
        swapCanceled(FeePaymentInvoiceExpired(request.swapId))
      case Success(feeInvoice) => payFeeInvoice(request, agreement, feeInvoice)
      case Failure(e) => swapCanceled(FeeInvoiceInvalid(request.swapId, e))
    }
  }

  private def payFeeInvoice(request: SwapOutRequest, agreement: SwapOutAgreement, feeInvoice: Bolt11Invoice): Behavior[SwapCommand] = {
    watchForPaymentSent(watch = true)
    payInvoice(nodeParams)(paymentInitiator, request.swapId, feeInvoice)
    receiveSwapMessage[PayFeeInvoiceMessages](context, "payFeeInvoice") {
      case _: WrappedPaymentPending => Behaviors.same
      case p: WrappedPaymentEvent if p.paymentHash != feeInvoice.paymentHash =>
        Behaviors.same
      case p: WrappedPaymentFailed => swapCanceled(LightningPaymentFailed(request.swapId, p.failure, "fee"))
      case _: WrappedPaymentSent =>
        // TODO: add counter party to naughty list if they do not send openingTxBroadcasted and publish a valid opening tx after we pay the fee invoice
        watchForPaymentSent(watch = false)
        Behaviors.same
      case SwapMessageReceived(openingTxBroadcasted: OpeningTxBroadcasted) => awaitOpeningTxConfirmed(request, agreement, openingTxBroadcasted, isInitiator = true)
      case SwapMessageReceived(_) => Behaviors.same
      case CancelRequested(replyTo) =>
        replyTo ! UserCanceled(request.swapId)
        sendCoopClose(UserRequestedCancel(request.swapId))
      case GetStatus(replyTo) =>
        replyTo ! SwapStatus(request.swapId, context.self.toString, "payFeeInvoice", request, Some(agreement), None, None)
        Behaviors.same
    }
  }

  private def validateRequest(request: SwapInRequest): Behavior[SwapCommand] = {
    if (request.protocolVersion != protocolVersion || request.asset != noAsset || request.network != NodeParams.chainFromHash(nodeParams.chainHash)) {
      swapCanceled(IncompatibleRequest(request.swapId, request))
    } else {
      sendAgreement(request, SwapInAgreement(protocolVersion, request.swapId, takerPubkey(request.swapId).toHex, premium.toLong))
    }
  }

  private def sendAgreement(request: SwapInRequest, agreement: SwapInAgreement): Behavior[SwapCommand] = {
    // TODO: SHOULD fail any htlc that would change the channel into a state, where the swap invoice can not be payed until the swap invoice was payed.
    send(switchboard, remoteNodeId)(agreement)
    receiveSwapMessage[SendAgreementMessages](context, "sendAgreement") {
      case SwapMessageReceived(openingTxBroadcasted: OpeningTxBroadcasted) => awaitOpeningTxConfirmed(request, agreement, openingTxBroadcasted, isInitiator = false)
      case SwapMessageReceived(cancel: CancelSwap) => swapCanceled(PeerCanceled(request.swapId, cancel.message))
      case SwapMessageReceived(m) => sendCoopClose(InvalidMessage(request.swapId, "sendAgreement", m))
      case CancelRequested(replyTo) => replyTo ! UserCanceled(request.swapId)
        sendCoopClose(UserRequestedCancel(request.swapId))
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "sendAgreement", request, Some(agreement))
        Behaviors.same
    }
  }

  private def awaitOpeningTxConfirmed(request: SwapRequest, agreement: SwapAgreement, openingTxBroadcasted: OpeningTxBroadcasted, isInitiator: Boolean): Behavior[SwapCommand] = {
    def openingConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](OpeningTxConfirmed)
    watchForTxConfirmation(watcher)(openingConfirmedAdapter, ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId)), 3) // watch for opening tx to be confirmed
    receiveSwapMessage[AwaitOpeningTxConfirmedMessages](context, "awaitOpeningTxConfirmed") {
      case OpeningTxConfirmed(opening) => validateOpeningTx(request, agreement, openingTxBroadcasted, opening.tx, isInitiator)
      case SwapMessageReceived(resend: OpeningTxBroadcasted) if resend == openingTxBroadcasted =>
        Behaviors.same
      case SwapMessageReceived(cancel: CancelSwap) => sendCoopClose(PeerCanceled(request.swapId, cancel.message))
      case SwapMessageReceived(m) => sendCoopClose(InvalidMessage(request.swapId, "awaitOpeningTxConfirmed", m))
      case CancelRequested(replyTo) =>
        replyTo ! UserCanceled(request.swapId)
        sendCoopClose(UserRequestedCancel(request.swapId))
      case GetStatus(replyTo) =>
        replyTo ! SwapStatus(request.swapId, context.self.toString, "awaitOpeningTxConfirmed", request, Some(agreement), None, Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  private def validateOpeningTx(request: SwapRequest, agreement: SwapAgreement, openingTxBroadcasted: OpeningTxBroadcasted, openingTx: Transaction, isInitiator: Boolean): Behavior[SwapCommand] =
    Bolt11Invoice.fromString(openingTxBroadcasted.payreq) match {
      case Failure(e) => sendCoopClose(SwapInvoiceInvalid(request.swapId, e))
      case Success(invoice) if invoice.amount_opt.isEmpty || invoice.amount_opt.get > request.amount.sat =>
        sendCoopClose(InvalidSwapInvoiceAmount(request.swapId, invoice.amount_opt, request.amount.sat))
      case Success(invoice) if invoice.routingInfo.flatten.exists(hop => hop.shortChannelId != shortChannelId) =>
        sendCoopClose(InvalidInvoiceChannel(request.swapId, shortChannelId, invoice.routingInfo, "swap"))
      case Success(invoice) if invoice.isExpired() =>
        sendCoopClose(SwapPaymentInvoiceExpired(request.swapId))
      case Success(invoice) if invoice.minFinalCltvExpiryDelta >= CltvExpiryDelta(claimByCsvDelta.toInt / 2) =>
        sendCoopClose(InvalidSwapInvoiceExpiryDelta(request.swapId))
      case Success(invoice) if validOpeningTx(openingTx, openingTxBroadcasted.scriptOut, (request.amount + agreement.premium).sat, makerPubkey(request, agreement, isInitiator), takerPubkey(request.swapId), invoice.paymentHash) =>
        db.add(SwapData(request, agreement, invoice, openingTxBroadcasted, Taker, isInitiator, remoteNodeId))
        payClaimInvoice(request, agreement, openingTxBroadcasted, invoice, isInitiator)
      case Success(_) => sendCoopClose(OpeningTxInvalid(request.swapId, openingTx))
    }

  private def payClaimInvoice(request: SwapRequest, agreement: SwapAgreement, openingTxBroadcasted: OpeningTxBroadcasted, invoice: Bolt11Invoice, isInitiator: Boolean): Behavior[SwapCommand] = {
    watchForPaymentSent(watch = true)
    payInvoice(nodeParams)(paymentInitiator, request.swapId, invoice)
    receiveSwapMessage[PayClaimPaymentMessages](context, "payClaimInvoice") {
      case _: WrappedPaymentPending =>
        Behaviors.same
      case p: WrappedPaymentEvent if p.paymentHash != invoice.paymentHash => Behaviors.same
      case p: WrappedPaymentFailed => sendCoopClose(LightningPaymentFailed(request.swapId, p.failure, "swap"))
      case p: WrappedPaymentSent =>
        watchForPaymentSent(watch = false)
        claimSwap(request, agreement, openingTxBroadcasted, invoice, p.paymentPreimage, isInitiator)
      case CancelRequested(replyTo) => replyTo ! UserCanceled(request.swapId)
        sendCoopClose(UserCanceled(request.swapId))
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "payClaimInvoice", request, Some(agreement), None, Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  private def claimSwap(request: SwapRequest, agreement: SwapAgreement, openingTxBroadcasted: OpeningTxBroadcasted, invoice: Bolt11Invoice, paymentPreimage: ByteVector32, isInitiator: Boolean): Behavior[SwapCommand] = {
    val inputInfo = makeSwapOpeningInputInfo(openingTxBroadcasted.txid, openingTxBroadcasted.scriptOut.toInt, (request.amount + agreement.premium).sat, makerPubkey(request, agreement, isInitiator), takerPubkey(request.swapId), invoice.paymentHash)
    val claimByInvoiceTx = makeSwapClaimByInvoiceTx((request.amount + agreement.premium).sat, makerPubkey(request, agreement, isInitiator), takerPrivkey(request.swapId), paymentPreimage, feeRatePerKw, openingTxBroadcasted.txid, openingTxBroadcasted.scriptOut.toInt)
    def claimByInvoiceConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](ClaimTxConfirmed)

    watchForTxConfirmation(watcher)(claimByInvoiceConfirmedAdapter, claimByInvoiceTx.txid, nodeParams.channelConf.minDepthBlocks)
    commitClaim(wallet)(request.swapId, SwapClaimByInvoiceTx(inputInfo, claimByInvoiceTx), "claim_by_invoice")
    receiveSwapMessage[ClaimSwapMessages](context, "claimSwap") {
      case ClaimTxCommitted => Behaviors.same
      case ClaimTxConfirmed(confirmedTriggered) => swapCompleted(ClaimByInvoiceConfirmed(request.swapId, confirmedTriggered))
      case SwapMessageReceived(m) => context.log.warn(s"received swap unhandled message while in state claimSwap: $m")
        Behaviors.same
      case ClaimTxFailed => Behaviors.same // TODO: handle when claim tx not confirmed, retry the tx?
      case ClaimTxInvalid => Behaviors.same // TODO: handle when claim tx not confirmed, retry the tx?
      case CancelRequested(replyTo) => replyTo ! CancelAfterClaimCommit(request.swapId)
        Behaviors.same
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "claimSwap", request, Some(agreement), None, Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  private def sendCoopClose(error: Error): Behavior[SwapCommand] = {
    context.log.error(s"swap ${error.swapId} sent coop close, reason: ${error.toString}")
    send(switchboard, remoteNodeId)(CoopClose(error.swapId, error.toString, takerPrivkey(error.swapId).toHex))
    swapCompleted(ClaimByCoopOffered(error.swapId, error.toString))
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