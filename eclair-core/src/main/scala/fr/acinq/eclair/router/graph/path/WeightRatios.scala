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

import fr.acinq.eclair.payment.relay.Relayer.RelayFees

/**
 * Heuristics are used to calculate the weight of an edge based on
 * channel age, cltv delta, capacity, and a virtual hop cost, to keep routes short.
 * Channels that are older, have smaller cltv delta, have bigger capacity, and have lower hop cost, are favored.
 * The sum of the four weights must be one.
 */
case class WeightRatios(baseWeight: Double, cltvDeltaWeight: Double, ageWeight: Double, capacityWeight: Double, hopCost: RelayFees) {
  require(baseWeight + cltvDeltaWeight + ageWeight + capacityWeight == 1, "The sum of heuristics ratios must be 1")
  require(baseWeight >= 0.0, "ratio-base must be nonnegative")
  require(cltvDeltaWeight >= 0.0, "ratio-cltv must be nonnegative")
  require(ageWeight >= 0.0, "ratio-channel-age must be nonnegative")
  require(capacityWeight >= 0.0, "ratio-channel-capacity must be nonnegative")

  def calculateWeightedFactor(cltvFactor: Double, ageFactor: Double, capFactor: Double): Double = {
    this.baseWeight + (cltvFactor * this.cltvDeltaWeight) + (ageFactor * this.ageWeight) + (capFactor * this.capacityWeight)
  }

}
