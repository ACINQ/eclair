package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, ScriptElt, Transaction}
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.wire.{ClosingSigned, FundingLocked, Shutdown}
import lightning.{route, route_step}

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
sealed trait State
case object WAIT_FOR_INIT_INTERNAL extends State
case object WAIT_FOR_OPEN_CHANNEL extends State
case object WAIT_FOR_ACCEPT_CHANNEL extends State
case object WAIT_FOR_FUNDING_CREATED_INTERNAL extends State
case object WAIT_FOR_FUNDING_CREATED extends State
case object WAIT_FOR_FUNDING_SIGNED extends State
case object WAIT_FOR_FUNDING_LOCKED_INTERNAL extends State
case object WAIT_FOR_FUNDING_LOCKED extends State
case object NORMAL extends State
case object SHUTDOWN extends State
case object NEGOTIATING extends State
case object CLOSING extends State
case object CLOSED extends State
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

case class INPUT_INIT_FUNDER(fundingSatoshis: Long, pushMsat: Long)
case class INPUT_INIT_FUNDEE()
case object INPUT_NO_MORE_HTLCS
// when requesting a mutual close, we wait for as much as this timeout, then unilateral close
case object INPUT_CLOSE_COMPLETE_TIMEOUT

sealed trait BitcoinEvent
case object BITCOIN_FUNDING_DEPTHOK extends BitcoinEvent
case object BITCOIN_FUNDING_LOST extends BitcoinEvent
case object BITCOIN_FUNDING_TIMEOUT extends BitcoinEvent
case object BITCOIN_FUNDING_SPENT extends BitcoinEvent
case object BITCOIN_FUNDING_OURCOMMIT_DELAYPASSED extends BitcoinEvent
case object BITCOIN_SPEND_THEIRS_DONE extends BitcoinEvent
case object BITCOIN_SPEND_OURS_DONE extends BitcoinEvent
case object BITCOIN_PUNISHMENT_DONE extends BitcoinEvent
case object BITCOIN_CLOSE_DONE extends BitcoinEvent

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

/*
  Used when relaying payments
 */
final case class Origin(channelId: BinaryData, htlc_id: Long)
/**
  * @param id should only be provided in tests otherwise it will be assigned automatically
  */
final case class CMD_ADD_HTLC(amountMsat: Long, paymentHash: BinaryData, expiry: Long, payment_route: route = route(route_step(0, next = route_step.Next.End(true)) :: Nil), origin: Option[Origin] = None, id: Option[Long] = None, commit: Boolean = false) extends Command
final case class CMD_FULFILL_HTLC(id: Long, r: BinaryData, commit: Boolean = false) extends Command
final case class CMD_FAIL_HTLC(id: Long, reason: String, commit: Boolean = false) extends Command
case object CMD_SIGN extends Command
final case class CMD_CLOSE(scriptPubKey: Option[BinaryData]) extends Command
case object CMD_GETSTATE extends Command
case object CMD_GETSTATEDATA extends Command
case object CMD_GETINFO extends Command
final case class RES_GETINFO(nodeid: BinaryData, channelId: Long, state: State, data: Data)

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
}

case class LocalCommitPublished(commitTx: Transaction, claimMainDelayedOutputTx: Option[Transaction], htlcSuccessTxs: Seq[Transaction], htlcTimeoutTxs: Seq[Transaction], claimHtlcDelayedTx: Seq[Transaction])
case class RemoteCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], claimHtlcSuccessTxs: Seq[Transaction], claimHtlcTimeoutTxs: Seq[Transaction])
case class RevokedCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[Transaction], mainPunishmentTx: Option[Transaction], claimHtlcTimeoutTxs: Seq[Transaction], htlcTimeoutTxs: Seq[Transaction], htlcPunishmentTxs: Seq[Transaction])

final case class DATA_WAIT_FOR_OPEN_CHANNEL(localParams: LocalParams, autoSignInterval: Option[FiniteDuration]) extends Data
final case class DATA_WAIT_FOR_ACCEPT_CHANNEL(temporaryChannelId: Long, localParams: LocalParams, fundingSatoshis: Long, pushMsat: Long, autoSignInterval: Option[FiniteDuration]) extends Data
final case class DATA_WAIT_FOR_FUNDING_INTERNAL(temporaryChannelId: Long, params: ChannelParams, pushMsat: Long, remoteFirstPerCommitmentPoint: Point) extends Data
final case class DATA_WAIT_FOR_FUNDING_CREATED(temporaryChannelId: Long, params: ChannelParams, pushMsat: Long, remoteFirstPerCommitmentPoint: Point) extends Data
final case class DATA_WAIT_FOR_FUNDING_SIGNED(temporaryChannelId: Long, params: ChannelParams, fundingTx: Transaction, localSpec: CommitmentSpec, localCommitTx: CommitTx, remoteCommit: RemoteCommit) extends Data
final case class DATA_WAIT_FOR_FUNDING_LOCKED_INTERNAL(temporaryChannelId: Long, params: ChannelParams, commitments: Commitments, deferred: Option[FundingLocked]) extends Data with HasCommitments
final case class DATA_NORMAL(channelId: Long, params: ChannelParams, commitments: Commitments, localShutdown: Option[Shutdown], downstreams: Map[Long, Option[Origin]]) extends Data with HasCommitments
final case class DATA_SHUTDOWN(channelId: Long, params: ChannelParams, commitments: Commitments,
                               localShutdown: Shutdown, remoteShutdown: Shutdown,
                               downstreams: Map[Long, Option[Origin]]) extends Data with HasCommitments
final case class DATA_NEGOTIATING(channelId: Long, params: ChannelParams, commitments: Commitments,
                                  localShutdown: Shutdown, remoteShutdown: Shutdown, localClosingSigned: ClosingSigned) extends Data with HasCommitments
final case class DATA_CLOSING(commitments: Commitments,
                              ourSignature: Option[ClosingSigned] = None,
                              mutualClosePublished: Option[Transaction] = None,
                              localCommitPublished: Option[LocalCommitPublished] = None,
                              remoteCommitPublished: Option[RemoteCommitPublished] = None,
                              revokedCommitPublished: Seq[RevokedCommitPublished] = Nil) extends Data with HasCommitments {
  require(mutualClosePublished.isDefined || localCommitPublished.isDefined || remoteCommitPublished.isDefined || revokedCommitPublished.size > 0, "there should be at least one tx published in this state")
}

final case class ChannelParams(localParams: LocalParams,
                               remoteParams: RemoteParams,
                               fundingSatoshis: Long,
                               minimumDepth: Long,
                               autoSignInterval: Option[FiniteDuration] = None)

final case class LocalParams(dustLimitSatoshis: Long,
                             maxHtlcValueInFlightMsat: Long,
                             channelReserveSatoshis: Long,
                             htlcMinimumMsat: Long,
                             feeratePerKw: Long,
                             toSelfDelay: Int,
                             maxAcceptedHtlcs: Int,
                             fundingPrivKey: PrivateKey,
                             revocationSecret: Scalar,
                             paymentKey: PrivateKey,
                             delayedPaymentKey: Scalar,
                             defaultFinalScriptPubKey: Seq[ScriptElt],
                             shaSeed: BinaryData,
                             isFunder: Boolean)

final case class RemoteParams(dustLimitSatoshis: Long,
                              maxHtlcValueInFlightMsat: Long,
                              channelReserveSatoshis: Long,
                              htlcMinimumMsat: Long,
                              feeratePerKw: Long,
                              toSelfDelay: Int,
                              maxAcceptedHtlcs: Int,
                              fundingPubKey: PublicKey,
                              revocationBasepoint: Point,
                              paymentBasepoint: Point,
                              delayedPaymentBasepoint: Point)

// @formatter:on
