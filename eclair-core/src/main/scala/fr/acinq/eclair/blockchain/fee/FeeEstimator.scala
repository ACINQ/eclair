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
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.blockchain.CurrentFeerates
import fr.acinq.eclair.channel.ChannelVersion

trait FeeEstimator {
  // @formatter:off
  def getFeeratePerKb(target: Int): FeeratePerKB
  def getFeeratePerKw(target: Int): FeeratePerKw
  // @formatter:on
}

case class FeeTargets(fundingBlockTarget: Int, commitmentBlockTarget: Int, mutualCloseBlockTarget: Int, claimMainBlockTarget: Int)

case class FeerateTolerance(ratioLow: Double, ratioHigh: Double, anchorOutputMaxCommitFeerate: FeeratePerKw) {
  /**
   * @param channelVersion  channel version
   * @param networkFeerate  reference fee rate (value we estimate from our view of the network)
   * @param proposedFeerate fee rate proposed (new proposal through update_fee or previous proposal used in our current commit tx)
   * @return true if the difference between proposed and reference fee rates is too high.
   */
  def isFeeDiffTooHigh(channelVersion: ChannelVersion, networkFeerate: FeeratePerKw, proposedFeerate: FeeratePerKw): Boolean = {
    if (channelVersion.hasAnchorOutputs) {
      proposedFeerate < networkFeerate * ratioLow || anchorOutputMaxCommitFeerate * ratioHigh < proposedFeerate
    } else {
      proposedFeerate < networkFeerate * ratioLow || networkFeerate * ratioHigh < proposedFeerate
    }
  }
}

case class OnChainFeeConf(feeTargets: FeeTargets, feeEstimator: FeeEstimator, closeOnOfflineMismatch: Boolean, updateFeeMinDiffRatio: Double, private val defaultFeerateTolerance: FeerateTolerance, private val perNodeFeerateTolerance: Map[PublicKey, FeerateTolerance]) {

  def feerateToleranceFor(nodeId: PublicKey): FeerateTolerance = perNodeFeerateTolerance.getOrElse(nodeId, defaultFeerateTolerance)

  /** To avoid spamming our peers with fee updates every time there's a small variation, we only update the fee when the difference exceeds a given ratio. */
  def shouldUpdateFee(currentFeeratePerKw: FeeratePerKw, nextFeeratePerKw: FeeratePerKw): Boolean =
    currentFeeratePerKw.toLong == 0 || Math.abs((currentFeeratePerKw.toLong - nextFeeratePerKw.toLong).toDouble / currentFeeratePerKw.toLong) > updateFeeMinDiffRatio

  /**
   * Get the feerate that should apply to a channel commitment transaction:
   *  - if we're using anchor outputs, we use a feerate that allows network propagation of the commit tx: we will use CPFP to speed up confirmation if needed
   *  - otherwise we use a feerate that should get the commit tx confirmed within the configured block target
   *
   * @param remoteNodeId        nodeId of our channel peer
   * @param channelVersion      channel version
   * @param currentFeerates_opt if provided, will be used to compute the most up-to-date network fee, otherwise we rely on the fee estimator
   */
  def getCommitmentFeerate(remoteNodeId: PublicKey, channelVersion: ChannelVersion, channelCapacity: Satoshi, currentFeerates_opt: Option[CurrentFeerates]): FeeratePerKw = {
    val networkFeerate = currentFeerates_opt match {
      case Some(currentFeerates) => currentFeerates.feeratesPerKw.feePerBlock(feeTargets.commitmentBlockTarget)
      case None => feeEstimator.getFeeratePerKw(feeTargets.commitmentBlockTarget)
    }
    if (channelVersion.hasAnchorOutputs) {
      networkFeerate.min(feerateToleranceFor(remoteNodeId).anchorOutputMaxCommitFeerate)
    } else {
      networkFeerate
    }
  }
}
