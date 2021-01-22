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
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, RES_SUCCESS}
import fr.acinq.eclair.db._
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.payment.PaymentRequest.{ExtraHop, PaymentRequestFeatures}
import fr.acinq.eclair.payment.{IncomingPacket, PaymentReceived, PaymentRequest}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Features, Logs, MilliSatoshi, NodeParams, randomBytes32}

import scala.util.{Failure, Success, Try}

/**
 * Simple payment handler that generates payment requests and fulfills incoming htlcs.
 *
 * Created by PM on 17/06/2016.
 */
class MultiPartHandler(nodeParams: NodeParams, register: ActorRef, db: IncomingPaymentsDb) extends ReceiveHandler {

  import MultiPartHandler._

  // NB: this is safe because this handler will be called from within an actor
  private var pendingPayments: Map[ByteVector32, (ByteVector32, ActorRef)] = Map.empty

  /**
   * Can be overridden for a more fine-grained control of whether or not to handle this payment hash.
   * If the call returns false, then the pattern matching will fail and the payload will be passed to other handlers.
   */
  def doHandle(paymentHash: ByteVector32): Boolean = true

  /** Can be overridden to do custom post-processing on successfully received payments. */
  def postFulfill(paymentReceived: PaymentReceived)(implicit log: LoggingAdapter): Unit = ()

  override def handle(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Receive = {
    case ReceivePayment(amount_opt, desc, expirySeconds_opt, extraHops, fallbackAddress_opt, paymentPreimage_opt, paymentType) =>
      Try {
        val paymentPreimage = paymentPreimage_opt.getOrElse(randomBytes32)
        val paymentHash = Crypto.sha256(paymentPreimage)
        val expirySeconds = expirySeconds_opt.getOrElse(nodeParams.paymentRequestExpiry.toSeconds)
        // We currently only optionally support payment secrets (to allow legacy clients to pay invoices).
        // Once we're confident most of the network has upgraded, we should switch to mandatory payment secrets.
        val features = {
          val f1 = Seq(Features.PaymentSecret.optional, Features.VariableLengthOnion.optional)
          val allowMultiPart = nodeParams.features.hasFeature(Features.BasicMultiPartPayment)
          val f2 = if (allowMultiPart) Seq(Features.BasicMultiPartPayment.optional) else Nil
          val f3 = if (nodeParams.enableTrampolinePayment) Seq(Features.TrampolinePayment.optional) else Nil
          Some(PaymentRequest.PaymentRequestFeatures(f1 ++ f2 ++ f3: _*))
        }
        val paymentRequest = PaymentRequest(nodeParams.chainHash, amount_opt, paymentHash, nodeParams.privateKey, desc, nodeParams.minFinalExpiryDelta, fallbackAddress_opt, expirySeconds = Some(expirySeconds), extraHops = extraHops, features = features)
        log.debug("generated payment request={} from amount={}", PaymentRequest.write(paymentRequest), amount_opt)
        db.addIncomingPayment(paymentRequest, paymentPreimage, paymentType)
        paymentRequest
      } match {
        case Success(paymentRequest) => ctx.sender ! paymentRequest
        case Failure(exception) => ctx.sender ! Status.Failure(exception)
      }

    case p: IncomingPacket.FinalPacket if doHandle(p.add.paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(p.add.paymentHash))) {
        db.getIncomingPayment(p.add.paymentHash) match {
          case Some(record) => validatePayment(nodeParams, p, record) match {
            case Some(cmdFail) =>
              Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, Tags.FailureType(cmdFail)).increment()
              PendingRelayDb.safeSend(register, nodeParams.db.pendingRelay, p.add.channelId, cmdFail)
            case None =>
              log.info("received payment for amount={} totalAmount={}", p.add.amountMsat, p.payload.totalAmount)
              pendingPayments.get(p.add.paymentHash) match {
                case Some((_, handler)) =>
                  handler ! MultiPartPaymentFSM.HtlcPart(p.payload.totalAmount, p.add)
                case None =>
                  val handler = ctx.actorOf(MultiPartPaymentFSM.props(nodeParams, p.add.paymentHash, p.payload.totalAmount, ctx.self))
                  handler ! MultiPartPaymentFSM.HtlcPart(p.payload.totalAmount, p.add)
                  pendingPayments = pendingPayments + (p.add.paymentHash -> (record.paymentPreimage, handler))
              }
          }
          case None => p.payload.paymentPreimage match {
            case Some(paymentPreimage) if nodeParams.features.hasFeature(Features.KeySend) =>
              val amount = Some(p.payload.totalAmount)
              val paymentHash = Crypto.sha256(paymentPreimage)
              val desc = "Donation"
              val features = if (nodeParams.features.hasFeature(Features.BasicMultiPartPayment)) {
                PaymentRequestFeatures(Features.BasicMultiPartPayment.optional, Features.PaymentSecret.optional, Features.VariableLengthOnion.optional)
              } else {
                PaymentRequestFeatures(Features.PaymentSecret.optional, Features.VariableLengthOnion.optional)
              }

              // Insert a fake invoice and then restart the incoming payment handler
              val paymentRequest = PaymentRequest(nodeParams.chainHash, amount, paymentHash, nodeParams.privateKey, desc, nodeParams.minFinalExpiryDelta, features = Some(features))
              log.debug("generated fake payment request={} from amount={} (KeySend)", PaymentRequest.write(paymentRequest), amount)
              db.addIncomingPayment(paymentRequest, paymentPreimage, paymentType = PaymentType.KeySend)
              ctx.self ! p
            case _ =>
              Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, "InvoiceNotFound").increment()
              val cmdFail = CMD_FAIL_HTLC(p.add.id, Right(IncorrectOrUnknownPaymentDetails(p.payload.totalAmount, nodeParams.currentBlockHeight)), commit = true)
              PendingRelayDb.safeSend(register, nodeParams.db.pendingRelay, p.add.channelId, cmdFail)
          }
        }
      }

    case MultiPartPaymentFSM.MultiPartPaymentFailed(paymentHash, failure, parts) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        Metrics.PaymentFailed.withTag(Tags.Direction, Tags.Directions.Received).withTag(Tags.Failure, failure.getClass.getSimpleName).increment()
        log.warning("payment with paidAmount={} failed ({})", parts.map(_.amount).sum, failure)
        pendingPayments.get(paymentHash).foreach { case (_, handler: ActorRef) => handler ! PoisonPill }
        parts.collect {
          case p: MultiPartPaymentFSM.HtlcPart => PendingRelayDb.safeSend(register, nodeParams.db.pendingRelay, p.htlc.channelId, CMD_FAIL_HTLC(p.htlc.id, Right(failure), commit = true))
        }
        pendingPayments = pendingPayments - paymentHash
      }

    case s@MultiPartPaymentFSM.MultiPartPaymentSucceeded(paymentHash, parts) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        log.info("received complete payment for amount={}", parts.map(_.amount).sum)
        pendingPayments.get(paymentHash).foreach {
          case (preimage: ByteVector32, handler: ActorRef) =>
            handler ! PoisonPill
            ctx.self ! DoFulfill(preimage, s)
        }
        pendingPayments = pendingPayments - paymentHash
      }

    case MultiPartPaymentFSM.ExtraPaymentReceived(paymentHash, p, failure) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        failure match {
          case Some(failure) => p match {
            case p: MultiPartPaymentFSM.HtlcPart => PendingRelayDb.safeSend(register, nodeParams.db.pendingRelay, p.htlc.channelId, CMD_FAIL_HTLC(p.htlc.id, Right(failure), commit = true))
          }
          case None => p match {
            // NB: this case shouldn't happen unless the sender violated the spec, so it's ok that we take a slightly more
            // expensive code path by fetching the preimage from DB.
            case p: MultiPartPaymentFSM.HtlcPart => db.getIncomingPayment(paymentHash).foreach(record => {
              PendingRelayDb.safeSend(register, nodeParams.db.pendingRelay, p.htlc.channelId, CMD_FULFILL_HTLC(p.htlc.id, record.paymentPreimage, commit = true))
              val received = PaymentReceived(paymentHash, PaymentReceived.PartialPayment(p.amount, p.htlc.channelId) :: Nil)
              db.receiveIncomingPayment(paymentHash, p.amount, received.timestamp)
              ctx.system.eventStream.publish(received)
            })
          }
        }
      }

    case DoFulfill(preimage, MultiPartPaymentFSM.MultiPartPaymentSucceeded(paymentHash, parts)) if doHandle(paymentHash) =>
      Logs.withMdc(log)(Logs.mdc(paymentHash_opt = Some(paymentHash))) {
        log.info("fulfilling payment for amount={}", parts.map(_.amount).sum)
        val received = PaymentReceived(paymentHash, parts.map {
          case p: MultiPartPaymentFSM.HtlcPart => PaymentReceived.PartialPayment(p.amount, p.htlc.channelId)
        })
        db.receiveIncomingPayment(paymentHash, received.amount, received.timestamp)
        parts.collect {
          case p: MultiPartPaymentFSM.HtlcPart => PendingRelayDb.safeSend(register, nodeParams.db.pendingRelay, p.htlc.channelId, CMD_FULFILL_HTLC(p.htlc.id, preimage, commit = true))
        }
        postFulfill(received)
        ctx.system.eventStream.publish(received)
      }

    case GetPendingPayments => ctx.sender ! PendingPayments(pendingPayments.keySet)

    case _: RES_SUCCESS[_] => // ignoring responses from channels
  }

}

object MultiPartHandler {

  // @formatter:off
  case class DoFulfill(preimage: ByteVector32, success: MultiPartPaymentFSM.MultiPartPaymentSucceeded)

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
                            paymentPreimage: Option[ByteVector32] = None,
                            paymentType: String = PaymentType.Standard)

  private def validatePaymentStatus(payment: IncomingPacket.FinalPacket, record: IncomingPayment)(implicit log: LoggingAdapter): Boolean = {
    if (record.status.isInstanceOf[IncomingPaymentStatus.Received]) {
      log.warning("ignoring incoming payment for which has already been paid")
      false
    } else if (record.paymentRequest.isExpired) {
      log.warning("received payment for expired amount={} totalAmount={}", payment.add.amountMsat, payment.payload.totalAmount)
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
      log.warning("received payment with amount too small for amount={} totalAmount={}", payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else if (payment.payload.totalAmount > expectedAmount * 2) {
      log.warning("received payment with amount too large for amount={} totalAmount={}", payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else {
      true
    }
  }

  private def validatePaymentCltv(nodeParams: NodeParams, payment: IncomingPacket.FinalPacket, record: IncomingPayment)(implicit log: LoggingAdapter): Boolean = {
    val minExpiry = record.paymentRequest.minFinalCltvExpiryDelta.getOrElse(nodeParams.minFinalExpiryDelta).toCltvExpiry(nodeParams.currentBlockHeight)
    if (payment.add.cltvExpiry < minExpiry) {
      log.warning("received payment with expiry too small for amount={} totalAmount={}", payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else {
      true
    }
  }

  private def validateInvoiceFeatures(payment: IncomingPacket.FinalPacket, pr: PaymentRequest)(implicit log: LoggingAdapter): Boolean = {
    if (payment.payload.amount < payment.payload.totalAmount && !pr.features.allowMultiPart) {
      log.warning("received multi-part payment but invoice doesn't support it for amount={} totalAmount={}", payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else if (payment.payload.amount < payment.payload.totalAmount && pr.paymentSecret != payment.payload.paymentSecret) {
      log.warning("received multi-part payment with invalid secret={} for amount={} totalAmount={}", payment.payload.paymentSecret, payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else if (payment.payload.paymentSecret.isDefined && pr.paymentSecret != payment.payload.paymentSecret) {
      log.warning("received payment with invalid secret={} for amount={} totalAmount={}", payment.payload.paymentSecret, payment.add.amountMsat, payment.payload.totalAmount)
      false
    } else {
      true
    }
  }

  private def validatePayment(nodeParams: NodeParams, payment: IncomingPacket.FinalPacket, record: IncomingPayment)(implicit log: LoggingAdapter): Option[CMD_FAIL_HTLC] = {
    // We send the same error regardless of the failure to avoid probing attacks.
    val cmdFail = CMD_FAIL_HTLC(payment.add.id, Right(IncorrectOrUnknownPaymentDetails(payment.payload.totalAmount, nodeParams.currentBlockHeight)), commit = true)
    val paymentAmountOk = record.paymentRequest.amount.forall(a => validatePaymentAmount(payment, a))
    val paymentCltvOk = validatePaymentCltv(nodeParams, payment, record)
    val paymentStatusOk = validatePaymentStatus(payment, record)
    val paymentFeaturesOk = validateInvoiceFeatures(payment, record.paymentRequest)
    if (paymentAmountOk && paymentCltvOk && paymentStatusOk && paymentFeaturesOk) None else Some(cmdFail)
  }
}
