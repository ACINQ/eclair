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

package fr.acinq.eclair.channel.fund

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{OutPoint, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.OnChainChannelFunder
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol.TxAddInput
import fr.acinq.eclair.{Logs, UInt64}
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Random, Success}

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
  case class FundTransaction(replyTo: ActorRef[Response]) extends Command
  private case class FundTransactionResult(tx: Transaction, changePosition: Option[Int]) extends Command
  private case class InputDetails(usableInputs: Seq[OutgoingInput], unusableInputs: Set[UnusableInput]) extends Command
  private case class WalletFailure(t: Throwable) extends Command
  private case object UtxosUnlocked extends Command

  sealed trait Response
  case class FundingContributions(inputs: Seq[OutgoingInput], outputs: Seq[OutgoingOutput]) extends Response
  case object FundingFailed extends Response
  // @formatter:on

  def apply(remoteNodeId: PublicKey, fundingParams: InteractiveTxParams, purpose: InteractiveTxBuilder.Purpose, wallet: OnChainChannelFunder)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(fundingParams.channelId))) {
        Behaviors.receiveMessagePartial {
          case FundTransaction(replyTo) =>
            val actor = new InteractiveTxFunder(replyTo, fundingParams, purpose, wallet, context)
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

  private def sortFundingContributions(fundingParams: InteractiveTxParams, inputs: Seq[OutgoingInput], outputs: Seq[OutgoingOutput]): FundingContributions = {
    // We always randomize the order of inputs and outputs.
    val sortedInputs = Random.shuffle(inputs).zipWithIndex.map { case (input, i) =>
      val serialId = UInt64(2 * i + fundingParams.serialIdParity)
      input match {
        case input: Input.Local => input.copy(serialId = serialId)
        case input: Input.Shared => input.copy(serialId = serialId)
      }
    }
    val sortedOutputs = Random.shuffle(outputs).zipWithIndex.map { case (output, i) =>
      val serialId = UInt64(2 * (i + inputs.length) + fundingParams.serialIdParity)
      output match {
        case output: Output.Local.Change => output.copy(serialId = serialId)
        case output: Output.Local.NonChange => output.copy(serialId = serialId)
        case output: Output.Shared => output.copy(serialId = serialId)
      }
    }
    FundingContributions(sortedInputs, sortedOutputs)
  }

}

private class InteractiveTxFunder(replyTo: ActorRef[InteractiveTxFunder.Response],
                                  fundingParams: InteractiveTxParams,
                                  purpose: InteractiveTxBuilder.Purpose,
                                  wallet: OnChainChannelFunder,
                                  context: ActorContext[InteractiveTxFunder.Command])(implicit ec: ExecutionContext) {

  import InteractiveTxFunder._

  private val log = context.log
  private val previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction] = purpose match {
    case rbf: InteractiveTxBuilder.PreviousTxRbf => rbf.previousTransactions
    case _ => Nil
  }

  def start(): Behavior[Command] = {
    // We always double-spend all our previous inputs. It's technically overkill because we only really need to double
    // spend one input of each previous tx, but it's simpler and less error-prone this way. It also ensures that in
    // most cases, we won't need to add new inputs and will simply lower the change amount.
    // The balances in the shared input may have changed since the previous funding attempt, so we ignore the previous
    // shared input and will add it explicitly later.
    val previousWalletInputs = previousTransactions.flatMap(_.tx.localInputs).distinctBy(_.outPoint)
    val hasEnoughFunding = fundingParams.localAmount + fundingParams.localOutputs.map(_.amount).sum <= purpose.previousLocalBalance
    if (hasEnoughFunding) {
      log.info("we seem to have enough funding, no need to request wallet inputs from bitcoind")
      // We're not contributing to the shared output or we have enough funds in our shared input, so we don't need to
      // ask bitcoind for more inputs. When splicing some funds out, we assume that the caller has allocated enough
      // fees to pay for its outputs. If this is an RBF attempt, we don't change our contributions, because that would
      // force us to add wallet inputs. The caller may manually decrease the output amounts if it wants to actually
      // contribute to the RBF attempt.
      if (fundingParams.isInitiator) {
        val sharedInput = fundingParams.sharedInput_opt.toSeq.map(sharedInput => Input.Shared(UInt64(0), sharedInput.info.outPoint, 0xfffffffdL, purpose.previousLocalBalance, purpose.previousRemoteBalance))
        val sharedOutput = Output.Shared(UInt64(0), fundingParams.fundingPubkeyScript, fundingParams.localAmount, fundingParams.remoteAmount)
        val nonChangeOutputs = fundingParams.localOutputs.map(txOut => Output.Local.NonChange(UInt64(0), txOut.amount, txOut.publicKeyScript))
        val fundingContributions = sortFundingContributions(fundingParams, sharedInput ++ previousWalletInputs, sharedOutput +: nonChangeOutputs)
        replyTo ! fundingContributions
        Behaviors.stopped
      } else {
        val nonChangeOutputs = fundingParams.localOutputs.map(txOut => Output.Local.NonChange(UInt64(0), txOut.amount, txOut.publicKeyScript))
        val fundingContributions = sortFundingContributions(fundingParams, previousWalletInputs, nonChangeOutputs)
        replyTo ! fundingContributions
        Behaviors.stopped
      }
    } else {
      // The shared input contains funds that belong to us *and* funds that belong to our peer, so we add our peer's
      // balance to our shared output to cancel its effect on funding. We always include this shared input in our
      // transaction and will let bitcoind make sure the target feerate is reached.
      // Note that if the local amount is smaller than the dust limit, bitcoind will reject the funding attempt.
      val sharedTxOut = TxOut(fundingParams.localAmount + purpose.previousRemoteBalance, fundingParams.fundingPubkeyScript)
      val sharedTxIn = fundingParams.sharedInput_opt.toSeq.map(sharedInput => TxIn(sharedInput.info.outPoint, ByteVector.empty, 0xfffffffdL))
      val previousWalletTxIn = previousWalletInputs.map(i => TxIn(i.outPoint, ByteVector.empty, i.sequence))
      val dummyTx = Transaction(2, sharedTxIn ++ previousWalletTxIn, sharedTxOut +: fundingParams.localOutputs, fundingParams.lockTime)
      fund(dummyTx, previousWalletInputs, Set.empty)
    }
  }

  /**
   * We (ab)use bitcoind's `fundrawtransaction` to select available utxos from our wallet. Not all utxos are suitable
   * for dual funding though (e.g. they need to use segwit), so we filter them and iterate until we have a valid set of
   * inputs.
   */
  private def fund(txNotFunded: Transaction, currentInputs: Seq[OutgoingInput], unusableInputs: Set[UnusableInput]): Behavior[Command] = {
    val sharedInputWeight = fundingParams.sharedInput_opt.toSeq.map(i => i.info.outPoint -> i.weight).toMap
    context.pipeToSelf(wallet.fundTransaction(txNotFunded, fundingParams.targetFeerate, replaceable = true, externalInputsWeight = sharedInputWeight)) {
      case Failure(t) => WalletFailure(t)
      case Success(result) => FundTransactionResult(result.tx, result.changePosition)
    }
    Behaviors.receiveMessagePartial {
      case FundTransactionResult(fundedTx, changePosition) =>
        // Those inputs were already selected by bitcoind and considered unsuitable for interactive tx.
        val lockedUnusableInputs = fundedTx.txIn.map(_.outPoint).filter(o => unusableInputs.map(_.outpoint).contains(o))
        if (lockedUnusableInputs.nonEmpty) {
          // We're keeping unusable inputs locked to ensure that bitcoind doesn't use them for funding, otherwise we
          // could be stuck in an infinite loop where bitcoind constantly adds the same inputs that we cannot use.
          log.error("could not fund interactive tx: bitcoind included already known unusable inputs that should have been locked: {}", lockedUnusableInputs.mkString(","))
          sendResultAndStop(FundingFailed, currentInputs.map(_.outPoint).toSet ++ fundedTx.txIn.map(_.outPoint) ++ unusableInputs.map(_.outpoint))
        } else {
          filterInputs(fundedTx, changePosition, currentInputs, unusableInputs)
        }
      case WalletFailure(t) =>
        log.error("could not fund interactive tx: ", t)
        sendResultAndStop(FundingFailed, currentInputs.map(_.outPoint).toSet ++ unusableInputs.map(_.outpoint))
    }
  }

  /** Not all inputs are suitable for interactive tx construction. */
  private def filterInputs(fundedTx: Transaction, changePosition: Option[Int], currentInputs: Seq[OutgoingInput], unusableInputs: Set[UnusableInput]): Behavior[Command] = {
    context.pipeToSelf(Future.sequence(fundedTx.txIn.map(txIn => getInputDetails(txIn, currentInputs)))) {
      case Failure(t) => WalletFailure(t)
      case Success(results) => InputDetails(results.collect { case Right(i) => i }, results.collect { case Left(i) => i }.toSet)
    }
    Behaviors.receiveMessagePartial {
      case inputDetails: InputDetails if inputDetails.unusableInputs.isEmpty =>
        // This funding iteration did not add any unusable inputs, so we can directly return the results.
        // The transaction should still contain the funding output.
        if (fundedTx.txOut.count(_.publicKeyScript == fundingParams.fundingPubkeyScript) != 1) {
          log.error("funded transaction is missing the funding output: {}", fundedTx)
          sendResultAndStop(FundingFailed, fundedTx.txIn.map(_.outPoint).toSet ++ unusableInputs.map(_.outpoint))
        } else if (fundingParams.localOutputs.exists(o => !fundedTx.txOut.contains(o))) {
          log.error("funded transaction is missing one of our local outputs: {}", fundedTx)
          sendResultAndStop(FundingFailed, fundedTx.txIn.map(_.outPoint).toSet ++ unusableInputs.map(_.outpoint))
        } else {
          val nonChangeOutputs = fundingParams.localOutputs.map(o => Output.Local.NonChange(UInt64(0), o.amount, o.publicKeyScript))
          val changeOutput_opt = changePosition.map(i => Output.Local.Change(UInt64(0), fundedTx.txOut(i).amount, fundedTx.txOut(i).publicKeyScript))
          val fundingContributions = if (fundingParams.isInitiator) {
            // The initiator is responsible for adding the shared output and the shared input.
            val inputs = inputDetails.usableInputs
            val fundingOutput = Output.Shared(UInt64(0), fundingParams.fundingPubkeyScript, fundingParams.localAmount, fundingParams.remoteAmount)
            val outputs = Seq(fundingOutput) ++ nonChangeOutputs ++ changeOutput_opt.toSeq
            sortFundingContributions(fundingParams, inputs, outputs)
          } else {
            // The non-initiator must not include the shared input or the shared output.
            val inputs = inputDetails.usableInputs.filterNot(_.isInstanceOf[Input.Shared])
            // The protocol only requires the non-initiator to pay the fees for its inputs and outputs, discounting the
            // common fields (shared input, shared output, version, nLockTime, etc).
            // By using bitcoind's fundrawtransaction we are currently paying fees for those fields, but we can fix that
            // by increasing our change output accordingly.
            // If we don't have a change output, we will slightly overpay the fees: fixing this is not worth the extra
            // complexity of adding a change output, which would require a call to bitcoind to get a change address.
            val outputs = changeOutput_opt match {
              case Some(changeOutput) =>
                val commonWeight = fundingParams.sharedInput_opt match {
                  case Some(sharedInput) => sharedInput.weight.toInt + Transaction(2, Nil, Seq(TxOut(fundingParams.fundingAmount, fundingParams.fundingPubkeyScript)), 0).weight()
                  case None => Transaction(2, Nil, Seq(TxOut(fundingParams.fundingAmount, fundingParams.fundingPubkeyScript)), 0).weight()
                }
                val overpaidFees = Transactions.weight2fee(fundingParams.targetFeerate, commonWeight)
                nonChangeOutputs :+ changeOutput.copy(amount = changeOutput.amount + overpaidFees)
              case None => nonChangeOutputs
            }
            sortFundingContributions(fundingParams, inputs, outputs)
          }
          log.debug("added {} inputs and {} outputs to interactive tx", fundingContributions.inputs.length, fundingContributions.outputs.length)
          // We unlock the unusable inputs (if any) as they can be used outside of interactive-tx sessions.
          sendResultAndStop(fundingContributions, unusableInputs.map(_.outpoint))
        }
      case inputDetails: InputDetails if inputDetails.unusableInputs.nonEmpty =>
        // Some wallet inputs are unusable, so we must fund again to obtain usable inputs instead.
        log.info("retrying funding as some utxos cannot be used for interactive-tx construction: {}", inputDetails.unusableInputs.map(i => s"${i.outpoint.txid}:${i.outpoint.index}").mkString(","))
        val sanitizedTx = fundedTx.copy(
          txIn = fundedTx.txIn.filter(txIn => !inputDetails.unusableInputs.map(_.outpoint).contains(txIn.outPoint)),
          // We remove the change output added by this funding iteration.
          txOut = fundedTx.txOut.filter {
            case txOut if txOut.publicKeyScript == fundingParams.fundingPubkeyScript => true // shared output
            case txOut if fundingParams.localOutputs.contains(txOut) => true // non-change output
            case _ => false
          },
        )
        fund(sanitizedTx, inputDetails.usableInputs, unusableInputs ++ inputDetails.unusableInputs)
      case WalletFailure(t) =>
        log.error("could not get input details: ", t)
        sendResultAndStop(FundingFailed, fundedTx.txIn.map(_.outPoint).toSet ++ unusableInputs.map(_.outpoint))
    }
  }

  /**
   * @param txIn          input we'd like to include in the transaction, if suitable.
   * @param currentInputs already known valid inputs, we don't need to fetch the details again for those.
   * @return the input is either unusable (left) or we'll send a [[TxAddInput]] command to add it to the transaction (right).
   */
  private def getInputDetails(txIn: TxIn, currentInputs: Seq[OutgoingInput]): Future[Either[UnusableInput, OutgoingInput]] = {
    currentInputs.find(i => txIn.outPoint == i.outPoint) match {
      case Some(previousInput) => Future.successful(Right(previousInput))
      case None => fundingParams.sharedInput_opt match {
        case Some(sharedInput) if sharedInput.info.outPoint == txIn.outPoint =>
          // We don't need to validate the shared input, it comes from a valid lightning channel.
          Future.successful(Right(Input.Shared(UInt64(0), sharedInput.info.outPoint, txIn.sequence, purpose.previousLocalBalance, purpose.previousRemoteBalance)))
        case _ =>
          for {
            previousTx <- wallet.getTransaction(txIn.outPoint.txid)
            confirmations_opt <- if (fundingParams.requireConfirmedInputs.forLocal) wallet.getTxConfirmations(txIn.outPoint.txid) else Future.successful(None)
          } yield {
            if (canUseInput(fundingParams, txIn, previousTx, confirmations_opt.getOrElse(0))) {
              Right(Input.Local(UInt64(0), previousTx, txIn.outPoint.index, txIn.sequence))
            } else {
              Left(UnusableInput(txIn.outPoint))
            }
          }
      }
    }
  }

  private def sendResultAndStop(result: Response, toUnlock: Set[OutPoint]): Behavior[Command] = {
    // We don't unlock previous inputs as the corresponding funding transaction may confirm.
    val previousInputs = previousTransactions.flatMap(_.tx.localInputs.map(_.outPoint)).toSet
    val toUnlock1 = toUnlock -- previousInputs
    if (toUnlock1.isEmpty) {
      replyTo ! result
      Behaviors.stopped
    } else {
      log.debug("unlocking inputs: {}", toUnlock1.map(o => s"${o.txid}:${o.index}").mkString(","))
      val dummyTx = Transaction(2, toUnlock1.toSeq.map(o => TxIn(o, Nil, 0)), Nil, 0)
      context.pipeToSelf(wallet.rollback(dummyTx))(_ => UtxosUnlocked)
      Behaviors.receiveMessagePartial {
        case UtxosUnlocked =>
          replyTo ! result
          Behaviors.stopped
      }
    }
  }

}
