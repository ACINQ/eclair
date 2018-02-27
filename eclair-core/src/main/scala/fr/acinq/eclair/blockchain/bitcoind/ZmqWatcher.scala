package fr.acinq.eclair.blockchain.bitcoind

import java.util.concurrent.Executors

import akka.actor.{Actor, ActorLogging, Cancellable, Props, Terminated}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair.Globals
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.channel.BITCOIN_PARENT_TX_CONFIRMED
import fr.acinq.eclair.transactions.Scripts

import scala.collection.SortedMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * A blockchain watcher that:
  * - receives bitcoin events (new blocks and new txes) directly from the bitcoin network
  * - also uses bitcoin-core rpc api, most notably for tx confirmation count and blockcount (because reorgs)
  * Created by PM on 21/02/2016.
  */
class ZmqWatcher(client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  import ZmqWatcher.TickNewBlock

  context.system.eventStream.subscribe(self, classOf[BlockchainEvent])

  // this is to initialize block count
  self ! TickNewBlock

  case class TriggerEvent(w: Watch, e: WatchEvent)

  def receive: Receive = watching(Set(), SortedMap(), None)

  def watching(watches: Set[Watch], block2tx: SortedMap[Long, Seq[Transaction]], nextTick: Option[Cancellable]): Receive = {

    case NewTransaction(tx) =>
      //log.debug(s"analyzing txid=${tx.txid} tx=$tx")
      watches.collect {
        case w@WatchSpentBasic(_, txid, outputIndex, _, event) if tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex) =>
          self ! TriggerEvent(w, WatchEventSpentBasic(event))
        case w@WatchSpent(_, txid, outputIndex, _, event) if tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex) =>
          self ! TriggerEvent(w, WatchEventSpent(event, tx))
      }

    case NewBlock(block) =>
      // using a Try because in tests we generate fake blocks
      log.debug(s"received blockid=${Try(block.blockId).getOrElse(BinaryData(""))}")
      nextTick.map(_.cancel()) // this may fail or succeed, worse case scenario we will have two ticks in a row (no big deal)
      log.debug(s"scheduling a new task to check on tx confirmations")
      // we do this to avoid herd effects in testing when generating a lots of blocks in a row
      val task = context.system.scheduler.scheduleOnce(2 seconds, self, TickNewBlock)
      context become watching(watches, block2tx, Some(task))

    case TickNewBlock =>
      client.getBlockCount.map {
        case count =>
          log.debug(s"setting blockCount=$count")
          Globals.blockCount.set(count)
          context.system.eventStream.publish(CurrentBlockCount(count))
      }
      // TODO: beware of the herd effect
      watches.collect { case w: WatchConfirmed => checkConfirmed(w) }
      context become (watching(watches, block2tx, None))

    case TriggerEvent(w, e) if watches.contains(w) =>
      log.info(s"triggering $w")
      w.channel ! e
      // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx
      // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
      if (!w.isInstanceOf[WatchSpent]) context.become(watching(watches - w, block2tx, None))

    case CurrentBlockCount(count) => {
      val toPublish = block2tx.filterKeys(_ <= count)
      toPublish.values.flatten.map(tx => publish(tx))
      context.become(watching(watches, block2tx -- toPublish.keys, None))
    }

    case w: Watch if !watches.contains(w) =>
      w match {
        case WatchSpentBasic(_, txid, outputIndex, _, _) =>
          // not: we assume parent tx was published, we just need to make sure this particular output has not been spent
          client.isTransactionOutputSpendable(txid.toString(), outputIndex, true).collect {
            case false =>
              log.info(s"output=$outputIndex of txid=$txid has already been spent")
              self ! TriggerEvent(w, WatchEventSpentBasic(w.event))
          }

        case WatchSpent(_, txid, outputIndex, _, _) =>
          // first let's see if the parent tx was published or not
          client.getTxConfirmations(txid.toString()).collect {
            case Some(_) =>
              // parent tx was published, we need to make sure this particular output has not been spent
              client.isTransactionOutputSpendable(txid.toString(), outputIndex, true).collect {
                case false =>
                  log.info(s"$txid:$outputIndex has already been spent, looking for the spending tx in the mempool")
                  client.getMempool().map { mempoolTxs =>
                    mempoolTxs.filter(tx => tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex)) match {
                      case Nil =>
                        log.warning(s"$txid:$outputIndex has already been spent, spending tx not in the mempool, looking in the blockchain...")
                        client.lookForSpendingTx(None, txid.toString(), outputIndex).map { tx =>
                          log.warning(s"found the spending tx of $txid:$outputIndex in the blockchain: txid=${tx.txid}")
                          self ! NewTransaction(tx)
                        }
                      case txs =>
                        log.info(s"found ${txs.size} txs spending $txid:$outputIndex in the mempool: txids=${txs.map(_.txid).mkString(",")}")
                        txs.foreach(tx => self ! NewTransaction(tx))
                    }
                  }
              }
          }

        case w: WatchConfirmed => checkConfirmed(w) // maybe the tx is already tx, in that case the watch will be triggered and removed immediately

        case _: WatchLost => () // TODO: not implemented

        case w => log.warning(s"ignoring $w")
      }

      log.debug(s"adding watch $w for $sender")
      context.watch(w.channel)
      context.become(watching(watches + w, block2tx, nextTick))

    case PublishAsap(tx) =>
      val blockCount = Globals.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = Scripts.csvTimeout(tx)
      if (csvTimeout > 0) {
        require(tx.txIn.size == 1, s"watcher only supports tx with 1 input, this tx has ${tx.txIn.size} inputs")
        val parentTxid = tx.txIn(0).outPoint.txid
        log.info(s"txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parenttxid=$parentTxid tx=$tx")
        val parentPublicKey = fr.acinq.bitcoin.Script.write(fr.acinq.bitcoin.Script.pay2wsh(tx.txIn.head.witness.stack.last))
        self ! WatchConfirmed(self, parentTxid, parentPublicKey, minDepth = 1, BITCOIN_PARENT_TX_CONFIRMED(tx))
      } else if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]) :+ tx)
        context.become(watching(watches, block2tx1, None))
      } else publish(tx)

    case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), blockHeight, _) =>
      log.info(s"parent tx of txid=${tx.txid} has been confirmed")
      val blockCount = Globals.blockCount.get()
      val csvTimeout = Scripts.csvTimeout(tx)
      val absTimeout = blockHeight + csvTimeout
      if (absTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$absTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(absTimeout, block2tx.getOrElse(absTimeout, Seq.empty[Transaction]) :+ tx)
        context.become(watching(watches, block2tx1, None))
      } else publish(tx)

    case ValidateRequest(ann) => client.validate(ann).pipeTo(sender)

    case Terminated(channel) =>
      // we remove watches associated to dead actor
      val deprecatedWatches = watches.filter(_.channel == channel)
      context.become(watching(watches -- deprecatedWatches, block2tx, None))

    case 'watches => sender ! watches

  }

  // NOTE: we use a single thread to publish transactions so that it preserves order.
  // CHANGING THIS WILL RESULT IN CONCURRENCY ISSUES WHILE PUBLISHING PARENT AND CHILD TXS
  val singleThreadExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  def publish(tx: Transaction, isRetry: Boolean = false): Unit = {
    log.info(s"publishing tx (isRetry=$isRetry): txid=${tx.txid} tx=$tx")
    client.publishTransaction(tx)(singleThreadExecutionContext).recover {
      case t: Throwable if t.getMessage.contains("(code: -25)") && !isRetry => // we retry only once
        import akka.pattern.after

        import scala.concurrent.duration._
        after(3 seconds, context.system.scheduler)(Future.successful({})).map(x => publish(tx, isRetry = true))
      case t: Throwable if t.getMessage.contains("(code: -27)") => () // "transaction already in block chain (code: -27)" ignore error
      case t: Throwable => log.error(s"cannot publish tx: reason=${t.getMessage} txid=${tx.txid} tx=$tx")
    }
  }

  def checkConfirmed(w: WatchConfirmed) = {
    log.debug(s"checking confirmations of txid=${w.txId}")
    client.getTxConfirmations(w.txId.toString).map {
      case Some(confirmations) if confirmations >= w.minDepth =>
        client.getTransactionShortId(w.txId.toString).map {
          case (height, index) => self ! TriggerEvent(w, WatchEventConfirmed(w.event, height, index))
        }
    }
  }

}

object ZmqWatcher {

  def props(client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) = Props(new ZmqWatcher(client)(ec))

  case object TickNewBlock

}