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
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.payment.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol.{FailureMessage, IncorrectOrUnknownPaymentDetails, UpdateAddHtlc}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Logs, MilliSatoshi, NodeParams, TimestampMilli}

import java.util.concurrent.TimeUnit
import scala.collection.immutable.Queue

/**
 * Created by t-bast on 18/07/2019.
 */

/**
 * Handler for a multi-part payment (see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#basic-multi-part-payments).
 * Once all the partial payments are received, a MultiPartPaymentSucceeded message is sent to the parent.
 * After a reasonable delay, if not enough partial payments have been received, a MultiPartPaymentFailed message is sent to the parent.
 * This handler assumes that the parent only sends payments for the same payment hash.
 */
class MultiPartPaymentFSM(nodeParams: NodeParams, paymentHash: ByteVector32, totalAmount: MilliSatoshi, replyTo: ActorRef) extends FSMDiagnosticActorLogging[MultiPartPaymentFSM.State, MultiPartPaymentFSM.Data] {

  import MultiPartPaymentFSM._

  val start = TimestampMilli.now()

  startSingleTimer(PaymentTimeout.toString, PaymentTimeout, nodeParams.multiPartPaymentExpiry)

  startWith(WAITING_FOR_HTLC, WaitingForHtlc(Queue.empty))

  when(WAITING_FOR_HTLC) {
    case Event(PaymentTimeout, d: WaitingForHtlc) =>
      log.warning("multi-part payment timed out (received {} expected {})", d.paidAmount, totalAmount)
      goto(PAYMENT_FAILED) using PaymentFailed(protocol.PaymentTimeout(), d.parts)

    case Event(part: PaymentPart, d: WaitingForHtlc) =>
      require(part.paymentHash == paymentHash, s"invalid payment hash (expected $paymentHash, received ${part.paymentHash}")
      val updatedParts = d.parts :+ part
      if (totalAmount != part.totalAmount) {
        log.warning("multi-part payment total amount mismatch: previously {}, now {}", totalAmount, part.totalAmount)
        goto(PAYMENT_FAILED) using PaymentFailed(IncorrectOrUnknownPaymentDetails(part.totalAmount, nodeParams.currentBlockHeight), updatedParts)
      } else if (d.paidAmount + part.amount >= totalAmount) {
        goto(PAYMENT_SUCCEEDED) using PaymentSucceeded(updatedParts)
      } else {
        stay() using d.copy(parts = updatedParts)
      }
  }

  when(PAYMENT_SUCCEEDED) {
    // A sender should not send us additional htlcs for that payment once we've already reached the total amount.
    // However if that happens the only rational choice is to fulfill it, because the pre-image has been released so
    // intermediate nodes will be able to fulfill that htlc anyway. This is a harmless spec violation.
    case Event(part: PaymentPart, _) =>
      require(part.paymentHash == paymentHash, s"invalid payment hash (expected $paymentHash, received ${part.paymentHash}")
      log.info("received extraneous payment part with amount={}", part.amount)
      replyTo ! ExtraPaymentReceived(paymentHash, part, None)
      stay()
  }

  when(PAYMENT_FAILED) {
    // If we receive htlcs after the multi-part payment has expired, we must fail them.
    // The LocalPaymentHandler will create a new instance of MultiPartPaymentHandler to handle a new attempt.
    case Event(part: PaymentPart, PaymentFailed(failure, _)) =>
      require(part.paymentHash == paymentHash, s"invalid payment hash (expected $paymentHash, received ${part.paymentHash}")
      log.info("received extraneous payment part for payment hash {}", paymentHash)
      replyTo ! ExtraPaymentReceived(paymentHash, part, Some(failure))
      stay()
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
          replyTo ! MultiPartPaymentSucceeded(paymentHash, parts)
          Metrics.ReceivedPaymentDuration.withTag(Tags.Success, value = true).record((TimestampMilli.now() - start).toMillis, TimeUnit.MILLISECONDS)
        case d =>
          log.error("unexpected payment success data {}", d.getClass.getSimpleName)
      }
    case _ -> PAYMENT_FAILED =>
      nextStateData match {
        case PaymentFailed(failure, parts) =>
          // We expect the parent actor to send us a PoisonPill after receiving this message.
          replyTo ! MultiPartPaymentFailed(paymentHash, failure, parts)
          Metrics.ReceivedPaymentDuration.withTag(Tags.Success, value = false).record((TimestampMilli.now() - start).toMillis, TimeUnit.MILLISECONDS)
        case d =>
          log.error("unexpected payment failure data {}", d.getClass.getSimpleName)
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
  /** An incoming payment that we're currently holding until we decide to fulfill or fail it (depending on whether we receive the complete payment). */
  sealed trait PaymentPart {
    def paymentHash: ByteVector32
    def amount: MilliSatoshi
    def totalAmount: MilliSatoshi
  }
  /** An incoming HTLC. */
  case class HtlcPart(totalAmount: MilliSatoshi, htlc: UpdateAddHtlc) extends PaymentPart {
    override def paymentHash: ByteVector32  = htlc.paymentHash
    override def amount: MilliSatoshi  = htlc.amountMsat
  }
  /** We successfully received all parts of the payment. */
  case class MultiPartPaymentSucceeded(paymentHash: ByteVector32, parts: Queue[PaymentPart])
  /** We aborted the payment because of an inconsistency in the payment set or because we didn't receive the total amount in reasonable time. */
  case class MultiPartPaymentFailed(paymentHash: ByteVector32, failure: FailureMessage, parts: Queue[PaymentPart])
  /** We received an extraneous payment after we reached a final state (succeeded or failed). */
  case class ExtraPaymentReceived[T <: PaymentPart](paymentHash: ByteVector32, payment: T, failure: Option[FailureMessage])
  // @formatter:on

  // @formatter:off
  sealed trait State
  case object WAITING_FOR_HTLC extends State
  case object PAYMENT_SUCCEEDED extends State
  case object PAYMENT_FAILED extends State
  // @formatter:on

  // @formatter:off
  sealed trait Data {
    def parts: Queue[PaymentPart]
    lazy val paidAmount = parts.map(_.amount).sum
  }
  case class WaitingForHtlc(parts: Queue[PaymentPart]) extends Data
  case class PaymentSucceeded(parts: Queue[PaymentPart]) extends Data
  case class PaymentFailed(failure: FailureMessage, parts: Queue[PaymentPart]) extends Data
  // @formatter:on

}