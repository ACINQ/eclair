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

import akka.actor.{Actor, ActorLogging, Cancellable, Props, Status, Terminated}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair.scriptPubKeyToAddress
import fr.acinq.eclair.KamonExt
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.channel.BITCOIN_PARENT_TX_CONFIRMED
import fr.acinq.eclair.transactions.Scripts
import scodec.bits.ByteVector

import scala.collection.{Set, SortedMap}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * A blockchain watcher that:
  * - receives bitcoin events (new blocks and new txes) directly from the bitcoin network
  * - also uses bitcoin-core rpc api, most notably for tx confirmation count and blockcount (because reorgs)
  *
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
    * init ------> scanPending ---- TickInitialRescan ---> watching ---> scanning --|
    *                                                        ^                      |
    *                                                        |----------------------|
    *
    * The watcher is initialized into a 'scanPending' state during which it will queue the events,
    * when a 'TickInitialRescan' is received the watcher will instruct bitcoind to rescan. Only after
    * the initial chain rescan is completed the watcher is ready to process watch events (both spent and confirm).
    * The "scanning" state is used to queue chain scan during normal operation.
    */
  def receive: Receive = scanPending(Set.empty, Map.empty, List.empty)

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

    // we don't check for watch-spent when a new block arrives
    case TickNewBlock =>
      updateBlockCount()
      val watchConfirmed = watches.collect { case w: WatchConfirmed => w }.asInstanceOf[Set[Watch]]
      importMultiAndScan(watchConfirmed).map(_ => ScanCompleted(watchConfirmed)).pipeTo(self)
      context.become(scanning(watchConfirmed, List.empty, watches, watchedUtxos, block2tx, nextTick))

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
      log.debug(s"adding watch $w for $sender")
      importMultiAndScan(Set(w)).map(_ => ScanCompleted(Set(w))).pipeTo(self)
      context.become(scanning(Set(w), List.empty, watches, watchedUtxos, block2tx, nextTick))

    case ScanCompleted(scannedWatches) =>
      checkWatches(scannedWatches)
      // we always re-watch the channel and re-add the watch to the watched-utxo
      scannedWatches.foreach(w => context.watch(w.channel))
      val updatedUtxos = scannedWatches.map( w => addWatchedUtxos(watchedUtxos, w)).reduce(_ ++ _)
      context.become(watching(watches ++ scannedWatches, updatedUtxos, block2tx, nextTick))

    case PublishAsap(tx) =>
      val blockCount = this.blockCount.get()
      val cltvTimeout = Scripts.cltvTimeout(tx)
      val csvTimeout = Scripts.csvTimeout(tx)
      if (csvTimeout > 0) {
        require(tx.txIn.size == 1, s"watcher only supports tx with 1 input, this tx has ${tx.txIn.size} inputs")
        val parentTxid = tx.txIn(0).outPoint.txid
        log.info(s"txid=${tx.txid} has a relative timeout of $csvTimeout blocks, watching parenttxid=$parentTxid tx=$tx")
        val parentPublicKey = fr.acinq.bitcoin.Script.write(fr.acinq.bitcoin.Script.pay2wsh(tx.txIn.head.witness.stack.last))
        self ! WatchConfirmed(self, parentTxid, parentPublicKey, minDepth = 1, BITCOIN_PARENT_TX_CONFIRMED(tx), RescanFrom(rescanHeight = Some(blockCount)))
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

    case GetTxWithMeta(channel, txid) => client.getTransactionMeta(txid.toString()).pipeTo(channel)

    case Terminated(channel) =>
      // we remove watches associated to dead actor
      val deprecatedWatches = watches.filter(_.channel == channel)
      val watchedUtxos1 = deprecatedWatches.foldLeft(watchedUtxos) { case (m, w) => removeWatchedUtxos(m, w) }
      context.become(watching(watches -- deprecatedWatches, watchedUtxos1, block2tx, None))

    case 'watches => sender ! watches
    case 'state => sender ! "WATCHING"
  }

  /**
    * Initial state: during this state we queue the watches and wait for `TickInitialRescan` which is
    * the trigger. Once received the trigger we call `importMulti` RPC using proper timestamps
    * for each script, `importMulti` will also rescan the chain. If and only if the `importMulti`
    * completed successfully we can transition to `watching` state.
    *
    * NB: `importmulti` is treated as an atomic operation, when it completes we assume it also rescanned
    * from where it was necessary (see timestamp), if we see some scripts already imported
    * we exclude them from `importmulti` because they are already indexed
    */
  def scanPending(watches: Set[Watch], watchedUtxos: Map[OutPoint, Set[Watch]], eventQueue: List[Any]): Receive = {

    case w: Watch => context.become(scanPending(watches + w, addWatchedUtxos(watchedUtxos, w), eventQueue))
    // while pending initial scan we can still validate announcements
    case ValidateRequest(ann) => client.validate(ann).pipeTo(sender)

    case TickNewBlock => updateBlockCount()

    case TickInitialRescan =>
      log.info(s"doing initial chain rescan")
      importMultiAndScan(watches).onComplete {
        case Success(_) =>
          checkWatches(watches)
          context.system.scheduler.scheduleOnce(1 seconds)(eventQueue.foreach(self ! _)) // fire non watch events that were queued while scanning
          context.become(watching(watches, watchedUtxos, SortedMap(), None)) // rescan completed now goto watching
        case Failure(exception) =>
          log.error(s"failed importing watch=${watches.toSeq.map(_.getClass.getSimpleName)}", exception)
          context.system.scheduler.scheduleOnce(2 seconds)(self ! TickInitialRescan)
      }

    case 'watches => sender ! watches
    case 'state => sender ! "INITIAL_SCAN_PENDING"
    // non watch related events are stored until we go to watching state
    case unhandled =>
      log.info(s"received $unhandled in 'pendingScan' state")
      context.become(scanPending(watches, watchedUtxos, eventQueue :+ unhandled))
  }

  def scanning(scanningWatches: Set[Watch], eventQueue: List[Any], oldWatches: Set[Watch], watchedUtxos: Map[OutPoint, Set[Watch]], block2tx: SortedMap[Long, Seq[Transaction]], nextTick: Option[Cancellable]): Receive = {
    case s: ScanCompleted =>
      log.debug(s"scan completed")
      context.system.scheduler.scheduleOnce(1 seconds)(self ! s)
      context.system.scheduler.scheduleOnce(1 seconds)(eventQueue.foreach(self ! _))
      context.become(watching(oldWatches, watchedUtxos, block2tx, nextTick))

    case ValidateRequest(ann) => client.validate(ann).pipeTo(sender)

    case 'state => sender ! "SCANNING"
    case 'watches => sender ! scanningWatches

    case Status.Failure(exception) =>
      log.warning(s"failed importing, retry in 2s watches=${scanningWatches.toSeq.map(_.getClass.getSimpleName)}", exception)
      context.system.scheduler.scheduleOnce(2 seconds){
        importMultiAndScan(scanningWatches).map(_ => ScanCompleted(scanningWatches)).pipeTo(self)
      }

    case unhandled =>
      log.info(s"received $unhandled in 'scanning' state")
      context.become(scanning(scanningWatches, eventQueue :+ unhandled, oldWatches, watchedUtxos, block2tx, nextTick))
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
    * Computes the address and timestamp for each watch, then it imports those that are not yet imported
    * instructing bitcoind to use the timestamp for the rescan height.
    */
  def importMultiAndScan(watches: Set[Watch]): Future[Unit] = {
    val scriptsWithTimestamp = watches.toSeq.collect {
      case WatchConfirmed(_, _, script, _, _, RescanFrom(Some(timestamp), _))  => Future.successful((scriptPubKeyToAddress(script), timestamp))
      case WatchSpent(_, _, _, script, _, RescanFrom(Some(timestamp), _))      => Future.successful((scriptPubKeyToAddress(script), timestamp))
      case WatchSpentBasic(_, _, _, script, _, RescanFrom(Some(timestamp), _)) => Future.successful((scriptPubKeyToAddress(script), timestamp))
      case WatchConfirmed(_, _, script, _, _, RescanFrom(_, Some(height)))  => client.getBlock(height).map { block => (scriptPubKeyToAddress(script), block.header.time) }
      case WatchSpent(_, _, _, script, _, RescanFrom(_, Some(height)))      => client.getBlock(height).map { block => (scriptPubKeyToAddress(script), block.header.time) }
      case WatchSpentBasic(_, _, _, script, _, RescanFrom(_, Some(height))) => client.getBlock(height).map { block => (scriptPubKeyToAddress(script), block.header.time) }
    }

    for {
      alreadyImported <- client.listReceivedByAddress().map(_.filter(_.label == "IMPORTED")).map(_.map(_.address))
      resolved <- Future.sequence(scriptsWithTimestamp)
      toImport = resolved.filterNot(el => alreadyImported.contains(el._1)).map {
        case (address, timestamp) => ImportMultiItem(address, "PENDING", timestamp)
      }
      imported <- if(toImport.nonEmpty) client.importMulti(toImport, rescan = true) else Future.successful(true) // import addresses and rescan
      _ = if(!imported) throw new IllegalStateException("failed to import some addresses")
      _ <- if(toImport.nonEmpty) Future.sequence(toImport.map( item => client.setLabel(item.address, "IMPORTED"))) else Future.successful(true)
    } yield Unit
  }

  def checkWatches(watches: Set[Watch]): Future[Set[Any]] = {
    KamonExt.timeFuture("watcher.newblock.checkwatch.time") {
      Future.sequence(watches.collect {
        case w: WatchConfirmed => checkConfirmed(w)
        case w: WatchSpent => checkSpent(w)
        case w: WatchSpentBasic => checkSpentBasic(w)
      })
    }
  }

  def checkSpent(w: WatchSpent) = {
    client.isTransactionOutputSpendable(w.txId.toString(), w.outputIndex, true).collect {
      case false =>
        log.info(s"${w.txId.toHex}:${w.outputIndex} has already been spent, looking for the spending tx in the mempool")
        client.getMempool().map { mempoolTxs =>
          mempoolTxs.filter(tx => tx.txIn.exists(i => i.outPoint.txid == w.txId && i.outPoint.index == w.outputIndex)) match {
            case Nil =>
              log.warning(s"${w.txId.toHex}:${w.outputIndex} has already been spent, spending tx not in the mempool, looking in the blockchain...")
              client.lookForSpendingTx(None, w.txId.toString(), w.outputIndex).map { tx =>
                log.warning(s"found the spending tx of ${w.txId.toHex}:${w.outputIndex} in the blockchain: txid=${tx.txid}")
                self ! NewTransaction(tx)
              }
            case txs =>
              log.info(s"found ${txs.size} txs spending ${w.txId.toHex}:${w.outputIndex} in the mempool: txids=${txs.map(_.txid).mkString(",")}")
              txs.foreach(tx => self ! NewTransaction(tx))
          }
        }
    }
  }

  def checkSpentBasic(w: WatchSpentBasic) = {
    // not: we assume parent tx was published, we just need to make sure this particular output has not been spent
    client.isTransactionOutputSpendable(w.txId.toString(), w.outputIndex, true).collect {
      case false =>
        log.info(s"output=${w.outputIndex} of txid=${w.txId.toHex} has already been spent")
        self ! TriggerEvent(w, WatchEventSpentBasic(w.event))
    }
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