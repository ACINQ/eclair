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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.{FundTransactionOptions, InputWeight}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.FullCommitment
import fr.acinq.eclair.channel.publish.ReplaceableTxPrePublisher._
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
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

  private case class AddInputsOk(tx: ReplaceableTxWithWitnessData, totalAmountIn: Satoshi) extends Command
  private case class AddInputsFailed(reason: Throwable) extends Command
  private case class SignWalletInputsOk(signedTx: Transaction) extends Command
  private case class SignWalletInputsFailed(reason: Throwable) extends Command
  private case object UtxosUnlocked extends Command
  // @formatter:on

  case class FundedTx(signedTxWithWitnessData: ReplaceableTxWithWitnessData, totalAmountIn: Satoshi, feerate: FeeratePerKw) {
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
          case FundTransaction(replyTo, cmd, tx, targetFeerate) =>
            val txFunder = new ReplaceableTxFunder(nodeParams, replyTo, cmd, bitcoinClient, context)
            tx match {
              case Right(txWithWitnessData) => txFunder.fund(txWithWitnessData, targetFeerate)
              case Left(previousTx) => txFunder.bump(previousTx, targetFeerate)
            }
        }
      }
    }
  }

  private def dummySignedCommitTx(commitment: FullCommitment): CommitTx = {
    val unsignedCommitTx = commitment.localCommit.commitTxAndRemoteSig.commitTx
    addSigs(unsignedCommitTx, PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderSig, PlaceHolderSig)
  }

  /**
   * Adjust the main output of a claim-htlc tx to match our target feerate.
   * If the resulting output is too small, we skip the transaction.
   */
  def adjustClaimHtlcTxOutput(claimHtlcTx: ClaimHtlcWithWitnessData, targetFeerate: FeeratePerKw, dustLimit: Satoshi): Either[TxGenerationSkipped, ClaimHtlcWithWitnessData] = {
    require(claimHtlcTx.txInfo.tx.txIn.size == 1, "claim-htlc transaction should have a single input")
    require(claimHtlcTx.txInfo.tx.txOut.size == 1, "claim-htlc transaction should have a single output")
    val dummySignedTx = claimHtlcTx.txInfo match {
      case tx: ClaimHtlcSuccessTx => addSigs(tx, PlaceHolderSig, ByteVector32.Zeroes)
      case tx: ClaimHtlcTimeoutTx => addSigs(tx, PlaceHolderSig)
      case tx: LegacyClaimHtlcSuccessTx => tx
    }
    val targetFee = weight2fee(targetFeerate, dummySignedTx.tx.weight())
    val outputAmount = claimHtlcTx.txInfo.amountIn - targetFee
    if (outputAmount < dustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val updatedClaimHtlcTx = claimHtlcTx match {
        // NB: we don't modify legacy claim-htlc-success, it's already signed.
        case legacyClaimHtlcSuccess: LegacyClaimHtlcSuccessWithWitnessData => legacyClaimHtlcSuccess
        case _ => claimHtlcTx.updateTx(claimHtlcTx.txInfo.tx.copy(txOut = Seq(claimHtlcTx.txInfo.tx.txOut.head.copy(amount = outputAmount))))
      }
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
  def adjustPreviousTxOutput(previousTx: FundedTx, targetFeerate: FeeratePerKw, commitment: FullCommitment): AdjustPreviousTxOutputResult = {
    val dustLimit = commitment.localParams.dustLimit
    val targetFee = previousTx.signedTxWithWitnessData match {
      case _: ClaimLocalAnchorWithWitnessData =>
        val commitTx = dummySignedCommitTx(commitment)
        val totalWeight = previousTx.signedTx.weight() + commitTx.tx.weight()
        weight2fee(targetFeerate, totalWeight) - commitTx.fee
      case _ =>
        weight2fee(targetFeerate, previousTx.signedTx.weight())
    }
    previousTx.signedTxWithWitnessData match {
      case claimLocalAnchor: ClaimLocalAnchorWithWitnessData =>
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
          claimHtlcTx match {
            // NB: we don't modify legacy claim-htlc-success, it's already signed.
            case _: LegacyClaimHtlcSuccessWithWitnessData => AdjustPreviousTxOutputResult.Skip("legacy claim-htlc-success should not be updated")
            case _ => AdjustPreviousTxOutputResult.TxOutputAdjusted(claimHtlcTx.updateTx(claimHtlcTx.txInfo.tx.copy(txOut = updatedTxOut)))
          }
        }
    }
  }

}

private class ReplaceableTxFunder(nodeParams: NodeParams,
                                  replyTo: ActorRef[ReplaceableTxFunder.FundingResult],
                                  cmd: TxPublisher.PublishReplaceableTx,
                                  bitcoinClient: BitcoinCoreClient,
                                  context: ActorContext[ReplaceableTxFunder.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import ReplaceableTxFunder._
  import nodeParams.{channelKeyManager => keyManager}

  private val log = context.log

  def fund(txWithWitnessData: ReplaceableTxWithWitnessData, targetFeerate: FeeratePerKw): Behavior[Command] = {
    txWithWitnessData match {
      case claimLocalAnchor: ClaimLocalAnchorWithWitnessData =>
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
          log.info("publishing {} without adding inputs: txid={}", cmd.desc, htlcTx.txInfo.tx.txid)
          sign(txWithWitnessData, htlcFeerate, htlcTx.txInfo.amountIn)
        } else {
          addWalletInputs(htlcTx, targetFeerate)
        }
      case claimHtlcTx: ClaimHtlcWithWitnessData =>
        adjustClaimHtlcTxOutput(claimHtlcTx, targetFeerate, cmd.commitment.localParams.dustLimit) match {
          case Left(reason) =>
            // The htlc isn't economical to claim at the current feerate, but if the feerate goes down, we may want to claim it later.
            log.warn("skipping {}: {} (feerate={})", cmd.desc, reason, targetFeerate)
            replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
            Behaviors.stopped
          case Right(updatedClaimHtlcTx) =>
            sign(updatedClaimHtlcTx, targetFeerate, updatedClaimHtlcTx.txInfo.amountIn)
        }
    }
  }

  private def bump(previousTx: FundedTx, targetFeerate: FeeratePerKw): Behavior[Command] = {
    adjustPreviousTxOutput(previousTx, targetFeerate, cmd.commitment) match {
      case AdjustPreviousTxOutputResult.Skip(reason) =>
        log.warn("skipping {} fee bumping: {} (feerate={})", cmd.desc, reason, targetFeerate)
        replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
        Behaviors.stopped
      case AdjustPreviousTxOutputResult.TxOutputAdjusted(updatedTx) =>
        log.debug("bumping {} fees without adding new inputs: txid={}", cmd.desc, updatedTx.txInfo.tx.txid)
        sign(updatedTx, targetFeerate, previousTx.totalAmountIn)
      case AdjustPreviousTxOutputResult.AddWalletInputs(tx) =>
        log.debug("bumping {} fees requires adding new inputs (feerate={})", cmd.desc, targetFeerate)
        // We restore the original transaction (remove previous attempt's wallet inputs).
        val resetTx = tx.updateTx(cmd.txInfo.tx)
        addWalletInputs(resetTx, targetFeerate)
    }
  }

  private def addWalletInputs(txWithWitnessData: ReplaceableTxWithWalletInputs, targetFeerate: FeeratePerKw): Behavior[Command] = {
    context.pipeToSelf(addInputs(txWithWitnessData, targetFeerate, cmd.commitment)) {
      case Success((fundedTx, totalAmountIn)) => AddInputsOk(fundedTx, totalAmountIn)
      case Failure(reason) => AddInputsFailed(reason)
    }
    Behaviors.receiveMessagePartial {
      case AddInputsOk(fundedTx, totalAmountIn) =>
        log.info("added {} wallet input(s) and {} wallet output(s) to {}", fundedTx.txInfo.tx.txIn.length - 1, fundedTx.txInfo.tx.txOut.length - 1, cmd.desc)
        sign(fundedTx, targetFeerate, totalAmountIn)
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

  private def sign(fundedTx: ReplaceableTxWithWitnessData, txFeerate: FeeratePerKw, amountIn: Satoshi): Behavior[Command] = {
    val channelKeyPath = keyManager.keyPath(cmd.commitment.localParams, cmd.commitment.params.channelConfig)
    fundedTx match {
      case ClaimLocalAnchorWithWitnessData(anchorTx) =>
        val localSig = keyManager.sign(anchorTx, keyManager.fundingPublicKey(cmd.commitment.localParams.fundingKeyPath), TxOwner.Local, cmd.commitment.params.commitmentFormat)
        val signedTx = ClaimLocalAnchorWithWitnessData(addSigs(anchorTx, localSig))
        signWalletInputs(signedTx, txFeerate, amountIn)
      case htlcTx: HtlcWithWitnessData =>
        val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, cmd.commitment.localCommit.index)
        val localHtlcBasepoint = keyManager.htlcPoint(channelKeyPath)
        val localSig = keyManager.sign(htlcTx.txInfo, localHtlcBasepoint, localPerCommitmentPoint, TxOwner.Local, cmd.commitment.params.commitmentFormat)
        val signedTx = htlcTx match {
          case htlcSuccess: HtlcSuccessWithWitnessData => htlcSuccess.copy(txInfo = addSigs(htlcSuccess.txInfo, localSig, htlcSuccess.remoteSig, htlcSuccess.preimage, cmd.commitment.params.commitmentFormat))
          case htlcTimeout: HtlcTimeoutWithWitnessData => htlcTimeout.copy(txInfo = addSigs(htlcTimeout.txInfo, localSig, htlcTimeout.remoteSig, cmd.commitment.params.commitmentFormat))
        }
        val hasWalletInputs = htlcTx.txInfo.tx.txIn.size > 1
        if (hasWalletInputs) {
          signWalletInputs(signedTx, txFeerate, amountIn)
        } else {
          replyTo ! TransactionReady(FundedTx(signedTx, amountIn, txFeerate))
          Behaviors.stopped
        }
      case claimHtlcTx: ClaimHtlcWithWitnessData =>
        val remotePerCommitmentPoint = cmd.commitment.nextRemoteCommit_opt match {
          case Some(c) if claimHtlcTx.txInfo.input.outPoint.txid == c.commit.txid => c.commit.remotePerCommitmentPoint
          case _ => cmd.commitment.remoteCommit.remotePerCommitmentPoint
        }
        val sig = keyManager.sign(claimHtlcTx.txInfo, keyManager.htlcPoint(channelKeyPath), remotePerCommitmentPoint, TxOwner.Local, cmd.commitment.params.commitmentFormat)
        val signedTx = claimHtlcTx match {
          case ClaimHtlcSuccessWithWitnessData(txInfo, preimage) => ClaimHtlcSuccessWithWitnessData(addSigs(txInfo, sig, preimage), preimage)
          case legacyClaimHtlcSuccess: LegacyClaimHtlcSuccessWithWitnessData => legacyClaimHtlcSuccess
          case ClaimHtlcTimeoutWithWitnessData(txInfo) => ClaimHtlcTimeoutWithWitnessData(addSigs(txInfo, sig))
        }
        replyTo ! TransactionReady(FundedTx(signedTx, amountIn, txFeerate))
        Behaviors.stopped
    }
  }

  private def signWalletInputs(locallySignedTx: ReplaceableTxWithWalletInputs, txFeerate: FeeratePerKw, amountIn: Satoshi): Behavior[Command] = {
    val inputInfo = BitcoinCoreClient.PreviousTx(locallySignedTx.txInfo.input, locallySignedTx.txInfo.tx.txIn.head.witness)
    context.pipeToSelf(bitcoinClient.signTransaction(locallySignedTx.txInfo.tx, Seq(inputInfo))) {
      case Success(signedTx) => SignWalletInputsOk(signedTx.tx)
      case Failure(reason) => SignWalletInputsFailed(reason)
    }
    Behaviors.receiveMessagePartial {
      case SignWalletInputsOk(signedTx) =>
        val fullySignedTx = locallySignedTx.updateTx(signedTx)
        replyTo ! TransactionReady(FundedTx(fullySignedTx, amountIn, txFeerate))
        Behaviors.stopped
      case SignWalletInputsFailed(reason) =>
        log.error(s"cannot sign ${cmd.desc}: ", reason)
        // We reply with the failure only once the utxos are unlocked, otherwise there is a risk that our parent stops
        // itself, which will automatically stop us before we had a chance to unlock them.
        unlockAndStop(locallySignedTx.txInfo.input.outPoint, locallySignedTx.txInfo.tx, TxPublisher.TxRejectedReason.UnknownTxFailure)
    }
  }

  def unlockAndStop(input: OutPoint, tx: Transaction, failure: TxPublisher.TxRejectedReason): Behavior[Command] = {
    val toUnlock = tx.txIn.filterNot(_.outPoint == input).map(_.outPoint)
    log.debug("unlocking utxos={}", toUnlock.mkString(", "))
    context.pipeToSelf(bitcoinClient.unlockOutpoints(toUnlock))(_ => UtxosUnlocked)
    Behaviors.receiveMessagePartial {
      case UtxosUnlocked =>
        log.debug("utxos unlocked")
        replyTo ! FundingFailed(failure)
        Behaviors.stopped
    }
  }

  private def addInputs(tx: ReplaceableTxWithWalletInputs, targetFeerate: FeeratePerKw, commitment: FullCommitment): Future[(ReplaceableTxWithWalletInputs, Satoshi)] = {
    tx match {
      case anchorTx: ClaimLocalAnchorWithWitnessData => addInputs(anchorTx, targetFeerate, commitment)
      case htlcTx: HtlcWithWitnessData => addInputs(htlcTx, targetFeerate, commitment)
    }
  }

  private def addInputs(anchorTx: ClaimLocalAnchorWithWitnessData, targetFeerate: FeeratePerKw, commitment: FullCommitment): Future[(ClaimLocalAnchorWithWitnessData, Satoshi)] = {
    val dustLimit = commitment.localParams.dustLimit
    val commitTx = dummySignedCommitTx(commitment).tx
    // NB: fundrawtransaction requires at least one output, and may add at most one additional change output.
    // Since the purpose of this transaction is just to do a CPFP, the resulting tx should have a single change output
    // (note that bitcoind doesn't let us publish a transaction with no outputs). To work around these limitations, we
    // start with a dummy output and later merge that dummy output with the optional change output added by bitcoind.
    val txNotFunded = anchorTx.txInfo.tx.copy(txOut = TxOut(dustLimit, Script.pay2wpkh(PlaceHolderPubKey)) :: Nil)
    // The anchor transaction is paying for the weight of the commitment transaction.
    val anchorWeight = Seq(InputWeight(anchorTx.txInfo.input.outPoint, anchorInputWeight + commitTx.weight()))
    bitcoinClient.fundTransaction(txNotFunded, FundTransactionOptions(targetFeerate, inputWeights = anchorWeight)).flatMap(fundTxResponse => {
      // We merge the outputs if there's more than one.
      fundTxResponse.changePosition match {
        case Some(changePos) =>
          val changeOutput = fundTxResponse.tx.txOut(changePos)
          val txSingleOutput = fundTxResponse.tx.copy(txOut = Seq(changeOutput))
          // We ask bitcoind to sign the wallet inputs to learn their final weight and adjust the change amount.
          bitcoinClient.signTransaction(txSingleOutput, allowIncomplete = true).map(signTxResponse => {
            val dummySignedTx = addSigs(anchorTx.updateTx(signTxResponse.tx).txInfo, PlaceHolderSig)
            val packageWeight = commitTx.weight() + dummySignedTx.tx.weight()
            val anchorTxFee = weight2fee(targetFeerate, packageWeight) - weight2fee(commitment.localCommit.spec.commitTxFeerate, commitTx.weight())
            val changeAmount = dustLimit.max(fundTxResponse.amountIn - anchorTxFee)
            val fundedTx = fundTxResponse.tx.copy(txOut = Seq(changeOutput.copy(amount = changeAmount)))
            (anchorTx.updateTx(fundedTx), fundTxResponse.amountIn)
          })
        case None =>
          bitcoinClient.getChangeAddress().map(pubkeyHash => {
            val fundedTx = fundTxResponse.tx.copy(txOut = Seq(TxOut(dustLimit, Script.pay2wpkh(pubkeyHash))))
            (anchorTx.updateTx(fundedTx), fundTxResponse.amountIn)
          })
      }
    })
  }

  private def addInputs(htlcTx: HtlcWithWitnessData, targetFeerate: FeeratePerKw, commitment: FullCommitment): Future[(HtlcWithWitnessData, Satoshi)] = {
    val htlcInputWeight = Seq(InputWeight(htlcTx.txInfo.input.outPoint, htlcTx.txInfo match {
      case _: HtlcSuccessTx => commitment.params.commitmentFormat.htlcSuccessInputWeight
      case _: HtlcTimeoutTx => commitment.params.commitmentFormat.htlcTimeoutInputWeight
    }))
    bitcoinClient.fundTransaction(htlcTx.txInfo.tx, FundTransactionOptions(targetFeerate, changePosition = Some(1), inputWeights = htlcInputWeight)).map(fundTxResponse => {
      val unsignedTx = htlcTx.updateTx(fundTxResponse.tx)
      (unsignedTx, fundTxResponse.amountIn)
    })
  }

}
