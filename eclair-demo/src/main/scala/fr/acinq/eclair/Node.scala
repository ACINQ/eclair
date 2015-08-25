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
case object WAIT_FOR_HTLC_ACCEPT extends State
case object WAIT_FOR_UPDATE_COMPLETE extends State
case object WAIT_FOR_UPDATE_SIG extends State
case object CLOSED extends State

// EVENTS

case object INPUT_NONE

sealed trait BlockchainEvent
final case class TxConfirmed(blockId: sha256_hash, confirmations: Int)

sealed trait Commands
final case class SendHtlcUpdate(amount: Long, finalPayee: String, rHash: sha256_hash)

// DATA

sealed trait Data
case object Nothing extends Data
final case class AnchorInput(amount: Long, previousTxOutput: OutPoint, signData: SignData) extends Data
final case class ChannelParams(delay: locktime, commitKey: bitcoin_pubkey, finalKey: bitcoin_pubkey, minDepth: Int, commitmentFee: Long)
final case class Anchor(txid: sha256_hash, outputIndex: Int, amount: Long)
final case class CommitmentTx(tx: Transaction, ourRevocationPreimage: sha256_hash)
final case class DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams: ChannelParams, ourRevocationPreimage: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_OPEN_WITHANCHOR(ourParams: ChannelParams, anchorInput: AnchorInput, ourRevocationPreimage: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_ANCHOR(ourParams: ChannelParams, theirParams: ChannelParams, ourRevocationPreimage: sha256_hash, theirRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams: ChannelParams, theirParams: ChannelParams, anchor: Anchor, ourRevocationPreimage: sha256_hash, theirRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAITING(ourParams: ChannelParams, theirParams: ChannelParams, anchor: Anchor, commitmentTx: CommitmentTx, otherPartyOpen: Boolean = false) extends Data
final case class DATA_NORMAL(ourParams: ChannelParams, theirParams: ChannelParams, anchor: Anchor, commitmentTx: CommitmentTx) extends Data

//

class Node(val commitPrivKey: BinaryData, val finalPrivKey: BinaryData, val anchorDataOpt: Option[AnchorInput]) extends LoggingFSM[State, Data] {

  val DEFAULT_delay = locktime(Blocks(1))
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
      val ourRevocationHashPreimage = sha256_hash(1, 2, 3, 4) //TODO : should be random
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      sender ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WONT_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
      goto(OPEN_WAIT_FOR_OPEN_NOANCHOR) using DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams, ourRevocationHashPreimage)
  }

  when(INIT_WITHANCHOR) {
    case Event(INPUT_NONE, anchorInput: AnchorInput) =>
      them = sender
      val ourParams = ChannelParams(DEFAULT_delay, commitPubKey, finalPubKey, DEFAULT_minDepth, DEFAULT_commitmentFee)
      val ourRevocationHashPreimage = sha256_hash(1, 2, 3, 4) //TODO : should be random
      val ourRevocationHash = Crypto.sha256(ourRevocationHashPreimage)
      sender ! open_channel(ourParams.delay, ourRevocationHash, ourParams.commitKey, ourParams.finalKey, WILL_CREATE_ANCHOR, Some(ourParams.minDepth), ourParams.commitmentFee)
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
      // TODO create the anchor (without publishing it !)
      val anchor = Anchor(anchorInput.previousTxOutput.hash, anchorInput.previousTxOutput.index.toInt, anchorInput.amount)
      // then we build their commitment tx and sign it
      val state = ChannelState(ChannelOneSide(anchor.amount, 0, Seq()), ChannelOneSide(0, 0, Seq()))
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchor.txid, anchor.outputIndex, Crypto.sha256(ourRevocationHashPreimage), state.copy(a = state.b, b = state.a))
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, anchor.outputIndex, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      sender ! open_anchor(anchor.txid, anchor.outputIndex, anchor.amount, ourSigForThem)
      goto(OPEN_WAIT_FOR_COMMIT_SIG) using DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchor, ourRevocationHashPreimage, revocationHash)
  }

  when(OPEN_WAIT_FOR_ANCHOR) {
    case Event(open_anchor(txid, outputIndex, amount, theirSig), DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, ourRevocationHashPreimage, theirRevocationHash)) =>
      val anchor = Anchor(txid, outputIndex, amount)
      val state = ChannelState(ChannelOneSide(amount, 0, Seq()), ChannelOneSide(0, 0, Seq()))
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchor.txid, anchor.outputIndex, theirRevocationHash, state)
      val ourSig = Transaction.signInput(ourCommitTx, anchor.outputIndex, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = ourCommitTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(OutPoint(txid, outputIndex) -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      // then we build their commitment tx and sign it
      val theirCommitTx = makeCommitTx(theirParams.finalKey, ourParams.finalKey, ourParams.delay, anchor.txid, anchor.outputIndex, Crypto.sha256(ourRevocationHashPreimage), state.copy(a = state.b, b = state.a))
      val ourSigForThem = bin2signature(Transaction.signInput(theirCommitTx, 0, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey)))
      sender ! open_commit_sig(ourSigForThem)
      // TODO : register for confirmations of anchor tx on the bitcoin network
      goto(OPEN_WAITING_THEIRANCHOR) using DATA_OPEN_WAITING(ourParams, theirParams, anchor, CommitmentTx(ourCommitTx, ourRevocationHashPreimage), false)
  }

  when(OPEN_WAIT_FOR_COMMIT_SIG) {
    case Event(open_commit_sig(theirSig), DATA_OPEN_WAIT_FOR_COMMIT_SIG(ourParams, theirParams, anchor, ourRevocationHashPreimage, theirRevocationHash)) =>
      val state = ChannelState(ChannelOneSide(anchor.amount, 0, Seq()), ChannelOneSide(0, 0, Seq()))
      // we build our commitment tx, sign it and check that it is spendable using the counterparty's sig
      val ourCommitTx = makeCommitTx(ourParams.finalKey, theirParams.finalKey, theirParams.delay, anchor.txid, anchor.outputIndex, theirRevocationHash, state)
      val ourSig = Transaction.signInput(ourCommitTx, anchor.outputIndex, multiSig2of2(ourParams.commitKey, theirParams.commitKey), SIGHASH_ALL, pubkey2bin(commitPrivKey))
      val signedCommitTx = ourCommitTx.updateSigScript(0, sigScript2of2(theirSig, ourSig, theirParams.commitKey, ourParams.commitKey))
      val ok = Try(Transaction.correctlySpends(signedCommitTx, Map(OutPoint(anchor.txid, anchor.outputIndex) -> multiSig2of2(ourParams.commitKey, theirParams.commitKey)), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      // TODO : return Error and close channel if !ok
      // TODO : publish the anchor
      goto(OPEN_WAITING_OURANCHOR) using DATA_OPEN_WAITING(ourParams, theirParams, anchor, CommitmentTx(ourCommitTx, ourRevocationHashPreimage), false)
  }

  when(OPEN_WAITING_THEIRANCHOR) {
    case Event(TxConfirmed(blockId, confirmations), DATA_OPEN_WAITING(ourParams, _, _, _, _)) if confirmations < ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx")
      stay

    case Event(TxConfirmed(blockId, confirmations), d@DATA_OPEN_WAITING(ourParams, _, _, _, false)) if confirmations >= ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached")
      them ! open_complete(Some(blockId))
      goto(OPEN_WAIT_FOR_COMPLETE) using DATA_NORMAL(d.ourParams, d.theirParams, d.anchor, d.commitmentTx)

    case Event(TxConfirmed(blockId, confirmations), d@DATA_OPEN_WAITING(ourParams, _, _, _, true)) if confirmations >= ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached, and already received their open_complete")
      them ! open_complete(Some(blockId))
      goto(NORMAL) using DATA_NORMAL(d.ourParams, d.theirParams, d.anchor, d.commitmentTx)

    case Event(open_complete(blockId_opt), d@DATA_OPEN_WAITING(ourParams, _, _, _, _)) =>
      log.info(s"received their open_complete, blockId=${blockId_opt.map(x => Hex.toHexString(sha2562bin(x))).getOrElse("unknown")}")
      stay using d.copy(otherPartyOpen = true)
  }

  when(OPEN_WAITING_OURANCHOR) {
    case Event(TxConfirmed(blockId, confirmations), DATA_OPEN_WAITING(ourParams, _, _, _, _)) if confirmations < ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx")
      stay

    case Event(TxConfirmed(blockId, confirmations), d@DATA_OPEN_WAITING(ourParams, _, _, _, false)) if confirmations >= ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached")
      them ! open_complete(Some(blockId))
      goto(OPEN_WAIT_FOR_COMPLETE) using DATA_NORMAL(d.ourParams, d.theirParams, d.anchor, d.commitmentTx)

    case Event(TxConfirmed(blockId, confirmations), d@DATA_OPEN_WAITING(ourParams, _, _, _, true)) if confirmations >= ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached, and already received their open_complete")
      them ! open_complete(Some(blockId))
      goto(NORMAL) using DATA_NORMAL(d.ourParams, d.theirParams, d.anchor, d.commitmentTx)

    case Event(open_complete(blockId_opt), d@DATA_OPEN_WAITING(ourParams, _, _, _, _)) =>
      log.info(s"received their open_complete, blockId=${blockId_opt.map(x => Hex.toHexString(sha2562bin(x))).getOrElse("unknown")}")
      stay using d.copy(otherPartyOpen = true)
  }

  when(OPEN_WAIT_FOR_COMPLETE) {
    case Event(open_complete(blockid_opt), d: DATA_NORMAL) =>
      goto(NORMAL) using d
  }

  when(NORMAL) {
    /*case Event(SendHtlcUpdate(amount), _) =>
      // TODO : we should reach the final payee, and get a rHash, and an expiry (which depends on the route)
      // TODO : send update_add_htlc(revocationHash, amount, rHash, expiry)
      goto(WAIT_FOR_HTLC_ACCEPT)*/

    case Event(update_add_htlc(revocationHash, amount, rHash, expiry), d: DATA_NORMAL) =>
      // TODO : we should probably check that we can reach the next node (which can be the final payee) using routing info that will be provided in the msg
      // TODO : reply with send update_accept(sig, revocationHash) ; note that this tx increases our balance so there is no risk in signing it
      // TODO : send update_add_htlc(revocationHash, amount, rHash, expiry - 1) *to the next node*
    goto(WAIT_FOR_UPDATE_SIG)

    case Event(update_complete_htlc(revocationHash, r), d: DATA_NORMAL) => // we get that from the *next node*
      // TODO : send update_accept(sig, revocationHash)
      goto(WAIT_FOR_UPDATE_SIG)
  }

  /*when(WAIT_FOR_UPDATE_SIG) {
    case Event(update_signature(sig, revocationPreimage), x: SimpleCommitmentTxData) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      // TODO : check that revocationPreimage indeed hashes to the revocationHash gave us previously
      // TODO : reply with update_complete(revocationPreimage) which revokes previous commit tx
      goto(NORMAL)

    case Event(update_signature(sig, revocationPreimage), x: HTLCCommitTxData) =>
      // TODO : reply with update_complete(revocationPreimage) which revokes previous commit tx
      goto(NORMAL)
  }*/

  /*when(WAIT_FOR_HTLC_ACCEPT) {
    case Event(update_accept(sig, revocationHash), _) =>
      // TODO : ???
      goto(WAIT_FOR_UPDATE_COMPLETE)
  }*/

  when(WAIT_FOR_UPDATE_COMPLETE) {
    case Event(update_complete(revocationPreimage), _) =>
      // TODO : ???
    goto(NORMAL)
  }

}
