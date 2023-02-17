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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, LexicographicalOrdering, OutPoint, Satoshi, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.OnChainChannelFunder
import fr.acinq.eclair.blockchain.OnChainWallet.SignTransactionResponse
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel.LocalFundingStatus.DualFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.Purpose
import fr.acinq.eclair.crypto.keymanager.ChannelKeyManager
import fr.acinq.eclair.transactions.Transactions.{InputInfo, TxOwner}
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Logs, MilliSatoshi, NodeParams, UInt64}
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
  case class Succeeded(fundingTx: DualFundedUnconfirmedFundingTx, commitment: Commitment) extends Response {
    val fundingParams: InteractiveTxParams = fundingTx.fundingParams
    val sharedTx: SignedSharedTransaction = fundingTx.sharedTx
  }
  sealed trait Failed extends Response { def cause: ChannelException }
  case class LocalFailure(cause: ChannelException) extends Failed
  case class RemoteFailure(cause: ChannelException) extends Failed
  // @formatter:on

  case class RequireConfirmedInputs(forLocal: Boolean, forRemote: Boolean)

  /** An input that is already shared between participants (e.g. the current funding output when doing a splice). */
  sealed trait SharedFundingInput {
    // @formatter:off
    def info: InputInfo
    def weight: Long
    def sign(keyManager: ChannelKeyManager, params: ChannelParams, tx: Transaction): ByteVector64
    // @formatter:on
  }

  case class Multisig2of2Input(info: InputInfo, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey) extends SharedFundingInput {
    // This value was computed assuming 73 bytes signatures (worst-case scenario).
    override val weight: Long = 388

    override def sign(keyManager: ChannelKeyManager, params: ChannelParams, tx: Transaction): ByteVector64 = {
      val fundingPubkey = keyManager.fundingPublicKey(params.localParams.fundingKeyPath)
      keyManager.sign(Transactions.SpliceTx(info, tx), fundingPubkey, TxOwner.Local, params.channelFeatures.commitmentFormat)
    }
  }

  object Multisig2of2Input {
    def apply(keyManager: ChannelKeyManager, params: ChannelParams, commitment: Commitment): Multisig2of2Input = Multisig2of2Input(
      info = commitment.commitInput,
      localFundingPubkey = keyManager.fundingPublicKey(params.localParams.fundingKeyPath).publicKey,
      remoteFundingPubkey = params.remoteParams.fundingPubKey
    )
  }

  /**
   * @param channelId              id of the channel.
   * @param isInitiator            true if we initiated the protocol, in which case we will pay fees for the shared parts of the transaction.
   * @param localAmount            amount contributed by us to the shared output.
   * @param remoteAmount           amount contributed by our peer to the shared output.
   * @param sharedInput_opt        previous input shared between the two participants (e.g. previous funding output when splicing).
   * @param fundingPubkeyScript    script of the shared output.
   * @param localOutputs           outputs to be added to the shared transaction (e.g. splice-out).
   * @param lockTime               transaction lock time.
   * @param dustLimit              outputs below this value are considered invalid.
   * @param targetFeerate          transaction feerate.
   * @param requireConfirmedInputs we may require that inputs added to the transaction are confirmed, especially with peers we don't trust.
   */
  case class InteractiveTxParams(channelId: ByteVector32,
                                 isInitiator: Boolean,
                                 localAmount: Satoshi,
                                 remoteAmount: Satoshi,
                                 sharedInput_opt: Option[SharedFundingInput],
                                 fundingPubkeyScript: ByteVector,
                                 localOutputs: List[TxOut],
                                 lockTime: Long,
                                 dustLimit: Satoshi,
                                 targetFeerate: FeeratePerKw,
                                 minDepth_opt: Option[Long],
                                 requireConfirmedInputs: RequireConfirmedInputs) {
    require(localAmount >= 0.sat && remoteAmount >= 0.sat, "funding amount cannot be negative")
    val fundingAmount: Satoshi = localAmount + remoteAmount
    // BOLT 2: MUST set `feerate` greater than or equal to 25/24 times the `feerate` of the previously constructed transaction, rounded down.
    val minNextFeerate: FeeratePerKw = targetFeerate * 25 / 24
    // BOLT 2: the initiator's serial IDs MUST use even values and the non-initiator odd values.
    val serialIdParity = if (isInitiator) 0 else 1
  }

  // @formatter:off
  sealed trait Purpose {
    def previousLocalBalance: Satoshi
    def previousRemoteBalance: Satoshi
    def localCommitIndex: Long
    def remoteCommitIndex: Long
    def remotePerCommitmentPoint: PublicKey
    def commitTxFeerate: FeeratePerKw
  }
  case class FundingTx(commitTxFeerate: FeeratePerKw, remotePerCommitmentPoint: PublicKey) extends Purpose {
    override val previousLocalBalance: Satoshi = 0 sat
    override val previousRemoteBalance: Satoshi = 0 sat
    override val localCommitIndex: Long = 0
    override val remoteCommitIndex: Long = 0
  }
  case class SpliceTx(commitment: Commitment) extends Purpose {
    // Note that previous balances are truncated, which can give away 1 sat as mining fees.
    override val previousLocalBalance: Satoshi = commitment.localCommit.spec.toLocal.truncateToSatoshi
    override val previousRemoteBalance: Satoshi = commitment.remoteCommit.spec.toLocal.truncateToSatoshi
    override val localCommitIndex: Long = commitment.localCommit.index
    override val remoteCommitIndex: Long = commitment.remoteCommit.index
    override val remotePerCommitmentPoint: PublicKey = commitment.remoteCommit.remotePerCommitmentPoint
    override val commitTxFeerate: FeeratePerKw = commitment.localCommit.spec.commitTxFeerate
  }
  /**
   * @param previousTransactions interactive transactions are replaceable and can be RBF-ed, but we need to make sure that
   *                             only one of them ends up confirming. We guarantee this by having the latest transaction
   *                             always double-spend all its predecessors.
   */
  case class PreviousTxRbf(commitment: Commitment, previousLocalBalance: Satoshi, previousRemoteBalance: Satoshi, previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction]) extends Purpose {
    override val localCommitIndex: Long = commitment.localCommit.index
    override val remoteCommitIndex: Long = commitment.remoteCommit.index
    override val remotePerCommitmentPoint: PublicKey = commitment.remoteCommit.remotePerCommitmentPoint
    override val commitTxFeerate: FeeratePerKw = commitment.localCommit.spec.commitTxFeerate
  }
  // @formatter:on

  // @formatter:off
  /** Transaction element added by our peer to the interactive transaction. */
  sealed trait Incoming
  /** Transaction element added by us to the interactive transaction. */
  sealed trait Outgoing

  sealed trait Input {
    def serialId: UInt64
    def outPoint: OutPoint
    def sequence: Long
  }
  object Input {
    /** A local-only input that funds the interactive transaction. */
    case class Local(serialId: UInt64, previousTx: Transaction, previousTxOutput: Long, sequence: Long) extends Input with Outgoing {
      override val outPoint: OutPoint = OutPoint(previousTx, previousTxOutput.toInt)
    }

    /**
     * A remote-only input that funds the interactive transaction.
     * We only keep the data we need from our peer's TxAddInput to avoid storing potentially large messages in our DB.
     */
    case class Remote(serialId: UInt64, outPoint: OutPoint, txOut: TxOut, sequence: Long) extends Input with Incoming

    /** The shared input can be added by us or by our peer, depending on who initiated the protocol. */
    case class Shared(serialId: UInt64, outPoint: OutPoint, sequence: Long, localAmount: Satoshi, remoteAmount: Satoshi) extends Input with Incoming with Outgoing
  }

  sealed trait Output {
    def serialId: UInt64
    def amount: Satoshi
    def pubkeyScript: ByteVector
  }
  object Output {
    /** A local-only output of the interactive transaction. */
    sealed trait Local extends Output with Outgoing
    object Local {
      case class Change(serialId: UInt64, amount: Satoshi, pubkeyScript: ByteVector) extends Local
      case class NonChange(serialId: UInt64, amount: Satoshi, pubkeyScript: ByteVector) extends Local
    }

    /**
     * A remote-only output of the interactive transaction.
     * We only keep the data we need from our peer's TxAddOutput to avoid storing potentially large messages in our DB.
     */
    case class Remote(serialId: UInt64, amount: Satoshi, pubkeyScript: ByteVector) extends Output with Incoming

    /** The shared output can be added by us or by our peer, depending on who initiated the protocol. */
    case class Shared(serialId: UInt64, pubkeyScript: ByteVector, localAmount: Satoshi, remoteAmount: Satoshi) extends Output with Incoming with Outgoing {
      override val amount: Satoshi = localAmount + remoteAmount
    }
  }

  type OutgoingInput = Input with Outgoing
  type IncomingInput = Input with Incoming
  type OutgoingOutput = Output with Outgoing
  type IncomingOutput = Output with Incoming
  // @formatter:on

  private case class InteractiveTxSession(toSend: Seq[Outgoing],
                                          localInputs: Seq[OutgoingInput] = Nil,
                                          remoteInputs: Seq[IncomingInput] = Nil,
                                          localOutputs: Seq[OutgoingOutput] = Nil,
                                          remoteOutputs: Seq[IncomingOutput] = Nil,
                                          txCompleteSent: Boolean = false,
                                          txCompleteReceived: Boolean = false,
                                          inputsReceivedCount: Int = 0,
                                          outputsReceivedCount: Int = 0) {
    val isComplete: Boolean = txCompleteSent && txCompleteReceived
  }

  /** Unsigned transaction created collaboratively. */
  case class SharedTransaction(sharedInput_opt: Option[Input.Shared], sharedOutput: Output.Shared,
                               localInputs: List[Input.Local], remoteInputs: List[Input.Remote],
                               localOutputs: List[Output.Local], remoteOutputs: List[Output.Remote],
                               lockTime: Long) {
    val localAmountIn: Satoshi = sharedInput_opt.map(_.localAmount).getOrElse(0 sat) + localInputs.map(i => i.previousTx.txOut(i.previousTxOutput.toInt).amount).sum
    val remoteAmountIn: Satoshi = sharedInput_opt.map(_.remoteAmount).getOrElse(0 sat) + remoteInputs.map(_.txOut.amount).sum
    val localAmountOut: Satoshi = sharedOutput.localAmount + localOutputs.map(_.amount).sum
    val remoteAmountOut: Satoshi = sharedOutput.remoteAmount + remoteOutputs.map(_.amount).sum
    val localFees: Satoshi = localAmountIn - localAmountOut
    val remoteFees: Satoshi = remoteAmountIn - remoteAmountOut
    val fees: Satoshi = localFees + remoteFees

    def buildUnsignedTx(): Transaction = {
      val sharedTxIn = sharedInput_opt.map(i => (i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence))).toSeq
      val localTxIn = localInputs.map(i => (i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence)))
      val remoteTxIn = remoteInputs.map(i => (i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence)))
      val inputs = (sharedTxIn ++ localTxIn ++ remoteTxIn).sortBy(_._1).map(_._2)
      val sharedTxOut = (sharedOutput.serialId, TxOut(sharedOutput.amount, sharedOutput.pubkeyScript))
      val localTxOut = localOutputs.map(o => (o.serialId, TxOut(o.amount, o.pubkeyScript)))
      val remoteTxOut = remoteOutputs.map(o => (o.serialId, TxOut(o.amount, o.pubkeyScript)))
      val outputs = (Seq(sharedTxOut) ++ localTxOut ++ remoteTxOut).sortBy(_._1).map(_._2)
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
  case class FullySignedSharedTransaction(tx: SharedTransaction, localSigs: TxSignatures, remoteSigs: TxSignatures, sharedSigs_opt: Option[ScriptWitness]) extends SignedSharedTransaction {
    val signedTx: Transaction = {
      import tx._
      val sharedTxIn = sharedInput_opt.map(i => (i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence, sharedSigs_opt.getOrElse(ScriptWitness.empty)))).toSeq
      val localTxIn = localInputs.sortBy(_.serialId).zip(localSigs.witnesses).map { case (i, w) => (i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence, w)) }
      val remoteTxIn = remoteInputs.sortBy(_.serialId).zip(remoteSigs.witnesses).map { case (i, w) => (i.serialId, TxIn(i.outPoint, ByteVector.empty, i.sequence, w)) }
      val inputs = (sharedTxIn ++ localTxIn ++ remoteTxIn).sortBy(_._1).map(_._2)
      val sharedTxOut = (sharedOutput.serialId, TxOut(sharedOutput.amount, sharedOutput.pubkeyScript))
      val localTxOut = localOutputs.map(o => (o.serialId, TxOut(o.amount, o.pubkeyScript)))
      val remoteTxOut = remoteOutputs.map(o => (o.serialId, TxOut(o.amount, o.pubkeyScript)))
      val outputs = (Seq(sharedTxOut) ++ localTxOut ++ remoteTxOut).sortBy(_._1).map(_._2)
      Transaction(2, inputs, outputs, lockTime)
    }
    override val txId: ByteVector32 = signedTx.txid
    override val signedTx_opt: Option[Transaction] = Some(signedTx)
    val feerate: FeeratePerKw = Transactions.fee2rate(tx.fees, signedTx.weight())
  }
  // @formatter:on

  def apply(nodeParams: NodeParams,
            fundingParams: InteractiveTxParams,
            channelParams: ChannelParams,
            purpose: Purpose,
            localPushAmount: MilliSatoshi,
            remotePushAmount: MilliSatoshi,
            wallet: OnChainChannelFunder)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      // The stash is used to buffer messages that arrive while we're funding the transaction.
      // Since the interactive-tx protocol is turn-based, we should not have more than one stashed lightning message.
      // We may also receive commands from our parent, but we shouldn't receive many, so we can keep the stash size small.
      Behaviors.withStash(10) { stash =>
        Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(channelParams.remoteParams.nodeId), channelId_opt = Some(fundingParams.channelId))) {
          Behaviors.receiveMessagePartial {
            case Start(replyTo) =>
              val actor = new InteractiveTxBuilder(replyTo, nodeParams, fundingParams, channelParams, purpose, localPushAmount, remotePushAmount, wallet, stash, context)
              actor.start()
            case Abort => Behaviors.stopped
          }
        }
      }
    }
  }

  // We restrict the number of inputs / outputs that our peer can send us to ensure the protocol eventually ends.
  val MAX_INPUTS_OUTPUTS_RECEIVED = 4096

  def addRemoteSigs(fundingParams: InteractiveTxParams, partiallySignedTx: PartiallySignedSharedTransaction, remoteSigs: TxSignatures): Either[ChannelException, FullySignedSharedTransaction] = {
    if (partiallySignedTx.tx.localInputs.length != partiallySignedTx.localSigs.witnesses.length) {
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
    }
    if (partiallySignedTx.tx.remoteInputs.length != remoteSigs.witnesses.length) {
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
    }
    val sharedSigs_opt = fundingParams.sharedInput_opt match {
      case Some(sharedInput: Multisig2of2Input) =>
        (partiallySignedTx.localSigs.previousFundingTxSig_opt, remoteSigs.previousFundingTxSig_opt) match {
          case (Some(localSig), Some(remoteSig)) => Some(Scripts.witness2of2(localSig, remoteSig, sharedInput.localFundingPubkey, sharedInput.remoteFundingPubkey))
          case _ => return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
        }
      case None => None
    }
    val txWithSigs = FullySignedSharedTransaction(partiallySignedTx.tx, partiallySignedTx.localSigs, remoteSigs, sharedSigs_opt)
    if (remoteSigs.txId != txWithSigs.signedTx.txid) {
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
    }
    // We allow a 5% error margin since witness size prediction could be inaccurate.
    if (fundingParams.localAmount > 0.sat && txWithSigs.feerate < fundingParams.targetFeerate * 0.95) {
      return Left(InvalidFundingFeerate(fundingParams.channelId, fundingParams.targetFeerate, txWithSigs.feerate))
    }
    val previousOutputs = {
      val sharedOutput = fundingParams.sharedInput_opt.map(sharedInput => sharedInput.info.outPoint -> sharedInput.info.txOut).toMap
      val localOutputs = txWithSigs.tx.localInputs.map(i => i.outPoint -> i.previousTx.txOut(i.previousTxOutput.toInt)).toMap
      val remoteOutputs = txWithSigs.tx.remoteInputs.map(i => i.outPoint -> i.txOut).toMap
      sharedOutput ++ localOutputs ++ remoteOutputs
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
                                   channelParams: ChannelParams,
                                   purpose: Purpose,
                                   localPushAmount: MilliSatoshi,
                                   remotePushAmount: MilliSatoshi,
                                   wallet: OnChainChannelFunder,
                                   stash: StashBuffer[InteractiveTxBuilder.Command],
                                   context: ActorContext[InteractiveTxBuilder.Command])(implicit ec: ExecutionContext) {

  import InteractiveTxBuilder._

  private val log = context.log
  private val keyManager = nodeParams.channelKeyManager
  private val remoteNodeId = channelParams.remoteParams.nodeId
  private val previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction] = purpose match {
    case rbf: PreviousTxRbf => rbf.previousTransactions
    case _ => Nil
  }

  def start(): Behavior[Command] = {
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

  private def buildTx(localContributions: InteractiveTxFunder.FundingContributions): Behavior[Command] = {
    val toSend = localContributions.inputs ++ localContributions.outputs
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
      case (addInput: Input) +: tail =>
        val message = addInput match {
          case i: Input.Local => TxAddInput(fundingParams.channelId, i.serialId, Some(i.previousTx), i.previousTxOutput, i.sequence)
          case i: Input.Shared => TxAddInput(fundingParams.channelId, i.serialId, i.outPoint, i.sequence)
        }
        replyTo ! SendMessage(message)
        val next = session.copy(toSend = tail, localInputs = session.localInputs :+ addInput, txCompleteSent = false)
        receive(next)
      case (addOutput: Output) +: tail =>
        val message = TxAddOutput(fundingParams.channelId, addOutput.serialId, addOutput.amount, addOutput.pubkeyScript)
        replyTo ! SendMessage(message)
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

  private def receiveInput(session: InteractiveTxSession, addInput: TxAddInput): Either[ChannelException, IncomingInput] = {
    if (session.inputsReceivedCount + 1 >= MAX_INPUTS_OUTPUTS_RECEIVED) {
      return Left(TooManyInteractiveTxRounds(fundingParams.channelId))
    }
    if (session.remoteInputs.exists(_.serialId == addInput.serialId)) {
      return Left(DuplicateSerialId(fundingParams.channelId, addInput.serialId))
    }
    // We check whether this is the shared input or a remote input.
    val input = addInput.previousTx_opt match {
      case Some(previousTx) if previousTx.txOut.length <= addInput.previousTxOutput =>
        return Left(InputOutOfBounds(fundingParams.channelId, addInput.serialId, previousTx.txid, addInput.previousTxOutput))
      case Some(previousTx) if fundingParams.sharedInput_opt.exists(_.info.outPoint == OutPoint(previousTx, addInput.previousTxOutput.toInt)) =>
        return Left(InvalidSharedInput(fundingParams.channelId, addInput.serialId))
      case Some(previousTx) if !Script.isNativeWitnessScript(previousTx.txOut(addInput.previousTxOutput.toInt).publicKeyScript) =>
        return Left(NonSegwitInput(fundingParams.channelId, addInput.serialId, previousTx.txid, addInput.previousTxOutput))
      case Some(previousTx) =>
        Input.Remote(addInput.serialId, OutPoint(previousTx, addInput.previousTxOutput.toInt), previousTx.txOut(addInput.previousTxOutput.toInt), addInput.sequence)
      case None =>
        (addInput.sharedInput_opt, fundingParams.sharedInput_opt) match {
          case (Some(outPoint), Some(sharedInput)) if outPoint == sharedInput.info.outPoint =>
            Input.Shared(addInput.serialId, outPoint, addInput.sequence, purpose.previousLocalBalance, purpose.previousRemoteBalance)
          case _ =>
            return Left(PreviousTxMissing(fundingParams.channelId, addInput.serialId))
        }
    }
    if (session.localInputs.exists(_.outPoint == input.outPoint) || session.remoteInputs.exists(_.outPoint == input.outPoint)) {
      return Left(DuplicateInput(fundingParams.channelId, addInput.serialId, input.outPoint.txid, input.outPoint.index))
    }
    if (input.sequence > 0xfffffffdL) {
      return Left(NonReplaceableInput(fundingParams.channelId, addInput.serialId, input.outPoint.txid, input.outPoint.index, addInput.sequence))
    }
    Right(input)
  }

  private def receiveOutput(session: InteractiveTxSession, addOutput: TxAddOutput): Either[ChannelException, IncomingOutput] = {
    if (session.outputsReceivedCount + 1 >= MAX_INPUTS_OUTPUTS_RECEIVED) {
      Left(TooManyInteractiveTxRounds(fundingParams.channelId))
    } else if (session.remoteOutputs.exists(_.serialId == addOutput.serialId)) {
      Left(DuplicateSerialId(fundingParams.channelId, addOutput.serialId))
    } else if (addOutput.amount < fundingParams.dustLimit) {
      Left(OutputBelowDust(fundingParams.channelId, addOutput.serialId, addOutput.amount, fundingParams.dustLimit))
    } else if (addOutput.pubkeyScript == fundingParams.fundingPubkeyScript && addOutput.amount != fundingParams.fundingAmount) {
      Left(InvalidSharedOutputAmount(fundingParams.channelId, addOutput.serialId, addOutput.amount, fundingParams.fundingAmount))
    } else if (addOutput.pubkeyScript == fundingParams.fundingPubkeyScript) {
      Right(Output.Shared(addOutput.serialId, addOutput.pubkeyScript, fundingParams.localAmount, fundingParams.remoteAmount))
    } else {
      Right(Output.Remote(addOutput.serialId, addOutput.amount, addOutput.pubkeyScript))
    }
  }

  private def receive(session: InteractiveTxSession): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case ReceiveTxMessage(msg) => msg match {
        case msg: HasSerialId if msg.serialId.toByteVector.bits.last != fundingParams.isInitiator =>
          replyTo ! RemoteFailure(InvalidSerialId(fundingParams.channelId, msg.serialId))
          unlockAndStop(session)
        case addInput: TxAddInput =>
          receiveInput(session, addInput) match {
            case Left(f) =>
              replyTo ! RemoteFailure(f)
              unlockAndStop(session)
            case Right(input) =>
              val next = session.copy(
                remoteInputs = session.remoteInputs :+ input,
                inputsReceivedCount = session.inputsReceivedCount + 1,
                txCompleteReceived = false,
              )
              send(next)
          }
        case addOutput: TxAddOutput =>
          receiveOutput(session, addOutput) match {
            case Left(f) =>
              replyTo ! RemoteFailure(f)
              unlockAndStop(session)
            case Right(output) =>
              val next = session.copy(
                remoteOutputs = session.remoteOutputs :+ output,
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
      // We ignore the shared input: we know it is a valid input since it comes from our commitment.
      context.pipeToSelf(checkInputsConfirmed(session.remoteInputs.collect { case i: Input.Remote => i })) {
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

  private def checkInputsConfirmed(inputs: Seq[Input.Remote]): Future[Boolean] = {
    // We check inputs sequentially and stop at the first unconfirmed one.
    inputs.map(_.outPoint.txid).toSet.foldLeft(Future.successful(true)) {
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
    if (session.localInputs.length + session.remoteInputs.length > 252 || session.localOutputs.length + session.remoteOutputs.length > 252) {
      log.warn("invalid interactive tx ({} local inputs, {} remote inputs, {} local outputs and {} remote outputs)", session.localInputs.length, session.remoteInputs.length, session.localOutputs.length, session.remoteOutputs.length)
      return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
    }

    val sharedInputs = session.localInputs.collect { case i: Input.Shared => i } ++ session.remoteInputs.collect { case i: Input.Shared => i }
    val localInputs = session.localInputs.collect { case i: Input.Local => i }
    val remoteInputs = session.remoteInputs.collect { case i: Input.Remote => i }
    val sharedOutputs = session.localOutputs.collect { case o: Output.Shared => o } ++ session.remoteOutputs.collect { case o: Output.Shared => o }
    val localOutputs = session.localOutputs.collect { case o: Output.Local => o }
    val remoteOutputs = session.remoteOutputs.collect { case o: Output.Remote => o }

    val sharedInput_opt = fundingParams.sharedInput_opt.map(_ => {
      val remoteReserve = (fundingParams.fundingAmount / 100).max(channelParams.localParams.dustLimit)
      if (fundingParams.remoteAmount < remoteReserve && remoteOutputs.nonEmpty) {
        log.warn("invalid interactive tx: peer takes too much funds out and falls below the channel reserve ({} < {})", fundingParams.remoteAmount, remoteReserve)
        return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
      }
      if (sharedInputs.length > 1) {
        log.warn("invalid interactive tx: shared input included multiple times")
        return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
      }
      sharedInputs.headOption match {
        case Some(input) => input
        case None =>
          log.warn("invalid interactive tx: shared input not included")
          return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
      }
    })

    if (sharedOutputs.length > 1) {
      log.warn("invalid interactive tx: funding script included multiple times")
      return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
    }
    val sharedOutput = sharedOutputs.headOption match {
      case Some(output) => output
      case None =>
        log.warn("invalid interactive tx: funding outpoint not included")
        return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
    }

    val sharedTx = SharedTransaction(sharedInput_opt, sharedOutput, localInputs.toList, remoteInputs.toList, localOutputs.toList, remoteOutputs.toList, fundingParams.lockTime)
    val tx = sharedTx.buildUnsignedTx()
    val sharedOutputIndex = tx.txOut.indexWhere(_.publicKeyScript == fundingParams.fundingPubkeyScript)

    if (sharedTx.localAmountIn < sharedTx.localAmountOut || sharedTx.remoteAmountIn < sharedTx.remoteAmountOut) {
      log.warn("invalid interactive tx: input amount is too small (localIn={}, localOut={}, remoteIn={}, remoteOut={})", sharedTx.localAmountIn, sharedTx.localAmountOut, sharedTx.remoteAmountIn, sharedTx.remoteAmountOut)
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
    Funding.makeCommitTxsWithoutHtlcs(keyManager, channelParams,
      fundingAmount = fundingParams.fundingAmount,
      toLocal = fundingParams.localAmount - localPushAmount + remotePushAmount,
      toRemote = fundingParams.remoteAmount - remotePushAmount + localPushAmount,
      purpose.commitTxFeerate, fundingTx.hash, fundingOutputIndex, purpose.remotePerCommitmentPoint, commitmentIndex = purpose.localCommitIndex) match {
      case Left(cause) =>
        replyTo ! RemoteFailure(cause)
        unlockAndStop(completeTx)
      case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx)) =>
        require(fundingTx.txOut(fundingOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript, "pubkey script mismatch!")
        val fundingPubKey = keyManager.fundingPublicKey(channelParams.localParams.fundingKeyPath)
        val localSigOfLocalTx = keyManager.sign(localCommitTx, fundingPubKey, TxOwner.Local, channelParams.channelFeatures.commitmentFormat)
        val localSigOfRemoteTx = keyManager.sign(remoteCommitTx, fundingPubKey, TxOwner.Remote, channelParams.channelFeatures.commitmentFormat)
        val localCommitSig = CommitSig(fundingParams.channelId, localSigOfRemoteTx, Nil)
        replyTo ! SendMessage(localCommitSig)
        Behaviors.receiveMessagePartial {
          case ReceiveCommitSig(remoteCommitSig) =>
            val signedLocalCommitTx = Transactions.addSigs(localCommitTx, fundingPubKey.publicKey, channelParams.remoteParams.fundingPubKey, localSigOfLocalTx, remoteCommitSig.signature)
            Transactions.checkSpendable(signedLocalCommitTx) match {
              case Failure(_) =>
                replyTo ! RemoteFailure(InvalidCommitmentSignature(fundingParams.channelId, signedLocalCommitTx.tx.txid))
                unlockAndStop(completeTx)
              case Success(_) =>
                val localCommit = LocalCommit(purpose.localCommitIndex, localSpec, CommitTxAndRemoteSig(localCommitTx, remoteCommitSig.signature), htlcTxsAndRemoteSigs = Nil)
                val remoteCommit = RemoteCommit(purpose.remoteCommitIndex, remoteSpec, remoteCommitTx.tx.txid, purpose.remotePerCommitmentPoint)
                signFundingTx(completeTx, localCommit, remoteCommit)
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

  private def createCommitment(fundingTx: DualFundedUnconfirmedFundingTx, localCommit: LocalCommit, remoteCommit: RemoteCommit): Commitment = {
    Commitment(fundingTx, RemoteFundingStatus.NotLocked, localCommit, remoteCommit, nextRemoteCommit_opt = None)
  }

  private def signFundingTx(completeTx: SharedTransaction, localCommit: LocalCommit, remoteCommit: RemoteCommit): Behavior[Command] = {
    val shouldSignFirst = if (completeTx.localAmountIn == completeTx.remoteAmountIn) {
      // When both peers contribute the same amount, the peer with the lowest pubkey must transmit its `tx_signatures` first.
      LexicographicalOrdering.isLessThan(channelParams.localNodeId.value, channelParams.remoteNodeId.value)
    } else if (fundingParams.sharedInput_opt.isEmpty && completeTx.remoteAmountIn == 0.sat) {
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
            val fundingTx = DualFundedUnconfirmedFundingTx(fullySignedTx, nodeParams.currentBlockHeight, fundingParams)
            val commitment = createCommitment(fundingTx, localCommit, remoteCommit)
            replyTo ! Succeeded(fundingTx, commitment)
            Behaviors.stopped
        }
      case SignTransactionResult(signedTx, None) =>
        // We return as soon as we sign the tx, because we need to be able to handle the case where remote publishes the
        // tx right away without properly sending us their signatures.
        if (fundingParams.sharedInput_opt.isEmpty && completeTx.remoteAmountIn == 0.sat) {
          log.info("interactive-tx fully signed with {} local inputs, {} remote inputs, {} local outputs and {} remote outputs", signedTx.tx.localInputs.length, signedTx.tx.remoteInputs.length, signedTx.tx.localOutputs.length, signedTx.tx.remoteOutputs.length)
          val remoteSigs = TxSignatures(signedTx.localSigs.channelId, signedTx.localSigs.txHash, Nil)
          val signedTx1 = FullySignedSharedTransaction(signedTx.tx, signedTx.localSigs, remoteSigs, None)
          val fundingTx = DualFundedUnconfirmedFundingTx(signedTx1, nodeParams.currentBlockHeight, fundingParams)
          val commitment = createCommitment(fundingTx, localCommit, remoteCommit)
          replyTo ! Succeeded(fundingTx, commitment)
        } else {
          log.info("interactive-tx partially signed with {} local inputs, {} remote inputs, {} local outputs and {} remote outputs", signedTx.tx.localInputs.length, signedTx.tx.remoteInputs.length, signedTx.tx.localOutputs.length, signedTx.tx.remoteOutputs.length)
          val fundingTx = DualFundedUnconfirmedFundingTx(signedTx, nodeParams.currentBlockHeight, fundingParams)
          val commitment = createCommitment(fundingTx, localCommit, remoteCommit)
          replyTo ! Succeeded(fundingTx, commitment)
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
    val sharedSig_opt = fundingParams.sharedInput_opt.map(_.sign(keyManager, channelParams, tx))
    if (unsignedTx.localInputs.isEmpty) {
      context.self ! SignTransactionResult(PartiallySignedSharedTransaction(unsignedTx, TxSignatures(fundingParams.channelId, tx, Nil, sharedSig_opt)), remoteSigs_opt)
    } else {
      context.pipeToSelf(wallet.signTransaction(tx, allowIncomplete = true).map {
        case SignTransactionResponse(signedTx, _) =>
          val localOutpoints = unsignedTx.localInputs.map(_.outPoint).toSet
          val sigs = signedTx.txIn.filter(txIn => localOutpoints.contains(txIn.outPoint)).map(_.witness)
          PartiallySignedSharedTransaction(unsignedTx, TxSignatures(fundingParams.channelId, tx, sigs, sharedSig_opt))
      }) {
        case Failure(t) => WalletFailure(t)
        case Success(signedTx) => SignTransactionResult(signedTx, remoteSigs_opt)
      }
    }
  }

  private def unlockAndStop(session: InteractiveTxSession): Behavior[Command] = {
    val localInputs = session.localInputs ++ session.toSend.collect { case addInput: Input.Local => addInput }
    unlockAndStop(localInputs.map(_.outPoint).toSet)
  }

  private def unlockAndStop(tx: SharedTransaction): Behavior[Command] = {
    val localInputs = tx.localInputs.map(_.outPoint).toSet
    unlockAndStop(localInputs)
  }

  private def unlockAndStop(txInputs: Set[OutPoint]): Behavior[Command] = {
    // We don't unlock previous inputs as the corresponding funding transaction may confirm.
    val previousInputs = previousTransactions.flatMap(_.tx.localInputs.map(_.outPoint)).toSet
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