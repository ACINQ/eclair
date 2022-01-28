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
import fr.acinq.bitcoin.{ByteVector32, OutPoint, Satoshi, Transaction}
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.{FundTransactionOptions, InputWeight}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.channel.publish.ReplaceableTxPrePublisher._
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.{NodeParams, NotificationsLogger}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 20/12/2021.
 */

/**
 * This actor funds a replaceable transaction to reach the requested feerate, signs it, and returns the resulting transaction to the caller.
 * This actor does not publish the resulting transaction.
 */
object ReplaceableTxFunder {

  // @formatter:off
  sealed trait Command
  case class FundTransaction(replyTo: ActorRef[FundingResult], cmd: TxPublisher.PublishReplaceableTx, tx: ReplaceableTxWithWitnessData, targetFeerate: FeeratePerKw) extends Command
  case class BumpTransaction(replyTo: ActorRef[FundingResult], cmd: TxPublisher.PublishReplaceableTx, previousTx: ReplaceableTxWithWitnessData, targetFeerate: FeeratePerKw) extends Command

  private case class PreviousTxFound(tx: ReplaceableTxWithWitnessData) extends Command
  private case object PreviousTxNotFound extends Command
  private case class AddInputsOk(tx: ReplaceableTxWithWitnessData, fee: Satoshi) extends Command
  private case class AddInputsFailed(reason: Throwable) extends Command
  private case class SignWalletInputsOk(signedTx: Transaction) extends Command
  private case class SignWalletInputsFailed(reason: Throwable) extends Command
  private case object UtxosUnlocked extends Command
  // @formatter:on

  case class FundedTx(signedTxWithWitnessData: ReplaceableTxWithWitnessData, feerate: FeeratePerKw, fee: Satoshi) {
    require(signedTxWithWitnessData.txInfo.tx.txIn.nonEmpty, "funded transaction must have inputs")
    require(signedTxWithWitnessData.txInfo.tx.txOut.nonEmpty, "funded transaction must have outputs")
    val signedTx: Transaction = signedTxWithWitnessData.txInfo.tx
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
          case FundTransaction(replyTo, cmd, tx, targetFeerate) => new ReplaceableTxFunder(nodeParams, replyTo, cmd, bitcoinClient, context).fund(tx, targetFeerate)
          case BumpTransaction(replyTo, cmd, previousTx, targetFeerate) => new ReplaceableTxFunder(nodeParams, replyTo, cmd, bitcoinClient, context).bump(previousTx, targetFeerate)
        }
      }
    }
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

  private def computeInputWeight(txWithWitnessData: ReplaceableTxWithWalletInputs, commitments: Commitments, targetFeerate: FeeratePerKw): Int = {
    txWithWitnessData match {
      case _: ClaimLocalAnchorWithWitnessData => inflateAnchorInputWeight(commitments, targetFeerate)
      case _: HtlcTimeoutWithWitnessData => commitments.commitmentFormat.htlcTimeoutInputWeight
      case _: HtlcSuccessWithWitnessData => commitments.commitmentFormat.htlcSuccessInputWeight
    }
  }

  /**
   * When funding an anchor transaction, we want this transaction to pay for its parent (the commit tx).
   * The feerate of the package containing both the commit tx and the anchor tx should reach our target feerate.
   * To do so, we just give bitcoind an inflated weight for the anchor input that contains the weight of the commit tx
   * and takes its current feerate into account.
   */
  private def inflateAnchorInputWeight(commitments: Commitments, targetFeerate: FeeratePerKw): Int = {
    val dummySignedCommitTx = addSigs(commitments.localCommit.commitTxAndRemoteSig.commitTx, PlaceHolderPubKey, PlaceHolderPubKey, PlaceHolderSig, PlaceHolderSig)
    val commitWeight = dummySignedCommitTx.tx.weight()
    val commitFeerate = commitments.localCommit.spec.commitTxFeerate
    (AnchorOutputsCommitmentFormat.anchorInputWeight + commitWeight * (1 - commitFeerate.toLong.toDouble / targetFeerate.toLong.toDouble)).ceil.toInt
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
        val commitFeerate = cmd.commitments.localCommit.spec.commitTxFeerate
        if (targetFeerate <= commitFeerate) {
          log.info("skipping {}: commit feerate is high enough (feerate={})", cmd.desc, commitFeerate)
          // We set retry = true in case the on-chain feerate rises before the commit tx is confirmed: if that happens
          // we'll want to claim our anchor to raise the feerate of the commit tx and get it confirmed faster.
          replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
          Behaviors.stopped
        } else {
          checkMempool(claimLocalAnchor, targetFeerate)
        }
      case htlcTx: HtlcWithWitnessData =>
        val htlcFeerate = cmd.commitments.localCommit.spec.htlcTxFeerate(cmd.commitments.commitmentFormat)
        if (targetFeerate <= htlcFeerate) {
          log.info("publishing {} without adding inputs: txid={}", cmd.desc, htlcTx.txInfo.tx.txid)
          sign(txWithWitnessData, htlcFeerate, htlcTx.txInfo.fee)
        } else {
          checkMempool(htlcTx, targetFeerate)
        }
      case claimHtlcTx: ClaimHtlcWithWitnessData =>
        adjustClaimHtlcTxOutput(claimHtlcTx, targetFeerate, cmd.commitments.localParams.dustLimit) match {
          case Left(reason) =>
            // The htlc isn't economical to claim at the current feerate, but if the feerate goes down, we may want to claim it later.
            log.warn("skipping {}: {} (feerate={})", cmd.desc, reason, targetFeerate)
            replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
            Behaviors.stopped
          case Right(updatedClaimHtlcTx) =>
            sign(updatedClaimHtlcTx, targetFeerate, claimHtlcTx.txInfo.fee)
        }
    }
  }

  /**
   * Before we ask bitcoind to fund the transaction, we check the mempool to see if we have already published a previous
   * version of that transaction (we may have lost the reference to it if we restarted).
   * If we do, we'll want to use the fee-bumping flow, because the initial funding flow will very likely not meet
   * bitcoind's RBF requirements (e.g. it may add new unconfirmed inputs which BIP 125 rule 2 forbids).
   */
  def checkMempool(txWithWitnessData: ReplaceableTxWithWalletInputs, targetFeerate: FeeratePerKw): Behavior[Command] = {
    context.pipeToSelf(findPreviousAttemptInMempool(txWithWitnessData)) {
      case Success(Some(tx)) => PreviousTxFound(tx)
      case _ => PreviousTxNotFound
    }
    Behaviors.receiveMessagePartial {
      case PreviousTxFound(previousTx) => bump(previousTx, targetFeerate)
      case PreviousTxNotFound =>
        val inputWeight = computeInputWeight(txWithWitnessData, cmd.commitments, targetFeerate)
        val fundingOptions = FundTransactionOptions(
          targetFeerate,
          lockUtxos = true,
          changePosition = Some(1),
          includeUnsafe = Some(true),
          inputWeights = Seq(InputWeight(txWithWitnessData.txInfo.input.outPoint, inputWeight))
        )
        addWalletInputs(txWithWitnessData, fundingOptions, targetFeerate)
    }
  }

  /**
   * When replacing a previous version of a transaction, we must ensure that we follow bitcoind's RBF requirements.
   * To maximize our chances, we keep all previous inputs and simply ask bitcoind to fund at a higher feerate, without
   * using unsafe inputs. This also ensures that bitcoind won't choose an input that is actually a descendant of the
   * transaction we're replacing (which we would obviously fail to publish).
   */
  def bump(previousTx: ReplaceableTxWithWitnessData, targetFeerate: FeeratePerKw): Behavior[Command] = {
    previousTx match {
      case txWithWalletInputs: ReplaceableTxWithWalletInputs =>
        // We remove the previous wallet change output and let bitcoind add a new one.
        val publishedTx = txWithWalletInputs.txInfo.tx
        val txNoChangeOutput = txWithWalletInputs match {
          case _: ClaimLocalAnchorWithWitnessData => publishedTx.copy(txOut = Seq(publishedTx.txOut.head.copy(amount = cmd.commitments.localParams.dustLimit)))
          case _: HtlcWithWitnessData => publishedTx.copy(txOut = publishedTx.txOut.take(1))
        }
        val inputWeight = computeInputWeight(txWithWalletInputs, cmd.commitments, targetFeerate)
        // NB: we don't allow unsafe inputs
        val fundingOptions = FundTransactionOptions(
          targetFeerate,
          lockUtxos = true,
          changePosition = Some(1),
          inputWeights = Seq(InputWeight(txWithWalletInputs.txInfo.input.outPoint, inputWeight))
        )
        addWalletInputs(txWithWalletInputs.updateTx(txNoChangeOutput), fundingOptions, targetFeerate)
      case claimHtlcTx: ClaimHtlcWithWitnessData =>
        adjustClaimHtlcTxOutput(claimHtlcTx, targetFeerate, cmd.commitments.localParams.dustLimit) match {
          case Left(reason) =>
            // The htlc isn't economical to claim at the current feerate, but if the feerate goes down, we may want to claim it later.
            log.warn("skipping {}: {} (feerate={})", cmd.desc, reason, targetFeerate)
            replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
            Behaviors.stopped
          case Right(updatedClaimHtlcTx) =>
            sign(updatedClaimHtlcTx, targetFeerate, claimHtlcTx.txInfo.fee)
        }
    }
  }

  def addWalletInputs(txWithWitnessData: ReplaceableTxWithWalletInputs, fundingOptions: FundTransactionOptions, targetFeerate: FeeratePerKw): Behavior[Command] = {
    context.pipeToSelf(addInputs(txWithWitnessData, fundingOptions)) {
      case Success((fundedTx, fee)) => AddInputsOk(fundedTx, fee)
      case Failure(reason) => AddInputsFailed(reason)
    }
    Behaviors.receiveMessagePartial {
      case AddInputsOk(fundedTx, fee) =>
        log.info("added {} wallet input(s) and {} wallet output(s) to {}", fundedTx.txInfo.tx.txIn.length - 1, fundedTx.txInfo.tx.txOut.length - 1, cmd.desc)
        sign(fundedTx, targetFeerate, fee)
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

  def sign(fundedTx: ReplaceableTxWithWitnessData, txFeerate: FeeratePerKw, fee: Satoshi): Behavior[Command] = {
    val channelKeyPath = keyManager.keyPath(cmd.commitments.localParams, cmd.commitments.channelConfig)
    fundedTx match {
      case ClaimLocalAnchorWithWitnessData(anchorTx) =>
        val localSig = keyManager.sign(anchorTx, keyManager.fundingPublicKey(cmd.commitments.localParams.fundingKeyPath), TxOwner.Local, cmd.commitments.commitmentFormat)
        val signedTx = ClaimLocalAnchorWithWitnessData(addSigs(anchorTx, localSig))
        signWalletInputs(signedTx, txFeerate, fee)
      case htlcTx: HtlcWithWitnessData =>
        val localPerCommitmentPoint = keyManager.commitmentPoint(channelKeyPath, cmd.commitments.localCommit.index)
        val localHtlcBasepoint = keyManager.htlcPoint(channelKeyPath)
        val localSig = keyManager.sign(htlcTx.txInfo, localHtlcBasepoint, localPerCommitmentPoint, TxOwner.Local, cmd.commitments.commitmentFormat)
        val signedTx = htlcTx match {
          case htlcSuccess: HtlcSuccessWithWitnessData => htlcSuccess.copy(txInfo = addSigs(htlcSuccess.txInfo, localSig, htlcSuccess.remoteSig, htlcSuccess.preimage, cmd.commitments.commitmentFormat))
          case htlcTimeout: HtlcTimeoutWithWitnessData => htlcTimeout.copy(txInfo = addSigs(htlcTimeout.txInfo, localSig, htlcTimeout.remoteSig, cmd.commitments.commitmentFormat))
        }
        val hasWalletInputs = htlcTx.txInfo.tx.txIn.size > 1
        if (hasWalletInputs) {
          signWalletInputs(signedTx, txFeerate, fee)
        } else {
          replyTo ! TransactionReady(FundedTx(signedTx, txFeerate, fee))
          Behaviors.stopped
        }
      case claimHtlcTx: ClaimHtlcWithWitnessData =>
        val sig = keyManager.sign(claimHtlcTx.txInfo, keyManager.htlcPoint(channelKeyPath), cmd.commitments.remoteCommit.remotePerCommitmentPoint, TxOwner.Local, cmd.commitments.commitmentFormat)
        val signedTx = claimHtlcTx match {
          case ClaimHtlcSuccessWithWitnessData(txInfo, preimage) => ClaimHtlcSuccessWithWitnessData(addSigs(txInfo, sig, preimage), preimage)
          case legacyClaimHtlcSuccess: LegacyClaimHtlcSuccessWithWitnessData => legacyClaimHtlcSuccess
          case ClaimHtlcTimeoutWithWitnessData(txInfo) => ClaimHtlcTimeoutWithWitnessData(addSigs(txInfo, sig))
        }
        replyTo ! TransactionReady(FundedTx(signedTx, txFeerate, fee))
        Behaviors.stopped
    }
  }

  def signWalletInputs(locallySignedTx: ReplaceableTxWithWalletInputs, txFeerate: FeeratePerKw, fee: Satoshi): Behavior[Command] = {
    val inputInfo = BitcoinCoreClient.PreviousTx(locallySignedTx.txInfo.input, locallySignedTx.txInfo.tx.txIn.head.witness)
    context.pipeToSelf(bitcoinClient.signTransaction(locallySignedTx.txInfo.tx, Seq(inputInfo))) {
      case Success(signedTx) => SignWalletInputsOk(signedTx.tx)
      case Failure(reason) => SignWalletInputsFailed(reason)
    }
    Behaviors.receiveMessagePartial {
      case SignWalletInputsOk(signedTx) =>
        val fullySignedTx = locallySignedTx.updateTx(signedTx)
        replyTo ! TransactionReady(FundedTx(fullySignedTx, txFeerate, fee))
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

  private def findPreviousAttemptInMempool(txWithWitnessData: ReplaceableTxWithWalletInputs): Future[Option[ReplaceableTxWithWalletInputs]] = {
    // TODO: this is highly inefficient, we must create a more efficient bitcoind RPC that achieves this
    // The issue with that approach could be that the tx is evicted from our mempool (e.g. fee too low) but not from miners' mempools
    // Then we will fund it again with unsafe inputs, but that will fail to replace the tx in miners' mempools
    // An alternative would be:
    //  - create a new DB for published txs indexed by input
    //  - get the latest attempt from that DB (if any)
    //    - if there is none, this is a new transaction, go through the initial funding flow
    //    - otherwise, check if the transaction is in our mempool
    //      - if it is, go through the bumping flow
    //      - otherwise go through the funding flow, but without unsafe inputs (the tx may be in someone else's mempool)
    bitcoinClient.getMempool().map(_.collectFirst { case tx if isPreviousAttempt(tx, txWithWitnessData) => txWithWitnessData.updateTx(tx) })
  }

  private def isPreviousAttempt(tx: Transaction, txWithWitnessData: ReplaceableTxWithWalletInputs): Boolean = {
    tx.txIn.find(_.outPoint == txWithWitnessData.txInfo.input.outPoint) match {
      case Some(txIn) => txWithWitnessData match {
        case _: ClaimLocalAnchorWithWitnessData => true
        // htlc transactions may also be spent by our peer: we verify that the mempool transaction really is an htlc
        // transaction and not our peer's claim-htlc transaction
        case _: HtlcTimeoutWithWitnessData => Seq(txIn.witness).collectFirst(Scripts.extractPaymentHashFromHtlcTimeout).nonEmpty
        case _: HtlcWithWitnessData => Seq(txIn.witness).collectFirst(Scripts.extractPreimageFromHtlcSuccess).nonEmpty
      }
      case None => false
    }
  }

  private def addInputs(tx: ReplaceableTxWithWalletInputs, fundingOptions: FundTransactionOptions): Future[(ReplaceableTxWithWalletInputs, Satoshi)] = {
    tx match {
      case anchorTx: ClaimLocalAnchorWithWitnessData => addInputs(anchorTx, fundingOptions)
      case htlcTx: HtlcWithWitnessData => addInputs(htlcTx, fundingOptions)
    }
  }

  private def addInputs(anchorTx: ClaimLocalAnchorWithWitnessData, fundingOptions: FundTransactionOptions): Future[(ClaimLocalAnchorWithWitnessData, Satoshi)] = {
    bitcoinClient.fundTransaction(anchorTx.txInfo.tx, fundingOptions).map(fundTxResponse => {
      val fee = fundTxResponse.fee
      if (fundTxResponse.tx.txOut.length > 1) {
        // We merge the outputs in a single change output, because we don't need two separate change outputs.
        // This will also slightly increase our feerate, which is good.
        val amountOut = fundTxResponse.tx.txOut.map(_.amount).sum
        val txSingleOutput = fundTxResponse.tx.copy(txOut = Seq(fundTxResponse.tx.txOut.last.copy(amount = amountOut)))
        (anchorTx.updateTx(txSingleOutput), fee)
      } else {
        (anchorTx.updateTx(fundTxResponse.tx), fee)
      }
    })
  }

  private def addInputs(htlcTx: HtlcWithWitnessData, fundingOptions: FundTransactionOptions): Future[(HtlcWithWitnessData, Satoshi)] = {
    bitcoinClient.fundTransaction(htlcTx.txInfo.tx, fundingOptions).map(fundTxResponse => (htlcTx.updateTx(fundTxResponse.tx), fundTxResponse.fee))
  }

}
