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
import fr.acinq.eclair.wire.protocol.{TlvStream, UpdateAddHtlc}
import fr.acinq.eclair.{BlockHeight, CltvExpiry, MilliSatoshiLong, TimestampMilli, randomBytes32, randomLong}
import org.scalactic.Tolerance.convertNumericToPlusOrMinusWrapper
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

class ReputationSpec extends AnyFunSuite {
  def makeAdd(expiry: CltvExpiry): UpdateAddHtlc = UpdateAddHtlc(randomBytes32(), randomLong(), 100000 msat, randomBytes32(), expiry, null, TlvStream.empty)

  test("basic, single endorsement level") {
    var r = Reputation.init(Config(enabled = true, 1 day, 10 minutes))
    assert(r.getConfidence(10000 msat, 0, BlockHeight(0), CltvExpiry(5)) == 0)
    val add1 = makeAdd(CltvExpiry(5))
    r = r.addPendingHtlc(add1, 10000 msat, 0)
    r = r.settlePendingHtlc(HtlcId(add1), isSuccess = true)
    assert(r.getConfidence(10000 msat, 0, BlockHeight(0), CltvExpiry(2)) === (1.0 / 3) +- 0.001)
    val add2 = makeAdd(CltvExpiry(2))
    r = r.addPendingHtlc(add2, 10000 msat, 0)
    assert(r.getConfidence(10000 msat, 0, BlockHeight(0), CltvExpiry(3)) === (1.0 / 6) +- 0.001)
    val add3 = makeAdd(CltvExpiry(3))
    r = r.addPendingHtlc(add3, 10000 msat, 0)
    r = r.settlePendingHtlc(HtlcId(add2), isSuccess = true)
    r = r.settlePendingHtlc(HtlcId(add3), isSuccess = true)
    assert(r.getConfidence(1 msat, 0, BlockHeight(0), CltvExpiry(4)) === 1.0 +- 0.001)
    val add4 = makeAdd(CltvExpiry(4))
    r = r.addPendingHtlc(add4, 1 msat, 0)
    assert(r.getConfidence(40000 msat, 0, BlockHeight(0), CltvExpiry(2)) === (3.0 / 11) +- 0.001)
    val add5 = makeAdd(CltvExpiry(2))
    r = r.addPendingHtlc(add5, 40000 msat, 0)
    assert(r.getConfidence(10000 msat, 0, BlockHeight(0), CltvExpiry(3)) === (3.0 / 14) +- 0.001)
    val add6 = makeAdd(CltvExpiry(3))
    r = r.addPendingHtlc(add6, 10000 msat, 0)
    r = r.settlePendingHtlc(HtlcId(add6), isSuccess = false)
    assert(r.getConfidence(10000 msat, 0, BlockHeight(0), CltvExpiry(2)) === (3.0 / 13) +- 0.001)
  }

  test("long HTLC, single endorsement level") {
    var r = Reputation.init(Config(enabled = true, 1000 day, 1 minute))
    assert(r.getConfidence(100000 msat, 1, BlockHeight(0), CltvExpiry(6), TimestampMilli(0)) == 0)
    val add1 = makeAdd(CltvExpiry(6))
    r = r.addPendingHtlc(add1, 100000 msat, 1, TimestampMilli(0))
    r = r.settlePendingHtlc(HtlcId(add1), isSuccess = true, now = TimestampMilli(0))
    assert(r.getConfidence(1000 msat, 1, BlockHeight(0), CltvExpiry(1), TimestampMilli(0)) === (10.0 / 11) +- 0.001)
    val add2 = makeAdd(CltvExpiry(1))
    r = r.addPendingHtlc(add2, 1000 msat, 1, TimestampMilli(0))
    r = r.settlePendingHtlc(HtlcId(add2), isSuccess = false, now = TimestampMilli(0) + 100.minutes)
    assert(r.getConfidence(0 msat, 1, BlockHeight(0), CltvExpiry(1), now = TimestampMilli(0) + 100.minutes) === 0.5 +- 0.001)
  }

  test("exponential decay, single endorsement level") {
    var r = Reputation.init(Config(enabled = true, 100 seconds, 10 minutes))
    val add1 = makeAdd(CltvExpiry(2))
    r = r.addPendingHtlc(add1, 1000 msat, 2, TimestampMilli(0))
    r = r.settlePendingHtlc(HtlcId(add1), isSuccess = true, now = TimestampMilli(0))
    assert(r.getConfidence(1000 msat, 2, BlockHeight(0), CltvExpiry(1), TimestampMilli(0)) == 1.0 / 2)
    val add2 = makeAdd(CltvExpiry(2))
    r = r.addPendingHtlc(add2, 1000 msat, 2, TimestampMilli(0))
    r = r.settlePendingHtlc(HtlcId(add2), isSuccess = true, now = TimestampMilli(0))
    assert(r.getConfidence(1000 msat, 2, BlockHeight(0), CltvExpiry(1), TimestampMilli(0)) == 2.0 / 3)
    val add3 = makeAdd(CltvExpiry(2))
    r = r.addPendingHtlc(add3, 1000 msat, 2, TimestampMilli(0))
    r = r.settlePendingHtlc(HtlcId(add3), isSuccess = true, now = TimestampMilli(0))
    assert(r.getConfidence(1000 msat, 2, BlockHeight(0), CltvExpiry(1), TimestampMilli(0) + 100.seconds) == 1.5 / 2.5)
    assert(r.getConfidence(1000 msat, 2, BlockHeight(0), CltvExpiry(1), TimestampMilli(0) + 1.hour) < 0.000001)
  }

  test("multiple endorsement levels") {
    var r = Reputation.init(Config(enabled = true, 1 day, 1 minute))
    assert(r.getConfidence(1 msat, 7, BlockHeight(0), CltvExpiry(1), TimestampMilli(0)) == 0)
    val add1 = makeAdd(CltvExpiry(3))
    r = r.addPendingHtlc(add1, 100000 msat, 0, TimestampMilli(0))
    r = r.settlePendingHtlc(HtlcId(add1), isSuccess = true, TimestampMilli(0))
    val add2 = makeAdd(CltvExpiry(4))
    r = r.addPendingHtlc(add2, 900000 msat, 0, TimestampMilli(0))
    r = r.settlePendingHtlc(HtlcId(add2), isSuccess = false, TimestampMilli(0) + 1.minute)
    val add3 = makeAdd(CltvExpiry(5))
    r = r.addPendingHtlc(add3, 50000 msat, 4, TimestampMilli(0) + 1.minute)
    r = r.settlePendingHtlc(HtlcId(add3), isSuccess = true, TimestampMilli(0) + 1.minute)
    val add4 = makeAdd(CltvExpiry(6))
    r = r.addPendingHtlc(add4, 50000 msat, 4, TimestampMilli(0) + 1.minute)
    r = r.settlePendingHtlc(HtlcId(add4), isSuccess = false, TimestampMilli(0) + 2.minutes)
    assert(r.getConfidence(1 msat, 0, BlockHeight(0), CltvExpiry(1), TimestampMilli(0) + 2.minutes) === 0.1 +- 0.01)
    assert(r.getConfidence(1 msat, 4, BlockHeight(0), CltvExpiry(1), TimestampMilli(0) + 2.minutes) === 0.5 +- 0.01)
    assert(r.getConfidence(1 msat, 7, BlockHeight(0), CltvExpiry(1), TimestampMilli(0) + 2.minutes) === 0.5 +- 0.01)
    assert(r.getConfidence(1000 msat, 0, BlockHeight(0), CltvExpiry(1), TimestampMilli(0) + 2.minutes) === 0.1 +- 0.01)
    assert(r.getConfidence(1000 msat, 4, BlockHeight(0), CltvExpiry(1), TimestampMilli(0) + 2.minutes) === 5.0 / 11 +- 0.01)
    assert(r.getConfidence(1000 msat, 7, BlockHeight(0), CltvExpiry(1), TimestampMilli(0) + 2.minutes) === 5.0 / 11 +- 0.01)
    assert(r.getConfidence(100000 msat, 0, BlockHeight(0), CltvExpiry(1), TimestampMilli(0) + 2.minutes) === 0.05 +- 0.01)
    assert(r.getConfidence(100000 msat, 4, BlockHeight(0), CltvExpiry(1), TimestampMilli(0) + 2.minutes) === 1.0 / 14 +- 0.01)
    assert(r.getConfidence(100000 msat, 7, BlockHeight(0), CltvExpiry(1), TimestampMilli(0) + 2.minutes) === 1.0 / 14 +- 0.01)
  }
}