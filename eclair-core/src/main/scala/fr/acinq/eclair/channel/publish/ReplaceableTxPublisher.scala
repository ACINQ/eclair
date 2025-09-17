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

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.{SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.fee.{ConfirmationTarget, FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel.publish.ReplaceableTxFunder.FundedTx
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
import fr.acinq.eclair.transactions.Transactions.ClaimAnchorTx
import fr.acinq.eclair.{BlockHeight, NodeParams}

import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success}

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
  case class UpdateConfirmationTarget(confirmationTarget: ConfirmationTarget) extends Command
  case object Stop extends Command

  private case class WrappedPreconditionsResult(result: ReplaceableTxPrePublisher.PreconditionsResult) extends Command
  private case object TimeLocksOk extends Command
  private case class CheckUtxosResult(isSafe: Boolean, currentBlockHeight: BlockHeight) extends Command
  private case class WrappedFundingResult(result: ReplaceableTxFunder.FundingResult) extends Command
  private case class WrappedTxResult(result: MempoolTxMonitor.TxResult) extends Command
  private case class BumpFee(targetFeerate: FeeratePerKw) extends Command
  private case object UtxosUnlocked extends Command
  // @formatter:on

  // Timer key to ensure we don't have multiple concurrent timers running.
  private case object BumpFeeKey

  def apply(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, txPublishContext: TxPublishContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(txPublishContext.mdc()) {
          Behaviors.receiveMessagePartial {
            case Publish(replyTo, cmd) => new ReplaceableTxPublisher(nodeParams, replyTo, cmd, bitcoinClient, context, timers, txPublishContext).checkPreconditions()
            case Stop => Behaviors.stopped
          }
        }
      }
    }
  }

  def getFeerate(feerates: FeeratesPerKw, confirmationTarget: ConfirmationTarget, currentBlockHeight: BlockHeight, hasEnoughSafeUtxos: Boolean): FeeratePerKw = {
    confirmationTarget match {
      case ConfirmationTarget.Absolute(confirmBefore) =>
        // If we have an absolute block height target, we take into account what the current height is and adjust the feerate
        val remainingBlocks = confirmBefore - currentBlockHeight
        if (hasEnoughSafeUtxos) {
          remainingBlocks match {
            // If our target is still very far in the future, no need to rush
            case t if t >= 36 => feerates.slow
            // However, if we get closer to the target, we start being more aggressive
            case t if t >= 12 => feerates.medium
            case t if t >= 2 => feerates.fast
            case _ => feerates.fastest
          }
        } else {
          // We don't have many safe utxos so we want the transaction to confirm quickly.
          if (remainingBlocks <= 1) {
            feerates.fastest
          } else {
            feerates.fast
          }
        }
      case ConfirmationTarget.Priority(priority) =>
        // If we have a priority target, then the current block height doesn't matter
        priority.getFeerate(feerates)
    }
  }

}

private class ReplaceableTxPublisher(nodeParams: NodeParams,
                                     replyTo: ActorRef[TxPublisher.PublishTxResult],
                                     cmd: TxPublisher.PublishReplaceableTx,
                                     bitcoinClient: BitcoinCoreClient,
                                     context: ActorContext[ReplaceableTxPublisher.Command],
                                     timers: TimerScheduler[ReplaceableTxPublisher.Command],
                                     txPublishContext: TxPublishContext)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import ReplaceableTxPublisher._

  private val log = context.log

  /** The confirmation target may be updated in some corner cases (e.g. if we learn a payment preimage after initiating a force-close). */
  private var confirmationTarget: ConfirmationTarget = cmd.confirmationTarget

  private def checkPreconditions(): Behavior[Command] = {
    val prePublisher = context.spawn(ReplaceableTxPrePublisher(nodeParams, bitcoinClient, txPublishContext), "pre-publisher")
    val concurrentCommitTxs = cmd.commitment.commitTxIds.txIds - cmd.commitTx.txid
    prePublisher ! ReplaceableTxPrePublisher.CheckPreconditions(context.messageAdapter[ReplaceableTxPrePublisher.PreconditionsResult](WrappedPreconditionsResult), cmd.txInfo, cmd.commitTx, concurrentCommitTxs)
    Behaviors.receiveMessagePartial {
      case WrappedPreconditionsResult(result) =>
        result match {
          case ReplaceableTxPrePublisher.PreconditionsOk => checkTimeLocks()
          case ReplaceableTxPrePublisher.PreconditionsFailed(reason) => sendResult(TxPublisher.TxRejected(txPublishContext.id, cmd, reason), None)
        }
      case UpdateConfirmationTarget(target) =>
        confirmationTarget = target
        Behaviors.same
      case Stop => Behaviors.stopped
    }
  }

  def checkTimeLocks(): Behavior[Command] = {
    cmd.txInfo match {
      // There are no time locks on anchor transactions, we can claim them right away.
      case _: ClaimAnchorTx => chooseFeerate()
      case _ =>
        val timeLocksChecker = context.spawn(TxTimeLocksMonitor(nodeParams, bitcoinClient, txPublishContext), "time-locks-monitor")
        timeLocksChecker ! TxTimeLocksMonitor.CheckTx(context.messageAdapter[TxTimeLocksMonitor.TimeLocksOk](_ => TimeLocksOk), cmd.txInfo.tx, cmd.desc)
        Behaviors.receiveMessagePartial {
          case TimeLocksOk => chooseFeerate()
          case UpdateConfirmationTarget(target) =>
            confirmationTarget = target
            Behaviors.same
          case Stop => Behaviors.stopped
        }
    }
  }

  private def chooseFeerate(): Behavior[Command] = {
    context.pipeToSelf(hasEnoughSafeUtxos(nodeParams.onChainFeeConf.safeUtxosThreshold)) {
      case Success(isSafe) => CheckUtxosResult(isSafe, nodeParams.currentBlockHeight)
      case Failure(_) => CheckUtxosResult(isSafe = false, nodeParams.currentBlockHeight) // if we can't check our utxos, we assume the worst
    }
    Behaviors.receiveMessagePartial {
      case CheckUtxosResult(isSafe, currentBlockHeight) =>
        val targetFeerate = getFeerate(nodeParams.currentBitcoinCoreFeerates, confirmationTarget, currentBlockHeight, isSafe)
        fund(targetFeerate)
      case UpdateConfirmationTarget(target) =>
        confirmationTarget = target
        Behaviors.same
      case Stop => Behaviors.stopped
    }
  }

  def fund(targetFeerate: FeeratePerKw): Behavior[Command] = {
    val txFunder = context.spawn(ReplaceableTxFunder(nodeParams, bitcoinClient, txPublishContext), "tx-funder")
    txFunder ! ReplaceableTxFunder.FundTransaction(context.messageAdapter[ReplaceableTxFunder.FundingResult](WrappedFundingResult), Right(cmd.txInfo), cmd.commitTx, cmd.commitment, targetFeerate)
    Behaviors.receiveMessagePartial {
      case WrappedFundingResult(result) =>
        result match {
          case ReplaceableTxFunder.TransactionReady(tx) =>
            confirmationTarget match {
              case ConfirmationTarget.Absolute(confirmBefore) => log.debug("publishing {} with confirmation target in {} blocks", cmd.desc, confirmBefore - nodeParams.currentBlockHeight)
              case ConfirmationTarget.Priority(priority) => log.debug("publishing {} with priority {}", cmd.desc, priority)
            }
            val txMonitor = context.spawn(MempoolTxMonitor(nodeParams, bitcoinClient, txPublishContext), s"mempool-tx-monitor-${tx.signedTx.txid}")
            val parentTx_opt = cmd.txInfo match {
              // Anchor output transactions are packaged with the corresponding commitment transaction.
              case _: ClaimAnchorTx => Some(cmd.commitTx)
              case _ => None
            }
            txMonitor ! MempoolTxMonitor.Publish(context.messageAdapter[MempoolTxMonitor.TxResult](WrappedTxResult), tx.signedTx, parentTx_opt, cmd.input, nodeParams.channelConf.minDepth, cmd.desc, tx.fee)
            wait(tx)
          case ReplaceableTxFunder.FundingFailed(reason) => sendResult(TxPublisher.TxRejected(txPublishContext.id, cmd, reason), None)
        }
      case UpdateConfirmationTarget(target) =>
        confirmationTarget = target
        Behaviors.same
      case Stop =>
        // We can't stop right now, the child actor is currently funding the transaction and will send its result soon.
        // We just wait for the funding process to finish before stopping (in the next state).
        timers.startSingleTimer(Stop, 1 second)
        Behaviors.same
    }
  }

  // Wait for our transaction to be confirmed or rejected from the mempool.
  // If we get close to the confirmation target and our transaction is stuck in the mempool, we will initiate an RBF attempt.
  private def wait(tx: FundedTx): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedTxResult(txResult) =>
        txResult match {
          case MempoolTxMonitor.TxInMempool(_, currentBlockHeight, parentConfirmed) =>
            val shouldRbf = cmd.txInfo match {
              // We only need to increase fees on the anchor tx if the commit tx isn't confirmed.
              case _: ClaimAnchorTx => !parentConfirmed
              case _ => true
            }
            if (shouldRbf) {
              context.pipeToSelf(hasEnoughSafeUtxos(nodeParams.onChainFeeConf.safeUtxosThreshold)) {
                case Success(isSafe) => CheckUtxosResult(isSafe, currentBlockHeight)
                case Failure(_) => CheckUtxosResult(isSafe = false, currentBlockHeight) // if we can't check our utxos, we assume the worst
              }
            }
            Behaviors.same
          case MempoolTxMonitor.TxRecentlyConfirmed(_, _) => Behaviors.same // just wait for the tx to be deeply buried
          case MempoolTxMonitor.TxDeeplyBuried(confirmedTx) => sendResult(TxPublisher.TxConfirmed(cmd, confirmedTx), None)
          case MempoolTxMonitor.TxRejected(_, reason) => sendResult(TxPublisher.TxRejected(txPublishContext.id, cmd, reason), Some(Seq(tx.signedTx)))
        }
      case CheckUtxosResult(isSafe, currentBlockHeight) =>
        // We make sure we increase the fees by at least 20% as we get closer to the confirmation target.
        val bumpRatio = 1.2
        val currentFeerate = getFeerate(nodeParams.currentBitcoinCoreFeerates, confirmationTarget, currentBlockHeight, isSafe)
        val targetFeerate_opt = confirmationTarget match {
          case ConfirmationTarget.Absolute(confirmBefore) =>
            if (confirmBefore <= currentBlockHeight + 6) {
              log.debug("{} confirmation target is close (in {} blocks): bumping fees", cmd.desc, confirmBefore - currentBlockHeight)
              Some(currentFeerate.max(tx.feerate * bumpRatio))
            } else if (tx.feerate * bumpRatio <= currentFeerate) {
              log.debug("{} confirmation target is in {} blocks: bumping fees", cmd.desc, confirmBefore - currentBlockHeight)
              Some(currentFeerate)
            } else {
              log.debug("{} confirmation target is in {} blocks: no need to bump fees", cmd.desc, confirmBefore - currentBlockHeight)
              None
            }
          case ConfirmationTarget.Priority(priority) =>
            if (tx.feerate * bumpRatio <= currentFeerate) {
              log.debug("{} priority {}: bumping fees", cmd.desc, priority)
              Some(currentFeerate)
            } else {
              log.debug("{} priority {}: no need to bump fees", cmd.desc, priority)
              None
            }
        }
        // We avoid a herd effect whenever we fee bump transactions.
        targetFeerate_opt.foreach(targetFeerate => timers.startSingleTimer(BumpFeeKey, BumpFee(targetFeerate), (1 + Random.nextLong(nodeParams.channelConf.maxTxPublishRetryDelay.toMillis)).millis))
        Behaviors.same
      case BumpFee(targetFeerate) => fundReplacement(targetFeerate, tx)
      case UpdateConfirmationTarget(target) =>
        confirmationTarget = target
        Behaviors.same
      case Stop => unlockAndStop(Seq(tx.signedTx))
    }
  }

  // Fund a replacement transaction because our previous attempt seems to be stuck in the mempool.
  private def fundReplacement(targetFeerate: FeeratePerKw, previousTx: FundedTx): Behavior[Command] = {
    log.info("bumping {} fees: previous feerate={}, next feerate={}", cmd.desc, previousTx.feerate, targetFeerate)
    val txFunder = context.spawn(ReplaceableTxFunder(nodeParams, bitcoinClient, txPublishContext), "tx-funder-rbf")
    txFunder ! ReplaceableTxFunder.FundTransaction(context.messageAdapter[ReplaceableTxFunder.FundingResult](WrappedFundingResult), Left(previousTx), cmd.commitTx, cmd.commitment, targetFeerate)
    Behaviors.receiveMessagePartial {
      case WrappedFundingResult(result) =>
        result match {
          case success: ReplaceableTxFunder.TransactionReady => publishReplacement(previousTx, success.fundedTx)
          case ReplaceableTxFunder.FundingFailed(_) =>
            log.warn("could not fund {} replacement transaction (target feerate={})", cmd.desc, targetFeerate)
            wait(previousTx)
        }
      case txResult: WrappedTxResult =>
        // This is the result of the previous publishing attempt.
        // We don't need to handle it now that we're in the middle of funding, we can defer it to the next state.
        timers.startSingleTimer(txResult, 1 second)
        Behaviors.same
      case UpdateConfirmationTarget(target) =>
        confirmationTarget = target
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
  private def publishReplacement(previousTx: FundedTx, bumpedTx: FundedTx): Behavior[Command] = {
    val txMonitor = context.spawn(MempoolTxMonitor(nodeParams, bitcoinClient, txPublishContext), s"mempool-tx-monitor-${bumpedTx.signedTx.txid}")
    val parentTx_opt = cmd.txInfo match {
      // Anchor output transactions are packaged with the corresponding commitment transaction.
      case _: ClaimAnchorTx => Some(cmd.commitTx)
      case _ => None
    }
    txMonitor ! MempoolTxMonitor.Publish(context.messageAdapter[MempoolTxMonitor.TxResult](WrappedTxResult), bumpedTx.signedTx, parentTx_opt, cmd.input, nodeParams.channelConf.minDepth, cmd.desc, bumpedTx.fee)
    Behaviors.receiveMessagePartial {
      case WrappedTxResult(txResult) =>
        txResult match {
          case MempoolTxMonitor.TxDeeplyBuried(confirmedTx) =>
            // Since our transactions conflict, we should always receive a failure from the evicted transaction before
            // one of them confirms: this case should not happen, so we don't bother unlocking utxos.
            log.warn("{} was confirmed while we're publishing an RBF attempt", cmd.desc)
            sendResult(TxPublisher.TxConfirmed(cmd, confirmedTx), None)
          case MempoolTxMonitor.TxRejected(txid, _) =>
            if (txid == bumpedTx.signedTx.txid) {
              log.warn("{} transaction paying more fees (txid={}) failed to replace previous transaction", cmd.desc, txid)
              cleanUpFailedTxAndWait(bumpedTx.signedTx, previousTx)
            } else {
              log.info("previous {} replaced by new transaction paying more fees (txid={})", cmd.desc, bumpedTx.signedTx.txid)
              cleanUpFailedTxAndWait(previousTx.signedTx, bumpedTx)
            }
          case _: MempoolTxMonitor.IntermediateTxResult =>
            // If a new block is found before our replacement transaction reaches the MempoolTxMonitor, we may receive
            // an intermediate result for the previous transaction. We want to handle this event once we're back in the
            // waiting state, because we may want to fee-bump even more aggressively if we're getting too close to the
            // confirmation target.
            timers.startSingleTimer(WrappedTxResult(txResult), 1 second)
            Behaviors.same
        }
      case UpdateConfirmationTarget(target) =>
        confirmationTarget = target
        Behaviors.same
      case Stop =>
        // We don't know yet which transaction won, so we try abandoning both and unlocking their utxos.
        // One of the calls will fail (for the transaction that is in the mempool), but we will simply ignore that failure.
        unlockAndStop(Seq(previousTx.signedTx, bumpedTx.signedTx))
    }
  }

  // Clean up the failed transaction attempt. Once that's done, go back to the waiting state with the new transaction.
  private def cleanUpFailedTxAndWait(failedTx: Transaction, mempoolTx: FundedTx): Behavior[Command] = {
    // Note that we don't need to filter inputs from the previous transaction, they have already been unlocked when the
    // previous transaction was published (but the bitcoin wallet ensures that they won't be double-spent).
    val toUnlock = failedTx.txIn.map(_.outPoint).toSet
    if (toUnlock.isEmpty) {
      context.self ! UtxosUnlocked
    } else {
      log.debug("unlocking utxos={}", toUnlock.mkString(", "))
      context.pipeToSelf(bitcoinClient.unlockOutpoints(toUnlock.toSeq))(_ => UtxosUnlocked)
    }
    Behaviors.receiveMessagePartial {
      case UtxosUnlocked =>
        // Now that we've cleaned up the failed transaction, we can go back to waiting for the current mempool transaction
        // or bump it if it doesn't confirm fast enough either.
        wait(mempoolTx)
      case txResult: WrappedTxResult =>
        // This is the result of the current mempool tx: we will handle this command once we're back in the waiting
        // state for this transaction.
        timers.startSingleTimer(txResult, 1 second)
        Behaviors.same
      case UpdateConfirmationTarget(target) =>
        confirmationTarget = target
        Behaviors.same
      case Stop =>
        // We don't stop right away, because we're cleaning up the failed transaction.
        // This shouldn't take long so we'll handle this command once we're back in the waiting state.
        timers.startSingleTimer(Stop, 1 second)
        Behaviors.same
    }
  }

  def sendResult(result: TxPublisher.PublishTxResult, toUnlock_opt: Option[Seq[Transaction]]): Behavior[Command] = {
    replyTo ! result
    toUnlock_opt match {
      case Some(txs) => unlockAndStop(txs)
      case None => stop()
    }
  }

  def unlockAndStop(txs: Seq[Transaction]): Behavior[Command] = {
    // Note that we unlock utxos but we don't abandon failed transactions:
    //  - if they were successfully published:
    //    - the utxos have automatically been unlocked, so the call to unlock is a (safe) no-op
    //    - there is still a risk that transactions may confirm later, so it's unsafe to abandon them
    //  - if they failed to be published:
    //    - we must unlock the utxos, otherwise they would stay locked forever
    //    - abandoning the transaction would be a no-op, as it was never added to our wallet
    val toUnlock = txs.flatMap(_.txIn).map(_.outPoint).toSet
    if (toUnlock.isEmpty) {
      context.self ! UtxosUnlocked
    } else {
      log.debug("unlocking utxos={}", toUnlock.mkString(", "))
      context.pipeToSelf(bitcoinClient.unlockOutpoints(toUnlock.toSeq))(_ => UtxosUnlocked)
    }
    Behaviors.receiveMessagePartial {
      case UtxosUnlocked =>
        log.debug("utxos unlocked")
        Behaviors.stopped
      case _: WrappedTxResult =>
        log.debug("ignoring transaction result while stopping")
        Behaviors.same
      case _: UpdateConfirmationTarget =>
        log.debug("ignoring confirmation target update while stopping")
        Behaviors.same
      case Stop =>
        log.debug("waiting for utxos to be unlocked before stopping")
        Behaviors.same
    }
  }

  def stop(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case Stop => Behaviors.stopped
    }
  }

  /** If we don't have a lot of safe utxos left, we will use an aggressive feerate to ensure our utxos aren't locked for too long. */
  private def hasEnoughSafeUtxos(threshold: Int): Future[Boolean] = {
    bitcoinClient.listUnspent().map(_.count(utxo => utxo.safe && utxo.amount >= 10_000.sat) >= threshold)
  }

}

