package fr.acinq.eclair.blockchain

import java.util.concurrent.Executors

import akka.actor.{Actor, ActorLogging, Cancellable, Props, Terminated}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel.BITCOIN_PARENT_TX_CONFIRMED
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.{Globals, NodeParams, feerateKB2Kw}

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
class PeerWatcher(nodeParams: NodeParams, client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[BlockchainEvent])

  case class TriggerEvent(w: Watch, e: WatchEvent)

  def receive: Receive = watching(Set(), SortedMap(), None)

  def watching(watches: Set[Watch], block2tx: SortedMap[Long, Seq[Transaction]], nextTick: Option[Cancellable]): Receive = {

    case NewTransaction(tx) =>
      //log.debug(s"analyzing txid=${tx.txid} tx=${Transaction.write(tx)}")
      watches.collect {
        case w@WatchSpentBasic(_, txid, outputIndex, event) if tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex) =>
          self ! TriggerEvent(w, WatchEventSpentBasic(event))
        case w@WatchSpent(_, txid, outputIndex, event) if tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex) =>
          self ! TriggerEvent(w, WatchEventSpent(event, tx))
      }

    case NewBlock(block) =>
      // using a Try because in tests we generate fake blocks
      log.debug(s"received blockid=${Try(block.blockId).getOrElse(BinaryData(""))}")
      nextTick.map(_.cancel()) // this may fail or succeed, worse case scenario we will have two 'ticks in a row (no big deal)
      log.debug(s"scheduling a new task to check on tx confirmations")
      // we do this to avoid herd effects in testing when generating a lots of blocks in a row
      val task = context.system.scheduler.scheduleOnce(2 seconds, self, 'tick)
      context become watching(watches, block2tx, Some(task))

    case 'tick =>
      client.getBlockCount.map {
        case count =>
          log.debug(s"setting blockCount=$count")
          Globals.blockCount.set(count)
          context.system.eventStream.publish(CurrentBlockCount(count))
      }
      client.estimateSmartFee(nodeParams.smartfeeNBlocks).map {
        case feeratePerKB if feeratePerKB > 0 =>
          val feeratePerKw = feerateKB2Kw(feeratePerKB)
          log.debug(s"setting feeratePerKB=$feeratePerKB -> feeratePerKw=$feeratePerKw")
          Globals.feeratePerKw.set(feeratePerKw)
          context.system.eventStream.publish(CurrentFeerate(feeratePerKw))
        case _ => () // bitcoind cannot estimate feerate
      }
      // TODO: beware of the herd effect
      watches.collect {
        case w@WatchConfirmed(_, txId, minDepth, event) =>
          log.debug(s"checking confirmations of txid=$txId")
          client.getTxConfirmations(txId.toString).map {
            case Some(confirmations) if confirmations >= minDepth =>
              client.getTransactionShortId(txId.toString).map {
                case (height, index) => self ! TriggerEvent(w, WatchEventConfirmed(event, height, index))
              }
          }
      }
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

    case w: Watch if !watches.contains(w) => addWatch(w, watches, block2tx)

    case PublishAsap(tx) =>
      val blockCount = Globals.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = Scripts.csvTimeout(tx)
      if (csvTimeout > 0) {
        require(tx.txIn.size == 1, s"watcher only supports tx with 1 input, this tx has ${tx.txIn.size} inputs")
        val parentTxid = tx.txIn(0).outPoint.txid
        log.info(s"txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parenttxid=$parentTxid tx=${Transaction.write(tx)}")
        self ! WatchConfirmed(self, parentTxid, minDepth = 1, BITCOIN_PARENT_TX_CONFIRMED(tx))
      } else if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, tx +: block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]))
        context.become(watching(watches, block2tx1, None))
      } else publish(tx)

    case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), blockHeight, _) =>
      log.info(s"parent tx of txid=${tx.txid} has been confirmed")
      val blockCount = Globals.blockCount.get()
      val csvTimeout = Scripts.csvTimeout(tx)
      val absTimeout = blockHeight + csvTimeout
      if (absTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$absTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(absTimeout, tx +: block2tx.getOrElse(absTimeout, Seq.empty[Transaction]))
        context.become(watching(watches, block2tx1, None))
      } else publish(tx)

    case MakeFundingTx(ourCommitPub, theirCommitPub, amount, feeRatePerKw) =>
      client.makeFundingTx(ourCommitPub, theirCommitPub, amount, feeRatePerKw).pipeTo(sender)

    case ParallelGetRequest(ann) => client.getParallel(ann).pipeTo(sender)

    case Terminated(channel) =>
      // we remove watches associated to dead actor
      val deprecatedWatches = watches.filter(_.channel == channel)
      context.become(watching(watches -- deprecatedWatches, block2tx, None))

    case 'watches => sender ! watches

  }

  def addWatch(w: Watch, watches: Set[Watch], block2tx: SortedMap[Long, Seq[Transaction]]) = {
    w match {
      case WatchSpentBasic(_, txid, outputIndex, _) =>
        // not: we assume parent tx was published, we just need to make sure this particular output has not been spent
        client.isTransactionOuputSpendable(txid.toString(), outputIndex, true).collect {
          case false =>
            log.warning(s"output=$outputIndex of txid=$txid has already been spent")
            self ! TriggerEvent(w, WatchEventSpentBasic(w.event))
        }

      case w@WatchSpent(_, txid, outputIndex, _) =>
        // first let's see if the parent tx was published or not
        client.getTxConfirmations(txid.toString()).collect {
          case Some(_) =>
            // parent tx was published, we need to make sure this particular output has not been spent
            client.isTransactionOuputSpendable(txid.toString(), outputIndex, true).collect {
              case false =>
                log.warning(s"output=$outputIndex of txid=$txid has already been spent")
                client.getTxBlockHash(txid.toString()).collect {
                  case Some(blockhash) =>
                    log.warning(s"getting all transactions since blockhash=$blockhash")
                    client.getTxsSinceBlockHash(blockhash).map {
                      case txs =>
                        log.warning(s"found ${txs.size} txs since blockhash=$blockhash")
                        txs.foreach(tx => self ! NewTransaction(tx))
                    } onFailure {
                      case t: Throwable => log.error(t, "")
                    }
                }
                client.getMempool().map {
                  case txs =>
                    log.warning(s"found ${txs.size} txs in the mempool")
                    txs.foreach(tx => self ! NewTransaction(tx))
                }
            }
        }

      case w: WatchConfirmed => self ! 'tick

      case w => log.warning(s"ignoring $w (not implemented)")
    }
    log.debug(s"adding watch $w for $sender")
    context.watch(w.channel)
    context.become(watching(watches + w, block2tx, None))
  }

  // NOTE: we use a single thread to publish transactions so that it preserves order.
  // CHANGING THIS WILL RESULT IN CONCURRENCY ISSUES WHILE PUBLISHING PARENT AND CHILD TXS
  val singleThreadExecutionContext = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  def publish(tx: Transaction, isRetry: Boolean = false): Unit = {
    log.info(s"publishing tx (isRetry=$isRetry): txid=${tx.txid} tx=${Transaction.write(tx)}")
    client.publishTransaction(tx)(singleThreadExecutionContext).recover {
      case t: Throwable if t.getMessage.contains("-25") && !isRetry => // we retry only once
        import akka.pattern.after

        import scala.concurrent.duration._
        after(3 seconds, context.system.scheduler)(Future.successful({})).map(x => publish(tx, isRetry = true))
      case t: Throwable => log.error(s"cannot publish tx: reason=${t.getMessage} txid=${tx.txid} tx=${BinaryData(Transaction.write(tx))}")
    }
  }

}

object PeerWatcher {

  def props(nodeParams: NodeParams, client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) = Props(new PeerWatcher(nodeParams, client)(ec))

}