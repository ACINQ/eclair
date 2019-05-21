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
import fr.acinq.eclair.payment.PaymentRequest

trait PaymentsDb {

  // creates a record for a non yet finalized outgoing payment
  def addOutgoingPayment(outgoingPayment: OutgoingPayment)

  // updates the status of the payment, if the newStatus is SUCCEEDED you must supply a preimage
  def updateOutgoingPayment(id: UUID, newStatus: OutgoingPaymentStatus.Value, preimage: Option[ByteVector32] = None)

  def getOutgoingPayment(id: UUID): Option[OutgoingPayment]

  // all the outgoing payment (attempts) to pay the given paymentHash
  def getOutgoingPayments(paymentHash: ByteVector32): Seq[OutgoingPayment]

  def listOutgoingPayments(): Seq[OutgoingPayment]

  def addPaymentRequest(pr: PaymentRequest, preimage: ByteVector32)

  def getPaymentRequest(paymentHash: ByteVector32): Option[PaymentRequest]

  def getPendingPaymentRequestAndPreimage(paymentHash: ByteVector32): Option[(ByteVector32, PaymentRequest)]

  def listPaymentRequests(from: Long, to: Long): Seq[PaymentRequest]

  // returns non paid, non expired payment requests
  def listPendingPaymentRequests(from: Long, to: Long): Seq[PaymentRequest]

  // assumes there is already a payment request for it (the record for the given payment hash)
  def addIncomingPayment(payment: IncomingPayment)

  def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment]

  def listIncomingPayments(): Seq[IncomingPayment]

}

/**
  * Incoming payment object stored in DB.
  *
  * @param paymentHash identifier of the payment
  * @param amountMsat  amount of the payment, in milli-satoshis
  * @param receivedAt  absolute time in seconds since UNIX epoch when the payment was received.
  */
case class IncomingPayment(paymentHash: ByteVector32, amountMsat: Long, receivedAt: Long)

/**
  * Sent payment is every payment that is sent by this node, they may not be finalized and
  * when is final it can be failed or successful.
  *
  * @param id          internal payment identifier
  * @param paymentHash payment_hash
  * @param preimage    the preimage of the payment_hash, known if the outgoing payment was successful
  * @param amountMsat  amount of the payment, in milli-satoshis
  * @param createdAt   absolute time in seconds since UNIX epoch when the payment was created.
  * @param completedAt absolute time in seconds since UNIX epoch when the payment succeeded.
  * @param status      current status of the payment.
  */
case class OutgoingPayment(id: UUID, paymentHash: ByteVector32, preimage:Option[ByteVector32], amountMsat: Long, createdAt: Long, completedAt: Option[Long], status: OutgoingPaymentStatus.Value)

object OutgoingPaymentStatus extends Enumeration {
  val PENDING = Value(1, "PENDING")
  val SUCCEEDED = Value(2, "SUCCEEDED")
  val FAILED = Value(3, "FAILED")
}