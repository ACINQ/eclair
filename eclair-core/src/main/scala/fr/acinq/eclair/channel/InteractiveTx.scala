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

import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, Satoshi, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.OnChainChannelFunder
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future}

/**
 * Created by t-bast on 27/04/2022.
 */

/**
 * Implementation of the interactive-tx protocol.
 * It allows two participants to collaborate to create a shared transaction.
 * This is a turn-based protocol: each participant sends one message and then waits for the other participant's response.
 */
object InteractiveTx {

  //                      Example flow:
  //     +-------+                             +-------+
  //     |       |---(1)-- tx_add_input ------>|       |
  //     |       |<--(2)-- tx_add_input -------|       |
  //     |       |---(3)-- tx_add_output ----->|       |
  //     |       |<--(4)-- tx_add_output ------|       |
  //     |       |---(5)-- tx_add_input ------>|       |
  //     |   A   |<--(6)-- tx_complete --------|   B   |
  //     |       |---(7)-- tx_remove_output -->|       |
  //     |       |<--(8)-- tx_add_output ------|       |
  //     |       |---(9)-- tx_complete ------->|       |
  //     |       |<--(10)- tx_complete --------|       |
  //     +-------+                             +-------+

  case class InteractiveTxParams(channelId: ByteVector32,
                                 isInitiator: Boolean,
                                 localAmount: Satoshi,
                                 remoteAmount: Satoshi,
                                 fundingPubkeyScript: ByteVector,
                                 lockTime: Long,
                                 dustLimit: Satoshi,
                                 targetFeerate: FeeratePerKw) {
    val fundingAmount: Satoshi = localAmount + remoteAmount
  }

  case class InteractiveTxSession(toSend: Seq[Either[TxAddInput, TxAddOutput]],
                                  localInputs: Seq[TxAddInput] = Nil,
                                  remoteInputs: Seq[TxAddInput] = Nil,
                                  localOutputs: Seq[TxAddOutput] = Nil,
                                  remoteOutputs: Seq[TxAddOutput] = Nil,
                                  txCompleteSent: Boolean = false,
                                  txCompleteReceived: Boolean = false,
                                  inputsReceivedCount: Int = 0,
                                  outputsReceivedCount: Int = 0) {
    val isComplete: Boolean = txCompleteSent && txCompleteReceived
  }

  /** Inputs and outputs we contribute to the funding transaction. */
  case class FundingContributions(inputs: Seq[TxAddInput], outputs: Seq[TxAddOutput])

  /** Unsigned transaction created collaboratively. */
  case class SharedTransaction(localInputs: Seq[TxAddInput], remoteInputs: Seq[TxAddInput], localOutputs: Seq[TxAddOutput], remoteOutputs: Seq[TxAddOutput], lockTime: Long) {
    def buildUnsignedTx(): Transaction = {
      val inputs = (localInputs ++ remoteInputs).sortBy(_.serialId).map(i => TxIn(toOutPoint(i), ByteVector.empty, i.sequence))
      val outputs = (localOutputs ++ remoteOutputs).sortBy(_.serialId).map(o => TxOut(o.amount, o.pubkeyScript))
      Transaction(2, inputs, outputs, lockTime)
    }
  }

  // We restrict the number of inputs / outputs that our peer can send us to ensure the protocol eventually ends.
  val MAX_INPUTS_OUTPUTS_RECEIVED = 4096

  def start(params: InteractiveTxParams, localContributions: FundingContributions): (InteractiveTxSession, Option[InteractiveTxConstructionMessage]) = {
    val toSend = localContributions.inputs.map(Left(_)) ++ localContributions.outputs.map(Right(_))
    if (params.isInitiator) {
      // The initiator sends the first message.
      send(InteractiveTxSession(toSend), params)
    } else {
      // The non-initiator waits for the initiator to send the first message.
      (InteractiveTxSession(toSend), None)
    }
  }

  private def send(session: InteractiveTxSession, params: InteractiveTxParams): (InteractiveTxSession, Option[InteractiveTxConstructionMessage]) = {
    session.toSend.headOption match {
      case Some(Left(addInput)) =>
        val next = session.copy(toSend = session.toSend.tail, localInputs = session.localInputs :+ addInput, txCompleteSent = false)
        (next, Some(addInput))
      case Some(Right(addOutput)) =>
        val next = session.copy(toSend = session.toSend.tail, localOutputs = session.localOutputs :+ addOutput, txCompleteSent = false)
        (next, Some(addOutput))
      case None =>
        val nextState = session.copy(txCompleteSent = true)
        (nextState, Some(TxComplete(params.channelId)))
    }
  }

  def receive(session: InteractiveTxSession, params: InteractiveTxParams, msg: InteractiveTxConstructionMessage): Either[ChannelException, (InteractiveTxSession, Option[InteractiveTxConstructionMessage])] = {
    msg match {
      case msg: HasSerialId if msg.serialId.toByteVector.bits.last != params.isInitiator =>
        Left(InvalidSerialId(params.channelId, msg.serialId))
      case addInput: TxAddInput =>
        if (session.inputsReceivedCount + 1 >= MAX_INPUTS_OUTPUTS_RECEIVED) {
          Left(TooManyInteractiveTxRounds(params.channelId))
        } else if (session.remoteInputs.exists(_.serialId == addInput.serialId)) {
          Left(DuplicateSerialId(params.channelId, addInput.serialId))
        } else if (session.localInputs.exists(i => spendSameOutpoint(i, addInput)) || session.remoteInputs.exists(i => spendSameOutpoint(i, addInput))) {
          Left(DuplicateInput(params.channelId, addInput.serialId, addInput.previousTx.txid, addInput.previousTxOutput))
        } else if (addInput.previousTx.txOut.length <= addInput.previousTxOutput) {
          Left(InputOutOfBounds(params.channelId, addInput.serialId, addInput.previousTx.txid, addInput.previousTxOutput))
        } else if (!Script.isNativeWitnessScript(addInput.previousTx.txOut(addInput.previousTxOutput.toInt).publicKeyScript)) {
          Left(NonSegwitInput(params.channelId, addInput.serialId, addInput.previousTx.txid, addInput.previousTxOutput))
        } else {
          val next = session.copy(
            remoteInputs = session.remoteInputs :+ addInput,
            inputsReceivedCount = session.inputsReceivedCount + 1,
            txCompleteReceived = false,
          )
          Right(send(next, params))
        }
      case addOutput: TxAddOutput =>
        if (session.outputsReceivedCount + 1 >= MAX_INPUTS_OUTPUTS_RECEIVED) {
          Left(TooManyInteractiveTxRounds(params.channelId))
        } else if (session.remoteOutputs.exists(_.serialId == addOutput.serialId)) {
          Left(DuplicateSerialId(params.channelId, addOutput.serialId))
        } else if (addOutput.amount < params.dustLimit) {
          Left(OutputBelowDust(params.channelId, addOutput.serialId, addOutput.amount, params.dustLimit))
        } else if (!Script.isNativeWitnessScript(addOutput.pubkeyScript)) {
          Left(NonSegwitOutput(params.channelId, addOutput.serialId))
        } else {
          val next = session.copy(
            remoteOutputs = session.remoteOutputs :+ addOutput,
            outputsReceivedCount = session.outputsReceivedCount + 1,
            txCompleteReceived = false,
          )
          Right(send(next, params))
        }
      case removeInput: TxRemoveInput =>
        session.remoteInputs.find(_.serialId == removeInput.serialId) match {
          case Some(_) =>
            val next = session.copy(
              remoteInputs = session.remoteInputs.filterNot(_.serialId == removeInput.serialId),
              txCompleteReceived = false,
            )
            Right(send(next, params))
          case None =>
            Left(UnknownSerialId(params.channelId, removeInput.serialId))
        }
      case removeOutput: TxRemoveOutput =>
        session.remoteOutputs.find(_.serialId == removeOutput.serialId) match {
          case Some(_) =>
            val next = session.copy(
              remoteOutputs = session.remoteOutputs.filterNot(_.serialId == removeOutput.serialId),
              txCompleteReceived = false,
            )
            Right(send(next, params))
          case None =>
            Left(UnknownSerialId(params.channelId, removeOutput.serialId))
        }
      case _: TxComplete =>
        val next = session.copy(txCompleteReceived = true)
        if (next.isComplete) {
          Right(next, None)
        } else {
          Right(send(next, params))
        }
    }
  }

  def validateTx(session: InteractiveTxSession, params: InteractiveTxParams)(implicit log: LoggingAdapter): Either[ChannelException, (SharedTransaction, Int)] = {
    val sharedTx = SharedTransaction(session.localInputs, session.remoteInputs, session.localOutputs, session.remoteOutputs, params.lockTime)
    val tx = sharedTx.buildUnsignedTx()

    if (!session.isComplete) {
      log.warning("invalid interactive tx: session isn't complete ({})", session)
      return Left(InvalidCompleteInteractiveTx(params.channelId))
    }

    if (tx.txIn.length > 252 || tx.txOut.length > 252) {
      log.warning("invalid interactive tx ({} inputs and {} outputs)", tx.txIn.length, tx.txOut.length)
      return Left(InvalidCompleteInteractiveTx(params.channelId))
    }

    val sharedOutputs = tx.txOut.zipWithIndex.filter(_._1.publicKeyScript == params.fundingPubkeyScript)
    if (sharedOutputs.length != 1) {
      log.warning("invalid interactive tx: funding outpoint not included (tx={})", tx)
      return Left(InvalidCompleteInteractiveTx(params.channelId))
    }
    val (sharedOutput, sharedOutputIndex) = sharedOutputs.head
    if (sharedOutput.amount != params.fundingAmount) {
      log.warning("invalid interactive tx: invalid funding amount (expected={}, actual={})", params.fundingAmount, sharedOutput.amount)
      return Left(InvalidCompleteInteractiveTx(params.channelId))
    }

    // NB: we have previously verified that the inputs exist in the previous transactions.
    val localAmountIn = sharedTx.localInputs.map(i => i.previousTx.txOut(i.previousTxOutput.toInt).amount).sum
    val localAmountOut = sharedTx.localOutputs.filter(_.pubkeyScript != params.fundingPubkeyScript).map(_.amount).sum + params.localAmount
    val remoteAmountIn = sharedTx.remoteInputs.map(i => i.previousTx.txOut(i.previousTxOutput.toInt).amount).sum
    val remoteAmountOut = sharedTx.remoteOutputs.filter(_.pubkeyScript != params.fundingPubkeyScript).map(_.amount).sum + params.remoteAmount
    if (localAmountIn < localAmountOut || remoteAmountIn < remoteAmountOut) {
      log.warning("invalid interactive tx: input amount is too small (localIn={}, localOut={}, remoteIn={}, remoteOut={})", localAmountIn, localAmountOut, remoteAmountIn, remoteAmountOut)
      return Left(InvalidCompleteInteractiveTx(params.channelId))
    }

    // The transaction isn't signed yet, so we estimate its weight knowing that all inputs are using native segwit.
    val minimumWitnessWeight = 110 // see Bolt 3
    val minimumWeight = tx.weight() + tx.txIn.length * minimumWitnessWeight
    if (minimumWeight > Transactions.MAX_STANDARD_TX_WEIGHT) {
      log.warning("invalid interactive tx: exceeds standard weight (weight={})", minimumWeight)
      return Left(InvalidCompleteInteractiveTx(params.channelId))
    }

    val minimumFee = Transactions.weight2fee(params.targetFeerate, minimumWeight)
    val fee = localAmountIn + remoteAmountIn - tx.txOut.map(_.amount).sum
    if (fee < minimumFee) {
      log.warning("invalid interactive tx: below the target feerate (target={}, actual={})", params.targetFeerate, Transactions.fee2rate(fee, minimumWeight))
      return Left(InvalidCompleteInteractiveTx(params.channelId))
    }

    Right(sharedTx, sharedOutputIndex)
  }

  def rollback(session: InteractiveTxSession, wallet: OnChainChannelFunder)(implicit ec: ExecutionContext): Future[Boolean] = {
    val inputs = (session.localInputs ++ session.toSend.collect { case Left(addInput) => addInput }).map(i => TxIn(toOutPoint(i), ByteVector.empty, i.sequence))
    val outputs = (session.localOutputs ++ session.toSend.collect { case Right(addOutput) => addOutput }).map(o => TxOut(o.amount, o.pubkeyScript))
    val dummyTx = Transaction(2, inputs, outputs, 0)
    wallet.rollback(dummyTx)
  }

  private def spendSameOutpoint(input1: TxAddInput, input2: TxAddInput): Boolean = {
    input1.previousTx.txid == input2.previousTx.txid && input1.previousTxOutput == input2.previousTxOutput
  }

  def toOutPoint(input: TxAddInput): OutPoint = OutPoint(input.previousTx, input.previousTxOutput.toInt)

}