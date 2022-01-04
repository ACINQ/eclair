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
import fr.acinq.bitcoin.scalacompat.{LexicographicalOrdering, Satoshi}
import fr.acinq.eclair.MilliSatoshi.toMilliSatoshi
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router.{ChannelHop, PublicChannel}
import fr.acinq.eclair.{MilliSatoshi, TimestampSecond}

import scala.concurrent.duration.FiniteDuration

/** Estimates the balance between a pair of nodes
 *
 * @param low           lower bound on the balance
 * @param lowTimestamp  time at which the lower bound was known to be correct
 * @param high          upper bound on the balance
 * @param highTimestamp time at which the upper bound was known to be correct
 * @param totalCapacity total capacity of all the channels between the pair of nodes
 * @param halfLife      time after which the certainty of the lower/upper bounds is halved
 */
case class BalanceEstimate private(low: MilliSatoshi, lowTimestamp: TimestampSecond, high: MilliSatoshi, highTimestamp: TimestampSecond, totalCapacity: Satoshi, halfLife: FiniteDuration) {

  def otherSide: BalanceEstimate = BalanceEstimate(totalCapacity - high, highTimestamp, totalCapacity - low, lowTimestamp, totalCapacity, halfLife)

  /* When we probe an edge, we get certain information on its balance, we know for sure that it can relay at least X or
   * at most Y. However after some time has passed, other payments may have changed the balance and we can't be so sure
   * anymore.
   * We model this decay with a half-life H: every H units of time, our confidence decreases by half and our estimated
   * probability distribution gets closer to the baseline uniform distribution of balances between 0 and totalCapacity.
   */
  private def decay(amount: MilliSatoshi, successProbabilityAtT: Double, t: TimestampSecond): Double = {
    val decayRatio = 1 / math.pow(2, (TimestampSecond.now() - t) / halfLife)
    val baseline = 1 - amount.toLong.toDouble / toMilliSatoshi(totalCapacity).toLong.toDouble
    baseline * (1 - decayRatio) + successProbabilityAtT * decayRatio
  }

  def couldNotSend(amount: MilliSatoshi, timestamp: TimestampSecond): BalanceEstimate =
    if (amount < high) {
      if (amount > low) {
        copy(high = amount, highTimestamp = timestamp)
      } else {
        // the balance is actually below `low`, we discard our lower bound
        copy(low = MilliSatoshi(0), lowTimestamp = timestamp, high = amount, highTimestamp = timestamp)
      }
    } else {
      /* We already expected not to be able to relay that amount as it above our upper bound. However if the upper bound
       * was old enough that replacing it with the current amount decreases the success probability for `high`, then we
       * replace it.
       */
      val pLow = decay(low, 1, lowTimestamp)
      val pHigh = decay(high, 0, highTimestamp)
      val x = low + (high - low) * (pLow / (pLow - pHigh))
      if (amount <= x) {
        copy(high = amount, highTimestamp = timestamp)
      } else {
        copy()
      }
    }

  def couldSend(amount: MilliSatoshi, timestamp: TimestampSecond): BalanceEstimate =
    otherSide.couldNotSend(totalCapacity - amount, timestamp).otherSide

  def didSend(amount: MilliSatoshi, timestamp: TimestampSecond): BalanceEstimate = {
    val newLow = (low - amount) max MilliSatoshi(0)
    val newHigh = (high - amount) max MilliSatoshi(0)
    val pLow = decay(newLow, 1, lowTimestamp)
    val pHigh = decay(newHigh, 0, highTimestamp)
    if (???) {
      copy(low = newLow, high = (totalCapacity - amount) max MilliSatoshi(0), highTimestamp = timestamp)
    } else {
      copy(low = newLow, high = newHigh)
    }
  }

  def addChannel(capacity: Satoshi): BalanceEstimate = copy(high = high + toMilliSatoshi(capacity), totalCapacity = totalCapacity + capacity)

  def removeChannel(capacity: Satoshi): BalanceEstimate = copy(low = (low - toMilliSatoshi(capacity)) max MilliSatoshi(0), high = high min toMilliSatoshi(totalCapacity - capacity), totalCapacity = totalCapacity - capacity)

  /* Estimate the probability that we can successfully send `amount` through the channel
   *
   * We estimate this probability with a piecewise linear function:
   * - probability that it can relay a payment of 0 is 1
   * - probability that it can relay a payment of low is close to 1 if lowTimestamp is recent
   * - probability that it can relay a payment of high is close to 0 if highTimestamp is recent
   * - probability that it can relay a payment of totalCapacity is 0
   */
  def canSend(amount: MilliSatoshi): Double = {
    val x = amount.toLong.toDouble
    val a = low.toLong.toDouble
    val b = high.toLong.toDouble
    val c = toMilliSatoshi(totalCapacity).toLong.toDouble

    // Success probability at the low and high points
    val pLow = decay(low, 1, lowTimestamp)
    val pHigh = decay(high, 0, highTimestamp)

    if (amount < low) {
      ((a - x) + x * pLow) / a
    } else if (amount <= high) {
      ((b - x) * pLow + (x - a) * pHigh) / (b - a)
    } else {
      ((c - x) * pHigh) / (c - b)
    }
  }
}

object BalanceEstimate {
  def noChannels(halfLife: FiniteDuration): BalanceEstimate = BalanceEstimate(MilliSatoshi(0), TimestampSecond(0), MilliSatoshi(0), TimestampSecond(0), Satoshi(0), halfLife)

  def baseline(capacity: Satoshi, halfLife: FiniteDuration): BalanceEstimate = noChannels(halfLife).addChannel(capacity)
}

case class BalancesEstimates(balances: Map[(PublicKey, PublicKey), BalanceEstimate], defaultHalfLife: FiniteDuration) {
  def get(edge: GraphEdge): BalanceEstimate =
    if (LexicographicalOrdering.isLessThan(edge.desc.a.value, edge.desc.b.value)) {
      balances.getOrElse((edge.desc.a, edge.desc.b), BalanceEstimate.baseline(edge.capacity, defaultHalfLife))
    } else {
      balances.getOrElse((edge.desc.b, edge.desc.a), BalanceEstimate.baseline(edge.capacity, defaultHalfLife)).otherSide
    }

  def addChannel(channel: PublicChannel): BalancesEstimates =
    BalancesEstimates(
      balances.updatedWith((channel.ann.nodeId1, channel.ann.nodeId2))(opt => Some(opt.getOrElse(BalanceEstimate.noChannels(defaultHalfLife).addChannel(channel.capacity)))),
      defaultHalfLife)

  def removeChannel(channel: PublicChannel): BalancesEstimates =
    BalancesEstimates(
      balances.updatedWith((channel.ann.nodeId1, channel.ann.nodeId2)) {
        case None => None
        case Some(balance) =>
          val newBalance = balance.removeChannel(channel.capacity)
          if (newBalance.totalCapacity.toLong > 0) {
            Some(newBalance)
          } else {
            None
          }
      }, defaultHalfLife)

  private def channelXSend(x: (BalanceEstimate, MilliSatoshi, TimestampSecond) => BalanceEstimate, hop: ChannelHop, amount: MilliSatoshi): BalancesEstimates =
    if (LexicographicalOrdering.isLessThan(hop.nodeId.value, hop.nextNodeId.value)) {
      BalancesEstimates(balances.updatedWith((hop.nodeId, hop.nextNodeId))(_.map(x(_, amount, TimestampSecond.now()))), defaultHalfLife)
    } else {
      BalancesEstimates(balances.updatedWith((hop.nextNodeId, hop.nodeId))(_.map(b => x(b.otherSide, amount, TimestampSecond.now()).otherSide)), defaultHalfLife)
    }

  def channelCouldSend(hop: ChannelHop, amount: MilliSatoshi): BalancesEstimates =
    channelXSend(_.couldSend(_, _), hop, amount)

  def channelCouldNotSend(hop: ChannelHop, amount: MilliSatoshi): BalancesEstimates =
    channelXSend(_.couldNotSend(_, _), hop, amount)

  def channelDidSend(hop: ChannelHop, amount: MilliSatoshi): BalancesEstimates =
    channelXSend(_.didSend(_, _), hop, amount)

}

object BalancesEstimates {
  def baseline(graph: DirectedGraph, defaultHalfLife: FiniteDuration): BalancesEstimates = BalancesEstimates(
    graph.edgeSet().foldLeft[Map[(PublicKey, PublicKey), BalanceEstimate]](Map.empty) {
      case (m, edge) => m.updatedWith(if (LexicographicalOrdering.isLessThan(edge.desc.a.value, edge.desc.b.value)) (edge.desc.a, edge.desc.b) else (edge.desc.b, edge.desc.a)) {
        case None => Some(BalanceEstimate.baseline(edge.capacity, defaultHalfLife))
        case Some(balance) => Some(balance.addChannel(edge.capacity))
      }
    },
    defaultHalfLife)
}
