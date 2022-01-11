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
import fr.acinq.bitcoin.{OutPoint, Transaction}
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeratePerKw}
import fr.acinq.eclair.channel.publish.ReplaceableTxFunder.FundedTx
import fr.acinq.eclair.channel.publish.ReplaceableTxPrePublisher.{ClaimLocalAnchorWithWitnessData, ReplaceableTxWithWitnessData}
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishLogContext
import fr.acinq.eclair.{BlockHeight, NodeParams}

import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Success}

/**
 * Created by t-bast on 10/06/2021.
 */

/**
 * This actor sets the fees, signs and publishes a transaction that can be RBF-ed.
 * It regularly RBFs the transaction as we get closer to its confirmation target.
 * It waits for confirmation or failure before reporting back to the requesting actor.
 */
object ReplaceableTxPublisher {

  // @formatter:off
  sealed trait Command
  case class Publish(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx) extends Command
  case object Stop extends Command

  private case class WrappedPreconditionsResult(result: ReplaceableTxPrePublisher.PreconditionsResult) extends Command
  private case object TimeLocksOk extends Command
  private case class WrappedFundingResult(result: ReplaceableTxFunder.FundingResult) extends Command
  private case class WrappedTxResult(result: MempoolTxMonitor.TxResult) extends Command
  private case class WrappedCurrentBlockCount(currentBlockCount: Long) extends Command
  private case class CheckFee(currentBlockCount: Long) extends Command
  private case class BumpFee(targetFeerate: FeeratePerKw) extends Command
  private case object Stay extends Command
  private case object UnlockUtxos extends Command
  private case object UtxosUnlocked extends Command

  // Keys to ensure we don't have multiple concurrent timers running.
  private case object CheckFeeKey
  private case object CurrentBlockCountKey
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, watcher: ActorRef[ZmqWatcher.Command], loggingInfo: TxPublishLogContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(loggingInfo.mdc()) {
          new ReplaceableTxPublisher(nodeParams, bitcoinClient, watcher, context, timers, loggingInfo).start()
        }
      }
    }
  }

  def getFeerate(feeEstimator: FeeEstimator, confirmationTarget: BlockHeight, currentBlockHeight: BlockHeight): FeeratePerKw = {
    val remainingBlocks = (confirmationTarget - currentBlockHeight).toLong
    val blockTarget = remainingBlocks match {
      // If our target is still very far in the future, no need to rush
      case t if t >= 144 => 144
      case t if t >= 72 => 72
      case t if t >= 36 => 36
      // However, if we get closer to the target, we start being more aggressive
      case t if t >= 18 => 12
      case t if t >= 12 => 6
      case t if t >= 2 => 2
      case _ => 1
    }
    feeEstimator.getFeeratePerKw(blockTarget)
  }

}

private class ReplaceableTxPublisher(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, watcher: ActorRef[ZmqWatcher.Command], context: ActorContext[ReplaceableTxPublisher.Command], timers: TimerScheduler[ReplaceableTxPublisher.Command], loggingInfo: TxPublishLogContext)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import ReplaceableTxPublisher._

  private val log = context.log

  def start(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case Publish(replyTo, cmd) => checkPreconditions(replyTo, cmd)
      case Stop => Behaviors.stopped
    }
  }

  def checkPreconditions(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx): Behavior[Command] = {
    val prePublisher = context.spawn(ReplaceableTxPrePublisher(nodeParams, bitcoinClient, loggingInfo), "pre-publisher")
    prePublisher ! ReplaceableTxPrePublisher.CheckPreconditions(context.messageAdapter[ReplaceableTxPrePublisher.PreconditionsResult](WrappedPreconditionsResult), cmd)
    Behaviors.receiveMessagePartial {
      case WrappedPreconditionsResult(result) =>
        result match {
          case ReplaceableTxPrePublisher.PreconditionsOk(txWithWitnessData) => checkTimeLocks(replyTo, cmd, txWithWitnessData)
          case ReplaceableTxPrePublisher.PreconditionsFailed(reason) => sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, reason))
        }
      case Stop =>
        prePublisher ! ReplaceableTxPrePublisher.Stop
        Behaviors.stopped
    }
  }

  def checkTimeLocks(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, txWithWitnessData: ReplaceableTxWithWitnessData): Behavior[Command] = {
    txWithWitnessData match {
      // There are no time locks on anchor transactions, we can claim them right away.
      case _: ClaimLocalAnchorWithWitnessData => fund(replyTo, cmd, txWithWitnessData)
      case _ =>
        val timeLocksChecker = context.spawn(TxTimeLocksMonitor(nodeParams, watcher, loggingInfo), "time-locks-monitor")
        timeLocksChecker ! TxTimeLocksMonitor.CheckTx(context.messageAdapter[TxTimeLocksMonitor.TimeLocksOk](_ => TimeLocksOk), cmd.txInfo.tx, cmd.desc)
        Behaviors.receiveMessagePartial {
          case TimeLocksOk => fund(replyTo, cmd, txWithWitnessData)
          case Stop =>
            timeLocksChecker ! TxTimeLocksMonitor.Stop
            Behaviors.stopped
        }
    }
  }

  def fund(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, txWithWitnessData: ReplaceableTxWithWitnessData): Behavior[Command] = {
    val targetFeerate = getFeerate(nodeParams.onChainFeeConf.feeEstimator, cmd.confirmationTarget, BlockHeight(nodeParams.currentBlockHeight))
    val txFunder = context.spawn(ReplaceableTxFunder(nodeParams, bitcoinClient, loggingInfo), "tx-funder")
    txFunder ! ReplaceableTxFunder.FundTransaction(context.messageAdapter[ReplaceableTxFunder.FundingResult](WrappedFundingResult), cmd, Right(txWithWitnessData), targetFeerate)
    Behaviors.receiveMessagePartial {
      case WrappedFundingResult(result) =>
        result match {
          case success: ReplaceableTxFunder.TransactionReady => publish(replyTo, cmd, success.fundedTx)
          case ReplaceableTxFunder.FundingFailed(reason) => sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, reason))
        }
      case Stop =>
        // We can't stop right now, the child actor is currently funding the transaction and will send its result soon.
        // We just wait for the funding process to finish before stopping (in the next state).
        timers.startSingleTimer(Stop, 1 second)
        Behaviors.same
    }
  }

  def publish(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, tx: FundedTx): Behavior[Command] = {
    val txMonitor = context.spawn(MempoolTxMonitor(nodeParams, bitcoinClient, loggingInfo), "mempool-tx-monitor")
    txMonitor ! MempoolTxMonitor.Publish(context.messageAdapter[MempoolTxMonitor.TxResult](WrappedTxResult), tx.signedTx, cmd.input, cmd.desc, tx.fee)
    // We register to new blocks: if the transaction doesn't confirm, we will replace it with one that pays more fees.
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockCount](cbc => WrappedCurrentBlockCount(cbc.blockCount)))
    wait(replyTo, cmd, txMonitor, tx)
  }

  // Wait for our transaction to be confirmed or rejected from the mempool.
  // If we get close to the confirmation target and our transaction is stuck in the mempool, we will initiate an RBF attempt.
  def wait(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, txMonitor: ActorRef[MempoolTxMonitor.Command], tx: FundedTx): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedTxResult(MempoolTxMonitor.TxConfirmed(confirmedTx)) => sendResult(replyTo, TxPublisher.TxConfirmed(cmd, confirmedTx))
      case WrappedTxResult(MempoolTxMonitor.TxRejected(_, reason)) =>
        replyTo ! TxPublisher.TxRejected(loggingInfo.id, cmd, reason)
        // We wait for our parent to stop us: when that happens we will unlock utxos.
        Behaviors.same
      case WrappedCurrentBlockCount(currentBlockCount) =>
        // We avoid a herd effect whenever a new block is found.
        timers.startSingleTimer(CheckFeeKey, CheckFee(currentBlockCount), (1 + Random.nextLong(nodeParams.maxTxPublishRetryDelay.toMillis)).millis)
        Behaviors.same
      case CheckFee(currentBlockCount) =>
        val currentFeerate = getFeerate(nodeParams.onChainFeeConf.feeEstimator, cmd.confirmationTarget, BlockHeight(currentBlockCount))
        val targetFeerate_opt = if (cmd.confirmationTarget.toLong <= currentBlockCount + 6) {
          log.debug("{} confirmation target is close (in {} blocks): bumping fees", cmd.desc, cmd.confirmationTarget.toLong - currentBlockCount)
          // We make sure we increase the fees by at least 20% as we get close to the confirmation target.
          Some(currentFeerate.max(tx.feerate * 1.2))
        } else if (tx.feerate * 1.2 <= currentFeerate) {
          log.debug("{} confirmation target is in {} blocks: bumping fees", cmd.desc, cmd.confirmationTarget.toLong - currentBlockCount)
          Some(currentFeerate)
        } else {
          log.debug("{} confirmation target is in {} blocks: no need to bump fees", cmd.desc, cmd.confirmationTarget.toLong - currentBlockCount)
          None
        }
        targetFeerate_opt.foreach(targetFeerate => {
          // We check whether our currently published transaction is confirmed: if it is, it doesn't make sense to bump
          // the fee, we're just waiting for enough confirmations to report the result back.
          context.pipeToSelf(bitcoinClient.getTxConfirmations(tx.signedTx.txid)) {
            case Success(Some(confirmations)) if confirmations > 0 => Stay
            case _ => BumpFee(targetFeerate)
          }
        })
        Behaviors.same
      case BumpFee(targetFeerate) => fundReplacement(replyTo, cmd, targetFeerate, txMonitor, tx)
      case Stay => Behaviors.same
      case Stop =>
        txMonitor ! MempoolTxMonitor.Stop
        unlockAndStop(cmd.input, Seq(tx.signedTx))
    }
  }

  // Fund a replacement transaction because our previous attempt seems to be stuck in the mempool.
  def fundReplacement(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, targetFeerate: FeeratePerKw, previousTxMonitor: ActorRef[MempoolTxMonitor.Command], previousTx: FundedTx): Behavior[Command] = {
    log.info("bumping {} fees: previous feerate={}, next feerate={}", cmd.desc, previousTx.feerate, targetFeerate)
    val txFunder = context.spawn(ReplaceableTxFunder(nodeParams, bitcoinClient, loggingInfo), "tx-funder-rbf")
    txFunder ! ReplaceableTxFunder.FundTransaction(context.messageAdapter[ReplaceableTxFunder.FundingResult](WrappedFundingResult), cmd, Left(previousTx), targetFeerate)
    Behaviors.receiveMessagePartial {
      case WrappedFundingResult(result) =>
        result match {
          case success: ReplaceableTxFunder.TransactionReady => publishReplacement(replyTo, cmd, previousTx, previousTxMonitor, success.fundedTx)
          case ReplaceableTxFunder.FundingFailed(_) =>
            log.warn("could not fund {} replacement transaction (target feerate={})", cmd.desc, targetFeerate)
            wait(replyTo, cmd, previousTxMonitor, previousTx)
        }
      case txResult: WrappedTxResult =>
        // This is the result of the previous publishing attempt.
        // We don't need to handle it now that we're in the middle of funding, we can defer it to the next state.
        timers.startSingleTimer(txResult, 1 second)
        Behaviors.same
      case cbc: WrappedCurrentBlockCount =>
        timers.startSingleTimer(CurrentBlockCountKey, cbc, 1 second)
        Behaviors.same
      case Stop =>
        // We can't stop right away, because the child actor may need to unlock utxos first.
        // We just wait for the funding process to finish before stopping.
        timers.startSingleTimer(Stop, 1 second)
        Behaviors.same
    }
  }

  // Publish an RBF attempt. We then have two concurrent transactions: the previous one and the updated one.
  // Only one of them can be in the mempool, so we wait for the other to be rejected. Once that's done, we're back to a
  // situation where we have one transaction in the mempool and wait for it to confirm.
  def publishReplacement(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, previousTx: FundedTx, previousTxMonitor: ActorRef[MempoolTxMonitor.Command], bumpedTx: FundedTx): Behavior[Command] = {
    val txMonitor = context.spawn(MempoolTxMonitor(nodeParams, bitcoinClient, loggingInfo), s"mempool-tx-monitor-rbf-${bumpedTx.signedTx.txid}")
    txMonitor ! MempoolTxMonitor.Publish(context.messageAdapter[MempoolTxMonitor.TxResult](WrappedTxResult), bumpedTx.signedTx, cmd.input, cmd.desc, bumpedTx.fee)
    Behaviors.receiveMessagePartial {
      case WrappedTxResult(MempoolTxMonitor.TxConfirmed(confirmedTx)) =>
        // Since our transactions conflict, we should always receive a failure from the evicted transaction before one
        // of them confirms: this case should not happen, so we don't bother unlocking utxos.
        log.warn("{} was confirmed while we're publishing an RBF attempt", cmd.desc)
        sendResult(replyTo, TxPublisher.TxConfirmed(cmd, confirmedTx))
      case WrappedTxResult(MempoolTxMonitor.TxRejected(txid, _)) =>
        if (txid == bumpedTx.signedTx.txid) {
          log.warn("{} transaction paying more fees (txid={}) failed to replace previous transaction", cmd.desc, txid)
          cleanUpFailedTxAndWait(replyTo, cmd, bumpedTx.signedTx, previousTxMonitor, previousTx)
        } else {
          log.info("previous {} replaced by new transaction paying more fees (txid={})", cmd.desc, bumpedTx.signedTx.txid)
          cleanUpFailedTxAndWait(replyTo, cmd, previousTx.signedTx, txMonitor, bumpedTx)
        }
      case cbc: WrappedCurrentBlockCount =>
        timers.startSingleTimer(CurrentBlockCountKey, cbc, 1 second)
        Behaviors.same
      case Stop =>
        previousTxMonitor ! MempoolTxMonitor.Stop
        txMonitor ! MempoolTxMonitor.Stop
        // We don't know yet which transaction won, so we try abandoning both and unlocking their utxos.
        // One of the calls will fail (for the transaction that is in the mempool), but we will simply ignore that failure.
        unlockAndStop(cmd.input, Seq(previousTx.signedTx, bumpedTx.signedTx))
    }
  }

  // Clean up the failed transaction attempt. Once that's done, go back to the waiting state with the new transaction.
  def cleanUpFailedTxAndWait(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, failedTx: Transaction, txMonitor: ActorRef[MempoolTxMonitor.Command], mempoolTx: FundedTx): Behavior[Command] = {
    context.pipeToSelf(bitcoinClient.abandonTransaction(failedTx.txid))(_ => UnlockUtxos)
    Behaviors.receiveMessagePartial {
      case UnlockUtxos =>
        val toUnlock = failedTx.txIn.map(_.outPoint).toSet -- mempoolTx.signedTx.txIn.map(_.outPoint).toSet
        if (toUnlock.isEmpty) {
          context.self ! UtxosUnlocked
        } else {
          log.debug("unlocking utxos={}", toUnlock.mkString(", "))
          context.pipeToSelf(bitcoinClient.unlockOutpoints(toUnlock.toSeq))(_ => UtxosUnlocked)
        }
        Behaviors.same
      case UtxosUnlocked =>
        // Now that we've cleaned up the failed transaction, we can go back to waiting for the current mempool transaction
        // or bump it if it doesn't confirm fast enough either.
        wait(replyTo, cmd, txMonitor, mempoolTx)
      case txResult: WrappedTxResult =>
        // This is the result of the current mempool tx: we will handle this command once we're back in the waiting
        // state for this transaction.
        timers.startSingleTimer(txResult, 1 second)
        Behaviors.same
      case cbc: WrappedCurrentBlockCount =>
        timers.startSingleTimer(CurrentBlockCountKey, cbc, 1 second)
        Behaviors.same
      case Stop =>
        // We don't stop right away, because we're cleaning up the failed transaction.
        // This shouldn't take long so we'll handle this command once we're back in the waiting state.
        timers.startSingleTimer(Stop, 1 second)
        Behaviors.same
    }
  }

  def unlockAndStop(input: OutPoint, txs: Seq[Transaction]): Behavior[Command] = {
    // The bitcoind wallet will keep transactions around even when they can't be published (e.g. one of their inputs has
    // disappeared but bitcoind thinks it may reappear later), hoping that it will be able to automatically republish
    // them later. In our case this is unnecessary, we will publish ourselves, and we don't want to pollute the wallet
    // state with transactions that will never be valid, so we eagerly abandon every time.
    // If the transaction is in the mempool or confirmed, it will be a no-op.
    context.pipeToSelf(Future.traverse(txs)(tx => bitcoinClient.abandonTransaction(tx.txid)))(_ => UnlockUtxos)
    Behaviors.receiveMessagePartial {
      case UnlockUtxos =>
        val toUnlock = txs.flatMap(_.txIn).filterNot(_.outPoint == input).map(_.outPoint).toSet
        if (toUnlock.isEmpty) {
          context.self ! UtxosUnlocked
        } else {
          log.debug("unlocking utxos={}", toUnlock.mkString(", "))
          context.pipeToSelf(bitcoinClient.unlockOutpoints(toUnlock.toSeq))(_ => UtxosUnlocked)
        }
        Behaviors.same
      case UtxosUnlocked =>
        log.debug("utxos unlocked")
        Behaviors.stopped
      case WrappedCurrentBlockCount(_) =>
        log.debug("ignoring new block while stopping")
        Behaviors.same
      case Stop =>
        log.debug("waiting for utxos to be unlocked before stopping")
        Behaviors.same
    }
  }

  /** Use this function to send the result upstream and stop without stopping child actors. */
  def sendResult(replyTo: ActorRef[TxPublisher.PublishTxResult], result: TxPublisher.PublishTxResult): Behavior[Command] = {
    replyTo ! result
    Behaviors.receiveMessagePartial {
      case WrappedCurrentBlockCount(_) =>
        log.debug("ignoring new block while stopping")
        Behaviors.same
      case Stop => Behaviors.stopped
    }
  }

}

