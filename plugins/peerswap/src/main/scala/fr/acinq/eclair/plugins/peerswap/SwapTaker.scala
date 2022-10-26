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
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchOutputSpentTriggered, WatchTxConfirmedTriggered}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentEvent, PaymentFailed, PaymentSent}
import fr.acinq.eclair.plugins.peerswap.SwapCommands._
import fr.acinq.eclair.plugins.peerswap.SwapEvents._
import fr.acinq.eclair.plugins.peerswap.SwapHelpers._
import fr.acinq.eclair.plugins.peerswap.SwapResponses.{CreateFailed, Error, Fail, InternalError, InvalidMessage, PeerCanceled, SwapError, SwapStatus, UserCanceled}
import fr.acinq.eclair.plugins.peerswap.SwapRole.Taker
import fr.acinq.eclair.plugins.peerswap.db.SwapsDb
import fr.acinq.eclair.plugins.peerswap.transactions.SwapTransactions._
import fr.acinq.eclair.plugins.peerswap.wire.protocol._
import fr.acinq.eclair.{NodeParams, ShortChannelId, ToMilliSatoshiConversion}
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

  def apply(nodeParams: NodeParams, paymentInitiator: actor.ActorRef, watcher: ActorRef[ZmqWatcher.Command], register: actor.ActorRef, wallet: OnChainWallet, keyManager: SwapKeyManager, db: SwapsDb): Behavior[SwapCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial {
        case StartSwapOutSender(amount, swapId, shortChannelId) =>
          new SwapTaker(shortChannelId, nodeParams, paymentInitiator, watcher, register, wallet, keyManager, db, context)
            .createSwap(amount, swapId)
        case StartSwapInReceiver(request: SwapInRequest) =>
          ShortChannelId.fromCoordinates(request.scid) match {
            case Success(shortChannelId) => new SwapTaker(shortChannelId, nodeParams, paymentInitiator, watcher, register, wallet, keyManager, db, context)
              .validateRequest(request)
            case Failure(e) => context.log.error(s"received swap request with invalid shortChannelId: $request, $e")
              Behaviors.stopped
          }
        case RestoreSwap(d) =>
          ShortChannelId.fromCoordinates(d.request.scid) match {
            case Success(shortChannelId) => new SwapTaker(shortChannelId, nodeParams, paymentInitiator, watcher, register, wallet, keyManager, db, context)
              .awaitOpeningTxConfirmed(d.request, d.agreement, d.openingTxBroadcasted, d.isInitiator)
            case Failure(e) => context.log.error(s"could not restore swap receiver with invalid shortChannelId: $d, $e")
              Behaviors.stopped
          }
        case AbortSwap => Behaviors.stopped
      }
    }
}

private class SwapTaker(shortChannelId: ShortChannelId, nodeParams: NodeParams, paymentInitiator: actor.ActorRef, watcher: ActorRef[ZmqWatcher.Command], register: actor.ActorRef, wallet: OnChainWallet, keyManager: SwapKeyManager, db: SwapsDb, implicit val context: ActorContext[SwapCommands.SwapCommand]) {
  val protocolVersion = 2
  val noAsset = ""
  implicit val timeout: Timeout = 30 seconds

  private val feeRatePerKw: FeeratePerKw = nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
  private val premium = (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong.sat // TODO: how should swap receiver calculate an acceptable premium?
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
    // TODO: why do we not get a ForwardFailure message when channel is not connected?
    sendShortId(register, shortChannelId)(request)

    receiveSwapMessage[AwaitAgreementMessages](context, "awaitAgreement") {
      case SwapMessageReceived(agreement: SwapOutAgreement) if agreement.protocolVersion != protocolVersion =>
        swapCanceled(InternalError(request.swapId, s"protocol version must be $protocolVersion."))
      case SwapMessageReceived(agreement: SwapOutAgreement) => validateFeeInvoice(request, agreement)
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

  def validateFeeInvoice(request: SwapOutRequest, agreement: SwapOutAgreement): Behavior[SwapCommand] = {
    Bolt11Invoice.fromString(agreement.payreq) match {
      case Success(i) if i.amount_opt.isDefined && i.amount_opt.get > maxOpeningFee =>
        swapCanceled(CreateFailed(request.swapId, s"invalid invoice: Invoice amount ${i.amount_opt} > estimated opening tx fee $maxOpeningFee"))
      case Success(i) if i.routingInfo.flatten.exists(hop => hop.shortChannelId != shortChannelId) =>
        swapCanceled(CreateFailed(request.swapId, s"invalid invoice: Channel hop other than $shortChannelId found in invoice hints ${i.routingInfo}"))
      case Success(i) if i.isExpired() =>
        swapCanceled(CreateFailed(request.swapId, s"invalid invoice: Invoice is expired."))
      case Success(i) if i.amount_opt.isEmpty || i.amount_opt.get > maxOpeningFee =>
        swapCanceled(CreateFailed(request.swapId, s"invalid invoice: unacceptable opening fee requested."))
      case Success(feeInvoice) => payFeeInvoice(request, agreement, feeInvoice)
      case Failure(e) => swapCanceled(CreateFailed(request.swapId, s"invalid invoice: Could not parse payreq: $e"))
    }
  }

  def payFeeInvoice(request: SwapOutRequest, agreement: SwapOutAgreement, feeInvoice: Bolt11Invoice): Behavior[SwapCommand] = {
    watchForPayment(watch = true) // subscribe to payment event notifications
    payInvoice(nodeParams)(paymentInitiator, request.swapId, feeInvoice)

    receiveSwapMessage[PayFeeInvoiceMessages](context, "payOpeningTxFeeInvoice") {
      // TODO: add counter party to naughty list if they do not send openingTxBroadcasted and publish a valid opening tx after we pay the fee invoice
      case PaymentEventReceived(p: PaymentEvent) if p.paymentHash != feeInvoice.paymentHash => Behaviors.same
      case PaymentEventReceived(_: PaymentSent) => Behaviors.same
      case PaymentEventReceived(p: PaymentFailed) => swapCanceled(CreateFailed(request.swapId, s"Lightning payment failed: $p"))
      case PaymentEventReceived(p: PaymentEvent) => swapCanceled(CreateFailed(request.swapId, s"Lightning payment failed, invalid PaymentEvent received: $p."))
      case SwapMessageReceived(openingTxBroadcasted: OpeningTxBroadcasted) => awaitOpeningTxConfirmed(request, agreement, openingTxBroadcasted, isInitiator = true)
      case SwapMessageReceived(cancel: CancelSwap) => swapCanceled(PeerCanceled(request.swapId, cancel.message))
      case SwapMessageReceived(m) => swapCanceled(CreateFailed(request.swapId, s"Invalid message received during payOpeningTxFeeInvoice: $m"))
      case StateTimeout => swapCanceled(InternalError(request.swapId, "timeout during payFeeInvoice"))
      case CancelRequested(replyTo) => replyTo ! UserCanceled(request.swapId)
        swapCanceled(CreateFailed(request.swapId, s"Cancel requested by user while validating opening tx."))
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "payFeeInvoice", request, Some(agreement), None, None)
        Behaviors.same
    }
  }

  def validateRequest(request: SwapInRequest): Behavior[SwapCommand] = {
    // fail if swap request is invalid, otherwise respond with agreement
    if (request.protocolVersion != protocolVersion || request.asset != noAsset || request.network != NodeParams.chainFromHash(nodeParams.chainHash)) {
      swapCanceled(InternalError(request.swapId, s"incompatible request: $request."))
    } else {
      sendAgreement(request, SwapInAgreement(protocolVersion, request.swapId, takerPubkey(request.swapId).toHex, premium.toLong))
    }
  }

  private def sendAgreement(request: SwapInRequest, agreement: SwapInAgreement): Behavior[SwapCommand] = {
    // TODO: SHOULD fail any htlc that would change the channel into a state, where the swap invoice can not be payed until the swap invoice was payed.
    sendShortId(register, shortChannelId)(agreement)

    receiveSwapMessage[SendAgreementMessages](context, "sendAgreement") {
      case SwapMessageReceived(openingTxBroadcasted: OpeningTxBroadcasted) => awaitOpeningTxConfirmed(request, agreement, openingTxBroadcasted, isInitiator = false)
      case SwapMessageReceived(cancel: CancelSwap) => swapCanceled(PeerCanceled(request.swapId, cancel.message))
      case SwapMessageReceived(m) => sendCoopClose(request, s"Invalid message received during sendAgreement: $m")
      case StateTimeout => swapCanceled(InternalError(request.swapId, "timeout during sendAgreement"))
      case ForwardShortIdFailureAdapter(_) => swapCanceled(InternalError(request.swapId, s"could not forward swap agreement to peer."))
      case CancelRequested(replyTo) => replyTo ! UserCanceled(request.swapId)
        sendCoopClose(request, s"Cancel requested by user after sending agreement.")
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "sendAgreement", request, Some(agreement))
        Behaviors.same
    }
  }

  def awaitOpeningTxConfirmed(request: SwapRequest, agreement: SwapAgreement, openingTxBroadcasted: OpeningTxBroadcasted, isInitiator: Boolean): Behavior[SwapCommand] = {
    def openingConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](OpeningTxConfirmed)
    watchForTxConfirmation(watcher)(openingConfirmedAdapter, ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId)), 3) // watch for opening tx to be confirmed

    receiveSwapMessage[AwaitOpeningTxConfirmedMessages](context, "awaitOpeningTxConfirmed") {
      case OpeningTxConfirmed(opening) => validateOpeningTx(request, agreement, openingTxBroadcasted, opening.tx, isInitiator)
      case SwapMessageReceived(cancel: CancelSwap) => swapCanceled(PeerCanceled(request.swapId, cancel.message))
      case SwapMessageReceived(m) => sendCoopClose(request, s"Invalid message received during awaitOpeningTxConfirmed: $m")
      case InvoiceExpired => sendCoopClose(request, "Timeout waiting for opening tx to confirm.")
      case CancelRequested(replyTo) => replyTo ! UserCanceled(request.swapId)
        sendCoopClose(request, s"Cancel requested by user while waiting for opening tx to confirm.")
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "awaitOpeningTxConfirmed", request, Some(agreement), None, Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def validateOpeningTx(request: SwapRequest, agreement: SwapAgreement, openingTxBroadcasted: OpeningTxBroadcasted, openingTx: Transaction, isInitiator: Boolean): Behavior[SwapCommand] = {
    Bolt11Invoice.fromString(openingTxBroadcasted.payreq) match {
      case Success(i) if i.amount_opt.isDefined && i.amount_opt.get > request.amount.sat.toMilliSatoshi =>
        context.self ! InvalidInvoice(s"Invoice amount ${i.amount_opt} > requested amount ${request.amount}")
      case Success(i) if i.routingInfo.flatten.exists(hop => hop.shortChannelId != shortChannelId) =>
        context.self ! InvalidInvoice(s"Channel hop other than $shortChannelId found in invoice hints ${i.routingInfo}")
      case Success(i) if i.isExpired() =>
        context.self ! InvalidInvoice(s"Invoice is expired.")
      case Success(i) => context.self ! ValidInvoice(i)
      case Failure(e) => context.self ! InvalidInvoice(s"Could not parse payreq: $e")
    }

    receiveSwapMessage[ValidateTxMessages](context, "validateOpeningTx") {
      case ValidInvoice(invoice) if validOpeningTx(openingTx, openingTxBroadcasted.scriptOut, (request.amount + agreement.premium).sat, makerPubkey(request, agreement, isInitiator), takerPubkey(request.swapId), invoice.paymentHash) =>
        db.add(SwapData(request, agreement, invoice, openingTxBroadcasted, Taker, isInitiator))
        payClaimInvoice(request, agreement, openingTxBroadcasted, invoice, openingTx, isInitiator)
      case ValidInvoice(_) => sendCoopClose(request,s"Invalid opening tx: $openingTx", Some(openingTxBroadcasted))
      case InvalidInvoice(reason) => sendCoopClose(request, reason, Some(openingTxBroadcasted))
      case SwapMessageReceived(m) => sendCoopClose(request, s"Invalid message received during validateOpeningTx: $m", Some(openingTxBroadcasted))
      case CancelRequested(replyTo) => replyTo ! UserCanceled(request.swapId)
        sendCoopClose(request, s"Cancel requested by user while validating opening tx.", Some(openingTxBroadcasted))
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "validateOpeningTx", request, Some(agreement), None, Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def payClaimInvoice(request: SwapRequest, agreement: SwapAgreement, openingTxBroadcasted: OpeningTxBroadcasted, invoice: Bolt11Invoice, openingTx: Transaction, isInitiator: Boolean): Behavior[SwapCommand] = {
    watchForPayment(watch = true) // subscribe to payment event notifications
    payInvoice(nodeParams)(paymentInitiator, request.swapId, invoice)

    receiveSwapMessage[PayClaimInvoiceMessages](context, "payClaimInvoice") {
      case PaymentEventReceived(p: PaymentEvent) if p.paymentHash != invoice.paymentHash => Behaviors.same
      case PaymentEventReceived(p: PaymentSent) => claimSwap(request, agreement, openingTxBroadcasted, invoice, p.paymentPreimage, openingTx, isInitiator)
      case PaymentEventReceived(p: PaymentFailed) => sendCoopClose(request, s"Lightning payment failed: $p", Some(openingTxBroadcasted))
      case PaymentEventReceived(p: PaymentEvent) => sendCoopClose(request, s"Lightning payment failed (invalid PaymentEvent received: $p).", Some(openingTxBroadcasted))
      case CancelRequested(replyTo) => replyTo ! UserCanceled(request.swapId)
        sendCoopClose(request, s"Cancel requested by user while paying claim invoice.", Some(openingTxBroadcasted))
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "payClaimInvoice", request, Some(agreement), None, Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def claimSwap(request: SwapRequest, agreement: SwapAgreement, openingTxBroadcasted: OpeningTxBroadcasted, invoice: Bolt11Invoice, paymentPreimage: ByteVector32, openingTx: Transaction, isInitiator: Boolean): Behavior[SwapCommand] = {
    val inputInfo = makeSwapOpeningInputInfo(openingTx.txid, openingTxBroadcasted.scriptOut.toInt, (request.amount + agreement.premium).sat, makerPubkey(request, agreement, isInitiator), takerPubkey(request.swapId), invoice.paymentHash)
    val claimByInvoiceTx = makeSwapClaimByInvoiceTx((request.amount + agreement.premium).sat, makerPubkey(request, agreement, isInitiator), takerPrivkey(request.swapId), paymentPreimage, feeRatePerKw, openingTx.txid, openingTxBroadcasted.scriptOut.toInt)
    def claimByInvoiceConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](ClaimTxConfirmed)

    watchForTxConfirmation(watcher)(claimByInvoiceConfirmedAdapter, claimByInvoiceTx.txid, nodeParams.channelConf.minDepthBlocks)
    watchForPayment(watch = false) // unsubscribe from payment event notifications
    commitClaim(wallet)(request.swapId, SwapClaimByInvoiceTx(inputInfo, claimByInvoiceTx), "swap-in-receiver-claimbyinvoice")

    receiveSwapMessage[ClaimSwapMessages](context, "claimSwap") {
      case ClaimTxCommitted => Behaviors.same
      case ClaimTxConfirmed(confirmedTriggered) => swapCompleted(ClaimByInvoiceConfirmed(request.swapId, confirmedTriggered))
      case SwapMessageReceived(m) => context.log.warn(s"received swap unhandled message while in state claimSwap: $m")
        Behaviors.same
      case ClaimTxFailed(error) => context.log.error(s"swap $request.swapId claim by invoice tx failed, error: $error")
        Behaviors.same // TODO: handle when claim tx not confirmed, retry the tx?
      case ClaimTxInvalid(e) => context.log.error(s"swap $request.swapId claim by invoice tx is invalid: $e, tx: $claimByInvoiceTx")
        Behaviors.same // TODO: handle when claim tx not confirmed, retry the tx?
      case StateTimeout => Behaviors.same // TODO: handle when claim tx not confirmed, retry or RBF the tx? can SwapInSender pin this tx with a low fee?
      case CancelRequested(replyTo) => replyTo ! SwapError(request.swapId, "Can not cancel swap after claim tx committed.")
        Behaviors.same // ignore
      case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "claimSwap", request, Some(agreement), None, Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def sendCoopClose(request: SwapRequest, reason: String, openingTxBroadcasted_opt: Option[OpeningTxBroadcasted] = None): Behavior[SwapCommand] = {
    context.log.error(s"swap ${request.swapId} sent coop close, reason: $reason")
    sendShortId(register, shortChannelId)(CoopClose(request.swapId, reason, takerPrivkey(request.swapId).toHex))
    def openingTxSpentAdapter: ActorRef[WatchOutputSpentTriggered] = context.messageAdapter[WatchOutputSpentTriggered](OpeningTxOutputSpent)
    openingTxBroadcasted_opt match {
      case Some(m) => watchForOutputSpent(watcher)(openingTxSpentAdapter, ByteVector32(ByteVector.fromValidHex(m.txId)), m.scriptOut.toInt)
        receiveSwapMessage[SendCoopCloseMessages](context, "sendCoopClose") {
          case OpeningTxOutputSpent(_) => swapCompleted(ClaimByCoopOffered(request.swapId, reason))
          case ForwardShortIdFailureAdapter(_) => swapCanceled(InternalError(request.swapId, s"could not forward swap coop close to peer."))
          // TODO: set long enough timeout delay to wait for counterparty to sweep opening tx
          case CancelRequested(replyTo) => replyTo ! UserCanceled(request.swapId)
            swapCompleted(ClaimByCoopOffered(request.swapId, reason + "+ user canceled while waiting for opening tx to be swept by counter party."))
          case GetStatus(replyTo) => replyTo ! SwapStatus(request.swapId, context.self.toString, "sendCoopClose", request, None, None, openingTxBroadcasted_opt)
            Behaviors.same
        }
      case None => swapCompleted(ClaimByCoopOffered(request.swapId, reason))
    }
  }

  def swapCompleted(event: SwapEvent): Behavior[SwapCommand] = {
    context.system.eventStream ! Publish(event)
    context.log.info(s"completed swap: $event.")
    Behaviors.stopped
  }

  def swapCanceled(failure: Fail): Behavior[SwapCommand] = {
    val swapEvent = Canceled(failure.swapId, failure.toString)
    context.system.eventStream ! Publish(swapEvent)
    failure match {
      case e: Error => context.log.error(s"canceled swap: $e")
      case s: CreateFailed => sendShortId(register, shortChannelId)(CancelSwap(s.swapId, s.toString))
        context.log.info(s"canceled swap: $s")
      case s: Fail => context.log.info(s"canceled swap: $s")
      case _ => context.log.error(s"canceled swap ${failure.swapId}, reason: unknown.")
    }
    Behaviors.stopped
  }

}