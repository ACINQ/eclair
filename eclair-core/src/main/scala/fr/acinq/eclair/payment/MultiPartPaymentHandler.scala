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
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.wire.{FailureMessage, IncorrectOrUnknownPaymentDetails, UpdateAddHtlc}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, LongToBtcAmount, MilliSatoshi, NodeParams, wire}

/**
 * Created by t-bast on 18/07/2019.
 */

/**
 * Handler for a multi-part payment (see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#basic-multi-part-payments).
 * Once all the partial payments are received, all the partial HTLCs are fulfilled.
 * After a reasonable delay, if not enough partial payments have been received, all the partial HTLCs are failed.
 * This handler assumes that the parent only sends payments for the same payment hash.
 */
class MultiPartPaymentHandler(nodeParams: NodeParams, paymentHash: ByteVector32, paymentPreimage: ByteVector32, parent: ActorRef) extends FSMDiagnosticActorLogging[MultiPartPaymentHandler.State, MultiPartPaymentHandler.Data] {
  require(Crypto.sha256(paymentPreimage) == paymentHash, "payment preimage must the preimage of the payment hash")

  import MultiPartPaymentHandler._

  setTimer(PaymentTimeout.toString, PaymentTimeout, nodeParams.multiPartPaymentExpiry, repeat = false)

  startWith(WAITING_FOR_FIRST_HTLC, WaitingForFirstHtlc)

  when(WAITING_FOR_FIRST_HTLC) {
    case Event(PaymentTimeout, _) =>
      goto(PAYMENT_FAILED) using PaymentFailed(0 msat, wire.PaymentTimeout, List.empty[(Long, PartialPayment, ActorRef)])

    case Event(MultiPartHtlc(totalAmount, htlc), _) =>
      require(htlc.paymentHash == paymentHash, s"invalid payment hash (expected $paymentHash, received ${htlc.paymentHash}")
      val pp = PartialPayment(htlc.amountMsat, htlc.channelId)
      if (htlc.amountMsat >= totalAmount) {
        goto(PAYMENT_SUCCEEDED) using PaymentSucceeded(htlc.amountMsat, (htlc.id, pp, sender) :: Nil)
      } else {
        goto(WAITING_FOR_MORE_HTLC) using WaitingForMoreHtlc(totalAmount, htlc.amountMsat, (htlc.id, pp, sender) :: Nil)
      }
  }

  when(WAITING_FOR_MORE_HTLC) {
    case Event(PaymentTimeout, d: WaitingForMoreHtlc) =>
      goto(PAYMENT_FAILED) using PaymentFailed(d.paidAmount, wire.PaymentTimeout, d.parts)

    case Event(MultiPartHtlc(totalAmount, htlc), d: WaitingForMoreHtlc) =>
      require(htlc.paymentHash == paymentHash, s"invalid payment hash (expected $paymentHash, received ${htlc.paymentHash}")
      val pp = PartialPayment(htlc.amountMsat, htlc.channelId)
      if (totalAmount != d.totalAmount) {
        log.warning(s"multi-part payment total amount mismatch: previously ${d.totalAmount}, now $totalAmount")
        goto(PAYMENT_FAILED) using PaymentFailed(d.paidAmount, IncorrectOrUnknownPaymentDetails(totalAmount, nodeParams.currentBlockHeight), (htlc.id, pp, sender) :: d.parts)
      } else if (htlc.amountMsat + d.paidAmount >= d.totalAmount) {
        goto(PAYMENT_SUCCEEDED) using PaymentSucceeded(htlc.amountMsat + d.paidAmount, (htlc.id, pp, sender) :: d.parts)
      } else {
        stay using d.copy(paidAmount = d.paidAmount + htlc.amountMsat, parts = (htlc.id, pp, sender) :: d.parts)
      }
  }

  when(PAYMENT_SUCCEEDED) {
    // A sender should not send us additional htlcs for that payment once we've already reached the total amount.
    // However if that happens the only rational choice is to fulfill it, because the pre-image has been released so
    // intermediate nodes will be able to fulfill that htlc anyway. This is a harmless spec violation.
    case Event(MultiPartHtlc(_, htlc), _) =>
      log.info(s"received extraneous htlc for payment hash ${htlc.paymentHash}")
      sender ! CMD_FULFILL_HTLC(htlc.id, paymentPreimage, commit = true)
      context.system.eventStream.publish(PaymentReceived(htlc.paymentHash, Seq(PartialPayment(htlc.amountMsat, htlc.channelId))))
      stay

    case Event("ok", _) => stay
  }

  when(PAYMENT_FAILED) {
    // If we receive htlcs after the multi-part payment has expired, we must fail them.
    // The LocalPaymentHandler will create a new instance of MultiPartPaymentHandler to handle a new attempt.
    case Event(MultiPartHtlc(_, htlc), PaymentFailed(_, failure, _)) =>
      sender ! CMD_FAIL_HTLC(htlc.id, Right(failure), commit = true)
      stay

    case Event("ok", _) => stay
  }

  onTransition {
    case _ -> PAYMENT_SUCCEEDED =>
      cancelTimer(PaymentTimeout.toString)
      nextStateData match {
        case PaymentSucceeded(_, parts) =>
          parts.reverse.foreach { case (id, _, sender) => sender ! CMD_FULFILL_HTLC(id, paymentPreimage, commit = true) }
          // We expect the parent actor to send us a PoisonPill after receiving this message.
          parent ! MultiPartHtlcSucceeded(PaymentReceived(paymentHash, parts.reverse.map(_._2)))
        case d =>
          log.error(s"unexpected payment success data ${d.getClass.getSimpleName}")
      }
    case _ -> PAYMENT_FAILED =>
      cancelTimer(PaymentTimeout.toString)
      nextStateData match {
        case PaymentFailed(paidAmount, failure, parts) =>
          parts.reverse.foreach { case (id, _, sender) => sender ! CMD_FAIL_HTLC(id, Right(failure), commit = true) }
          // We expect the parent actor to send us a PoisonPill after receiving this message.
          parent ! MultiPartHtlcFailed(paymentHash, paidAmount)
        case d =>
          log.error(s"unexpected payment failure data ${d.getClass.getSimpleName}")
      }
  }

  initialize()

}

object MultiPartPaymentHandler {

  def props(nodeParams: NodeParams, paymentHash: ByteVector32, paymentPreimage: ByteVector32, parent: ActorRef) = Props(new MultiPartPaymentHandler(nodeParams, paymentHash, paymentPreimage, parent))

  case object PaymentTimeout

  // @formatter:off
  case class MultiPartHtlc(totalAmount: MilliSatoshi, htlc: UpdateAddHtlc)
  case class MultiPartHtlcSucceeded(paymentReceived: PaymentReceived)
  case class MultiPartHtlcFailed(paymentHash: ByteVector32, receivedAmount: MilliSatoshi)
  // @formatter:on

  // @formatter:off
  sealed trait State
  case object WAITING_FOR_FIRST_HTLC extends State
  case object WAITING_FOR_MORE_HTLC extends State
  case object PAYMENT_SUCCEEDED extends State
  case object PAYMENT_FAILED extends State
  // @formatter:on

  // @formatter:off
  sealed trait Data
  case object WaitingForFirstHtlc extends Data
  case class WaitingForMoreHtlc(totalAmount: MilliSatoshi, paidAmount: MilliSatoshi, parts: List[(Long, PartialPayment, ActorRef)]) extends Data
  case class PaymentSucceeded(paidAmount: MilliSatoshi, parts: List[(Long, PartialPayment, ActorRef)]) extends Data
  case class PaymentFailed(paidAmount: MilliSatoshi, failure: FailureMessage, parts: List[(Long, PartialPayment, ActorRef)]) extends Data
  // @formatter:on

}