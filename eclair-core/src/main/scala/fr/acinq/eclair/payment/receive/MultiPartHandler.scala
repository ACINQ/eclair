/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.payment.receive

import akka.actor.Actor.Receive
import akka.actor.{ActorContext, ActorRef, PoisonPill, Status}
import akka.event.{DiagnosticLoggingAdapter, LoggingAdapter}
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Channel}
import fr.acinq.eclair.db.{IncomingPayment, IncomingPaymentStatus, IncomingPaymentsDb}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.relay.CommandBuffer
import fr.acinq.eclair.payment.{IncomingPacket, PaymentReceived, PaymentRequest}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, Features, Logs, MilliSatoshi, NodeParams, randomBytes32}

import scala.util.{Failure, Success, Try}

/**
 * Simple payment handler that generates payment requests and fulfills incoming htlcs.
 *
 * Created by PM on 17/06/2016.
 */
class MultiPartHandler(nodeParams: NodeParams, db: IncomingPaymentsDb, commandBuffer: ActorRef) extends ReceiveHandler {

  import MultiPartHandler._

  // NB: this is safe because this handler will be called from within an actor
  private var pendingPayments: Map[ByteVector32, (ByteVector32, ActorRef)] = Map.empty

  /**
   * Can be overridden for a more fine-grained control of whether or not to handle this payment hash.
   * If the call returns false, then the pattern matching will fail and the payload will be passed to other handlers.
   */
  def doHandle(paymentHash: ByteVector32): Boolean = true

  /**
   * Can be overridden to do custom processing on successfully received payments.
   */
  def onSuccess(paymentReceived: PaymentReceived)(implicit log: LoggingAdapter): Unit = ()

  override def handle(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Receive = {
    case ReceivePayment(amount_opt, desc, expirySeconds_opt, extraHops, fallbackAddress_opt, paymentPreimage_opt) =>
      Try {
        val paymentPreimage = paymentPreimage_opt.getOrElse(randomBytes32)
        val paymentHash = Crypto.sha256(paymentPreimage)
        val expirySeconds = expirySeconds_opt.getOrElse(nodeParams.paymentRequestExpiry.toSeconds)
        // We currently only optionally support payment secrets (to allow legacy clients to pay invoices).
        // Once we're confident most of the network has upgraded, we should switch to mandatory payment secrets.
        val features = {
          val f1 = Seq(Features.PaymentSecret.optional, Features.VariableLengthOnion.optional)
          val allowMultiPart = Features.hasFeature(nodeParams.features, Features.BasicMultiPartPayment)
          val f2 = if (allowMultiPart) Seq(Features.BasicMultiPartPayment.optional) else Nil
          val f3 = if (nodeParams.enableTrampolinePayment) Seq(Features.TrampolinePayment.optional) else Nil
          Some(PaymentRequest.Features(f1 ++ f2 ++ f3: _*))
        }
        val paymentRequest = PaymentRequest(nodeParams.chainHash, amount_opt, paymentHash, nodeParams.privateKey, desc, fallbackAddress_opt, expirySeconds = Some(expirySeconds), extraHops = extraHops, features = features)
        log.debug(s"generated payment request={} from amount={}", PaymentRequest.write(paymentRequest), amount_opt)
        db.addIncomingPayment(paymentRequest, paymentPreimage)
        paymentRequest
      } match {
        case Success(paymentRequest) => ctx.sender ! paymentRequest
        case Failure(exception) => ctx.sender ! Status.Failure(exception)
      }

    case p: IncomingPacket.FinalPacket if doHandle(p.add.paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(p.add.paymentHash))) {
        db.getIncomingPayment(p.add.paymentHash) match {
          case Some(record) => validatePayment(p, record, nodeParams.currentBlockHeight) match {
            case Some(cmdFail) =>
              commandBuffer ! CommandBuffer.CommandSend(p.add.channelId, cmdFail)
            case None =>
              log.info(s"received payment for amount=${p.add.amountMsat} totalAmount=${p.payload.totalAmount}")
              pendingPayments.get(p.add.paymentHash) match {
                case Some((_, handler)) =>
                  handler ! MultiPartPaymentFSM.MultiPartHtlc(p.payload.totalAmount, p.add)
                case None =>
                  val handler = ctx.actorOf(MultiPartPaymentFSM.props(nodeParams, p.add.paymentHash, p.payload.totalAmount, ctx.self))
                  handler ! MultiPartPaymentFSM.MultiPartHtlc(p.payload.totalAmount, p.add)
                  pendingPayments = pendingPayments + (p.add.paymentHash -> (record.paymentPreimage, handler))
              }
          }
          case None =>
            val cmdFail = CMD_FAIL_HTLC(p.add.id, Right(IncorrectOrUnknownPaymentDetails(p.payload.totalAmount, nodeParams.currentBlockHeight)), commit = true)
            commandBuffer ! CommandBuffer.CommandSend(p.add.channelId, cmdFail)
        }
      }

    case MultiPartPaymentFSM.MultiPartHtlcFailed(paymentHash, failure, parts) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        log.warning(s"payment with paidAmount=${parts.map(_.payment.amount).sum} failed ($failure)")
        pendingPayments.get(paymentHash).foreach { case (_, handler: ActorRef) => handler ! PoisonPill }
        parts.foreach(p => commandBuffer ! CommandBuffer.CommandSend(p.payment.fromChannelId, CMD_FAIL_HTLC(p.htlcId, Right(failure), commit = true)))
        pendingPayments = pendingPayments - paymentHash
      }

    case MultiPartPaymentFSM.MultiPartHtlcSucceeded(paymentHash, parts) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        val received = PaymentReceived(paymentHash, parts.map(_.payment))
        log.info(s"received complete payment for amount=${received.amount}")
        // The first thing we do is store the payment. This allows us to reconcile pending HTLCs after a restart.
        db.receiveIncomingPayment(paymentHash, received.amount, received.timestamp)
        pendingPayments.get(paymentHash).foreach {
          case (preimage: ByteVector32, handler: ActorRef) =>
            handler ! PoisonPill
            parts.foreach(p => commandBuffer ! CommandBuffer.CommandSend(p.payment.fromChannelId, CMD_FULFILL_HTLC(p.htlcId, preimage, commit = true)))
        }
        ctx.system.eventStream.publish(received)
        pendingPayments = pendingPayments - paymentHash
        onSuccess(received)
      }

    case MultiPartPaymentFSM.ExtraHtlcReceived(paymentHash, p, failure) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        failure match {
          case Some(failure) => commandBuffer ! CommandBuffer.CommandSend(p.payment.fromChannelId, CMD_FAIL_HTLC(p.htlcId, Right(failure), commit = true))
          // NB: this case shouldn't happen unless the sender violated the spec, so it's ok that we take a slightly more
          // expensive code path by fetching the preimage from DB.
          case None => db.getIncomingPayment(paymentHash).foreach(record => {
            commandBuffer ! CommandBuffer.CommandSend(p.payment.fromChannelId, CMD_FULFILL_HTLC(p.htlcId, record.paymentPreimage, commit = true))
            db.receiveIncomingPayment(paymentHash, p.payment.amount, p.payment.timestamp)
            ctx.system.eventStream.publish(PaymentReceived(paymentHash, p.payment :: Nil))
          })
        }
      }

    case GetPendingPayments => ctx.sender ! PendingPayments(pendingPayments.keySet)

    case ack: CommandBuffer.CommandAck => commandBuffer forward ack

    case "ok" => // ignoring responses from channels
  }

}

object MultiPartHandler {

  // @formatter:off
  case object GetPendingPayments
  case class PendingPayments(paymentHashes: Set[ByteVector32])
  // @formatter:on

  /**
   * Use this message to create a Bolt 11 invoice to receive a payment.
   *
   * @param amount_opt        amount to receive in milli-satoshis.
   * @param description       payment description.
   * @param expirySeconds_opt number of seconds before the invoice expires (relative to the invoice creation time).
   * @param extraHops         routing hints to help the payer.
   * @param fallbackAddress   fallback Bitcoin address.
   * @param paymentPreimage   payment preimage.
   */
  case class ReceivePayment(amount_opt: Option[MilliSatoshi],
                            description: String,
                            expirySeconds_opt: Option[Long] = None,
                            extraHops: List[List[ExtraHop]] = Nil,
                            fallbackAddress: Option[String] = None,
                            paymentPreimage: Option[ByteVector32] = None)

  private def validatePaymentStatus(payment: IncomingPacket.FinalPacket, record: IncomingPayment)(implicit log: LoggingAdapter): Boolean = {
    if (record.status.isInstanceOf[IncomingPaymentStatus.Received]) {
      log.warning(s"ignoring incoming payment for which has already been paid")
      false
    } else if (record.paymentRequest.isExpired) {
      log.warning(s"received payment for expired amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else {
      true
    }
  }

  private def validatePaymentAmount(payment: IncomingPacket.FinalPacket, expectedAmount: MilliSatoshi)(implicit log: LoggingAdapter): Boolean = {
    // The total amount must be equal or greater than the requested amount. A slight overpaying is permitted, however
    // it must not be greater than two times the requested amount.
    // see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#failure-messages
    if (payment.payload.totalAmount < expectedAmount) {
      log.warning(s"received payment with amount too small for amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else if (payment.payload.totalAmount > expectedAmount * 2) {
      log.warning(s"received payment with amount too large for amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else {
      true
    }
  }

  private def validatePaymentCltv(payment: IncomingPacket.FinalPacket, minExpiry: CltvExpiry)(implicit log: LoggingAdapter): Boolean = {
    if (payment.add.cltvExpiry < minExpiry) {
      log.warning(s"received payment with expiry too small for amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else {
      true
    }
  }

  private def validateInvoiceFeatures(payment: IncomingPacket.FinalPacket, pr: PaymentRequest)(implicit log: LoggingAdapter): Boolean = {
    if (payment.payload.amount < payment.payload.totalAmount && !pr.features.allowMultiPart) {
      log.warning(s"received multi-part payment but invoice doesn't support it for amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else if (payment.payload.amount < payment.payload.totalAmount && pr.paymentSecret != payment.payload.paymentSecret) {
      log.warning(s"received multi-part payment with invalid secret=${payment.payload.paymentSecret} for amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else if (payment.payload.paymentSecret.isDefined && pr.paymentSecret != payment.payload.paymentSecret) {
      log.warning(s"received payment with invalid secret=${payment.payload.paymentSecret} for amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else {
      true
    }
  }

  private def validatePayment(payment: IncomingPacket.FinalPacket, record: IncomingPayment, currentBlockHeight: Long)(implicit log: LoggingAdapter): Option[CMD_FAIL_HTLC] = {
    // We send the same error regardless of the failure to avoid probing attacks.
    val cmdFail = CMD_FAIL_HTLC(payment.add.id, Right(IncorrectOrUnknownPaymentDetails(payment.payload.totalAmount, currentBlockHeight)), commit = true)
    val paymentAmountOk = record.paymentRequest.amount.forall(a => validatePaymentAmount(payment, a))
    val paymentCltvOk = validatePaymentCltv(payment, record.paymentRequest.minFinalCltvExpiryDelta.getOrElse(Channel.MIN_CLTV_EXPIRY_DELTA).toCltvExpiry(currentBlockHeight))
    val paymentStatusOk = validatePaymentStatus(payment, record)
    val paymentFeaturesOk = validateInvoiceFeatures(payment, record.paymentRequest)
    if (paymentAmountOk && paymentCltvOk && paymentStatusOk && paymentFeaturesOk) None else Some(cmdFail)
  }
}
