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

import fr.acinq.bitcoin.scalacompat.Block
import fr.acinq.eclair._
import fr.acinq.eclair.wire.protocol.OfferTypes.Offer
import org.scalatest.funsuite.AnyFunSuite

class OffersDbSpec extends AnyFunSuite {

  import fr.acinq.eclair.TestDatabases.forAllDbs

  test("add/disable/enable/list offers") {
    forAllDbs { dbs =>
      val db = dbs.offers

      assert(db.listOffers(onlyActive = false).isEmpty)
      val offer1 = OfferData(Offer(None, Some("test 1"), randomKey().publicKey, Features(), Block.LivenetGenesisBlock.hash), None, TimestampMilli(100), None)
      assert(db.addOffer(offer1.offer, None, offer1.createdAt).nonEmpty)
      assert(db.addOffer(offer1.offer, None, TimestampMilli(150)).isEmpty)
      assert(db.listOffers(onlyActive = true) == Seq(offer1))
      val pathId = randomBytes32()
      val offer2 = OfferData(Offer(Some(15_000 msat), Some("test 2"), randomKey().publicKey, Features(), Block.LivenetGenesisBlock.hash), Some(pathId), TimestampMilli(200), None)
      assert(db.addOffer(offer2.offer, Some(pathId), offer2.createdAt).nonEmpty)
      assert(db.listOffers(onlyActive = true) == Seq(offer2, offer1))
      db.disableOffer(offer1.offer, disabledAt = TimestampMilli(250))
      assert(db.listOffers(onlyActive = true) == Seq(offer2))
      assert(db.listOffers(onlyActive = false) == Seq(offer2, offer1.copy(disabledAt_opt = Some(TimestampMilli(250)))))
      db.disableOffer(offer2.offer, disabledAt = TimestampMilli(300))
      assert(db.listOffers(onlyActive = true).isEmpty)
      assert(db.listOffers(onlyActive = false) == Seq(offer2.copy(disabledAt_opt = Some(TimestampMilli(300))), offer1.copy(disabledAt_opt = Some(TimestampMilli(250)))))
    }
  }

}
