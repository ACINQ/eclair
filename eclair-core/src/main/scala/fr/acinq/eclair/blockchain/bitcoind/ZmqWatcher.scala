/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.bitcoind

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

import akka.actor.{Actor, ActorLogging, Cancellable, Props, Terminated}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair
import fr.acinq.eclair.KamonExt
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.channel.BITCOIN_PARENT_TX_CONFIRMED
import fr.acinq.eclair.transactions.Scripts
import scodec.bits.ByteVector

import scala.collection.{Set, SortedMap}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/**
  * A blockchain watcher that:
  * - receives bitcoin events (new blocks and new txes) directly from the bitcoin network
  * - also uses bitcoin-core rpc api, most notably for tx confirmation count and blockcount (because reorgs)
  * Created by PM on 21/02/2016.
  */
class ZmqWatcher(blockCount: AtomicLong, client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  import ZmqWatcher._

  context.system.eventStream.subscribe(self, classOf[BlockchainEvent])

  self ! TickNewBlock // this is to initialize block count
  // on startup we need to wait until all the channels have been restored, we'll receive
  // watches from them and we want to scan only once for all, 'TickInitialRescan' triggers the rescan
  context.system.scheduler.scheduleOnce(15 seconds)(self ! TickInitialRescan)

  /**
    * init -----------------> scanning ---- TickInitialRescan ---> watching
    *
    * The watcher is initialized into a scanning state during which it will queue the events,
    * when a 'TickInitialRescan' is received the watcher will instruct bitcoind to rescan. Only after
    * the initial chain rescan is completed the watcher is ready to process watch events (both spent and confirm).
    */
  def receive: Receive = scanPending(Set(), List.empty[Any])

  def watching(watches: Set[Watch], watchedUtxos: Map[OutPoint, Set[Watch]], block2tx: SortedMap[Long, Seq[Transaction]], nextTick: Option[Cancellable]): Receive = {

    case NewTransaction(tx) =>
      KamonExt.time("watcher.newtx.checkwatch.time") {
        log.debug(s"analyzing txid={} tx={}", tx.txid, tx)
        tx.txIn
          .map(_.outPoint)
          .flatMap(watchedUtxos.get)
          .flatten // List[Watch] -> Watch
          .collect {
            case w: WatchSpentBasic =>
              self ! TriggerEvent(w, WatchEventSpentBasic(w.event))
            case w: WatchSpent =>
              self ! TriggerEvent(w, WatchEventSpent(w.event, tx))
          }
      }

    case NewBlock(block) =>
      // using a Try because in tests we generate fake blocks
      log.debug(s"received blockid=${Try(block.blockId).getOrElse(ByteVector32(ByteVector.empty))}")
      nextTick.map(_.cancel()) // this may fail or succeed, worse case scenario we will have two ticks in a row (no big deal)
      log.debug(s"scheduling a new task to check on tx confirmations")
      // we do this to avoid herd effects in testing when generating a lots of blocks in a row
      val task = context.system.scheduler.scheduleOnce(2 seconds, self, TickNewBlock)
      context become watching(watches, watchedUtxos, block2tx, Some(task))

    case TickNewBlock =>
      updateBlockCount()
      // if there are pending WatchConfirmed(s) we trigger a rescan to be able to check if any of them was confirmed
      val watchesConfirmed = watches.collect { case w: WatchConfirmed => w }
      rescanAndCheck(watchesConfirmed)
      context become watching(watches, watchedUtxos, block2tx, None)

    case TriggerEvent(w, e) if watches.contains(w) =>
      log.info(s"triggering $w")
      w.channel ! e
      w match {
        case _: WatchSpent =>
          // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx
          // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
          ()
        case _ =>
          context become watching(watches - w, removeWatchedUtxos(watchedUtxos, w), block2tx, None)
      }

    case CurrentBlockCount(count) => {
      val toPublish = block2tx.filterKeys(_ <= count)
      toPublish.values.flatten.map(tx => publish(tx))
      context become watching(watches, watchedUtxos, block2tx -- toPublish.keys, None)
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

        case w: WatchConfirmed =>
          val watchAddress = eclair.scriptPubKeyToAddress(w.publicKeyScript)
          for {
            isImported <- client.isAddressImported(watchAddress)
            if !isImported
            _ <- client.importAddress(watchAddress)
          } yield Unit

        case _: WatchLost => () // TODO: not implemented

        case w => log.warning(s"ignoring $w")
      }

      log.debug(s"adding watch $w for $sender")
      context.watch(w.channel)
      context become watching(watches + w, addWatchedUtxos(watchedUtxos, w), block2tx, nextTick)

    case PublishAsap(tx) =>
      val blockCount = this.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = Scripts.csvTimeout(tx)
      if (csvTimeout > 0) {
        require(tx.txIn.size == 1, s"watcher only supports tx with 1 input, this tx has ${tx.txIn.size} inputs")
        val parentTxid = tx.txIn(0).outPoint.txid
        log.info(s"txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parenttxid=$parentTxid tx=$tx")
        val parentPublicKey = fr.acinq.bitcoin.Script.write(fr.acinq.bitcoin.Script.pay2wsh(tx.txIn.head.witness.stack.last))
        self ! WatchConfirmed(self, parentTxid, parentPublicKey, minDepth = 1, BITCOIN_PARENT_TX_CONFIRMED(tx), blockCount)
      } else if (cltvTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$cltvTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(cltvTimeout, block2tx.getOrElse(cltvTimeout, Seq.empty[Transaction]) :+ tx)
        context become watching(watches, watchedUtxos, block2tx1, None)
      } else publish(tx)

    case WatchEventConfirmed(BITCOIN_PARENT_TX_CONFIRMED(tx), blockHeight, _, _) =>
      log.info(s"parent tx of txid=${tx.txid} has been confirmed")
      val blockCount = this.blockCount.get()
      val csvTimeout = Scripts.csvTimeout(tx)
      val absTimeout = blockHeight + csvTimeout
      if (absTimeout > blockCount) {
        log.info(s"delaying publication of txid=${tx.txid} until block=$absTimeout (curblock=$blockCount)")
        val block2tx1 = block2tx.updated(absTimeout, block2tx.getOrElse(absTimeout, Seq.empty[Transaction]) :+ tx)
        context become watching(watches, watchedUtxos, block2tx1, None)
      } else publish(tx)

    case ValidateRequest(ann) => client.validate(ann).pipeTo(sender)

    case GetTxWithMeta(txid) => client.getTransactionMeta(txid.toString()).pipeTo(sender)

    case Terminated(channel) =>
      // we remove watches associated to dead actor
      val deprecatedWatches = watches.filter(_.channel == channel)
      val watchedUtxos1 = deprecatedWatches.foldLeft(watchedUtxos) { case (m, w) => removeWatchedUtxos(m, w) }
      context.become(watching(watches -- deprecatedWatches, watchedUtxos1, block2tx, None))

    case GetHeightByTimestamp(time) => client.getHeightByTimestamp(time).map(GetHeightByTimestampResponse).pipeTo(sender)

    case 'watches => sender ! watches
  }

  // watchesConfirmed = all watch confirmed events, eventQueue = everything else
  def scanPending(watchesConfirmed: Set[WatchConfirmed], eventQueue: List[Any]): Receive = {
    case w:WatchConfirmed => context.become(scanPending(watchesConfirmed + w, eventQueue))

    // we must be able to compute heights by timestamp even before scanning
    case GetHeightByTimestamp(time) => client.getHeightByTimestamp(time).map(GetHeightByTimestampResponse).pipeTo(sender)

    // while pending initial scan we can still validate announcements
    case ValidateRequest(ann) => client.validate(ann).pipeTo(sender)

    case TickNewBlock => updateBlockCount()

    case TickInitialRescan =>
      log.info(s"doing initial chain rescan")
      rescanAndCheck(watchesConfirmed).onSuccess { case _ =>
        log.info(s"initial chain rescan completed, watcher ready")
        context.system.scheduler.scheduleOnce(1 seconds)(eventQueue.foreach(self ! _)) // fire the events that we did not process while scanning
        context.become(watching(watchesConfirmed.asInstanceOf[Set[Watch]], Map(), SortedMap(), None)) // rescan completed now goto watching
      }

    // non watch related events are stored until we go to watching state
    case unhandled =>
      log.info(s"received $unhandled in 'pendingScan' state")
      context.become(scanPending(watchesConfirmed, eventQueue :+ unhandled))
  }

  def updateBlockCount() = client.getBlockCount.map { count =>
    log.debug(s"setting blockCount=$count")
    blockCount.set(count)
    context.system.eventStream.publish(CurrentBlockCount(count))
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
      case t: Throwable => log.error(s"cannot publish tx: reason=${t.getMessage} txid=${tx.txid} tx=$tx")
    }
  }

  /**
    * Rescan the chain and check for confirmed watches, the rescan operation
    * is blocking while the check is not.
    */
  def rescanAndCheck(watches: Set[WatchConfirmed]): Future[Unit] = {
    if (watches.nonEmpty) {
      for {
        // we use the earliest rescan height '.min' is guaranteed to run on a non-empty list
        _ <- client.rescanBlockChain(watches.map(_.rescanHeight).min)
        _ = KamonExt.timeFuture("watcher.newblock.checkwatch.time") { Future.sequence(watches.map(checkConfirmed)) } // non blocking
      } yield Unit
    } else Future.successful(Unit)
  }

  def checkConfirmed(w: WatchConfirmed) = {
    log.debug(s"checking confirmations of txid=${w.txId}")
    // NB: this assumes that the tx addresses have been imported and the rescan has been done
    client.getTxConfirmations(w.txId.toString).map {
      case Some(confirmations) if confirmations >= w.minDepth =>
        client.getTransaction(w.txId.toString).map {
          case tx =>
            client.getTransactionShortId(w.txId.toString).map {
              case (height, index) => self ! TriggerEvent(w, WatchEventConfirmed(w.event, height, index, tx))
          }
        }
      case None =>
        log.warning(s"could not get confirmations of tx=${w.txId} for watch=$w")
    }
  }

}

object ZmqWatcher {

  val MIN_PRUNE_TARGET_SIZE = 25000000000L // minimum save prune target size is ~25gb

  def props(blockCount: AtomicLong, client: ExtendedBitcoinClient)(implicit ec: ExecutionContext = ExecutionContext.global) = Props(new ZmqWatcher(blockCount, client)(ec))

  case class TriggerEvent(w: Watch, e: WatchEvent)
  case object TickNewBlock
  case object TickInitialRescan

  def utxo(w: Watch): Option[OutPoint] =
    w match {
      case w: WatchSpent => Some(OutPoint(w.txId.reverse, w.outputIndex))
      case w: WatchSpentBasic => Some(OutPoint(w.txId.reverse, w.outputIndex))
      case _ => None
    }

  /**
    * The resulting map allows checking spent txes in constant time wrt number of watchers
    */
  def addWatchedUtxos(m: Map[OutPoint, Set[Watch]], w: Watch): Map[OutPoint, Set[Watch]] = {
    utxo(w) match {
      case Some(utxo) =>  m.get(utxo) match {
        case Some(watches) => m + (utxo -> (watches + w))
        case None => m + (utxo -> Set(w))
      }
      case None => m
    }
  }

  def removeWatchedUtxos(m: Map[OutPoint, Set[Watch]], w: Watch): Map[OutPoint, Set[Watch]] = {
    utxo(w) match {
      case Some(utxo) => m.get(utxo) match {
        case Some(watches) if watches - w == Set.empty => m - utxo
        case Some(watches) => m + (utxo -> (watches - w))
        case None => m
      }
      case None => m
    }
  }

}