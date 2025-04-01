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

import fr.acinq.bitcoin.scalacompat.{Satoshi, SatoshiLong}
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.{InteractiveTxParams, SharedTransaction}
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc}
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
    val InteractiveTxFundingTargetAmount = Kamon.histogram("channels.interactive-tx-funding.target-amount", "Interactive tx funding target amount")
    val InteractiveTxFundingInputsCount = Kamon.histogram("channels.interactive-tx-funding.inputs-count", "Interactive tx funding inputs count")
    val InteractiveTxFundingHasChange = Kamon.histogram("channels.interactive-tx-funding.change", "Interactive tx funding change")
    val InteractiveTxMiningFeeDiff = Kamon.histogram("channels.interactive-tx-funding.mining-fee", "Interactive tx mining fee diff")
    val Splices = Kamon.histogram("channels.splices", "Splices")
    val ProcessMessage = Kamon.timer("channels.messages-processed")

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

    /**
     * Record parameters and results for calls to bitcoind's `fundrawtransaction`.
     */
    def recordInteractiveTxFunding(targetAmount: Satoshi, inputsCount: Int, change: Satoshi): Unit = {
      Metrics.InteractiveTxFundingTargetAmount.withoutTags().record(targetAmount.toLong)
      Metrics.InteractiveTxFundingInputsCount.withoutTags().record(inputsCount)
      Metrics.InteractiveTxFundingHasChange.withoutTags().record(change.toLong)
    }

    /**
     * Record actual vs estimated mining fee
     */
    def recordInteractiveTxMiningFeeDiff(userMiningFee: Satoshi, actualMiningFee: Satoshi): Unit = {
      // If actual mining fee is greater than what the user paid, we lose money
      val diff = userMiningFee - actualMiningFee
      Metrics.InteractiveTxMiningFeeDiff.withTag(Tags.DiffSign, if (diff > 0.sat) Tags.DiffSigns.plus else Tags.DiffSigns.minus).record(Math.abs(diff.toLong))
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
  }

  object Tags {
    val Direction = "direction"
    val Event = "event"
    val Fatal = "fatal"
    val Origin = "origin"
    val State = "state"
    val CommitmentFormat = "commitment-format"
    val Amount = "amount"
    val SpliceType = "splice-type"
    val DiffSign = "sign"

    object Events {
      val Created = "created"
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
