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
import fr.acinq.eclair.BlockHeight
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._

// @formatter:off
sealed trait ConfirmationPriority extends Ordered[ConfirmationPriority] {
  def underlying: Int

  def getFeerate(feerates: FeeratesPerKw): FeeratePerKw = this match {
    case ConfirmationPriority.Slow => feerates.slow
    case ConfirmationPriority.Medium => feerates.medium
    case ConfirmationPriority.Fast => feerates.fast
  }

  override def compare(that: ConfirmationPriority): Int = this.underlying.compare(that.underlying)
}
object ConfirmationPriority {
  case object Slow extends ConfirmationPriority {
    override val underlying = 1
    override def toString = "slow"
  }
  case object Medium extends ConfirmationPriority {
    override val underlying = 2
    override def toString = "medium"
  }
  case object Fast extends ConfirmationPriority {
    override val underlying = 3
    override def toString = "fast"
  }
}
sealed trait ConfirmationTarget
object ConfirmationTarget {
  case class Absolute(confirmBefore: BlockHeight) extends ConfirmationTarget
  case class Priority(priority: ConfirmationPriority) extends ConfirmationTarget
}
// @formatter:on

case class FeeTargets(funding: ConfirmationPriority, closing: ConfirmationPriority)

/**
 * @param maxExposure              maximum exposure to pending dust htlcs we tolerate: we will automatically fail HTLCs when going above this threshold.
 * @param closeOnUpdateFeeOverflow force-close channels when an update_fee forces us to go above our max exposure.
 */
case class DustTolerance(maxExposure: Satoshi, closeOnUpdateFeeOverflow: Boolean)

case class FeerateTolerance(ratioLow: Double, ratioHigh: Double, anchorOutputMaxCommitFeerate: FeeratePerKw, dustTolerance: DustTolerance) {
  /**
   * @param commitmentFormat commitment format (anchor outputs allows a much higher tolerance since fees can be adjusted after tx is published)
   * @param networkFeerate   reference fee rate (value we estimate from our view of the network)
   * @param proposedFeerate  fee rate proposed (new proposal through update_fee or previous proposal used in our current commit tx)
   * @return true if the difference between proposed and reference fee rates is too high.
   */
  def isFeeDiffTooHigh(commitmentFormat: CommitmentFormat, networkFeerate: FeeratePerKw, proposedFeerate: FeeratePerKw): Boolean = {
    isProposedFeerateTooLow(commitmentFormat, networkFeerate, proposedFeerate) || isProposedFeerateTooHigh(commitmentFormat, networkFeerate, proposedFeerate)
  }

  def isProposedFeerateTooHigh(commitmentFormat: CommitmentFormat, networkFeerate: FeeratePerKw, proposedFeerate: FeeratePerKw): Boolean = {
    commitmentFormat match {
      case Transactions.DefaultCommitmentFormat => networkFeerate * ratioHigh < proposedFeerate
      case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat | UnsafeLegacyAnchorOutputsCommitmentFormat | PhoenixSimpleTaprootChannelCommitmentFormat | ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat => networkFeerate * ratioHigh < proposedFeerate
    }
  }

  def isProposedFeerateTooLow(commitmentFormat: CommitmentFormat, networkFeerate: FeeratePerKw, proposedFeerate: FeeratePerKw): Boolean = {
    commitmentFormat match {
      case Transactions.DefaultCommitmentFormat => proposedFeerate < networkFeerate * ratioLow
      // When using anchor outputs, we allow low feerates: fees will be set with CPFP and RBF at broadcast time.
      case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat | UnsafeLegacyAnchorOutputsCommitmentFormat | PhoenixSimpleTaprootChannelCommitmentFormat | ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat => false
    }
  }
}

case class OnChainFeeConf(feeTargets: FeeTargets,
                          maxClosingFeerate: FeeratePerKw,
                          safeUtxosThreshold: Int,
                          spendAnchorWithoutHtlcs: Boolean,
                          anchorWithoutHtlcsMaxFee: Satoshi,
                          closeOnOfflineMismatch: Boolean,
                          updateFeeMinDiffRatio: Double,
                          defaultFeerateTolerance: FeerateTolerance,
                          private val perNodeFeerateTolerance: Map[PublicKey, FeerateTolerance]) {

  def feerateToleranceFor(nodeId: PublicKey): FeerateTolerance = perNodeFeerateTolerance.getOrElse(nodeId, defaultFeerateTolerance)

  /** To avoid spamming our peers with fee updates every time there's a small variation, we only update the fee when the difference exceeds a given ratio. */
  def shouldUpdateFee(currentFeeratePerKw: FeeratePerKw, nextFeeratePerKw: FeeratePerKw): Boolean =
    currentFeeratePerKw.toLong == 0 || Math.abs((currentFeeratePerKw.toLong - nextFeeratePerKw.toLong).toDouble / currentFeeratePerKw.toLong) > updateFeeMinDiffRatio

  def getFundingFeerate(feerates: FeeratesPerKw): FeeratePerKw = feeTargets.funding.getFeerate(feerates)

  /**
   * Get the feerate that should apply to a channel commitment transaction:
   *  - if we're using anchor outputs, we use a feerate that allows network propagation of the commit tx: we will use CPFP to speed up confirmation if needed
   *  - otherwise we use a feerate that should get the commit tx confirmed within the configured block target
   *
   * @param remoteNodeId     nodeId of our channel peer
   * @param commitmentFormat commitment format
   */
  def getCommitmentFeerate(feerates: FeeratesPerKw, remoteNodeId: PublicKey, commitmentFormat: CommitmentFormat): FeeratePerKw = {
    val networkFeerate = feerates.fast
    val networkMinFee = feerates.minimum
    commitmentFormat match {
      case Transactions.DefaultCommitmentFormat => networkFeerate
      case _: Transactions.AnchorOutputsCommitmentFormat | _: Transactions.SimpleTaprootChannelCommitmentFormat =>
        val targetFeerate = networkFeerate.min(feerateToleranceFor(remoteNodeId).anchorOutputMaxCommitFeerate)
        // We make sure the feerate is always greater than the propagation threshold.
        targetFeerate.max(networkMinFee * 1.25)
    }
  }

  def getClosingFeerate(feerates: FeeratesPerKw, maxClosingFeerateOverride_opt: Option[FeeratePerKw]): FeeratePerKw = {
    feeTargets.closing.getFeerate(feerates).min(maxClosingFeerateOverride_opt.getOrElse(maxClosingFeerate))
  }

}
