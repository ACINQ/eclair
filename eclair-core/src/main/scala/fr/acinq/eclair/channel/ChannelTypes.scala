package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.{Point, PublicKey}
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet, OutPoint, Transaction}
import fr.acinq.eclair.{ShortChannelId, UInt64}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.wire.{AcceptChannel, ChannelAnnouncement, ChannelReestablish, ChannelUpdate, ClosingSigned, FailureMessage, FundingCreated, FundingLocked, FundingSigned, Init, OpenChannel, Shutdown, UpdateAddHtlc}


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
case object ERR_FUNDING_TIMEOUT extends State
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

case class INPUT_INIT_FUNDER(temporaryChannelId: BinaryData, fundingSatoshis: Long, pushMsat: Long, initialFeeratePerKw: Long, fundingTxFeeratePerKw: Long, localParams: LocalParams, remote: ActorRef, remoteInit: Init, channelFlags: Byte)
case class INPUT_INIT_FUNDEE(temporaryChannelId: BinaryData, localParams: LocalParams, remote: ActorRef, remoteInit: Init)
case object INPUT_CLOSE_COMPLETE_TIMEOUT // when requesting a mutual close, we wait for as much as this timeout, then unilateral close
case object INPUT_DISCONNECTED
case class INPUT_RECONNECTED(remote: ActorRef)
case class INPUT_RESTORED(data: HasCommitments)

sealed trait BitcoinEvent
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

sealed trait Command
final case class CMD_ADD_HTLC(amountMsat: Long, paymentHash: BinaryData, expiry: Long, onion: BinaryData = Sphinx.LAST_PACKET.serialize, upstream_opt: Option[UpdateAddHtlc] = None, commit: Boolean = false) extends Command
final case class CMD_FULFILL_HTLC(id: Long, r: BinaryData, commit: Boolean = false) extends Command
final case class CMD_FAIL_HTLC(id: Long, reason: Either[BinaryData, FailureMessage], commit: Boolean = false) extends Command
final case class CMD_FAIL_MALFORMED_HTLC(id: Long, onionHash: BinaryData, failureCode: Int, commit: Boolean = false) extends Command
final case class CMD_UPDATE_FEE(feeratePerKw: Long, commit: Boolean = false) extends Command
final case object CMD_SIGN extends Command
final case class CMD_CLOSE(scriptPubKey: Option[BinaryData]) extends Command
final case object CMD_FORCECLOSE extends Command
final case object CMD_GETSTATE extends Command
final case object CMD_GETSTATEDATA extends Command
final case object CMD_GETINFO extends Command
final case class RES_GETINFO(nodeId: BinaryData, channelId: BinaryData, state: State, data: Data)

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

sealed trait Data

case object Nothing extends Data

trait HasCommitments extends Data {
  def commitments: Commitments
  def channelId = commitments.channelId
}

case class ClosingTxProposed(unsignedTx: Transaction, localClosingSigned: ClosingSigned)

case class LocalCommitPublished(commitTx: Transaction, claimMainDelayedOutputTx: Option[Transaction], htlcSuccessTxs: List[Transaction], htlcTimeoutTxs: List[Transaction], claimHtlcDelayedTx: List[Transaction], irrevocablySpent: Map[OutPoint, BinaryData])
case class RemoteCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], claimHtlcSuccessTxs: List[Transaction], claimHtlcTimeoutTxs: List[Transaction], irrevocablySpent: Map[OutPoint, BinaryData])
case class RevokedCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], mainPenaltyTx: Option[Transaction], claimHtlcTimeoutTxs: List[Transaction], htlcTimeoutTxs: List[Transaction], htlcPenaltyTxs: List[Transaction], irrevocablySpent: Map[OutPoint, BinaryData])

final case class DATA_WAIT_FOR_OPEN_CHANNEL(initFundee: INPUT_INIT_FUNDEE) extends Data
final case class DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder: INPUT_INIT_FUNDER, lastSent: OpenChannel) extends Data
final case class DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId: BinaryData, localParams: LocalParams, remoteParams: RemoteParams, fundingSatoshis: Long, pushMsat: Long, initialFeeratePerKw: Long, remoteFirstPerCommitmentPoint: Point, lastSent: OpenChannel) extends Data
final case class DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId: BinaryData, localParams: LocalParams, remoteParams: RemoteParams, fundingSatoshis: Long, pushMsat: Long, initialFeeratePerKw: Long, remoteFirstPerCommitmentPoint: Point, channelFlags: Byte, lastSent: AcceptChannel) extends Data
final case class DATA_WAIT_FOR_FUNDING_SIGNED(channelId: BinaryData, localParams: LocalParams, remoteParams: RemoteParams, fundingTx: Transaction, localSpec: CommitmentSpec, localCommitTx: CommitTx, remoteCommit: RemoteCommit, channelFlags: Byte, lastSent: FundingCreated) extends Data
final case class DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments: Commitments, deferred: Option[FundingLocked], lastSent: Either[FundingCreated, FundingSigned]) extends Data with HasCommitments
final case class DATA_WAIT_FOR_FUNDING_LOCKED(commitments: Commitments, shortChannelId: ShortChannelId, lastSent: FundingLocked) extends Data with HasCommitments
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
  require(!closingTxProposed.isEmpty, "there must always be a list for the current negotiation")
  require(!commitments.localParams.isFunder || closingTxProposed.forall(!_.isEmpty), "funder must have at least one closing signature for every negotation attempt because it initiates the closing")
}
final case class DATA_CLOSING(commitments: Commitments,
                              mutualCloseProposed: List[Transaction], // all exchanged closing sigs are flattened, we use this only to keep track of what publishable tx they have
                              mutualClosePublished: List[Transaction] = Nil,
                              localCommitPublished: Option[LocalCommitPublished] = None,
                              remoteCommitPublished: Option[RemoteCommitPublished] = None,
                              nextRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              futureRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              revokedCommitPublished: List[RevokedCommitPublished] = Nil) extends Data with HasCommitments {
  val spendingTxes = mutualClosePublished ::: localCommitPublished.map(_.commitTx).toList ::: remoteCommitPublished.map(_.commitTx).toList ::: nextRemoteCommitPublished.map(_.commitTx).toList ::: futureRemoteCommitPublished.map(_.commitTx).toList ::: revokedCommitPublished.map(_.commitTx)
  require(spendingTxes.size > 0, "there must be at least one tx published in this state")
}

final case class DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(commitments: Commitments, remoteChannelReestablish: ChannelReestablish) extends Data with HasCommitments

final case class LocalParams(nodeId: PublicKey,
                             channelKeyPath: DeterministicWallet.KeyPath,
                             dustLimitSatoshis: Long,
                             maxHtlcValueInFlightMsat: UInt64,
                             channelReserveSatoshis: Long,
                             htlcMinimumMsat: Long,
                             toSelfDelay: Int,
                             maxAcceptedHtlcs: Int,
                             isFunder: Boolean,
                             defaultFinalScriptPubKey: BinaryData,
                             globalFeatures: BinaryData,
                             localFeatures: BinaryData)

final case class RemoteParams(nodeId: PublicKey,
                              dustLimitSatoshis: Long,
                              maxHtlcValueInFlightMsat: UInt64,
                              channelReserveSatoshis: Long,
                              htlcMinimumMsat: Long,
                              toSelfDelay: Int,
                              maxAcceptedHtlcs: Int,
                              fundingPubKey: PublicKey,
                              revocationBasepoint: Point,
                              paymentBasepoint: Point,
                              delayedPaymentBasepoint: Point,
                              htlcBasepoint: Point,
                              globalFeatures: BinaryData,
                              localFeatures: BinaryData)

object ChannelFlags {
  val AnnounceChannel = 0x01.toByte
  val Empty = 0x00.toByte
}
// @formatter:on
