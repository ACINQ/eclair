package fr.acinq.eclair.channel

import akka.actor.ActorRef
import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, Transaction}
import fr.acinq.eclair.blockchain.MakeFundingTxResponse
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.wire.{AcceptChannel, AnnouncementSignatures, ClosingSigned, FailureMessage, FundingCreated, FundingLocked, FundingSigned, Init, OpenChannel, Shutdown, UpdateAddHtlc}


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
case object WAIT_FOR_FUNDING_PARENT extends State
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

case class INPUT_INIT_FUNDER(temporaryChannelId: BinaryData, fundingSatoshis: Long, pushMsat: Long, initialFeeratePerKw: Long, localParams: LocalParams, remote: ActorRef, remoteInit: Init)
case class INPUT_INIT_FUNDEE(temporaryChannelId: BinaryData, localParams: LocalParams, remote: ActorRef, remoteInit: Init)
case object INPUT_CLOSE_COMPLETE_TIMEOUT // when requesting a mutual close, we wait for as much as this timeout, then unilateral close
case object INPUT_PUBLISH_LOCALCOMMIT // used in tests
case object INPUT_DISCONNECTED
case class INPUT_RECONNECTED(remote: ActorRef)
case class INPUT_RESTORED(data: HasCommitments)

sealed trait BitcoinEvent
case object BITCOIN_FUNDING_DEPTHOK extends BitcoinEvent
case object BITCOIN_FUNDING_DEEPLYBURIED extends BitcoinEvent
case object BITCOIN_FUNDING_LOST extends BitcoinEvent
case object BITCOIN_FUNDING_TIMEOUT extends BitcoinEvent
case object BITCOIN_FUNDING_SPENT extends BitcoinEvent
case object BITCOIN_HTLC_SPENT extends BitcoinEvent
case object BITCOIN_LOCALCOMMIT_DONE extends BitcoinEvent
case object BITCOIN_REMOTECOMMIT_DONE extends BitcoinEvent
case object BITCOIN_NEXTREMOTECOMMIT_DONE extends BitcoinEvent
case object BITCOIN_PENALTY_DONE extends BitcoinEvent
case object BITCOIN_CLOSE_DONE extends BitcoinEvent
case class BITCOIN_FUNDING_OTHER_CHANNEL_SPENT(shortChannelId: Long) extends BitcoinEvent
case class BITCOIN_TX_CONFIRMED(tx: Transaction) extends BitcoinEvent
case class BITCOIN_PARENT_TX_CONFIRMED(childTx: Transaction) extends BitcoinEvent
case class BITCOIN_INPUT_SPENT(tx: Transaction) extends BitcoinEvent

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
case object CMD_SIGN extends Command
final case class CMD_CLOSE(scriptPubKey: Option[BinaryData]) extends Command
case object CMD_GETSTATE extends Command
case object CMD_GETSTATEDATA extends Command
case object CMD_GETINFO extends Command
final case class RES_GETINFO(nodeid: BinaryData, channelId: BinaryData, state: State, data: Data)

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

case class LocalCommitPublished(commitTx: Transaction, claimMainDelayedOutputTx: Option[Transaction], htlcSuccessTxs: List[Transaction], htlcTimeoutTxs: List[Transaction], claimHtlcDelayedTx: List[Transaction])
case class RemoteCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], claimHtlcSuccessTxs: List[Transaction], claimHtlcTimeoutTxs: List[Transaction])
case class RevokedCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], mainPenaltyTx: Option[Transaction], claimHtlcTimeoutTxs: List[Transaction], htlcTimeoutTxs: List[Transaction], htlcPenaltyTxs: List[Transaction])

final case class DATA_WAIT_FOR_OPEN_CHANNEL(initFundee: INPUT_INIT_FUNDEE) extends Data
final case class DATA_WAIT_FOR_ACCEPT_CHANNEL(initFunder: INPUT_INIT_FUNDER, lastSent: OpenChannel) extends Data
final case class DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId: BinaryData, localParams: LocalParams, remoteParams: RemoteParams, fundingSatoshis: Long, pushMsat: Long, initialFeeratePerKw: Long, remoteFirstPerCommitmentPoint: Point, lastSent: OpenChannel) extends Data
final case class DATA_WAIT_FOR_FUNDING_PARENT(fundingResponse: MakeFundingTxResponse, parentCandidates: Set[Transaction], data: DATA_WAIT_FOR_FUNDING_INTERNAL) extends Data
final case class DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId: BinaryData, localParams: LocalParams, remoteParams: RemoteParams, fundingSatoshis: Long, pushMsat: Long, initialFeeratePerKw: Long, remoteFirstPerCommitmentPoint: Point, lastSent: AcceptChannel) extends Data
final case class DATA_WAIT_FOR_FUNDING_SIGNED(channelId: BinaryData, localParams: LocalParams, remoteParams: RemoteParams, fundingTx: Transaction, localSpec: CommitmentSpec, localCommitTx: CommitTx, remoteCommit: RemoteCommit, lastSent: FundingCreated) extends Data
final case class DATA_WAIT_FOR_FUNDING_CONFIRMED(commitments: Commitments, deferred: Option[FundingLocked], lastSent: Either[FundingCreated, FundingSigned]) extends Data with HasCommitments
final case class DATA_WAIT_FOR_FUNDING_LOCKED(commitments: Commitments, lastSent: FundingLocked) extends Data with HasCommitments
final case class DATA_NORMAL(commitments: Commitments,
                             shortChannelId: Option[Long],
                             localAnnouncementSignatures: Option[AnnouncementSignatures],
                             localShutdown: Option[Shutdown],
                             remoteShutdown: Option[Shutdown]) extends Data with HasCommitments
final case class DATA_SHUTDOWN(commitments: Commitments,
                               localShutdown: Shutdown, remoteShutdown: Shutdown) extends Data with HasCommitments
final case class DATA_NEGOTIATING(commitments: Commitments,
                                  localShutdown: Shutdown, remoteShutdown: Shutdown, localClosingSigned: ClosingSigned) extends Data with HasCommitments
final case class DATA_CLOSING(commitments: Commitments,
                              mutualClosePublished: Option[Transaction] = None,
                              localCommitPublished: Option[LocalCommitPublished] = None,
                              remoteCommitPublished: Option[RemoteCommitPublished] = None,
                              nextRemoteCommitPublished: Option[RemoteCommitPublished] = None,
                              revokedCommitPublished: List[RevokedCommitPublished] = Nil) extends Data with HasCommitments {
  require(mutualClosePublished.isDefined || localCommitPublished.isDefined || remoteCommitPublished.isDefined || nextRemoteCommitPublished.isDefined || revokedCommitPublished.size > 0, "there should be at least one tx published in this state")
}

final case class LocalParams(nodeId: PublicKey,
                             dustLimitSatoshis: Long,
                             maxHtlcValueInFlightMsat: Long,
                             channelReserveSatoshis: Long,
                             htlcMinimumMsat: Long,
                             toSelfDelay: Int,
                             maxAcceptedHtlcs: Int,
                             fundingPrivKey: PrivateKey,
                             revocationSecret: Scalar,
                             paymentKey: PrivateKey,
                             delayedPaymentKey: Scalar,
                             defaultFinalScriptPubKey: BinaryData,
                             shaSeed: BinaryData,
                             isFunder: Boolean,
                             globalFeatures: BinaryData,
                             localFeatures: BinaryData)

final case class RemoteParams(nodeId: PublicKey,
                              dustLimitSatoshis: Long,
                              maxHtlcValueInFlightMsat: Long,
                              channelReserveSatoshis: Long,
                              htlcMinimumMsat: Long,
                              toSelfDelay: Int,
                              maxAcceptedHtlcs: Int,
                              fundingPubKey: PublicKey,
                              revocationBasepoint: Point,
                              paymentBasepoint: Point,
                              delayedPaymentBasepoint: Point,
                              globalFeatures: BinaryData,
                              localFeatures: BinaryData)

// @formatter:on
