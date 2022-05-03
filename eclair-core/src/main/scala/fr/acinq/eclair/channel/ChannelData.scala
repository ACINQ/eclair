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

import akka.actor.{ActorRef, PossiblyHarmful}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, DeterministicWallet, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.payment.OutgoingPaymentPacket.Upstream
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.{AcceptChannel, ChannelAnnouncement, ChannelReady, ChannelReestablish, ChannelUpdate, ClosingSigned, FailureMessage, FundingCreated, FundingSigned, Init, OnionRoutingPacket, OpenChannel, Shutdown, UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, Features, InitFeature, LocalAlias, MilliSatoshi, RealShortChannelId, ShortChannelId, UInt64}
import scodec.bits.ByteVector

import java.util.UUID

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
case object WAIT_FOR_OPEN_CHANNEL extends ChannelState
case object WAIT_FOR_ACCEPT_CHANNEL extends ChannelState
case object WAIT_FOR_FUNDING_INTERNAL extends ChannelState
case object WAIT_FOR_FUNDING_CREATED extends ChannelState
case object WAIT_FOR_FUNDING_SIGNED extends ChannelState
case object WAIT_FOR_FUNDING_CONFIRMED extends ChannelState
case object WAIT_FOR_CHANNEL_READY extends ChannelState
case object NORMAL extends ChannelState
case object SHUTDOWN extends ChannelState
case object NEGOTIATING extends ChannelState
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

case class INPUT_INIT_FUNDER(temporaryChannelId: ByteVector32,
                             fundingAmount: Satoshi,
                             pushAmount: MilliSatoshi,
                             commitTxFeerate: FeeratePerKw,
                             fundingTxFeerate: FeeratePerKw,
                             localParams: LocalParams,
                             remote: ActorRef,
                             remoteInit: Init,
                             channelFlags: ChannelFlags,
                             channelConfig: ChannelConfig,
                             channelType: SupportedChannelType) {
  require(!(channelType.features.contains(Features.ScidAlias) && channelFlags.announceChannel), "option_scid_alias is not compatible with public channels")
}
case class INPUT_INIT_FUNDEE(temporaryChannelId: ByteVector32,
                             localParams: LocalParams,
                             remote: ActorRef,
                             remoteInit: Init,
                             channelConfig: ChannelConfig,
                             channelType: SupportedChannelType)
case object INPUT_CLOSE_COMPLETE_TIMEOUT // when requesting a mutual close, we wait for as much as this timeout, then unilateral close
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

/**
 * Origin of a payment, answering both questions:
 * - what actor in the app sent that htlc? (Origin.replyTo)
 * - what are the upstream parent(s) of this payment in the htlc chain?
 */
sealed trait Origin
object Origin {
  /** We haven't restarted since we sent the payment downstream: the origin actor is known. */
  sealed trait Hot extends Origin { def replyTo: ActorRef }
  /** We have restarted after the payment was sent, we have limited info and the origin actor doesn't exist anymore. */
  sealed trait Cold extends Origin

  /** Our node is the origin of the payment. */
  sealed trait Local extends Origin { def id: UUID }
  case class LocalHot(replyTo: ActorRef, id: UUID) extends Local with Hot
  case class LocalCold(id: UUID) extends Local with Cold

  /** Our node forwarded a single incoming HTLC to an outgoing channel. */
  sealed trait ChannelRelayed extends Origin {
    def originChannelId: ByteVector32
    def originHtlcId: Long
    def amountIn: MilliSatoshi
    def amountOut: MilliSatoshi
  }
  case class ChannelRelayedHot(replyTo: ActorRef, add: UpdateAddHtlc, override val amountOut: MilliSatoshi) extends ChannelRelayed with Hot {
    override def originChannelId: ByteVector32 = add.channelId
    override def originHtlcId: Long = add.id
    override def amountIn: MilliSatoshi = add.amountMsat
  }
  case class ChannelRelayedCold(originChannelId: ByteVector32, originHtlcId: Long, amountIn: MilliSatoshi, amountOut: MilliSatoshi) extends ChannelRelayed with Cold

  /** Our node forwarded an incoming HTLC set to a remote outgoing node (potentially producing multiple downstream HTLCs).*/
  sealed trait TrampolineRelayed extends Origin { def htlcs: List[(ByteVector32, Long)] }
  case class TrampolineRelayedHot(replyTo: ActorRef, adds: Seq[UpdateAddHtlc]) extends TrampolineRelayed with Hot {
    override def htlcs: List[(ByteVector32, Long)] = adds.map(u => (u.channelId, u.id)).toList
    val amountIn: MilliSatoshi = adds.map(_.amountMsat).sum
    val expiryIn: CltvExpiry = adds.map(_.cltvExpiry).min
  }
  case class TrampolineRelayedCold(override val htlcs: List[(ByteVector32, Long)]) extends TrampolineRelayed with Cold

  object Hot {
    def apply(replyTo: ActorRef, upstream: Upstream): Hot = upstream match {
      case u: Upstream.Local => Origin.LocalHot(replyTo, u.id)
      case u: Upstream.Trampoline => Origin.TrampolineRelayedHot(replyTo, u.adds)
    }
  }
}

/** should not be used directly */
sealed trait Command extends PossiblyHarmful
sealed trait HasReplyToCommand extends Command { def replyTo: ActorRef }
sealed trait HasOptionalReplyToCommand extends Command { def replyTo_opt: Option[ActorRef] }

final case class CMD_ADD_HTLC(replyTo: ActorRef, amount: MilliSatoshi, paymentHash: ByteVector32, cltvExpiry: CltvExpiry, onion: OnionRoutingPacket, origin: Origin.Hot, commit: Boolean = false) extends HasReplyToCommand
sealed trait HtlcSettlementCommand extends HasOptionalReplyToCommand { def id: Long }
final case class CMD_FULFILL_HTLC(id: Long, r: ByteVector32, commit: Boolean = false, replyTo_opt: Option[ActorRef] = None) extends HtlcSettlementCommand
final case class CMD_FAIL_HTLC(id: Long, reason: Either[ByteVector, FailureMessage], commit: Boolean = false, replyTo_opt: Option[ActorRef] = None) extends HtlcSettlementCommand
final case class CMD_FAIL_MALFORMED_HTLC(id: Long, onionHash: ByteVector32, failureCode: Int, commit: Boolean = false, replyTo_opt: Option[ActorRef] = None) extends HtlcSettlementCommand
final case class CMD_UPDATE_FEE(feeratePerKw: FeeratePerKw, commit: Boolean = false, replyTo_opt: Option[ActorRef] = None) extends HasOptionalReplyToCommand
final case class CMD_SIGN(replyTo_opt: Option[ActorRef] = None) extends HasOptionalReplyToCommand

final case class ClosingFees(preferred: Satoshi, min: Satoshi, max: Satoshi)
final case class ClosingFeerates(preferred: FeeratePerKw, min: FeeratePerKw, max: FeeratePerKw) {
  def computeFees(closingTxWeight: Int): ClosingFees = ClosingFees(weight2fee(preferred, closingTxWeight), weight2fee(min, closingTxWeight), weight2fee(max, closingTxWeight))
}

sealed trait CloseCommand extends HasReplyToCommand
final case class CMD_CLOSE(replyTo: ActorRef, scriptPubKey: Option[ByteVector], feerates: Option[ClosingFeerates]) extends CloseCommand
final case class CMD_FORCECLOSE(replyTo: ActorRef) extends CloseCommand
final case class CMD_UPDATE_RELAY_FEE(replyTo: ActorRef, feeBase: MilliSatoshi, feeProportionalMillionths: Long, cltvExpiryDelta_opt: Option[CltvExpiryDelta]) extends HasReplyToCommand
final case class CMD_GET_CHANNEL_STATE(replyTo: ActorRef) extends HasReplyToCommand
final case class CMD_GET_CHANNEL_DATA(replyTo: ActorRef) extends HasReplyToCommand
final case class CMD_GET_CHANNEL_INFO(replyTo: ActorRef)extends HasReplyToCommand

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
  case class RemoteFulfill(fulfill: UpdateFulfillHtlc) extends Fulfill { override val paymentPreimage = fulfill.paymentPreimage }
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
final case class RES_GET_CHANNEL_STATE(state: ChannelState) extends CommandSuccess[CMD_GET_CHANNEL_STATE]
final case class RES_GET_CHANNEL_DATA[+D <: ChannelData](data: D) extends CommandSuccess[CMD_GET_CHANNEL_DATA]
final case class RES_GET_CHANNEL_INFO(nodeId: PublicKey, channelId: ByteVector32, state: ChannelState, data: ChannelData) extends CommandSuccess[CMD_GET_CHANNEL_INFO]

/**
 * Those are not response to [[Command]], but to [[fr.acinq.eclair.io.Peer.OpenChannel]]
 *
 * If actor A sends a [[fr.acinq.eclair.io.Peer.OpenChannel]] and actor B sends a [[CMD_CLOSE]], then A will receive a
 * [[ChannelOpenResponse.ChannelClosed]] whereas B will receive a [[RES_SUCCESS]]
 */
sealed trait ChannelOpenResponse
object ChannelOpenResponse {
  case class ChannelOpened(channelId: ByteVector32) extends ChannelOpenResponse { override def toString  = s"created channel $channelId" }
  case class ChannelClosed(channelId: ByteVector32) extends ChannelOpenResponse { override def toString  = s"closed channel $channelId" }
}

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

sealed trait CommitPublished {
  /** Commitment tx. */
  def commitTx: Transaction
  /** Map of relevant outpoints that have been spent and the confirmed transaction that spends them. */
  def irrevocablySpent: Map[OutPoint, Transaction]

  def isConfirmed: Boolean = {
    // NB: if multiple transactions end up in the same block, the first confirmation we receive may not be the commit tx.
    // However if the confirmed tx spends from the commit tx, we know that the commit tx is already confirmed and we know
    // the type of closing.
    irrevocablySpent.values.exists(tx => tx.txid == commitTx.txid) || irrevocablySpent.keys.exists(_.txid == commitTx.txid)
  }
}

/**
 * Details about a force-close where we published our commitment.
 *
 * @param claimMainDelayedOutputTx tx claiming our main output (if we have one).
 * @param htlcTxs                  txs claiming HTLCs. There will be one entry for each pending HTLC. The value will be
 *                                 None only for incoming HTLCs for which we don't have the preimage (we can't claim them yet).
 * @param claimHtlcDelayedTxs      3rd-stage txs (spending the output of HTLC txs).
 * @param claimAnchorTxs           txs spending anchor outputs to bump the feerate of the commitment tx (if applicable).
 *                                 We currently only claim our local anchor, but it would be nice to claim both when it
 *                                 is economical to do so to avoid polluting the utxo set.
 */
case class LocalCommitPublished(commitTx: Transaction, claimMainDelayedOutputTx: Option[ClaimLocalDelayedOutputTx], htlcTxs: Map[OutPoint, Option[HtlcTx]], claimHtlcDelayedTxs: List[HtlcDelayedTx], claimAnchorTxs: List[ClaimAnchorOutputTx], irrevocablySpent: Map[OutPoint, Transaction]) extends CommitPublished {
  /**
   * A local commit is considered done when:
   * - all commitment tx outputs that we can spend have been spent and confirmed (even if the spending tx was not ours)
   * - all 3rd stage txs (txs spending htlc txs) have been confirmed
   */
  def isDone: Boolean = {
    val confirmedTxs = irrevocablySpent.values.map(_.txid).toSet
    // is the commitment tx confirmed (we need to check this because we may not have any outputs)?
    val isCommitTxConfirmed = confirmedTxs.contains(commitTx.txid)
    // is our main output confirmed (if we have one)?
    val isMainOutputConfirmed = claimMainDelayedOutputTx.forall(tx => irrevocablySpent.contains(tx.input.outPoint))
    // are all htlc outputs from the commitment tx spent (we need to check them all because we may receive preimages later)?
    val allHtlcsSpent = (htlcTxs.keySet -- irrevocablySpent.keys).isEmpty
    // are all outputs from htlc txs spent?
    val unconfirmedHtlcDelayedTxs = claimHtlcDelayedTxs.map(_.input.outPoint)
      // only the txs which parents are already confirmed may get confirmed (note that this eliminates outputs that have been double-spent by a competing tx)
      .filter(input => confirmedTxs.contains(input.txid))
      // has the tx already been confirmed?
      .filterNot(input => irrevocablySpent.contains(input))
    isCommitTxConfirmed && isMainOutputConfirmed && allHtlcsSpent && unconfirmedHtlcDelayedTxs.isEmpty
  }
}

/**
 * Details about a force-close where they published their commitment.
 *
 * @param claimMainOutputTx tx claiming our main output (if we have one).
 * @param claimHtlcTxs      txs claiming HTLCs. There will be one entry for each pending HTLC. The value will be None
 *                          only for incoming HTLCs for which we don't have the preimage (we can't claim them yet).
 * @param claimAnchorTxs    txs spending anchor outputs to bump the feerate of the commitment tx (if applicable).
 *                          We currently only claim our local anchor, but it would be nice to claim both when it is
 *                          economical to do so to avoid polluting the utxo set.
 */
case class RemoteCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[ClaimRemoteCommitMainOutputTx], claimHtlcTxs: Map[OutPoint, Option[ClaimHtlcTx]], claimAnchorTxs: List[ClaimAnchorOutputTx], irrevocablySpent: Map[OutPoint, Transaction]) extends CommitPublished {
  /**
   * A remote commit is considered done when all commitment tx outputs that we can spend have been spent and confirmed
   * (even if the spending tx was not ours).
   */
  def isDone: Boolean = {
    val confirmedTxs = irrevocablySpent.values.map(_.txid).toSet
    // is the commitment tx confirmed (we need to check this because we may not have any outputs)?
    val isCommitTxConfirmed = confirmedTxs.contains(commitTx.txid)
    // is our main output confirmed (if we have one)?
    val isMainOutputConfirmed = claimMainOutputTx.forall(tx => irrevocablySpent.contains(tx.input.outPoint))
    // are all htlc outputs from the commitment tx spent (we need to check them all because we may receive preimages later)?
    val allHtlcsSpent = (claimHtlcTxs.keySet -- irrevocablySpent.keys).isEmpty
    isCommitTxConfirmed && isMainOutputConfirmed && allHtlcsSpent
  }
}

/**
 * Details about a force-close where they published one of their revoked commitments.
 *
 * @param claimMainOutputTx          tx claiming our main output (if we have one).
 * @param mainPenaltyTx              penalty tx claiming their main output (if they have one).
 * @param htlcPenaltyTxs             penalty txs claiming every HTLC output.
 * @param claimHtlcDelayedPenaltyTxs penalty txs claiming the output of their HTLC txs (if they managed to get them confirmed before our htlcPenaltyTxs).
 */
case class RevokedCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[ClaimRemoteCommitMainOutputTx], mainPenaltyTx: Option[MainPenaltyTx], htlcPenaltyTxs: List[HtlcPenaltyTx], claimHtlcDelayedPenaltyTxs: List[ClaimHtlcDelayedOutputPenaltyTx], irrevocablySpent: Map[OutPoint, Transaction]) extends CommitPublished {
  /**
   * A revoked commit is considered done when all commitment tx outputs that we can spend have been spent and confirmed
   * (even if the spending tx was not ours).
   */
  def isDone: Boolean = {
    val confirmedTxs = irrevocablySpent.values.map(_.txid).toSet
    // is the commitment tx confirmed (we need to check this because we may not have any outputs)?
    val isCommitTxConfirmed = confirmedTxs.contains(commitTx.txid)
    // are there remaining spendable outputs from the commitment tx?
    val unspentCommitTxOutputs = {
      val commitOutputsSpendableByUs = (claimMainOutputTx.toSeq ++ mainPenaltyTx.toSeq ++ htlcPenaltyTxs).map(_.input.outPoint)
      commitOutputsSpendableByUs.toSet -- irrevocablySpent.keys
    }
    // are all outputs from htlc txs spent?
    val unconfirmedHtlcDelayedTxs = claimHtlcDelayedPenaltyTxs.map(_.input.outPoint)
      // only the txs which parents are already confirmed may get confirmed (note that this eliminates outputs that have been double-spent by a competing tx)
      .filter(input => confirmedTxs.contains(input.txid))
      // if one of the tx inputs has been spent, the tx has already been confirmed or a competing tx has been confirmed
      .filterNot(input => irrevocablySpent.contains(input))
    isCommitTxConfirmed && unspentCommitTxOutputs.isEmpty && unconfirmedHtlcDelayedTxs.isEmpty
  }
}

sealed trait ChannelData extends PossiblyHarmful {
  def channelId: ByteVector32
}

sealed trait TransientChannelData extends ChannelData

case object Nothing extends TransientChannelData {
  val channelId: ByteVector32 = ByteVector32.Zeroes
}

sealed trait PersistentChannelData extends ChannelData {
  val channelId: ByteVector32 = commitments.channelId
  def commitments: Commitments
}

final case class DATA_WAIT_FOR_OPEN_CHANNEL(initFundee: INPUT_INIT_FUNDEE) extends TransientChannelData {
  val channelId: ByteVector32 = initFundee.temporaryChannelId
}
final case class DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder: INPUT_INIT_FUNDER, lastSent: OpenChannel) extends TransientChannelData {
  val channelId: ByteVector32 = initFunder.temporaryChannelId
}
final case class DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId: ByteVector32,
                                                localParams: LocalParams,
                                                remoteParams: RemoteParams,
                                                fundingAmount: Satoshi,
                                                pushAmount: MilliSatoshi,
                                                commitTxFeerate: FeeratePerKw,
                                                remoteFirstPerCommitmentPoint: PublicKey,
                                                channelConfig: ChannelConfig,
                                                channelFeatures: ChannelFeatures,
                                                lastSent: OpenChannel) extends TransientChannelData {
  val channelId: ByteVector32 = temporaryChannelId
}
final case class DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId: ByteVector32,
                                               localParams: LocalParams,
                                               remoteParams: RemoteParams,
                                               fundingAmount: Satoshi,
                                               pushAmount: MilliSatoshi,
                                               commitTxFeerate: FeeratePerKw,
                                               remoteFirstPerCommitmentPoint: PublicKey,
                                               channelFlags: ChannelFlags,
                                               channelConfig: ChannelConfig,
                                               channelFeatures: ChannelFeatures,
                                               lastSent: AcceptChannel) extends TransientChannelData {
  val channelId: ByteVector32 = temporaryChannelId
}
final case class DATA_WAIT_FOR_FUNDING_SIGNED(channelId: ByteVector32,
                                              localParams: LocalParams,
                                              remoteParams: RemoteParams,
                                              fundingTx: Transaction,
                                              fundingTxFee: Satoshi,
                                              localSpec: CommitmentSpec,
                                              localCommitTx: CommitTx,
                                              remoteCommit: RemoteCommit,
                                              channelFlags: ChannelFlags,
                                              channelConfig: ChannelConfig,
                                              channelFeatures: ChannelFeatures,
                                              lastSent: FundingCreated) extends TransientChannelData
final case class DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments: Commitments,
                                                 fundingTx: Option[Transaction],
                                                 waitingSince: BlockHeight, // how long have we been waiting for the funding tx to confirm
                                                 deferred: Option[ChannelReady],
                                                 lastSent: Either[FundingCreated, FundingSigned]) extends PersistentChannelData
final case class DATA_WAIT_FOR_CHANNEL_READY(commitments: Commitments,
                                             realShortChannelId_opt: Option[RealShortChannelId],
                                             localAlias: LocalAlias,
                                             lastSent: ChannelReady) extends PersistentChannelData
final case class DATA_NORMAL(commitments: Commitments,
                             realShortChannelId_opt: Option[RealShortChannelId],
                             buried: Boolean,
                             channelAnnouncement: Option[ChannelAnnouncement],
                             channelUpdate: ChannelUpdate,
                             localAlias: LocalAlias,
                             remoteAlias_opt: Option[ShortChannelId],
                             localShutdown: Option[Shutdown],
                             remoteShutdown: Option[Shutdown],
                             closingFeerates: Option[ClosingFeerates]) extends PersistentChannelData
final case class DATA_SHUTDOWN(commitments: Commitments, localShutdown: Shutdown, remoteShutdown: Shutdown, closingFeerates: Option[ClosingFeerates]) extends PersistentChannelData
final case class DATA_NEGOTIATING(commitments: Commitments,
                                  localShutdown: Shutdown, remoteShutdown: Shutdown,
                                  closingTxProposed: List[List[ClosingTxProposed]], // one list for every negotiation (there can be several in case of disconnection)
                                  bestUnpublishedClosingTx_opt: Option[ClosingTx]) extends PersistentChannelData {
  require(closingTxProposed.nonEmpty, "there must always be a list for the current negotiation")
  require(!commitments.localParams.isInitiator || closingTxProposed.forall(_.nonEmpty), "initiator must have at least one closing signature for every negotiation attempt because it initiates the closing")
}
final case class DATA_CLOSING(commitments: Commitments,
                              fundingTx: Option[Transaction], // this will be non-empty if we are the initiator and we got in closing while waiting for our own tx to be published
                              waitingSince: BlockHeight, // how long since we initiated the closing
                              mutualCloseProposed: List[ClosingTx], // all exchanged closing sigs are flattened, we use this only to keep track of what publishable tx they have
                              mutualClosePublished: List[ClosingTx] = Nil,
                              localCommitPublished: Option[LocalCommitPublished] = None,
                              remoteCommitPublished: Option[RemoteCommitPublished] = None,
                              nextRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              futureRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              revokedCommitPublished: List[RevokedCommitPublished] = Nil) extends PersistentChannelData {
  val spendingTxs: List[Transaction] = mutualClosePublished.map(_.tx) ::: localCommitPublished.map(_.commitTx).toList ::: remoteCommitPublished.map(_.commitTx).toList ::: nextRemoteCommitPublished.map(_.commitTx).toList ::: futureRemoteCommitPublished.map(_.commitTx).toList ::: revokedCommitPublished.map(_.commitTx)
  require(spendingTxs.nonEmpty, "there must be at least one tx published in this state")
}

final case class DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(commitments: Commitments, remoteChannelReestablish: ChannelReestablish) extends PersistentChannelData

/**
 * @param initFeatures current connection features, or last features used if the channel is disconnected. Note that these
 *                     features are updated at each reconnection and may be different from the channel permanent features
 *                     (see [[ChannelFeatures]]).
 */
case class LocalParams(nodeId: PublicKey,
                       fundingKeyPath: DeterministicWallet.KeyPath,
                       dustLimit: Satoshi,
                       maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                       requestedChannelReserve_opt: Option[Satoshi],
                       htlcMinimum: MilliSatoshi,
                       toSelfDelay: CltvExpiryDelta,
                       maxAcceptedHtlcs: Int,
                       isInitiator: Boolean,
                       defaultFinalScriptPubKey: ByteVector,
                       walletStaticPaymentBasepoint: Option[PublicKey],
                       initFeatures: Features[InitFeature])

/**
 * @param initFeatures see [[LocalParams.initFeatures]]
 */
case class RemoteParams(nodeId: PublicKey,
                        dustLimit: Satoshi,
                        maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                        requestedChannelReserve_opt: Option[Satoshi],
                        htlcMinimum: MilliSatoshi,
                        toSelfDelay: CltvExpiryDelta,
                        maxAcceptedHtlcs: Int,
                        fundingPubKey: PublicKey,
                        revocationBasepoint: PublicKey,
                        paymentBasepoint: PublicKey,
                        delayedPaymentBasepoint: PublicKey,
                        htlcBasepoint: PublicKey,
                        initFeatures: Features[InitFeature],
                        shutdownScript: Option[ByteVector])

case class ChannelFlags(announceChannel: Boolean) {
  override def toString: String = s"ChannelFlags(announceChannel=$announceChannel)"
}
object ChannelFlags {
  val Private: ChannelFlags = ChannelFlags(announceChannel = false)
  val Public: ChannelFlags = ChannelFlags(announceChannel = true)
}
// @formatter:on
