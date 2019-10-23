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

package fr.acinq.eclair.payment.handlers

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.{MilliSatoshi, NodeParams}
import fr.acinq.eclair.db.IncomingPayment
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.payment.handlers.MultipartHandler.IncomingPaymentsDb

/**
 * Default payment handler that supports multi-parts payments and uses the local payments db
 */
class DefaultHandler(nodeParams: NodeParams) extends MultipartHandler(nodeParams, new IncomingPaymentsDb {

  override def addIncomingPayment(pr: PaymentRequest, preimage: ByteVector32): Unit = nodeParams.db.payments.addIncomingPayment(pr, preimage)

  override def receiveIncomingPayment(paymentHash: ByteVector32, amount: MilliSatoshi, receivedAt: Long): Unit = nodeParams.db.payments.receiveIncomingPayment(paymentHash, amount, receivedAt)

  override def getIncomingPayment(paymentHash: ByteVector32): Option[IncomingPayment] = nodeParams.db.payments.getIncomingPayment(paymentHash)
})
