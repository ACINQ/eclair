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
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey, XonlyPublicKey, ripemd160}
import fr.acinq.bitcoin.scalacompat.Script._
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.bitcoin.{ScriptFlags, ScriptTree}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.{ConfirmationTarget, FeeratePerKw}
import fr.acinq.eclair.transactions.CommitmentOutput._
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc
import scodec.bits.ByteVector

import java.nio.ByteOrder
import scala.util.{Success, Try}

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
  /**
   * This trait contains redeem information necessary to spend different types of segwit inputs.
   * We also define subtraits to express all the combinations we support: this isn't very elegant, but what we really
   * need is union types that only exist in Scala 3 (or in libraries like Cats), so we don't really have a choice if
   * we want to benefit from exhaustive pattern matching compiler checks.
   */
  sealed trait RedeemInfo
  object RedeemInfo {
    /** Inputs that use either segwit v0 or a taproot key path. */
    sealed trait TaprootKeyPathOrSegwitV0 extends RedeemInfo
    /** Inputs that use either segwit v0 or a taproot script path. */
    sealed trait TaprootScriptPathOrSegwitV0 extends RedeemInfo

    /** @param redeemScript the actual script must be known to redeem segwit v0 inputs (p2wpkh or p2wsh). */
    case class SegwitV0(redeemScript: ByteVector) extends TaprootKeyPathOrSegwitV0 with TaprootScriptPathOrSegwitV0
    object SegwitV0 {
      def apply(redeemScript: Seq[ScriptElt]): SegwitV0 = SegwitV0(Script.write(redeemScript))
    }

    /** This trait can be used to match all taproot spending paths. */
    sealed trait Taproot extends RedeemInfo

    /**
     * @param internalKey    the private key associated with this public key will be used to sign.
     * @param scriptTree_opt the script tree must be known if there is one, even when spending via the key path.
     */
    case class TaprootKeyPath(internalKey: XonlyPublicKey, scriptTree_opt: Option[ScriptTree]) extends Taproot with TaprootKeyPathOrSegwitV0

    /**
     * @param internalKey we need the internal key, even if we don't have the private key, to spend via a script path.
     * @param scriptTree  we need the complete script tree to spend taproot inputs.
     * @param leafHash    hash of the leaf script we're spending (must belong to the tree).
     */
    case class TaprootScriptPath(internalKey: XonlyPublicKey, scriptTree: ScriptTree, leafHash: ByteVector32) extends Taproot with TaprootScriptPathOrSegwitV0 {
      require(findScript(scriptTree, leafHash).nonEmpty, "script tree must contain the provided leaf")

      /**
       * TODO: this won't be needed once findScript is added to bitcoin-kmp, remove when updating
       *
       * @return the leaf that matches `leafHash`
       */
      private def findScript(scriptTree: ScriptTree, leafHash: ByteVector32): Option[ScriptTree.Leaf] = scriptTree match {
        case l: ScriptTree.Leaf => if (l.hash() == KotlinUtils.scala2kmp(leafHash)) Some(l) else None
        case b: ScriptTree.Branch => findScript(b.getLeft, leafHash) orElse findScript(b.getRight, leafHash)
      }
    }
  }

  case class InputInfo(outPoint: OutPoint, txOut: TxOut)
  case class OutputInfo(index: Long, amount: Satoshi, publicKeyScript: ByteVector)

  /** Owner of a given transaction (local/remote). */
  sealed trait TxOwner
  object TxOwner {
    case object Local extends TxOwner
    case object Remote extends TxOwner
  }

  sealed trait TransactionWithInputInfo {
    def input: InputInfo
    /** Redeem information for the [[input]] this transaction spends. */
    def redeemInfo: RedeemInfo
    def desc: String
    def tx: Transaction
    def amountIn: Satoshi = input.txOut.amount
    def fee: Satoshi = amountIn - tx.txOut.map(_.amount).sum
    def minRelayFee: Satoshi = {
      val vsize = (tx.weight() + 3) / 4
      Satoshi(FeeratePerKw.MinimumRelayFeeRate * vsize / 1000)
    }
    /** Sighash flags to use when signing the transaction. */
    def sighash(txOwner: TxOwner, commitmentFormat: CommitmentFormat): Int = redeemInfo match {
      case _: RedeemInfo.SegwitV0 => SIGHASH_ALL
      case _: RedeemInfo.Taproot => SIGHASH_DEFAULT
    }

    /**
     * @param extraUtxos extra outputs spent by this transaction (in addition to the main [[input]]).
     */
    def sign(key: PrivateKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat, extraUtxos: Map[OutPoint, TxOut]): ByteVector64 = {
      sign(key, sighash(txOwner, commitmentFormat), extraUtxos)
    }

    def sign(key: PrivateKey, sighashType: Int, extraUtxos: Map[OutPoint, TxOut]): ByteVector64 = {
      verifyInputDetails(extraUtxos)
      // NB: the tx may have multiple inputs, we will only sign the one matching our outPoint. Bear in mind that the
      // signature will be invalidated if other inputs are added *afterwards* depending on the sighashType used.
      val inputIndex = tx.txIn.indexWhere(_.outPoint == input.outPoint)
      redeemInfo match {
        case RedeemInfo.SegwitV0(redeemScript) =>
          val sigDER = Transaction.signInput(tx, inputIndex, redeemScript, sighashType, input.txOut.amount, SIGVERSION_WITNESS_V0, key)
          Crypto.der2compact(sigDER)
        case _: RedeemInfo.Taproot => ???
      }
    }

    def checkSig(sig: ByteVector64, pubKey: PublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat, extraUtxos: Map[OutPoint, TxOut]): Boolean = {
      verifyInputDetails(extraUtxos)
      val inputIndex = tx.txIn.indexWhere(_.outPoint == input.outPoint)
      if (inputIndex >= 0) {
        redeemInfo match {
          case RedeemInfo.SegwitV0(redeemScript) =>
            val data = Transaction.hashForSigning(tx, inputIndex, redeemScript, sighash(txOwner, commitmentFormat), input.txOut.amount, SIGVERSION_WITNESS_V0)
            Crypto.verifySignature(data, sig, pubKey)
          case _: RedeemInfo.Taproot => ???
        }
      } else {
        false
      }
    }

    /** Verify that we have the [[TxOut]] for every input in the transaction, and returns them in the transaction order. */
    private def verifyInputDetails(extraUtxos: Map[OutPoint, TxOut]): Seq[TxOut] = {
      val inputsMap = extraUtxos + (input.outPoint -> input.txOut)
      tx.txIn.map(txIn => {
        // Note that using a require here is dangerous, because callers don't expect this function to throw.
        // But we want to ensure that we're correctly providing input details, otherwise our signature will silently be
        // invalid when using taproot. We verify this in all cases, even when using segwit v0, to ensure that we have as
        // many tests as possible that exercise this codepath.
        require(inputsMap.contains(txIn.outPoint), s"cannot sign or verify $desc with txId=${tx.txid}: missing input details for ${txIn.outPoint}")
        inputsMap(txIn.outPoint)
      })
    }
  }

  sealed trait ReplaceableTransactionWithInputInfo extends TransactionWithInputInfo {
    /** Block before which the transaction must be confirmed. */
    def confirmationTarget: ConfirmationTarget
  }

  case class CommitTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0, tx: Transaction) extends TransactionWithInputInfo { override def desc: String = "commit-tx" }
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
  case class HtlcSuccessTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootScriptPathOrSegwitV0, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute) extends HtlcTx { override def desc: String = "htlc-success" }
  case class HtlcTimeoutTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootScriptPathOrSegwitV0, tx: Transaction, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute) extends HtlcTx { override def desc: String = "htlc-timeout" }
  case class HtlcDelayedTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootScriptPathOrSegwitV0, tx: Transaction) extends TransactionWithInputInfo { override def desc: String = "htlc-delayed" }
  sealed trait ClaimHtlcTx extends ReplaceableTransactionWithInputInfo {
    def htlcId: Long
    override def confirmationTarget: ConfirmationTarget.Absolute
  }
  case class LegacyClaimHtlcSuccessTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootScriptPathOrSegwitV0, tx: Transaction, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute) extends ClaimHtlcTx { override def desc: String = "claim-htlc-success" }
  case class ClaimHtlcSuccessTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootScriptPathOrSegwitV0, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute) extends ClaimHtlcTx { override def desc: String = "claim-htlc-success" }
  case class ClaimHtlcTimeoutTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootScriptPathOrSegwitV0, tx: Transaction, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute) extends ClaimHtlcTx { override def desc: String = "claim-htlc-timeout" }
  sealed trait ClaimAnchorOutputTx extends TransactionWithInputInfo
  case class ClaimLocalAnchorOutputTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0, tx: Transaction, confirmationTarget: ConfirmationTarget) extends ClaimAnchorOutputTx with ReplaceableTransactionWithInputInfo { override def desc: String = "local-anchor" }
  case class ClaimRemoteAnchorOutputTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0, tx: Transaction) extends ClaimAnchorOutputTx { override def desc: String = "remote-anchor" }
  sealed trait ClaimRemoteCommitMainOutputTx extends TransactionWithInputInfo
  case class ClaimP2WPKHOutputTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0, tx: Transaction) extends ClaimRemoteCommitMainOutputTx { override def desc: String = "remote-main" }
  case class ClaimRemoteDelayedOutputTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootScriptPathOrSegwitV0, tx: Transaction) extends ClaimRemoteCommitMainOutputTx { override def desc: String = "remote-main-delayed" }
  case class ClaimLocalDelayedOutputTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootScriptPathOrSegwitV0, tx: Transaction) extends TransactionWithInputInfo { override def desc: String = "local-main-delayed" }
  case class MainPenaltyTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0, tx: Transaction) extends TransactionWithInputInfo { override def desc: String = "main-penalty" }
  case class HtlcPenaltyTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0, tx: Transaction) extends TransactionWithInputInfo { override def desc: String = "htlc-penalty" }
  case class ClaimHtlcDelayedOutputPenaltyTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0, tx: Transaction) extends TransactionWithInputInfo { override def desc: String = "htlc-delayed-penalty" }
  case class ClosingTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0, tx: Transaction, toLocalOutput: Option[OutputInfo]) extends TransactionWithInputInfo { override def desc: String = "closing" }
  case class SpliceTx(input: InputInfo, redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0, tx: Transaction) extends TransactionWithInputInfo { override def desc: String = "splice-tx" }

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

  private def weight2feeMsat(feeratePerKw: FeeratePerKw, weight: Int): MilliSatoshi = MilliSatoshi(feeratePerKw.toLong * weight)

  def weight2fee(feeratePerKw: FeeratePerKw, weight: Int): Satoshi = weight2feeMsat(feeratePerKw, weight).truncateToSatoshi

  /**
   * @param fee    tx fee
   * @param weight tx weight
   * @return the fee rate (in Satoshi/Kw) for this tx
   */
  def fee2rate(fee: Satoshi, weight: Int): FeeratePerKw = FeeratePerKw((fee * 1000L) / weight)

  /** As defined in https://github.com/lightning/bolts/blob/master/03-transactions.md#dust-limits */
  def dustLimit(scriptPubKey: ByteVector): Satoshi = {
    Try(Script.parse(scriptPubKey)) match {
      case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubkeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) if pubkeyHash.size == 20 => 546.sat
      case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) if scriptHash.size == 20 => 540.sat
      case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.size == 20 => 294.sat
      case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.size == 32 => 330.sat
      case Success((OP_1 | OP_2 | OP_3 | OP_4 | OP_5 | OP_6 | OP_7 | OP_8 | OP_9 | OP_10 | OP_11 | OP_12 | OP_13 | OP_14 | OP_15 | OP_16) :: OP_PUSHDATA(program, _) :: Nil) if 2 <= program.length && program.length <= 40 => 354.sat
      case Success(OP_RETURN :: _) => 0.sat // OP_RETURN is never dust
      case _ => 546.sat
    }
  }

  /** When an output is using OP_RETURN, we usually want to make sure its amount is 0, otherwise bitcoind won't accept it. */
  private def isOpReturn(scriptPubKey: ByteVector): Boolean = {
    Try(Script.parse(scriptPubKey)) match {
      case Success(OP_RETURN :: _) => true
      case _ => false
    }
  }

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

  object CommitmentOutputLink {
    /**
     * We sort HTLC outputs according to BIP69 + CLTV as tie-breaker for offered HTLC, we do this only for the outgoing
     * HTLC because we must agree with the remote on the order of HTLC-Timeout transactions even for identical HTLC outputs.
     * See https://github.com/lightningnetwork/lightning-rfc/issues/448#issuecomment-432074187.
     */
    def sort(a: CommitmentOutputLink[CommitmentOutput], b: CommitmentOutputLink[CommitmentOutput]): Boolean = (a.commitmentOutput, b.commitmentOutput) match {
      case (OutHtlc(_, OutgoingHtlc(htlcA)), OutHtlc(_, OutgoingHtlc(htlcB))) if htlcA.paymentHash == htlcB.paymentHash && htlcA.amountMsat.truncateToSatoshi == htlcB.amountMsat.truncateToSatoshi =>
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
      val (publicKeyScript, redeemInfo) = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = htlcOffered(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash.bytes), commitmentFormat)
          (pay2wsh(redeemScript), RedeemInfo.SegwitV0(redeemScript))
      }
      outputs.append(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi, publicKeyScript), OutHtlc(redeemInfo, htlc)))
    }

    trimReceivedHtlcs(localDustLimit, spec, commitmentFormat).foreach { htlc =>
      val (publicKeyScript, redeemInfo) = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = htlcReceived(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash.bytes), htlc.add.cltvExpiry, commitmentFormat)
          (pay2wsh(redeemScript), RedeemInfo.SegwitV0(redeemScript))
      }
      outputs.append(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi, publicKeyScript), InHtlc(redeemInfo, htlc)))
    }

    val hasHtlcs = outputs.nonEmpty

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (localPaysCommitTxFees) {
      (spec.toLocal.truncateToSatoshi - commitTxTotalCost(localDustLimit, spec, commitmentFormat), spec.toRemote.truncateToSatoshi)
    } else {
      (spec.toLocal.truncateToSatoshi, spec.toRemote.truncateToSatoshi - commitTxTotalCost(localDustLimit, spec, commitmentFormat))
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

    if (toLocalAmount >= localDustLimit) {
      val (publicKeyScript, redeemInfo) = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
          val redeemScript = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
          (pay2wsh(redeemScript), RedeemInfo.SegwitV0(redeemScript))
      }
      outputs.append(CommitmentOutputLink(TxOut(toLocalAmount, publicKeyScript), ToLocal(redeemInfo)))
    }

    if (toRemoteAmount >= localDustLimit) {
      val (publicKeyScript, redeemInfo) = commitmentFormat match {
        case DefaultCommitmentFormat =>
          // Note: when using p2wpkh, the redeem script is actually p2pkh.
          val redeemScript = pay2pkh(remotePaymentPubkey)
          (pay2wpkh(remotePaymentPubkey), RedeemInfo.SegwitV0(redeemScript))
        case _: AnchorOutputsCommitmentFormat =>
          val redeemScript = toRemoteDelayed(remotePaymentPubkey)
          (pay2wsh(redeemScript), RedeemInfo.SegwitV0(redeemScript))
      }
      outputs.append(CommitmentOutputLink(TxOut(toRemoteAmount, publicKeyScript), ToRemote(redeemInfo)))
    }

    commitmentFormat match {
      case DefaultCommitmentFormat => ()
      case _: AnchorOutputsCommitmentFormat =>
        if (toLocalAmount >= localDustLimit || hasHtlcs) {
          val redeemScript = anchor(localFundingPubkey)
          outputs.append(CommitmentOutputLink(TxOut(AnchorOutputsCommitmentFormat.anchorAmount, pay2wsh(redeemScript)), ToLocalAnchor(RedeemInfo.SegwitV0(redeemScript))))
        }
        if (toRemoteAmount >= localDustLimit || hasHtlcs) {
          val redeemScript = anchor(remoteFundingPubkey)
          outputs.append(CommitmentOutputLink(TxOut(AnchorOutputsCommitmentFormat.anchorAmount, pay2wsh(redeemScript)), ToRemoteAnchor(RedeemInfo.SegwitV0(redeemScript))))
        }
    }

    outputs.sortWith(CommitmentOutputLink.sort).toSeq
  }

  def makeCommitTx(commitTxInput: InputInfo,
                   redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0,
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
      lockTime = lockTime
    )
    CommitTx(commitTxInput, redeemInfo, tx)
  }

  private def makeHtlcTimeoutTx(commitTx: Transaction,
                                output: CommitmentOutputLink[OutHtlc],
                                outputIndex: Int,
                                localDustLimit: Satoshi,
                                localRevocationPubkey: PublicKey,
                                toLocalDelay: CltvExpiryDelta,
                                localDelayedPaymentPubkey: PublicKey,
                                feeratePerKw: FeeratePerKw,
                                commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcTimeoutTx] = {
    val fee = weight2fee(feeratePerKw, commitmentFormat.htlcTimeoutWeight)
    val htlc = output.commitmentOutput.outgoingHtlc.add
    val amount = htlc.amountMsat.truncateToSatoshi - fee
    if (amount < localDustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
      val publicKeyScript = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat => pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))
      }
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
        txOut = TxOut(amount, publicKeyScript) :: Nil,
        lockTime = htlc.cltvExpiry.toLong
      )
      Right(HtlcTimeoutTx(input, output.commitmentOutput.redeemInfo, tx, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))))
    }
  }

  private def makeHtlcSuccessTx(commitTx: Transaction,
                                output: CommitmentOutputLink[InHtlc],
                                outputIndex: Int,
                                localDustLimit: Satoshi,
                                localRevocationPubkey: PublicKey,
                                toLocalDelay: CltvExpiryDelta,
                                localDelayedPaymentPubkey: PublicKey,
                                feeratePerKw: FeeratePerKw,
                                commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcSuccessTx] = {
    val fee = weight2fee(feeratePerKw, commitmentFormat.htlcSuccessWeight)
    val htlc = output.commitmentOutput.incomingHtlc.add
    val amount = htlc.amountMsat.truncateToSatoshi - fee
    if (amount < localDustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
      val publicKeyScript = commitmentFormat match {
        case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat => pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))
      }
      val tx = Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
        txOut = TxOut(amount, publicKeyScript) :: Nil,
        lockTime = 0
      )
      Right(HtlcSuccessTx(input, output.commitmentOutput.redeemInfo, tx, htlc.paymentHash, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))))
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
      case (CommitmentOutputLink(txOut, OutHtlc(redeemInfo, htlcOut)), outputIndex) =>
        val co = CommitmentOutputLink(txOut, OutHtlc(redeemInfo, htlcOut))
        makeHtlcTimeoutTx(commitTx, co, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feeratePerKw, commitmentFormat)
    }.collect { case Right(htlcTimeoutTx) => htlcTimeoutTx }
    val htlcSuccessTxs = outputs.zipWithIndex.collect {
      case (CommitmentOutputLink(txOut, InHtlc(redeemInfo, htlcIn)), outputIndex) =>
        val co = CommitmentOutputLink(txOut, InHtlc(redeemInfo, htlcIn))
        makeHtlcSuccessTx(commitTx, co, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feeratePerKw, commitmentFormat)
    }.collect { case Right(htlcSuccessTx) => htlcSuccessTx }
    htlcTimeoutTxs ++ htlcSuccessTxs
  }

  def makeClaimHtlcSuccessTx(commitTx: Transaction,
                             outputs: CommitmentOutputs,
                             localDustLimit: Satoshi,
                             localFinalScriptPubKey: ByteVector,
                             htlc: UpdateAddHtlc,
                             feeratePerKw: FeeratePerKw,
                             commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimHtlcSuccessTx] = {
    outputs.zipWithIndex.collectFirst {
      case (CommitmentOutputLink(_, OutHtlc(redeemInfo, OutgoingHtlc(outgoingHtlc))), outIndex) if outgoingHtlc.id == htlc.id => (redeemInfo, outIndex)
    } match {
      case Some((redeemInfo, outputIndex)) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
        val unsignedTx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
          lockTime = 0
        )
        val weight = addSigs(ClaimHtlcSuccessTx(input, redeemInfo, unsignedTx, htlc.paymentHash, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))), PlaceHolderSig, ByteVector32.Zeroes).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
          Right(ClaimHtlcSuccessTx(input, redeemInfo, tx, htlc.paymentHash, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))))
        }
      case None => Left(OutputNotFound)
    }
  }

  def makeClaimHtlcTimeoutTx(commitTx: Transaction,
                             outputs: CommitmentOutputs,
                             localDustLimit: Satoshi,
                             localFinalScriptPubKey: ByteVector,
                             htlc: UpdateAddHtlc,
                             feeratePerKw: FeeratePerKw,
                             commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimHtlcTimeoutTx] = {
    outputs.zipWithIndex.collectFirst {
      case (CommitmentOutputLink(_, InHtlc(redeemInfo, IncomingHtlc(incomingHtlc))), outIndex) if incomingHtlc.id == htlc.id => (redeemInfo, outIndex)
    } match {
      case Some((redeemInfo, outputIndex)) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
        val unsignedTx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
          lockTime = htlc.cltvExpiry.toLong
        )
        val weight = addSigs(ClaimHtlcTimeoutTx(input, redeemInfo, unsignedTx, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
          Right(ClaimHtlcTimeoutTx(input, redeemInfo, tx, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))))
        }
      case None => Left(OutputNotFound)
    }
  }

  def makeClaimP2WPKHOutputTx(commitTx: Transaction, localDustLimit: Satoshi, localPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, ClaimP2WPKHOutputTx] = {
    val redeemInfo = RedeemInfo.SegwitV0(Script.pay2pkh(localPaymentPubkey))
    val pubkeyScript = write(pay2wpkh(localPaymentPubkey))
    findPubKeyScriptIndex(commitTx, pubkeyScript) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
        val unsignedTx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0) :: Nil,
          txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
          lockTime = 0
        )
        // compute weight with a dummy 73 bytes signature (the largest you can get) and a dummy 33 bytes pubkey
        val weight = addSigs(ClaimP2WPKHOutputTx(input, redeemInfo, unsignedTx), PlaceHolderPubKey, PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
          Right(ClaimP2WPKHOutputTx(input, redeemInfo, tx))
        }
    }
  }

  def makeClaimRemoteDelayedOutputTx(commitTx: Transaction, localDustLimit: Satoshi, localPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimRemoteDelayedOutputTx] = {
    val (redeemInfo, pubkeyScript) = commitmentFormat match {
      case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
        val redeemScript = toRemoteDelayed(localPaymentPubkey)
        (RedeemInfo.SegwitV0(redeemScript), pay2wsh(redeemScript))
    }
    findPubKeyScriptIndex(commitTx, pubkeyScript) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
        val unsignedTx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 1) :: Nil,
          txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
          lockTime = 0
        )
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(ClaimRemoteDelayedOutputTx(input, redeemInfo, unsignedTx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
          Right(ClaimRemoteDelayedOutputTx(input, redeemInfo, tx))
        }
    }
  }

  def makeHtlcDelayedTx(htlcTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcDelayedTx] = {
    makeLocalDelayedOutputTx(htlcTx, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, localFinalScriptPubKey, feeratePerKw, commitmentFormat).map {
      case (input, redeemInfo, tx) => HtlcDelayedTx(input, redeemInfo, tx)
    }
  }

  def makeClaimLocalDelayedOutputTx(commitTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimLocalDelayedOutputTx] = {
    makeLocalDelayedOutputTx(commitTx, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, localFinalScriptPubKey, feeratePerKw, commitmentFormat).map {
      case (input, redeemInfo, tx) => ClaimLocalDelayedOutputTx(input, redeemInfo, tx)
    }
  }

  private def makeLocalDelayedOutputTx(parentTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, (InputInfo, RedeemInfo.TaprootScriptPathOrSegwitV0, Transaction)] = {
    val (redeemInfo, pubkeyScript) = commitmentFormat match {
      case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
        val redeemScript = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
        (RedeemInfo.SegwitV0(redeemScript), write(pay2wsh(redeemScript)))
    }
    findPubKeyScriptIndex(parentTx, pubkeyScript) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(parentTx, outputIndex), parentTx.txOut(outputIndex))
        val unsignedTx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toInt) :: Nil,
          txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
          lockTime = 0
        )
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(ClaimLocalDelayedOutputTx(input, redeemInfo, unsignedTx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
          Right((input, redeemInfo, tx))
        }
    }
  }

  private def makeClaimAnchorOutputTx(commitTx: Transaction, anchorKey: PublicKey, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, (InputInfo, RedeemInfo.TaprootKeyPathOrSegwitV0, Transaction)] = {
    val (redeemInfo, pubkeyScript) = commitmentFormat match {
      case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
        val redeemScript = anchor(anchorKey)
        (RedeemInfo.SegwitV0(redeemScript), write(pay2wsh(redeemScript)))
    }
    findPubKeyScriptIndex(commitTx, pubkeyScript) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
        val unsignedTx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0) :: Nil,
          txOut = Nil, // anchor is only used to bump fees, the output will be added later depending on available inputs
          lockTime = 0
        )
        Right((input, redeemInfo, unsignedTx))
    }
  }

  def makeClaimLocalAnchorOutputTx(commitTx: Transaction, localFundingPubkey: PublicKey, confirmationTarget: ConfirmationTarget, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimLocalAnchorOutputTx] = {
    makeClaimAnchorOutputTx(commitTx, localFundingPubkey, commitmentFormat).map {
      case (input, redeemInfo, tx) => ClaimLocalAnchorOutputTx(input, redeemInfo, tx, confirmationTarget)
    }
  }

  def makeClaimRemoteAnchorOutputTx(commitTx: Transaction, remoteFundingPubkey: PublicKey, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimRemoteAnchorOutputTx] = {
    makeClaimAnchorOutputTx(commitTx, remoteFundingPubkey, commitmentFormat).map {
      case (input, redeemInfo, tx) => ClaimRemoteAnchorOutputTx(input, redeemInfo, tx)
    }
  }

  def makeClaimHtlcDelayedOutputPenaltyTxs(htlcTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Seq[Either[TxGenerationSkipped, ClaimHtlcDelayedOutputPenaltyTx]] = {
    val (redeemInfo, pubkeyScript) = commitmentFormat match {
      case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
        val redeemScript = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
        (RedeemInfo.SegwitV0(redeemScript), write(pay2wsh(redeemScript)))
    }
    findPubKeyScriptIndexes(htlcTx, pubkeyScript) match {
      case Left(skip) => Seq(Left(skip))
      case Right(outputIndexes) => outputIndexes.map(outputIndex => {
        val input = InputInfo(OutPoint(htlcTx, outputIndex), htlcTx.txOut(outputIndex))
        val unsignedTx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
          txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
          lockTime = 0
        )
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(ClaimHtlcDelayedOutputPenaltyTx(input, redeemInfo, unsignedTx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
          Right(ClaimHtlcDelayedOutputPenaltyTx(input, redeemInfo, tx))
        }
      })
    }
  }

  def makeMainPenaltyTx(commitTx: Transaction, localDustLimit: Satoshi, remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: ByteVector, toRemoteDelay: CltvExpiryDelta, remoteDelayedPaymentPubkey: PublicKey, feeratePerKw: FeeratePerKw, commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, MainPenaltyTx] = {
    val (redeemInfo, pubkeyScript) = commitmentFormat match {
      case DefaultCommitmentFormat | _: AnchorOutputsCommitmentFormat =>
        val redeemScript = toLocalDelayed(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey)
        (RedeemInfo.SegwitV0(redeemScript), write(pay2wsh(redeemScript)))
    }
    findPubKeyScriptIndex(commitTx, pubkeyScript) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
        val unsignedTx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0
        )
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(MainPenaltyTx(input, redeemInfo, unsignedTx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
          Right(MainPenaltyTx(input, redeemInfo, tx))
        }
    }
  }

  def makeHtlcPenaltyTx(commitTx: Transaction, htlcOutputIndex: Int, redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0, localDustLimit: Satoshi, localFinalScriptPubKey: ByteVector, feeratePerKw: FeeratePerKw): Either[TxGenerationSkipped, HtlcPenaltyTx] = {
    val input = InputInfo(OutPoint(commitTx, htlcOutputIndex), commitTx.txOut(htlcOutputIndex))
    val unsignedTx = Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
      txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
      lockTime = 0
    )
    // compute weight with a dummy 73 bytes signature (the largest you can get)
    val weight = addSigs(MainPenaltyTx(input, redeemInfo, unsignedTx), PlaceHolderSig).tx.weight()
    val fee = weight2fee(feeratePerKw, weight)
    val amount = input.txOut.amount - fee
    if (amount < localDustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
      Right(HtlcPenaltyTx(input, redeemInfo, tx))
    }
  }

  def makeClosingTx(commitTxInput: InputInfo,
                    redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0,
                    localScriptPubKey: ByteVector,
                    remoteScriptPubKey: ByteVector,
                    localPaysClosingFees: Boolean,
                    dustLimit: Satoshi,
                    closingFee: Satoshi,
                    spec: CommitmentSpec): ClosingTx = {
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
      txIn = TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = 0xffffffffL) :: Nil,
      txOut = toLocalOutput_opt.toSeq ++ toRemoteOutput_opt.toSeq ++ Nil,
      lockTime = 0
    ))
    val toLocalOutput = findPubKeyScriptIndex(tx, localScriptPubKey).map(index => OutputInfo(index, toLocalAmount, localScriptPubKey)).toOption
    ClosingTx(commitTxInput, redeemInfo, tx, toLocalOutput)
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

  def makeSimpleClosingTxs(input: InputInfo,
                           redeemInfo: RedeemInfo.TaprootKeyPathOrSegwitV0,
                           spec: CommitmentSpec,
                           fee: SimpleClosingTxFee,
                           lockTime: Long,
                           localScriptPubKey: ByteVector,
                           remoteScriptPubKey: ByteVector): ClosingTxs = {
    require(spec.htlcs.isEmpty, "there shouldn't be any pending htlcs")

    val txNoOutput = Transaction(2, Seq(TxIn(input.outPoint, ByteVector.empty, sequence = 0xFFFFFFFDL)), Nil, lockTime)

    // We compute the remaining balance for each side after paying the closing fees.
    // This lets us decide whether outputs can be included in the closing transaction or not.
    val (toLocalAmount, toRemoteAmount) = fee match {
      case SimpleClosingTxFee.PaidByUs(fee) => (spec.toLocal.truncateToSatoshi - fee, spec.toRemote.truncateToSatoshi)
      case SimpleClosingTxFee.PaidByThem(fee) => (spec.toLocal.truncateToSatoshi, spec.toRemote.truncateToSatoshi - fee)
    }

    // An OP_RETURN script may be provided, but only when burning all of the peer's balance to fees.
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
        val Right(toLocalOutputInfo) = findPubKeyScriptIndex(txLocalAndRemote, localScriptPubKey).map(index => OutputInfo(index, toLocalOutput.amount, localScriptPubKey))
        ClosingTxs(
          localAndRemote_opt = Some(ClosingTx(input, redeemInfo, txLocalAndRemote, Some(toLocalOutputInfo))),
          // We also provide a version of the transaction without the remote output, which they may want to omit if not economical to spend.
          localOnly_opt = Some(ClosingTx(input, redeemInfo, txNoOutput.copy(txOut = Seq(toLocalOutput)), Some(OutputInfo(0, toLocalOutput.amount, localScriptPubKey)))),
          remoteOnly_opt = None
        )
      case (Some(toLocalOutput), None) =>
        ClosingTxs(
          localAndRemote_opt = None,
          localOnly_opt = Some(ClosingTx(input, redeemInfo, txNoOutput.copy(txOut = Seq(toLocalOutput)), Some(OutputInfo(0, toLocalOutput.amount, localScriptPubKey)))),
          remoteOnly_opt = None
        )
      case (None, Some(toRemoteOutput)) =>
        ClosingTxs(
          localAndRemote_opt = None,
          localOnly_opt = None,
          remoteOnly_opt = Some(ClosingTx(input, redeemInfo, txNoOutput.copy(txOut = Seq(toRemoteOutput)), None))
        )
      case (None, None) => ClosingTxs(None, None, None)
    }
  }

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: Seq[ScriptElt]): Either[TxGenerationSkipped, Int] = findPubKeyScriptIndex(tx, write(pubkeyScript))

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: ByteVector): Either[TxGenerationSkipped, Int] = {
    val outputIndex = tx.txOut.indexWhere(_.publicKeyScript == pubkeyScript)
    if (outputIndex >= 0) {
      Right(outputIndex)
    } else {
      Left(OutputNotFound)
    }
  }

  private def findPubKeyScriptIndexes(tx: Transaction, pubkeyScript: ByteVector): Either[TxGenerationSkipped, Seq[Int]] = {
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
  val PlaceHolderPubKey: PublicKey = PrivateKey(ByteVector32.One).publicKey

  /**
   * This default sig takes 72B when encoded in DER (incl. 1B for the trailing sig hash), it is used for fee estimation
   * It is 72 bytes because our signatures are normalized (low-s) and will take up 72 bytes at most in DER format
   */
  val PlaceHolderSig: ByteVector64 = ByteVector64(ByteVector.fill(64)(0xaa))
  assert(der(PlaceHolderSig).size == 72)

  def addSigs(commitTx: CommitTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): CommitTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    commitTx.copy(tx = commitTx.tx.updateWitness(0, witness))
  }

  def addSigs(mainPenaltyTx: MainPenaltyTx, revocationSig: ByteVector64): MainPenaltyTx = mainPenaltyTx.redeemInfo match {
    case RedeemInfo.SegwitV0(redeemScript) =>
      val witness = Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, redeemScript)
      mainPenaltyTx.copy(tx = mainPenaltyTx.tx.updateWitness(0, witness))
    case _: RedeemInfo.TaprootKeyPath => mainPenaltyTx
  }

  def addSigs(htlcPenaltyTx: HtlcPenaltyTx, revocationSig: ByteVector64, revocationPubkey: PublicKey): HtlcPenaltyTx = htlcPenaltyTx.redeemInfo match {
    case RedeemInfo.SegwitV0(redeemScript) =>
      val witness = Scripts.witnessHtlcWithRevocationSig(revocationSig, revocationPubkey, redeemScript)
      htlcPenaltyTx.copy(tx = htlcPenaltyTx.tx.updateWitness(0, witness))
    case _: RedeemInfo.TaprootKeyPath => htlcPenaltyTx
  }

  def addSigs(htlcSuccessTx: HtlcSuccessTx, localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32, commitmentFormat: CommitmentFormat): HtlcSuccessTx = htlcSuccessTx.redeemInfo match {
    case RedeemInfo.SegwitV0(redeemScript) =>
      val witness = witnessHtlcSuccess(localSig, remoteSig, paymentPreimage, redeemScript, commitmentFormat)
      htlcSuccessTx.copy(tx = htlcSuccessTx.tx.updateWitness(0, witness))
    case _: RedeemInfo.TaprootScriptPath => htlcSuccessTx
  }

  def addSigs(htlcTimeoutTx: HtlcTimeoutTx, localSig: ByteVector64, remoteSig: ByteVector64, commitmentFormat: CommitmentFormat): HtlcTimeoutTx = htlcTimeoutTx.redeemInfo match {
    case RedeemInfo.SegwitV0(redeemScript) =>
      val witness = witnessHtlcTimeout(localSig, remoteSig, redeemScript, commitmentFormat)
      htlcTimeoutTx.copy(tx = htlcTimeoutTx.tx.updateWitness(0, witness))
    case _: RedeemInfo.TaprootScriptPath => htlcTimeoutTx
  }

  def addSigs(claimHtlcSuccessTx: ClaimHtlcSuccessTx, localSig: ByteVector64, paymentPreimage: ByteVector32): ClaimHtlcSuccessTx = claimHtlcSuccessTx.redeemInfo match {
    case RedeemInfo.SegwitV0(redeemScript) =>
      val witness = witnessClaimHtlcSuccessFromCommitTx(localSig, paymentPreimage, redeemScript)
      claimHtlcSuccessTx.copy(tx = claimHtlcSuccessTx.tx.updateWitness(0, witness))
    case _: RedeemInfo.TaprootScriptPath => claimHtlcSuccessTx
  }

  def addSigs(claimHtlcTimeoutTx: ClaimHtlcTimeoutTx, localSig: ByteVector64): ClaimHtlcTimeoutTx = claimHtlcTimeoutTx.redeemInfo match {
    case RedeemInfo.SegwitV0(redeemScript) =>
      val witness = witnessClaimHtlcTimeoutFromCommitTx(localSig, redeemScript)
      claimHtlcTimeoutTx.copy(tx = claimHtlcTimeoutTx.tx.updateWitness(0, witness))
    case _: RedeemInfo.TaprootScriptPath => claimHtlcTimeoutTx
  }

  def addSigs(claimP2WPKHOutputTx: ClaimP2WPKHOutputTx, localPaymentPubkey: PublicKey, localSig: ByteVector64): ClaimP2WPKHOutputTx = {
    val witness = ScriptWitness(Seq(der(localSig), localPaymentPubkey.value))
    claimP2WPKHOutputTx.copy(tx = claimP2WPKHOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimRemoteDelayedOutputTx: ClaimRemoteDelayedOutputTx, localSig: ByteVector64): ClaimRemoteDelayedOutputTx = claimRemoteDelayedOutputTx.redeemInfo match {
    case RedeemInfo.SegwitV0(redeemScript) =>
      val witness = witnessClaimToRemoteDelayedFromCommitTx(localSig, redeemScript)
      claimRemoteDelayedOutputTx.copy(tx = claimRemoteDelayedOutputTx.tx.updateWitness(0, witness))
    case _: RedeemInfo.TaprootScriptPath => claimRemoteDelayedOutputTx
  }

  def addSigs(claimDelayedOutputTx: ClaimLocalDelayedOutputTx, localSig: ByteVector64): ClaimLocalDelayedOutputTx = claimDelayedOutputTx.redeemInfo match {
    case RedeemInfo.SegwitV0(redeemScript) =>
      val witness = witnessToLocalDelayedAfterDelay(localSig, redeemScript)
      claimDelayedOutputTx.copy(tx = claimDelayedOutputTx.tx.updateWitness(0, witness))
    case _: RedeemInfo.TaprootScriptPath => claimDelayedOutputTx
  }

  def addSigs(htlcDelayedTx: HtlcDelayedTx, localSig: ByteVector64): HtlcDelayedTx = htlcDelayedTx.redeemInfo match {
    case RedeemInfo.SegwitV0(redeemScript) =>
      val witness = witnessToLocalDelayedAfterDelay(localSig, redeemScript)
      htlcDelayedTx.copy(tx = htlcDelayedTx.tx.updateWitness(0, witness))
    case _: RedeemInfo.TaprootScriptPath => htlcDelayedTx
  }

  def addSigs(claimAnchorOutputTx: ClaimLocalAnchorOutputTx, localSig: ByteVector64): ClaimLocalAnchorOutputTx = claimAnchorOutputTx.redeemInfo match {
    case RedeemInfo.SegwitV0(redeemScript) =>
      val witness = witnessAnchor(localSig, redeemScript)
      claimAnchorOutputTx.copy(tx = claimAnchorOutputTx.tx.updateWitness(0, witness))
    case _: RedeemInfo.TaprootKeyPath => claimAnchorOutputTx
  }

  def addSigs(claimHtlcDelayedPenalty: ClaimHtlcDelayedOutputPenaltyTx, revocationSig: ByteVector64): ClaimHtlcDelayedOutputPenaltyTx = claimHtlcDelayedPenalty.redeemInfo match {
    case RedeemInfo.SegwitV0(redeemScript) =>
      val witness = Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, redeemScript)
      claimHtlcDelayedPenalty.copy(tx = claimHtlcDelayedPenalty.tx.updateWitness(0, witness))
    case _: RedeemInfo.TaprootKeyPath => claimHtlcDelayedPenalty
  }

  def addSigs(closingTx: ClosingTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): ClosingTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    closingTx.copy(tx = closingTx.tx.updateWitness(0, witness))
  }

  def checkSpendable(txInfo: TransactionWithInputInfo): Try[Unit] = {
    // NB: we don't verify the other inputs as they should only be wallet inputs used to RBF the transaction.
    Try(Transaction.correctlySpends(txInfo.tx, Map(txInfo.input.outPoint -> txInfo.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
  }
}
