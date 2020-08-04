/*
 * Copyright 2020 ACINQ SAS
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

import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKB, FeeratesPerKB}
import fr.acinq.eclair.db.sqlite.SqliteUtils.{getVersion, using}
import fr.acinq.eclair.db.sqlite.SqliteFeeratesDb
import org.scalatest.funsuite.AnyFunSuite

class SqliteFeeratesDbSpec extends AnyFunSuite {

  val feerate = FeeratesPerKB(
    block_1 = FeeratePerKB(150000 sat),
    blocks_2 = FeeratePerKB(120000 sat),
    blocks_6 = FeeratePerKB(100000 sat),
    blocks_12 = FeeratePerKB(90000 sat),
    blocks_36 = FeeratePerKB(70000 sat),
    blocks_72 = FeeratePerKB(50000 sat),
    blocks_144 = FeeratePerKB(20000 sat),
    blocks_1008 = FeeratePerKB(10000 sat))

  test("init sqlite 2 times in a row") {
    val sqlite = TestConstants.sqliteInMemory()
    val db1 = new SqliteFeeratesDb(sqlite)
    val db2 = new SqliteFeeratesDb(sqlite)
  }

  test("add/get feerates") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteFeeratesDb(sqlite)

    db.addOrUpdateFeerates(feerate)
    assert(db.getFeerates().get == feerate)
  }

  test("migration 1->2") {
    val sqlite = TestConstants.sqliteInMemory()

    using(sqlite.createStatement()) { statement =>
      getVersion(statement, "feerates", 1) // this will set version to 1
      statement.executeUpdate(
        """
          |CREATE TABLE IF NOT EXISTS feerates_per_kb (
          |rate_block_1 INTEGER NOT NULL, rate_blocks_2 INTEGER NOT NULL, rate_blocks_6 INTEGER NOT NULL, rate_blocks_12 INTEGER NOT NULL, rate_blocks_36 INTEGER NOT NULL, rate_blocks_72 INTEGER NOT NULL, rate_blocks_144 INTEGER NOT NULL,
          |timestamp INTEGER NOT NULL)""".stripMargin)
    }

    using(sqlite.createStatement()) { statement =>
      assert(getVersion(statement, "feerates", 2) == 1) // version 1 is deployed now
    }

    // Version 1 was missing the 1008 block target.
    using(sqlite.prepareStatement("INSERT INTO feerates_per_kb VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1, feerate.block_1.toLong)
      statement.setLong(2, feerate.blocks_2.toLong)
      statement.setLong(3, feerate.blocks_6.toLong)
      statement.setLong(4, feerate.blocks_12.toLong)
      statement.setLong(5, feerate.blocks_36.toLong)
      statement.setLong(6, feerate.blocks_72.toLong)
      statement.setLong(7, feerate.blocks_144.toLong)
      statement.setLong(8, System.currentTimeMillis())
      statement.executeUpdate()
    }

    val migratedDb = new SqliteFeeratesDb(sqlite)
    using(sqlite.createStatement()) { statement =>
      assert(getVersion(statement, "feerates", 2) == 2) // version changed from 1 -> 2
    }

    // When migrating, we simply copy the estimate for blocks 144 to blocks 1008.
    assert(migratedDb.getFeerates() === Some(feerate.copy(blocks_1008 = feerate.blocks_144)))
    migratedDb.addOrUpdateFeerates(feerate)
    assert(migratedDb.getFeerates() === Some(feerate))
  }

}
