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

package fr.acinq.eclair.reputation

import fr.acinq.eclair.reputation.Reputation._
import fr.acinq.eclair.{MilliSatoshiLong, TimestampMilli, randomBytes32}
import org.scalactic.Tolerance.convertNumericToPlusOrMinusWrapper
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

class ReputationSpec extends AnyFunSuite {
  val (htlcId1, htlcId2, htlcId3, htlcId4, htlcId5, htlcId6, htlcId7) = (HtlcId(randomBytes32(), 1), HtlcId(randomBytes32(), 2), HtlcId(randomBytes32(), 3), HtlcId(randomBytes32(), 4), HtlcId(randomBytes32(), 5), HtlcId(randomBytes32(), 6), HtlcId(randomBytes32(), 7))

  test("basic, single endorsement level") {
    val r = Reputation.init(Config(enabled = true, 1 day, 1 second, 2))
    assert(r.getConfidence(10000 msat, 0) == 0)
    r.attempt(htlcId1, 10000 msat, 0)
    r.record(htlcId1, isSuccess = true)
    assert(r.getConfidence(10000 msat, 0) === (1.0 / 3) +- 0.001)
    r.attempt(htlcId2, 10000 msat, 0)
    assert(r.getConfidence(10000 msat, 0) === (1.0 / 5) +- 0.001)
    r.attempt(htlcId3, 10000 msat, 0)
    r.record(htlcId2, isSuccess = true)
    r.record(htlcId3, isSuccess = true)
    assert(r.getConfidence(1 msat, 0) === 1.0 +- 0.001)
    r.attempt(htlcId4, 1 msat, 0)
    assert(r.getConfidence(40000 msat, 0) === (3.0 / 11) +- 0.001)
    r.attempt(htlcId5, 40000 msat, 0)
    assert(r.getConfidence(10000 msat, 0) === (3.0 / 13) +- 0.001)
    r.attempt(htlcId6, 10000 msat, 0)
    r.record(htlcId6, isSuccess = false)
    assert(r.getConfidence(10000 msat, 0) === (3.0 / 13) +- 0.001)
  }

  test("long HTLC, single endorsement level") {
    val r = Reputation.init(Config(enabled = true, 1000 day, 1 second, 10))
    assert(r.getConfidence(100000 msat, 1, TimestampMilli(0)) == 0)
    r.attempt(htlcId1, 100000 msat, 1, TimestampMilli(0))
    r.record(htlcId1, isSuccess = true, now = TimestampMilli(0))
    assert(r.getConfidence(1000 msat, 1, TimestampMilli(0)) === (10.0 / 11) +- 0.001)
    r.attempt(htlcId2, 1000 msat, 1, TimestampMilli(0))
    r.record(htlcId2, isSuccess = false, now = TimestampMilli(0) + 100.seconds)
    assert(r.getConfidence(0 msat, 1, now = TimestampMilli(0) + 100.seconds) === 0.5 +- 0.001)
  }

  test("exponential decay, single endorsement level") {
    val r = Reputation.init(Config(enabled = true, 100 seconds, 1 second, 1))
    r.attempt(htlcId1, 1000 msat, 2, TimestampMilli(0))
    r.record(htlcId1, isSuccess = true, now = TimestampMilli(0))
    assert(r.getConfidence(1000 msat, 2, TimestampMilli(0)) == 1.0 / 2)
    r.attempt(htlcId2, 1000 msat, 2, TimestampMilli(0))
    r.record(htlcId2, isSuccess = true, now = TimestampMilli(0))
    assert(r.getConfidence(1000 msat, 2, TimestampMilli(0)) == 2.0 / 3)
    r.attempt(htlcId3, 1000 msat, 2, TimestampMilli(0))
    r.record(htlcId3, isSuccess = true, now = TimestampMilli(0))
    assert(r.getConfidence(1000 msat, 2, TimestampMilli(0) + 100.seconds) == 1.5 / 2.5)
    assert(r.getConfidence(1000 msat, 2, TimestampMilli(0) + 1.hour) < 0.000001)
  }

  test("multiple endorsement levels") {
    val r = Reputation.init(Config(enabled = true, 1 day, 1 second, 10))
    assert(r.getConfidence(1 msat, 7, TimestampMilli(0)) == 0)
    r.attempt(htlcId1, 100000 msat, 0, TimestampMilli(0))
    r.record(htlcId1, isSuccess = true, TimestampMilli(0))
    r.attempt(htlcId2, 900000 msat, 0, TimestampMilli(0))
    r.record(htlcId2, isSuccess = false, TimestampMilli(1000))
    r.attempt(htlcId3, 50000 msat, 4, TimestampMilli(1000))
    r.record(htlcId3, isSuccess = true, TimestampMilli(1000))
    r.attempt(htlcId4, 50000 msat, 4, TimestampMilli(1000))
    r.record(htlcId4, isSuccess = false, TimestampMilli(2000))
    assert(r.getConfidence(1 msat, 0, TimestampMilli(2000)) === 0.1 +- 0.001)
    assert(r.getConfidence(1 msat, 4, TimestampMilli(2000)) === 0.5 +- 0.001)
    assert(r.getConfidence(1 msat, 7, TimestampMilli(2000)) === 0.5 +- 0.001)
    assert(r.getConfidence(1000 msat, 0, TimestampMilli(2000)) === 0.1 +- 0.001)
    assert(r.getConfidence(1000 msat, 4, TimestampMilli(2000)) === 5.0 / 11 +- 0.001)
    assert(r.getConfidence(1000 msat, 7, TimestampMilli(2000)) === 5.0 / 11 +- 0.001)
    assert(r.getConfidence(100000 msat, 0, TimestampMilli(2000)) === 0.05 +- 0.001)
    assert(r.getConfidence(100000 msat, 4, TimestampMilli(2000)) === 1.0 / 14 +- 0.001)
    assert(r.getConfidence(100000 msat, 7, TimestampMilli(2000)) === 1.0 / 14 +- 0.001)
  }
}