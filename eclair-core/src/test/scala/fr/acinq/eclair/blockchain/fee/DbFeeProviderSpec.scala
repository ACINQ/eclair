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

package fr.acinq.eclair.blockchain.fee

import akka.util.Timeout
import fr.acinq.eclair.db.sqlite.SqliteFeeratesDb
import fr.acinq.eclair.{LongToBtcAmount, TestConstants}
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class DbFeeProviderSpec extends AnyFunSuite {

  val feerates1: FeeratesPerKB = FeeratesPerKB(FeeratePerKB(100 sat), FeeratePerKB(200 sat), FeeratePerKB(300 sat), FeeratePerKB(400 sat), FeeratePerKB(500 sat), FeeratePerKB(600 sat), FeeratePerKB(700 sat), FeeratePerKB(800 sat))

  test("db fee provider saves feerates in database") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteFeeratesDb(sqlite)
    val provider = new DbFeeProvider(db, new ConstantFeeProvider(feerates1))

    assert(db.getFeerates().isEmpty)
    assert(Await.result(provider.getFeerates, Timeout(30 seconds).duration) == feerates1)
    assert(db.getFeerates().get == feerates1)
  }

}
