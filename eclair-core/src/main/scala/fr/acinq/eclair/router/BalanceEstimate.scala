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

import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Satoshi, SatoshiLong}
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router.{ChannelDesc, ChannelHop, Route}
import fr.acinq.eclair.wire.protocol.NodeAnnouncement
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, RealShortChannelId, ShortChannelId, TimestampSecond, TimestampSecondLong, ToMilliSatoshiConversion}

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
  val maxCapacity: Satoshi = capacities.values.maxOption.getOrElse(0 sat)

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
  private def decay(amount: MilliSatoshi, successProbabilityAtT: Double, t: TimestampSecond, now: TimestampSecond): Double = {
    val decayRatio = 1 / math.pow(2, (now - t) / halfLife)
    val baseline = 1 - amount.toLong.toDouble / maxCapacity.toMilliSatoshi.toLong
    baseline * (1 - decayRatio) + successProbabilityAtT * decayRatio
  }

  private def otherSide: BalanceEstimate =
    BalanceEstimate(maxCapacity - high, highTimestamp, maxCapacity - low, lowTimestamp, capacities, halfLife)

  /**
   * We tried to send the given amount and received a temporary channel failure. We assume that this failure was caused
   * by a lack of liquidity: it could also be caused by a violation of max_accepted_htlcs, max_htlc_value_in_flight_msat
   * or a spamming protection heuristic by the relaying node, but since we have no way of detecting that, our best
   * strategy is to ignore these cases.
   */
  def couldNotSend(amount: MilliSatoshi, timestamp: TimestampSecond)(implicit log: LoggingAdapter): BalanceEstimate = {
    if (amount <= low) {
      // the balance is actually below `low`, we discard our previous lower bound
      copy(low = 0 msat, lowTimestamp = timestamp, high = amount, highTimestamp = timestamp)
    } else if (amount < high) {
      // the balance is between `low` and `high` as we expected, we discard our previous upper bound
      copy(high = amount, highTimestamp = timestamp)
    } else {
      // We already expected not to be able to relay that amount as it is above our upper bound. However if the upper bound
      // was old enough that replacing it with the current amount decreases the success probability for `high`, then we
      // replace it.
      val updated = copy(high = amount, highTimestamp = timestamp)
      if (updated.canSend(high, timestamp) < this.canSend(high, timestamp)) {
        updated
      } else {
        this
      }
    }
  }

  /**
   * We tried to send the given amount, it was correctly relayed but failed afterwards, so we know we should be able to
   * send at least this amount again.
   */
  def couldSend(amount: MilliSatoshi, timestamp: TimestampSecond)(implicit log: LoggingAdapter): BalanceEstimate =
    otherSide.couldNotSend(maxCapacity - amount, timestamp).otherSide

  /**
   * We successfully sent the given amount, so we know that some of the liquidity has shifted.
   */
  def didSend(amount: MilliSatoshi, timestamp: TimestampSecond)(implicit log: LoggingAdapter): BalanceEstimate = {
    val newLow = (low - amount).max(0 msat)
    if (capacities.size == 1) {
      // Special case for single channel as we expect this case to be quite common and we can easily get more precise bounds.
      val newHigh = (high - amount).max(0 msat)
      // We could shift everything left by amount without changing the timestamps but we may get more information by
      // ignoring the old high if it has decayed too much. We try both and choose the one that gives the lowest
      // probability for the new high.
      val a = copy(low = newLow, high = newHigh)
      val b = copy(low = newLow, high = (maxCapacity - amount).max(0 msat), highTimestamp = timestamp)
      if (a.canSend(newHigh, timestamp) < b.canSend(newHigh, timestamp)) {
        a
      } else {
        b
      }
    } else {
      copy(low = newLow)
    }
  }

  /**
   * We successfully received the given amount, so we know that some of the liquidity has shifted.
   */
  def didReceive(amount: MilliSatoshi, timestamp: TimestampSecond)(implicit log: LoggingAdapter): BalanceEstimate =
    otherSide.didSend(amount, timestamp).otherSide

  def addEdge(edge: GraphEdge): BalanceEstimate = copy(
    high = high.max(edge.capacity.toMilliSatoshi),
    capacities = capacities.updated(edge.desc.shortChannelId, edge.capacity)
  )

  def removeEdge(desc: ChannelDesc): BalanceEstimate = {
    val edgeCapacity = capacities.getOrElse(desc.shortChannelId, 0 sat)
    val newCapacities = capacities.removed(desc.shortChannelId)
    copy(
      low = (low - edgeCapacity.toMilliSatoshi).max(0 msat),
      high = high.min(newCapacities.values.maxOption.getOrElse(0 sat).toMilliSatoshi),
      capacities = newCapacities
    )
  }

  def updateEdge(desc: ChannelDesc, newShortChannelId: RealShortChannelId, newCapacity: Satoshi): BalanceEstimate = {
    val newCapacities = capacities - desc.shortChannelId + (newShortChannelId -> newCapacity)
    val capacityDelta = (newCapacity - capacities.getOrElse(desc.shortChannelId, newCapacity)).toMilliSatoshi
    copy(
      // a capacity decrease will decrease the low bound, but not below 0
      low = (low + capacityDelta.min(0 msat)).max(0 msat),
      // a capacity increase will increase the high bound, but not above the capacity of the largest channel
      high = (high + capacityDelta.max(0 msat)).min(newCapacities.values.maxOption.getOrElse(0 sat).toMilliSatoshi),
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
   * - probability that it can relay a payment of maxCapacity is 0
   */
  def canSend(amount: MilliSatoshi, now: TimestampSecond): Double = {
    val a = amount.toLong.toDouble
    val l = low.toLong.toDouble
    val h = high.toLong.toDouble
    val c = maxCapacity.toMilliSatoshi.toLong.toDouble

    // Success probability at the low and high points
    val pLow = decay(low, 1, lowTimestamp, now)
    val pHigh = decay(high, 0, highTimestamp, now)

    if (amount < low) {
      (l - a * (1.0 - pLow)) / l
    } else if (amount < high) {
      ((h - a) * pLow + (a - l) * pHigh) / (h - l)
    } else if (h < c) {
      ((c - a) * pHigh) / (c - h)
    } else {
      0
    }
  }
}

object BalanceEstimate {
  def empty(halfLife: FiniteDuration): BalanceEstimate = BalanceEstimate(0 msat, 0 unixsec, 0 msat, 0 unixsec, Map.empty, halfLife)
}

/**
 * Balance estimates for the whole routing graph.
 */
case class BalancesEstimates(balances: Map[(PublicKey, PublicKey), BalanceEstimate], defaultHalfLife: FiniteDuration) {
  private def get(a: PublicKey, b: PublicKey): Option[BalanceEstimate] = balances.get((a, b))

  def get(edge: GraphEdge): BalanceEstimate = get(edge.desc.a, edge.desc.b).getOrElse(BalanceEstimate.empty(defaultHalfLife).addEdge(edge))

  def addEdge(edge: GraphEdge): BalancesEstimates = BalancesEstimates(
    balances.updatedWith((edge.desc.a, edge.desc.b))(balance =>
      Some(balance.getOrElse(BalanceEstimate.empty(defaultHalfLife)).addEdge(edge))
    ),
    defaultHalfLife
  )

  def removeEdge(desc: ChannelDesc): BalancesEstimates = BalancesEstimates(
    balances.updatedWith((desc.a, desc.b)) {
      case None => None
      case Some(balance) =>
        val newBalance = balance.removeEdge(desc)
        if (newBalance.capacities.nonEmpty) {
          Some(newBalance)
        } else {
          None
        }
    },
    defaultHalfLife
  )

  def updateEdge(desc: ChannelDesc, newShortChannelId: RealShortChannelId, newCapacity: Satoshi): BalancesEstimates = BalancesEstimates(
    balances.updatedWith((desc.a, desc.b)) {
      case None => None
      case Some(balance) => Some(balance.updateEdge(desc, newShortChannelId, newCapacity))
    },
    defaultHalfLife
  )

  def channelCouldSend(hop: ChannelHop, amount: MilliSatoshi)(implicit log: LoggingAdapter): BalancesEstimates = {
    log.info("channelCouldSend: amount={} scid={} from={} to={}", amount, hop.shortChannelId, hop.nodeId, hop.nextNodeId)
    get(hop.nodeId, hop.nextNodeId).foreach { balance =>
      val estimatedProbability = balance.canSend(amount, TimestampSecond.now())
      Monitoring.Metrics.remoteEdgeRelaySuccess(estimatedProbability)
    }
    BalancesEstimates(balances.updatedWith((hop.nodeId, hop.nextNodeId))(_.map(_.couldSend(amount, TimestampSecond.now()))), defaultHalfLife)
  }

  def channelCouldNotSend(hop: ChannelHop, amount: MilliSatoshi)(implicit log: LoggingAdapter): BalancesEstimates = {
    log.info("channelCouldNotSend: amount={} scid={} from={} to={}", amount, hop.shortChannelId, hop.nodeId, hop.nextNodeId)
    get(hop.nodeId, hop.nextNodeId).foreach { balance =>
      val estimatedProbability = balance.canSend(amount, TimestampSecond.now())
      Monitoring.Metrics.remoteEdgeRelayFailure(estimatedProbability)
    }
    BalancesEstimates(balances.updatedWith((hop.nodeId, hop.nextNodeId))(_.map(_.couldNotSend(amount, TimestampSecond.now()))), defaultHalfLife)
  }

  def channelDidSend(hop: ChannelHop, amount: MilliSatoshi)(implicit log: LoggingAdapter): BalancesEstimates = {
    log.info("channelDidSend: amount={} scid={} from={} to={}", amount, hop.shortChannelId, hop.nodeId, hop.nextNodeId)
    get(hop.nodeId, hop.nextNodeId).foreach { balance =>
      val estimatedProbability = balance.canSend(amount, TimestampSecond.now())
      Monitoring.Metrics.remoteEdgeRelaySuccess(estimatedProbability)
    }
    val balances1 = balances.updatedWith((hop.nodeId, hop.nextNodeId))(_.map(_.didSend(amount, TimestampSecond.now())))
    val balances2 = balances1.updatedWith((hop.nextNodeId, hop.nodeId))(_.map(_.didReceive(amount, TimestampSecond.now())))
    BalancesEstimates(balances2, defaultHalfLife)
  }

}

case class GraphWithBalanceEstimates(graph: DirectedGraph, balances: BalancesEstimates) {
  def addOrUpdateVertex(ann: NodeAnnouncement): GraphWithBalanceEstimates = GraphWithBalanceEstimates(graph.addOrUpdateVertex(ann), balances)

  def addEdge(edge: GraphEdge): GraphWithBalanceEstimates = GraphWithBalanceEstimates(graph.addEdge(edge), balances.addEdge(edge))

  def disableEdge(desc: ChannelDesc): GraphWithBalanceEstimates = GraphWithBalanceEstimates(graph.disableEdge(desc), balances.removeEdge(desc))

  def removeChannel(desc: ChannelDesc): GraphWithBalanceEstimates = GraphWithBalanceEstimates(graph.removeChannel(desc), balances.removeEdge(desc).removeEdge(desc.reversed))

  def removeChannels(descList: Iterable[ChannelDesc]): GraphWithBalanceEstimates = GraphWithBalanceEstimates(
    graph.removeChannels(descList),
    descList.foldLeft(balances)((acc, edge) => acc.removeEdge(edge).removeEdge(edge.reversed)),
  )

  def updateChannel(desc: ChannelDesc, newShortChannelId: RealShortChannelId, newCapacity: Satoshi): GraphWithBalanceEstimates = {
    GraphWithBalanceEstimates(
      graph.updateChannel(desc, newShortChannelId, newCapacity),
      balances.updateEdge(desc, newShortChannelId, newCapacity).updateEdge(desc.reversed, newShortChannelId, newCapacity)
    )
  }

  def routeCouldRelay(route: Route)(implicit log: LoggingAdapter): GraphWithBalanceEstimates = {
    // We drop the first hop which is a local channel, we already have perfect balance information for local channels.
    val (balances1, _) = route.hops.drop(1).foldRight((balances, route.amount)) {
      case (hop, (balances, amount)) =>
        (balances.channelCouldSend(hop, amount), amount + hop.fee(amount))
    }
    GraphWithBalanceEstimates(graph, balances1)
  }

  def routeDidRelay(route: Route)(implicit log: LoggingAdapter): GraphWithBalanceEstimates = {
    // We drop the first hop which is a local channel, we already have perfect balance information for local channels.
    val (balances1, _) = route.hops.drop(1).foldRight((balances, route.amount)) {
      case (hop, (balances, amount)) =>
        (balances.channelDidSend(hop, amount), amount + hop.fee(amount))
    }
    GraphWithBalanceEstimates(graph, balances1)
  }

  def channelCouldNotSend(hop: ChannelHop, amount: MilliSatoshi)(implicit log: LoggingAdapter): GraphWithBalanceEstimates = {
    GraphWithBalanceEstimates(graph, balances.channelCouldNotSend(hop, amount))
  }
}

object GraphWithBalanceEstimates {
  def apply(graph: DirectedGraph, defaultHalfLife: FiniteDuration): GraphWithBalanceEstimates = {
    val balances = graph.edgeSet().foldLeft(Map.empty[(PublicKey, PublicKey), BalanceEstimate]) {
      case (m, edge) => m.updatedWith((edge.desc.a, edge.desc.b))(balance => Some(balance.getOrElse(BalanceEstimate.empty(defaultHalfLife)).addEdge(edge)))
    }
    GraphWithBalanceEstimates(graph, BalancesEstimates(balances, defaultHalfLife))
  }
}