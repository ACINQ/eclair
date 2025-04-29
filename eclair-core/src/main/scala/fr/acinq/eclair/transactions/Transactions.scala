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

import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.SigHash._
import fr.acinq.bitcoin.SigVersion._
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.{ConfirmationTarget, FeeratePerKw}
import fr.acinq.eclair.crypto.keymanager.{CommitmentPublicKeys, RemoteCommitmentKeys}
import fr.acinq.eclair.transactions.CommitmentOutput._
import fr.acinq.eclair.transactions.RedeemInfo.{SegwitV0, TaprootKeyPath, TaprootScriptPath}
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

  sealed trait CommitmentFormat {
    // @formatter:off
    def commitWeight: Int
    def htlcOutputWeight: Int
    def htlcTimeoutWeight: Int
    def htlcSuccessWeight: Int
    def htlcTimeoutInputWeight: Int
    def htlcSuccessInputWeight: Int
    def claimHtlcSuccessWeight: Int
    def claimHtlcTimeoutWeight: Int
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
    override val claimHtlcSuccessWeight = 571
    override val claimHtlcTimeoutWeight = 545
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
    override val htlcTimeoutInputWeight = 452 // 288 + 4 * 41
    override val htlcSuccessInputWeight = 491 // 327 + 4 * 41
    override val claimHtlcSuccessWeight = 571
    override val claimHtlcTimeoutWeight = 545
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

  case object SimpleTaprootChannelCommitmentFormat extends CommitmentFormat {
    // weights for taproot transactions are deterministic since signatures are encoded as 64 bytes and
    // not in variable length DER format (around 72 bytes)

    // commit tx witness is just a single 64 bytes signature
    override val commitWeight = 960
    // HTLC output weight remains the same
    override val htlcOutputWeight = 172

    // witness is remote sig (64 + 1 bytes + local sig (64 bytes) + script (68 bytes) + control block (65 bytes)
    override val htlcTimeoutWeight = 645
    // witness is remote sig (64 + 1 bytes + local sig (64 bytes) + preimage (32 bytes) +  script (95 bytes) + control block (65 bytes)
    override val htlcSuccessWeight = 705

    // witness is remote sig (64 + 1 bytes + local sig (64 bytes) + script (68 bytes) + control block (65 bytes)
    // input weight = 4 * 41 (input without witness) + 174 (witness)
    override val htlcTimeoutInputWeight = 431

    // witness is remote sig (64 + 1 bytes + local sig (64 bytes) + preimage (32 bytes) +  script (95 bytes) + control block (65 bytes)
    // input weight = 4 * 41 (input without witness) + 229 (witness)
    override val htlcSuccessInputWeight = 491

    override val claimHtlcSuccessWeight = 559
    override val claimHtlcTimeoutWeight = 504
  }

  // @formatter:off
  case class OutputInfo(index: Long, amount: Satoshi, publicKeyScript: ByteVector)

  case class InputInfo(outPoint: OutPoint, txOut: TxOut)

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
    /** Sighash flags to use when signing the transaction. */
    def sighash(txOwner: TxOwner, commitmentFormat: CommitmentFormat): Int = commitmentFormat match {
      case SimpleTaprootChannelCommitmentFormat => SIGHASH_DEFAULT
      case DefaultCommitmentFormat | _:AnchorOutputsCommitmentFormat => SIGHASH_ALL
    }

    /**
     * @param extraUtxos extra outputs spent by this transaction (in addition to the main [[input]]).
     */
    def sign(key: PrivateKey, redeemInfo: RedeemInfo, txOwner: TxOwner, commitmentFormat: CommitmentFormat, extraUtxos: Map[OutPoint, TxOut]): ByteVector64 = {
      sign(key, redeemInfo, sighash(txOwner, commitmentFormat), extraUtxos)
    }

    def sign(key: PrivateKey, redeemInfo: RedeemInfo, sighashType: Int, extraUtxos: Map[OutPoint, TxOut]): ByteVector64 = {
      val inputsMap = extraUtxos + (input.outPoint -> input.txOut)
      tx.txIn.foreach(txIn => {
        // Note that using a require here is dangerous, because callers don't except this function to throw.
        // But we want to ensure that we're correctly providing input details, otherwise our signature will silently be
        // invalid when using taproot. We verify this in all cases, even when using segwit v0, to ensure that we have as
        // many tests as possible that exercise this codepath.
        require(inputsMap.contains(txIn.outPoint), s"cannot sign $desc with txId=${tx.txid}: missing input details for ${txIn.outPoint}")
      })
      val inputIndex = tx.txIn.indexWhere(_.outPoint == input.outPoint)
      redeemInfo match {
        case RedeemInfo.SegwitV0(redeemScript) =>
          // NB: the tx may have multiple inputs, we will only sign the one provided in txinfo.input. Bear in mind that the
          // signature will be invalidated if other inputs are added *afterwards* and sighashType was SIGHASH_ALL.
          val sigDER = Transaction.signInput(tx, inputIndex, redeemScript, sighashType, input.txOut.amount, SIGVERSION_WITNESS_V0, key)
          Crypto.der2compact(sigDER)
        case t :RedeemInfo.TaprootKeyPath =>
          val spentOutputs = tx.txIn.map(txIn => inputsMap(txIn.outPoint))
          Transaction.signInputTaprootKeyPath(key, tx, tx.txIn.indexWhere(_.outPoint == input.outPoint), spentOutputs, sighashType, t.scriptTree_opt)
        case s: RedeemInfo.TaprootScriptPath =>
          val spentOutputs = tx.txIn.map(txIn => inputsMap(txIn.outPoint))
          Transaction.signInputTaprootScriptPath(key, tx, tx.txIn.indexWhere(_.outPoint == input.outPoint), spentOutputs, sighashType, s.leafHash)
      }
    }

    def checkSig(sig: ByteVector64, redeemInfo: RedeemInfo, pubKey: PublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): Boolean = redeemInfo match {
      case _: RedeemInfo.TaprootKeyPath =>
        val data = Transaction.hashForSigningTaprootKeyPath(tx, inputIndex = 0, Seq(input.txOut), sighash(txOwner, commitmentFormat))
        Crypto.verifySignatureSchnorr(data, sig, pubKey.xOnly)
      case s: RedeemInfo.TaprootScriptPath =>
        val data = Transaction.hashForSigningTaprootScriptPath(tx, inputIndex = 0, Seq(input.txOut), sighash(txOwner, commitmentFormat), s.leafHash)
        Crypto.verifySignatureSchnorr(data, sig, pubKey.xOnly)
      case RedeemInfo.SegwitV0(redeemScript) =>
        val sighash = this.sighash(txOwner, commitmentFormat)
        val inputIndex = tx.txIn.indexWhere(_.outPoint == input.outPoint)
        if (inputIndex >= 0) {
          val data = Transaction.hashForSigning(tx, inputIndex, redeemScript, sighash, input.txOut.amount, SIGVERSION_WITNESS_V0)
          Crypto.verifySignature(data, sig, pubKey)
        } else {
          false
        }
      case _ => false
    }
  }
  // @formatter:on

  sealed trait ReplaceableTransactionWithInputInfo extends TransactionWithInputInfo {
    /** Block before which the transaction must be confirmed. */
    def confirmationTarget: ConfirmationTarget
  }

  case class SpliceTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override def desc: String = "splice-tx"
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
    def htlcId: Long

    override def sighash(txOwner: TxOwner, commitmentFormat: CommitmentFormat): Int = commitmentFormat match {
      case DefaultCommitmentFormat => SIGHASH_ALL
      case _: AnchorOutputsCommitmentFormat => txOwner match {
        case TxOwner.Local => SIGHASH_ALL
        case TxOwner.Remote => SIGHASH_SINGLE | SIGHASH_ANYONECANPAY
      }
      case SimpleTaprootChannelCommitmentFormat => txOwner match {
        case TxOwner.Local => SIGHASH_DEFAULT
        case TxOwner.Remote => SIGHASH_SINGLE | SIGHASH_ANYONECANPAY
      }
    }

    override def confirmationTarget: ConfirmationTarget.Absolute
  }

  case class HtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, expiry: CltvExpiry, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute) extends HtlcTx {
    override val desc: String = "htlc-success"
  }

  case class HtlcTimeoutTx(input: InputInfo, tx: Transaction, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute, paymentHash: RipemdOfPaymentHash) extends HtlcTx {
    override val desc: String = "htlc-timeout"
  }

  case class HtlcDelayedTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override val desc: String = "htlc-delayed"
  }

  sealed trait ClaimHtlcTx extends ReplaceableTransactionWithInputInfo {
    def htlcId: Long

    override def confirmationTarget: ConfirmationTarget.Absolute
  }

  case class ClaimHtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32, htlcId: Long, confirmationTarget: ConfirmationTarget.Absolute) extends ClaimHtlcTx {
    override val desc: String = "claim-htlc-success"
  }

  case class ClaimHtlcTimeoutTx(input: InputInfo, tx: Transaction, htlcId: Long, paymentHash: RipemdOfPaymentHash, cltvExpiry: CltvExpiry, confirmationTarget: ConfirmationTarget.Absolute) extends ClaimHtlcTx {
    override val desc: String = "claim-htlc-timeout"
  }

  case class ClaimAnchorOutputTx(input: InputInfo, tx: Transaction, confirmationTarget: ConfirmationTarget) extends ReplaceableTransactionWithInputInfo {
    override val desc: String = "local-anchor"
  }

  sealed trait ClaimRemoteCommitMainOutputTx extends TransactionWithInputInfo

  case class ClaimP2WPKHOutputTx(input: InputInfo, tx: Transaction) extends ClaimRemoteCommitMainOutputTx {
    override val desc: String = "remote-main"
  }

  case class ClaimRemoteDelayedOutputTx(input: InputInfo, tx: Transaction) extends ClaimRemoteCommitMainOutputTx {
    override val desc: String = "remote-main-delayed"
  }

  case class ClaimLocalDelayedOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override def desc: String = "local-main-delayed"
  }

  case class MainPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override val desc: String = "main-penalty"
  }

  case class HtlcPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override val desc: String = "htlc-penalty"
  }

  case class ClaimHtlcDelayedOutputPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo {
    override val desc: String = "htlc-delayed-penalty"
  }

  case class ClosingTx(input: InputInfo, tx: Transaction, toLocalOutput: Option[OutputInfo]) extends TransactionWithInputInfo {
    override val desc: String = "closing"

    def addSigs(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): ClosingTx = {
      val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
      copy(tx = tx.updateWitness(0, witness))
    }
  }

  // @formatter:off
  sealed trait TxGenerationSkipped
  case object OutputNotFound extends TxGenerationSkipped { override def toString = "output not found (probably trimmed)" }
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
  val htlcDelayedWeight = 483
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
      case _: AnchorOutputsCommitmentFormat | SimpleTaprootChannelCommitmentFormat => AnchorOutputsCommitmentFormat.anchorAmount * 2
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
    case _: AnchorOutputsCommitmentFormat | SimpleTaprootChannelCommitmentFormat => 1 // htlc txs have a 1-block delay to allow CPFP carve-out on anchors
  }

  def makeCommitTxOutputs(localFundingPublicKey: PublicKey,
                          remoteFundingPublicKey: PublicKey,
                          commitmentKeys: CommitmentPublicKeys,
                          payCommitTxFees: Boolean,
                          dustLimit: Satoshi,
                          toSelfDelay: CltvExpiryDelta,
                          spec: CommitmentSpec,
                          commitmentFormat: CommitmentFormat): Seq[CommitmentOutput] = {
    import KotlinUtils._

    val outputs = collection.mutable.ArrayBuffer.empty[CommitmentOutput]

    trimOfferedHtlcs(dustLimit, spec, commitmentFormat).foreach { htlc =>
      val fee = weight2fee(spec.htlcTxFeerate(commitmentFormat), commitmentFormat.htlcTimeoutWeight)
      val amount = htlc.add.amountMsat.truncateToSatoshi - fee
      val (redeemInfo, htlcTimeoutRedeemInfo) = commitmentFormat match {
        case SimpleTaprootChannelCommitmentFormat =>
          val offeredHtlcTree = Taproot.offeredHtlcScriptTree(commitmentKeys, htlc.add.paymentHash)
          val htlcDelayedTree = Taproot.htlcDelayedScriptTree(commitmentKeys, toSelfDelay)
          TaprootScriptPath(commitmentKeys.revocationPublicKey.xOnly, offeredHtlcTree, offeredHtlcTree.getLeft.hash()) ->
            TaprootScriptPath(commitmentKeys.revocationPublicKey.xOnly, htlcDelayedTree, htlcDelayedTree.hash())
        case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat =>
          SegwitV0(htlcOffered(commitmentKeys, htlc.add.paymentHash, commitmentFormat)) -> SegwitV0(toLocalDelayed(commitmentKeys, toSelfDelay))
      }
      if (amount >= dustLimit) {
        outputs.append(OutHtlc(htlc.add.amountMsat.truncateToSatoshi, htlc, redeemInfo, HtlcTimeoutOutput(amount, htlcTimeoutRedeemInfo)))
      } else {
        outputs.append(OutHtlcWithoutHtlcTimeout(htlc.add.amountMsat.truncateToSatoshi, htlc, redeemInfo))
      }
    }

    trimReceivedHtlcs(dustLimit, spec, commitmentFormat).foreach { htlc =>
      val fee = weight2fee(spec.htlcTxFeerate(commitmentFormat), commitmentFormat.htlcSuccessWeight)
      val amount = htlc.add.amountMsat.truncateToSatoshi - fee
      val (redeemInfo, htlcSuccessRedeemInfo) = commitmentFormat match {
        case SimpleTaprootChannelCommitmentFormat =>
          val receivedHtlcTree = Taproot.receivedHtlcScriptTree(commitmentKeys, htlc.add.paymentHash, htlc.add.cltvExpiry)
          val htlcDelayedTree = Taproot.htlcDelayedScriptTree(commitmentKeys, toSelfDelay)
          TaprootScriptPath(commitmentKeys.revocationPublicKey.xOnly, receivedHtlcTree, receivedHtlcTree.getRight.hash()) ->
            TaprootScriptPath(commitmentKeys.revocationPublicKey.xOnly, htlcDelayedTree, htlcDelayedTree.hash())
        case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat =>
          SegwitV0(htlcReceived(commitmentKeys, htlc.add.paymentHash, htlc.add.cltvExpiry, commitmentFormat)) -> SegwitV0(toLocalDelayed(commitmentKeys, toSelfDelay))
      }
      if (amount >= dustLimit) {
        outputs.append(InHtlc(htlc.add.amountMsat.truncateToSatoshi, htlc, redeemInfo, HtlcSuccessOutput(amount, htlcSuccessRedeemInfo)))
      } else {
        outputs.append(InHtlcWithoutHtlcSuccess(htlc.add.amountMsat.truncateToSatoshi, htlc, redeemInfo))
      }
    }

    val hasHtlcs = outputs.nonEmpty

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (payCommitTxFees) {
      (spec.toLocal.truncateToSatoshi - commitTxTotalCost(dustLimit, spec, commitmentFormat), spec.toRemote.truncateToSatoshi)
    } else {
      (spec.toLocal.truncateToSatoshi, spec.toRemote.truncateToSatoshi - commitTxTotalCost(dustLimit, spec, commitmentFormat))
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

    if (toLocalAmount >= dustLimit) {
      commitmentFormat match {
        case SimpleTaprootChannelCommitmentFormat =>
          val scriptTree = Taproot.toLocalScriptTree(commitmentKeys, toSelfDelay)
          outputs.append(ToLocal(toLocalAmount, TaprootScriptPath(NUMS_POINT.xOnly, scriptTree, scriptTree.getLeft.hash())))
        case _: AnchorOutputsCommitmentFormat | DefaultCommitmentFormat =>
          outputs.append(ToLocal(toLocalAmount, SegwitV0(toLocalDelayed(commitmentKeys, toSelfDelay))))
      }
    }

    if (toRemoteAmount >= dustLimit) {
      val commitmentOutput = commitmentFormat match {
        case SimpleTaprootChannelCommitmentFormat =>
          val scripTree = Taproot.toRemoteScriptTree(commitmentKeys)
          ToRemote(toRemoteAmount, TaprootScriptPath(NUMS_POINT.xOnly, scripTree, scripTree.hash()))
        case DefaultCommitmentFormat =>
          // special case: redeem script for p2wpkh is a legacy p2pkh script
          ToRemote(toRemoteAmount, SegwitV0(Script.pay2pkh(commitmentKeys.remotePaymentPublicKey)))
        case _: AnchorOutputsCommitmentFormat =>
          ToRemote(toRemoteAmount, SegwitV0(toRemoteDelayed(commitmentKeys)))
      }
      outputs.append(commitmentOutput)
    }

    if (toLocalAmount >= dustLimit || hasHtlcs) {
      commitmentFormat match {
        case SimpleTaprootChannelCommitmentFormat =>
          outputs.append(ToLocalAnchor(AnchorOutputsCommitmentFormat.anchorAmount, TaprootKeyPath(commitmentKeys.localDelayedPaymentPublicKey.xOnly, Some(Taproot.anchorScriptTree))))
        case _: AnchorOutputsCommitmentFormat =>
          outputs.append(ToLocalAnchor(AnchorOutputsCommitmentFormat.anchorAmount, SegwitV0(anchor(localFundingPublicKey))))
        case DefaultCommitmentFormat => ()
      }
    }
    if (toRemoteAmount >= dustLimit || hasHtlcs) {
      commitmentFormat match {
        case SimpleTaprootChannelCommitmentFormat =>
          outputs.append(ToRemoteAnchor(AnchorOutputsCommitmentFormat.anchorAmount, TaprootKeyPath(commitmentKeys.remotePaymentPublicKey.xOnly, Some(Taproot.anchorScriptTree))))
        case _: AnchorOutputsCommitmentFormat =>
          outputs.append(ToRemoteAnchor(AnchorOutputsCommitmentFormat.anchorAmount, SegwitV0(anchor(remoteFundingPublicKey))))
        case DefaultCommitmentFormat => ()
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
      lockTime = lockTime)

    CommitTx(commitTxInput, tx)
  }

  private def makeHtlcTimeoutTx(commitTx: Transaction,
                                output: OutHtlc,
                                outputIndex: Int,
                                commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcTimeoutTx] = {
    val htlc = output.outgoingHtlc.add
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
    val tx = Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
      txOut = output.htlcTimeoutOutput.txOut :: Nil,
      lockTime = htlc.cltvExpiry.toLong
    )
    Right(HtlcTimeoutTx(input, tx, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong)), RipemdOfPaymentHash(htlc.paymentHash)))
  }

  private def makeHtlcSuccessTx(commitTx: Transaction,
                                output: InHtlc,
                                outputIndex: Int,
                                commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, HtlcSuccessTx] = {
    val htlc = output.incomingHtlc.add
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
    val tx = Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
      txOut = output.htlcSuccessOutput.txOut :: Nil,
      lockTime = 0
    )
    Right(HtlcSuccessTx(input, tx, htlc.paymentHash, htlc.cltvExpiry, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong))))
  }

  def makeHtlcTxs(commitTx: Transaction,
                  outputs: Seq[CommitmentOutput],
                  commitmentFormat: CommitmentFormat): Seq[(HtlcTx, RedeemInfo)] = {
    val htlcTimeoutTxs = outputs.zipWithIndex.collect {
      case (co: OutHtlc, outputIndex) => makeHtlcTimeoutTx(commitTx, co, outputIndex, commitmentFormat).map(_ -> co.redeemInfo)
    }.collect { case Right(htlcTimeoutTx) => htlcTimeoutTx }


    val htlcSuccessTxs = outputs.zipWithIndex.collect {
      case (ci: InHtlc, outputIndex) => makeHtlcSuccessTx(commitTx, ci, outputIndex, commitmentFormat).map(_ -> ci.redeemInfo)
    }.collect { case Right(htlcSuccessTx) => htlcSuccessTx }

    htlcTimeoutTxs ++ htlcSuccessTxs
  }

  def makeClaimHtlcSuccessTx(commitTx: Transaction,
                             outputs: Seq[CommitmentOutput],
                             dustLimit: Satoshi,
                             localFinalScriptPubKey: ByteVector,
                             htlc: UpdateAddHtlc,
                             feeratePerKw: FeeratePerKw,
                             commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimHtlcSuccessTx] = {
    outputs.zipWithIndex.collectFirst {
      case (o: OutHtlc, outputIndex) if o.outgoingHtlc.add.id == htlc.id =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
        val unsignedTx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
          lockTime = 0
        )
        val unsignedClaimTx = ClaimHtlcSuccessTx(input, unsignedTx, htlc.paymentHash, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong)))
        val solver = Solver.ClaimHtlcSuccess(RemoteCommitmentKeys(Left(PlaceHolderPubKey), PlaceHolderPubKey, PlaceHolderPubKey, PrivateKey(ByteVector32.One), PlaceHolderPubKey, PlaceHolderPubKey), unsignedClaimTx, commitmentFormat)
        val dummySignedTx = solver.addSig(unsignedClaimTx, SolverData.ClaimHtlcSuccess(PlaceHolderSig, ByteVector32.Zeroes))
        skipTxIfBelowDust(dummySignedTx, feeratePerKw, dustLimit).map { amount =>
          val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
          ClaimHtlcSuccessTx(input, tx, htlc.paymentHash, htlc.id, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong)))
        }
    }.getOrElse(Left(OutputNotFound))
  }

  def makeClaimHtlcTimeoutTx(commitTx: Transaction,
                             outputs: Seq[CommitmentOutput],
                             dustLimit: Satoshi,
                             localFinalScriptPubKey: ByteVector,
                             htlc: UpdateAddHtlc,
                             feeratePerKw: FeeratePerKw,
                             commitmentFormat: CommitmentFormat): Either[TxGenerationSkipped, ClaimHtlcTimeoutTx] = {
    outputs.zipWithIndex.collectFirst {
      case (i: InHtlc, outputIndex) if i.incomingHtlc.add.id == htlc.id =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex))
        val unsignedTx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, getHtlcTxInputSequence(commitmentFormat)) :: Nil,
          txOut = TxOut(0 sat, localFinalScriptPubKey) :: Nil,
          lockTime = htlc.cltvExpiry.toLong
        )
        val unsignedClaimTx = ClaimHtlcTimeoutTx(input, unsignedTx, htlc.id, RipemdOfPaymentHash(htlc.paymentHash), htlc.cltvExpiry, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong)))
        val solver = Solver.ClaimHtlcTimeout(RemoteCommitmentKeys(Left(PlaceHolderPubKey), PlaceHolderPubKey, PlaceHolderPubKey, PrivateKey(ByteVector32.One), PlaceHolderPubKey, PlaceHolderPubKey), unsignedClaimTx, commitmentFormat)
        val dummySignedTx = solver.addSig(unsignedClaimTx, SolverData.SingleSig(PlaceHolderSig))
        skipTxIfBelowDust(dummySignedTx, feeratePerKw, dustLimit).map { amount =>
          val tx = unsignedTx.copy(txOut = TxOut(amount, localFinalScriptPubKey) :: Nil)
          ClaimHtlcTimeoutTx(input, tx, htlc.id, RipemdOfPaymentHash(htlc.paymentHash), htlc.cltvExpiry, ConfirmationTarget.Absolute(BlockHeight(htlc.cltvExpiry.toLong)))
        }
    }.getOrElse(Left(OutputNotFound))
  }

  def makeClosingTx(commitTxInput: InputInfo, localScriptPubKey: ByteVector, remoteScriptPubKey: ByteVector, localPaysClosingFees: Boolean, dustLimit: Satoshi, closingFee: Satoshi, spec: CommitmentSpec): ClosingTx = {
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
      lockTime = 0))
    val toLocalOutput = findPubKeyScriptIndex(tx, localScriptPubKey).map(index => OutputInfo(index, toLocalAmount, localScriptPubKey)).toOption
    ClosingTx(commitTxInput, tx, toLocalOutput)
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
        val Right(toLocalOutputInfo) = findPubKeyScriptIndex(txLocalAndRemote, localScriptPubKey).map(index => OutputInfo(index, toLocalOutput.amount, localScriptPubKey))
        ClosingTxs(
          localAndRemote_opt = Some(ClosingTx(input, txLocalAndRemote, Some(toLocalOutputInfo))),
          // We also provide a version of the transaction without the remote output, which they may want to omit if not economical to spend.
          localOnly_opt = Some(ClosingTx(input, txNoOutput.copy(txOut = Seq(toLocalOutput)), Some(OutputInfo(0, toLocalOutput.amount, localScriptPubKey)))),
          remoteOnly_opt = None
        )
      case (Some(toLocalOutput), None) =>
        ClosingTxs(
          localAndRemote_opt = None,
          localOnly_opt = Some(ClosingTx(input, txNoOutput.copy(txOut = Seq(toLocalOutput)), Some(OutputInfo(0, toLocalOutput.amount, localScriptPubKey)))),
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
   * @param dummySignedTx the transaction with a witness filled with dummy signatures (to compute its weight).
   * @return the output amount, unless the transaction should be skipped because it's below dust.
   */
  def skipTxIfBelowDust(dummySignedTx: TransactionWithInputInfo, feerate: FeeratePerKw, dustLimit: Satoshi): Either[TxGenerationSkipped, Satoshi] = {
    val fee = weight2fee(feerate, dummySignedTx.tx.weight())
    val amount = dummySignedTx.input.txOut.amount - fee
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

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: Seq[ScriptElt]): Either[TxGenerationSkipped, Int] = findPubKeyScriptIndex(tx, Script.write(pubkeyScript))

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
}
