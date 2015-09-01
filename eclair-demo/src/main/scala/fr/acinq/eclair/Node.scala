package fr.acinq.eclair

import akka.actor.{Stash, ActorRef, LoggingFSM}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin._
import fr.acinq.lightning._
import lightning._
import lightning.locktime.Locktime.Blocks
import lightning.open_channel.anchor_offer.{WILL_CREATE_ANCHOR, WONT_CREATE_ANCHOR}
import lightning.update_decline_htlc.Reason.{CannotRoute, InsufficientFunds}
import org.bouncycastle.util.encoders.Hex

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
case object NORMAL_HIGHPRIO extends State
case object NORMAL_LOWPRIO extends State
case object WAIT_FOR_HTLC_ACCEPT_HIGHPRIO extends State
case object WAIT_FOR_HTLC_ACCEPT_LOWPRIO extends State
case object WAIT_FOR_UPDATE_SIG_HIGHPRIO extends State
case object WAIT_FOR_UPDATE_SIG_LOWPRIO extends State
case object WAIT_FOR_UPDATE_COMPLETE_HIGHPRIO extends State
case object WAIT_FOR_UPDATE_COMPLETE_LOWPRIO extends State
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

// EVENTS

case object INPUT_NONE
sealed trait BlockchainEvent
final case class BITCOIN_TX_CONFIRMED(blockId: sha256_hash, confirmations: Int) extends BlockchainEvent
case object BITCOIN_CLOSE_DONE
case object BITCOIN_ANCHOR_UNSPENT
case object BITCOIN_ANCHOR_TIMEOUT
case object BITCOIN_ANCHOR_THEIRSPEND
case object BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED
case object BITCOIN_ANCHOR_OTHERSPEND

sealed trait Command
final case class CMD_SEND_HTLC_UPDATE(amount: Int, rHash: sha256_hash, expiry: locktime) extends Command
final case class CMD_SEND_HTLC_COMPLETE(r: sha256_hash) extends Command
final case class CMD_CLOSE(fee: Long) extends Command
final case class CMD_SEND_HTLC_ROUTEFAIL(h: sha256_hash)
final case class CMD_SEND_HTLC_TIMEDOUT(h: sha256_hash)

// DATA

sealed trait Data
case object Nothing extends Data
final case class AnchorInput(amount: Long, previousTxOutput: OutPoint, signData: SignData) extends Data
final case class ChannelParams(delay: locktime, commitKey: bitcoin_pubkey, finalKey: bitcoin_pubkey, minDepth: Int, commitmentFee: Long)
final case class CommitmentTx(tx: Transaction, state: ChannelState, ourRevocationPreimage: sha256_hash, theirRevocationHash: sha256_hash)
final case class UpdateProposal(state: ChannelState, ourRevocationPreimage: sha256_hash)

final case class DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams: ChannelParams, ourRevocationPreimage: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams: ChannelParams, anchorInput: AnchorInput, ourRevocationPreimage: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_ANCHOR(ourParams: ChannelParams, theirParams: ChannelParams, ourRevocationPreimage: sha256_hash, theirRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams: ChannelParams, theirParams: ChannelParams, anchorTx: Transaction, anchorOutputIndex: Int, newCommitmentTxUnsigned: CommitmentTx) extends Data
final case class DATA_OPEN_WAITING(ourParams: ChannelParams, theirParams: ChannelParams, commitmentTx: CommitmentTx) extends Data
final case class DATA_NORMAL(ourParams: ChannelParams, theirParams: ChannelParams, commitmentTx: CommitmentTx) extends Data
//TODO : create SignedTransaction
final case class DATA_WAIT_FOR_UPDATE_ACCEPT(ourParams: ChannelParams, theirParams: ChannelParams, previousCommitmentTxSigned: CommitmentTx, updateProposal: UpdateProposal) extends Data
final case class DATA_WAIT_FOR_HTLC_ACCEPT(ourParams: ChannelParams, theirParams: ChannelParams, previousCommitmentTxSigned: CommitmentTx, updateProposal: UpdateProposal) extends Data
final case class DATA_WAIT_FOR_UPDATE_SIG(ourParams: ChannelParams, theirParams: ChannelParams, previousCommitmentTxSigned: CommitmentTx, newCommitmentTxUnsigned: CommitmentTx) extends Data
final case class DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams: ChannelParams, theirParams: ChannelParams, previousCommitmentTxSigned: CommitmentTx, newCommitmentTxUnsigned: CommitmentTx) extends Data

// @formatter:on

class Node(val blockchain: ActorRef, val commitPrivKey: BinaryData, val finalPrivKey: BinaryData, val minDepth: Int, val anchorDataOpt: Option[AnchorInput]) extends LoggingFSM[State, Data] with Stash {

  val DEFAULT_delay = locktime(Blocks(10))
  val DEFAULT_commitmentFee = 100000

  val commitPubKey = bitcoin_pubkey(ByteString.copyFrom(Crypto.publicKeyFromPrivateKey(commitPrivKey.key.toByteArray)))
  val finalPubKey = bitcoin_pubkey(ByteString.copyFrom(Crypto.publicKeyFromPrivateKey(finalPrivKey.key.toByteArray)))

  // TODO
  var them: ActorRef = null

  anchorDataOpt match {
    case None => startWith(INIT_NOANCHOR, Nothing)
    case Some(anchorData) => startWith(INIT_WITHANCHOR, anchorData)
  }

  when(INIT_NOANCHOR) {
    case Event(INPUT_NONE, _) =>
      them = sender
      val ourParams = ChannelParams(DEFAULT_delay, commitPubKey, finalPubKey, minDepth, DEFAULT_commitmentFee)
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      them ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      goto(OPEN_WAIT_FOR_OPEN_NOANCHOR) using DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams, ourRevocationHashPreimage)
  }

  when(INIT_WITHANCHOR) {
    case Event(INPUT_NONE, anchorInput: AnchorInput) =>
      them = sender
      val ourParams = ChannelParams(DEFAULT_delay, commitPubKey, finalPubKey, minDepth, DEFAULT_commitmentFee)
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      them ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      goto(OPEN_WAIT_FOR_OPEN_WITHANCHOR) using DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams, anchorInput, ourRevocationHashPreimage)
  }

  when(OPEN_WAIT_FOR_OPEN_NOANCHOR) {
    case Event(open_channel(delay, theirRevocationHash, commitKey, finalKey, WILL_CREATE_ANCHOR, minDepth, commitmentFee), DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams, ourRevocationPreimage)) =>
      val theirParams = ChannelParams(delay, commitKey, finalKey, minDepth.get, commitmentFee)
      goto(OPEN_WAIT_FOR_ANCHOR) using DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, ourRevocationPreimage, theirRevocationHash)

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAIT_FOR_OPEN_WITHANCHOR) {
    case Event(open_channel(delay, theirRevocationHash, commitKey, finalKey, WONT_CREATE_ANCHOR, minDepth, commitmentFee), DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams, anchorInput, ourRevocationHashPreimage)) =>
      val theirParams = ChannelParams(delay, commitKey, finalKey, minDepth.get, commitmentFee)
      val anchorTx = makeAnchorTx(ourParams.commitKey, theirParams.commitKey, anchorInput.amount, anchorInput.previousTxOutput, anchorInput.signData)
      log.info(s"anchor txid=${anchorTx.hash}")
      //TODO : anchorOutputIndex might not always be zero if there are multiple outputs
      val anchorOutputIndex = 0
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(0, 0, Seq()), us = ChannelOneSide(anchorInput.amount - DEFAULT_commitmentFee, 0, Seq()))
      // we build our commitment tx, leaving it unsigned
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchorTx.hash, anchorOutputIndex, theirRevocationHash, state)
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchorTx.hash, anchorOutputIndex, Crypto.sha256(ourRevocationHashPreimage), state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      them ! open_anchor(anchorTx.hash, anchorOutputIndex, anchorInput.amount, ourSigForThem)
      goto(OPEN_WAIT_FOR_COMMIT_SIG) using DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchorTx, anchorOutputIndex, CommitmentTx(ourCommitTx, state, ourRevocationHashPreimage, theirRevocationHash))

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAIT_FOR_ANCHOR) {
    case Event(open_anchor(anchorTxid, anchorOutputIndex, anchorAmount, theirSig), DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, ourRevocationHashPreimage, theirRevocationHash)) =>
      // they fund the channel with their anchor tx, so the money is theirs
      val state = ChannelState(them = ChannelOneSide(anchorAmount - DEFAULT_commitmentFee, 0, Seq()), us = ChannelOneSide(0, 0, Seq()))
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchorTxid, anchorOutputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      // TODO : Transaction.sign(...) should handle multisig
      val ourSig = Transaction.signInput(ourCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = ourCommitTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(OutPoint(anchorTxid, anchorOutputIndex) -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchorTxid, anchorOutputIndex, theirRevocationHash, state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      them ! open_commit_sig(ourSigForThem)
      blockchain ! Watch(anchorTxid)
      goto(OPEN_WAITING_THEIRANCHOR) using DATA_OPEN_WAITING(ourParams, theirParams, CommitmentTx(signedCommitTx, state, ourRevocationHashPreimage, theirRevocationHash))

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAIT_FOR_COMMIT_SIG) {
    case Event(open_commit_sig(theirSig), DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchorTx, anchorOutputIndex, newCommitTx)) =>
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourSig = Transaction.signInput(newCommitTx.tx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = newCommitTx.tx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(OutPoint(anchorTx.hash, anchorOutputIndex) -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      if (!ok) log.error(s"invalid sig")
      blockchain ! Watch(anchorTx.hash)
      blockchain ! Publish(anchorTx)
      goto(OPEN_WAITING_OURANCHOR) using DATA_OPEN_WAITING(ourParams, theirParams, newCommitTx.copy(tx = signedCommitTx))

    case Event(CMD_CLOSE(_), _) => goto(CLOSED)
  }

  when(OPEN_WAITING_THEIRANCHOR) {
    case Event(BITCOIN_TX_CONFIRMED(blockId, confirmations), DATA_OPEN_WAITING(ourParams, _, _)) if confirmations < ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx")
      stay

    case Event(BITCOIN_TX_CONFIRMED(blockId, confirmations), d@DATA_OPEN_WAITING(ourParams, _, _)) if confirmations >= ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached")
      them ! open_complete(Some(blockId))
      unstashAll()
      goto(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) using DATA_NORMAL(d.ourParams, d.theirParams, d.commitmentTx)

    case Event(msg@open_complete(blockId_opt), d@DATA_OPEN_WAITING(ourParams, _, _)) =>
      log.info(s"received their open_complete, deferring message")
      stash()
      stay

    case Event(BITCOIN_ANCHOR_TIMEOUT, _) =>
      them ! error(Some("Anchor timed out"))
      goto(ERR_ANCHOR_TIMEOUT)

    case Event(cmd: CMD_CLOSE, DATA_OPEN_WAITING(ourParams, theirParams, commitmentTx)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(pkt: close_channel, DATA_OPEN_WAITING(ourParams, theirParams, commitmentTx)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(OPEN_WAITING_OURANCHOR) {
    case Event(BITCOIN_TX_CONFIRMED(blockId, confirmations), DATA_OPEN_WAITING(ourParams, _, _)) if confirmations < ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx")
      stay

    case Event(BITCOIN_TX_CONFIRMED(blockId, confirmations), d@DATA_OPEN_WAITING(ourParams, _, _)) if confirmations >= ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached")
      them ! open_complete(Some(blockId))
      unstashAll()
      goto(OPEN_WAIT_FOR_COMPLETE_OURANCHOR) using DATA_NORMAL(d.ourParams, d.theirParams, d.commitmentTx)

    case Event(msg@open_complete(blockId_opt), d@DATA_OPEN_WAITING(ourParams, _, _)) =>
      log.info(s"received their open_complete, deferring message")
      stash()
      stay

    case Event(cmd: CMD_CLOSE, DATA_OPEN_WAITING(ourParams, theirParams, commitmentTx)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(pkt: close_channel, DATA_OPEN_WAITING(ourParams, theirParams, commitmentTx)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(OPEN_WAIT_FOR_COMPLETE_THEIRANCHOR) {
    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      goto(NORMAL_LOWPRIO) using d

    case Event(cmd: CMD_CLOSE, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(pkt: close_channel, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(OPEN_WAIT_FOR_COMPLETE_OURANCHOR) {
    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      goto(NORMAL_HIGHPRIO) using d

    case Event(cmd: CMD_CLOSE, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(pkt: close_channel, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(ERR_INFORMATION_LEAK)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(NORMAL_HIGHPRIO) {
    case Event(CMD_SEND_HTLC_UPDATE(amount, rHash, expiry), DATA_NORMAL(ourParams, theirParams, p@CommitmentTx(previousCommitmentTx, previousState, _, _))) =>
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      val htlc = update_add_htlc(ourRevocationHash, amount, rHash, expiry)
      val newState = previousState.htlc_send(htlc)
      them ! htlc
      goto(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, p, UpdateProposal(newState, ourRevocationHashPreimage))

    case Event(pkt: update_add_htlc, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      val (newCommitmentTx, updateAccept) = handle_pkt_update_add_htlc(pkt, ourParams, theirParams, commitmentTx)
      them ! updateAccept
      goto(WAIT_FOR_UPDATE_SIG_HIGHPRIO) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, newCommitmentTx)

    //    case Event(htlc@update_add_htlc(theirRevocationHash, amount, rHash, expiry), d@DATA_NORMAL(priority, ourParams, theirParams, p@CommitmentTx(previousCommitmentTx, previousState, _, _))) =>
    //      them ! update_decline_htlc(CannotRoute(true))
    //      goto(NORMAL) using d.copy(priority = priority.invert())

    case Event(cmd: CMD_SEND_HTLC_ROUTEFAIL, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      val (updateProposal, res) = handle_cmd_send_htlc_routefail(cmd)
      them ! res
      goto(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, commitmentTx, updateProposal)

    case Event(pkt: update_routefail_htlc, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      val (newCommitmentTx, res) = handle_pkt_update_routefail_htlc(pkt)
      them ! res
      goto(WAIT_FOR_UPDATE_SIG_HIGHPRIO) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, newCommitmentTx)

    case Event(cmd: CMD_SEND_HTLC_TIMEDOUT, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      val (updateProposal, res) = handle_cmd_send_htlc_timedout(cmd)
      them ! res
      goto(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, commitmentTx, updateProposal)

    case Event(pkt: update_timedout_htlc, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      val (newCommitmentTx, res) = handle_pkt_update_timedout_htlc(pkt)
      them ! res
      goto(WAIT_FOR_UPDATE_SIG_HIGHPRIO) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, newCommitmentTx)

    case Event(CMD_SEND_HTLC_COMPLETE(r), DATA_NORMAL(ourParams, theirParams, p@CommitmentTx(previousCommitmentTx, previousState, _, _))) =>
      // we paid upstream in exchange for r, now lets gets paid
      val newState = previousState.htlc_complete(r)
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      // Complete your HTLC: I have the R value, pay me!
      them ! update_complete_htlc(ourRevocationHash, r)
      goto(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, p, UpdateProposal(newState, ourRevocationHashPreimage))

    case Event(pkt: update_complete_htlc, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      val (newCommitmentTx, updateAccept) = handle_pkt_update_complete(pkt, ourParams, theirParams, commitmentTx)
      them ! updateAccept
      goto(WAIT_FOR_UPDATE_SIG_HIGHPRIO) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, newCommitmentTx)

    case Event(cmd: CMD_CLOSE, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(pkt: close_channel, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      them ! handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(NORMAL_LOWPRIO) {
    case Event(CMD_SEND_HTLC_UPDATE(amount, rHash, expiry), DATA_NORMAL(ourParams, theirParams, p@CommitmentTx(previousCommitmentTx, previousState, _, _))) =>
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      val htlc = update_add_htlc(ourRevocationHash, amount, rHash, expiry)
      val newState = previousState.htlc_send(htlc)
      them ! htlc
      goto(WAIT_FOR_HTLC_ACCEPT_LOWPRIO) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, p, UpdateProposal(newState, ourRevocationHashPreimage))

    case Event(pkt: update_add_htlc, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      val (newCommitmentTx, updateAccept) = handle_pkt_update_add_htlc(pkt, ourParams, theirParams, commitmentTx)
      them ! updateAccept
      goto(WAIT_FOR_UPDATE_SIG_LOWPRIO) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, newCommitmentTx)

    //    case Event(htlc@update_add_htlc(theirRevocationHash, amount, rHash, expiry), d@DATA_NORMAL(priority, ourParams, theirParams, p@CommitmentTx(previousCommitmentTx, previousState, _, _))) =>
    //      them ! update_decline_htlc(CannotRoute(true))
    //      goto(NORMAL) using d.copy(priority = priority.invert())

    case Event(cmd: CMD_SEND_HTLC_ROUTEFAIL, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      val (updateProposal, res) = handle_cmd_send_htlc_routefail(cmd)
      them ! res
      goto(WAIT_FOR_HTLC_ACCEPT_LOWPRIO) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, commitmentTx, updateProposal)

    case Event(pkt: update_routefail_htlc, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      val (newCommitmentTx, res) = handle_pkt_update_routefail_htlc(pkt)
      them ! res
      goto(WAIT_FOR_UPDATE_SIG_LOWPRIO) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, newCommitmentTx)

    case Event(cmd: CMD_SEND_HTLC_TIMEDOUT, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      val (updateProposal, res) = handle_cmd_send_htlc_timedout(cmd)
      them ! res
      goto(WAIT_FOR_HTLC_ACCEPT_LOWPRIO) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, commitmentTx, updateProposal)

    case Event(pkt: update_timedout_htlc, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      val (newCommitmentTx, res) = handle_pkt_update_timedout_htlc(pkt)
      them ! res
      goto(WAIT_FOR_UPDATE_SIG_LOWPRIO) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, newCommitmentTx)

    case Event(CMD_SEND_HTLC_COMPLETE(r), DATA_NORMAL(ourParams, theirParams, p@CommitmentTx(previousCommitmentTx, previousState, _, _))) =>
      // we paid upstream in exchange for r, now lets gets paid
      val newState = previousState.htlc_complete(r)
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      // Complete your HTLC: I have the R value, pay me!
      them ! update_complete_htlc(ourRevocationHash, r)
      goto(WAIT_FOR_HTLC_ACCEPT_LOWPRIO) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, p, UpdateProposal(newState, ourRevocationHashPreimage))

    case Event(pkt: update_complete_htlc, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      val (newCommitmentTx, updateAccept) = handle_pkt_update_complete(pkt, ourParams, theirParams, commitmentTx)
      them ! updateAccept
      goto(WAIT_FOR_UPDATE_SIG_LOWPRIO) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, newCommitmentTx)

    case Event(cmd: CMD_CLOSE, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(pkt: close_channel, DATA_NORMAL(ourParams, theirParams, commitmentTx)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      them ! handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  onTransition {
    case _ -> NORMAL_HIGHPRIO => log.debug(s"my state is now ${nextStateData.asInstanceOf[DATA_NORMAL].commitmentTx.state.prettyString()}")
    case _ -> NORMAL_LOWPRIO => log.debug(s"my state is now ${nextStateData.asInstanceOf[DATA_NORMAL].commitmentTx.state.prettyString()}")
  }

  when(WAIT_FOR_HTLC_ACCEPT_HIGHPRIO) {
    case Event(update_accept(theirSig, theirRevocationHash), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, previous, UpdateProposal(newState, ourRevocationHashPreimage))) =>
      // counterparty replied with the signature for the new commitment tx
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val newCommitmentTx = makeCommitTx(previous.tx.txIn, ourParams.finalKey, theirParams.finalKey, theirParams.delay, Crypto.sha256(ourRevocationHashPreimage), newState)
      val ourSig = Transaction.signInput(newCommitmentTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = newCommitmentTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previous.tx.txIn(0).outPoint -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      if (!ok) log.error(s"invalid sig")
      them ! update_signature(ourSig, previous.ourRevocationPreimage)
      goto(WAIT_FOR_UPDATE_COMPLETE_HIGHPRIO) using DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, previous, CommitmentTx(signedCommitTx, newState, ourRevocationHashPreimage, theirRevocationHash))

    case Event(update_decline_htlc(reason), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, previous, _)) =>
      log.info(s"counterparty declined htlc update with reason=$reason")
      goto(NORMAL_LOWPRIO) using DATA_NORMAL(ourParams, theirParams, previous)

    case Event(pkt: close_channel, DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, commitmentTx, _)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      them ! handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(WAIT_FOR_HTLC_ACCEPT_LOWPRIO) {
    case Event(update_accept(theirSig, theirRevocationHash), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, previous, UpdateProposal(newState, ourRevocationHashPreimage))) =>
      // counterparty replied with the signature for the new commitment tx
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val newCommitmentTx = makeCommitTx(previous.tx.txIn, ourParams.finalKey, theirParams.finalKey, theirParams.delay, Crypto.sha256(ourRevocationHashPreimage), newState)
      val ourSig = Transaction.signInput(newCommitmentTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = newCommitmentTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previous.tx.txIn(0).outPoint -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      if (!ok) log.error(s"invalid sig")
      them ! update_signature(ourSig, previous.ourRevocationPreimage)
      goto(WAIT_FOR_UPDATE_COMPLETE_LOWPRIO) using DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, previous, CommitmentTx(signedCommitTx, newState, ourRevocationHashPreimage, theirRevocationHash))

    case Event(update_decline_htlc(reason), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, previous, _)) =>
      log.info(s"counterparty declined htlc update with reason=$reason")
      goto(NORMAL_HIGHPRIO) using DATA_NORMAL(ourParams, theirParams, previous)

    case Event(htlc@update_add_htlc(theirRevocationHash, amount, rHash, expiry), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, commitmentTx, _)) =>
      val (newCommitmentTx, res) = handle_pkt_update_add_htlc(htlc, ourParams, theirParams, commitmentTx)
      them ! res
      goto(WAIT_FOR_UPDATE_SIG_LOWPRIO) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, newCommitmentTx)

//    case Event(htlc@update_add_htlc(theirRevocationHash, amount, rHash, expiry), DATA_WAIT_FOR_HTLC_ACCEPT(LowPriority, ourParams, theirParams, commitmentTx, _)) =>
//      them ! update_decline_htlc(CannotRoute(true))
//      goto(NORMAL_HIGHPRIO) using DATA_NORMAL(HighPriority, ourParams, theirParams, commitmentTx)

    case Event(pkt: update_routefail_htlc, DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, commitmentTx, _)) =>
      val (newCommitmentTx, res) = handle_pkt_update_routefail_htlc(pkt)
      them ! res
      goto(WAIT_FOR_UPDATE_SIG_LOWPRIO) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, newCommitmentTx)

    case Event(pkt: update_timedout_htlc, DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, commitmentTx, _)) =>
      val (newCommitmentTx, res) = handle_pkt_update_timedout_htlc(pkt)
      them ! res
      goto(WAIT_FOR_UPDATE_SIG_LOWPRIO) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, newCommitmentTx)

    case Event(pkt: update_complete_htlc, DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, commitmentTx, _)) =>
      val (newCommitmentTx, updateAccept) = handle_pkt_update_complete(pkt, ourParams, theirParams, commitmentTx)
      them ! updateAccept
      goto(WAIT_FOR_UPDATE_SIG_LOWPRIO) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, newCommitmentTx)

    case Event(pkt: close_channel, DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, commitmentTx, _)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      them ! handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(WAIT_FOR_UPDATE_SIG_HIGHPRIO) {
    case Event(update_signature(theirSig, theirRevocationPreimage), DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, previousCommitmentTx, newCommitmentTx)) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      assert(new BinaryData(previousCommitmentTx.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage)), s"the revocation preimage they gave us is wrong! hash=${previousCommitmentTx.theirRevocationHash} preimage=$theirRevocationPreimage")
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourSig = Transaction.signInput(newCommitmentTx.tx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = newCommitmentTx.tx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitmentTx.tx.txIn(0).outPoint -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      if (!ok) log.error(s"invalid sig")
      them ! update_complete(previousCommitmentTx.ourRevocationPreimage)
      goto(NORMAL_LOWPRIO) using DATA_NORMAL(ourParams, theirParams, newCommitmentTx.copy(tx = signedCommitTx))

    case Event(cmd: CMD_CLOSE, DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, _)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      them ! handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(WAIT_FOR_UPDATE_SIG_LOWPRIO) {
    case Event(update_signature(theirSig, theirRevocationPreimage), DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, previousCommitmentTx, newCommitmentTx)) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      assert(new BinaryData(previousCommitmentTx.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage)), s"the revocation preimage they gave us is wrong! hash=${previousCommitmentTx.theirRevocationHash} preimage=$theirRevocationPreimage")
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourSig = Transaction.signInput(newCommitmentTx.tx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = newCommitmentTx.tx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitmentTx.tx.txIn(0).outPoint -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      if (!ok) log.error(s"invalid sig")
      them ! update_complete(previousCommitmentTx.ourRevocationPreimage)
      goto(NORMAL_HIGHPRIO) using DATA_NORMAL(ourParams, theirParams, newCommitmentTx.copy(tx = signedCommitTx))

    case Event(cmd: CMD_CLOSE, DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, commitmentTx, _)) =>
      them ! handle_cmd_close(cmd, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      them ! handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(WAIT_FOR_UPDATE_COMPLETE_HIGHPRIO) {
    case Event(update_complete(theirRevocationPreimage), DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, previousCommitmentTx, newCommitmentTx)) =>
      assert(new BinaryData(previousCommitmentTx.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage)), s"the revocation preimage they gave us is wrong! hash=${previousCommitmentTx.theirRevocationHash} preimage=$theirRevocationPreimage")
      goto(NORMAL_LOWPRIO) using DATA_NORMAL(ourParams, theirParams, newCommitmentTx)

    case Event(pkt: close_channel, DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, commitmentTx, _)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      them ! handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(WAIT_FOR_UPDATE_COMPLETE_LOWPRIO) {
    case Event(update_complete(theirRevocationPreimage), DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, previousCommitmentTx, newCommitmentTx)) =>
      assert(new BinaryData(previousCommitmentTx.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage)), s"the revocation preimage they gave us is wrong! hash=${previousCommitmentTx.theirRevocationHash} preimage=$theirRevocationPreimage")
      goto(NORMAL_HIGHPRIO) using DATA_NORMAL(ourParams, theirParams, newCommitmentTx)

    case Event(pkt: close_channel, DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, commitmentTx, _)) =>
      them ! handle_pkt_close(pkt, ourParams, theirParams, commitmentTx)
      goto(WAIT_FOR_CLOSE_ACK)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      them ! handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_OURCOMMIT)
  }

  when(WAIT_FOR_CLOSE_COMPLETE) {
    case Event(close_channel(theirSig, closeFee), DATA_NORMAL(ourParams, theirParams, CommitmentTx(commitmentTx, state, _, _))) =>
      //the only difference between their final tx and ours is the order of the outputs, because state is symmetric
      val theirFinalTx = makeFinalTx(commitmentTx.txIn, ourParams.finalKey, theirParams.finalKey, state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      val ourFinalTx = makeFinalTx(commitmentTx.txIn, ourParams.finalKey, theirParams.finalKey, state)
      val ourSig = bin2signature(Transaction.signInput(ourFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      val signedFinaltx = ourFinalTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      log.debug(s"final tx : ${Hex.toHexString(Transaction.write(signedFinaltx))}")
      // ok now we can broadcast the final tx if we want
      them ! close_channel_ack()
      goto(CLOSE_WAIT_CLOSE)

    case Event(close_channel_complete(theirSig), DATA_NORMAL(ourParams, theirParams, CommitmentTx(commitmentTx, state, _, _))) =>
      val finalTx = makeFinalTx(commitmentTx.txIn, ourParams.finalKey, theirParams.finalKey, state)
      val ourSig = Transaction.signInput(finalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedFinaltx = finalTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      log.debug(s"final tx : ${Hex.toHexString(Transaction.write(signedFinaltx))}")
      // ok now we can broadcast the final tx if we want
      them ! close_channel_ack()
      goto(CLOSE_WAIT_CLOSE)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_CLOSE)

    case Event(pkt: error, _) => stay
  }

  when(WAIT_FOR_CLOSE_ACK) {
    case Event(close_channel_ack(), _) =>
      goto(CLOSE_WAIT_CLOSE)

    case Event(pkt: error, _) => goto(CLOSE_WAIT_CLOSE)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_CLOSE)
  }

  when(CLOSE_WAIT_OURCOMMIT) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
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

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
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

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_CLOSE)
  }

  when(CLOSE_WAIT_CLOSE_OURCOMMIT) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_SPENDTHEM_CLOSE_OURCOMMIT)

    case Event(BITCOIN_ANCHOR_OURCOMMIT_DELAYPASSED, _) =>
      handle_btc_anchor_ourcommit_delaypassed()
      goto(CLOSE_WAIT_CLOSE_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL_CLOSE_OURCOMMIT)
  }

  when(CLOSE_WAIT_CLOSE_SPENDOURS) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
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

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_STEAL_SPENDTHEM)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      goto(CLOSE_WAIT_STEAL)
  }

  when(CLOSE_WAIT_STEAL_CLOSE) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_STEAL_SPENDTHEM_CLOSE)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      stay
  }

  when(CLOSE_WAIT_STEAL_CLOSE_OURCOMMIT) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
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

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
      goto(CLOSE_WAIT_STEAL_CLOSE_SPENDOURS)

    case Event(BITCOIN_ANCHOR_OTHERSPEND, _) =>
      handle_btc_anchor_otherspend()
      stay
  }

  when(CLOSE_WAIT_STEAL_OURCOMMIT) {
    case Event(BITCOIN_ANCHOR_UNSPENT, _) => goto(ERROR_ANCHOR_LOST)

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
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

    case Event(BITCOIN_ANCHOR_THEIRSPEND, _) =>
      them ! handle_btc_anchor_theirspend()
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
    case _ => stay
  }

  whenUnhandled {
    case Event(e@BITCOIN_TX_CONFIRMED(_, confirmations), _) =>
      log.debug(s"dropped $e")
      // drops silently, we don't care for confirmations above minDepth
      stay
  }

  /*
  HANDLERS
   */

  def handle_pkt_update_add_htlc(pkt: update_add_htlc, ourParams: ChannelParams, theirParams: ChannelParams, commitmentTx: CommitmentTx): (CommitmentTx, update_accept) = {
    // TODO : we should check that we can reach the next node (which can be the final payee) using routing info that will be provided in the msg
    // them ! update_decline_htlc(CannotRoute)
    // TODO : we should also make sure that funds are sufficient
    // them ! update_decline_htlc(InsufficientFunds)
    // the receiver of this message will have its balance increased : it is the receiver of the htlc
    val newState = commitmentTx.state.htlc_receive(pkt)
    val ourRevocationHashPreimage = randomsha256()
    val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
    val theirRevocationHash = pkt.revocationHash
    // we build our side of the new commitment tx
    val ourCommitTx = makeCommitTx(commitmentTx.tx.txIn, ourParams.finalKey, theirParams.finalKey, theirParams.delay, Crypto.sha256(ourRevocationHashPreimage), newState)
    // we build their commitment tx and sign it
    val theirCommitTx = makeCommitTx(commitmentTx.tx.txIn, theirParams.finalKey, ourParams.finalKey, ourParams.delay, theirRevocationHash, newState.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
    // note that this tx increases our balance so there is no risk in signing it
    // TODO : send update_add_htlc(revocationHash, amount, rHash, expiry - 1) *to the next node*
    (CommitmentTx(ourCommitTx, newState, ourRevocationHashPreimage, theirRevocationHash), update_accept(ourSigForThem, ourRevocationHash))
  }

  def handle_cmd_send_htlc_routefail(cmd: CMD_SEND_HTLC_ROUTEFAIL): (UpdateProposal, update_routefail_htlc) = {
    null
  }

  def handle_pkt_update_routefail_htlc(pkt: update_routefail_htlc): (CommitmentTx, update_accept) = {
    null
  }

  def handle_cmd_send_htlc_timedout(cmd: CMD_SEND_HTLC_TIMEDOUT): (UpdateProposal, update_timedout_htlc) = {
    null
  }

  def handle_pkt_update_timedout_htlc(pkt: update_timedout_htlc): (CommitmentTx, update_accept) = {
    null
  }

  def handle_pkt_update_complete(pkt: update_complete_htlc, ourParams: ChannelParams, theirParams: ChannelParams, commitmentTx: CommitmentTx): (CommitmentTx, update_accept) = {
    // they are requesting that we pay them in the channel in exchange for r
    val newState = commitmentTx.state.htlc_complete(pkt.r)
    val ourRevocationHashPreimage = randomsha256()
    val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
    val theirRevocationHash = pkt.revocationHash
    // we build our side of the new commitment tx
    val ourCommitTx = makeCommitTx(commitmentTx.tx.txIn, ourParams.finalKey, theirParams.finalKey, theirParams.delay, Crypto.sha256(ourRevocationHashPreimage), newState)
    // we build their commitment tx and sign it
    val theirCommitTx = makeCommitTx(commitmentTx.tx.txIn, theirParams.finalKey, ourParams.finalKey, ourParams.delay, theirRevocationHash, newState.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
    (CommitmentTx(ourCommitTx, newState, ourRevocationHashPreimage, theirRevocationHash), update_accept(ourSigForThem, ourRevocationHash))
  }

  def handle_cmd_close(cmd: CMD_CLOSE, ourParams: ChannelParams, theirParams: ChannelParams, commitmentTx: CommitmentTx): close_channel = {
    // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
    val theirFinalTx = makeFinalTx(commitmentTx.tx.txIn, theirParams.finalKey, ourParams.finalKey, commitmentTx.state.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
    close_channel(ourSigForThem, cmd.fee)
  }

  def handle_pkt_close(pkt: close_channel, ourParams: ChannelParams, theirParams: ChannelParams, commitmentTx: CommitmentTx): close_channel_complete = {
    // the only difference between their final tx and ours is the order of the outputs, because state is symmetric
    val theirFinalTx = makeFinalTx(commitmentTx.tx.txIn, ourParams.finalKey, theirParams.finalKey, commitmentTx.state.reverse)
    val ourSigForThem = bin2signature(Transaction.signInput(theirFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
    val ourFinalTx = makeFinalTx(commitmentTx.tx.txIn, ourParams.finalKey, theirParams.finalKey, commitmentTx.state)
    val ourSig = Transaction.signInput(ourFinalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
    val signedFinaltx = ourFinalTx.updateSigScript(0, sigScript2of2(pkt.sig, ourSig, theirParams.commitKey, ourParams.commitKey))
    log.debug(s"*** final tx : ${Hex.toHexString(Transaction.write(signedFinaltx))}")
    // ok now we can broadcast the final tx if we want
    close_channel_complete(ourSigForThem)
  }

  def handle_btc_anchor_theirspend(): error = {
    error(Some("Commit tx noticed"))
  }

  def handle_btc_anchor_ourcommit_delaypassed() = {

  }

  def handle_btc_anchor_otherspend(): error = {
    error(Some("Otherspend noticed"))
  }

}
