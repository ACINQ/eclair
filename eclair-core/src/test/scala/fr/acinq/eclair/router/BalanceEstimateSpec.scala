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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Satoshi, SatoshiLong}
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router.{ChannelDesc, ChannelRelayParams}
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshiLong, ShortChannelId, TimestampSecond, randomKey}
import org.scalactic.Tolerance.convertNumericToPlusOrMinusWrapper
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

class BalanceEstimateSpec extends AnyFunSuite {
  def isValid(balance: BalanceEstimate): Boolean = {
    balance.low >= 0.msat &&
      balance.low <= balance.high &&
      balance.high <= balance.maxCapacity
  }

  def makeEdge(nodeId1: PublicKey, nodeId2: PublicKey, channelId: Long, capacity: Satoshi): GraphEdge =
    GraphEdge(
      ChannelDesc(ShortChannelId(channelId), nodeId1, nodeId2),
      ChannelRelayParams.FromHint(ExtraHop(nodeId1, ShortChannelId(channelId), 0 msat, 0, CltvExpiryDelta(0)), 0 msat),
      capacity, None)

  def makeEdge(channelId: Long, capacity: Satoshi): GraphEdge =
    makeEdge(randomKey().publicKey, randomKey().publicKey, channelId, capacity)

  test("no balance information") {
    val balance = BalanceEstimate.empty(1 day).addEdge(makeEdge(0, 100 sat))
    assert(isValid(balance))
    assert(balance.canSend(0 msat) === 1.0 +- 0.001)
    assert(balance.canSend(1 msat) === 1.0 +- 0.001)
    assert(balance.canSend(50000 msat) === 0.5 +- 0.001)
    assert(balance.canSend(99999 msat) === 0.0 +- 0.001)
    assert(balance.canSend(100000 msat) === 0.0 +- 0.001)
  }

  test("add and remove channels") {
    val (a, b, c, d, e) =
      (makeEdge(0, 200 sat),
        makeEdge(1, 100 sat),
        makeEdge(2, 800 sat),
        makeEdge(3, 120 sat),
        makeEdge(4, 190 sat))
    val balance = BalanceEstimate.empty(1 day)
      .addEdge(a)
      .addEdge(b)
      .removeEdge(a.desc)
    assert(isValid(balance))
    assert(balance.maxCapacity == 100.sat)
    val balance1 = balance
      .addEdge(c)
      .removeEdge(c.desc)
      .removeEdge(b.desc)
      .addEdge(d)
      .addEdge(e)
    assert(isValid(balance1))
    assert(balance1.maxCapacity == 190.sat)
    val balance2 = balance1.removeEdge(d.desc)
      .removeEdge(e.desc)
    assert(isValid(balance2))
    assert(balance2.maxCapacity == 0.sat)
    assert(balance2.capacities.isEmpty)
  }

  test("can send balance info bounds") {
    val now = TimestampSecond.now()
    val balance =
      BalanceEstimate.empty(1 day).addEdge(makeEdge(0, 100 sat))
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
      BalanceEstimate.empty(1 day).addEdge(makeEdge(0, 100 sat))
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
      BalanceEstimate.empty(1 day).addEdge(makeEdge(0, 100 sat))
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
      BalanceEstimate.empty(1 second).addEdge(makeEdge(0, 100 sat))
        .couldNotSend(32000 msat, longAgo)
        .couldSend(28000 msat, longAgo)
    assert(isValid(balance))
    assert(balance.canSend(1 msat) === 1.0 +- 0.01)
    assert(balance.canSend(33333 msat) === 0.666 +- 0.01)
    assert(balance.canSend(66666 msat) === 0.333 +- 0.01)
    assert(balance.canSend(99999 msat) === 0.0 +- 0.01)
  }

  test("sending on single channel shifts amounts") {
    val now = TimestampSecond.now()
    val balance =
      BalanceEstimate.empty(1 day).addEdge(makeEdge(0, 100 sat))
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

  test("sending on single channel after decay") {
    val longAgo = TimestampSecond.now() - 1.day
    val now = TimestampSecond.now()
    val balance =
      BalanceEstimate.empty(1 second).addEdge(makeEdge(0, 100 sat))
        .couldNotSend(80000 msat, longAgo)
        .couldSend(50000 msat, longAgo)
        .didSend(40000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(60000 msat) === 0.0 +- 0.01)
  }

  test("sending on parallel channels shifts low only") {
    val now = TimestampSecond.now()
    val balance =
      BalanceEstimate.empty(1 day)
        .addEdge(makeEdge(0, 100 sat))
        .addEdge(makeEdge(1, 100 sat))
        .couldNotSend(80000 msat, now)
        .couldSend(50000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(50000 msat) === 1.0 +- 0.001)
    assert(balance.canSend(80000 msat) === 0.0 +- 0.001)
    val balanceAfterSend = balance.didSend(20000 msat, now)
    assert(isValid(balanceAfterSend))
    assert(balanceAfterSend.canSend(30000 msat) === 1.0 +- 0.001)
    assert(balanceAfterSend.canSend(60000 msat) > 0.1)
  }

  test("baseline from graph") {
    val (a, b, c) = (randomKey().publicKey, randomKey().publicKey, randomKey().publicKey)
    val g = DirectedGraph(Seq(
      makeEdge(a, b, 1L, 100 sat),
      makeEdge(b, a, 1L, 100 sat),
      makeEdge(a, b, 2L, 110 sat),
      makeEdge(b, a, 3L, 120 sat),
      makeEdge(a, c, 4L, 130 sat),
      makeEdge(c, a, 4L, 130 sat),
      makeEdge(a, c, 5L, 140 sat),
      makeEdge(c, a, 5L, 140 sat),
      makeEdge(b, c, 6L, 150 sat),
    ))

    val balances = BalancesEstimates.baseline(g, 1 day)
    assert(balances.get(makeEdge(a, b, 1L, 100 sat)).canSend(55000 msat) === 0.5 +- 0.01)
  }
}
