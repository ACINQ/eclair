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
import fr.acinq.eclair.blockchain.fee.FeeratesPerKB
import fr.acinq.eclair.db.sqlite.SqliteFeeratesDb
import org.scalatest.funsuite.AnyFunSuite

class SqliteFeeratesDbSpec extends AnyFunSuite {

  test("init sqlite 2 times in a row") {
    val sqlite = TestConstants.sqliteInMemory()
    val db1 = new SqliteFeeratesDb(sqlite)
    val db2 = new SqliteFeeratesDb(sqlite)
  }

  test("add/get feerates") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteFeeratesDb(sqlite)
    val feerate = FeeratesPerKB(
      block_1 = 150000,
      blocks_2 = 120000,
      blocks_6 = 100000,
      blocks_12 = 90000,
      blocks_36 = 70000,
      blocks_72 = 50000,
      blocks_144 = 20000)

    db.addOrUpdateFeerates(feerate)
    assert(db.getFeerates().get == feerate)
  }
}
