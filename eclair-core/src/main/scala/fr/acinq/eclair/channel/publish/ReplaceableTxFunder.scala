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
import fr.acinq.bitcoin.scalacompat.{Satoshi, SatoshiLong, Transaction, TxIn, TxOut}
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw, OnChainFeeConf}
import fr.acinq.eclair.channel.FullCommitment
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
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
  case class FundTransaction(replyTo: ActorRef[FundingResult], txInfo: Either[FundedTx, ForceCloseTransaction], commitTx: Transaction, commitment: FullCommitment, targetFeerate: FeeratePerKw) extends Command

  private case class AddInputsOk(walletInputs: WalletInputs) extends Command
  private case class AddInputsFailed(reason: Throwable) extends Command
  private case class SignWalletInputsOk(signedTx: Transaction) extends Command
  private case class SignWalletInputsFailed(reason: Throwable) extends Command
  private case object UtxosUnlocked extends Command
  // @formatter:on

  case class FundedTx(txInfo: ForceCloseTransaction, walletInputs_opt: Option[WalletInputs], signedTx: Transaction, feerate: FeeratePerKw) {
    val totalAmountIn: Satoshi = txInfo.amountIn + walletInputs_opt.map(_.amountIn).getOrElse(0 sat)
    val fee: Satoshi = txInfo.fee + walletInputs_opt.map(_.fee).getOrElse(0 sat)
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
          case cmd: FundTransaction =>
            val txInfo = cmd.txInfo.fold(fundedTx => fundedTx.txInfo, tx => tx)
            val targetFeerate = cmd.targetFeerate.min(maxFeerate(txInfo, cmd.commitTx, cmd.commitment, nodeParams.currentBitcoinCoreFeerates, nodeParams.onChainFeeConf))
            val txFunder = new ReplaceableTxFunder(cmd.replyTo, cmd.commitTx, cmd.commitment, bitcoinClient, context)
            cmd.txInfo match {
              case Right(txInfo) => txFunder.fund(txInfo, targetFeerate)
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
  def maxFeerate(tx: ForceCloseTransaction, commitTx: Transaction, commitment: FullCommitment, currentFeerates: FeeratesPerKw, feeConf: OnChainFeeConf): FeeratePerKw = {
    // We don't want to pay more in fees than the amount at risk in pending HTLCs.
    val maxFee = tx match {
      case _: ClaimAnchorTx =>
        val htlcBalance = commitment.localCommit.spec.htlcs.map(_.add.amountMsat).sum.truncateToSatoshi
        val mainBalance = commitment.localCommit.spec.toLocal.truncateToSatoshi
        // If there are no HTLCs or a low HTLC amount, we still want to get back our main balance.
        // In that case, we spend at most 5% of our balance in fees, with a hard cap configured by the node operator.
        val mainBalanceFee = (mainBalance * 5 / 100).min(feeConf.anchorWithoutHtlcsMaxFee)
        htlcBalance.max(mainBalanceFee)
      case _ => tx.amountIn
    }
    // We cannot know beforehand how many wallet inputs will be added, but an estimation should be good enough.
    val weight = tx match {
      // When claiming our anchor output, it must pay for the weight of the commitment transaction.
      // We usually add a wallet input and a change output.
      case tx: ClaimAnchorTx => commitTx.weight() + tx.expectedWeight + Transactions.maxWalletInputWeight + Transactions.maxWalletOutputWeight
      // For HTLC transactions, we usually add a wallet input and a change output.
      case tx: SignedHtlcTx => tx.expectedWeight + Transactions.maxWalletInputWeight + Transactions.maxWalletOutputWeight
      // Other transactions don't use any additional inputs or outputs.
      case tx => tx.expectedWeight
    }
    // It doesn't make sense to use a feerate that is much higher than the current feerate for inclusion into the next block,
    // so we restrict the weight-based feerate obtained.
    Transactions.fee2rate(maxFee, weight).min(currentFeerates.fastest * 1.25)
  }

}

private class ReplaceableTxFunder(replyTo: ActorRef[ReplaceableTxFunder.FundingResult],
                                  commitTx: Transaction,
                                  commitment: FullCommitment,
                                  bitcoinClient: BitcoinCoreClient,
                                  context: ActorContext[ReplaceableTxFunder.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import ReplaceableTxFunder._

  private val dustLimit = commitment.localCommitParams.dustLimit
  private val commitFee: Satoshi = commitment.capacity - commitTx.txOut.map(_.amount).sum

  private val log = context.log

  def fund(tx: ForceCloseTransaction, targetFeerate: FeeratePerKw): Behavior[Command] = {
    log.info("funding {} tx (targetFeerate={})", tx.desc, targetFeerate)
    tx match {
      case anchorTx: ClaimAnchorTx =>
        val commitFeerate = commitment.localCommit.spec.commitTxFeerate
        if (targetFeerate <= commitFeerate) {
          log.info("skipping {}: commit feerate is high enough (feerate={})", tx.desc, commitFeerate)
          // We set retry = true in case the on-chain feerate rises before the commit tx is confirmed: if that happens
          // we'll want to claim our anchor to raise the feerate of the commit tx and get it confirmed faster.
          replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
          Behaviors.stopped
        } else {
          addWalletInputs(anchorTx, targetFeerate)
        }
      case htlcTx: SignedHtlcTx =>
        val htlcFeerate = commitment.localCommit.spec.htlcTxFeerate(htlcTx.commitmentFormat)
        if (targetFeerate <= htlcFeerate) {
          log.debug("publishing {} without adding inputs: txid={}", tx.desc, htlcTx.tx.txid)
          sign(htlcTx, htlcFeerate, walletInputs_opt = None)
        } else {
          addWalletInputs(htlcTx, targetFeerate)
        }
      case _ =>
        Transactions.updateFee(tx, Transactions.weight2fee(targetFeerate, tx.expectedWeight), dustLimit) match {
          case Left(reason) =>
            // The output isn't economical to claim at the current feerate, but if the feerate goes down, we may want to claim it later.
            log.warn("skipping {}: {} (feerate={})", tx.desc, reason.toString, targetFeerate)
            replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
            Behaviors.stopped
          case Right(updatedTx) =>
            sign(updatedTx, targetFeerate, walletInputs_opt = None)
        }
    }
  }

  private def bump(previousTx: FundedTx, targetFeerate: FeeratePerKw): Behavior[Command] = {
    log.info("bumping {} tx (targetFeerate={})", previousTx.txInfo.desc, targetFeerate)
    val targetFee = previousTx.txInfo match {
      case _: ClaimAnchorTx =>
        val totalWeight = previousTx.signedTx.weight() + commitTx.weight()
        weight2fee(targetFeerate, totalWeight) - commitFee
      case _ =>
        weight2fee(targetFeerate, previousTx.signedTx.weight())
    }
    // Whenever possible, we keep the previous wallet input(s) and simply update the change amount.
    previousTx.txInfo match {
      case anchorTx: ClaimAnchorTx =>
        val changeAmount = previousTx.totalAmountIn - targetFee
        previousTx.walletInputs_opt match {
          case Some(walletInputs) if changeAmount > dustLimit => sign(anchorTx, targetFeerate, Some(walletInputs.setChangeAmount(changeAmount)))
          case _ => addWalletInputs(anchorTx, targetFeerate)
        }
      case htlcTx: SignedHtlcTx =>
        previousTx.walletInputs_opt match {
          case Some(walletInputs) =>
            val htlcAmount = htlcTx.amountIn
            val changeAmount = previousTx.totalAmountIn - targetFee - htlcAmount
            if (changeAmount > dustLimit) {
              // The existing wallet inputs are sufficient to pay the target feerate: no need to add new inputs.
              sign(htlcTx, targetFeerate, Some(walletInputs.setChangeAmount(changeAmount)))
            } else {
              // We try removing the change output to see if it provides a high enough feerate.
              val htlcTxNoChange = previousTx.signedTx.copy(txOut = previousTx.signedTx.txOut.take(1))
              val feeWithoutChange = previousTx.totalAmountIn - htlcAmount
              if (feeWithoutChange <= htlcAmount) {
                val feerate = Transactions.fee2rate(feeWithoutChange, htlcTxNoChange.weight())
                if (targetFeerate <= feerate) {
                  // Without the change output, we're able to reach our desired feerate.
                  sign(htlcTx, targetFeerate, Some(walletInputs.copy(changeOutput_opt = None)))
                } else {
                  // Even without the change output, the feerate is too low: we must add new wallet inputs.
                  addWalletInputs(htlcTx, targetFeerate)
                }
              } else {
                log.warn("skipping {} fee bumping: htlc amount too low (amount={} feerate={})", previousTx.txInfo.desc, htlcAmount, targetFeerate)
                replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
                Behaviors.stopped
              }
            }
          case None => addWalletInputs(htlcTx, targetFeerate)
        }
      case _ =>
        Transactions.updateFee(previousTx.txInfo, targetFee, dustLimit) match {
          case Left(reason) =>
            log.warn("skipping {} fee bumping: {} (feerate={})", previousTx.txInfo.desc, reason.toString, targetFeerate)
            replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
            Behaviors.stopped
          case Right(updatedTx) =>
            sign(updatedTx, targetFeerate, walletInputs_opt = None)
        }
    }
  }

  private def addWalletInputs(tx: HasWalletInputs, targetFeerate: FeeratePerKw): Behavior[Command] = {
    context.pipeToSelf(tx match {
      case anchorTx: ClaimAnchorTx => addInputs(anchorTx, targetFeerate)
      case htlcTx: SignedHtlcTx => addInputs(htlcTx, targetFeerate)
    }) {
      case Success(walletInputs) => AddInputsOk(walletInputs)
      case Failure(reason) => AddInputsFailed(reason)
    }
    Behaviors.receiveMessagePartial {
      case AddInputsOk(walletInputs) =>
        log.debug("added {} wallet input(s) and {} wallet output(s) to {}", walletInputs.inputs.size, walletInputs.txOut.size, tx.desc)
        sign(tx, targetFeerate, Some(walletInputs))
      case AddInputsFailed(reason) =>
        if (reason.getMessage.contains("Insufficient funds")) {
          val nodeOperatorMessage =
            s"""Insufficient funds in bitcoin wallet to set feerate=$targetFeerate for ${tx.desc}.
               |You should add more utxos to your bitcoin wallet to guarantee funds safety.
               |Attempts will be made periodically to re-publish this transaction.
               |""".stripMargin
          context.system.eventStream ! EventStream.Publish(NotifyNodeOperator(NotificationsLogger.Warning, nodeOperatorMessage))
          log.warn("cannot add inputs to {}: {}", tx.desc, reason.getMessage)
        } else {
          log.error(s"cannot add inputs to ${tx.desc}: ", reason)
        }
        replyTo ! FundingFailed(TxPublisher.TxRejectedReason.CouldNotFund)
        Behaviors.stopped
    }
  }

  private def sign(tx: ForceCloseTransaction, txFeerate: FeeratePerKw, walletInputs_opt: Option[WalletInputs]): Behavior[Command] = {
    (tx, walletInputs_opt) match {
      case (tx: HasWalletInputs, Some(walletInputs)) =>
        val locallySignedTx = tx.sign(walletInputs)
        signWalletInputs(tx, locallySignedTx, txFeerate, walletInputs)
      case _ =>
        val signedTx = tx.sign()
        replyTo ! TransactionReady(FundedTx(tx, None, signedTx, txFeerate))
        Behaviors.stopped
    }
  }

  private def signWalletInputs(tx: HasWalletInputs, locallySignedTx: Transaction, txFeerate: FeeratePerKw, walletInputs: WalletInputs): Behavior[Command] = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    // We create a PSBT with the non-wallet input already signed:
    val witnessScript = tx.redeemInfo match {
      case redeemInfo: RedeemInfo.SegwitV0 => fr.acinq.bitcoin.Script.parse(redeemInfo.redeemScript)
      case _: RedeemInfo.Taproot => null
    }
    val psbt = new Psbt(locallySignedTx).updateWitnessInput(
      tx.input.outPoint,
      tx.input.txOut,
      null,
      witnessScript,
      tx.sighash,
      java.util.Map.of(),
      null,
      null,
      java.util.Map.of()
    ).flatMap(_.finalizeWitnessInput(0, locallySignedTx.txIn.head.witness))
    psbt match {
      case Left(failure) =>
        log.error(s"cannot sign ${tx.desc}: $failure")
        unlockAndStop(locallySignedTx, TxPublisher.TxRejectedReason.UnknownTxFailure)
      case Right(psbt1) =>
        // The transaction that we want to fund/replace has one input, the first one.
        // Additional inputs are provided by our on-chain wallet.
        val ourWalletInputs = locallySignedTx.txIn.indices.tail
        // For "claim anchor txs" there is a single change output that sends to our on-chain wallet.
        // For htlc txs the first output is the one we want to fund/bump, additional outputs send to our on-chain wallet.
        val ourWalletOutputs = tx match {
          case _: ClaimAnchorTx => Seq(0)
          case _: SignedHtlcTx => locallySignedTx.txOut.indices.tail
        }
        context.pipeToSelf(bitcoinClient.signPsbt(psbt1, ourWalletInputs, ourWalletOutputs)) {
          case Success(processPsbtResponse) =>
            processPsbtResponse.finalTx_opt match {
              case Right(signedTx) =>
                val actualFees = kmp2scala(processPsbtResponse.psbt.computeFees())
                val actualWeight = tx match {
                  case _: ClaimAnchorTx => signedTx.weight() + commitTx.weight()
                  case _: SignedHtlcTx => signedTx.weight()
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
            replyTo ! TransactionReady(FundedTx(tx, Some(walletInputs), signedTx, txFeerate))
            Behaviors.stopped
          case SignWalletInputsFailed(reason) =>
            log.error(s"cannot sign ${tx.desc}: ", reason)
            // We reply with the failure only once the utxos are unlocked, otherwise there is a risk that our parent stops
            // itself, which will automatically stop us before we had a chance to unlock them.
            unlockAndStop(locallySignedTx, TxPublisher.TxRejectedReason.UnknownTxFailure)
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

  private def getWalletUtxos(inputs: Seq[TxIn]): Future[Seq[WalletInput]] = {
    Future.sequence(inputs.map(txIn => {
      bitcoinClient.getTransaction(txIn.outPoint.txid).flatMap {
        case inputTx if inputTx.txOut.size <= txIn.outPoint.index => Future.failed(new IllegalArgumentException(s"input ${inputTx.txid}:${txIn.outPoint.index} doesn't exist"))
        case inputTx => Future.successful(WalletInput(txIn, inputTx.txOut(txIn.outPoint.index.toInt)))
      }
    }))
  }

  private def addInputs(anchorTx: ClaimAnchorTx, targetFeerate: FeeratePerKw): Future[WalletInputs] = {
    // We want to pay the commit fees using CPFP. Since the commit tx may not be in the mempool yet (its feerate may be
    // below the minimum acceptable mempool feerate), we cannot ask bitcoind to fund a transaction that spends that
    // commit tx: it would fail because it cannot find the input in the utxo set. So we instead ask bitcoind to fund an
    // empty transaction that pays the fees we must add to the transaction package, and we then add the input spending
    // the commit tx and adjust the change output.
    val expectedCommitFee = Transactions.weight2fee(targetFeerate, commitTx.weight())
    val anchorFee = Transactions.weight2fee(targetFeerate, anchorTx.expectedWeight)
    val missingFee = expectedCommitFee - commitFee + anchorFee
    for {
      changeScript <- bitcoinClient.getChangePublicKeyScript()
      txNotFunded = Transaction(2, Nil, TxOut(dustLimit + missingFee, changeScript) :: Nil, 0)
      // We only use confirmed inputs for anchor transactions to be able to leverage 1-parent-1-child package relay.
      fundTxResponse <- bitcoinClient.fundTransaction(txNotFunded, targetFeerate, minInputConfirmations_opt = Some(1))
      walletInputs <- getWalletUtxos(fundTxResponse.tx.txIn)
    } yield {
      // We merge our dummy change output with the one added by Bitcoin Core, if any, and adjust the change amount to
      // pay the expected package feerate.
      val packageWeight = commitTx.weight() + anchorTx.commitmentFormat.anchorInputWeight + fundTxResponse.tx.weight()
      val expectedFee = Transactions.weight2fee(targetFeerate, packageWeight)
      val currentFee = commitFee + fundTxResponse.fee
      val changeAmount = (fundTxResponse.tx.txOut.map(_.amount).sum - expectedFee + currentFee).max(dustLimit)
      WalletInputs(walletInputs, changeOutput_opt = Some(TxOut(changeAmount, changeScript)))
    }
  }

  private def addInputs(htlcTx: SignedHtlcTx, targetFeerate: FeeratePerKw): Future[WalletInputs] = {
    val htlcInputWeight = htlcTx match {
      case _: HtlcSuccessTx => htlcTx.commitmentFormat.htlcSuccessInputWeight.toLong
      case _: HtlcTimeoutTx => htlcTx.commitmentFormat.htlcTimeoutInputWeight.toLong
    }
    for {
      fundTxResponse <- bitcoinClient.fundTransaction(htlcTx.tx, targetFeerate, changePosition = Some(1), externalInputsWeight = Map(htlcTx.input.outPoint -> htlcInputWeight))
      walletInputs <- getWalletUtxos(fundTxResponse.tx.txIn.filter(_.outPoint != htlcTx.input.outPoint))
    } yield {
      val changeOutput_opt = fundTxResponse.changePosition match {
        case Some(changeIndex) if changeIndex < fundTxResponse.tx.txOut.size => Some(fundTxResponse.tx.txOut(changeIndex))
        case _ => None
      }
      WalletInputs(walletInputs, changeOutput_opt)
    }
  }
}
