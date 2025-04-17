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
import fr.acinq.bitcoin.scalacompat.{KotlinUtils, OutPoint, Satoshi, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.OnChainChannelFunder
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.Output.Local.{Change, NonChange}
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.weight2fee
import fr.acinq.eclair.wire.protocol.{LiquidityAds, TxAddInput}
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
  private case class FundTransactionResult(tx: Transaction, fee: Satoshi, changePosition: Option[Int]) extends Command
  private case class InputDetails(usableInputs: Seq[OutgoingInput], unusableInputs: Set[UnusableInput]) extends Command
  private case class WalletFailure(t: Throwable) extends Command
  private case object UtxosUnlocked extends Command

  sealed trait Response
  case class FundingContributions(inputs: Seq[OutgoingInput], outputs: Seq[OutgoingOutput], excess_opt: Option[Satoshi]) extends Response
  case object FundingFailed extends Response
  // @formatter:on

  def apply(remoteNodeId: PublicKey, fundingParams: InteractiveTxParams, fundingPubkeyScript: ByteVector, purpose: InteractiveTxBuilder.FundingInfo, wallet: OnChainChannelFunder)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(fundingParams.channelId))) {
        Behaviors.receiveMessagePartial {
          case FundTransaction(replyTo) =>
            val actor = new InteractiveTxFunder(replyTo, fundingParams, fundingPubkeyScript, purpose, wallet, context)
            actor.start()
        }
      }
    }
  }

  /** A wallet input that doesn't match interactive-tx construction requirements. */
  private case class UnusableInput(outpoint: OutPoint)

  /**
   * Compute the funding contribution we're making to the channel output, by aggregating splice-in and splice-out and
   * paying on-chain fees either from our wallet inputs or our current channel balance.
   */
  def computeSpliceContribution(isInitiator: Boolean, sharedInput: SharedFundingInput, spliceInAmount: Satoshi, spliceOut: Seq[TxOut], targetFeerate: FeeratePerKw): Satoshi = {
    val fees = if (spliceInAmount == 0.sat) {
      val spliceOutputsWeight = spliceOut.map(KotlinUtils.scala2kmp).map(_.weight()).sum
      val weight = if (isInitiator) {
        // The initiator must add the shared input, the shared output and pay for the fees of the common transaction fields.
        val dummyTx = Transaction(2, Nil, Seq(sharedInput.info.txOut), 0)
        sharedInput.weight + dummyTx.weight() + spliceOutputsWeight
      } else {
        // The non-initiator only pays for the weights of their own inputs and outputs.
        spliceOutputsWeight
      }
      Transactions.weight2fee(targetFeerate, weight)
    } else {
      // If we're splicing some funds into the channel, bitcoind will be responsible for adding more funds to pay the
      // fees, so we don't need to pay them from our channel balance.
      0 sat
    }
    spliceInAmount - spliceOut.map(_.amount).sum - fees
  }

  private def needsAdditionalFunding(fundingParams: InteractiveTxParams, purpose: FundingInfo): Boolean = {
    if (fundingParams.isInitiator) {
      purpose match {
        case _: FundingTx | _: FundingTxRbf | _: DummyFundingTx =>
          // We're the initiator, but we may be purchasing liquidity without contributing to the funding transaction if
          // we're using on-the-fly funding. In that case it's acceptable that we don't pay the mining fees for the
          // shared output. Otherwise, we must contribute funds to pay the mining fees.
          fundingParams.localContribution > 0.sat || fundingParams.localOutputs.nonEmpty
        case _: SpliceTx | _: SpliceTxRbf =>
          // We're the initiator, we always have to pay on-chain fees for the shared input and output, even if we don't
          // splice in or out. If we're not paying those on-chain fees by lowering our channel contribution, we must add
          // more funding.
          fundingParams.localContribution + fundingParams.localOutputs.map(_.amount).sum >= 0.sat
      }
    } else {
      // We're not the initiator, so we don't have to pay on-chain fees for the common transaction fields.
      if (fundingParams.localOutputs.isEmpty) {
        // We're not splicing out: we only need to add funds if we're splicing in.
        fundingParams.localContribution > 0.sat
      } else {
        // We need to add funds if we're not paying on-chain fees by lowering our channel contribution.
        fundingParams.localContribution + fundingParams.localOutputs.map(_.amount).sum >= 0.sat
      }
    }
  }

  private def canUseInput(txIn: TxIn, previousTx: Transaction): Boolean = {
    // Wallet input transaction must fit inside the tx_add_input message.
    val previousTxSizeOk = Transaction.write(previousTx).length <= 65000
    // Wallet input must be a native segwit input.
    val isNativeSegwit = Script.isNativeWitnessScript(previousTx.txOut(txIn.outPoint.index.toInt).publicKeyScript)
    previousTxSizeOk && isNativeSegwit
  }

  def sortFundingContributions(fundingParams: InteractiveTxParams, inputs: Seq[OutgoingInput], outputs: Seq[OutgoingOutput], excess_opt: Option[Satoshi]): FundingContributions = {
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
    FundingContributions(sortedInputs, sortedOutputs, excess_opt)
  }

  /**
   * Instead of adding additional funding inputs to achieve a new feerate, reduce our change output amount. For changeless
   * funding contributions, any excess funding added to our local contribution above the purchased funding amount will be
   * treated as change that can contribute to fees. Returns a new funding contribution that spends the same inputs and
   * contributes up to the change output amount to achieve the new feerate. If we cannot achieve the new feerate with the
   * available change output, we will underpay the fees, which is acceptable.
   */
  def adjustRbfFunding(willFund_opt:  Option[LiquidityAds.WillFundPurchase], rbf: InteractiveTxBuilder.SpliceTxRbf, feerate: FeeratePerKw): (Satoshi, Seq[OutgoingOutput]) = {
    val localContribution = willFund_opt.map(_.purchase.amount).getOrElse(rbf.latestFundingTx.fundingParams.localContribution)
    val signedSharedTx = rbf.latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    val localFundedTx = signedSharedTx.signedTx.copy(
      txIn = signedSharedTx.signedTx.txIn.filter(i => signedSharedTx.tx.localInputs.exists(_.outPoint == i.outPoint)),
      txOut = signedSharedTx.signedTx.txOut.filter(txo => signedSharedTx.tx.localOutputs.exists(lo => lo.pubkeyScript == txo.publicKeyScript && lo.amount == txo.amount)))
    val localFees = weight2fee(feerate, localFundedTx.weight())
    val localNonChange = signedSharedTx.tx.localOutputs.collect { case o: NonChange => o.amount }.sum
    val change = signedSharedTx.tx.localInputs.map(i => i.txOut.amount).sum - localContribution - localNonChange - localFees
    val localOutputs = signedSharedTx.tx.localOutputs.collect {
      // remove our change output unless it is for more than the dust limit
      case o: Change if change > rbf.latestFundingTx.fundingParams.dustLimit => o.copy(amount = change)
      case o: NonChange => o
    }
    // If we don't have a change output, add any positive change amount to our local contribution.
    val localContribution1 = if (!localOutputs.exists(_.isInstanceOf[Change]) && change > 0.sat) {
      localContribution + change
    } else localContribution
    (localContribution1, localOutputs)
  }
}

private class InteractiveTxFunder(replyTo: ActorRef[InteractiveTxFunder.Response],
                                  fundingParams: InteractiveTxParams,
                                  fundingPubkeyScript: ByteVector,
                                  purpose: InteractiveTxBuilder.FundingInfo,
                                  wallet: OnChainChannelFunder,
                                  context: ActorContext[InteractiveTxFunder.Command])(implicit ec: ExecutionContext) {

  import InteractiveTxFunder._

  private val log = context.log
  private val previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction] = purpose match {
    case rbf: InteractiveTxBuilder.FundingTxRbf => rbf.previousTransactions
    case rbf: InteractiveTxBuilder.SpliceTxRbf => rbf.previousTransactions
    case _ => Nil
  }

  private val spliceInOnly = fundingParams.sharedInput_opt.nonEmpty && fundingParams.localContribution > 0.sat && fundingParams.localOutputs.isEmpty

  def start(): Behavior[Command] = {
    // We always double-spend all our previous inputs. It's technically overkill because we only really need to double
    // spend one input of each previous tx, but it's simpler and less error-prone this way. It also ensures that in
    // most cases, we won't need to add new inputs and will simply lower the change amount.
    // The balances in the shared input may have changed since the previous funding attempt, so we ignore the previous
    // shared input and will add it explicitly later.
    val previousWalletInputs = previousTransactions.flatMap(_.tx.localInputs).distinctBy(_.outPoint)
    if (!needsAdditionalFunding(fundingParams, purpose)) {
      log.info("we seem to have enough funding, no need to request wallet inputs from bitcoind")
      // We're not contributing to the shared output or we have enough funds in our shared input, so we don't need to
      // ask bitcoind for more inputs. When splicing some funds out, we assume that the caller has allocated enough
      // fees to pay for its outputs. If this is an RBF attempt, we don't change our contributions, because that would
      // force us to add wallet inputs. The caller may manually decrease the output amounts if it wants to actually
      // contribute to the RBF attempt.
      if (fundingParams.isInitiator) {
        val sharedInput = fundingParams.sharedInput_opt.toSeq.map(sharedInput => Input.Shared(UInt64(0), sharedInput.info.outPoint, sharedInput.info.txOut.publicKeyScript, 0xfffffffdL, purpose.previousLocalBalance, purpose.previousRemoteBalance, purpose.htlcBalance))
        val sharedOutput = Output.Shared(UInt64(0), fundingPubkeyScript, purpose.previousLocalBalance + fundingParams.localContribution, purpose.previousRemoteBalance + fundingParams.remoteContribution, purpose.htlcBalance)
        val nonChangeOutputs = fundingParams.localOutputs.map(txOut => Output.Local.NonChange(UInt64(0), txOut.amount, txOut.publicKeyScript))
        val fundingContributions = sortFundingContributions(fundingParams, sharedInput ++ previousWalletInputs, sharedOutput +: nonChangeOutputs, excess_opt = None)
        replyTo ! fundingContributions
        Behaviors.stopped
      } else {
        val nonChangeOutputs = fundingParams.localOutputs.map(txOut => Output.Local.NonChange(UInt64(0), txOut.amount, txOut.publicKeyScript))
        val fundingContributions = sortFundingContributions(fundingParams, previousWalletInputs, nonChangeOutputs, excess_opt = None)
        replyTo ! fundingContributions
        Behaviors.stopped
      }
    } else if (!fundingParams.isInitiator && spliceInOnly) {
      // We are splicing funds in without being the initiator (most likely responding to a liquidity ads).
      // We don't need to include the shared input, the other node will pay for its weight.
      // We create a dummy shared output with the amount we want to splice in, and bitcoind will make sure we match that
      // amount.
      val sharedTxOut = TxOut(fundingParams.localContribution, fundingPubkeyScript)
      val previousWalletTxIn = previousWalletInputs.map(i => TxIn(i.outPoint, ByteVector.empty, i.sequence))
      val dummyTx = Transaction(2, previousWalletTxIn, Seq(sharedTxOut), fundingParams.lockTime)
      fund(dummyTx, previousWalletInputs, Set.empty)
    } else {
      // The shared input contains funds that belong to us *and* funds that belong to our peer, so we add the previous
      // funding amount to our shared output to make sure bitcoind adds what is required for our local contribution.
      // We always include the shared input in our transaction and will let bitcoind make sure the target feerate is reached.
      // We will later subtract the fees for that input to ensure we don't overshoot the feerate: however, if bitcoind
      // doesn't add a change output, we won't be able to do so and will overpay miner fees.
      // Note that if the shared output amount is smaller than the dust limit, bitcoind will reject the funding attempt.
      val sharedTxOut = TxOut(purpose.previousFundingAmount + fundingParams.localContribution, fundingPubkeyScript)
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
    val sharedInputWeight = fundingParams.sharedInput_opt match {
      case Some(i) if txNotFunded.txIn.exists(_.outPoint == i.info.outPoint) => Map(i.info.outPoint -> i.weight.toLong)
      case _ => Map.empty[OutPoint, Long]
    }
    val feeBudget_opt = purpose match {
      case p: DummyFundingTx => p.feeBudget_opt
      case p: FundingTx => p.feeBudget_opt
      case p: FundingTxRbf => p.feeBudget_opt
      case p: SpliceTxRbf => p.feeBudget_opt
      case _ => None
    }
    val minConfirmations_opt = if (fundingParams.requireConfirmedInputs.forLocal) Some(1) else None
    context.pipeToSelf(wallet.fundTransaction(txNotFunded, fundingParams.targetFeerate, externalInputsWeight = sharedInputWeight, minInputConfirmations_opt = minConfirmations_opt, feeBudget_opt = feeBudget_opt)) {
      case Failure(t) => WalletFailure(t)
      case Success(result) =>
        FundTransactionResult(result.tx, result.fee, result.changePosition)
    }
    Behaviors.receiveMessagePartial {
      case FundTransactionResult(fundedTx, fee, changePosition) =>
        // Those inputs were already selected by bitcoind and considered unsuitable for interactive tx.
        val lockedUnusableInputs = fundedTx.txIn.map(_.outPoint).filter(o => unusableInputs.map(_.outpoint).contains(o))
        if (lockedUnusableInputs.nonEmpty) {
          // We're keeping unusable inputs locked to ensure that bitcoind doesn't use them for funding, otherwise we
          // could be stuck in an infinite loop where bitcoind constantly adds the same inputs that we cannot use.
          log.error("could not fund interactive tx: bitcoind included already known unusable inputs that should have been locked: {}", lockedUnusableInputs.mkString(","))
          sendResultAndStop(FundingFailed, currentInputs.map(_.outPoint).toSet ++ fundedTx.txIn.map(_.outPoint) ++ unusableInputs.map(_.outpoint))
        } else {
          filterInputs(fundedTx, changePosition, currentInputs, unusableInputs, fee)
        }
      case WalletFailure(t) =>
        log.error("could not fund interactive tx: ", t)
        sendResultAndStop(FundingFailed, currentInputs.map(_.outPoint).toSet ++ unusableInputs.map(_.outpoint))
    }
  }

  private def computeFee(tx: Transaction, inputs: Seq[OutgoingInput]): Satoshi = {
    val sharedInputWeight = fundingParams.sharedInput_opt match {
      case Some(i) if tx.txIn.exists(_.outPoint == i.info.outPoint) => Map(i.info.outPoint -> i.weight.toLong)
      case _ => Map.empty[OutPoint, Long]
    }
    // Only add splice-in inputs that are not shared inputs, as those are already accounted for in the shared input weight.
    val inputs1 = tx.txIn.filterNot(i => sharedInputWeight.contains(i.outPoint)).map { txIn =>
      inputs.find(_.outPoint == txIn.outPoint) match {
        case Some(i: Input.Local) =>
          Script.parse(i.previousTx.txOut(i.outPoint.index.toInt).publicKeyScript) match {
            // Must check for p2tr before p2wpkh, as a p2tr script can also be a native witness script.
            case script if Script.isPay2tr(script) =>
              txIn.copy(witness = Script.witnessKeyPathPay2tr(Transactions.PlaceHolderSig))
            case script if Script.isNativeWitnessScript(script) =>
              txIn.copy(witness = Script.witnessPay2wpkh(Transactions.PlaceHolderPubKey, ByteVector.fill(73)(0)))
            case _ =>
              txIn
          }
        case _ => txIn
      }
    }
    // Remove funding outputs that are not used for splice-out or as a shared output
    val outputs1 = tx.txOut.filter { txOut => fundingParams.localOutputs.contains(txOut) || (txOut.publicKeyScript == fundingPubkeyScript && fundingParams.isInitiator) }
    val dummySignedTx = tx.copy(txIn = inputs1, txOut = outputs1)
    Transactions.weight2fee(fundingParams.targetFeerate, dummySignedTx.weight() + sharedInputWeight.values.sum.toInt)
  }

  /** Not all inputs are suitable for interactive tx construction. */
  private def filterInputs(fundedTx: Transaction, changePosition: Option[Int], currentInputs: Seq[OutgoingInput], unusableInputs: Set[UnusableInput], fee: Satoshi): Behavior[Command] = {
    context.pipeToSelf(Future.sequence(fundedTx.txIn.map(txIn => getInputDetails(txIn, currentInputs)))) {
      case Failure(t) => WalletFailure(t)
      case Success(results) => InputDetails(results.collect { case Right(i) => i }, results.collect { case Left(i) => i }.toSet)
    }
    Behaviors.receiveMessagePartial {
      case inputDetails: InputDetails if inputDetails.unusableInputs.isEmpty =>
        // This funding iteration did not add any unusable inputs, so we can directly return the results.
        // The transaction should still contain the funding output.
        if (fundedTx.txOut.count(_.publicKeyScript == fundingPubkeyScript) != 1) {
          log.error("funded transaction is missing the funding output: {}", fundedTx)
          sendResultAndStop(FundingFailed, fundedTx.txIn.map(_.outPoint).toSet ++ unusableInputs.map(_.outpoint))
        } else if (fundingParams.localOutputs.exists(o => !fundedTx.txOut.contains(o))) {
          log.error("funded transaction is missing one of our local outputs: {}", fundedTx)
          sendResultAndStop(FundingFailed, fundedTx.txIn.map(_.outPoint).toSet ++ unusableInputs.map(_.outpoint))
        } else {
          // The fee from a changeless funding solution may have excess that can be added to our contribution.
          val excess = fee - computeFee(fundedTx, inputDetails.usableInputs)
          val excess_opt = if (changePosition.isEmpty && excess > 0.sat) Some(excess) else None
          val nonChangeOutputs = fundingParams.localOutputs.map(o => Output.Local.NonChange(UInt64(0), o.amount, o.publicKeyScript))
          val changeOutput_opt = changePosition.map(i => Output.Local.Change(UInt64(0), fundedTx.txOut(i).amount, fundedTx.txOut(i).publicKeyScript))
          val fundingContributions = if (fundingParams.isInitiator) {
            // The initiator is responsible for adding the shared output and the shared input.
            val inputs = inputDetails.usableInputs
            val fundingOutput = Output.Shared(UInt64(0), fundingPubkeyScript, purpose.previousLocalBalance + fundingParams.localContribution, purpose.previousRemoteBalance + fundingParams.remoteContribution, purpose.htlcBalance)
            val outputs = Seq(fundingOutput) ++ nonChangeOutputs ++ changeOutput_opt.toSeq
            sortFundingContributions(fundingParams, inputs, outputs, excess_opt)
          } else {
            // The non-initiator must not include the shared input or the shared output.
            val inputs = inputDetails.usableInputs.filterNot(_.isInstanceOf[Input.Shared])
            // The protocol only requires the non-initiator to pay the fees for its inputs and outputs, discounting the
            // common fields (shared input, shared output, version, nLockTime, etc).
            // By using bitcoind's fundrawtransaction we are currently paying fees for those fields, but we can fix that
            // by increasing our change output accordingly.
            // If we don't have a change output, we will slightly overpay the fees: fixing this is not worth the extra
            // complexity of adding a change output, which would require a call to bitcoind to get a change address and
            // create a tiny change output that would most likely be unusable and costly to spend.
            val outputs = changeOutput_opt match {
              case Some(changeOutput) =>
                val txWeightWithoutInput = Transaction(2, Nil, Seq(TxOut(fundingParams.fundingAmount, fundingPubkeyScript)), 0).weight()
                val commonWeight = fundingParams.sharedInput_opt match {
                  // If we are only splicing in, we didn't include the shared input in the funding transaction, but
                  // otherwise we did and must thus claim the corresponding fee back.
                  case Some(sharedInput) if !spliceInOnly => sharedInput.weight + txWeightWithoutInput
                  case _ => txWeightWithoutInput
                }
                val overpaidFees = Transactions.weight2fee(fundingParams.targetFeerate, commonWeight)
                nonChangeOutputs :+ changeOutput.copy(amount = changeOutput.amount + overpaidFees)
              case None => nonChangeOutputs
            }
            sortFundingContributions(fundingParams, inputs, outputs, excess_opt)
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
            case txOut if txOut.publicKeyScript == fundingPubkeyScript => true // shared output
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
          Future.successful(Right(Input.Shared(UInt64(0), sharedInput.info.outPoint, sharedInput.info.txOut.publicKeyScript, txIn.sequence, purpose.previousLocalBalance, purpose.previousRemoteBalance, purpose.htlcBalance)))
        case _ =>
          wallet.getTransaction(txIn.outPoint.txid).map(tx => {
            // Strip input witnesses to save space (there is a max size on txs due to lightning message limits).
            val txWithoutWitness = tx.copy(txIn = tx.txIn.map(_.copy(witness = ScriptWitness.empty)))
            if (canUseInput(txIn, txWithoutWitness)) {
              Right(Input.Local(UInt64(0), txWithoutWitness, txIn.outPoint.index, txIn.sequence))
            } else {
              Left(UnusableInput(txIn.outPoint))
            }
          })
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
