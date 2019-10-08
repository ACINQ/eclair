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

import scala.compat.Platform

trait PaymentsDb {

  /** Create a record for a non yet finalized outgoing payment. */
  def addOutgoingPayment(outgoingPayment: OutgoingPayment): Unit

  /** Update the status of the payment in case of success. */
  def updateOutgoingPayment(paymentResult: PaymentSent): Unit

  /** Update the status of the payment in case of failure. */
  def updateOutgoingPayment(paymentResult: PaymentFailed): Unit

  /** Get an outgoing payment attempt. */
  def getOutgoingPayment(id: UUID): Option[OutgoingPayment]

  /** List all the outgoing payment attempts that are children of the given id. */
  def listOutgoingPayments(parentId: UUID): Seq[OutgoingPayment]

  /** List all the outgoing payment attempts that tried to pay the given payment hash. */
  def listOutgoingPayments(paymentHash: ByteVector32): Seq[OutgoingPayment]

  /** List all the outgoing payment attempts in the given time range (milli-seconds). */
  def listOutgoingPayments(from: Long, to: Long): Seq[OutgoingPayment]

  /** Add a new expected incoming payment (not yet received). */
  def addIncomingPayment(pr: PaymentRequest, preimage: ByteVector32): Unit

  /**
   * Mark an incoming payment as received (paid). The received amount may exceed the payment request amount.
   * Note that this function assumes that there is a matching payment request in the DB.
   */
  def receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: Long = Platform.currentTime): Unit

  /** Get information about the incoming payment (paid or not) for the given payment hash, if any. */
  def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment]

  /** List all incoming payments (pending, expired and succeeded) in the given time range (milli-seconds). */
  def listIncomingPayments(from: Long, to: Long): Seq[IncomingPayment]

  /** List all pending (not paid, not expired) incoming payments in the given time range (milli-seconds). */
  def listPendingIncomingPayments(from: Long, to: Long): Seq[IncomingPayment]

  /** List all expired (not paid) incoming payments in the given time range (milli-seconds). */
  def listExpiredIncomingPayments(from: Long, to: Long): Seq[IncomingPayment]

  /** List all received (paid) incoming payments in the given time range (milli-seconds). */
  def listReceivedIncomingPayments(from: Long, to: Long): Seq[IncomingPayment]

}

/**
 * An incoming payment received by this node.
 * At first it is in a pending state once the payment request has been generated, then will become either a success (if
 * we receive a valid HTLC) or a failure (if the payment request expires).
 *
 * @param paymentRequest  Bolt 11 payment request.
 * @param paymentPreimage pre-image associated with the payment request's payment_hash.
 * @param createdAt       absolute time in milli-seconds since UNIX epoch when the payment request was generated.
 * @param status          current status of the payment.
 */
case class IncomingPayment(paymentRequest: PaymentRequest,
                           paymentPreimage: ByteVector32,
                           createdAt: Long,
                           status: IncomingPaymentStatus)

sealed trait IncomingPaymentStatus

object IncomingPaymentStatus {

  /** Payment is pending (waiting to receive). */
  case object Pending extends IncomingPaymentStatus

  /** Payment has expired. */
  case object Expired extends IncomingPaymentStatus

  /**
   * Payment has been successfully received.
   *
   * @param amount     amount of the payment received, in milli-satoshis (may exceed the payment request amount).
   * @param receivedAt absolute time in milli-seconds since UNIX epoch when the payment was received.
   */
  case class Received(amount: MilliSatoshi, receivedAt: Long) extends IncomingPaymentStatus

}

/**
 * An outgoing payment sent by this node.
 * At first it is in a pending state, then will become either a success or a failure.
 *
 * @param id             internal payment identifier.
 * @param parentId       internal identifier of a parent payment, or [[id]] if single-part payment.
 * @param externalId     external payment identifier: lets lightning applications reconcile payments with their own db.
 * @param paymentHash    payment_hash.
 * @param amount         amount of the payment, in milli-satoshis.
 * @param targetNodeId   node ID of the payment recipient.
 * @param createdAt      absolute time in milli-seconds since UNIX epoch when the payment was created.
 * @param paymentRequest Bolt 11 payment request (if paying from an invoice).
 * @param status         current status of the payment.
 */
case class OutgoingPayment(id: UUID,
                           parentId: UUID,
                           externalId: Option[String],
                           paymentHash: ByteVector32,
                           amount: MilliSatoshi,
                           targetNodeId: PublicKey,
                           createdAt: Long,
                           paymentRequest: Option[PaymentRequest],
                           status: OutgoingPaymentStatus)

sealed trait OutgoingPaymentStatus

object OutgoingPaymentStatus {

  /** Payment is pending (waiting for the recipient to release the pre-image). */
  case object Pending extends OutgoingPaymentStatus

  /**
   * Payment has been successfully sent and the recipient released the pre-image.
   * We now have a valid proof-of-payment.
   *
   * @param paymentPreimage the preimage of the payment_hash.
   * @param feesPaid        total amount of fees paid to intermediate routing nodes.
   * @param route           payment route.
   * @param completedAt     absolute time in milli-seconds since UNIX epoch when the payment was completed.
   */
  case class Succeeded(paymentPreimage: ByteVector32, feesPaid: MilliSatoshi, route: Seq[HopSummary], completedAt: Long) extends OutgoingPaymentStatus

  /**
   * Payment has failed and may be retried.
   *
   * @param failures    failed payment attempts.
   * @param completedAt absolute time in milli-seconds since UNIX epoch when the payment was completed.
   */
  case class Failed(failures: Seq[FailureSummary], completedAt: Long) extends OutgoingPaymentStatus

}

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