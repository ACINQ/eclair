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
    val r0 = Reputation.init(ReputationConfig(1 day, 1 second, 2))
    val (r1, c1) = r0.attempt(uuid1, 10000 msat)
    assert(c1 == 0)
    val r2 = r1.record(uuid1, isSuccess = true)
    val (r3, c3) = r2.attempt(uuid2, 10000 msat)
    assert(c3 === (1.0 / 3) +- 0.001)
    val (r4, c4) = r3.attempt(uuid3, 10000 msat)
    assert(c4 === (1.0 / 5) +- 0.001)
    val r5 = r4.record(uuid2, isSuccess = true)
    val r6 = r5.record(uuid3, isSuccess = true)
    val (r7, c7) = r6.attempt(uuid4, 1 msat)
    assert(c7 === 1.0 +- 0.001)
    val (r8, c8) = r7.attempt(uuid5, 40000 msat)
    assert(c8 === (3.0 / 11) +- 0.001)
    val (r9, c9) = r8.attempt(uuid6, 10000 msat)
    assert(c9 === (3.0 / 13) +- 0.001)
    val r10 = r9.cancel(uuid5)
    val r11 = r10.record(uuid6, isSuccess = false)
    val (_, c12) = r11.attempt(uuid7, 10000 msat)
    assert(c12 === (3.0 / 6) +- 0.001)
  }

  test("long HTLC") {
    val r0 = Reputation.init(ReputationConfig(1000 day, 1 second, 10))
    val (r1, c1) = r0.attempt(uuid1, 100000 msat, TimestampMilli(0))
    assert(c1 == 0)
    val r2 = r1.record(uuid1, isSuccess = true, now = TimestampMilli(0))
    val (r3, c3) = r2.attempt(uuid2, 1000 msat, TimestampMilli(0))
    assert(c3 === (10.0 / 11) +- 0.001)
    val r4 = r3.record(uuid2, isSuccess = false, now = TimestampMilli(0) + 100.seconds)
    val (_, c5) = r4.attempt(uuid3, 0 msat, now = TimestampMilli(0) + 100.seconds)
    assert(c5 === 0.5 +- 0.001)
  }

  test("exponential decay") {
    val r0 = Reputation.init(ReputationConfig(100 seconds, 1 second, 1))
    val (r1, _) = r0.attempt(uuid1, 1000 msat, TimestampMilli(0))
    val r2 = r1.record(uuid1, isSuccess = true, now = TimestampMilli(0))
    val (r3, c3) = r2.attempt(uuid2, 1000 msat, TimestampMilli(0))
    assert(c3 == 1.0 / 2)
    val r4 = r3.record(uuid2, isSuccess = true, now = TimestampMilli(0))
    val (r5, c5) = r4.attempt(uuid3, 1000 msat, TimestampMilli(0))
    assert(c5 == 2.0 / 3)
    val r6 = r5.record(uuid3, isSuccess = true, now = TimestampMilli(0))
    val (r7, c7) = r6.attempt(uuid4, 1000 msat, TimestampMilli(0) + 100.seconds)
    assert(c7 == 1.5 / 2.5)
    val r8 = r7.cancel(uuid4)
    val (_, c9) = r8.attempt(uuid5, 1000 msat, TimestampMilli(0) + 1.hour)
    assert(c9 < 0.000001)
  }
}