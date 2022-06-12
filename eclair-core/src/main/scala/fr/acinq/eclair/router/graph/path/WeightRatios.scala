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
