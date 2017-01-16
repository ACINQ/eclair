package fr.acinq.eclair.blockchain

import akka.actor.{Actor, ActorLogging, Props, Terminated}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.peer.{BlockchainEvent, CurrentBlockCount, NewBlock, NewTransaction}
import fr.acinq.eclair.channel.BITCOIN_FUNDING_SPENT
import fr.acinq.eclair.transactions.Scripts

import scala.collection.SortedMap
import scala.concurrent.ExecutionContext

/**
  * A blockchain watcher that:
  * - connects directly to the bitcoin network and listens to new txs and blocks
  * - also uses bitcoin-core rpc api, most notably for tx confirmation count and blockcount (because reorgs)
  * Created by PM on 21/02/2016.
  */
class PeerWatcher(client: ExtendedBitcoinClient, blockCount: Long)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[BlockchainEvent])

  def receive: Receive = watching(Set(), SortedMap(), blockCount)

  def watching(watches: Set[Watch], block2tx: SortedMap[Long, Seq[Transaction]], currentBlockCount: Long): Receive = {

    case NewTransaction(tx) =>
      val triggeredWatches = watches.collect {
        case w@WatchSpent(channel, txid, outputIndex, minDepth, event)
          if tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex) =>
          channel ! (BITCOIN_FUNDING_SPENT, tx)
          w
      }
      context.become(watching(watches -- triggeredWatches, block2tx, currentBlockCount))

    case NewBlock(block) =>
      client.getBlockCount.map(count => context.system.eventStream.publish(CurrentBlockCount(count)))
      // TODO: beware of the herd effect
      watches.collect {
        case w@WatchConfirmed(channel, txId, minDepth, event) =>
          client.getTxConfirmations(txId.toString).collect {
            // TODO: this is a workaround to not have WatchConfirmed triggered multiple times in testing
            // the reason is that we cannot fo a become(watches - w, ...) because it happens in the future callback
            case Some(confirmations) if confirmations >= minDepth => self ! ('trigger, w)
          }
      }

    case ('trigger, w: WatchConfirmed) if watches.contains(w) =>
      log.info(s"triggering $w")
      w.channel ! w.event
      context.become(watching(watches - w, block2tx, currentBlockCount))

    case ('trigger, w: WatchConfirmed) if !watches.contains(w) => {}

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

    case MakeFundingTx(ourCommitPub, theirCommitPub, amount) =>
      client.makeAnchorTx(ourCommitPub, theirCommitPub, amount).pipeTo(sender)

    case Terminated(channel) =>
      // we remove watches associated to dead actor
      val deprecatedWatches = watches.filter(_.channel == channel)
      context.become(watching(watches -- deprecatedWatches, block2tx, currentBlockCount))

  }
}

object PeerWatcher {

  def props(client: ExtendedBitcoinClient, initialBlockCount: Long)(implicit ec: ExecutionContext = ExecutionContext.global) = Props(classOf[PeerWatcher], client, initialBlockCount, ec)

}