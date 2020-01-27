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

import akka.actor.{ActorRef, Props}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.wire.{FailureMessage, IncorrectOrUnknownPaymentDetails, UpdateAddHtlc}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Logs, MilliSatoshi, NodeParams, wire}

import scala.collection.immutable.Queue

/**
 * Created by t-bast on 18/07/2019.
 */

/**
 * Handler for a multi-part payment (see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#basic-multi-part-payments).
 * Once all the partial payments are received, a MultiPartHtlcSucceeded message is sent to the parent.
 * After a reasonable delay, if not enough partial payments have been received, a MultiPartHtlcFailed message is sent to the parent.
 * This handler assumes that the parent only sends payments for the same payment hash.
 */
class MultiPartPaymentFSM(nodeParams: NodeParams, paymentHash: ByteVector32, totalAmount: MilliSatoshi, parent: ActorRef) extends FSMDiagnosticActorLogging[MultiPartPaymentFSM.State, MultiPartPaymentFSM.Data] {

  import MultiPartPaymentFSM._

  setTimer(PaymentTimeout.toString, PaymentTimeout, nodeParams.multiPartPaymentExpiry, repeat = false)

  startWith(WAITING_FOR_HTLC, WaitingForHtlc(Queue.empty))

  when(WAITING_FOR_HTLC) {
    case Event(PaymentTimeout, d: WaitingForHtlc) =>
      log.warning(s"multi-part payment timed out (received ${d.paidAmount} expected $totalAmount)")
      goto(PAYMENT_FAILED) using PaymentFailed(wire.PaymentTimeout, d.parts)

    case Event(MultiPartHtlc(totalAmount2, htlc), d: WaitingForHtlc) =>
      require(htlc.paymentHash == paymentHash, s"invalid payment hash (expected $paymentHash, received ${htlc.paymentHash}")
      val pp = PendingPayment(htlc.id, PartialPayment(htlc.amountMsat, htlc.channelId))
      val updatedParts = d.parts :+ pp
      if (totalAmount != totalAmount2) {
        log.warning(s"multi-part payment total amount mismatch: previously $totalAmount, now $totalAmount2")
        goto(PAYMENT_FAILED) using PaymentFailed(IncorrectOrUnknownPaymentDetails(totalAmount2, nodeParams.currentBlockHeight), updatedParts)
      } else if (d.paidAmount + htlc.amountMsat >= totalAmount) {
        goto(PAYMENT_SUCCEEDED) using PaymentSucceeded(updatedParts)
      } else {
        stay using d.copy(parts = updatedParts)
      }
  }

  when(PAYMENT_SUCCEEDED) {
    // A sender should not send us additional htlcs for that payment once we've already reached the total amount.
    // However if that happens the only rational choice is to fulfill it, because the pre-image has been released so
    // intermediate nodes will be able to fulfill that htlc anyway. This is a harmless spec violation.
    case Event(MultiPartHtlc(_, htlc), _) =>
      require(htlc.paymentHash == paymentHash, s"invalid payment hash (expected $paymentHash, received ${htlc.paymentHash}")
      log.info(s"received extraneous htlc amountMsat=${htlc.amountMsat}")
      parent ! ExtraHtlcReceived(paymentHash, PendingPayment(htlc.id, PartialPayment(htlc.amountMsat, htlc.channelId)), None)
      stay
  }

  when(PAYMENT_FAILED) {
    // If we receive htlcs after the multi-part payment has expired, we must fail them.
    // The LocalPaymentHandler will create a new instance of MultiPartPaymentHandler to handle a new attempt.
    case Event(MultiPartHtlc(_, htlc), PaymentFailed(failure, _)) =>
      require(htlc.paymentHash == paymentHash, s"invalid payment hash (expected $paymentHash, received ${htlc.paymentHash}")
      log.info(s"received extraneous htlc for payment hash $paymentHash")
      parent ! ExtraHtlcReceived(paymentHash, PendingPayment(htlc.id, PartialPayment(htlc.amountMsat, htlc.channelId)), Some(failure))
      stay
  }

  whenUnhandled {
    case Event("ok", _) => stay
  }

  onTransition {
    case WAITING_FOR_HTLC -> WAITING_FOR_HTLC => () // don't do anything if we stay in that state
    case WAITING_FOR_HTLC -> _ => cancelTimer(PaymentTimeout.toString)
  }

  onTransition {
    case _ -> PAYMENT_SUCCEEDED =>
      nextStateData match {
        case PaymentSucceeded(parts) =>
          // We expect the parent actor to send us a PoisonPill after receiving this message.
          parent ! MultiPartHtlcSucceeded(paymentHash, parts)
        case d =>
          log.error(s"unexpected payment success data ${d.getClass.getSimpleName}")
      }
    case _ -> PAYMENT_FAILED =>
      nextStateData match {
        case PaymentFailed(failure, parts) =>
          // We expect the parent actor to send us a PoisonPill after receiving this message.
          parent ! MultiPartHtlcFailed(paymentHash, failure, parts)
        case d =>
          log.error(s"unexpected payment failure data ${d.getClass.getSimpleName}")
      }
  }

  initialize()

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), paymentHash_opt = Some(paymentHash))
  }
}

object MultiPartPaymentFSM {

  def props(nodeParams: NodeParams, paymentHash: ByteVector32, totalAmount: MilliSatoshi, parent: ActorRef) =
    Props(new MultiPartPaymentFSM(nodeParams, paymentHash, totalAmount, parent))

  case object PaymentTimeout

  // @formatter:off
  /** A payment that we're currently holding until we decide to fulfill or fail it. */
  case class PendingPayment(htlcId: Long, payment: PartialPayment)
  /** An incoming partial payment. */
  case class MultiPartHtlc(totalAmount: MilliSatoshi, htlc: UpdateAddHtlc)
  /** We successfully received all parts of the payment. */
  case class MultiPartHtlcSucceeded(paymentHash: ByteVector32, parts: Queue[PendingPayment])
  /** We aborted the payment because of an inconsistency in the payment set or because we didn't receive the total amount in reasonable time. */
  case class MultiPartHtlcFailed(paymentHash: ByteVector32, failure: FailureMessage, parts: Queue[PendingPayment])
  /** We received an extraneous payment after we reached a final state (succeeded or failed). */
  case class ExtraHtlcReceived(paymentHash: ByteVector32, payment: PendingPayment, failure: Option[FailureMessage])
  // @formatter:on

  // @formatter:off
  sealed trait State
  case object WAITING_FOR_HTLC extends State
  case object PAYMENT_SUCCEEDED extends State
  case object PAYMENT_FAILED extends State
  // @formatter:on

  // @formatter:off
  sealed trait Data {
    def parts: Queue[PendingPayment]
    lazy val paidAmount = parts.map(_.payment.amount).sum
  }
  case class WaitingForHtlc(parts: Queue[PendingPayment]) extends Data
  case class PaymentSucceeded(parts: Queue[PendingPayment]) extends Data
  case class PaymentFailed(failure: FailureMessage, parts: Queue[PendingPayment]) extends Data
  // @formatter:on

}