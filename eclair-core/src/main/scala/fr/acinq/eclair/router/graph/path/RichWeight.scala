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

package fr.acinq.eclair.router.graph.path

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.router.graph.RoutingHeuristics
import fr.acinq.eclair.router.graph.path.Path.NegativeProbability
import fr.acinq.eclair.router.graph.structure.GraphEdge
import fr.acinq.eclair._


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

object RichWeight {

  /**
   * @return a RichWeight created from previous edge and WeightRatios
   */
  def apply(sender: PublicKey, edge: GraphEdge, prev: RichWeight, currentBlockHeight: BlockHeight, totalAmount: MilliSatoshi,
            fee: MilliSatoshi, totalFees: MilliSatoshi, totalCltv: CltvExpiryDelta, weightRatios: WeightRatios): RichWeight = {
    val hopCost = if (edge.desc.a == sender) 0 msat else nodeFee(weightRatios.hopCost, prev.amount)
    import RoutingHeuristics._

    // Every edge is weighted by funding block height where older blocks add less weight. The window considered is 1 year.
    val channelBlockHeight = ShortChannelId.coordinates(edge.desc.shortChannelId).blockHeight
    val ageFactor = normalize(channelBlockHeight.toDouble, min = (currentBlockHeight - BLOCK_TIME_ONE_YEAR).toDouble, max = currentBlockHeight.toDouble)

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

  /**
   * @return a RichWeight created from previous edge and HeuristicsConstants
   */
  def apply(edge: GraphEdge, prev: RichWeight, totalAmount: MilliSatoshi, fee: MilliSatoshi, totalFees: MilliSatoshi, cltv: CltvExpiryDelta, totalCltv: CltvExpiryDelta,
            heuristicsConstants: HeuristicsConstants): RichWeight = {
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
}
