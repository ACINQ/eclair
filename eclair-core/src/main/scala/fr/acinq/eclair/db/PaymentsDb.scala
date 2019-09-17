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

package fr.acinq.eclair.db

import java.util.UUID

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId}

trait PaymentsDb {

  /** Create a record for a non yet finalized outgoing payment. */
  def addOutgoingPayment(outgoingPayment: OutgoingPayment)

  /** Update the status of the payment in case of success. */
  def updateOutgoingPayment(paymentResult: PaymentSent)

  /** Update the status of the payment in case of failure. */
  def updateOutgoingPayment(paymentResult: PaymentFailed)

  /** Get an outgoing payment attempt. */
  def getOutgoingPayment(id: UUID): Option[OutgoingPayment]

  /** Get all the outgoing payment attempts to pay the given paymentHash. */
  def getOutgoingPayments(paymentHash: ByteVector32): Seq[OutgoingPayment]

  /** Get all the outgoing payment attempts in the given time range. */
  def listOutgoingPayments(from: Long, to: Long): Seq[OutgoingPayment]

  /** Add a new payment request (to receive a payment). */
  def addPaymentRequest(pr: PaymentRequest, preimage: ByteVector32)

  /** Get the payment request for the given payment hash, if any. */
  def getPaymentRequest(paymentHash: ByteVector32): Option[PaymentRequest]

  /** Get the currently pending payment request for the given payment hash, if any. */
  def getPendingPaymentRequestAndPreimage(paymentHash: ByteVector32): Option[(ByteVector32, PaymentRequest)]

  /** Get all payment requests (pending, expired and fulfilled) in the given time range. */
  def listPaymentRequests(from: Long, to: Long): Seq[PaymentRequest]

  /** Get pending, non expired payment requests in the given time range. */
  def listPendingPaymentRequests(from: Long, to: Long): Seq[PaymentRequest]

  /** Add a received payment (assumes there is already a payment request for the given payment hash). */
  def addIncomingPayment(payment: IncomingPayment)

  /** Get the received payment associated with a given payment hash, if any. */
  def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment]

  /** Get all payments received in the given time range. */
  def listIncomingPayments(from: Long, to: Long): Seq[IncomingPayment]

}

/**
 * Incoming payment object stored in DB.
 *
 * @param paymentHash identifier of the payment.
 * @param amount      amount of the payment, in milli-satoshis.
 * @param receivedAt  absolute time in seconds since UNIX epoch when the payment was received.
 */
case class IncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: Long)

/**
 * An outgoing payment sent by this node.
 * At first it is in a pending state, then will become either a success or a failure.
 *
 * @param id             internal payment identifier.
 * @param parentId       internal identifier of a parent payment, if any.
 * @param externalId     external payment identifier: lets lightning applications reconcile payments with their own db.
 * @param paymentHash    payment_hash.
 * @param amount         amount of the payment, in milli-satoshis.
 * @param targetNodeId   node ID of the payment recipient.
 * @param createdAt      absolute time in seconds since UNIX epoch when the payment was created.
 * @param status         current status of the payment.
 * @param paymentRequest Bolt 11 payment request (if paying from an invoice).
 * @param completedAt    absolute time in seconds since UNIX epoch when the payment completed (success of failure).
 * @param successSummary summary of the payment success (if status == "SUCCEEDED").
 * @param failureSummary summary of the payment failure (if status == "FAILED").
 */
case class OutgoingPayment(id: UUID,
                           parentId: Option[UUID],
                           externalId: Option[String],
                           paymentHash: ByteVector32,
                           amount: MilliSatoshi,
                           targetNodeId: PublicKey,
                           createdAt: Long,
                           status: OutgoingPaymentStatus.Value,
                           paymentRequest: Option[PaymentRequest],
                           completedAt: Option[Long] = None,
                           successSummary: Option[PaymentSuccessSummary] = None,
                           failureSummary: Option[PaymentFailureSummary] = None)

object OutgoingPaymentStatus extends Enumeration {
  val PENDING = Value(1, "PENDING")
  val SUCCEEDED = Value(2, "SUCCEEDED")
  val FAILED = Value(3, "FAILED")
}

case class PaymentSuccessSummary(paymentPreimage: ByteVector32, feesPaid: MilliSatoshi, route: Seq[HopSummary])

case class PaymentFailureSummary(failures: Seq[FailureSummary])

/** A minimal representation of a hop in a payment route (suitable to store in a database). */
case class HopSummary(nodeId: PublicKey, nextNodeId: PublicKey, shortChannelId: Option[ShortChannelId] = None) {
  override def toString = shortChannelId match {
    case Some(shortChannelId) => s"$nodeId->$nextNodeId ($shortChannelId)"
    case None => s"$nodeId->$nextNodeId"
  }
}

object HopSummary {
  def apply(h: Hop): HopSummary = HopSummary(h.nodeId, h.nextNodeId, Some(h.lastUpdate.shortChannelId))
}

/** A minimal representation of a payment failure (suitable to store in a database). */
case class FailureSummary(failureType: FailureType.Value, failureMessage: String, failedRoute: List[HopSummary])

object FailureType extends Enumeration {
  val LOCAL = Value(1, "Local")
  val REMOTE = Value(2, "Remote")
  val UNREADABLE_REMOTE = Value(3, "UnreadableRemote")
}

object FailureSummary {
  def apply(f: PaymentFailure): FailureSummary = f match {
    case LocalFailure(t) => FailureSummary(FailureType.LOCAL, t.getMessage, Nil)
    case RemoteFailure(route, e) => FailureSummary(FailureType.REMOTE, e.failureMessage.message, route.map(h => HopSummary(h)).toList)
    case UnreadableRemoteFailure(route) => FailureSummary(FailureType.UNREADABLE_REMOTE, "could not decrypt failure onion", route.map(h => HopSummary(h)).toList)
  }
}