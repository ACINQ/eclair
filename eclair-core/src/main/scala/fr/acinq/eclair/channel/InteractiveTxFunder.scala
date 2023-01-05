/*
 * Copyright 2023 ACINQ SAS
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

package fr.acinq.eclair.channel

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{OutPoint, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.OnChainChannelFunder
import fr.acinq.eclair.channel.InteractiveTxBuilder.{InteractiveTxParams, SignedSharedTransaction, toOutPoint}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol.{TxAddInput, TxAddOutput}
import fr.acinq.eclair.{Logs, UInt64}
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 05/01/2023.
 */

/**
 * This actor creates the local contributions (inputs and outputs) for an interactive-tx session.
 * The actor will stop itself after sending the result to the caller.
 */
object InteractiveTxFunder {

  // @formatter:off
  sealed trait Command
  case class FundTransaction(replyTo: ActorRef[Response], previousTransactions: Seq[SignedSharedTransaction]) extends Command
  private case class FundTransactionResult(tx: Transaction) extends Command
  private case class InputDetails(usableInputs: Seq[TxAddInput], unusableInputs: Set[UnusableInput]) extends Command
  private case class WalletFailure(t: Throwable) extends Command
  private case object UtxosUnlocked extends Command

  sealed trait Response
  case class FundingContributions(inputs: Seq[TxAddInput], outputs: Seq[TxAddOutput]) extends Response
  case object FundingFailed extends Response
  // @formatter:on

  def apply(remoteNodeId: PublicKey, fundingParams: InteractiveTxParams, wallet: OnChainChannelFunder)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(fundingParams.channelId))) {
        Behaviors.receiveMessagePartial {
          case FundTransaction(replyTo, previousTransactions) =>
            val actor = new InteractiveTxFunder(replyTo, fundingParams, previousTransactions, wallet, context)
            actor.start()
        }
      }
    }
  }

  /** A wallet input that doesn't match interactive-tx construction requirements. */
  private case class UnusableInput(outpoint: OutPoint)

  private def canUseInput(fundingParams: InteractiveTxParams, txIn: TxIn, previousTx: Transaction, confirmations: Int): Boolean = {
    // Wallet input transaction must fit inside the tx_add_input message.
    val previousTxSizeOk = Transaction.write(previousTx).length <= 65000
    // Wallet input must be a native segwit input.
    val isNativeSegwit = Script.isNativeWitnessScript(previousTx.txOut(txIn.outPoint.index.toInt).publicKeyScript)
    // Wallet input must be confirmed if our peer requested it.
    val confirmationsOk = !fundingParams.requireConfirmedInputs.forLocal || confirmations > 0
    previousTxSizeOk && isNativeSegwit && confirmationsOk
  }

}

private class InteractiveTxFunder(replyTo: ActorRef[InteractiveTxFunder.Response],
                                  fundingParams: InteractiveTxParams,
                                  previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction],
                                  wallet: OnChainChannelFunder,
                                  context: ActorContext[InteractiveTxFunder.Command])(implicit ec: ExecutionContext) {

  import InteractiveTxFunder._

  private val log = context.log

  def start(): Behavior[Command] = {
    val toFund = if (fundingParams.isInitiator) {
      // If we're the initiator, we need to pay the fees of the common fields of the transaction, even if we don't want
      // to contribute to the shared output. We create a non-zero amount here to ensure that bitcoind will fund the
      // fees for the shared output (because it would otherwise reject a txOut with an amount of zero).
      fundingParams.localAmount.max(fundingParams.dustLimit)
    } else {
      fundingParams.localAmount
    }
    require(toFund >= 0.sat, "funding amount cannot be negative")
    log.debug("contributing {} to interactive-tx construction", toFund)
    if (toFund == 0.sat) {
      // We're not the initiator and we don't want to contribute to the funding transaction.
      replyTo ! FundingContributions(Nil, Nil)
      Behaviors.stopped
    } else {
      // We always double-spend all our previous inputs. It's technically overkill because we only really need to double
      // spend one input of each previous tx, but it's simpler and less error-prone this way. It also ensures that in
      // most cases, we won't need to add new inputs and will simply lower the change amount.
      val previousInputs = previousTransactions.flatMap(_.tx.localInputs).distinctBy(_.serialId)
      val dummyTx = Transaction(2, previousInputs.map(i => TxIn(toOutPoint(i), ByteVector.empty, i.sequence)), Seq(TxOut(toFund, fundingParams.fundingPubkeyScript)), fundingParams.lockTime)
      fund(dummyTx, previousInputs, Set.empty)
    }
  }

  /**
   * We (ab)use bitcoind's `fundrawtransaction` to select available utxos from our wallet. Not all utxos are suitable
   * for dual funding though (e.g. they need to use segwit), so we filter them and iterate until we have a valid set of
   * inputs.
   */
  private def fund(txNotFunded: Transaction, currentInputs: Seq[TxAddInput], unusableInputs: Set[UnusableInput]): Behavior[Command] = {
    context.pipeToSelf(wallet.fundTransaction(txNotFunded, fundingParams.targetFeerate, replaceable = true, lockUtxos = true)) {
      case Failure(t) => WalletFailure(t)
      case Success(result) => FundTransactionResult(result.tx)
    }
    Behaviors.receiveMessagePartial {
      case FundTransactionResult(fundedTx) =>
        // Those inputs were already selected by bitcoind and considered unsuitable for interactive tx.
        val lockedUnusableInputs = fundedTx.txIn.map(_.outPoint).filter(o => unusableInputs.map(_.outpoint).contains(o))
        if (lockedUnusableInputs.nonEmpty) {
          // We're keeping unusable inputs locked to ensure that bitcoind doesn't use them for funding, otherwise we
          // could be stuck in an infinite loop where bitcoind constantly adds the same inputs that we cannot use.
          log.error("could not fund interactive tx: bitcoind included already known unusable inputs that should have been locked: {}", lockedUnusableInputs.mkString(","))
          replyTo ! FundingFailed
          unlockAndStop(currentInputs.map(toOutPoint).toSet ++ fundedTx.txIn.map(_.outPoint) ++ unusableInputs.map(_.outpoint))
        } else {
          filterInputs(fundedTx, currentInputs, unusableInputs)
        }
      case WalletFailure(t) =>
        log.error("could not fund interactive tx: ", t)
        replyTo ! FundingFailed
        unlockAndStop(currentInputs.map(toOutPoint).toSet ++ unusableInputs.map(_.outpoint))
    }
  }

  /** Not all inputs are suitable for interactive tx construction. */
  private def filterInputs(fundedTx: Transaction, currentInputs: Seq[TxAddInput], unusableInputs: Set[UnusableInput]): Behavior[Command] = {
    context.pipeToSelf(Future.sequence(fundedTx.txIn.map(txIn => getInputDetails(txIn, currentInputs)))) {
      case Failure(t) => WalletFailure(t)
      case Success(results) => InputDetails(results.collect { case Right(i) => i }, results.collect { case Left(i) => i }.toSet)
    }
    Behaviors.receiveMessagePartial {
      case inputDetails: InputDetails if inputDetails.unusableInputs.isEmpty =>
        // This funding iteration did not add any unusable inputs, so we can directly return the results.
        val (fundingOutputs, otherOutputs) = fundedTx.txOut.partition(_.publicKeyScript == fundingParams.fundingPubkeyScript)
        // The transaction should still contain the funding output, with at most one change output added by bitcoind.
        if (fundingOutputs.length != 1) {
          log.error("funded transaction is missing the funding output: {}", fundedTx)
          replyTo ! FundingFailed
          unlockAndStop(fundedTx.txIn.map(_.outPoint).toSet ++ unusableInputs.map(_.outpoint))
        } else if (otherOutputs.length > 1) {
          log.error("funded transaction contains unexpected outputs: {}", fundedTx)
          replyTo ! FundingFailed
          unlockAndStop(fundedTx.txIn.map(_.outPoint).toSet ++ unusableInputs.map(_.outpoint))
        } else {
          val changeOutput_opt = otherOutputs.headOption.map(txOut => TxAddOutput(fundingParams.channelId, UInt64(0), txOut.amount, txOut.publicKeyScript))
          val outputs = if (fundingParams.isInitiator) {
            val initiatorChangeOutput = changeOutput_opt match {
              case Some(changeOutput) if fundingParams.localAmount == 0.sat =>
                // If the initiator doesn't want to contribute, we should cancel the dummy amount artificially added previously.
                val dummyFundingAmount = fundingOutputs.head.amount
                Seq(changeOutput.copy(amount = changeOutput.amount + dummyFundingAmount))
              case Some(changeOutput) => Seq(changeOutput)
              case None => Nil
            }
            // The initiator is responsible for adding the shared output.
            val fundingOutput = TxAddOutput(fundingParams.channelId, UInt64(0), fundingParams.fundingAmount, fundingParams.fundingPubkeyScript)
            fundingOutput +: initiatorChangeOutput
          } else {
            // The protocol only requires the non-initiator to pay the fees for its inputs and outputs, discounting the
            // common fields (shared output, version, nLockTime, etc). By using bitcoind's fundrawtransaction we are
            // currently paying fees for those fields, but we can fix that by increasing our change output accordingly.
            // If we don't have a change output, we will slightly overpay the fees: fixing this is not worth the extra
            // complexity of adding a change output, which would require a call to bitcoind to get a change address.
            changeOutput_opt match {
              case Some(changeOutput) =>
                val commonWeight = Transaction(2, Nil, Seq(TxOut(fundingParams.fundingAmount, fundingParams.fundingPubkeyScript)), 0).weight()
                val overpaidFees = Transactions.weight2fee(fundingParams.targetFeerate, commonWeight)
                Seq(changeOutput.copy(amount = changeOutput.amount + overpaidFees))
              case None => Nil
            }
          }
          log.debug("added {} inputs and {} outputs to interactive tx", inputDetails.usableInputs.length, outputs.length)
          // The initiator's serial IDs must use even values and the non-initiator odd values.
          val serialIdParity = if (fundingParams.isInitiator) 0 else 1
          val txAddInputs = inputDetails.usableInputs.zipWithIndex.map { case (input, i) => input.copy(serialId = UInt64(2 * i + serialIdParity)) }
          val txAddOutputs = outputs.zipWithIndex.map { case (output, i) => output.copy(serialId = UInt64(2 * (i + txAddInputs.length) + serialIdParity)) }
          replyTo ! FundingContributions(txAddInputs, txAddOutputs)
          // We unlock the unusable inputs (if any) as they can be used outside of interactive-tx sessions.
          unlockAndStop(unusableInputs.map(_.outpoint))
        }
      case inputDetails: InputDetails if inputDetails.unusableInputs.nonEmpty =>
        // Some wallet inputs are unusable, so we must fund again to obtain usable inputs instead.
        log.info("retrying funding as some utxos cannot be used for interactive-tx construction: {}", inputDetails.unusableInputs.map(i => s"${i.outpoint.txid}:${i.outpoint.index}").mkString(","))
        val sanitizedTx = fundedTx.copy(
          txIn = fundedTx.txIn.filter(txIn => !inputDetails.unusableInputs.map(_.outpoint).contains(txIn.outPoint)),
          // We remove the change output added by this funding iteration.
          txOut = fundedTx.txOut.filter(txOut => txOut.publicKeyScript == fundingParams.fundingPubkeyScript),
        )
        fund(sanitizedTx, inputDetails.usableInputs, unusableInputs ++ inputDetails.unusableInputs)
      case WalletFailure(t) =>
        log.error("could not get input details: ", t)
        replyTo ! FundingFailed
        unlockAndStop(fundedTx.txIn.map(_.outPoint).toSet ++ unusableInputs.map(_.outpoint))
    }
  }

  /**
   * @param txIn          input we'd like to include in the transaction, if suitable.
   * @param currentInputs already known valid inputs, we don't need to fetch the details again for those.
   * @return the input is either unusable (left) or we'll send a [[TxAddInput]] command to add it to the transaction (right).
   */
  private def getInputDetails(txIn: TxIn, currentInputs: Seq[TxAddInput]): Future[Either[UnusableInput, TxAddInput]] = {
    currentInputs.find(i => txIn.outPoint == toOutPoint(i)) match {
      case Some(previousInput) => Future.successful(Right(previousInput))
      case None =>
        for {
          previousTx <- wallet.getTransaction(txIn.outPoint.txid)
          confirmations_opt <- if (fundingParams.requireConfirmedInputs.forLocal) wallet.getTxConfirmations(txIn.outPoint.txid) else Future.successful(None)
        } yield {
          if (canUseInput(fundingParams, txIn, previousTx, confirmations_opt.getOrElse(0))) {
            Right(TxAddInput(fundingParams.channelId, UInt64(0), previousTx, txIn.outPoint.index, txIn.sequence))
          } else {
            Left(UnusableInput(txIn.outPoint))
          }
        }
    }
  }

  private def unlockAndStop(inputs: Set[OutPoint]): Behavior[Command] = {
    // We don't unlock previous inputs as the corresponding funding transaction may confirm.
    val previousInputs = previousTransactions.flatMap(_.tx.localInputs.map(toOutPoint)).toSet
    val toUnlock = inputs -- previousInputs
    if (toUnlock.isEmpty) {
      Behaviors.stopped
    } else {
      log.debug("unlocking inputs: {}", toUnlock.map(o => s"${o.txid}:${o.index}").mkString(","))
      val dummyTx = Transaction(2, toUnlock.toSeq.map(o => TxIn(o, Nil, 0)), Nil, 0)
      context.pipeToSelf(wallet.rollback(dummyTx))(_ => UtxosUnlocked)
      Behaviors.receiveMessagePartial {
        case UtxosUnlocked => Behaviors.stopped
      }
    }
  }

}
