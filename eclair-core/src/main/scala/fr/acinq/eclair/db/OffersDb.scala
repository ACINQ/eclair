/*
 * Copyright 2025 ACINQ SAS
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
import fr.acinq.eclair.TimestampMilli
import fr.acinq.eclair.wire.protocol.OfferTypes.Offer

/**
 * Database for offers fully managed by eclair, as opposed to offers managed by a plugin.
 */
trait OffersDb {
  /**
   * Add an offer managed by eclair.
   *
   * @param pathId_opt If the offer uses a blinded path, this is the corresponding pathId.
   */
  def addOffer(offer: Offer, pathId_opt: Option[ByteVector32], createdAt: TimestampMilli = TimestampMilli.now()): Option[OfferData]

  /**
   * Disable an offer. The offer is still stored but new invoice requests and new payment attempts for already emitted
   * invoices will be rejected.
   */
  def disableOffer(offer: Offer, disabledAt: TimestampMilli = TimestampMilli.now()): Unit

  /**
   * List offers managed by eclair.
   *
   * @param onlyActive Whether to return only active offers or also disabled ones.
   */
  def listOffers(onlyActive: Boolean): Seq[OfferData]
}

case class OfferData(offer: Offer, pathId_opt: Option[ByteVector32], createdAt: TimestampMilli, disabledAt_opt: Option[TimestampMilli]) {
  val disabled: Boolean = disabledAt_opt.nonEmpty
}
