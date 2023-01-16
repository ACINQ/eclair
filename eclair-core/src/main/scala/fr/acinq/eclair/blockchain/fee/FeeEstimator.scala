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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.Satoshi
import fr.acinq.eclair.blockchain.CurrentFeerates
import fr.acinq.eclair.channel.{ChannelTypes, SupportedChannelType}
import fr.acinq.eclair.transactions.Transactions

trait FeeEstimator {
  // @formatter:off
  def getFeeratePerKb(target: Int): FeeratePerKB
  def getFeeratePerKw(target: Int): FeeratePerKw
  def getMempoolMinFeeratePerKw(): FeeratePerKw
  // @formatter:on
}

case class FeeTargets(fundingBlockTarget: Int, commitmentBlockTarget: Int, commitmentWithoutHtlcsBlockTarget: Int, mutualCloseBlockTarget: Int, claimMainBlockTarget: Int, safeUtxosThreshold: Int) {
  require(fundingBlockTarget > 0)
  require(commitmentBlockTarget > 0)
  require(commitmentWithoutHtlcsBlockTarget > 0)
  require(mutualCloseBlockTarget > 0)
  require(claimMainBlockTarget > 0)
}

/**
 * @param maxExposure              maximum exposure to pending dust htlcs we tolerate: we will automatically fail HTLCs when going above this threshold.
 * @param closeOnUpdateFeeOverflow force-close channels when an update_fee forces us to go above our max exposure.
 */
case class DustTolerance(maxExposure: Satoshi, closeOnUpdateFeeOverflow: Boolean)

case class FeerateTolerance(ratioLow: Double, ratioHigh: Double, anchorOutputMaxCommitFeerate: FeeratePerKw, dustTolerance: DustTolerance) {
  /**
   * @param channelType     channel type
   * @param networkFeerate  reference fee rate (value we estimate from our view of the network)
   * @param proposedFeerate fee rate proposed (new proposal through update_fee or previous proposal used in our current commit tx)
   * @return true if the difference between proposed and reference fee rates is too high.
   */
  def isFeeDiffTooHigh(channelType: SupportedChannelType, networkFeerate: FeeratePerKw, proposedFeerate: FeeratePerKw): Boolean = {
    channelType match {
      case _: ChannelTypes.Standard | _: ChannelTypes.StaticRemoteKey =>
        proposedFeerate < networkFeerate * ratioLow || networkFeerate * ratioHigh < proposedFeerate
      case _: ChannelTypes.AnchorOutputs | _: ChannelTypes.AnchorOutputsZeroFeeHtlcTx =>
        // when using anchor outputs, we allow any feerate: fees will be set with CPFP and RBF at broadcast time
        false
    }
  }
}

case class OnChainFeeConf(feeTargets: FeeTargets, feeEstimator: FeeEstimator, spendAnchorWithoutHtlcs: Boolean, closeOnOfflineMismatch: Boolean, updateFeeMinDiffRatio: Double, private val defaultFeerateTolerance: FeerateTolerance, private val perNodeFeerateTolerance: Map[PublicKey, FeerateTolerance]) {

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
   * @param channelType         channel type
   * @param currentFeerates_opt if provided, will be used to compute the most up-to-date network fee, otherwise we rely on the fee estimator
   */
  def getCommitmentFeerate(remoteNodeId: PublicKey, channelType: SupportedChannelType, channelCapacity: Satoshi, currentFeerates_opt: Option[CurrentFeerates]): FeeratePerKw = {
    val (networkFeerate, networkMinFee) = currentFeerates_opt match {
      case Some(currentFeerates) => (currentFeerates.feeratesPerKw.feePerBlock(feeTargets.commitmentBlockTarget), currentFeerates.feeratesPerKw.mempoolMinFee)
      case None => (feeEstimator.getFeeratePerKw(feeTargets.commitmentBlockTarget), feeEstimator.getMempoolMinFeeratePerKw())
    }
    channelType.commitmentFormat match {
      case Transactions.DefaultCommitmentFormat => networkFeerate
      case _: Transactions.AnchorOutputsCommitmentFormat =>
        val targetFeerate = networkFeerate.min(feerateToleranceFor(remoteNodeId).anchorOutputMaxCommitFeerate)
        // We make sure the feerate is always greater than the propagation threshold.
        targetFeerate.max(networkMinFee * 1.25)
    }
  }
}
