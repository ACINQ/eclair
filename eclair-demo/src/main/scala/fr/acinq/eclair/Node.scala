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

sealed trait Data
case object Uninitialized extends Data
final case class ChannelParams(revocationHash: sha256_hash, minDepth: Int) extends Data

//blockchain events
sealed trait BlockchainEvent
final case class TxConfirmed(confirmations: Int)

class Node extends LoggingFSM[State, Data] {

  startWith(OPEN_WAIT_FOR_OPEN_NOANCHOR, Uninitialized)

  when(OPEN_WAIT_FOR_OPEN_NOANCHOR) {
    case Event(open_channel(delay, revocationHash, commitKey, finalKey, WILL_CREATE_ANCHOR, minDepth, commitmentFee), Uninitialized) =>
      // Nothing to do here, we wait for the anchor
      goto(OPEN_WAIT_FOR_ANCHOR) using ChannelParams(revocationHash, minDepth.get) // minDepth has a default value in the .proto
  }

  when(OPEN_WAIT_FOR_ANCHOR) {
    case Event(open_anchor(txid, outputIndex, amount, commitSig), params@ChannelParams(revocationHash, _)) =>
      // TODO : sign commitment tx
      // TODO : reply with commit tx sig
      // TODO : register for confirmations of anchor tx on the bitcoin network
      goto(OPEN_WAITING)
  }

  when(OPEN_WAITING) {
    case Event(TxConfirmed(confirmations), params@ChannelParams(_, minDepth)) if confirmations < minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx")
      stay
    case Event(TxConfirmed(confirmations), params@ChannelParams(_, minDepth)) if confirmations >= minDepth =>
      log.info(s"got $confirmations confirmation(s) for anchor tx, minDepth reached")
      //TODO : send open complete message
      goto(OPEN_WAIT_FOR_COMPLETE)
  }

  when(OPEN_WAIT_FOR_COMPLETE) {
    case Event(open_complete(blockid_opt), _) =>
      goto(NORMAL)
  }

  when(NORMAL) {
    case Event(_, _) =>
      stay
  }

}
