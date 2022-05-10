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
import fr.acinq.bitcoin.scalacompat.Satoshi
import fr.acinq.eclair.MilliSatoshi.toMilliSatoshi
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router.{ChannelDesc, ChannelHop}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId, TimestampSecond}

import scala.concurrent.duration.FiniteDuration

/**
 * Estimates the balance between a pair of nodes
 *
 * @param low           lower bound on the balance
 * @param lowTimestamp  time at which the lower bound was known to be correct
 * @param high          upper bound on the balance
 * @param highTimestamp time at which the upper bound was known to be correct
 * @param capacities    capacities of the channels between these two nodes
 * @param halfLife      time after which the certainty of the lower/upper bounds is halved
 */
case class BalanceEstimate private(low: MilliSatoshi,
                                   lowTimestamp: TimestampSecond,
                                   high: MilliSatoshi, highTimestamp: TimestampSecond,
                                   capacities: Map[ShortChannelId, Satoshi],
                                   halfLife: FiniteDuration) {
  val maxCapacity: Satoshi = capacities.values.maxOption.getOrElse(Satoshi(0))

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
   *   0 +--------------|-----------|*************************
   *     0             low         high                   capacity
   *
   * However this lower bound (or upper bound) is only valid at the moment we got that information. If we wait, the
   * information decays and we slowly go back towards our baseline:
   *
   *   1 |*****
   *     |     *****
   *     |          *****
   *     |              |**
   *     |              |  *
   *     |              |   **
   *     |              |     **
   *     |              |       *
   *     |              |        **
   *     |              |          *
   *     |              |           ********
   *     |              |           |       *********
   *   0 +--------------|-----------|----------------*********
   *     0             low         high                   capacity
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
    val baseline = 1 - amount.toLong.toDouble / toMilliSatoshi(maxCapacity).toLong.toDouble
    baseline * (1 - decayRatio) + successProbabilityAtT * decayRatio
  }

  private def otherSide: BalanceEstimate =
    BalanceEstimate(maxCapacity - high, highTimestamp, maxCapacity - low, lowTimestamp, capacities, halfLife)

  def couldNotSend(amount: MilliSatoshi, timestamp: TimestampSecond): BalanceEstimate = {
    if (amount <= low) {
      // the balance is actually below `low`, we discard our previous lower bound
      copy(low = MilliSatoshi(0), lowTimestamp = timestamp, high = amount, highTimestamp = timestamp)
    } else if (amount < high) {
      // the balance is between `low` and `high` as we expected, we discard our previous upper bound
      copy(high = amount, highTimestamp = timestamp)
    } else {
      // We already expected not to be able to relay that amount as it above our upper bound. However if the upper bound
      // was old enough that replacing it with the current amount decreases the success probability for `high`, then we
      // replace it.
      val updated = copy(high = amount, highTimestamp = timestamp)
      if (updated.canSend(high) < this.canSend(high)) {
        updated
      } else {
        this
      }
    }
  }

  def couldSend(amount: MilliSatoshi, timestamp: TimestampSecond): BalanceEstimate =
    otherSide.couldNotSend(maxCapacity - amount, timestamp).otherSide

  def didSend(amount: MilliSatoshi, timestamp: TimestampSecond): BalanceEstimate = {
    val newLow = (low - amount) max MilliSatoshi(0)
    if (capacities.size == 1) {
      // Special case for single channel as we expect this case to be quite common and we can easily get more precise bounds.
      val newHigh = (high - amount) max MilliSatoshi(0)
      // We could shift everything left by amount without changing the timestamps (a) but we may get more information by
      // ignoring the old high (b) if if has decayed too much. We try both and choose the one that gives the lowest
      // probability for high.
      val a = copy(low = newLow, high = newHigh)
      val b = copy(low = newLow, high = (maxCapacity - amount) max MilliSatoshi(0), highTimestamp = timestamp)
      if (a.canSend(newHigh) < b.canSend(newHigh)) {
        a
      } else {
        b
      }
    } else {
      copy(low = newLow)
    }
  }

  def addEdge(edge: GraphEdge): BalanceEstimate =
    copy(high = high max toMilliSatoshi(edge.capacity), capacities = capacities.updated(edge.desc.shortChannelId, edge.capacity))

  def removeEdge(desc: ChannelDesc): BalanceEstimate = {
    val edgeCapacity = capacities.getOrElse(desc.shortChannelId, Satoshi(0))
    val newCapacities = capacities.removed(desc.shortChannelId)
    copy(
      low = (low - toMilliSatoshi(edgeCapacity)) max MilliSatoshi(0),
      high = high min toMilliSatoshi(newCapacities.values.maxOption.getOrElse(Satoshi(0))),
      capacities = newCapacities
    )
  }

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
    val c = toMilliSatoshi(maxCapacity).toLong.toDouble

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
  def empty(halfLife: FiniteDuration): BalanceEstimate =
    BalanceEstimate(MilliSatoshi(0), TimestampSecond(0), MilliSatoshi(0), TimestampSecond(0), Map.empty, halfLife)
}

/**
 * Balance estimates for the whole routing graph.
 */
case class BalancesEstimates(balances: Map[(PublicKey, PublicKey), BalanceEstimate], defaultHalfLife: FiniteDuration) {
  private def get(a: PublicKey, b: PublicKey): Option[BalanceEstimate] = balances.get((a, b))

  def get(edge: GraphEdge): BalanceEstimate =
    get(edge.desc.a, edge.desc.b).getOrElse(BalanceEstimate.empty(defaultHalfLife).addEdge(edge))

  def addEdge(edge: GraphEdge): BalancesEstimates =
    BalancesEstimates(
      balances.updatedWith((edge.desc.a, edge.desc.b))(balance =>
        Some(balance.getOrElse(BalanceEstimate.empty(defaultHalfLife)).addEdge(edge))),
      defaultHalfLife)

  def removeEdge(desc: ChannelDesc): BalancesEstimates =
    BalancesEstimates(
      balances.updatedWith((desc.a, desc.b)) {
        case None => None
        case Some(balance) =>
          val newBalance = balance.removeEdge(desc)
          if (newBalance.maxCapacity.toLong > 0) {
            Some(newBalance)
          } else {
            None
          }
      },
      defaultHalfLife)

  def channelCouldSend(hop: ChannelHop, amount: MilliSatoshi): BalancesEstimates = {
    get(hop.nodeId, hop.nextNodeId).foreach { balance =>
      val estimatedProbability = balance.canSend(amount)
      Monitoring.Metrics.remoteEdgeRelaySuccess(estimatedProbability)
    }
    BalancesEstimates(balances.updatedWith((hop.nodeId, hop.nextNodeId))(_.map(_.couldSend(amount, TimestampSecond.now()))), defaultHalfLife)
  }

  def channelCouldNotSend(hop: ChannelHop, amount: MilliSatoshi): BalancesEstimates = {
    get(hop.nodeId, hop.nextNodeId).foreach { balance =>
      val estimatedProbability = balance.canSend(amount)
      Monitoring.Metrics.remoteEdgeRelayFailure(estimatedProbability)
    }
    BalancesEstimates(balances.updatedWith((hop.nodeId, hop.nextNodeId))(_.map(_.couldNotSend(amount, TimestampSecond.now()))), defaultHalfLife)
  }

  def channelDidSend(hop: ChannelHop, amount: MilliSatoshi): BalancesEstimates = {
    get(hop.nodeId, hop.nextNodeId).foreach { balance =>
      val estimatedProbability = balance.canSend(amount)
      Monitoring.Metrics.remoteEdgeRelaySuccess(estimatedProbability)
    }
    BalancesEstimates(balances.updatedWith((hop.nodeId, hop.nextNodeId))(_.map(_.didSend(amount, TimestampSecond.now()))), defaultHalfLife)
  }

}

object BalancesEstimates {
  def baseline(graph: DirectedGraph, defaultHalfLife: FiniteDuration): BalancesEstimates = BalancesEstimates(
    graph.edgeSet().foldLeft[Map[(PublicKey, PublicKey), BalanceEstimate]](Map.empty) {
      case (m, edge) =>
        m.updatedWith((edge.desc.a, edge.desc.b))(balance =>
          Some(balance.getOrElse(BalanceEstimate.empty(defaultHalfLife)).addEdge(edge)))
    },
    defaultHalfLife)
}

case class BalancesAndGraph(balances: BalancesEstimates, graph: DirectedGraph) {
  // Use these functions to ensure that the balances and the graph are always updated together.
  def addEdge(edge: GraphEdge): BalancesAndGraph = BalancesAndGraph(balances.addEdge(edge), graph.addEdge(edge))

  def removeEdge(desc: ChannelDesc): BalancesAndGraph = BalancesAndGraph(balances.removeEdge(desc), graph.removeEdge(desc))
}