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
import fr.acinq.eclair.payment.Invoice
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router.{ChannelDesc, HopRelayParams}
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshiLong, RealShortChannelId, ShortChannelId, TimestampSecond, randomKey}
import org.scalactic.Tolerance.convertNumericToPlusOrMinusWrapper
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

class BalanceEstimateSpec extends AnyFunSuite {

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  def isValid(balance: BalanceEstimate): Boolean = {
    balance.low >= 0.msat &&
      balance.low <= balance.high &&
      balance.high <= balance.maxCapacity
  }

  def makeEdge(nodeId1: PublicKey, nodeId2: PublicKey, channelId: Long, capacity: Satoshi): GraphEdge =
    GraphEdge(
      ChannelDesc(ShortChannelId(channelId), nodeId1, nodeId2),
      HopRelayParams.FromHint(Invoice.ExtraEdge(nodeId1, nodeId2, ShortChannelId(channelId), 0 msat, 0, CltvExpiryDelta(0), 0 msat, None)),
      capacity, None)

  def makeEdge(channelId: Long, capacity: Satoshi): GraphEdge =
    makeEdge(randomKey().publicKey, randomKey().publicKey, channelId, capacity)

  test("no balance information") {
    val balance = BalanceEstimate.empty(1 day).addEdge(makeEdge(0, 100 sat))
    val now = TimestampSecond.now()
    assert(isValid(balance))
    assert(balance.canSend(0 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(1 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(25000 msat, now) === 0.75 +- 0.001)
    assert(balance.canSend(50000 msat, now) === 0.5 +- 0.001)
    assert(balance.canSend(75000 msat, now) === 0.25 +- 0.001)
    assert(balance.canSend(99999 msat, now) === 0.0 +- 0.001)
    assert(balance.canSend(100000 msat, now) === 0.0 +- 0.001)
  }

  test("add and remove channels") {
    val a = makeEdge(0, 200 sat)
    val b = makeEdge(1, 100 sat)
    val c = makeEdge(2, 800 sat)
    val d = makeEdge(3, 120 sat)
    val e = makeEdge(4, 190 sat)
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
    val balance2 = balance1
      .removeEdge(d.desc)
      .removeEdge(e.desc)
    assert(isValid(balance2))
    assert(balance2.maxCapacity == 0.sat)
    assert(balance2.capacities.isEmpty)
  }

  test("update channels after a splice") {
    val a = makeEdge(0, 200 sat)
    val b = makeEdge(1, 100 sat)
    val unknownDesc = ChannelDesc(ShortChannelId(3), randomKey().publicKey, randomKey().publicKey)
    val balance = BalanceEstimate.empty(1 day)
      .addEdge(a)
      .addEdge(b)
      .couldNotSend(140_000 msat, TimestampSecond.now())
      .couldSend(60_000 msat, TimestampSecond.now())

    // a splice-in that increases channel capacity increases high but not low bounds
    val balance1 = balance.updateEdge(a.desc, RealShortChannelId(5), 250 sat)
    assert(balance1.maxCapacity == 250.sat)
    assert(balance1.low == 60_000.msat)
    assert(balance1.high == 190_000.msat)

    // a splice-in that increases channel capacity of smaller channel does not increase high more than max capacity
    val balance2 = balance
      .updateEdge(b.desc, RealShortChannelId(5), 300 sat)
    assert(balance2.maxCapacity == 300.sat)
    assert(balance2.low == 60_000.msat)
    assert(balance2.high == 300_000.msat)

    // a splice-out that decreases channel capacity decreases low bounds but not high bounds
    val balance3 = balance
      .updateEdge(a.desc, RealShortChannelId(5), 150 sat)
    assert(balance3.maxCapacity == 150.sat)
    assert(balance3.low == 10_000.msat)
    assert(balance3.high == 140_000.msat)

    // a splice-out that decreases channel capacity of largest channel does not decrease low bounds below zero
    val balance4 = balance
      .updateEdge(a.desc, RealShortChannelId(5), 50 sat)
    assert(balance4.maxCapacity == 100.sat)
    assert(balance4.low == 0.msat)
    assert(balance4.high == 100_000.msat)

    // a splice-out that does not decrease the largest channel only decreases low bounds
    val balance5 = balance
      .updateEdge(b.desc, RealShortChannelId(5), 50 sat)
    assert(balance5.maxCapacity == 200.sat)
    assert(balance5.low == 10_000.msat)
    assert(balance5.high == 140_000.msat)

    // a splice of an unknown channel that increases max capacity does not change the low/high bounds
    val balance6 = balance
      .updateEdge(unknownDesc, RealShortChannelId(5), 900 sat)
    assert(isValid(balance6))
    assert(balance6.maxCapacity == 900.sat)
    assert(balance6.low == 60_000.msat)
    assert(balance6.high == 140_000.msat)

    // a splice of an unknown channel below max capacity does not change max capacity or low/high bounds
    val balance7 = balance
      .updateEdge(unknownDesc, RealShortChannelId(5), 150 sat)
    assert(isValid(balance7))
    assert(balance7.maxCapacity == 200.sat)
    assert(balance7.low == 60_000.msat)
    assert(balance7.high == 140_000.msat)
  }

  test("update bounds based on what could then could not be sent (increasing amounts)") {
    val now = TimestampSecond.now()
    val balance = BalanceEstimate.empty(1 day)
      // NB: the number of channels has no impact here
      .addEdge(makeEdge(0, 100 sat))
      .addEdge(makeEdge(1, 100 sat))
      .couldSend(24000 msat, now)
      .couldNotSend(30000 msat, now)
    assert(balance.canSend(0 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(23999 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(24000 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(24001 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(27000 msat, now) === 0.5 +- 0.001)
    assert(balance.canSend(29999 msat, now) === 0.0 +- 0.001)
    assert(balance.canSend(30000 msat, now) === 0.0 +- 0.001)
    assert(balance.canSend(30001 msat, now) === 0.0 +- 0.001)
    assert(balance.canSend(100000 msat, now) === 0.0 +- 0.001)
  }

  test("update bounds based on what could then could not be sent (decreasing amounts)") {
    val now = TimestampSecond.now()
    val balance = BalanceEstimate.empty(1 day)
      // NB: the number of channels has no impact here
      .addEdge(makeEdge(0, 75 sat))
      .addEdge(makeEdge(1, 100 sat))
      .couldSend(26000 msat, now)
      .couldNotSend(14000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(0 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(1 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(7000 msat, now) === 0.5 +- 0.001)
    assert(balance.canSend(14000 msat, now) === 0.0 +- 0.001)
    assert(balance.canSend(26000 msat, now) === 0.0 +- 0.001)
    assert(balance.canSend(99999 msat, now) === 0.0 +- 0.001)
    assert(balance.canSend(100000 msat, now) === 0.0 +- 0.001)
  }

  test("update bounds based on what could not then could be sent (increasing amounts)") {
    val now = TimestampSecond.now()
    val balance = BalanceEstimate.empty(1 day)
      // NB: the number of channels has no impact here
      .addEdge(makeEdge(0, 100 sat))
      .addEdge(makeEdge(1, 50 sat))
      .couldNotSend(26000 msat, now)
      .couldSend(30000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(0 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(1 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(30000 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(65000 msat, now) === 0.5 +- 0.001)
    assert(balance.canSend(99999 msat, now) === 0.0 +- 0.001)
    assert(balance.canSend(100000 msat, now) === 0.0 +- 0.001)
  }

  test("update bounds based on what could not then could be sent (decreasing amounts)") {
    val now = TimestampSecond.now()
    val balance = BalanceEstimate.empty(1 day)
      // NB: the number of channels has no impact here
      .addEdge(makeEdge(0, 100 sat))
      .addEdge(makeEdge(1, 50 sat))
      .couldNotSend(30000 msat, now)
      .couldSend(20000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(0 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(1 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(20000 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(25000 msat, now) === 0.5 +- 0.001)
    assert(balance.canSend(30000 msat, now) === 0.0 +- 0.001)
    assert(balance.canSend(99999 msat, now) === 0.0 +- 0.001)
    assert(balance.canSend(100000 msat, now) === 0.0 +- 0.001)
  }

  test("decay restores baseline bounds") {
    val now = TimestampSecond.now()
    val longAgo = now - 30.seconds
    val balance = BalanceEstimate.empty(1 second)
      .addEdge(makeEdge(0, 100 sat))
      .couldNotSend(32000 msat, longAgo)
      .couldSend(28000 msat, longAgo)
    assert(isValid(balance))
    assert(balance.canSend(1 msat, now) === 1.0 +- 0.01)
    assert(balance.canSend(33333 msat, now) === 0.666 +- 0.01)
    assert(balance.canSend(66666 msat, now) === 0.333 +- 0.01)
    assert(balance.canSend(99999 msat, now) === 0.0 +- 0.01)
  }

  test("sending on single channel shifts amounts") {
    val now = TimestampSecond.now()
    val balance = BalanceEstimate.empty(1 day)
      .addEdge(makeEdge(0, 100 sat))
      .couldNotSend(80000 msat, now)
      .couldSend(50000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(50000 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(80000 msat, now) === 0.0 +- 0.001)
    val balanceAfterSend = balance.didSend(20000 msat, now)
    assert(isValid(balanceAfterSend))
    assert(balanceAfterSend.canSend(30000 msat, now) === 1.0 +- 0.001)
    assert(balanceAfterSend.canSend(45000 msat, now) === 0.5 +- 0.001)
    assert(balanceAfterSend.canSend(60000 msat, now) === 0.0 +- 0.001)
  }

  test("sending on single channel after decay") {
    val longAgo = TimestampSecond.now() - 60.seconds
    val now = TimestampSecond.now()
    val balance = BalanceEstimate.empty(1 second)
      .addEdge(makeEdge(0, 100 sat))
      .couldNotSend(80000 msat, longAgo)
      .couldSend(50000 msat, longAgo)
      .didSend(40000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(0 msat, now) === 1.0 +- 0.01)
    assert(balance.canSend(10000 msat, now) <= 0.9)
    assert(balance.canSend(50000 msat, now) >= 0.1)
    assert(balance.canSend(60000 msat, now) === 0.0 +- 0.01)
  }

  test("sending on parallel channels shifts low only") {
    val now = TimestampSecond.now()
    val balance = BalanceEstimate.empty(1 day)
      .addEdge(makeEdge(0, 100 sat))
      .addEdge(makeEdge(1, 80 sat))
      .couldNotSend(80000 msat, now)
      .couldSend(50000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(50000 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(80000 msat, now) === 0.0 +- 0.001)
    val balanceAfterSend = balance.didSend(20000 msat, now)
    assert(isValid(balanceAfterSend))
    assert(balanceAfterSend.canSend(30000 msat, now) === 1.0 +- 0.001)
    assert(balanceAfterSend.canSend(70000 msat, now) > 0.1)
  }

  test("receiving on single channel shifts amounts") {
    val now = TimestampSecond.now()
    val balance = BalanceEstimate.empty(1 day)
      .addEdge(makeEdge(0, 100 sat))
      .couldNotSend(80000 msat, now)
      .couldSend(50000 msat, now)
      .didReceive(10000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(60000 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(75000 msat, now) === 0.5 +- 0.001)
    assert(balance.canSend(90000 msat, now) === 0.0 +- 0.001)
  }

  test("receiving on single channel after decay") {
    val longAgo = TimestampSecond.now() - 60.seconds
    val now = TimestampSecond.now()
    val balance = BalanceEstimate.empty(1 second)
      .addEdge(makeEdge(0, 100 sat))
      .couldNotSend(80000 msat, longAgo)
      .couldSend(50000 msat, longAgo)
      .didReceive(10000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(10000 msat, now) >= 0.9)
    assert(balance.canSend(20000 msat, now) <= 0.9)
    assert(balance.canSend(80000 msat, now) >= 0.1)
    assert(balance.canSend(90000 msat, now) <= 0.1)
  }

  test("receiving on parallel channels shifts high only") {
    val now = TimestampSecond.now()
    val balance = BalanceEstimate.empty(1 day)
      .addEdge(makeEdge(0, 100 sat))
      .addEdge(makeEdge(1, 80 sat))
      .couldNotSend(70000 msat, now)
      .couldSend(50000 msat, now)
      .didReceive(20000 msat, now)
    assert(isValid(balance))
    assert(balance.canSend(50000 msat, now) === 1.0 +- 0.001)
    assert(balance.canSend(70000 msat, now) === 0.5 +- 0.001)
    assert(balance.canSend(90000 msat, now) === 0.0 +- 0.001)
  }

  test("baseline from graph") {
    val (a, b, c) = (randomKey().publicKey, randomKey().publicKey, randomKey().publicKey)
    val g = DirectedGraph(Seq(
      makeEdge(a, b, 1, 100 sat),
      makeEdge(b, a, 1, 100 sat),
      makeEdge(a, b, 2, 110 sat),
      makeEdge(b, a, 3, 120 sat),
      makeEdge(a, c, 4, 130 sat),
      makeEdge(c, a, 4, 130 sat),
      makeEdge(a, c, 5, 140 sat),
      makeEdge(c, a, 5, 140 sat),
      makeEdge(b, c, 6, 150 sat),
    ))

    val graphWithBalances = GraphWithBalanceEstimates(g, 1 day)
    val now = TimestampSecond.now()
    // NB: it doesn't matter which edge is selected, the balance estimation takes all existing edges into account.
    val edge_ab = makeEdge(a, b, 1, 10 sat)
    val edge_ba = makeEdge(b, a, 1, 10 sat)
    val edge_bc = makeEdge(b, c, 6, 10 sat)
    assert(graphWithBalances.balances.get(edge_ab).canSend(27500 msat, now) === 0.75 +- 0.01)
    assert(graphWithBalances.balances.get(edge_ab).canSend(55000 msat, now) === 0.5 +- 0.01)
    assert(graphWithBalances.balances.get(edge_ba).canSend(30000 msat, now) === 0.75 +- 0.01)
    assert(graphWithBalances.balances.get(edge_ba).canSend(60000 msat, now) === 0.5 +- 0.01)
    assert(graphWithBalances.balances.get(edge_bc).canSend(75000 msat, now) === 0.5 +- 0.01)
    assert(graphWithBalances.balances.get(edge_bc).canSend(100000 msat, now) === 0.33 +- 0.01)
    val unknownEdge = makeEdge(42, 40 sat)
    assert(graphWithBalances.balances.get(unknownEdge).canSend(10000 msat, now) === 0.75 +- 0.01)
    assert(graphWithBalances.balances.get(unknownEdge).canSend(20000 msat, now) === 0.5 +- 0.01)
    assert(graphWithBalances.balances.get(unknownEdge).canSend(30000 msat, now) === 0.25 +- 0.01)
  }

}
