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
import fr.acinq.bitcoin._
import fr.acinq.eclair.KamonExt
import fr.acinq.eclair.blockchain.Monitoring.Metrics
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.watchdogs.BlockchainWatchdog
import fr.acinq.eclair.channel.BitcoinEvent
import fr.acinq.eclair.wire.protocol.ChannelAnnouncement
import org.json4s.JsonAST._

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by PM on 21/02/2016.
 */

/**
 * A blockchain watcher that:
 *  - receives bitcoin events (new blocks and new txs) directly from the bitcoin network
 *  - also uses bitcoin-core rpc api, most notably for tx confirmation count and block count (because reorgs)
 */
private class ZmqWatcher(chainHash: ByteVector32, blockCount: AtomicLong, client: ExtendedBitcoinClient, context: ActorContext[ZmqWatcher.Command], timers: TimerScheduler[ZmqWatcher.Command])(implicit ec: ExecutionContext = ExecutionContext.global) {

  import ZmqWatcher._

  private val log = context.log

  private val watchdog = context.spawn(Behaviors.supervise(BlockchainWatchdog(chainHash, 150 seconds)).onFailure(SupervisorStrategy.resume), "blockchain-watchdog")

  // @formatter:off
  private sealed trait AddWatchResult
  private case object Keep extends AddWatchResult
  private case object Ignore extends AddWatchResult
  // @formatter:on

  private def watching[E <: BitcoinEvent](watches: Set[Watch[E]], watchedUtxos: Map[OutPoint, Set[Watch[E]]]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case WrappedNewTransaction(tx) =>
        log.debug("analyzing txid={} tx={}", tx.txid, tx)
        tx.txIn
          .map(_.outPoint)
          .flatMap(watchedUtxos.get)
          .flatten
          .collect {
            case w: WatchSpentBasic[E] =>
              context.self ! TriggerEvent(w, WatchEventSpentBasic(w.event))
            case w: WatchSpent[E] =>
              context.self ! TriggerEvent(w, WatchEventSpent(w.event, tx))
          }
        Behaviors.same

      case WrappedNewBlock(block) =>
        log.debug("received blockid={}", block.blockId)
        log.debug("scheduling a new task to check on tx confirmations")
        // we do this to avoid herd effects in testing when generating a lots of blocks in a row
        timers.startSingleTimer(TickNewBlock, TickNewBlock, 2 seconds)
        Behaviors.same

      case TickNewBlock =>
        client.getBlockCount.map {
          count =>
            log.debug("setting blockCount={}", count)
            blockCount.set(count)
            context.system.eventStream ! EventStream.Publish(CurrentBlockCount(count))
        }
        checkUtxos()
        // TODO: beware of the herd effect
        KamonExt.timeFuture(Metrics.NewBlockCheckConfirmedDuration.withoutTags()) {
          Future.sequence(watches.collect { case w: WatchConfirmed[E] => checkConfirmed(w) })
        }
        Behaviors.same

      case trigger: TriggerEvent[E] =>
        if (watches.contains(trigger.watch)) {
          log.info("triggering {}", trigger.watch)
          trigger.watch.replyTo ! trigger.event
          trigger.watch match {
            case _: WatchSpent[E] =>
              // NB: WatchSpent are permanent because we need to detect multiple spending of the funding tx or the commit tx
              // They are never cleaned up but it is not a big deal for now (1 channel == 1 watch)
              Behaviors.same
            case _ =>
              watching(watches - trigger.watch, removeWatchedUtxos(watchedUtxos, trigger.watch))
          }
        } else {
          Behaviors.same
        }

      case w: Watch[E] =>
        val result = w match {
          case _ if watches.contains(w) =>
            Ignore // we ignore duplicates
          case w@WatchSpentBasic(_, txid, outputIndex, _) =>
            // NB: we assume parent tx was published, we just need to make sure this particular output has not been spent
            client.isTransactionOutputSpendable(txid, outputIndex, includeMempool = true).collect {
              case false =>
                log.info(s"output=$outputIndex of txid=$txid has already been spent")
                context.self ! TriggerEvent(w, WatchEventSpentBasic(w.event))
            }
            Keep
          case WatchSpent(_, txid, outputIndex, _, hints) =>
            // first let's see if the parent tx was published or not
            client.getTxConfirmations(txid).collect {
              case Some(_) =>
                // parent tx was published, we need to make sure this particular output has not been spent
                client.isTransactionOutputSpendable(txid, outputIndex, includeMempool = true).collect {
                  case false =>
                    // the output has been spent, let's find the spending tx
                    // if we know some potential spending txs, we try to fetch them directly
                    Future.sequence(hints.map(txid => client.getTransaction(txid).map(Some(_)).recover { case _ => None }))
                      .map(_
                        .flatten // filter out errors
                        .find(tx => tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex)) match {
                        case Some(spendingTx) =>
                          // there can be only one spending tx for an utxo
                          log.info(s"$txid:$outputIndex has already been spent by a tx provided in hints: txid=${spendingTx.txid}")
                          context.self ! WrappedNewTransaction(spendingTx)
                        case None =>
                          // no luck, we have to do it the hard way...
                          log.info(s"$txid:$outputIndex has already been spent, looking for the spending tx in the mempool")
                          client.getMempool().map { mempoolTxs =>
                            mempoolTxs.filter(tx => tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex)) match {
                              case Nil =>
                                log.warn(s"$txid:$outputIndex has already been spent, spending tx not in the mempool, looking in the blockchain...")
                                client.lookForSpendingTx(None, txid, outputIndex).map { tx =>
                                  log.warn(s"found the spending tx of $txid:$outputIndex in the blockchain: txid=${tx.txid}")
                                  context.self ! WrappedNewTransaction(tx)
                                }
                              case txs =>
                                log.info(s"found ${txs.size} txs spending $txid:$outputIndex in the mempool: txids=${txs.map(_.txid).mkString(",")}")
                                txs.foreach(tx => context.self ! WrappedNewTransaction(tx))
                            }
                          }
                      })
                }
            }
            Keep
          case w: WatchConfirmed[E] =>
            checkConfirmed(w) // maybe the tx is already confirmed, in that case the watch will be triggered and removed immediately
            Keep
          case _: WatchLost[E] =>
            // TODO: not implemented, we ignore it silently
            Ignore
        }
        result match {
          case Keep =>
            log.debug("adding watch {} for {}", w, w.replyTo)
            context.watchWith(w.replyTo, StopWatching(w.replyTo))
            watching(watches + w, addWatchedUtxos(watchedUtxos, w))
          case Ignore =>
            Behaviors.same
        }

      case stop: StopWatching[E] =>
        // we remove watches associated to dead actors
        val deprecatedWatches = watches.filter(_.replyTo == stop.sender)
        val watchedUtxos1 = deprecatedWatches.foldLeft(watchedUtxos) { case (m, w) => removeWatchedUtxos(m, w) }
        watching(watches -- deprecatedWatches, watchedUtxos1)

      case ValidateRequest(replyTo, ann) =>
        client.validate(ann).map(replyTo ! _)
        Behaviors.same

      case GetTxWithMeta(replyTo, txid) =>
        client.getTransactionMeta(txid).map(replyTo ! _)
        Behaviors.same

      case r: Watches[E] =>
        r.replyTo ! watches
        Behaviors.same

    }
  }

  def checkConfirmed[E <: BitcoinEvent](w: WatchConfirmed[E]): Future[Unit] = {
    log.debug("checking confirmations of txid={}", w.txId)
    // NB: this is very inefficient since internally we call `getrawtransaction` three times, but it doesn't really
    // matter because this only happens once, when the watched transaction has reached min_depth
    client.getTxConfirmations(w.txId).flatMap {
      case Some(confirmations) if confirmations >= w.minDepth =>
        client.getTransaction(w.txId).flatMap { tx =>
          client.getTransactionShortId(w.txId).map {
            case (height, index) => context.self ! TriggerEvent(w, WatchEventConfirmed(w.event, height, index, tx))
          }
        }
    }
  }

  def checkUtxos(): Future[Unit] = {
    case class Utxo(txId: ByteVector32, amount: MilliBtc, confirmations: Long, safe: Boolean)

    def listUnspent(): Future[Seq[Utxo]] = client.rpcClient.invoke("listunspent", /* minconf */ 0).collect {
      case JArray(values) => values.map(utxo => {
        val JInt(confirmations) = utxo \ "confirmations"
        val JBool(safe) = utxo \ "safe"
        val JDecimal(amount) = utxo \ "amount"
        val JString(txid) = utxo \ "txid"
        Utxo(ByteVector32.fromValidHex(txid), (amount.doubleValue * 1000).millibtc, confirmations.toLong, safe)
      })
    }

    def getUnconfirmedAncestorCount(utxo: Utxo): Future[(ByteVector32, Long)] = client.rpcClient.invoke("getmempoolentry", utxo.txId).map(json => {
      val JInt(ancestorCount) = json \ "ancestorcount"
      (utxo.txId, ancestorCount.toLong)
    })

    def getUnconfirmedAncestorCountMap(utxos: Seq[Utxo]): Future[Map[ByteVector32, Long]] = Future.sequence(utxos.filter(_.confirmations == 0).map(getUnconfirmedAncestorCount)).map(_.toMap)

    def recordUtxos(utxos: Seq[Utxo], ancestorCount: Map[ByteVector32, Long]): Unit = {
      val filteredByStatus = Seq(
        (Monitoring.Tags.UtxoStatuses.Confirmed, utxos.filter(utxo => utxo.confirmations > 0)),
        // We cannot create chains of unconfirmed transactions with more than 25 elements, so we ignore such utxos.
        (Monitoring.Tags.UtxoStatuses.Unconfirmed, utxos.filter(utxo => utxo.confirmations == 0 && ancestorCount.getOrElse(utxo.txId, 1L) < 25)),
        (Monitoring.Tags.UtxoStatuses.Safe, utxos.filter(utxo => utxo.safe)),
        (Monitoring.Tags.UtxoStatuses.Unsafe, utxos.filter(utxo => !utxo.safe)),
      )
      filteredByStatus.foreach {
        case (status, filteredUtxos) =>
          val amount = filteredUtxos.map(_.amount.toDouble).sum
          log.info(s"we have ${filteredUtxos.length} $status utxos ($amount mBTC)")
          Monitoring.Metrics.UtxoCount.withTag(Monitoring.Tags.UtxoStatus, status).update(filteredUtxos.length)
          Monitoring.Metrics.BitcoinBalance.withTag(Monitoring.Tags.UtxoStatus, status).update(amount)
      }
    }

    (for {
      utxos <- listUnspent()
      ancestorCount <- getUnconfirmedAncestorCountMap(utxos)
    } yield recordUtxos(utxos, ancestorCount)).recover {
      case ex => log.warn(s"could not check utxos: $ex")
    }
  }

}

object ZmqWatcher {

  // @formatter:off
  sealed trait Command
  sealed trait WatchEvent[E <: BitcoinEvent] {
    def event: E
  }
  sealed trait Watch[E <: BitcoinEvent] extends Command {
    def replyTo: ActorRef[WatchEvent[E]]
    def event: E
  }
  private case class TriggerEvent[E <: BitcoinEvent](watch: Watch[E], event: WatchEvent[E]) extends Command
  private[bitcoind] case class StopWatching[E <: BitcoinEvent](sender: ActorRef[WatchEvent[E]]) extends Command
  case class Watches[E <: BitcoinEvent](replyTo: ActorRef[Set[Watch[E]]]) extends Command

  private case object TickNewBlock extends Command
  private case class WrappedNewBlock(block: Block) extends Command
  private case class WrappedNewTransaction(tx: Transaction) extends Command

  final case class ValidateRequest(replyTo: ActorRef[ValidateResult], ann: ChannelAnnouncement) extends Command
  final case class ValidateResult(c: ChannelAnnouncement, fundingTx: Either[Throwable, (Transaction, UtxoStatus)])

  final case class GetTxWithMeta(replyTo: ActorRef[GetTxWithMetaResponse], txid: ByteVector32) extends Command
  final case class GetTxWithMetaResponse(txid: ByteVector32, tx_opt: Option[Transaction], lastBlockTimestamp: Long)

  sealed trait UtxoStatus
  object UtxoStatus {
    case object Unspent extends UtxoStatus
    case class Spent(spendingTxConfirmed: Boolean) extends UtxoStatus
  }
  // @formatter:on

  /**
   * Watch for confirmation of a given transaction.
   *
   * @param replyTo  actor to notify once the transaction is confirmed.
   * @param txId     txid of the transaction to watch.
   * @param minDepth number of confirmations.
   * @param event    channel event related to the transaction.
   */
  final case class WatchConfirmed[E <: BitcoinEvent](replyTo: ActorRef[WatchEvent[E]], txId: ByteVector32, minDepth: Long, event: E) extends Watch[E]

  /**
   * This event is sent when a [[WatchConfirmed]] condition is met.
   *
   * @param event       channel event related to the transaction that has been confirmed.
   * @param blockHeight block in which the transaction was confirmed.
   * @param txIndex     index of the transaction in the block.
   * @param tx          transaction that has been confirmed.
   */
  final case class WatchEventConfirmed[E <: BitcoinEvent](event: E, blockHeight: Int, txIndex: Int, tx: Transaction) extends WatchEvent[E]

  /**
   * Watch for transactions spending the given outpoint.
   *
   * NB: an event will be triggered *every time* a transaction spends the given outpoint. This can be useful when:
   *  - we see a spending transaction in the mempool, but it is then replaced (RBF)
   *  - we see a spending transaction in the mempool, but a conflicting transaction "wins" and gets confirmed in a block
   *
   * @param replyTo     actor to notify when the outpoint is spent.
   * @param txId        txid of the outpoint to watch.
   * @param outputIndex index of the outpoint to watch.
   * @param event       channel event related to the outpoint.
   * @param hints       txids of potential spending transactions; most of the time we know the txs, and it allows for optimizations.
   *                    This argument can safely be ignored by watcher implementations.
   */
  final case class WatchSpent[E <: BitcoinEvent](replyTo: ActorRef[WatchEvent[E]], txId: ByteVector32, outputIndex: Int, event: E, hints: Set[ByteVector32]) extends Watch[E]

  /**
   * This event is sent when a [[WatchSpent]] condition is met.
   *
   * @param event channel event related to the outpoint that was spent.
   * @param tx    transaction spending the watched outpoint.
   */
  final case class WatchEventSpent[E <: BitcoinEvent](event: E, tx: Transaction) extends WatchEvent[E]

  /**
   * Watch for the first transaction spending the given outpoint. We assume that txid is already confirmed or in the
   * mempool (i.e. the outpoint exists).
   *
   * NB: an event will be triggered only once when we see a transaction that spends the given outpoint. If you want to
   * react to the transaction spending the outpoint, you should use [[WatchSpent[_]]] instead.
   *
   * @param replyTo     actor to notify when the outpoint is spent.
   * @param txId        txid of the outpoint to watch.
   * @param outputIndex index of the outpoint to watch.
   * @param event       channel event related to the outpoint.
   */
  final case class WatchSpentBasic[E <: BitcoinEvent](replyTo: ActorRef[WatchEvent[E]], txId: ByteVector32, outputIndex: Int, event: E) extends Watch[E]

  /**
   * This event is sent when a [[WatchSpentBasic]] condition is met.
   *
   * @param event channel event related to the outpoint that was spent.
   */
  final case class WatchEventSpentBasic[E <: BitcoinEvent](event: E) extends WatchEvent[E]

  // TODO: not implemented yet: notify me if confirmation number gets below minDepth?
  final case class WatchLost[E <: BitcoinEvent](replyTo: ActorRef[WatchEvent[E]], txId: ByteVector32, minDepth: Long, event: E) extends Watch[E]

  // TODO: not implemented yet.
  final case class WatchEventLost[E <: BitcoinEvent](event: E) extends WatchEvent[E]

  def apply[E <: BitcoinEvent](chainHash: ByteVector32, blockCount: AtomicLong, client: ExtendedBitcoinClient): Behavior[Command] =
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[NewBlock](b => WrappedNewBlock(b.block)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[NewTransaction](t => WrappedNewTransaction(t.tx)))
      Behaviors.withTimers { timers =>
        // we initialize block count
        timers.startSingleTimer(TickNewBlock, TickNewBlock, 1 second)
        new ZmqWatcher(chainHash, blockCount, client, context, timers).watching(Set.empty[Watch[E]], Map.empty[OutPoint, Set[Watch[E]]])
      }
    }

  private def utxo[E <: BitcoinEvent](w: Watch[E]): Option[OutPoint] = {
    w match {
      case w: WatchSpent[E] => Some(OutPoint(w.txId.reverse, w.outputIndex))
      case w: WatchSpentBasic[E] => Some(OutPoint(w.txId.reverse, w.outputIndex))
      case _ => None
    }
  }

  /**
   * The resulting map allows checking spent txs in constant time wrt number of watchers.
   */
  def addWatchedUtxos[E <: BitcoinEvent](m: Map[OutPoint, Set[Watch[E]]], w: Watch[E]): Map[OutPoint, Set[Watch[E]]] = {
    utxo(w) match {
      case Some(utxo) => m.get(utxo) match {
        case Some(watches) => m + (utxo -> (watches + w))
        case None => m + (utxo -> Set(w))
      }
      case None => m
    }
  }

  def removeWatchedUtxos[E <: BitcoinEvent](m: Map[OutPoint, Set[Watch[E]]], w: Watch[E]): Map[OutPoint, Set[Watch[E]]] = {
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
