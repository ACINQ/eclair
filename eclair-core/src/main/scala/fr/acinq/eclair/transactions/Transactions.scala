/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.SigHash._
import fr.acinq.bitcoin.SigVersion._
import fr.acinq.bitcoin.crypto.musig2.{IndividualNonce, SecretNonce}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey, XonlyPublicKey}
import fr.acinq.bitcoin.scalacompat.KotlinUtils._
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.bitcoin.{ScriptFlags, ScriptTree}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.ChannelSpendSignature
import fr.acinq.eclair.channel.ChannelSpendSignature._
import fr.acinq.eclair.crypto.NonceGenerator
import fr.acinq.eclair.crypto.keymanager.{CommitmentPublicKeys, LocalCommitmentKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.transactions.CommitmentOutput._
import fr.acinq.eclair.transactions.Scripts.Taproot.NUMS_POINT
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc
import scodec.bits.ByteVector

import java.nio.ByteOrder
import scala.util.Try

/**
 * Created by PM on 15/12/2016.
 */
object Transactions {

  val MAX_STANDARD_TX_WEIGHT = 400_000

  /** Weight of a fully signed p2wpkh input (using a 73 bytes signature). */
  val p2wpkhInputWeight = 273
  /** Weight of a fully signed p2tr wallet input. */
  val p2trInputWeight = 230
  /** Weight of an additional p2wpkh output added to a transaction. */
  val p2wpkhOutputWeight = 124
  /** Weight of an additional p2tr wallet output added to a transaction. */
  val p2trOutputWeight = 172

  val maxWalletInputWeight: Int = p2wpkhInputWeight.max(p2trInputWeight)
  val maxWalletOutputWeight: Int = p2wpkhOutputWeight.max(p2trOutputWeight)

  sealed trait CommitmentFormat {
    // @formatter:off
    /** Weight of a fully signed channel output, when spent by a [[ChannelSpendTransaction]]. */
    def fundingInputWeight: Int
    /** Weight of a fully signed [[CommitTx]] transaction without any HTLCs. */
    def commitWeight: Int
    /** Weight of a fully signed [[ClaimLocalAnchorTx]] or [[ClaimRemoteAnchorTx]] input. */
    def anchorInputWeight: Int
    /** Weight of an additional HTLC output added to a [[CommitTx]]. */
    def htlcOutputWeight: Int
    /** Weight of the fully signed [[HtlcTimeoutTx]] input. */
    def htlcTimeoutInputWeight: Int
    /** Weight of a fully signed [[HtlcTimeoutTx]] transaction without additional wallet inputs. */
    def htlcTimeoutWeight: Int
    /** Weight of the fully signed [[HtlcSuccessTx]] input. */
    def htlcSuccessInputWeight: Int
    /** Weight of a fully signed [[HtlcSuccessTx]] transaction without additional wallet inputs. */
    def htlcSuccessWeight: Int
    /** Weight of a fully signed [[ClaimHtlcSuccessTx]] transaction. */
    def claimHtlcSuccessWeight: Int
    /** Weight of a fully signed [[ClaimHtlcTimeoutTx]] transaction. */
    def claimHtlcTimeoutWeight: Int
    /** Weight of a fully signed [[ClaimLocalDelayedOutputTx]] transaction. */
    def toLocalDelayedWeight: Int
    /** Weight of a fully signed [[ClaimRemoteCommitMainOutputTx]] transaction. */
    def toRemoteWeight: Int
    /** Weight of a fully signed [[HtlcDelayedTx]] 3rd-stage transaction (spending the output of an [[HtlcTx]]). */
    def htlcDelayedWeight: Int
    /** Weight of a fully signed [[MainPenaltyTx]] transaction. */
    def mainPenaltyWeight: Int
    /** Weight of a fully signed [[HtlcPenaltyTx]] transaction for an offered HTLC. */
    def htlcOfferedPenaltyWeight: Int
    /** Weight of a fully signed [[HtlcPenaltyTx]] transaction for a received HTLC. */
    def htlcReceivedPenaltyWeight: Int
    /** Weight of a fully signed [[ClaimHtlcDelayedOutputPenaltyTx]] transaction. */
    def claimHtlcPenaltyWeight: Int
    // @formatter:on
  }

  sealed trait SegwitV0CommitmentFormat extends CommitmentFormat {
    override val fundingInputWeight = 384
  }

  /**
   * Commitment format as defined in the v1.0 specification (https://github.com/lightningnetwork/lightning-rfc/tree/v1.0).
   */
  case object DefaultCommitmentFormat extends SegwitV0CommitmentFormat {
    override val commitWeight = 724
    override val anchorInputWeight = 0
    override val htlcOutputWeight = 172
    override val htlcTimeoutInputWeight = 449
    override val htlcTimeoutWeight = 663
    override val htlcSuccessInputWeight = 488
    override val htlcSuccessWeight = 703
    override val claimHtlcSuccessWeight = 571
    override val claimHtlcTimeoutWeight = 544
    override val toLocalDelayedWeight = 483
    override val toRemoteWeight = 438
    override val htlcDelayedWeight = 483
    override val mainPenaltyWeight = 484
    override val htlcOfferedPenaltyWeight = 572
    override val htlcReceivedPenaltyWeight = 577
    override val claimHtlcPenaltyWeight = 484

    override def toString: String = "legacy"
  }

  /**
   * Commitment format that adds anchor outputs to the commitment transaction and uses custom sighash flags for HTLC
   * transactions to allow unilateral fee bumping (https://github.com/lightningnetwork/lightning-rfc/pull/688).
   */
  sealed trait AnchorOutputsCommitmentFormat extends SegwitV0CommitmentFormat {
    override val commitWeight = 1124
    override val anchorInputWeight = 279
    override val htlcOutputWeight = 172
    override val htlcTimeoutInputWeight = 452
    override val htlcTimeoutWeight = 666
    override val htlcSuccessInputWeight = 491
    override val htlcSuccessWeight = 706
    override val claimHtlcSuccessWeight = 574
    override val claimHtlcTimeoutWeight = 547
    override val toLocalDelayedWeight = 483
    override val toRemoteWeight = 442
    override val htlcDelayedWeight = 483
    override val mainPenaltyWeight = 483
    override val htlcOfferedPenaltyWeight = 575
    override val htlcReceivedPenaltyWeight = 580
    override val claimHtlcPenaltyWeight = 483
  }

  object AnchorOutputsCommitmentFormat {
    val anchorAmount: Satoshi = Satoshi(330)
  }

  /**
   * This commitment format may be unsafe where you're fundee, as it exposes you to a fee inflating attack.
   * Don't use this commitment format unless you know what you're doing!
   * See https://lists.linuxfoundation.org/pipermail/lightning-dev/2020-September/002796.html for details.
   */
  case object UnsafeLegacyAnchorOutputsCommitmentFormat extends AnchorOutputsCommitmentFormat {
    override def toString: String = "unsafe_anchor_outputs"
  }

  /**
   * This commitment format removes the fees from the pre-signed 2nd-stage htlc transactions to fix the fee inflating
   * attack against [[UnsafeLegacyAnchorOutputsCommitmentFormat]].
   */
  case object ZeroFeeHtlcTxAnchorOutputsCommitmentFormat extends AnchorOutputsCommitmentFormat {
    override def toString: String = "anchor_outputs"
  }

  sealed trait TaprootCommitmentFormat extends CommitmentFormat

  sealed trait SimpleTaprootChannelCommitmentFormat extends TaprootCommitmentFormat {
    // weights for taproot transactions are deterministic since signatures are encoded as 64 bytes and
    // not in variable length DER format (around 72 bytes)
    override val fundingInputWeight = 230
    override val commitWeight = 960
    override val anchorInputWeight = 230
    override val htlcOutputWeight = 172
    override val htlcTimeoutWeight = 645
    override val htlcSuccessWeight = 705
    override val htlcTimeoutInputWeight = 431
    override val htlcSuccessInputWeight = 491
    override val claimHtlcSuccessWeight = 559
    override val claimHtlcTimeoutWeight = 504
    override val toLocalDelayedWeight = 501
    override val toRemoteWeight = 467
    override val htlcDelayedWeight = 469
    override val mainPenaltyWeight = 531
    override val htlcOfferedPenaltyWeight = 396
    override val htlcReceivedPenaltyWeight = 396
    override val claimHtlcPenaltyWeight = 396
  }

  /** For Phoenix users we sign HTLC transactions with the same feerate as the commit tx to allow broadcasting without wallet inputs. */
  case object PhoenixSimpleTaprootChannelCommitmentFormat extends SimpleTaprootChannelCommitmentFormat {
    override def toString: String = "simple_taproot_phoenix"
  }

  case object ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat extends SimpleTaprootChannelCommitmentFormat {
    override def toString: String = "simple_taproot"
  }

  case class InputInfo(outPoint: OutPoint, txOut: TxOut)

  // @formatter:off
  /** This trait contains redeem information necessary to spend different types of segwit inputs. */
  sealed trait RedeemInfo {
    def pubkeyScript: ByteVector
  }
  object RedeemInfo {
    sealed trait SegwitV0 extends RedeemInfo { def redeemScript: ByteVector }
    /** @param publicKey the public key for this p2wpkh input. */
    case class P2wpkh(publicKey: PublicKey) extends SegwitV0 {
      override val redeemScript: ByteVector = Script.write(Script.pay2pkh(publicKey))
      override val pubkeyScript: ByteVector = Script.write(Script.pay2wpkh(publicKey))
    }
    /** @param redeemScript the actual script must be known to redeem pay2wsh inputs. */
    case class P2wsh(redeemScript: ByteVector) extends SegwitV0 {
      override val pubkeyScript: ByteVector = Script.write(Script.pay2wsh(redeemScript))
    }
    object P2wsh {
      def apply(script: Seq[ScriptElt]): P2wsh = P2wsh(Script.write(script))
    }

    sealed trait Taproot extends RedeemInfo
    /**
     * @param internalKey    the private key associated with this public key will be used to sign.
     * @param scriptTree_opt the script tree must be known if there is one, even when spending via the key path.
     */
    case class TaprootKeyPath(internalKey: XonlyPublicKey, scriptTree_opt: Option[ScriptTree]) extends Taproot {
      override val pubkeyScript: ByteVector = Script.write(Script.pay2tr(internalKey, scriptTree_opt))
    }
    /**
     * @param internalKey we need the internal key, even if we don't have the private key, to spend via a script path.
     * @param scriptTree  we need the complete script tree to spend taproot inputs.
     * @param leafHash    hash of the leaf script we're spending (must belong to the tree).
     */
    case class TaprootScriptPath(internalKey: XonlyPublicKey, scriptTree: ScriptTree, leafHash: ByteVector32) extends Taproot {
      val leaf: ScriptTree.Leaf = Option(scriptTree.findScript(leafHash)).getOrElse(throw new IllegalArgumentException("script tree must contain the provided leaf"))
      val redeemScript: ByteVector = leaf.getScript
      override val pubkeyScript: ByteVector = Script.write(Script.pay2tr(internalKey, Some(scriptTree)))
    }
  }
  // @formatter:on

  case class LocalNonce(secretNonce: SecretNonce, publicNonce: IndividualNonce)

  sealed trait TransactionWithInputInfo {
    // @formatter:off
    def input: InputInfo
    def desc: String
    def tx: Transaction
    def amountIn: Satoshi = input.txOut.amount
    def fee: Satoshi = amountIn - tx.txOut.map(_.amount).sum
    def inputIndex: Int = tx.txIn.indexWhere(_.outPoint == input.outPoint)
    // @formatter:on

    protected def buildSpentOutputs(extraUtxos: Map[OutPoint, TxOut]): Seq[TxOut] = {
      // Callers don't except this function to throw.
      // But we want to ensure that we're correctly providing input details, otherwise our signature will silently be
      // invalid when using taproot. We verify this in all cases, even when using segwit v0, to ensure that we have as
      // many tests as possible that exercise this codepath.
      val inputsMap = extraUtxos + (input.outPoint -> input.txOut)
      tx.txIn.foreach(txIn => require(inputsMap.contains(txIn.outPoint), s"cannot sign $desc with txId=${tx.txid}: missing input details for ${txIn.outPoint}"))
      tx.txIn.map(txIn => inputsMap(txIn.outPoint))
    }

    protected def sign(key: PrivateKey, sighash: Int, redeemInfo: RedeemInfo, extraUtxos: Map[OutPoint, TxOut]): ByteVector64 = {
      val spentOutputs = buildSpentOutputs(extraUtxos)
      // NB: the tx may have multiple inputs, we will only sign the one provided in our input. Bear in mind that the
      // signature will be invalidated if other inputs are added *afterwards* and sighash was SIGHASH_ALL.
      redeemInfo match {
        case redeemInfo: RedeemInfo.SegwitV0 =>
          val sigDER = Transaction.signInput(tx, inputIndex, redeemInfo.redeemScript, sighash, input.txOut.amount, SIGVERSION_WITNESS_V0, key)
          Crypto.der2compact(sigDER)
        case t: RedeemInfo.TaprootKeyPath =>
          Transaction.signInputTaprootKeyPath(key, tx, inputIndex, spentOutputs, sighash, t.scriptTree_opt)
        case s: RedeemInfo.TaprootScriptPath =>
          Transaction.signInputTaprootScriptPath(key, tx, inputIndex, spentOutputs, sighash, s.leafHash)
      }
    }

    protected def checkSig(sig: ByteVector64, publicKey: PublicKey, sighash: Int, redeemInfo: RedeemInfo): Boolean = {
      if (inputIndex >= 0) {
        redeemInfo match {
          case redeemInfo: RedeemInfo.SegwitV0 =>
            val data = Transaction.hashForSigning(tx, inputIndex, redeemInfo.redeemScript, sighash, input.txOut.amount, SIGVERSION_WITNESS_V0)
            Crypto.verifySignature(data, sig, publicKey)
          case _: RedeemInfo.TaprootKeyPath =>
            val data = Transaction.hashForSigningTaprootKeyPath(tx, inputIndex, Seq(input.txOut), sighash)
            Crypto.verifySignatureSchnorr(data, sig, publicKey.xOnly)
          case s: RedeemInfo.TaprootScriptPath =>
            val data = Transaction.hashForSigningTaprootScriptPath(tx, inputIndex, Seq(input.txOut), sighash, s.leafHash)
            Crypto.verifySignatureSchnorr(data, sig, publicKey.xOnly)
        }
      } else {
        false
      }
    }

    /** Check that this transaction is correctly signed. */
    def validate(extraUtxos: Map[OutPoint, TxOut]): Boolean = {
      val inputsMap = extraUtxos + (input.outPoint -> input.txOut)
      val allInputsProvided = tx.txIn.forall(txIn => inputsMap.contains(txIn.outPoint))
      val witnessesOk = Try(Transaction.correctlySpends(tx, inputsMap, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)).isSuccess
      allInputsProvided && witnessesOk
    }
  }

  /**
   * Transactions spending the channel funding output: [[CommitTx]], [[SpliceTx]] and [[ClosingTx]].
   * Those transactions always require two signatures, one from each channel participant.
   */
  sealed trait ChannelSpendTransaction extends TransactionWithInputInfo {
    /** Sign the channel's 2-of-2 funding output when using a [[SegwitV0CommitmentFormat]]. */
    def sign(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey, extraUtxos: Map[OutPoint, TxOut]): ChannelSpendSignature.IndividualSignature = {
      val redeemScript = Script.write(Scripts.multiSig2of2(localFundingKey.publicKey, remoteFundingPubkey))
      val sig = sign(localFundingKey, SIGHASH_ALL, RedeemInfo.P2wsh(redeemScript), extraUtxos)
      ChannelSpendSignature.IndividualSignature(sig)
    }

    /** Create a partial transaction for the channel's musig2 funding output when using a [[TaprootCommitmentFormat]]. */
    def partialSign(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey, extraUtxos: Map[OutPoint, TxOut], localNonce: LocalNonce, publicNonces: Seq[IndividualNonce]): Either[Throwable, ChannelSpendSignature.PartialSignatureWithNonce] = {
      val spentOutputs = buildSpentOutputs(extraUtxos)
      for {
        partialSig <- Musig2.signTaprootInput(localFundingKey, tx, inputIndex, spentOutputs, Scripts.sort(Seq(localFundingKey.publicKey, remoteFundingPubkey)), localNonce.secretNonce, publicNonces, None)
      } yield ChannelSpendSignature.PartialSignatureWithNonce(partialSig, localNonce.publicNonce)
    }

    /** Verify a signature received from the remote channel participant. */
    def checkRemoteSig(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, remoteSig: ChannelSpendSignature.IndividualSignature): Boolean = {
      val redeemScript = Script.write(Scripts.multiSig2of2(localFundingPubkey, remoteFundingPubkey))
      checkSig(remoteSig.sig, remoteFundingPubkey, SIGHASH_ALL, RedeemInfo.P2wsh(redeemScript))
    }

    def checkRemotePartialSignature(localFundingPubKey: PublicKey, remoteFundingPubKey: PublicKey, remoteSig: PartialSignatureWithNonce, localNonce: IndividualNonce): Boolean = {
      Musig2.verifyTaprootSignature(remoteSig.partialSig, remoteSig.nonce, remoteFundingPubKey, tx, inputIndex, Seq(input.txOut), Scripts.sort(Seq(localFundingPubKey, remoteFundingPubKey)), Seq(localNonce, remoteSig.nonce), scriptTree_opt = None)
    }

    /** Aggregate local and remote channel spending signatures for a [[SegwitV0CommitmentFormat]]. */
    def aggregateSigs(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: IndividualSignature, remoteSig: IndividualSignature): Transaction = {
      val witness = Scripts.witness2of2(localSig.sig, remoteSig.sig, localFundingPubkey, remoteFundingPubkey)
      tx.updateWitness(inputIndex, witness)
    }

    /** Aggregate local and remote channel spending partial signatures for a [[TaprootCommitmentFormat]]. */
    def aggregateSigs(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: PartialSignatureWithNonce, remoteSig: PartialSignatureWithNonce, extraUtxos: Map[OutPoint, TxOut]): Either[Throwable, Transaction] = {
      val spentOutputs = buildSpentOutputs(extraUtxos)
      for {
        aggregatedSignature <- Musig2.aggregateTaprootSignatures(Seq(localSig.partialSig, remoteSig.partialSig), tx, inputIndex, spentOutputs, sort(Seq(localFundingPubkey, remoteFundingPubkey)), Seq(localSig.nonce, remoteSig.nonce), None)
        witness = Script.witnessKeyPathPay2tr(aggregatedSignature)
      } yield tx.updateWitness(inputIndex, witness)
    }
  }

  /** This transaction collaboratively spends the channel funding output to change its capacity. */
  case class SpliceTx(input: InputInfo, tx: Transaction) extends ChannelSpendTransaction {
    override val desc: String = "splice-tx"
  }

  /** This transaction unilaterally spends the channel funding output (force-close). */
  case class CommitTx(input: InputInfo, tx: Transaction) extends ChannelSpendTransaction {
    override val desc: String = "commit-tx"

    def sign(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey): ChannelSpendSignature.IndividualSignature = sign(localFundingKey, remoteFundingPubkey, extraUtxos = Map.empty)

    def partialSign(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey, localNonce: LocalNonce, publicNonces: Seq[IndividualNonce]): Either[Throwable, ChannelSpendSignature.PartialSignatureWithNonce] = partialSign(localFundingKey, remoteFundingPubkey, extraUtxos = Map.empty, localNonce, publicNonces)

    def aggregateSigs(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: PartialSignatureWithNonce, remoteSig: PartialSignatureWithNonce): Either[Throwable, Transaction] = aggregateSigs(localFundingPubkey, remoteFundingPubkey, localSig, remoteSig, extraUtxos = Map.empty)
  }

  /** This transaction collaboratively spends the channel funding output (mutual-close). */
  case class ClosingTx(input: InputInfo, tx: Transaction, toLocalOutputIndex_opt: Option[Long]) extends ChannelSpendTransaction {
    override val desc: String = "closing-tx"
    val toLocalOutput_opt: Option[TxOut] = toLocalOutputIndex_opt.map(i => tx.txOut(i.toInt))

    def sign(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey): ChannelSpendSignature.IndividualSignature = sign(localFundingKey, remoteFundingPubkey, extraUtxos = Map.empty)

    def partialSign(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey, localNonce: LocalNonce, publicNonces: Seq[IndividualNonce]): Either[Throwable, ChannelSpendSignature.PartialSignatureWithNonce] = partialSign(localFundingKey, remoteFundingPubkey, extraUtxos = Map.empty, localNonce, publicNonces)

    def aggregateSigs(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: PartialSignatureWithNonce, remoteSig: PartialSignatureWithNonce): Either[Throwable, Transaction] = aggregateSigs(localFundingPubkey, remoteFundingPubkey, localSig, remoteSig, extraUtxos = Map.empty)
  }

  object ClosingTx {
    def createUnsignedTx(input: InputInfo, localScriptPubKey: ByteVector, remoteScriptPubKey: ByteVector, localPaysClosingFees: Boolean, dustLimit: Satoshi, closingFee: Satoshi, spec: CommitmentSpec): ClosingTx = {
      require(spec.htlcs.isEmpty, "there shouldn't be any pending htlcs")

      val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (localPaysClosingFees) {
        (spec.toLocal.truncateToSatoshi - closingFee, spec.toRemote.truncateToSatoshi)
      } else {
        (spec.toLocal.truncateToSatoshi, spec.toRemote.truncateToSatoshi - closingFee)
      } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

      val toLocalOutput_opt = if (toLocalAmount >= dustLimit) Some(TxOut(toLocalAmount, localScriptPubKey)) else None
      val toRemoteOutput_opt = if (toRemoteAmount >= dustLimit) Some(TxOut(toRemoteAmount, remoteScriptPubKey)) else None

      val tx = LexicographicalOrdering.sort(Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, sequence = 0xffffffffL) :: Nil,
        txOut = toLocalOutput_opt.toSeq ++ toRemoteOutput_opt.toSeq ++ Nil,
        lockTime = 0
      ))
      val toLocalOutput = findPubKeyScriptIndex(tx, localScriptPubKey).map(_.toLong).toOption
      ClosingTx(input, tx, toLocalOutput)
    }
  }

  /**
   * @param txIn        wallet input.
   * @param spentOutput utxo spent by this wallet input.
   */
  case class WalletInput(txIn: TxIn, spentOutput: TxOut) {
    val amount: Satoshi = spentOutput.amount
  }

  /**
   * Whenever possible, [[ForceCloseTransaction]]s pay on-chain fees by lowering their output amount.
   * When this isn't possible, we add wallet inputs to allow paying on-chain fees.
   *
   * @param inputs           inputs added by our bitcoin wallet.
   * @param changeOutput_opt change output added by our bitcoin wallet, if any.
   */
  case class WalletInputs(inputs: Seq[WalletInput], changeOutput_opt: Option[TxOut]) {
    val amountIn: Satoshi = inputs.map(_.amount).sum
    val fee: Satoshi = amountIn - changeOutput_opt.map(_.amount).getOrElse(0 sat)
    val txIn: Seq[TxIn] = inputs.map(_.txIn)
    val txOut: Seq[TxOut] = changeOutput_opt.toSeq
    val spentUtxos: Map[OutPoint, TxOut] = inputs.map(i => i.txIn.outPoint -> i.spentOutput).toMap

    /** Set the change output. */
    def setChangeOutput(amount: Satoshi, changeScript: ByteVector): WalletInputs = {
      val changeOutput = TxOut(amount, changeScript)
      this.copy(changeOutput_opt = Some(changeOutput))
    }

    /** Set the change output amount. */
    def setChangeAmount(amount: Satoshi): WalletInputs = {
      this.copy(changeOutput_opt = changeOutput_opt.map(_.copy(amount = amount)))
    }
  }

  /** Transactions spending a [[CommitTx]] or one of its descendants. */
  sealed trait ForceCloseTransaction extends TransactionWithInputInfo {
    // @formatter:off
    def commitmentFormat: CommitmentFormat
    def expectedWeight: Int
    // @formatter:on

    def sign(): Transaction

    /** Sighash flags to use when signing the transaction. */
    def sighash: Int = commitmentFormat match {
      case _: SegwitV0CommitmentFormat => SIGHASH_ALL
      case _: SimpleTaprootChannelCommitmentFormat => SIGHASH_DEFAULT
    }
  }

  /** Some force-close transactions require wallet inputs to pay on-chain fees. */
  sealed trait HasWalletInputs extends ForceCloseTransaction {
    /** Create redeem information for this transaction, based on the commitment format used. */
    def redeemInfo: RedeemInfo

    /** Sign the transaction combined with the wallet inputs provided. */
    def sign(walletInputs: WalletInputs): Transaction

    override def sign(): Transaction = sign(WalletInputs(Nil, None))

    protected def setWalletInputs(walletInputs: WalletInputs): Transaction = {
      // Note that we always keep the channel input in first position for simplicity.
      val txIn = tx.txIn.take(1) ++ walletInputs.txIn
      val txOut = tx.txOut.headOption.toSeq ++ walletInputs.changeOutput_opt.toSeq
      tx.copy(txIn = txIn, txOut = txOut)
    }
  }

  /**
   * Transactions spending a local [[CommitTx]] or one of its descendants:
   *    - [[ClaimLocalDelayedOutputTx]] spends the to-local output of [[CommitTx]] after a delay
   *    - When using anchor outputs, [[ClaimLocalAnchorTx]] spends the to-local anchor of [[CommitTx]]
   *    - [[HtlcSuccessTx]] spends received htlc outputs of [[CommitTx]] for which we have the preimage
   *      - [[HtlcDelayedTx]] spends [[HtlcSuccessTx]] after a delay
   *    - [[HtlcTimeoutTx]] spends sent htlc outputs of [[CommitTx]] after a timeout
   *      - [[HtlcDelayedTx]] spends [[HtlcTimeoutTx]] after a delay
   */
  sealed trait LocalCommitForceCloseTransaction extends ForceCloseTransaction {
    def commitKeys: LocalCommitmentKeys
  }

  /**
   * Transactions spending a remote [[CommitTx]] or one of its descendants.
   *
   * When a current remote [[CommitTx]] is published:
   *    - When using the default commitment format, [[ClaimP2WPKHOutputTx]] spends the to-local output of [[CommitTx]]
   *    - When using anchor outputs, [[ClaimRemoteDelayedOutputTx]] spends the to-local output of [[CommitTx]]
   *    - When using anchor outputs, [[ClaimRemoteAnchorTx]] spends the to-local anchor of [[CommitTx]]
   *    - [[ClaimHtlcSuccessTx]] spends received htlc outputs of [[CommitTx]] for which we have the preimage
   *    - [[ClaimHtlcTimeoutTx]] spends sent htlc outputs of [[CommitTx]] after a timeout
   *
   * When a revoked remote [[CommitTx]] is published:
   *    - When using the default commitment format, [[ClaimP2WPKHOutputTx]] spends the to-local output of [[CommitTx]]
   *    - When using anchor outputs, [[ClaimRemoteDelayedOutputTx]] spends the to-local output of [[CommitTx]]
   *    - [[MainPenaltyTx]] spends the remote main output using the revocation secret
   *    - [[HtlcPenaltyTx]] spends all htlc outputs using the revocation secret (and competes with [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] published by the remote node)
   *    - [[ClaimHtlcDelayedOutputPenaltyTx]] spends [[HtlcSuccessTx]] transactions published by the remote node using the revocation secret
   *    - [[ClaimHtlcDelayedOutputPenaltyTx]] spends [[HtlcTimeoutTx]] transactions published by the remote node using the revocation secret
   */
  sealed trait RemoteCommitForceCloseTransaction extends ForceCloseTransaction {
    def commitKeys: RemoteCommitmentKeys
  }

  // @formatter:off
  /** Owner of a given HTLC transaction (local/remote). */
  sealed trait TxOwner
  private object TxOwner {
    case object Local extends TxOwner
    case object Remote extends TxOwner
  }
  // @formatter:on

  /**
   * HTLC transactions require local and remote signatures and can be spent using two distinct script paths:
   *  - the success path by revealing the payment preimage
   *  - the timeout path after a predefined block height
   *
   * The success path must be used before the timeout is reached, otherwise there is a race where both channel
   * participants may claim the output.
   *
   * Once confirmed, HTLC transactions need to be spent by an [[HtlcDelayedTx]] after a relative delay to get the funds
   * back into our bitcoin wallet.
   */
  sealed trait HtlcTx extends TransactionWithInputInfo {
    // @formatter:off
    def htlcId: Long
    def paymentHash: ByteVector32
    def htlcExpiry: CltvExpiry
    def commitmentFormat: CommitmentFormat
    // @formatter:on

    def sighash(txOwner: TxOwner): Int = commitmentFormat match {
      case DefaultCommitmentFormat => SIGHASH_ALL
      case _: AnchorOutputsCommitmentFormat => txOwner match {
        case TxOwner.Local => SIGHASH_ALL
        case TxOwner.Remote => SIGHASH_SINGLE | SIGHASH_ANYONECANPAY
      }
      case _: SimpleTaprootChannelCommitmentFormat => txOwner match {
        case TxOwner.Local => SIGHASH_DEFAULT
        case TxOwner.Remote => SIGHASH_SINGLE | SIGHASH_ANYONECANPAY
      }
    }
  }

  /**
   * We first create unsigned HTLC transactions based on the [[CommitTx]]: this lets us produce our local signature,
   * which we need to send to our peer for their commitment.
   */
  sealed trait UnsignedHtlcTx extends HtlcTx {
    /** Create redeem information for this HTLC transaction, based on the commitment format used. */
    def redeemInfo(commitKeys: CommitmentPublicKeys): RedeemInfo

    /** Sign an HTLC transaction for the remote commitment. */
    def localSig(commitKeys: RemoteCommitmentKeys): ByteVector64 = {
      sign(commitKeys.ourHtlcKey, sighash(TxOwner.Remote), redeemInfo(commitKeys.publicKeys), extraUtxos = Map.empty)
    }

    /** This is a function only used in tests to produce signatures with a different sighash. */
    def localSigWithInvalidSighash(commitKeys: RemoteCommitmentKeys, sighash: Int): ByteVector64 = {
      sign(commitKeys.ourHtlcKey, sighash, redeemInfo(commitKeys.publicKeys), extraUtxos = Map.empty)
    }

    def checkRemoteSig(commitKeys: LocalCommitmentKeys, remoteSig: ByteVector64): Boolean = {
      // The transaction was signed by our remote for us: from their point of view, we're a remote owner.
      val remoteSighash = sighash(TxOwner.Remote)
      checkSig(remoteSig, commitKeys.theirHtlcPublicKey, remoteSighash, redeemInfo(commitKeys.publicKeys))
    }
  }

  /**
   * Once we've received valid signatures from our peer and the payment preimage for incoming HTLCs, we can create fully
   * signed HTLC transactions for our local [[CommitTx]].
   */
  sealed trait SignedHtlcTx extends HtlcTx with LocalCommitForceCloseTransaction with HasWalletInputs {
    /** Sign an HTLC transaction spending our local commitment. */
    def localSig(walletInputs: WalletInputs): ByteVector64 = {
      sign(commitKeys.ourHtlcKey, sighash(TxOwner.Local), redeemInfo, walletInputs.spentUtxos)
    }
  }

  /** This transaction spends a received (incoming) HTLC from a local or remote commitment by revealing the payment preimage. */
  case class HtlcSuccessTx(commitKeys: LocalCommitmentKeys, input: InputInfo, tx: Transaction, htlcId: Long, htlcExpiry: CltvExpiry, preimage: ByteVector32, remoteSig: ByteVector64, commitmentFormat: CommitmentFormat) extends SignedHtlcTx {
    override val desc: String = "htlc-success"
    override val paymentHash: ByteVector32 = Crypto.sha256(preimage)
    override val redeemInfo: RedeemInfo = HtlcSuccessTx.redeemInfo(commitKeys.publicKeys, paymentHash, htlcExpiry, commitmentFormat)
    override val expectedWeight: Int = commitmentFormat.htlcSuccessWeight

    override def sign(walletInputs: WalletInputs): Transaction = {
      val toSign = copy(tx = setWalletInputs(walletInputs))
      val sig = toSign.localSig(walletInputs)
      val witness = redeemInfo match {
        case redeemInfo: RedeemInfo.SegwitV0 =>
          witnessHtlcSuccess(sig, remoteSig, preimage, redeemInfo.redeemScript, commitmentFormat)
        case _: RedeemInfo.Taproot =>
          val receivedHtlcTree = Taproot.receivedHtlcScriptTree(commitKeys.publicKeys, paymentHash, htlcExpiry)
          receivedHtlcTree.witnessSuccess(commitKeys, sig, remoteSig, preimage)
      }
      toSign.tx.updateWitness(toSign.inputIndex, witness)
    }
  }

  case class UnsignedHtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, htlcExpiry: CltvExpiry, commitmentFormat: CommitmentFormat) extends UnsignedHtlcTx {
    override val desc: String = "htlc-success"

    override def redeemInfo(commitKeys: CommitmentPublicKeys): RedeemInfo = HtlcSuccessTx.redeemInfo(commitKeys, paymentHash, htlcExpiry, commitmentFormat)

    def addRemoteSig(commitKeys: LocalCommitmentKeys, remoteSig: ByteVector64, preimage: ByteVector32): HtlcSuccessTx = HtlcSuccessTx(commitKeys, input, tx, htlcId, htlcExpiry, preimage, remoteSig, commitmentFormat)
  }

  object HtlcSuccessTx {
    def createUnsignedTx(commitTx: Transaction,
                         output: InHtlc,
                         outputIndex: Int,
                         commitmentFormat: CommitmentFormat): UnsignedHtlcSuccessTx = {
      val htlc = output.htlc.add
      val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
        txOut = output.htlcDelayedOutput :: Nil,
        lockTime = 0
      )
      UnsignedHtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id, htlc.cltvExpiry, commitmentFormat)
    }

    def redeemInfo(commitKeys: CommitmentPublicKeys, paymentHash: ByteVector32, htlcExpiry: CltvExpiry, commitmentFormat: CommitmentFormat): RedeemInfo = commitmentFormat match {
      case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
        val redeemScript = Script.write(htlcReceived(commitKeys, paymentHash, htlcExpiry, commitmentFormat))
        RedeemInfo.P2wsh(redeemScript)
      case _: SimpleTaprootChannelCommitmentFormat =>
        val receivedHtlcTree = Taproot.receivedHtlcScriptTree(commitKeys, paymentHash, htlcExpiry)
        RedeemInfo.TaprootScriptPath(commitKeys.revocationPublicKey.xOnly, receivedHtlcTree.scriptTree, receivedHtlcTree.success.hash())
    }
  }

  /** This transaction spends an offered (outgoing) HTLC from a local or remote commitment after its expiry. */
  case class HtlcTimeoutTx(commitKeys: LocalCommitmentKeys, input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, htlcExpiry: CltvExpiry, remoteSig: ByteVector64, commitmentFormat: CommitmentFormat) extends SignedHtlcTx {
    override val desc: String = "htlc-timeout"
    override val redeemInfo: RedeemInfo = HtlcTimeoutTx.redeemInfo(commitKeys.publicKeys, paymentHash, commitmentFormat)
    override val expectedWeight: Int = commitmentFormat.htlcTimeoutWeight

    def sign(walletInputs: WalletInputs): Transaction = {
      val toSign = copy(tx = setWalletInputs(walletInputs))
      val sig = toSign.localSig(walletInputs)
      val witness = redeemInfo match {
        case redeemInfo: RedeemInfo.SegwitV0 =>
          witnessHtlcTimeout(sig, remoteSig, redeemInfo.redeemScript, commitmentFormat)
        case _: RedeemInfo.Taproot =>
          val offeredHtlcTree = Taproot.offeredHtlcScriptTree(commitKeys.publicKeys, paymentHash)
          offeredHtlcTree.witnessTimeout(commitKeys, sig, remoteSig)
      }
      toSign.tx.updateWitness(toSign.inputIndex, witness)
    }
  }

  case class UnsignedHtlcTimeoutTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, htlcExpiry: CltvExpiry, commitmentFormat: CommitmentFormat) extends UnsignedHtlcTx {
    override val desc: String = "htlc-timeout"

    override def redeemInfo(commitKeys: CommitmentPublicKeys): RedeemInfo = HtlcTimeoutTx.redeemInfo(commitKeys, paymentHash, commitmentFormat)

    def addRemoteSig(commitKeys: LocalCommitmentKeys, remoteSig: ByteVector64): HtlcTimeoutTx = HtlcTimeoutTx(commitKeys, input, tx, paymentHash, htlcId, htlcExpiry, remoteSig, commitmentFormat)
  }

  object HtlcTimeoutTx {
    def createUnsignedTx(commitTx: Transaction,
                         output: OutHtlc,
                         outputIndex: Int,
                         commitmentFormat: CommitmentFormat): UnsignedHtlcTimeoutTx = {
      val htlc = output.htlc.add
      val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
        txOut = output.htlcDelayedOutput :: Nil,
        lockTime = htlc.cltvExpiry.toLong
      )
      UnsignedHtlcTimeoutTx(input, tx, htlc.paymentHash, htlc.id, htlc.cltvExpiry, commitmentFormat)
    }

    def redeemInfo(commitKeys: CommitmentPublicKeys, paymentHash: ByteVector32, commitmentFormat: CommitmentFormat): RedeemInfo = commitmentFormat match {
      case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
        val redeemScript = Script.write(htlcOffered(commitKeys, paymentHash, commitmentFormat))
        RedeemInfo.P2wsh(redeemScript)
      case _: SimpleTaprootChannelCommitmentFormat =>
        val offeredHtlcTree = Taproot.offeredHtlcScriptTree(commitKeys, paymentHash)
        RedeemInfo.TaprootScriptPath(commitKeys.revocationPublicKey.xOnly, offeredHtlcTree.scriptTree, offeredHtlcTree.timeout.hash())
    }
  }

  /** This transaction spends the output of a local [[HtlcTx]] after a to_self_delay relative delay. */
  case class HtlcDelayedTx(commitKeys: LocalCommitmentKeys, input: InputInfo, tx: Transaction, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat) extends LocalCommitForceCloseTransaction {
    override val desc: String = "htlc-delayed"
    override val expectedWeight: Int = commitmentFormat.htlcDelayedWeight

    override def sign(): Transaction = {
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toLocalDelay))
          val sig = sign(commitKeys.ourDelayedPaymentKey, sighash, RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          witnessToLocalDelayedAfterDelay(sig, redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          val scriptTree: ScriptTree.Leaf = Taproot.htlcDelayedScriptTree(commitKeys.publicKeys, toLocalDelay)
          val redeemInfo = RedeemInfo.TaprootScriptPath(commitKeys.revocationPublicKey.xOnly, scriptTree, scriptTree.hash())
          val sig = sign(commitKeys.ourDelayedPaymentKey, sighash, redeemInfo, extraUtxos = Map.empty)
          Script.witnessScriptPathPay2tr(commitKeys.revocationPublicKey.xOnly, scriptTree, ScriptWitness(Seq(sig)), scriptTree)
      }
      tx.updateWitness(inputIndex, witness)
    }
  }

  object HtlcDelayedTx {
    def createUnsignedTx(commitKeys: LocalCommitmentKeys, htlcTx: Transaction, localDustLimit: Satoshi, toLocalDelay: CltvExpiryDelta, localFinalScriptPubKey: ByteVector, feerate: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcDelayedTx] = {
      val pubkeyScript = redeemInfo(commitKeys.publicKeys, toLocalDelay, commitmentFormat).pubkeyScript
      findPubKeyScriptIndex(htlcTx, pubkeyScript) match {
        case Left(skip) => Left(skip)
        case Right(outputIndex) =>
          val input = InputInfo(OutPoint(htlcTx, outputIndex), htlcTx.txOut(outputIndex))
          val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.htlcDelayedWeight)
          val tx = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toInt) :: Nil,
            txOut = TxOut(amount, localFinalScriptPubKey) :: Nil,
            lockTime = 0
          )
          val unsignedTx = HtlcDelayedTx(commitKeys, input, tx, toLocalDelay, commitmentFormat)
          skipTxIfBelowDust(unsignedTx, localDustLimit)
      }
    }

    def redeemInfo(commitKeys: CommitmentPublicKeys, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat): RedeemInfo = commitmentFormat match {
      case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
        val redeemScript = Script.write(toLocalDelayed(commitKeys, toLocalDelay))
        RedeemInfo.P2wsh(redeemScript)
      case _: SimpleTaprootChannelCommitmentFormat =>
        val scriptTree: ScriptTree.Leaf = Taproot.htlcDelayedScriptTree(commitKeys, toLocalDelay)
        RedeemInfo.TaprootScriptPath(commitKeys.revocationPublicKey.xOnly, scriptTree, scriptTree.hash())
    }
  }

  sealed trait ClaimHtlcTx extends RemoteCommitForceCloseTransaction {
    // @formatter:off
    def htlcId: Long
    def paymentHash: ByteVector32
    def htlcExpiry: CltvExpiry
    // @formatter:on
  }

  /** This transaction spends an HTLC we received by revealing the payment preimage, from the remote commitment. */
  case class ClaimHtlcSuccessTx(commitKeys: RemoteCommitmentKeys, input: InputInfo, tx: Transaction, preimage: ByteVector32, htlcId: Long, htlcExpiry: CltvExpiry, commitmentFormat: CommitmentFormat) extends ClaimHtlcTx {
    override val desc: String = "claim-htlc-success"
    override val paymentHash: ByteVector32 = Crypto.sha256(preimage)
    override val expectedWeight: Int = commitmentFormat.claimHtlcSuccessWeight

    override def sign(): Transaction = {
      // Note that in/out HTLCs are inverted in the remote commitment: from their point of view it's an offered (outgoing) HTLC.
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(htlcOffered(commitKeys.publicKeys, paymentHash, commitmentFormat))
          val sig = sign(commitKeys.ourHtlcKey, sighash, RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          witnessClaimHtlcSuccessFromCommitTx(sig, preimage, redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          val offeredTree = Taproot.offeredHtlcScriptTree(commitKeys.publicKeys, paymentHash)
          val redeemInfo = RedeemInfo.TaprootScriptPath(commitKeys.revocationPublicKey.xOnly, offeredTree.scriptTree, offeredTree.success.hash())
          val sig = sign(commitKeys.ourHtlcKey, sighash, redeemInfo, extraUtxos = Map.empty)
          offeredTree.witnessSuccess(commitKeys, sig, preimage)
      }
      tx.updateWitness(inputIndex, witness)
    }
  }

  object ClaimHtlcSuccessTx {
    /**
     * Find the output of the commitment transaction matching this HTLC.
     * Note that we match on a specific HTLC, because we may have multiple HTLCs with the same payment_hash, expiry
     * and amount and thus the same pubkeyScript, and we must make sure we claim them all.
     */
    def findInput(commitTx: Transaction, outputs: Seq[CommitmentOutput], htlc: UpdateAddHtlc): Option[InputInfo] = {
      outputs.zipWithIndex.collectFirst {
        case (OutHtlc(outgoingHtlc, _, _), outputIndex) if outgoingHtlc.add.id == htlc.id =>
          InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
      }
    }

    def createUnsignedTx(commitKeys: RemoteCommitmentKeys,
                         commitTx: Transaction,
                         dustLimit: Satoshi,
                         outputs: Seq[CommitmentOutput],
                         localFinalScriptPubKey: ByteVector,
                         htlc: UpdateAddHtlc,
                         preimage: ByteVector32,
                         feerate: FeeratePerKw,
                         commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimHtlcSuccessTx] = {
      findInput(commitTx, outputs, htlc) match {
        case Some(input) =>
          val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.claimHtlcSuccessWeight)
          val tx = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
            txOut = TxOut(amount, localFinalScriptPubKey) :: Nil,
            lockTime = 0
          )
          val unsignedTx = ClaimHtlcSuccessTx(commitKeys, input, tx, preimage, htlc.id, htlc.cltvExpiry, commitmentFormat)
          skipTxIfBelowDust(unsignedTx, dustLimit)
        case None => Left(OutputNotFound)
      }
    }
  }

  /** This transaction spends an HTLC we sent after its expiry, from the remote commitment. */
  case class ClaimHtlcTimeoutTx(commitKeys: RemoteCommitmentKeys, input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, htlcExpiry: CltvExpiry, commitmentFormat: CommitmentFormat) extends ClaimHtlcTx {
    override val desc: String = "claim-htlc-timeout"
    override val expectedWeight: Int = commitmentFormat.claimHtlcTimeoutWeight

    override def sign(): Transaction = {
      // Note that in/out HTLCs are inverted in the remote commitment: from their point of view it's a received (incoming) HTLC.
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(htlcReceived(commitKeys.publicKeys, paymentHash, htlcExpiry, commitmentFormat))
          val sig = sign(commitKeys.ourHtlcKey, sighash, RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          witnessClaimHtlcTimeoutFromCommitTx(sig, redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          val offeredTree = Taproot.receivedHtlcScriptTree(commitKeys.publicKeys, paymentHash, htlcExpiry)
          val redeemInfo = RedeemInfo.TaprootScriptPath(commitKeys.revocationPublicKey.xOnly, offeredTree.scriptTree, offeredTree.timeout.hash())
          val sig = sign(commitKeys.ourHtlcKey, sighash, redeemInfo, extraUtxos = Map.empty)
          offeredTree.witnessTimeout(commitKeys, sig)
      }
      tx.updateWitness(inputIndex, witness)
    }
  }

  object ClaimHtlcTimeoutTx {
    /**
     * Find the output of the commitment transaction matching this HTLC.
     * Note that we match on a specific HTLC, because we may have multiple HTLCs with the same payment_hash, expiry
     * and amount and thus the same pubkeyScript, and we must make sure we claim them all.
     */
    def findInput(commitTx: Transaction, outputs: Seq[CommitmentOutput], htlc: UpdateAddHtlc): Option[InputInfo] = {
      outputs.zipWithIndex.collectFirst {
        case (InHtlc(incomingHtlc, _, _), outputIndex) if incomingHtlc.add.id == htlc.id =>
          InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
      }
    }

    def createUnsignedTx(commitKeys: RemoteCommitmentKeys,
                         commitTx: Transaction,
                         dustLimit: Satoshi,
                         outputs: Seq[CommitmentOutput],
                         localFinalScriptPubKey: ByteVector,
                         htlc: UpdateAddHtlc,
                         feerate: FeeratePerKw,
                         commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimHtlcTimeoutTx] = {
      findInput(commitTx, outputs, htlc) match {
        case Some(input) =>
          val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.claimHtlcTimeoutWeight)
          val tx = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
            txOut = TxOut(amount, localFinalScriptPubKey) :: Nil,
            lockTime = htlc.cltvExpiry.toLong
          )
          val unsignedTx = ClaimHtlcTimeoutTx(commitKeys, input, tx, htlc.paymentHash, htlc.id, htlc.cltvExpiry, commitmentFormat)
          skipTxIfBelowDust(unsignedTx, dustLimit)
        case None => Left(OutputNotFound)
      }
    }
  }

  /** This transaction claims our anchor output to CPFP the parent commitment transaction and get it confirmed. */
  sealed trait ClaimAnchorTx extends ForceCloseTransaction with HasWalletInputs {
    // On top of the anchor input, the weight includes the nVersion field, nLockTime and other shared fields.
    override def expectedWeight: Int = commitmentFormat.anchorInputWeight + 42
  }

  object ClaimAnchorTx {
    def redeemInfo(fundingKey: PublicKey, paymentKey: PublicKey, commitmentFormat: CommitmentFormat): RedeemInfo = {
      commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat => RedeemInfo.P2wsh(anchor(fundingKey))
        case _: SimpleTaprootChannelCommitmentFormat => RedeemInfo.TaprootKeyPath(paymentKey.xOnly, Some(Taproot.anchorScriptTree))
      }
    }

    def createUnsignedTx(input: InputInfo): Transaction = {
      Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, 0) :: Nil,
        txOut = Nil, // anchor is only used to bump fees, the output will be added later depending on available inputs
        lockTime = 0
      )
    }
  }

  /** This transaction claims our anchor output in our local commitment. */
  case class ClaimLocalAnchorTx(fundingKey: PrivateKey, commitKeys: LocalCommitmentKeys, input: InputInfo, tx: Transaction, commitmentFormat: CommitmentFormat) extends ClaimAnchorTx with LocalCommitForceCloseTransaction {
    override val desc: String = "local-anchor"
    override val redeemInfo: RedeemInfo = ClaimLocalAnchorTx.redeemInfo(fundingKey.publicKey, commitKeys.publicKeys, commitmentFormat)

    override def sign(walletInputs: WalletInputs): Transaction = {
      val toSign = copy(tx = setWalletInputs(walletInputs))
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(anchor(fundingKey.publicKey))
          val sig = toSign.sign(fundingKey, sighash, RedeemInfo.P2wsh(redeemScript), walletInputs.spentUtxos)
          witnessAnchor(sig, redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          val anchorKey = commitKeys.ourDelayedPaymentKey
          val redeemInfo = RedeemInfo.TaprootKeyPath(anchorKey.xOnlyPublicKey(), Some(Taproot.anchorScriptTree))
          val sig = toSign.sign(anchorKey, sighash, redeemInfo, walletInputs.spentUtxos)
          Script.witnessKeyPathPay2tr(sig)
      }
      toSign.tx.updateWitness(toSign.inputIndex, witness)
    }
  }

  object ClaimLocalAnchorTx {
    def redeemInfo(fundingKey: PublicKey, commitKeys: CommitmentPublicKeys, commitmentFormat: CommitmentFormat): RedeemInfo = {
      ClaimAnchorTx.redeemInfo(fundingKey, commitKeys.localDelayedPaymentPublicKey, commitmentFormat)
    }

    def findInput(commitTx: Transaction, fundingKey: PrivateKey, commitKeys: LocalCommitmentKeys, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, InputInfo] = {
      val pubKeyScript = redeemInfo(fundingKey.publicKey, commitKeys.publicKeys, commitmentFormat).pubkeyScript
      findPubKeyScriptIndex(commitTx, pubKeyScript).map(outputIndex => InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex)))
    }

    def createUnsignedTx(fundingKey: PrivateKey, commitKeys: LocalCommitmentKeys, commitTx: Transaction, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimLocalAnchorTx] = {
      findInput(commitTx, fundingKey, commitKeys, commitmentFormat).map(input => ClaimLocalAnchorTx(fundingKey, commitKeys, input, ClaimAnchorTx.createUnsignedTx(input), commitmentFormat))
    }
  }

  /** This transaction claims our anchor output in a remote commitment. */
  case class ClaimRemoteAnchorTx(fundingKey: PrivateKey, commitKeys: RemoteCommitmentKeys, input: InputInfo, tx: Transaction, commitmentFormat: CommitmentFormat) extends ClaimAnchorTx with RemoteCommitForceCloseTransaction {
    override val desc: String = "remote-anchor"
    override val redeemInfo: RedeemInfo = ClaimRemoteAnchorTx.redeemInfo(fundingKey.publicKey, commitKeys.publicKeys, commitmentFormat)

    override def sign(walletInputs: WalletInputs): Transaction = {
      val toSign = copy(tx = setWalletInputs(walletInputs))
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(anchor(fundingKey.publicKey))
          val sig = toSign.sign(fundingKey, sighash, RedeemInfo.P2wsh(redeemScript), walletInputs.spentUtxos)
          witnessAnchor(sig, redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          val Right(anchorKey) = commitKeys.ourPaymentKey
          val redeemInfo = RedeemInfo.TaprootKeyPath(anchorKey.xOnlyPublicKey(), Some(Taproot.anchorScriptTree))
          val sig = toSign.sign(anchorKey, sighash, redeemInfo, walletInputs.spentUtxos)
          Script.witnessKeyPathPay2tr(sig)
      }
      toSign.tx.updateWitness(toSign.inputIndex, witness)
    }
  }

  object ClaimRemoteAnchorTx {
    def redeemInfo(fundingKey: PublicKey, commitKeys: CommitmentPublicKeys, commitmentFormat: CommitmentFormat): RedeemInfo = {
      ClaimAnchorTx.redeemInfo(fundingKey, commitKeys.remotePaymentPublicKey, commitmentFormat)
    }

    def findInput(commitTx: Transaction, fundingKey: PrivateKey, commitKeys: RemoteCommitmentKeys, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, InputInfo] = {
      val pubKeyScript = redeemInfo(fundingKey.publicKey, commitKeys.publicKeys, commitmentFormat).pubkeyScript
      findPubKeyScriptIndex(commitTx, pubKeyScript).map(outputIndex => InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex)))
    }

    def createUnsignedTx(fundingKey: PrivateKey, commitKeys: RemoteCommitmentKeys, commitTx: Transaction, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimRemoteAnchorTx] = {
      findInput(commitTx, fundingKey, commitKeys, commitmentFormat).map(input => ClaimRemoteAnchorTx(fundingKey, commitKeys, input, ClaimAnchorTx.createUnsignedTx(input), commitmentFormat))
    }
  }

  sealed trait ClaimRemoteCommitMainOutputTx extends RemoteCommitForceCloseTransaction

  /** This transaction claims our main balance from the remote commitment without any delay, when using the [[DefaultCommitmentFormat]]. */
  case class ClaimP2WPKHOutputTx(commitKeys: RemoteCommitmentKeys, input: InputInfo, tx: Transaction, commitmentFormat: CommitmentFormat) extends ClaimRemoteCommitMainOutputTx {
    override val desc: String = "remote-main"
    override val expectedWeight: Int = commitmentFormat.toRemoteWeight

    override def sign(): Transaction = {
      val Right(paymentKey) = commitKeys.ourPaymentKey
      val redeemInfo = RedeemInfo.P2wpkh(paymentKey.publicKey)
      val sig = sign(paymentKey, sighash, redeemInfo, extraUtxos = Map.empty)
      val witness = Script.witnessPay2wpkh(paymentKey.publicKey, der(sig))
      tx.updateWitness(inputIndex, witness)
    }
  }

  object ClaimP2WPKHOutputTx {
    def createUnsignedTx(commitKeys: RemoteCommitmentKeys, commitTx: Transaction, localDustLimit: Satoshi, localFinalScriptPubKey: ByteVector, feerate: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimP2WPKHOutputTx] = {
      val redeemInfo = RedeemInfo.P2wpkh(commitKeys.ourPaymentPublicKey)
      findPubKeyScriptIndex(commitTx, redeemInfo.pubkeyScript) match {
        case Left(skip) => Left(skip)
        case Right(outputIndex) =>
          commitKeys.ourPaymentKey match {
            case Left(_) => Left(OutputAlreadyInWallet)
            case Right(_) =>
              val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
              val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.toRemoteWeight)
              val tx = Transaction(
                version = 2,
                txIn = TxIn(input.outPoint, ByteVector.empty, 0) :: Nil,
                txOut = TxOut(amount, localFinalScriptPubKey) :: Nil,
                lockTime = 0
              )
              val unsignedTx = ClaimP2WPKHOutputTx(commitKeys, input, tx, commitmentFormat)
              skipTxIfBelowDust(unsignedTx, localDustLimit)
          }
      }
    }
  }

  /** This transaction spends our main balance from the remote commitment with a 1-block relative delay. */
  case class ClaimRemoteDelayedOutputTx(commitKeys: RemoteCommitmentKeys, input: InputInfo, tx: Transaction, commitmentFormat: CommitmentFormat) extends ClaimRemoteCommitMainOutputTx {
    override val desc: String = "remote-main-delayed"
    override val expectedWeight: Int = commitmentFormat.toRemoteWeight

    override def sign(): Transaction = {
      val Right(paymentKey) = commitKeys.ourPaymentKey
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toRemoteDelayed(commitKeys.publicKeys))
          val sig = sign(paymentKey, sighash, RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          witnessClaimToRemoteDelayedFromCommitTx(sig, redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          val scriptTree: ScriptTree.Leaf = Taproot.toRemoteScriptTree(commitKeys.publicKeys)
          val redeemInfo = RedeemInfo.TaprootScriptPath(NUMS_POINT.xOnly, scriptTree, scriptTree.hash())
          val sig = sign(paymentKey, sighash, redeemInfo, extraUtxos = Map.empty)
          Script.witnessScriptPathPay2tr(redeemInfo.internalKey, scriptTree, ScriptWitness(Seq(sig)), scriptTree)
      }
      tx.updateWitness(inputIndex, witness)
    }
  }

  object ClaimRemoteDelayedOutputTx {
    def createUnsignedTx(commitKeys: RemoteCommitmentKeys, commitTx: Transaction, localDustLimit: Satoshi, localFinalScriptPubKey: ByteVector, feerate: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimRemoteDelayedOutputTx] = {
      val redeemInfo = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toRemoteDelayed(commitKeys.publicKeys))
          RedeemInfo.P2wsh(redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          val scriptTree: ScriptTree.Leaf = Taproot.toRemoteScriptTree(commitKeys.publicKeys)
          RedeemInfo.TaprootScriptPath(NUMS_POINT.xOnly, scriptTree, scriptTree.hash())
      }
      findPubKeyScriptIndex(commitTx, redeemInfo.pubkeyScript) match {
        case Left(skip) => Left(skip)
        case Right(outputIndex) =>
          commitKeys.ourPaymentKey match {
            case Left(_) => Left(OutputAlreadyInWallet)
            case Right(_) =>
              val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
              val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.toRemoteWeight)
              val tx = Transaction(
                version = 2,
                txIn = TxIn(input.outPoint, ByteVector.empty, 1) :: Nil,
                txOut = TxOut(amount, localFinalScriptPubKey) :: Nil,
                lockTime = 0
              )
              val unsignedTx = ClaimRemoteDelayedOutputTx(commitKeys, input, tx, commitmentFormat)
              skipTxIfBelowDust(unsignedTx, localDustLimit)
          }
      }
    }
  }

  /** This transaction spends our main balance from our commitment after a to_self_delay relative delay. */
  case class ClaimLocalDelayedOutputTx(commitKeys: LocalCommitmentKeys, input: InputInfo, tx: Transaction, toLocalDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat) extends LocalCommitForceCloseTransaction {
    override val desc: String = "local-main-delayed"
    override val expectedWeight: Int = commitmentFormat.toLocalDelayedWeight

    override def sign(): Transaction = {
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toLocalDelay))
          val sig = sign(commitKeys.ourDelayedPaymentKey, sighash, RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          witnessToLocalDelayedAfterDelay(sig, redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          val toLocalTree = Taproot.toLocalScriptTree(commitKeys.publicKeys, toLocalDelay)
          val redeemInfo = RedeemInfo.TaprootScriptPath(NUMS_POINT.xOnly, toLocalTree.scriptTree, toLocalTree.localDelayed.hash())
          val sig = sign(commitKeys.ourDelayedPaymentKey, sighash, redeemInfo, extraUtxos = Map.empty)
          Script.witnessScriptPathPay2tr(redeemInfo.internalKey, redeemInfo.leaf, ScriptWitness(Seq(sig)), toLocalTree.scriptTree)
      }
      tx.updateWitness(inputIndex, witness)
    }
  }

  object ClaimLocalDelayedOutputTx {
    def createUnsignedTx(commitKeys: LocalCommitmentKeys, commitTx: Transaction, localDustLimit: Satoshi, toLocalDelay: CltvExpiryDelta, localFinalScriptPubKey: ByteVector, feerate: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimLocalDelayedOutputTx] = {
      val redeemInfo = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toLocalDelay))
          RedeemInfo.P2wsh(redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          val toLocalTree = Taproot.toLocalScriptTree(commitKeys.publicKeys, toLocalDelay)
          RedeemInfo.TaprootScriptPath(NUMS_POINT.xOnly, toLocalTree.scriptTree, toLocalTree.localDelayed.hash())
      }
      findPubKeyScriptIndex(commitTx, redeemInfo.pubkeyScript) match {
        case Left(skip) => Left(skip)
        case Right(outputIndex) =>
          val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
          val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.toLocalDelayedWeight)
          val tx = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toInt) :: Nil,
            txOut = TxOut(amount, localFinalScriptPubKey) :: Nil,
            lockTime = 0
          )
          val unsignedTx = ClaimLocalDelayedOutputTx(commitKeys, input, tx, toLocalDelay, commitmentFormat)
          skipTxIfBelowDust(unsignedTx, localDustLimit)
      }
    }
  }

  /** This transaction spends the remote main balance from one of their revoked commitments. */
  case class MainPenaltyTx(commitKeys: RemoteCommitmentKeys, revocationKey: PrivateKey, input: InputInfo, tx: Transaction, toRemoteDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat) extends RemoteCommitForceCloseTransaction {
    override val desc: String = "main-penalty"
    override val expectedWeight: Int = commitmentFormat.mainPenaltyWeight

    override def sign(): Transaction = {
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toRemoteDelay))
          val sig = sign(revocationKey, sighash, RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          Scripts.witnessToLocalDelayedWithRevocationSig(sig, redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          val toLocalTree = Taproot.toLocalScriptTree(commitKeys.publicKeys, toRemoteDelay)
          val redeemInfo = RedeemInfo.TaprootScriptPath(NUMS_POINT.xOnly, toLocalTree.scriptTree, toLocalTree.revocation.hash())
          val sig = sign(revocationKey, sighash, redeemInfo, extraUtxos = Map.empty)
          Script.witnessScriptPathPay2tr(redeemInfo.internalKey, redeemInfo.leaf, ScriptWitness(Seq(sig)), toLocalTree.scriptTree)
      }
      tx.updateWitness(inputIndex, witness)
    }
  }

  object MainPenaltyTx {
    def createUnsignedTx(commitKeys: RemoteCommitmentKeys, revocationKey: PrivateKey, commitTx: Transaction, localDustLimit: Satoshi, localFinalScriptPubKey: ByteVector, toRemoteDelay: CltvExpiryDelta, feerate: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, MainPenaltyTx] = {
      val redeemInfo = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toRemoteDelay))
          RedeemInfo.P2wsh(redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          val toLocalTree = Taproot.toLocalScriptTree(commitKeys.publicKeys, toRemoteDelay)
          RedeemInfo.TaprootScriptPath(NUMS_POINT.xOnly, toLocalTree.scriptTree, toLocalTree.revocation.hash())
      }
      findPubKeyScriptIndex(commitTx, redeemInfo.pubkeyScript) match {
        case Left(skip) => Left(skip)
        case Right(outputIndex) =>
          val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
          val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.mainPenaltyWeight)
          val tx = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
            txOut = TxOut(amount, localFinalScriptPubKey) :: Nil,
            lockTime = 0
          )
          val unsignedTx = MainPenaltyTx(commitKeys, revocationKey, input, tx, toRemoteDelay, commitmentFormat)
          skipTxIfBelowDust(unsignedTx, localDustLimit)
      }
    }
  }

  private case class HtlcPenaltyRedeemDetails(redeemInfo: RedeemInfo, paymentHash: ByteVector32, htlcExpiry: CltvExpiry, weight: Int)

  /** This transaction spends an HTLC output from one of the remote revoked commitments. */
  case class HtlcPenaltyTx(commitKeys: RemoteCommitmentKeys, revocationKey: PrivateKey, redeemInfo: RedeemInfo, input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcExpiry: CltvExpiry, commitmentFormat: CommitmentFormat) extends RemoteCommitForceCloseTransaction {
    override val desc: String = "htlc-penalty"
    // We don't know if this is an incoming or outgoing HTLC, so we just use the bigger one (they are very close anyway).
    override val expectedWeight: Int = commitmentFormat.htlcOfferedPenaltyWeight.max(commitmentFormat.htlcReceivedPenaltyWeight)

    override def sign(): Transaction = {
      val sig = sign(revocationKey, sighash, redeemInfo, extraUtxos = Map.empty)
      val witness = redeemInfo match {
        case RedeemInfo.P2wpkh(_) => Script.witnessPay2wpkh(revocationKey.publicKey, der(sig))
        case RedeemInfo.P2wsh(redeemScript) => Scripts.witnessHtlcWithRevocationSig(commitKeys, sig, redeemScript)
        case _: RedeemInfo.TaprootKeyPath => Script.witnessKeyPathPay2tr(sig, sighash)
        case s: RedeemInfo.TaprootScriptPath => Script.witnessScriptPathPay2tr(s.internalKey, s.leaf, ScriptWitness(Seq(sig)), s.scriptTree)
      }
      tx.updateWitness(inputIndex, witness)
    }
  }

  object HtlcPenaltyTx {
    def createUnsignedTxs(commitKeys: RemoteCommitmentKeys,
                          revocationKey: PrivateKey,
                          commitTx: Transaction,
                          htlcs: Seq[(ByteVector32, CltvExpiry)],
                          localDustLimit: Satoshi,
                          localFinalScriptPubKey: ByteVector,
                          feerate: FeeratePerKw,
                          commitmentFormat: CommitmentFormat): Seq[Either[TxGenerationSkipped, HtlcPenaltyTx]] = {
      // We create the output scripts for the corresponding HTLCs.
      val redeemInfos: Map[ByteVector, HtlcPenaltyRedeemDetails] = htlcs.flatMap {
        case (paymentHash, htlcExpiry) =>
          // We don't know if this was an incoming or outgoing HTLC, so we try both cases.
          val (offered, received) = commitmentFormat match {
            case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
              (RedeemInfo.P2wsh(Script.write(htlcOffered(commitKeys.publicKeys, paymentHash, commitmentFormat))),
                RedeemInfo.P2wsh(Script.write(htlcReceived(commitKeys.publicKeys, paymentHash, htlcExpiry, commitmentFormat))))
            case _: SimpleTaprootChannelCommitmentFormat =>
              (RedeemInfo.TaprootKeyPath(commitKeys.revocationPublicKey.xOnly, Some(Taproot.offeredHtlcScriptTree(commitKeys.publicKeys, paymentHash).scriptTree)),
                RedeemInfo.TaprootKeyPath(commitKeys.revocationPublicKey.xOnly, Some(Taproot.receivedHtlcScriptTree(commitKeys.publicKeys, paymentHash, htlcExpiry).scriptTree)))
          }
          Seq(
            offered.pubkeyScript -> HtlcPenaltyRedeemDetails(offered, paymentHash, htlcExpiry, commitmentFormat.htlcOfferedPenaltyWeight),
            received.pubkeyScript -> HtlcPenaltyRedeemDetails(received, paymentHash, htlcExpiry, commitmentFormat.htlcReceivedPenaltyWeight),
          )
      }.toMap
      // We check every output of the commitment transaction, and create an HTLC-penalty transaction if it is an HTLC output.
      commitTx.txOut.zipWithIndex.collect {
        case (txOut, outputIndex) if redeemInfos.contains(txOut.publicKeyScript) =>
          val Some(redeemInfo) = redeemInfos.get(txOut.publicKeyScript)
          createUnsignedTx(commitKeys, revocationKey, commitTx, outputIndex, redeemInfo, localDustLimit, localFinalScriptPubKey, feerate, commitmentFormat)
      }
    }

    private def createUnsignedTx(commitKeys: RemoteCommitmentKeys,
                                 revocationKey: PrivateKey,
                                 commitTx: Transaction,
                                 htlcOutputIndex: Int,
                                 redeemDetails: HtlcPenaltyRedeemDetails,
                                 localDustLimit: Satoshi,
                                 localFinalScriptPubKey: ByteVector,
                                 feerate: FeeratePerKw,
                                 commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcPenaltyTx] = {
      val input = InputInfo(OutPoint(commitTx, htlcOutputIndex), commitTx.txOut(htlcOutputIndex))
      val amount = input.txOut.amount - weight2fee(feerate, redeemDetails.weight)
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
        txOut = TxOut(amount, localFinalScriptPubKey) :: Nil,
        lockTime = 0
      )
      val unsignedTx = HtlcPenaltyTx(commitKeys, revocationKey, redeemDetails.redeemInfo, input, tx, redeemDetails.paymentHash, redeemDetails.htlcExpiry, commitmentFormat)
      skipTxIfBelowDust(unsignedTx, localDustLimit)
    }
  }

  /** This transaction spends a remote [[HtlcTx]] from one of their revoked commitments. */
  case class ClaimHtlcDelayedOutputPenaltyTx(commitKeys: RemoteCommitmentKeys, revocationKey: PrivateKey, input: InputInfo, tx: Transaction, toRemoteDelay: CltvExpiryDelta, commitmentFormat: CommitmentFormat) extends RemoteCommitForceCloseTransaction {
    override val desc: String = "htlc-delayed-penalty"
    override val expectedWeight: Int = commitmentFormat.claimHtlcPenaltyWeight

    override def sign(): Transaction = {
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toRemoteDelay))
          val sig = sign(revocationKey, sighash, RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          Scripts.witnessToLocalDelayedWithRevocationSig(sig, redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          val redeemInfo = RedeemInfo.TaprootKeyPath(commitKeys.revocationPublicKey.xOnly, Some(Taproot.htlcDelayedScriptTree(commitKeys.publicKeys, toRemoteDelay)))
          val sig = sign(revocationKey, sighash, redeemInfo, extraUtxos = Map.empty)
          Script.witnessKeyPathPay2tr(sig)
      }
      tx.updateWitness(inputIndex, witness)
    }
  }

  object ClaimHtlcDelayedOutputPenaltyTx {
    def createUnsignedTxs(commitKeys: RemoteCommitmentKeys,
                          revocationKey: PrivateKey,
                          htlcTx: Transaction,
                          localDustLimit: Satoshi,
                          toRemoteDelay: CltvExpiryDelta,
                          localFinalScriptPubKey: ByteVector,
                          feerate: FeeratePerKw,
                          commitmentFormat: CommitmentFormat): Seq[Either[TxGenerationSkipped, ClaimHtlcDelayedOutputPenaltyTx]] = {
      val redeemInfo = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toRemoteDelay))
          RedeemInfo.P2wsh(redeemScript)
        case _: SimpleTaprootChannelCommitmentFormat =>
          RedeemInfo.TaprootKeyPath(commitKeys.revocationPublicKey.xOnly, Some(Taproot.htlcDelayedScriptTree(commitKeys.publicKeys, toRemoteDelay)))
      }
      // Note that we check *all* outputs of the tx, because it could spend a batch of HTLC outputs from the commit tx.
      htlcTx.txOut.zipWithIndex.collect {
        case (txOut, outputIndex) if txOut.publicKeyScript == redeemInfo.pubkeyScript =>
          val input = InputInfo(OutPoint(htlcTx, outputIndex), htlcTx.txOut(outputIndex))
          val amount = input.txOut.amount - weight2fee(feerate, commitmentFormat.claimHtlcPenaltyWeight)
          val tx = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
            txOut = TxOut(amount, localFinalScriptPubKey) :: Nil,
            lockTime = 0
          )
          val unsignedTx = ClaimHtlcDelayedOutputPenaltyTx(commitKeys, revocationKey, input, tx, toRemoteDelay, commitmentFormat)
          skipTxIfBelowDust(unsignedTx, localDustLimit)
      }
    }
  }

  // @formatter:off
  sealed trait TxGenerationSkipped
  case object OutputNotFound extends TxGenerationSkipped { override def toString = "output not found (probably trimmed)" }
  private case object OutputAlreadyInWallet extends TxGenerationSkipped { override def toString = "output doesn't need to be claimed, it belongs to our bitcoin wallet (p2wpkh or p2tr)" }
  case object AmountBelowDustLimit extends TxGenerationSkipped { override def toString = "amount is below dust limit" }
  private case class CannotUpdateFee(txInfo: ForceCloseTransaction) extends TxGenerationSkipped { override def toString = s"cannot update fee for ${txInfo.desc} transactions" }
  // @formatter:on

  private def weight2feeMsat(feeratePerKw: FeeratePerKw, weight: Int): MilliSatoshi = MilliSatoshi(feeratePerKw.toLong * weight)

  def weight2fee(feeratePerKw: FeeratePerKw, weight: Int): Satoshi = weight2feeMsat(feeratePerKw, weight).truncateToSatoshi

  /**
   * @param fee    tx fee
   * @param weight tx weight
   * @return the fee rate (in Satoshi/Kw) for this tx
   */
  def fee2rate(fee: Satoshi, weight: Int): FeeratePerKw = FeeratePerKw((fee * 1000L) / weight)

  /** Offered HTLCs below this amount will be trimmed. */
  def offeredHtlcTrimThreshold(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Satoshi =
    dustLimit + weight2fee(spec.htlcTxFeerate(commitmentFormat), commitmentFormat.htlcTimeoutWeight)

  def offeredHtlcTrimThreshold(dustLimit: Satoshi, feerate: FeeratePerKw, commitmentFormat: CommitmentFormat): Satoshi = {
    commitmentFormat match {
      case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat | ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat => dustLimit
      case _ => dustLimit + weight2fee(feerate, commitmentFormat.htlcTimeoutWeight)
    }
  }

  def trimOfferedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Seq[OutgoingHtlc] = {
    val threshold = offeredHtlcTrimThreshold(dustLimit, spec, commitmentFormat)
    spec.htlcs
      .collect { case o: OutgoingHtlc if o.add.amountMsat >= threshold => o }
      .toSeq
  }

  /** Received HTLCs below this amount will be trimmed. */
  def receivedHtlcTrimThreshold(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Satoshi =
    dustLimit + weight2fee(spec.htlcTxFeerate(commitmentFormat), commitmentFormat.htlcSuccessWeight)

  def receivedHtlcTrimThreshold(dustLimit: Satoshi, feerate: FeeratePerKw, commitmentFormat: CommitmentFormat): Satoshi = {
    commitmentFormat match {
      case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat | ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat => dustLimit
      case _ => dustLimit + weight2fee(feerate, commitmentFormat.htlcSuccessWeight)
    }
  }

  def trimReceivedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Seq[IncomingHtlc] = {
    val threshold = receivedHtlcTrimThreshold(dustLimit, spec, commitmentFormat)
    spec.htlcs
      .collect { case i: IncomingHtlc if i.add.amountMsat >= threshold => i }
      .toSeq
  }

  /** Fee for an un-trimmed HTLC. */
  def htlcOutputFee(feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): MilliSatoshi = weight2feeMsat(feeratePerKw, commitmentFormat.htlcOutputWeight)

  /** Fee paid by the commit tx (depends on which HTLCs will be trimmed). */
  def commitTxFeeMsat(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): MilliSatoshi = {
    val trimmedOfferedHtlcs = trimOfferedHtlcs(dustLimit, spec, commitmentFormat)
    val trimmedReceivedHtlcs = trimReceivedHtlcs(dustLimit, spec, commitmentFormat)
    val weight = commitmentFormat.commitWeight + commitmentFormat.htlcOutputWeight * (trimmedOfferedHtlcs.size + trimmedReceivedHtlcs.size)
    weight2feeMsat(spec.commitTxFeerate, weight)
  }

  /**
   * While on-chain amounts are generally computed in Satoshis (since this is the smallest on-chain unit), it may be
   * useful in some cases to calculate it in MilliSatoshi to avoid rounding issues.
   * If you are adding multiple fees together for example, you should always add them in MilliSatoshi and then round
   * down to Satoshi.
   */
  def commitTxTotalCostMsat(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): MilliSatoshi = {
    // The channel initiator pays the on-chain fee by deducing it from its main output.
    val txFee = commitTxFeeMsat(dustLimit, spec, commitmentFormat)
    // When using anchor outputs, the channel initiator pays for *both* anchors all the time, even if only one anchor is present.
    // This is not technically a fee (it doesn't go to miners) but it also has to be deduced from the channel initiator's main output.
    val anchorsCost = commitmentFormat match {
      case DefaultCommitmentFormat => Satoshi(0)
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => AnchorOutputsCommitmentFormat.anchorAmount * 2
    }
    txFee + anchorsCost
  }

  def commitTxTotalCost(dustLimit: Satoshi, spec: CommitmentSpec, commitmentFormat: CommitmentFormat): Satoshi = commitTxTotalCostMsat(dustLimit, spec, commitmentFormat).truncateToSatoshi

  /**
   * @param commitTxNumber         commit tx number
   * @param localIsChannelOpener   true if local node initiated the channel open
   * @param localPaymentBasePoint  local payment base point
   * @param remotePaymentBasePoint remote payment base point
   * @return the obscured tx number as defined in BOLT #3 (a 48 bits integer)
   */
  def obscuredCommitTxNumber(commitTxNumber: Long, localIsChannelOpener: Boolean, localPaymentBasePoint: PublicKey, remotePaymentBasePoint: PublicKey): Long = {
    // from BOLT 3: SHA256(payment-basepoint from open_channel || payment-basepoint from accept_channel)
    val h = if (localIsChannelOpener) {
      Crypto.sha256(localPaymentBasePoint.value ++ remotePaymentBasePoint.value)
    } else {
      Crypto.sha256(remotePaymentBasePoint.value ++ localPaymentBasePoint.value)
    }
    val blind = Protocol.uint64((h.takeRight(6).reverse ++ ByteVector.fromValidHex("0000")).toArray, ByteOrder.LITTLE_ENDIAN)
    commitTxNumber ^ blind
  }

  /**
   * @param commitTx               commit tx
   * @param localIsChannelOpener   true if local node initiated the channel open
   * @param localPaymentBasePoint  local payment base point
   * @param remotePaymentBasePoint remote payment base point
   * @return the actual commit tx number that was blinded and stored in locktime and sequence fields
   */
  def getCommitTxNumber(commitTx: Transaction, localIsChannelOpener: Boolean, localPaymentBasePoint: PublicKey, remotePaymentBasePoint: PublicKey): Long = {
    require(commitTx.txIn.size == 1, "commitment tx should have 1 input")
    val blind = obscuredCommitTxNumber(0, localIsChannelOpener, localPaymentBasePoint, remotePaymentBasePoint)
    val obscured = decodeTxNumber(commitTx.txIn.head.sequence, commitTx.lockTime)
    obscured ^ blind
  }

  /**
   * This is a trick to split and encode a 48-bit txnumber into the sequence and locktime fields of a tx
   *
   * @param txnumber commitment number
   * @return (sequence, locktime)
   */
  def encodeTxNumber(txnumber: Long): (Long, Long) = {
    require(txnumber <= 0xffffffffffffL, "txnumber must be lesser than 48 bits long")
    (0x80000000L | (txnumber >> 24), (txnumber & 0xffffffL) | 0x20000000)
  }

  def decodeTxNumber(sequence: Long, locktime: Long): Long = ((sequence & 0xffffffL) << 24) + (locktime & 0xffffffL)

  private def getHtlcTxInputSequence(commitmentFormat: CommitmentFormat): Long = commitmentFormat match {
    case DefaultCommitmentFormat => 0 // htlc txs immediately spend the commit tx
    case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => 1 // htlc txs have a 1-block delay to allow CPFP carve-out on anchors
  }

  def makeCommitTxOutputs(localFundingPublicKey: PublicKey,
                          remoteFundingPublicKey: PublicKey,
                          commitmentKeys: CommitmentPublicKeys,
                          payCommitTxFees: Boolean,
                          dustLimit: Satoshi,
                          toSelfDelay: CltvExpiryDelta,
                          spec: CommitmentSpec,
                          commitmentFormat: CommitmentFormat): Seq[CommitmentOutput] = {
    val outputs = collection.mutable.ArrayBuffer.empty[CommitmentOutput]

    trimOfferedHtlcs(dustLimit, spec, commitmentFormat).foreach { htlc =>
      val fee = weight2fee(spec.htlcTxFeerate(commitmentFormat), commitmentFormat.htlcTimeoutWeight)
      val amountAfterFees = htlc.add.amountMsat.truncateToSatoshi - fee
      val redeemInfo = HtlcTimeoutTx.redeemInfo(commitmentKeys, htlc.add.paymentHash, commitmentFormat)
      val htlcDelayedRedeemInfo = HtlcDelayedTx.redeemInfo(commitmentKeys, toSelfDelay, commitmentFormat)
      outputs.append(OutHtlc(htlc, TxOut(htlc.add.amountMsat.truncateToSatoshi, redeemInfo.pubkeyScript), TxOut(amountAfterFees, htlcDelayedRedeemInfo.pubkeyScript)))
    }

    trimReceivedHtlcs(dustLimit, spec, commitmentFormat).foreach { htlc =>
      val fee = weight2fee(spec.htlcTxFeerate(commitmentFormat), commitmentFormat.htlcSuccessWeight)
      val amountAfterFees = htlc.add.amountMsat.truncateToSatoshi - fee
      val redeemInfo = HtlcSuccessTx.redeemInfo(commitmentKeys, htlc.add.paymentHash, htlc.add.cltvExpiry, commitmentFormat)
      val htlcDelayedRedeemInfo = HtlcDelayedTx.redeemInfo(commitmentKeys, toSelfDelay, commitmentFormat)
      outputs.append(InHtlc(htlc, TxOut(htlc.add.amountMsat.truncateToSatoshi, redeemInfo.pubkeyScript), TxOut(amountAfterFees, htlcDelayedRedeemInfo.pubkeyScript)))
    }

    val hasHtlcs = outputs.nonEmpty

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (payCommitTxFees) {
      (spec.toLocal.truncateToSatoshi - commitTxTotalCost(dustLimit, spec, commitmentFormat), spec.toRemote.truncateToSatoshi)
    } else {
      (spec.toLocal.truncateToSatoshi, spec.toRemote.truncateToSatoshi - commitTxTotalCost(dustLimit, spec, commitmentFormat))
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

    if (toLocalAmount >= dustLimit) {
      val redeemInfo = commitmentFormat match {
        case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat =>
          RedeemInfo.P2wsh(toLocalDelayed(commitmentKeys, toSelfDelay))
        case _: SimpleTaprootChannelCommitmentFormat =>
          val toLocalTree = Taproot.toLocalScriptTree(commitmentKeys, toSelfDelay)
          RedeemInfo.TaprootScriptPath(NUMS_POINT.xOnly, toLocalTree.scriptTree, toLocalTree.localDelayed.hash())
      }
      outputs.append(ToLocal(TxOut(toLocalAmount, redeemInfo.pubkeyScript)))
    }

    if (toRemoteAmount >= dustLimit) {
      val redeemInfo = commitmentFormat match {
        case DefaultCommitmentFormat =>
          RedeemInfo.P2wpkh(commitmentKeys.remotePaymentPublicKey)
        case _: AnchorOutputsCommitmentFormat =>
          RedeemInfo.P2wsh(toRemoteDelayed(commitmentKeys))
        case _: SimpleTaprootChannelCommitmentFormat =>
          val scripTree = Taproot.toRemoteScriptTree(commitmentKeys)
          RedeemInfo.TaprootScriptPath(NUMS_POINT.xOnly, scripTree, scripTree.hash())
      }
      outputs.append(ToRemote(TxOut(toRemoteAmount, redeemInfo.pubkeyScript)))
    }

    commitmentFormat match {
      case DefaultCommitmentFormat => ()
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat =>
        if (toLocalAmount >= dustLimit || hasHtlcs) {
          val redeemInfo = ClaimLocalAnchorTx.redeemInfo(localFundingPublicKey, commitmentKeys, commitmentFormat)
          outputs.append(ToLocalAnchor(TxOut(AnchorOutputsCommitmentFormat.anchorAmount, redeemInfo.pubkeyScript)))
        }
        if (toRemoteAmount >= dustLimit || hasHtlcs) {
          val redeemInfo = ClaimRemoteAnchorTx.redeemInfo(remoteFundingPublicKey, commitmentKeys, commitmentFormat)
          outputs.append(ToRemoteAnchor(TxOut(AnchorOutputsCommitmentFormat.anchorAmount, redeemInfo.pubkeyScript)))
        }
    }

    outputs.sortWith(CommitmentOutput.isLessThan).toSeq
  }

  def makeCommitTx(commitTxInput: InputInfo,
                   commitTxNumber: Long,
                   localPaymentBasePoint: PublicKey,
                   remotePaymentBasePoint: PublicKey,
                   localIsChannelOpener: Boolean,
                   outputs: Seq[CommitmentOutput]): CommitTx = {
    val txNumber = obscuredCommitTxNumber(commitTxNumber, localIsChannelOpener, localPaymentBasePoint, remotePaymentBasePoint)
    val (sequence, lockTime) = encodeTxNumber(txNumber)
    val tx = Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = sequence) :: Nil,
      txOut = outputs.map(_.txOut),
      lockTime = lockTime
    )
    CommitTx(commitTxInput, tx)
  }

  def makeHtlcTxs(commitTx: Transaction,
                  outputs: Seq[CommitmentOutput],
                  commitmentFormat: CommitmentFormat): Seq[UnsignedHtlcTx] = {
    outputs.zipWithIndex.collect {
      case (o: OutHtlc, outputIndex) => HtlcTimeoutTx.createUnsignedTx(commitTx, o, outputIndex, commitmentFormat)
      case (i: InHtlc, outputIndex) => HtlcSuccessTx.createUnsignedTx(commitTx, i, outputIndex, commitmentFormat)
    }
  }

  def makeFundingScript(localFundingKey: PublicKey, remoteFundingKey: PublicKey, commitmentFormat: CommitmentFormat): RedeemInfo = {
    commitmentFormat match {
      case _: SegwitV0CommitmentFormat => RedeemInfo.P2wsh(Script.write(multiSig2of2(localFundingKey, remoteFundingKey)))
      case _: SimpleTaprootChannelCommitmentFormat => RedeemInfo.TaprootKeyPath(Taproot.musig2Aggregate(localFundingKey, remoteFundingKey), None)
    }
  }

  def makeFundingInputInfo(fundingTxId: TxId, fundingOutputIndex: Int, fundingAmount: Satoshi, localFundingKey: PublicKey, remoteFundingKey: PublicKey, commitmentFormat: CommitmentFormat): InputInfo = {
    val redeemInfo = makeFundingScript(localFundingKey, remoteFundingKey, commitmentFormat)
    val fundingTxOut = TxOut(fundingAmount, redeemInfo.pubkeyScript)
    InputInfo(OutPoint(fundingTxId, fundingOutputIndex), fundingTxOut)
  }

  // @formatter:off
  /** We always create multiple versions of each closing transaction, where fees are either paid by us or by our peer. */
  sealed trait SimpleClosingTxFee
  object SimpleClosingTxFee {
    case class PaidByUs(fee: Satoshi) extends SimpleClosingTxFee
    case class PaidByThem(fee: Satoshi) extends SimpleClosingTxFee
  }
  // @formatter:on

  /**
   * When sending [[fr.acinq.eclair.wire.protocol.ClosingComplete]], we use a different nonce for each closing transaction we create.
   * We generate nonces for all variants of the closing transaction for simplicity, even though we never use them all.
   */
  case class CloserNonces(localAndRemote: LocalNonce, localOnly: LocalNonce, remoteOnly: LocalNonce)

  object CloserNonces {
    /** Generate a set of random signing nonces for our closing transactions. */
    def generate(localFundingKey: PublicKey, remoteFundingKey: PublicKey, fundingTxId: TxId): CloserNonces = CloserNonces(
      NonceGenerator.signingNonce(localFundingKey, remoteFundingKey, fundingTxId),
      NonceGenerator.signingNonce(localFundingKey, remoteFundingKey, fundingTxId),
      NonceGenerator.signingNonce(localFundingKey, remoteFundingKey, fundingTxId),
    )
  }

  /** Each closing attempt can result in multiple potential closing transactions, depending on which outputs are included. */
  case class ClosingTxs(localAndRemote_opt: Option[ClosingTx], localOnly_opt: Option[ClosingTx], remoteOnly_opt: Option[ClosingTx]) {
    /** Preferred closing transaction for this closing attempt. */
    val preferred_opt: Option[ClosingTx] = localAndRemote_opt.orElse(localOnly_opt).orElse(remoteOnly_opt)
    val all: Seq[ClosingTx] = Seq(localAndRemote_opt, localOnly_opt, remoteOnly_opt).flatten

    override def toString: String = s"localAndRemote=${localAndRemote_opt.map(_.tx.toString()).getOrElse("n/a")}, localOnly=${localOnly_opt.map(_.tx.toString()).getOrElse("n/a")}, remoteOnly=${remoteOnly_opt.map(_.tx.toString()).getOrElse("n/a")}"
  }

  def makeSimpleClosingTxs(input: InputInfo, spec: CommitmentSpec, fee: SimpleClosingTxFee, lockTime: Long, localScriptPubKey: ByteVector, remoteScriptPubKey: ByteVector): ClosingTxs = {
    require(spec.htlcs.isEmpty, "there shouldn't be any pending htlcs")

    val txNoOutput = Transaction(2, Seq(TxIn(input.outPoint, ByteVector.empty, sequence = 0xFFFFFFFDL)), Nil, lockTime)

    // We compute the remaining balance for each side after paying the closing fees.
    // This lets us decide whether outputs can be included in the closing transaction or not.
    val (toLocalAmount, toRemoteAmount) = fee match {
      case SimpleClosingTxFee.PaidByUs(fee) => (spec.toLocal.truncateToSatoshi - fee, spec.toRemote.truncateToSatoshi)
      case SimpleClosingTxFee.PaidByThem(fee) => (spec.toLocal.truncateToSatoshi, spec.toRemote.truncateToSatoshi - fee)
    }

    // An OP_RETURN script may be provided, but only when burning all of the peer's balance to fees, otherwise bitcoind won't accept it.
    val toLocalOutput_opt = if (toLocalAmount >= dustLimit(localScriptPubKey)) {
      val amount = if (isOpReturn(localScriptPubKey)) 0.sat else toLocalAmount
      Some(TxOut(amount, localScriptPubKey))
    } else {
      None
    }
    val toRemoteOutput_opt = if (toRemoteAmount >= dustLimit(remoteScriptPubKey)) {
      val amount = if (isOpReturn(remoteScriptPubKey)) 0.sat else toRemoteAmount
      Some(TxOut(amount, remoteScriptPubKey))
    } else {
      None
    }

    // We may create multiple closing transactions based on which outputs may be included.
    (toLocalOutput_opt, toRemoteOutput_opt) match {
      case (Some(toLocalOutput), Some(toRemoteOutput)) =>
        val txLocalAndRemote = LexicographicalOrdering.sort(txNoOutput.copy(txOut = Seq(toLocalOutput, toRemoteOutput)))
        ClosingTxs(
          localAndRemote_opt = Some(ClosingTx(input, txLocalAndRemote, findPubKeyScriptIndex(txLocalAndRemote, localScriptPubKey).map(_.toLong).toOption)),
          // We also provide a version of the transaction without the remote output, which they may want to omit if not economical to spend.
          localOnly_opt = Some(ClosingTx(input, txNoOutput.copy(txOut = Seq(toLocalOutput)), Some(0))),
          remoteOnly_opt = None
        )
      case (Some(toLocalOutput), None) =>
        ClosingTxs(
          localAndRemote_opt = None,
          localOnly_opt = Some(ClosingTx(input, txNoOutput.copy(txOut = Seq(toLocalOutput)), Some(0))),
          remoteOnly_opt = None
        )
      case (None, Some(toRemoteOutput)) =>
        ClosingTxs(
          localAndRemote_opt = None,
          localOnly_opt = None,
          remoteOnly_opt = Some(ClosingTx(input, txNoOutput.copy(txOut = Seq(toRemoteOutput)), None))
        )
      case (None, None) => ClosingTxs(None, None, None)
    }
  }

  /** We skip creating transactions spending commitment outputs when the remaining amount is below dust. */
  private def skipTxIfBelowDust[T <: TransactionWithInputInfo](txInfo: T, dustLimit: Satoshi): Either[TxGenerationSkipped, T] = {
    txInfo.tx.txOut.headOption match {
      case Some(txOut) if txOut.amount < dustLimit => Left(AmountBelowDustLimit)
      case _ => Right(txInfo)
    }
  }

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: ByteVector): Either[TxGenerationSkipped, Int] = {
    val outputIndex = tx.txOut.indexWhere(_.publicKeyScript == pubkeyScript)
    if (outputIndex >= 0) {
      Right(outputIndex)
    } else {
      Left(OutputNotFound)
    }
  }

  /** Update the on-chain fee paid by this transaction by lowering its output amount, if possible. */
  def updateFee(txInfo: ForceCloseTransaction, fee: Satoshi, dustLimit: Satoshi): Either[TxGenerationSkipped, ForceCloseTransaction] = {
    if (txInfo.amountIn < fee + dustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val updatedTx = txInfo.tx.copy(txOut = txInfo.tx.txOut.headOption.map(_.copy(amount = txInfo.amountIn - fee)).toSeq)
      txInfo match {
        case txInfo: ClaimLocalDelayedOutputTx => Right(txInfo.copy(tx = updatedTx))
        case txInfo: ClaimRemoteDelayedOutputTx => Right(txInfo.copy(tx = updatedTx))
        case txInfo: ClaimP2WPKHOutputTx => Right(txInfo.copy(tx = updatedTx))
        // Anchor transaction don't have any output: wallet inputs must be used to pay fees.
        case txInfo: ClaimAnchorTx => Left(CannotUpdateFee(txInfo))
        // HTLC transactions are pre-signed, we can't update their fee by lowering the output amount.
        case txInfo: SignedHtlcTx => Left(CannotUpdateFee(txInfo))
        case txInfo: ClaimHtlcSuccessTx => Right(txInfo.copy(tx = updatedTx))
        case txInfo: ClaimHtlcTimeoutTx => Right(txInfo.copy(tx = updatedTx))
        case txInfo: HtlcDelayedTx => Right(txInfo.copy(tx = updatedTx))
        case txInfo: MainPenaltyTx => Right(txInfo.copy(tx = updatedTx))
        case txInfo: HtlcPenaltyTx => Right(txInfo.copy(tx = updatedTx))
        case txInfo: ClaimHtlcDelayedOutputPenaltyTx => Right(txInfo.copy(tx = updatedTx))
      }
    }
  }

  /**
   * This default sig takes 72B when encoded in DER (incl. 1B for the trailing sig hash), it is used for fee estimation
   * It is 72 bytes because our signatures are normalized (low-s) and will take up 72 bytes at most in DER format
   */
  val PlaceHolderSig: ByteVector64 = ByteVector64(ByteVector.fill(64)(0xaa))
  assert(der(PlaceHolderSig).size == 72)
}
