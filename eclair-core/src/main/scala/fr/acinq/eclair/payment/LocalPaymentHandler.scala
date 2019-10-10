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

package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props, Status}
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Channel}
import fr.acinq.eclair.db.{IncomingPayment, IncomingPaymentStatus}
import fr.acinq.eclair.payment.PaymentLifecycle.ReceivePayment
import fr.acinq.eclair.payment.Relayer.FinalPayload
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, NodeParams, randomBytes32}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}

/**
 * Simple payment handler that generates payment requests and fulfills incoming htlcs.
 *
 * Created by PM on 17/06/2016.
 */
class LocalPaymentHandler(nodeParams: NodeParams) extends Actor with ActorLogging {

  import LocalPaymentHandler._

  implicit val ec: ExecutionContext = context.system.dispatcher
  val paymentDb = nodeParams.db.payments

  override def receive: Receive = main(Map.empty)

  def main(pendingPayments: Map[ByteVector32, (ByteVector32, ActorRef)]): Receive = {
    case ReceivePayment(amount_opt, desc, expirySeconds_opt, extraHops, fallbackAddress_opt, paymentPreimage_opt, allowMultiPart) =>
      Try {
        val paymentPreimage = paymentPreimage_opt.getOrElse(randomBytes32)
        val paymentHash = Crypto.sha256(paymentPreimage)
        val expirySeconds = expirySeconds_opt.getOrElse(nodeParams.paymentRequestExpiry.toSeconds)
        // We currently only optionally support payment secrets (to allow legacy clients to pay invoices).
        // Once we're confident most of the network has upgraded, we should switch to mandatory payment secrets.
        val features = if (allowMultiPart) {
          Some(PaymentRequest.Features(PaymentRequest.Features.BASIC_MULTI_PART_PAYMENT_OPTIONAL, PaymentRequest.Features.PAYMENT_SECRET_OPTIONAL))
        } else {
          Some(PaymentRequest.Features(PaymentRequest.Features.PAYMENT_SECRET_OPTIONAL))
        }
        val paymentRequest = PaymentRequest(nodeParams.chainHash, amount_opt, paymentHash, nodeParams.privateKey, desc, fallbackAddress_opt, expirySeconds = Some(expirySeconds), extraHops = extraHops, features = features)
        log.debug(s"generated payment request={} from amount={}", PaymentRequest.write(paymentRequest), amount_opt)
        paymentDb.addIncomingPayment(paymentRequest, paymentPreimage)
        paymentRequest
      } match {
        case Success(paymentRequest) => sender ! paymentRequest
        case Failure(exception) => sender ! Status.Failure(exception)
      }

    case p: FinalPayload => paymentDb.getIncomingPayment(p.add.paymentHash) match {
      case Some(record) => validatePayment(p, record) match {
        case Some(cmdFail) =>
          sender ! cmdFail
        case None =>
          log.info(s"received payment for paymentHash=${p.add.paymentHash} amount=${p.add.amountMsat} totalAmount=${p.payload.totalAmount}")
          pendingPayments.get(p.add.paymentHash) match {
            case Some((_, handler)) =>
              handler forward MultiPartPaymentHandler.MultiPartHtlc(p.payload.totalAmount, p.add)
            case None =>
              val handler = context.actorOf(MultiPartPaymentHandler.props(nodeParams, p.add.paymentHash, p.payload.totalAmount, self))
              handler forward MultiPartPaymentHandler.MultiPartHtlc(p.payload.totalAmount, p.add)
              context become main(pendingPayments + (p.add.paymentHash -> (record.paymentPreimage, handler)))
          }
      }
      case None =>
        sender ! CMD_FAIL_HTLC(p.add.id, Right(IncorrectOrUnknownPaymentDetails(p.payload.totalAmount, nodeParams.currentBlockHeight)), commit = true)
    }

    case MultiPartPaymentHandler.MultiPartHtlcFailed(paymentHash, failure, parts) =>
      log.warning(s"payment with paymentHash=$paymentHash paidAmount=${parts.map(_.payment.amount).sum} failed ($failure)")
      pendingPayments.get(paymentHash).foreach(h => h._2 ! PoisonPill)
      parts.foreach(p => p.sender ! CMD_FAIL_HTLC(p.htlcId, Right(failure), commit = true))
      context become main(pendingPayments - paymentHash)

    case MultiPartPaymentHandler.MultiPartHtlcSucceeded(paymentHash, parts) =>
      val received = PaymentReceived(paymentHash, parts.map(_.payment))
      log.info(s"received complete payment for paymentHash=$paymentHash amount=${received.amount}")
      pendingPayments.get(paymentHash) match {
        case Some((preimage, handler)) =>
          handler ! PoisonPill
          parts.foreach(p => p.sender ! CMD_FULFILL_HTLC(p.htlcId, preimage, commit = true))
        // NB: this case shouldn't happen unless the sender violated the spec, so it's ok that we take a slightly more
        // expensive code path by fetching the preimage from DB.
        case None => paymentDb.getIncomingPayment(paymentHash).foreach(record => {
          parts.foreach(p => p.sender ! CMD_FULFILL_HTLC(p.htlcId, record.paymentPreimage, commit = true))
        })
      }
      paymentDb.receiveIncomingPayment(paymentHash, received.amount, received.timestamp)
      context.system.eventStream.publish(received)
      context become main(pendingPayments - paymentHash)

    case GetPendingPayments => sender ! PendingPayments(pendingPayments.keySet)
  }

  private def validatePaymentStatus(payment: FinalPayload, record: IncomingPayment): Boolean = {
    if (record.status.isInstanceOf[IncomingPaymentStatus.Received]) {
      log.warning(s"ignoring incoming payment for paymentHash=${payment.add.paymentHash} which has already been paid")
      false
    } else if (record.paymentRequest.isExpired) {
      log.warning(s"received payment for expired paymentHash=${payment.add.paymentHash} amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else {
      true
    }
  }

  private def validatePaymentAmount(payment: FinalPayload, expectedAmount: MilliSatoshi): Boolean = {
    // The total amount must be equal or greater than the requested amount. A slight overpaying is permitted, however
    // it must not be greater than two times the requested amount.
    // see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#failure-messages
    if (payment.payload.totalAmount < expectedAmount) {
      log.warning(s"received payment with amount too small for paymentHash=${payment.add.paymentHash} amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else if (payment.payload.totalAmount > expectedAmount * 2) {
      log.warning(s"received payment with amount too large for paymentHash=${payment.add.paymentHash} amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else {
      true
    }
  }

  private def validatePaymentCltv(payment: FinalPayload, minExpiry: CltvExpiry): Boolean = {
    if (payment.add.cltvExpiry < minExpiry) {
      log.warning(s"received payment with expiry too small for paymentHash=${payment.add.paymentHash} amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else {
      true
    }
  }

  private def validateInvoiceFeatures(payment: FinalPayload, pr: PaymentRequest): Boolean = {
    if (payment.payload.amount < payment.payload.totalAmount && !pr.features.allowMultiPart) {
      log.warning(s"received multi-part payment but invoice doesn't support it for paymentHash=${payment.add.paymentHash} amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else if (payment.payload.amount < payment.payload.totalAmount && pr.paymentSecret != payment.payload.paymentSecret) {
      log.warning(s"received multi-part payment with invalid secret=${payment.payload.paymentSecret} for paymentHash=${payment.add.paymentHash} amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else if (payment.payload.paymentSecret.isDefined && pr.paymentSecret != payment.payload.paymentSecret) {
      log.warning(s"received payment with invalid secret=${payment.payload.paymentSecret} for paymentHash=${payment.add.paymentHash} amount=${payment.add.amountMsat} totalAmount=${payment.payload.totalAmount}")
      false
    } else {
      true
    }
  }

  private def validatePayment(payment: FinalPayload, record: IncomingPayment): Option[CMD_FAIL_HTLC] = {
    // We send the same error regardless of the failure to avoid probing attacks.
    val cmdFail = CMD_FAIL_HTLC(payment.add.id, Right(IncorrectOrUnknownPaymentDetails(payment.payload.totalAmount, nodeParams.currentBlockHeight)), commit = true)
    val paymentAmountOk = record.paymentRequest.amount.forall(a => validatePaymentAmount(payment, a))
    val paymentCltvOk = validatePaymentCltv(payment, record.paymentRequest.minFinalCltvExpiryDelta.getOrElse(Channel.MIN_CLTV_EXPIRY_DELTA).toCltvExpiry(nodeParams.currentBlockHeight))
    val paymentStatusOk = validatePaymentStatus(payment, record)
    val paymentFeaturesOk = validateInvoiceFeatures(payment, record.paymentRequest)
    if (paymentAmountOk && paymentCltvOk && paymentStatusOk && paymentFeaturesOk) None else Some(cmdFail)
  }

}

object LocalPaymentHandler {

  def props(nodeParams: NodeParams): Props = Props(new LocalPaymentHandler(nodeParams))

  // @formatter:off
  case object GetPendingPayments
  case class PendingPayments(paymentHashes: Set[ByteVector32])
  // @formatter:on

}
