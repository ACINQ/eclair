/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.router

import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.{MilliSatoshiLong, TimestampSecond, TimestampSecondLong}
import org.scalactic.Tolerance.convertNumericToPlusOrMinusWrapper
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

class BalanceEstimateSpec extends AnyFunSuite {
  def isValid(balance: BalanceEstimate): Boolean = {
    balance.low >= 0.msat &&
      balance.low < balance.high &&
      balance.high <= balance.totalCapacity
  }

  test("symmetry") {
    var balanceA = BalanceEstimate.baseline(40000 sat, 1 day)
    var balanceB = balanceA.otherSide
    assert(isValid(balanceA))
    assert(isValid(balanceB))
    assert(balanceB.otherSide === balanceA)

    balanceA = balanceA.couldNotSend(35000000 msat, 1000 unixsec)
    balanceB = balanceB.couldSend(balanceB.totalCapacity - 35000000.msat, 1000 unixsec)
    assert(isValid(balanceA))
    assert(isValid(balanceB))
    assert(balanceB.otherSide === balanceA)

    balanceA = balanceA.couldSend(10000000 msat, 2000 unixsec)
    balanceB = balanceB.couldNotSend(balanceB.totalCapacity - 10000000.msat, 2000 unixsec)
    assert(isValid(balanceA))
    assert(isValid(balanceB))
    assert(balanceB.otherSide === balanceA)

    balanceA = balanceA.addChannel(5000 sat)
    balanceB = balanceB.addChannel(5000 sat)
    assert(isValid(balanceA))
    assert(isValid(balanceB))
    assert(balanceB.otherSide === balanceA)

    balanceA = balanceA.couldSend(balanceA.totalCapacity - 1000000.msat, 15000 unixsec)
    balanceB = balanceB.couldNotSend(1000000 msat, 15000 unixsec)
    assert(isValid(balanceA))
    assert(isValid(balanceB))
    assert(balanceB.otherSide === balanceA)

    balanceA = balanceA.removeChannel(5000 sat)
    balanceB = balanceB.removeChannel(5000 sat)
    assert(isValid(balanceA))
    assert(isValid(balanceB))
    assert(balanceB.otherSide === balanceA)
  }

  test("no balance information") {
    val balance = BalanceEstimate.baseline(100 sat, 1 day)
    assert(balance.canSend(0 msat) === 1.0 +- 0.001)
    assert(balance.canSend(1 msat) === 1.0 +- 0.001)
    assert(balance.canSend(50000 msat) === 0.5 +- 0.001)
    assert(balance.canSend(99999 msat) === 0.0 +- 0.001)
    assert(balance.canSend(100000 msat) === 0.0 +- 0.001)
  }

  test("can send balance info bounds") {
    val now = TimestampSecond.now()
    val balance =
      BalanceEstimate.baseline(100 sat, 1 day)
        .couldSend(24000 msat, now)
        .couldNotSend(30000 msat, now)
    assert(balance.canSend(0 msat) === 1.0 +- 0.001)
    assert(balance.canSend(1 msat) === 1.0 +- 0.001)
    assert(balance.canSend(23999 msat) === 1.0 +- 0.001)
    assert(balance.canSend(24000 msat) === 1.0 +- 0.001)
    assert(balance.canSend(24001 msat) === 1.0 +- 0.001)
    assert(balance.canSend(27000 msat) === 0.5 +- 0.001)
    assert(balance.canSend(29999 msat) === 0.0 +- 0.001)
    assert(balance.canSend(30000 msat) === 0.0 +- 0.001)
    assert(balance.canSend(30001 msat) === 0.0 +- 0.001)
    assert(balance.canSend(99999 msat) === 0.0 +- 0.001)
    assert(balance.canSend(100000 msat) === 0.0 +- 0.001)
  }

  test("could and couldn't send at the same time") {
    val now = TimestampSecond.now()
    val balance =
      BalanceEstimate.baseline(100 sat, 1 day)
        .couldSend(26000 msat, now)
        .couldNotSend(26000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(0 msat) === 1.0 +- 0.001)
    assert(balance.canSend(1 msat) === 1.0 +- 0.001)
    assert(balance.canSend(26000 msat) >= 0)
    assert(balance.canSend(26000 msat) <= 1)
    assert(balance.canSend(99999 msat) === 0.0 +- 0.001)
    assert(balance.canSend(100000 msat) === 0.0 +- 0.001)
  }

  test("couldn't and could send at the same time") {
    val now = TimestampSecond.now()
    val balance =
      BalanceEstimate.baseline(100 sat, 1 day)
        .couldNotSend(26000 msat, now)
        .couldSend(26000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(0 msat) === 1.0 +- 0.001)
    assert(balance.canSend(1 msat) === 1.0 +- 0.001)
    assert(balance.canSend(26000 msat) >= 0)
    assert(balance.canSend(26000 msat) <= 1)
    assert(balance.canSend(99999 msat) === 0.0 +- 0.001)
    assert(balance.canSend(100000 msat) === 0.0 +- 0.001)
  }

  test("decay") {
    val longAgo = TimestampSecond.now() - 1.day
    val balance =
      BalanceEstimate.baseline(100 sat, 1 second)
        .couldNotSend(32000 msat, longAgo)
        .couldSend(28000 msat, longAgo)
    assert(isValid(balance))
    assert(balance.canSend(1 msat) === 1.0 +- 0.01)
    assert(balance.canSend(33333 msat) === 0.666 +- 0.01)
    assert(balance.canSend(66666 msat) === 0.333 +- 0.01)
    assert(balance.canSend(99999 msat) === 0.0 +- 0.01)
  }

  test("sending shifts amounts") {
    val now = TimestampSecond.now()
    val balance =
      BalanceEstimate.baseline(100 sat, 1 day)
        .couldNotSend(80000 msat, now)
        .couldSend(50000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(50000 msat) === 1.0 +- 0.001)
    assert(balance.canSend(80000 msat) === 0.0 +- 0.001)
    val balanceAfterSend = balance.didSend(20000 msat, now)
    assert(isValid(balanceAfterSend))
    assert(balanceAfterSend.canSend(30000 msat) === 1.0 +- 0.001)
    assert(balanceAfterSend.canSend(60000 msat) === 0.0 +- 0.001)
  }

  test("sending after decay") {
    val longAgo = TimestampSecond.now() - 1.day
    val now = TimestampSecond.now()
    val balance =
      BalanceEstimate.baseline(100 sat, 1 second)
        .couldNotSend(80000 msat, longAgo)
        .couldSend(50000 msat, longAgo)
        .didSend(40000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(60000 msat) === 0.0 +- 0.01)
  }
}
