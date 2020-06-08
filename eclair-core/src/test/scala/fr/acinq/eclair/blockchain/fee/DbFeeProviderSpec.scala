/*
 * Copyright 2019 ACINQ SAS
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

  test("get feerates from db and not from provider") {
    // init db
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteFeeratesDb(sqlite)
    db.addOrUpdateFeerates("foo", feerates1)

    // result from provider should be ignored
    val provider = new DbFeeProvider(db, new FeeProvider {
      override def getName: String = "foo"

      override def getFeerates: Future[FeeratesPerKB] = {
        Future.successful(feerates2)
      }
    }, 60 * 1000)

    assert(Await.result(provider.getFeerates, Timeout(30 seconds).duration) == feerates1)
  }

  test("refresh feerates in db when db is obsolete") {
    // init db
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteFeeratesDb(sqlite)
    val provider = new DbFeeProvider(db, new FeeProvider {
      var counter = 1

      override def getName: String = "foo"

      override def getFeerates: Future[FeeratesPerKB] = {
        val feerates = if (counter == 1) feerates1 else feerates2
        counter += 1
        Future.successful(feerates)
      }
    }, 1)

    // first call: db empty
    assert(db.getFeerates("foo").isEmpty)
    assert(Await.result(provider.getFeerates, Timeout(30 seconds).duration) == feerates1)
    assert(db.getFeerates("foo").get._1 == feerates1)

    // second call: data from db is already obsolete and should be ignored
    Thread.sleep(5)
    assert(Await.result(provider.getFeerates, Timeout(30 seconds).duration) == feerates2)
  }
}
