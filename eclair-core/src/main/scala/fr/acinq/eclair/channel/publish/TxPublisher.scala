/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.channel.publish

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, OutPoint, Transaction}
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.transactions.Transactions.{ReplaceableTransactionWithInputInfo, TransactionWithInputInfo}
import fr.acinq.eclair.{Logs, NodeParams}

import java.util.UUID
import scala.concurrent.duration.DurationLong
import scala.util.Random

/**
 * Created by t-bast on 10/06/2021.
 */

/**
 * Every channel has a single child instance of the TxPublisher actor.
 * The TxPublisher ensures the channel's on-chain transactions confirm before funds become at risk.
 * It sets the fees, tracks confirmation progress and bumps the fees if necessary (via RBF or CPFP).
 */
object TxPublisher {

  // Here is a high-level view of how the transaction publishing process works:
  //
  // +---------+
  // | Channel |---+
  // +---------+   |             +--------------------+
  //               | PublishTx   |    TxPublisher     |
  //               +------------>| - stores txs and   |---+                      +-----------------+
  //                             |   deadlines        |   | create child actor   |    TxPublish    |
  //                             | - create child     |   | ask it to publish    | - preconditions |
  //                             |   actors that fund |   | at a given feerate   | - (funding)     |
  //                             |   and publish txs  |   +--------------------->| - (signing)     |
  //                             +--------------------+                          | - publishing    |
  //                                       ^                                     | - waiting       |
  //                                       |                                     | - stopping      |
  //                                       |                                     +-----------------+
  //                                       |                                              |
  //                                       +----------------------------------------------+
  //                                         result:
  //                                          - transaction confirmed
  //                                          - transaction rejected

  // @formatter:off
  sealed trait Command
  sealed trait PublishTx extends Command {
    /**
     * We don't aggregate transactions, so we can safely consider that transactions spend a single input of interest.
     * Transactions may have additional wallet inputs to set the fees (or to fund a channel), but these are not inputs
     * that we *need* to spend to make progress on the protocol.
     */
    def input: OutPoint
    def desc: String
  }
  /**
   * Publish a fully signed transaction without modifying it.
   * NB: the parent tx should only be provided when it's being concurrently published, it's unnecessary when it is
   * confirmed or when the tx has a relative delay.
   */
  case class PublishRawTx(tx: Transaction, input: OutPoint, desc: String, parentTx_opt: Option[Transaction]) extends PublishTx
  object PublishRawTx {
    def apply(txInfo: TransactionWithInputInfo, parentTx_opt: Option[Transaction]): PublishRawTx = PublishRawTx(txInfo.tx, txInfo.input.outPoint, txInfo.desc, parentTx_opt)
  }
  /** Publish an unsigned transaction that can be RBF-ed. */
  case class PublishReplaceableTx(txInfo: ReplaceableTransactionWithInputInfo, commitments: Commitments) extends PublishTx {
    override def input: OutPoint = txInfo.input.outPoint
    override def desc: String = txInfo.desc
  }

  sealed trait PublishTxResult extends Command {
    def cmd: PublishTx
  }
  /**
   * The requested transaction has been confirmed.
   *
   * @param tx the confirmed transaction (which may be different from the initial tx because of RBF).
   */
  case class TxConfirmed(cmd: PublishTx, tx: Transaction) extends PublishTxResult
  /** The requested transaction could not be published at that time, or was evicted from the mempool. */
  case class TxRejected(id: UUID, cmd: PublishTx, reason: TxRejectedReason) extends PublishTxResult

  sealed trait TxRejectedReason
  object TxRejectedReason {
    /** We don't have enough funds in our wallet to reach the given feerate. */
    case object CouldNotFund extends TxRejectedReason
    /** The transaction was published but then evicted from the mempool, because one of its wallet inputs disappeared (e.g. unconfirmed output of a transaction that was replaced). */
    case object WalletInputGone extends TxRejectedReason
    /** A conflicting transaction spending the same input has been confirmed. */
    case object ConflictingTxConfirmed extends TxRejectedReason
    /** A conflicting transaction spending the same input is in the mempool and we failed to replace it. */
    case object ConflictingTxUnconfirmed extends TxRejectedReason
    /** The transaction wasn't published because it's unnecessary or we're missing information. */
    case class TxSkipped(retryNextBlock: Boolean) extends TxRejectedReason
    /** Unrecoverable failure. */
    case object UnknownTxFailure extends TxRejectedReason
  }

  case class WrappedCurrentBlockCount(currentBlockCount: Long) extends Command
  case class SetChannelId(remoteNodeId: PublicKey, channelId: ByteVector32) extends Command
  // @formatter:on

  // @formatter:off
  case class ChannelInfo(remoteNodeId: PublicKey, channelId_opt: Option[ByteVector32])
  case class TxPublishInfo(id: UUID, remoteNodeId: PublicKey, channelId_opt: Option[ByteVector32]) {
    def mdc(): Map[String, String] = Logs.mdc(remoteNodeId_opt = Some(remoteNodeId), channelId_opt = channelId_opt, paymentId_opt = Some(id))
  }
  // @formatter:on

  trait ChildFactory {
    // @formatter:off
    def spawnRawTxPublisher(context: ActorContext[TxPublisher.Command], loggingInfo: TxPublishInfo): ActorRef[RawTxPublisher.Command]
    def spawnReplaceableTxPublisher(context: ActorContext[TxPublisher.Command], loggingInfo: TxPublishInfo): ActorRef[ReplaceableTxPublisher.Command]
    // @formatter:on
  }

  case class SimpleChildFactory(nodeParams: NodeParams, bitcoinClient: ExtendedBitcoinClient, watcher: ActorRef[ZmqWatcher.Command]) extends ChildFactory {
    // @formatter:off
    override def spawnRawTxPublisher(context: ActorContext[TxPublisher.Command], loggingInfo: TxPublishInfo): ActorRef[RawTxPublisher.Command] = {
      context.spawn(RawTxPublisher(nodeParams, bitcoinClient, watcher, loggingInfo), s"raw-tx-${loggingInfo.id}")
    }
    override def spawnReplaceableTxPublisher(context: ActorContext[Command], loggingInfo: TxPublishInfo): ActorRef[ReplaceableTxPublisher.Command] = {
      context.spawn(ReplaceableTxPublisher(nodeParams, bitcoinClient, watcher, loggingInfo), s"replaceable-tx-${loggingInfo.id}")
    }
    // @formatter:on
  }

  def apply(nodeParams: NodeParams, remoteNodeId: PublicKey, factory: ChildFactory): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))) {
          context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockCount](cbc => WrappedCurrentBlockCount(cbc.blockCount)))
          new TxPublisher(nodeParams, factory, context, timers).run(Map.empty, Seq.empty, ChannelInfo(remoteNodeId, None))
        }
      }
    }

}

private class TxPublisher(nodeParams: NodeParams, factory: TxPublisher.ChildFactory, context: ActorContext[TxPublisher.Command], timers: TimerScheduler[TxPublisher.Command]) {

  import TxPublisher._
  import nodeParams.onChainFeeConf.{feeEstimator, feeTargets}

  private val log = context.log

  // @formatter:off
  private sealed trait PublishAttempt {
    def id: UUID
    def cmd: PublishTx
  }
  private case class RawAttempt(id: UUID, cmd: PublishRawTx, actor: ActorRef[RawTxPublisher.Command]) extends PublishAttempt
  private case class ReplaceableAttempt(id: UUID, cmd: PublishReplaceableTx, feerate: FeeratePerKw, actor: ActorRef[ReplaceableTxPublisher.Command]) extends PublishAttempt
  // @formatter:on

  private def run(pending: Map[OutPoint, Seq[PublishAttempt]], retryNextBlock: Seq[PublishTx], channelInfo: ChannelInfo): Behavior[Command] = {
    Behaviors.receiveMessage {
      case cmd: PublishRawTx =>
        val attempts = pending.getOrElse(cmd.input, Seq.empty)
        val alreadyPublished = attempts.exists {
          case a: RawAttempt if a.cmd.tx.txid == cmd.tx.txid => true
          case _ => false
        }
        if (alreadyPublished) {
          log.info("not publishing {} txid={} spending {}:{}, publishing is already in progress", cmd.desc, cmd.tx.txid, cmd.input.txid, cmd.input.index)
          Behaviors.same
        } else {
          val publishId = UUID.randomUUID()
          log.info("publishing {} txid={} spending {}:{} with id={} ({} other attempts)", cmd.desc, cmd.tx.txid, cmd.input.txid, cmd.input.index, publishId, attempts.length)
          val actor = factory.spawnRawTxPublisher(context, TxPublishInfo(publishId, channelInfo.remoteNodeId, channelInfo.channelId_opt))
          actor ! RawTxPublisher.Publish(context.self, cmd)
          run(pending + (cmd.input -> attempts.appended(RawAttempt(publishId, cmd, actor))), retryNextBlock, channelInfo)
        }

      case cmd: PublishReplaceableTx =>
        val targetFeerate = feeEstimator.getFeeratePerKw(feeTargets.commitmentBlockTarget)
        val attempts = pending.getOrElse(cmd.input, Seq.empty)
        val alreadyPublished = attempts.exists {
          // If there is already an attempt at spending this outpoint with a higher feerate, there is no point in publishing again.
          case a: ReplaceableAttempt if targetFeerate <= a.feerate => true
          case _ => false
        }
        if (alreadyPublished) {
          log.info("not publishing replaceable {} spending {}:{} with feerate={}, publishing is already in progress", cmd.desc, cmd.input.txid, cmd.input.index, targetFeerate)
          Behaviors.same
        } else {
          val publishId = UUID.randomUUID()
          log.info("publishing replaceable {} spending {}:{} with id={} ({} other attempts)", cmd.desc, cmd.input.txid, cmd.input.index, publishId, attempts.length)
          val actor = factory.spawnReplaceableTxPublisher(context, TxPublishInfo(publishId, channelInfo.remoteNodeId, channelInfo.channelId_opt))
          actor ! ReplaceableTxPublisher.Publish(context.self, cmd, targetFeerate)
          run(pending + (cmd.input -> attempts.appended(ReplaceableAttempt(publishId, cmd, targetFeerate, actor))), retryNextBlock, channelInfo)
        }

      case result: PublishTxResult => result match {
        case TxConfirmed(cmd, _) =>
          pending.get(cmd.input).foreach(stopAttempts)
          run(pending - cmd.input, retryNextBlock, channelInfo)
        case TxRejected(id, cmd, reason) =>
          val (rejectedAttempts, remainingAttempts) = pending.getOrElse(cmd.input, Seq.empty).partition(_.id == id)
          stopAttempts(rejectedAttempts)
          val pending2 = if (remainingAttempts.isEmpty) pending - cmd.input else pending + (cmd.input -> remainingAttempts)
          reason match {
            case TxRejectedReason.WalletInputGone =>
              // Our transaction has been evicted from the mempool because it depended on an unconfirmed input that has
              // been replaced. We should be able to retry right now with new wallet inputs (no need to wait for a new
              // block).
              timers.startSingleTimer(cmd, (1 + Random.nextLong(nodeParams.maxTxPublishRetryDelay.toMillis)).millis)
              run(pending2, retryNextBlock, channelInfo)
            case TxRejectedReason.CouldNotFund =>
              // We don't have enough funds at the moment to afford our target feerate, but it may change once pending
              // transactions confirm, so we retry when a new block is found.
              run(pending2, retryNextBlock ++ rejectedAttempts.map(_.cmd), channelInfo)
            case TxRejectedReason.TxSkipped(retry) =>
              val retryNextBlock2 = if (retry) retryNextBlock ++ rejectedAttempts.map(_.cmd) else retryNextBlock
              run(pending2, retryNextBlock2, channelInfo)
            case TxRejectedReason.ConflictingTxUnconfirmed =>
              // Our transaction was replaced by a transaction that pays more fees, so it doesn't make sense to retry now.
              // We will automatically retry with a higher fee if we get close to the deadline.
              run(pending2, retryNextBlock, channelInfo)
            case TxRejectedReason.ConflictingTxConfirmed =>
              // Our transaction was double-spent by a competing transaction that has been confirmed, so it doesn't make
              // sense to retry.
              run(pending2, retryNextBlock, channelInfo)
            case TxRejectedReason.UnknownTxFailure =>
              // We don't automatically retry unknown failures, they should be investigated manually.
              run(pending2, retryNextBlock, channelInfo)
          }
      }

      case WrappedCurrentBlockCount(currentBlockCount) =>
        log.debug("retry publishing {} transactions at block {}", retryNextBlock.length, currentBlockCount)
        retryNextBlock.foreach(cmd => timers.startSingleTimer(cmd, (1 + Random.nextLong(nodeParams.maxTxPublishRetryDelay.toMillis)).millis))
        run(pending, Seq.empty, channelInfo)

      case SetChannelId(remoteNodeId, channelId) =>
        Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(channelId))) {
          run(pending, retryNextBlock, channelInfo.copy(remoteNodeId = remoteNodeId, channelId_opt = Some(channelId)))
        }
    }
  }

  private def stopAttempts(attempts: Seq[PublishAttempt]): Unit = attempts.foreach(stopAttempt)

  private def stopAttempt(attempt: PublishAttempt): Unit = attempt match {
    case RawAttempt(_, _, actor) => actor ! RawTxPublisher.Stop
    case ReplaceableAttempt(_, _, _, actor) => actor ! ReplaceableTxPublisher.Stop
  }

}
