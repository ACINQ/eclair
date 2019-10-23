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

import akka.actor.{ActorRef, Props}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.wire.{FailureMessage, IncorrectOrUnknownPaymentDetails, UpdateAddHtlc}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, LongToBtcAmount, MilliSatoshi, NodeParams, wire}

/**
 * Created by t-bast on 18/07/2019.
 */

/**
 * Handler for a multi-part payment (see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#basic-multi-part-payments).
 * Once all the partial payments are received, a MultiPartHtlcSucceeded message is sent to the parent.
 * After a reasonable delay, if not enough partial payments have been received, a MultiPartHtlcFailed message is sent to the parent.
 * This handler assumes that the parent only sends payments for the same payment hash.
 */
class MultiPartPaymentHandler(nodeParams: NodeParams, paymentHash: ByteVector32, totalAmount: MilliSatoshi, parent: ActorRef) extends FSMDiagnosticActorLogging[MultiPartPaymentHandler.State, MultiPartPaymentHandler.Data] {

  import MultiPartPaymentHandler._

  setTimer(PaymentTimeout.toString, PaymentTimeout, nodeParams.multiPartPaymentExpiry, repeat = false)

  startWith(WAITING_FOR_HTLC, WaitingForHtlc(0 msat, Nil))

  when(WAITING_FOR_HTLC) {
    case Event(PaymentTimeout, d: WaitingForHtlc) =>
      goto(PAYMENT_FAILED) using PaymentFailed(d.paidAmount, wire.PaymentTimeout, d.parts)

    case Event(MultiPartHtlc(totalAmount2, htlc), d: WaitingForHtlc) =>
      require(htlc.paymentHash == paymentHash, s"invalid payment hash (expected $paymentHash, received ${htlc.paymentHash}")
      val pp = PartialPayment(htlc.amountMsat, htlc.channelId)
      if (totalAmount != totalAmount2) {
        log.warning(s"multi-part payment total amount mismatch: previously $totalAmount, now $totalAmount2")
        goto(PAYMENT_FAILED) using PaymentFailed(d.paidAmount, IncorrectOrUnknownPaymentDetails(totalAmount2, nodeParams.currentBlockHeight), PendingPayment(htlc.id, pp, sender) :: d.parts)
      } else if (htlc.amountMsat + d.paidAmount >= totalAmount) {
        goto(PAYMENT_SUCCEEDED) using PaymentSucceeded(htlc.amountMsat + d.paidAmount, PendingPayment(htlc.id, pp, sender) :: d.parts)
      } else {
        stay using d.copy(paidAmount = d.paidAmount + htlc.amountMsat, parts = PendingPayment(htlc.id, pp, sender) :: d.parts)
      }
  }

  when(PAYMENT_SUCCEEDED) {
    // A sender should not send us additional htlcs for that payment once we've already reached the total amount.
    // However if that happens the only rational choice is to fulfill it, because the pre-image has been released so
    // intermediate nodes will be able to fulfill that htlc anyway. This is a harmless spec violation.
    case Event(MultiPartHtlc(_, htlc), _) =>
      require(htlc.paymentHash == paymentHash, s"invalid payment hash (expected $paymentHash, received ${htlc.paymentHash}")
      log.info(s"received extraneous htlc for payment hash $paymentHash")
      parent ! ExtraHtlcReceived(paymentHash, PendingPayment(htlc.id, PartialPayment(htlc.amountMsat, htlc.channelId), sender), None)
      stay

    case Event("ok", _) => stay
  }

  when(PAYMENT_FAILED) {
    // If we receive htlcs after the multi-part payment has expired, we must fail them.
    // The LocalPaymentHandler will create a new instance of MultiPartPaymentHandler to handle a new attempt.
    case Event(MultiPartHtlc(_, htlc), PaymentFailed(_, failure, _)) =>
      require(htlc.paymentHash == paymentHash, s"invalid payment hash (expected $paymentHash, received ${htlc.paymentHash}")
      parent ! ExtraHtlcReceived(paymentHash, PendingPayment(htlc.id, PartialPayment(htlc.amountMsat, htlc.channelId), sender), Some(failure))
      stay

    case Event("ok", _) => stay
  }

  onTransition {
    case _ -> PAYMENT_SUCCEEDED =>
      cancelTimer(PaymentTimeout.toString)
      nextStateData match {
        case PaymentSucceeded(_, parts) =>
          // We expect the parent actor to send us a PoisonPill after receiving this message.
          parent ! MultiPartHtlcSucceeded(paymentHash, parts.reverse)
        case d =>
          log.error(s"unexpected payment success data ${d.getClass.getSimpleName}")
      }
    case _ -> PAYMENT_FAILED =>
      cancelTimer(PaymentTimeout.toString)
      nextStateData match {
        case PaymentFailed(_, failure, parts) =>
          // We expect the parent actor to send us a PoisonPill after receiving this message.
          parent ! MultiPartHtlcFailed(paymentHash, failure, parts.reverse)
        case d =>
          log.error(s"unexpected payment failure data ${d.getClass.getSimpleName}")
      }
  }

  initialize()

}

object MultiPartPaymentHandler {

  def props(nodeParams: NodeParams, paymentHash: ByteVector32, totalAmount: MilliSatoshi, parent: ActorRef) =
    Props(new MultiPartPaymentHandler(nodeParams, paymentHash, totalAmount, parent))

  case object PaymentTimeout

  // @formatter:off
  /** A payment that we're currently holding until we decide to fulfill or fail it. */
  case class PendingPayment(htlcId: Long, payment: PartialPayment, sender: ActorRef)
  /** An incoming partial payment. */
  case class MultiPartHtlc(totalAmount: MilliSatoshi, htlc: UpdateAddHtlc)
  /** We successfully received all parts of the payment. */
  case class MultiPartHtlcSucceeded(paymentHash: ByteVector32, parts: List[PendingPayment])
  /** We aborted the payment because of an inconsistency in the payment set or because we didn't receive the total amount in reasonable time. */
  case class MultiPartHtlcFailed(paymentHash: ByteVector32, failure: FailureMessage, parts: List[PendingPayment])
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
  sealed trait Data
  case class WaitingForHtlc(paidAmount: MilliSatoshi, parts: List[PendingPayment]) extends Data
  case class PaymentSucceeded(paidAmount: MilliSatoshi, parts: List[PendingPayment]) extends Data
  case class PaymentFailed(paidAmount: MilliSatoshi, failure: FailureMessage, parts: List[PendingPayment]) extends Data
  // @formatter:on

}