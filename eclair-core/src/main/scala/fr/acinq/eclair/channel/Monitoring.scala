/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.channel

import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.{InteractiveTxParams, SharedTransaction}
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc}
import fr.acinq.eclair.wire.protocol.LiquidityAds
import kamon.Kamon

object Monitoring {

  object Metrics {
    val ChannelsCount = Kamon.gauge("channels.count")
    val ChannelErrors = Kamon.counter("channels.errors")
    val ChannelLifecycleEvents = Kamon.counter("channels.lifecycle")
    val HtlcsInFlight = Kamon.histogram("channels.htlc-in-flight", "Per-channel HTLCs in flight")
    val HtlcsInFlightGlobal = Kamon.gauge("channels.htlc-in-flight-global", "Global HTLCs in flight across all channels")
    val HtlcValueInFlight = Kamon.histogram("channels.htlc-value-in-flight", "Per-channel HTLC value in flight")
    val HtlcValueInFlightGlobal = Kamon.gauge("channels.htlc-value-in-flight-global", "Global HTLC value in flight across all channels")
    val LocalFeeratePerByte = Kamon.histogram("channels.local-feerate-per-byte")
    val RemoteFeeratePerByte = Kamon.histogram("channels.remote-feerate-per-byte")
    val InteractiveTxFundingTargetAmount = Kamon.histogram("channels.interactive-tx.target-funding-amount", "Interactive tx funding target amount")
    val InteractiveTxFundingChangeAmount = Kamon.histogram("channels.interactive-tx.change-amount", "Interactive tx funding change")
    val InteractiveTxLocalMiningFee = Kamon.histogram("channels.interactive-tx.local-mining-fee", "Interactive tx mining fee paid by us")
    val InteractiveTxInputs = Kamon.histogram("channels.interactive-tx.inputs", "Interactive tx inputs")
    val InteractiveTxOutputs = Kamon.histogram("channels.interactive-tx.outputs", "Interactive tx outputs")
    val InteractiveTxInputsPerSession = Kamon.histogram("channels.interactive-tx.inputs-per-session", "Interactive tx inputs per session")
    val InteractiveTxOutputsPerSession = Kamon.histogram("channels.interactive-tx.outputs-per-session", "Interactive tx outputs per session")
    val LiquidityPurchaseAmount = Kamon.histogram("channels.interactive-tx.liquidity-purchase-amount", "Amount of liquidity purchased (if positive) or sold (if negative)")
    val LiquidityPurchaseMiningFeeDiff = Kamon.histogram("channels.interactive-tx.liquidity-purchase-mining-fee-diff", "When selling liquidity, difference between the refunded mining fee and the actual mining fee paid")
    val Splices = Kamon.histogram("channels.splices", "Splices")
    val ProcessMessage = Kamon.timer("channels.messages-processed")
    val HtlcDropped = Kamon.counter("channels.htlc-dropped")

    def recordHtlcsInFlight(remoteSpec: CommitmentSpec, previousRemoteSpec: CommitmentSpec): Unit = {
      for (direction <- Tags.Directions.Incoming :: Tags.Directions.Outgoing :: Nil) {
        // NB: IN/OUT htlcs are inverted because this is the remote commit
        val filter = if (direction == Tags.Directions.Incoming) DirectedHtlc.outgoing else DirectedHtlc.incoming
        // NB: we need the `toSeq` because otherwise duplicate amounts would be removed (since htlcs are sets)
        val htlcs = remoteSpec.htlcs.collect(filter).toSeq.map(_.amountMsat)
        val previousHtlcs = previousRemoteSpec.htlcs.collect(filter).toSeq.map(_.amountMsat)
        HtlcsInFlight.withTag(Tags.Direction, direction).record(htlcs.length)
        HtlcsInFlightGlobal.withTag(Tags.Direction, direction).increment(htlcs.length - previousHtlcs.length)
        val (value, previousValue) = (htlcs.sum.truncateToSatoshi.toLong, previousHtlcs.sum.truncateToSatoshi.toLong)
        HtlcValueInFlight.withTag(Tags.Direction, direction).record(value)
        HtlcValueInFlightGlobal.withTag(Tags.Direction, direction).increment((value - previousValue).toDouble)
      }
    }

    def recordInteractiveTx(fundingParams: InteractiveTxParams, sharedTx: SharedTransaction, liquidityPurchase_opt: Option[LiquidityAds.Purchase]): Unit = {
      // Global, not "per session". The goal is to measure the total number of inputs/outputs and distribution of amounts across all interactive-tx sessions.
      sharedTx.sharedInput_opt.foreach(i => InteractiveTxInputs.withTag(Tags.InputType, "shared").record(i.txOut.amount.toLong))
      sharedTx.localInputs.foreach(i => InteractiveTxInputs.withTag(Tags.InputType, "local").record(i.txOut.amount.toLong))
      sharedTx.remoteInputs.foreach(i => InteractiveTxInputs.withTag(Tags.InputType, "remote").record(i.txOut.amount.toLong))
      InteractiveTxOutputs.withTag(Tags.OutputType, "shared").record(sharedTx.sharedOutput.amount.toLong)
      sharedTx.localOutputs.foreach(o => InteractiveTxOutputs.withTag(Tags.OutputType, "local").record(o.amount.toLong))
      sharedTx.remoteOutputs.foreach(o => InteractiveTxOutputs.withTag(Tags.OutputType, "remote").record(o.amount.toLong))

      // We measure the number of each non-shared input/output type per session.
      if (sharedTx.localInputs.nonEmpty) InteractiveTxInputsPerSession.withTag(Tags.InputType, "local").record(sharedTx.localInputs.size)
      if (sharedTx.remoteInputs.nonEmpty) InteractiveTxInputsPerSession.withTag(Tags.InputType, "remote").record(sharedTx.remoteInputs.size)
      if (sharedTx.localOutputs.nonEmpty) InteractiveTxOutputsPerSession.withTag(Tags.OutputType, "local").record(sharedTx.localOutputs.size)
      if (sharedTx.remoteOutputs.nonEmpty) InteractiveTxOutputsPerSession.withTag(Tags.OutputType, "remote").record(sharedTx.remoteOutputs.size)

      // We measure the difference between the amount we want to fund and the resulting change.
      if (fundingParams.localContribution >= 0.sat) {
        InteractiveTxFundingTargetAmount.withTag(Tags.DiffSign, Tags.DiffSigns.plus).record(fundingParams.localContribution.toLong)
        // Note that we explicitly want to record a 0 value when we don't have any change output: it lets us see how many sessions don't need change, which is the best outcome.
        InteractiveTxFundingChangeAmount.withoutTags().record(sharedTx.localOutputs.collect { case o: InteractiveTxBuilder.Output.Local.Change => o.amount.toLong }.sum)
      } else {
        InteractiveTxFundingTargetAmount.withTag(Tags.DiffSign, Tags.DiffSigns.minus).record(-fundingParams.localContribution.toLong)
      }
      InteractiveTxLocalMiningFee.withoutTags().record(sharedTx.localFees.truncateToSatoshi.toLong)

      // We record liquidity purchase details.
      liquidityPurchase_opt.foreach(p => {
        // If we initiate the interactive-tx session, we're the buyer: otherwise, we're the seller.
        LiquidityPurchaseAmount.withTag(Tags.DiffSign, if (fundingParams.isInitiator) Tags.DiffSigns.plus else Tags.DiffSigns.minus).record(p.amount.toLong)
        // If the actual mining fee we paid is greater than what the user refunds, we lose money.
        if (!fundingParams.isInitiator) {
          val miningFeeDiff = p.fees.miningFee - sharedTx.localFees.truncateToSatoshi
          if (miningFeeDiff >= 0.sat) {
            LiquidityPurchaseMiningFeeDiff.withTag(Tags.DiffSign, Tags.DiffSigns.plus).record(miningFeeDiff.toLong)
          } else {
            LiquidityPurchaseMiningFeeDiff.withTag(Tags.DiffSign, Tags.DiffSigns.minus).record(-miningFeeDiff.toLong)
          }
        }
      })
    }

    /**
     * This is best effort! It is not possible to attribute a type to a splice in all cases. For example, if remote provides
     * both inputs and outputs, it could be a splice-in (with change), or a combined splice-in + splice-out.
     */
    def recordSplice(fundingParams: InteractiveTxParams, sharedTx: SharedTransaction): Unit = {
      if (fundingParams.localContribution > 0.sat) {
        Metrics.Splices.withTag(Tags.Origin, Tags.Origins.Local).withTag(Tags.SpliceType, Tags.SpliceTypes.SpliceIn).record(fundingParams.localContribution.toLong)
      }
      if (fundingParams.remoteContribution > 0.sat) {
        Metrics.Splices.withTag(Tags.Origin, Tags.Origins.Remote).withTag(Tags.SpliceType, Tags.SpliceTypes.SpliceIn).record(fundingParams.remoteContribution.toLong)
      }
      if (sharedTx.localOnlyNonChangeOutputs.nonEmpty) {
        Metrics.Splices.withTag(Tags.Origin, Tags.Origins.Local).withTag(Tags.SpliceType, Tags.SpliceTypes.SpliceOut).record(sharedTx.localOnlyNonChangeOutputs.map(_.amount).sum.toLong)
      }
      if (sharedTx.remoteOutputs.nonEmpty) {
        Metrics.Splices.withTag(Tags.Origin, Tags.Origins.Remote).withTag(Tags.SpliceType, Tags.SpliceTypes.SpliceOut).record(sharedTx.remoteOutputs.map(_.amount).sum.toLong)
      }
      if (fundingParams.localContribution < 0.sat && sharedTx.localOutputs.isEmpty) {
        Metrics.Splices.withTag(Tags.Origin, Tags.Origins.Local).withTag(Tags.SpliceType, Tags.SpliceTypes.SpliceCpfp).record(Math.abs(fundingParams.localContribution.toLong))
      }
      if (fundingParams.remoteContribution < 0.sat && sharedTx.remoteOutputs.isEmpty) {
        Metrics.Splices.withTag(Tags.Origin, Tags.Origins.Remote).withTag(Tags.SpliceType, Tags.SpliceTypes.SpliceCpfp).record(Math.abs(fundingParams.remoteContribution.toLong))
      }
    }

    def dropHtlc(reason: ChannelException, direction: String): Unit = {
      HtlcDropped.withTag(Tags.Reason, reason.getClass.getSimpleName).withTag(Tags.Direction, direction).increment()
    }
  }

  object Tags {
    val Direction = "direction"
    val Event = "event"
    val Fatal = "fatal"
    val ErrorType = "error-type"
    val Origin = "origin"
    val State = "state"
    val CommitmentFormat = "commitment-format"
    val SpliceType = "splice-type"
    val InputType = "input-type"
    val OutputType = "output-type"
    val Reason = "reason"
    val DiffSign = "sign"

    object Events {
      val Created = "created"
      val Spliced = "spliced"
      val Closing = "closing"
      val Closed = "closed"
    }

    object Origins {
      val Local = "local"
      val Remote = "remote"
    }

    object Directions {
      val Incoming = "incoming"
      val Outgoing = "outgoing"
    }

    object SpliceTypes {
      val SpliceIn = "splice-in"
      val SpliceOut = "splice-out"
      val SpliceCpfp = "splice-cpfp"
    }

    /** we can't chart negative amounts in Kamon */
    object DiffSigns {
      val plus = "plus"
      val minus = "minus"
    }
  }

}
