package fr.acinq.eclair

import akka.actor.{ActorRef, LoggingFSM}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin._
import fr.acinq.lightning._
import lightning._
import lightning.locktime.Locktime.Blocks
import lightning.open_channel.anchor_offer.{WILL_CREATE_ANCHOR, WONT_CREATE_ANCHOR}
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
case object OPEN_WAIT_FOR_COMPLETE extends State
case object NORMAL extends State
case object WAIT_FOR_UPDATE_ACCEPT extends State
case object WAIT_FOR_UPDATE_SIG extends State
case object WAIT_FOR_UPDATE_COMPLETE extends State
case object WAIT_FOR_HTLC_ACCEPT extends State
case object WAIT_FOR_CLOSE_ACK extends State
case object CLOSE_WAIT_CLOSE extends State
case object CLOSED extends State

// EVENTS

case object INPUT_NONE
sealed trait BlockchainEvent
final case class TxConfirmed(blockId: sha256_hash, confirmations: Int)
final case class BITCOIN_CLOSE_DONE()

sealed trait Commands
case object CMD_SEND_UPDATE

// DATA

sealed trait Data
case object Nothing extends Data
final case class AnchorInput(amount: Long, previousTxOutput: OutPoint, signData: SignData) extends Data
final case class ChannelParams(delay: locktime, commitKey: bitcoin_pubkey, finalKey: bitcoin_pubkey, minDepth: Int, commitmentFee: Long)
final case class CommitmentTx(tx: Transaction, ourRevocationPreimage: sha256_hash)
final case class DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams: ChannelParams, ourRevocationPreimage: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams: ChannelParams, anchorInput: AnchorInput, ourRevocationPreimage: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_ANCHOR(ourParams: ChannelParams, theirParams: ChannelParams, ourRevocationPreimage: sha256_hash, theirRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams: ChannelParams, theirParams: ChannelParams, anchorTx: Transaction, anchorOutputIndex: Int, ourRevocationPreimage: sha256_hash, theirRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAITING(ourParams: ChannelParams, theirParams: ChannelParams, commitmentTx: CommitmentTx, otherPartyOpen: Boolean = false) extends Data
final case class DATA_NORMAL(ourParams: ChannelParams, theirParams: ChannelParams, commitmentTx: CommitmentTx) extends Data
//TODO : create SignedTransaction
final case class DATA_WAIT_FOR_UPDATE_ACCEPT(ourParams: ChannelParams, theirParams: ChannelParams, previousCommitmentTxSigned: CommitmentTx, newCommitmentTxUnsigned: CommitmentTx) extends Data
final case class DATA_WAIT_FOR_UPDATE_SIG(ourParams: ChannelParams, theirParams: ChannelParams, previousCommitmentTxSigned: CommitmentTx, newCommitmentTxUnsigned: CommitmentTx) extends Data
final case class DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams: ChannelParams, theirParams: ChannelParams, previousCommitmentTxSigned: CommitmentTx, newCommitmentTxUnsigned: CommitmentTx) extends Data

// @formatter:on

class Node(val commitPrivKey: BinaryData, val finalPrivKey: BinaryData, val anchorDataOpt: Option[AnchorInput]) extends LoggingFSM[State, Data] {

  val DEFAULT_delay = locktime(Blocks(10))
  val DEFAULT_minDepth = 2
  val DEFAULT_commitmentFee = 100

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
      val ourParams = ChannelParams(DEFAULT_delay, commitPubKey, finalPubKey, DEFAULT_minDepth, DEFAULT_commitmentFee)
      //TODO : should be random
      val ourRevocationHashPreimage = sha256_hash(1, 2, 3, 4)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      them ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      goto(OPEN_WAIT_FOR_OPEN_NOANCHOR) using DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams, ourRevocationHashPreimage)
  }

  when(INIT_WITHANCHOR) {
    case Event(INPUT_NONE, anchorInput: AnchorInput) =>
      them = sender
      val ourParams = ChannelParams(DEFAULT_delay, commitPubKey, finalPubKey, DEFAULT_minDepth, DEFAULT_commitmentFee)
      //TODO : should be random
      val ourRevocationHashPreimage = sha256_hash(1, 2, 3, 4)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      them ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      goto(OPEN_WAIT_FOR_OPEN_WITHANCHOR) using DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams, anchorInput, ourRevocationHashPreimage)
  }

  when(OPEN_WAIT_FOR_OPEN_NOANCHOR) {
    case Event(open_channel(delay, revocationHash, commitKey, finalKey, WILL_CREATE_ANCHOR, minDepth, commitmentFee), DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams, ourRevocationPreimage)) =>
      val theirParams = ChannelParams(delay, commitKey, finalKey, minDepth.get, commitmentFee)
      goto(OPEN_WAIT_FOR_ANCHOR) using DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, ourRevocationPreimage, revocationHash)
  }

  when(OPEN_WAIT_FOR_OPEN_WITHANCHOR) {
    case Event(open_channel(delay, revocationHash, commitKey, finalKey, WONT_CREATE_ANCHOR, minDepth, commitmentFee), DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams, anchorInput, ourRevocationHashPreimage)) =>
      val theirParams = ChannelParams(delay, commitKey, finalKey, minDepth.get, commitmentFee)
      val anchorTx = makeAnchorTx(ourParams.commitKey, theirParams.commitKey, anchorInput.amount, anchorInput.previousTxOutput, anchorInput.signData)
      //TODO : anchorOutputIndex might not always be zero if there are multiple outputs
      val anchorOutputIndex = 0
      // then we build their commitment tx and sign it
      val state = ChannelState(ChannelOneSide(anchorInput.amount, 0, Seq()), ChannelOneSide(0, 0, Seq()))
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchorTx.hash, anchorOutputIndex, Crypto.sha256(ourRevocationHashPreimage), state.copy(a = state.b, b = state.a))
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      them ! open_anchor(anchorTx.hash, anchorOutputIndex, anchorInput.amount, ourSigForThem)
      goto(OPEN_WAIT_FOR_COMMIT_SIG) using DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchorTx, anchorOutputIndex, ourRevocationHashPreimage, revocationHash)
  }

  when(OPEN_WAIT_FOR_ANCHOR) {
    case Event(open_anchor(anchorTxid, anchorOutputIndex, anchorAmount, theirSig), DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, ourRevocationHashPreimage, theirRevocationHash)) =>
      val state = ChannelState(ChannelOneSide(anchorAmount, 0, Seq()), ChannelOneSide(0, 0, Seq()))
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchorTxid, anchorOutputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      // TODO : Transaction.sign(...) should handle multisig
      val ourSig = Transaction.signInput(ourCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = ourCommitTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(OutPoint(anchorTxid, anchorOutputIndex) -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchorTxid, anchorOutputIndex, theirRevocationHash, state.copy(a = state.b, b = state.a))
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      them ! open_commit_sig(ourSigForThem)
      // TODO : register for confirmations of anchor tx on the bitcoin network
      goto(OPEN_WAITING_THEIRANCHOR) using DATA_OPEN_WAITING(ourParams, theirParams, CommitmentTx(signedCommitTx, ourRevocationHashPreimage), false)
  }

  when(OPEN_WAIT_FOR_COMMIT_SIG) {
    case Event(open_commit_sig(theirSig), DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchorTx, anchorOutputIndex, ourRevocationHashPreimage, theirRevocationHash)) =>
      val state = ChannelState(ChannelOneSide(anchorTx.txOut(0).amount, 0, Seq()), ChannelOneSide(0, 0, Seq()))
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchorTx.hash, anchorOutputIndex, Crypto.sha256(ourRevocationHashPreimage), state)
      val ourSig = Transaction.signInput(ourCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = ourCommitTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(OutPoint(anchorTx.hash, anchorOutputIndex) -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      log.info(s"publishing anchor tx ${new BinaryData(Transaction.write(anchorTx))}")
      goto(OPEN_WAITING_OURANCHOR) using DATA_OPEN_WAITING(ourParams, theirParams, CommitmentTx(ourCommitTx, ourRevocationHashPreimage), false)
  }

  when(OPEN_WAITING_THEIRANCHOR) {
    case Event(TxConfirmed(blockId, confirmations), DATA_OPEN_WAITING(ourParams, _, _, _)) if confirmations < ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx")
      stay

    case Event(TxConfirmed(blockId, confirmations), d@DATA_OPEN_WAITING(ourParams, _, _, false)) if confirmations >= ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached")
      them ! open_complete(Some(blockId))
      goto(OPEN_WAIT_FOR_COMPLETE) using DATA_NORMAL(d.ourParams, d.theirParams, d.commitmentTx)

    case Event(TxConfirmed(blockId, confirmations), d@DATA_OPEN_WAITING(ourParams, _, _, true)) if confirmations >= ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached, and already received their open_complete")
      them ! open_complete(Some(blockId))
      goto(NORMAL) using DATA_NORMAL(d.ourParams, d.theirParams, d.commitmentTx)

    case Event(open_complete(blockId_opt), d@DATA_OPEN_WAITING(ourParams, _, _, _)) =>
      log.info(s"received their open_complete, blockId=${blockId_opt.map(x => Hex.toHexString(sha2562bin(x))).getOrElse("unknown")}")
      stay using d.copy(otherPartyOpen = true)
  }

  when(OPEN_WAITING_OURANCHOR) {
    case Event(TxConfirmed(blockId, confirmations), DATA_OPEN_WAITING(ourParams, _, _, _)) if confirmations < ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx")
      stay

    case Event(TxConfirmed(blockId, confirmations), d@DATA_OPEN_WAITING(ourParams, _, _, false)) if confirmations >= ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached")
      them ! open_complete(Some(blockId))
      goto(OPEN_WAIT_FOR_COMPLETE) using DATA_NORMAL(d.ourParams, d.theirParams, d.commitmentTx)

    case Event(TxConfirmed(blockId, confirmations), d@DATA_OPEN_WAITING(ourParams, _, _, true)) if confirmations >= ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached, and already received their open_complete")
      them ! open_complete(Some(blockId))
      goto(NORMAL) using DATA_NORMAL(d.ourParams, d.theirParams, d.commitmentTx)

    case Event(open_complete(blockId_opt), d@DATA_OPEN_WAITING(ourParams, _, _, _)) =>
      log.info(s"received their open_complete, blockId=${blockId_opt.map(x => Hex.toHexString(sha2562bin(x))).getOrElse("unknown")}")
      stay using d.copy(otherPartyOpen = true)
  }

  when(OPEN_WAIT_FOR_COMPLETE) {
    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      goto(NORMAL) using d
  }

  when(NORMAL) {
    case Event(CMD_SEND_UPDATE, DATA_NORMAL(ourParams, theirParams, previousCommitmentTx)) =>
      // TODO : should be random
      val ourRevocationHashPreimage = sha256_hash(1, 2, 3, 4)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      // TODO : delta should be computed
      val delta = 5000000
      // TODO : state is wrong
      val state = ChannelState(ChannelOneSide(delta, 0, Seq()), ChannelOneSide(0, 0, Seq()))
      // we build our side of the new commitment tx
      val ourCommitTx = makeCommitTx(previousCommitmentTx.tx.txIn, ourParams.finalKey, theirParams.finalKey, theirParams.delay, Crypto.sha256(ourRevocationHashPreimage), state)
      them ! update(ourRevocationHash, delta)
      goto(WAIT_FOR_UPDATE_ACCEPT) using DATA_WAIT_FOR_UPDATE_ACCEPT(ourParams, theirParams, previousCommitmentTx, CommitmentTx(ourCommitTx, ourRevocationHashPreimage))

    case Event(update(theirRevocationHash, delta), DATA_NORMAL(ourParams, theirParams, previousCommitmentTx)) =>
      // TODO : we should check the delta
      // TODO : should be random
      val ourRevocationHashPreimage = sha256_hash(1, 2, 3, 4)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      // TODO : state is wrong
      val state = ChannelState(ChannelOneSide(delta, 0, Seq()), ChannelOneSide(0, 0, Seq()))
      // we build our side of the new commitment tx
      val ourCommitTx = makeCommitTx(previousCommitmentTx.tx.txIn, ourParams.finalKey, theirParams.finalKey, theirParams.delay, Crypto.sha256(ourRevocationHashPreimage), state)
      // we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(previousCommitmentTx.tx.txIn, theirParams.finalKey, ourParams.finalKey, ourParams.delay, theirRevocationHash, state.copy(a = state.b, b = state.a))
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, previousCommitmentTx, CommitmentTx(ourCommitTx, ourRevocationHashPreimage))

    case Event(update_add_htlc(revocationHash, amount, rHash, expiry), d: DATA_NORMAL) =>
      // TODO : we should probably check that we can reach the next node (which can be the final payee) using routing info that will be provided in the msg
      // TODO : should be random
      val ourRevocationHashPreimage = sha256_hash(1, 2, 3, 4)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      // TODO : build htlc output
      val ourCommitTx = null
      // TODO : build their commitment tx and sign it
      val theirCommitTx = null
      val ourSigForThem = null
      // TODO : reply with send update_accept(sig, revocationHash) ; note that this tx increases our balance so there is no risk in signing it
      them ! update_accept(ourSigForThem, ourRevocationHash)
      // TODO : send update_add_htlc(revocationHash, amount, rHash, expiry - 1) *to the next node*
      goto(WAIT_FOR_UPDATE_SIG)

    case Event(update_complete_htlc(revocationHash, r), d: DATA_NORMAL) =>
      // TODO : check that r hashes to the rHash we have
      // TODO : should be random
      val ourRevocationHashPreimage = sha256_hash(1, 2, 3, 4)
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      // TODO : remove htlc and update balance in the channel
      val ourCommitTx = null
      // TODO : build their commitment tx and sign it
      val theirCommitTx = null
      val ourSigForThem = null
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG)

    case Event(close_channel(theirSig, closeFee), d: DATA_NORMAL) =>
      // TODO generate a standard multisig tx with one output to their final key and one output to our final key
      val ourSig = signature(1, 2, 3, 4, 5, 6, 7, 8)
      them ! close_channel_complete(ourSig)
      goto(WAIT_FOR_CLOSE_ACK)
  }

  when(WAIT_FOR_UPDATE_ACCEPT) {
    case Event(update_accept(theirSig, theirRevocationHash), DATA_WAIT_FOR_UPDATE_ACCEPT(ourParams, theirParams, previousCommitmentTx, newCommitmentTx)) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      // TODO : check that revocationPreimage indeed hashes to the revocationHash they gave us previously
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourSig = Transaction.signInput(newCommitmentTx.tx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = newCommitmentTx.tx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitmentTx.tx.txIn(0).outPoint -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      them ! update_signature(ourSig, previousCommitmentTx.ourRevocationPreimage)
      goto(WAIT_FOR_UPDATE_COMPLETE) using DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, previousCommitmentTx, newCommitmentTx.copy(tx = signedCommitTx))
  }

  when(WAIT_FOR_UPDATE_SIG) {
    case Event(update_signature(theirSig, revocationPreimage), DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, previousCommitmentTx, newCommitmentTx)) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      // TODO : check that revocationPreimage indeed hashes to the revocationHash they gave us previously
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourSig = Transaction.signInput(newCommitmentTx.tx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = newCommitmentTx.tx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previousCommitmentTx.tx.txIn(0).outPoint -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      them ! update_complete(previousCommitmentTx.ourRevocationPreimage)
      goto(NORMAL) using DATA_NORMAL(ourParams, theirParams, newCommitmentTx.copy(tx = signedCommitTx))
  }

  when(WAIT_FOR_UPDATE_COMPLETE) {
    case Event(update_complete(theirRevocationPreimage), DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, previousCommitmentTx, newCommitmentTx)) => {
      // TODO : check that revocationPreimage indeed hashes to the revocationHash they gave us previously
      goto(NORMAL) using DATA_NORMAL(ourParams, theirParams, newCommitmentTx)
    }
  }

  when(WAIT_FOR_CLOSE_ACK) {
    case Event(close_channel_complete(theirSig), _) =>
    // ok now we can broadcast the final tx if we want
    goto(CLOSE_WAIT_CLOSE)
  }

  when(CLOSE_WAIT_CLOSE) {
    case Event(BITCOIN_CLOSE_DONE, _) =>
      goto(CLOSED)
  }

}
