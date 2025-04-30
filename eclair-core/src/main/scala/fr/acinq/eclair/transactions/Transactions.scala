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
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey, XonlyPublicKey}
import fr.acinq.bitcoin.scalacompat.Script._
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.bitcoin.{ScriptFlags, ScriptTree}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.{ConfirmationTarget, FeeratePerKw}
import fr.acinq.eclair.crypto.keymanager.{CommitmentPublicKeys, LocalCommitmentKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.transactions.CommitmentOutput._
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

  sealed trait CommitmentFormat {
    // @formatter:off
    def commitWeight: Int
    def htlcOutputWeight: Int
    def htlcTimeoutWeight: Int
    def htlcSuccessWeight: Int
    def htlcTimeoutInputWeight: Int
    def htlcSuccessInputWeight: Int
    // @formatter:on
  }

  sealed trait SegwitV0CommitmentFormat extends CommitmentFormat

  /**
   * Commitment format as defined in the v1.0 specification (https://github.com/lightningnetwork/lightning-rfc/tree/v1.0).
   */
  case object DefaultCommitmentFormat extends SegwitV0CommitmentFormat {
    override val commitWeight = 724
    override val htlcOutputWeight = 172
    override val htlcTimeoutWeight = 663
    override val htlcSuccessWeight = 703
    override val htlcTimeoutInputWeight = 449
    override val htlcSuccessInputWeight = 488
  }

  /**
   * Commitment format that adds anchor outputs to the commitment transaction and uses custom sighash flags for HTLC
   * transactions to allow unilateral fee bumping (https://github.com/lightningnetwork/lightning-rfc/pull/688).
   */
  sealed trait AnchorOutputsCommitmentFormat extends SegwitV0CommitmentFormat {
    override val commitWeight = 1124
    override val htlcOutputWeight = 172
    override val htlcTimeoutWeight = 666
    override val htlcSuccessWeight = 706
    override val htlcTimeoutInputWeight = 452
    override val htlcSuccessInputWeight = 491
  }

  object AnchorOutputsCommitmentFormat {
    val anchorAmount: Satoshi = Satoshi(330)
  }

  /**
   * This commitment format may be unsafe where you're fundee, as it exposes you to a fee inflating attack.
   * Don't use this commitment format unless you know what you're doing!
   * See https://lists.linuxfoundation.org/pipermail/lightning-dev/2020-September/002796.html for details.
   */
  case object UnsafeLegacyAnchorOutputsCommitmentFormat extends AnchorOutputsCommitmentFormat

  /**
   * This commitment format removes the fees from the pre-signed 2nd-stage htlc transactions to fix the fee inflating
   * attack against [[UnsafeLegacyAnchorOutputsCommitmentFormat]].
   */
  case object ZeroFeeHtlcTxAnchorOutputsCommitmentFormat extends AnchorOutputsCommitmentFormat

  // @formatter:off
  // TODO: we're currently keeping the now unused redeemScript to avoid a painful codec update. When creating v5 codecs
  //  (for taproot channels), don't forget to remove this field from the InputInfo class!
  case class InputInfo(outPoint: OutPoint, txOut: TxOut, unusedRedeemScript: ByteVector)

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
      require(Option(scriptTree.findScript(KotlinUtils.scala2kmp(leafHash))).nonEmpty, "script tree must contain the provided leaf")
      val redeemScript: ByteVector = KotlinUtils.kmp2scala(scriptTree.findScript(KotlinUtils.scala2kmp(leafHash)).getScript)
      override val pubkeyScript: ByteVector = Script.write(Script.pay2tr(internalKey, Some(scriptTree)))
    }
  }

  /** Owner of a given transaction (local/remote). */
  sealed trait TxOwner
  object TxOwner {
    case object Local extends TxOwner
    case object Remote extends TxOwner
  }
  // @formatter:on

  sealed trait TransactionWithInputInfo {
    // @formatter:off
    def input: InputInfo
    def desc: String
    def tx: Transaction
    def amountIn: Satoshi = input.txOut.amount
    def fee: Satoshi = amountIn - tx.txOut.map(_.amount).sum
    /** Sighash flags to use when signing the transaction. */
    def sighash(txOwner: TxOwner, commitmentFormat: CommitmentFormat): Int = commitmentFormat match {
      case _: SegwitV0CommitmentFormat => SIGHASH_ALL
    }
    // @formatter:on

    /** Sign a transaction spending a channel's segwit v0 2-of-2 funding output. */
    protected def signChannelSpendingTx(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat, extraUtxos: Map[OutPoint, TxOut]): ByteVector64 = {
      val redeemScript = Script.write(Scripts.multiSig2of2(localFundingKey.publicKey, remoteFundingPubkey))
      sign(localFundingKey, sighash(txOwner, commitmentFormat), RedeemInfo.P2wsh(redeemScript), extraUtxos)
    }

    protected def sign(key: PrivateKey, sighash: Int, redeemInfo: RedeemInfo, extraUtxos: Map[OutPoint, TxOut]): ByteVector64 = {
      val inputsMap = extraUtxos + (input.outPoint -> input.txOut)
      tx.txIn.foreach(txIn => {
        // Note that using a require here is dangerous, because callers don't except this function to throw.
        // But we want to ensure that we're correctly providing input details, otherwise our signature will silently be
        // invalid when using taproot. We verify this in all cases, even when using segwit v0, to ensure that we have as
        // many tests as possible that exercise this codepath.
        require(inputsMap.contains(txIn.outPoint), s"cannot sign $desc with txId=${tx.txid}: missing input details for ${txIn.outPoint}")
      })
      // NB: the tx may have multiple inputs, we will only sign the one provided in our input. Bear in mind that the
      // signature will be invalidated if other inputs are added *afterwards* and sighash was SIGHASH_ALL.
      val inputIndex = tx.txIn.indexWhere(_.outPoint == input.outPoint)
      redeemInfo match {
        case redeemInfo: RedeemInfo.SegwitV0 =>
          val sigDER = Transaction.signInput(tx, inputIndex, redeemInfo.redeemScript, sighash, input.txOut.amount, SIGVERSION_WITNESS_V0, key)
          Crypto.der2compact(sigDER)
        case _: RedeemInfo.TaprootKeyPath => ???
        case _: RedeemInfo.TaprootScriptPath => ???
      }
    }

    protected def checkSig(sig: ByteVector64, publicKey: PublicKey, sighash: Int, redeemInfo: RedeemInfo): Boolean = {
      val inputIndex = tx.txIn.indexWhere(_.outPoint == input.outPoint)
      if (inputIndex >= 0) {
        redeemInfo match {
          case redeemInfo: RedeemInfo.SegwitV0 =>
            val data = Transaction.hashForSigning(tx, inputIndex, redeemInfo.redeemScript, sighash, input.txOut.amount, SIGVERSION_WITNESS_V0)
            Crypto.verifySignature(data, sig, publicKey)
          case _: RedeemInfo.TaprootKeyPath => ???
          case _: RedeemInfo.TaprootScriptPath => ???
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

  sealed trait ReplaceableTransactionWithInputInfo extends TransactionWithInputInfo

  case class SpliceTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override val desc: String = "splice-tx"

    def sign(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey, parentCommitmentFormat: CommitmentFormat, extraUtxos: Map[OutPoint, TxOut]): ByteVector64 = {
      signChannelSpendingTx(localFundingKey, remoteFundingPubkey, TxOwner.Local, parentCommitmentFormat, extraUtxos)
    }
  }

  /**
   * When *local* *current* [[CommitTx]] is published:
   *   - [[ClaimLocalDelayedOutputTx]] spends to-local output of [[CommitTx]] after a delay
   *   - When using anchor outputs, [[ClaimAnchorOutputTx]] spends to-local anchor of [[CommitTx]]
   *   - [[HtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
   *     - [[HtlcDelayedTx]] spends [[HtlcSuccessTx]] after a delay
   *   - [[HtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
   *     - [[HtlcDelayedTx]] spends [[HtlcTimeoutTx]] after a delay
   *
   * When *remote* *current* [[CommitTx]] is published:
   *   - When using the default commitment format, [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
   *   - When using anchor outputs, [[ClaimRemoteDelayedOutputTx]] spends to-local output of [[CommitTx]]
   *   - When using anchor outputs, [[ClaimAnchorOutputTx]] spends to-local anchor of [[CommitTx]]
   *   - [[ClaimHtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
   *   - [[ClaimHtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
   *
   * When *remote* *revoked* [[CommitTx]] is published:
   *   - When using the default commitment format, [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
   *   - When using anchor outputs, [[ClaimRemoteDelayedOutputTx]] spends to-local output of [[CommitTx]]
   *   - When using anchor outputs, [[ClaimAnchorOutputTx]] spends to-local anchor of [[CommitTx]]
   *   - [[MainPenaltyTx]] spends remote main output using the per-commitment secret
   *   - [[HtlcSuccessTx]] spends htlc-sent outputs of [[CommitTx]] for which they have the preimage (published by remote)
   *     - [[ClaimHtlcDelayedOutputPenaltyTx]] spends [[HtlcSuccessTx]] using the revocation secret (published by local)
   *   - [[HtlcTimeoutTx]] spends htlc-received outputs of [[CommitTx]] after a timeout (published by remote)
   *     - [[ClaimHtlcDelayedOutputPenaltyTx]] spends [[HtlcTimeoutTx]] using the revocation secret (published by local)
   *   - [[HtlcPenaltyTx]] spends competes with [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] for the same outputs (published by local)
   */
  case class CommitTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override val desc: String = "commit-tx"

    def sign(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): ByteVector64 = {
      signChannelSpendingTx(localFundingKey, remoteFundingPubkey, txOwner, commitmentFormat, extraUtxos = Map.empty)
    }

    def checkRemoteSig(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, remoteSig: ByteVector64, commitmentFormat: CommitmentFormat): Boolean = {
      val redeemScript = Script.write(Scripts.multiSig2of2(localFundingPubkey, remoteFundingPubkey))
      checkSig(remoteSig, remoteFundingPubkey, sighash(TxOwner.Local, commitmentFormat), RedeemInfo.P2wsh(redeemScript))
    }

    def addSigs(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): CommitTx = {
      val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  /**
   * It's important to note that htlc transactions with the default commitment format are not actually replaceable: only
   * anchor outputs htlc transactions are replaceable. We should have used different types for these different kinds of
   * htlc transactions, but we introduced that before implementing the replacement strategy.
   * Unfortunately, if we wanted to change that, we would have to update the codecs and implement a migration of channel
   * data, which isn't trivial, so we chose to temporarily live with that inconsistency (and have the transaction
   * replacement logic abort when non-anchor outputs htlc transactions are provided).
   * Ideally, we'd like to implement a dynamic commitment format upgrade mechanism and depreciate the pre-anchor outputs
   * format soon, which will get rid of this inconsistency.
   * The next time we introduce a new type of commitment, we should avoid repeating that mistake and define separate
   * types right from the start.
   */
  sealed trait HtlcTx extends ReplaceableTransactionWithInputInfo {
    // @formatter:off
    def htlcId: Long
    def paymentHash: ByteVector32
    def htlcExpiry: CltvExpiry
    // @formatter:on

    /** Create redeem information for this HTLC transaction, based on the commitment format used. */
    def redeemInfo(commitKeys: CommitmentPublicKeys, commitmentFormat: CommitmentFormat): RedeemInfo

    /** Sign an HTLC transaction spending our local commitment. */
    def sign(commitKeys: LocalCommitmentKeys, commitmentFormat: CommitmentFormat, extraUtxos: Map[OutPoint, TxOut]): ByteVector64 = {
      sign(commitKeys.ourHtlcKey, sighash(TxOwner.Local, commitmentFormat), redeemInfo(commitKeys.publicKeys, commitmentFormat), extraUtxos)
    }

    /** Sign an HTLC transaction for the remote commitment. */
    def sign(commitKeys: RemoteCommitmentKeys, commitmentFormat: CommitmentFormat): ByteVector64 = {
      sign(commitKeys.ourHtlcKey, sighash(TxOwner.Remote, commitmentFormat), redeemInfo(commitKeys.publicKeys, commitmentFormat), extraUtxos = Map.empty)
    }

    /** This is a function only used in tests to produce signatures with a different sighash. */
    def signWithInvalidSighash(commitKeys: RemoteCommitmentKeys, commitmentFormat: CommitmentFormat, sighash: Int): ByteVector64 = {
      sign(commitKeys.ourHtlcKey, sighash, redeemInfo(commitKeys.publicKeys, commitmentFormat), extraUtxos = Map.empty)
    }

    def checkRemoteSig(commitKeys: LocalCommitmentKeys, remoteSig: ByteVector64, commitmentFormat: CommitmentFormat): Boolean = {
      // The transaction was signed by our remote for us: from their point of view, we're a remote owner.
      val remoteSighash = sighash(TxOwner.Remote, commitmentFormat)
      checkSig(remoteSig, commitKeys.theirHtlcPublicKey, remoteSighash, redeemInfo(commitKeys.publicKeys, commitmentFormat))
    }

    override def sighash(txOwner: TxOwner, commitmentFormat: CommitmentFormat): Int = commitmentFormat match {
      case DefaultCommitmentFormat => SIGHASH_ALL
      case _: AnchorOutputsCommitmentFormat => txOwner match {
        case TxOwner.Local => SIGHASH_ALL
        case TxOwner.Remote => SIGHASH_SINGLE | SIGHASH_ANYONECANPAY
      }
    }
  }

  /** This transaction spends a received (incoming) HTLC from a local or remote commitment by revealing the payment preimage. */
  case class HtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, htlcExpiry: CltvExpiry) extends HtlcTx {
    override val desc: String = "htlc-success"

    override def redeemInfo(commitKeys: CommitmentPublicKeys, commitmentFormat: CommitmentFormat): RedeemInfo = commitmentFormat match {
      case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
        val redeemScript = Script.write(htlcReceived(commitKeys, paymentHash, htlcExpiry, commitmentFormat))
        RedeemInfo.P2wsh(redeemScript)
    }

    def addSigs(commitKeys: LocalCommitmentKeys, localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32, commitmentFormat: CommitmentFormat): HtlcSuccessTx = {
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(htlcReceived(commitKeys.publicKeys, paymentHash, htlcExpiry, commitmentFormat))
          witnessHtlcSuccess(localSig, remoteSig, paymentPreimage, redeemScript, commitmentFormat)
      }
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  object HtlcSuccessTx {
    def createUnsignedTx(keys: CommitmentPublicKeys,
                         commitTx: Transaction,
                         output: CommitmentOutputLink[InHtlc],
                         outputIndex: Int,
                         dustLimit: Satoshi,
                         toSelfDelay: CltvExpiryDelta,
                         feeratePerKw: FeeratePerKw,
                         commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcSuccessTx] = {
      val fee = weight2fee(feeratePerKw, commitmentFormat.htlcSuccessWeight)
      val htlc = output.commitmentOutput.incomingHtlc.add
      val amount = htlc.amountMsat.truncateToSatoshi - fee
      if (amount < dustLimit) {
        Left(AmountBelowDustLimit)
      } else {
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), ByteVector.empty)
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(amount, pay2wsh(toLocalDelayed(keys, toSelfDelay))) :: Nil,
          lockTime = 0
        )
        Right(HtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id, htlc.cltvExpiry))
      }
    }
  }

  /** This transaction spends an offered (outgoing) HTLC from a local or remote commitment after its expiry. */
  case class HtlcTimeoutTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, htlcExpiry: CltvExpiry) extends HtlcTx {
    override val desc: String = "htlc-timeout"

    override def redeemInfo(commitKeys: CommitmentPublicKeys, commitmentFormat: CommitmentFormat): RedeemInfo = commitmentFormat match {
      case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
        val redeemScript = Script.write(htlcOffered(commitKeys, paymentHash, commitmentFormat))
        RedeemInfo.P2wsh(redeemScript)
    }

    def addSigs(commitKeys: LocalCommitmentKeys, localSig: ByteVector64, remoteSig: ByteVector64, commitmentFormat: CommitmentFormat): HtlcTimeoutTx = {
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(htlcOffered(commitKeys.publicKeys, paymentHash, commitmentFormat))
          witnessHtlcTimeout(localSig, remoteSig, redeemScript, commitmentFormat)
      }
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  object HtlcTimeoutTx {
    def createUnsignedTx(keys: CommitmentPublicKeys,
                         commitTx: Transaction,
                         output: CommitmentOutputLink[OutHtlc],
                         outputIndex: Int,
                         dustLimit: Satoshi,
                         toSelfDelay: CltvExpiryDelta,
                         feeratePerKw: FeeratePerKw,
                         commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcTimeoutTx] = {
      val fee = weight2fee(feeratePerKw, commitmentFormat.htlcTimeoutWeight)
      val htlc = output.commitmentOutput.outgoingHtlc.add
      val amount = htlc.amountMsat.truncateToSatoshi - fee
      if (amount < dustLimit) {
        Left(AmountBelowDustLimit)
      } else {
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), ByteVector.empty)
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(amount, pay2wsh(toLocalDelayed(keys, toSelfDelay))) :: Nil,
          lockTime = htlc.cltvExpiry.toLong
        )
        Right(HtlcTimeoutTx(input, tx, htlc.paymentHash, htlc.id, htlc.cltvExpiry))
      }
    }
  }

  /** This transaction spends the output of a local [[HtlcTx]] after a to_self_delay relative delay. */
  case class HtlcDelayedTx(input: InputInfo, tx: Transaction, toLocalDelay: CltvExpiryDelta) extends TransactionWithInputInfo {
    override val desc: String = "htlc-delayed"

    def sign(commitKeys: LocalCommitmentKeys, commitmentFormat: CommitmentFormat): HtlcDelayedTx = {
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toLocalDelay))
          val sig = sign(commitKeys.ourDelayedPaymentKey, sighash(TxOwner.Local, commitmentFormat), RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          witnessToLocalDelayedAfterDelay(sig, redeemScript)
      }
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  object HtlcDelayedTx {
    def createSignedTx(commitKeys: LocalCommitmentKeys, htlcTx: Transaction, localDustLimit: Satoshi, toLocalDelay: CltvExpiryDelta, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcDelayedTx] = {
      val redeemInfo = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toLocalDelay))
          RedeemInfo.P2wsh(redeemScript)
      }
      findPubKeyScriptIndex(htlcTx, redeemInfo.pubkeyScript) match {
        case Left(skip) => Left(skip)
        case Right(outputIndex) =>
          val input = InputInfo(OutPoint(htlcTx, outputIndex), htlcTx.txOut(outputIndex), ByteVector.empty)
          val txWithoutFees = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toInt) :: Nil,
            txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
            lockTime = 0
          )
          val signedTx = HtlcDelayedTx(input, txWithoutFees, toLocalDelay).sign(commitKeys, commitmentFormat)
          skipTxIfBelowDust(signedTx, feeratePerKw, localDustLimit).map { amount =>
            val tx = txWithoutFees.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
            HtlcDelayedTx(input, tx, toLocalDelay).sign(commitKeys, commitmentFormat)
          }
      }
    }
  }

  sealed trait ClaimHtlcTx extends ReplaceableTransactionWithInputInfo {
    // @formatter:off
    def htlcId: Long
    def paymentHash: ByteVector32
    def htlcExpiry: CltvExpiry
    // @formatter:on
  }

  /** This transaction spends an HTLC we received by revealing the payment preimage, from the remote commitment. */
  case class ClaimHtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, htlcExpiry: CltvExpiry) extends ClaimHtlcTx {
    override val desc: String = "claim-htlc-success"

    def sign(commitKeys: RemoteCommitmentKeys, paymentPreimage: ByteVector32, commitmentFormat: CommitmentFormat): ClaimHtlcSuccessTx = {
      // Note that in/out HTLCs are inverted in the remote commitment: from their point of view it's an offered (outgoing) HTLC.
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(htlcOffered(commitKeys.publicKeys, paymentHash, commitmentFormat))
          val sig = sign(commitKeys.ourHtlcKey, sighash(TxOwner.Local, commitmentFormat), RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          witnessClaimHtlcSuccessFromCommitTx(sig, paymentPreimage, redeemScript)
      }
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  object ClaimHtlcSuccessTx {
    /**
     * Find the output of the commitment transaction matching this HTLC.
     * Note that we match on a specific HTLC, because we may have multiple HTLCs with the same payment_hash, expiry
     * and amount and thus the same pubkeyScript, and we must make sure we claim them all.
     */
    def findInput(commitTx: Transaction, outputs: CommitmentOutputs, htlc: UpdateAddHtlc): Option[InputInfo] = {
      outputs.zipWithIndex.collectFirst {
        case (CommitmentOutputLink(_, OutHtlc(OutgoingHtlc(outgoingHtlc))), outputIndex) if outgoingHtlc.id == htlc.id =>
          InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), ByteVector.empty)
      }
    }

    def createSignedTx(commitKeys: RemoteCommitmentKeys,
                       commitTx: Transaction,
                       dustLimit: Satoshi,
                       outputs: CommitmentOutputs,
                       localFinalScriptPubKey: ByteVector,
                       htlc: UpdateAddHtlc,
                       preimage: ByteVector32,
                       feeratePerKw: FeeratePerKw,
                       commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimHtlcSuccessTx] = {
      findInput(commitTx, outputs, htlc) match {
        case Some(input) =>
          val txWithoutFees = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
            txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
            lockTime = 0
          )
          val signedTx = ClaimHtlcSuccessTx(input, txWithoutFees, htlc.paymentHash, htlc.id, htlc.cltvExpiry).sign(commitKeys, preimage, commitmentFormat)
          skipTxIfBelowDust(signedTx, feeratePerKw, dustLimit).map { amount =>
            val tx = txWithoutFees.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
            ClaimHtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id, htlc.cltvExpiry).sign(commitKeys, preimage, commitmentFormat)
          }
        case None => Left(OutputNotFound)
      }
    }
  }

  /** This transaction spends an HTLC we sent after its expiry, from the remote commitment. */
  case class ClaimHtlcTimeoutTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, htlcExpiry: CltvExpiry) extends ClaimHtlcTx {
    override val desc: String = "claim-htlc-timeout"

    def sign(commitKeys: RemoteCommitmentKeys, commitmentFormat: CommitmentFormat): ClaimHtlcTimeoutTx = {
      // Note that in/out HTLCs are inverted in the remote commitment: from their point of view it's a received (incoming) HTLC.
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(htlcReceived(commitKeys.publicKeys, paymentHash, htlcExpiry, commitmentFormat))
          val sig = sign(commitKeys.ourHtlcKey, sighash(TxOwner.Local, commitmentFormat), RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          witnessClaimHtlcTimeoutFromCommitTx(sig, redeemScript)
      }
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  object ClaimHtlcTimeoutTx {
    def createSignedTx(commitKeys: RemoteCommitmentKeys,
                       commitTx: Transaction,
                       dustLimit: Satoshi,
                       outputs: CommitmentOutputs,
                       localFinalScriptPubKey: ByteVector,
                       htlc: UpdateAddHtlc,
                       feeratePerKw: FeeratePerKw,
                       commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimHtlcTimeoutTx] = {
      outputs.zipWithIndex.collectFirst {
        case (CommitmentOutputLink(_, InHtlc(IncomingHtlc(incomingHtlc))), outIndex) if incomingHtlc.id == htlc.id => outIndex
      } match {
        case Some(outputIndex) =>
          val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), ByteVector.empty)
          val txWithoutFees = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
            txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
            lockTime = htlc.cltvExpiry.toLong
          )
          val signedTx = ClaimHtlcTimeoutTx(input, txWithoutFees, htlc.paymentHash, htlc.id, htlc.cltvExpiry).sign(commitKeys, commitmentFormat)
          skipTxIfBelowDust(signedTx, feeratePerKw, dustLimit).map { amount =>
            val tx = txWithoutFees.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
            ClaimHtlcTimeoutTx(input, tx, htlc.paymentHash, htlc.id, htlc.cltvExpiry).sign(commitKeys, commitmentFormat)
          }
        case None => Left(OutputNotFound)
      }
    }
  }

  /** This transaction claims our anchor output in either the local or remote commitment, to CPFP and get it confirmed. */
  case class ClaimAnchorOutputTx(input: InputInfo, tx: Transaction, confirmationTarget: ConfirmationTarget) extends ReplaceableTransactionWithInputInfo {
    override val desc: String = "local-anchor"

    def sign(fundingKey: PrivateKey, commitKeys: CommitmentPublicKeys, commitmentFormat: CommitmentFormat, extraUtxos: Map[OutPoint, TxOut]): ClaimAnchorOutputTx = {
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(anchor(fundingKey.publicKey))
          val sig = sign(fundingKey, sighash(TxOwner.Local, commitmentFormat), RedeemInfo.P2wsh(redeemScript), extraUtxos)
          witnessAnchor(sig, redeemScript)
      }
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  object ClaimAnchorOutputTx {
    def redeemInfo(fundingKey: PrivateKey, commitKeys: CommitmentPublicKeys, commitmentFormat: CommitmentFormat): RedeemInfo = {
      commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(anchor(fundingKey.publicKey))
          RedeemInfo.P2wsh(redeemScript)
      }
    }

    def createUnsignedTx(fundingKey: PrivateKey, commitKeys: CommitmentPublicKeys, commitTx: Transaction, confirmationTarget: ConfirmationTarget, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimAnchorOutputTx] = {
      val pubkeyScript = redeemInfo(fundingKey, commitKeys, commitmentFormat).pubkeyScript
      findPubKeyScriptIndex(commitTx, pubkeyScript).map { outputIndex =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), ByteVector.empty)
        val unsignedTx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0) :: Nil,
          txOut = Nil, // anchor is only used to bump fees, the output will be added later depending on available inputs
          lockTime = 0
        )
        ClaimAnchorOutputTx(input, unsignedTx, confirmationTarget)
      }
    }
  }

  sealed trait ClaimRemoteCommitMainOutputTx extends TransactionWithInputInfo

  /** This transaction claims our main balance from the remote commitment without any delay, when using the [[DefaultCommitmentFormat]]. */
  case class ClaimP2WPKHOutputTx(input: InputInfo, tx: Transaction) extends ClaimRemoteCommitMainOutputTx {
    override val desc: String = "remote-main"

    def sign(paymentKey: PrivateKey, commitmentFormat: CommitmentFormat): ClaimP2WPKHOutputTx = {
      val redeemInfo = RedeemInfo.P2wpkh(paymentKey.publicKey)
      val sig = sign(paymentKey, sighash(TxOwner.Local, commitmentFormat), redeemInfo, extraUtxos = Map.empty)
      val witness = Script.witnessPay2wpkh(paymentKey.publicKey, der(sig))
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  object ClaimP2WPKHOutputTx {
    def createSignedTx(commitKeys: RemoteCommitmentKeys, commitTx: Transaction, localDustLimit: Satoshi, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimP2WPKHOutputTx] = {
      val redeemInfo = RedeemInfo.P2wpkh(commitKeys.ourPaymentPublicKey)
      findPubKeyScriptIndex(commitTx, redeemInfo.pubkeyScript) match {
        case Left(skip) => Left(skip)
        case Right(outputIndex) =>
          commitKeys.ourPaymentKey match {
            case Left(_) => Left(OutputAlreadyInWallet)
            case Right(paymentKey) =>
              val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), ByteVector.empty)
              val txWithoutFees = Transaction(
                version = 2,
                txIn = TxIn(input.outPoint, ByteVector.empty, 0) :: Nil,
                txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
                lockTime = 0
              )
              val signedTx = ClaimP2WPKHOutputTx(input, txWithoutFees).sign(paymentKey, commitmentFormat)
              skipTxIfBelowDust(signedTx, feeratePerKw, localDustLimit).map { amount =>
                val tx = txWithoutFees.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
                ClaimP2WPKHOutputTx(input, tx).sign(paymentKey, commitmentFormat)
              }
          }
      }
    }
  }

  /** This transaction spends our main balance from the remote commitment with a 1-block relative delay. */
  case class ClaimRemoteDelayedOutputTx(input: InputInfo, tx: Transaction) extends ClaimRemoteCommitMainOutputTx {
    override val desc: String = "remote-main-delayed"

    def sign(commitKeys: RemoteCommitmentKeys, commitmentFormat: CommitmentFormat): ClaimRemoteDelayedOutputTx = {
      commitKeys.ourPaymentKey match {
        case Left(_) => this
        case Right(priv) =>
          val witness = commitmentFormat match {
            case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
              val redeemScript = Script.write(toRemoteDelayed(commitKeys.publicKeys))
              val sig = sign(priv, sighash(TxOwner.Local, commitmentFormat), RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
              witnessClaimToRemoteDelayedFromCommitTx(sig, redeemScript)
          }
          copy(tx = tx.updateWitness(0, witness))
      }
    }
  }

  object ClaimRemoteDelayedOutputTx {
    def createSignedTx(commitKeys: RemoteCommitmentKeys, commitTx: Transaction, localDustLimit: Satoshi, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimRemoteDelayedOutputTx] = {
      val redeemInfo = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toRemoteDelayed(commitKeys.publicKeys))
          RedeemInfo.P2wsh(redeemScript)
      }
      findPubKeyScriptIndex(commitTx, redeemInfo.pubkeyScript) match {
        case Left(skip) => Left(skip)
        case Right(outputIndex) =>
          val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), ByteVector.empty)
          val txWithoutFees = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, 1) :: Nil,
            txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
            lockTime = 0
          )
          val signedTx = ClaimRemoteDelayedOutputTx(input, txWithoutFees).sign(commitKeys, commitmentFormat)
          skipTxIfBelowDust(signedTx, feeratePerKw, localDustLimit).map { amount =>
            val tx = txWithoutFees.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
            ClaimRemoteDelayedOutputTx(input, tx).sign(commitKeys, commitmentFormat)
          }
      }
    }
  }

  /** This transaction spends our main balance from our commitment after a to_self_delay relative delay. */
  case class ClaimLocalDelayedOutputTx(input: InputInfo, tx: Transaction, toLocalDelay: CltvExpiryDelta) extends TransactionWithInputInfo {
    override val desc: String = "local-main-delayed"

    def sign(commitKeys: LocalCommitmentKeys, commitmentFormat: CommitmentFormat): ClaimLocalDelayedOutputTx = {
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toLocalDelay))
          val sig = sign(commitKeys.ourDelayedPaymentKey, sighash(TxOwner.Local, commitmentFormat), RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          witnessToLocalDelayedAfterDelay(sig, redeemScript)
      }
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  object ClaimLocalDelayedOutputTx {
    def createSignedTx(commitKeys: LocalCommitmentKeys, commitTx: Transaction, localDustLimit: Satoshi, toLocalDelay: CltvExpiryDelta, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimLocalDelayedOutputTx] = {
      val redeemInfo = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toLocalDelay))
          RedeemInfo.P2wsh(redeemScript)
      }
      findPubKeyScriptIndex(commitTx, redeemInfo.pubkeyScript) match {
        case Left(skip) => Left(skip)
        case Right(outputIndex) =>
          val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), ByteVector.empty)
          val txWithoutFees = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toInt) :: Nil,
            txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
            lockTime = 0
          )
          val signedTx = ClaimLocalDelayedOutputTx(input, txWithoutFees, toLocalDelay).sign(commitKeys, commitmentFormat)
          skipTxIfBelowDust(signedTx, feeratePerKw, localDustLimit).map { amount =>
            val tx = txWithoutFees.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
            ClaimLocalDelayedOutputTx(input, tx, toLocalDelay).sign(commitKeys, commitmentFormat)
          }
      }
    }
  }

  /** This transaction spends the remote main balance from one of their revoked commitments. */
  case class MainPenaltyTx(input: InputInfo, tx: Transaction, toRemoteDelay: CltvExpiryDelta) extends TransactionWithInputInfo {
    override val desc: String = "main-penalty"

    def sign(commitKeys: RemoteCommitmentKeys, revocationKey: PrivateKey, commitmentFormat: CommitmentFormat): MainPenaltyTx = {
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toRemoteDelay))
          val sig = sign(revocationKey, sighash(TxOwner.Local, commitmentFormat), RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          Scripts.witnessToLocalDelayedWithRevocationSig(sig, redeemScript)
      }
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  object MainPenaltyTx {
    def createSignedTx(commitKeys: RemoteCommitmentKeys, revocationKey: PrivateKey, commitTx: Transaction, localDustLimit: Satoshi, localFinalScriptPubKey: ByteVector, toRemoteDelay: CltvExpiryDelta, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, MainPenaltyTx] = {
      val redeemInfo = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toRemoteDelay))
          RedeemInfo.P2wsh(redeemScript)
      }
      findPubKeyScriptIndex(commitTx, redeemInfo.pubkeyScript) match {
        case Left(skip) => Left(skip)
        case Right(outputIndex) =>
          val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), ByteVector.empty)
          val unsignedTx = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
            txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
            lockTime = 0
          )
          val signedTx = MainPenaltyTx(input, unsignedTx, toRemoteDelay).sign(commitKeys, revocationKey, commitmentFormat)
          skipTxIfBelowDust(signedTx, feeratePerKw, localDustLimit).map { amount =>
            val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
            MainPenaltyTx(input, tx, toRemoteDelay).sign(commitKeys, revocationKey, commitmentFormat)
          }
      }
    }
  }

  /** This transaction spends an HTLC output from one of the remote revoked commitments. */
  case class HtlcPenaltyTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcExpiry: CltvExpiry) extends TransactionWithInputInfo {
    override val desc: String = "htlc-penalty"

    def sign(commitKeys: RemoteCommitmentKeys, revocationKey: PrivateKey, redeemInfo: RedeemInfo, commitmentFormat: CommitmentFormat): HtlcPenaltyTx = {
      val sig = sign(revocationKey, sighash(TxOwner.Local, commitmentFormat), redeemInfo, extraUtxos = Map.empty)
      val witness = redeemInfo match {
        case RedeemInfo.P2wpkh(_) => Script.witnessPay2wpkh(revocationKey.publicKey, der(sig))
        case RedeemInfo.P2wsh(redeemScript) => Scripts.witnessHtlcWithRevocationSig(commitKeys, sig, redeemScript)
        case _: RedeemInfo.TaprootKeyPath => ???
        case _: RedeemInfo.TaprootScriptPath => ???
      }
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  object HtlcPenaltyTx {
    def createSignedTxs(commitKeys: RemoteCommitmentKeys,
                        revocationKey: PrivateKey,
                        commitTx: Transaction,
                        htlcs: Seq[(ByteVector32, CltvExpiry)],
                        localDustLimit: Satoshi,
                        localFinalScriptPubKey: ByteVector,
                        feeratePerKw: FeeratePerKw,
                        commitmentFormat: CommitmentFormat): Seq[Either[TxGenerationSkipped, HtlcPenaltyTx]] = {
      // We create the output scripts for the corresponding HTLCs.
      val redeemInfos: Map[ByteVector, (RedeemInfo, ByteVector32, CltvExpiry)] = htlcs.flatMap {
        case (paymentHash, htlcExpiry) =>
          // We don't know if this was an incoming or outgoing HTLC, so we try both cases.
          commitmentFormat match {
            case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
              val offered = RedeemInfo.P2wsh(Script.write(htlcOffered(commitKeys.publicKeys, paymentHash, commitmentFormat)))
              val received = RedeemInfo.P2wsh(Script.write(htlcReceived(commitKeys.publicKeys, paymentHash, htlcExpiry, commitmentFormat)))
              Seq(
                offered.pubkeyScript -> (offered, paymentHash, htlcExpiry),
                received.pubkeyScript -> (received, paymentHash, htlcExpiry),
              )
          }
      }.toMap
      // We check every output of the commitment transaction, and create an HTLC-penalty transaction if it is an HTLC output.
      commitTx.txOut.zipWithIndex.collect {
        case (txOut, outputIndex) if redeemInfos.contains(txOut.publicKeyScript) =>
          val Some((redeemInfo, paymentHash, expiry)) = redeemInfos.get(txOut.publicKeyScript)
          createSignedTx(commitKeys, revocationKey, commitTx, outputIndex, paymentHash, expiry, redeemInfo, localDustLimit, localFinalScriptPubKey, feeratePerKw, commitmentFormat)
      }
    }

    private def createSignedTx(commitKeys: RemoteCommitmentKeys,
                               revocationKey: PrivateKey,
                               commitTx: Transaction,
                               htlcOutputIndex: Int,
                               paymentHash: ByteVector32,
                               htlcExpiry: CltvExpiry,
                               redeemInfo: RedeemInfo,
                               localDustLimit: Satoshi,
                               localFinalScriptPubKey: ByteVector,
                               feeratePerKw: FeeratePerKw,
                               commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcPenaltyTx] = {
      val input = InputInfo(OutPoint(commitTx, htlcOutputIndex), commitTx.txOut(htlcOutputIndex), ByteVector.empty)
      val unsignedTx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
        txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
        lockTime = 0
      )
      val signedTx = HtlcPenaltyTx(input, unsignedTx, paymentHash, htlcExpiry).sign(commitKeys, revocationKey, redeemInfo, commitmentFormat)
      skipTxIfBelowDust(signedTx, feeratePerKw, localDustLimit).map { amount =>
        val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
        HtlcPenaltyTx(input, tx, paymentHash, htlcExpiry).sign(commitKeys, revocationKey, redeemInfo, commitmentFormat)
      }
    }
  }

  /** This transaction spends a remote [[HtlcTx]] from one of their revoked commitments. */
  case class ClaimHtlcDelayedOutputPenaltyTx(input: InputInfo, tx: Transaction, toRemoteDelay: CltvExpiryDelta) extends TransactionWithInputInfo {
    override val desc: String = "htlc-delayed-penalty"

    def sign(commitKeys: RemoteCommitmentKeys, revocationKey: PrivateKey, commitmentFormat: CommitmentFormat): ClaimHtlcDelayedOutputPenaltyTx = {
      val witness = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toRemoteDelay))
          val sig = sign(revocationKey, sighash(TxOwner.Local, commitmentFormat), RedeemInfo.P2wsh(redeemScript), extraUtxos = Map.empty)
          Scripts.witnessToLocalDelayedWithRevocationSig(sig, redeemScript)
      }
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  object ClaimHtlcDelayedOutputPenaltyTx {
    def createSignedTxs(commitKeys: RemoteCommitmentKeys,
                        revocationKey: PrivateKey,
                        htlcTx: Transaction,
                        localDustLimit: Satoshi,
                        toRemoteDelay: CltvExpiryDelta,
                        localFinalScriptPubKey: ByteVector,
                        feeratePerKw: FeeratePerKw,
                        commitmentFormat: CommitmentFormat): Seq[Either[TxGenerationSkipped, ClaimHtlcDelayedOutputPenaltyTx]] = {
      val redeemInfo = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = Script.write(toLocalDelayed(commitKeys.publicKeys, toRemoteDelay))
          RedeemInfo.P2wsh(redeemScript)
      }
      // Note that we check *all* outputs of the tx, because it could spend a batch of HTLC outputs from the commit tx.
      htlcTx.txOut.zipWithIndex.collect {
        case (txOut, outputIndex) if txOut.publicKeyScript == redeemInfo.pubkeyScript =>
          val input = InputInfo(OutPoint(htlcTx, outputIndex), htlcTx.txOut(outputIndex), ByteVector.empty)
          val unsignedTx = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
            txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
            lockTime = 0
          )
          val signedTx = ClaimHtlcDelayedOutputPenaltyTx(input, unsignedTx, toRemoteDelay).sign(commitKeys, revocationKey, commitmentFormat)
          skipTxIfBelowDust(signedTx, feeratePerKw, localDustLimit).map { amount =>
            val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
            ClaimHtlcDelayedOutputPenaltyTx(input, tx, toRemoteDelay).sign(commitKeys, revocationKey, commitmentFormat)
          }
      }
    }
  }

  /** This transaction collaboratively spends the channel funding output. */
  case class ClosingTx(input: InputInfo, tx: Transaction, toLocalOutputIndex_opt: Option[Long]) extends TransactionWithInputInfo {
    override val desc: String = "closing"

    val toLocalOutput_opt: Option[TxOut] = toLocalOutputIndex_opt.map(i => tx.txOut(i.toInt))

    def sign(localFundingKey: PrivateKey, remoteFundingPubkey: PublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): ByteVector64 = {
      signChannelSpendingTx(localFundingKey, remoteFundingPubkey, txOwner, commitmentFormat, extraUtxos = Map.empty)
    }

    def addSigs(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): ClosingTx = {
      val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
      copy(tx = tx.updateWitness(0, witness))
    }
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

  // @formatter:off
  sealed trait TxGenerationSkipped
  case object OutputNotFound extends TxGenerationSkipped { override def toString = "output not found (probably trimmed)" }
  case object OutputAlreadyInWallet extends TxGenerationSkipped { override def toString = "output doesn't need to be claimed, it belongs to our bitcoin wallet (p2wpkh or p2tr)" }
  case object AmountBelowDustLimit extends TxGenerationSkipped { override def toString = "amount is below dust limit" }
  // @formatter:on

  /**
   * these values are specific to us (not defined in the specification) and used to estimate fees
   */
  val claimP2WPKHOutputWeight = 438
  val anchorInputWeight = 279
  // The smallest transaction that spends an anchor contains 2 inputs (the commit tx output and a wallet input to set the feerate)
  // and 1 output (change). If we're using P2WPKH wallet inputs/outputs with 72 bytes signatures, this results in a weight of 717.
  // We round it down to 700 to allow for some error margin (e.g. signatures smaller than 72 bytes).
  val claimAnchorOutputMinWeight = 700
  val claimHtlcSuccessWeight = 571
  val claimHtlcTimeoutWeight = 545

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
      case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat => dustLimit
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
      case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat => dustLimit
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
      case _: AnchorOutputsCommitmentFormat => AnchorOutputsCommitmentFormat.anchorAmount * 2
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
    case _: AnchorOutputsCommitmentFormat => 1 // htlc txs have a 1-block delay to allow CPFP carve-out on anchors
  }

  /**
   * Represent a link between a commitment spec item (to-local, to-remote, anchors, htlc) and the actual output in the commit tx
   *
   * @param output           transaction output
   * @param commitmentOutput commitment spec item this output is built from
   */
  case class CommitmentOutputLink[T <: CommitmentOutput](output: TxOut, commitmentOutput: T)

  /** Type alias for a collection of commitment output links */
  private type CommitmentOutputs = Seq[CommitmentOutputLink[CommitmentOutput]]

  private object CommitmentOutputLink {
    /**
     * We sort HTLC outputs according to BIP69 + CLTV as tie-breaker for offered HTLC, we do this only for the outgoing
     * HTLC because we must agree with the remote on the order of HTLC-Timeout transactions even for identical HTLC outputs.
     * See https://github.com/lightningnetwork/lightning-rfc/issues/448#issuecomment-432074187.
     */
    def sort(a: CommitmentOutputLink[CommitmentOutput], b: CommitmentOutputLink[CommitmentOutput]): Boolean = (a.commitmentOutput, b.commitmentOutput) match {
      case (OutHtlc(OutgoingHtlc(htlcA)), OutHtlc(OutgoingHtlc(htlcB))) if htlcA.paymentHash == htlcB.paymentHash && htlcA.amountMsat.truncateToSatoshi == htlcB.amountMsat.truncateToSatoshi && htlcA.cltvExpiry == htlcB.cltvExpiry => htlcA.id <= htlcB.id
      case (OutHtlc(OutgoingHtlc(htlcA)), OutHtlc(OutgoingHtlc(htlcB))) if htlcA.paymentHash == htlcB.paymentHash && htlcA.amountMsat.truncateToSatoshi == htlcB.amountMsat.truncateToSatoshi => htlcA.cltvExpiry <= htlcB.cltvExpiry
      case _ => LexicographicalOrdering.isLessThan(a.output, b.output)
    }
  }

  def makeCommitTxOutputs(localFundingPublicKey: PublicKey,
                          remoteFundingPublicKey: PublicKey,
                          commitmentKeys: CommitmentPublicKeys,
                          payCommitTxFees: Boolean,
                          dustLimit: Satoshi,
                          toSelfDelay: CltvExpiryDelta,
                          spec: CommitmentSpec,
                          commitmentFormat: CommitmentFormat): CommitmentOutputs = {
    val outputs = collection.mutable.ArrayBuffer.empty[CommitmentOutputLink[CommitmentOutput]]

    trimOfferedHtlcs(dustLimit, spec, commitmentFormat).foreach { htlc =>
      val redeemScript = htlcOffered(commitmentKeys, htlc.add.paymentHash, commitmentFormat)
      outputs.append(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi, pay2wsh(redeemScript)), OutHtlc(htlc)))
    }

    trimReceivedHtlcs(dustLimit, spec, commitmentFormat).foreach { htlc =>
      val redeemScript = htlcReceived(commitmentKeys, htlc.add.paymentHash, htlc.add.cltvExpiry, commitmentFormat)
      outputs.append(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi, pay2wsh(redeemScript)), InHtlc(htlc)))
    }

    val hasHtlcs = outputs.nonEmpty

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (payCommitTxFees) {
      (spec.toLocal.truncateToSatoshi - commitTxTotalCost(dustLimit, spec, commitmentFormat), spec.toRemote.truncateToSatoshi)
    } else {
      (spec.toLocal.truncateToSatoshi, spec.toRemote.truncateToSatoshi - commitTxTotalCost(dustLimit, spec, commitmentFormat))
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

    if (toLocalAmount >= dustLimit) {
      val redeemScript = toLocalDelayed(commitmentKeys, toSelfDelay)
      outputs.append(CommitmentOutputLink(TxOut(toLocalAmount, pay2wsh(redeemScript)), ToLocal))
    }

    if (toRemoteAmount >= dustLimit) {
      commitmentFormat match {
        case DefaultCommitmentFormat =>
          val redeemKey = commitmentKeys.remotePaymentPublicKey
          outputs.append(CommitmentOutputLink(TxOut(toRemoteAmount, pay2wpkh(redeemKey)), ToRemote))
        case _: AnchorOutputsCommitmentFormat =>
          val redeemScript = toRemoteDelayed(commitmentKeys)
          outputs.append(CommitmentOutputLink(TxOut(toRemoteAmount, pay2wsh(redeemScript)), ToRemote))
      }
    }

    commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat =>
        if (toLocalAmount >= dustLimit || hasHtlcs) {
          val redeemScript = anchor(localFundingPublicKey)
          outputs.append(CommitmentOutputLink(TxOut(AnchorOutputsCommitmentFormat.anchorAmount, pay2wsh(redeemScript)), ToLocalAnchor))
        }
        if (toRemoteAmount >= dustLimit || hasHtlcs) {
          val redeemScript = anchor(remoteFundingPublicKey)
          outputs.append(CommitmentOutputLink(TxOut(AnchorOutputsCommitmentFormat.anchorAmount, pay2wsh(redeemScript)), ToRemoteAnchor))
        }
      case _ =>
    }

    outputs.sortWith(CommitmentOutputLink.sort).toSeq
  }

  def makeCommitTx(commitTxInput: InputInfo,
                   commitTxNumber: Long,
                   localPaymentBasePoint: PublicKey,
                   remotePaymentBasePoint: PublicKey,
                   localIsChannelOpener: Boolean,
                   outputs: CommitmentOutputs): CommitTx = {
    val txNumber = obscuredCommitTxNumber(commitTxNumber, localIsChannelOpener, localPaymentBasePoint, remotePaymentBasePoint)
    val (sequence, lockTime) = encodeTxNumber(txNumber)

    val tx = Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = sequence) :: Nil,
      txOut = outputs.map(_.output),
      lockTime = lockTime)

    CommitTx(commitTxInput, tx)
  }

  def makeHtlcTxs(keys: CommitmentPublicKeys,
                  commitTx: Transaction,
                  dustLimit: Satoshi,
                  toSelfDelay: CltvExpiryDelta,
                  feeratePerKw: FeeratePerKw,
                  outputs: CommitmentOutputs,
                  commitmentFormat: CommitmentFormat): Seq[HtlcTx] = {
    val htlcTimeoutTxs = outputs.zipWithIndex.collect {
      case (CommitmentOutputLink(o, OutHtlc(ou)), outputIndex) =>
        val co = CommitmentOutputLink(o, OutHtlc(ou))
        HtlcTimeoutTx.createUnsignedTx(keys, commitTx, co, outputIndex, dustLimit, toSelfDelay, feeratePerKw, commitmentFormat)
    }.collect { case Right(htlcTimeoutTx) => htlcTimeoutTx }
    val htlcSuccessTxs = outputs.zipWithIndex.collect {
      case (CommitmentOutputLink(o, InHtlc(in)), outputIndex) =>
        val co = CommitmentOutputLink(o, InHtlc(in))
        HtlcSuccessTx.createUnsignedTx(keys, commitTx, co, outputIndex, dustLimit, toSelfDelay, feeratePerKw, commitmentFormat)
    }.collect { case Right(htlcSuccessTx) => htlcSuccessTx }
    htlcTimeoutTxs ++ htlcSuccessTxs
  }

  // @formatter:off
  /** We always create multiple versions of each closing transaction, where fees are either paid by us or by our peer. */
  sealed trait SimpleClosingTxFee
  object SimpleClosingTxFee {
    case class PaidByUs(fee: Satoshi) extends SimpleClosingTxFee
    case class PaidByThem(fee: Satoshi) extends SimpleClosingTxFee
  }
  // @formatter:on

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

  /**
   * We skip creating transactions spending commitment outputs when the remaining amount is below dust.
   *
   * @param signedTxWithoutFees a fully signed version of the transaction paying no fees (to compute its weight).
   * @return the output amount, unless the transaction should be skipped because it's below dust.
   */
  private def skipTxIfBelowDust(signedTxWithoutFees: TransactionWithInputInfo, feerate: FeeratePerKw, dustLimit: Satoshi): Either[TxGenerationSkipped, Satoshi] = {
    val fee = weight2fee(feerate, signedTxWithoutFees.tx.weight())
    val amount = signedTxWithoutFees.input.txOut.amount - fee
    if (amount < dustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      Right(amount)
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

  /**
   * This default sig takes 72B when encoded in DER (incl. 1B for the trailing sig hash), it is used for fee estimation
   * It is 72 bytes because our signatures are normalized (low-s) and will take up 72 bytes at most in DER format
   */
  val PlaceHolderSig: ByteVector64 = ByteVector64(ByteVector.fill(64)(0xaa))
  assert(der(PlaceHolderSig).size == 72)

}
