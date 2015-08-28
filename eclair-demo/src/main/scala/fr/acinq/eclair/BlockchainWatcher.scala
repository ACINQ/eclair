package fr.acinq.eclair

import akka.actor.{ActorRef, Actor, ActorLogging}
import fr.acinq.bitcoin.{Transaction, BinaryData}
import fr.acinq.lightning._

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

  context.become(watching(Map()))

  override def receive: Receive = ???

  def watching(m: Map[BinaryData, ActorRef]): Receive = {
    case Watch(txId) =>
      log.info(s"watching tx $txId for $sender")
      // instant confirmation for testing
      (0 until 3) foreach(i => self ! TxConfirmed(txId, "5deedc4c7f4c8e3250a486f340e57a565cda908eef7b7df2c1cd61b8ad6b42e6", i))
      context.become(watching(m + (txId -> sender)))

    case Unwatch(txId) =>
      context.become(watching(m - txId))

    case TxConfirmed(txId, blockId, confirmations) if m.contains(txId) =>
      val channel = m(txId)
      channel ! BITCOIN_TX_CONFIRMED(blockId, confirmations)


    case Publish(tx) =>
      log.info(s"publishing tx $tx")
  }
}
