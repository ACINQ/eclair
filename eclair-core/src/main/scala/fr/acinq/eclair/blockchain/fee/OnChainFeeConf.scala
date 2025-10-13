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
import fr.acinq.bitcoin.scalacompat.{Satoshi, SatoshiLong}
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
   * @param networkFeerate  reference fee rate (value we estimate from our view of the network)
   * @param proposedFeerate fee rate proposed (new proposal through update_fee or previous proposal used in our current commit tx)
   * @return true if the difference between proposed and reference fee rates is too high.
   */
  def isProposedCommitFeerateTooHigh(networkFeerate: FeeratePerKw, proposedFeerate: FeeratePerKw): Boolean = networkFeerate * ratioHigh < proposedFeerate
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
  def shouldUpdateFee(currentFeeratePerKw: FeeratePerKw, nextFeeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Boolean = {
    commitmentFormat match {
      case Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat | Transactions.PhoenixSimpleTaprootChannelCommitmentFormat =>
        // If we're not already using 1 sat/byte, we update the fee.
        FeeratePerKw(FeeratePerByte(1 sat)) < currentFeeratePerKw
      case Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat | Transactions.ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat =>
        // If the fee has a large enough change, we update the fee.
        currentFeeratePerKw.toLong == 0 || Math.abs((currentFeeratePerKw.toLong - nextFeeratePerKw.toLong).toDouble / currentFeeratePerKw.toLong) > updateFeeMinDiffRatio
    }
  }

  def getFundingFeerate(feerates: FeeratesPerKw): FeeratePerKw = feeTargets.funding.getFeerate(feerates)

  /**
   * Get the feerate that should apply to a channel commitment transaction:
   *  - if the remote peer is a mobile wallet that supports anchor outputs, we use 1 sat/byte
   *  - otherwise, we use a feerate that should allow network propagation of the commit tx on its own: we will use CPFP
   *    on the anchor output to speed up confirmation if needed or to help propagation
   *
   * @param remoteNodeId     nodeId of our channel peer
   * @param commitmentFormat commitment format
   */
  def getCommitmentFeerate(feerates: FeeratesPerKw, remoteNodeId: PublicKey, commitmentFormat: CommitmentFormat): FeeratePerKw = {
    val networkFeerate = feerates.fast
    val networkMinFee = feerates.minimum
    commitmentFormat match {
      case Transactions.UnsafeLegacyAnchorOutputsCommitmentFormat | Transactions.PhoenixSimpleTaprootChannelCommitmentFormat =>
        // Since Bitcoin Core v28, 1-parent-1-child package relay has been deployed: it should be ok if the commit tx
        // doesn't propagate on its own.
        FeeratePerKw(FeeratePerByte(1 sat))
      case Transactions.ZeroFeeHtlcTxAnchorOutputsCommitmentFormat | Transactions.ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat =>
        val targetFeerate = networkFeerate.min(feerateToleranceFor(remoteNodeId).anchorOutputMaxCommitFeerate)
        // We make sure the feerate is always greater than the propagation threshold.
        targetFeerate.max(networkMinFee * 1.25)
    }
  }

  def getClosingFeerate(feerates: FeeratesPerKw, maxClosingFeerateOverride_opt: Option[FeeratePerKw]): FeeratePerKw = {
    feeTargets.closing.getFeerate(feerates).min(maxClosingFeerateOverride_opt.getOrElse(maxClosingFeerate))
  }

}
