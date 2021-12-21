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

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.{OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeratePerKw}
import fr.acinq.eclair.channel.publish.ReplaceableTxPrePublisher.{ClaimLocalAnchorWithWitnessData, ReplaceableTxWithWitnessData}
import fr.acinq.eclair.channel.publish.TxPublisher.{TxPublishLogContext, TxRejectedReason}

import scala.concurrent.ExecutionContext

/**
 * Created by t-bast on 10/06/2021.
 */

/**
 * This actor sets the fees, signs and publishes a transaction that can be RBF-ed.
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
  private case object UtxosUnlocked extends Command
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, watcher: ActorRef[ZmqWatcher.Command], loggingInfo: TxPublishLogContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(loggingInfo.mdc()) {
        new ReplaceableTxPublisher(nodeParams, bitcoinClient, watcher, context, loggingInfo).start()
      }
    }
  }

  def getFeerate(feeEstimator: FeeEstimator, deadline: Long, currentBlockHeight: Long): FeeratePerKw = {
    val remainingBlocks = deadline - currentBlockHeight
    val blockTarget = remainingBlocks match {
      // If our target is still very far in the future, no need to rush
      case t if t >= 144 => 144
      case t if t >= 72 => 72
      case t if t >= 36 => 36
      // However, if we get closer to the deadline, we start being more aggressive
      case t if t >= 18 => 12
      case t if t >= 12 => 6
      case t if t >= 2 => 2
      case _ => 1
    }
    feeEstimator.getFeeratePerKw(blockTarget)
  }

}

private class ReplaceableTxPublisher(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, watcher: ActorRef[ZmqWatcher.Command], context: ActorContext[ReplaceableTxPublisher.Command], loggingInfo: TxPublishLogContext)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

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
    val targetFeerate = getFeerate(nodeParams.onChainFeeConf.feeEstimator, cmd.deadline, nodeParams.currentBlockHeight)
    val txFunder = context.spawn(ReplaceableTxFunder(nodeParams, bitcoinClient, loggingInfo), "tx-funder")
    txFunder ! ReplaceableTxFunder.FundTransaction(context.messageAdapter[ReplaceableTxFunder.FundingResult](WrappedFundingResult), cmd, txWithWitnessData, targetFeerate)
    Behaviors.receiveMessagePartial {
      case WrappedFundingResult(result) =>
        result match {
          case success: ReplaceableTxFunder.TransactionReady => publish(replyTo, cmd, success.tx, success.fee)
          case ReplaceableTxFunder.FundingFailed(reason) => sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, reason))
        }
      case Stop =>
        // We don't stop right away, because the child actor may need to unlock utxos first.
        // If the child actor has already been terminated, this will emit the event instantly.
        context.watchWith(txFunder, UtxosUnlocked)
        txFunder ! ReplaceableTxFunder.Stop
        Behaviors.same
      case UtxosUnlocked =>
        Behaviors.stopped
    }
  }

  def publish(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, tx: Transaction, fee: Satoshi): Behavior[Command] = {
    val txMonitor = context.spawn(MempoolTxMonitor(nodeParams, bitcoinClient, loggingInfo), "mempool-tx-monitor")
    txMonitor ! MempoolTxMonitor.Publish(context.messageAdapter[MempoolTxMonitor.TxResult](WrappedTxResult), tx, cmd.input, cmd.desc, fee)
    Behaviors.receiveMessagePartial {
      case WrappedTxResult(MempoolTxMonitor.TxConfirmed) => sendResult(replyTo, TxPublisher.TxConfirmed(cmd, tx))
      case WrappedTxResult(MempoolTxMonitor.TxRejected(reason)) =>
        reason match {
          case TxRejectedReason.WalletInputGone =>
            // The transaction now has an unknown input from bitcoind's point of view, so it will keep it in the wallet in
            // case that input appears later in the mempool or the blockchain. In our case, we know it won't happen so we
            // abandon that transaction and will retry with a different set of inputs (if it still makes sense to publish).
            bitcoinClient.abandonTransaction(tx.txid)
          case _ => // nothing to do
        }
        replyTo ! TxPublisher.TxRejected(loggingInfo.id, cmd, reason)
        // We wait for our parent to stop us: when that happens we will unlock utxos.
        Behaviors.same
      case Stop =>
        txMonitor ! MempoolTxMonitor.Stop
        unlockAndStop(cmd.input, tx)
    }
  }

  def unlockAndStop(input: OutPoint, tx: Transaction): Behavior[Command] = {
    val toUnlock = tx.txIn.filterNot(_.outPoint == input).map(_.outPoint)
    log.debug("unlocking utxos={}", toUnlock.mkString(", "))
    context.pipeToSelf(bitcoinClient.unlockOutpoints(toUnlock))(_ => UtxosUnlocked)
    Behaviors.receiveMessagePartial {
      case UtxosUnlocked =>
        log.debug("utxos unlocked")
        Behaviors.stopped
      case Stop =>
        log.debug("waiting for utxos to be unlocked before stopping")
        Behaviors.same
    }
  }

  /** Use this function to send the result upstream and stop without stopping child actors. */
  def sendResult(replyTo: ActorRef[TxPublisher.PublishTxResult], result: TxPublisher.PublishTxResult): Behavior[Command] = {
    replyTo ! result
    Behaviors.receiveMessagePartial {
      case Stop => Behaviors.stopped
    }
  }

}

