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

/**
 * Estimates the balance between a pair of nodes
 *
 * @param low           lower bound on the balance
 * @param lowTimestamp  time at which the lower bound was known to be correct
 * @param high          upper bound on the balance
 * @param highTimestamp time at which the upper bound was known to be correct
 * @param totalCapacity total capacity of all the channels between the pair of nodes
 * @param halfLife      time after which the certainty of the lower/upper bounds is halved
 */
case class BalanceEstimate private(low: MilliSatoshi, lowTimestamp: TimestampSecond, high: MilliSatoshi, highTimestamp: TimestampSecond, totalCapacity: Satoshi, halfLife: FiniteDuration) {

  /* The goal of this class is to estimate the probability that a given edge can relay the amount that we plan to send
   * through it. We model this probability with 3 pieces of linear functions.
   *
   * Without any information we use the following baseline (x is the amount we're sending and y the probability it can be relayed):
   *
   *   1 |****
   *     |    ****
   *     |        ****
   *     |            ****
   *     |                ****
   *     |                    ****
   *     |                        ****
   *     |                            ****
   *     |                                ****
   *     |                                    ****
   *     |                                        ****
   *     |                                            ****
   *   0 +------------------------------------------------****
   *     0                                                capacity
   *
   * If we get the information that the edge can (or can't) relay a given amount (because we tried), then we get a lower
   * bound (or upper bound) that we can use and our model becomes:
   *
   *   1 |***************
   *     |              |*
   *     |              | *
   *     |              |  *
   *     |              |   *
   *     |              |    *
   *     |              |     *
   *     |              |      *
   *     |              |       *
   *     |              |        *
   *     |              |         *
   *     |              |          *
   *   0 +--------------|-----------|***********************
   *     0             low     high                      capacity
   *
   * However this lower bound (or upper bound) is only valid at the moment we got that information. If we wait, the
   * information decays and we slowly go back towards our baseline:
   *
   *   1 |*****
   *     |     *****
   *     |          *****
   *     |              |*
   *     |              | *
   *     |              |  *
   *     |              |   *
   *     |              |    *
   *     |              |     *
   *     |              |      *
   *     |              |       **********
   *     |              |       |         **********
   *   0 +--------------|-------|-------------------**********
   *     0             low     high                       capacity
   */

  /**
   * We model the decay with a half-life H: every H units of time, our confidence decreases by half and our estimated
   * probability distribution gets closer to the baseline uniform distribution of balances between 0 and totalCapacity.
   *
   * @param amount                the amount that we knew we could send or not send at time t
   * @param successProbabilityAtT probability that we could relay amount at time t (usually 0 or 1)
   * @param t                     time at which we knew if we could or couldn't send amount
   * @return the probability that we can send amount now
   */
  private def decay(amount: MilliSatoshi, successProbabilityAtT: Double, t: TimestampSecond): Double = {
    val decayRatio = 1 / math.pow(2, (TimestampSecond.now() - t) / halfLife)
    val baseline = 1 - amount.toLong.toDouble / toMilliSatoshi(totalCapacity).toLong.toDouble
    baseline * (1 - decayRatio) + successProbabilityAtT * decayRatio
  }

  def otherSide: BalanceEstimate = BalanceEstimate(totalCapacity - high, highTimestamp, totalCapacity - low, lowTimestamp, totalCapacity, halfLife)

  def couldNotSend(amount: MilliSatoshi, timestamp: TimestampSecond): BalanceEstimate = {
    if (amount <= low) {
      // the balance is actually below `low`, we discard our previous lower bound
      copy(low = MilliSatoshi(0), lowTimestamp = timestamp, high = amount, highTimestamp = timestamp)
    } else if (amount < high) {
      // the balance is actually below `high`, we discard our previous higher bound
      copy(high = amount, highTimestamp = timestamp)
    } else {
      // We already expected not to be able to relay that amount as it above our upper bound. However if the upper bound
      // was old enough that replacing it with the current amount decreases the success probability for `high`, then we
      // replace it.
      val pLow = decay(low, 1, lowTimestamp)
      val pHigh = decay(high, 0, highTimestamp)
      val x = low + (high - low) * (pLow / (pLow - pHigh))
      if (amount <= x) {
        copy(high = amount, highTimestamp = timestamp)
      } else {
        this
      }
    }
  }

  def couldSend(amount: MilliSatoshi, timestamp: TimestampSecond): BalanceEstimate =
    otherSide.couldNotSend(totalCapacity - amount, timestamp).otherSide

  def didSend(amount: MilliSatoshi, timestamp: TimestampSecond): BalanceEstimate = {
    val newLow = (low - amount) max MilliSatoshi(0)
    val newHigh = (high - amount) max MilliSatoshi(0)
    // We could shift everything left by amount without changing the timestamps (a) but we may get more information by
    // ignoring the old high (b) if if has decayed too much. We try both and choose the one that gives the lowest
    // probability for high.
    val a = copy(low = newLow, high = newHigh)
    val b = copy(low = newLow, high = (totalCapacity - amount) max MilliSatoshi(0), highTimestamp = timestamp)
    if (a.canSend(newHigh) < b.canSend(newHigh)) {
      a
    } else {
      b
    }
  }

  def addChannel(capacity: Satoshi): BalanceEstimate = copy(high = high + toMilliSatoshi(capacity), totalCapacity = totalCapacity + capacity)

  def removeChannel(capacity: Satoshi): BalanceEstimate = copy(low = (low - toMilliSatoshi(capacity)) max MilliSatoshi(0), high = high min toMilliSatoshi(totalCapacity - capacity), totalCapacity = totalCapacity - capacity)

  /**
   * Estimate the probability that we can successfully send `amount` through the channel
   *
   * We estimate this probability with a piecewise linear function:
   * - probability that it can relay a payment of 0 is 1
   * - probability that it can relay a payment of low is decay(low, 1, lowTimestamp) which is close to 1 if lowTimestamp is recent
   * - probability that it can relay a payment of high is decay(high, 0, highTimestamp) which is close to 0 if highTimestamp is recent
   * - probability that it can relay a payment of totalCapacity is 0
   */
  def canSend(amount: MilliSatoshi): Double = {
    val a = amount.toLong.toDouble
    val l = low.toLong.toDouble
    val h = high.toLong.toDouble
    val c = toMilliSatoshi(totalCapacity).toLong.toDouble

    // Success probability at the low and high points
    val pLow = decay(low, 1, lowTimestamp)
    val pHigh = decay(high, 0, highTimestamp)

    if (amount < low) {
      (l - a * (1.0 - pLow)) / l
    } else if (amount <= high) {
      ((h - a) * pLow + (a - l) * pHigh) / (h - l)
    } else {
      ((c - a) * pHigh) / (c - h)
    }
  }
}

object BalanceEstimate {
  def baseline(capacity: Satoshi, halfLife: FiniteDuration): BalanceEstimate = BalanceEstimate(MilliSatoshi(0), TimestampSecond(0), MilliSatoshi(0), TimestampSecond(0), Satoshi(0), halfLife).addChannel(capacity)
}

/** A pair of nodes, lexicographically ordered. */
case class OrderedNodePair private(node1: PublicKey, node2: PublicKey)

object OrderedNodePair {
  def create(a: PublicKey, b: PublicKey): OrderedNodePair = if (LexicographicalOrdering.isLessThan(a.value, b.value)) OrderedNodePair(a, b) else OrderedNodePair(b, a)
}

/**
 * Balance estimates for the whole routing graph.
 * Balance estimates are symmetrical: we can compute the balance estimate b -> a based on the balance estimate a -> b,
 * so we only store it for one direction.
 */
case class BalancesEstimates(balances: Map[OrderedNodePair, BalanceEstimate], defaultHalfLife: FiniteDuration) {
  private def get(a: PublicKey, b: PublicKey): Option[BalanceEstimate] = {
    val nodePair = OrderedNodePair.create(a, b)
    if (nodePair.node1 == a) {
      balances.get(nodePair)
    } else {
      balances.get(nodePair).map(_.otherSide)
    }
  }

  def get(edge: GraphEdge): BalanceEstimate = get(edge.desc.a, edge.desc.b).getOrElse(BalanceEstimate.baseline(edge.capacity, defaultHalfLife))

  def addChannel(channel: PublicChannel): BalancesEstimates =
    BalancesEstimates(
      balances.updatedWith(OrderedNodePair.create(channel.ann.nodeId1, channel.ann.nodeId2)) {
        case None => Some(BalanceEstimate.baseline(channel.capacity, defaultHalfLife))
        case Some(balance) => Some(balance)
      },
      defaultHalfLife)

  def removeChannel(channel: PublicChannel): BalancesEstimates =
    BalancesEstimates(
      balances.updatedWith(OrderedNodePair.create(channel.ann.nodeId1, channel.ann.nodeId2)) {
        case None => None
        case Some(balance) =>
          val newBalance = balance.removeChannel(channel.capacity)
          if (newBalance.totalCapacity.toLong > 0) {
            Some(newBalance)
          } else {
            None
          }
      }, defaultHalfLife)

  private def channelXSend(x: (BalanceEstimate, MilliSatoshi, TimestampSecond) => BalanceEstimate, hop: ChannelHop, amount: MilliSatoshi): BalancesEstimates = {
    val nodePair = OrderedNodePair(hop.nodeId, hop.nextNodeId)
    if (nodePair.node1 == hop.nodeId) {
      BalancesEstimates(balances.updatedWith(nodePair)(_.map(b => x(b, amount, TimestampSecond.now()))), defaultHalfLife)
    } else {
      BalancesEstimates(balances.updatedWith(nodePair)(_.map(b => x(b.otherSide, amount, TimestampSecond.now()).otherSide)), defaultHalfLife)
    }
  }

  def channelCouldSend(hop: ChannelHop, amount: MilliSatoshi): BalancesEstimates = {
    get(hop.nodeId, hop.nextNodeId).foreach { balance =>
      val estimatedProbability = balance.canSend(amount)
      Monitoring.Metrics.remoteEdgeRelaySuccess(estimatedProbability)
    }
    channelXSend(_.couldSend(_, _), hop, amount)
  }

  def channelCouldNotSend(hop: ChannelHop, amount: MilliSatoshi): BalancesEstimates = {
    get(hop.nodeId, hop.nextNodeId).foreach { balance =>
      val estimatedProbability = balance.canSend(amount)
      Monitoring.Metrics.remoteEdgeRelayFailure(estimatedProbability)
    }
    channelXSend(_.couldNotSend(_, _), hop, amount)
  }

  def channelDidSend(hop: ChannelHop, amount: MilliSatoshi): BalancesEstimates = {
    get(hop.nodeId, hop.nextNodeId).foreach { balance =>
      val estimatedProbability = balance.canSend(amount)
      Monitoring.Metrics.remoteEdgeRelaySuccess(estimatedProbability)
    }
    channelXSend(_.didSend(_, _), hop, amount)
  }

}

object BalancesEstimates {
  def baseline(graph: DirectedGraph, defaultHalfLife: FiniteDuration): BalancesEstimates = BalancesEstimates(
    graph.edgeSet().foldLeft[Map[OrderedNodePair, BalanceEstimate]](Map.empty) {
      case (m, edge) => m.updatedWith(OrderedNodePair.create(edge.desc.a, edge.desc.b)) {
        case None => Some(BalanceEstimate.baseline(edge.capacity, defaultHalfLife))
        case Some(balance) => Some(balance.addChannel(edge.capacity))
      }
    },
    defaultHalfLife)
}
