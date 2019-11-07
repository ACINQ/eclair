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

import akka.actor.{ActorRef, FSM, Props}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.wire.{FailureMessage, IncorrectOrUnknownPaymentDetails, UpdateAddHtlc}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, MilliSatoshi, NodeParams, wire}

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

  startWith(WAITING_FOR_HTLC, WaitingForHtlc(Nil))

  when(WAITING_FOR_HTLC) {
    case Event(PaymentTimeout, d: WaitingForHtlc) =>
      val failure = wire.PaymentTimeout
      stop(FSM.Failure(failure), PaymentFailed(wire.PaymentTimeout, d.parts))

    case Event(mph@MultiPartHtlc(_, htlc), d: WaitingForHtlc) =>
      require(htlc.paymentHash == paymentHash, s"invalid payment hash (expected $paymentHash, received ${htlc.paymentHash}")
      val paidAmount1 = d.paidAmount + htlc.amountMsat
      val part = PendingPayment(htlc.id, PartialPayment(htlc.amountMsat, htlc.channelId), sender)
      val parts1 = part +: d.parts
      if (totalAmount != mph.totalAmount) {
        log.warning(s"multi-part payment total amount mismatch: previously $totalAmount, now ${mph.totalAmount}")
        val failure = IncorrectOrUnknownPaymentDetails(mph.totalAmount, nodeParams.currentBlockHeight)
        stop(FSM.Failure(failure), PaymentFailed(failure, parts1))
      } else if (paidAmount1 >= totalAmount) {
        stop(FSM.Normal, PaymentSucceeded(parts1))
      } else {
        stay using d.copy(parts1)
      }
  }

  whenUnhandled {
    case Event("ok", _) => stay
  }

  onTransition {
    case WAITING_FOR_HTLC -> WAITING_FOR_HTLC => () // don't do anything if we stay in that state
    case WAITING_FOR_HTLC -> _ => cancelTimer(PaymentTimeout.toString)
  }

  onTermination {
    // NB: order matters!
    case StopEvent(FSM.Normal, _, PaymentSucceeded(parts)) =>
      parent ! MultiPartHtlcSucceeded(paymentHash, parts.reverse)
    case StopEvent(FSM.Normal, _, d) =>
      log.error(s"unexpected payment success data ${d.getClass.getSimpleName}")
    case StopEvent(FSM.Failure(_), _, PaymentFailed(failure, parts)) =>
      parent ! MultiPartHtlcFailed(paymentHash, failure, parts.reverse)
    case StopEvent(FSM.Failure(_), _, d) =>
      log.error(s"unexpected payment failure data ${d.getClass.getSimpleName}")
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
  // @formatter:on

  // @formatter:off
  sealed trait Data {
    def parts: List[PendingPayment]
    lazy val paidAmount = parts.map(_.payment.amount).sum
  }
  case class WaitingForHtlc(parts: List[PendingPayment]) extends Data
  case class PaymentSucceeded(parts: List[PendingPayment]) extends Data
  case class PaymentFailed(failure: FailureMessage, parts: List[PendingPayment]) extends Data
  // @formatter:on

}