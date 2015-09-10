package fr.acinq.eclair


import akka.actor.{Stash, ActorRef, LoggingFSM}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin._
import fr.acinq.lightning._
import lightning._
import lightning.open_channel.anchor_offer.{WILL_CREATE_ANCHOR, WONT_CREATE_ANCHOR}
import lightning.update_decline_htlc.Reason.{CannotRoute, InsufficientFunds}
import scala.util.Try

/**
 * Created by PM on 20/08/2015.
 */

// @formatter:off

// STATES

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
case object CLOSE_WAIT_CLOSE extends State
case object CLOSE_WAIT_OURCOMMIT extends State
case object CLOSE_WAIT_THEIRCOMMIT extends State
case object CLOSE_WAIT_CLOSE_OURCOMMIT extends State
case object CLOSE_WAIT_CLOSE_THEIRCOMMIT extends State
case object CLOSE_WAIT_CLOSE_SPENDOURS extends State
case object CLOSE_WAIT_SPENDOURS extends State
case object CLOSE_WAIT_SPENDTHEM extends State
case object CLOSE_WAIT_SPENDTHEM_CLOSE extends State
case object CLOSE_WAIT_SPENDTHEM_CLOSE_OURCOMMIT extends State
case object CLOSE_WAIT_SPENDTHEM_CLOSE_SPENDOURS extends State
case object CLOSE_WAIT_SPENDTHEM_OURCOMMIT extends State
case object CLOSE_WAIT_SPENDTHEM_SPENDOURS extends State
case object CLOSE_WAIT_STEAL extends State
case object CLOSE_WAIT_STEAL_CLOSE extends State
case object CLOSE_WAIT_STEAL_CLOSE_OURCOMMIT extends State
case object CLOSE_WAIT_STEAL_CLOSE_SPENDOURS extends State
case object CLOSE_WAIT_STEAL_OURCOMMIT extends State
case object CLOSE_WAIT_STEAL_SPENDOURS extends State
case object CLOSE_WAIT_STEAL_SPENDTHEM extends State
case object CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE extends State
case object CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE_OURCOMMIT extends State
case object CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE_SPENDOURS extends State
case object CLOSE_WAIT_STEAL_SPENDTHEM_OURCOMMIT extends State
case object CLOSE_WAIT_STEAL_SPENDTHEM_SPENDOURS extends State
case object CLOSED extends State
case object ERROR_ANCHOR_LOST extends State
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

// EVENTS

case object INPUT_NONE
sealed trait BlockchainEvent
case object BITCOIN_ANCHOR_DEPTHOK
case object BITCOIN_ANCHOR_UNSPENT
case object BITCOIN_ANCHOR_TIMEOUT
final case class BITCOIN_ANCHOR_THEIRSPEND(tx: Transaction)
case object BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED
case object BITCOIN_ANCHOR_OTHERSPEND
case object BITCOIN_CLOSE_DONE

sealed trait Command
final case class CMD_SEND_HTLC_UPDATE(amount: Int, rHash: sha256_hash, expiry: locktime) extends Command
final case class CMD_SEND_HTLC_FULFILL(r: sha256_hash) extends Command
final case class CMD_CLOSE(fee: Long) extends Command
final case class CMD_SEND_HTLC_ROUTEFAIL(h: sha256_hash) extends Command
final case class CMD_SEND_HTLC_TIMEDOUT(h: sha256_hash) extends Command
case object CMD_GETSTATE extends Command

// DATA

sealed trait Data
case object Nothing extends Data
final case class AnchorInput(amount: Long, previousTxOutput: OutPoint, signData: SignData) extends Data
final case class OurChannelParams(delay: locktime, commitPrivKey: BinaryData, finalPrivKey: BinaryData, minDepth: Int, commitmentFee: Long, shaSeed: BinaryData)
final case class TheirChannelParams(delay: locktime, commitPubKey: BinaryData, finalPubKey: BinaryData, minDepth: Int, commitmentFee: Long)
final case class Commitment(index: Long, tx: Transaction, state: ChannelState, theirRevocationHash: sha256_hash)
final case class UpdateProposal(index: Long, state: ChannelState)

final case class DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams: OurChannelParams) extends Data
final case class DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams: OurChannelParams, anchorInput: AnchorInput) extends Data
final case class DATA_OPEN_WAIT_FOR_ANCHOR(ourParams: OurChannelParams, theirParams: TheirChannelParams, theirRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams: OurChannelParams, theirParams: TheirChannelParams, anchorTx: Transaction, anchorOutputIndex: Int, newCommitmentUnsigned: Commitment) extends Data
final case class DATA_OPEN_WAITING(ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: Commitment) extends Data
final case class DATA_NORMAL(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment) extends Data
//TODO : create SignedTransaction
final case class DATA_WAIT_FOR_UPDATE_ACCEPT(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, previousCommitmentSigned: Commitment, updateProposal: UpdateProposal) extends Data
final case class DATA_WAIT_FOR_HTLC_ACCEPT(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, previousCommitmentSigned: Commitment, updateProposal: UpdateProposal) extends Data
final case class DATA_WAIT_FOR_UPDATE_SIG(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, previousCommitmentSigned: Commitment, newCommitmentUnsigned: Commitment) extends Data
final case class DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, previousCommitmentSigned: Commitment, newCommitmentTxUnsigned: Commitment) extends Data
final case class DATA_WAIT_FOR_CLOSE_ACK(finalTx: Transaction) extends Data

// @formatter:on

class Node(val blockchain: ActorRef, val params: OurChannelParams, val anchorDataOpt: Option[AnchorInput]) extends LoggingFSM[State, Data] with Stash {

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
      val anchorTx = makeAnchorTx(ourCommitPubKey, theirParams.commitPubKey, anchorInput.amount, anchorInput.previousTxOutput, anchorInput.signData)
      log.info(s"anchor txid=${anchorTx.hash}")
      //TODO : anchorOutputIndex might not always be zero if there are multiple outputs
      val anchorOutputIndex = 0
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
      val state = ChannelState(them = ChannelOneSide(anchorAmount -  ourParams.commitmentFee, 0, Seq()), us = ChannelOneSide(0, 0, Seq()))
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, 0))
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val (ourCommitTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, TxIn(OutPoint(anchorTxid, anchorOutputIndex), Array.emptyByteArray, 0xffffffffL) :: Nil, state, ourRevocationHash, theirRevocationHash)
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, ourCommitTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(OutPoint(anchorTxid, anchorOutputIndex) -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          stay
        case true =>
          them ! open_commit_sig(ourSigForThem)
          blockchain ! Watch(self, anchorTxid, Anchor, ourParams.minDepth)
          goto(OPEN_WAITING_THEIRANCHOR) using DATA_OPEN_WAITING(ourParams, theirParams, Commitment(0, signedCommitTx, state, theirRevocationHash))
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
          stay
        case true =>
          blockchain ! Watch(self, anchorTx.hash, Anchor, ourParams.minDepth)
          blockchain ! Publish(anchorTx)
          goto(OPEN_WAITING_OURANCHOR) using DATA_OPEN_WAITING(ourParams, theirParams, commitment.copy(tx = signedCommitTx))
      }

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAITING_THEIRANCHOR) {
    case Event(BITCOIN_ANCHOR_DEPTHOK, DATA_OPEN_WAITING(ourParams, theirParams, commitment)) =>
      them ! open_complete(None)
      unstashAll()
      goto(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) using DATA_NORMAL(ourParams, theirParams, ShaChain.init, commitment)

    case Event(msg@open_complete(blockId_opt), d@DATA_OPEN_WAITING(ourParams, _, _)) =>
      log.info(s"received their open_complete, deferring message")
      stash()
      stay

    case Event(BITCOIN_ANCHOR_TIMEOUT, _) =>
      them ! error(Some("Anchor timed out"))
      goto(ERR_ANCHOR_TIMEOUT)

    case Event(cmd: CMD_CLOSE, DATA_OPEN_WAITING(ourParams, theirParams, commitment)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(pkt: close_channel, DATA_OPEN_WAITING(ourParams, theirParams, commitment)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_OPEN_WAITING(ourParams, theirParams, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, ShaChain.init, commitment)
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(OPEN_WAITING_OURANCHOR) {
    case Event(BITCOIN_ANCHOR_DEPTHOK, DATA_OPEN_WAITING(ourParams, theirParams, commitment)) =>
      them ! open_complete(None)
      unstashAll()
      goto(OPEN_WAIT_FOR_COMPLETE_OURANCHOR) using DATA_NORMAL(ourParams, theirParams, ShaChain.init, commitment)

    case Event(msg@open_complete(blockId_opt), d@DATA_OPEN_WAITING(ourParams, _, _)) =>
      log.info(s"received their open_complete, deferring message")
      stash()
      stay

    case Event(cmd: CMD_CLOSE, DATA_OPEN_WAITING(ourParams, theirParams, commitment)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(pkt: close_channel, DATA_OPEN_WAITING(ourParams, theirParams, commitment)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_OPEN_WAITING(ourParams, theirParams, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, ShaChain.init, commitment)
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) {
    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      goto(NORMAL_LOWPRIO) using d

    case Event(cmd: CMD_CLOSE, DATA_NORMAL(ourParams, theirParams, _, commitment)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(pkt: close_channel, DATA_NORMAL(ourParams, theirParams, _, commitment)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(OPEN_WAIT_FOR_COMPLETE_OURANCHOR) {
    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      goto(NORMAL_HIGHPRIO) using d

    case Event(cmd: CMD_CLOSE, DATA_NORMAL(ourParams, theirParams, _, commitment)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(pkt: close_channel, DATA_NORMAL(ourParams, theirParams, _, commitment)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  def NORMAL_handler: StateFunction = {
    case Event(CMD_SEND_HTLC_UPDATE(amount, rHash, expiry), DATA_NORMAL(ourParams, theirParams, shaChain, commitment@Commitment(_, _, previousState, _))) =>
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val htlc = update_add_htlc(ourRevocationHash, amount, rHash, expiry)
      val newState = previousState.htlc_send(htlc)
      them ! htlc
      goto(WAIT_FOR_HTLC_ACCEPT(priority)) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, UpdateProposal(commitment.index + 1, newState))

    case Event(htlc@update_add_htlc(sha256_hash(0, 0, 0, 0), amount, rHash, expiry), d@DATA_NORMAL(ourParams, theirParams, shaChain, p@Commitment(previousCommitmentTx, previousState, _, _))) =>
      //TODO : for testing, hashes 0/0/0/0 are declined
      them ! update_decline_htlc(CannotRoute(true))
      goto(NORMAL(priority))

    case Event(htlc@update_add_htlc(theirRevocationHash, _, _, _), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      val newState = commitment.state.htlc_receive(htlc)
      val ourRevocationHash = Crypto.sha256(ShaChain.shaChainFromSeed(ourParams.shaSeed, commitment.index + 1))
      val (newCommitmentTx, ourSigForThem) = sign_their_commitment_tx(ourParams, theirParams, commitment.tx.txIn, newState, ourRevocationHash, theirRevocationHash)
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG(priority)) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, Commitment(commitment.index + 1, newCommitmentTx, newState, theirRevocationHash))

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

    case Event(cmd: CMD_CLOSE, DATA_NORMAL(ourParams, theirParams, _, commitment)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(pkt: close_channel, DATA_NORMAL(ourParams, theirParams, _, commitment)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      them ! handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
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
          stay
        case true =>
          val preimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index)
          them ! update_signature(ourSigForThem, preimage)
          goto(WAIT_FOR_UPDATE_COMPLETE(priority)) using DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, shaChain, previousCommitment, Commitment(newIndex, signedCommitTx, newState, theirRevocationHash))
      }

    case Event(update_decline_htlc(reason), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, previousCommitmentTx, _)) =>
      log.info(s"counterparty declined htlc update with reason=$reason")
      goto(NORMAL(priority)) using DATA_NORMAL(ourParams, theirParams, shaChain, previousCommitmentTx)

    case Event(pkt: close_channel, DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, shaChain, commitment, _)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      them ! handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
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
      assert(new BinaryData(previousCommitment.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage)), s"the revocation preimage they gave us is wrong! hash=${previousCommitment.theirRevocationHash} preimage=$theirRevocationPreimage")
      // TODO if wrong we should close the channel
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val signedCommitTx = sign_our_commitment_tx(ourParams, theirParams, newCommitment.tx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitment.tx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          stay
        case true =>
          val preimage = ShaChain.shaChainFromSeed(ourParams.shaSeed, previousCommitment.index)
          them ! update_complete(preimage)
          goto(NORMAL(priority.invert)) using DATA_NORMAL(ourParams, theirParams, ShaChain.addHash(shaChain, theirRevocationPreimage, previousCommitment.index), newCommitment)
      }

    case Event(cmd: CMD_CLOSE, DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, shaChain, commitment, _)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      them ! handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(WAIT_FOR_UPDATE_SIG_HIGHPRIO)(WAIT_FOR_UPDATE_SIG_handler)

  when(WAIT_FOR_UPDATE_SIG_LOWPRIO)(WAIT_FOR_UPDATE_SIG_handler)

  def WAIT_FOR_UPDATE_COMPLETE_handler: StateFunction = {
    case Event(update_complete(theirRevocationPreimage), DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, shaChain, previousCommitment, newCommitment)) =>
      assert(new BinaryData(previousCommitment.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage)), s"the revocation preimage they gave us is wrong! hash=${previousCommitment.theirRevocationHash} preimage=$theirRevocationPreimage")
      // TODO if wrong we should close the channel
      goto(NORMAL(priority.invert)) using DATA_NORMAL(ourParams, theirParams, ShaChain.addHash(shaChain, theirRevocationPreimage, previousCommitment.index), newCommitment)

    case Event(pkt: close_channel, DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, shaChain, commitment, _)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitment)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      them ! handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(WAIT_FOR_UPDATE_COMPLETE_HIGHPRIO)(WAIT_FOR_UPDATE_COMPLETE_handler)

  when(WAIT_FOR_UPDATE_COMPLETE_LOWPRIO)(WAIT_FOR_UPDATE_COMPLETE_handler)

  when(WAIT_FOR_CLOSE_COMPLETE) {
    case Event(close_channel_complete(theirSig), DATA_OPEN_WAITING(ourParams, theirParams, Commitment(_, commitment, state, _))) =>
      val finalTx = makeFinalTx(commitment.txIn, ourFinalPubKey, theirParams.finalPubKey, state)
      val signedFinalTx = sign_our_commitment_tx(ourParams, theirParams, finalTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedFinalTx, Map(signedFinalTx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          stay
        case true =>
          them ! close_channel_ack()
          blockchain ! Watch(self, signedFinalTx.hash, Final, 1)
          blockchain ! Publish(signedFinalTx)
          goto(CLOSE_WAIT_CLOSE)
      }

    case Event(close_channel_complete(theirSig), DATA_NORMAL(ourParams, theirParams, shaChain, Commitment(_, commitment, state, _))) =>
      val finalTx = makeFinalTx(commitment.txIn, ourFinalPubKey, theirParams.finalPubKey, state)
      val signedFinalTx = sign_our_commitment_tx(ourParams, theirParams, finalTx, theirSig)
      val ok = Try(Transaction.correctlySpends(signedFinalTx, Map(signedFinalTx.txIn(0).outPoint -> anchorPubkeyScript(ourCommitPubKey, theirParams.commitPubKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      ok match {
        case false =>
          them ! error(Some("Bad signature"))
          stay
        case true =>
          them ! close_channel_ack()
          blockchain ! Watch(self, signedFinalTx.hash, Final, 1)
          blockchain ! Publish(signedFinalTx)
          goto(CLOSE_WAIT_CLOSE)
      }

    case Event(BITCOIN_CLOSE_DONE, _) => goto(CLOSED)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_OPEN_WAITING(ourParams, theirParams, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, ShaChain.init, commitment)
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_CLOSE)

    case Event(pkt: error, _) => stay
  }

  when(WAIT_FOR_CLOSE_ACK) {
    case Event(close_channel_ack(), _) =>
      goto(CLOSE_WAIT_CLOSE)

    case Event(BITCOIN_CLOSE_DONE, _) => goto(CLOSED)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_CLOSE)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_CLOSE)
  }

  when(CLOSE_WAIT_OURCOMMIT) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE_OURCOMMIT)

    case Event(BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED, _) =>
      handle_btc_anchor_ourcommit_delaypassed()
      goto(CLOSE_WAIT_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_OURCOMMIT)
  }

  when(CLOSE_WAIT_SPENDOURS) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_SPENDOURS)
  }

  when(CLOSE_WAIT_SPENDTHEM) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_SPENDTHEM)
  }

  when(CLOSE_WAIT_CLOSE) {
    case Event(BITCOIN_CLOSE_DONE, _) => goto(CLOSED)

    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_CLOSE)
  }

  when(CLOSE_WAIT_CLOSE_OURCOMMIT) {

    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE_OURCOMMIT)

    case Event(BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED, _) =>
      handle_btc_anchor_ourcommit_delaypassed()
      goto(CLOSE_WAIT_CLOSE_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_CLOSE_OURCOMMIT)

    case Event(BITCOIN_CLOSE_DONE, _) => goto(CLOSED)
  }

  when(CLOSE_WAIT_CLOSE_SPENDOURS) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_CLOSE_SPENDOURS)
  }

  when(CLOSE_WAIT_SPENDTHEM_CLOSE) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE)
  }

  when(CLOSE_WAIT_SPENDTHEM_CLOSE_OURCOMMIT) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED, _) =>
      handle_btc_anchor_ourcommit_delaypassed()
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE_OURCOMMIT)
  }

  when(CLOSE_WAIT_SPENDTHEM_CLOSE_SPENDOURS) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE_SPENDOURS)
  }

  when(CLOSE_WAIT_SPENDTHEM_OURCOMMIT) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED, _) =>
      handle_btc_anchor_ourcommit_delaypassed()
      goto(CLOSE_WAIT_SPENDTHEM_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_OURCOMMIT)
  }

  when(CLOSE_WAIT_SPENDTHEM_SPENDOURS) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_SPENDOURS)
  }

  when(CLOSE_WAIT_STEAL) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_STEAL_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)
  }

  when(CLOSE_WAIT_STEAL_CLOSE) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      stay
  }

  when(CLOSE_WAIT_STEAL_CLOSE_OURCOMMIT) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE_OURCOMMIT)

    case Event(BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED, _) =>
      handle_btc_anchor_ourcommit_delaypassed()
      goto(CLOSE_WAIT_STEAL_CLOSE_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      stay
  }

  when(CLOSE_WAIT_STEAL_CLOSE_SPENDOURS) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_STEAL_CLOSE_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      stay
  }

  when(CLOSE_WAIT_STEAL_OURCOMMIT) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_OURCOMMIT)

    case Event(BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED, _) =>
      handle_btc_anchor_ourcommit_delaypassed()
      goto(CLOSE_WAIT_STEAL_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      stay
  }

  when(CLOSE_WAIT_STEAL_SPENDOURS) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND(tx), DATA_NORMAL(ourParams, theirParams, shaChain, commitment)) =>
      them ! handle_btc_anchor_theirspend(tx, ourParams, theirParams, shaChain, commitment)
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      stay
  }

  when(CLOSE_WAIT_STEAL_SPENDTHEM) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      stay
  }

  when(CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      stay
  }

  when(CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE_OURCOMMIT) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED, _) =>
      handle_btc_anchor_ourcommit_delaypassed()
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      stay
  }

  when(CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE_SPENDOURS) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      stay
  }

  when(CLOSE_WAIT_STEAL_SPENDTHEM_OURCOMMIT) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED, _) =>
      handle_btc_anchor_ourcommit_delaypassed()
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_OURCOMMIT)
  }

  when(CLOSE_WAIT_STEAL_SPENDTHEM_SPENDOURS) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      stay
  }

  when(CLOSED) {
    case null => stay
  }

  whenUnhandled {
    case Event(CMD_GETSTATE, _) =>
      sender ! stateName
      stay
  }

  /*
  HANDLERS
   */

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
    close_channel(ourSigForThem, cmd.fee)
  }

  def handle_pkt_close(pkt: close_channel, ourParams: OurChannelParams, theirParams: TheirChannelParams, commitment: Commitment): close_channel_complete = {
    // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
    val theirFinalTx = makeFinalTx(commitment.tx.txIn, theirParams.finalPubKey, ourFinalPubKey, commitment.state.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey))
    val ourFinalTx = makeFinalTx(commitment.tx.txIn, ourFinalPubKey, theirParams.finalPubKey, commitment.state)
    val ourSig = Transaction.signInput(ourFinalTx, 0, multiSig2of2(ourCommitPubKey, theirParams.commitPubKey), SIGHASH_ALL, ourParams.commitPrivKey)
    val signedFinalTx = ourFinalTx.updateSigScript(0, sigScript2of2(pkt.sig, ourSig, theirParams.commitPubKey, ourCommitPubKey))
    blockchain ! Watch(self, signedFinalTx.hash, Final, 1)
    blockchain ! Publish(signedFinalTx)
    close_channel_complete(ourSigForThem)
  }

  def handle_btc_anchor_theirspend(publishedTx: Transaction, ourParams: OurChannelParams, theirParams: TheirChannelParams, shaChain: ShaChain, commitment: Commitment): error = {
    // let's find out which pubscript was used (as a P2SH it is not 'in clear' in the blockchain)
    // is it the latest commitment ?
    publishedTx.txOut.find(_.publicKeyScript.data.toArray.deep == Script.write(pay2sh(redeemSecretOrDelay(theirParams.finalPubKey, ourParams.delay, ourFinalPubKey, commitment.theirRevocationHash))).deep) match {
      case Some(txOut) =>
        log.warning(s"they published their commitment tx !")
        // there are several kind of outputs :
        // a) our 'regular' output going to our final key, immediately spendable
        // b) their 'regular' output going to their final key, spendable after a delay
        // c) the htlc outputs we paid, spendable by us using our final key after a delay
        // d) the hltc outputs that they paid, spendable by them using their final key after adelay
        // what we should do is wait for max(delay) and spend all available outputs to our final key
        // TODO : let's make the final tx, and publish it later
      case None =>
        // it has to be one of the revoked tx
        // one way is to use the main revocation hash, and rebuild the pub script we signed
        (commitment.index - 1 to 0L by -1)
          .find { i =>
          val theirRevocationHash = Crypto.sha256(ShaChain.getHash(shaChain, i).get)
          publishedTx.txOut.exists(o => o.publicKeyScript.data.toArray.deep == Script.write(pay2sh(redeemSecretOrDelay(theirParams.finalPubKey, ourParams.delay, ourFinalPubKey, theirRevocationHash))).deep)
        } match {
          case Some(revokedCommitment) =>
            log.warning(s"they published a revoked tx !")
          // there are several kind of outputs :
          // a) our 'regular' output going to our final key, immediately spendable
          // b) their 'regular' output going to our final key and the revocation key, that we can steal
          // c) all the htlc outputs, paid by both of us, going to our final key and the revocation key, that we can steal
          // we should steal all the money immediately !
          // TODO : let's steal all the money
          case None =>
            //  should NEVER happen (really)
            ???
        }
    }
    error(Some("Commit tx noticed"))
  }

  def handle_btc_anchor_ourcommit_delaypassed() = {

  }

  def handle_btc_anchor_otherspend(): error = {
    error(Some("Otherspend noticed"))
  }

}
