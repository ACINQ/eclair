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
case object WAIT_FOR_CLOSE_COMPLETE extends State
case object CLOSE_WAIT_CLOSE extends State
case object CLOSED extends State

// EVENTS

case object INPUT_NONE
sealed trait BlockchainEvent
final case class TxConfirmed(blockId: sha256_hash, confirmations: Int) extends BlockchainEvent
final case class BITCOIN_CLOSE_DONE()

sealed trait Command
case object CMD_SEND_UPDATE extends Command
final case class CMD_SEND_HTLC_UPDATE(amount: Long, rHash: sha256_hash, expiry: locktime) extends Command
final case class CMD_SEND_HTLC_COMPLETE(r: sha256_hash) extends Command
final case class CMD_CLOSE(fee: Long) extends Command

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
final case class DATA_OPEN_WAITING(ourParams: ChannelParams, theirParams: ChannelParams, commitmentTx: CommitmentTx, otherPartyOpen: Boolean = false) extends Data
final case class DATA_NORMAL(ourParams: ChannelParams, theirParams: ChannelParams, commitmentTx: CommitmentTx) extends Data
//TODO : create SignedTransaction
final case class DATA_WAIT_FOR_UPDATE_ACCEPT(ourParams: ChannelParams, theirParams: ChannelParams, previousCommitmentTxSigned: CommitmentTx, updateProposal: UpdateProposal) extends Data
final case class DATA_WAIT_FOR_HTLC_ACCEPT(ourParams: ChannelParams, theirParams: ChannelParams, previousCommitmentTxSigned: CommitmentTx, updateProposal: UpdateProposal) extends Data
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
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      them ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      goto(OPEN_WAIT_FOR_OPEN_NOANCHOR) using DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams, ourRevocationHashPreimage)
  }

  when(INIT_WITHANCHOR) {
    case Event(INPUT_NONE, anchorInput: AnchorInput) =>
      them = sender
      val ourParams = ChannelParams(DEFAULT_delay, commitPubKey, finalPubKey, DEFAULT_minDepth, DEFAULT_commitmentFee)
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      them ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      goto(OPEN_WAIT_FOR_OPEN_WITHANCHOR) using DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams, anchorInput, ourRevocationHashPreimage)
  }

  when(OPEN_WAIT_FOR_OPEN_NOANCHOR) {
    case Event(open_channel(delay, theirRevocationHash, commitKey, finalKey, WILL_CREATE_ANCHOR, minDepth, commitmentFee), DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams, ourRevocationPreimage)) =>
      val theirParams = ChannelParams(delay, commitKey, finalKey, minDepth.get, commitmentFee)
      goto(OPEN_WAIT_FOR_ANCHOR) using DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, ourRevocationPreimage, theirRevocationHash)
  }

  when(OPEN_WAIT_FOR_OPEN_WITHANCHOR) {
    case Event(open_channel(delay, theirRevocationHash, commitKey, finalKey, WONT_CREATE_ANCHOR, minDepth, commitmentFee), DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams, anchorInput, ourRevocationHashPreimage)) =>
      val theirParams = ChannelParams(delay, commitKey, finalKey, minDepth.get, commitmentFee)
      val anchorTx = makeAnchorTx(ourParams.commitKey, theirParams.commitKey, anchorInput.amount, anchorInput.previousTxOutput, anchorInput.signData)
      //TODO : anchorOutputIndex might not always be zero if there are multiple outputs
      val anchorOutputIndex = 0
      // we fund the channel with the anchor tx, so the money is ours
      val state = ChannelState(them = ChannelOneSide(0, 0, Seq()), us = ChannelOneSide(anchorInput.amount, 0, Seq()))
      // we build our commitment tx, leaving it unsigned
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchorTx.hash, anchorOutputIndex, theirRevocationHash, state)
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchorTx.hash, anchorOutputIndex, Crypto.sha256(ourRevocationHashPreimage), state.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      them ! open_anchor(anchorTx.hash, anchorOutputIndex, anchorInput.amount, ourSigForThem)
      goto(OPEN_WAIT_FOR_COMMIT_SIG) using DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchorTx, anchorOutputIndex, CommitmentTx(ourCommitTx, state, ourRevocationHashPreimage, theirRevocationHash))
  }

  when(OPEN_WAIT_FOR_ANCHOR) {
    case Event(open_anchor(anchorTxid, anchorOutputIndex, anchorAmount, theirSig), DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, ourRevocationHashPreimage, theirRevocationHash)) =>
      // they fund the channel with their anchor tx, so the money is theirs
      val state = ChannelState(them = ChannelOneSide(anchorAmount, 0, Seq()), us = ChannelOneSide(0, 0, Seq()))
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
      // TODO : register for confirmations of anchor tx on the bitcoin network
      goto(OPEN_WAITING_THEIRANCHOR) using DATA_OPEN_WAITING(ourParams, theirParams, CommitmentTx(signedCommitTx, state, ourRevocationHashPreimage, theirRevocationHash), false)
  }

  when(OPEN_WAIT_FOR_COMMIT_SIG) {
    case Event(open_commit_sig(theirSig), DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchorTx, anchorOutputIndex, newCommitTx)) =>
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourSig = Transaction.signInput(newCommitTx.tx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = newCommitTx.tx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(OutPoint(anchorTx.hash, anchorOutputIndex) -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      log.info(s"publishing anchor tx ${new BinaryData(Transaction.write(anchorTx))}")
      goto(OPEN_WAITING_OURANCHOR) using DATA_OPEN_WAITING(ourParams, theirParams, newCommitTx.copy(tx = signedCommitTx), false)
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
    case Event(CMD_SEND_UPDATE, DATA_NORMAL(ourParams, theirParams, p@CommitmentTx(previousCommitmentTx, previousState, _, _))) =>
      val (newState, delta) = previousState.fold
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      them ! update(ourRevocationHash, delta)
      goto(WAIT_FOR_UPDATE_ACCEPT) using DATA_WAIT_FOR_UPDATE_ACCEPT(ourParams, theirParams, p, UpdateProposal(newState, ourRevocationHashPreimage))

    case Event(update(theirRevocationHash, theirDelta), DATA_NORMAL(ourParams, theirParams, p@CommitmentTx(previousCommitmentTx, previousState, _, _))) =>
      val (newState, delta) = previousState.fold
      assert(delta == -theirDelta, s"delta mismatch, ours=$delta theirs=$theirDelta")
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      // we build our side of the new commitment tx
      val ourCommitTx = makeCommitTx(previousCommitmentTx.txIn, ourParams.finalKey, theirParams.finalKey, theirParams.delay, Crypto.sha256(ourRevocationHashPreimage), newState)
      // we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(previousCommitmentTx.txIn, theirParams.finalKey, ourParams.finalKey, ourParams.delay, theirRevocationHash, newState.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, p, CommitmentTx(ourCommitTx, newState, ourRevocationHashPreimage, theirRevocationHash))

    case Event(CMD_SEND_HTLC_UPDATE(amount, rHash, expiry), DATA_NORMAL(ourParams, theirParams, p@CommitmentTx(previousCommitmentTx, previousState, _, _))) =>
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      val htlc = update_add_htlc(ourRevocationHash, amount, rHash, expiry)
      val newState = previousState.copy(them = previousState.them.copy(htlcs = previousState.them.htlcs :+ htlc))
      them ! htlc
      goto(WAIT_FOR_HTLC_ACCEPT) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, p, UpdateProposal(newState, ourRevocationHashPreimage))

    case Event(m@update_add_htlc(theirRevocationHash, amount, rHash, expiry), DATA_NORMAL(ourParams, theirParams, p@CommitmentTx(previousCommitmentTx, previousState, _, _))) =>
      // TODO : we should probably check that we can reach the next node (which can be the final payee) using routing info that will be provided in the msg
      // the receiver of this message will have its balance increased : it is the receiver of the htlc
      val newState = previousState.copy(us = previousState.us.copy(htlcs = previousState.us.htlcs :+ m))
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      // we build our side of the new commitment tx
      val ourCommitTx = makeCommitTx(previousCommitmentTx.txIn, ourParams.finalKey, theirParams.finalKey, theirParams.delay, Crypto.sha256(ourRevocationHashPreimage), newState)
      // we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(previousCommitmentTx.txIn, theirParams.finalKey, ourParams.finalKey, ourParams.delay, theirRevocationHash, newState.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      // note that this tx increases our balance so there is no risk in signing it
      them ! update_accept(ourSigForThem, ourRevocationHash)
      // TODO : send update_add_htlc(revocationHash, amount, rHash, expiry - 1) *to the next node*
      goto(WAIT_FOR_UPDATE_SIG) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, p, CommitmentTx(ourCommitTx, newState, ourRevocationHashPreimage, theirRevocationHash))

    case Event(CMD_SEND_HTLC_COMPLETE(r), DATA_NORMAL(ourParams, theirParams, p@CommitmentTx(previousCommitmentTx, previousState, _, _))) =>
      val htlc = previousState.us.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).getOrElse(throw new RuntimeException(s"could not find corresponding htlc (r=$r)"))
      val newState = previousState.copy(them = previousState.them.copy(htlcs = previousState.them.htlcs.filterNot(_ == htlc)))
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      them ! update_complete_htlc(ourRevocationHash, r)
      goto(WAIT_FOR_HTLC_ACCEPT) using DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, p, UpdateProposal(newState, ourRevocationHashPreimage))

    case Event(update_complete_htlc(theirRevocationHash, r), DATA_NORMAL(ourParams, theirParams, p@CommitmentTx(previousCommitmentTx, previousState, _, _))) =>
      val htlc = previousState.them.htlcs.find(_.rHash == bin2sha256(Crypto.sha256(r))).getOrElse(throw new RuntimeException(s"could not find corresponding htlc (r=$r)"))
      val newState = previousState.copy(us = previousState.us.copy(htlcs = previousState.us.htlcs.filterNot(_ == htlc)))
      val ourRevocationHashPreimage = randomsha256()
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      // we build our side of the new commitment tx
      val ourCommitTx = makeCommitTx(previousCommitmentTx.txIn, ourParams.finalKey, theirParams.finalKey, theirParams.delay, Crypto.sha256(ourRevocationHashPreimage), newState)
      // we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(previousCommitmentTx.txIn, theirParams.finalKey, ourParams.finalKey, ourParams.delay, theirRevocationHash, newState.reverse)
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      them ! update_accept(ourSigForThem, ourRevocationHash)
      goto(WAIT_FOR_UPDATE_SIG) using DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, p, CommitmentTx(ourCommitTx, newState, ourRevocationHashPreimage, theirRevocationHash))

    case Event(CMD_CLOSE(fee), DATA_NORMAL(ourParams, theirParams, CommitmentTx(commitmentTx, state, _, _))) =>
      val finalTx = makeFinalTx(commitmentTx.txIn, ourParams.finalKey, theirParams.finalKey, state)
      val ourSigForThem = bin2signature(Transaction.signInput(finalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      them ! close_channel(ourSigForThem, fee)
      goto(WAIT_FOR_CLOSE_COMPLETE)

    case Event(close_channel(theirSig, closeFee), DATA_NORMAL(ourParams, theirParams, CommitmentTx(commitmentTx, state, _, _))) =>
      val finalTx = makeFinalTx(commitmentTx.txIn, ourParams.finalKey, theirParams.finalKey, state)
      val ourSigForThem = bin2signature(Transaction.signInput(finalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      // TODO : make sure the final tx is now spendable (should we spend it right now ?)
      them ! close_channel_complete(ourSigForThem)
      goto(WAIT_FOR_CLOSE_ACK)
  }

  when(WAIT_FOR_UPDATE_ACCEPT) {
    case Event(update_accept(theirSig, theirRevocationHash), DATA_WAIT_FOR_UPDATE_ACCEPT(ourParams, theirParams, previous, UpdateProposal(newState, ourRevocationHashPreimage))) =>
      // counterparty replied with the signature for the new commitment tx
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val newCommitmentTx = makeCommitTx(previous.tx.txIn, ourParams.finalKey, theirParams.finalKey, theirParams.delay, Crypto.sha256(ourRevocationHashPreimage), newState)
      val ourSig = Transaction.signInput(newCommitmentTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = newCommitmentTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previous.tx.txIn(0).outPoint -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      them ! update_signature(ourSig, previous.ourRevocationPreimage)
      goto(WAIT_FOR_UPDATE_COMPLETE) using DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, previous, CommitmentTx(signedCommitTx, newState, ourRevocationHashPreimage, theirRevocationHash))
  }

  when(WAIT_FOR_HTLC_ACCEPT) {
    case Event(update_accept(theirSig, theirRevocationHash), DATA_WAIT_FOR_HTLC_ACCEPT(ourParams, theirParams, previous, UpdateProposal(newState, ourRevocationHashPreimage))) =>
      // counterparty replied with the signature for the new commitment tx
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val newCommitmentTx = makeCommitTx(previous.tx.txIn, ourParams.finalKey, theirParams.finalKey, theirParams.delay, Crypto.sha256(ourRevocationHashPreimage), newState)
      val ourSig = Transaction.signInput(newCommitmentTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = newCommitmentTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(previous.tx.txIn(0).outPoint -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      them ! update_signature(ourSig, previous.ourRevocationPreimage)
      goto(WAIT_FOR_UPDATE_COMPLETE) using DATA_WAIT_FOR_UPDATE_COMPLETE(ourParams, theirParams, previous, CommitmentTx(signedCommitTx, newState, ourRevocationHashPreimage, theirRevocationHash))
  }

  when(WAIT_FOR_UPDATE_SIG) {
    case Event(update_signature(theirSig, theirRevocationPreimage), DATA_WAIT_FOR_UPDATE_SIG(ourParams, theirParams, previousCommitmentTx, newCommitmentTx)) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      assert(new BinaryData(previousCommitmentTx.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage)), s"the revocation preimage they gave us is wrong! hash=${previousCommitmentTx.theirRevocationHash} preimage=$theirRevocationPreimage")
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
      assert(new BinaryData(previousCommitmentTx.theirRevocationHash) == new BinaryData(Crypto.sha256(theirRevocationPreimage)), s"the revocation preimage they gave us is wrong! hash=${previousCommitmentTx.theirRevocationHash} preimage=$theirRevocationPreimage")
      goto(NORMAL) using DATA_NORMAL(ourParams, theirParams, newCommitmentTx)
    }
  }

  when(WAIT_FOR_CLOSE_COMPLETE) {
    case Event(close_channel(theirSig, closeFee), DATA_NORMAL(ourParams, theirParams, CommitmentTx(commitmentTx, state, _, _))) =>
      val finalTx = makeFinalTx(commitmentTx.txIn, ourParams.finalKey, theirParams.finalKey, state)
      val ourSigForThem = bin2signature(Transaction.signInput(finalTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      them ! close_channel_complete(ourSigForThem)
      goto(CLOSE_WAIT_CLOSE)

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
