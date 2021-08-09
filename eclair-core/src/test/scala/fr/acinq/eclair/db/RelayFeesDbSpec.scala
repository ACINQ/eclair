/*
 * Copyright 2021 ACINQ SAS
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

import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases}
import fr.acinq.eclair.db.pg.PgRelayFeesDb
import fr.acinq.eclair.db.sqlite.SqliteRelayFeesDb
import fr.acinq.eclair._
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import org.scalatest.funsuite.AnyFunSuite

class RelayFeesDbSpec extends AnyFunSuite {

  import fr.acinq.eclair.TestDatabases.forAllDbs

  test("init database two times in a row") {
    forAllDbs {
      case sqlite: TestSqliteDatabases =>
        new SqliteRelayFeesDb(sqlite.connection)
        new SqliteRelayFeesDb(sqlite.connection)
      case pg: TestPgDatabases =>
        new PgRelayFeesDb()(pg.datasource, pg.lock)
        new PgRelayFeesDb()(pg.datasource, pg.lock)
    }
  }

  test("add and update relay fees") {
    forAllDbs { dbs =>
      val db = dbs.relayFees

      val a = randomKey().publicKey
      val b = randomKey().publicKey

      assert(db.getFees(a) === None)
      assert(db.getFees(b) === None)
      db.addOrUpdateFees(a, RelayFees(1 msat, 123))
      assert(db.getFees(a) === Some(1 msat, 123))
      assert(db.getFees(b) === None)
      Thread.sleep(2)
      db.addOrUpdateFees(a, RelayFees(2 msat, 456))
      assert(db.getFees(a) === Some(2 msat, 456))
      assert(db.getFees(b) === None)
      db.addOrUpdateFees(b, RelayFees(3 msat, 789))
      assert(db.getFees(a) === Some(2 msat, 456))
      assert(db.getFees(b) === Some(3 msat, 789))
    }
  }

}
