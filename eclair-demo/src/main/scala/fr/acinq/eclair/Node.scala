package fr.acinq.eclair

import akka.actor.{LoggingFSM, FSM}
import lightning._
import lightning.open_channel.anchor_offer.WILL_CREATE_ANCHOR

/**
 * Created by PM on 20/08/2015.
 */

sealed trait State
case object OPEN_WAIT_FOR_OPEN_NOANCHOR extends State
case object OPEN_WAIT_FOR_ANCHOR extends State
case object OPEN_WAITING extends State
case object OPEN_WAIT_FOR_COMPLETE extends State
case object NORMAL extends State
case object WAIT_FOR_HTLC_ACCEPT extends State
case object WAIT_FOR_UPDATE_COMPLETE extends State
case object WAIT_FOR_UPDATE_SIG extends State

sealed trait Data
case object Uninitialized extends Data
final case class ChannelParams(minDepth: Int) extends Data
final case class OurSimpleCommitmentTx(theirRevocationHash: sha256_hash, theirSig: Option[signature])
final case class TheirSimpleCommitmentTx(ourRevocationPreimage: sha256_hash, ourSig: Option[signature])
final case class SimpleCommitmentTxData(params: ChannelParams, ourCommitTx: OurSimpleCommitmentTx, theirCommitmentTx: TheirSimpleCommitmentTx) extends Data
final case class HTLCCommitTxData() extends Data


//blockchain events
sealed trait BlockchainEvent
final case class TxConfirmed(confirmations: Int)

//commands
sealed trait Commands
final case class SendHtlcUpdate(amount: Long, finalPayee: String, rHash: sha256_hash)

class Node extends LoggingFSM[State, Data] {

  startWith(OPEN_WAIT_FOR_OPEN_NOANCHOR, Uninitialized)

  when(OPEN_WAIT_FOR_OPEN_NOANCHOR) {
    case Event(open_channel(delay, revocationHash, commitKey, finalKey, WILL_CREATE_ANCHOR, minDepth, commitmentFee), Uninitialized) =>
      val ourRevocationHashPreimage = sha256_hash(1, 1, 1, 1)
      val ourRevocationHash = sha256_hash(1, 1, 1, 1)
      // TODO : open_channel(delay, ourRevocationHash, commitKey, finalKey, WONT_CREATE_ANCHOR, minDepth, commitmentFee)
      goto(OPEN_WAIT_FOR_ANCHOR) using SimpleCommitmentTxData(ChannelParams(2), OurSimpleCommitmentTx(revocationHash, None), TheirSimpleCommitmentTx(ourRevocationHashPreimage, None))
  }

  when(OPEN_WAIT_FOR_ANCHOR) {
    case Event(open_anchor(txid, outputIndex, amount, commitSig), SimpleCommitmentTxData(params, ourCommitTx, theirCommitmentTx)) =>
      // TODO : build our commitment tx and check counterparty's sig
      // TODO : build counterparty's commitment tx and sign it
      val ourSig = signature(1, 1, 1, 1, 0, 0, 0, 0)
      // TODO : reply with open_commit_sig(sig)
      // TODO : register for confirmations of anchor tx on the bitcoin network
      goto(OPEN_WAITING) using SimpleCommitmentTxData(params, ourCommitTx.copy(theirSig = Some(commitSig)), theirCommitmentTx.copy(ourSig = Some(ourSig)))
  }

  when(OPEN_WAITING) {
    case Event(TxConfirmed(confirmations), SimpleCommitmentTxData(ChannelParams(minDepth), _, _)) if confirmations < minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx")
      stay
    case Event(TxConfirmed(confirmations), SimpleCommitmentTxData(ChannelParams(minDepth), _, _)) if confirmations >= minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached")
      // TODO : send open_complete(blockid)
      goto(OPEN_WAIT_FOR_COMPLETE)
  }

  when(OPEN_WAIT_FOR_COMPLETE) {
    case Event(open_complete(blockid_opt), _) =>
      goto(NORMAL)
  }

  when(NORMAL) {
    /*case Event(SendHtlcUpdate(amount), _) =>
      // TODO : we should reach the final payee, and get a rHash, and an expiry (which depends on the route)
      // TODO : send update_add_htlc(revocationHash, amount, rHash, expiry)
      goto(WAIT_FOR_HTLC_ACCEPT)*/

    case Event(update_add_htlc(revocationHash, amount, rHash, expiry), _) =>
      // TODO : we should probably check that we can reach the next node (which can be the final payee) using routing info that will be provided in the msg
      // TODO : reply with send update_accept(sig, revocationHash) ; note that this tx increases our balance so there is no risk in signing it
      // TODO : send update_add_htlc(revocationHash, amount, rHash, expiry - 1) *to the next node*
    goto(WAIT_FOR_UPDATE_SIG)

    case Event(update_complete_htlc(revocationHash, r), _) => // we get that from the *next node*
      // TODO : send update_accept(sig, revocationHash)
      goto(WAIT_FOR_UPDATE_SIG)
  }

  when(WAIT_FOR_UPDATE_SIG) {
    case Event(update_signature(sig, revocationPreimage), x: SimpleCommitmentTxData) =>
      // counterparty replied with the signature for its new commitment tx, and revocationPreimage
      // TODO : check that revocationPreimage indeed hashes to the revocationHash gave us previously
      // TODO : reply with update_complete(revocationPreimage) which revokes previous commit tx
      goto(NORMAL)

    case Event(update_signature(sig, revocationPreimage), x: HTLCCommitTxData) =>
      // TODO : reply with update_complete(revocationPreimage) which revokes previous commit tx
      goto(NORMAL)
  }

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
