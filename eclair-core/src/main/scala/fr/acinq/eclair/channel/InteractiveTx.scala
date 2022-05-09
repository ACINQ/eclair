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
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, Satoshi, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.blockchain.OnChainChannelFunder
import fr.acinq.eclair.blockchain.OnChainWallet.SignTransactionResponse
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

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

  /** A lighter version of our peer's TxAddInput that avoids storing potentially large messages in our DB. */
  case class RemoteTxAddInput(serialId: UInt64, outPoint: OutPoint, txOut: TxOut, sequence: Long)

  object RemoteTxAddInput {
    def apply(i: TxAddInput): RemoteTxAddInput = RemoteTxAddInput(i.serialId, toOutPoint(i), i.previousTx.txOut(i.previousTxOutput.toInt), i.sequence)
  }

  /** A lighter version of our peer's TxAddOutput that avoids storing potentially large messages in our DB. */
  case class RemoteTxAddOutput(serialId: UInt64, amount: Satoshi, pubkeyScript: ByteVector)

  object RemoteTxAddOutput {
    def apply(o: TxAddOutput): RemoteTxAddOutput = RemoteTxAddOutput(o.serialId, o.amount, o.pubkeyScript)
  }

  /** Unsigned transaction created collaboratively. */
  case class SharedTransaction(localInputs: Seq[TxAddInput], remoteInputs: Seq[RemoteTxAddInput], localOutputs: Seq[TxAddOutput], remoteOutputs: Seq[RemoteTxAddOutput], lockTime: Long) {
    val localAmountIn: Satoshi = localInputs.map(i => i.previousTx.txOut(i.previousTxOutput.toInt).amount).sum
    val remoteAmountIn: Satoshi = remoteInputs.map(_.txOut.amount).sum
    val totalAmountIn: Satoshi = localAmountIn + remoteAmountIn
    val fees: Satoshi = totalAmountIn - localOutputs.map(_.amount).sum - remoteOutputs.map(_.amount).sum

    def localFees(params: InteractiveTxParams): Satoshi = {
      val localAmountOut = params.localAmount + localOutputs.filter(_.pubkeyScript != params.fundingPubkeyScript).map(_.amount).sum
      localAmountIn - localAmountOut
    }

    def buildUnsignedTx(): Transaction = {
      val localTxIn = localInputs.map(i => (i.serialId, TxIn(toOutPoint(i), ByteVector.empty, i.sequence)))
      val remoteTxIn = remoteInputs.map(i => (i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence)))
      val inputs = (localTxIn ++ remoteTxIn).sortBy(_._1).map(_._2)
      val localTxOut = localOutputs.map(o => (o.serialId, TxOut(o.amount, o.pubkeyScript)))
      val remoteTxOut = remoteOutputs.map(o => (o.serialId, TxOut(o.amount, o.pubkeyScript)))
      val outputs = (localTxOut ++ remoteTxOut).sortBy(_._1).map(_._2)
      Transaction(2, inputs, outputs, lockTime)
    }
  }

  // @formatter:off
  sealed trait SignedSharedTransaction {
    def tx: SharedTransaction
  }
  case class PartiallySignedSharedTransaction(tx: SharedTransaction, localSigs: TxSignatures) extends SignedSharedTransaction
  case class FullySignedSharedTransaction(tx: SharedTransaction, localSigs: TxSignatures, remoteSigs: TxSignatures) extends SignedSharedTransaction {
    val signedTx: Transaction = {
      import tx._
      require(localSigs.witnesses.length == localInputs.length, "the number of local signatures does not match the number of local inputs")
      require(remoteSigs.witnesses.length == remoteInputs.length, "the number of remote signatures does not match the number of remote inputs")
      val signedLocalInputs = localInputs.sortBy(_.serialId).zip(localSigs.witnesses).map { case (i, w) => (i.serialId, TxIn(toOutPoint(i), ByteVector.empty, i.sequence, w)) }
      val signedRemoteInputs = remoteInputs.sortBy(_.serialId).zip(remoteSigs.witnesses).map { case (i, w) => (i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence, w)) }
      val inputs = (signedLocalInputs ++ signedRemoteInputs).sortBy(_._1).map(_._2)
      val localTxOut = localOutputs.map(o => (o.serialId, TxOut(o.amount, o.pubkeyScript)))
      val remoteTxOut = remoteOutputs.map(o => (o.serialId, TxOut(o.amount, o.pubkeyScript)))
      val outputs = (localTxOut ++ remoteTxOut).sortBy(_._1).map(_._2)
      Transaction(2, inputs, outputs, lockTime)
    }
    val feerate: FeeratePerKw = Transactions.fee2rate(tx.fees, signedTx.weight())
  }
  // @formatter:on

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
    val sharedTx = SharedTransaction(session.localInputs, session.remoteInputs.map(i => RemoteTxAddInput(i)), session.localOutputs, session.remoteOutputs.map(o => RemoteTxAddOutput(o)), params.lockTime)
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

    val localAmountOut = sharedTx.localOutputs.filter(_.pubkeyScript != params.fundingPubkeyScript).map(_.amount).sum + params.localAmount
    val remoteAmountOut = sharedTx.remoteOutputs.filter(_.pubkeyScript != params.fundingPubkeyScript).map(_.amount).sum + params.remoteAmount
    if (sharedTx.localAmountIn < localAmountOut || sharedTx.remoteAmountIn < remoteAmountOut) {
      log.warning("invalid interactive tx: input amount is too small (localIn={}, localOut={}, remoteIn={}, remoteOut={})", sharedTx.localAmountIn, localAmountOut, sharedTx.remoteAmountIn, remoteAmountOut)
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
    if (sharedTx.fees < minimumFee) {
      log.warning("invalid interactive tx: below the target feerate (target={}, actual={})", params.targetFeerate, Transactions.fee2rate(sharedTx.fees, minimumWeight))
      return Left(InvalidCompleteInteractiveTx(params.channelId))
    }

    Right(sharedTx, sharedOutputIndex)
  }

  def signTx(channelId: ByteVector32, unsignedTx: SharedTransaction, wallet: OnChainChannelFunder)(implicit ec: ExecutionContext): Future[PartiallySignedSharedTransaction] = {
    val tx = unsignedTx.buildUnsignedTx()
    if (unsignedTx.localInputs.isEmpty) {
      Future.successful(PartiallySignedSharedTransaction(unsignedTx, TxSignatures(channelId, tx.txid, Nil)))
    } else {
      wallet.signTransaction(tx, allowIncomplete = true).map {
        case SignTransactionResponse(signedTx, _) =>
          val localOutpoints = unsignedTx.localInputs.map(toOutPoint).toSet
          val sigs = signedTx.txIn.filter(txIn => localOutpoints.contains(txIn.outPoint)).map(_.witness)
          PartiallySignedSharedTransaction(unsignedTx, TxSignatures(channelId, tx.txid, sigs))
      }
    }
  }

  def addRemoteSigs(params: InteractiveTxParams, partiallySignedTx: PartiallySignedSharedTransaction, remoteSigs: TxSignatures): Either[ChannelException, FullySignedSharedTransaction] = {
    if (partiallySignedTx.tx.localInputs.length != partiallySignedTx.localSigs.witnesses.length) {
      return Left(InvalidFundingSignature(params.channelId, Some(partiallySignedTx.tx.buildUnsignedTx())))
    }
    if (partiallySignedTx.tx.remoteInputs.length != remoteSigs.witnesses.length) {
      return Left(InvalidFundingSignature(params.channelId, Some(partiallySignedTx.tx.buildUnsignedTx())))
    }
    val txWithSigs = FullySignedSharedTransaction(partiallySignedTx.tx, partiallySignedTx.localSigs, remoteSigs)
    if (remoteSigs.txId != txWithSigs.signedTx.txid) {
      return Left(InvalidFundingSignature(params.channelId, Some(partiallySignedTx.tx.buildUnsignedTx())))
    }
    // We allow a 5% error margin since witness size prediction could be inaccurate.
    if (params.localAmount > 0.sat && txWithSigs.feerate < params.targetFeerate * 0.95) {
      return Left(InvalidFundingFeerate(params.channelId, params.targetFeerate, txWithSigs.feerate))
    }
    val previousOutputs = {
      val localOutputs = txWithSigs.tx.localInputs.map(i => toOutPoint(i) -> i.previousTx.txOut(i.previousTxOutput.toInt)).toMap
      val remoteOutputs = txWithSigs.tx.remoteInputs.map(i => i.outPoint -> i.txOut).toMap
      localOutputs ++ remoteOutputs
    }
    Try(Transaction.correctlySpends(txWithSigs.signedTx, previousOutputs, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)) match {
      case Failure(_) => Left(InvalidFundingSignature(params.channelId, Some(partiallySignedTx.tx.buildUnsignedTx()))) // NB: we don't send our signatures to our peer.
      case Success(_) => Right(txWithSigs)
    }
  }

  /** Return a dummy transaction containing all local contributions. */
  def dummyLocalTx(session: InteractiveTxSession): Transaction = {
    val inputs = (session.localInputs ++ session.toSend.collect { case Left(addInput) => addInput }).map(i => TxIn(toOutPoint(i), ByteVector.empty, i.sequence))
    val outputs = (session.localOutputs ++ session.toSend.collect { case Right(addOutput) => addOutput }).map(o => TxOut(o.amount, o.pubkeyScript))
    Transaction(2, inputs, outputs, 0)
  }

  /** Return a dummy transaction containing local contributions from every given transaction. */
  def dummyLocalTx(sharedTxs: Seq[SharedTransaction]): Transaction = {
    val inputs = sharedTxs.flatMap(_.localInputs).distinctBy(_.serialId).map(i => TxIn(toOutPoint(i), ByteVector.empty, i.sequence))
    val outputs = sharedTxs.flatMap(_.localOutputs).distinctBy(_.serialId).map(o => TxOut(o.amount, o.pubkeyScript))
    Transaction(2, inputs, outputs, 0)
  }

  private def spendSameOutpoint(input1: TxAddInput, input2: TxAddInput): Boolean = {
    input1.previousTx.txid == input2.previousTx.txid && input1.previousTxOutput == input2.previousTxOutput
  }

  def toOutPoint(input: TxAddInput): OutPoint = OutPoint(input.previousTx, input.previousTxOutput.toInt)

}