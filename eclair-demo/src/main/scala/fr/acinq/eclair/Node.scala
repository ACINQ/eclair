package fr.acinq.eclair

import akka.actor.{LoggingFSM, FSM}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin.Transaction
import lightning._
import lightning.locktime.Locktime.Blocks
import lightning.open_channel.anchor_offer.WILL_CREATE_ANCHOR

/**
 * Created by PM on 20/08/2015.
 */

// STATES

sealed trait State
case object INIT_NOANCHOR extends State
case object OPEN_WAIT_FOR_OPEN_NOANCHOR extends State
case object OPEN_WAIT_FOR_ANCHOR extends State
case object OPEN_WAITING extends State
case object OPEN_WAIT_FOR_COMPLETE extends State
case object NORMAL extends State
case object WAIT_FOR_HTLC_ACCEPT extends State
case object WAIT_FOR_UPDATE_COMPLETE extends State
case object WAIT_FOR_UPDATE_SIG extends State
case object CLOSED extends State

// EVENTS

case object INPUT_NONE

sealed trait BlockchainEvent
final case class TxConfirmed(confirmations: Int)

sealed trait Commands
final case class SendHtlcUpdate(amount: Long, finalPayee: String, rHash: sha256_hash)

// DATA

sealed trait Data
case object Uninitialized extends Data
final case class ChannelParams(delay: locktime, commitKey: bitcoin_pubkey, finalKey: bitcoin_pubkey, minDepth: Int, commitmentFee: Long)
final case class Anchor(txid: sha256_hash, outputIndex: Int, amount: Long)
final case class CommitmentTx(tx: Transaction, theirSig: signature, ourRevocationPreimage: sha256_hash)
final case class DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams: ChannelParams, ourRevocationPreimage: sha256_hash) extends Data
final case class DATA_OPEN_WAIT_FOR_ANCHOR(ourParams: ChannelParams, theirParams: ChannelParams, ourRevocationPreimage: sha256_hash, theirRevocationHash: sha256_hash) extends Data
final case class DATA_OPEN_WAITING(ourParams: ChannelParams, theirParams: ChannelParams, anchor: Anchor, commitmentTx: CommitmentTx, otherPartyOpen: Boolean = false) extends Data
final case class DATA_NORMAL(ourParams: ChannelParams, theirParams: ChannelParams, anchor: Anchor, commitmentTx: CommitmentTx) extends Data

//

class Node extends LoggingFSM[State, Data] {

  val DEFAULT_delay = locktime(Blocks(1))
  val DEFAULT_commitKey = bitcoin_pubkey(ByteString.copyFromUtf8("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
  val DEFAULT_finalKey = bitcoin_pubkey(ByteString.copyFromUtf8("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
  val DEFAULT_minDepth = 2
  val DEFAULT_commitmentFee = 100

  startWith(INIT_NOANCHOR, Uninitialized)

  when(INIT_NOANCHOR) {
    case Event(INPUT_NONE, _) =>
      val ourParams = ChannelParams(DEFAULT_delay, DEFAULT_commitKey, DEFAULT_finalKey, DEFAULT_minDepth, DEFAULT_commitmentFee)
      val ourRevocationHashPreimage = sha256_hash(1, 1, 1, 1)
      val ourRevocationHash = sha256_hash(1, 1, 1, 1)
      // TODO : send open_channel(delay, ourRevocationHash, commitKey, finalKey, WONT_CREATE_ANCHOR, minDepth, commitmentFee)
      goto(OPEN_WAIT_FOR_OPEN_NOANCHOR) using DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams, ourRevocationHashPreimage)
  }

  when(OPEN_WAIT_FOR_OPEN_NOANCHOR) {
    case Event(open_channel(delay, revocationHash, commitKey, finalKey, WILL_CREATE_ANCHOR, minDepth, commitmentFee), DATA_OPEN_WAIT_FOR_OPEN_NOANCHOR(ourParams, ourRevocationPreimage)) =>
      val theirParams = ChannelParams(delay, commitKey, finalKey, minDepth.get, commitmentFee)
      goto(OPEN_WAIT_FOR_ANCHOR) using DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, ourRevocationPreimage, revocationHash)
  }

  when(OPEN_WAIT_FOR_ANCHOR) {
    case Event(open_anchor(txid, outputIndex, amount, commitSig), DATA_OPEN_WAIT_FOR_ANCHOR(ourParams, theirParams, ourRevocationPreimage, theirRevocationHash)) =>
      val anchor = Anchor(txid, outputIndex, amount)
      // TODO : build our commitment tx and check counterparty's sig
      // TODO : build counterparty's commitment tx and sign it
      val ourSig = signature(1, 1, 1, 1, 0, 0, 0, 0)
      // TODO : reply with open_commit_sig(sig)
      // TODO : register for confirmations of anchor tx on the bitcoin network
      goto(OPEN_WAITING) using DATA_OPEN_WAITING(ourParams, theirParams, anchor, null, false)
  }

  when(OPEN_WAITING) {
    case Event(TxConfirmed(confirmations), DATA_OPEN_WAITING(ourParams, _, _, _, _)) if confirmations < ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx")
      stay

    case Event(TxConfirmed(confirmations), d@DATA_OPEN_WAITING(ourParams, _, _, _, false)) if confirmations >= ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached")
      // TODO : send open_complete(blockid)
      goto(OPEN_WAIT_FOR_COMPLETE) using DATA_NORMAL(d.ourParams, d.theirParams, d.anchor, d.commitmentTx)

    case Event(TxConfirmed(confirmations), d@DATA_OPEN_WAITING(ourParams, _, _, _, true)) if confirmations >= ourParams.minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached, and already received their open_complete")
      // TODO : send open_complete(blockid)
      goto(NORMAL) using DATA_NORMAL(d.ourParams, d.theirParams, d.anchor, d.commitmentTx)

    case Event(open_complete(blockid_opt), d@DATA_OPEN_WAITING(ourParams, _, _, _, _)) =>
      log.info(s"received their open_complete")
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
