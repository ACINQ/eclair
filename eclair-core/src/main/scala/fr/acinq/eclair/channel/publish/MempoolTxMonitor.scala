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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.publish.TxPublisher.{TxPublishContext, TxRejectedReason}
import fr.acinq.eclair.channel.{TransactionConfirmed, TransactionPublished}
import fr.acinq.eclair.{BlockHeight, NodeParams}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationLong
import scala.util.{Failure, Random, Success}

/**
 * This actor publishes a fully signed transaction and monitors its status.
 * It detects if the transaction is evicted from the mempool, and reports details about its status.
 */
object MempoolTxMonitor {

  // @formatter:off
  sealed trait Command
  case class Publish(replyTo: ActorRef[TxResult], tx: Transaction, input: OutPoint, desc: String, fee: Satoshi) extends Command
  private case object PublishOk extends Command
  private case class PublishFailed(reason: Throwable) extends Command
  private case class InputStatus(spentConfirmed: Boolean, spentUnconfirmed: Boolean) extends Command
  private case class CheckInputFailed(reason: Throwable) extends Command
  private case class TxConfirmations(confirmations: Int, blockHeight: BlockHeight) extends Command
  private case class ParentTxStatus(confirmed: Boolean, blockHeight: BlockHeight) extends Command
  private case object TxNotFound extends Command
  private case class GetTxConfirmationsFailed(reason: Throwable) extends Command
  final case class WrappedCurrentBlockHeight(currentBlockHeight: BlockHeight) extends Command
  private case class CheckTxConfirmations(currentBlockHeight: BlockHeight) extends Command
  // @formatter:on

  // Timer key to ensure we don't have multiple concurrent timers running.
  private case object CheckTxConfirmationsKey

  // @formatter:off
  /** Once the transaction is published, we notify the sender of its confirmation status at every block. */
  sealed trait TxResult
  sealed trait IntermediateTxResult extends TxResult
  /** The transaction is still unconfirmed and available in the mempool. */
  case class TxInMempool(txid: ByteVector32, blockHeight: BlockHeight, parentConfirmed: Boolean) extends IntermediateTxResult
  /** The transaction is confirmed, but hasn't reached min depth yet, we should wait for more confirmations. */
  case class TxRecentlyConfirmed(txid: ByteVector32, confirmations: Int) extends IntermediateTxResult
  sealed trait FinalTxResult extends TxResult
  /** The transaction is confirmed and has reached min depth. */
  case class TxDeeplyBuried(tx: Transaction) extends FinalTxResult
  /** The transaction has been evicted from the mempool. */
  case class TxRejected(txid: ByteVector32, reason: TxPublisher.TxRejectedReason) extends FinalTxResult
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, txPublishContext: TxPublishContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(txPublishContext.mdc()) {
          Behaviors.receiveMessagePartial {
            case cmd: Publish => new MempoolTxMonitor(nodeParams, cmd, bitcoinClient, txPublishContext, context, timers).publish()
          }
        }
      }
    }
  }

}

private class MempoolTxMonitor(nodeParams: NodeParams,
                               cmd: MempoolTxMonitor.Publish,
                               bitcoinClient: BitcoinCoreClient,
                               txPublishContext: TxPublishContext,
                               context: ActorContext[MempoolTxMonitor.Command],
                               timers: TimerScheduler[MempoolTxMonitor.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import MempoolTxMonitor._

  private val log = context.log

  def publish(): Behavior[Command] = {
    context.pipeToSelf(bitcoinClient.publishTransaction(cmd.tx)) {
      case Success(_) => PublishOk
      case Failure(reason) => PublishFailed(reason)
    }
    Behaviors.receiveMessagePartial {
      case PublishOk =>
        log.debug("txid={} was successfully published, waiting for confirmation...", cmd.tx.txid)
        waitForConfirmation()
      case PublishFailed(reason) if reason.getMessage.contains("rejecting replacement") =>
        log.info("could not publish tx: a conflicting mempool transaction is already in the mempool")
        sendFinalResult(TxRejected(cmd.tx.txid, TxRejectedReason.ConflictingTxUnconfirmed))
      case PublishFailed(reason) if reason.getMessage.contains("bad-txns-inputs-missingorspent") =>
        // This can only happen if one of our inputs is already spent by a confirmed transaction or doesn't exist (e.g.
        // unconfirmed wallet input that has been replaced).
        checkInputStatus(cmd.input)
        Behaviors.same
      case PublishFailed(reason) =>
        log.error("could not publish transaction: ", reason)
        sendFinalResult(TxRejected(cmd.tx.txid, TxRejectedReason.UnknownTxFailure))
      case status: InputStatus =>
        if (status.spentConfirmed) {
          log.info("could not publish tx: a conflicting transaction is already confirmed")
          sendFinalResult(TxRejected(cmd.tx.txid, TxRejectedReason.ConflictingTxConfirmed))
        } else if (status.spentUnconfirmed) {
          log.info("could not publish tx: a conflicting mempool transaction is already in the mempool")
          sendFinalResult(TxRejected(cmd.tx.txid, TxRejectedReason.ConflictingTxUnconfirmed))
        } else {
          log.info("could not publish tx: one of our inputs cannot be found")
          sendFinalResult(TxRejected(cmd.tx.txid, TxRejectedReason.InputGone))
        }
      case CheckInputFailed(reason) =>
        log.error("could not check input status: ", reason)
        sendFinalResult(TxRejected(cmd.tx.txid, TxRejectedReason.TxSkipped(retryNextBlock = true))) // we act as if the input is potentially still spendable
    }
  }

  def waitForConfirmation(): Behavior[Command] = {
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockHeight](cbc => WrappedCurrentBlockHeight(cbc.blockHeight)))
    context.system.eventStream ! EventStream.Publish(TransactionPublished(txPublishContext.channelId_opt.getOrElse(ByteVector32.Zeroes), txPublishContext.remoteNodeId, cmd.tx, cmd.fee, cmd.desc))
    Behaviors.receiveMessagePartial {
      case WrappedCurrentBlockHeight(currentBlockHeight) =>
        timers.startSingleTimer(CheckTxConfirmationsKey, CheckTxConfirmations(currentBlockHeight), (1 + Random.nextLong(nodeParams.channelConf.maxTxPublishRetryDelay.toMillis)).millis)
        Behaviors.same
      case CheckTxConfirmations(currentBlockHeight) =>
        context.pipeToSelf(bitcoinClient.getTxConfirmations(cmd.tx.txid)) {
          case Success(Some(confirmations)) => TxConfirmations(confirmations, currentBlockHeight)
          case Success(None) => TxNotFound
          case Failure(reason) => GetTxConfirmationsFailed(reason)
        }
        Behaviors.same
      case TxConfirmations(confirmations, currentBlockHeight) =>
        if (confirmations == 0) {
          context.pipeToSelf(bitcoinClient.getTxConfirmations(cmd.input.txid)) {
            case Success(parentConfirmations_opt) => ParentTxStatus(parentConfirmations_opt.exists(_ >= 1), currentBlockHeight)
            case Failure(reason) => GetTxConfirmationsFailed(reason)
          }
          Behaviors.same
        } else if (confirmations < nodeParams.channelConf.minDepthBlocks) {
          log.info("txid={} has {} confirmations, waiting to reach min depth", cmd.tx.txid, confirmations)
          cmd.replyTo ! TxRecentlyConfirmed(cmd.tx.txid, confirmations)
          Behaviors.same
        } else {
          log.info("txid={} has reached min depth", cmd.tx.txid)
          context.system.eventStream ! EventStream.Publish(TransactionConfirmed(txPublishContext.channelId_opt.getOrElse(ByteVector32.Zeroes), txPublishContext.remoteNodeId, cmd.tx))
          sendFinalResult(TxDeeplyBuried(cmd.tx))
        }
      case ParentTxStatus(confirmed, currentBlockHeight) =>
        cmd.replyTo ! TxInMempool(cmd.tx.txid, currentBlockHeight, confirmed)
        Behaviors.same
      case TxNotFound =>
        log.warn("txid={} has been evicted from the mempool", cmd.tx.txid)
        checkInputStatus(cmd.input)
        Behaviors.same
      case GetTxConfirmationsFailed(reason) =>
        log.error("could not get tx confirmations: ", reason)
        // We will retry when the next block is found.
        Behaviors.same
      case status: InputStatus =>
        if (status.spentConfirmed) {
          log.info("tx was evicted from the mempool: a conflicting transaction has been confirmed")
          sendFinalResult(TxRejected(cmd.tx.txid, TxRejectedReason.ConflictingTxConfirmed))
        } else if (status.spentUnconfirmed) {
          log.info("tx was evicted from the mempool: a conflicting transaction replaced it")
          sendFinalResult(TxRejected(cmd.tx.txid, TxRejectedReason.ConflictingTxUnconfirmed))
        } else {
          log.info("tx was evicted from the mempool: one of our inputs disappeared")
          sendFinalResult(TxRejected(cmd.tx.txid, TxRejectedReason.InputGone))
        }
      case CheckInputFailed(reason) =>
        log.error("could not check input status: ", reason)
        sendFinalResult(TxRejected(cmd.tx.txid, TxRejectedReason.TxSkipped(retryNextBlock = true)))
    }
  }

  def sendFinalResult(result: FinalTxResult): Behavior[Command] = {
    cmd.replyTo ! result
    Behaviors.stopped
  }

  private def checkInputStatus(input: OutPoint): Unit = {
    val checkInputTask = for {
      parentConfirmations <- bitcoinClient.getTxConfirmations(input.txid)
      spendableMempoolExcluded <- bitcoinClient.isTransactionOutputSpendable(input.txid, input.index.toInt, includeMempool = false)
      spendableMempoolIncluded <- bitcoinClient.isTransactionOutputSpendable(input.txid, input.index.toInt, includeMempool = true)
    } yield computeInputStatus(parentConfirmations, spendableMempoolExcluded, spendableMempoolIncluded)
    context.pipeToSelf(checkInputTask) {
      case Success(status) => status
      case Failure(reason) => CheckInputFailed(reason)
    }
  }

  private def computeInputStatus(parentConfirmations: Option[Int], spendableMempoolExcluded: Boolean, spendableMempoolIncluded: Boolean): InputStatus = {
    parentConfirmations match {
      case Some(0) => InputStatus(spentConfirmed = false, spentUnconfirmed = !spendableMempoolIncluded)
      case Some(_) => InputStatus(!spendableMempoolExcluded, spendableMempoolExcluded && !spendableMempoolIncluded)
      case None => InputStatus(spentConfirmed = false, spentUnconfirmed = false)
    }
  }

}