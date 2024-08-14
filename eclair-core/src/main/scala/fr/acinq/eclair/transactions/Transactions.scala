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

import fr.acinq.bitcoin.{ScriptFlags, ScriptTree, SigHash}
import fr.acinq.bitcoin.SigHash._
import fr.acinq.bitcoin.SigVersion._
import fr.acinq.bitcoin.crypto.musig2.{IndividualNonce, SecretNonce}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey, XonlyPublicKey, ripemd160}
import fr.acinq.bitcoin.scalacompat.Script._
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.{ConfirmationTarget, FeeratePerKw}
import fr.acinq.eclair.channel.PartialSignatureWithNonce
import fr.acinq.eclair.transactions.CommitmentOutput._
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.wire.protocol.{CommitSig, UpdateAddHtlc}
import scodec.bits.ByteVector

import java.nio.ByteOrder
import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.util.Try

/**
 * Created by PM on 15/12/2016.
 */
object Transactions {

  val MAX_STANDARD_TX_WEIGHT = 400_000
  val NUMS_POINT = PublicKey(ByteVector.fromValidHex("02dca094751109d0bd055d03565874e8276dd53e926b44e3bd1bb6bf4bc130a279"))

  sealed trait CommitmentFormat {
    // @formatter:off
    def commitWeight: Int
    def htlcOutputWeight: Int
    def htlcTimeoutWeight: Int
    def htlcSuccessWeight: Int
    def htlcTimeoutInputWeight: Int
    def htlcSuccessInputWeight: Int
    def useTaproot: Boolean = false
    // @formatter:on
  }

  /**
   * Commitment format as defined in the v1.0 specification (https://github.com/lightningnetwork/lightning-rfc/tree/v1.0).
   */
  case object DefaultCommitmentFormat extends CommitmentFormat {
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
  sealed trait AnchorOutputsCommitmentFormat extends CommitmentFormat {
    override val commitWeight = 1124
    override val htlcOutputWeight = 172
    override val htlcTimeoutWeight = 666
    override val htlcSuccessWeight = 706
    override val htlcTimeoutInputWeight = 452
    override val htlcSuccessInputWeight = 491
  }

  object AnchorOutputsCommitmentFormat {
    val anchorAmount = Satoshi(330)
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

  case object SimpleTaprootChannelsStagingCommitmentFormat extends AnchorOutputsCommitmentFormat {
    override val commitWeight = 968
    override val useTaproot = true
  }

  // taproot channels that use the legacy (and unsafe) anchor output format
  case object SimpleTaprootChannelsStagingLegacyCommitmentFormat extends AnchorOutputsCommitmentFormat {
    override val commitWeight = 968
    override val useTaproot = true
  }

  // @formatter:off
  case class OutputInfo(index: Long, amount: Satoshi, publicKeyScript: ByteVector)

  /**
   * to spend the output of a taproot transactions, we need to know the script tree and internal key used to build this output
   */
  case class ScriptTreeAndInternalKey(scriptTree: ScriptTree, internalKey: XonlyPublicKey) {
    val publicKeyScript: ByteVector = Script.write(Script.pay2tr(internalKey, Some(scriptTree)))
  }

  case class InputInfo(outPoint: OutPoint, txOut: TxOut, redeemScriptOrScriptTree: Either[ByteVector, ScriptTreeAndInternalKey]) {
    val redeemScriptOrEmptyScript: ByteVector = redeemScriptOrScriptTree.swap.getOrElse(ByteVector.empty) // TODO: use the actual script tree for taproot transactions, once we implement them
  }

  object InputInfo {
    def apply(outPoint: OutPoint, txOut: TxOut, redeemScript: ByteVector) = new InputInfo(outPoint, txOut, Left(redeemScript))
    def apply(outPoint: OutPoint, txOut: TxOut, redeemScript: Seq[ScriptElt]) = new InputInfo(outPoint, txOut, Left(Script.write(redeemScript)))
    def apply(outPoint: OutPoint, txOut: TxOut, scriptTree: ScriptTreeAndInternalKey) = new InputInfo(outPoint, txOut, Right(scriptTree))
  }

  /** Owner of a given transaction (local/remote). */
  sealed trait TxOwner
  object TxOwner {
    case object Local extends TxOwner
    case object Remote extends TxOwner
  }

  sealed trait TransactionWithInputInfo {
    def input: InputInfo
    def desc: String
    def tx: Transaction
    def amountIn: Satoshi = input.txOut.amount
    def fee: Satoshi = amountIn - tx.txOut.map(_.amount).sum
    def minRelayFee: Satoshi = {
      val vsize = (tx.weight() + 3) / 4
      Satoshi(FeeratePerKw.MinimumRelayFeeRate * vsize / 1000)
    }
    /** Sighash flags to use when signing the transaction. */
   def sighash(txOwner: TxOwner, commitmentFormat: CommitmentFormat): Int = if (commitmentFormat.useTaproot) {
     SIGHASH_DEFAULT
   } else {
     SIGHASH_ALL
   }

    def sign(privateKey: PrivateKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): ByteVector64 = {
      sign(privateKey, sighash(txOwner, commitmentFormat))
    }

    def sign(key: PrivateKey, sighashType: Int): ByteVector64 = {
      // NB: the tx may have multiple inputs, we will only sign the one provided in txinfo.input. Bear in mind that the
      // signature will be invalidated if other inputs are added *afterwards* and sighashType was SIGHASH_ALL.
      val inputIndex = tx.txIn.zipWithIndex.find(_._1.outPoint == input.outPoint).get._2
      Transactions.sign(tx, input.redeemScriptOrEmptyScript, input.txOut.amount, key, sighashType, inputIndex)
    }

    def checkSig(commitSig : CommitSig, pubKey: PublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): Boolean =
      checkSig(commitSig.signature, pubKey, txOwner, commitmentFormat)

    def checkSig(sig: ByteVector64, pubKey: PublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): Boolean = {
      val sighash = this.sighash(txOwner, commitmentFormat)
      val data = Transaction.hashForSigning(tx, inputIndex = 0, input.redeemScriptOrEmptyScript, sighash, input.txOut.amount, SIGVERSION_WITNESS_V0)
      Crypto.verifySignature(data, sig, pubKey)
    }
  }

  sealed trait ReplaceableTransactionWithInputInfo extends TransactionWithInputInfo {
    /** Block before which the transaction must be confirmed. */
    def confirmationTarget: ConfirmationTarget
  }

  case class SpliceTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo { override def desc: String = "splice-tx" }

  case class CommitTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override def desc: String = "commit-tx"

    override def checkSig(commitSig:  CommitSig, pubKey:  PublicKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): Boolean = if (commitmentFormat.useTaproot) {
      commitSig.sigOrPartialSig.isRight
    } else {
      super.checkSig(commitSig, pubKey, txOwner, commitmentFormat)
    }

    def checkPartialSignature(psig: PartialSignatureWithNonce, localPubKey: PublicKey, localNonce: IndividualNonce, remotePubKey: PublicKey): Boolean = {
      import KotlinUtils._
      val session = fr.acinq.bitcoin.crypto.musig2.Musig2.taprootSession(
          this.tx,
          0,
          java.util.List.of(this.input.txOut),
          Scripts.sort(Seq(localPubKey, remotePubKey)).map(scala2kmp).asJava,
          java.util.List.of(localNonce, psig.nonce),
          null
        ).getRight
      session.verify(psig.partialSig, psig.nonce, remotePubKey)
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
    def htlcId: Long
    override def sighash(txOwner: TxOwner, commitmentFormat: CommitmentFormat): Int = commitmentFormat match {
      case DefaultCommitmentFormat => SIGHASH_ALL
      case _: AnchorOutputsCommitmentFormat => txOwner match {
        case TxOwner.Local => SIGHASH_ALL
        case TxOwner.Remote => SIGHASH_SINGLE | SIGHASH_ANYONECANPAY
      }
    }
    override def confirmationTarget: ConfirmationTarget.Absolute
  }

  case class HtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute) extends HtlcTx {
    override def desc: String = "htlc-success"

    override def sign(privateKey:  PrivateKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): ByteVector64 = if (commitmentFormat.useTaproot) {
      val Right(scriptTree: ScriptTree.Branch) = input.redeemScriptOrScriptTree.map(_.scriptTree)
      Transaction.signInputTaprootScriptPath(privateKey, tx, 0, Seq(input.txOut), SigHash.SIGHASH_SINGLE | SigHash.SIGHASH_ANYONECANPAY, KotlinUtils.kmp2scala(scriptTree.getRight.hash()))
    } else {
      super.sign(privateKey, txOwner, commitmentFormat)
    }

    override def checkSig(sig:  ByteVector64, pubKey:  PublicKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): Boolean = if (commitmentFormat.useTaproot) {
      import KotlinUtils._
      val sighash = this.sighash(txOwner, commitmentFormat)
      val Right(scriptTree: ScriptTree.Branch) = input.redeemScriptOrScriptTree.map(_.scriptTree)
      val data = Transaction.hashForSigningTaprootScriptPath(tx, inputIndex = 0, Seq(input.txOut), sighash, scriptTree.getRight.hash())
      Crypto.verifySignatureSchnorr(data, sig, pubKey.xOnly)
    } else {
      super.checkSig(sig, pubKey, txOwner, commitmentFormat)
    }
  }

  case class HtlcTimeoutTx(input: InputInfo, tx: Transaction, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute) extends HtlcTx {
    override def desc: String = "htlc-timeout"

    override def sign(privateKey:  PrivateKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): ByteVector64 = {
      if (commitmentFormat.useTaproot) {
val Right(scriptTree: ScriptTree.Branch) = input.redeemScriptOrScriptTree.map(_.scriptTree)
          Transaction.signInputTaprootScriptPath(privateKey, tx, 0, Seq(input.txOut), SigHash.SIGHASH_SINGLE | SigHash.SIGHASH_ANYONECANPAY, KotlinUtils.kmp2scala(scriptTree.getLeft.hash()))
} else {
super.sign(privateKey, txOwner, commitmentFormat)
}
    }

    override def checkSig(sig:  ByteVector64, pubKey:  PublicKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): Boolean = if (commitmentFormat.useTaproot) {
import KotlinUtils._
        val sighash = this.sighash(txOwner, commitmentFormat)
        val Right(scriptTree: ScriptTree.Branch) = input.redeemScriptOrScriptTree.map(_.scriptTree)
        val data = Transaction.hashForSigningTaprootScriptPath(tx, inputIndex = 0, Seq(input.txOut), sighash, scriptTree.getLeft.hash())
        Crypto.verifySignatureSchnorr(data, sig, pubKey.xOnly)
} else {
super.checkSig(sig, pubKey, txOwner, commitmentFormat)
}
  }

  case class HtlcDelayedTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override def desc: String = "htlc-delayed"
    override def sign(privateKey:  PrivateKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): ByteVector64 = if (commitmentFormat.useTaproot) {
val Right(scriptTree: ScriptTree.Leaf) = input.redeemScriptOrScriptTree.map(_.scriptTree)
        Transaction.signInputTaprootScriptPath(privateKey, tx, 0, Seq(input.txOut), SigHash.SIGHASH_DEFAULT, KotlinUtils.kmp2scala(scriptTree.hash()))
} else {
super.sign(privateKey, txOwner, commitmentFormat)
}
  }

  sealed trait ClaimHtlcTx extends ReplaceableTransactionWithInputInfo {
    def htlcId: Long
    override def confirmationTarget: ConfirmationTarget.Absolute
  }
  case class LegacyClaimHtlcSuccessTx(input: InputInfo, tx: Transaction, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute) extends ClaimHtlcTx { override def desc: String = "claim-htlc-success" }
  case class ClaimHtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute) extends ClaimHtlcTx {
    override def desc: String = "claim-htlc-success"

    override def sign(privateKey:  PrivateKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): ByteVector64 = if (commitmentFormat.useTaproot) {
val Right(htlcTree: ScriptTree.Branch) = input.redeemScriptOrScriptTree.map(_.scriptTree)
        Transaction.signInputTaprootScriptPath(privateKey, tx, 0, Seq(input.txOut), SigHash.SIGHASH_DEFAULT, KotlinUtils.kmp2scala(htlcTree.getRight.hash()))
} else {
super.sign(privateKey, txOwner, commitmentFormat)
}
}
  case class ClaimHtlcTimeoutTx(input: InputInfo, tx: Transaction, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute) extends ClaimHtlcTx {
    override def desc: String = "claim-htlc-timeout"

    override def sign(privateKey:  PrivateKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): ByteVector64 = if (commitmentFormat.useTaproot) {
val Right(htlcTree: ScriptTree.Branch) = input.redeemScriptOrScriptTree.map(_.scriptTree)
        Transaction.signInputTaprootScriptPath(privateKey, tx, 0, Seq(input.txOut), SigHash.SIGHASH_DEFAULT, KotlinUtils.kmp2scala(htlcTree.getLeft.hash()))
} else {
super.sign(privateKey, txOwner, commitmentFormat)
}
  }

  sealed trait ClaimAnchorOutputTx extends TransactionWithInputInfo
  case class ClaimLocalAnchorOutputTx(input: InputInfo, tx: Transaction, confirmationTarget: ConfirmationTarget) extends ClaimAnchorOutputTx with ReplaceableTransactionWithInputInfo {
    override def desc: String = "local-anchor"

    override def sign(privateKey:  PrivateKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): ByteVector64 = if (commitmentFormat.useTaproot) {
Transaction.signInputTaprootKeyPath(privateKey, tx, 0, Seq(input.txOut), SigHash.SIGHASH_DEFAULT, Some(Scripts.Taproot.anchorScriptTree))
} else {
super.sign(privateKey, txOwner, commitmentFormat)
}
  }

  case class ClaimRemoteAnchorOutputTx(input: InputInfo, tx: Transaction) extends ClaimAnchorOutputTx { override def desc: String = "remote-anchor" }
  sealed trait ClaimRemoteCommitMainOutputTx extends TransactionWithInputInfo
  case class ClaimP2WPKHOutputTx(input: InputInfo, tx: Transaction) extends ClaimRemoteCommitMainOutputTx { override def desc: String = "remote-main" }
  case class ClaimRemoteDelayedOutputTx(input: InputInfo, tx: Transaction) extends ClaimRemoteCommitMainOutputTx {
    override def desc: String = "remote-main-delayed"

    override def sign(privateKey:  PrivateKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): ByteVector64 = if (commitmentFormat.useTaproot) {
val Right(toRemoteScriptTree: ScriptTree.Leaf) = input.redeemScriptOrScriptTree.map(_.scriptTree)
        Transaction.signInputTaprootScriptPath(privateKey, tx, 0, Seq(input.txOut), SigHash.SIGHASH_DEFAULT, KotlinUtils.kmp2scala(toRemoteScriptTree.hash()))
} else {
super.sign(privateKey, txOwner, commitmentFormat)
}
  }

  case class ClaimLocalDelayedOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override def desc: String = "local-main-delayed"

    override def sign(privateKey:  PrivateKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): ByteVector64 = if (commitmentFormat.useTaproot) {
val Right(toLocalScriptTree: ScriptTree.Branch) = input.redeemScriptOrScriptTree.map(_.scriptTree)
        Transaction.signInputTaprootScriptPath(privateKey, tx, 0, Seq(input.txOut), SigHash.SIGHASH_DEFAULT, KotlinUtils.kmp2scala(toLocalScriptTree.getLeft.hash()))
} else {
super.sign(privateKey, txOwner, commitmentFormat)
}
  }

  case class MainPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override def desc: String = "main-penalty"

    override def sign(privateKey:  PrivateKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): ByteVector64 = if (commitmentFormat.useTaproot) {
val Right(toLocalScriptTree: ScriptTree.Branch) = input.redeemScriptOrScriptTree.map(_.scriptTree)
        Transaction.signInputTaprootScriptPath(privateKey, tx, 0, Seq(input.txOut), SigHash.SIGHASH_DEFAULT, KotlinUtils.kmp2scala(toLocalScriptTree.getRight.hash()))
} else {
super.sign(privateKey, txOwner, commitmentFormat)
}
  }

  case class HtlcPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override def desc: String = "htlc-penalty"

    override def sign(privateKey:  PrivateKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): ByteVector64 = if (commitmentFormat.useTaproot) {
Transaction.signInputTaprootKeyPath(privateKey, tx, 0, Seq(input.txOut), SigHash.SIGHASH_DEFAULT, input.redeemScriptOrScriptTree.map(_.scriptTree).toOption)
} else {
super.sign(privateKey, txOwner, commitmentFormat)
}
  }

  case class ClaimHtlcDelayedOutputPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override def desc: String = "htlc-delayed-penalty"

    override def sign(privateKey:  PrivateKey, txOwner:  TxOwner, commitmentFormat:  CommitmentFormat): ByteVector64 = if (commitmentFormat.useTaproot) {
Transaction.signInputTaprootKeyPath(privateKey, tx, 0, Seq(input.txOut), SigHash.SIGHASH_DEFAULT, input.redeemScriptOrScriptTree.map(_.scriptTree).toOption)
} else {
super.sign(privateKey, txOwner, commitmentFormat)
}
  }

  case class ClosingTx(input: InputInfo, tx: Transaction, toLocalOutput: Option[OutputInfo]) extends TransactionWithInputInfo { override def desc: String = "closing" }

  sealed trait TxGenerationSkipped
  case object OutputNotFound extends TxGenerationSkipped { override def toString = "output not found (probably trimmed)" }
  case object AmountBelowDustLimit extends TxGenerationSkipped { override def toString = "amount is below dust limit" }
  // @formatter:on

  /**
   * When *local* *current* [[CommitTx]] is published:
   *   - [[ClaimLocalDelayedOutputTx]] spends to-local output of [[CommitTx]] after a delay
   *   - When using anchor outputs, [[ClaimLocalAnchorOutputTx]] spends to-local anchor of [[CommitTx]]
   *   - [[HtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
   *     - [[HtlcDelayedTx]] spends [[HtlcSuccessTx]] after a delay
   *   - [[HtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
   *     - [[HtlcDelayedTx]] spends [[HtlcTimeoutTx]] after a delay
   *
   * When *remote* *current* [[CommitTx]] is published:
   *   - When using the default commitment format, [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
   *   - When using anchor outputs, [[ClaimRemoteDelayedOutputTx]] spends to-local output of [[CommitTx]]
   *   - When using anchor outputs, [[ClaimLocalAnchorOutputTx]] spends to-local anchor of [[CommitTx]]
   *   - [[ClaimHtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
   *   - [[ClaimHtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
   *
   * When *remote* *revoked* [[CommitTx]] is published:
   *   - When using the default commitment format, [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
   *   - When using anchor outputs, [[ClaimRemoteDelayedOutputTx]] spends to-local output of [[CommitTx]]
   *   - When using anchor outputs, [[ClaimLocalAnchorOutputTx]] spends to-local anchor of [[CommitTx]]
   *   - [[MainPenaltyTx]] spends remote main output using the per-commitment secret
   *   - [[HtlcSuccessTx]] spends htlc-sent outputs of [[CommitTx]] for which they have the preimage (published by remote)
   *     - [[ClaimHtlcDelayedOutputPenaltyTx]] spends [[HtlcSuccessTx]] using the revocation secret (published by local)
   *   - [[HtlcTimeoutTx]] spends htlc-received outputs of [[CommitTx]] after a timeout (published by remote)
   *     - [[ClaimHtlcDelayedOutputPenaltyTx]] spends [[HtlcTimeoutTx]] using the revocation secret (published by local)
   *   - [[HtlcPenaltyTx]] spends competes with [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] for the same outputs (published by local)
   */

  /**
   * these values are specific to us (not defined in the specification) and used to estimate fees
   */
  val claimP2WPKHOutputWeight = 438
  val anchorInputWeight = 279
  // The smallest transaction that spends an anchor contains 2 inputs (the commit tx output and a wallet input to set the feerate)
  // and 1 output (change). If we're using P2WPKH wallet inputs/outputs with 72 bytes signatures, this results in a weight of 717.
  // We round it down to 700 to allow for some error margin (e.g. signatures smaller than 72 bytes).
  val claimAnchorOutputMinWeight = 700
  val htlcDelayedWeight = 483
  val claimHtlcSuccessWeight = 571
  val claimHtlcTimeoutWeight = 545
  val mainPenaltyWeight = 484
  val htlcPenaltyWeight = 578 // based on spending an HTLC-Success output (would be 571 with HTLC-Timeout)

  def weight2feeMsat(feeratePerKw: FeeratePerKw, weight: Int): MilliSatoshi = MilliSatoshi(feeratePerKw.toLong * weight)

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
      case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat | SimpleTaprootChannelsStagingCommitmentFormat => dustLimit
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
      case ZeroFeeHtlcTxAnchorOutputsCommitmentFormat | SimpleTaprootChannelsStagingCommitmentFormat => dustLimit
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

  def getHtlcTxInputSequence(commitmentFormat: CommitmentFormat): Long = commitmentFormat match {
    case DefaultCommitmentFormat => 0 // htlc txs immediately spend the commit tx
    case _: AnchorOutputsCommitmentFormat => 1 // htlc txs have a 1-block delay to allow CPFP carve-out on anchors
  }

  /**
   * Represent a link between a commitment spec item (to-local, to-remote, anchors, htlc) and the actual output in the commit tx
   *
   * @param output           transaction output
   * @param redeemScript     redeem script that matches this output (most of them are p2wsh)
   * @param commitmentOutput commitment spec item this output is built from
   */
  case class CommitmentOutputLink[T <: CommitmentOutput](output: TxOut, redeemScript: Seq[ScriptElt], scriptTree_opt: Option[ScriptTreeAndInternalKey], commitmentOutput: T)


  /** Type alias for a collection of commitment output links */
  type CommitmentOutputs = Seq[CommitmentOutputLink[CommitmentOutput]]

  object CommitmentOutputLink {

    def apply[T <: CommitmentOutput](output: TxOut, redeemScript: Seq[ScriptElt], commitmentOutput: T) = new CommitmentOutputLink(output, redeemScript, None, commitmentOutput)

    def apply[T <: CommitmentOutput](output: TxOut, scriptTree: ScriptTreeAndInternalKey, commitmentOutput: T) = new CommitmentOutputLink(output, Seq(), Some(scriptTree), commitmentOutput)

    /**
     * We sort HTLC outputs according to BIP69 + CLTV as tie-breaker for offered HTLC, we do this only for the outgoing
     * HTLC because we must agree with the remote on the order of HTLC-Timeout transactions even for identical HTLC outputs.
     * See https://github.com/lightningnetwork/lightning-rfc/issues/448#issuecomment-432074187.
     */
    def sort(a: CommitmentOutputLink[CommitmentOutput], b: CommitmentOutputLink[CommitmentOutput]): Boolean = (a.commitmentOutput, b.commitmentOutput) match {
      case (OutHtlc(OutgoingHtlc(htlcA)), OutHtlc(OutgoingHtlc(htlcB))) if htlcA.paymentHash == htlcB.paymentHash && htlcA.amountMsat.truncateToSatoshi == htlcB.amountMsat.truncateToSatoshi =>
        htlcA.cltvExpiry <= htlcB.cltvExpiry
      case _ => LexicographicalOrdering.isLessThan(a.output, b.output)
    }
  }

  def makeCommitTxOutputs(localPaysCommitTxFees: Boolean,
                          localDustLimit: Satoshi,
                          localRevocationPubkey: PublicKey,
                          toLocalDelay: CltvExpiryDelta,
                          localDelayedPaymentPubkey: PublicKey,
                          remotePaymentPubkey: PublicKey,
                          localHtlcPubkey: PublicKey,
                          remoteHtlcPubkey: PublicKey,
                          localFundingPubkey: PublicKey,
                          remoteFundingPubkey: PublicKey,
                          spec: CommitmentSpec,
                          commitmentFormat: CommitmentFormat): CommitmentOutputs = {

    val outputs = collection.mutable.ArrayBuffer.empty[CommitmentOutputLink[CommitmentOutput]]

    trimOfferedHtlcs(localDustLimit, spec, commitmentFormat).foreach { htlc =>
      commitmentFormat.useTaproot match {
        case true =>
          val offeredHtlcTree = Scripts.Taproot.offeredHtlcTree(localHtlcPubkey, remoteHtlcPubkey, htlc.add.paymentHash)
          outputs.append(CommitmentOutputLink(
            TxOut(htlc.add.amountMsat.truncateToSatoshi, pay2tr(localRevocationPubkey.xOnly, Some(offeredHtlcTree))), ScriptTreeAndInternalKey(offeredHtlcTree, localRevocationPubkey.xOnly), OutHtlc(htlc)
          ))
        case _ =>
          val redeemScript = htlcOffered(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash.bytes), commitmentFormat)
          outputs.append(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi, pay2wsh(redeemScript)), redeemScript, OutHtlc(htlc)))
      }
    }

    trimReceivedHtlcs(localDustLimit, spec, commitmentFormat).foreach { htlc =>
      commitmentFormat.useTaproot match {
        case true =>
          val receivedHtlcTree = Scripts.Taproot.receivedHtlcTree(localHtlcPubkey, remoteHtlcPubkey, htlc.add.paymentHash, htlc.add.cltvExpiry)
          outputs.append(CommitmentOutputLink(
            TxOut(htlc.add.amountMsat.truncateToSatoshi, pay2tr(localRevocationPubkey.xOnly, Some(receivedHtlcTree))), ScriptTreeAndInternalKey(receivedHtlcTree, localRevocationPubkey.xOnly), InHtlc(htlc)
          ))
        case _ =>
          val redeemScript = htlcReceived(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash.bytes), htlc.add.cltvExpiry, commitmentFormat)
          outputs.append(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi, pay2wsh(redeemScript)), redeemScript, InHtlc(htlc)))
      }
    }

    val hasHtlcs = outputs.nonEmpty

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (localPaysCommitTxFees) {
      (spec.toLocal.truncateToSatoshi - commitTxTotalCost(localDustLimit, spec, commitmentFormat), spec.toRemote.truncateToSatoshi)
    } else {
      (spec.toLocal.truncateToSatoshi, spec.toRemote.truncateToSatoshi - commitTxTotalCost(localDustLimit, spec, commitmentFormat))
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

    if (toLocalAmount >= localDustLimit) {
      if (commitmentFormat.useTaproot) {
        val toLocalScriptTree = Scripts.Taproot.toLocalScriptTree(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
        outputs.append(CommitmentOutputLink(
          TxOut(toLocalAmount, pay2tr(XonlyPublicKey(NUMS_POINT), Some(toLocalScriptTree))),
          ScriptTreeAndInternalKey(toLocalScriptTree, NUMS_POINT.xOnly),
          ToLocal))
      } else {
        outputs.append(CommitmentOutputLink(
          TxOut(toLocalAmount, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))),
          toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey),
          ToLocal))
      }
    }

    if (toRemoteAmount >= localDustLimit) {
      commitmentFormat match {
        case _ if commitmentFormat.useTaproot =>
          val toRemoteScriptTree = Scripts.Taproot.toRemoteScriptTree(remotePaymentPubkey)
          outputs.append(CommitmentOutputLink(
            TxOut(toRemoteAmount, pay2tr(XonlyPublicKey(NUMS_POINT), Some(toRemoteScriptTree))),
            ScriptTreeAndInternalKey(toRemoteScriptTree, NUMS_POINT.xOnly),
            ToRemote))
        case DefaultCommitmentFormat => outputs.append(CommitmentOutputLink(
          TxOut(toRemoteAmount, pay2wpkh(remotePaymentPubkey)),
          pay2pkh(remotePaymentPubkey),
          ToRemote))
        case _: AnchorOutputsCommitmentFormat => outputs.append(CommitmentOutputLink(
          TxOut(toRemoteAmount, pay2wsh(toRemoteDelayed(remotePaymentPubkey))),
          toRemoteDelayed(remotePaymentPubkey),
          ToRemote))
      }
    }

    commitmentFormat match {
      case _ if commitmentFormat.useTaproot =>
        if (toLocalAmount >= localDustLimit || hasHtlcs) {
          outputs.append(CommitmentOutputLink(TxOut(AnchorOutputsCommitmentFormat.anchorAmount, pay2tr(localDelayedPaymentPubkey.xOnly, Some(Taproot.anchorScriptTree))), Taproot.anchorScript, ToLocalAnchor))
        }
        if (toRemoteAmount >= localDustLimit || hasHtlcs) {
          outputs.append(CommitmentOutputLink(TxOut(AnchorOutputsCommitmentFormat.anchorAmount, pay2tr(remotePaymentPubkey.xOnly, Some(Taproot.anchorScriptTree))), Taproot.anchorScript, ToRemoteAnchor))
        }
      case _: AnchorOutputsCommitmentFormat =>
        if (toLocalAmount >= localDustLimit || hasHtlcs) {
          outputs.append(CommitmentOutputLink(TxOut(AnchorOutputsCommitmentFormat.anchorAmount, pay2wsh(anchor(localFundingPubkey))), anchor(localFundingPubkey), ToLocalAnchor))
        }
        if (toRemoteAmount >= localDustLimit || hasHtlcs) {
          outputs.append(CommitmentOutputLink(TxOut(AnchorOutputsCommitmentFormat.anchorAmount, pay2wsh(anchor(remoteFundingPubkey))), anchor(remoteFundingPubkey), ToRemoteAnchor))
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

  def makeHtlcTimeoutTx(commitTx: Transaction,
                        output: CommitmentOutputLink[OutHtlc],
                        outputIndex: Int,
                        localDustLimit: Satoshi,
                        localRevocationPubkey: PublicKey,
                        toLocalDelay: CltvExpiryDelta,
                        localDelayedPaymentPubkey: PublicKey,
                        feeratePerKw: FeeratePerKw,
                        commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcTimeoutTx] = {
    import KotlinUtils._

    val fee = weight2fee(feeratePerKw, commitmentFormat.htlcTimeoutWeight)
    val redeemScript = output.redeemScript
    val scriptTree_opt = output.scriptTree_opt
    val htlc = output.commitmentOutput.outgoingHtlc.add
    val amount = htlc.amountMsat.truncateToSatoshi - fee
    if (amount < localDustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      if (commitmentFormat.useTaproot) {
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), scriptTree_opt.get)
        val tree = new ScriptTree.Leaf(Taproot.toDelayScript(localDelayedPaymentPubkey, toLocalDelay).map(scala2kmp).asJava)
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(amount, Script.pay2tr(localRevocationPubkey.xOnly(), Some(tree))) :: Nil,
          lockTime = htlc.cltvExpiry.toLong
        )
        Right(HtlcTimeoutTx(input, tx, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))))
      } else {
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(amount, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))) :: Nil,
          lockTime = htlc.cltvExpiry.toLong
        )
        Right(HtlcTimeoutTx(input, tx, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))))
      }
    }
  }

  def makeHtlcSuccessTx(commitTx: Transaction,
                        output: CommitmentOutputLink[InHtlc],
                        outputIndex: Int,
                        localDustLimit: Satoshi,
                        localRevocationPubkey: PublicKey,
                        toLocalDelay: CltvExpiryDelta,
                        localDelayedPaymentPubkey: PublicKey,
                        feeratePerKw: FeeratePerKw,
                        commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcSuccessTx] = {
    import KotlinUtils._

    val fee = weight2fee(feeratePerKw, commitmentFormat.htlcSuccessWeight)
    val redeemScript = output.redeemScript
    val scriptTree_opt = output.scriptTree_opt
    val htlc = output.commitmentOutput.incomingHtlc.add
    val amount = htlc.amountMsat.truncateToSatoshi - fee
    if (amount < localDustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      if (commitmentFormat.useTaproot) {
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), scriptTree_opt.get)
        val tree = new ScriptTree.Leaf(Taproot.toDelayScript(localDelayedPaymentPubkey, toLocalDelay).map(scala2kmp).asJava)
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(amount, Script.pay2tr(localRevocationPubkey.xOnly(), Some(tree))) :: Nil,
          lockTime = 0
        )
        Right(HtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))))
      } else {
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(amount, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))) :: Nil,
          lockTime = 0
        )
        Right(HtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))))
      }
    }
  }

  def makeHtlcTxs(commitTx: Transaction,
                  localDustLimit: Satoshi,
                  localRevocationPubkey: PublicKey,
                  toLocalDelay: CltvExpiryDelta,
                  localDelayedPaymentPubkey: PublicKey,
                  feeratePerKw: FeeratePerKw,
                  outputs: CommitmentOutputs,
                  commitmentFormat: CommitmentFormat): Seq[HtlcTx] = {
    val htlcTimeoutTxs = outputs.zipWithIndex.collect {
      case (co@CommitmentOutputLink(o, s, t, OutHtlc(ou)), outputIndex) =>
        val co = CommitmentOutputLink(o, s, t, OutHtlc(ou))
        makeHtlcTimeoutTx(commitTx, co, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feeratePerKw, commitmentFormat)
    }.collect { case Right(htlcTimeoutTx) => htlcTimeoutTx }
    val htlcSuccessTxs = outputs.zipWithIndex.collect {
      case (CommitmentOutputLink(o, s, t, InHtlc(in)), outputIndex) =>
        val co = CommitmentOutputLink(o, s, t, InHtlc(in))
        makeHtlcSuccessTx(commitTx, co, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feeratePerKw, commitmentFormat)
    }.collect { case Right(htlcSuccessTx) => htlcSuccessTx }
    htlcTimeoutTxs ++ htlcSuccessTxs
  }

  def makeClaimHtlcSuccessTx(commitTx: Transaction,
                             outputs: CommitmentOutputs,
                             localDustLimit: Satoshi,
                             localHtlcPubkey: PublicKey,
                             remoteHtlcPubkey: PublicKey,
                             remoteRevocationPubkey: PublicKey,
                             localFinalScriptPubKey: ByteVector,
                             htlc: UpdateAddHtlc,
                             feeratePerKw: FeeratePerKw,
                             commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimHtlcSuccessTx] = {
    outputs.zipWithIndex.collectFirst {
      case (CommitmentOutputLink(_, _, _, OutHtlc(OutgoingHtlc(outgoingHtlc))), outIndex) if outgoingHtlc.id == htlc.id => outIndex
    } match {
      case Some(outputIndex) =>
        val redeemScriptOrTree = outputs(outputIndex).scriptTree_opt match {
          case Some(scriptTee) => Right(scriptTee)
          case None => Left(write(htlcOffered(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, ripemd160(htlc.paymentHash.bytes), commitmentFormat)))
        }
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), redeemScriptOrTree)
        // unsigned tx
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        val weight = addSigs(ClaimHtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))), PlaceHolderSig, ByteVector32.Zeroes).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          Right(ClaimHtlcSuccessTx(input, tx1, htlc.paymentHash, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))))
        }
      case None => Left(OutputNotFound)
    }
  }

  def makeClaimHtlcTimeoutTx(commitTx: Transaction,
                             outputs: CommitmentOutputs,
                             localDustLimit: Satoshi,
                             localHtlcPubkey: PublicKey,
                             remoteHtlcPubkey: PublicKey,
                             remoteRevocationPubkey: PublicKey,
                             localFinalScriptPubKey: ByteVector,
                             htlc: UpdateAddHtlc,
                             feeratePerKw: FeeratePerKw,
                             commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimHtlcTimeoutTx] = {
    outputs.zipWithIndex.collectFirst {
      case (CommitmentOutputLink(_, _, _, InHtlc(IncomingHtlc(incomingHtlc))), outIndex) if incomingHtlc.id == htlc.id => outIndex
    } match {
      case Some(outputIndex) =>
        val scriptTree_opt = outputs(outputIndex).scriptTree_opt
        val redeemScriptOrTree = outputs(outputIndex).scriptTree_opt match {
          case Some(scriptTree) => Right(scriptTree)
          case None => Left(write(htlcReceived(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, ripemd160(htlc.paymentHash.bytes), htlc.cltvExpiry, commitmentFormat)))
        }
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), redeemScriptOrTree)
        // unsigned tx
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = htlc.cltvExpiry.toLong)
        val weight = addSigs(ClaimHtlcTimeoutTx(input, tx, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          Right(ClaimHtlcTimeoutTx(input, tx1, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))))
        }
      case None => Left(OutputNotFound)
    }
  }

  def makeClaimP2WPKHOutputTx(commitTx: Transaction, localDustLimit: Satoshi, localPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, ClaimP2WPKHOutputTx] = {
    val redeemScript = Script.pay2pkh(localPaymentPubkey)
    val pubkeyScript = write(pay2wpkh(localPaymentPubkey))
    findPubKeyScriptIndex(commitTx, pubkeyScript) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
        // unsigned tx
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0x00000000L) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get) and a dummy 33 bytes pubkey
        val weight = addSigs(ClaimP2WPKHOutputTx(input, tx), PlaceHolderPubKey, PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          Right(ClaimP2WPKHOutputTx(input, tx1))
        }
    }
  }

  def makeClaimRemoteDelayedOutputTx(commitTx: Transaction, localDustLimit: Satoshi, localPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimRemoteDelayedOutputTx] = {
    val (pubkeyScript, redeemScriptOrTree) = commitmentFormat.useTaproot match {
      case true =>
        Scripts.Taproot.toRemoteScript(localPaymentPubkey)
        val scripTree = Scripts.Taproot.toRemoteScriptTree(localPaymentPubkey)
        (write(pay2tr(XonlyPublicKey(NUMS_POINT), Some(scripTree))), Right(ScriptTreeAndInternalKey(scripTree, NUMS_POINT.xOnly)))
      case _ =>
        val redeemScript = toRemoteDelayed(localPaymentPubkey)
        (write(pay2wsh(redeemScript)), Left(write(redeemScript)))
    }
    findPubKeyScriptIndex(commitTx, pubkeyScript) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), redeemScriptOrTree)
        // unsigned transaction
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 1) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(ClaimRemoteDelayedOutputTx(input, tx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          Right(ClaimRemoteDelayedOutputTx(input, tx1))
        }
    }
  }

  def makeHtlcDelayedTx(htlcTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcDelayedTx] = {
    if (commitmentFormat.useTaproot) {
      val htlcTxTree = new ScriptTree.Leaf(Scripts.Taproot.toDelayScript(localDelayedPaymentPubkey, toLocalDelay).map(KotlinUtils.scala2kmp).asJava)
      val scriptTree = ScriptTreeAndInternalKey(htlcTxTree, localRevocationPubkey.xOnly)
      findPubKeyScriptIndex(htlcTx, scriptTree.publicKeyScript) match {
        case Left(skip) => Left(skip)
        case Right(outputIndex) =>
          val input = InputInfo(OutPoint(htlcTx, outputIndex), htlcTx.txOut(outputIndex), Right(ScriptTreeAndInternalKey(htlcTxTree, localRevocationPubkey.xOnly)))
          // unsigned transaction
          val tx = Transaction(
            version = 2,
            txIn = TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toInt) :: Nil,
            txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
            lockTime = 0)
          val weight = {
            val witness = Script.witnessScriptPathPay2tr(localRevocationPubkey.xOnly, htlcTxTree, ScriptWitness(Seq(ByteVector64.Zeroes)), htlcTxTree)
            tx.updateWitness(0, witness).weight()
          }
          val fee = weight2fee(feeratePerKw, weight)
          val amount = input.txOut.amount - fee
          if (amount < localDustLimit) {
            Left(AmountBelowDustLimit)
          } else {
            val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
            Right(HtlcDelayedTx(input, tx1))
          }
      }
    } else {
      makeLocalDelayedOutputTx(htlcTx, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, localFinalScriptPubKey, feeratePerKw, commitmentFormat).map {
        case (input, tx) => HtlcDelayedTx(input, tx)
      }
    }
  }

  def makeClaimLocalDelayedOutputTx(commitTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimLocalDelayedOutputTx] = {
    makeLocalDelayedOutputTx(commitTx, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, localFinalScriptPubKey, feeratePerKw, commitmentFormat).map {
      case (input, tx) => ClaimLocalDelayedOutputTx(input, tx)
    }
  }

  private def makeLocalDelayedOutputTx(parentTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, (InputInfo, Transaction)] = {
    val (pubkeyScript, redeemScriptOrTree) = if (commitmentFormat.useTaproot) {
      val toLocalScriptTree = Scripts.Taproot.toLocalScriptTree(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
      (
        write(pay2tr(XonlyPublicKey(NUMS_POINT), Some(toLocalScriptTree))),
        Right(ScriptTreeAndInternalKey(toLocalScriptTree, NUMS_POINT.xOnly))
      )
    } else {
      val redeemScript = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
      (write(pay2wsh(redeemScript)), Left(write(redeemScript)))
    }

    findPubKeyScriptIndex(parentTx, pubkeyScript) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(parentTx, outputIndex), parentTx.txOut(outputIndex), redeemScriptOrTree)
        // unsigned transaction
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toInt) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = if (commitmentFormat.useTaproot) {
          val toLocalScriptTree = Scripts.Taproot.toLocalScriptTree(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
          val witness = Script.witnessScriptPathPay2tr(XonlyPublicKey(NUMS_POINT), toLocalScriptTree.getLeft.asInstanceOf[ScriptTree.Leaf], ScriptWitness(Seq(ByteVector64.Zeroes)), toLocalScriptTree)
          tx.updateWitness(0, witness).weight()
        } else {
          addSigs(ClaimLocalDelayedOutputTx(input, tx), PlaceHolderSig).tx.weight()
        }
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          Right(input, tx1)
        }
    }
  }

  private def makeClaimAnchorOutputTx(commitTx: Transaction, fundingPubkey: PublicKey, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, (InputInfo, Transaction)] = {
    val (pubkeyScript, redeemScriptOrTree) = if (commitmentFormat.useTaproot) {
      (write(pay2tr(fundingPubkey.xOnly, Some(Scripts.Taproot.anchorScriptTree))), Right(ScriptTreeAndInternalKey(Scripts.Taproot.anchorScriptTree, fundingPubkey.xOnly)))
    } else {
      val redeemScript = anchor(fundingPubkey)
      (write(pay2wsh(redeemScript)), Left(write(redeemScript)))
    }

    findPubKeyScriptIndex(commitTx, pubkeyScript) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), redeemScriptOrTree)
        // unsigned transaction
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0) :: Nil,
          txOut = Nil, // anchor is only used to bump fees, the output will be added later depending on available inputs
          lockTime = 0)
        Right((input, tx))
    }
  }

  def makeClaimLocalAnchorOutputTx(commitTx: Transaction, localFundingPubkey: PublicKey, confirmationTarget: ConfirmationTarget, commitmentFormat: CommitmentFormat = DefaultCommitmentFormat): Either[TxGenerationSkipped, ClaimLocalAnchorOutputTx] = {
    makeClaimAnchorOutputTx(commitTx, localFundingPubkey, commitmentFormat).map { case (input, tx) => ClaimLocalAnchorOutputTx(input, tx, confirmationTarget) }
  }

  def makeClaimRemoteAnchorOutputTx(commitTx: Transaction, remoteFundingPubkey: PublicKey, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimRemoteAnchorOutputTx] = {
    makeClaimAnchorOutputTx(commitTx, remoteFundingPubkey, commitmentFormat).map { case (input, tx) => ClaimRemoteAnchorOutputTx(input, tx) }
  }

  def makeClaimHtlcDelayedOutputPenaltyTxs(htlcTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat = DefaultCommitmentFormat): Seq[Either[TxGenerationSkipped, ClaimHtlcDelayedOutputPenaltyTx]] = {
    import KotlinUtils._

    val (pubkeyScript, redeemScriptOrTree) = if (commitmentFormat.useTaproot) {
      val tree = new ScriptTree.Leaf(Taproot.toDelayScript(localDelayedPaymentPubkey, toLocalDelay).map(scala2kmp).asJava)
      (
        write(Script.pay2tr(localRevocationPubkey.xOnly(), Some(tree))),
        Right(ScriptTreeAndInternalKey(tree, localRevocationPubkey.xOnly))
      )
    } else {
      val script = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
      (write(pay2wsh(script)), Left(write(script)))
    }
    findPubKeyScriptIndexes(htlcTx, pubkeyScript) match {
      case Left(skip) => Seq(Left(skip))
      case Right(outputIndexes) => outputIndexes.map(outputIndex => {
        val input = InputInfo(OutPoint(htlcTx, outputIndex), htlcTx.txOut(outputIndex), redeemScriptOrTree)
        // unsigned transaction
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(ClaimHtlcDelayedOutputPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          Right(ClaimHtlcDelayedOutputPenaltyTx(input, tx1))
        }
      })
    }
  }

  def makeMainPenaltyTx(commitTx: Transaction, localDustLimit: Satoshi, remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: ByteVector, toRemoteDelay: CltvExpiryDelta, remoteDelayedPaymentPubkey: PublicKey, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, MainPenaltyTx] = {
    val redeemScript = if (commitmentFormat.useTaproot) {
      Nil
    } else {
      toLocalDelayed(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey)
    }
    val pubkeyScript = if (commitmentFormat.useTaproot) {
      val toLocalScriptTree = Scripts.Taproot.toLocalScriptTree(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey)
      write(pay2tr(XonlyPublicKey(NUMS_POINT), Some(toLocalScriptTree)))
    } else {
      write(pay2wsh(redeemScript))
    }

    findPubKeyScriptIndex(commitTx, pubkeyScript) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val redeemScriptOrTree = if (commitmentFormat.useTaproot) {
          Right(ScriptTreeAndInternalKey(Scripts.Taproot.toLocalScriptTree(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey), NUMS_POINT.xOnly))
        } else {
          Left(write(redeemScript))
        }
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), redeemScriptOrTree)
        // unsigned transaction
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(MainPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          Right(MainPenaltyTx(input, tx1))
        }
    }
  }

  /**
   * We already have the redeemScript, no need to build it
   */
  def makeHtlcPenaltyTx(commitTx: Transaction, htlcOutputIndex: Int, redeemScript: ByteVector, localDustLimit: Satoshi, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, HtlcPenaltyTx] = {
    val input = InputInfo(OutPoint(commitTx, htlcOutputIndex), commitTx.txOut(htlcOutputIndex), redeemScript)
    // unsigned transaction
    val tx = Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
      txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
      lockTime = 0)
    // compute weight with a dummy 73 bytes signature (the largest you can get)
    val weight = addSigs(MainPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
    val fee = weight2fee(feeratePerKw, weight)
    val amount = input.txOut.amount - fee
    if (amount < localDustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
      Right(HtlcPenaltyTx(input, tx1))
    }
  }

  def makeHtlcPenaltyTx(commitTx: Transaction, htlcOutputIndex: Int, scriptTreeAndInternalKey: ScriptTreeAndInternalKey, localDustLimit: Satoshi, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, HtlcPenaltyTx] = {
    val input = InputInfo(OutPoint(commitTx, htlcOutputIndex), commitTx.txOut(htlcOutputIndex), scriptTreeAndInternalKey)
    // unsigned transaction
    val tx = Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
      txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
      lockTime = 0)
    // compute weight with a dummy 73 bytes signature (the largest you can get)
    val weight = addSigs(MainPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
    val fee = weight2fee(feeratePerKw, weight)
    val amount = input.txOut.amount - fee
    if (amount < localDustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
      Right(HtlcPenaltyTx(input, tx1))
    }
  }


  def makeClosingTx(commitTxInput: InputInfo, localScriptPubKey: ByteVector, remoteScriptPubKey: ByteVector, localPaysClosingFees: Boolean, dustLimit: Satoshi, closingFee: Satoshi, spec: CommitmentSpec, sequence: Long = 0xffffffffL): ClosingTx = {
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
      txIn = TxIn(commitTxInput.outPoint, ByteVector.empty, sequence) :: Nil,
      txOut = toLocalOutput_opt.toSeq ++ toRemoteOutput_opt.toSeq ++ Nil,
      lockTime = 0))
    val toLocalOutput = findPubKeyScriptIndex(tx, localScriptPubKey).map(index => OutputInfo(index, toLocalAmount, localScriptPubKey)).toOption
    ClosingTx(commitTxInput, tx, toLocalOutput)
  }

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: ByteVector): Either[TxGenerationSkipped, Int] = {
    val outputIndex = tx.txOut.indexWhere(_.publicKeyScript == pubkeyScript)
    if (outputIndex >= 0) {
      Right(outputIndex)
    } else {
      Left(OutputNotFound)
    }
  }

  def findPubKeyScriptIndexes(tx: Transaction, pubkeyScript: ByteVector): Either[TxGenerationSkipped, Seq[Int]] = {
    val outputIndexes = tx.txOut.zipWithIndex.collect {
      case (txOut, index) if txOut.publicKeyScript == pubkeyScript => index
    }
    if (outputIndexes.nonEmpty) {
      Right(outputIndexes)
    } else {
      Left(OutputNotFound)
    }
  }

  /**
   * Default public key used for fee estimation
   */
  val PlaceHolderPubKey = PrivateKey(ByteVector32.One).publicKey

  /**
   * This default sig takes 72B when encoded in DER (incl. 1B for the trailing sig hash), it is used for fee estimation
   * It is 72 bytes because our signatures are normalized (low-s) and will take up 72 bytes at most in DER format
   */
  val PlaceHolderSig = ByteVector64(ByteVector.fill(64)(0xaa))
  assert(der(PlaceHolderSig).size == 72)

  private def sign(tx: Transaction, redeemScript: ByteVector, amount: Satoshi, key: PrivateKey, sighashType: Int, inputIndex: Int): ByteVector64 = {
    val sigDER = Transaction.signInput(tx, inputIndex, redeemScript, sighashType, amount, SIGVERSION_WITNESS_V0, key)
    val sig64 = Crypto.der2compact(sigDER)
    sig64
  }

  private def sign(txinfo: TransactionWithInputInfo, key: PrivateKey, sighashType: Int): ByteVector64 = {
    // NB: the tx may have multiple inputs, we will only sign the one provided in txinfo.input. Bear in mind that the
    // signature will be invalidated if other inputs are added *afterwards* and sighashType was SIGHASH_ALL.
    val inputIndex = txinfo.tx.txIn.zipWithIndex.find(_._1.outPoint == txinfo.input.outPoint).get._2
    sign(txinfo.tx, txinfo.input.redeemScriptOrEmptyScript, txinfo.input.txOut.amount, key, sighashType, inputIndex)
  }

  private def sign(txinfo: TransactionWithInputInfo, key: PrivateKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): ByteVector64 = sign(txinfo, key, txinfo.sighash(txOwner, commitmentFormat))

  def partialSign(key: PrivateKey, tx: Transaction, inputIndex: Int, spentOutputs: Seq[TxOut],
                  localFundingPublicKey: PublicKey, remoteFundingPublicKey: PublicKey,
                  localNonce: (SecretNonce, IndividualNonce), remoteNextLocalNonce: IndividualNonce): Either[Throwable, ByteVector32] = {
    val publicKeys = Scripts.sort(Seq(localFundingPublicKey, remoteFundingPublicKey))
    Musig2.signTaprootInput(key, tx, inputIndex, spentOutputs, publicKeys, localNonce._1, Seq(localNonce._2, remoteNextLocalNonce), None)
  }

  def partialSign(txinfo: TransactionWithInputInfo, key: PrivateKey,
                  localFundingPublicKey: PublicKey, remoteFundingPublicKey: PublicKey,
                  localNonce: (SecretNonce, IndividualNonce), remoteNextLocalNonce: IndividualNonce): Either[Throwable, ByteVector32] = {
    val inputIndex = txinfo.tx.txIn.indexWhere(_.outPoint == txinfo.input.outPoint)
    partialSign(key, txinfo.tx, inputIndex, Seq(txinfo.input.txOut), localFundingPublicKey: PublicKey, remoteFundingPublicKey: PublicKey, localNonce, remoteNextLocalNonce)
  }

  def aggregatePartialSignatures(txinfo: TransactionWithInputInfo,
                                 localSig: ByteVector32, remoteSig: ByteVector32,
                                 localFundingPublicKey: PublicKey, remoteFundingPublicKey: PublicKey,
                                 localNonce: IndividualNonce, remoteNonce: IndividualNonce): Either[Throwable, ByteVector64] = {
    Musig2.aggregateTaprootSignatures(
      Seq(localSig, remoteSig), txinfo.tx, txinfo.tx.txIn.indexWhere(_.outPoint == txinfo.input.outPoint),
      Seq(txinfo.input.txOut),
      Scripts.sort(Seq(localFundingPublicKey, remoteFundingPublicKey)),
      Seq(localNonce, remoteNonce),
      None)
  }

  def addSigs(commitTx: CommitTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): CommitTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    commitTx.copy(tx = commitTx.tx.updateWitness(0, witness))
  }

  def addSigs(mainPenaltyTx: MainPenaltyTx, revocationSig: ByteVector64): MainPenaltyTx = {
    val witness = mainPenaltyTx.input.redeemScriptOrScriptTree match {
      case Right(ScriptTreeAndInternalKey(toLocalScriptTree: ScriptTree.Branch, internalKey)) =>
        Script.witnessScriptPathPay2tr(internalKey, toLocalScriptTree.getRight.asInstanceOf[ScriptTree.Leaf], ScriptWitness(Seq(revocationSig)), toLocalScriptTree)
      case Right(ScriptTreeAndInternalKey(_: ScriptTree, _)) => throw new IllegalArgumentException("unexpected script tree leaf when building main penalty tx")
      case Left(redeemScript) =>
        Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, redeemScript)
    }
    mainPenaltyTx.copy(tx = mainPenaltyTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcPenaltyTx: HtlcPenaltyTx, revocationSig: ByteVector64, revocationPubkey: PublicKey, commitmentFormat: CommitmentFormat): HtlcPenaltyTx = {
    val witness = if (commitmentFormat.useTaproot) {
      Script.witnessKeyPathPay2tr(revocationSig)
    } else {
      Scripts.witnessHtlcWithRevocationSig(revocationSig, revocationPubkey, htlcPenaltyTx.input.redeemScriptOrScriptTree.swap.getOrElse(ByteVector.empty))
    }
    htlcPenaltyTx.copy(tx = htlcPenaltyTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcSuccessTx: HtlcSuccessTx, localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32, commitmentFormat: CommitmentFormat): HtlcSuccessTx = {
    val witness = if (commitmentFormat.useTaproot) {
      val Right(ScriptTreeAndInternalKey(htlcTree: ScriptTree.Branch, internalKey)) = htlcSuccessTx.input.redeemScriptOrScriptTree
      val sigHash = (SigHash.SIGHASH_SINGLE | SigHash.SIGHASH_ANYONECANPAY).toByte
      Script.witnessScriptPathPay2tr(internalKey, htlcTree.getRight.asInstanceOf[ScriptTree.Leaf], ScriptWitness(Seq(remoteSig :+ sigHash, localSig :+ sigHash, paymentPreimage)), htlcTree)
    } else {
      witnessHtlcSuccess(localSig, remoteSig, paymentPreimage, htlcSuccessTx.input.redeemScriptOrEmptyScript, commitmentFormat)
    }
    htlcSuccessTx.copy(tx = htlcSuccessTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcTimeoutTx: HtlcTimeoutTx, localSig: ByteVector64, remoteSig: ByteVector64, commitmentFormat: CommitmentFormat): HtlcTimeoutTx = {
    val witness = if (commitmentFormat.useTaproot) {
      val Right(ScriptTreeAndInternalKey(htlcTree: ScriptTree.Branch, internalKey)) = htlcTimeoutTx.input.redeemScriptOrScriptTree
      val sigHash = (SigHash.SIGHASH_SINGLE | SigHash.SIGHASH_ANYONECANPAY).toByte
      Script.witnessScriptPathPay2tr(internalKey, htlcTree.getLeft.asInstanceOf[ScriptTree.Leaf], ScriptWitness(Seq(remoteSig :+ sigHash, localSig :+ sigHash)), htlcTree)
    } else {
      witnessHtlcTimeout(localSig, remoteSig, htlcTimeoutTx.input.redeemScriptOrEmptyScript, commitmentFormat)
    }
    htlcTimeoutTx.copy(tx = htlcTimeoutTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcSuccessTx: ClaimHtlcSuccessTx, localSig: ByteVector64, paymentPreimage: ByteVector32): ClaimHtlcSuccessTx = {
    val witness = claimHtlcSuccessTx.input.redeemScriptOrScriptTree match {
      case Right(ScriptTreeAndInternalKey(htlcTree: ScriptTree.Branch, internalKey)) =>
        Script.witnessScriptPathPay2tr(internalKey, htlcTree.getRight.asInstanceOf[ScriptTree.Leaf], ScriptWitness(Seq(localSig, paymentPreimage)), htlcTree)
      case Right(ScriptTreeAndInternalKey(_: ScriptTree, _)) => throw new IllegalArgumentException("unexpected script tree leaf when building claim htlc success tx")
      case Left(redeemScript) =>
        witnessClaimHtlcSuccessFromCommitTx(localSig, paymentPreimage, redeemScript)
    }
    claimHtlcSuccessTx.copy(tx = claimHtlcSuccessTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcTimeoutTx: ClaimHtlcTimeoutTx, localSig: ByteVector64): ClaimHtlcTimeoutTx = {
    val witness = claimHtlcTimeoutTx.input.redeemScriptOrScriptTree match {
      case Right(ScriptTreeAndInternalKey(htlcTree: ScriptTree.Branch, internalKey)) =>
        Script.witnessScriptPathPay2tr(internalKey, htlcTree.getLeft.asInstanceOf[ScriptTree.Leaf], ScriptWitness(Seq(localSig)), htlcTree)
      case Right(ScriptTreeAndInternalKey(_: ScriptTree, _)) => throw new IllegalArgumentException("unexpected script tree leaf when building claim htlc timeout tx")
      case Left(redeemScript) =>
        witnessClaimHtlcTimeoutFromCommitTx(localSig, redeemScript)
    }
    claimHtlcTimeoutTx.copy(tx = claimHtlcTimeoutTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimP2WPKHOutputTx: ClaimP2WPKHOutputTx, localPaymentPubkey: PublicKey, localSig: ByteVector64): ClaimP2WPKHOutputTx = {
    val witness = ScriptWitness(Seq(der(localSig), localPaymentPubkey.value))
    claimP2WPKHOutputTx.copy(tx = claimP2WPKHOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimRemoteDelayedOutputTx: ClaimRemoteDelayedOutputTx, localSig: ByteVector64): ClaimRemoteDelayedOutputTx = {
    val witness = claimRemoteDelayedOutputTx.input.redeemScriptOrScriptTree match {
      case Right(ScriptTreeAndInternalKey(toRemoteScriptTree: ScriptTree.Leaf, internalKey)) =>
        Script.witnessScriptPathPay2tr(internalKey, toRemoteScriptTree, ScriptWitness(Seq(localSig)), toRemoteScriptTree)
      case Right(ScriptTreeAndInternalKey(_: ScriptTree, _)) => throw new IllegalArgumentException("unexpected script tree branch when building claim remote delayed output tx")
      case Left(redeemScript) =>
        witnessClaimToRemoteDelayedFromCommitTx(localSig, redeemScript)
    }
    claimRemoteDelayedOutputTx.copy(tx = claimRemoteDelayedOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimDelayedOutputTx: ClaimLocalDelayedOutputTx, localSig: ByteVector64): ClaimLocalDelayedOutputTx = {
    val witness = claimDelayedOutputTx.input.redeemScriptOrScriptTree match {
      case Right(ScriptTreeAndInternalKey(scriptTree: ScriptTree.Branch, internalKey)) =>
        Script.witnessScriptPathPay2tr(internalKey, scriptTree.getLeft.asInstanceOf[ScriptTree.Leaf], ScriptWitness(Seq(localSig)), scriptTree)
      case Right(ScriptTreeAndInternalKey(_: ScriptTree, _)) => throw new IllegalArgumentException("unexpected script tree leaf when building claim delayed output tx")
      case Left(redeemScript) =>
        witnessToLocalDelayedAfterDelay(localSig, redeemScript)
    }
    claimDelayedOutputTx.copy(tx = claimDelayedOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcDelayedTx: HtlcDelayedTx, localSig: ByteVector64): HtlcDelayedTx = {
    val witness = htlcDelayedTx.input.redeemScriptOrScriptTree match {
      case Right(ScriptTreeAndInternalKey(scriptTree: ScriptTree.Leaf, internalKey)) =>
        Script.witnessScriptPathPay2tr(internalKey, scriptTree, ScriptWitness(Seq(localSig)), scriptTree)
      case Right(ScriptTreeAndInternalKey(_: ScriptTree, _)) => throw new IllegalArgumentException("unexpected script tree leaf when building htlc delayed tx")
      case Left(redeemScript) =>
        witnessToLocalDelayedAfterDelay(localSig, redeemScript)
    }
    htlcDelayedTx.copy(tx = htlcDelayedTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimAnchorOutputTx: ClaimLocalAnchorOutputTx, localSig: ByteVector64): ClaimLocalAnchorOutputTx = {
    val witness = claimAnchorOutputTx.input.redeemScriptOrScriptTree match {
      case Right(_) => Script.witnessKeyPathPay2tr(localSig)
      case Left(redeemScript) => witnessAnchor(localSig, redeemScript)
    }
    claimAnchorOutputTx.copy(tx = claimAnchorOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcDelayedPenalty: ClaimHtlcDelayedOutputPenaltyTx, revocationSig: ByteVector64): ClaimHtlcDelayedOutputPenaltyTx = {
    val witness = claimHtlcDelayedPenalty.input.redeemScriptOrScriptTree match {
      case Right(_) => Script.witnessKeyPathPay2tr(revocationSig)
      case Left(redeemScript) => Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, redeemScript)
    }
    claimHtlcDelayedPenalty.copy(tx = claimHtlcDelayedPenalty.tx.updateWitness(0, witness))
  }

  def addSigs(closingTx: ClosingTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): ClosingTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    closingTx.copy(tx = closingTx.tx.updateWitness(0, witness))
  }

  def addAggregatedSignature(commitTx: CommitTx, aggregatedSignature: ByteVector64): CommitTx = {
    commitTx.copy(tx = commitTx.tx.updateWitness(0, Script.witnessKeyPathPay2tr(aggregatedSignature)))
  }

  def addAggregatedSignature(closingTx: ClosingTx, aggregatedSignature: ByteVector64): ClosingTx = {
    closingTx.copy(tx = closingTx.tx.updateWitness(0, Script.witnessKeyPathPay2tr(aggregatedSignature)))
  }

  def checkSpendable(txinfo: TransactionWithInputInfo): Try[Unit] = {
    // NB: we don't verify the other inputs as they should only be wallet inputs used to RBF the transaction
    Try(Transaction.correctlySpends(txinfo.tx, Map(txinfo.input.outPoint -> txinfo.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
  }

  def checkSig(txinfo: TransactionWithInputInfo, sig: ByteVector64, pubKey: PublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): Boolean = {
    import KotlinUtils._
    val sighash = txinfo.sighash(txOwner, commitmentFormat)
    if (commitmentFormat.useTaproot) {
      val Right(scriptTree: ScriptTree.Branch) = txinfo.input.redeemScriptOrScriptTree.map(_.scriptTree)
      val data = Transaction.hashForSigningTaprootScriptPath(txinfo.tx, inputIndex = 0, Seq(txinfo.input.txOut), sighash, scriptTree.getLeft.hash())
      Crypto.verifySignatureSchnorr(data, sig, pubKey.xOnly)
    } else {
      val data = Transaction.hashForSigning(txinfo.tx, inputIndex = 0, txinfo.input.redeemScriptOrEmptyScript, sighash, txinfo.input.txOut.amount, SIGVERSION_WITNESS_V0)
      Crypto.verifySignature(data, sig, pubKey)
    }
  }

}
