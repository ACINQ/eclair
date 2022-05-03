/*
 * Copyright 2022 ACINQ SAS
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
import fr.acinq.eclair.channel.InteractiveTx.{FundingContributions, InteractiveTxParams, toOutPoint}
import fr.acinq.eclair.wire.protocol.{TxAddInput, TxAddOutput}
import fr.acinq.eclair.{Logs, UInt64, randomBytes}
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Created by t-bast on 02/05/2022.
 */

/**
 * This actor adds wallet funds to an interactive-tx session.
 * Some inputs cannot be used in this protocol: this actor filters them out and retries funding until it finds a set of
 * inputs that the remote peer will accept.
 */
object InteractiveTxFunder {

  // @formatter:off
  sealed trait Command
  case class Fund(replyTo: ActorRef[Response], previousInputs: Seq[TxAddInput]) extends Command
  private case class FundTransactionResult(tx: Transaction) extends Command
  private case class InputDetails(usableInputs: Seq[TxAddInput], unusableInputs: Set[OutPoint]) extends Command
  private case class WalletFailure(t: Throwable) extends Command
  private case object UtxosUnlocked extends Command

  sealed trait Response
  case class FundingSucceeded(contributions: FundingContributions) extends Response
  case class FundingFailed(t: Throwable) extends Response
  // @formatter:on

  def apply(remoteNodeId: PublicKey, params: InteractiveTxParams, wallet: OnChainChannelFunder): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(params.channelId))) {
        Behaviors.receiveMessagePartial {
          case Fund(replyTo, previousInputs) => new InteractiveTxFunder(replyTo, params, wallet, context).start(previousInputs)
        }
      }
    }
  }

}

private class InteractiveTxFunder(replyTo: ActorRef[InteractiveTxFunder.Response],
                                  params: InteractiveTxParams,
                                  wallet: OnChainChannelFunder,
                                  context: ActorContext[InteractiveTxFunder.Command])(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) {

  import InteractiveTxFunder._

  private val log = context.log

  def start(previousInputs: Seq[TxAddInput]): Behavior[Command] = {
    val toFund = if (params.isInitiator) {
      // If we're the initiator, we need to pay the fees of the common fields of the transaction, even if we don't want
      // to contribute to the shared output.
      params.localAmount.max(params.dustLimit)
    } else {
      params.localAmount
    }
    log.debug("contributing {} to interactive-tx construction", toFund)
    if (toFund <= 0.sat) {
      // We're not the initiator and we don't want to contribute to the funding transaction.
      replyTo ! FundingSucceeded(FundingContributions(Nil, Nil))
      Behaviors.stopped
    } else {
      // We always double-spend all our previous inputs.
      val inputs = previousInputs.map(i => TxIn(toOutPoint(i), ByteVector.empty, i.sequence))
      val dummyTx = Transaction(2, inputs, Seq(TxOut(toFund, params.fundingPubkeyScript)), params.lockTime)
      fund(dummyTx, previousInputs, Set.empty)
    }
  }

  def fund(txNotFunded: Transaction, previousInputs: Seq[TxAddInput], unusableInputs: Set[OutPoint]): Behavior[Command] = {
    context.pipeToSelf(wallet.fundTransaction(txNotFunded, params.targetFeerate, replaceable = true, lockUtxos = true)) {
      case Failure(t) => WalletFailure(t)
      case Success(result) => FundTransactionResult(result.tx)
    }
    Behaviors.receiveMessagePartial {
      case FundTransactionResult(fundedTx) =>
        filterInputs(fundedTx, previousInputs, unusableInputs)
      case WalletFailure(t) =>
        replyTo ! FundingFailed(t)
        val toUnlock = previousInputs.map(i => toOutPoint(i)).toSet ++ unusableInputs
        unlockAndStop(toUnlock)
    }
  }

  def filterInputs(fundedTx: Transaction, previousInputs: Seq[TxAddInput], unusableInputs: Set[OutPoint]): Behavior[Command] = {
    context.pipeToSelf(Future.sequence(fundedTx.txIn.map(txIn => getInputDetails(txIn, previousInputs)))) {
      case Failure(t) => WalletFailure(t)
      case Success(results) => InputDetails(results.collect { case Right(i) => i }, results.collect { case Left(i) => i }.toSet)
    }
    Behaviors.receiveMessagePartial {
      case inputDetails: InputDetails =>
        if (inputDetails.unusableInputs.isEmpty) {
          // This funding iteration did not add any unusable inputs, so we can directly return the results.
          val changeOutputs = fundedTx.txOut
            .filter(_.publicKeyScript != params.fundingPubkeyScript)
            .map(txOut => TxAddOutput(params.channelId, generateSerialId(), txOut.amount, txOut.publicKeyScript))
          val outputs = if (params.isInitiator) {
            // If the initiator doesn't want to contribute, we should cancel out the dust amount artificially added previously.
            val initiatorChangeOutputs = if (params.localAmount == 0.sat) {
              changeOutputs.map(o => o.copy(amount = o.amount + params.dustLimit))
            } else {
              changeOutputs
            }
            // The initiator is responsible for adding the shared output.
            TxAddOutput(params.channelId, generateSerialId(), params.fundingAmount, params.fundingPubkeyScript) +: initiatorChangeOutputs
          } else {
            // The protocol only requires the non-initiator to pay the fees for its inputs and outputs, discounting the
            // common fields (shared output, version, nLockTime, etc). However, this is really hard to compute here,
            // because we don't know the witness size of our inputs (we let bitcoind handle that). For simplicity's sake,
            // we simply accept that we'll slightly overpay the fee (which speeds up channel confirmation).
            changeOutputs
          }
          log.info("added {} inputs and {} outputs to interactive tx", inputDetails.usableInputs.length, outputs.length)
          replyTo ! FundingSucceeded(FundingContributions(inputDetails.usableInputs, outputs))
          // We unlock the unusable inputs from previous iterations (if any) as they can be used outside of this protocol.
          unlockAndStop(unusableInputs)
        } else {
          // Some wallet inputs are unusable, so we must fund again to obtain usable inputs instead.
          log.info("retrying funding as some utxos cannot be used for interactive-tx construction: {}", inputDetails.unusableInputs.map(o => s"${o.txid}:${o.index}").mkString(","))
          val sanitizedTx = fundedTx.copy(
            txIn = fundedTx.txIn.filter(txIn => !inputDetails.unusableInputs.contains(txIn.outPoint)),
            // We remove the change output added by this funding iteration.
            txOut = fundedTx.txOut.filter(txOut => txOut.publicKeyScript == params.fundingPubkeyScript),
          )
          fund(sanitizedTx, inputDetails.usableInputs, unusableInputs ++ inputDetails.unusableInputs)
        }
      case WalletFailure(t) =>
        replyTo ! FundingFailed(t)
        val toUnlock = fundedTx.txIn.map(_.outPoint).toSet ++ unusableInputs
        unlockAndStop(toUnlock)
    }
  }

  def unlockAndStop(toUnlock: Set[OutPoint]): Behavior[Command] = {
    if (toUnlock.isEmpty) {
      context.self ! UtxosUnlocked
    } else {
      val dummyTx = Transaction(2, toUnlock.toSeq.map(o => TxIn(o, Nil, 0)), Nil, 0)
      context.pipeToSelf(wallet.rollback(dummyTx))(_ => UtxosUnlocked)
    }
    Behaviors.receiveMessagePartial {
      case UtxosUnlocked => Behaviors.stopped
    }
  }

  private def getInputDetails(txIn: TxIn, previousInputs: Seq[TxAddInput]): Future[Either[OutPoint, TxAddInput]] = {
    previousInputs.find(i => txIn.outPoint == toOutPoint(i)) match {
      case Some(previousInput) => Future.successful(Right(previousInput))
      case None => wallet.getTransaction(txIn.outPoint.txid).map(previousTx => {
        if (Transaction.write(previousTx).length > 65000) {
          // Wallet input transaction is too big to fit inside tx_add_input.
          Left(txIn.outPoint)
        } else if (!Script.isNativeWitnessScript(previousTx.txOut(txIn.outPoint.index.toInt).publicKeyScript)) {
          // Wallet input must be a native segwit input.
          Left(txIn.outPoint)
        } else {
          Right(TxAddInput(params.channelId, generateSerialId(), previousTx, txIn.outPoint.index, txIn.sequence))
        }
      })
    }
  }

  private def generateSerialId(): UInt64 = {
    // The initiator must use even values and the non-initiator odd values.
    if (params.isInitiator) {
      UInt64(randomBytes(8) & hex"fffffffffffffffe")
    } else {
      UInt64(randomBytes(8) | hex"0000000000000001")
    }
  }

}
