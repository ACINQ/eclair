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

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
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
import fr.acinq.eclair.{Logs, MilliSatoshi, UInt64, randomKey}
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
  case class Start(replyTo: ActorRef[Response], previousTransactions: Seq[SignedSharedTransaction]) extends Command
  sealed trait ReceiveMessage extends Command
  case class ReceiveTxMessage(msg: InteractiveTxConstructionMessage) extends ReceiveMessage
  case class ReceiveCommitSig(msg: CommitSig) extends ReceiveMessage
  case class ReceiveTxSigs(msg: TxSignatures) extends ReceiveMessage
  case object Abort extends Command
  private case class FundTransactionResult(tx: Transaction) extends Command
  private case class InputDetails(usableInputs: Seq[TxAddInput], unusableInputs: Set[UnusableInput]) extends Command
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

  /** A wallet input that doesn't match interactive-tx construction requirements. */
  case class UnusableInput(outpoint: OutPoint)

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
    def tx: SharedTransaction
    def localSigs: TxSignatures
    def signedTx_opt: Option[Transaction]
  }
  case class PartiallySignedSharedTransaction(tx: SharedTransaction, localSigs: TxSignatures) extends SignedSharedTransaction {
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
    override val signedTx_opt: Option[Transaction] = Some(signedTx)
    val feerate: FeeratePerKw = Transactions.fee2rate(tx.fees, signedTx.weight())
  }
  // @formatter:on

  def apply(remoteNodeId: PublicKey,
            fundingParams: InteractiveTxParams,
            keyManager: ChannelKeyManager,
            localPushAmount: MilliSatoshi,
            remotePushAmount: MilliSatoshi,
            localParams: LocalParams,
            remoteParams: RemoteParams,
            commitTxFeerate: FeeratePerKw,
            remoteFirstPerCommitmentPoint: PublicKey,
            channelFlags: ChannelFlags,
            channelConfig: ChannelConfig,
            channelFeatures: ChannelFeatures,
            wallet: OnChainChannelFunder)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      // The stash is used to buffer messages that arrive while we're funding the transaction.
      // Since the interactive-tx protocol is turn-based, we should not have more than one stashed lightning message.
      // We may also receive commands from our parent, but we shouldn't receive many, so we can keep the stash size small.
      Behaviors.withStash(10) { stash =>
        Behaviors.withMdc(Logs.mdc(remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(fundingParams.channelId))) {
          Behaviors.receiveMessagePartial {
            case Start(replyTo, previousTransactions) =>
              val actor = new InteractiveTxBuilder(replyTo, fundingParams, keyManager, localPushAmount, remotePushAmount, localParams, remoteParams, commitTxFeerate, remoteFirstPerCommitmentPoint, channelFlags, channelConfig, channelFeatures, wallet, previousTransactions, stash, context)
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
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.tx.buildUnsignedTx().txid)))
    }
    if (partiallySignedTx.tx.remoteInputs.length != remoteSigs.witnesses.length) {
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.tx.buildUnsignedTx().txid)))
    }
    val txWithSigs = FullySignedSharedTransaction(partiallySignedTx.tx, partiallySignedTx.localSigs, remoteSigs)
    if (remoteSigs.txId != txWithSigs.signedTx.txid) {
      return Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.tx.buildUnsignedTx().txid)))
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
      case Failure(_) => Left(InvalidFundingSignature(fundingParams.channelId, Some(partiallySignedTx.tx.buildUnsignedTx().txid)))
      case Success(_) => Right(txWithSigs)
    }
  }

}

/**
 * @param previousTransactions interactive transactions are replaceable and can be RBF-ed, but we need to make sure that
 *                             only one of them ends up confirming. We guarantee this by having the latest transaction
 *                             always double-spend all its predecessors.
 */
private class InteractiveTxBuilder(replyTo: ActorRef[InteractiveTxBuilder.Response],
                                   fundingParams: InteractiveTxBuilder.InteractiveTxParams,
                                   keyManager: ChannelKeyManager,
                                   localPushAmount: MilliSatoshi,
                                   remotePushAmount: MilliSatoshi,
                                   localParams: LocalParams,
                                   remoteParams: RemoteParams,
                                   commitTxFeerate: FeeratePerKw,
                                   remoteFirstPerCommitmentPoint: PublicKey,
                                   channelFlags: ChannelFlags,
                                   channelConfig: ChannelConfig,
                                   channelFeatures: ChannelFeatures,
                                   wallet: OnChainChannelFunder,
                                   previousTransactions: Seq[InteractiveTxBuilder.SignedSharedTransaction],
                                   stash: StashBuffer[InteractiveTxBuilder.Command],
                                   context: ActorContext[InteractiveTxBuilder.Command])(implicit ec: ExecutionContext) {

  import InteractiveTxBuilder._

  private val log = context.log

  private def start(): Behavior[Command] = {
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
      buildTx(FundingContributions(Nil, Nil))
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
  def fund(txNotFunded: Transaction, currentInputs: Seq[TxAddInput], unusableInputs: Set[UnusableInput]): Behavior[Command] = {
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
          unlockAndStop(currentInputs.map(toOutPoint).toSet ++ fundedTx.txIn.map(_.outPoint) ++ unusableInputs.map(_.outpoint))
        } else {
          filterInputs(fundedTx, currentInputs, unusableInputs)
        }
      case WalletFailure(t) =>
        if (previousTransactions.nonEmpty && !fundingParams.isInitiator) {
          // We don't have enough funds to reach the desired feerate, but this is an RBF attempt that we did not initiate.
          // It still makes sense for us to contribute whatever we're able to (by using our previous set of inputs and
          // outputs): the final feerate will be less than what the initiator intended, but it's still better than being
          // stuck with a low feerate transaction that won't confirm.
          log.warn("could not fund interactive tx at {}, re-using previous inputs and outputs", fundingParams.targetFeerate)
          val previousTx = previousTransactions.head.tx
          stash.unstashAll(buildTx(FundingContributions(previousTx.localInputs, previousTx.localOutputs)))
        } else {
          log.error("could not fund interactive tx: ", t)
          // We use a generic exception and don't send the internal error to the peer.
          replyTo ! LocalFailure(ChannelFundingError(fundingParams.channelId))
          unlockAndStop(currentInputs.map(toOutPoint).toSet ++ unusableInputs.map(_.outpoint))
        }
      case msg: ReceiveMessage =>
        stash.stash(msg)
        Behaviors.same
      case Abort =>
        stash.stash(Abort)
        Behaviors.same
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
          replyTo ! LocalFailure(ChannelFundingError(fundingParams.channelId))
          unlockAndStop(fundedTx.txIn.map(_.outPoint).toSet ++ unusableInputs.map(_.outpoint))
        } else if (otherOutputs.length > 1) {
          log.error("funded transaction contains unexpected outputs: {}", fundedTx)
          replyTo ! LocalFailure(ChannelFundingError(fundingParams.channelId))
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
          // We unlock the unusable inputs from previous iterations (if any) as they can be used outside of this session.
          unlock(unusableInputs.map(_.outpoint))
          // The initiator's serial IDs must use even values and the non-initiator odd values.
          val serialIdParity = if (fundingParams.isInitiator) 0 else 1
          val txAddInputs = inputDetails.usableInputs.zipWithIndex.map { case (input, i) => input.copy(serialId = UInt64(2 * i + serialIdParity)) }
          val txAddOutputs = outputs.zipWithIndex.map { case (output, i) => output.copy(serialId = UInt64(2 * (i + txAddInputs.length) + serialIdParity)) }
          stash.unstashAll(buildTx(FundingContributions(txAddInputs, txAddOutputs)))
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
        // We use a generic exception and don't send the internal error to the peer.
        replyTo ! LocalFailure(ChannelFundingError(fundingParams.channelId))
        unlockAndStop(fundedTx.txIn.map(_.outPoint).toSet ++ unusableInputs.map(_.outpoint))
      case msg: ReceiveMessage =>
        stash.stash(msg)
        Behaviors.same
      case Abort =>
        stash.stash(Abort)
        Behaviors.same
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
        val inputTxDetails = for {
          tx <- wallet.getTransaction(txIn.outPoint.txid)
          confirmations <- if (fundingParams.requireConfirmedInputs.forLocal) wallet.getTxConfirmations(txIn.outPoint.txid) else Future.successful(None)
        } yield (tx, confirmations.getOrElse(0))
        inputTxDetails.map { case (previousTx, confirmations) =>
          if (Transaction.write(previousTx).length > 65000) {
            // Wallet input transaction is too big to fit inside tx_add_input.
            Left(UnusableInput(txIn.outPoint))
          } else if (!Script.isNativeWitnessScript(previousTx.txOut(txIn.outPoint.index.toInt).publicKeyScript)) {
            // Wallet input must be a native segwit input.
            Left(UnusableInput(txIn.outPoint))
          } else if (fundingParams.requireConfirmedInputs.forLocal && confirmations < 1) {
            // Wallet input must be confirmed.
            Left(UnusableInput(txIn.outPoint))
          } else {
            Right(TxAddInput(fundingParams.channelId, UInt64(0), previousTx, txIn.outPoint.index, txIn.sequence))
          }
        }
    }
  }

  private def buildTx(localContributions: FundingContributions): Behavior[Command] = {
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
    Funding.makeFirstCommitTxs(keyManager, channelConfig, channelFeatures, fundingParams.channelId, localParams, remoteParams, fundingParams.localAmount, fundingParams.remoteAmount, localPushAmount, remotePushAmount, commitTxFeerate, fundingTx.hash, fundingOutputIndex, remoteFirstPerCommitmentPoint) match {
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
                replyTo ! RemoteFailure(InvalidCommitmentSignature(fundingParams.channelId, signedLocalCommitTx.tx.txid))
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
            replyTo ! Succeeded(fundingParams, fullySignedTx, commitments)
            Behaviors.stopped
        }
      case SignTransactionResult(signedTx, None) =>
        // We return as soon as we sign the tx, because we need to be able to handle the case where remote publishes the
        // tx right away without properly sending us their signatures.
        if (completeTx.remoteAmountIn == 0.sat) {
          log.info("interactive-tx fully signed with {} local inputs, {} remote inputs, {} local outputs and {} remote outputs", signedTx.tx.localInputs.length, signedTx.tx.remoteInputs.length, signedTx.tx.localOutputs.length, signedTx.tx.remoteOutputs.length)
          val remoteSigs = TxSignatures(signedTx.localSigs.channelId, signedTx.localSigs.txHash, Nil)
          replyTo ! Succeeded(fundingParams, FullySignedSharedTransaction(signedTx.tx, signedTx.localSigs, remoteSigs), commitments)
        } else {
          log.info("interactive-tx partially signed with {} local inputs, {} remote inputs, {} local outputs and {} remote outputs", signedTx.tx.localInputs.length, signedTx.tx.remoteInputs.length, signedTx.tx.localOutputs.length, signedTx.tx.remoteOutputs.length)
          replyTo ! Succeeded(fundingParams, signedTx, commitments)
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