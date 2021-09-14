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

import java.nio.ByteOrder
import fr.acinq.bitcoin.scala.Crypto.{PrivateKey, PublicKey, ripemd160}
import fr.acinq.bitcoin.scala.Script._
import fr.acinq.bitcoin.scala.SigVersion._
import fr.acinq.bitcoin.scala._
import fr.acinq.eclair._
import fr.acinq.eclair.transactions.CommitmentOutput.{InHtlc, OutHtlc, ToLocal, ToRemote}
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.wire.UpdateAddHtlc
import scodec.bits.ByteVector
import scala.util.Try

/**
 * Created by PM on 15/12/2016.
 */
object Transactions {

  // @formatter:off
  case class InputInfo(outPoint: OutPoint, txOut: TxOut, redeemScript: ByteVector)
  object InputInfo {
    def apply(outPoint: OutPoint, txOut: TxOut, redeemScript: Seq[ScriptElt]) = new InputInfo(outPoint, txOut, Script.write(redeemScript))
  }

  sealed trait TransactionWithInputInfo {
    def input: InputInfo
    def tx: Transaction
    def fee: Satoshi = input.txOut.amount - tx.txOut.map(_.amount).sum
    def minRelayFee: Satoshi = {
      val vsize = (tx.weight() + 3) / 4
      Satoshi(fr.acinq.eclair.MinimumRelayFeeRate * vsize / 1000)
    }
  }

  case class CommitTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: ByteVector32) extends TransactionWithInputInfo
  case class HtlcTimeoutTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcSuccessTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcTimeoutTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimP2WPKHOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimDelayedOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimDelayedOutputPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class MainPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClosingTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo

  sealed trait TxGenerationSkipped
  case object OutputNotFound extends TxGenerationSkipped { override def toString = "output not found (probably trimmed)" }
  case object AmountBelowDustLimit extends TxGenerationSkipped { override def toString = "amount is below dust limit" }
  // @formatter:on

  /**
   * When *local* *current* [[CommitTx]] is published:
   *   - [[ClaimDelayedOutputTx]] spends to-local output of [[CommitTx]] after a delay
   *   - [[HtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
   *     - [[ClaimDelayedOutputTx]] spends [[HtlcSuccessTx]] after a delay
   *   - [[HtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
   *     - [[ClaimDelayedOutputTx]] spends [[HtlcTimeoutTx]] after a delay
   *
   * When *remote* *current* [[CommitTx]] is published:
   *   - [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
   *   - [[ClaimHtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
   *   - [[ClaimHtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
   *
   * When *remote* *revoked* [[CommitTx]] is published:
   *   - [[ClaimP2WPKHOutputTx]] spends to-local output of [[CommitTx]]
   *   - [[MainPenaltyTx]] spends remote main output using the per-commitment secret
   *   - [[HtlcSuccessTx]] spends htlc-sent outputs of [[CommitTx]] for which they have the preimage (published by remote)
   *     - [[ClaimDelayedOutputPenaltyTx]] spends [[HtlcSuccessTx]] using the revocation secret (published by local)
   *   - [[HtlcTimeoutTx]] spends htlc-received outputs of [[CommitTx]] after a timeout (published by remote)
   *     - [[ClaimDelayedOutputPenaltyTx]] spends [[HtlcTimeoutTx]] using the revocation secret (published by local)
   *   - [[HtlcPenaltyTx]] spends competes with [[HtlcSuccessTx]] and [[HtlcTimeoutTx]] for the same outputs (published by local)
   */

  /**
   * these values are defined in the RFC
   */
  val commitWeight = 724
  val htlcOutputWeight = 172
  val htlcTimeoutWeight = 663
  val htlcSuccessWeight = 703

  /**
   * these values specific to us and used to estimate fees
   */
  val claimP2WPKHOutputWeight = 438
  val claimHtlcDelayedWeight = 483
  val claimHtlcSuccessWeight = 571
  val claimHtlcTimeoutWeight = 545
  val mainPenaltyWeight = 484
  val htlcPenaltyWeight = 578 // based on spending an HTLC-Success output (would be 571 with HTLC-Timeout)

  def weight2feeMsat(feeratePerKw: Long, weight: Int) = MilliSatoshi(feeratePerKw * weight)

  def weight2fee(feeratePerKw: Long, weight: Int): Satoshi = weight2feeMsat(feeratePerKw, weight).truncateToSatoshi

  /**
   * @param fee    tx fee
   * @param weight tx weight
   * @return the fee rate (in Satoshi/Kw) for this tx
   */
  def fee2rate(fee: Satoshi, weight: Int): Long = (fee.toLong * 1000L) / weight

  /** Offered HTLCs below this amount will be trimmed. */
  def offeredHtlcTrimThreshold(dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = dustLimit + weight2fee(spec.feeratePerKw, htlcTimeoutWeight)

  def trimOfferedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec): Seq[OutgoingHtlc] = {
    val threshold = offeredHtlcTrimThreshold(dustLimit, spec)
    spec.htlcs
      .collect { case o: OutgoingHtlc if o.add.amountMsat >= threshold => o }
      .toSeq
  }

  /** Received HTLCs below this amount will be trimmed. */
  def receivedHtlcTrimThreshold(dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = dustLimit + weight2fee(spec.feeratePerKw, htlcSuccessWeight)

  def trimReceivedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec): Seq[IncomingHtlc] = {
    val threshold = receivedHtlcTrimThreshold(dustLimit, spec)
    spec.htlcs
      .collect { case i: IncomingHtlc if i.add.amountMsat >= threshold => i }
      .toSeq
  }

  /** Fee for an un-trimmed HTLC. */
  def htlcOutputFee(feeratePerKw: Long): MilliSatoshi = weight2feeMsat(feeratePerKw, htlcOutputWeight)

  /**
   * While fees are generally computed in Satoshis (since this is the smallest on-chain unit), it may be useful in some
   * cases to calculate it in MilliSatoshi to avoid rounding issues.
   * If you are adding multiple fees together for example, you should always add them in MilliSatoshi and then round
   * down to Satoshi.
   */
  def commitTxFeeMsat(dustLimit: Satoshi, spec: CommitmentSpec): MilliSatoshi = {
    val trimmedOfferedHtlcs = trimOfferedHtlcs(dustLimit, spec)
    val trimmedReceivedHtlcs = trimReceivedHtlcs(dustLimit, spec)
    val weight = commitWeight + htlcOutputWeight * (trimmedOfferedHtlcs.size + trimmedReceivedHtlcs.size)
    weight2feeMsat(spec.feeratePerKw, weight)
  }

  def commitTxFee(dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = commitTxFeeMsat(dustLimit, spec).truncateToSatoshi

  /**
   *
   * @param commitTxNumber         commit tx number
   * @param isFunder               true if local node is funder
   * @param localPaymentBasePoint  local payment base point
   * @param remotePaymentBasePoint remote payment base point
   * @return the obscured tx number as defined in BOLT #3 (a 48 bits integer)
   */
  def obscuredCommitTxNumber(commitTxNumber: Long, isFunder: Boolean, localPaymentBasePoint: PublicKey, remotePaymentBasePoint: PublicKey): Long = {
    // from BOLT 3: SHA256(payment-basepoint from open_channel || payment-basepoint from accept_channel)
    val h = if (isFunder)
      Crypto.sha256(localPaymentBasePoint.value ++ remotePaymentBasePoint.value)
    else
      Crypto.sha256(remotePaymentBasePoint.value ++ localPaymentBasePoint.value)

    val blind = Protocol.uint64((h.takeRight(6).reverse ++ ByteVector.fromValidHex("0000")).toArray, ByteOrder.LITTLE_ENDIAN)
    commitTxNumber ^ blind
  }

  /**
   *
   * @param commitTx               commit tx
   * @param isFunder               true if local node is funder
   * @param localPaymentBasePoint  local payment base point
   * @param remotePaymentBasePoint remote payment base point
   * @return the actual commit tx number that was blinded and stored in locktime and sequence fields
   */
  def getCommitTxNumber(commitTx: Transaction, isFunder: Boolean, localPaymentBasePoint: PublicKey, remotePaymentBasePoint: PublicKey): Long = {
    val blind = obscuredCommitTxNumber(0, isFunder, localPaymentBasePoint, remotePaymentBasePoint)
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

  /**
   * Represent a link between a commitment spec item (to-local, to-remote, htlc) and the actual output in the commit tx
   *
   * @param output           transaction output
   * @param redeemScript     redeem script that matches this output (most of them are p2wsh)
   * @param commitmentOutput commitment spec item this output is built from
   */
  case class CommitmentOutputLink[T <: CommitmentOutput](output: TxOut, redeemScript: Seq[ScriptElt], commitmentOutput: T)

  /** Type alias for a collection of commitment output links */
  type CommitmentOutputs = Seq[CommitmentOutputLink[CommitmentOutput]]

  object CommitmentOutputLink {
    /**
     * We sort HTLC outputs according to BIP69 + CLTV as tie-breaker for offered HTLC, we do this only for the outgoing
     * HTLC because we must agree with the remote on the order of HTLC-Timeout transactions even for identical HTLC outputs.
     * See https://github.com/lightningnetwork/lightning-rfc/issues/448#issuecomment-432074187.
     */
    def sort(a: CommitmentOutputLink[CommitmentOutput], b: CommitmentOutputLink[CommitmentOutput]): Boolean = (a.commitmentOutput, b.commitmentOutput) match {
      case (OutHtlc(OutgoingHtlc(htlcA)), OutHtlc(OutgoingHtlc(htlcB))) if htlcA.paymentHash == htlcB.paymentHash && htlcA.amountMsat == htlcB.amountMsat =>
        htlcA.cltvExpiry <= htlcB.cltvExpiry
      case _ => LexicographicalOrdering.isLessThan(a.output, b.output)
    }
  }

  def makeCommitTxOutputs(localIsFunder: Boolean,
                          localDustLimit: Satoshi,
                          localRevocationPubkey: PublicKey,
                          toLocalDelay: CltvExpiryDelta,
                          localDelayedPaymentPubkey: PublicKey,
                          remotePaymentPubkey: PublicKey,
                          localHtlcPubkey: PublicKey,
                          remoteHtlcPubkey: PublicKey,
                          spec: CommitmentSpec): CommitmentOutputs = {
    val commitFee = commitTxFee(localDustLimit, spec)

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (localIsFunder) {
      (spec.toLocal.truncateToSatoshi - commitFee, spec.toRemote.truncateToSatoshi)
    } else {
      (spec.toLocal.truncateToSatoshi, spec.toRemote.truncateToSatoshi - commitFee)
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway
    val outputs = collection.mutable.ArrayBuffer.empty[CommitmentOutputLink[CommitmentOutput]]

    if (toLocalAmount >= localDustLimit) outputs.append(
      CommitmentOutputLink(
        TxOut(toLocalAmount, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))),
        toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey),
        ToLocal))

    if (toRemoteAmount >= localDustLimit) outputs.append(
      CommitmentOutputLink(
        TxOut(toRemoteAmount, pay2wpkh(remotePaymentPubkey)),
        pay2pkh(remotePaymentPubkey),
        ToRemote))

    trimOfferedHtlcs(localDustLimit, spec).foreach { htlc =>
      val redeemScript = htlcOffered(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash.bytes))
      outputs.append(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi, pay2wsh(redeemScript)), redeemScript, OutHtlc(htlc)))
    }

    trimReceivedHtlcs(localDustLimit, spec).foreach { htlc =>
      val redeemScript = htlcReceived(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash.bytes), htlc.add.cltvExpiry)
      outputs.append(CommitmentOutputLink(TxOut(htlc.add.amountMsat.truncateToSatoshi, pay2wsh(redeemScript)), redeemScript, InHtlc(htlc)))
    }

    outputs.sortWith(CommitmentOutputLink.sort).toSeq
  }

  def makeCommitTx(commitTxInput: InputInfo,
                   commitTxNumber: Long,
                   localPaymentBasePoint: PublicKey,
                   remotePaymentBasePoint: PublicKey,
                   localIsFunder: Boolean,
                   outputs: CommitmentOutputs): CommitTx = {
    val txnumber = obscuredCommitTxNumber(commitTxNumber, localIsFunder, localPaymentBasePoint, remotePaymentBasePoint)
    val (sequence, locktime) = encodeTxNumber(txnumber)

    val tx = Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = sequence) :: Nil,
      txOut = outputs.map(_.output),
      lockTime = locktime)

    CommitTx(commitTxInput, tx)
  }

  def makeHtlcTimeoutTx(commitTx: Transaction,
                        output: CommitmentOutputLink[OutHtlc],
                        outputIndex: Int,
                        localDustLimit: Satoshi,
                        localRevocationPubkey: PublicKey,
                        toLocalDelay: CltvExpiryDelta,
                        localDelayedPaymentPubkey: PublicKey,
                        feeratePerKw: Long): Either[TxGenerationSkipped, HtlcTimeoutTx] = {
    val fee = weight2fee(feeratePerKw, htlcTimeoutWeight)
    val redeemScript = output.redeemScript
    val htlc = output.commitmentOutput.outgoingHtlc.add
    val amount = htlc.amountMsat.truncateToSatoshi - fee
    if (amount < localDustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
      Right(HtlcTimeoutTx(input, Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, 0x00000000L) :: Nil,
        txOut = TxOut(amount, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))) :: Nil,
        lockTime = htlc.cltvExpiry.toLong)))
    }
  }

  def makeHtlcSuccessTx(commitTx: Transaction,
                        output: CommitmentOutputLink[InHtlc],
                        outputIndex: Int,
                        localDustLimit: Satoshi,
                        localRevocationPubkey: PublicKey,
                        toLocalDelay: CltvExpiryDelta,
                        localDelayedPaymentPubkey: PublicKey,
                        feeratePerKw: Long): Either[TxGenerationSkipped, HtlcSuccessTx] = {
    val fee = weight2fee(feeratePerKw, htlcSuccessWeight)
    val redeemScript = output.redeemScript
    val htlc = output.commitmentOutput.incomingHtlc.add
    val amount = htlc.amountMsat.truncateToSatoshi - fee
    if (amount < localDustLimit) {
      Left(AmountBelowDustLimit)
    } else {
      val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
      Right(HtlcSuccessTx(input, Transaction(
        version = 2,
        txIn = TxIn(input.outPoint, ByteVector.empty, 0x00000000L) :: Nil,
        txOut = TxOut(amount, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))) :: Nil,
        lockTime = 0), htlc.paymentHash))
    }
  }

  def makeHtlcTxs(commitTx: Transaction,
                  localDustLimit: Satoshi,
                  localRevocationPubkey: PublicKey,
                  toLocalDelay: CltvExpiryDelta,
                  localDelayedPaymentPubkey: PublicKey,
                  feeratePerKw: Long,
                  outputs: CommitmentOutputs): (Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val htlcTimeoutTxs = outputs.zipWithIndex.collect {
      case (CommitmentOutputLink(o, s, OutHtlc(ou)), outputIndex) =>
        val co = CommitmentOutputLink(o, s, OutHtlc(ou))
        makeHtlcTimeoutTx(commitTx, co, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feeratePerKw)
    }.collect { case Right(htlcTimeoutTx) => htlcTimeoutTx }
    val htlcSuccessTxs = outputs.zipWithIndex.collect {
      case (CommitmentOutputLink(o, s, InHtlc(in)), outputIndex) =>
        val co = CommitmentOutputLink(o, s, InHtlc(in))
        makeHtlcSuccessTx(commitTx, co, outputIndex, localDustLimit, localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey, feeratePerKw)
    }.collect { case Right(htlcSuccessTx) => htlcSuccessTx }
    (htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeClaimHtlcSuccessTx(commitTx: Transaction, outputs: CommitmentOutputs, localDustLimit: Satoshi, localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: ByteVector, htlc: UpdateAddHtlc, feeratePerKw: Long): Either[TxGenerationSkipped, ClaimHtlcSuccessTx] = {
    val redeemScript = htlcOffered(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, ripemd160(htlc.paymentHash.bytes))
    outputs.zipWithIndex.collectFirst {
      case (CommitmentOutputLink(_, _, OutHtlc(OutgoingHtlc(outgoingHtlc))), outIndex) if outgoingHtlc.id == htlc.id => outIndex
    } match {
      case Some(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        val weight = addSigs(ClaimHtlcSuccessTx(input, tx), PlaceHolderSig, ByteVector32.Zeroes).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          Right(ClaimHtlcSuccessTx(input, tx1))
        }
      case None => Left(OutputNotFound)
    }
  }

  def makeClaimHtlcTimeoutTx(commitTx: Transaction, outputs: CommitmentOutputs, localDustLimit: Satoshi, localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: ByteVector, htlc: UpdateAddHtlc, feeratePerKw: Long): Either[TxGenerationSkipped, ClaimHtlcTimeoutTx] = {
    val redeemScript = htlcReceived(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, ripemd160(htlc.paymentHash.bytes), htlc.cltvExpiry)
    outputs.zipWithIndex.collectFirst {
      case (CommitmentOutputLink(_, _, InHtlc(IncomingHtlc(incomingHtlc))), outIndex) if incomingHtlc.id == htlc.id => outIndex
    } match {
      case Some(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
        // unsigned tx
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0x00000000L) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = htlc.cltvExpiry.toLong)
        val weight = addSigs(ClaimHtlcTimeoutTx(input, tx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          Right(ClaimHtlcTimeoutTx(input, tx1))
        }
      case None => Left(OutputNotFound)
    }
  }

  def makeClaimP2WPKHOutputTx(delayedOutputTx: Transaction, localDustLimit: Satoshi, localPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: Long): Either[TxGenerationSkipped, ClaimP2WPKHOutputTx] = {
    val redeemScript = Script.pay2pkh(localPaymentPubkey)
    val pubkeyScript = write(pay2wpkh(localPaymentPubkey))
    findPubKeyScriptIndex(delayedOutputTx, pubkeyScript, amount_opt = None) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(delayedOutputTx, outputIndex), delayedOutputTx.txOut(outputIndex), write(redeemScript))
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

  def makeClaimDelayedOutputTx(delayedOutputTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: Long): Either[TxGenerationSkipped, ClaimDelayedOutputTx] = {
    val redeemScript = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    findPubKeyScriptIndex(delayedOutputTx, pubkeyScript, amount_opt = None) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(delayedOutputTx, outputIndex), delayedOutputTx.txOut(outputIndex), write(redeemScript))
        // unsigned transaction
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, toLocalDelay.toInt) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(ClaimDelayedOutputTx(input, tx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          Right(ClaimDelayedOutputTx(input, tx1))
        }
    }
  }

  def makeClaimDelayedOutputPenaltyTx(delayedOutputTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: CltvExpiryDelta, localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: ByteVector, feeratePerKw: Long): Either[TxGenerationSkipped, ClaimDelayedOutputPenaltyTx] = {
    val redeemScript = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    findPubKeyScriptIndex(delayedOutputTx, pubkeyScript, amount_opt = None) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(delayedOutputTx, outputIndex), delayedOutputTx.txOut(outputIndex), write(redeemScript))
        // unsigned transaction
        val tx = Transaction(
          version = 2,
          txIn = TxIn(input.outPoint, ByteVector.empty, 0xffffffffL) :: Nil,
          txOut = TxOut(Satoshi(0), localFinalScriptPubKey) :: Nil,
          lockTime = 0)
        // compute weight with a dummy 73 bytes signature (the largest you can get)
        val weight = addSigs(ClaimDelayedOutputPenaltyTx(input, tx), PlaceHolderSig).tx.weight()
        val fee = weight2fee(feeratePerKw, weight)
        val amount = input.txOut.amount - fee
        if (amount < localDustLimit) {
          Left(AmountBelowDustLimit)
        } else {
          val tx1 = tx.copy(txOut = tx.txOut.head.copy(amount = amount) :: Nil)
          Right(ClaimDelayedOutputPenaltyTx(input, tx1))
        }
    }
  }

  def makeMainPenaltyTx(commitTx: Transaction, localDustLimit: Satoshi, remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: ByteVector, toRemoteDelay: CltvExpiryDelta, remoteDelayedPaymentPubkey: PublicKey, feeratePerKw: Long): Either[TxGenerationSkipped, MainPenaltyTx] = {
    val redeemScript = toLocalDelayed(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    findPubKeyScriptIndex(commitTx, pubkeyScript, amount_opt = None) match {
      case Left(skip) => Left(skip)
      case Right(outputIndex) =>
        val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
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
  def makeHtlcPenaltyTx(commitTx: Transaction, htlcOutputIndex: Int, redeemScript: ByteVector, localDustLimit: Satoshi, localFinalScriptPubKey: ByteVector, feeratePerKw: Long): Either[TxGenerationSkipped, HtlcPenaltyTx] = {
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

  def makeClosingTx(commitTxInput: InputInfo, localScriptPubKey: ByteVector, remoteScriptPubKey: ByteVector, localIsFunder: Boolean, dustLimit: Satoshi, closingFee: Satoshi, spec: CommitmentSpec): ClosingTx = {
    require(spec.htlcs.isEmpty, "there shouldn't be any pending htlcs")

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (localIsFunder) {
      (spec.toLocal.truncateToSatoshi - closingFee, spec.toRemote.truncateToSatoshi)
    } else {
      (spec.toLocal.truncateToSatoshi, spec.toRemote.truncateToSatoshi - closingFee)
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

    val toLocalOutput_opt = if (toLocalAmount >= dustLimit) Some(TxOut(toLocalAmount, localScriptPubKey)) else None
    val toRemoteOutput_opt = if (toRemoteAmount >= dustLimit) Some(TxOut(toRemoteAmount, remoteScriptPubKey)) else None

    val tx = Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, ByteVector.empty, sequence = 0xffffffffL) :: Nil,
      txOut = toLocalOutput_opt.toSeq ++ toRemoteOutput_opt.toSeq ++ Nil,
      lockTime = 0)
    ClosingTx(commitTxInput, LexicographicalOrdering.sort(tx))
  }

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: ByteVector, amount_opt: Option[Satoshi]): Either[TxGenerationSkipped, Int] = {
    val outputIndex = tx.txOut
      .zipWithIndex
      .indexWhere { case (txOut, _) => amount_opt.forall(_ == txOut.amount) && txOut.publicKeyScript == pubkeyScript }
    if (outputIndex >= 0) {
      Right(outputIndex)
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

  def sign(tx: Transaction, inputIndex: Int, redeemScript: ByteVector, amount: Satoshi, key: PrivateKey): ByteVector64 = {
    val sigDER = Transaction.signInput(tx, inputIndex, redeemScript, SIGHASH_ALL, amount, SIGVERSION_WITNESS_V0, key)
    val sig64 = Crypto.der2compact(sigDER)
    sig64
  }

  def sign(txinfo: TransactionWithInputInfo, key: PrivateKey): ByteVector64 = {
    require(txinfo.tx.txIn.lengthCompare(1) == 0, "only one input allowed")
    sign(txinfo.tx, inputIndex = 0, txinfo.input.redeemScript, txinfo.input.txOut.amount, key)
  }

  def addSigs(commitTx: CommitTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): CommitTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    commitTx.copy(tx = commitTx.tx.updateWitness(0, witness))
  }

  def addSigs(mainPenaltyTx: MainPenaltyTx, revocationSig: ByteVector64): MainPenaltyTx = {
    val witness = Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, mainPenaltyTx.input.redeemScript)
    mainPenaltyTx.copy(tx = mainPenaltyTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcPenaltyTx: HtlcPenaltyTx, revocationSig: ByteVector64, revocationPubkey: PublicKey): HtlcPenaltyTx = {
    val witness = Scripts.witnessHtlcWithRevocationSig(revocationSig, revocationPubkey, htlcPenaltyTx.input.redeemScript)
    htlcPenaltyTx.copy(tx = htlcPenaltyTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcSuccessTx: HtlcSuccessTx, localSig: ByteVector64, remoteSig: ByteVector64, paymentPreimage: ByteVector32): HtlcSuccessTx = {
    val witness = witnessHtlcSuccess(localSig, remoteSig, paymentPreimage, htlcSuccessTx.input.redeemScript)
    htlcSuccessTx.copy(tx = htlcSuccessTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcTimeoutTx: HtlcTimeoutTx, localSig: ByteVector64, remoteSig: ByteVector64): HtlcTimeoutTx = {
    val witness = witnessHtlcTimeout(localSig, remoteSig, htlcTimeoutTx.input.redeemScript)
    htlcTimeoutTx.copy(tx = htlcTimeoutTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcSuccessTx: ClaimHtlcSuccessTx, localSig: ByteVector64, paymentPreimage: ByteVector32): ClaimHtlcSuccessTx = {
    val witness = witnessClaimHtlcSuccessFromCommitTx(localSig, paymentPreimage, claimHtlcSuccessTx.input.redeemScript)
    claimHtlcSuccessTx.copy(tx = claimHtlcSuccessTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcTimeoutTx: ClaimHtlcTimeoutTx, localSig: ByteVector64): ClaimHtlcTimeoutTx = {
    val witness = witnessClaimHtlcTimeoutFromCommitTx(localSig, claimHtlcTimeoutTx.input.redeemScript)
    claimHtlcTimeoutTx.copy(tx = claimHtlcTimeoutTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimP2WPKHOutputTx: ClaimP2WPKHOutputTx, localPaymentPubkey: PublicKey, localSig: ByteVector64): ClaimP2WPKHOutputTx = {
    val witness = ScriptWitness(Seq(der(localSig), localPaymentPubkey.value))
    claimP2WPKHOutputTx.copy(tx = claimP2WPKHOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcDelayed: ClaimDelayedOutputTx, localSig: ByteVector64): ClaimDelayedOutputTx = {
    val witness = witnessToLocalDelayedAfterDelay(localSig, claimHtlcDelayed.input.redeemScript)
    claimHtlcDelayed.copy(tx = claimHtlcDelayed.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcDelayedPenalty: ClaimDelayedOutputPenaltyTx, revocationSig: ByteVector64): ClaimDelayedOutputPenaltyTx = {
    val witness = Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, claimHtlcDelayedPenalty.input.redeemScript)
    claimHtlcDelayedPenalty.copy(tx = claimHtlcDelayedPenalty.tx.updateWitness(0, witness))
  }

  def addSigs(closingTx: ClosingTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: ByteVector64, remoteSig: ByteVector64): ClosingTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    closingTx.copy(tx = closingTx.tx.updateWitness(0, witness))
  }

  def checkSpendable(txinfo: TransactionWithInputInfo): Try[Unit] =
    Try(Transaction.correctlySpends(txinfo.tx, Map(txinfo.tx.txIn.head.outPoint -> txinfo.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

  def checkSig(txinfo: TransactionWithInputInfo, sig: ByteVector64, pubKey: PublicKey): Boolean = {
    val data = Transaction.hashForSigning(txinfo.tx, inputIndex = 0, txinfo.input.redeemScript, SIGHASH_ALL, txinfo.input.txOut.amount, SIGVERSION_WITNESS_V0)
    Crypto.verifySignature(data, sig, pubKey)
  }

}
