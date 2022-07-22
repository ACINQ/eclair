/*
 * Copyright 2022 ACINQ SAS
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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.payment.Bolt12Invoice
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}

import java.util.UUID

trait OffersDb extends OffersToSellDb with OffersToBuyDb

trait OffersToSellDb {

  def addOfferToSell(offer: Offer): Unit

  def getOfferToSell(offerId: ByteVector32, pathId_opt: Option[ByteVector32]): Option[Offer]

  def listOffersToSell(): Seq[(Offer, Option[ByteVector32])]

  def addAttemptToSellOffer(offerId: ByteVector32, invoiceRequest: InvoiceRequest, invoice: Bolt12Invoice, preimage: ByteVector32, pathId: ByteVector32): Unit

}

trait OffersToBuyDb {

  def addAttemptToBuyOffer(offer: Offer, amount: MilliSatoshi, quantity: Long, payerKey: PrivateKey, attemptId: UUID): Unit

  def getAttemptToBuyOffer(attemptId: UUID): Option[Any]

  def addInvoiceReceived(attemptId: UUID, invoice: Bolt12Invoice, paymentId_opt: Option[UUID]): Unit

}
