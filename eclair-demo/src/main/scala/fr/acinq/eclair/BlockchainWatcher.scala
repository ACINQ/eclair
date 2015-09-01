package fr.acinq.eclair

import akka.actor.{ActorRef, Actor, ActorLogging}
import fr.acinq.bitcoin.{Transaction, BinaryData}
import fr.acinq.lightning._

import scala.collection.mutable

// @formatter:off

final case class Watch(txId: BinaryData)
final case class Unwatch(txId: BinaryData)
final case class TxConfirmed(txId: BinaryData, blockId: BinaryData, confirmations: Int)
final case class Publish(tx: Transaction)

// @formatter:on

/**
 * Created by PM on 28/08/2015.
 */
class BlockchainWatcher extends Actor with ActorLogging {

  val m = new mutable.HashMap[BinaryData, mutable.Set[ActorRef]] with mutable.MultiMap[BinaryData, ActorRef]
  context.become(watching(m))

  override def receive: Receive = ???

  def watching(m: mutable.MultiMap[BinaryData, ActorRef]): Receive = {
    case Watch(txId) =>
      log.info(s"watching tx $txId for $sender")
      // instant confirmation for testing
      (0 until 3) foreach(i => self ! TxConfirmed(txId, "5deedc4c7f4c8e3250a486f340e57a565cda908eef7b7df2c1cd61b8ad6b42e6", i))
      context.become(watching(m.addBinding(txId, sender)))

    case Unwatch(txId) =>
      context.become(watching(m.removeBinding(txId, sender)))

    case TxConfirmed(txId, blockId, confirmations) if m.contains(txId) =>
       m(txId).foreach(_ ! BITCOIN_TX_CONFIRMED(blockId, confirmations))

    case Publish(tx) =>
      log.info(s"publishing tx $tx")
  }
}
