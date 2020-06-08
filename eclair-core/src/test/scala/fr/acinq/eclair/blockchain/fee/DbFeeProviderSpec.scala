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
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.db.sqlite.SqliteFeeratesDb
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class DbFeeProviderSpec extends AnyFunSuite {

  val feerates1: FeeratesPerKB = FeeratesPerKB(100, 200, 300, 400, 500, 600, 700)
  val feerates2: FeeratesPerKB = FeeratesPerKB(200, 300, 400, 500, 600, 700, 800)

  test("get feerates from db on first call only") {
    // init db
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteFeeratesDb(sqlite)
    db.addOrUpdateFeerates("foo", feerates1)

    val provider = new DbFeeProvider(db, new ConstantFeeProvider(feerates2) {
      override def getName: String = "foo"
    })

    assert(Await.result(provider.getFeerates, Timeout(30 seconds).duration) == feerates1)
    assert(Await.result(provider.getFeerates, Timeout(30 seconds).duration) == feerates2)
  }

  test("fallback when feerates db is empty") {
    // init db
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteFeeratesDb(sqlite)
    val provider = new DbFeeProvider(db, new ConstantFeeProvider(feerates1) {
      override def getName: String = "foo"
    })

    // first db empty, then should be filled with the result from the provider
    assert(db.getFeerates("foo").isEmpty)
    assert(Await.result(provider.getFeerates, Timeout(30 seconds).duration) == feerates1)
    assert(db.getFeerates("foo").get == feerates1)
  }
}
