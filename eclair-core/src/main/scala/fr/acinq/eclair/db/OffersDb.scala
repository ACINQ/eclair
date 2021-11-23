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

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.eclair.payment.Bolt12Invoice
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}

import java.util.UUID

trait OffersDb extends PayOffersDb

case class AttemptToPayOffer(attemptId: UUID,
                             offer: Offer,
                             request: InvoiceRequest,
                             payerKey: PrivateKey,
                             invoice: Option[Bolt12Invoice],
                             paymentId_opt: Option[UUID])

trait PayOffersDb {

  def addAttemptToPayOffer(offer: Offer, request: InvoiceRequest, payerKey: PrivateKey, attemptId: UUID): Unit

  def getAttemptToPayOffer(attemptId: UUID): Option[AttemptToPayOffer]

  def getAttemptsToPayOffer(offer: Offer): Seq[AttemptToPayOffer]

  def addOfferInvoice(attemptId: UUID, invoice: Option[Bolt12Invoice], paymentId_opt: Option[UUID]): Unit

}
