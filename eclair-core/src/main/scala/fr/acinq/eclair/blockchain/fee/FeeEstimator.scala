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

package fr.acinq.eclair.blockchain.fee

import fr.acinq.bitcoin.Crypto.PublicKey

trait FeeEstimator {
  // @formatter:off
  def getFeeratePerKb(target: Int): FeeratePerKB
  def getFeeratePerKw(target: Int): FeeratePerKw
  // @formatter:on
}

case class FeeTargets(fundingBlockTarget: Int, commitmentBlockTarget: Int, mutualCloseBlockTarget: Int, claimMainBlockTarget: Int)

case class FeerateTolerance(ratioLow: Double, ratioHigh: Double)

case class OnChainFeeConf(feeTargets: FeeTargets, feeEstimator: FeeEstimator, closeOnOfflineMismatch: Boolean, updateFeeMinDiffRatio: Double, private val defaultFeerateTolerance: FeerateTolerance, private val perNodeFeerateTolerance: Map[PublicKey, FeerateTolerance]) {
  def maxFeerateMismatchFor(nodeId: PublicKey): FeerateTolerance = perNodeFeerateTolerance.getOrElse(nodeId, defaultFeerateTolerance)
}
