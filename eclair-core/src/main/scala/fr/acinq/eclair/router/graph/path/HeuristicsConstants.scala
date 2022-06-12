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
 * We use heuristics to calculate the weight of an edge in a path.
 * The fee for a failed attempt and the fee per hop are never actually spent, they are used to incentivize shorter
 * paths or a path with higher success probability.
 *
 * @param lockedFundsRisk cost of having funds locked in htlc in msat per msat per block
 * @param failureCost     fee for a failed attempt
 * @param hopCost         virtual fee per hop (how much we're willing to pay to make the route one hop shorter)
 * @param useLogProbability if true, then log of successProbability is used when calculating the [[RichWeight]]
 */
case class HeuristicsConstants(lockedFundsRisk: Double, failureCost: RelayFees, hopCost: RelayFees, useLogProbability: Boolean)