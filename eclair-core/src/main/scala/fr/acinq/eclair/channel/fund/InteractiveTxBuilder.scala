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

package fr.acinq.eclair.channel.fund

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, LexicographicalOrdering, OutPoint, Satoshi, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.OnChainChannelFunder
import fr.acinq.eclair.blockchain.OnChainWallet.SignTransactionResponse
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel.LocalFundingStatus.DualFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.Purpose
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.TxOwner
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Logs, MilliSatoshi, MilliSatoshiLong, NodeParams, UInt64}
import scodec.bits.ByteVector

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
  case class Start(replyTo: ActorRef[Response]) extends Command
  sealed trait ReceiveMessage extends Command
  case class ReceiveTxMessage(msg: InteractiveTxConstructionMessage) extends ReceiveMessage
  case class ReceiveCommitSig(msg: CommitSig) extends ReceiveMessage
  case class ReceiveTxSigs(msg: TxSignatures) extends ReceiveMessage
  case object Abort extends Command
  private case class FundTransactionResult(result: InteractiveTxFunder.Response) extends Command
  private case object ValidateSharedTx extends Command
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

  case class RequireConfirmedInputs(forLocal: Boolean, forRemote: Boolean)

  case class InteractiveTxParams(channelId: ByteVector32,
                                 isInitiator: Boolean,
                                 localAmount: Satoshi,
                                 remoteAmount: Satoshi,
                                 fundingPubkeyScript: ByteVector,
                                 lockTime: Long,
                                 dustLimit: Satoshi,
                                 targetFeerate: FeeratePerKw,
                                 requireConfirmedInputs: RequireConfirmedInputs) {
    val fundingAmount: Satoshi = localAmount + remoteAmount
    // BOLT 2: MUST set `feerate` greater than or equal to 25/24 times the `feerate` of the previously constructed transaction, rounded down.
    val minNextFeerate: FeeratePerKw = targetFeerate * 25 / 24
  }

  // @formatter:off
  sealed trait Purpose {
    def previousLocalBalance: MilliSatoshi
    def previousRemoteBalance: MilliSatoshi
    def remotePerCommitmentPoint: PublicKey
    def commitTxFeerate: FeeratePerKw
    def common: Common
  }
  case class FundingTx(commitTxFeerate: FeeratePerKw, remotePerCommitmentPoint: PublicKey, nextRemotePerCommitmentPoint: PublicKey) extends Purpose {
    override val previousLocalBalance: MilliSatoshi = 0.msat
    override val previousRemoteBalance: MilliSatoshi = 0.msat
    override val common: Common = Common(
      localChanges = LocalChanges(Nil, Nil, Nil), remoteChanges = RemoteChanges(Nil, Nil, Nil),
      localNextHtlcId = 0L, remoteNextHtlcId = 0L,
      localCommitIndex = 0L, remoteCommitIndex = 0L,
      originChannels = Map.empty,
      remoteNextCommitInfo = Right(nextRemotePerCommitmentPoint),
      remotePerCommitmentSecrets = ShaChain.init
    )
  }
  /**
   * @param previousTransactions interactive transactions are replaceable and can be RBF-ed, but we need to make sure that
   *                             only one of them ends up confirming. We guarantee this by having the latest transaction
   *                             always double-spend all its predecessors.
   */
  case class FundingTxRbf(common: Common, commitment: Commitment, previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction]) extends Purpose {
    override val previousLocalBalance: MilliSatoshi = 0.msat
    override val previousRemoteBalance: MilliSatoshi = 0.msat
    override val remotePerCommitmentPoint: PublicKey = commitment.remoteCommit.remotePerCommitmentPoint
    override val commitTxFeerate: FeeratePerKw = commitment.localCommit.spec.commitTxFeerate
  }
  // @formatter:on

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
  case class SharedTransaction(localInputs: List[TxAddInput], remoteInputs: List[RemoteTxAddInput], localOutputs: List[TxAddOutput], remoteOutputs: List[RemoteTxAddOutput], lockTime: Long) {
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
    def txId: ByteVector32
    def tx: SharedTransaction
    def localSigs: TxSignatures
    def signedTx_opt: Option[Transaction]
  }
  case class PartiallySignedSharedTransaction(tx: SharedTransaction, localSigs: TxSignatures) extends SignedSharedTransaction {
    override val txId: ByteVector32 = tx.buildUnsignedTx().txid
    override val signedTx_opt: Option[Transaction] = None
  }
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
    override val txId: ByteVector32 = signedTx.txid
    override val signedTx_opt: Option[Transaction] = Some(signedTx)
    val feerate: FeeratePerKw = Transactions.fee2rate(tx.fees, signedTx.weight())
  }
  // @formatter:on

  def apply(nodeParams: NodeParams,
            fundingParams: InteractiveTxParams,
            commitmentParams: Params,
            purpose: Purpose,
            localPushAmount: MilliSatoshi,
            remotePushAmount: MilliSatoshi,
            wallet: OnChainChannelFunder)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      // The stash is used to buffer messages that arrive while we're funding the transaction.
      // Since the interactive-tx protocol is turn-based, we should not have more than one stashed lightning message.
      // We may also receive commands from our parent, but we shouldn't receive many, so we can keep the stash size small.
      Behaviors.withStash(10) { stash =>
        Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(commitmentParams.remoteParams.nodeId), channelId_opt = Some(fundingParams.channelId))) {
          Behaviors.receiveMessagePartial {
            case Start(replyTo) =>
              val actor = new InteractiveTxBuilder(replyTo, nodeParams, fundingParams, commitmentParams, purpose, localPushAmount, remotePushAmount, wallet, stash, context)
              actor.start()
            case Abort => Behaviors.stopped
          }
        }
      }
    }
  }

  // We restrict the number of inputs / outputs that our peer can send us to ensure the protocol eventually ends.
  val MAX_INPUTS_OUTPUTS_RECEIVED = 4096

  def toOutPoint(input: TxAddInput): OutPoint = OutPoint(input.previousTx, input.previousTxOutput.toInt)

  def addRemoteSigs(fundingParams: InteractiveTxParams, partiallySignedTx: PartiallySignedSharedTransaction, remoteSigs: TxSignatures): Either[ChannelException, FullySignedSharedTransaction] = {
    if (partiallySignedTx.tx.localInputs.length != partiallySignedTx.localSigs.witnesses.length) {
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
    }
    if (partiallySignedTx.tx.remoteInputs.length != remoteSigs.witnesses.length) {
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
    }
    val txWithSigs = FullySignedSharedTransaction(partiallySignedTx.tx, partiallySignedTx.localSigs, remoteSigs)
    if (remoteSigs.txId != txWithSigs.signedTx.txid) {
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
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
      case Failure(_) => Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
      case Success(_) => Right(txWithSigs)
    }
  }

}

private class InteractiveTxBuilder(replyTo: ActorRef[InteractiveTxBuilder.Response],
                                   nodeParams: NodeParams,
                                   fundingParams: InteractiveTxBuilder.InteractiveTxParams,
                                   commitmentParams: Params,
                                   purpose: Purpose,
                                   localPushAmount: MilliSatoshi,
                                   remotePushAmount: MilliSatoshi,
                                   wallet: OnChainChannelFunder,
                                   stash: StashBuffer[InteractiveTxBuilder.Command],
                                   context: ActorContext[InteractiveTxBuilder.Command])(implicit ec: ExecutionContext) {

  import InteractiveTxBuilder._

  private val log = context.log
  private val keyManager = nodeParams.channelKeyManager
  private val remoteNodeId = commitmentParams.remoteParams.nodeId
  private val previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction] = purpose match {
    case rbf: FundingTxRbf => rbf.previousTransactions
    case _ => Nil
  }

  def start(): Behavior[Command] = {
    if (!fundingParams.isInitiator && fundingParams.localAmount == 0.sat) {
      buildTx(InteractiveTxFunder.FundingContributions(Nil, Nil))
    } else {
      val txFunder = context.spawnAnonymous(InteractiveTxFunder(remoteNodeId, fundingParams, purpose, wallet))
      txFunder ! InteractiveTxFunder.FundTransaction(context.messageAdapter[InteractiveTxFunder.Response](r => FundTransactionResult(r)))
      Behaviors.receiveMessagePartial {
        case FundTransactionResult(result) => result match {
          case InteractiveTxFunder.FundingFailed =>
            if (previousTransactions.nonEmpty && !fundingParams.isInitiator) {
              // We don't have enough funds to reach the desired feerate, but this is an RBF attempt that we did not initiate.
              // It still makes sense for us to contribute whatever we're able to (by using our previous set of inputs and
              // outputs): the final feerate will be less than what the initiator intended, but it's still better than being
              // stuck with a low feerate transaction that won't confirm.
              log.warn("could not fund interactive tx at {}, re-using previous inputs and outputs", fundingParams.targetFeerate)
              val previousTx = previousTransactions.head.tx
              stash.unstashAll(buildTx(InteractiveTxFunder.FundingContributions(previousTx.localInputs, previousTx.localOutputs)))
            } else {
              // We use a generic exception and don't send the internal error to the peer.
              replyTo ! LocalFailure(ChannelFundingError(fundingParams.channelId))
              Behaviors.stopped
            }
          case fundingContributions: InteractiveTxFunder.FundingContributions =>
            stash.unstashAll(buildTx(fundingContributions))
        }
        case msg: ReceiveMessage =>
          stash.stash(msg)
          Behaviors.same
        case Abort =>
          stash.stash(Abort)
          Behaviors.same
      }
    }
  }

  private def buildTx(localContributions: InteractiveTxFunder.FundingContributions): Behavior[Command] = {
    val toSend = localContributions.inputs.map(Left(_)) ++ localContributions.outputs.map(Right(_))
    if (fundingParams.isInitiator) {
      // The initiator sends the first message.
      send(InteractiveTxSession(toSend))
    } else {
      // The non-initiator waits for the initiator to send the first message.
      receive(InteractiveTxSession(toSend))
    }
  }

  private def send(session: InteractiveTxSession): Behavior[Command] = {
    session.toSend match {
      case Left(addInput) +: tail =>
        replyTo ! SendMessage(addInput)
        val next = session.copy(toSend = tail, localInputs = session.localInputs :+ addInput, txCompleteSent = false)
        receive(next)
      case Right(addOutput) +: tail =>
        replyTo ! SendMessage(addOutput)
        val next = session.copy(toSend = tail, localOutputs = session.localOutputs :+ addOutput, txCompleteSent = false)
        receive(next)
      case Nil =>
        replyTo ! SendMessage(TxComplete(fundingParams.channelId))
        val next = session.copy(txCompleteSent = true)
        if (next.isComplete) {
          validateAndSign(next)
        } else {
          receive(next)
        }
    }
  }

  private def receive(session: InteractiveTxSession): Behavior[Command] = {
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
          } else if (addInput.previousTx.txOut.length <= addInput.previousTxOutput) {
            replyTo ! RemoteFailure(InputOutOfBounds(fundingParams.channelId, addInput.serialId, addInput.previousTx.txid, addInput.previousTxOutput))
            unlockAndStop(session)
          } else if (session.localInputs.exists(i => toOutPoint(i) == toOutPoint(addInput)) || session.remoteInputs.exists(i => toOutPoint(i) == toOutPoint(addInput))) {
            replyTo ! RemoteFailure(DuplicateInput(fundingParams.channelId, addInput.serialId, addInput.previousTx.txid, addInput.previousTxOutput))
            unlockAndStop(session)
          } else if (addInput.sequence > 0xfffffffdL) {
            replyTo ! RemoteFailure(NonReplaceableInput(fundingParams.channelId, addInput.serialId, addInput.previousTx.txid, addInput.previousTxOutput, addInput.sequence))
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
            validateAndSign(next)
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

  private def validateAndSign(session: InteractiveTxSession): Behavior[Command] = {
    require(session.isComplete, "interactive session was not completed")
    if (fundingParams.requireConfirmedInputs.forRemote) {
      context.pipeToSelf(checkInputsConfirmed(session.remoteInputs)) {
        case Failure(t) => WalletFailure(t)
        case Success(false) => WalletFailure(UnconfirmedInteractiveTxInputs(fundingParams.channelId))
        case Success(true) => ValidateSharedTx
      }
    } else {
      context.self ! ValidateSharedTx
    }
    Behaviors.receiveMessagePartial {
      case ValidateSharedTx => validateTx(session) match {
        case Left(cause) =>
          replyTo ! RemoteFailure(cause)
          unlockAndStop(session)
        case Right((completeTx, fundingOutputIndex)) =>
          stash.unstashAll(signCommitTx(completeTx, fundingOutputIndex))
      }
      case _: WalletFailure =>
        replyTo ! RemoteFailure(UnconfirmedInteractiveTxInputs(fundingParams.channelId))
        unlockAndStop(session)
      case msg: ReceiveCommitSig =>
        stash.stash(msg)
        Behaviors.same
      case ReceiveTxSigs(_) =>
        replyTo ! RemoteFailure(UnexpectedFundingSignatures(fundingParams.channelId))
        unlockAndStop(session)
      case ReceiveTxMessage(msg) =>
        replyTo ! RemoteFailure(UnexpectedInteractiveTxMessage(fundingParams.channelId, msg))
        unlockAndStop(session)
      case Abort =>
        unlockAndStop(session)
    }
  }

  private def checkInputsConfirmed(inputs: Seq[TxAddInput]): Future[Boolean] = {
    // We check inputs sequentially and stop at the first unconfirmed one.
    inputs.map(_.previousTx.txid).toSet.foldLeft(Future.successful(true)) {
      case (current, txId) => current.transformWith {
        case Success(true) => wallet.getTxConfirmations(txId).map {
          case None => false
          case Some(confirmations) => confirmations > 0
        }
        case Success(false) => Future.successful(false)
        case Failure(t) => Future.failed(t)
      }
    }
  }

  private def validateTx(session: InteractiveTxSession): Either[ChannelException, (SharedTransaction, Int)] = {
    val sharedTx = SharedTransaction(session.localInputs.toList, session.remoteInputs.map(i => RemoteTxAddInput(i)).toList, session.localOutputs.toList, session.remoteOutputs.map(o => RemoteTxAddOutput(o)).toList, fundingParams.lockTime)
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

    // The transaction isn't signed yet, and segwit witnesses can be arbitrarily low (e.g. when using an OP_1 script),
    // so we use empty witnesses to provide a lower bound on the transaction weight.
    if (tx.weight() > Transactions.MAX_STANDARD_TX_WEIGHT) {
      log.warn("invalid interactive tx: exceeds standard weight (weight={})", tx.weight())
      return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
    }

    previousTransactions.headOption match {
      case Some(previousTx) =>
        // This is an RBF attempt: even if our peer does not contribute to the feerate increase, we'd like to broadcast
        // the new transaction if it has a better feerate than the previous one. This is better than being stuck with
        // a transaction that doesn't confirm.
        val remoteInputsUnchanged = previousTx.tx.remoteInputs.map(_.outPoint).toSet == sharedTx.remoteInputs.map(_.outPoint).toSet
        val remoteOutputsUnchanged = previousTx.tx.remoteOutputs.map(o => TxOut(o.amount, o.pubkeyScript)).toSet == sharedTx.remoteOutputs.map(o => TxOut(o.amount, o.pubkeyScript)).toSet
        if (remoteInputsUnchanged && remoteOutputsUnchanged) {
          log.debug("peer did not contribute to the feerate increase to {}: they used the same inputs and outputs", fundingParams.targetFeerate)
        }
        // We don't know yet the witness weight since the transaction isn't signed, so we compare unsigned transactions.
        val previousUnsignedTx = previousTx.tx.buildUnsignedTx()
        val previousFeerate = Transactions.fee2rate(previousTx.tx.fees, previousUnsignedTx.weight())
        val nextFeerate = Transactions.fee2rate(sharedTx.fees, tx.weight())
        if (nextFeerate <= previousFeerate) {
          log.warn("invalid interactive tx: next feerate isn't greater than previous feerate (previous={}, next={})", previousFeerate, nextFeerate)
          return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
        }
      case None =>
        val minimumFee = Transactions.weight2fee(fundingParams.targetFeerate, tx.weight())
        if (sharedTx.fees < minimumFee) {
          log.warn("invalid interactive tx: below the target feerate (target={}, actual={})", fundingParams.targetFeerate, Transactions.fee2rate(sharedTx.fees, tx.weight()))
          return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
        }
    }

    // The transaction must double-spend every previous attempt, otherwise there is a risk that two funding transactions
    // confirm for the same channel.
    val currentInputs = tx.txIn.map(_.outPoint).toSet
    val doubleSpendsPreviousTransactions = previousTransactions.forall(previousTx => previousTx.tx.buildUnsignedTx().txIn.map(_.outPoint).exists(o => currentInputs.contains(o)))
    if (!doubleSpendsPreviousTransactions) {
      log.warn("invalid interactive tx: it doesn't double-spend all previous transactions")
      return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
    }

    Right(sharedTx, sharedOutputIndex)
  }

  private def signCommitTx(completeTx: SharedTransaction, fundingOutputIndex: Int): Behavior[Command] = {
    val fundingTx = completeTx.buildUnsignedTx()
    Funding.makeCommitTxsWithoutHtlcs(keyManager, commitmentParams,
      fundingAmount = fundingParams.fundingAmount,
      toLocal = fundingParams.localAmount - localPushAmount + remotePushAmount + purpose.previousLocalBalance,
      toRemote = fundingParams.remoteAmount - remotePushAmount + localPushAmount + purpose.previousRemoteBalance,
      purpose.commitTxFeerate, fundingTx.hash, fundingOutputIndex, purpose.remotePerCommitmentPoint, commitmentIndex = purpose.common.localCommitIndex) match {
      case Left(cause) =>
        replyTo ! RemoteFailure(cause)
        unlockAndStop(completeTx)
      case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
        require(fundingTx.txOut(fundingOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript, "pubkey script mismatch!")
        val fundingPubKey = keyManager.fundingPublicKey(commitmentParams.localParams.fundingKeyPath)
        val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, commitmentParams.channelFeatures.commitmentFormat)
        val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, keyManager.fundingPublicKey(commitmentParams.localParams.fundingKeyPath), TxOwner.Remote, commitmentParams.channelFeatures.commitmentFormat)
        val localCommitSig = CommitSig(fundingParams.channelId, localSigOfRemoteTx, Nil)
        replyTo ! SendMessage(localCommitSig)
        Behaviors.receiveMessagePartial {
          case ReceiveCommitSig(remoteCommitSig) =>
            val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, commitmentParams.remoteParams.fundingPubKey, localSigOfLocalTx, remoteCommitSig.signature)
            Transactions.checkSpendable(signedLocalCommitTx) match {
              case Failure(_) =>
                replyTo ! RemoteFailure(InvalidCommitmentSignature(fundingParams.channelId, signedLocalCommitTx.tx.txid))
                unlockAndStop(completeTx)
              case Success(_) =>
                val common = purpose.common
                val commitment = Commitment(
                  localFundingStatus = LocalFundingStatus.UnknownFundingTx, // hacky, but we don't have the signed funding tx yet, we'll learn it at the next step
                  remoteFundingStatus = RemoteFundingStatus.NotLocked,
                  localCommit = LocalCommit(common.localCommitIndex, localSpec, CommitTxAndRemoteSig(localCommitTx, remoteCommitSig.signature), htlcTxsAndRemoteSigs = Nil),
                  remoteCommit = RemoteCommit(common.remoteCommitIndex, remoteSpec, remoteCommitTx.tx.txid, purpose.remotePerCommitmentPoint),
                  nextRemoteCommit_opt = None
                )
                val commitments = Commitments(commitmentParams, common, commitment)
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

  private def signFundingTx(completeTx: SharedTransaction, commitments: Commitments): Behavior[Command] = {
    val shouldSignFirst = if (completeTx.localAmountIn == completeTx.remoteAmountIn) {
      // When both peers contribute the same amount, the peer with the lowest pubkey must transmit its `tx_signatures` first.
      LexicographicalOrdering.isLessThan(commitments.localParams.nodeId.value, commitments.remoteNodeId.value)
    } else if (completeTx.remoteAmountIn == 0.sat) {
      // If our peer didn't contribute to the transaction, we don't need to wait for their `tx_signatures`, they will be
      // empty anyway.
      true
    } else {
      // Otherwise, the peer with the lowest total of input amount must transmit its `tx_signatures` first.
      completeTx.localAmountIn < completeTx.remoteAmountIn
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
            log.info("interactive-tx fully signed with {} local inputs, {} remote inputs, {} local outputs and {} remote outputs", fullySignedTx.tx.localInputs.length, fullySignedTx.tx.remoteInputs.length, fullySignedTx.tx.localOutputs.length, fullySignedTx.tx.remoteOutputs.length)
            replyTo ! Succeeded(fundingParams, fullySignedTx, commitments.copy(localFundingStatus = DualFundedUnconfirmedFundingTx(fullySignedTx, nodeParams.currentBlockHeight)))
            Behaviors.stopped
        }
      case SignTransactionResult(signedTx, None) =>
        // We return as soon as we sign the tx, because we need to be able to handle the case where remote publishes the
        // tx right away without properly sending us their signatures.
        if (completeTx.remoteAmountIn == 0.sat) {
          log.info("interactive-tx fully signed with {} local inputs, {} remote inputs, {} local outputs and {} remote outputs", signedTx.tx.localInputs.length, signedTx.tx.remoteInputs.length, signedTx.tx.localOutputs.length, signedTx.tx.remoteOutputs.length)
          val remoteSigs = TxSignatures(signedTx.localSigs.channelId, signedTx.localSigs.txHash, Nil)
          val signedTx1 = FullySignedSharedTransaction(signedTx.tx, signedTx.localSigs, remoteSigs)
          val commitments1 = commitments.copy(localFundingStatus = DualFundedUnconfirmedFundingTx(signedTx1, nodeParams.currentBlockHeight))
          replyTo ! Succeeded(fundingParams, signedTx1, commitments1)
        } else {
          log.info("interactive-tx partially signed with {} local inputs, {} remote inputs, {} local outputs and {} remote outputs", signedTx.tx.localInputs.length, signedTx.tx.remoteInputs.length, signedTx.tx.localOutputs.length, signedTx.tx.remoteOutputs.length)
          val commitments1 = commitments.copy(localFundingStatus = DualFundedUnconfirmedFundingTx(signedTx, nodeParams.currentBlockHeight))
          replyTo ! Succeeded(fundingParams, signedTx, commitments1)
        }
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
      context.self ! SignTransactionResult(PartiallySignedSharedTransaction(unsignedTx, TxSignatures(fundingParams.channelId, tx, Nil)), remoteSigs_opt)
    } else {
      context.pipeToSelf(wallet.signTransaction(tx, allowIncomplete = true).map {
        case SignTransactionResponse(signedTx, _) =>
          val localOutpoints = unsignedTx.localInputs.map(toOutPoint).toSet
          val sigs = signedTx.txIn.filter(txIn => localOutpoints.contains(txIn.outPoint)).map(_.witness)
          PartiallySignedSharedTransaction(unsignedTx, TxSignatures(fundingParams.channelId, tx, sigs))
      }) {
        case Failure(t) => WalletFailure(t)
        case Success(signedTx) => SignTransactionResult(signedTx, remoteSigs_opt)
      }
    }
  }

  private def unlockAndStop(session: InteractiveTxSession): Behavior[Command] = {
    val localInputs = session.localInputs ++ session.toSend.collect { case Left(addInput) => addInput }
    unlockAndStop(localInputs.map(toOutPoint).toSet)
  }

  private def unlockAndStop(tx: SharedTransaction): Behavior[Command] = {
    val localInputs = tx.localInputs.map(toOutPoint).toSet
    unlockAndStop(localInputs)
  }

  private def unlockAndStop(txInputs: Set[OutPoint]): Behavior[Command] = {
    // We don't unlock previous inputs as the corresponding funding transaction may confirm.
    val previousInputs = previousTransactions.flatMap(_.tx.localInputs.map(toOutPoint)).toSet
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

}