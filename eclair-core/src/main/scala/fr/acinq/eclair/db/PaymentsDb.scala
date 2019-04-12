/*
 * Copyright 2018 ACINQ SAS
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
import fr.acinq.eclair.payment.PaymentRequest

trait PaymentsDb {

  // assumes there is already a payment request for it (the record for the given payment hash)
  def addIncomingPayment(payment: IncomingPayment)

  // creates a record for a non yet finalized outgoing payment
  def addOutgoingPayment(sent: OutgoingPayment)

  // updates the status of the payment, succeededAt OR failedAt
  def updateOutgoingStatus(id: UUID, newStatus: OutgoingPaymentStatus.Value)

  // adds a new payment request and stores its preimage with it
  def addPaymentRequest(pr: PaymentRequest, preimage: ByteVector32)

  def getIncoming(paymentHash: ByteVector32): Option[IncomingPayment]

  def getOutgoing(id: UUID): Option[OutgoingPayment]

  def getOutgoing(paymentHash: ByteVector32): Option[OutgoingPayment]

  // return the payment request associated with this paymentHash
  def getPaymentRequest(paymentHash: ByteVector32): Option[PaymentRequest]

  // returns preimage + invoice
  def getRequestAndPreimage(paymentHash: ByteVector32): Option[(ByteVector32, PaymentRequest)]

  // returns all received payments
  def listIncoming(): Seq[IncomingPayment]

  // returns all sent payments
  def listOutgoing(): Seq[OutgoingPayment]

  // returns all payment request
  def listPaymentRequests(from: Long, to: Long): Seq[PaymentRequest]

  // returns non paid, non expired payment requests
  def listPendingPaymentRequests(): Seq[PaymentRequest]

}

/**
  * Incoming payment object stored in DB.
  *
  * @param paymentHash identifier of the payment
  * @param amountMsat  amount of the payment, in milli-satoshis
  * @param timestamp   absolute time in seconds since UNIX epoch when the payment was created.
  */
case class IncomingPayment(paymentHash: ByteVector32, amountMsat: Long, timestamp: Long)

/**
  * Sent payment is every payment that is sent by this node, they may not be finalized and
  * when is final it can be failed or successful.
  *
  * @param id          internal payment identifier
  * @param paymentHash payment_hash
  * @param amountMsat  amount of the payment, in milli-satoshis
  * @param createdAt   absolute time in seconds since UNIX epoch when the payment was created.
  * @param paidAt      absolute time in seconds since UNIX epoch when the payment was last updated.
  */
case class OutgoingPayment(id: UUID, paymentHash: ByteVector32, amountMsat: Long, createdAt: Long, succeededAt: Option[Long], failedAt: Option[Long], status: OutgoingPaymentStatus.Value)

object OutgoingPaymentStatus extends Enumeration {
  val PENDING = Value(1, "PENDING")
  val SUCCEEDED = Value(2, "SUCCEEDED")
  val FAILED = Value(3, "FAILED")
}

object OutgoingPayment {

  import OutgoingPaymentStatus._

  def apply(id: UUID, paymentHash: ByteVector32, amountMsat: Long, createdAt: Long, succeededAt: Option[Long] = None, failedAt: Option[Long] = None): OutgoingPayment = {
    val status = (succeededAt, failedAt) match {
      case (None, None) => PENDING
      case (Some(_), None) => SUCCEEDED
      case (None, Some(_)) => FAILED
      case (Some(_), Some(_)) => throw new RuntimeException(s"Invalid update field found for outgoing payment id=$id")
    }
    new OutgoingPayment(id, paymentHash, amountMsat, createdAt, succeededAt, failedAt, status = status)
  }

}
