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

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, LexicographicalOrdering, OutPoint, Satoshi, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.OnChainChannelFunder
import fr.acinq.eclair.blockchain.OnChainWallet.SignTransactionResponse
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.TxOwner
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Logs, MilliSatoshiLong, UInt64, randomBytes, randomKey}
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Created by t-bast on 27/04/2022.
 */

/**
 * This actor implements the interactive-tx protocol.
 * It allows two participants to collaborate to create a shared transaction.
 * This is a turn-based protocol: each participant sends one message and then waits for the other participant's response.
 *
 * This actor returns [[InteractiveTxBuilder.Succeeded]] once we're ready to send our signatures for the shared
 * transaction. Once they are sent, we must remember it because the transaction may confirm (unless it is double-spent).
 *
 * Note that this actor doesn't handle the RBF messages: the parent actor must decide whether they accept an RBF attempt
 * and how much they want to contribute.
 *
 * This actor locks utxos for the duration of the protocol. When the protocol fails, it will automatically unlock them.
 * If this actor is killed, it may not be able to properly unlock utxos, so the parent should instead wait for this
 * actor to stop itself. The parent can use [[InteractiveTxBuilder.Abort]] to gracefully stop the protocol.
 */
object InteractiveTxBuilder {

  //                      Example flow:
  //     +-------+                             +-------+
  //     |       |-------- tx_add_input ------>|       |
  //     |       |<------- tx_add_input -------|       |
  //     |       |-------- tx_add_output ----->|       |
  //     |       |<------- tx_add_output ------|       |
  //     |       |-------- tx_add_input ------>|       |
  //     |   A   |<------- tx_complete --------|   B   |
  //     |       |-------- tx_remove_output -->|       |
  //     |       |<------- tx_add_output ------|       |
  //     |       |-------- tx_complete ------->|       |
  //     |       |<------- tx_complete --------|       |
  //     |       |-------- commit_sig -------->|       |
  //     |       |<------- commit_sig ---------|       |
  //     |       |-------- tx_signatures ----->|       |
  //     |       |<------- tx_signatures ------|       |
  //     +-------+                             +-------+

  // @formatter:off
  sealed trait Command
  case class Start(replyTo: ActorRef[Response], previousAttempts: Seq[SignedSharedTransaction]) extends Command
  sealed trait ReceiveMessage extends Command
  case class ReceiveTxMessage(msg: InteractiveTxConstructionMessage) extends ReceiveMessage
  case class ReceiveCommitSig(msg: CommitSig) extends ReceiveMessage
  case class ReceiveTxSigs(msg: TxSignatures) extends ReceiveMessage
  case object Abort extends Command
  private case class FundTransactionResult(tx: Transaction) extends Command
  private case class InputDetails(usableInputs: Seq[TxAddInput], unusableInputs: Set[OutPoint]) extends Command
  private case class SignTransactionResult(signedTx: PartiallySignedSharedTransaction, remoteSigs_opt: Option[TxSignatures]) extends Command
  private case class WalletFailure(t: Throwable) extends Command
  private case object UtxosUnlocked extends Command

  sealed trait Response
  case class SendMessage(msg: LightningMessage) extends Response
  case class Succeeded(fundingParams: InteractiveTxParams, sharedTx: SignedSharedTransaction, commitments: Commitments) extends Response
  sealed trait Failed extends Response { def cause: ChannelException }
  case class LocalFailure(cause: ChannelException) extends Failed
  case class RemoteFailure(cause: ChannelException) extends Failed
  // @formatter:on

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
    def localSigs: TxSignatures
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

  def apply(remoteNodeId: PublicKey,
            fundingParams: InteractiveTxParams,
            keyManager: ChannelKeyManager,
            localParams: LocalParams,
            remoteParams: RemoteParams,
            commitTxFeerate: FeeratePerKw,
            remoteFirstPerCommitmentPoint: PublicKey,
            channelFlags: ChannelFlags,
            channelConfig: ChannelConfig,
            channelFeatures: ChannelFeatures,
            wallet: OnChainChannelFunder)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(fundingParams.channelId))) {
          Behaviors.receiveMessagePartial {
            case Start(replyTo, previousAttempts) =>
              val actor = new InteractiveTxBuilder(replyTo, fundingParams, keyManager, localParams, remoteParams, commitTxFeerate, remoteFirstPerCommitmentPoint, channelFlags, channelConfig, channelFeatures, wallet, previousAttempts, timers, context)
              actor.start()
            case Abort => Behaviors.stopped
          }
        }
      }
    }
  }

  // We restrict the number of inputs / outputs that our peer can send us to ensure the protocol eventually ends.
  val MAX_INPUTS_OUTPUTS_RECEIVED = 4096

  def spendSameOutpoint(input1: TxAddInput, input2: TxAddInput): Boolean = {
    input1.previousTx.txid == input2.previousTx.txid && input1.previousTxOutput == input2.previousTxOutput
  }

  def toOutPoint(input: TxAddInput): OutPoint = OutPoint(input.previousTx, input.previousTxOutput.toInt)

  def addRemoteSigs(fundingParams: InteractiveTxParams, partiallySignedTx: PartiallySignedSharedTransaction, remoteSigs: TxSignatures): Either[ChannelException, FullySignedSharedTransaction] = {
    if (partiallySignedTx.tx.localInputs.length != partiallySignedTx.localSigs.witnesses.length) {
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.tx.buildUnsignedTx())))
    }
    if (partiallySignedTx.tx.remoteInputs.length != remoteSigs.witnesses.length) {
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.tx.buildUnsignedTx())))
    }
    val txWithSigs = FullySignedSharedTransaction(partiallySignedTx.tx, partiallySignedTx.localSigs, remoteSigs)
    if (remoteSigs.txId != txWithSigs.signedTx.txid) {
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.tx.buildUnsignedTx())))
    }
    // We allow a 5% error margin since witness size prediction could be inaccurate.
    if (fundingParams.localAmount > 0.sat && txWithSigs.feerate < fundingParams.targetFeerate * 0.95) {
      return Left(InvalidFundingFeerate(fundingParams.channelId, fundingParams.targetFeerate, txWithSigs.feerate))
    }
    val previousOutputs = {
      val localOutputs = txWithSigs.tx.localInputs.map(i => toOutPoint(i) -> i.previousTx.txOut(i.previousTxOutput.toInt)).toMap
      val remoteOutputs = txWithSigs.tx.remoteInputs.map(i => i.outPoint -> i.txOut).toMap
      localOutputs ++ remoteOutputs
    }
    Try(Transaction.correctlySpends(txWithSigs.signedTx, previousOutputs, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)) match {
      case Failure(_) => Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.tx.buildUnsignedTx()))) // NB: we don't send our signatures to our peer.
      case Success(_) => Right(txWithSigs)
    }
  }

}

private class InteractiveTxBuilder(replyTo: ActorRef[InteractiveTxBuilder.Response],
                                   fundingParams: InteractiveTxBuilder.InteractiveTxParams,
                                   keyManager: ChannelKeyManager,
                                   localParams: LocalParams,
                                   remoteParams: RemoteParams,
                                   commitTxFeerate: FeeratePerKw,
                                   remoteFirstPerCommitmentPoint: PublicKey,
                                   channelFlags: ChannelFlags,
                                   channelConfig: ChannelConfig,
                                   channelFeatures: ChannelFeatures,
                                   wallet: OnChainChannelFunder,
                                   previousAttempts: Seq[InteractiveTxBuilder.SignedSharedTransaction],
                                   timers: TimerScheduler[InteractiveTxBuilder.Command],
                                   context: ActorContext[InteractiveTxBuilder.Command])(implicit ec: ExecutionContext) {

  import InteractiveTxBuilder._

  private val log = context.log

  def start(): Behavior[Command] = {
    val toFund = if (fundingParams.isInitiator) {
      // If we're the initiator, we need to pay the fees of the common fields of the transaction, even if we don't want
      // to contribute to the shared output.
      fundingParams.localAmount.max(fundingParams.dustLimit)
    } else {
      fundingParams.localAmount
    }
    log.debug("contributing {} to interactive-tx construction", toFund)
    if (toFund <= 0.sat) {
      // We're not the initiator and we don't want to contribute to the funding transaction.
      buildTx(FundingContributions(Nil, Nil))
    } else {
      // We always double-spend all our previous inputs.
      val previousInputs = previousAttempts.flatMap(_.tx.localInputs).distinctBy(_.serialId)
      val dummyTx = Transaction(2, previousInputs.map(i => TxIn(toOutPoint(i), ByteVector.empty, i.sequence)), Seq(TxOut(toFund, fundingParams.fundingPubkeyScript)), fundingParams.lockTime)
      fund(dummyTx, previousInputs, Set.empty)
    }
  }

  def fund(txNotFunded: Transaction, currentInputs: Seq[TxAddInput], unusableInputs: Set[OutPoint]): Behavior[Command] = {
    context.pipeToSelf(wallet.fundTransaction(txNotFunded, fundingParams.targetFeerate, replaceable = true, lockUtxos = true)) {
      case Failure(t) => WalletFailure(t)
      case Success(result) => FundTransactionResult(result.tx)
    }
    Behaviors.receiveMessagePartial {
      case FundTransactionResult(fundedTx) =>
        filterInputs(fundedTx, currentInputs, unusableInputs)
      case WalletFailure(t) =>
        log.error("could not fund dual-funded channel: ", t)
        // We use a generic exception and don't send the internal error to the peer.
        replyTo ! LocalFailure(ChannelFundingError(fundingParams.channelId))
        unlockAndStop(currentInputs.map(toOutPoint).toSet ++ unusableInputs)
      case msg: ReceiveMessage =>
        timers.startSingleTimer(msg, 1 second)
        Behaviors.same
      case Abort =>
        timers.startSingleTimer(Abort, 1 second)
        Behaviors.same
    }
  }

  def filterInputs(fundedTx: Transaction, currentInputs: Seq[TxAddInput], unusableInputs: Set[OutPoint]): Behavior[Command] = {
    context.pipeToSelf(Future.sequence(fundedTx.txIn.map(txIn => getInputDetails(txIn, currentInputs)))) {
      case Failure(t) => WalletFailure(t)
      case Success(results) => InputDetails(results.collect { case Right(i) => i }, results.collect { case Left(i) => i }.toSet)
    }
    Behaviors.receiveMessagePartial {
      case inputDetails: InputDetails =>
        if (inputDetails.unusableInputs.isEmpty) {
          // This funding iteration did not add any unusable inputs, so we can directly return the results.
          val changeOutputs = fundedTx.txOut
            .filter(_.publicKeyScript != fundingParams.fundingPubkeyScript)
            .map(txOut => TxAddOutput(fundingParams.channelId, generateSerialId(), txOut.amount, txOut.publicKeyScript))
          val outputs = if (fundingParams.isInitiator) {
            // If the initiator doesn't want to contribute, we should cancel out the dust amount artificially added previously.
            val initiatorChangeOutputs = if (fundingParams.localAmount == 0.sat) {
              changeOutputs.map(o => o.copy(amount = o.amount + fundingParams.dustLimit))
            } else {
              changeOutputs
            }
            // The initiator is responsible for adding the shared output.
            TxAddOutput(fundingParams.channelId, generateSerialId(), fundingParams.fundingAmount, fundingParams.fundingPubkeyScript) +: initiatorChangeOutputs
          } else {
            // The protocol only requires the non-initiator to pay the fees for its inputs and outputs, discounting the
            // common fields (shared output, version, nLockTime, etc). However, this is really hard to compute here,
            // because we don't know the witness size of our inputs (we let bitcoind handle that). For simplicity's sake,
            // we simply accept that we'll slightly overpay the fee (which speeds up channel confirmation).
            changeOutputs
          }
          log.info("added {} inputs and {} outputs to interactive tx", inputDetails.usableInputs.length, outputs.length)
          // We unlock the unusable inputs from previous iterations (if any) as they can be used outside of this protocol.
          unlock(unusableInputs)
          buildTx(FundingContributions(inputDetails.usableInputs, outputs))
        } else {
          // Some wallet inputs are unusable, so we must fund again to obtain usable inputs instead.
          log.info("retrying funding as some utxos cannot be used for interactive-tx construction: {}", inputDetails.unusableInputs.map(o => s"${o.txid}:${o.index}").mkString(","))
          val sanitizedTx = fundedTx.copy(
            txIn = fundedTx.txIn.filter(txIn => !inputDetails.unusableInputs.contains(txIn.outPoint)),
            // We remove the change output added by this funding iteration.
            txOut = fundedTx.txOut.filter(txOut => txOut.publicKeyScript == fundingParams.fundingPubkeyScript),
          )
          fund(sanitizedTx, inputDetails.usableInputs, unusableInputs ++ inputDetails.unusableInputs)
        }
      case WalletFailure(t) =>
        log.error("could not get input details: ", t)
        // We use a generic exception and don't send the internal error to the peer.
        replyTo ! LocalFailure(ChannelFundingError(fundingParams.channelId))
        unlockAndStop(fundedTx.txIn.map(_.outPoint).toSet ++ unusableInputs)
      case msg: ReceiveMessage =>
        timers.startSingleTimer(msg, 1 second)
        Behaviors.same
      case Abort =>
        timers.startSingleTimer(Abort, 1 second)
        Behaviors.same
    }
  }

  private def getInputDetails(txIn: TxIn, currentInputs: Seq[TxAddInput]): Future[Either[OutPoint, TxAddInput]] = {
    currentInputs.find(i => txIn.outPoint == toOutPoint(i)) match {
      case Some(previousInput) => Future.successful(Right(previousInput))
      case None => wallet.getTransaction(txIn.outPoint.txid).map(previousTx => {
        if (Transaction.write(previousTx).length > 65000) {
          // Wallet input transaction is too big to fit inside tx_add_input.
          Left(txIn.outPoint)
        } else if (!Script.isNativeWitnessScript(previousTx.txOut(txIn.outPoint.index.toInt).publicKeyScript)) {
          // Wallet input must be a native segwit input.
          Left(txIn.outPoint)
        } else {
          Right(TxAddInput(fundingParams.channelId, generateSerialId(), previousTx, txIn.outPoint.index, txIn.sequence))
        }
      })
    }
  }

  def buildTx(localContributions: FundingContributions): Behavior[Command] = {
    val toSend = localContributions.inputs.map(Left(_)) ++ localContributions.outputs.map(Right(_))
    if (fundingParams.isInitiator) {
      // The initiator sends the first message.
      send(InteractiveTxSession(toSend))
    } else {
      // The non-initiator waits for the initiator to send the first message.
      receive(InteractiveTxSession(toSend))
    }
  }

  def send(session: InteractiveTxSession): Behavior[Command] = {
    session.toSend.headOption match {
      case Some(Left(addInput)) =>
        val next = session.copy(toSend = session.toSend.tail, localInputs = session.localInputs :+ addInput, txCompleteSent = false)
        replyTo ! SendMessage(addInput)
        receive(next)
      case Some(Right(addOutput)) =>
        val next = session.copy(toSend = session.toSend.tail, localOutputs = session.localOutputs :+ addOutput, txCompleteSent = false)
        replyTo ! SendMessage(addOutput)
        receive(next)
      case None =>
        val next = session.copy(txCompleteSent = true)
        replyTo ! SendMessage(TxComplete(fundingParams.channelId))
        if (next.isComplete) {
          validateTx(next) match {
            case Left(cause) =>
              replyTo ! RemoteFailure(cause)
              unlockAndStop(next)
            case Right((completeTx, fundingOutputIndex)) =>
              signCommitTx(completeTx, fundingOutputIndex)
          }
        }
        else {
          receive(next)
        }
    }
  }

  def receive(session: InteractiveTxSession): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case ReceiveTxMessage(msg) => msg match {
        case msg: HasSerialId if msg.serialId.toByteVector.bits.last != fundingParams.isInitiator =>
          replyTo ! RemoteFailure(InvalidSerialId(fundingParams.channelId, msg.serialId))
          unlockAndStop(session)
        case addInput: TxAddInput =>
          if (session.inputsReceivedCount + 1 >= MAX_INPUTS_OUTPUTS_RECEIVED) {
            replyTo ! RemoteFailure(TooManyInteractiveTxRounds(fundingParams.channelId))
            unlockAndStop(session)
          } else if (session.remoteInputs.exists(_.serialId == addInput.serialId)) {
            replyTo ! RemoteFailure(DuplicateSerialId(fundingParams.channelId, addInput.serialId))
            unlockAndStop(session)
          } else if (session.localInputs.exists(i => spendSameOutpoint(i, addInput)) || session.remoteInputs.exists(i => spendSameOutpoint(i, addInput))) {
            replyTo ! RemoteFailure(DuplicateInput(fundingParams.channelId, addInput.serialId, addInput.previousTx.txid, addInput.previousTxOutput))
            unlockAndStop(session)
          } else if (addInput.previousTx.txOut.length <= addInput.previousTxOutput) {
            replyTo ! RemoteFailure(InputOutOfBounds(fundingParams.channelId, addInput.serialId, addInput.previousTx.txid, addInput.previousTxOutput))
            unlockAndStop(session)
          } else if (!Script.isNativeWitnessScript(addInput.previousTx.txOut(addInput.previousTxOutput.toInt).publicKeyScript)) {
            replyTo ! RemoteFailure(NonSegwitInput(fundingParams.channelId, addInput.serialId, addInput.previousTx.txid, addInput.previousTxOutput))
            unlockAndStop(session)
          } else {
            val next = session.copy(
              remoteInputs = session.remoteInputs :+ addInput,
              inputsReceivedCount = session.inputsReceivedCount + 1,
              txCompleteReceived = false,
            )
            send(next)
          }
        case addOutput: TxAddOutput =>
          if (session.outputsReceivedCount + 1 >= MAX_INPUTS_OUTPUTS_RECEIVED) {
            replyTo ! RemoteFailure(TooManyInteractiveTxRounds(fundingParams.channelId))
            unlockAndStop(session)
          } else if (session.remoteOutputs.exists(_.serialId == addOutput.serialId)) {
            replyTo ! RemoteFailure(DuplicateSerialId(fundingParams.channelId, addOutput.serialId))
            unlockAndStop(session)
          } else if (addOutput.amount < fundingParams.dustLimit) {
            replyTo ! RemoteFailure(OutputBelowDust(fundingParams.channelId, addOutput.serialId, addOutput.amount, fundingParams.dustLimit))
            unlockAndStop(session)
          } else if (!Script.isNativeWitnessScript(addOutput.pubkeyScript)) {
            replyTo ! RemoteFailure(NonSegwitOutput(fundingParams.channelId, addOutput.serialId))
            unlockAndStop(session)
          } else {
            val next = session.copy(
              remoteOutputs = session.remoteOutputs :+ addOutput,
              outputsReceivedCount = session.outputsReceivedCount + 1,
              txCompleteReceived = false,
            )
            send(next)
          }
        case removeInput: TxRemoveInput =>
          session.remoteInputs.find(_.serialId == removeInput.serialId) match {
            case Some(_) =>
              val next = session.copy(
                remoteInputs = session.remoteInputs.filterNot(_.serialId == removeInput.serialId),
                txCompleteReceived = false,
              )
              send(next)
            case None =>
              replyTo ! RemoteFailure(UnknownSerialId(fundingParams.channelId, removeInput.serialId))
              unlockAndStop(session)
          }
        case removeOutput: TxRemoveOutput =>
          session.remoteOutputs.find(_.serialId == removeOutput.serialId) match {
            case Some(_) =>
              val next = session.copy(
                remoteOutputs = session.remoteOutputs.filterNot(_.serialId == removeOutput.serialId),
                txCompleteReceived = false,
              )
              send(next)
            case None =>
              replyTo ! RemoteFailure(UnknownSerialId(fundingParams.channelId, removeOutput.serialId))
              unlockAndStop(session)
          }
        case _: TxComplete =>
          val next = session.copy(txCompleteReceived = true)
          if (next.isComplete) {
            validateTx(next) match {
              case Left(cause) =>
                replyTo ! RemoteFailure(cause)
                unlockAndStop(next)
              case Right((completeTx, fundingOutputIndex)) =>
                signCommitTx(completeTx, fundingOutputIndex)
            }
          } else {
            send(next)
          }
      }
      case _: ReceiveCommitSig =>
        replyTo ! RemoteFailure(UnexpectedCommitSig(fundingParams.channelId))
        unlockAndStop(session)
      case _: ReceiveTxSigs =>
        replyTo ! RemoteFailure(UnexpectedFundingSignatures(fundingParams.channelId))
        unlockAndStop(session)
      case Abort =>
        unlockAndStop(session)
    }
  }

  def validateTx(session: InteractiveTxSession): Either[ChannelException, (SharedTransaction, Int)] = {
    val sharedTx = SharedTransaction(session.localInputs, session.remoteInputs.map(i => RemoteTxAddInput(i)), session.localOutputs, session.remoteOutputs.map(o => RemoteTxAddOutput(o)), fundingParams.lockTime)
    val tx = sharedTx.buildUnsignedTx()

    if (tx.txIn.length > 252 || tx.txOut.length > 252) {
      log.warn("invalid interactive tx ({} inputs and {} outputs)", tx.txIn.length, tx.txOut.length)
      return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
    }

    val sharedOutputs = tx.txOut.zipWithIndex.filter(_._1.publicKeyScript == fundingParams.fundingPubkeyScript)
    if (sharedOutputs.length != 1) {
      log.warn("invalid interactive tx: funding outpoint not included (tx={})", tx)
      return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
    }
    val (sharedOutput, sharedOutputIndex) = sharedOutputs.head
    if (sharedOutput.amount != fundingParams.fundingAmount) {
      log.warn("invalid interactive tx: invalid funding amount (expected={}, actual={})", fundingParams.fundingAmount, sharedOutput.amount)
      return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
    }

    val localAmountOut = sharedTx.localOutputs.filter(_.pubkeyScript != fundingParams.fundingPubkeyScript).map(_.amount).sum + fundingParams.localAmount
    val remoteAmountOut = sharedTx.remoteOutputs.filter(_.pubkeyScript != fundingParams.fundingPubkeyScript).map(_.amount).sum + fundingParams.remoteAmount
    if (sharedTx.localAmountIn < localAmountOut || sharedTx.remoteAmountIn < remoteAmountOut) {
      log.warn("invalid interactive tx: input amount is too small (localIn={}, localOut={}, remoteIn={}, remoteOut={})", sharedTx.localAmountIn, localAmountOut, sharedTx.remoteAmountIn, remoteAmountOut)
      return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
    }

    // The transaction isn't signed yet, so we estimate its weight knowing that all inputs are using native segwit.
    val minimumWitnessWeight = 107 // see Bolt 3
    val minimumWeight = tx.weight() + tx.txIn.length * minimumWitnessWeight
    if (minimumWeight > Transactions.MAX_STANDARD_TX_WEIGHT) {
      log.warn("invalid interactive tx: exceeds standard weight (weight={})", minimumWeight)
      return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
    }

    val minimumFee = Transactions.weight2fee(fundingParams.targetFeerate, minimumWeight)
    if (sharedTx.fees < minimumFee) {
      log.warn("invalid interactive tx: below the target feerate (target={}, actual={})", fundingParams.targetFeerate, Transactions.fee2rate(sharedTx.fees, minimumWeight))
      return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
    }

    // The transaction must double-spent every previous attempt, otherwise there is a risk that two funding transactions
    // confirm for the same channel.
    val currentInputs = tx.txIn.map(_.outPoint).toSet
    val doubleSpendsPreviousAttempts = previousAttempts.forall(previousTx => previousTx.tx.buildUnsignedTx().txIn.map(_.outPoint).exists(o => currentInputs.contains(o)))
    if (!doubleSpendsPreviousAttempts) {
      log.warn("invalid interactive tx: it doesn't double-spend all previous attempts")
      return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
    }

    Right(sharedTx, sharedOutputIndex)
  }

  def signCommitTx(completeTx: SharedTransaction, fundingOutputIndex: Int): Behavior[Command] = {
    val fundingTx = completeTx.buildUnsignedTx()
    Funding.makeFirstCommitTxs(keyManager, channelConfig, channelFeatures, fundingParams.channelId, localParams, remoteParams, fundingParams.localAmount, fundingParams.remoteAmount, 0 msat, commitTxFeerate, fundingTx.hash, fundingOutputIndex, remoteFirstPerCommitmentPoint) match {
      case Left(cause) =>
        replyTo ! RemoteFailure(cause)
        unlockAndStop(completeTx)
      case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
        require(fundingTx.txOut(fundingOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript, "pubkey script mismatch!")
        val fundingPubKey = keyManager.fundingPublicKey(localParams.fundingKeyPath)
        val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, channelFeatures.commitmentFormat)
        val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(localParams.fundingKeyPath), TxOwner.Remote, channelFeatures.commitmentFormat)
        val localCommitSig = CommitSig(fundingParams.channelId, localSigOfRemoteTx, Nil)
        replyTo ! SendMessage(localCommitSig)
        Behaviors.receiveMessagePartial {
          case ReceiveCommitSig(remoteCommitSig) =>
            val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, remoteParams.fundingPubKey, localSigOfLocalTx, remoteCommitSig.signature)
            Transactions.checkSpendable(signedLocalCommitTx) match {
              case Failure(_) =>
                replyTo ! RemoteFailure(InvalidCommitmentSignature(fundingParams.channelId, signedLocalCommitTx.tx))
                unlockAndStop(completeTx)
              case Success(_) =>
                val commitments = Commitments(
                  fundingParams.channelId, channelConfig, channelFeatures,
                  localParams, remoteParams, channelFlags,
                  LocalCommit(0, localSpec, CommitTxAndRemoteSig(localCommitTx, remoteCommitSig.signature), htlcTxsAndRemoteSigs = Nil),
                  RemoteCommit(0, remoteSpec, remoteCommitTx.tx.txid, remoteFirstPerCommitmentPoint),
                  LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
                  localNextHtlcId = 0L, remoteNextHtlcId = 0L,
                  originChannels = Map.empty,
                  remoteNextCommitInfo = Right(randomKey().publicKey), // we will receive their next per-commitment point in the next message, so we temporarily put a random byte array,
                  localCommitTx.input,
                  ShaChain.init)
                signFundingTx(completeTx, commitments)
            }
          case ReceiveTxSigs(_) =>
            replyTo ! RemoteFailure(UnexpectedFundingSignatures(fundingParams.channelId))
            unlockAndStop(completeTx)
          case ReceiveTxMessage(msg) =>
            replyTo ! RemoteFailure(UnexpectedInteractiveTxMessage(fundingParams.channelId, msg))
            unlockAndStop(completeTx)
          case Abort =>
            unlockAndStop(completeTx)
        }
    }
  }

  def signFundingTx(completeTx: SharedTransaction, commitments: Commitments): Behavior[Command] = {
    val shouldSignFirst = if (fundingParams.localAmount < fundingParams.remoteAmount) {
      // The peer with the lowest total of input amount must transmit its `tx_signatures` first.
      true
    } else if (fundingParams.localAmount == fundingParams.remoteAmount) {
      // When both peers contribute the same amount, the peer with the lowest pubkey must transmit its `tx_signatures` first.
      LexicographicalOrdering.isLessThan(commitments.localParams.nodeId.value, commitments.remoteNodeId.value)
    } else {
      false
    }
    if (shouldSignFirst) {
      signTx(completeTx, None)
    }
    Behaviors.receiveMessagePartial {
      case SignTransactionResult(signedTx, Some(remoteSigs)) =>
        addRemoteSigs(fundingParams, signedTx, remoteSigs) match {
          case Left(cause) =>
            replyTo ! RemoteFailure(cause)
            unlockAndStop(completeTx)
          case Right(fullySignedTx) =>
            replyTo ! Succeeded(fundingParams, fullySignedTx, commitments)
            Behaviors.stopped
        }
      case SignTransactionResult(signedTx, None) =>
        replyTo ! Succeeded(fundingParams, signedTx, commitments)
        Behaviors.stopped
      case ReceiveTxSigs(remoteSigs) =>
        signTx(completeTx, Some(remoteSigs))
        Behaviors.same
      case WalletFailure(t) =>
        log.error("could not sign funding transaction: ", t)
        // We use a generic exception and don't send the internal error to the peer.
        replyTo ! LocalFailure(ChannelFundingError(fundingParams.channelId))
        unlockAndStop(completeTx)
      case ReceiveCommitSig(_) =>
        replyTo ! RemoteFailure(UnexpectedCommitSig(fundingParams.channelId))
        unlockAndStop(completeTx)
      case ReceiveTxMessage(msg) =>
        replyTo ! RemoteFailure(UnexpectedInteractiveTxMessage(fundingParams.channelId, msg))
        unlockAndStop(completeTx)
      case Abort =>
        unlockAndStop(completeTx)
    }
  }

  private def signTx(unsignedTx: SharedTransaction, remoteSigs_opt: Option[TxSignatures]): Unit = {
    val tx = unsignedTx.buildUnsignedTx()
    if (unsignedTx.localInputs.isEmpty) {
      context.self ! SignTransactionResult(PartiallySignedSharedTransaction(unsignedTx, TxSignatures(fundingParams.channelId, tx.txid, Nil)), remoteSigs_opt)
    } else {
      context.pipeToSelf(wallet.signTransaction(tx, allowIncomplete = true).map {
        case SignTransactionResponse(signedTx, _) =>
          val localOutpoints = unsignedTx.localInputs.map(toOutPoint).toSet
          val sigs = signedTx.txIn.filter(txIn => localOutpoints.contains(txIn.outPoint)).map(_.witness)
          PartiallySignedSharedTransaction(unsignedTx, TxSignatures(fundingParams.channelId, tx.txid, sigs))
      }) {
        case Failure(t) => WalletFailure(t)
        case Success(signedTx) => SignTransactionResult(signedTx, remoteSigs_opt)
      }
    }
  }

  def unlockAndStop(session: InteractiveTxSession): Behavior[Command] = {
    val localInputs = session.localInputs ++ session.toSend.collect { case Left(addInput) => addInput }
    unlockAndStop(localInputs.map(toOutPoint).toSet)
  }

  def unlockAndStop(tx: SharedTransaction): Behavior[Command] = {
    val localInputs = tx.localInputs.map(toOutPoint).toSet
    unlockAndStop(localInputs)
  }

  def unlockAndStop(txInputs: Set[OutPoint]): Behavior[Command] = {
    // We don't unlock previous inputs as the corresponding funding transaction may confirm.
    val previousInputs = previousAttempts.flatMap(_.tx.localInputs.map(toOutPoint)).toSet
    val toUnlock = txInputs -- previousInputs
    log.debug("unlocking inputs: {}", toUnlock.map(o => s"${o.txid}:${o.index}").mkString(","))
    context.pipeToSelf(unlock(toUnlock))(_ => UtxosUnlocked)
    Behaviors.receiveMessagePartial {
      case UtxosUnlocked => Behaviors.stopped
    }
  }

  private def unlock(inputs: Set[OutPoint]): Future[Boolean] = {
    if (inputs.isEmpty) {
      Future.successful(true)
    } else {
      val dummyTx = Transaction(2, inputs.toSeq.map(o => TxIn(o, Nil, 0)), Nil, 0)
      wallet.rollback(dummyTx)
    }
  }

  private def generateSerialId(): UInt64 = {
    // The initiator must use even values and the non-initiator odd values.
    if (fundingParams.isInitiator) {
      UInt64(randomBytes(8) & hex"fffffffffffffffe")
    } else {
      UInt64(randomBytes(8) | hex"0000000000000001")
    }
  }

}