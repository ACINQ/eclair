package fr.acinq.eclair

import akka.actor.{ActorRef, Actor, ActorLogging}
import fr.acinq.bitcoin.{Transaction, BinaryData}
import scala.concurrent.duration._

import scala.collection.mutable

// @formatter:off

final case class WatchConfirmed(channel: ActorRef, txId: BinaryData, minDepth: Int, event: AnyRef)
final case class WatchSpent(channel: ActorRef, txId: BinaryData, minDepth: Int, event: AnyRef)
final case class TxConfirmed(txId: BinaryData, blockId: BinaryData, confirmations: Int)
final case class TxSpent(txId: BinaryData, blockId: BinaryData, confirmations: Int)
final case class Publish(tx: Transaction)

// @formatter:on

/**
 * Created by PM on 28/08/2015.
 */
class BlockchainWatcher extends Actor with ActorLogging {

  context.become(watching(Set()))

  override def receive: Receive = ???

  def watching(watches: Set[WatchConfirmed]): Receive = {
    case w@WatchConfirmed(channel, txId, minDepth, _) =>
      log.info(s"watching tx $txId for $sender")
      // TODO : for testing
      import scala.concurrent.ExecutionContext.Implicits.global
      (0 until 3) foreach(i => context.system.scheduler.scheduleOnce(i * 100 milliseconds, self, TxConfirmed(txId, "5deedc4c7f4c8e3250a486f340e57a565cda908eef7b7df2c1cd61b8ad6b42e6", i)))
      context.become(watching(watches + w))

    case TxConfirmed(txId, blockId, confirmations) =>
      log.info(s"got $confirmations confirmation(s) for tx $txId")
      watches.filter(_.txId == txId).foreach(w => w match {
         case WatchConfirmed(channel, _, minDepth, event) if confirmations >= minDepth =>
           channel ! event
           context.become(watching(watches - w))
         case _ => {}
       })

    case Publish(tx) =>
      log.info(s"publishing tx $tx")
  }
}
