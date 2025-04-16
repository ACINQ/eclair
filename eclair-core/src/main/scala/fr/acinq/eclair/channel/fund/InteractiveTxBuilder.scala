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

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior}
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, LexicographicalOrdering, OutPoint, Satoshi, SatoshiLong, Script, ScriptWitness, Transaction, TxId, TxIn, TxOut}
import fr.acinq.eclair.blockchain.OnChainChannelFunder
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Closing.MutualClose
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.Output.Local
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.Purpose
import fr.acinq.eclair.channel.fund.InteractiveTxSigningSession.UnsignedLocalCommit
import fr.acinq.eclair.crypto.keymanager.{ChannelKeys, LocalCommitmentKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.transactions.Transactions.{CommitTx, HtlcTx, InputInfo, TxOwner}
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, Logs, MilliSatoshi, MilliSatoshiLong, NodeParams, ToMilliSatoshiConversion, UInt64}
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
 * This actor returns [[InteractiveTxBuilder.Succeeded]] once we're ready to send our signatures for the commitment and
 * the shared transaction. Once those are sent, we must remember it because the transaction may confirm (unless it is
 * double-spent).
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
  //     +-------+                             +-------+

  // @formatter:off
  sealed trait Command
  case class Start(replyTo: ActorRef[Response]) extends Command
  case class ReceiveMessage(msg: InteractiveTxConstructionMessage) extends Command
  case object Abort extends Command
  private case class FundTransactionResult(result: InteractiveTxFunder.Response) extends Command
  private case object ValidateSharedTx extends Command
  private case class SignTransactionResult(signedTx: PartiallySignedSharedTransaction) extends Command
  private case class WalletFailure(t: Throwable) extends Command
  private case object UtxosUnlocked extends Command

  sealed trait Response
  case class SendMessage(sessionId: ByteVector32, msg: LightningMessage) extends Response
  case class Succeeded(signingSession: InteractiveTxSigningSession.WaitingForSigs, commitSig: CommitSig, liquidityPurchase_opt: Option[LiquidityAds.Purchase]) extends Response
  sealed trait Failed extends Response { def cause: ChannelException }
  case class LocalFailure(cause: ChannelException) extends Failed
  case class RemoteFailure(cause: ChannelException) extends Failed
  // @formatter:on

  case class RequireConfirmedInputs(forLocal: Boolean, forRemote: Boolean)

  /** An input that is already shared between participants (e.g. the current funding output when doing a splice). */
  sealed trait SharedFundingInput {
    // @formatter:off
    def info: InputInfo
    def weight: Int
    // @formatter:on
  }

  object SharedFundingInput {
    def apply(commitment: Commitment): SharedFundingInput = commitment.commitInput.redeemInfo match {
      case _: RedeemInfo.SegwitV0 => Multisig2of2Input(commitment.commitInput, commitment.fundingTxIndex, commitment.remoteFundingPubKey)
      case _: RedeemInfo.TaprootKeyPath => Musig2Input(commitment.commitInput, commitment.fundingTxIndex, commitment.remoteFundingPubKey)
      case _ => throw new IllegalArgumentException(s"invalid commitment input")
    }
  }

  case class Multisig2of2Input(info: InputInfo, fundingTxIndex: Long, remoteFundingPubkey: PublicKey) extends SharedFundingInput {
    override val weight: Int = 388

    def sign(channelKeys: ChannelKeys, params: ChannelParams, tx: Transaction, spentUtxos: Map[OutPoint, TxOut]): ByteVector64 = {
      val localFundingKey = channelKeys.fundingKey(fundingTxIndex)
      Transactions.SpliceTx(info, tx).sign(localFundingKey, TxOwner.Local, params.commitmentFormat, spentUtxos)
    }
  }

  case class Musig2Input(info: InputInfo, fundingTxIndex: Long, remoteFundingPubkey: PublicKey) extends SharedFundingInput {
    // witness is a single 64 bytes signature, weight = 1 (# of items) + 1 (size) + 64 = 66
    // weight is 4 * (unsigned input weight) + witness weight = 4 * (32 + 4 + 4 + 1) + 66 = 230
    override val weight: Int = 230
  }

  /**
   * @param channelId              id of the channel.
   * @param isInitiator            true if we initiated the protocol, in which case we will pay fees for the shared parts of the transaction.
   * @param localContribution      amount contributed by us to the shared output (can be negative when removing funds from an existing channel).
   * @param remoteContribution     amount contributed by our peer to the shared output (can be negative when removing funds from an existing channel).
   * @param sharedInput_opt        previous input shared between the two participants (e.g. previous funding output when splicing).
   * @param remoteFundingPubKey    public key provided by our peer, that will be combined with ours to create the script of the shared output.
   * @param localOutputs           outputs to be added to the shared transaction (e.g. splice-out).
   * @param lockTime               transaction lock time.
   * @param dustLimit              outputs below this value are considered invalid.
   * @param targetFeerate          transaction feerate.
   * @param requireConfirmedInputs we may require that inputs added to the transaction are confirmed, especially with peers we don't trust.
   */
  case class InteractiveTxParams(channelId: ByteVector32,
                                 isInitiator: Boolean,
                                 localContribution: Satoshi,
                                 remoteContribution: Satoshi,
                                 sharedInput_opt: Option[SharedFundingInput],
                                 remoteFundingPubKey: PublicKey,
                                 localOutputs: List[TxOut],
                                 lockTime: Long,
                                 dustLimit: Satoshi,
                                 targetFeerate: FeeratePerKw,
                                 requireConfirmedInputs: RequireConfirmedInputs) {
    /** The amount of the new funding output, which is the sum of the shared input, if any, and both sides' contributions. */
    val fundingAmount: Satoshi = sharedInput_opt.map(_.info.txOut.amount).getOrElse(0 sat) + localContribution + remoteContribution
    // BOLT 2: MUST set `feerate` greater than or equal to 25/24 times the `feerate` of the previously constructed transaction, rounded down.
    val minNextFeerate: FeeratePerKw = targetFeerate * 25 / 24
    // BOLT 2: the initiator's serial IDs MUST use even values and the non-initiator odd values.
    val serialIdParity: Int = if (isInitiator) 0 else 1

    def liquidityFees(liquidityPurchase_opt: Option[LiquidityAds.Purchase]): MilliSatoshi = {
      liquidityPurchase_opt.map(l => l.paymentDetails match {
        // The initiator of the interactive-tx is the liquidity buyer (if liquidity ads is used).
        case LiquidityAds.PaymentDetails.FromChannelBalance | _: LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc =>
          val feesOwed = l match {
            case l: LiquidityAds.Purchase.Standard => l.fees.total.toMilliSatoshi
            case l: LiquidityAds.Purchase.WithFeeCredit => l.fees.total.toMilliSatoshi - l.feeCreditUsed
          }
          if (isInitiator) feesOwed else -feesOwed
        // Fees will be paid later, when relaying HTLCs.
        case _: LiquidityAds.PaymentDetails.FromFutureHtlc | _: LiquidityAds.PaymentDetails.FromFutureHtlcWithPreimage => 0 msat
      }).getOrElse(0 msat)
    }
  }

  // @formatter:off
  sealed trait Purpose {
    def previousLocalBalance: MilliSatoshi
    def previousRemoteBalance: MilliSatoshi
    def previousFundingAmount: Satoshi
    def localCommitIndex: Long
    def remoteCommitIndex: Long
    def localNextHtlcId: Long
    def remoteNextHtlcId: Long
    def remotePerCommitmentPoint: PublicKey
    def commitTxFeerate: FeeratePerKw
    def fundingTxIndex: Long
    def localHtlcs: Set[DirectedHtlc]
    def htlcBalance: MilliSatoshi = localHtlcs.toSeq.map(_.add.amountMsat).sum
  }
  case class FundingTx(commitTxFeerate: FeeratePerKw, remotePerCommitmentPoint: PublicKey, feeBudget_opt: Option[Satoshi]) extends Purpose {
    override val previousLocalBalance: MilliSatoshi = 0 msat
    override val previousRemoteBalance: MilliSatoshi = 0 msat
    override val previousFundingAmount: Satoshi = 0 sat
    override val localCommitIndex: Long = 0
    override val remoteCommitIndex: Long = 0
    override val localNextHtlcId: Long = 0
    override val remoteNextHtlcId: Long = 0
    override val fundingTxIndex: Long = 0
    override val localHtlcs: Set[DirectedHtlc] = Set.empty
  }
  case class SpliceTx(parentCommitment: Commitment, changes: CommitmentChanges) extends Purpose {
    override val previousLocalBalance: MilliSatoshi = parentCommitment.localCommit.spec.toLocal
    override val previousRemoteBalance: MilliSatoshi = parentCommitment.remoteCommit.spec.toLocal
    override val previousFundingAmount: Satoshi = parentCommitment.capacity
    override val localCommitIndex: Long = parentCommitment.localCommit.index
    override val remoteCommitIndex: Long = parentCommitment.remoteCommit.index
    override val localNextHtlcId: Long = changes.localNextHtlcId
    override val remoteNextHtlcId: Long = changes.remoteNextHtlcId
    override val remotePerCommitmentPoint: PublicKey = parentCommitment.remoteCommit.remotePerCommitmentPoint
    override val commitTxFeerate: FeeratePerKw = parentCommitment.localCommit.spec.commitTxFeerate
    override val fundingTxIndex: Long = parentCommitment.fundingTxIndex + 1
    override val localHtlcs: Set[DirectedHtlc] = parentCommitment.localCommit.spec.htlcs
  }
  /**
   * @param previousTransactions interactive transactions are replaceable and can be RBF-ed, but we need to make sure that
   *                             only one of them ends up confirming. We guarantee this by having the latest transaction
   *                             always double-spend all its predecessors.
   */
  case class FundingTxRbf(replacedCommitment: Commitment, previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction], feeBudget_opt: Option[Satoshi]) extends Purpose {
    override val previousLocalBalance: MilliSatoshi = 0 msat
    override val previousRemoteBalance: MilliSatoshi = 0 msat
    override val previousFundingAmount: Satoshi = 0 sat
    override val localCommitIndex: Long = replacedCommitment.localCommit.index
    override val remoteCommitIndex: Long = replacedCommitment.remoteCommit.index
    override val localNextHtlcId: Long = 0
    override val remoteNextHtlcId: Long = 0
    override val remotePerCommitmentPoint: PublicKey = replacedCommitment.remoteCommit.remotePerCommitmentPoint
    override val commitTxFeerate: FeeratePerKw = replacedCommitment.localCommit.spec.commitTxFeerate
    override val fundingTxIndex: Long = replacedCommitment.fundingTxIndex
    override val localHtlcs: Set[DirectedHtlc] = replacedCommitment.localCommit.spec.htlcs
  }

  /**
   * @param previousTransactions splice RBF attempts all spend the previous funding transaction, so they automatically
   *                             double-spend each other, but we reuse previous inputs as much as possible anyway.
   */
  case class SpliceTxRbf(parentCommitment: Commitment, changes: CommitmentChanges, latestFundingTx: LocalFundingStatus.DualFundedUnconfirmedFundingTx, previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction], feeBudget_opt: Option[Satoshi]) extends Purpose {
    override val previousLocalBalance: MilliSatoshi = parentCommitment.localCommit.spec.toLocal
    override val previousRemoteBalance: MilliSatoshi = parentCommitment.remoteCommit.spec.toLocal
    override val previousFundingAmount: Satoshi = parentCommitment.capacity
    override val localCommitIndex: Long = parentCommitment.localCommit.index
    override val remoteCommitIndex: Long = parentCommitment.remoteCommit.index
    override val localNextHtlcId: Long = changes.localNextHtlcId
    override val remoteNextHtlcId: Long = changes.remoteNextHtlcId
    override val remotePerCommitmentPoint: PublicKey = parentCommitment.remoteCommit.remotePerCommitmentPoint
    override val commitTxFeerate: FeeratePerKw = parentCommitment.localCommit.spec.commitTxFeerate
    override val fundingTxIndex: Long = parentCommitment.fundingTxIndex + 1
    override val localHtlcs: Set[DirectedHtlc] = parentCommitment.localCommit.spec.htlcs
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
    def txOut: TxOut
  }
  object Input {
    /** A local-only input that funds the interactive transaction. */
    case class Local(serialId: UInt64, previousTx: Transaction, previousTxOutput: Long, sequence: Long) extends Input with Outgoing {
      override val outPoint: OutPoint = OutPoint(previousTx, previousTxOutput.toInt)
      override def txOut: TxOut = previousTx.txOut(previousTxOutput.toInt)
    }

    /**
     * A remote-only input that funds the interactive transaction.
     * We only keep the data we need from our peer's TxAddInput to avoid storing potentially large messages in our DB.
     */
    case class Remote(serialId: UInt64, outPoint: OutPoint, txOut: TxOut, sequence: Long) extends Input with Incoming

    /** The shared input can be added by us or by our peer, depending on who initiated the protocol. */
    case class Shared(serialId: UInt64, outPoint: OutPoint, publicKeyScript: ByteVector, sequence: Long, localAmount: MilliSatoshi, remoteAmount: MilliSatoshi, htlcAmount: MilliSatoshi) extends Input with Incoming with Outgoing {
      override def txOut: TxOut = TxOut((localAmount + remoteAmount + htlcAmount).truncateToSatoshi, publicKeyScript)
    }
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
    case class Shared(serialId: UInt64, pubkeyScript: ByteVector, localAmount: MilliSatoshi, remoteAmount: MilliSatoshi, htlcAmount: MilliSatoshi) extends Output with Incoming with Outgoing {
      // Note that the truncation is a no-op: the sum of balances in a channel must be a satoshi amount.
      override val amount: Satoshi = (localAmount + remoteAmount + htlcAmount).truncateToSatoshi
    }
  }

  type OutgoingInput = Input with Outgoing
  private type IncomingInput = Input with Incoming
  type OutgoingOutput = Output with Outgoing
  private type IncomingOutput = Output with Incoming
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
    val localAmountIn: MilliSatoshi = sharedInput_opt.map(_.localAmount).getOrElse(0 msat) + localInputs.map(i => i.txOut.amount).sum
    val remoteAmountIn: MilliSatoshi = sharedInput_opt.map(_.remoteAmount).getOrElse(0 msat) + remoteInputs.map(_.txOut.amount).sum
    val localAmountOut: MilliSatoshi = sharedOutput.localAmount + localOutputs.map(_.amount).sum
    val remoteAmountOut: MilliSatoshi = sharedOutput.remoteAmount + remoteOutputs.map(_.amount).sum
    val localFees: MilliSatoshi = localAmountIn - localAmountOut
    val remoteFees: MilliSatoshi = remoteAmountIn - remoteAmountOut
    // Note that the truncation is a no-op: sub-satoshi balances are carried over from inputs to outputs and cancel out.
    val fees: Satoshi = (localFees + remoteFees).truncateToSatoshi
    // When signing transactions that include taproot inputs, we must provide details about all of the transaction's inputs.
    val inputDetails: Map[OutPoint, TxOut] = (sharedInput_opt.toSeq.map(i => i.outPoint -> i.txOut) ++ localInputs.map(i => i.outPoint -> i.txOut) ++ remoteInputs.map(i => i.outPoint -> i.txOut)).toMap

    def localOnlyNonChangeOutputs: List[Output.Local.NonChange] = localOutputs.collect { case o: Local.NonChange => o }

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
    def txId: TxId
    def tx: SharedTransaction
    def localSigs: TxSignatures
    def signedTx_opt: Option[Transaction]
  }
  case class PartiallySignedSharedTransaction(tx: SharedTransaction, localSigs: TxSignatures) extends SignedSharedTransaction {
    override val txId: TxId = tx.buildUnsignedTx().txid
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
    override val txId: TxId = signedTx.txid
    override val signedTx_opt: Option[Transaction] = Some(signedTx)
    val feerate: FeeratePerKw = Transactions.fee2rate(tx.fees, signedTx.weight())
  }
  // @formatter:on

  def apply(sessionId: ByteVector32,
            nodeParams: NodeParams,
            fundingParams: InteractiveTxParams,
            channelParams: ChannelParams,
            channelKeys: ChannelKeys,
            purpose: Purpose,
            localPushAmount: MilliSatoshi,
            remotePushAmount: MilliSatoshi,
            liquidityPurchase_opt: Option[LiquidityAds.Purchase],
            wallet: OnChainChannelFunder)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      // The stash is used to buffer messages that arrive while we're funding the transaction.
      // Since the interactive-tx protocol is turn-based, we should not have more than one stashed lightning message.
      // We may also receive commands from our parent, but we shouldn't receive many, so we can keep the stash size small.
      Behaviors.withStash(10) { stash =>
        Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(channelParams.remoteParams.nodeId), channelId_opt = Some(fundingParams.channelId))) {
          Behaviors.receiveMessagePartial {
            case Start(replyTo) =>
              val liquidityFee = fundingParams.liquidityFees(liquidityPurchase_opt)
              // Note that pending HTLCs are ignored: splices only affect the main outputs.
              val nextLocalBalance = purpose.previousLocalBalance + fundingParams.localContribution - localPushAmount + remotePushAmount - liquidityFee
              val nextRemoteBalance = purpose.previousRemoteBalance + fundingParams.remoteContribution - remotePushAmount + localPushAmount + liquidityFee
              val liquidityPaymentTypeOk = liquidityPurchase_opt match {
                case Some(l) if !fundingParams.isInitiator => l.paymentDetails match {
                  case LiquidityAds.PaymentDetails.FromChannelBalance | _: LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc => true
                  // If our peer has enough balance to pay the liquidity fees, they shouldn't use future HTLCs which
                  // involves trust: they should directly pay from their channel balance.
                  case _: LiquidityAds.PaymentDetails.FromFutureHtlc | _: LiquidityAds.PaymentDetails.FromFutureHtlcWithPreimage => nextRemoteBalance < l.fees.total
                }
                case _ => true
              }
              if (fundingParams.fundingAmount < fundingParams.dustLimit) {
                replyTo ! LocalFailure(FundingAmountTooLow(channelParams.channelId, fundingParams.fundingAmount, fundingParams.dustLimit))
                Behaviors.stopped
              } else if (nextLocalBalance < 0.msat || nextRemoteBalance < 0.msat) {
                replyTo ! LocalFailure(InvalidFundingBalances(channelParams.channelId, fundingParams.fundingAmount, nextLocalBalance, nextRemoteBalance))
                Behaviors.stopped
              } else if (!liquidityPaymentTypeOk) {
                replyTo ! LocalFailure(InvalidLiquidityAdsPaymentType(channelParams.channelId, liquidityPurchase_opt.get.paymentDetails.paymentType, Set(LiquidityAds.PaymentType.FromChannelBalance, LiquidityAds.PaymentType.FromChannelBalanceForFutureHtlc)))
                Behaviors.stopped
              } else {
                val actor = new InteractiveTxBuilder(replyTo, sessionId, nodeParams, channelParams, channelKeys, fundingParams, purpose, localPushAmount, remotePushAmount, liquidityPurchase_opt, wallet, stash, context)
                actor.start()
              }
            case Abort => Behaviors.stopped
          }
        }
      }
    }
  }

  // We restrict the number of inputs / outputs that our peer can send us to ensure the protocol eventually ends.
  val MAX_INPUTS_OUTPUTS_RECEIVED = 4096

}

private class InteractiveTxBuilder(replyTo: ActorRef[InteractiveTxBuilder.Response],
                                   sessionId: ByteVector32,
                                   nodeParams: NodeParams,
                                   channelParams: ChannelParams,
                                   channelKeys: ChannelKeys,
                                   fundingParams: InteractiveTxBuilder.InteractiveTxParams,
                                   purpose: Purpose,
                                   localPushAmount: MilliSatoshi,
                                   remotePushAmount: MilliSatoshi,
                                   liquidityPurchase_opt: Option[LiquidityAds.Purchase],
                                   wallet: OnChainChannelFunder,
                                   stash: StashBuffer[InteractiveTxBuilder.Command],
                                   context: ActorContext[InteractiveTxBuilder.Command])(implicit ec: ExecutionContext) {

  import InteractiveTxBuilder._

  private val log = context.log
  private val localFundingKey: PrivateKey = channelKeys.fundingKey(purpose.fundingTxIndex)
  private val fundingPubkeyScript: ByteVector = Script.write(Script.pay2wsh(Scripts.multiSig2of2(localFundingKey.publicKey, fundingParams.remoteFundingPubKey)))
  private val remoteNodeId = channelParams.remoteParams.nodeId
  private val previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction] = purpose match {
    case rbf: FundingTxRbf => rbf.previousTransactions
    case rbf: SpliceTxRbf => rbf.previousTransactions
    case _ => Nil
  }

  def start(): Behavior[Command] = {
    val txFunder = context.spawnAnonymous(InteractiveTxFunder(remoteNodeId, fundingParams, fundingPubkeyScript, purpose, wallet))
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
          case i: Input.Local =>
            // for debugging wallet locking issues, it is useful to log local utxos
            log.info(s"adding local input ${i.previousTx.txid}:${i.previousTxOutput} to interactive-tx")
            TxAddInput(fundingParams.channelId, i.serialId, Some(i.previousTx), i.previousTxOutput, i.sequence)
          case i: Input.Shared => TxAddInput(fundingParams.channelId, i.serialId, i.outPoint, i.sequence)
        }
        replyTo ! SendMessage(sessionId, message)
        val next = session.copy(toSend = tail, localInputs = session.localInputs :+ addInput, txCompleteSent = false)
        receive(next)
      case (addOutput: Output) +: tail =>
        val message = TxAddOutput(fundingParams.channelId, addOutput.serialId, addOutput.amount, addOutput.pubkeyScript)
        replyTo ! SendMessage(sessionId, message)
        val next = session.copy(toSend = tail, localOutputs = session.localOutputs :+ addOutput, txCompleteSent = false)
        receive(next)
      case Nil =>
        replyTo ! SendMessage(sessionId, TxComplete(fundingParams.channelId))
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
            Input.Shared(addInput.serialId, outPoint, sharedInput.info.txOut.publicKeyScript, addInput.sequence, purpose.previousLocalBalance, purpose.previousRemoteBalance, purpose.htlcBalance)
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
    } else if (addOutput.pubkeyScript == fundingPubkeyScript && addOutput.amount != fundingParams.fundingAmount) {
      Left(InvalidSharedOutputAmount(fundingParams.channelId, addOutput.serialId, addOutput.amount, fundingParams.fundingAmount))
    } else if (!MutualClose.isValidFinalScriptPubkey(addOutput.pubkeyScript, allowAnySegwit = true, allowOpReturn = false)) {
      Left(InvalidSpliceOutputScript(fundingParams.channelId, addOutput.serialId, addOutput.pubkeyScript))
    } else if (addOutput.pubkeyScript == fundingPubkeyScript) {
      Right(Output.Shared(addOutput.serialId, addOutput.pubkeyScript, purpose.previousLocalBalance + fundingParams.localContribution, purpose.previousRemoteBalance + fundingParams.remoteContribution, purpose.htlcBalance))
    } else {
      Right(Output.Remote(addOutput.serialId, addOutput.amount, addOutput.pubkeyScript))
    }
  }

  private def receive(session: InteractiveTxSession): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case ReceiveMessage(msg) => msg match {
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
        case Right(completeTx) =>
          signCommitTx(completeTx)
      }
      case _: WalletFailure =>
        replyTo ! RemoteFailure(UnconfirmedInteractiveTxInputs(fundingParams.channelId))
        unlockAndStop(session)
      case ReceiveMessage(msg) =>
        replyTo ! RemoteFailure(UnexpectedInteractiveTxMessage(fundingParams.channelId, msg))
        unlockAndStop(session)
      case Abort =>
        unlockAndStop(session)
    }
  }

  private def checkInputsConfirmed(inputs: Seq[Input.Remote]): Future[Boolean] = {
    // We check inputs sequentially and stop at the first unconfirmed one.
    inputs.map(_.outPoint).toSet.foldLeft(Future.successful(true)) {
      case (current, outpoint) => current.transformWith {
        case Success(true) => wallet.getTxConfirmations(outpoint.txid).flatMap {
          case Some(confirmations) if confirmations > 0 =>
            // The input is confirmed, so we can reliably check whether it is unspent, We don't check this for
            // unconfirmed inputs, because if they are valid but not in our mempool we would incorrectly consider
            // them unspendable (unknown). We want to reject unspendable inputs to immediately fail the funding
            // attempt, instead of waiting to detect the double-spend later.
            wallet.isTransactionOutputSpendable(outpoint.txid, outpoint.index.toInt, includeMempool = true)
          case _ => Future.successful(false)
        }
        case Success(false) => Future.successful(false)
        case Failure(t) => Future.failed(t)
      }
    }
  }

  private def validateTx(session: InteractiveTxSession): Either[ChannelException, SharedTransaction] = {
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

    val sharedInput_opt = fundingParams.sharedInput_opt.map(_ => {
      if (fundingParams.remoteContribution >= 0.sat) {
        // If remote has a positive contribution, we do not check their post-splice reserve level, because they are improving
        // their situation, even if they stay below the requirement. Note that if local splices-in some funds in the same
        // operation, remote post-splice reserve may actually be worse than before, but that's not their fault.
      } else {
        // If remote removes funds from the channel, it must meet reserve requirements post-splice
        val remoteReserve = channelParams.remoteChannelReserveForCapacity(fundingParams.fundingAmount, isSplice = true)
        if (sharedOutput.remoteAmount < remoteReserve) {
          log.warn("invalid interactive tx: peer takes too much funds out and falls below the channel reserve ({} < {})", sharedOutput.remoteAmount, remoteReserve)
          return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
        }
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

    val sharedTx = SharedTransaction(sharedInput_opt, sharedOutput, localInputs.toList, remoteInputs.toList, localOutputs.toList, remoteOutputs.toList, fundingParams.lockTime)
    val tx = sharedTx.buildUnsignedTx()
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

    liquidityPurchase_opt match {
      case Some(p: LiquidityAds.Purchase.WithFeeCredit) if !fundingParams.isInitiator =>
        val currentFeeCredit = nodeParams.db.liquidity.getFeeCredit(remoteNodeId)
        if (currentFeeCredit < p.feeCreditUsed) {
          log.warn("not enough fee credit: our peer may be malicious ({} < {})", currentFeeCredit, p.feeCreditUsed)
          return Left(InvalidCompleteInteractiveTx(fundingParams.channelId))
        }
      case _ => ()
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
          return Left(InvalidRbfFeerate(fundingParams.channelId, nextFeerate, previousFeerate))
        }
      case None =>
        val feeWithoutWitness = Transactions.weight2fee(fundingParams.targetFeerate, tx.weight())
        val minimumFee = liquidityPurchase_opt.map(_.paymentDetails) match {
          case Some(paymentDetails) => paymentDetails match {
            case LiquidityAds.PaymentDetails.FromChannelBalance | _: LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc => feeWithoutWitness
            // We allow the feerate to be lower than requested when using on-the-fly funding, because our peer may not
            // be able to contribute as much as expected to the funding transaction itself since they don't have funds.
            // It's acceptable because they will be paying liquidity fees from future HTLCs.
            case _: LiquidityAds.PaymentDetails.FromFutureHtlc | _: LiquidityAds.PaymentDetails.FromFutureHtlcWithPreimage => feeWithoutWitness * 0.5
          }
          case None => feeWithoutWitness
        }
        if (sharedTx.fees < minimumFee) {
          log.warn("invalid interactive tx: below the target feerate (target={}, actual={})", fundingParams.targetFeerate, Transactions.fee2rate(sharedTx.fees, tx.weight()))
          return Left(InvalidSpliceFeerate(fundingParams.channelId, Transactions.fee2rate(sharedTx.fees, tx.weight()), fundingParams.targetFeerate))
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

    Right(sharedTx)
  }

  private def signCommitTx(completeTx: SharedTransaction): Behavior[Command] = {
    val fundingTx = completeTx.buildUnsignedTx()
    val fundingOutputIndex = fundingTx.txOut.indexWhere(_.publicKeyScript == fundingPubkeyScript)
    val liquidityFee = fundingParams.liquidityFees(liquidityPurchase_opt)
    val localCommitmentKeys = LocalCommitmentKeys(channelParams, channelKeys, purpose.localCommitIndex)
    val remoteCommitmentKeys = RemoteCommitmentKeys(channelParams, channelKeys, purpose.remotePerCommitmentPoint)
    Funding.makeCommitTxs(channelParams,
      fundingAmount = fundingParams.fundingAmount,
      toLocal = completeTx.sharedOutput.localAmount - localPushAmount + remotePushAmount - liquidityFee,
      toRemote = completeTx.sharedOutput.remoteAmount - remotePushAmount + localPushAmount + liquidityFee,
      localHtlcs = purpose.localHtlcs,
      purpose.commitTxFeerate,
      fundingTxIndex = purpose.fundingTxIndex,
      fundingTx.txid, fundingOutputIndex,
      localFundingKey, fundingParams.remoteFundingPubKey,
      localCommitmentKeys, remoteCommitmentKeys,
      localCommitmentIndex = purpose.localCommitIndex, remoteCommitmentIndex = purpose.remoteCommitIndex) match {
      case Left(cause) =>
        replyTo ! RemoteFailure(cause)
        unlockAndStop(completeTx)
      case Right((localSpec, localCommitTx, remoteSpec, remoteCommitTx, sortedHtlcTxs)) =>
        require(fundingTx.txOut(fundingOutputIndex).publicKeyScript == localCommitTx.input.txOut.publicKeyScript, "pubkey script mismatch!")
        val localSigOfRemoteTx = remoteCommitTx.sign(localFundingKey, TxOwner.Remote, channelParams.channelFeatures.commitmentFormat, Map.empty)
        val htlcSignatures = sortedHtlcTxs.map(_.sign(remoteCommitmentKeys.ourHtlcKey, TxOwner.Remote, channelParams.commitmentFormat, Map.empty)).toList
        val localCommitSig = CommitSig(fundingParams.channelId, localSigOfRemoteTx, htlcSignatures)
        val localCommit = UnsignedLocalCommit(purpose.localCommitIndex, localSpec, localCommitTx, htlcTxs = Nil)
        val remoteCommit = RemoteCommit(purpose.remoteCommitIndex, remoteSpec, remoteCommitTx.tx.txid, purpose.remotePerCommitmentPoint)
        signFundingTx(completeTx, localCommitSig, localCommit, remoteCommit)
    }
  }

  private def signFundingTx(completeTx: SharedTransaction, commitSig: CommitSig, localCommit: UnsignedLocalCommit, remoteCommit: RemoteCommit): Behavior[Command] = {
    signTx(completeTx)
    Behaviors.receiveMessagePartial {
      case SignTransactionResult(signedTx) =>
        log.info(s"interactive-tx txid=${signedTx.txId} partially signed with {} local inputs, {} remote inputs, {} local outputs and {} remote outputs", signedTx.tx.localInputs.length, signedTx.tx.remoteInputs.length, signedTx.tx.localOutputs.length, signedTx.tx.remoteOutputs.length)
        // At this point, we're not completely sure that the transaction will succeed: if our peer doesn't send their
        // commit_sig, the transaction will be aborted. But it's a best effort, because after sending our commit_sig,
        // we won't store details about the liquidity purchase so we'll be unable to emit that event later. Even after
        // fully signing the transaction, it may be double-spent by a force-close, which would invalidate it as well.
        // The right solution is to check confirmations on the funding transaction before considering that a liquidity
        // purchase is completed, which is what we do in our AuditDb.
        liquidityPurchase_opt.foreach { p =>
          val purchase = LiquidityPurchase(
            fundingTxId = signedTx.txId,
            fundingTxIndex = purpose.fundingTxIndex,
            isBuyer = fundingParams.isInitiator,
            amount = p.amount,
            fees = p.fees,
            capacity = fundingParams.fundingAmount,
            localContribution = fundingParams.localContribution,
            remoteContribution = fundingParams.remoteContribution,
            localBalance = localCommit.spec.toLocal,
            remoteBalance = localCommit.spec.toRemote,
            outgoingHtlcCount = purpose.localNextHtlcId,
            incomingHtlcCount = purpose.remoteNextHtlcId,
          )
          context.system.eventStream ! EventStream.Publish(ChannelLiquidityPurchased(replyTo.toClassic, channelParams.channelId, remoteNodeId, purchase))
        }
        val signingSession = InteractiveTxSigningSession.WaitingForSigs(
          fundingParams,
          purpose.fundingTxIndex,
          signedTx,
          Left(localCommit),
          remoteCommit,
          liquidityPurchase_opt.map(_.basicInfo(isBuyer = fundingParams.isInitiator))
        )
        replyTo ! Succeeded(signingSession, commitSig, liquidityPurchase_opt)
        Behaviors.stopped
      case WalletFailure(t) =>
        log.error("could not sign funding transaction: ", t)
        // We use a generic exception and don't send the internal error to the peer.
        replyTo ! LocalFailure(ChannelFundingError(fundingParams.channelId))
        unlockAndStop(completeTx)
      case ReceiveMessage(msg) =>
        replyTo ! RemoteFailure(UnexpectedInteractiveTxMessage(fundingParams.channelId, msg))
        unlockAndStop(completeTx)
      case Abort =>
        unlockAndStop(completeTx)
    }
  }

  private def signTx(unsignedTx: SharedTransaction): Unit = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val tx = unsignedTx.buildUnsignedTx()
    val sharedSig_opt = fundingParams.sharedInput_opt.collect {
      case i: Multisig2of2Input => i.sign(channelKeys, channelParams, tx, unsignedTx.inputDetails)
    }
    if (unsignedTx.localInputs.isEmpty) {
      context.self ! SignTransactionResult(PartiallySignedSharedTransaction(unsignedTx, TxSignatures(fundingParams.channelId, tx, Nil, sharedSig_opt)))
    } else {
      // We track our wallet inputs and outputs, so we can verify them when we sign the transaction: if Eclair is managing bitcoin core wallet keys, it will
      // only sign our wallet inputs, and check that it can re-compute private keys for our wallet outputs.
      val ourWalletInputs = unsignedTx.localInputs.map(i => tx.txIn.indexWhere(_.outPoint == i.outPoint))
      val ourWalletOutputs = unsignedTx.localOutputs.flatMap {
        case Output.Local.Change(_, amount, pubkeyScript) => Some(tx.txOut.indexWhere(output => output.amount == amount && output.publicKeyScript == pubkeyScript))
        // Non-change outputs may go to an external address (typically during a splice-out).
        // Here we only keep outputs which are ours i.e explicitly go back into our wallet.
        // We trust that non-change outputs are valid: this only works if the entry point for creating such outputs is trusted (for example, a secure API call).
        case _: Output.Local.NonChange => None
      }
      // If this is a splice, the PSBT we create must contain the shared input, because if we use taproot wallet inputs
      // we need information about *all* of the transaction's inputs, not just the one we're signing.
      val psbt = unsignedTx.sharedInput_opt.flatMap {
        si => new Psbt(tx).updateWitnessInput(si.outPoint, si.txOut, null, null, null, java.util.Map.of(), null, null, java.util.Map.of()).toOption
      }.getOrElse(new Psbt(tx))
      context.pipeToSelf(wallet.signPsbt(psbt, ourWalletInputs, ourWalletOutputs).map {
        response =>
          val localOutpoints = unsignedTx.localInputs.map(_.outPoint).toSet
          val partiallySignedTx = response.partiallySignedTx
          // Partially signed PSBT must include spent amounts for all inputs that were signed, and we can "trust" these amounts because they are included
          // in the hash that we signed (see BIP143). If our bitcoin node lied about them, then our signatures are invalid.
          val actualLocalAmountIn = ourWalletInputs.map(i => kmp2scala(response.psbt.getInput(i).getWitnessUtxo.amount)).sum
          val expectedLocalAmountIn = unsignedTx.localInputs.map(i => i.txOut.amount).sum
          require(actualLocalAmountIn == expectedLocalAmountIn, s"local spent amount $actualLocalAmountIn does not match what we expect ($expectedLocalAmountIn): bitcoin core may be malicious")
          val actualLocalAmountOut = ourWalletOutputs.map(i => partiallySignedTx.txOut(i).amount).sum
          val expectedLocalAmountOut = unsignedTx.localOutputs.map {
            case c: Output.Local.Change => c.amount
            case _: Output.Local.NonChange => 0.sat
          }.sum
          require(actualLocalAmountOut == expectedLocalAmountOut, s"local output amount $actualLocalAmountOut does not match what we expect ($expectedLocalAmountOut): bitcoin core may be malicious")
          val sigs = partiallySignedTx.txIn.filter(txIn => localOutpoints.contains(txIn.outPoint)).map(_.witness)
          PartiallySignedSharedTransaction(unsignedTx, TxSignatures(fundingParams.channelId, partiallySignedTx, sigs, sharedSig_opt))
      }) {
        case Failure(t) => WalletFailure(t)
        case Success(signedTx) => SignTransactionResult(signedTx)
      }
    }
  }

  private def unlockAndStop(session: InteractiveTxSession): Behavior[Command] = {
    val localInputs = session.localInputs ++ session.toSend.collect {
      case addInput: Input.Local => addInput
      case addInput: Input.Shared => addInput
    }
    unlockAndStop(localInputs.map(_.outPoint).toSet)
  }

  private def unlockAndStop(tx: SharedTransaction): Behavior[Command] = {
    val localInputs = tx.localInputs.map(_.outPoint).toSet ++ tx.sharedInput_opt.map(_.outPoint).toSet
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

/**
 * Once a shared transaction has been created, peers exchange signatures for the commitment and the shared transaction.
 * We store the channel state once we reach that step. Once we've sent tx_signatures, we cannot forget the channel
 * until it has been spent or double-spent.
 */
sealed trait InteractiveTxSigningSession

object InteractiveTxSigningSession {

  import InteractiveTxBuilder._

  //                      Example flow:
  //     +-------+                             +-------+
  //     |       |-------- commit_sig -------->|       |
  //     |   A   |<------- commit_sig ---------|   B   |
  //     |       |-------- tx_signatures ----->|       |
  //     |       |<------- tx_signatures ------|       |
  //     +-------+                             +-------+

  /** A local commitment for which we haven't received our peer's signatures. */
  case class UnsignedLocalCommit(index: Long, spec: CommitmentSpec, commitTx: CommitTx, htlcTxs: List[HtlcTx])

  private def shouldSignFirst(isInitiator: Boolean, channelParams: ChannelParams, tx: SharedTransaction): Boolean = {
    val sharedAmountIn = tx.sharedInput_opt.map(_.txOut.amount).getOrElse(0 sat)
    val (localAmountIn, remoteAmountIn) = if (isInitiator) {
      (sharedAmountIn + tx.localInputs.map(i => i.txOut.amount).sum, tx.remoteInputs.map(i => i.txOut.amount).sum)
    } else {
      (tx.localInputs.map(i => i.txOut.amount).sum, sharedAmountIn + tx.remoteInputs.map(i => i.txOut.amount).sum)
    }
    if (localAmountIn == remoteAmountIn) {
      // When both peers contribute the same amount, the peer with the lowest pubkey must transmit its `tx_signatures` first.
      LexicographicalOrdering.isLessThan(channelParams.localNodeId.value, channelParams.remoteNodeId.value)
    } else {
      // Otherwise, the peer with the lowest total of input amount must transmit its `tx_signatures` first.
      localAmountIn < remoteAmountIn
    }
  }

  def addRemoteSigs(channelKeys: ChannelKeys, fundingParams: InteractiveTxParams, partiallySignedTx: PartiallySignedSharedTransaction, remoteSigs: TxSignatures)(implicit log: LoggingAdapter): Either[ChannelException, FullySignedSharedTransaction] = {
    if (partiallySignedTx.tx.localInputs.length != partiallySignedTx.localSigs.witnesses.length) {
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
    }
    if (partiallySignedTx.tx.remoteInputs.length != remoteSigs.witnesses.length) {
      log.info("invalid tx_signatures: witness count mismatch (expected={}, got={})", partiallySignedTx.tx.remoteInputs.length, remoteSigs.witnesses.length)
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
    }
    val sharedSigs_opt = fundingParams.sharedInput_opt match {
      case Some(sharedInput: Multisig2of2Input) =>
        (partiallySignedTx.localSigs.previousFundingTxSig_opt, remoteSigs.previousFundingTxSig_opt) match {
          case (Some(localSig), Some(remoteSig)) =>
            val localFundingPubkey = channelKeys.fundingKey(sharedInput.fundingTxIndex).publicKey
            Some(Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, sharedInput.remoteFundingPubkey))
          case _ =>
            log.info("invalid tx_signatures: missing shared input signatures")
            return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
        }
      case Some(_: Musig2Input) => return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId))) // TODO: not implemented
      case None => None
    }
    val txWithSigs = FullySignedSharedTransaction(partiallySignedTx.tx, partiallySignedTx.localSigs, remoteSigs, sharedSigs_opt)
    if (remoteSigs.txId != txWithSigs.signedTx.txid) {
      log.info("invalid tx_signatures: txId mismatch (expected={}, got={})", txWithSigs.signedTx.txid, remoteSigs.txId)
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
    }
    // We allow a 5% error margin since witness size prediction could be inaccurate.
    // If they didn't contribute to the transaction, they're not responsible, so we don't check the feerate.
    // If we didn't contribute to the transaction, we don't care if they use a lower feerate than expected.
    val localContributed = txWithSigs.tx.localInputs.nonEmpty || txWithSigs.tx.localOutputs.nonEmpty
    val remoteContributed = txWithSigs.tx.remoteInputs.nonEmpty || txWithSigs.tx.remoteOutputs.nonEmpty
    if (localContributed && remoteContributed && txWithSigs.feerate < fundingParams.targetFeerate * 0.95) {
      return Left(InvalidFundingFeerate(fundingParams.channelId, fundingParams.targetFeerate, txWithSigs.feerate))
    }
    val previousOutputs = {
      val sharedOutput = fundingParams.sharedInput_opt.map(sharedInput => sharedInput.info.outPoint -> sharedInput.info.txOut).toMap
      val localOutputs = txWithSigs.tx.localInputs.map(i => i.outPoint -> i.txOut).toMap
      val remoteOutputs = txWithSigs.tx.remoteInputs.map(i => i.outPoint -> i.txOut).toMap
      sharedOutput ++ localOutputs ++ remoteOutputs
    }
    Try(Transaction.correctlySpends(txWithSigs.signedTx, previousOutputs, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)) match {
      case Failure(f) =>
        log.info("invalid tx_signatures: {}", f.getMessage)
        Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.txId)))
      case Success(_) => Right(txWithSigs)
    }
  }

  /**
   * We haven't sent our tx_signatures: we store the channel state, but we can safely forget it if we discover that our
   * peer has forgotten that channel (which may happen if we disconnected before they received our tx_complete).
   */
  case class WaitingForSigs(fundingParams: InteractiveTxParams,
                            fundingTxIndex: Long,
                            fundingTx: PartiallySignedSharedTransaction,
                            localCommit: Either[UnsignedLocalCommit, LocalCommit],
                            remoteCommit: RemoteCommit,
                            liquidityPurchase_opt: Option[LiquidityAds.PurchaseBasicInfo]) extends InteractiveTxSigningSession {
    val commitInput: InputInfo = localCommit.fold(_.commitTx.input, _.commitTxAndRemoteSig.commitTx.input)
    val localCommitIndex: Long = localCommit.fold(_.index, _.index)
    // This value tells our peer whether we need them to retransmit their commit_sig on reconnection or not.
    val nextLocalCommitmentNumber: Long = localCommit match {
      case Left(unsignedCommit) => unsignedCommit.index
      case Right(commit) => commit.index + 1
    }

    def receiveCommitSig(channelParams: ChannelParams, channelKeys: ChannelKeys, remoteCommitSig: CommitSig, currentBlockHeight: BlockHeight)(implicit log: LoggingAdapter): Either[ChannelException, InteractiveTxSigningSession] = {
      localCommit match {
        case Left(unsignedLocalCommit) =>
          val fundingKey = channelKeys.fundingKey(fundingTxIndex)
          val commitKeys = LocalCommitmentKeys(channelParams, channelKeys, localCommitIndex)
          LocalCommit.fromCommitSig(channelParams, commitKeys, fundingTx.txId, fundingKey, fundingParams.remoteFundingPubKey, commitInput, remoteCommitSig, localCommitIndex, unsignedLocalCommit.spec).map { signedLocalCommit =>
            if (shouldSignFirst(fundingParams.isInitiator, channelParams, fundingTx.tx)) {
              val fundingStatus = LocalFundingStatus.DualFundedUnconfirmedFundingTx(fundingTx, currentBlockHeight, fundingParams, liquidityPurchase_opt)
              val commitment = Commitment(fundingTxIndex, remoteCommit.index, fundingParams.remoteFundingPubKey, fundingStatus, RemoteFundingStatus.NotLocked, signedLocalCommit, remoteCommit, None)
              SendingSigs(fundingStatus, commitment, fundingTx.localSigs)
            } else {
              this.copy(localCommit = Right(signedLocalCommit))
            }
          }
        case Right(_) =>
          log.info("ignoring duplicate commit_sig")
          Right(this)
      }
    }

    def receiveTxSigs(channelKeys: ChannelKeys, remoteTxSigs: TxSignatures, currentBlockHeight: BlockHeight)(implicit log: LoggingAdapter): Either[ChannelException, SendingSigs] = {
      localCommit match {
        case Left(_) =>
          log.info("received tx_signatures before commit_sig")
          Left(UnexpectedFundingSignatures(fundingParams.channelId))
        case Right(signedLocalCommit) =>
          addRemoteSigs(channelKeys, fundingParams, fundingTx, remoteTxSigs) match {
            case Left(f) =>
              log.info("received invalid tx_signatures")
              Left(f)
            case Right(fullySignedTx) =>
              log.info("interactive-tx fully signed with {} local inputs, {} remote inputs, {} local outputs and {} remote outputs", fullySignedTx.tx.localInputs.length, fullySignedTx.tx.remoteInputs.length, fullySignedTx.tx.localOutputs.length, fullySignedTx.tx.remoteOutputs.length)
              val fundingStatus = LocalFundingStatus.DualFundedUnconfirmedFundingTx(fullySignedTx, currentBlockHeight, fundingParams, liquidityPurchase_opt)
              val commitment = Commitment(fundingTxIndex, remoteCommit.index, fundingParams.remoteFundingPubKey, fundingStatus, RemoteFundingStatus.NotLocked, signedLocalCommit, remoteCommit, None)
              Right(SendingSigs(fundingStatus, commitment, fullySignedTx.localSigs))
          }
      }
    }
  }

  /** We send our tx_signatures: we cannot forget the channel until it has been spent or double-spent. */
  case class SendingSigs(fundingTx: LocalFundingStatus.DualFundedUnconfirmedFundingTx, commitment: Commitment, localSigs: TxSignatures) extends InteractiveTxSigningSession
}