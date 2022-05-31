/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.router.graph

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.graph.structure.GraphEdge
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, MilliSatoshi, ShortChannelId, nodeFee}
import fr.acinq.eclair._

import scala.annotation.tailrec

object Path {

  /**
   * The cumulative weight of a set of edges (path in the graph).
   *
   * @param amount             amount to send to the recipient + each edge's fees
   * @param length             number of edges in the path
   * @param cltv               sum of each edge's cltv
   * @param successProbability estimate of the probability that the payment would succeed using this path
   * @param fees               total fees of the path
   * @param weight             cost multiplied by a factor based on heuristics (see [[WeightRatios]]).
   */
  case class RichWeight(amount: MilliSatoshi, length: Int, cltv: CltvExpiryDelta, successProbability: Double,
                        fees: MilliSatoshi, virtualFees: MilliSatoshi, weight: Double) extends Ordered[RichWeight] {
    override def compare(that: RichWeight): Int = this.weight.compareTo(that.weight)
  }

  /**
   * We use heuristics to calculate the weight of an edge based on channel age, cltv delta, capacity and a virtual hop cost to keep routes short.
   * We favor older channels, with bigger capacity and small cltv delta.
   */
  case class WeightRatios(baseFactor: Double, cltvDeltaFactor: Double, ageFactor: Double, capacityFactor: Double, hopCost: RelayFees) {
    require(baseFactor + cltvDeltaFactor + ageFactor + capacityFactor == 1, "The sum of heuristics ratios must be 1")
    require(baseFactor >= 0.0, "ratio-base must be nonnegative")
    require(cltvDeltaFactor >= 0.0, "ratio-cltv must be nonnegative")
    require(ageFactor >= 0.0, "ratio-channel-age must be nonnegative")
    require(capacityFactor >= 0.0, "ratio-channel-capacity must be nonnegative")
  }

  /**
   * We use heuristics to calculate the weight of an edge.
   * The fee for a failed attempt and the fee per hop are never actually spent, they are used to incentivize shorter
   * paths or path with higher success probability.
   *
   * @param lockedFundsRisk cost of having funds locked in htlc in msat per msat per block
   * @param failureCost     fee for a failed attempt
   * @param hopCost         virtual fee per hop (how much we're willing to pay to make the route one hop shorter)
   */
  case class HeuristicsConstants(lockedFundsRisk: Double, failureCost: RelayFees, hopCost: RelayFees, useLogProbability: Boolean)

  case class WeightedNode(key: PublicKey, weight: RichWeight)

  case class WeightedPath(path: Seq[GraphEdge], weight: RichWeight)

  /**
   * This comparator must be consistent with the "equals" behavior, thus for two weighted nodes with
   * the same weight we distinguish them by their public key.
   * See https://docs.oracle.com/javase/8/docs/api/java/util/Comparator.html
   */
  object NodeComparator extends Ordering[WeightedNode] {
    override def compare(x: WeightedNode, y: WeightedNode): Int = {
      val weightCmp = x.weight.compareTo(y.weight)
      if (weightCmp == 0) x.key.toString().compareTo(y.key.toString())
      else weightCmp
    }
  }

  implicit object PathComparator extends Ordering[WeightedPath] {
    override def compare(x: WeightedPath, y: WeightedPath): Int = y.weight.compare(x.weight)
  }

  case class InfiniteLoop(path: Seq[GraphEdge]) extends Exception

  case class NegativeProbability(edge: GraphEdge, weight: RichWeight, heuristicsConstants: HeuristicsConstants) extends Exception

  /**
   * Add the given edge to the path and compute the new weight.
   *
   * @param sender                  node sending the payment
   * @param edge                    the edge we want to cross
   * @param prev                    weight of the rest of the path
   * @param currentBlockHeight      the height of the chain tip (latest block).
   * @param weightRatios            ratios used to 'weight' edges when searching for the shortest path
   * @param includeLocalChannelCost if the path is for relaying and we need to include the cost of the local channel
   */
  def addEdgeWeight(sender: PublicKey, edge: GraphEdge, prev: RichWeight, currentBlockHeight: BlockHeight,
                    weightRatios: Either[WeightRatios, HeuristicsConstants], includeLocalChannelCost: Boolean): RichWeight = {
    val totalAmount = if (edge.desc.a == sender && !includeLocalChannelCost) prev.amount else addEdgeFees(edge, prev.amount)
    val fee = totalAmount - prev.amount
    val totalFees = prev.fees + fee
    val cltv = if (edge.desc.a == sender && !includeLocalChannelCost) CltvExpiryDelta(0) else edge.params.cltvExpiryDelta
    val totalCltv = prev.cltv + cltv

    weightRatios match {
      case Left(weightRatios) =>
        addEdgeWeight(sender, edge, prev, currentBlockHeight, totalAmount, fee, totalFees, totalCltv, weightRatios)
      case Right(heuristicsConstants) =>
        addEdgeWeight(edge, prev, totalAmount, fee, totalFees, cltv, totalCltv, heuristicsConstants)
    }
  }

  private def addEdgeWeight(sender: PublicKey, edge: GraphEdge, prev: RichWeight, currentBlockHeight: BlockHeight, totalAmount: MilliSatoshi, fee: MilliSatoshi, totalFees: MilliSatoshi, totalCltv: CltvExpiryDelta, weightRatios: WeightRatios): RichWeight = {
    val hopCost = if (edge.desc.a == sender) 0 msat else nodeFee(weightRatios.hopCost, prev.amount)
    import RoutingHeuristics._

    // Every edge is weighted by funding block height where older blocks add less weight. The window considered is 1 year.
    val ageFactor = edge.desc.shortChannelId match {
    case real: RealShortChannelId => normalize(real.blockHeight.toDouble, min = (currentBlockHeight - BLOCK_TIME_ONE_YEAR).toDouble, max = currentBlockHeight.toDouble)
          // for local channels or route hints we don't easily have access to the channel block height, but we want to
          // give them the best score anyway
          case _: Alias => 1
          case _: UnspecifiedShortChannelId => 1
        }

    // Every edge is weighted by channel capacity, larger channels add less weight
    val edgeMaxCapacity = edge.capacity.toMilliSatoshi
    val capFactor =
      if (edge.balance_opt.isDefined) 0 // If we know the balance of the channel we treat it as if it had the maximum capacity.
      else 1 - normalize(edgeMaxCapacity.toLong.toDouble, CAPACITY_CHANNEL_LOW.toLong.toDouble, CAPACITY_CHANNEL_HIGH.toLong.toDouble)

    // Every edge is weighted by its cltv-delta value, normalized
    val cltvFactor = normalize(edge.params.cltvExpiryDelta.toInt, CLTV_LOW, CLTV_HIGH)

    // NB we're guaranteed to have weightRatios and factors > 0
    val factor = weightRatios.baseFactor + (cltvFactor * weightRatios.cltvDeltaFactor) + (ageFactor * weightRatios.ageFactor) + (capFactor * weightRatios.capacityFactor)
    val totalWeight = prev.weight + (fee + hopCost).toLong * factor
    RichWeight(totalAmount, prev.length + 1, totalCltv, 1.0, totalFees, 0 msat, totalWeight)
  }


  private def addEdgeWeight(edge: GraphEdge, prev: RichWeight, totalAmount: MilliSatoshi, fee: MilliSatoshi, totalFees: MilliSatoshi, cltv: CltvExpiryDelta, totalCltv: CltvExpiryDelta, heuristicsConstants: HeuristicsConstants): RichWeight = {
    val hopCost = nodeFee(heuristicsConstants.hopCost, prev.amount)
    val totalHopsCost = prev.virtualFees + hopCost
    // If we know the balance of the channel, then we will check separately that it can relay the payment.
    val successProbability = if (edge.balance_opt.nonEmpty) 1.0 else 1.0 - prev.amount.toLong.toDouble / edge.capacity.toMilliSatoshi.toLong.toDouble
    if (successProbability < 0) {
      throw NegativeProbability(edge, prev, heuristicsConstants)
    }
    val totalSuccessProbability = prev.successProbability * successProbability
    val failureCost = nodeFee(heuristicsConstants.failureCost, totalAmount)
    if (heuristicsConstants.useLogProbability) {
      val riskCost = totalAmount.toLong * cltv.toInt * heuristicsConstants.lockedFundsRisk
      val weight = prev.weight + fee.toLong + hopCost.toLong + riskCost - failureCost.toLong * math.log(successProbability)
      RichWeight(totalAmount, prev.length + 1, totalCltv, totalSuccessProbability, totalFees, totalHopsCost, weight)
    } else {
      val totalRiskCost = totalAmount.toLong * totalCltv.toInt * heuristicsConstants.lockedFundsRisk
      val weight = totalFees.toLong + totalHopsCost.toLong + totalRiskCost + failureCost.toLong / totalSuccessProbability
      RichWeight(totalAmount, prev.length + 1, totalCltv, totalSuccessProbability, totalFees, totalHopsCost, weight)
    }
  }

  /**
   * Calculate the minimum amount that the start node needs to receive to be able to forward @amountWithFees to the end
   * node.
   *
   * @param edge            the edge we want to cross
   * @param amountToForward the value that this edge will have to carry along
   * @return the new amount updated with the necessary fees for this edge
   */
  private def addEdgeFees(edge: GraphEdge, amountToForward: MilliSatoshi): MilliSatoshi = {
    amountToForward + edge.params.fee(amountToForward)
  }

  /** Validate that all edges along the path can relay the amount with fees. */
  def validatePath(path: Seq[GraphEdge], amount: MilliSatoshi): Boolean = validateReversePath(path.reverse, amount)

  @tailrec
  private def validateReversePath(path: Seq[GraphEdge], amount: MilliSatoshi): Boolean = path.headOption match {
    case None => true
    case Some(edge) =>
      val canRelayAmount = amount <= edge.capacity &&
        edge.balance_opt.forall(amount <= _) &&
        edge.params.htlcMaximum_opt.forall(amount <= _) &&
        edge.params.htlcMinimum <= amount
      if (canRelayAmount) validateReversePath(path.tail, addEdgeFees(edge, amount)) else false
  }

  /**
   * Calculates the total weighted cost of a path.
   * Note that the first hop from the sender is ignored: we don't pay a routing fee to ourselves.
   *
   * @param sender                  node sending the payment
   * @param path                    candidate path.
   * @param amount                  amount to send to the last node.
   * @param currentBlockHeight      the height of the chain tip (latest block).
   * @param wr                      ratios used to 'weight' edges when searching for the shortest path
   * @param includeLocalChannelCost if the path is for relaying and we need to include the cost of the local channel
   */
  def pathWeight(sender: PublicKey, path: Seq[GraphEdge], amount: MilliSatoshi, currentBlockHeight: BlockHeight,
                 wr: Either[WeightRatios, HeuristicsConstants], includeLocalChannelCost: Boolean): RichWeight = {
    path.foldRight(RichWeight(amount, 0, CltvExpiryDelta(0), 1.0, 0 msat, 0 msat, 0.0)) { (edge, prev) =>
      addEdgeWeight(sender, edge, prev, currentBlockHeight, wr, includeLocalChannelCost)
    }
  }

}
