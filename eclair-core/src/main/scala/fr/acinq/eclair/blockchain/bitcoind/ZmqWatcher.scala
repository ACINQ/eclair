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

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair.blockchain.Monitoring.Metrics
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog
import fr.acinq.eclair.wire.protocol.ChannelAnnouncement
import fr.acinq.eclair.{BlockHeight, KamonExt, NodeParams, RealShortChannelId, TimestampSecond}

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success}

/**
 * Created by PM on 21/02/2016.
 */

/**
 * A blockchain watcher that:
 *  - receives bitcoin events (new blocks and new txs) directly from the bitcoin network
 *  - also uses bitcoin-core rpc api, most notably for tx confirmation count and block count (because reorgs)
 */
object ZmqWatcher {

  val blockTimeout: FiniteDuration = 15 minutes

  // @formatter:off
  sealed trait Command
  sealed trait Watch[T <: WatchTriggered] extends Command {
    def replyTo: ActorRef[T]
  }
  type GenericWatch = Watch[_ <: WatchTriggered]
  sealed trait WatchTriggered
  private case class TriggerEvent[T <: WatchTriggered](replyTo: ActorRef[T], watch: Watch[T], event: T) extends Command
  private[bitcoind] case class StopWatching[T <: WatchTriggered](sender: ActorRef[T]) extends Command
  case class ListWatches(replyTo: ActorRef[Set[GenericWatch]]) extends Command

  private case object TickNewBlock extends Command
  private case object TickBlockTimeout extends Command
  private case class GetBlockCountFailed(t: Throwable) extends Command
  private case class GetBlockIdFailed(blockHeight: BlockHeight, t: Throwable) extends Command
  private case class GetBlockFailed(blockId: BlockId, t: Throwable) extends Command
  private case class CheckBlockHeight(current: BlockHeight) extends Command
  private case class PublishBlockHeight(current: BlockHeight) extends Command
  private case class ProcessNewBlock(blockId: BlockId) extends Command
  private case class ProcessNewTransaction(tx: Transaction) extends Command
  private case class AnalyzeLastBlock(remaining: Int) extends Command
  private case class AnalyzeBlockId(blockId: BlockId, remaining: Int) extends Command
  private case class AnalyzeBlock(block: Block, remaining: Int) extends Command
  private case class SetWatchHint(w: GenericWatch, hint: WatchHint) extends Command

  final case class ValidateRequest(replyTo: ActorRef[ValidateResult], ann: ChannelAnnouncement) extends Command
  final case class ValidateResult(c: ChannelAnnouncement, fundingTx: Either[Throwable, (Transaction, UtxoStatus)])

  final case class GetTxWithMeta(replyTo: ActorRef[GetTxWithMetaResponse], txid: TxId) extends Command
  final case class GetTxWithMetaResponse(txid: TxId, tx_opt: Option[Transaction], lastBlockTimestamp: TimestampSecond)

  sealed trait UtxoStatus
  object UtxoStatus {
    case object Unspent extends UtxoStatus
    case class Spent(spendingTxConfirmed: Boolean) extends UtxoStatus
  }

  /** Watch for confirmation of a given transaction. */
  sealed trait WatchConfirmed[T <: WatchConfirmedTriggered] extends Watch[T] {
    /** TxId of the transaction to watch. */
    def txId: TxId
    /** Number of confirmations. */
    def minDepth: Int
  }

  /**
   * Watch for transactions spending the given outpoint.
   *
   * NB: an event will be triggered *every time* a transaction spends the given outpoint. This can be useful when:
   *  - we see a spending transaction in the mempool, but it is then replaced (RBF)
   *  - we see a spending transaction in the mempool, but a conflicting transaction "wins" and gets confirmed in a block
   */
  sealed trait WatchSpent[T <: WatchSpentTriggered] extends Watch[T] {
    /** TxId of the outpoint to watch. */
    def txId: TxId
    /** Index of the outpoint to watch. */
    def outputIndex: Int
    /**
     * TxIds of potential spending transactions; most of the time we know the txs, and it allows for optimizations.
     * This argument can safely be ignored by watcher implementations.
     */
    def hints: Set[TxId]
  }

  /** This event is sent when a [[WatchConfirmed]] condition is met. */
  sealed trait WatchConfirmedTriggered extends WatchTriggered {
    /** Block in which the transaction was confirmed. */
    def blockHeight: BlockHeight
    /** Index of the transaction in that block. */
    def txIndex: Int
    /** Transaction that has been confirmed. */
    def tx: Transaction
  }

  /** This event is sent when a [[WatchSpent]] condition is met. */
  sealed trait WatchSpentTriggered extends WatchTriggered {
    /** Transaction spending the watched outpoint. */
    def spendingTx: Transaction
  }

  case class WatchExternalChannelSpent(replyTo: ActorRef[WatchExternalChannelSpentTriggered], txId: TxId, outputIndex: Int, shortChannelId: RealShortChannelId) extends WatchSpent[WatchExternalChannelSpentTriggered] { override def hints: Set[TxId] = Set.empty }
  case class WatchExternalChannelSpentTriggered(shortChannelId: RealShortChannelId, spendingTx: Transaction) extends WatchSpentTriggered
  case class UnwatchExternalChannelSpent(txId: TxId, outputIndex: Int) extends Command

  case class WatchFundingSpent(replyTo: ActorRef[WatchFundingSpentTriggered], txId: TxId, outputIndex: Int, hints: Set[TxId]) extends WatchSpent[WatchFundingSpentTriggered]
  case class WatchFundingSpentTriggered(spendingTx: Transaction) extends WatchSpentTriggered

  case class WatchOutputSpent(replyTo: ActorRef[WatchOutputSpentTriggered], txId: TxId, outputIndex: Int, amount: Satoshi, hints: Set[TxId]) extends WatchSpent[WatchOutputSpentTriggered]
  case class WatchOutputSpentTriggered(amount: Satoshi, spendingTx: Transaction) extends WatchSpentTriggered

  /** Waiting for a wallet transaction to be published guarantees that bitcoind won't double-spend it in the future, unless we explicitly call abandontransaction. */
  case class WatchPublished(replyTo: ActorRef[WatchPublishedTriggered], txId: TxId) extends Watch[WatchPublishedTriggered]
  case class WatchPublishedTriggered(tx: Transaction) extends WatchTriggered

  case class WatchFundingConfirmed(replyTo: ActorRef[WatchFundingConfirmedTriggered], txId: TxId, minDepth: Int) extends WatchConfirmed[WatchFundingConfirmedTriggered]
  case class WatchFundingConfirmedTriggered(blockHeight: BlockHeight, txIndex: Int, tx: Transaction) extends WatchConfirmedTriggered

  case class WatchTxConfirmed(replyTo: ActorRef[WatchTxConfirmedTriggered], txId: TxId, minDepth: Int) extends WatchConfirmed[WatchTxConfirmedTriggered]
  case class WatchTxConfirmedTriggered(blockHeight: BlockHeight, txIndex: Int, tx: Transaction) extends WatchConfirmedTriggered

  case class WatchParentTxConfirmed(replyTo: ActorRef[WatchParentTxConfirmedTriggered], txId: TxId, minDepth: Int) extends WatchConfirmed[WatchParentTxConfirmedTriggered]
  case class WatchParentTxConfirmedTriggered(blockHeight: BlockHeight, txIndex: Int, tx: Transaction) extends WatchConfirmedTriggered

  case class WatchAlternativeCommitTxConfirmed(replyTo: ActorRef[WatchAlternativeCommitTxConfirmedTriggered], txId: TxId, minDepth: Int) extends WatchConfirmed[WatchAlternativeCommitTxConfirmedTriggered]
  case class WatchAlternativeCommitTxConfirmedTriggered(blockHeight: BlockHeight, txIndex: Int, tx: Transaction) extends WatchConfirmedTriggered

  private sealed trait AddWatchResult
  private case object Keep extends AddWatchResult
  private case object Ignore extends AddWatchResult

  /** Stop watching confirmations for a given transaction: must be used to stop watching obsolete RBF attempts. */
  case class UnwatchTxConfirmed(txId: TxId) extends Command

  sealed trait WatchHint
  /**
   * In some cases we don't need to check watches every time a block is found and only need to check again after we
   * reach a specific block height. This is for example the case for transactions with a CSV delay.
   */
  private case class CheckAfterBlock(blockHeight: BlockHeight) extends WatchHint
  // @formatter:on

  def apply(nodeParams: NodeParams, blockCount: AtomicLong, client: BitcoinCoreClient): Behavior[Command] =
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[NewBlock](b => ProcessNewBlock(b.blockId)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[NewTransaction](t => ProcessNewTransaction(t.tx)))
      Behaviors.withTimers { timers =>
        // we initialize block count
        timers.startSingleTimer(TickNewBlock, 1 second)
        // we start a timer in case we don't receive ZMQ block events
        timers.startSingleTimer(TickBlockTimeout, blockTimeout)
        new ZmqWatcher(nodeParams, blockCount, client, context, timers).watching(Map.empty[GenericWatch, Option[WatchHint]], Map.empty[OutPoint, Set[GenericWatch]], Nil)
      }
    }

  private def utxo(w: GenericWatch): Option[OutPoint] = {
    w match {
      case w: WatchSpent[_] => Some(OutPoint(w.txId, w.outputIndex))
      case _ => None
    }
  }

  /**
   * The resulting map allows checking spent txs in constant time wrt number of watchers.
   */
  def addWatchedUtxos(m: Map[OutPoint, Set[GenericWatch]], w: GenericWatch): Map[OutPoint, Set[GenericWatch]] = {
    utxo(w) match {
      case Some(utxo) => m.get(utxo) match {
        case Some(watches) => m + (utxo -> (watches + w))
        case None => m + (utxo -> Set(w))
      }
      case None => m
    }
  }

  def removeWatchedUtxos(m: Map[OutPoint, Set[GenericWatch]], w: GenericWatch): Map[OutPoint, Set[GenericWatch]] = {
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

private class ZmqWatcher(nodeParams: NodeParams, blockHeight: AtomicLong, client: BitcoinCoreClient, context: ActorContext[ZmqWatcher.Command], timers: TimerScheduler[ZmqWatcher.Command])(implicit ec: ExecutionContext = ExecutionContext.global) {

  import ZmqWatcher._

  private val log = context.log

  private val watchdog = context.spawn(Behaviors.supervise(BlockchainWatchdog(nodeParams, 150 seconds)).onFailure(SupervisorStrategy.resume), "blockchain-watchdog")

  private def watching(watches: Map[GenericWatch, Option[WatchHint]], watchedUtxos: Map[OutPoint, Set[GenericWatch]], analyzedBlocks: Seq[BlockId]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case ProcessNewTransaction(tx) =>
        log.debug("analyzing txid={} tx={}", tx.txid, tx)
        tx.txIn
          .map(_.outPoint)
          .flatMap(watchedUtxos.get)
          .flatten
          .foreach {
            case w: WatchExternalChannelSpent => context.self ! TriggerEvent(w.replyTo, w, WatchExternalChannelSpentTriggered(w.shortChannelId, tx))
            case w: WatchFundingSpent => context.self ! TriggerEvent(w.replyTo, w, WatchFundingSpentTriggered(tx))
            case w: WatchOutputSpent => context.self ! TriggerEvent(w.replyTo, w, WatchOutputSpentTriggered(w.amount, tx))
            case _: WatchPublished => // nothing to do
            case _: WatchConfirmed[_] => // nothing to do
          }
        watches.keySet.collect {
          case w: WatchPublished if w.txId == tx.txid => context.self ! TriggerEvent(w.replyTo, w, WatchPublishedTriggered(tx))
        }
        Behaviors.same

      case ProcessNewBlock(blockHash) =>
        log.debug("received blockhash={}", blockHash)
        log.debug("scheduling a new task to check on tx confirmations")
        // we have received a block, so we can reset the block timeout timer
        timers.startSingleTimer(TickBlockTimeout, blockTimeout)
        // we do this to avoid herd effects in testing when generating a lots of blocks in a row
        timers.startSingleTimer(TickNewBlock, 2 seconds)
        Behaviors.same

      case AnalyzeLastBlock(remaining) =>
        val currentBlockHeight = blockHeight.get().toInt
        context.pipeToSelf(client.getBlockId(currentBlockHeight)) {
          case Failure(f) => GetBlockIdFailed(BlockHeight(currentBlockHeight), f)
          case Success(blockId) => AnalyzeBlockId(blockId, remaining)
        }
        Behaviors.same

      case AnalyzeBlockId(blockId, remaining) =>
        if (analyzedBlocks.contains(blockId)) {
          log.debug("blockId={} has already been analyzed, we can skip it", blockId)
        } else if (remaining > 0) {
          context.pipeToSelf(client.getBlock(blockId)) {
            case Failure(f) => GetBlockFailed(blockId, f)
            case Success(block) => AnalyzeBlock(block, remaining)
          }
        }
        Behaviors.same

      case AnalyzeBlock(block, remaining) =>
        // We analyze every transaction in that block to see if one of our watches is triggered.
        block.tx.forEach(tx => context.self ! ProcessNewTransaction(KotlinUtils.kmp2scala(tx)))
        // We keep analyzing previous blocks in this chain.
        context.self ! AnalyzeBlockId(BlockId(KotlinUtils.kmp2scala(block.header.hashPreviousBlock)), remaining - 1)
        // We update our list of analyzed blocks, while ensuring that it doesn't grow unbounded.
        val maxCacheSize = nodeParams.channelConf.scanPreviousBlocksDepth * 3
        val analyzedBlocks1 = (KotlinUtils.kmp2scala(block.blockId) +: analyzedBlocks).take(maxCacheSize)
        watching(watches, watchedUtxos, analyzedBlocks1)

      case TickBlockTimeout =>
        // we haven't received a block in a while, we check whether we're behind and restart the timer.
        timers.startSingleTimer(TickBlockTimeout, blockTimeout)
        context.pipeToSelf(client.getBlockHeight()) {
          case Failure(t) => GetBlockCountFailed(t)
          case Success(currentHeight) => CheckBlockHeight(currentHeight)
        }
        Behaviors.same

      case GetBlockCountFailed(t) =>
        log.error("could not get block count from bitcoind", t)
        Behaviors.same

      case GetBlockIdFailed(blockHeight, t) =>
        log.error(s"cannot get blockId for blockHeight=$blockHeight", t)
        Behaviors.same

      case GetBlockFailed(blockId, t) =>
        // Note that this may happen if there is a reorg while we're analyzing the pre-reorg chain.
        log.warn("cannot get block for blockId={}, a reorg may have happened: {}", blockId, t.getMessage)
        Behaviors.same

      case CheckBlockHeight(height) =>
        val current = blockHeight.get()
        if (height.toLong > current) {
          log.warn("block {} wasn't received via ZMQ, you should verify that your bitcoind node is running", height.toLong)
          context.self ! TickNewBlock
        }
        Behaviors.same

      case TickNewBlock =>
        context.pipeToSelf(client.getBlockHeight()) {
          case Failure(t) => GetBlockCountFailed(t)
          case Success(currentHeight) => PublishBlockHeight(currentHeight)
        }
        Behaviors.same

      case PublishBlockHeight(currentHeight) =>
        log.debug("setting blockHeight={}", currentHeight)
        blockHeight.set(currentHeight.toLong)
        context.system.eventStream ! EventStream.Publish(CurrentBlockHeight(currentHeight))
        // TODO: should we try to mitigate the herd effect and not check all watches immediately?
        KamonExt.timeFuture(Metrics.NewBlockCheckConfirmedDuration.withoutTags()) {
          Future.sequence(watches.collect {
            case (w: WatchPublished, _) => checkPublished(w)
            case (w: WatchConfirmed[_], hint) =>
              hint match {
                case Some(CheckAfterBlock(delayUntilBlock)) if currentHeight < delayUntilBlock => Future.successful(())
                case _ => checkConfirmed(w, currentHeight)
              }
          })
        }
        timers.startSingleTimer(AnalyzeLastBlock(nodeParams.channelConf.scanPreviousBlocksDepth), Random.nextLong(nodeParams.channelConf.maxBlockProcessingDelay.toMillis + 1).milliseconds)
        Behaviors.same

      case SetWatchHint(w, hint) =>
        val watches1 = watches.get(w) match {
          case Some(_) => watches + (w -> Some(hint))
          case None => watches
        }
        watching(watches1, watchedUtxos, analyzedBlocks)

      case TriggerEvent(replyTo, watch, event) =>
        if (watches.contains(watch)) {
          log.debug("triggering {}", watch)
          replyTo ! event
          watch match {
            case _: WatchSpent[_] =>
              // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx or the commit tx
              // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
              Behaviors.same
            case _ =>
              watching(watches - watch, removeWatchedUtxos(watchedUtxos, watch), analyzedBlocks)
          }
        } else {
          Behaviors.same
        }

      case w: Watch[_] =>
        // We call check* methods and store the watch unconditionally.
        // Maybe the tx is already confirmed or spent, in that case the watch will be triggered and removed immediately.
        val result = w match {
          case _ if watches.contains(w) =>
            Ignore // we ignore duplicates
          case w: WatchSpent[_] =>
            checkSpent(w)
            Keep
          case w: WatchConfirmed[_] =>
            checkConfirmed(w, BlockHeight(blockHeight.get()))
            Keep
          case w: WatchPublished =>
            checkPublished(w)
            Keep
        }
        result match {
          case Keep =>
            log.debug("adding watch {}", w)
            context.watchWith(w.replyTo, StopWatching(w.replyTo))
            watching(watches + (w -> None), addWatchedUtxos(watchedUtxos, w), analyzedBlocks)
          case Ignore =>
            Behaviors.same
        }

      case StopWatching(origin) =>
        // We remove watches associated to dead actors.
        val deprecatedWatches = watches.keySet.filter(_.replyTo == origin)
        val watchedUtxos1 = deprecatedWatches.foldLeft(watchedUtxos) { case (m, w) => removeWatchedUtxos(m, w) }
        watching(watches -- deprecatedWatches, watchedUtxos1, analyzedBlocks)

      case UnwatchTxConfirmed(txId) =>
        // We remove watches that match the given txId.
        val deprecatedWatches = watches.keySet.filter {
          case w: WatchConfirmed[_] => w.txId == txId
          case _ => false
        }
        watching(watches -- deprecatedWatches, watchedUtxos, analyzedBlocks)

      case UnwatchExternalChannelSpent(txId, outputIndex) =>
        val deprecatedWatches = watches.keySet.collect { case w: WatchExternalChannelSpent if w.txId == txId && w.outputIndex == outputIndex => w }
        val watchedUtxos1 = deprecatedWatches.foldLeft(watchedUtxos) { case (m, w) => removeWatchedUtxos(m, w) }
        watching(watches -- deprecatedWatches, watchedUtxos1, analyzedBlocks)

      case ValidateRequest(replyTo, ann) =>
        client.validate(ann).map(replyTo ! _)
        Behaviors.same

      case GetTxWithMeta(replyTo, txid) =>
        client.getTransactionMeta(txid).map(replyTo ! _)
        Behaviors.same

      case r: ListWatches =>
        r.replyTo ! watches.keySet
        Behaviors.same

    }
  }

  private def checkSpent(w: WatchSpent[_ <: WatchSpentTriggered]): Future[Unit] = {
    // first let's see if the parent tx was published or not
    client.getTxConfirmations(w.txId).collect {
      case Some(_) =>
        // parent tx was published, we need to make sure this particular output has not been spent
        client.isTransactionOutputSpendable(w.txId, w.outputIndex, includeMempool = true).collect {
          case false =>
            // the output has been spent, let's find the spending tx
            // if we know some potential spending txs, we try to fetch them directly
            Future.sequence(w.hints.map(txid => client.getTransaction(txid).map(Some(_)).recover { case _ => None }))
              .map(_.flatten) // filter out errors and hint transactions that can't be found
              .map(hintTxs => {
                hintTxs.find(tx => tx.txIn.exists(i => i.outPoint.txid == w.txId && i.outPoint.index == w.outputIndex)) match {
                  case Some(spendingTx) =>
                    log.info(s"${w.txId}:${w.outputIndex} has already been spent by a tx provided in hints: txid=${spendingTx.txid}")
                    context.self ! ProcessNewTransaction(spendingTx)
                  case None =>
                    // The hints didn't help us, let's search for the spending transaction.
                    log.info(s"${w.txId}:${w.outputIndex} has already been spent, looking for the spending tx in the mempool")
                    client.lookForMempoolSpendingTx(w.txId, w.outputIndex).map(Some(_)).recover { case _ => None }.map {
                      case Some(spendingTx) =>
                        log.info(s"found tx spending ${w.txId}:${w.outputIndex} in the mempool: txid=${spendingTx.txid}")
                        context.self ! ProcessNewTransaction(spendingTx)
                      case None =>
                        // no luck, we have to do it the hard way...
                        log.warn(s"${w.txId}:${w.outputIndex} has already been spent, spending tx not in the mempool, looking in the blockchain...")
                        client.lookForSpendingTx(None, w.txId, w.outputIndex, nodeParams.channelConf.maxChannelSpentRescanBlocks).map { spendingTx =>
                          log.warn(s"found the spending tx of ${w.txId}:${w.outputIndex} in the blockchain: txid=${spendingTx.txid}")
                          context.self ! ProcessNewTransaction(spendingTx)
                        }.recover {
                          case _ => log.warn(s"could not find the spending tx of ${w.txId}:${w.outputIndex} in the blockchain, funds are at risk")
                        }
                    }
                }
              })
        }
    }
  }

  private def checkPublished(w: WatchPublished): Future[Unit] = {
    log.debug("checking publication of txid={}", w.txId)
    client.getTransaction(w.txId).map(tx => context.self ! TriggerEvent(w.replyTo, w, WatchPublishedTriggered(tx)))
  }

  private def checkConfirmed(w: WatchConfirmed[_ <: WatchConfirmedTriggered], currentHeight: BlockHeight): Future[Unit] = {
    log.debug("checking confirmations of txid={}", w.txId)
    client.getTxConfirmations(w.txId).flatMap {
      case Some(confirmations) if confirmations >= w.minDepth =>
        // NB: this is very inefficient since internally we call `getrawtransaction` three times, but it doesn't really
        // matter because this only happens once, when the watched transaction has reached min_depth
        client.getTransaction(w.txId).flatMap { tx =>
          client.getTransactionShortId(w.txId).map {
            case (height, index) => w match {
              case w: WatchFundingConfirmed => context.self ! TriggerEvent(w.replyTo, w, WatchFundingConfirmedTriggered(height, index, tx))
              case w: WatchTxConfirmed => context.self ! TriggerEvent(w.replyTo, w, WatchTxConfirmedTriggered(height, index, tx))
              case w: WatchParentTxConfirmed => context.self ! TriggerEvent(w.replyTo, w, WatchParentTxConfirmedTriggered(height, index, tx))
              case w: WatchAlternativeCommitTxConfirmed => context.self ! TriggerEvent(w.replyTo, w, WatchAlternativeCommitTxConfirmedTriggered(height, index, tx))
            }
          }
        }
      case Some(confirmations) =>
        // Once the transaction is confirmed, we don't need to check again at every new block, we only need to check
        // again once we should have reached the minimum depth to verify that there hasn't been a reorg.
        context.self ! SetWatchHint(w, CheckAfterBlock(currentHeight + w.minDepth - confirmations))
        Future.successful(())
      case None =>
        // The transaction is unconfirmed: we don't need to check again at every new block: we can check only once
        // every minDepth blocks, which is more efficient. If the transaction is included at the current height in
        // a reorg, we will trigger the watch one block later than expected, but this is fine.
        context.self ! SetWatchHint(w, CheckAfterBlock(currentHeight + w.minDepth))
        Future.successful(())
    }
  }

}
