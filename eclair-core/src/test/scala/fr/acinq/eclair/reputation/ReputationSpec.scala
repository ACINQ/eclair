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

import fr.acinq.eclair.reputation.Reputation._
import fr.acinq.eclair.{MilliSatoshiLong, TimestampMilli, randomBytes32}
import org.scalactic.Tolerance.convertNumericToPlusOrMinusWrapper
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

class ReputationSpec extends AnyFunSuite {
  val (htlcId1, htlcId2, htlcId3, htlcId4, htlcId5, htlcId6, htlcId7) = (HtlcId(randomBytes32(), 1), HtlcId(randomBytes32(), 2), HtlcId(randomBytes32(), 3), HtlcId(randomBytes32(), 4), HtlcId(randomBytes32(), 5), HtlcId(randomBytes32(), 6), HtlcId(randomBytes32(), 7))

  test("basic") {
    val r0 = Reputation.init(Config(enabled = true, 1 day, 1 second, 2))
    assert(r0.getConfidence(10000 msat) == 0)
    val r1 = r0.attempt(htlcId1, 10000 msat)
    val r2 = r1.record(htlcId1, isSuccess = true)
    val r3 = r2.attempt(htlcId2, 10000 msat)
    assert(r2.getConfidence(10000 msat) === (1.0 / 3) +- 0.001)
    val r4 = r3.attempt(htlcId3, 10000 msat)
    assert(r3.getConfidence(10000 msat) === (1.0 / 5) +- 0.001)
    val r5 = r4.record(htlcId2, isSuccess = true)
    val r6 = r5.record(htlcId3, isSuccess = true)
    val r7 = r6.attempt(htlcId4, 1 msat)
    assert(r6.getConfidence(1 msat) === 1.0 +- 0.001)
    val r8 = r7.attempt(htlcId5, 40000 msat)
    assert(r7.getConfidence(40000 msat) === (3.0 / 11) +- 0.001)
    val r9 = r8.attempt(htlcId6, 10000 msat)
    assert(r8.getConfidence(10000 msat) === (3.0 / 13) +- 0.001)
    val r10 = r9.record(htlcId6, isSuccess = false)
    assert(r10.getConfidence(10000 msat) === (3.0 / 13) +- 0.001)
  }

  test("long HTLC") {
    val r0 = Reputation.init(Config(enabled = true, 1000 day, 1 second, 10))
    assert(r0.getConfidence(100000 msat, TimestampMilli(0)) == 0)
    val r1 = r0.attempt(htlcId1, 100000 msat, TimestampMilli(0))
    val r2 = r1.record(htlcId1, isSuccess = true, now = TimestampMilli(0))
    assert(r2.getConfidence(1000 msat, TimestampMilli(0)) === (10.0 / 11) +- 0.001)
    val r3 = r2.attempt(htlcId2, 1000 msat, TimestampMilli(0))
    val r4 = r3.record(htlcId2, isSuccess = false, now = TimestampMilli(0) + 100.seconds)
    assert(r4.getConfidence(0 msat, now = TimestampMilli(0) + 100.seconds) === 0.5 +- 0.001)
  }

  test("exponential decay") {
    val r0 = Reputation.init(Config(enabled = true, 100 seconds, 1 second, 1))
    val r1 = r0.attempt(htlcId1, 1000 msat, TimestampMilli(0))
    val r2 = r1.record(htlcId1, isSuccess = true, now = TimestampMilli(0))
    assert(r2.getConfidence(1000 msat, TimestampMilli(0)) == 1.0 / 2)
    val r3 = r2.attempt(htlcId2, 1000 msat, TimestampMilli(0))
    val r4 = r3.record(htlcId2, isSuccess = true, now = TimestampMilli(0))
    assert(r4.getConfidence(1000 msat, TimestampMilli(0)) == 2.0 / 3)
    val r5 = r4.attempt(htlcId3, 1000 msat, TimestampMilli(0))
    val r6 = r5.record(htlcId3, isSuccess = true, now = TimestampMilli(0))
    assert(r6.getConfidence(1000 msat, TimestampMilli(0) + 100.seconds) == 1.5 / 2.5)
    assert(r6.getConfidence(1000 msat, TimestampMilli(0) + 1.hour) < 0.000001)
  }
}