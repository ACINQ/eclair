package fr.acinq.eclair.blockchain


import akka.actor.{Actor, ActorLogging, Cancellable, Terminated}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.{BITCOIN_ANCHOR_SPENT, Scripts}
import org.bouncycastle.util.encoders.Hex

import scala.collection.SortedMap
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration._

/**
  * Simple blockchain watcher that periodically polls bitcoin-core using rpc api
  * /!\ Obviously not scalable /!\
  * Created by PM on 28/08/2015.
  */
class PollingWatcher(client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  override def receive: Receive = ???

  context.become(watching(Map(), SortedMap(), 0))

  context.system.scheduler.schedule(150 milliseconds, 10 seconds)(client.getBlockCount.map(count => ('currentBlockCount, count)).pipeTo(self))

  def publish(tx: Transaction): Unit = {
    log.info(s"publishing tx ${tx.txid} $tx")
    client.publishTransaction(tx).onFailure {
      case t: Throwable => log.error(t, s"cannot publish tx ${Hex.toHexString(Transaction.write(tx, Protocol.PROTOCOL_VERSION))}")
    }
  }

  def watching(watches: Map[Watch, Cancellable], block2tx: SortedMap[Long, Seq[Transaction]], currentBlockCount: Long): Receive = {

    case w: WatchLost => log.warning(s"ignoring $w (not implemented)")

    case w: Watch if !watches.contains(w) =>
      log.info(s"adding watch $w for $sender")
      context.watch(w.channel)
      val cancellable = context.system.scheduler.schedule(100 milliseconds, 10 seconds)(w match {
        case w@WatchConfirmed(channel, txId, minDepth, event) =>
          client.getTxConfirmations(txId.toString).map(_ match {
            case Some(confirmations) if confirmations >= minDepth =>
              channel ! event
              self ! ('remove, w)
            case _ => {}
          })
        case w@WatchSpent(channel, txId, outputIndex, minDepth, event) =>
          for {
            conf <- client.getTxConfirmations(txId.toString)
            unspent <- client.isUnspent(txId.toString, outputIndex)
          } yield {
            if (conf.isDefined && !unspent) {
              // NOTE : isSpent=!isUnspent only works if the parent transaction actually exists (which we assume to be true)
              client.findSpendingTransaction(txId.toString(), outputIndex).map(tx => channel ! (BITCOIN_ANCHOR_SPENT, tx))
              self ! ('remove, w)
            } else {}
          }
      })
      context.become(watching(watches + (w -> cancellable), block2tx, currentBlockCount))

    case ('remove, w: Watch) if watches.contains(w) =>
      watches(w).cancel()
      context.become(watching(watches - w, block2tx, currentBlockCount))

    case ('currentBlockCount, count: Long) => {
      val topublish = block2tx.filterKeys(_ <= count)
      topublish.values.flatten.map(publish)
      context.become(watching(watches, block2tx -- topublish.keys, count))
    }

    case Publish(tx) => publish(tx)

    case PublishAsap(tx) =>
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = currentBlockCount + Scripts.csvTimeout(tx)
      val timeout = Math.max(cltvTimeout, csvTimeout)
      val block2tx1 = block2tx.updated(timeout, tx +: block2tx.getOrElse(timeout, Seq.empty[Transaction]))
      context.become(watching(watches, block2tx1, currentBlockCount))

    case MakeAnchor(ourCommitPub, theirCommitPub, amount) =>
      client.makeAnchorTx(ourCommitPub, theirCommitPub, amount).pipeTo(sender)

    case Terminated(subject) =>
      val deadWatches = watches.keys.filter(_.channel == subject)
      deadWatches.map(w => watches(w).cancel())
      context.become(watching(watches -- deadWatches, block2tx, currentBlockCount))
  }
}

object PollingWatcher {

}