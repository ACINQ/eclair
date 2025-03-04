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
import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases}
import fr.acinq.eclair._
import fr.acinq.eclair.db.pg.PgOffersDb
import fr.acinq.eclair.db.sqlite.SqliteOffersDb
import fr.acinq.eclair.wire.protocol.OfferTypes.Offer
import org.scalatest.funsuite.AnyFunSuite

class OffersDbSpec extends AnyFunSuite {

  import fr.acinq.eclair.TestDatabases.forAllDbs

  test("init database two times in a row") {
    forAllDbs {
      case sqlite: TestSqliteDatabases =>
        new SqliteOffersDb(sqlite.connection)
        new SqliteOffersDb(sqlite.connection)
      case pg: TestPgDatabases =>
        new PgOffersDb()(pg.datasource, pg.lock)
        new PgOffersDb()(pg.datasource, pg.lock)
    }
  }

  test("add/disable/enable/list offers") {
    forAllDbs { dbs =>
      val db = dbs.managedOffers

      assert(db.listOffers(onlyActive = false).isEmpty)
      val offer1 = Offer(None, Some("test 1"), randomKey().publicKey, Features(), Block.LivenetGenesisBlock.hash)
      db.addOffer(offer1, None)
      val listed1 = db.listOffers(onlyActive = true)
      assert(listed1.length == 1)
      assert(listed1.head.offer == offer1)
      assert(listed1.head.pathId_opt == None)
      assert(listed1.head.isActive)
      val offer2 = Offer(None, Some("test 2"), randomKey().publicKey, Features(), Block.LivenetGenesisBlock.hash)
      val pathId = randomBytes32()
      db.addOffer(offer2, Some(pathId))
      assert(db.listOffers(onlyActive = true).length == 2)
      db.disableOffer(offer1)
      assert(db.listOffers(onlyActive = false).length == 2)
      val listed2 = db.listOffers(onlyActive = true)
      assert(listed2.length == 1)
      assert(listed2.head.offer == offer2)
      assert(listed2.head.pathId_opt == Some(pathId))
      assert(listed2.head.isActive)
      db.disableOffer(offer2)
      db.enableOffer(offer1)
      assert(db.listOffers(onlyActive = true) == listed1)
    }
  }
}
