package fr.acinq.eclair.blockchain


import akka.actor.{Actor, ActorLogging, Cancellable, Terminated}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.BITCOIN_ANCHOR_SPENT
import org.bouncycastle.util.encoders.Hex

import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._

/**
  * Simple blockchain watcher that periodically polls bitcoin-core using rpc api
  * /!\ Obviously not scalable /!\
  * Created by PM on 28/08/2015.
  */
class PollingWatcher(client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  context.become(watching(Map()))

  override def receive: Receive = ???

  def watching(watches: Map[Watch, Cancellable]): Receive = {

    case w: WatchLost => log.warning(s"ignoring $w (not implemented)")

    case w: Watch if !watches.contains(w) =>
      log.info(s"adding watch $w for $sender")
      context.watch(w.channel)
      val cancellable = context.system.scheduler.schedule(100 milliseconds, 10 seconds)(w match {
        case w@WatchConfirmed(channel, txId, minDepth, event) =>
          client.getTxConfirmations(txId.toString).map(_ match {
            case Some(confirmations) if confirmations >= minDepth =>
              channel ! event
              self !('remove, w)
            case _ => {}
          })
        case w@WatchSpent(channel, txId, outputIndex, minDepth, event) =>
          for {
            conf <- client.getTxConfirmations(txId.toString)
            unspent <- client.isUnspent(txId.toString, outputIndex)
          } yield {
            if (conf.isDefined && !unspent) {
              // NOTE : isSpent=!isUnspent only works if the parent transaction actually exists (which we assume to be true)
              client.findSpendingTransaction(txId.toString(), outputIndex).map(tx => channel !(BITCOIN_ANCHOR_SPENT, tx))
              self !('remove, w)
            } else {}
          }
      })
      context.become(watching(watches + (w -> cancellable)))

    case ('remove, w: Watch) if watches.contains(w) =>
      watches(w).cancel()
      context.become(watching(watches - w))

    case Publish(tx) =>
      log.info(s"publishing tx ${tx.txid} $tx")
      client.publishTransaction(tx).onFailure {
        case t: Throwable => log.error(t, s"cannot publish tx ${Hex.toHexString(Transaction.write(tx, Protocol.PROTOCOL_VERSION))}")
      }

    case MakeAnchor(ourCommitPub, theirCommitPub, amount) =>
      client.makeAnchorTx(ourCommitPub, theirCommitPub, amount).pipeTo(sender)

    case Terminated(subject) =>
      val deadWatches = watches.keys.filter(_.channel == subject)
      deadWatches.map(w => watches(w).cancel())
      context.become(watching(watches -- deadWatches))
  }
}
