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
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.FullCommitment
import fr.acinq.eclair.transactions.Transactions.{ReplaceableTransactionWithInputInfo, TransactionWithInputInfo}
import fr.acinq.eclair.{BlockHeight, Logs, NodeParams}

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
  //                             |   block targets    |   | create child actor   |    TxPublish    |
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
   *
   * @param fee the fee that we're actually paying: it must be set to the mining fee, unless our peer is paying it (in
   *            which case it must be set to zero here).
   */
  case class PublishFinalTx(tx: Transaction, input: OutPoint, desc: String, fee: Satoshi, parentTx_opt: Option[ByteVector32]) extends PublishTx
  object PublishFinalTx {
    def apply(txInfo: TransactionWithInputInfo, fee: Satoshi, parentTx_opt: Option[ByteVector32]): PublishFinalTx = PublishFinalTx(txInfo.tx, txInfo.input.outPoint, txInfo.desc, fee, parentTx_opt)
  }
  /** Publish an unsigned transaction that can be RBF-ed. */
  case class PublishReplaceableTx(txInfo: ReplaceableTransactionWithInputInfo, commitment: FullCommitment) extends PublishTx {
    override def input: OutPoint = txInfo.input.outPoint
    override def desc: String = txInfo.desc
  }

  sealed trait PublishTxResult extends Command { def cmd: PublishTx }
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
    /** The transaction was published but then evicted from the mempool, because one of its inputs disappeared (e.g. unconfirmed output of a transaction that was replaced). */
    case object InputGone extends TxRejectedReason
    /** A conflicting transaction spending the same input has been confirmed. */
    case object ConflictingTxConfirmed extends TxRejectedReason
    /** A conflicting transaction spending the same input is in the mempool and we failed to replace it. */
    case object ConflictingTxUnconfirmed extends TxRejectedReason
    /** The transaction wasn't published because it's unnecessary or we're missing information. */
    case class TxSkipped(retryNextBlock: Boolean) extends TxRejectedReason
    /** Unrecoverable failure. */
    case object UnknownTxFailure extends TxRejectedReason
  }

  case class WrappedCurrentBlockHeight(currentBlockHeight: BlockHeight) extends Command
  case class SetChannelId(remoteNodeId: PublicKey, channelId: ByteVector32) extends Command
  // @formatter:on

  // @formatter:off
  case class ChannelContext(remoteNodeId: PublicKey, channelId_opt: Option[ByteVector32]) {
    def mdc(): Map[String, String] = Logs.mdc(remoteNodeId_opt = Some(remoteNodeId), channelId_opt = channelId_opt)
  }
  case class TxPublishContext(id: UUID, remoteNodeId: PublicKey, channelId_opt: Option[ByteVector32]) {
    def mdc(): Map[String, String] = Logs.mdc(txPublishId_opt = Some(id), remoteNodeId_opt = Some(remoteNodeId), channelId_opt = channelId_opt)
  }
  // @formatter:on

  trait ChildFactory {
    // @formatter:off
    def spawnFinalTxPublisher(context: ActorContext[TxPublisher.Command], txPublishContext: TxPublishContext): ActorRef[FinalTxPublisher.Command]
    def spawnReplaceableTxPublisher(context: ActorContext[TxPublisher.Command], txPublishContext: TxPublishContext): ActorRef[ReplaceableTxPublisher.Command]
    // @formatter:on
  }

  case class SimpleChildFactory(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, watcher: ActorRef[ZmqWatcher.Command]) extends ChildFactory {
    // @formatter:off
    override def spawnFinalTxPublisher(context: ActorContext[TxPublisher.Command], txPublishContext: TxPublishContext): ActorRef[FinalTxPublisher.Command] = {
      context.spawn(FinalTxPublisher(nodeParams, bitcoinClient, watcher, txPublishContext), s"final-tx-${txPublishContext.id}")
    }
    override def spawnReplaceableTxPublisher(context: ActorContext[Command], txPublishContext: TxPublishContext): ActorRef[ReplaceableTxPublisher.Command] = {
      context.spawn(ReplaceableTxPublisher(nodeParams, bitcoinClient, watcher, txPublishContext), s"replaceable-tx-${txPublishContext.id}")
    }
    // @formatter:on
  }

  // @formatter:off
  sealed trait PublishAttempt {
    def id: UUID
    def cmd: PublishTx
  }
  case class FinalAttempt(id: UUID, cmd: PublishFinalTx, actor: ActorRef[FinalTxPublisher.Command]) extends PublishAttempt
  case class ReplaceableAttempt(id: UUID, cmd: PublishReplaceableTx, confirmBefore: BlockHeight, actor: ActorRef[ReplaceableTxPublisher.Command]) extends PublishAttempt
  // @formatter:on

  /**
   * There can be multiple attempts to spend the same [[OutPoint]].
   * Only one of them will work, but we keep track of all of them.
   * There is only one [[ReplaceableAttempt]] because we will replace the existing attempt instead of creating a new one.
   */
  case class PublishAttempts(finalAttempts: Seq[FinalAttempt], replaceableAttempt_opt: Option[ReplaceableAttempt]) {
    val attempts: Seq[PublishAttempt] = finalAttempts ++ replaceableAttempt_opt.toSeq
    val count: Int = attempts.length

    def add(finalAttempt: FinalAttempt): PublishAttempts = copy(finalAttempts = finalAttempts :+ finalAttempt)

    def remove(id: UUID): (Seq[PublishAttempt], PublishAttempts) = {
      val (removed, remaining) = finalAttempts.partition(_.id == id)
      replaceableAttempt_opt match {
        case Some(replaceableAttempt) if replaceableAttempt.id == id => (removed :+ replaceableAttempt, PublishAttempts(remaining, None))
        case _ => (removed, PublishAttempts(remaining, replaceableAttempt_opt))
      }
    }

    def isEmpty: Boolean = replaceableAttempt_opt.isEmpty && finalAttempts.isEmpty
  }

  object PublishAttempts {
    val empty: PublishAttempts = PublishAttempts(Nil, None)
  }

  def apply(nodeParams: NodeParams, remoteNodeId: PublicKey, factory: ChildFactory): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))) {
          context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockHeight](cbc => WrappedCurrentBlockHeight(cbc.blockHeight)))
          new TxPublisher(nodeParams, factory, context, timers).run(Map.empty, Seq.empty, ChannelContext(remoteNodeId, None))
        }
      }
    }

}

private class TxPublisher(nodeParams: NodeParams, factory: TxPublisher.ChildFactory, context: ActorContext[TxPublisher.Command], timers: TimerScheduler[TxPublisher.Command]) {

  import TxPublisher._

  private val log = context.log

  private def run(pending: Map[OutPoint, PublishAttempts], retryNextBlock: Seq[PublishTx], channelContext: ChannelContext): Behavior[Command] = {
    Behaviors.receiveMessage {
      case cmd: PublishFinalTx =>
        val attempts = pending.getOrElse(cmd.input, PublishAttempts.empty)
        val alreadyPublished = attempts.finalAttempts.exists(_.cmd.tx.txid == cmd.tx.txid)
        if (alreadyPublished) {
          log.debug("not publishing {} txid={} spending {}:{}, publishing is already in progress", cmd.desc, cmd.tx.txid, cmd.input.txid, cmd.input.index)
          Behaviors.same
        } else {
          val publishId = UUID.randomUUID()
          log.info("publishing {} txid={} spending {}:{} with id={} ({} other attempts)", cmd.desc, cmd.tx.txid, cmd.input.txid, cmd.input.index, publishId, attempts.count)
          val actor = factory.spawnFinalTxPublisher(context, TxPublishContext(publishId, channelContext.remoteNodeId, channelContext.channelId_opt))
          actor ! FinalTxPublisher.Publish(context.self, cmd)
          run(pending + (cmd.input -> attempts.add(FinalAttempt(publishId, cmd, actor))), retryNextBlock, channelContext)
        }

      case cmd: PublishReplaceableTx =>
        val proposedConfirmationTarget = cmd.txInfo.confirmBefore
        val attempts = pending.getOrElse(cmd.input, PublishAttempts.empty)
        attempts.replaceableAttempt_opt match {
          case Some(currentAttempt) =>
            if (currentAttempt.cmd.txInfo.tx.txOut.headOption.map(_.publicKeyScript) != cmd.txInfo.tx.txOut.headOption.map(_.publicKeyScript)) {
              log.error("replaceable {} sends to a different address than the previous attempt, this should not happen: proposed={}, previous={}", currentAttempt.cmd.desc, cmd.txInfo, currentAttempt.cmd.txInfo)
            }
            val currentConfirmationTarget = currentAttempt.confirmBefore
            if (currentConfirmationTarget <= proposedConfirmationTarget) {
              log.debug("not publishing replaceable {} spending {}:{} with confirmation target={}, publishing is already in progress with confirmation target={}", cmd.desc, cmd.input.txid, cmd.input.index, proposedConfirmationTarget, currentConfirmationTarget)
              Behaviors.same
            } else {
              log.info("replaceable {} spending {}:{} has new confirmation target={} (previous={})", cmd.desc, cmd.input.txid, cmd.input.index, proposedConfirmationTarget, currentConfirmationTarget)
              currentAttempt.actor ! ReplaceableTxPublisher.UpdateConfirmationTarget(proposedConfirmationTarget)
              val attempts2 = attempts.copy(replaceableAttempt_opt = Some(currentAttempt.copy(confirmBefore = proposedConfirmationTarget)))
              run(pending + (cmd.input -> attempts2), retryNextBlock, channelContext)
            }
          case None =>
            val publishId = UUID.randomUUID()
            log.info("publishing replaceable {} spending {}:{} with id={} ({} other attempts)", cmd.desc, cmd.input.txid, cmd.input.index, publishId, attempts.count)
            val actor = factory.spawnReplaceableTxPublisher(context, TxPublishContext(publishId, channelContext.remoteNodeId, channelContext.channelId_opt))
            actor ! ReplaceableTxPublisher.Publish(context.self, cmd)
            val attempts2 = attempts.copy(replaceableAttempt_opt = Some(ReplaceableAttempt(publishId, cmd, proposedConfirmationTarget, actor)))
            run(pending + (cmd.input -> attempts2), retryNextBlock, channelContext)
        }

      case result: PublishTxResult => result match {
        case TxConfirmed(cmd, _) =>
          pending.get(cmd.input).foreach(a => stopAttempts(a.attempts))
          run(pending - cmd.input, retryNextBlock, channelContext)
        case TxRejected(id, cmd, reason) =>
          val (rejectedAttempts, remainingAttempts) = pending.getOrElse(cmd.input, PublishAttempts.empty).remove(id)
          stopAttempts(rejectedAttempts)
          val pending2 = if (remainingAttempts.isEmpty) pending - cmd.input else pending + (cmd.input -> remainingAttempts)
          reason match {
            case TxRejectedReason.InputGone =>
              // Our transaction has been evicted from the mempool because it depended on an unconfirmed input that has
              // been replaced.
              cmd match {
                case _: PublishReplaceableTx =>
                  // We should be able to retry right now with new wallet inputs (no need to wait for a new block).
                  timers.startSingleTimer(cmd, (1 + Random.nextLong(nodeParams.channelConf.maxTxPublishRetryDelay.toMillis)).millis)
                  run(pending2, retryNextBlock, channelContext)
                case _: PublishFinalTx =>
                  // The transaction cannot be replaced, so there is no point in retrying immediately, let's wait until
                  // the next block to see if our input comes back to the mempool.
                  run(pending2, retryNextBlock ++ rejectedAttempts.map(_.cmd), channelContext)
              }
            case TxRejectedReason.CouldNotFund =>
              // We don't have enough funds at the moment to afford our target feerate, but it may change once pending
              // transactions confirm, so we retry when a new block is found.
              run(pending2, retryNextBlock ++ rejectedAttempts.map(_.cmd), channelContext)
            case TxRejectedReason.TxSkipped(retry) =>
              val retryNextBlock2 = if (retry) retryNextBlock ++ rejectedAttempts.map(_.cmd) else retryNextBlock
              run(pending2, retryNextBlock2, channelContext)
            case TxRejectedReason.ConflictingTxUnconfirmed =>
              cmd match {
                case _: PublishFinalTx =>
                  // Our transaction is not replaceable, and the mempool contains a transaction that pays more fees, so
                  // it doesn't make sense to retry, we will keep getting rejected.
                  run(pending2, retryNextBlock, channelContext)
                case _: PublishReplaceableTx =>
                  // The mempool contains a transaction that pays more fees, but as we get closer to the confirmation
                  // target, we will try to publish with higher fees, so if the conflicting transaction doesn't confirm,
                  // we should be able to replace it before we reach the confirmation target.
                  run(pending2, retryNextBlock ++ rejectedAttempts.map(_.cmd), channelContext)
              }
            case TxRejectedReason.ConflictingTxConfirmed =>
              // Our transaction was double-spent by a competing transaction that has been confirmed, so it doesn't make
              // sense to retry.
              run(pending2, retryNextBlock, channelContext)
            case TxRejectedReason.UnknownTxFailure =>
              // We don't automatically retry unknown failures, they should be investigated manually.
              run(pending2, retryNextBlock, channelContext)
          }
      }

      case WrappedCurrentBlockHeight(currentBlockHeight) =>
        if (retryNextBlock.nonEmpty) {
          log.info("{} transactions are still pending at block {}, retrying {} transactions that previously failed", pending.size, currentBlockHeight, retryNextBlock.length)
          retryNextBlock.foreach(cmd => timers.startSingleTimer(cmd, (1 + Random.nextLong(nodeParams.channelConf.maxTxPublishRetryDelay.toMillis)).millis))
        }
        run(pending, Seq.empty, channelContext)

      case SetChannelId(remoteNodeId, channelId) =>
        val channelInfo2 = channelContext.copy(remoteNodeId = remoteNodeId, channelId_opt = Some(channelId))
        Behaviors.withMdc(channelInfo2.mdc()) {
          run(pending, retryNextBlock, channelInfo2)
        }
    }
  }

  private def stopAttempts(attempts: Seq[PublishAttempt]): Unit = attempts.foreach(stopAttempt)

  private def stopAttempt(attempt: PublishAttempt): Unit = attempt match {
    case FinalAttempt(_, _, actor) => actor ! FinalTxPublisher.Stop
    case ReplaceableAttempt(_, _, _, actor) => actor ! ReplaceableTxPublisher.Stop
  }

}
