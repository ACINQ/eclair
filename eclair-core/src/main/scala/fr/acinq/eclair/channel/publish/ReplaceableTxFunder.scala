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
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.{ByteVector32, OutPoint, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.FundTransactionOptions
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.channel.publish.ReplaceableTxPrePublisher._
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishLogContext
import fr.acinq.eclair.transactions.Transactions._

import scala.concurrent.duration.DurationInt
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
  case class FundTransaction(replyTo: ActorRef[FundingResult], cmd: TxPublisher.PublishReplaceableTx, txWithWitnessData: ReplaceableTxWithWitnessData, targetFeerate: FeeratePerKw) extends Command
  case object Stop extends Command

  private case class AddInputsOk(tx: ReplaceableTxWithWitnessData, fee: Satoshi) extends Command
  private case class AddInputsFailed(reason: Throwable) extends Command
  private case class SignWalletInputsOk(signedTx: Transaction) extends Command
  private case class SignWalletInputsFailed(reason: Throwable) extends Command
  private case object UtxosUnlocked extends Command
  // @formatter:on

  // @formatter:off
  sealed trait FundingResult
  case class FundingFailed(reason: TxPublisher.TxRejectedReason) extends FundingResult
  case class TransactionReady(tx: Transaction, amountIn: Satoshi) extends FundingResult {
    val fee: Satoshi = amountIn - tx.txOut.map(_.amount).sum
  }
  // @formatter:on

  def apply(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, loggingInfo: TxPublishLogContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(loggingInfo.mdc()) {
          new ReplaceableTxFunder(nodeParams, bitcoinClient, context, timers).start()
        }
      }
    }
  }

  /**
   * Adjust the amount of the change output of an anchor tx to match our target feerate.
   * We need this because fundrawtransaction doesn't allow us to leave non-wallet inputs, so we have to add them
   * afterwards which may bring the resulting feerate below our target.
   */
  def adjustAnchorOutputChange(unsignedTx: ClaimLocalAnchorWithWitnessData, commitTx: Transaction, amountIn: Satoshi, commitFeerate: FeeratePerKw, targetFeerate: FeeratePerKw, dustLimit: Satoshi): (ClaimLocalAnchorWithWitnessData, Satoshi) = {
    require(unsignedTx.txInfo.tx.txOut.size == 1, "funded transaction should have a single change output")
    // We take into account witness weight and adjust the fee to match our desired feerate.
    val dummySignedClaimAnchorTx = addSigs(unsignedTx.txInfo, PlaceHolderSig)
    // NB: we assume that our bitcoind wallet uses only P2WPKH inputs when funding txs.
    val estimatedWeight = commitTx.weight() + dummySignedClaimAnchorTx.tx.weight() + claimP2WPKHOutputWitnessWeight * (dummySignedClaimAnchorTx.tx.txIn.size - 1)
    val targetFee = weight2fee(targetFeerate, estimatedWeight) - weight2fee(commitFeerate, commitTx.weight())
    val amountOut = dustLimit.max(amountIn - targetFee)
    val updatedAnchorTx = unsignedTx.modify(_.txInfo.tx.txOut).setTo(Seq(unsignedTx.txInfo.tx.txOut.head.copy(amount = amountOut)))
    val fee = amountIn - updatedAnchorTx.txInfo.tx.txOut.map(_.amount).sum
    (updatedAnchorTx, fee)
  }

  /**
   * Adjust the change output of an htlc tx to match our target feerate.
   * We need this because fundrawtransaction doesn't allow us to leave non-wallet inputs, so we have to add them
   * afterwards which may bring the resulting feerate below our target.
   */
  def adjustHtlcTxChange(unsignedTx: HtlcWithWitnessData, amountIn: Satoshi, targetFeerate: FeeratePerKw, commitments: Commitments): (HtlcWithWitnessData, Satoshi) = {
    require(unsignedTx.txInfo.tx.txOut.size <= 2, "funded transaction should have at most one change output")
    val dummySignedTx = unsignedTx.txInfo match {
      case tx: HtlcSuccessTx => addSigs(tx, PlaceHolderSig, PlaceHolderSig, ByteVector32.Zeroes, commitments.commitmentFormat)
      case tx: HtlcTimeoutTx => addSigs(tx, PlaceHolderSig, PlaceHolderSig, commitments.commitmentFormat)
    }
    // We adjust the change output to obtain the targeted feerate.
    val estimatedWeight = dummySignedTx.tx.weight() + claimP2WPKHOutputWitnessWeight * (dummySignedTx.tx.txIn.size - 1)
    val targetFee = weight2fee(targetFeerate, estimatedWeight)
    val changeAmount = amountIn - dummySignedTx.tx.txOut.head.amount - targetFee
    val updatedHtlcTx = if (dummySignedTx.tx.txOut.length == 2 && changeAmount >= commitments.localParams.dustLimit) {
      unsignedTx match {
        case htlcSuccess: HtlcSuccessWithWitnessData => htlcSuccess.modify(_.txInfo.tx.txOut).setTo(Seq(htlcSuccess.txInfo.tx.txOut.head, htlcSuccess.txInfo.tx.txOut(1).copy(amount = changeAmount)))
        case htlcTimeout: HtlcTimeoutWithWitnessData => htlcTimeout.modify(_.txInfo.tx.txOut).setTo(Seq(htlcTimeout.txInfo.tx.txOut.head, htlcTimeout.txInfo.tx.txOut(1).copy(amount = changeAmount)))
      }
    } else {
      unsignedTx match {
        case htlcSuccess: HtlcSuccessWithWitnessData => htlcSuccess.modify(_.txInfo.tx.txOut).setTo(Seq(htlcSuccess.txInfo.tx.txOut.head))
        case htlcTimeout: HtlcTimeoutWithWitnessData => htlcTimeout.modify(_.txInfo.tx.txOut).setTo(Seq(htlcTimeout.txInfo.tx.txOut.head))
      }
    }
    val fee = amountIn - updatedHtlcTx.txInfo.tx.txOut.map(_.amount).sum
    (updatedHtlcTx, fee)
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
    val outputAmount = claimHtlcTx.txInfo.input.txOut.amount - targetFee
    if (outputAmount < dustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val updatedClaimHtlcTx = claimHtlcTx match {
        case claimHtlcSuccess: ClaimHtlcSuccessWithWitnessData => claimHtlcSuccess.modify(_.txInfo.tx.txOut).setTo(Seq(claimHtlcSuccess.txInfo.tx.txOut.head.copy(amount = outputAmount)))
        case claimHtlcTimeout: ClaimHtlcTimeoutWithWitnessData => claimHtlcTimeout.modify(_.txInfo.tx.txOut).setTo(Seq(claimHtlcTimeout.txInfo.tx.txOut.head.copy(amount = outputAmount)))
        // NB: we don't modify legacy claim-htlc-success, it's already signed.
        case legacyClaimHtlcSuccess: LegacyClaimHtlcSuccessWithWitnessData => legacyClaimHtlcSuccess
      }
      Right(updatedClaimHtlcTx)
    }
  }

}

private class ReplaceableTxFunder(nodeParams: NodeParams, bitcoinClient: BitcoinCoreClient, context: ActorContext[ReplaceableTxFunder.Command], timers: TimerScheduler[ReplaceableTxFunder.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import ReplaceableTxFunder._
  import nodeParams.{channelKeyManager => keyManager}

  private val log = context.log

  def start(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case FundTransaction(replyTo, cmd, txWithWitnessData, targetFeerate) => fund(replyTo, cmd, txWithWitnessData, targetFeerate)
      case Stop => Behaviors.stopped
    }
  }

  def fund(replyTo: ActorRef[FundingResult], cmd: TxPublisher.PublishReplaceableTx, txWithWitnessData: ReplaceableTxWithWitnessData, targetFeerate: FeeratePerKw): Behavior[Command] = {
    val commitFeerate = cmd.commitments.localCommit.spec.commitTxFeerate
    txWithWitnessData match {
      case claimLocalAnchor: ReplaceableTxPrePublisher.ClaimLocalAnchorWithWitnessData =>
        if (targetFeerate <= commitFeerate) {
          log.info("skipping {}: commit feerate is high enough (feerate={})", cmd.desc, commitFeerate)
          // We set retry = true in case the on-chain feerate rises before the commit tx is confirmed: if that happens
          // we'll want to claim our anchor to raise the feerate of the commit tx and get it confirmed faster.
          replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
          Behaviors.stopped
        } else {
          addWalletInputs(replyTo, cmd, claimLocalAnchor, targetFeerate)
        }
      case htlcTx: ReplaceableTxPrePublisher.HtlcWithWitnessData =>
        if (targetFeerate <= commitFeerate) {
          log.info("publishing {} without adding inputs: txid={}", cmd.desc, htlcTx.txInfo.tx.txid)
          sign(replyTo, cmd, txWithWitnessData, htlcTx.txInfo.fee)
        } else {
          addWalletInputs(replyTo, cmd, htlcTx, targetFeerate)
        }
      case claimHtlcTx: ReplaceableTxPrePublisher.ClaimHtlcWithWitnessData =>
        adjustClaimHtlcTxOutput(claimHtlcTx, targetFeerate, cmd.commitments.localParams.dustLimit) match {
          case Left(reason) =>
            // The htlc isn't economical to claim at the current feerate, but if the feerate goes down, we may want to claim it later.
            log.warn("skipping {}: {} (feerate={})", cmd.desc, reason, targetFeerate)
            replyTo ! FundingFailed(TxPublisher.TxRejectedReason.TxSkipped(retryNextBlock = true))
            Behaviors.stopped
          case Right(updatedClaimHtlcTx) =>
            sign(replyTo, cmd, updatedClaimHtlcTx, updatedClaimHtlcTx.txInfo.fee)
        }
    }
  }

  def addWalletInputs(replyTo: ActorRef[FundingResult], cmd: TxPublisher.PublishReplaceableTx, txWithWitnessData: ReplaceableTxWithWalletInputs, targetFeerate: FeeratePerKw): Behavior[Command] = {
    context.pipeToSelf(addInputs(txWithWitnessData, targetFeerate, cmd.commitments)) {
      case Success((fundedTx, fee)) => AddInputsOk(fundedTx, fee)
      case Failure(reason) => AddInputsFailed(reason)
    }
    Behaviors.receiveMessagePartial {
      case AddInputsOk(fundedTx, fee) =>
        log.info("added {} wallet input(s) and {} wallet output(s) to {}", fundedTx.txInfo.tx.txIn.length - 1, fundedTx.txInfo.tx.txOut.length - 1, cmd.desc)
        sign(replyTo, cmd, fundedTx, fee)
      case AddInputsFailed(reason) =>
        if (reason.getMessage.contains("Insufficient funds")) {
          log.warn("cannot add inputs to {}: {}", cmd.desc, reason.getMessage)
        } else {
          log.error("cannot add inputs to {}: {}", cmd.desc, reason)
        }
        replyTo ! FundingFailed(TxPublisher.TxRejectedReason.CouldNotFund)
        Behaviors.stopped
      case Stop =>
        // We've asked bitcoind to lock utxos, so we can't stop right now without unlocking them.
        // Since we don't know yet what utxos have been locked, we defer the message.
        timers.startSingleTimer(Stop, 1 second)
        Behaviors.same
    }
  }

  def sign(replyTo: ActorRef[FundingResult], cmd: TxPublisher.PublishReplaceableTx, fundedTx: ReplaceableTxWithWitnessData, fee: Satoshi): Behavior[Command] = {
    val channelKeyPath = keyManager.keyPath(cmd.commitments.localParams, cmd.commitments.channelConfig)
    fundedTx match {
      case ClaimLocalAnchorWithWitnessData(anchorTx) =>
        val localSig = keyManager.sign(anchorTx, keyManager.fundingPublicKey(cmd.commitments.localParams.fundingKeyPath), TxOwner.Local, cmd.commitments.commitmentFormat)
        val signedTx = ClaimLocalAnchorWithWitnessData(addSigs(anchorTx, localSig))
        signWalletInputs(replyTo, cmd, signedTx, fee)
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
          signWalletInputs(replyTo, cmd, signedTx, fee)
        } else {
          replyTo ! TransactionReady(signedTx.txInfo.tx, signedTx.txInfo.input.txOut.amount)
          Behaviors.stopped
        }
      case claimHtlcTx: ClaimHtlcWithWitnessData =>
        val sig = keyManager.sign(claimHtlcTx.txInfo, keyManager.htlcPoint(channelKeyPath), cmd.commitments.remoteCommit.remotePerCommitmentPoint, TxOwner.Local, cmd.commitments.commitmentFormat)
        val signedTx = claimHtlcTx match {
          case ClaimHtlcSuccessWithWitnessData(txInfo, preimage) => addSigs(txInfo, sig, preimage)
          case LegacyClaimHtlcSuccessWithWitnessData(txInfo, _) => txInfo
          case ClaimHtlcTimeoutWithWitnessData(txInfo) => addSigs(txInfo, sig)
        }
        replyTo ! TransactionReady(signedTx.tx, signedTx.input.txOut.amount)
        Behaviors.stopped
    }
  }

  def signWalletInputs(replyTo: ActorRef[FundingResult], cmd: TxPublisher.PublishReplaceableTx, locallySignedTx: ReplaceableTxWithWalletInputs, fee: Satoshi): Behavior[Command] = {
    locallySignedTx match {
      case ClaimLocalAnchorWithWitnessData(anchorTx) =>
        val commitInfo = BitcoinCoreClient.PreviousTx(anchorTx.input, anchorTx.tx.txIn.head.witness)
        context.pipeToSelf(bitcoinClient.signTransaction(anchorTx.tx, Seq(commitInfo))) {
          case Success(signedTx) => SignWalletInputsOk(signedTx.tx)
          case Failure(reason) => SignWalletInputsFailed(reason)
        }
      case htlcTx: HtlcWithWitnessData =>
        val inputInfo = BitcoinCoreClient.PreviousTx(htlcTx.txInfo.input, htlcTx.txInfo.tx.txIn.head.witness)
        context.pipeToSelf(bitcoinClient.signTransaction(htlcTx.txInfo.tx, Seq(inputInfo), allowIncomplete = true).map(signTxResponse => {
          // NB: bitcoind versions older than 0.21.1 messes up the witness stack for our htlc input, so we need to restore it.
          // See https://github.com/bitcoin/bitcoin/issues/21151
          htlcTx.txInfo.tx.copy(txIn = htlcTx.txInfo.tx.txIn.head +: signTxResponse.tx.txIn.tail)
        })) {
          case Success(signedTx) => SignWalletInputsOk(signedTx)
          case Failure(reason) => SignWalletInputsFailed(reason)
        }
    }
    Behaviors.receiveMessagePartial {
      case SignWalletInputsOk(signedTx) =>
        val amountIn = fee + signedTx.txOut.map(_.amount).sum
        replyTo ! TransactionReady(signedTx, amountIn)
        Behaviors.stopped
      case SignWalletInputsFailed(reason) =>
        log.error("cannot sign {}: {}", cmd.desc, reason)
        // We reply with the failure only once the utxos are unlocked, otherwise there is a risk that our parent stops
        // itself, which will automatically stop us before we had a chance to unlock them.
        unlockAndStop(replyTo, locallySignedTx.txInfo.input.outPoint, locallySignedTx.txInfo.tx, Some(TxPublisher.TxRejectedReason.UnknownTxFailure))
      case Stop =>
        // We have added wallet inputs, we need to unlock them before stopping.
        unlockAndStop(replyTo, locallySignedTx.txInfo.input.outPoint, locallySignedTx.txInfo.tx, None)
    }
  }

  def unlockAndStop(replyTo: ActorRef[FundingResult], input: OutPoint, tx: Transaction, failure_opt: Option[TxPublisher.TxRejectedReason]): Behavior[Command] = {
    val toUnlock = tx.txIn.filterNot(_.outPoint == input).map(_.outPoint)
    log.debug("unlocking utxos={}", toUnlock.mkString(", "))
    context.pipeToSelf(bitcoinClient.unlockOutpoints(toUnlock))(_ => UtxosUnlocked)
    Behaviors.receiveMessagePartial {
      case UtxosUnlocked =>
        log.debug("utxos unlocked")
        failure_opt.foreach(failure => replyTo ! FundingFailed(failure))
        Behaviors.stopped
      case Stop =>
        log.debug("waiting for utxos to be unlocked before stopping")
        Behaviors.same
    }
  }

  private def addInputs(tx: ReplaceableTxWithWalletInputs, targetFeerate: FeeratePerKw, commitments: Commitments): Future[(ReplaceableTxWithWalletInputs, Satoshi)] = {
    tx match {
      case anchorTx: ClaimLocalAnchorWithWitnessData => addInputs(anchorTx, targetFeerate, commitments)
      case htlcTx: HtlcWithWitnessData => addInputs(htlcTx, targetFeerate, commitments)
    }
  }

  private def addInputs(anchorTx: ClaimLocalAnchorWithWitnessData, targetFeerate: FeeratePerKw, commitments: Commitments): Future[(ClaimLocalAnchorWithWitnessData, Satoshi)] = {
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
      val unsignedTx = anchorTx.modify(_.txInfo.tx).setTo(fundTxResponse.tx.copy(txIn = anchorTx.txInfo.tx.txIn.head +: fundTxResponse.tx.txIn))
      adjustAnchorOutputChange(unsignedTx, commitTx, fundTxResponse.amountIn + AnchorOutputsCommitmentFormat.anchorAmount, commitFeerate, targetFeerate, dustLimit)
    })
  }

  private def addInputs(htlcTx: HtlcWithWitnessData, targetFeerate: FeeratePerKw, commitments: Commitments): Future[(HtlcWithWitnessData, Satoshi)] = {
    // NB: fundrawtransaction doesn't support non-wallet inputs, so we clear the input and re-add it later.
    val txNotFunded = htlcTx.txInfo.tx.copy(txIn = Nil, txOut = htlcTx.txInfo.tx.txOut.head.copy(amount = commitments.localParams.dustLimit) :: Nil)
    val htlcTxWeight = htlcTx.txInfo match {
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
        txIn = htlcTx.txInfo.tx.txIn ++ fundTxResponse.tx.txIn,
        txOut = htlcTx.txInfo.tx.txOut ++ fundTxResponse.tx.txOut.tail
      )
      val unsignedTx = htlcTx match {
        case htlcSuccess: HtlcSuccessWithWitnessData => htlcSuccess.modify(_.txInfo.tx).setTo(txWithHtlcInput)
        case htlcTimeout: HtlcTimeoutWithWitnessData => htlcTimeout.modify(_.txInfo.tx).setTo(txWithHtlcInput)
      }
      adjustHtlcTxChange(unsignedTx, fundTxResponse.amountIn + unsignedTx.txInfo.input.txOut.amount, targetFeerate, commitments)
    })
  }

}
