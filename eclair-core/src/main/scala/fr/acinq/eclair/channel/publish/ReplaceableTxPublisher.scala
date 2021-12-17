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
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, OutPoint, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.FundTransactionOptions
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeratePerKw}
import fr.acinq.eclair.channel.publish.TxPublisher.{TxPublishLogContext, TxRejectedReason}
import fr.acinq.eclair.channel.publish.TxTimeLocksMonitor.CheckTx
import fr.acinq.eclair.channel.{Commitments, HtlcTxAndRemoteSig}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.UpdateFulfillHtlc

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

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
  private case object TimeLocksOk extends Command
  private case object CommitTxAlreadyConfirmed extends RuntimeException with Command
  private case object RemoteCommitTxPublished extends RuntimeException with Command
  private case object LocalCommitTxConfirmed extends Command
  private case object RemoteCommitTxConfirmed extends Command
  private case object PreconditionsOk extends Command
  private case class FundingFailed(reason: Throwable) extends Command
  private case class SignFundedTx(tx: ReplaceableTransactionWithInputInfo, fee: Satoshi) extends Command
  private case class PublishSignedTx(tx: Transaction) extends Command
  private case class WrappedTxResult(result: MempoolTxMonitor.TxResult) extends Command
  private case class UnknownFailure(reason: Throwable) extends Command
  private case object UtxosUnlocked extends Command
  case object Stop extends Command
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

  /**
   * Adjust the amount of the change output of an anchor tx to match our target feerate.
   * We need this because fundrawtransaction doesn't allow us to leave non-wallet inputs, so we have to add them
   * afterwards which may bring the resulting feerate below our target.
   */
  def adjustAnchorOutputChange(unsignedTx: ClaimLocalAnchorOutputTx, commitTx: Transaction, amountIn: Satoshi, currentFeerate: FeeratePerKw, targetFeerate: FeeratePerKw, dustLimit: Satoshi): (ClaimLocalAnchorOutputTx, Satoshi) = {
    require(unsignedTx.tx.txOut.size == 1, "funded transaction should have a single change output")
    // We take into account witness weight and adjust the fee to match our desired feerate.
    val dummySignedClaimAnchorTx = addSigs(unsignedTx, PlaceHolderSig)
    // NB: we assume that our bitcoind wallet uses only P2WPKH inputs when funding txs.
    val estimatedWeight = commitTx.weight() + dummySignedClaimAnchorTx.tx.weight() + claimP2WPKHOutputWitnessWeight * (dummySignedClaimAnchorTx.tx.txIn.size - 1)
    val targetFee = weight2fee(targetFeerate, estimatedWeight) - weight2fee(currentFeerate, commitTx.weight())
    val amountOut = dustLimit.max(amountIn - targetFee)
    val updatedAnchorTx = unsignedTx.copy(tx = unsignedTx.tx.copy(txOut = unsignedTx.tx.txOut.head.copy(amount = amountOut) :: Nil))
    val fee = amountIn - updatedAnchorTx.tx.txOut.map(_.amount).sum
    (updatedAnchorTx, fee)
  }

  /**
   * Adjust the change output of an htlc tx to match our target feerate.
   * We need this because fundrawtransaction doesn't allow us to leave non-wallet inputs, so we have to add them
   * afterwards which may bring the resulting feerate below our target.
   */
  def adjustHtlcTxChange(unsignedTx: HtlcTx, amountIn: Satoshi, targetFeerate: FeeratePerKw, commitments: Commitments): (HtlcTx, Satoshi) = {
    require(unsignedTx.tx.txOut.size <= 2, "funded transaction should have at most one change output")
    val dummySignedTx = unsignedTx match {
      case tx: HtlcSuccessTx => addSigs(tx, PlaceHolderSig, PlaceHolderSig, ByteVector32.Zeroes, commitments.commitmentFormat)
      case tx: HtlcTimeoutTx => addSigs(tx, PlaceHolderSig, PlaceHolderSig, commitments.commitmentFormat)
    }
    // We adjust the change output to obtain the targeted feerate.
    val estimatedWeight = dummySignedTx.tx.weight() + claimP2WPKHOutputWitnessWeight * (dummySignedTx.tx.txIn.size - 1)
    val targetFee = weight2fee(targetFeerate, estimatedWeight)
    val changeAmount = amountIn - dummySignedTx.tx.txOut.head.amount - targetFee
    val updatedHtlcTx = if (dummySignedTx.tx.txOut.length == 2 && changeAmount >= commitments.localParams.dustLimit) {
      unsignedTx match {
        case htlcSuccess: HtlcSuccessTx => htlcSuccess.copy(tx = htlcSuccess.tx.copy(txOut = Seq(htlcSuccess.tx.txOut.head, htlcSuccess.tx.txOut(1).copy(amount = changeAmount))))
        case htlcTimeout: HtlcTimeoutTx => htlcTimeout.copy(tx = htlcTimeout.tx.copy(txOut = Seq(htlcTimeout.tx.txOut.head, htlcTimeout.tx.txOut(1).copy(amount = changeAmount))))
      }
    } else {
      unsignedTx match {
        case htlcSuccess: HtlcSuccessTx => htlcSuccess.copy(tx = htlcSuccess.tx.copy(txOut = Seq(htlcSuccess.tx.txOut.head)))
        case htlcTimeout: HtlcTimeoutTx => htlcTimeout.copy(tx = htlcTimeout.tx.copy(txOut = Seq(htlcTimeout.tx.txOut.head)))
      }
    }
    val fee = amountIn - updatedHtlcTx.tx.txOut.map(_.amount).sum
    (updatedHtlcTx, fee)
  }

  def adjustClaimHtlcTxOutput(unsignedTx: ClaimHtlcTx, targetFeerate: FeeratePerKw, commitments: Commitments): Either[TxGenerationSkipped, (ClaimHtlcTx, Satoshi)] = {
    require(unsignedTx.tx.txIn.size == 1, "claim-htlc transaction should have a single input")
    require(unsignedTx.tx.txOut.size == 1, "claim-htlc transaction should have a single output")
    val dummySignedTx = unsignedTx match {
      case tx: ClaimHtlcSuccessTx => addSigs(tx, PlaceHolderSig, ByteVector32.Zeroes)
      case tx: ClaimHtlcTimeoutTx => addSigs(tx, PlaceHolderSig)
      case tx: LegacyClaimHtlcSuccessTx => tx
    }
    val targetFee = weight2fee(targetFeerate, dummySignedTx.tx.weight())
    val outputAmount = unsignedTx.input.txOut.amount - targetFee
    if (outputAmount < commitments.localParams.dustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val updatedClaimHtlcTx = unsignedTx match {
        case claimHtlcSuccess: ClaimHtlcSuccessTx => claimHtlcSuccess.copy(tx = claimHtlcSuccess.tx.copy(txOut = Seq(claimHtlcSuccess.tx.txOut.head.copy(amount = outputAmount))))
        case claimHtlcTimeout: ClaimHtlcTimeoutTx => claimHtlcTimeout.copy(tx = claimHtlcTimeout.tx.copy(txOut = Seq(claimHtlcTimeout.tx.txOut.head.copy(amount = outputAmount))))
        case legacyClaimHtlcSuccess: LegacyClaimHtlcSuccessTx => legacyClaimHtlcSuccess
      }
      Right(updatedClaimHtlcTx, targetFee)
    }
  }

  sealed trait HtlcTxAndWitnessData {
    // @formatter:off
    def txInfo: HtlcTx
    def updateTx(tx: Transaction): HtlcTxAndWitnessData
    def addSigs(localSig: ByteVector64, commitmentFormat: CommitmentFormat): HtlcTx
    // @formatter:on
  }

  object HtlcTxAndWitnessData {

    case class HtlcSuccess(txInfo: HtlcSuccessTx, remoteSig: ByteVector64, preimage: ByteVector32) extends HtlcTxAndWitnessData {
      // @formatter:off
      override def updateTx(tx: Transaction): HtlcTxAndWitnessData = copy(txInfo = txInfo.copy(tx = tx))
      override def addSigs(localSig: ByteVector64, commitmentFormat: CommitmentFormat): HtlcTx = Transactions.addSigs(txInfo, localSig, remoteSig, preimage, commitmentFormat)
      // @formatter:on
    }

    case class HtlcTimeout(txInfo: HtlcTimeoutTx, remoteSig: ByteVector64) extends HtlcTxAndWitnessData {
      // @formatter:off
      override def updateTx(tx: Transaction): HtlcTxAndWitnessData = copy(txInfo = txInfo.copy(tx = tx))
      override def addSigs(localSig: ByteVector64, commitmentFormat: CommitmentFormat): HtlcTx = Transactions.addSigs(txInfo, localSig, remoteSig, commitmentFormat)
      // @formatter:on
    }

    def apply(txInfo: HtlcTx, commitments: Commitments): Option[HtlcTxAndWitnessData] = {
      txInfo match {
        case tx: HtlcSuccessTx =>
          commitments.localChanges.all.collectFirst {
            case u: UpdateFulfillHtlc if Crypto.sha256(u.paymentPreimage) == tx.paymentHash => u.paymentPreimage
          }.flatMap(preimage => {
            commitments.localCommit.htlcTxsAndRemoteSigs.collectFirst {
              case HtlcTxAndRemoteSig(HtlcSuccessTx(input, _, _, _), remoteSig) if input.outPoint == tx.input.outPoint => HtlcSuccess(tx, remoteSig, preimage)
            }
          })
        case tx: HtlcTimeoutTx =>
          commitments.localCommit.htlcTxsAndRemoteSigs.collectFirst {
            case HtlcTxAndRemoteSig(HtlcTimeoutTx(input, _, _), remoteSig) if input.outPoint == tx.input.outPoint => HtlcTimeout(tx, remoteSig)
          }
      }
    }

  }

}

private class ReplaceableTxPublisher(nodeParams: NodeParams,
                                     bitcoinClient: BitcoinCoreClient,
                                     watcher: ActorRef[ZmqWatcher.Command],
                                     context: ActorContext[ReplaceableTxPublisher.Command],
                                     timers: TimerScheduler[ReplaceableTxPublisher.Command],
                                     loggingInfo: TxPublishLogContext)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import ReplaceableTxPublisher._
  import nodeParams.{channelKeyManager => keyManager}

  private val log = context.log

  def start(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case Publish(replyTo, cmd) =>
        cmd.txInfo match {
          case _: ClaimLocalAnchorOutputTx => checkAnchorPreconditions(replyTo, cmd)
          case htlcTx: HtlcTx => checkHtlcPreconditions(replyTo, cmd, htlcTx)
          case claimHtlcTx: ClaimHtlcTx => checkClaimHtlcPreconditions(replyTo, cmd, claimHtlcTx)
        }
      case Stop => Behaviors.stopped
    }
  }

  def checkAnchorPreconditions(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx): Behavior[Command] = {
    // We verify that:
    //  - our commit is not confirmed (if it is, no need to claim our anchor)
    //  - their commit is not confirmed (if it is, no need to claim our anchor either)
    //  - our commit tx is in the mempool (otherwise we can't claim our anchor)
    val commitTx = cmd.commitments.fullySignedLocalCommitTx(nodeParams.channelKeyManager).tx
    val fundingOutpoint = cmd.commitments.commitInput.outPoint
    context.pipeToSelf(bitcoinClient.isTransactionOutputSpendable(fundingOutpoint.txid, fundingOutpoint.index.toInt, includeMempool = false).flatMap {
      case false => Future.failed(CommitTxAlreadyConfirmed)
      case true =>
        // We must ensure our local commit tx is in the mempool before publishing the anchor transaction.
        // If it's already published, this call will be a no-op.
        bitcoinClient.publishTransaction(commitTx)
    }) {
      case Success(_) => PreconditionsOk
      case Failure(CommitTxAlreadyConfirmed) => CommitTxAlreadyConfirmed
      case Failure(reason) if reason.getMessage.contains("rejecting replacement") => RemoteCommitTxPublished
      case Failure(reason) => UnknownFailure(reason)
    }
    Behaviors.receiveMessagePartial {
      case PreconditionsOk =>
        val targetFeerate = getFeerate(nodeParams.onChainFeeConf.feeEstimator, cmd.deadline, nodeParams.currentBlockHeight)
        val commitFeerate = cmd.commitments.localCommit.spec.commitTxFeerate
        if (targetFeerate <= commitFeerate) {
          log.info("skipping {}: commit feerate is high enough (feerate={})", cmd.desc, commitFeerate)
          // We set retry = true in case the on-chain feerate rises before the commit tx is confirmed: if that happens we'll
          // want to claim our anchor to raise the feerate of the commit tx and get it confirmed faster.
          sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true)))
        } else {
          fund(replyTo, cmd, targetFeerate)
        }
      case CommitTxAlreadyConfirmed =>
        log.debug("commit tx is already confirmed, no need to claim our anchor")
        sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false)))
      case RemoteCommitTxPublished =>
        log.warn("cannot publish commit tx: there is a conflicting tx in the mempool")
        // We retry until that conflicting commit tx is confirmed or we're able to publish our local commit tx.
        sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true)))
      case UnknownFailure(reason) =>
        log.error(s"could not check ${cmd.desc} preconditions", reason)
        sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, TxPublisher.TxRejectedReason.UnknownTxFailure))
      case Stop => Behaviors.stopped
    }
  }

  def checkHtlcPreconditions(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, htlcTx: HtlcTx): Behavior[Command] = {
    // We verify that:
    //  - their commit is not confirmed: if it is, there is no need to publish our HTLC transactions
    context.pipeToSelf(bitcoinClient.getTxConfirmations(cmd.commitments.remoteCommit.txid)) {
      case Success(Some(depth)) if depth >= nodeParams.minDepthBlocks => RemoteCommitTxConfirmed
      case Success(_) => PreconditionsOk
      case Failure(_) => PreconditionsOk // if our checks fail, we don't want it to prevent us from publishing HTLC transactions
    }
    Behaviors.receiveMessagePartial {
      case PreconditionsOk =>
        HtlcTxAndWitnessData(htlcTx, cmd.commitments) match {
          case Some(txWithWitnessData) => checkTimeLocks(replyTo, cmd, txWithWitnessData)
          case None =>
            log.error("witness data not found for htlcId={}, skipping...", htlcTx.htlcId)
            sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false)))
        }
      case RemoteCommitTxConfirmed =>
        log.warn("cannot publish {}: remote commit has been confirmed", cmd.desc)
        sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, TxPublisher.TxRejectedReason.ConflictingTxConfirmed))
      case Stop => Behaviors.stopped
    }
  }

  def checkClaimHtlcPreconditions(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, claimHtlcTx: ClaimHtlcTx): Behavior[Command] = {
    // We verify that:
    //  - our commit is not confirmed: if it is, there is no need to publish our claim-HTLC transactions
    context.pipeToSelf(bitcoinClient.getTxConfirmations(cmd.commitments.localCommit.commitTxAndRemoteSig.commitTx.tx.txid)) {
      case Success(Some(depth)) if depth >= nodeParams.minDepthBlocks => LocalCommitTxConfirmed
      case Success(_) => PreconditionsOk
      case Failure(_) => PreconditionsOk // if our checks fail, we don't want it to prevent us from publishing claim-HTLC transactions
    }
    Behaviors.receiveMessagePartial {
      case PreconditionsOk => checkTimeLocks(replyTo, cmd, claimHtlcTx)
      case LocalCommitTxConfirmed =>
        log.warn("cannot publish {}: local commit has been confirmed", cmd.desc)
        sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, TxPublisher.TxRejectedReason.ConflictingTxConfirmed))
      case Stop => Behaviors.stopped
    }
  }

  def checkTimeLocks(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, htlcTxWithWitnessData: HtlcTxAndWitnessData): Behavior[Command] = {
    val timeLocksChecker = context.spawn(TxTimeLocksMonitor(nodeParams, watcher, loggingInfo), "time-locks-monitor")
    timeLocksChecker ! CheckTx(context.messageAdapter[TxTimeLocksMonitor.TimeLocksOk](_ => TimeLocksOk), cmd.txInfo.tx, cmd.desc)
    Behaviors.receiveMessagePartial {
      case TimeLocksOk =>
        val targetFeerate = getFeerate(nodeParams.onChainFeeConf.feeEstimator, cmd.deadline, nodeParams.currentBlockHeight)
        val htlcFeerate = cmd.commitments.localCommit.spec.htlcTxFeerate(cmd.commitments.commitmentFormat)
        if (targetFeerate <= htlcFeerate) {
          val channelKeyPath = keyManager.keyPath(cmd.commitments.localParams, cmd.commitments.channelConfig)
          val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, cmd.commitments.localCommit.index)
          val localHtlcBasepoint = keyManager.htlcPoint(channelKeyPath)
          val localSig = keyManager.sign(htlcTxWithWitnessData.txInfo, localHtlcBasepoint, localPerCommitmentPoint, TxOwner.Local, cmd.commitments.commitmentFormat)
          val signedHtlcTx = htlcTxWithWitnessData.addSigs(localSig, cmd.commitments.commitmentFormat)
          log.info("publishing {} without adding inputs: txid={}", cmd.desc, signedHtlcTx.tx.txid)
          publish(replyTo, cmd, signedHtlcTx.tx, signedHtlcTx.fee)
        } else {
          fund(replyTo, cmd, targetFeerate)
        }
      case Stop =>
        timeLocksChecker ! TxTimeLocksMonitor.Stop
        Behaviors.stopped
    }
  }

  def checkTimeLocks(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, claimHtlcTx: ClaimHtlcTx): Behavior[Command] = {
    val timeLocksChecker = context.spawn(TxTimeLocksMonitor(nodeParams, watcher, loggingInfo), "time-locks-monitor")
    timeLocksChecker ! CheckTx(context.messageAdapter[TxTimeLocksMonitor.TimeLocksOk](_ => TimeLocksOk), cmd.txInfo.tx, cmd.desc)
    Behaviors.receiveMessagePartial {
      case TimeLocksOk =>
        val targetFeerate = getFeerate(nodeParams.onChainFeeConf.feeEstimator, cmd.deadline, nodeParams.currentBlockHeight)
        adjustClaimHtlcTxOutput(claimHtlcTx, targetFeerate, cmd.commitments) match {
          case Left(reason) =>
            // The HTLC isn't economical to claim at the current feerate, but if the feerate goes down, we may want to claim it later.
            log.warn("cannot publish {}: {}", cmd.desc, reason)
            sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true)))
          case Right((updatedClaimHtlcTx, fee)) =>
            val channelKeyPath = keyManager.keyPath(cmd.commitments.localParams, cmd.commitments.channelConfig)
            val sig = keyManager.sign(updatedClaimHtlcTx, keyManager.htlcPoint(channelKeyPath), cmd.commitments.remoteCommit.remotePerCommitmentPoint, TxOwner.Local, cmd.commitments.commitmentFormat)
            updatedClaimHtlcTx match {
              case claimHtlcSuccess: LegacyClaimHtlcSuccessTx =>
                // The payment hash has been added to claim-htlc-success in https://github.com/ACINQ/eclair/pull/2101
                // Some transactions made with older versions of eclair may not set it correctly, in which case we simply
                // publish the transaction as initially signed.
                log.warn("payment hash not set for htlcId={}, publishing original transaction", claimHtlcSuccess.htlcId)
                publish(replyTo, cmd, cmd.txInfo.tx, cmd.txInfo.fee)
              case claimHtlcSuccess: ClaimHtlcSuccessTx =>
                val preimage_opt = cmd.commitments.localChanges.all.collectFirst {
                  case u: UpdateFulfillHtlc if Crypto.sha256(u.paymentPreimage) == claimHtlcSuccess.paymentHash => u.paymentPreimage
                }
                preimage_opt match {
                  case Some(preimage) =>
                    val signedClaimHtlcTx = addSigs(claimHtlcSuccess, sig, preimage)
                    publish(replyTo, cmd, signedClaimHtlcTx.tx, fee)
                  case None =>
                    log.error("preimage not found for htlcId={}, skipping...", claimHtlcSuccess.htlcId)
                    sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = false)))
                }
              case claimHtlcTimeout: ClaimHtlcTimeoutTx =>
                val signedClaimHtlcTx = addSigs(claimHtlcTimeout, sig)
                publish(replyTo, cmd, signedClaimHtlcTx.tx, fee)
            }
        }
      case Stop =>
        timeLocksChecker ! TxTimeLocksMonitor.Stop
        Behaviors.stopped
    }
  }

  def fund(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, targetFeerate: FeeratePerKw): Behavior[Command] = {
    context.pipeToSelf(addInputs(cmd.txInfo, targetFeerate, cmd.commitments)) {
      case Success((fundedTx, fee)) => SignFundedTx(fundedTx, fee)
      case Failure(reason) => FundingFailed(reason)
    }
    Behaviors.receiveMessagePartial {
      case SignFundedTx(fundedTx, fee) =>
        log.info("added {} wallet input(s) and {} wallet output(s) to {}", fundedTx.tx.txIn.length - 1, fundedTx.tx.txOut.length - 1, cmd.desc)
        sign(replyTo, cmd, fundedTx, fee)
      case FundingFailed(reason) =>
        if (reason.getMessage.contains("Insufficient funds")) {
          log.warn("cannot add inputs to {}: {}", cmd.desc, reason.getMessage)
        } else {
          log.error("cannot add inputs to {}: {}", cmd.desc, reason)
        }
        sendResult(replyTo, TxPublisher.TxRejected(loggingInfo.id, cmd, TxPublisher.TxRejectedReason.CouldNotFund))
      case Stop =>
        // We've asked bitcoind to lock utxos, so we can't stop right now without unlocking them.
        // Since we don't know yet what utxos have been locked, we defer the message.
        timers.startSingleTimer(Stop, 1 second)
        Behaviors.same
    }
  }

  def sign(replyTo: ActorRef[TxPublisher.PublishTxResult], cmd: TxPublisher.PublishReplaceableTx, fundedTx: ReplaceableTransactionWithInputInfo, fee: Satoshi): Behavior[Command] = {
    fundedTx match {
      case claimAnchorTx: ClaimLocalAnchorOutputTx =>
        val claimAnchorSig = keyManager.sign(claimAnchorTx, keyManager.fundingPublicKey(cmd.commitments.localParams.fundingKeyPath), TxOwner.Local, cmd.commitments.commitmentFormat)
        val signedClaimAnchorTx = addSigs(claimAnchorTx, claimAnchorSig)
        val commitInfo = BitcoinCoreClient.PreviousTx(signedClaimAnchorTx.input, signedClaimAnchorTx.tx.txIn.head.witness)
        context.pipeToSelf(bitcoinClient.signTransaction(signedClaimAnchorTx.tx, Seq(commitInfo))) {
          case Success(signedTx) => PublishSignedTx(signedTx.tx)
          case Failure(reason) => UnknownFailure(reason)
        }
      case htlcTx: HtlcTx =>
        // NB: we've already checked witness data in the precondition phase. Witness data extraction should be done
        // earlier by the channel to remove this duplication.
        val txWithWitnessData = HtlcTxAndWitnessData(htlcTx, cmd.commitments).get
        val channelKeyPath = keyManager.keyPath(cmd.commitments.localParams, cmd.commitments.channelConfig)
        val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, cmd.commitments.localCommit.index)
        val localHtlcBasepoint = keyManager.htlcPoint(channelKeyPath)
        val localSig = keyManager.sign(htlcTx, localHtlcBasepoint, localPerCommitmentPoint, TxOwner.Local, cmd.commitments.commitmentFormat)
        val signedHtlcTx = txWithWitnessData.addSigs(localSig, cmd.commitments.commitmentFormat)
        val inputInfo = BitcoinCoreClient.PreviousTx(signedHtlcTx.input, signedHtlcTx.tx.txIn.head.witness)
        context.pipeToSelf(bitcoinClient.signTransaction(signedHtlcTx.tx, Seq(inputInfo), allowIncomplete = true).map(signTxResponse => {
          // NB: bitcoind versions older than 0.21.1 messes up the witness stack for our htlc input, so we need to restore it.
          // See https://github.com/bitcoin/bitcoin/issues/21151
          signedHtlcTx.tx.copy(txIn = signedHtlcTx.tx.txIn.head +: signTxResponse.tx.txIn.tail)
        })) {
          case Success(signedTx) => PublishSignedTx(signedTx)
          case Failure(reason) => UnknownFailure(reason)
        }
      case _: ClaimHtlcTx => log.error("claim-htlc-tx should not use external inputs")
    }
    Behaviors.receiveMessagePartial {
      case PublishSignedTx(signedTx) => publish(replyTo, cmd, signedTx, fee)
      case UnknownFailure(reason) =>
        log.error("cannot sign {}: {}", cmd.desc, reason)
        replyTo ! TxPublisher.TxRejected(loggingInfo.id, cmd, TxPublisher.TxRejectedReason.UnknownTxFailure)
        // We wait for our parent to stop us: when that happens we will unlock utxos.
        Behaviors.same
      case Stop => unlockAndStop(cmd.input, fundedTx.tx)
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

  def sendResult(replyTo: ActorRef[TxPublisher.PublishTxResult], result: TxPublisher.PublishTxResult): Behavior[Command] = {
    replyTo ! result
    Behaviors.receiveMessagePartial {
      case Stop => Behaviors.stopped
    }
  }

  private def addInputs(txInfo: ReplaceableTransactionWithInputInfo, targetFeerate: FeeratePerKw, commitments: Commitments): Future[(ReplaceableTransactionWithInputInfo, Satoshi)] = {
    txInfo match {
      case anchorTx: ClaimLocalAnchorOutputTx => addInputs(anchorTx, targetFeerate, commitments)
      case htlcTx: HtlcTx => addInputs(htlcTx, targetFeerate, commitments)
      case _: ClaimHtlcTx => Future.failed(new RuntimeException("claim-htlc-tx should not use external inputs"))
    }
  }

  private def addInputs(txInfo: ClaimLocalAnchorOutputTx, targetFeerate: FeeratePerKw, commitments: Commitments): Future[(ClaimLocalAnchorOutputTx, Satoshi)] = {
    val dustLimit = commitments.localParams.dustLimit
    val commitFeerate = commitments.localCommit.spec.commitTxFeerate
    val commitTx = commitments.fullySignedLocalCommitTx(nodeParams.channelKeyManager).tx
    // We want the feerate of the package (commit tx + tx spending anchor) to equal targetFeerate.
    // Thus we have: anchorFeerate = targetFeerate + (weight-commit-tx / weight-anchor-tx) * (targetFeerate - commitTxFeerate)
    // If we use the smallest weight possible for the anchor tx, the feerate we use will thus be greater than what we want,
    // and we can adjust it afterwards by raising the change output amount.
    val anchorFeerate = targetFeerate + FeeratePerKw(targetFeerate.feerate - commitFeerate.feerate) * commitTx.weight() / claimAnchorOutputMinWeight
    // NB: fundrawtransaction requires at least one output, and may add at most one additional change output.
    // Since the purpose of this transaction is just to do a CPFP, the resulting tx should have a single change output
    // (note that bitcoind doesn't let us publish a transaction with no outputs).
    // To work around these limitations, we start with a dummy output and later merge that dummy output with the optional
    // change output added by bitcoind.
    // NB: fundrawtransaction doesn't support non-wallet inputs, so we have to remove our anchor input and re-add it later.
    // That means bitcoind will not take our anchor input's weight into account when adding inputs to set the fee.
    // That's ok, we can increase the fee later by decreasing the output amount. But we need to ensure we'll have enough
    // to cover the weight of our anchor input, which is why we set it to the following value.
    val dummyChangeAmount = weight2fee(anchorFeerate, claimAnchorOutputMinWeight) + dustLimit
    val txNotFunded = Transaction(2, Nil, TxOut(dummyChangeAmount, Script.pay2wpkh(PlaceHolderPubKey)) :: Nil, 0)
    bitcoinClient.fundTransaction(txNotFunded, FundTransactionOptions(anchorFeerate, lockUtxos = true)).flatMap(fundTxResponse => {
      // We merge the outputs if there's more than one.
      fundTxResponse.changePosition match {
        case Some(changePos) =>
          val changeOutput = fundTxResponse.tx.txOut(changePos)
          val txSingleOutput = fundTxResponse.tx.copy(txOut = Seq(changeOutput.copy(amount = changeOutput.amount + dummyChangeAmount)))
          Future.successful(fundTxResponse.copy(tx = txSingleOutput))
        case None =>
          bitcoinClient.getChangeAddress().map(pubkeyHash => {
            val txSingleOutput = fundTxResponse.tx.copy(txOut = Seq(TxOut(dummyChangeAmount, Script.pay2wpkh(pubkeyHash))))
            fundTxResponse.copy(tx = txSingleOutput)
          })
      }
    }).map(fundTxResponse => {
      require(fundTxResponse.tx.txOut.size == 1, "funded transaction should have a single change output")
      // NB: we insert the anchor input in the *first* position because our signing helpers only sign input #0.
      val unsignedTx = txInfo.copy(tx = fundTxResponse.tx.copy(txIn = txInfo.tx.txIn.head +: fundTxResponse.tx.txIn))
      adjustAnchorOutputChange(unsignedTx, commitTx, fundTxResponse.amountIn + AnchorOutputsCommitmentFormat.anchorAmount, commitFeerate, targetFeerate, dustLimit)
    })
  }

  private def addInputs(txInfo: HtlcTx, targetFeerate: FeeratePerKw, commitments: Commitments): Future[(HtlcTx, Satoshi)] = {
    // NB: fundrawtransaction doesn't support non-wallet inputs, so we clear the input and re-add it later.
    val txNotFunded = txInfo.tx.copy(txIn = Nil, txOut = txInfo.tx.txOut.head.copy(amount = commitments.localParams.dustLimit) :: Nil)
    val htlcTxWeight = txInfo match {
      case _: HtlcSuccessTx => commitments.commitmentFormat.htlcSuccessWeight
      case _: HtlcTimeoutTx => commitments.commitmentFormat.htlcTimeoutWeight
    }
    // We want the feerate of our final HTLC tx to equal targetFeerate. However, we removed the HTLC input from what we
    // send to fundrawtransaction, so bitcoind will not know the total weight of the final tx. In order to make up for
    // this difference, we need to tell bitcoind to target a higher feerate that takes into account the weight of the
    // input we removed.
    // That feerate will satisfy the following equality:
    // feerate * weight_seen_by_bitcoind = target_feerate * (weight_seen_by_bitcoind + htlc_input_weight)
    // So: feerate = target_feerate * (1 + htlc_input_weight / weight_seen_by_bitcoind)
    // Because bitcoind will add at least one P2WPKH input, weight_seen_by_bitcoind >= htlc_tx_weight + p2wpkh_weight
    // Thus: feerate <= target_feerate * (1 + htlc_input_weight / (htlc_tx_weight + p2wpkh_weight))
    // NB: we don't take into account the fee paid by our HTLC input: we will take it into account when we adjust the
    // change output amount (unless bitcoind didn't add any change output, in that case we will overpay the fee slightly).
    val weightRatio = 1.0 + (htlcInputMaxWeight.toDouble / (htlcTxWeight + claimP2WPKHOutputWeight))
    bitcoinClient.fundTransaction(txNotFunded, FundTransactionOptions(targetFeerate * weightRatio, lockUtxos = true, changePosition = Some(1))).map(fundTxResponse => {
      // We add the HTLC input (from the commit tx) and restore the HTLC output.
      // NB: we can't modify them because they are signed by our peer (with SIGHASH_SINGLE | SIGHASH_ANYONECANPAY).
      val txWithHtlcInput = fundTxResponse.tx.copy(
        txIn = txInfo.tx.txIn ++ fundTxResponse.tx.txIn,
        txOut = txInfo.tx.txOut ++ fundTxResponse.tx.txOut.tail
      )
      val unsignedTx = txInfo match {
        case htlcSuccess: HtlcSuccessTx => htlcSuccess.copy(tx = txWithHtlcInput)
        case htlcTimeout: HtlcTimeoutTx => htlcTimeout.copy(tx = txWithHtlcInput)
      }
      adjustHtlcTxChange(unsignedTx, fundTxResponse.amountIn + unsignedTx.input.txOut.amount, targetFeerate, commitments)
    })
  }

}

