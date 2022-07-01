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
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.OnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchOutputSpentTriggered, WatchTxConfirmedTriggered}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentEvent, PaymentFailed, PaymentSent}
import fr.acinq.eclair.swap.SwapCommands._
import fr.acinq.eclair.swap.SwapEvents._
import fr.acinq.eclair.swap.SwapHelpers._
import fr.acinq.eclair.swap.SwapResponses.{Error, Fail, InternalError, PeerCanceled, SwapError, SwapInStatus, UserCanceled}
import fr.acinq.eclair.swap.SwapTransactions.{claimByInvoiceTxWeight, makeSwapClaimByInvoiceTx, makeSwapOpeningInputInfo, validOpeningTx}
import fr.acinq.eclair.transactions.Transactions.SwapClaimByCoopTx
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{NodeParams, ShortChannelId, ToMilliSatoshiConversion}
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

object SwapInReceiver {
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

  def apply(request: SwapInRequest, nodeParams: NodeParams, paymentInitiator: actor.ActorRef, watcher: ActorRef[ZmqWatcher.Command], register: actor.ActorRef, wallet: OnChainWallet): Behavior[SwapCommand] =
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial {
        case StartSwapInReceiver =>
          ShortChannelId.fromCoordinates(request.scid) match {
            case Success(shortChannelId) => new SwapInReceiver(request, shortChannelId, nodeParams, paymentInitiator, watcher, register, wallet, context)
              .validateRequest()
            case Failure(e) => context.log.error(s"received swap request with invalid shortChannelId: $request, $e")
              Behaviors.stopped
          }
        case RestoreSwapInReceiver(d) =>
          ShortChannelId.fromCoordinates(d.request.scid) match {
            case Success(shortChannelId) => new SwapInReceiver(d.request, shortChannelId, nodeParams, paymentInitiator, watcher, register, wallet, context)
              .awaitOpeningTxConfirmed(d.agreement, d.openingTxBroadcasted)
            case Failure(e) => context.log.error(s"could not restore swap request with invalid shortChannelId: $request, $e")
              Behaviors.stopped
          }
        case AbortSwapInReceiver => Behaviors.stopped
      }
    }
}

private class SwapInReceiver(request: SwapInRequest, shortChannelId: ShortChannelId, nodeParams: NodeParams, paymentInitiator: actor.ActorRef, watcher: ActorRef[ZmqWatcher.Command], register: actor.ActorRef, wallet: OnChainWallet, implicit val context: ActorContext[SwapCommands.SwapCommand]) {
  val protocolVersion = 1
  val noAsset = ""
  implicit val timeout: Timeout = 30 seconds

  private val keyManager: SwapKeyManager = nodeParams.swapKeyManager
  private val feeRatePerKw: FeeratePerKw = nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
  private val premium = (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong // TODO: how should swap receiver calculate an acceptable premium?
  private val swapId: String = request.swapId
  private val takerPrivkey: PrivateKey = keyManager.openingPrivateKey(SwapKeyManager.keyPath(swapId)).privateKey
  private val takerPubkey: PublicKey = takerPrivkey.publicKey
  private val makerPubkey: PublicKey = PublicKey(ByteVector.fromValidHex(request.pubkey))

  private def validateRequest(): Behavior[SwapCommand] = {
    // fail if swap request is invalid, otherwise respond with agreement
    if (request.protocolVersion != protocolVersion || request.asset != noAsset || request.network != NodeParams.chainFromHash(nodeParams.chainHash)) {
      swapCanceled(InternalError(swapId, s"swap $swapId incompatible request: $request."))
    } else {
      sendAgreement(SwapInAgreement(protocolVersion, swapId, takerPubkey.toHex, premium))
    }
  }

  private def sendAgreement(agreement: SwapInAgreement): Behavior[SwapCommand] = {
    // TODO: SHOULD fail any htlc that would change the channel into a state, where the swap invoice can not be payed until the swap invoice was payed.
    sendShortId(register, shortChannelId)(agreement)

    receiveSwapMessage[SendAgreementMessages](context, "sendAgreement") {
      case SwapMessageReceived(openingTxBroadcasted: OpeningTxBroadcasted) if agreement.protocolVersion == request.protocolVersion && agreement.swapId == swapId =>
        awaitOpeningTxConfirmed(agreement, openingTxBroadcasted)
      case CancelReceived(c) if c.swapId == swapId => swapCanceled(PeerCanceled(swapId))
      case CancelReceived(_) => Behaviors.same
      case StateTimeout => swapCanceled(InternalError(swapId, "timeout during sendAgreement"))
      case ForwardShortIdFailureAdapter(_) => swapCanceled(InternalError(swapId, s"could not forward swap agreement to peer."))
      case SwapMessageReceived(m) => sendCoopClose(s"Invalid message received during sendAgreement: $m")
      case CancelRequested(replyTo) => replyTo ! UserCanceled(swapId)
        sendCoopClose(s"Cancel requested by user after sending agreement.")
      case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "sendAgreement", ByteVector32.Zeroes, request, Some(agreement))
        Behaviors.same
    }
  }

  def awaitOpeningTxConfirmed(agreement: SwapInAgreement, openingTxBroadcasted: OpeningTxBroadcasted): Behavior[SwapCommand] = {
    def openingConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](OpeningTxConfirmed)
    watchForTxConfirmation(watcher)(openingConfirmedAdapter, ByteVector32(ByteVector.fromValidHex(openingTxBroadcasted.txId)), 3) // watch for opening tx to be confirmed

    receiveSwapMessage[AwaitOpeningTxConfirmedMessages](context, "awaitOpeningTxConfirmed") {
      case OpeningTxConfirmed(opening) => validateOpeningTx(agreement, openingTxBroadcasted, opening.tx)
      case SwapMessageReceived(m) => sendCoopClose(s"Invalid message received during awaitOpeningTxConfirmed: $m")
      case CancelReceived(c) if c.swapId == swapId => swapCanceled(PeerCanceled(swapId))
      case CancelReceived(_) => Behaviors.same
      case InvoiceExpired => sendCoopClose("Timeout waiting for opening tx to confirm.")
      case CancelRequested(replyTo) => replyTo ! UserCanceled(swapId)
        sendCoopClose(s"Cancel requested by user while waiting for opening tx to confirm.")
      case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "awaitOpeningTxConfirmed", ByteVector32.Zeroes, request, Some(agreement), None, Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def validateOpeningTx(agreement: SwapInAgreement, openingTxBroadcasted: OpeningTxBroadcasted, openingTx: Transaction): Behavior[SwapCommand] = {
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
      case ValidInvoice(invoice) if validOpeningTx(openingTx, openingTxBroadcasted.scriptOut, (request.amount + agreement.premium).sat, makerPubkey, takerPubkey, invoice.paymentHash) =>
        payClaimInvoice(agreement, openingTxBroadcasted, invoice, openingTx)
      case ValidInvoice(_) => sendCoopClose(s"Invalid opening tx: $openingTx", Some(openingTxBroadcasted))
      case InvalidInvoice(reason) => sendCoopClose(reason, Some(openingTxBroadcasted))
      case SwapMessageReceived(m) => sendCoopClose(s"Invalid message received during validateOpeningTx: $m", Some(openingTxBroadcasted))
      case CancelRequested(replyTo) => replyTo ! UserCanceled(swapId)
        sendCoopClose(s"Cancel requested by user while validating opening tx.", Some(openingTxBroadcasted))
      case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "validateOpeningTx", ByteVector32.Zeroes, request, Some(agreement), None, Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def payClaimInvoice(agreement: SwapInAgreement, openingTxBroadcasted: OpeningTxBroadcasted, invoice: Bolt11Invoice, openingTx: Transaction): Behavior[SwapCommand] = {
    watchForPayment(watch = true) // subscribe to payment event notifications
    payInvoice(nodeParams)(paymentInitiator, swapId, invoice)

    receiveSwapMessage[PayClaimInvoiceMessages](context, "payClaimInvoice") {
      case PaymentEventReceived(p: PaymentEvent) if p.paymentHash != invoice.paymentHash => Behaviors.same
      case PaymentEventReceived(p: PaymentSent) => claimSwap(agreement, openingTxBroadcasted, invoice, p.paymentPreimage, openingTx)
      case PaymentEventReceived(p: PaymentFailed) => sendCoopClose(s"Lightning payment failed: $p", Some(openingTxBroadcasted))
      case PaymentEventReceived(p: PaymentEvent) => sendCoopClose(s"Lightning payment failed (invalid PaymentEvent received: $p).", Some(openingTxBroadcasted))
      case CancelRequested(replyTo) => replyTo ! UserCanceled(swapId)
        sendCoopClose(s"Cancel requested by user while paying claim invoice.", Some(openingTxBroadcasted))
      case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "payClaimInvoice", ByteVector32.Zeroes, request, Some(agreement), None, Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def claimSwap(agreement: SwapInAgreement, openingTxBroadcasted: OpeningTxBroadcasted, invoice: Bolt11Invoice, paymentPreimage: ByteVector32, openingTx: Transaction): Behavior[SwapCommand] = {
    val inputInfo = makeSwapOpeningInputInfo(openingTx.hash, openingTxBroadcasted.scriptOut.toInt, (request.amount + agreement.premium).sat, makerPubkey, takerPubkey, invoice.paymentHash)
    val claimByInvoiceTx = makeSwapClaimByInvoiceTx((request.amount + agreement.premium).sat, makerPubkey, takerPrivkey, paymentPreimage, feeRatePerKw, openingTx.hash, openingTxBroadcasted.scriptOut.toInt)
    def claimByInvoiceConfirmedAdapter: ActorRef[WatchTxConfirmedTriggered] = context.messageAdapter[WatchTxConfirmedTriggered](ClaimTxConfirmed)

    watchForTxConfirmation(watcher)(claimByInvoiceConfirmedAdapter, claimByInvoiceTx.txid, nodeParams.channelConf.minDepthBlocks)
    watchForPayment(watch = false) // unsubscribe from payment event notifications
    commitClaim(wallet)(swapId, SwapClaimByCoopTx(inputInfo, claimByInvoiceTx), "swap-in-receiver-claimbyinvoice")

    receiveSwapMessage[ClaimSwapMessages](context, "claimSwap") {
      case ClaimTxCommitted => Behaviors.same
      case ClaimTxConfirmed(confirmedTriggered) => swapCompleted(ClaimByInvoiceConfirmed(swapId, confirmedTriggered))
      case SwapMessageReceived(m) => context.log.warn(s"received swap unhandled message while in state claimSwap: $m")
        Behaviors.same
      case ClaimTxFailed(error) => context.log.error(s"swap $swapId claim by invoice tx failed, error: $error")
        Behaviors.same // TODO: handle when claim tx not confirmed, retry the tx?
      case ClaimTxInvalid(e) => context.log.error(s"swap $swapId claim by invoice tx is invalid: $e, tx: $claimByInvoiceTx")
        Behaviors.same // TODO: handle when claim tx not confirmed, retry the tx?
      case StateTimeout => Behaviors.same // TODO: handle when claim tx not confirmed, retry or RBF the tx? can SwapInSender pin this tx with a low fee?
      case CancelRequested(replyTo) => replyTo ! SwapError(swapId, "Can not cancel swap after claim tx committed.")
        Behaviors.same // ignore
      case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "claimSwap", ByteVector32.Zeroes, request, Some(agreement), None, Some(openingTxBroadcasted))
        Behaviors.same
    }
  }

  def sendCoopClose(reason: String, openingTxBroadcasted_opt: Option[OpeningTxBroadcasted] = None): Behavior[SwapCommand] = {
    context.log.error(s"swap $swapId sent coop close, reason: $reason")
    sendShortId(register, shortChannelId)(CoopClose(swapId, reason, takerPrivkey.toHex))
    def openingTxSpentAdapter: ActorRef[WatchOutputSpentTriggered] = context.messageAdapter[WatchOutputSpentTriggered](OpeningTxOutputSpent)
    openingTxBroadcasted_opt match {
      case Some(m) => watchForOutputSpent(watcher)(openingTxSpentAdapter, ByteVector32(ByteVector.fromValidHex(m.txId)), m.scriptOut.toInt)
        receiveSwapMessage[SendCoopCloseMessages](context, "sendCoopClose") {
          case OpeningTxOutputSpent(_) => swapCompleted(ClaimByCoopOffered(swapId, reason))
          case ForwardShortIdFailureAdapter(_) => swapCanceled(InternalError(swapId, s"could not forward swap coop close to peer."))
          // TODO: set long enough timeout delay to wait for counterparty to sweep opening tx
          case CancelRequested(replyTo) => replyTo ! UserCanceled(swapId)
            swapCompleted(ClaimByCoopOffered(swapId, reason + "+ user canceled while waiting for opening tx to be swept by counter party."))
          case GetStatus(replyTo) => replyTo ! SwapInStatus(swapId, context.self.toString, "sendCoopClose", ByteVector32.Zeroes, request, None, None, openingTxBroadcasted_opt)
            Behaviors.same
        }
      case None => swapCompleted(ClaimByCoopOffered(swapId, reason))
    }
  }

  def swapCompleted(event: SwapEvent): Behavior[SwapCommand] = {
    context.system.eventStream ! Publish(event)
    context.log.info(s"completed swap $swapId: $event.")
    Behaviors.stopped
  }

  def swapCanceled(failure: Fail): Behavior[SwapCommand] = {
    context.system.eventStream ! Publish(Canceled(swapId))
    failure match {
      case e: Error => context.log.error(s"canceled swap: $e")
      case s: Fail => context.log.info(s"canceled swap: $s")
      case _ => context.log.error(s"canceled swap $swapId, reason: unknown.")
    }
    Behaviors.stopped
  }

}