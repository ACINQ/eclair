package fr.acinq.eclair.blockchain

import akka.actor.{Actor, ActorLogging, Props, Terminated}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair.Globals
import fr.acinq.eclair.blockchain.peer.{BlockchainEvent, CurrentBlockCount, NewBlock, NewTransaction}
import fr.acinq.eclair.channel.BITCOIN_PARENT_TX_CONFIRMED
import fr.acinq.eclair.transactions.Scripts

import scala.collection.SortedMap
import scala.concurrent.ExecutionContext

/**
  * A blockchain watcher that:
  * - connects directly to the bitcoin network and listens to new txs and blocks
  * - also uses bitcoin-core rpc api, most notably for tx confirmation count and blockcount (because reorgs)
  * Created by PM on 21/02/2016.
  */
class PeerWatcher(client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[BlockchainEvent])

  val LAST_TX_QUEUE_SIZE = 1000

  def receive: Receive = watching(Set(), SortedMap(), Nil)

  def watching(watches: Set[Watch], block2tx: SortedMap[Long, Seq[Transaction]], lastTxes: Seq[Transaction]): Receive = {

    case NewTransaction(tx) =>
      val triggeredWatches = watches.collect {
        case w@WatchSpent(channel, txid, outputIndex, event)
          if tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex) =>
          channel ! WatchEventSpent(event, tx)
          w
      }
      context.become(watching(watches -- triggeredWatches, block2tx, (lastTxes :+ tx).takeRight(LAST_TX_QUEUE_SIZE)))

    case NewBlock(block) =>
      client.getBlockCount.map {
        case count =>
          Globals.blockCount.set(count)
          context.system.eventStream.publish(CurrentBlockCount(count))
      }
      // TODO: beware of the herd effect
      watches.collect {
        case w@WatchConfirmed(channel, txId, minDepth, event) =>
          client.getTxConfirmations(txId.toString).map {
            case Some(confirmations) if confirmations >= minDepth =>
              client.getTransactionShortId(txId.toString).map {
                // TODO: this is a workaround to not have WatchConfirmed triggered multiple times in testing
                // the reason is that we cannot do a become(watches - w, ...) because it happens in the future callback
                case (height, index) => self ! ('trigger, w, WatchEventConfirmed(w.event, height, index))
              }

          }
      }

    case ('trigger, w: WatchConfirmed, e: WatchEvent) if watches.contains(w) =>
      log.info(s"triggering $w")
      w.channel ! e
      context.become(watching(watches - w, block2tx, lastTxes))

    case ('trigger, w: WatchConfirmed, e: WatchEvent) if !watches.contains(w) => {}

    case CurrentBlockCount(count) => {
      val toPublish = block2tx.filterKeys(_ <= count)
      toPublish.values.flatten.map(publish)
      context.become(watching(watches, block2tx -- toPublish.keys, lastTxes))
    }

    case w: WatchLost => log.warning(s"ignoring $w (not implemented)")

    case w@WatchSpent(channel, txid, outputIndex, event) => {
      // we need to check if the tx was already spent
      // TODO: dirty
      lastTxes.find(tx => tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex))
      match {
        case Some(tx) => channel ! WatchEventSpent(event, tx)
        case None => addWatch(w, watches, block2tx, lastTxes)
      }
    }

    case w@WatchConfirmed(channel, txId, minDepth, event) => {
      client.getTxConfirmations(txId.toString).map {
        case Some(confirmations) if confirmations >= minDepth =>
          client.getTransactionShortId(txId.toString).map {
            // TODO: this is a workaround to not have WatchConfirmed triggered multiple times in testing
            // the reason is that we cannot do a become(watches - w, ...) because it happens in the future callback
            case (height, index) => self ! ('trigger, w, WatchEventConfirmed(w.event, height, index))
          }
        case _ => ()
      }
      addWatch(w, watches, block2tx, lastTxes)
    }
      
    case w: Watch => addWatch(w, watches, block2tx, lastTxes)

    case PublishAsap(tx) =>
      val blockCount = Globals.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = Scripts.csvTimeout(tx)
      if (csvTimeout > 0) {
        require(tx.txIn.size == 1, s"watcher only supports tx with 1 input, this tx has ${tx.txIn.size} inputs")
        val parentTxid = tx.txIn(0).outPoint.txid
        log.info(s"this tx has a relative timeout of ${csvTimeout} blocks, watching parent tx: parenttxid=$parentTxid txid=${tx.txid} tx=${Transaction.write(tx)}")
        self ! WatchConfirmed(self, parentTxid, minDepth = 1, BITCOIN_PARENT_TX_CONFIRMED(tx))
      } else if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of tx until block=$cltvTimeout (curblock=$blockCount): txid=${tx.txid} tx=${Transaction.write(tx)}")
        val block2tx1 = block2tx.updated(cltvTimeout, tx +: block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]))
        context.become(watching(watches, block2tx1, lastTxes))
      } else publish(tx)

    case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), blockHeight, _) =>
      val blockCount = Globals.blockCount.get()
      val csvTimeout = Scripts.csvTimeout(tx)
      val absTimeout = blockHeight + csvTimeout
      log.info(s"parent tx has been confirmed, now need to wait for relative timeout of ${csvTimeout} blocks")
      log.info(s"delaying publication of tx until block=$absTimeout (curblock=$blockCount): txid=${tx.txid} tx=${Transaction.write(tx)}")
      val block2tx1 = block2tx.updated(absTimeout, tx +: block2tx.getOrElse(absTimeout, Seq.empty[Transaction]))
      context.become(watching(watches, block2tx1, lastTxes))

    case MakeFundingTx(ourCommitPub, theirCommitPub, amount) =>
      client.makeFundingTx(ourCommitPub, theirCommitPub, amount).map(r => MakeFundingTxResponse(r._1, r._2)).pipeTo(sender)

    case GetTx(blockHeight, txIndex, outputIndex, ctx) =>
      (for {
        tx <- client.getTransaction(blockHeight, txIndex)
        spendable <- client.isTransactionOuputSpendable(tx.txid.toString(), outputIndex, true)
      } yield GetTxResponse(tx, spendable, ctx)).pipeTo(sender)

    case Terminated(channel) =>
      // we remove watches associated to dead actor
      val deprecatedWatches = watches.filter(_.channel == channel)
      context.become(watching(watches -- deprecatedWatches, block2tx, lastTxes))

  }

  def addWatch(w: Watch, watches: Set[Watch], block2tx: SortedMap[Long, Seq[Transaction]], lastTx: Seq[Transaction]) = {
    if (!watches.contains(w)) {
      log.info(s"adding watch $w for $sender")
      context.watch(w.channel)
      context.become(watching(watches + w, block2tx, lastTx))
    }
  }

  def publish(tx: Transaction) = {
    log.info(s"publishing tx: txid=${tx.txid} tx=${Transaction.write(tx)}")
    client.publishTransaction(tx).onFailure {
      case t: Throwable => log.error(s"cannot publish tx: reason=${t.getMessage} txid=${tx.txid} tx=${BinaryData(Transaction.write(tx))}")
    }
  }
}

object PeerWatcher {

  def props(client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) = Props(classOf[PeerWatcher], client, ec)

}