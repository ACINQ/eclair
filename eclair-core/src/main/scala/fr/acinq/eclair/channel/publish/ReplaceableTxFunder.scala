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
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw, OnChainFeeConf}
import fr.acinq.eclair.channel.FullCommitment
import fr.acinq.eclair.channel.publish.ReplaceableTxPrePublisher._
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
import fr.acinq.eclair.crypto.keymanager.RemoteCommitmentKeys
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.{NodeParams, NotificationsLogger}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 20/12/2021.
 */

/**
 * This actor funds a replaceable transaction to reach the requested feerate, signs it, and returns the resulting
 * transaction to the caller. Whenever possible, we avoid adding new inputs.
 * This actor does not publish the resulting transaction.
 */
object ReplaceableTxFunder {

  // @formatter:off
  sealed trait Command
  case class FundTransaction(replyTo: ActorRef[FundingResult], cmd: TxPublisher.PublishReplaceableTx, tx: Either[FundedTx, ReplaceableTxWithWitnessData], targetFeerate: FeeratePerKw) extends Command

  private case class AddInputsOk(tx: ReplaceableTxWithWitnessData, totalAmountIn: Satoshi, walletUtxos: Map[OutPoint, TxOut]) extends Command
  private case class AddInputsFailed(reason: Throwable) extends Command
  private case class SignWalletInputsOk(signedTx: Transaction) extends Command
  private case class SignWalletInputsFailed(reason: Throwable) extends Command
  private case object UtxosUnlocked extends Command
  // @formatter:on

  case class FundedTx(signedTxWithWitnessData: ReplaceableTxWithWitnessData, totalAmountIn: Satoshi, feerate: FeeratePerKw, walletInputs: Map[OutPoint, TxOut]) {
    require(signedTxWithWitnessData.txInfo.tx.txIn.nonEmpty, "funded transaction must have inputs")
    require(signedTxWithWitnessData.txInfo.tx.txOut.nonEmpty, "funded transaction must have outputs")
    val signedTx: Transaction = signedTxWithWitnessData.txInfo.tx
    val fee: Satoshi = totalAmountIn - signedTx.txOut.map(_.amount).sum
  }

  // @formatter:off
  sealed trait FundingResult
  case class TransactionReady(fundedTx: FundedTx) extends FundingResult
  case class FundingFailed(reason: TxPublisher.TxRejectedReason) extends FundingResult
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, txPublishContext: TxPublishContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(txPublishContext.mdc()) {
        Behaviors.receiveMessagePartial {
          case FundTransaction(replyTo, cmd, tx, requestedFeerate) =>
            val targetFeerate = requestedFeerate.min(maxFeerate(cmd.txInfo, cmd.commitment, cmd.commitTx, nodeParams.currentBitcoinCoreFeerates, nodeParams.onChainFeeConf))
            val txFunder = new ReplaceableTxFunder(replyTo, cmd, bitcoinClient, context)
            tx match {
              case Right(txWithWitnessData) => txFunder.fund(txWithWitnessData, targetFeerate)
              case Left(previousTx) => txFunder.bump(previousTx, targetFeerate)
            }
        }
      }
    }
  }

  /**
   * The on-chain feerate can be arbitrarily high, but it wouldn't make sense to pay more fees than the amount we're
   * trying to claim on-chain. We compute how much funds we have at risk and the feerate that matches this amount.
   */
  def maxFeerate(txInfo: ReplaceableTransactionWithInputInfo, commitment: FullCommitment, commitTx: Transaction, currentFeerates: FeeratesPerKw, feeConf: OnChainFeeConf): FeeratePerKw = {
    // We don't want to pay more in fees than the amount at risk in untrimmed pending HTLCs.
    val maxFee = txInfo match {
      case tx: HtlcTx => tx.input.txOut.amount
      case tx: ClaimHtlcTx => tx.input.txOut.amount
      case _: ClaimAnchorOutputTx =>
        val htlcBalance = commitment.localCommit.htlcTxsAndRemoteSigs.map(_.htlcTx.input.txOut.amount).sum
        val mainBalance = commitment.localCommit.spec.toLocal.truncateToSatoshi
        // If there are no HTLCs or a low HTLC amount, we still want to get back our main balance.
        // In that case, we spend at most 5% of our balance in fees, with a hard cap configured by the node operator.
        val mainBalanceFee = (mainBalance * 5 / 100).min(feeConf.anchorWithoutHtlcsMaxFee)
        htlcBalance.max(mainBalanceFee)
    }
    // We cannot know beforehand how many wallet inputs will be added, but an estimation should be good enough.
    val weight = txInfo match {
      // For HTLC transactions, we add a p2wpkh input and a p2wpkh change output.
      case _: HtlcSuccessTx => commitment.params.commitmentFormat.htlcSuccessWeight + Transactions.claimP2WPKHOutputWeight
      case _: HtlcTimeoutTx => commitment.params.commitmentFormat.htlcTimeoutWeight + Transactions.claimP2WPKHOutputWeight
      case _: ClaimHtlcSuccessTx => Transactions.claimHtlcSuccessWeight
      case _: ClaimHtlcTimeoutTx => Transactions.claimHtlcTimeoutWeight
      case _: ClaimAnchorOutputTx => commitTx.weight() + Transactions.claimAnchorOutputMinWeight
    }
    // It doesn't make sense to use a feerate that is much higher than the current feerate for inclusion into the next block.
    Transactions.fee2rate(maxFee, weight).min(currentFeerates.fastest * 1.25)
  }

  /**
   * Adjust the main output of a claim-htlc tx to match our target feerate.
   * If the resulting output is too small, we skip the transaction.
   */
  def adjustClaimHtlcTxOutput(claimHtlcTx: ClaimHtlcWithWitnessData, commitKeys: RemoteCommitmentKeys, targetFeerate: FeeratePerKw, dustLimit: Satoshi, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimHtlcWithWitnessData] = {
    require(claimHtlcTx.txInfo.tx.txIn.size == 1, "claim-htlc transaction should have a single input")
    require(claimHtlcTx.txInfo.tx.txOut.size == 1, "claim-htlc transaction should have a single output")
    val dummySignedTx = claimHtlcTx.txInfo match {
      case tx: ClaimHtlcSuccessTx => tx.sign(commitKeys, ByteVector32.Zeroes, commitmentFormat)
      case tx: ClaimHtlcTimeoutTx => tx.sign(commitKeys, commitmentFormat)
    }
    val targetFee = weight2fee(targetFeerate, dummySignedTx.tx.weight())
    val outputAmount = claimHtlcTx.txInfo.amountIn - targetFee
    if (outputAmount < dustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val updatedClaimHtlcTx = claimHtlcTx.updateTx(claimHtlcTx.txInfo.tx.copy(txOut = Seq(claimHtlcTx.txInfo.tx.txOut.head.copy(amount = outputAmount))))
      Right(updatedClaimHtlcTx)
    }
  }

  // @formatter:off
  sealed trait AdjustPreviousTxOutputResult
  object AdjustPreviousTxOutputResult {
    case class Skip(reason: String) extends AdjustPreviousTxOutputResult
    case class AddWalletInputs(previousTx: ReplaceableTxWithWalletInputs) extends AdjustPreviousTxOutputResult
    case class TxOutputAdjusted(updatedTx: ReplaceableTxWithWitnessData) extends AdjustPreviousTxOutputResult
  }
  // @formatter:on

  /**
   * Adjust the outputs of a transaction that was previously published at a lower feerate.
   * If the current set of inputs doesn't let us to reach the target feerate, we should request new wallet inputs from bitcoind.
   */
  def adjustPreviousTxOutput(previousTx: FundedTx, targetFeerate: FeeratePerKw, commitment: FullCommitment, commitTx: Transaction): AdjustPreviousTxOutputResult = {
    val dustLimit = commitment.localParams.dustLimit
    val targetFee = previousTx.signedTxWithWitnessData match {
      case _: ClaimAnchorWithWitnessData =>
        val commitFee = commitment.localCommit.commitTxAndRemoteSig.commitTx.fee
        val totalWeight = previousTx.signedTx.weight() + commitTx.weight()
        weight2fee(targetFeerate, totalWeight) - commitFee
      case _ =>
        weight2fee(targetFeerate, previousTx.signedTx.weight())
    }
    previousTx.signedTxWithWitnessData match {
      case claimLocalAnchor: ClaimAnchorWithWitnessData =>
        val changeAmount = previousTx.totalAmountIn - targetFee
        if (changeAmount < dustLimit) {
          AdjustPreviousTxOutputResult.AddWalletInputs(claimLocalAnchor)
        } else {
          val updatedTxOut = Seq(claimLocalAnchor.txInfo.tx.txOut.head.copy(amount = changeAmount))
          AdjustPreviousTxOutputResult.TxOutputAdjusted(claimLocalAnchor.updateTx(claimLocalAnchor.txInfo.tx.copy(txOut = updatedTxOut)))
        }
      case htlcTx: HtlcWithWitnessData =>
        if (htlcTx.txInfo.tx.txOut.length <= 1) {
          // There is no change output, so we can't increase the fees without adding new inputs.
          AdjustPreviousTxOutputResult.AddWalletInputs(htlcTx)
        } else {
          val htlcAmount = htlcTx.txInfo.tx.txOut.head.amount
          val changeAmount = previousTx.totalAmountIn - targetFee - htlcAmount
          if (dustLimit <= changeAmount) {
            val updatedTxOut = Seq(htlcTx.txInfo.tx.txOut.head, htlcTx.txInfo.tx.txOut.last.copy(amount = changeAmount))
            AdjustPreviousTxOutputResult.TxOutputAdjusted(htlcTx.updateTx(htlcTx.txInfo.tx.copy(txOut = updatedTxOut)))
          } else {
            // We try removing the change output to see if it provides a high enough feerate.
            val htlcTxNoChange = htlcTx.updateTx(htlcTx.txInfo.tx.copy(txOut = Seq(htlcTx.txInfo.tx.txOut.head)))
            val fee = previousTx.totalAmountIn - htlcAmount
            if (fee <= htlcAmount) {
              val feerate = fee2rate(fee, htlcTxNoChange.txInfo.tx.weight())
              if (targetFeerate <= feerate) {
                // Without the change output, we're able to reach our desired feerate.
                AdjustPreviousTxOutputResult.TxOutputAdjusted(htlcTxNoChange)
              } else {
                // Even without the change output, the feerate is too low: we must add new wallet inputs.
                AdjustPreviousTxOutputResult.AddWalletInputs(htlcTx)
              }
            } else {
              AdjustPreviousTxOutputResult.Skip("fee higher than htlc amount")
            }
          }
        }
      case claimHtlcTx: ClaimHtlcWithWitnessData =>
        val updatedAmount = previousTx.totalAmountIn - targetFee
        if (updatedAmount < dustLimit) {
          AdjustPreviousTxOutputResult.Skip("fee higher than htlc amount")
        } else {
          val updatedTxOut = Seq(claimHtlcTx.txInfo.tx.txOut.head.copy(amount = updatedAmount))
          AdjustPreviousTxOutputResult.TxOutputAdjusted(claimHtlcTx.updateTx(claimHtlcTx.txInfo.tx.copy(txOut = updatedTxOut)))
        }
    }
  }

}

private class ReplaceableTxFunder(replyTo: ActorRef[ReplaceableTxFunder.FundingResult],
                                  cmd: TxPublisher.PublishReplaceableTx,
                                  bitcoinClient: BitcoinCoreClient,
                                  context: ActorContext[ReplaceableTxFunder.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import ReplaceableTxFunder._

  private val log = context.log

  def fund(txWithWitnessData: ReplaceableTxWithWitnessData, targetFeerate: FeeratePerKw): Behavior[Command] = {
    log.info("funding {} tx (targetFeerate={})", txWithWitnessData.txInfo.desc, targetFeerate)
    txWithWitnessData match {
      case claimLocalAnchor: ClaimAnchorWithWitnessData =>
        val commitFeerate = cmd.commitment.localCommit.spec.commitTxFeerate
        if (targetFeerate <= commitFeerate) {
          log.info("skipping {}: commit feerate is high enough (feerate={})", cmd.desc, commitFeerate)
          // We set retry = true in case the on-chain feerate rises before the commit tx is confirmed: if that happens
          // we'll want to claim our anchor to raise the feerate of the commit tx and get it confirmed faster.
          replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
          Behaviors.stopped
        } else {
          addWalletInputs(claimLocalAnchor, targetFeerate)
        }
      case htlcTx: HtlcWithWitnessData =>
        val htlcFeerate = cmd.commitment.localCommit.spec.htlcTxFeerate(cmd.commitment.params.commitmentFormat)
        if (targetFeerate <= htlcFeerate) {
          log.debug("publishing {} without adding inputs: txid={}", cmd.desc, htlcTx.txInfo.tx.txid)
          sign(txWithWitnessData, htlcFeerate, htlcTx.txInfo.amountIn, Map.empty)
        } else {
          addWalletInputs(htlcTx, targetFeerate)
        }
      case claimHtlcTx: ClaimHtlcWithWitnessData =>
        val commitKeys = cmd.commitment.remoteKeys(cmd.channelKeys, cmd.remotePerCommitmentPoint)
        adjustClaimHtlcTxOutput(claimHtlcTx, commitKeys, targetFeerate, cmd.commitment.localParams.dustLimit, cmd.commitment.params.commitmentFormat) match {
          case Left(reason) =>
            // The htlc isn't economical to claim at the current feerate, but if the feerate goes down, we may want to claim it later.
            log.warn("skipping {}: {} (feerate={})", cmd.desc, reason, targetFeerate)
            replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
            Behaviors.stopped
          case Right(updatedClaimHtlcTx) =>
            sign(updatedClaimHtlcTx, targetFeerate, updatedClaimHtlcTx.txInfo.amountIn, Map.empty)
        }
    }
  }

  private def bump(previousTx: FundedTx, targetFeerate: FeeratePerKw): Behavior[Command] = {
    log.info("bumping {} tx (targetFeerate={})", previousTx.signedTxWithWitnessData.txInfo.desc, targetFeerate)
    adjustPreviousTxOutput(previousTx, targetFeerate, cmd.commitment, cmd.commitTx) match {
      case AdjustPreviousTxOutputResult.Skip(reason) =>
        log.warn("skipping {} fee bumping: {} (feerate={})", cmd.desc, reason, targetFeerate)
        replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
        Behaviors.stopped
      case AdjustPreviousTxOutputResult.TxOutputAdjusted(updatedTx) =>
        log.debug("bumping {} fees without adding new inputs: txid={}", cmd.desc, updatedTx.txInfo.tx.txid)
        sign(updatedTx, targetFeerate, previousTx.totalAmountIn, previousTx.walletInputs)
      case AdjustPreviousTxOutputResult.AddWalletInputs(tx) =>
        log.debug("bumping {} fees requires adding new inputs (feerate={})", cmd.desc, targetFeerate)
        // We restore the original transaction (remove previous attempt's wallet inputs).
        val resetTx = tx.updateTx(cmd.txInfo.tx)
        addWalletInputs(resetTx, targetFeerate)
    }
  }

  private def addWalletInputs(txWithWitnessData: ReplaceableTxWithWalletInputs, targetFeerate: FeeratePerKw): Behavior[Command] = {
    context.pipeToSelf(addInputs(txWithWitnessData, targetFeerate, cmd.commitment)) {
      case Success((fundedTx, totalAmountIn, psbt)) => AddInputsOk(fundedTx, totalAmountIn, psbt)
      case Failure(reason) => AddInputsFailed(reason)
    }
    Behaviors.receiveMessagePartial {
      case AddInputsOk(fundedTx, totalAmountIn, walletUtxos) =>
        log.debug("added {} wallet input(s) and {} wallet output(s) to {}", fundedTx.txInfo.tx.txIn.length - 1, fundedTx.txInfo.tx.txOut.length - 1, cmd.desc)
        sign(fundedTx, targetFeerate, totalAmountIn, walletUtxos)
      case AddInputsFailed(reason) =>
        if (reason.getMessage.contains("Insufficient funds")) {
          val nodeOperatorMessage =
            s"""Insufficient funds in bitcoin wallet to set feerate=$targetFeerate for ${cmd.desc}.
               |You should add more utxos to your bitcoin wallet to guarantee funds safety.
               |Attempts will be made periodically to re-publish this transaction.
               |""".stripMargin
          context.system.eventStream ! EventStream.Publish(NotifyNodeOperator(NotificationsLogger.Warning, nodeOperatorMessage))
          log.warn("cannot add inputs to {}: {}", cmd.desc, reason.getMessage)
        } else {
          log.error(s"cannot add inputs to ${cmd.desc}: ", reason)
        }
        replyTo ! FundingFailed(TxPublisher.TxRejectedReason.CouldNotFund)
        Behaviors.stopped
    }
  }

  private def sign(fundedTx: ReplaceableTxWithWitnessData, txFeerate: FeeratePerKw, amountIn: Satoshi, walletUtxos: Map[OutPoint, TxOut]): Behavior[Command] = {
    fundedTx match {
      case claimAnchorTx: ClaimAnchorWithWitnessData if cmd.isLocalCommitAnchor =>
        val commitKeys = cmd.commitment.localKeys(cmd.channelKeys)
        val signedTx = claimAnchorTx.copy(txInfo = claimAnchorTx.txInfo.sign(cmd.fundingKey, commitKeys, cmd.commitment.params.commitmentFormat, walletUtxos))
        signWalletInputs(signedTx, txFeerate, amountIn, walletUtxos)
      case claimAnchorTx: ClaimAnchorWithWitnessData =>
        val commitKeys = cmd.commitment.remoteKeys(cmd.channelKeys, cmd.remotePerCommitmentPoint)
        val signedTx = claimAnchorTx.copy(txInfo = claimAnchorTx.txInfo.sign(cmd.fundingKey, commitKeys, cmd.commitment.params.commitmentFormat, walletUtxos))
        signWalletInputs(signedTx, txFeerate, amountIn, walletUtxos)
      case htlcTx: HtlcWithWitnessData =>
        val commitKeys = cmd.commitment.localKeys(cmd.channelKeys)
        val localSig = htlcTx.txInfo.sign(commitKeys, cmd.commitment.params.commitmentFormat, walletUtxos)
        val signedTx = htlcTx match {
          case htlcSuccess: HtlcSuccessWithWitnessData => htlcSuccess.copy(txInfo = htlcSuccess.txInfo.addSigs(commitKeys, localSig, htlcSuccess.remoteSig, htlcSuccess.preimage, cmd.commitment.params.commitmentFormat))
          case htlcTimeout: HtlcTimeoutWithWitnessData => htlcTimeout.copy(txInfo = htlcTimeout.txInfo.addSigs(commitKeys, localSig, htlcTimeout.remoteSig, cmd.commitment.params.commitmentFormat))
        }
        val hasWalletInputs = htlcTx.txInfo.tx.txIn.size > 1
        if (hasWalletInputs) {
          signWalletInputs(signedTx, txFeerate, amountIn, walletUtxos)
        } else {
          replyTo ! TransactionReady(FundedTx(signedTx, amountIn, txFeerate, walletUtxos))
          Behaviors.stopped
        }
      case claimHtlcTx: ClaimHtlcWithWitnessData =>
        val commitKeys = cmd.commitment.remoteKeys(cmd.channelKeys, cmd.remotePerCommitmentPoint)
        val signedTx = claimHtlcTx match {
          case claimSuccess: ClaimHtlcSuccessWithWitnessData => claimSuccess.copy(txInfo = claimSuccess.txInfo.sign(commitKeys, claimSuccess.preimage, cmd.commitment.params.commitmentFormat))
          case claimTimeout: ClaimHtlcTimeoutWithWitnessData => claimTimeout.copy(txInfo = claimTimeout.txInfo.sign(commitKeys, cmd.commitment.params.commitmentFormat))
        }
        replyTo ! TransactionReady(FundedTx(signedTx, amountIn, txFeerate, walletUtxos))
        Behaviors.stopped
    }
  }

  private def signWalletInputs(locallySignedTx: ReplaceableTxWithWalletInputs, txFeerate: FeeratePerKw, amountIn: Satoshi, walletUtxos: Map[OutPoint, TxOut]): Behavior[Command] = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    // We create a PSBT with the non-wallet input already signed:
    val redeemInfo = locallySignedTx match {
      case _: ClaimAnchorWithWitnessData if cmd.isLocalCommitAnchor =>
        val commitKeys = cmd.commitment.localKeys(cmd.channelKeys).publicKeys
        ClaimAnchorOutputTx.redeemInfo(cmd.fundingKey, commitKeys, cmd.commitment.params.commitmentFormat)
      case _: ClaimAnchorWithWitnessData =>
        val commitKeys = cmd.commitment.remoteKeys(cmd.channelKeys, cmd.remotePerCommitmentPoint).publicKeys
        ClaimAnchorOutputTx.redeemInfo(cmd.fundingKey, commitKeys, cmd.commitment.params.commitmentFormat)
      case htlcTx: HtlcWithWitnessData =>
        val commitKeys = cmd.commitment.localKeys(cmd.channelKeys).publicKeys
        htlcTx.txInfo.redeemInfo(commitKeys, cmd.commitment.params.commitmentFormat)
    }
    val witnessScript = redeemInfo match {
      case redeemInfo: RedeemInfo.SegwitV0 => fr.acinq.bitcoin.Script.parse(redeemInfo.redeemScript)
      case _: RedeemInfo.Taproot => null
    }
    val sigHash = locallySignedTx.txInfo.sighash(TxOwner.Local, cmd.commitment.params.commitmentFormat)
    val psbt = new Psbt(locallySignedTx.txInfo.tx)
      .updateWitnessInput(
        locallySignedTx.txInfo.input.outPoint,
        locallySignedTx.txInfo.input.txOut,
        null,
        witnessScript,
        sigHash,
        java.util.Map.of(),
        null,
        null,
        java.util.Map.of()
      ).flatMap(_.finalizeWitnessInput(0, locallySignedTx.txInfo.tx.txIn.head.witness))
    psbt match {
      case Left(failure) =>
        log.error(s"cannot sign ${cmd.desc}: $failure")
        unlockAndStop(locallySignedTx.txInfo.tx, TxPublisher.TxRejectedReason.UnknownTxFailure)
      case Right(psbt1) =>
        // The transaction that we want to fund/replace has one input, the first one. Additional inputs are provided by our on-chain wallet.
        val ourWalletInputs = locallySignedTx.txInfo.tx.txIn.indices.tail
        // For "claim anchor txs" there is a single change output that sends to our on-chain wallet.
        // For htlc txs the first output is the one we want to fund/bump, additional outputs send to our on-chain wallet.
        val ourWalletOutputs = locallySignedTx match {
          case _: ClaimAnchorWithWitnessData => Seq(0)
          case _: HtlcWithWitnessData => locallySignedTx.txInfo.tx.txOut.indices.tail
        }
        context.pipeToSelf(bitcoinClient.signPsbt(psbt1, ourWalletInputs, ourWalletOutputs)) {
          case Success(processPsbtResponse) =>
            processPsbtResponse.finalTx_opt match {
              case Right(signedTx) =>
                val actualFees = kmp2scala(processPsbtResponse.psbt.computeFees())
                val actualWeight = locallySignedTx match {
                  case _: ClaimAnchorWithWitnessData => signedTx.weight() + cmd.commitTx.weight()
                  case _ => signedTx.weight()
                }
                val actualFeerate = Transactions.fee2rate(actualFees, actualWeight)
                if (actualFeerate >= txFeerate * 2) {
                  SignWalletInputsFailed(new RuntimeException(s"actual fee rate $actualFeerate is more than twice the requested fee rate $txFeerate"))
                } else {
                  SignWalletInputsOk(signedTx)
                }
              case Left(failure) => SignWalletInputsFailed(new RuntimeException(s"could not sign psbt: $failure"))
            }
          case Failure(reason) => SignWalletInputsFailed(reason)
        }
        Behaviors.receiveMessagePartial {
          case SignWalletInputsOk(signedTx) =>
            val fullySignedTx = locallySignedTx.updateTx(signedTx)
            replyTo ! TransactionReady(FundedTx(fullySignedTx, amountIn, txFeerate, walletUtxos))
            Behaviors.stopped
          case SignWalletInputsFailed(reason) =>
            log.error(s"cannot sign ${cmd.desc}: ", reason)
            // We reply with the failure only once the utxos are unlocked, otherwise there is a risk that our parent stops
            // itself, which will automatically stop us before we had a chance to unlock them.
            unlockAndStop(locallySignedTx.txInfo.tx, TxPublisher.TxRejectedReason.UnknownTxFailure)
        }
    }
  }

  def unlockAndStop(tx: Transaction, failure: TxPublisher.TxRejectedReason): Behavior[Command] = {
    val toUnlock = tx.txIn.map(_.outPoint)
    log.debug("unlocking utxos={}", toUnlock.mkString(", "))
    context.pipeToSelf(bitcoinClient.unlockOutpoints(toUnlock))(_ => UtxosUnlocked)
    Behaviors.receiveMessagePartial {
      case UtxosUnlocked =>
        log.debug("utxos unlocked")
        replyTo ! FundingFailed(failure)
        Behaviors.stopped
    }
  }

  private def getWalletUtxos(txInfo: ForceCloseTransaction): Future[Map[OutPoint, TxOut]] = {
    Future.sequence(txInfo.tx.txIn.filter(_.outPoint != txInfo.input.outPoint).map(txIn => {
      bitcoinClient.getTransaction(txIn.outPoint.txid).flatMap {
        case inputTx if inputTx.txOut.size <= txIn.outPoint.index => Future.failed(new IllegalArgumentException(s"input ${inputTx.txid}:${txIn.outPoint.index} doesn't exist"))
        case inputTx => Future.successful(txIn.outPoint -> inputTx.txOut(txIn.outPoint.index.toInt))
      }
    })).map(_.toMap)
  }

  private def addInputs(tx: ReplaceableTxWithWalletInputs, targetFeerate: FeeratePerKw, commitment: FullCommitment): Future[(ReplaceableTxWithWalletInputs, Satoshi, Map[OutPoint, TxOut])] = {
    for {
      (fundedTx, amountIn) <- tx match {
        case anchorTx: ClaimAnchorWithWitnessData => addInputs(anchorTx, targetFeerate, commitment)
        case htlcTx: HtlcWithWitnessData => addInputs(htlcTx, targetFeerate, commitment)
      }
      spentUtxos <- getWalletUtxos(fundedTx.txInfo)
    } yield (fundedTx, amountIn, spentUtxos)
  }

  private def addInputs(anchorTx: ClaimAnchorWithWitnessData, targetFeerate: FeeratePerKw, commitment: FullCommitment): Future[(ClaimAnchorWithWitnessData, Satoshi)] = {
    // We want to pay the commit fees using CPFP. Since the commit tx may not be in the mempool yet (its feerate may be
    // below the minimum acceptable mempool feerate), we cannot ask bitcoind to fund a transaction that spends that
    // commit tx: it would fail because it cannot find the input in the utxo set. So we instead ask bitcoind to fund an
    // empty transaction that pays the fees we must add to the transaction package, and we then add the input spending
    // the commit tx and adjust the change output.
    val expectedCommitFee = Transactions.weight2fee(targetFeerate, cmd.commitTx.weight())
    val actualCommitFee = commitment.commitInput.txOut.amount - cmd.commitTx.txOut.map(_.amount).sum
    val anchorInputFee = Transactions.weight2fee(targetFeerate, anchorInputWeight)
    val missingFee = expectedCommitFee - actualCommitFee + anchorInputFee
    for {
      changeScript <- bitcoinClient.getChangePublicKeyScript()
      txNotFunded = Transaction(2, Nil, TxOut(commitment.localParams.dustLimit + missingFee, changeScript) :: Nil, 0)
      // We only use confirmed inputs for anchor transactions to be able to leverage 1-parent-1-child package relay.
      fundTxResponse <- bitcoinClient.fundTransaction(txNotFunded, targetFeerate, minInputConfirmations_opt = Some(1))
    } yield {
      // We merge our dummy change output with the one added by Bitcoin Core, if any, and adjust the change amount to
      // pay the expected package feerate.
      val txIn = anchorTx.txInfo.tx.txIn ++ fundTxResponse.tx.txIn
      val packageWeight = cmd.commitTx.weight() + anchorInputWeight + fundTxResponse.tx.weight()
      val expectedFee = Transactions.weight2fee(targetFeerate, packageWeight)
      val currentFee = actualCommitFee + fundTxResponse.fee
      val changeAmount = (fundTxResponse.tx.txOut.map(_.amount).sum - expectedFee + currentFee).max(commitment.localParams.dustLimit)
      val changeOutput = fundTxResponse.changePosition match {
        case Some(changePos) => fundTxResponse.tx.txOut(changePos).copy(amount = changeAmount)
        case None => TxOut(changeAmount, changeScript)
      }
      val txSingleOutput = fundTxResponse.tx.copy(txIn = txIn, txOut = Seq(changeOutput))
      (anchorTx.updateTx(txSingleOutput), fundTxResponse.amountIn)
    }
  }

  private def addInputs(htlcTx: HtlcWithWitnessData, targetFeerate: FeeratePerKw, commitment: FullCommitment): Future[(HtlcWithWitnessData, Satoshi)] = {
    val htlcInputWeight = htlcTx.txInfo match {
      case _: HtlcSuccessTx => commitment.params.commitmentFormat.htlcSuccessInputWeight.toLong
      case _: HtlcTimeoutTx => commitment.params.commitmentFormat.htlcTimeoutInputWeight.toLong
    }
    bitcoinClient.fundTransaction(htlcTx.txInfo.tx, targetFeerate, changePosition = Some(1), externalInputsWeight = Map(htlcTx.txInfo.input.outPoint -> htlcInputWeight)).map(fundTxResponse => {
      // Bitcoin Core may not preserve the order of inputs, we need to make sure the htlc is the first input.
      val fundedTx = fundTxResponse.tx.copy(txIn = htlcTx.txInfo.tx.txIn ++ fundTxResponse.tx.txIn.filterNot(_.outPoint == htlcTx.txInfo.input.outPoint))
      (htlcTx.updateTx(fundedTx), fundTxResponse.amountIn)
    })
  }
}
