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

package fr.acinq.eclair.channel

import akka.actor.{ActorRef, PossiblyHarmful, typed}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, DeterministicWallet, OutPoint, Satoshi, SatoshiLong, Transaction, TxId, TxOut}
import fr.acinq.eclair.blockchain.fee.{ConfirmationTarget, FeeratePerKw}
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder._
import fr.acinq.eclair.channel.fund.{InteractiveTxBuilder, InteractiveTxSigningSession}
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelReady, ChannelReestablish, ChannelUpdate, ClosingSigned, CommitSig, FailureReason, FundingCreated, FundingSigned, Init, LiquidityAds, OnionRoutingPacket, OpenChannel, OpenDualFundedChannel, Shutdown, SpliceInit, Stfu, TxInitRbf, TxSignatures, UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.{Alias, BlockHeight, CltvExpiry, CltvExpiryDelta, Features, InitFeature, MilliSatoshi, MilliSatoshiLong, RealShortChannelId, TimestampMilli, UInt64}
import scodec.bits.ByteVector

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/**
 * Created by PM on 20/05/2016.
 */

// @formatter:off

/*
       .d8888b. 88888888888     d8888 88888888888 8888888888 .d8888b.
      d88P  Y88b    888        d88888     888     888       d88P  Y88b
      Y88b.         888       d88P888     888     888       Y88b.
       "Y888b.      888      d88P 888     888     8888888    "Y888b.
          "Y88b.    888     d88P  888     888     888           "Y88b.
            "888    888    d88P   888     888     888             "888
      Y88b  d88P    888   d8888888888     888     888       Y88b  d88P
       "Y8888P"     888  d88P     888     888     8888888888 "Y8888P"
 */
sealed trait ChannelState
case object WAIT_FOR_INIT_INTERNAL extends ChannelState
// Single-funder channel opening:
case object WAIT_FOR_INIT_SINGLE_FUNDED_CHANNEL extends ChannelState
case object WAIT_FOR_OPEN_CHANNEL extends ChannelState
case object WAIT_FOR_ACCEPT_CHANNEL extends ChannelState
case object WAIT_FOR_FUNDING_INTERNAL extends ChannelState
case object WAIT_FOR_FUNDING_CREATED extends ChannelState
case object WAIT_FOR_FUNDING_SIGNED extends ChannelState
case object WAIT_FOR_FUNDING_CONFIRMED extends ChannelState
case object WAIT_FOR_CHANNEL_READY extends ChannelState
// Dual-funded channel opening:
case object WAIT_FOR_INIT_DUAL_FUNDED_CHANNEL extends ChannelState
case object WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL extends ChannelState
case object WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL extends ChannelState
case object WAIT_FOR_DUAL_FUNDING_CREATED extends ChannelState
case object WAIT_FOR_DUAL_FUNDING_SIGNED extends ChannelState
case object WAIT_FOR_DUAL_FUNDING_CONFIRMED extends ChannelState
case object WAIT_FOR_DUAL_FUNDING_READY extends ChannelState
// Channel opened:
case object NORMAL extends ChannelState
case object SHUTDOWN extends ChannelState
case object NEGOTIATING extends ChannelState
case object NEGOTIATING_SIMPLE extends ChannelState
case object CLOSING extends ChannelState
case object CLOSED extends ChannelState
case object OFFLINE extends ChannelState
case object SYNCING extends ChannelState
case object WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT extends ChannelState
case object ERR_INFORMATION_LEAK extends ChannelState

/*
      8888888888 888     888 8888888888 888b    888 88888888888 .d8888b.
      888        888     888 888        8888b   888     888    d88P  Y88b
      888        888     888 888        88888b  888     888    Y88b.
      8888888    Y88b   d88P 8888888    888Y88b 888     888     "Y888b.
      888         Y88b d88P  888        888 Y88b888     888        "Y88b.
      888          Y88o88P   888        888  Y88888     888          "888
      888           Y888P    888        888   Y8888     888    Y88b  d88P
      8888888888     Y8P     8888888888 888    Y888     888     "Y8888P"
 */

case class ProposedCommitParams(localDustLimit: Satoshi,
                                localHtlcMinimum: MilliSatoshi,
                                localMaxHtlcValueInFlight: UInt64,
                                localMaxAcceptedHtlcs: Int,
                                toRemoteDelay: CltvExpiryDelta)

case class INPUT_INIT_CHANNEL_INITIATOR(temporaryChannelId: ByteVector32,
                                        fundingAmount: Satoshi,
                                        dualFunded: Boolean,
                                        commitTxFeerate: FeeratePerKw,
                                        fundingTxFeerate: FeeratePerKw,
                                        fundingTxFeeBudget_opt: Option[Satoshi],
                                        pushAmount_opt: Option[MilliSatoshi],
                                        requireConfirmedInputs: Boolean,
                                        requestFunding_opt: Option[LiquidityAds.RequestFunding],
                                        localChannelParams: LocalChannelParams,
                                        proposedCommitParams: ProposedCommitParams,
                                        remote: ActorRef,
                                        remoteInit: Init,
                                        channelFlags: ChannelFlags,
                                        channelConfig: ChannelConfig,
                                        channelType: SupportedChannelType,
                                        replyTo: akka.actor.typed.ActorRef[Peer.OpenChannelResponse]) {
  require(!(channelType.features.contains(Features.ScidAlias) && channelFlags.announceChannel), "option_scid_alias is not compatible with public channels")
}
case class INPUT_INIT_CHANNEL_NON_INITIATOR(temporaryChannelId: ByteVector32,
                                            fundingContribution_opt: Option[LiquidityAds.AddFunding],
                                            dualFunded: Boolean,
                                            pushAmount_opt: Option[MilliSatoshi],
                                            requireConfirmedInputs: Boolean,
                                            localChannelParams: LocalChannelParams,
                                            proposedCommitParams: ProposedCommitParams,
                                            remote: ActorRef,
                                            remoteInit: Init,
                                            channelConfig: ChannelConfig,
                                            channelType: SupportedChannelType)

case object INPUT_DISCONNECTED
case class INPUT_RECONNECTED(remote: ActorRef, localInit: Init, remoteInit: Init)
case class INPUT_RESTORED(data: PersistentChannelData)

/*
       .d8888b.   .d88888b.  888b     d888 888b     d888        d8888 888b    888 8888888b.   .d8888b.
      d88P  Y88b d88P" "Y88b 8888b   d8888 8888b   d8888       d88888 8888b   888 888  "Y88b d88P  Y88b
      888    888 888     888 88888b.d88888 88888b.d88888      d88P888 88888b  888 888    888 Y88b.
      888        888     888 888Y88888P888 888Y88888P888     d88P 888 888Y88b 888 888    888  "Y888b.
      888        888     888 888 Y888P 888 888 Y888P 888    d88P  888 888 Y88b888 888    888     "Y88b.
      888    888 888     888 888  Y8P  888 888  Y8P  888   d88P   888 888  Y88888 888    888       "888
      Y88b  d88P Y88b. .d88P 888   "   888 888   "   888  d8888888888 888   Y8888 888  .d88P Y88b  d88P
       "Y8888P"   "Y88888P"  888       888 888       888 d88P     888 888    Y888 8888888P"   "Y8888P"
 */

/** Detailed upstream parent(s) of a payment in the HTLC chain. */
sealed trait Upstream { def amountIn: MilliSatoshi }
object Upstream {
  /** We haven't restarted and have full information about the upstream parent(s). */
  sealed trait Hot extends Upstream {
    /**
     * Occupancy of the incoming channel (both slot and value occupancy combined) that will be compared to the outgoing confidence.
     */
    def incomingChannelOccupancy: Double
  }
  object Hot {
    /** Our node is forwarding a single incoming HTLC. */
    case class Channel(add: UpdateAddHtlc, receivedAt: TimestampMilli, receivedFrom: PublicKey, incomingChannelOccupancy: Double) extends Hot {
      override val amountIn: MilliSatoshi = add.amountMsat
      val expiryIn: CltvExpiry = add.cltvExpiry

      override def toString: String = s"Channel(amountIn=$amountIn, receivedAt=${receivedAt.toLong}, receivedFrom=${receivedFrom.toHex}, endorsement=${add.endorsement}, incomingChannelOccupancy=$incomingChannelOccupancy)"
    }
    /** Our node is forwarding a payment based on a set of HTLCs from potentially multiple upstream channels. */
    case class Trampoline(received: List[Channel]) extends Hot {
      override val amountIn: MilliSatoshi = received.map(_.add.amountMsat).sum
      // We must use the lowest expiry of the incoming HTLC set.
      val expiryIn: CltvExpiry = received.map(_.add.cltvExpiry).min
      val receivedAt: TimestampMilli = received.map(_.receivedAt).max

      override def incomingChannelOccupancy: Double = received.map(_.incomingChannelOccupancy).max

      override def toString: String = s"Trampoline(${received.map(_.toString).mkString(",")})"
    }
  }

  /** We have restarted and stored limited information about the upstream parent(s). */
  sealed trait Cold extends Upstream
  object Cold {
    def apply(hot: Hot): Cold = hot match {
      case Local(id) => Local(id)
      case Hot.Channel(add, _, _, _) => Cold.Channel(add.channelId, add.id, add.amountMsat)
      case Hot.Trampoline(received) => Cold.Trampoline(received.map(r => Cold.Channel(r.add.channelId, r.add.id, r.add.amountMsat)))
    }

    /** Our node is forwarding a single incoming HTLC. */
    case class Channel(originChannelId: ByteVector32, originHtlcId: Long, amountIn: MilliSatoshi) extends Cold
    object Channel {
      def apply(add: UpdateAddHtlc): Channel = Channel(add.channelId, add.id, add.amountMsat)
    }

    /** Our node is forwarding a payment based on a set of HTLCs from potentially multiple upstream channels. */
    case class Trampoline(originHtlcs: List[Channel]) extends Cold { override val amountIn: MilliSatoshi = originHtlcs.map(_.amountIn).sum }
  }

  /** Our node is the origin of the payment: there are no matching upstream HTLCs. */
  case class Local(id: UUID) extends Hot with Cold {
    override val amountIn: MilliSatoshi = 0 msat
    override def incomingChannelOccupancy: Double = 0.0
  }
}

/**
 * Origin of a payment, answering both questions:
 *  - what actor in the app sent that htlc and is waiting for its result?
 *  - what are the upstream parent(s) of this payment in the htlc chain?
 */
sealed trait Origin { def upstream: Upstream }
object Origin {
  /** We haven't restarted since we sent the payment downstream: the origin actor is known. */
  case class Hot(replyTo: ActorRef, upstream: Upstream.Hot) extends Origin
  /** We have restarted after the payment was sent, we have limited info and the origin actor doesn't exist anymore. */
  case class Cold(upstream: Upstream.Cold) extends Origin
  object Cold {
    def apply(hot: Hot): Cold = Cold(Upstream.Cold(hot.upstream))
  }
}

/** should not be used directly */
sealed trait Command extends PossiblyHarmful
sealed trait HasReplyToCommand extends Command { def replyTo: ActorRef }
sealed trait HasOptionalReplyToCommand extends Command { def replyTo_opt: Option[ActorRef] }

sealed trait ForbiddenCommandDuringQuiescenceNegotiation extends Command
sealed trait ForbiddenCommandWhenQuiescent extends Command

final case class CMD_ADD_HTLC(replyTo: ActorRef,
                              amount: MilliSatoshi,
                              paymentHash: ByteVector32,
                              cltvExpiry: CltvExpiry,
                              onion: OnionRoutingPacket,
                              nextPathKey_opt: Option[PublicKey],
                              reputationScore: Reputation.Score,
                              fundingFee_opt: Option[LiquidityAds.FundingFee],
                              origin: Origin.Hot,
                              commit: Boolean = false) extends HasReplyToCommand with ForbiddenCommandDuringQuiescenceNegotiation with ForbiddenCommandWhenQuiescent

case class FailureAttributionData(htlcReceivedAt: TimestampMilli, trampolineReceivedAt_opt: Option[TimestampMilli])
case class FulfillAttributionData(htlcReceivedAt: TimestampMilli, trampolineReceivedAt_opt: Option[TimestampMilli], downstreamAttribution_opt: Option[ByteVector])

sealed trait HtlcSettlementCommand extends HasOptionalReplyToCommand with ForbiddenCommandDuringQuiescenceNegotiation with ForbiddenCommandWhenQuiescent { def id: Long }
final case class CMD_FULFILL_HTLC(id: Long, r: ByteVector32, attribution_opt: Option[FulfillAttributionData], commit: Boolean = false, replyTo_opt: Option[ActorRef] = None) extends HtlcSettlementCommand
final case class CMD_FAIL_HTLC(id: Long, reason: FailureReason, attribution_opt: Option[FailureAttributionData], delay_opt: Option[FiniteDuration] = None, commit: Boolean = false, replyTo_opt: Option[ActorRef] = None) extends HtlcSettlementCommand
final case class CMD_FAIL_MALFORMED_HTLC(id: Long, onionHash: ByteVector32, failureCode: Int, commit: Boolean = false, replyTo_opt: Option[ActorRef] = None) extends HtlcSettlementCommand
final case class CMD_UPDATE_FEE(feeratePerKw: FeeratePerKw, commit: Boolean = false, replyTo_opt: Option[ActorRef] = None) extends HasOptionalReplyToCommand with ForbiddenCommandDuringQuiescenceNegotiation with ForbiddenCommandWhenQuiescent
final case class CMD_SIGN(replyTo_opt: Option[ActorRef] = None) extends HasOptionalReplyToCommand with ForbiddenCommandWhenQuiescent

final case class ClosingFees(preferred: Satoshi, min: Satoshi, max: Satoshi)
final case class ClosingFeerates(preferred: FeeratePerKw, min: FeeratePerKw, max: FeeratePerKw) {
  def computeFees(closingTxWeight: Int): ClosingFees = ClosingFees(weight2fee(preferred, closingTxWeight), weight2fee(min, closingTxWeight), weight2fee(max, closingTxWeight))
}

sealed trait CloseCommand extends HasReplyToCommand
final case class CMD_CLOSE(replyTo: ActorRef, scriptPubKey: Option[ByteVector], feerates: Option[ClosingFeerates]) extends CloseCommand with ForbiddenCommandDuringQuiescenceNegotiation with ForbiddenCommandWhenQuiescent
final case class CMD_FORCECLOSE(replyTo: ActorRef, maxClosingFeerate_opt: Option[FeeratePerKw] = None, resetFundingTxIndex_opt: Option[Int] = None) extends CloseCommand
final case class CMD_BUMP_FORCE_CLOSE_FEE(replyTo: akka.actor.typed.ActorRef[CommandResponse[CMD_BUMP_FORCE_CLOSE_FEE]], confirmationTarget: ConfirmationTarget) extends Command

sealed trait ChannelFundingCommand extends Command {
  def replyTo: akka.actor.typed.ActorRef[CommandResponse[ChannelFundingCommand]]
}
case class SpliceIn(additionalLocalFunding: Satoshi, pushAmount: MilliSatoshi = 0 msat)
case class SpliceOut(amount: Satoshi, scriptPubKey: ByteVector)
final case class CMD_SPLICE(replyTo: akka.actor.typed.ActorRef[CommandResponse[ChannelFundingCommand]], spliceIn_opt: Option[SpliceIn], spliceOut_opt: Option[SpliceOut], requestFunding_opt: Option[LiquidityAds.RequestFunding], channelType_opt:Option[ChannelType]) extends ChannelFundingCommand {
  require(spliceIn_opt.isDefined || spliceOut_opt.isDefined, "there must be a splice-in or a splice-out")
  val additionalLocalFunding: Satoshi = spliceIn_opt.map(_.additionalLocalFunding).getOrElse(0 sat)
  val pushAmount: MilliSatoshi = spliceIn_opt.map(_.pushAmount).getOrElse(0 msat)
  val spliceOutputs: List[TxOut] = spliceOut_opt.toList.map(s => TxOut(s.amount, s.scriptPubKey))
}
final case class CMD_BUMP_FUNDING_FEE(replyTo: akka.actor.typed.ActorRef[CommandResponse[ChannelFundingCommand]], targetFeerate: FeeratePerKw, fundingFeeBudget: Satoshi, lockTime: Long, requestFunding_opt: Option[LiquidityAds.RequestFunding]) extends ChannelFundingCommand
final case class CMD_UPDATE_RELAY_FEE(replyTo: ActorRef, feeBase: MilliSatoshi, feeProportionalMillionths: Long, inboundFeeBase_opt: Option[MilliSatoshi] = None, inboundFeeProportionalMillionths_opt: Option[Long]= None) extends HasReplyToCommand
final case class CMD_GET_CHANNEL_STATE(replyTo: ActorRef) extends HasReplyToCommand
final case class CMD_GET_CHANNEL_DATA(replyTo: ActorRef) extends HasReplyToCommand
final case class CMD_GET_CHANNEL_INFO(replyTo: akka.actor.typed.ActorRef[RES_GET_CHANNEL_INFO]) extends Command

/*
       88888888b.  8888888888  .d8888b.  88888888b.    ,ad8888ba,   888b      88  .d8888b.  8888888888  .d8888b.
       88      "8b 88         d88P  Y88b 88      "8b  d8"'    `"8b  8888b     88 d88P  Y88b 88         d88P  Y88b
       88      ,8P 88         Y88b.      88      ,8P d8'        `8b 88 `8b    88 Y88b.      88         Y88b.
       888888888P' 888888      "Y888b.   888888888P' 88          88 88  `8b   88  "Y888b.   888888      "Y888b.
       88    88'   88             "Y88b. 88          88          88 88   `8b  88     "Y88b. 88             "Y88b.
       88    `8b   88               "888 88          Y8,        ,8P 88    `8b 88       "888 88               "888
       88     `8b  88         Y88b  d88P 88           Y8a.    .a8P  88     `8888 Y88b  d88P 88         Y88b  d88P
       88      `8b 8888888888  "Y8888P"  88            `"Y8888Y"'   88      `888  "Y8888P"  8888888888  "Y8888P"
 */

/** response to [[Command]] requests */
sealed trait CommandResponse[+C <: Command]
sealed trait CommandSuccess[+C <: Command] extends CommandResponse[C]
sealed trait CommandFailure[+C <: Command, +T <: Throwable] extends CommandResponse[C] { def t: T }

/** generic responses */
final case class RES_SUCCESS[+C <: Command](cmd: C, channelId: ByteVector32) extends CommandSuccess[C]
final case class RES_FAILURE[+C <: Command, +T <: Throwable](cmd: C, t: T) extends CommandFailure[C, T]

/**
 * special case for [[CMD_ADD_HTLC]]
 * note that for this command there is gonna be two response patterns:
 * - either [[RES_ADD_FAILED]]
 * - or [[RES_SUCCESS[CMD_ADD_HTLC]]] followed by [[RES_ADD_SETTLED]] (possibly a while later)
 */
final case class RES_ADD_FAILED[+T <: ChannelException](c: CMD_ADD_HTLC, t: T, channelUpdate: Option[ChannelUpdate]) extends CommandFailure[CMD_ADD_HTLC, T] { override def toString = s"cannot add htlc with origin=${c.origin} reason=${t.getMessage}" }
sealed trait HtlcResult
object HtlcResult {
  sealed trait Fulfill extends HtlcResult { def paymentPreimage: ByteVector32 }
  case class RemoteFulfill(fulfill: UpdateFulfillHtlc) extends Fulfill { override val paymentPreimage: ByteVector32 = fulfill.paymentPreimage }
  case class OnChainFulfill(paymentPreimage: ByteVector32) extends Fulfill
  sealed trait Fail extends HtlcResult
  case class RemoteFail(fail: UpdateFailHtlc) extends Fail
  case class RemoteFailMalformed(fail: UpdateFailMalformedHtlc) extends Fail
  case class OnChainFail(cause: Throwable) extends Fail
  case object ChannelFailureBeforeSigned extends Fail
  case class DisconnectedBeforeSigned(channelUpdate: ChannelUpdate) extends Fail { require(!channelUpdate.channelFlags.isEnabled, "channel update must have disabled flag set") }
}
final case class RES_ADD_SETTLED[+O <: Origin, +R <: HtlcResult](origin: O, htlc: UpdateAddHtlc, result: R) extends CommandSuccess[CMD_ADD_HTLC]

/** other specific responses */
final case class RES_BUMP_FUNDING_FEE(rbfIndex: Int, fundingTxId: TxId, fee: Satoshi) extends CommandSuccess[CMD_BUMP_FUNDING_FEE]
final case class RES_SPLICE(fundingTxIndex: Long, fundingTxId: TxId, capacity: Satoshi, balance: MilliSatoshi) extends CommandSuccess[CMD_SPLICE]
final case class RES_GET_CHANNEL_STATE(state: ChannelState) extends CommandSuccess[CMD_GET_CHANNEL_STATE]
final case class RES_GET_CHANNEL_DATA[+D <: ChannelData](data: D) extends CommandSuccess[CMD_GET_CHANNEL_DATA]
final case class RES_GET_CHANNEL_INFO(nodeId: PublicKey, channelId: ByteVector32, channel: ActorRef, state: ChannelState, data: ChannelData) extends CommandSuccess[CMD_GET_CHANNEL_INFO]

/*
      8888888b.        d8888 88888888888     d8888
      888  "Y88b      d88888     888        d88888
      888    888     d88P888     888       d88P888
      888    888    d88P 888     888      d88P 888
      888    888   d88P  888     888     d88P  888
      888    888  d88P   888     888    d88P   888
      888  .d88P d8888888888     888   d8888888888
      8888888P" d88P     888     888  d88P     888
 */

case class ClosingTxProposed(unsignedTx: ClosingTx, localClosingSigned: ClosingSigned)

/**
 * When a commitment is published, we keep track of all outputs that can be spent (even if we don't yet have the data
 * to spend them, for example the preimage for received HTLCs). Once all of those outputs have been spent by a confirmed
 * transaction, the channel close is complete.
 *
 * Note that we only store transactions after they have been confirmed: we're using RBF to get transactions confirmed,
 * and it would be wasteful to store previous versions of the transactions that have been replaced.
 */
sealed trait CommitPublished {
  /** Commitment tx. */
  def commitTx: Transaction
  /** Our main output, if we had some balance in the channel. */
  def localOutput_opt: Option[OutPoint]
  /** Our anchor output, if one is available to CPFP the [[commitTx]]. */
  def anchorOutput_opt: Option[OutPoint]
  /**
   * Outputs corresponding to HTLCs that we may be able to claim (even when we don't have the preimage yet).
   * Note that some HTLC outputs of the [[commitTx]] may not be included, if we know that we will never claim them
   * (such as HTLCs that we didn't relay or that were failed downstream).
   */
  def htlcOutputs: Set[OutPoint]
  /** Map of outpoints that have been spent and the confirmed transaction that spends them. */
  def irrevocablySpent: Map[OutPoint, Transaction]
  /** Returns true if the commitment transaction is confirmed. */
  def isConfirmed: Boolean = {
    // NB: if multiple transactions end up in the same block, the first confirmation we receive may not be the commit tx.
    // However if the confirmed tx spends from the commit tx, we know that the commit tx is already confirmed and we know
    // the type of closing.
    irrevocablySpent.values.exists(tx => tx.txid == commitTx.txid) || irrevocablySpent.keys.exists(_.txid == commitTx.txid)
  }
  /**
   * Returns true when all outputs that can be claimed have been spent: we can forget the channel at that point.
   * Note that some of those outputs may be claimed by our peer (e.g. HTLCs that reached their expiry).
   */
  def isDone: Boolean
}

/**
 * Details about a force-close where we published our commitment.
 *
 * @param htlcDelayedOutputs when an HTLC transaction confirms, we must claim its output using a 3rd-stage delayed
 *                           transaction. An entry containing the corresponding output must be added to this set to
 *                           ensure that we don't forget the channel too soon, and correctly wait until we've spent it.
 */
case class LocalCommitPublished(commitTx: Transaction, localOutput_opt: Option[OutPoint], anchorOutput_opt: Option[OutPoint], incomingHtlcs: Map[OutPoint, Long], outgoingHtlcs: Map[OutPoint, Long], htlcDelayedOutputs: Set[OutPoint], irrevocablySpent: Map[OutPoint, Transaction]) extends CommitPublished {
  override val htlcOutputs: Set[OutPoint] = incomingHtlcs.keySet ++ outgoingHtlcs.keySet
  override val isDone: Boolean = {
    val mainOutputSpent = localOutput_opt.forall(o => irrevocablySpent.contains(o))
    val allHtlcsSpent = (htlcOutputs -- irrevocablySpent.keySet).isEmpty
    val allHtlcTxsSpent = (htlcDelayedOutputs -- irrevocablySpent.keySet).isEmpty
    isConfirmed && mainOutputSpent && allHtlcsSpent && allHtlcTxsSpent
  }
}

/**
 * Details about a force-close where they published their commitment (current or next).
 */
case class RemoteCommitPublished(commitTx: Transaction, localOutput_opt: Option[OutPoint], anchorOutput_opt: Option[OutPoint], incomingHtlcs: Map[OutPoint, Long], outgoingHtlcs: Map[OutPoint, Long], irrevocablySpent: Map[OutPoint, Transaction]) extends CommitPublished {
  override val htlcOutputs: Set[OutPoint] = incomingHtlcs.keySet ++ outgoingHtlcs.keySet
  override val isDone: Boolean = {
    val mainOutputSpent = localOutput_opt.forall(o => irrevocablySpent.contains(o))
    val allHtlcsSpent = (htlcOutputs -- irrevocablySpent.keySet).isEmpty
    isConfirmed && mainOutputSpent && allHtlcsSpent
  }
}

/**
 * Details about a force-close where they published one of their revoked commitments.
 * In that case, we're able to spend every output of the commitment transaction (if economical).
 *
 * @param htlcDelayedOutputs if our peer manages to get some of their HTLC transactions confirmed before our penalty
 *                           transactions, we must spend the output(s) of their HTLC transactions.
 */
case class RevokedCommitPublished(commitTx: Transaction, localOutput_opt: Option[OutPoint], remoteOutput_opt: Option[OutPoint], htlcOutputs: Set[OutPoint], htlcDelayedOutputs: Set[OutPoint], irrevocablySpent: Map[OutPoint, Transaction]) extends CommitPublished {
  // We don't use the anchor output, we can CPFP the commitment with any other output.
  override val anchorOutput_opt: Option[OutPoint] = None
  override val isDone: Boolean = {
    val mainOutputsSpent = (localOutput_opt.toSeq ++ remoteOutput_opt.toSeq).forall(o => irrevocablySpent.contains(o))
    val allHtlcsSpent = (htlcOutputs -- irrevocablySpent.keySet).isEmpty
    val allHtlcTxsSpent = (htlcDelayedOutputs -- irrevocablySpent.keySet).isEmpty
    isConfirmed && mainOutputsSpent && allHtlcsSpent && allHtlcTxsSpent
  }
}

/**
 * Short identifiers for the channel that aren't related to the on-chain utxo.
 *
 * @param localAlias      we must remember the alias that we sent to our peer because we use it to:
 *                          - identify incoming [[ChannelUpdate]] at the connection level
 *                          - route outgoing payments to that channel
 * @param remoteAlias_opt we only remember the last alias received from our peer, we use this to generate
 *                        routing hints in [[fr.acinq.eclair.payment.Bolt11Invoice]]
 */
case class ShortIdAliases(localAlias: Alias, remoteAlias_opt: Option[Alias])

sealed trait LocalFundingStatus {
  /** While the transaction is unconfirmed, we keep the funding transaction (if available) to allow rebroadcasting. */
  def signedTx_opt: Option[Transaction]
  /** We store local signatures for the purpose of retransmitting if the funding/splicing flow is interrupted. */
  def localSigs_opt: Option[TxSignatures]
  /** Basic information about the liquidity purchase negotiated in this transaction, if any. */
  def liquidityPurchase_opt: Option[LiquidityAds.PurchaseBasicInfo]
}
object LocalFundingStatus {
  sealed trait NotLocked extends LocalFundingStatus
  sealed trait Locked extends LocalFundingStatus

  sealed trait UnconfirmedFundingTx extends LocalFundingStatus
  /**
   * In single-funding, fundees only know the funding txid.
   * We also set an empty funding tx in the backward compatibility context, for channels that were in a state where we
   * didn't keep the funding tx at all, even as funder (e.g. NORMAL). However, right after restoring those channels we
   * retrieve the funding tx and update the funding status immediately.
   */
  case class SingleFundedUnconfirmedFundingTx(signedTx_opt: Option[Transaction]) extends UnconfirmedFundingTx with NotLocked {
    override val localSigs_opt: Option[TxSignatures] = None
    override val liquidityPurchase_opt: Option[LiquidityAds.PurchaseBasicInfo] = None
  }
  case class DualFundedUnconfirmedFundingTx(sharedTx: SignedSharedTransaction, createdAt: BlockHeight, fundingParams: InteractiveTxParams, liquidityPurchase_opt: Option[LiquidityAds.PurchaseBasicInfo]) extends UnconfirmedFundingTx with NotLocked {
    override val signedTx_opt: Option[Transaction] = sharedTx.signedTx_opt
    override val localSigs_opt: Option[TxSignatures] = Some(sharedTx.localSigs)
  }
  case class ZeroconfPublishedFundingTx(tx: Transaction, localSigs_opt: Option[TxSignatures], liquidityPurchase_opt: Option[LiquidityAds.PurchaseBasicInfo]) extends UnconfirmedFundingTx with Locked {
    override val signedTx_opt: Option[Transaction] = Some(tx)
  }
  case class ConfirmedFundingTx(spentInputs: Seq[OutPoint], txOut: TxOut, shortChannelId: RealShortChannelId, localSigs_opt: Option[TxSignatures], liquidityPurchase_opt: Option[LiquidityAds.PurchaseBasicInfo]) extends LocalFundingStatus with Locked {
    override val signedTx_opt: Option[Transaction] = None
  }
}

sealed trait RemoteFundingStatus
object RemoteFundingStatus {
  case object NotLocked extends RemoteFundingStatus
  case object Locked extends RemoteFundingStatus
}

sealed trait DualFundingStatus
object DualFundingStatus {
  /** We're waiting for one of the funding transactions to confirm. */
  case object WaitingForConfirmations extends DualFundingStatus
  /** We told our peer we want to RBF the funding transaction. */
  case class RbfRequested(cmd: CMD_BUMP_FUNDING_FEE) extends DualFundingStatus
  /** We both agreed to RBF and are building the new funding transaction. */
  case class RbfInProgress(cmd_opt: Option[CMD_BUMP_FUNDING_FEE], rbf: typed.ActorRef[InteractiveTxBuilder.Command], remoteCommitSig: Option[CommitSig]) extends DualFundingStatus
  /** A new funding transaction has been negotiated, we're exchanging signatures. */
  case class RbfWaitingForSigs(signingSession: InteractiveTxSigningSession.WaitingForSigs) extends DualFundingStatus
  /** The RBF attempt was aborted by us, we're waiting for our peer to ack. */
  case object RbfAborted extends DualFundingStatus
}

sealed trait CloseStatus {
  def feerates_opt: Option[ClosingFeerates]
}
object CloseStatus {
  final case class Initiator(override val feerates_opt: Option[ClosingFeerates]) extends CloseStatus
  final case class NonInitiator(override val feerates_opt: Option[ClosingFeerates]) extends CloseStatus
}

/** We're waiting for the channel to be quiescent. */
sealed trait QuiescenceNegotiation
object QuiescenceNegotiation {
  sealed trait Initiator extends QuiescenceNegotiation
  object Initiator {
    /** We stop sending new updates and wait for our updates to be added to the local and remote commitments. */
    case object QuiescenceRequested extends Initiator
    /** Our updates have been added to the local and remote commitments, we wait for our peer to do the same. */
    case class SentStfu(stfu: Stfu) extends Initiator
  }

  sealed trait NonInitiator extends QuiescenceNegotiation
  object NonInitiator {
    /** Our peer has asked us to stop sending new updates and wait for our updates to be added to the local and remote commitments. */
    case class ReceivedStfu(stfu: Stfu) extends NonInitiator
  }
}

sealed trait SpliceStatus {
  def isNegotiatingQuiescence: Boolean = this.isInstanceOf[SpliceStatus.NegotiatingQuiescence]
  def isQuiescent: Boolean = this match {
    case SpliceStatus.NoSplice | _: SpliceStatus.NegotiatingQuiescence => false
    case _ => true
  }
}
object SpliceStatus {
  case object NoSplice extends SpliceStatus
  /** We're trying to quiesce the channel in order to negotiate a splice. */
  case class NegotiatingQuiescence(cmd_opt: Option[ChannelFundingCommand], status: QuiescenceNegotiation) extends SpliceStatus
  /** The channel is quiescent, we wait for our peer to send splice_init or tx_init_rbf. */
  case object NonInitiatorQuiescent extends SpliceStatus
  /** We told our peer we want to splice funds in the channel. */
  case class SpliceRequested(cmd: CMD_SPLICE, init: SpliceInit) extends SpliceStatus
  /** We told our peer we want to RBF the latest splice transaction. */
  case class RbfRequested(cmd: CMD_BUMP_FUNDING_FEE, rbf: TxInitRbf) extends SpliceStatus
  /** We both agreed to splice/rbf and are building the corresponding transaction. */
  case class SpliceInProgress(cmd_opt: Option[ChannelFundingCommand], sessionId: ByteVector32, splice: typed.ActorRef[InteractiveTxBuilder.Command], remoteCommitSig: Option[CommitSig]) extends SpliceStatus
  /** The splice transaction has been negotiated, we're exchanging signatures. */
  case class SpliceWaitingForSigs(signingSession: InteractiveTxSigningSession.WaitingForSigs) extends SpliceStatus
  /** The splice attempt was aborted by us, we're waiting for our peer to ack. */
  case object SpliceAborted extends SpliceStatus
}

sealed trait ChannelData extends PossiblyHarmful {
  def channelId: ByteVector32
}

sealed trait TransientChannelData extends ChannelData

case object Nothing extends TransientChannelData {
  val channelId: ByteVector32 = ByteVector32.Zeroes
}

sealed trait PersistentChannelData extends ChannelData {
  def remoteNodeId: PublicKey
  def channelParams: ChannelParams
}
sealed trait ChannelDataWithoutCommitments extends PersistentChannelData {
  val channelId: ByteVector32 = channelParams.channelId
  val remoteNodeId: PublicKey = channelParams.remoteNodeId
  def channelParams: ChannelParams
}
sealed trait ChannelDataWithCommitments extends PersistentChannelData {
  val channelId: ByteVector32 = commitments.channelId
  val remoteNodeId: PublicKey = commitments.remoteNodeId
  val channelParams: ChannelParams = commitments.channelParams
  def commitments: Commitments
}

final case class DATA_WAIT_FOR_OPEN_CHANNEL(initFundee: INPUT_INIT_CHANNEL_NON_INITIATOR) extends TransientChannelData {
  val channelId: ByteVector32 = initFundee.temporaryChannelId
}
final case class DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder: INPUT_INIT_CHANNEL_INITIATOR, lastSent: OpenChannel) extends TransientChannelData {
  val channelId: ByteVector32 = initFunder.temporaryChannelId
}
final case class DATA_WAIT_FOR_FUNDING_INTERNAL(channelParams: ChannelParams,
                                                channelType: SupportedChannelType,
                                                localCommitParams: CommitParams,
                                                remoteCommitParams: CommitParams,
                                                fundingAmount: Satoshi,
                                                pushAmount: MilliSatoshi,
                                                commitTxFeerate: FeeratePerKw,
                                                remoteFundingPubKey: PublicKey,
                                                remoteFirstPerCommitmentPoint: PublicKey,
                                                replyTo: akka.actor.typed.ActorRef[Peer.OpenChannelResponse]) extends TransientChannelData {
  val channelId: ByteVector32 = channelParams.channelId
  val commitmentFormat: CommitmentFormat = channelType.commitmentFormat
}
final case class DATA_WAIT_FOR_FUNDING_CREATED(channelParams: ChannelParams,
                                               channelType: SupportedChannelType,
                                               localCommitParams: CommitParams,
                                               remoteCommitParams: CommitParams,
                                               fundingAmount: Satoshi,
                                               pushAmount: MilliSatoshi,
                                               commitTxFeerate: FeeratePerKw,
                                               remoteFundingPubKey: PublicKey,
                                               remoteFirstPerCommitmentPoint: PublicKey) extends TransientChannelData {
  val channelId: ByteVector32 = channelParams.channelId
  val commitmentFormat: CommitmentFormat = channelType.commitmentFormat
}
final case class DATA_WAIT_FOR_FUNDING_SIGNED(channelParams: ChannelParams,
                                              channelType: SupportedChannelType,
                                              localCommitParams: CommitParams,
                                              remoteCommitParams: CommitParams,
                                              remoteFundingPubKey: PublicKey,
                                              fundingTx: Transaction,
                                              fundingTxFee: Satoshi,
                                              localSpec: CommitmentSpec,
                                              localCommitTx: CommitTx,
                                              remoteCommit: RemoteCommit,
                                              lastSent: FundingCreated,
                                              replyTo: akka.actor.typed.ActorRef[Peer.OpenChannelResponse]) extends TransientChannelData {
  val channelId: ByteVector32 = channelParams.channelId
  val commitmentFormat: CommitmentFormat = channelType.commitmentFormat
}
final case class DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments: Commitments,
                                                 waitingSince: BlockHeight, // how long have we been waiting for the funding tx to confirm
                                                 deferred: Option[ChannelReady],
                                                 lastSent: Either[FundingCreated, FundingSigned]) extends ChannelDataWithCommitments {
  def fundingTx_opt: Option[Transaction] = commitments.latest.localFundingStatus.signedTx_opt
}
final case class DATA_WAIT_FOR_CHANNEL_READY(commitments: Commitments, aliases: ShortIdAliases) extends ChannelDataWithCommitments

final case class DATA_WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL(init: INPUT_INIT_CHANNEL_NON_INITIATOR) extends TransientChannelData {
  val channelId: ByteVector32 = init.temporaryChannelId
}
final case class DATA_WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL(init: INPUT_INIT_CHANNEL_INITIATOR, lastSent: OpenDualFundedChannel) extends TransientChannelData {
  val channelId: ByteVector32 = lastSent.temporaryChannelId
}
final case class DATA_WAIT_FOR_DUAL_FUNDING_CREATED(channelId: ByteVector32,
                                                    channelParams: ChannelParams,
                                                    localCommitParams: CommitParams,
                                                    remoteCommitParams: CommitParams,
                                                    secondRemotePerCommitmentPoint: PublicKey,
                                                    localPushAmount: MilliSatoshi,
                                                    remotePushAmount: MilliSatoshi,
                                                    txBuilder: typed.ActorRef[InteractiveTxBuilder.Command],
                                                    deferred: Option[CommitSig],
                                                    replyTo_opt: Option[akka.actor.typed.ActorRef[Peer.OpenChannelResponse]]) extends TransientChannelData
final case class DATA_WAIT_FOR_DUAL_FUNDING_SIGNED(channelParams: ChannelParams,
                                                   secondRemotePerCommitmentPoint: PublicKey,
                                                   localPushAmount: MilliSatoshi,
                                                   remotePushAmount: MilliSatoshi,
                                                   signingSession: InteractiveTxSigningSession.WaitingForSigs) extends ChannelDataWithoutCommitments
final case class DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(commitments: Commitments,
                                                      localPushAmount: MilliSatoshi,
                                                      remotePushAmount: MilliSatoshi,
                                                      waitingSince: BlockHeight, // how long have we been waiting for a funding tx to confirm
                                                      lastChecked: BlockHeight, // last time we checked if the channel was double-spent
                                                      status: DualFundingStatus,
                                                      deferred: Option[ChannelReady]) extends ChannelDataWithCommitments {
  def allFundingTxs: Seq[LocalFundingStatus.DualFundedUnconfirmedFundingTx] = commitments.active.map(_.localFundingStatus).collect { case fundingTx: LocalFundingStatus.DualFundedUnconfirmedFundingTx => fundingTx }
  def latestFundingTx: LocalFundingStatus.DualFundedUnconfirmedFundingTx = commitments.latest.localFundingStatus.asInstanceOf[LocalFundingStatus.DualFundedUnconfirmedFundingTx]
  def previousFundingTxs: Seq[LocalFundingStatus.DualFundedUnconfirmedFundingTx] = allFundingTxs diff Seq(latestFundingTx)
}
final case class DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments: Commitments, aliases: ShortIdAliases) extends ChannelDataWithCommitments

final case class DATA_NORMAL(commitments: Commitments,
                             aliases: ShortIdAliases,
                             lastAnnouncement_opt: Option[ChannelAnnouncement],
                             channelUpdate: ChannelUpdate,
                             spliceStatus: SpliceStatus,
                             localShutdown: Option[Shutdown],
                             remoteShutdown: Option[Shutdown],
                             closeStatus_opt: Option[CloseStatus]) extends ChannelDataWithCommitments {
  val lastAnnouncedCommitment_opt: Option[AnnouncedCommitment] = lastAnnouncement_opt.flatMap(ann => commitments.resolveCommitment(ann.shortChannelId).map(c => AnnouncedCommitment(c, ann)))
  val lastAnnouncedFundingTxId_opt: Option[TxId] = lastAnnouncedCommitment_opt.map(_.fundingTxId)
  val isNegotiatingQuiescence: Boolean = spliceStatus.isNegotiatingQuiescence
  val isQuiescent: Boolean = spliceStatus.isQuiescent
}
final case class DATA_SHUTDOWN(commitments: Commitments, localShutdown: Shutdown, remoteShutdown: Shutdown, closeStatus: CloseStatus) extends ChannelDataWithCommitments
final case class DATA_NEGOTIATING(commitments: Commitments,
                                  localShutdown: Shutdown, remoteShutdown: Shutdown,
                                  closingTxProposed: List[List[ClosingTxProposed]], // one list for every negotiation (there can be several in case of disconnection)
                                  bestUnpublishedClosingTx_opt: Option[ClosingTx]) extends ChannelDataWithCommitments {
  require(closingTxProposed.nonEmpty, "there must always be a list for the current negotiation")
  require(!commitments.localChannelParams.paysClosingFees || closingTxProposed.forall(_.nonEmpty), "initiator must have at least one closing signature for every negotiation attempt because it initiates the closing")
}
final case class DATA_NEGOTIATING_SIMPLE(commitments: Commitments,
                                         lastClosingFeerate: FeeratePerKw,
                                         localScriptPubKey: ByteVector, remoteScriptPubKey: ByteVector,
                                         // Closing transactions we created, where we pay the fees (unsigned).
                                         proposedClosingTxs: List[ClosingTxs],
                                         // Closing transactions we published: this contains our local transactions for
                                         // which they sent a signature, and their closing transactions that we signed.
                                         publishedClosingTxs: List[ClosingTx]) extends ChannelDataWithCommitments {
  def findClosingTx(tx: Transaction): Option[ClosingTx] = publishedClosingTxs.find(_.tx.txid == tx.txid).orElse(proposedClosingTxs.flatMap(_.all).find(_.tx.txid == tx.txid))
}
final case class DATA_CLOSING(commitments: Commitments,
                              waitingSince: BlockHeight, // how long since we initiated the closing
                              finalScriptPubKey: ByteVector, // where to send all on-chain funds
                              mutualCloseProposed: List[ClosingTx], // all exchanged closing sigs are flattened, we use this only to keep track of what publishable tx they have
                              mutualClosePublished: List[ClosingTx] = Nil,
                              localCommitPublished: Option[LocalCommitPublished] = None,
                              remoteCommitPublished: Option[RemoteCommitPublished] = None,
                              nextRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              futureRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              revokedCommitPublished: List[RevokedCommitPublished] = Nil,
                              maxClosingFeerate_opt: Option[FeeratePerKw] = None) extends ChannelDataWithCommitments {
  val spendingTxs: List[Transaction] = mutualClosePublished.map(_.tx) ::: localCommitPublished.map(_.commitTx).toList ::: remoteCommitPublished.map(_.commitTx).toList ::: nextRemoteCommitPublished.map(_.commitTx).toList ::: futureRemoteCommitPublished.map(_.commitTx).toList ::: revokedCommitPublished.map(_.commitTx)
  require(spendingTxs.nonEmpty, "there must be at least one tx published in this state")
}

final case class DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(commitments: Commitments, remoteChannelReestablish: ChannelReestablish) extends ChannelDataWithCommitments

/** Local params that apply for the channel's lifetime. */
case class LocalChannelParams(nodeId: PublicKey,
                              fundingKeyPath: DeterministicWallet.KeyPath,
                              // Channel reserve applied to the remote peer, if we're not using [[Features.DualFunding]] (in
                              // which case the reserve is set to 1%). If the channel is spliced, this initial value will be
                              // ignored in favor of a 1% reserve of the resulting capacity.
                              initialRequestedChannelReserve_opt: Option[Satoshi],
                              isChannelOpener: Boolean,
                              paysCommitTxFees: Boolean,
                              upfrontShutdownScript_opt: Option[ByteVector],
                              walletStaticPaymentBasepoint: Option[PublicKey],
                              // Current connection features, or last features used if the channel is disconnected. Note that
                              // these features are updated at each reconnection and may be different from the channel permanent
                              // features (see [[ChannelFeatures]]).
                              initFeatures: Features[InitFeature]) {
  // The node responsible for the commit tx fees is also the node paying the mutual close fees.
  // The other node's balance may be empty, which wouldn't allow them to pay the closing fees.
  val paysClosingFees: Boolean = paysCommitTxFees
}

/** Remote params that apply for the channel's lifetime. */
case class RemoteChannelParams(nodeId: PublicKey,
                               // See comment in LocalChannelParams for details.
                               initialRequestedChannelReserve_opt: Option[Satoshi],
                               revocationBasepoint: PublicKey,
                               paymentBasepoint: PublicKey,
                               delayedPaymentBasepoint: PublicKey,
                               htlcBasepoint: PublicKey,
                               // See comment in LocalChannelParams for details.
                               initFeatures: Features[InitFeature],
                               upfrontShutdownScript_opt: Option[ByteVector])

/** Configuration parameters that apply to local or remote commitment transactions, and may be updated dynamically. */
case class CommitParams(dustLimit: Satoshi,
                        htlcMinimum: MilliSatoshi,
                        maxHtlcValueInFlight: UInt64,
                        maxAcceptedHtlcs: Int,
                        toSelfDelay: CltvExpiryDelta)

/**
 * The [[nonInitiatorPaysCommitFees]] parameter is set to true when the sender wants the receiver to pay the commitment transaction fees.
 * This is not part of the BOLTs and won't be needed anymore once commitment transactions don't pay any on-chain fees.
 */
case class ChannelFlags(nonInitiatorPaysCommitFees: Boolean, announceChannel: Boolean) {
  override def toString: String = s"ChannelFlags(announceChannel=$announceChannel, nonInitiatorPaysCommitFees=$nonInitiatorPaysCommitFees)"
}

object ChannelFlags {
  def apply(announceChannel: Boolean): ChannelFlags = ChannelFlags(nonInitiatorPaysCommitFees = false, announceChannel = announceChannel)
}
// @formatter:on
