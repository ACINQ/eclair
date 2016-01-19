package fr.acinq.eclair.blockchain

import akka.actor.{Actor, ActorLogging}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import fr.acinq.bitcoin._

/**
  * Simple blockchain watcher that periodically polls bitcoin-core using rpc api
  * Obviously not scalable
  * Created by PM on 28/08/2015.
  */
class BlockchainWatcher()(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  context.become(watching(Set()))

  // this timer is used to trigger a polling
  context.system.scheduler.schedule(10 seconds, 2 minutes, self, 'tick)

  override def receive: Receive = ???

  def watching(watches: Set[Watch]): Receive = {
    case w: Watch =>
      log.info(s"adding watch $w for $sender")
      context.become(watching(watches + w))
      /* TODO : for testing
      import scala.concurrent.ExecutionContext.Implicits.global
      (0 until 3) foreach(i => context.system.scheduler.scheduleOnce(i * 100 milliseconds, self, TxConfirmed(txId, "5deedc4c7f4c8e3250a486f340e57a565cda908eef7b7df2c1cd61b8ad6b42e6", i)))
      */

    case TxConfirmed(txId, blockId, confirmations) =>
      watches.foreach(_ match {
        case w@WatchConfirmed(channel, `txId`, minDepth, event) if confirmations >= minDepth =>
          channel ! event
          context.become(watching(watches - w))
      })

    case Publish(tx) =>
      log.info(s"publishing tx $tx")
  }
}
