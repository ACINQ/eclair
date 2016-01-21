package fr.acinq.eclair.channel

import akka.actor.{ActorRef, LoggingFSM, Stash}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.crypto.ShaChain
import Scripts._
import lightning._
import lightning.open_channel.anchor_offer.{WILL_CREATE_ANCHOR, WONT_CREATE_ANCHOR}
import lightning.update_decline_htlc.Reason.{CannotRoute, InsufficientFunds}

import scala.util.Try

/**
  * Created by PM on 20/08/2015.
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
case object INIT_NOANCHOR extends State
case object INIT_WITHANCHOR extends State
case object OPEN_WAIT_FOR_OPEN_NOANCHOR extends State
case object OPEN_WAIT_FOR_OPEN_WITHANCHOR extends State
case object OPEN_WAIT_FOR_ANCHOR extends State
case object OPEN_WAIT_FOR_COMMIT_SIG extends State
case object OPEN_WAITING_THEIRANCHOR extends State
case object OPEN_WAITING_OURANCHOR extends State
case object OPEN_WAIT_FOR_COMPLETE_OURANCHOR extends State
case object OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR extends State
case object NORMAL {
  def apply(priority: Priority) = priority match {
    case High => NORMAL_HIGHPRIO
    case Low => NORMAL_LOWPRIO
  }
}
case object NORMAL_HIGHPRIO extends State with HighPriority
case object NORMAL_LOWPRIO extends State with LowPriority
case object WAIT_FOR_HTLC_ACCEPT {
  def apply(priority: Priority) = priority match {
    case High => WAIT_FOR_HTLC_ACCEPT_HIGHPRIO
    case Low => WAIT_FOR_HTLC_ACCEPT_LOWPRIO
  }
}
case object WAIT_FOR_HTLC_ACCEPT_HIGHPRIO extends State with HighPriority
case object WAIT_FOR_HTLC_ACCEPT_LOWPRIO extends State with LowPriority
case object WAIT_FOR_UPDATE_SIG {
  def apply(priority: Priority) = priority match {
    case High => WAIT_FOR_UPDATE_SIG_HIGHPRIO
    case Low => WAIT_FOR_UPDATE_SIG_LOWPRIO
  }
}
case object WAIT_FOR_UPDATE_SIG_HIGHPRIO extends State with HighPriority
case object WAIT_FOR_UPDATE_SIG_LOWPRIO extends State with LowPriority
case object WAIT_FOR_UPDATE_COMPLETE {
  def apply(priority: Priority) = priority match {
    case High => WAIT_FOR_UPDATE_COMPLETE_HIGHPRIO
    case Low => WAIT_FOR_UPDATE_COMPLETE_LOWPRIO
  }
}
case object WAIT_FOR_UPDATE_COMPLETE_HIGHPRIO extends State with HighPriority
case object WAIT_FOR_UPDATE_COMPLETE_LOWPRIO extends State with LowPriority
case object WAIT_FOR_CLOSE_ACK extends State
case object WAIT_FOR_CLOSE_COMPLETE extends State
case object CLOSING extends State
case object CLOSED extends State
case object ERR_ANCHOR_LOST extends State
case object ERR_ANCHOR_TIMEOUT extends State
case object ERR_INFORMATION_LEAK extends State

sealed trait Priority {
  def invert: Priority = this match {
    case High => Low
    case Low => High
  }
}
case object High extends Priority
case object Low extends Priority
sealed trait HighPriority extends State
sealed trait LowPriority extends State

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

case object INPUT_NONE
case object INPUT_NO_MORE_HTLCS
// when requesting a mutual close, we wait for as much as this timeout, then unilateral close
case object INPUT_CLOSE_COMPLETE_TIMEOUT

sealed trait BlockchainEvent
case object BITCOIN_ANCHOR_DEPTHOK
case object BITCOIN_ANCHOR_LOST
case object BITCOIN_ANCHOR_TIMEOUT
case class BITCOIN_ANCHOR_SPENT(tx: Transaction)
case object BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED
case object BITCOIN_SPEND_THEIRS_DONE
case object BITCOIN_SPEND_OURS_DONE
case object BITCOIN_STEAL_DONE
case object BITCOIN_CLOSE_DONE

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
final case class CMD_SEND_HTLC_UPDATE(amount: Int, rHash: sha256_hash, expiry: locktime) extends Command
final case class CMD_SEND_HTLC_FULFILL(r: sha256_hash) extends Command
final case class CMD_CLOSE(fee: Long) extends Command
final case class CMD_SEND_HTLC_ROUTEFAIL(h: sha256_hash) extends Command
final case class CMD_SEND_HTLC_TIMEDOUT(h: sha256_hash) extends Command
case object CMD_GETSTATE extends Command
case object CMD_GETSTATEDATA extends Command

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
final case class AnchorInput(amount: Long, previousTxOutput: OutPoint, signData: SignData) extends Data
final case class OurChannelParams(delay: locktime, commitPrivKey: BinaryData, finalPrivKey: BinaryData, minDepth: Int, commitmentFee: Long, shaSeed: BinaryData)
final case class TheirChannelParams(delay: locktime, commitPubKey: BinaryData, finalPubKey: BinaryData, minDepth: Int, commitmentFee: Long)
final case class Commitment(index: Long, tx: Transaction, state: ChannelState, theirRevocationHash: sha256_hash)
final case class UpdateProposal(index: Long, state: ChannelState)

trait CurrentCommitment {
  def ourParams: OurChannelParams
  def theirParams: TheirChannelParams
  def shaChain: ShaChain
  def commitment: Commitment
}

final case class DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams: OurChannelParams) extends Data
final case class DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams: OurChannelParams, anchorInput: AnchorInput) extends Data
final case class DATA_OPEN_WAIT_FOR_ANCHOR(ourParams: OurChannelParams, theirParams: TheirChannelParams, theirRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorTx: Transaction, anchorOutputIndex: Int, newCommitmentUnsigned: Commitment) extends Data
final case class DATA_OPEN_WAITING(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment) extends Data with CurrentCommitment
final case class DATA_NORMAL(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment) extends Data with CurrentCommitment
final case class DATA_WAIT_FOR_UPDATE_ACCEPT(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment, updateProposal: UpdateProposal) extends Data with CurrentCommitment
final case class DATA_WAIT_FOR_HTLC_ACCEPT(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment, updateProposal: UpdateProposal) extends Data with CurrentCommitment
final case class DATA_WAIT_FOR_UPDATE_SIG(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment, newCommitmentUnsigned: Commitment) extends Data with CurrentCommitment
final case class DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment, newCommitmentTxUnsigned: Commitment) extends Data with CurrentCommitment
final case class DATA_WAIT_FOR_CLOSE_ACK(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment, mutualCloseTx: Transaction) extends Data with CurrentCommitment
final case class DATA_CLOSING(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment,
                              mutualClosePublished: Option[Transaction] = None, ourCommitPublished: Option[Transaction] = None, theirCommitPublished: Option[Transaction] = None, revokedPublished: Seq[Transaction] = Seq()) extends Data with CurrentCommitment {
  assert(mutualClosePublished.isDefined || ourCommitPublished.isDefined || theirCommitPublished.isDefined || revokedPublished.size > 0, "there should be at least one tx published in this state")
}

// @formatter:on

class Channel(val blockchain: ActorRef, val params: OurChannelParams, val anchorDataOpt: Option[AnchorInput]) extends LoggingFSM[State, Data] with Stash {

  val ourCommitPubKey = bitcoin_pubkey(ByteString.copyFrom(Crypto.publicKeyFromPrivateKey(params.commitPrivKey.key.toByteArray)))
  val ourFinalPubKey = bitcoin_pubkey(ByteString.copyFrom(Crypto.publicKeyFromPrivateKey(params.finalPrivKey.key.toByteArray)))

  // TODO
  var them: ActorRef = null

  def priority: Priority = stateName match {
    case _: HighPriority => High
    case _: LowPriority => Low
    case _ => ???
  }

  anchorDataOpt match {
    case None => startWith(INIT_NOANCHOR, Nothing)
    case Some(anchorData) => startWith(INIT_WITHANCHOR, anchorData)
  }

  /*
          8888888 888b    888 8888888 88888888888
            888   8888b   888   888       888
            888   88888b  888   888       888
            888   888Y88b 888   888       888
            888   888 Y88b888   888       888
            888   888  Y88888   888       888
            888   888   Y8888   888       888
          8888888 888    Y888 8888888     888
   */

  when(INIT_NOANCHOR) {
    case Event(INPUT_NONE, _) =>
      them = sender
      val ourParams = params
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      them ! open_channel(ourParams.delay, ourRevocationHash, ourCommitPubKey, ourFinalPubKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      goto(OPEN_WAIT_FOR_OPEN_NOANCHOR) using DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams)
  }

  when(INIT_WITHANCHOR) {
    case Event(INPUT_NONE, anchorInput: AnchorInput) =>
      them = sender
      val ourParams = params
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      them ! open_channel(ourParams.delay, ourRevocationHash, ourCommitPubKey, ourFinalPubKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      goto(OPEN_WAIT_FOR_OPEN_WITHANCHOR) using DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams, anchorInput)
  }

  when(OPEN_WAIT_FOR_OPEN_NOANCHOR) {
    case Event(open_channel(delay, theirRevocationHash, commitKey, finalKey, WILL_CREATE_ANCHOR, minDepth, commitmentFee), DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams)) =>
      val theirParams = TheirChannelParams(delay, commitKey, finalKey, minDepth.get, commitmentFee)
      goto(OPEN_WAIT_FOR_ANCHOR) using DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, theirRevocationHash)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAIT_FOR_OPEN_WITHANCHOR) {
    case Event(open_channel(delay, theirRevocationHash, commitKey, finalKey, WONT_CREATE_ANCHOR, minDepth, commitmentFee), DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams, anchorInput)) =>
      val theirParams = TheirChannelParams(delay, commitKey, finalKey, minDepth.get, commitmentFee)
      val (anchorTx, anchorOutputIndex) = makeAnchorTx(ourCommitPubKey, theirParams.commitPubKey, anchorInput.amount, anchorInput.previousTxOutput, anchorInput.signData)
      log.info(s"anchor txid=${anchorTx.hash}")
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(0, 0, Seq()), us = ChannelOneSide(anchorInput.amount - ourParams.commitmentFee, 0, Seq()))
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, TxIn(OutPoint(anchorTx.hash, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, state, ourRevocationHash, theirRevocationHash)
      them ! open_anchor(anchorTx.hash, anchorOutputIndex, anchorInput.amount, ourSigForThem)
      goto(OPEN_WAIT_FOR_COMMIT_SIG) using DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchorTx, anchorOutputIndex, Commitment(0, ourCommitTx, state, theirRevocationHash))

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAIT_FOR_ANCHOR) {
    case Event(open_anchor(anchorTxid, anchorOutputIndex, anchorAmount, theirSig), DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, theirRevocationHash)) =>
      // they fund the channel with their anchor tx, so the money is theirs
      val state = ChannelState(them = ChannelOneSide(anchorAmount - ourParams.commitmentFee, 0, Seq()), us = ChannelOneSide(0, 0, Seq()))
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, TxIn(OutPoint(anchorTxid, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, state, ourRevocationHash, theirRevocationHash)
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(OutPoint(anchorTxid, anchorOutputIndex) -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          goto(CLOSED)
        case true =>
          them ! open_commit_sig(ourSigForThem)
          blockchain ! WatchConfirmed(self, anchorTxid, ourParams.minDepth, BITCOIN_ANCHOR_DEPTHOK)
          blockchain ! WatchSpent(self, anchorTxid, anchorOutputIndex, 1, BITCOIN_ANCHOR_SPENT)
          goto(OPEN_WAITING_THEIRANCHOR) using DATA_OPEN_WAITING(ourParams, theirParams, ShaChain.init, Commitment(0, signedCommitTx, state, theirRevocationHash))
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAIT_FOR_COMMIT_SIG) {
    case Event(open_commit_sig(theirSig), DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchorTx, anchorOutputIndex, commitment)) =>
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, commitment.tx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(OutPoint(anchorTx.hash, anchorOutputIndex) -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          goto(CLOSED)
        case true =>
          blockchain ! WatchConfirmed(self, anchorTx.hash, ourParams.minDepth, BITCOIN_ANCHOR_DEPTHOK)
          blockchain ! WatchSpent(self, anchorTx.hash, anchorOutputIndex, 0, BITCOIN_ANCHOR_SPENT)
          blockchain ! Publish(anchorTx)
          goto(OPEN_WAITING_OURANCHOR) using DATA_OPEN_WAITING(ourParams, theirParams, ShaChain.init, commitment.copy(tx = signedCommitTx))
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAITING_THEIRANCHOR) {
    case Event(BITCOIN_ANCHOR_DEPTHOK, DATA_OPEN_WAITING(ourParams, theirParams, shaChain, commitment)) =>
      val anchorTxId = commitment.tx.txIn(0).outPoint.hash // commit tx only has 1 input, which is the anchor
      blockchain ! WatchLost(self, anchorTxId, ourParams.minDepth, BITCOIN_ANCHOR_LOST)
      them ! open_complete(None)
      unstashAll()
      goto(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) using DATA_NORMAL(ourParams, theirParams, shaChain, commitment)

    case Event(msg@open_complete(blockId_opt), d@DATA_OPEN_WAITING(ourParams, _, _, _)) =>
      log.info(s"received their open_complete, deferring message")
      stash()
      stay

    case Event(BITCOIN_ANCHOR_TIMEOUT, _) =>
      them ! error(Some("Anchor timed out"))
      goto(ERR_ANCHOR_TIMEOUT)

    case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: CurrentCommitment) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT, _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }

  when(OPEN_WAITING_OURANCHOR) {
    case Event(BITCOIN_ANCHOR_DEPTHOK, DATA_OPEN_WAITING(ourParams, theirParams, shaChain, commitment)) =>
      val anchorTxId = commitment.tx.txIn(0).outPoint.hash // commit tx only has 1 input, which is the anchor
      blockchain ! WatchLost(self, anchorTxId, ourParams.minDepth, BITCOIN_ANCHOR_LOST)
      them ! open_complete(None)
      unstashAll()
      goto(OPEN_WAIT_FOR_COMPLETE_OURANCHOR) using DATA_NORMAL(ourParams, theirParams, shaChain, commitment)

    case Event(msg@open_complete(blockId_opt), d@DATA_OPEN_WAITING(ourParams, _, _, _)) =>
      log.info(s"received their open_complete, deferring message")
      stash()
      stay

    case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: CurrentCommitment) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT, _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }

  when(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) {
    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      goto(NORMAL_LOWPRIO) using d

    case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: DATA_NORMAL) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT, _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }

  when(OPEN_WAIT_FOR_COMPLETE_OURANCHOR) {
    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      goto(NORMAL_HIGHPRIO) using d

    case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: DATA_NORMAL) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT, _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }


  /*
          888b     d888        d8888 8888888 888b    888      888      .d88888b.   .d88888b.  8888888b.
          8888b   d8888       d88888   888   8888b   888      888     d88P" "Y88b d88P" "Y88b 888   Y88b
          88888b.d88888      d88P888   888   88888b  888      888     888     888 888     888 888    888
          888Y88888P888     d88P 888   888   888Y88b 888      888     888     888 888     888 888   d88P
          888 Y888P 888    d88P  888   888   888 Y88b888      888     888     888 888     888 8888888P"
          888  Y8P  888   d88P   888   888   888  Y88888      888     888     888 888     888 888
          888   "   888  d8888888888   888   888   Y8888      888     Y88b. .d88P Y88b. .d88P 888
          888       888 d88P     888 8888888 888    Y888      88888888 "Y88888P"   "Y88888P"  888
   */

  def NORMAL_handler: StateFunction = {
    case Event(CMD_SEND_HTLC_UPDATE(amount, rHash, expiry), DATA_NORMAL(ourParams, theirParams, shaChain, commitment@Commitment(_, _, previousState, _))) =>
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val htlc = update_add_htlc(ourRevocationHash, amount, rHash, expiry)
      val newState = previousState.htlc_send(htlc)
      // for now we don't care if we don't have the money, in that case they will decline the request
      them ! htlc
      goto(WAIT_FOR_HTLC_ACCEPT(priority)) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, UpdateProposal(commitment.index + 1, newState))


    case Event(htlc@update_add_htlc(sha256_hash(0, 0, 0, 0), amount, rHash, expiry), d@DATA_NORMAL(ourParams, theirParams, shaChain, p@Commitment(previousCommitmentTx, previousState, _, _))) =>
      //TODO : for testing, hashes 0/0/0/0 are declined
      them ! update_decline_htlc(CannotRoute(true))
      goto(NORMAL(priority))

    case Event(htlc@update_add_htlc(theirRevocationHash, _, _, _), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      commitment.state.htlc_receive(htlc) match {
        case newState if (newState.them.pay < 0) =>
          // insufficient funds
          them ! update_decline_htlc(InsufficientFunds(funding(None)))
          stay
        case newState =>
          val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
          val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
          them ! update_accept(ourSigForThem, ourRevocationHash)
          goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))
      }

    case Event(CMD_SEND_HTLC_ROUTEFAIL(rHash), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      // we couldn't reach upstream node, so we update the commitment tx, removing the corresponding htlc
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val newState = commitment.state.htlc_remove(rHash)
      them ! update_routefail_htlc(ourRevocationHash, rHash)
      goto(WAIT_FOR_HTLC_ACCEPT(priority)) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, UpdateProposal(commitment.index + 1, newState))

    case Event(update_routefail_htlc(theirRevocationHash, rHash), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      val newState = commitment.state.htlc_remove(rHash)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))

    case Event(CMD_SEND_HTLC_TIMEDOUT(rHash), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      // the upstream node didn't provide the r value in time
      // we couldn't reach upstream node, so we update the commitment tx, removing the corresponding htlc
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val newState = commitment.state.htlc_remove(rHash)
      them ! update_timedout_htlc(ourRevocationHash, rHash)
      goto(WAIT_FOR_HTLC_ACCEPT(priority)) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, UpdateProposal(commitment.index + 1, newState))

    case Event(update_timedout_htlc(theirRevocationHash, rHash), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      val newState = commitment.state.htlc_remove(rHash)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))

    case Event(CMD_SEND_HTLC_FULFILL(r), DATA_NORMAL(ourParams, theirParams, shaChain, commitment@Commitment(_, _, previousState, _))) =>
      // we paid upstream in exchange for r, now lets gets paid
      val newState = previousState.htlc_fulfill(r)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      // Complete your HTLC: I have the R value, pay me!
      them ! update_fulfill_htlc(ourRevocationHash, r)
      goto(WAIT_FOR_HTLC_ACCEPT(priority)) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, UpdateProposal(commitment.index + 1, newState))

    case Event(update_fulfill_htlc(theirRevocationHash, r), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      val newState = commitment.state.htlc_fulfill(r)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))

    case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: DATA_NORMAL) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event(BITCOIN_ANCHOR_SPENT, _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }

  when(NORMAL_HIGHPRIO)(NORMAL_handler)

  when(NORMAL_LOWPRIO)(NORMAL_handler)

  onTransition {
    case _ -> NORMAL_HIGHPRIO => log.debug(s"my state is now ${nextStateData.asInstanceOf[DATA_NORMAL].commitment.state.prettyString()}")
    case _ -> NORMAL_LOWPRIO => log.debug(s"my state is now ${nextStateData.asInstanceOf[DATA_NORMAL].commitment.state.prettyString()}")
  }

  def WAIT_FOR_HTLC_ACCEPT_HIGHPRIO_handler: StateFunction = {
    case Event(update_accept(theirSig, theirRevocationHash), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, previousCommitment, UpdateProposal(newIndex, newState))) =>
      // counterparty replied with the signature for the new commitment tx
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, newIndex))
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, previousCommitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          publish_ourcommit(previousCommitment)
          goto(CLOSING) using DATA_CLOSING(ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
        case true =>
          val preimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index)
          them ! update_signature(ourSigForThem, preimage)
          goto(WAIT_FOR_UPDATE_COMPLETE(priority)) using DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, shaChain, previousCommitment, Commitment(newIndex, signedCommitTx, newState, theirRevocationHash))
      }

    case Event(update_decline_htlc(reason), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, previousCommitmentTx, _)) =>
      log.info(s"counterparty declined htlc update with reason=$reason")
      goto(NORMAL(priority)) using DATA_NORMAL(ourParams, theirParams, shaChain, previousCommitmentTx)

    case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: DATA_WAIT_FOR_HTLC_ACCEPT) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event(BITCOIN_ANCHOR_SPENT, _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }

  when(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO)(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO_handler)

  when(WAIT_FOR_HTLC_ACCEPT_LOWPRIO)(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO_handler orElse {
    case Event(htlc@update_add_htlc(sha256_hash(0, 0, 0, 0), amount, rHash, expiry), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, _)) =>
      //TODO : for testing, hashes 0/0/0/0 are declined
      them ! update_decline_htlc(CannotRoute(true))
      goto(NORMAL_LOWPRIO) using DATA_NORMAL(ourParams, theirParams, shaChain, commitment)

    case Event(htlc@update_add_htlc(theirRevocationHash, _, _, _), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, _)) =>
      val newState = commitment.state.htlc_receive(htlc)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))

    case Event(update_routefail_htlc(theirRevocationHash, rHash), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, _)) =>
      val newState = commitment.state.htlc_remove(rHash)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))

    case Event(update_timedout_htlc(theirRevocationHash, rHash), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, _)) =>
      val newState = commitment.state.htlc_remove(rHash)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))

    case Event(update_fulfill_htlc(theirRevocationHash, r), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, _)) =>
      val newState = commitment.state.htlc_fulfill(r)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))
  })

  def WAIT_FOR_UPDATE_SIG_handler: StateFunction = {
    case Event(update_signature(theirSig, theirRevocationPreimage), DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, previousCommitment, newCommitment)) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      val revocationHashCheck = new BinaryData(previousCommitment.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage))
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, newCommitment.tx, theirSig)
      val newSigCheck = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      (revocationHashCheck, newSigCheck) match {
        case (true, true) =>
          val preimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index)
          them ! update_complete(preimage)
          goto(NORMAL(priority.invert)) using DATA_NORMAL(ourParams, theirParams, ShaChain.addHash(shaChain, theirRevocationPreimage, previousCommitment.index), newCommitment)
        case (true, false) =>
          log.warning(s"bad signature !")
          them ! error(Some("Bad signature"))
          publish_ourcommit(previousCommitment)
          goto(CLOSING) using DATA_CLOSING(ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
        case (false, _) =>
          log.warning(s"the revocation preimage they gave us is wrong! hash=${previousCommitment.theirRevocationHash} preimage=$theirRevocationPreimage")
          them ! error(Some("Wrong preimage"))
          publish_ourcommit(previousCommitment)
          goto(CLOSING) using DATA_CLOSING(ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
      }

    case Event(cmd: CMD_CLOSE, d: CurrentCommitment) =>
      them ! handle_cmd_close(cmd, d.ourParams, d.theirParams, d.commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: DATA_WAIT_FOR_UPDATE_SIG) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event(BITCOIN_ANCHOR_SPENT, _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))

  }

  when(WAIT_FOR_UPDATE_SIG_HIGHPRIO)(WAIT_FOR_UPDATE_SIG_handler)

  when(WAIT_FOR_UPDATE_SIG_LOWPRIO)(WAIT_FOR_UPDATE_SIG_handler)

  def WAIT_FOR_UPDATE_COMPLETE_handler: StateFunction = {
    case Event(update_complete(theirRevocationPreimage), DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, shaChain, previousCommitment, newCommitment)) =>
      val ok = new BinaryData(previousCommitment.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage))
      ok match {
        case false =>
          log.warning(s"the revocation preimage they gave us is wrong! hash=${previousCommitment.theirRevocationHash} preimage=$theirRevocationPreimage")
          them ! error(Some("Wrong preimage"))
          publish_ourcommit(previousCommitment)
          goto(CLOSING) using DATA_CLOSING(ourParams, theirParams, shaChain, previousCommitment, ourCommitPublished = Some(previousCommitment.tx))
        case true =>
          goto(NORMAL(priority.invert)) using DATA_NORMAL(ourParams, theirParams, ShaChain.addHash(shaChain, theirRevocationPreimage, previousCommitment.index), newCommitment)
      }

    case Event(pkt: close_channel, d: CurrentCommitment) =>
      val (finalTx, res) = handle_pkt_close(pkt, d.ourParams, d.theirParams, d.commitment)
      them ! res
      goto(WAIT_FOR_CLOSE_ACK) using DATA_WAIT_FOR_CLOSE_ACK(d.ourParams, d.theirParams, d.shaChain, d.commitment, finalTx)

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: DATA_WAIT_FOR_UPDATE_COMPLETE) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event(BITCOIN_ANCHOR_SPENT, _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, d: CurrentCommitment) =>
      publish_ourcommit(d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
  }

  when(WAIT_FOR_UPDATE_COMPLETE_HIGHPRIO)(WAIT_FOR_UPDATE_COMPLETE_handler)

  when(WAIT_FOR_UPDATE_COMPLETE_LOWPRIO)(WAIT_FOR_UPDATE_COMPLETE_handler)

  /*
           .d8888b.  888      .d88888b.   .d8888b. 8888888 888b    888  .d8888b.
          d88P  Y88b 888     d88P" "Y88b d88P  Y88b  888   8888b   888 d88P  Y88b
          888    888 888     888     888 Y88b.       888   88888b  888 888    888
          888        888     888     888  "Y888b.    888   888Y88b 888 888
          888        888     888     888     "Y88b.  888   888 Y88b888 888  88888
          888    888 888     888     888       "888  888   888  Y88888 888    888
          Y88b  d88P 888     Y88b. .d88P Y88b  d88P  888   888   Y8888 Y88b  d88P
           "Y8888P"  88888888 "Y88888P"   "Y8888P" 8888888 888    Y888  "Y8888P88
   */

  when(WAIT_FOR_CLOSE_COMPLETE) {
    case Event(close_channel_complete(theirSig), d: CurrentCommitment) =>
      val finalTx = makeFinalTx(d.commitment.tx.txIn, ourFinalPubKey, d.theirParams.finalPubKey, d.commitment.state)
      val signedFinalTx = sign_our_commitment_tx(d.ourParams, d.theirParams, finalTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedFinalTx, Map(signedFinalTx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, d.theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          publish_ourcommit(d.commitment)
          goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, ourCommitPublished = Some(d.commitment.tx))
        case true =>
          them ! close_channel_ack()
          blockchain ! WatchConfirmed(self, signedFinalTx.hash, d.ourParams.minDepth, BITCOIN_CLOSE_DONE)
          blockchain ! Publish(signedFinalTx)
          goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(signedFinalTx))
      }

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: CurrentCommitment) if (isMutualClose(tx, d.ourParams, d.theirParams, d.commitment)) =>
      // it is possible that we received this before the close_channel_complete, we may still receive the latter
      log.info(s"mutual close detected: $tx")
      stay

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: CurrentCommitment) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event(BITCOIN_ANCHOR_SPENT, _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(BITCOIN_CLOSE_DONE, _) => goto(CLOSED)
  }

  /**
    * At this point we have already published the closing tx
    */
  when(WAIT_FOR_CLOSE_ACK) {
    case Event(close_channel_ack(), d: DATA_WAIT_FOR_CLOSE_ACK) =>
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(d.mutualCloseTx))

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: CurrentCommitment) if (isMutualClose(tx, d.ourParams, d.theirParams, d.commitment)) =>
      // it is possible that we received this before the close_channel_ack, we may still receive the latter
      log.info(s"mutual close detected: $tx")
      stay

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: CurrentCommitment) if (isTheirCommit(tx, d.ourParams, d.theirParams, d.commitment)) =>
      them ! handle_theircommit(tx, d.ourParams, d.theirParams, d.shaChain, d.commitment)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: CurrentCommitment) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, revokedPublished = tx :: Nil)

    case Event(BITCOIN_ANCHOR_SPENT, _) =>
      goto(ERR_INFORMATION_LEAK)

    case Event(BITCOIN_CLOSE_DONE, _) => goto(CLOSED)

    case Event(pkt: error, d: DATA_WAIT_FOR_CLOSE_ACK) =>
      // no-op, because at this point we have already published the mutual close tx on the blockchain
      goto(CLOSING) using DATA_CLOSING(d.ourParams, d.theirParams, d.shaChain, d.commitment, mutualClosePublished = Some(d.mutualCloseTx))
  }

  /**
    * We enter this state when the anchor is spent by at least one tx
    * We leave this state when tx (or txes) spending the spending tx is buried deep enough in the blockchain
    */
  when(CLOSING) {

    case Event(BITCOIN_ANCHOR_SPENT(tx), d@DATA_CLOSING(ourParams, theirParams, _, commitment, _, _, _, _)) if (isMutualClose(tx, ourParams, theirParams, commitment)) =>
      log.info(s"mutual close detected: $tx")
      // wait for BITCOIN_CLOSE_DONE
      // should we override the previous tx? (which may be different because of malleability)
      stay using d.copy(mutualClosePublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT(tx), DATA_CLOSING(_, _, _, commitment, _, _, _, _)) if (isOurCommit(tx, commitment)) =>
      log.info(s"our commit detected: $tx")
      handle_ourcommit()
      stay

    case Event(BITCOIN_ANCHOR_SPENT(tx), d@DATA_CLOSING(ourParams, theirParams, shaChain, commitment, _, _, _, _)) if (isTheirCommit(tx, ourParams, theirParams, commitment)) =>
      handle_theircommit(tx, ourParams, theirParams, shaChain, commitment)
      stay using d.copy(theirCommitPublished = Some(tx))

    case Event(BITCOIN_ANCHOR_SPENT(tx), d: DATA_CLOSING) if (isRevokedCommit(tx)) =>
      them ! handle_revoked(tx)
      stay using d.copy(revokedPublished = tx +: d.revokedPublished)

    case Event(BITCOIN_ANCHOR_SPENT(tx), _) =>
      // somebody managed to spend the anchor...
      // we're fucked
      goto(ERR_INFORMATION_LEAK)

    case Event(INPUT_NO_MORE_HTLCS, _) =>
      // should we do something ???
      // wait for BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED
      stay

    case Event(BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED, DATA_CLOSING(ourParams, theirParams, _, _, _, Some(ourCommitPublished), _, _)) =>
      handle_ourcommit_delaypassed()
      stay

    case Event(pkt: error, _) =>
      // there is nothing to do here
      stay

    case Event(BITCOIN_CLOSE_DONE, _) => goto(CLOSED)

    case Event(BITCOIN_SPEND_OURS_DONE, _) => goto(CLOSED)

    case Event(BITCOIN_SPEND_THEIRS_DONE, _) => goto(CLOSED)

    case Event(BITCOIN_STEAL_DONE, _) => goto(CLOSED)
  }

  when(CLOSED) {
    case _ if false => stay // we don't want this to match so that whenUnhandled works
  }

  whenUnhandled {
    case Event(BITCOIN_ANCHOR_LOST, _) => goto(ERR_ANCHOR_LOST)

    case Event(CMD_GETSTATE, _) =>
      sender ! stateName
      stay

    case Event(CMD_GETSTATEDATA, _) =>
      sender ! stateData
      stay

    // TODO : them ! error(Some("Unexpected message")) ?

  }

  /*
          888    888 8888888888 888      8888888b.  8888888888 8888888b.   .d8888b.
          888    888 888        888      888   Y88b 888        888   Y88b d88P  Y88b
          888    888 888        888      888    888 888        888    888 Y88b.
          8888888888 8888888    888      888   d88P 8888888    888   d88P  "Y888b.
          888    888 888        888      8888888P"  888        8888888P"      "Y88b.
          888    888 888        888      888        888        888 T88b         "888
          888    888 888        888      888        888        888  T88b  Y88b  d88P
          888    888 8888888888 88888888 888        8888888888 888   T88b  "Y8888P"
  */

  def isMutualClose(tx: Transaction, comourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: Commitment): Boolean = {
    // we rebuild the closing tx as seen by both parties
    // TODO : should they be equal ?
    val theirFinalTx = makeFinalTx(commitment.tx.txIn, theirParams.finalPubKey, ourFinalPubKey, commitment.state.reverse)
    val ourFinalTx = makeFinalTx(commitment.tx.txIn, ourFinalPubKey, theirParams.finalPubKey, commitment.state)
    // and only compare the outputs
    tx.txOut == theirFinalTx.txOut || tx.txOut == ourFinalTx.txOut
  }

  def isOurCommit(tx: Transaction, commitment: Commitment): Boolean = tx == commitment.tx

  def isTheirCommit(tx: Transaction, ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: Commitment): Boolean = {
    // we rebuild their commitment tx
    val theirCommitTx = makeCommitTx(commitment.tx.txIn, theirParams.finalPubKey, ourFinalPubKey, ourParams.delay, commitment.theirRevocationHash, commitment.state.reverse)
    // and only compare the outputs
    tx.txOut == theirCommitTx.txOut
  }

  def isRevokedCommit(tx: Transaction): Boolean = {
    // TODO : for now we assume that every published tx which is none of (mutualclose, ourcommit, theircommit) is a revoked commit
    // which means ERR_INFORMATION_LEAK will never occur
    true
  }

  def sign_their_commitment_tx(ourParams: OurChannelParams, theirParams: TheirChannelParams, inputs: Seq[TxIn], newState: ChannelState, ourRevocationHash: sha256_hash, theirRevocationHash: sha256_hash): (Transaction, signature) = {
    // we build our side of the new commitment tx
    val ourCommitTx = makeCommitTx(inputs, ourFinalPubKey, theirParams.finalPubKey, theirParams.delay, ourRevocationHash, newState)
    // we build their commitment tx and sign it
    val theirCommitTx = makeCommitTx(inputs, theirParams.finalPubKey, ourFinalPubKey, ourParams.delay, theirRevocationHash, newState.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
    (ourCommitTx, ourSigForThem)
  }

  def sign_our_commitment_tx(ourParams: OurChannelParams, theirParams: TheirChannelParams, ourCommitTx: Transaction, theirSig: signature): Transaction = {
    // TODO : Transaction.sign(...) should handle multisig
    val ourSig = Transaction.signInput(ourCommitTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey)
    ourCommitTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitPubKey, ourCommitPubKey))
  }

  def handle_cmd_close(cmd: CMD_CLOSE, ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: Commitment): close_channel = {
    // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
    val theirFinalTx = makeFinalTx(commitment.tx.txIn, theirParams.finalPubKey, ourFinalPubKey, commitment.state.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
    val anchorTxId = commitment.tx.txIn(0).outPoint.hash // commit tx only has 1 input, which is the anchor
    // we need to watch for BITCOIN_CLOSE_DONE with what we have here, because they may never answer with the fully signed closing tx and still publish it
    blockchain ! WatchConfirmedBasedOnOutputs(self, anchorTxId, theirFinalTx.txOut, ourParams.minDepth, BITCOIN_CLOSE_DONE)
    close_channel(ourSigForThem, cmd.fee)
  }

  def handle_pkt_close(pkt: close_channel, ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: Commitment): (Transaction, close_channel_complete) = {
    // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
    val theirFinalTx = makeFinalTx(commitment.tx.txIn, theirParams.finalPubKey, ourFinalPubKey, commitment.state.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
    val ourFinalTx = makeFinalTx(commitment.tx.txIn, ourFinalPubKey, theirParams.finalPubKey, commitment.state)
    val ourSig = Transaction.signInput(ourFinalTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey)
    val signedFinalTx = ourFinalTx.updateSigScript(0, sigScript2of2(pkt.sig, ourSig, theirParams.commitPubKey, ourCommitPubKey))
    blockchain ! WatchConfirmed(self, signedFinalTx.hash, ourParams.minDepth, BITCOIN_CLOSE_DONE)
    blockchain ! Publish(signedFinalTx)
    (signedFinalTx, close_channel_complete(ourSigForThem))
  }

  /**
    * Something went wrong, we publish the current commitment transaction
    */
  def publish_ourcommit(commitment: Commitment) = {
    log.info(s"publishing our commitment tx: ${commitment.tx}")
    blockchain ! Publish(commitment.tx)
  }

  def handle_ourcommit() = {
    // if (HTLCs)
    //    handle them (how ???)
    //    wait for INPUT_NO_MORE_HTLCS
    // else
    //    wait for BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED
  }

  def handle_ourcommit_delaypassed() = {
    // spend ours
    // wait for BITCOIN_SPEND_OURS_DONE
  }

  /**
    * They published their current commitment transaction
    */
  def handle_theircommit(publishedTx: Transaction, ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment): error = {
    log.info(s"their commit detected: $publishedTx")
    // if (HTLCs)
    //    handle them (how ???)
    //    wait for INPUT_NO_MORE_HTLCS
    // else
    //    spend theirs
    //    wait for BITCOIN_SPEND_THEIRS_DONE
    error(Some("Commit tx noticed"))
  }

  def handle_revoked(publishedTx: Transaction): error = {
    log.info(s"revoked commit detected: $publishedTx")
    // steal immediately
    // wait for BITCOIN_STEAL_DONE
    error(Some("Otherspend noticed"))
  }

}




