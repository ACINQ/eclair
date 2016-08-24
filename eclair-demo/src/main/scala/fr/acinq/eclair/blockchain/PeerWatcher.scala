package fr.acinq.eclair.blockchain

import akka.actor.{Actor, ActorLogging, Terminated}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.peer.{BlockchainEvent, CurrentBlockCount, NewBlock, NewTransaction}
import fr.acinq.eclair.channel.{BITCOIN_ANCHOR_SPENT, Scripts}

import scala.collection.SortedMap
import scala.concurrent.ExecutionContext

/**
  * A blockchain watcher that:
  * - connects directly to the bitcoin network and listens to new tx
  * - periodically polls bitcoin-core using rpc api
  * Created by PM on 21/02/2016.
  */
class PeerWatcher(client: ExtendedBitcoinClient, blockCount: Long)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[BlockchainEvent])

  def receive: Receive = watching(Set(), SortedMap(), blockCount)

  def watching(watches: Set[Watch], block2tx: SortedMap[Long, Seq[Transaction]], currentBlockCount: Long): Receive = {

    case NewTransaction(tx) =>
      watches.collect {
        case w@WatchSpent(channel, txid, outputIndex, minDepth, event)
          if tx.txIn.exists(i => i.outPoint.hash == BinaryData(txid.reverse) && i.outPoint.index == outputIndex) =>
          channel ! (BITCOIN_ANCHOR_SPENT, tx)
          self ! ('remove, w)
        case _ => {}
      }

    case NewBlock(block) =>
      client.getBlockCount.map(count => context.system.eventStream.publish(CurrentBlockCount(count)))
      // TODO : beware of the herd effect
      watches.collect {
        case w@WatchConfirmed(channel, txId, minDepth, event) =>
          client.getTxConfirmations(txId.toString).map(_ match {
            case Some(confirmations) if confirmations >= minDepth =>
              log.info(s"triggering $w")
              channel ! event
              self ! ('remove, w)
            case _ => {} // not enough confirmations
          })
      }

    case CurrentBlockCount(count) => {
      val toPublish = block2tx.filterKeys(_ <= count)
      toPublish.values.flatten.map(tx => self ! Publish(tx))
      context.become(watching(watches, block2tx -- toPublish.keys, count))
    }

    case w: WatchLost => log.warning(s"ignoring $w (not implemented)")

    case w: Watch if !watches.contains(w) =>
      log.info(s"adding watch $w for $sender")
      context.watch(w.channel)
      context.become(watching(watches + w, block2tx, currentBlockCount))

    case ('remove, w: Watch) if watches.contains(w) =>
      context.become(watching(watches - w, block2tx, currentBlockCount))

    case Publish(tx) =>
      log.info(s"publishing tx $tx")
      client.publishTransaction(tx).onFailure {
        case t: Throwable => log.error(t, s"cannot publish tx ${BinaryData(Transaction.write(tx))}")
      }

    case PublishAsap(tx) =>
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = currentBlockCount + Scripts.csvTimeout(tx)
      val timeout = Math.max(cltvTimeout, csvTimeout)
      val block2tx1 = block2tx.updated(timeout, tx +: block2tx.getOrElse(timeout, Seq.empty[Transaction]))
      context.become(watching(watches, block2tx1, currentBlockCount))

    case MakeAnchor(ourCommitPub, theirCommitPub, amount) =>
      client.makeAnchorTx(ourCommitPub, theirCommitPub, amount).pipeTo(sender)

    case Terminated(channel) =>
      // we remove watches associated to dead actor
      watches.filter(_.channel == channel).foreach(w => self ! ('remove, w))

  }
}