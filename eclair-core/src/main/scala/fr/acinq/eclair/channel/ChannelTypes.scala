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

import java.util.UUID

import akka.actor.{ActorRef, PossiblyHarmful}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, DeterministicWallet, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions.{AnchorOutputsCommitmentFormat, CommitTx, CommitmentFormat, DefaultCommitmentFormat}
import fr.acinq.eclair.wire.{AcceptChannel, ChannelAnnouncement, ChannelReestablish, ChannelUpdate, ClosingSigned, FailureMessage, FundingCreated, FundingLocked, FundingSigned, Init, OnionRoutingPacket, OpenChannel, Shutdown, UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, ShortChannelId, UInt64}
import scodec.bits.{BitVector, ByteVector}

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
sealed trait State
case object WAIT_FOR_INIT_INTERNAL extends State
case object WAIT_FOR_OPEN_CHANNEL extends State
case object WAIT_FOR_ACCEPT_CHANNEL extends State
case object WAIT_FOR_FUNDING_INTERNAL extends State
case object WAIT_FOR_FUNDING_CREATED extends State
case object WAIT_FOR_FUNDING_SIGNED extends State
case object WAIT_FOR_FUNDING_CONFIRMED extends State
case object WAIT_FOR_FUNDING_LOCKED extends State
case object NORMAL extends State
case object SHUTDOWN extends State
case object NEGOTIATING extends State
case object CLOSING extends State
case object CLOSED extends State
case object OFFLINE extends State
case object SYNCING extends State
case object WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT extends State
case object ERR_FUNDING_LOST extends State
case object ERR_INFORMATION_LEAK extends State

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
                             initialFeeratePerKw: FeeratePerKw,
                             fundingTxFeeratePerKw: FeeratePerKw,
                             initialRelayFees_opt: Option[(MilliSatoshi, Int)],
                             localParams: LocalParams,
                             remote: ActorRef,
                             remoteInit: Init,
                             channelFlags: Byte,
                             channelVersion: ChannelVersion)
case class INPUT_INIT_FUNDEE(temporaryChannelId: ByteVector32, localParams: LocalParams, remote: ActorRef, remoteInit: Init, channelVersion: ChannelVersion)
case object INPUT_CLOSE_COMPLETE_TIMEOUT // when requesting a mutual close, we wait for as much as this timeout, then unilateral close
case object INPUT_DISCONNECTED
case class INPUT_RECONNECTED(remote: ActorRef, localInit: Init, remoteInit: Init)
case class INPUT_RESTORED(data: HasCommitments)

sealed trait BitcoinEvent extends PossiblyHarmful
case object BITCOIN_FUNDING_PUBLISH_FAILED extends BitcoinEvent
case object BITCOIN_FUNDING_DEPTHOK extends BitcoinEvent
case object BITCOIN_FUNDING_DEEPLYBURIED extends BitcoinEvent
case object BITCOIN_FUNDING_LOST extends BitcoinEvent
case object BITCOIN_FUNDING_TIMEOUT extends BitcoinEvent
case object BITCOIN_FUNDING_SPENT extends BitcoinEvent
case object BITCOIN_OUTPUT_SPENT extends BitcoinEvent
case class BITCOIN_TX_CONFIRMED(tx: Transaction) extends BitcoinEvent
case class BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(shortChannelId: ShortChannelId) extends BitcoinEvent
case class BITCOIN_PARENT_TX_CONFIRMED(childTx: Transaction) extends BitcoinEvent

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
sealed trait CloseCommand extends HasReplyToCommand
final case class CMD_CLOSE(replyTo: ActorRef, scriptPubKey: Option[ByteVector]) extends CloseCommand
final case class CMD_FORCECLOSE(replyTo: ActorRef) extends CloseCommand
final case class CMD_UPDATE_RELAY_FEE(replyTo: ActorRef, feeBase: MilliSatoshi, feeProportionalMillionths: Long) extends HasReplyToCommand
final case class CMD_GETSTATE(replyTo: ActorRef) extends HasReplyToCommand
final case class CMD_GETSTATEDATA(replyTo: ActorRef) extends HasReplyToCommand
final case class CMD_GETINFO(replyTo: ActorRef)extends HasReplyToCommand

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
sealed trait CommandFailure[+C <: Command, +T <: Throwable] extends CommandResponse[C] { def t: Throwable }

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
  case class OnChainFail(cause: ChannelException) extends Fail
  case class Disconnected(channelUpdate: ChannelUpdate) extends Fail { assert(!Announcements.isEnabled(channelUpdate.channelFlags), "channel update must have disabled flag set") }
}
final case class RES_ADD_SETTLED[+O <: Origin, +R <: HtlcResult](origin: O, htlc: UpdateAddHtlc, result: R) extends CommandSuccess[CMD_ADD_HTLC]

/** other specific responses */
final case class RES_GETSTATE[+S <: State](state: S) extends CommandSuccess[CMD_GETSTATE]
final case class RES_GETSTATEDATA[+D <: Data](data: D) extends CommandSuccess[CMD_GETSTATEDATA]
final case class RES_GETINFO(nodeId: PublicKey, channelId: ByteVector32, state: State, data: Data) extends CommandSuccess[CMD_GETINFO]
final case class RES_CLOSE(channelId: ByteVector32) extends CommandSuccess[CMD_CLOSE]

/**
 * Those are not response to [[Command]], but to [[fr.acinq.eclair.io.Peer.OpenChannel]]
 *
 * If actor A sends a [[fr.acinq.eclair.io.Peer.OpenChannel]] and actor B sends a [[CMD_CLOSE]], then A will receive a
 * [[ChannelOpenResponse.ChannelClosed]] whereas B will receive a [[RES_CLOSE]]
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

sealed trait Data extends PossiblyHarmful {
  def channelId: ByteVector32
}

case object Nothing extends Data {
  val channelId: ByteVector32 = ByteVector32.Zeroes
}

sealed trait HasCommitments extends Data {
  val channelId: ByteVector32 = commitments.channelId
  def commitments: Commitments
}

case class ClosingTxProposed(unsignedTx: Transaction, localClosingSigned: ClosingSigned)

case class LocalCommitPublished(commitTx: Transaction, claimMainDelayedOutputTx: Option[Transaction], htlcSuccessTxs: List[Transaction], htlcTimeoutTxs: List[Transaction], claimHtlcDelayedTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32]) {
  def isConfirmed: Boolean = {
    // NB: if multiple transactions end up in the same block, the first confirmation we receive may not be the commit tx.
    // However if the confirmed tx spends from the commit tx, we know that the commit tx is already confirmed and we know
    // the type of closing.
    val confirmedTxs = irrevocablySpent.values.toSet
    (commitTx :: claimMainDelayedOutputTx.toList ::: htlcSuccessTxs ::: htlcTimeoutTxs ::: claimHtlcDelayedTxs).exists(tx => confirmedTxs.contains(tx.txid))
  }
}
case class RemoteCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], claimHtlcSuccessTxs: List[Transaction], claimHtlcTimeoutTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32]) {
  def isConfirmed: Boolean = {
    // NB: if multiple transactions end up in the same block, the first confirmation we receive may not be the commit tx.
    // However if the confirmed tx spends from the commit tx, we know that the commit tx is already confirmed and we know
    // the type of closing.
    val confirmedTxs = irrevocablySpent.values.toSet
    (commitTx :: claimMainOutputTx.toList ::: claimHtlcSuccessTxs ::: claimHtlcTimeoutTxs).exists(tx => confirmedTxs.contains(tx.txid))
  }
}
case class RevokedCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], mainPenaltyTx: Option[Transaction], htlcPenaltyTxs: List[Transaction], claimHtlcDelayedPenaltyTxs: List[Transaction], irrevocablySpent: Map[OutPoint, ByteVector32])

final case class DATA_WAIT_FOR_OPEN_CHANNEL(initFundee: INPUT_INIT_FUNDEE) extends Data {
  val channelId: ByteVector32 = initFundee.temporaryChannelId
}
final case class DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder: INPUT_INIT_FUNDER, lastSent: OpenChannel) extends Data {
  val channelId: ByteVector32 = initFunder.temporaryChannelId
}
final case class DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId: ByteVector32,
                                                localParams: LocalParams,
                                                remoteParams: RemoteParams,
                                                fundingAmount: Satoshi,
                                                pushAmount: MilliSatoshi,
                                                initialFeeratePerKw: FeeratePerKw,
                                                initialRelayFees_opt: Option[(MilliSatoshi, Int)],
                                                remoteFirstPerCommitmentPoint: PublicKey,
                                                channelVersion: ChannelVersion,
                                                lastSent: OpenChannel) extends Data {
  val channelId: ByteVector32 = temporaryChannelId
}
final case class DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId: ByteVector32,
                                               localParams: LocalParams,
                                               remoteParams: RemoteParams,
                                               fundingAmount: Satoshi,
                                               pushAmount: MilliSatoshi,
                                               initialFeeratePerKw: FeeratePerKw,
                                               initialRelayFees_opt: Option[(MilliSatoshi, Int)],
                                               remoteFirstPerCommitmentPoint: PublicKey,
                                               channelFlags: Byte,
                                               channelVersion: ChannelVersion,
                                               lastSent: AcceptChannel) extends Data {
  val channelId: ByteVector32 = temporaryChannelId
}
final case class DATA_WAIT_FOR_FUNDING_SIGNED(channelId: ByteVector32,
                                              localParams: LocalParams,
                                              remoteParams: RemoteParams,
                                              fundingTx: Transaction,
                                              fundingTxFee: Satoshi,
                                              initialRelayFees_opt: Option[(MilliSatoshi, Int)],
                                              localSpec: CommitmentSpec,
                                              localCommitTx: CommitTx,
                                              remoteCommit: RemoteCommit,
                                              channelFlags: Byte,
                                              channelVersion: ChannelVersion,
                                              lastSent: FundingCreated) extends Data
final case class DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments: Commitments,
                                                 fundingTx: Option[Transaction],
                                                 initialRelayFees_opt: Option[(MilliSatoshi, Int)],
                                                 waitingSince: Long, // how long have we been waiting for the funding tx to confirm
                                                 deferred: Option[FundingLocked],
                                                 lastSent: Either[FundingCreated, FundingSigned]) extends Data with HasCommitments
final case class DATA_WAIT_FOR_FUNDING_LOCKED(commitments: Commitments, shortChannelId: ShortChannelId, lastSent: FundingLocked, initialRelayFees_opt: Option[(MilliSatoshi, Int)]) extends Data with HasCommitments
final case class DATA_NORMAL(commitments: Commitments,
                             shortChannelId: ShortChannelId,
                             buried: Boolean,
                             channelAnnouncement: Option[ChannelAnnouncement],
                             channelUpdate: ChannelUpdate,
                             localShutdown: Option[Shutdown],
                             remoteShutdown: Option[Shutdown]) extends Data with HasCommitments
final case class DATA_SHUTDOWN(commitments: Commitments,
                               localShutdown: Shutdown, remoteShutdown: Shutdown) extends Data with HasCommitments
final case class DATA_NEGOTIATING(commitments: Commitments,
                                  localShutdown: Shutdown, remoteShutdown: Shutdown,
                                  closingTxProposed: List[List[ClosingTxProposed]], // one list for every negotiation (there can be several in case of disconnection)
                                  bestUnpublishedClosingTx_opt: Option[Transaction]) extends Data with HasCommitments {
  require(closingTxProposed.nonEmpty, "there must always be a list for the current negotiation")
  require(!commitments.localParams.isFunder || closingTxProposed.forall(_.nonEmpty), "funder must have at least one closing signature for every negotation attempt because it initiates the closing")
}
final case class DATA_CLOSING(commitments: Commitments,
                              fundingTx: Option[Transaction], // this will be non-empty if we are funder and we got in closing while waiting for our own tx to be published
                              waitingSince: Long, // how long since we initiated the closing
                              mutualCloseProposed: List[Transaction], // all exchanged closing sigs are flattened, we use this only to keep track of what publishable tx they have
                              mutualClosePublished: List[Transaction] = Nil,
                              localCommitPublished: Option[LocalCommitPublished] = None,
                              remoteCommitPublished: Option[RemoteCommitPublished] = None,
                              nextRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              futureRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              revokedCommitPublished: List[RevokedCommitPublished] = Nil) extends Data with HasCommitments {
  val spendingTxes = mutualClosePublished ::: localCommitPublished.map(_.commitTx).toList ::: remoteCommitPublished.map(_.commitTx).toList ::: nextRemoteCommitPublished.map(_.commitTx).toList ::: futureRemoteCommitPublished.map(_.commitTx).toList ::: revokedCommitPublished.map(_.commitTx)
  require(spendingTxes.nonEmpty, "there must be at least one tx published in this state")
}

final case class DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(commitments: Commitments, remoteChannelReestablish: ChannelReestablish) extends Data with HasCommitments


/**
 * @param features current connection features, or last features used if the channel is disconnected. Note that these
 *                 features are updated at each reconnection and may be different from the ones that were used when the
 *                 channel was created. See [[ChannelVersion]] for permanent features associated to a channel.
 */
final case class LocalParams(nodeId: PublicKey,
                             fundingKeyPath: DeterministicWallet.KeyPath,
                             dustLimit: Satoshi,
                             maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                             channelReserve: Satoshi,
                             htlcMinimum: MilliSatoshi,
                             toSelfDelay: CltvExpiryDelta,
                             maxAcceptedHtlcs: Int,
                             isFunder: Boolean,
                             defaultFinalScriptPubKey: ByteVector,
                             walletStaticPaymentBasepoint: Option[PublicKey],
                             features: Features)

/**
 * @param features see [[LocalParams.features]]
 */
final case class RemoteParams(nodeId: PublicKey,
                              dustLimit: Satoshi,
                              maxHtlcValueInFlightMsat: UInt64, // this is not MilliSatoshi because it can exceed the total amount of MilliSatoshi
                              channelReserve: Satoshi,
                              htlcMinimum: MilliSatoshi,
                              toSelfDelay: CltvExpiryDelta,
                              maxAcceptedHtlcs: Int,
                              fundingPubKey: PublicKey,
                              revocationBasepoint: PublicKey,
                              paymentBasepoint: PublicKey,
                              delayedPaymentBasepoint: PublicKey,
                              htlcBasepoint: PublicKey,
                              features: Features)

object ChannelFlags {
  val AnnounceChannel = 0x01.toByte
  val Empty = 0x00.toByte
}

case class ChannelVersion(bits: BitVector) {
  import ChannelVersion._

  require(bits.size == ChannelVersion.LENGTH_BITS, "channel version takes 4 bytes")

  val commitmentFormat: CommitmentFormat = if (hasAnchorOutputs) {
    AnchorOutputsCommitmentFormat
  } else {
    DefaultCommitmentFormat
  }

  def |(other: ChannelVersion) = ChannelVersion(bits | other.bits)
  def &(other: ChannelVersion) = ChannelVersion(bits & other.bits)
  def ^(other: ChannelVersion) = ChannelVersion(bits ^ other.bits)

  def isSet(bit: Int): Boolean = bits.reverse.get(bit)

  def hasPubkeyKeyPath: Boolean = isSet(USE_PUBKEY_KEYPATH_BIT)
  def hasStaticRemotekey: Boolean = isSet(USE_STATIC_REMOTEKEY_BIT)
  def hasAnchorOutputs: Boolean = isSet(USE_ANCHOR_OUTPUTS_BIT)
  /** True if our main output in the remote commitment is directly sent (without any delay) to one of our wallet addresses. */
  def paysDirectlyToWallet: Boolean = hasStaticRemotekey && !hasAnchorOutputs
}

object ChannelVersion {
  import scodec.bits._

  val LENGTH_BITS: Int = 4 * 8

  private val USE_PUBKEY_KEYPATH_BIT = 0 // bit numbers start at 0
  private val USE_STATIC_REMOTEKEY_BIT = 1
  private val USE_ANCHOR_OUTPUTS_BIT = 2

  def fromBit(bit: Int): ChannelVersion = ChannelVersion(BitVector.low(LENGTH_BITS).set(bit).reverse)

  def pickChannelVersion(localFeatures: Features, remoteFeatures: Features): ChannelVersion = {
    if (Features.canUseFeature(localFeatures, remoteFeatures, Features.AnchorOutputs)) {
      ANCHOR_OUTPUTS
    } else if (Features.canUseFeature(localFeatures, remoteFeatures, Features.StaticRemoteKey)) {
      STATIC_REMOTEKEY
    } else {
      STANDARD
    }
  }

  val ZEROES = ChannelVersion(bin"00000000000000000000000000000000")
  val STANDARD = ZEROES | fromBit(USE_PUBKEY_KEYPATH_BIT)
  val STATIC_REMOTEKEY = STANDARD | fromBit(USE_STATIC_REMOTEKEY_BIT) // PUBKEY_KEYPATH + STATIC_REMOTEKEY
  val ANCHOR_OUTPUTS = STATIC_REMOTEKEY | fromBit(USE_ANCHOR_OUTPUTS_BIT) // PUBKEY_KEYPATH + STATIC_REMOTEKEY + ANCHOR_OUTPUTS
}
// @formatter:on
