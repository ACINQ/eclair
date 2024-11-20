/*
 * Copyright 2024 ACINQ SAS
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

package fr.acinq.eclair.db.sqlite

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.db.{OfferData, OffersDb}
import fr.acinq.eclair.wire.protocol.OfferTypes
import grizzled.slf4j.Logging

import java.sql.Connection

class SqliteOffersDb(val sqlite: Connection) extends OffersDb with Logging {

  override def addOffer(offer: OfferTypes.Offer, pathId_opt: Option[ByteVector32]): Unit = ???

  override def disableOffer(offer: OfferTypes.Offer): Unit = ???

  override def listOffers(onlyActive: Boolean): Seq[OfferData] = ???
}
