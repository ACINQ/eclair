/*
 * Copyright 2023 ACINQ SAS
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

package fr.acinq.eclair.reputation

import fr.acinq.eclair.{MilliSatoshiLong, TimestampMilli}
import fr.acinq.eclair.reputation.Reputation.ReputationConfig
import org.scalatest.funsuite.AnyFunSuite
import org.scalactic.Tolerance.convertNumericToPlusOrMinusWrapper

import java.util.UUID
import scala.concurrent.duration.DurationInt

class ReputationSpec extends AnyFunSuite {
  val (uuid1, uuid2, uuid3, uuid4, uuid5, uuid6, uuid7) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

  test("basic") {
    var r = Reputation.init(ReputationConfig(1000000000 msat, 1 second, 2))
    r = r.attempt(uuid1, 10000 msat)
    assert(r.confidence() == 0)
    r = r.record(uuid1, isSuccess = true)
    r = r.attempt(uuid2, 10000 msat)
    assert(r.confidence() === (1.0 / 3) +- 0.001)
    r = r.attempt(uuid3, 10000 msat)
    assert(r.confidence() === (1.0 / 5) +- 0.001)
    r = r.record(uuid2, isSuccess = true)
    r = r.record(uuid3, isSuccess = true)
    assert(r.confidence() == 1)
    r = r.attempt(uuid4, 1 msat)
    assert(r.confidence() === 1.0 +- 0.001)
    r = r.attempt(uuid5, 40000 msat)
    assert(r.confidence() === (3.0 / 11) +- 0.001)
    r = r.attempt(uuid6, 10000 msat)
    assert(r.confidence() === (3.0 / 13) +- 0.001)
    r = r.cancel(uuid5)
    assert(r.confidence() === (3.0 / 5) +- 0.001)
    r = r.record(uuid6, isSuccess = false)
    assert(r.confidence() === (3.0 / 4) +- 0.001)
    r = r.attempt(uuid7, 10000 msat)
    assert(r.confidence() === (3.0 / 6) +- 0.001)
  }

  test("long HTLC") {
    var r = Reputation.init(ReputationConfig(1000000000 msat, 1 second, 10))
    r = r.attempt(uuid1, 100000 msat)
    assert(r.confidence() == 0)
    r = r.record(uuid1, isSuccess = true)
    assert(r.confidence() == 1)
    r = r.attempt(uuid2, 1000 msat, TimestampMilli(0))
    assert(r.confidence(TimestampMilli(0)) === (10.0 / 11) +- 0.001)
    assert(r.confidence(TimestampMilli(0) + 100.seconds) == 0.5)
    r = r.record(uuid2, isSuccess = false, now = TimestampMilli(0) + 100.seconds)
    assert(r.confidence() == 0.5)
  }

  test("max weight") {
    var r = Reputation.init(ReputationConfig(100 msat, 1 second, 10))
    // build perfect reputation
    for(i <- 1 to 100){
      val uuid = UUID.randomUUID()
      r = r.attempt(uuid, 10 msat)
      r = r.record(uuid, isSuccess = true)
    }
    assert(r.confidence() == 1)
    r = r.attempt(uuid1, 1 msat)
    assert(r.confidence() === (100.0 / 110) +- 0.001)
    r = r.record(uuid1, isSuccess = false)
    assert(r.confidence() === (100.0 / 101) +- 0.001)
    r = r.attempt(uuid2, 1 msat)
    assert(r.confidence() === (100.0 / 101) * (100.0 / 110) +- 0.001)
    r = r.record(uuid2, isSuccess = false)
    assert(r.confidence() === (100.0 / 101) * (100.0 / 101) +- 0.001)
    r = r.attempt(uuid3, 1 msat)
    assert(r.confidence() === (100.0 / 101) * (100.0 / 101) * (100.0 / 110) +- 0.001)
    r = r.record(uuid3, isSuccess = false)
    assert(r.confidence() === (100.0 / 101) * (100.0 / 101) * (100.0 / 101) +- 0.001)
  }
}