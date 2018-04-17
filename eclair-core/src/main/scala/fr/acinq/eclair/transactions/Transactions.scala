/*
 * Copyright 2018 ACINQ SAS
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

import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, ripemd160}
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin.SigVersion._
import fr.acinq.bitcoin.{BinaryData, Crypto, LexicographicalOrdering, MilliSatoshi, OutPoint, Protocol, SIGHASH_ALL, Satoshi, Script, ScriptElt, ScriptFlags, ScriptWitness, Transaction, TxIn, TxOut, millisatoshi2satoshi}
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.transactions.Transactions.OutputNotFound
import fr.acinq.eclair.wire.UpdateAddHtlc

import scala.util.Try

/**
  * Created by PM on 15/12/2016.
  */
object Transactions {

  // @formatter:off
  case class InputInfo(outPoint: OutPoint, txOut: TxOut, redeemScript: BinaryData)
  object InputInfo {
    def apply(outPoint: OutPoint, txOut: TxOut, redeemScript: Seq[ScriptElt]) = new InputInfo(outPoint, txOut, Script.write(redeemScript))
  }

  sealed trait TransactionWithInputInfo {
    def input: InputInfo
    def tx: Transaction
  }

  case class CommitTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcSuccessTx(input: InputInfo, tx: Transaction, paymentHash: BinaryData) extends TransactionWithInputInfo
  case class HtlcTimeoutTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcSuccessTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcTimeoutTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimP2WPKHOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimDelayedOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimDelayedOutputPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class MainPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcPenaltyTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClosingTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo

  sealed trait TxGenerationSkipped extends RuntimeException
  case object OutputNotFound extends RuntimeException(s"output not found (probably trimmed)") with TxGenerationSkipped
  case object AmountBelowDustLimit extends RuntimeException(s"amount is below dust limit") with TxGenerationSkipped

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

  val commitWeight = 724
  val htlcTimeoutWeight = 663
  val htlcSuccessWeight = 703
  val claimP2WPKHOutputWeight = 437
  val claimHtlcDelayedWeight = 482
  val claimHtlcSuccessWeight = 570
  val claimHtlcTimeoutWeight = 544
  val mainPenaltyWeight = 483
  val htlcPenaltyWeight = 577 // based on spending an HTLC-Success output (would be 571 with HTLC-Timeout)

  def weight2fee(feeratePerKw: Long, weight: Int) = Satoshi((feeratePerKw * weight) / 1000)

  /**
    *
    * @param fee    tx fee
    * @param weight tx weight
    * @return the fee rate (in Satoshi/Kw) for this tx
    */
  def fee2rate(fee: Satoshi, weight: Int) = (fee.amount * 1000L) / weight

  def trimOfferedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec): Seq[DirectedHtlc] = {
    val htlcTimeoutFee = weight2fee(spec.feeratePerKw, htlcTimeoutWeight)
    spec.htlcs
      .filter(_.direction == OUT)
      .filter(htlc => MilliSatoshi(htlc.add.amountMsat) >= (dustLimit + htlcTimeoutFee))
      .toSeq
  }

  def trimReceivedHtlcs(dustLimit: Satoshi, spec: CommitmentSpec): Seq[DirectedHtlc] = {
    val htlcSuccessFee = weight2fee(spec.feeratePerKw, htlcSuccessWeight)
    spec.htlcs
      .filter(_.direction == IN)
      .filter(htlc => MilliSatoshi(htlc.add.amountMsat) >= (dustLimit + htlcSuccessFee))
      .toSeq
  }

  def commitTxFee(dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = {
    val trimmedOfferedHtlcs = trimOfferedHtlcs(dustLimit, spec)
    val trimmedReceivedHtlcs = trimReceivedHtlcs(dustLimit, spec)
    val weight = commitWeight + 172 * (trimmedOfferedHtlcs.size + trimmedReceivedHtlcs.size)
    weight2fee(spec.feeratePerKw, weight)
  }

  /**
    *
    * @param commitTxNumber         commit tx number
    * @param isFunder               true if local node is funder
    * @param localPaymentBasePoint  local payment base point
    * @param remotePaymentBasePoint remote payment base point
    * @return the obscured tx number as defined in BOLT #3 (a 48 bits integer)
    */
  def obscuredCommitTxNumber(commitTxNumber: Long, isFunder: Boolean, localPaymentBasePoint: Point, remotePaymentBasePoint: Point): Long = {
    // from BOLT 3: SHA256(payment-basepoint from open_channel || payment-basepoint from accept_channel)
    val h = if (isFunder)
      Crypto.sha256(localPaymentBasePoint.toBin(true) ++ remotePaymentBasePoint.toBin(true))
    else
      Crypto.sha256(remotePaymentBasePoint.toBin(true) ++ localPaymentBasePoint.toBin(true))

    val blind = Protocol.uint64(h.takeRight(6).reverse ++ BinaryData("0x0000"), ByteOrder.LITTLE_ENDIAN)
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
  def getCommitTxNumber(commitTx: Transaction, isFunder: Boolean, localPaymentBasePoint: Point, remotePaymentBasePoint: Point): Long = {
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

  def makeCommitTx(commitTxInput: InputInfo, commitTxNumber: Long, localPaymentBasePoint: Point, remotePaymentBasePoint: Point, localIsFunder: Boolean, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: Int, localDelayedPaymentPubkey: PublicKey, remotePaymentPubkey: PublicKey, localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, spec: CommitmentSpec): CommitTx = {
    val commitFee = commitTxFee(localDustLimit, spec)

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (localIsFunder) {
      (millisatoshi2satoshi(MilliSatoshi(spec.toLocalMsat)) - commitFee, millisatoshi2satoshi(MilliSatoshi(spec.toRemoteMsat)))
    } else {
      (millisatoshi2satoshi(MilliSatoshi(spec.toLocalMsat)), millisatoshi2satoshi(MilliSatoshi(spec.toRemoteMsat)) - commitFee)
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

    val toLocalDelayedOutput_opt = if (toLocalAmount >= localDustLimit) Some(TxOut(toLocalAmount, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)))) else None
    val toRemoteOutput_opt = if (toRemoteAmount >= localDustLimit) Some(TxOut(toRemoteAmount, pay2wpkh(remotePaymentPubkey))) else None

    val htlcOfferedOutputs = trimOfferedHtlcs(localDustLimit, spec)
      .map(htlc => TxOut(MilliSatoshi(htlc.add.amountMsat), pay2wsh(htlcOffered(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash)))))
    val htlcReceivedOutputs = trimReceivedHtlcs(localDustLimit, spec)
      .map(htlc => TxOut(MilliSatoshi(htlc.add.amountMsat), pay2wsh(htlcReceived(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash), htlc.add.expiry))))

    val txnumber = obscuredCommitTxNumber(commitTxNumber, localIsFunder, localPaymentBasePoint, remotePaymentBasePoint)
    val (sequence, locktime) = encodeTxNumber(txnumber)

    val tx = Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, Array.emptyByteArray, sequence = sequence) :: Nil,
      txOut = toLocalDelayedOutput_opt.toSeq ++ toRemoteOutput_opt.toSeq ++ htlcOfferedOutputs ++ htlcReceivedOutputs,
      lockTime = locktime)
    CommitTx(commitTxInput, LexicographicalOrdering.sort(tx))
  }

  def makeHtlcTxs(commitTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: Int, localDelayedPaymentPubkey: PublicKey,
                  localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey, spec: CommitmentSpec): (Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {

    val finder = new PubKeyScriptIndexFinder(commitTx)

    def makeHtlcTx(redeemScript: Seq[ScriptElt], pubKeyScript: Seq[ScriptElt], amount: Satoshi, fee: Satoshi, expiry: Long) = {
      val index = finder.findPubKeyScriptIndex(pubkeyScript = Script.write(Script pay2wsh redeemScript), Option(amount))
      val inputInfo = InputInfo(OutPoint(commitTx, index), commitTx.txOut(index), Script.write(redeemScript))
      val txIn = TxIn(inputInfo.outPoint, BinaryData.empty, 0x00000000L) :: Nil
      val txOut = TxOut(amount - fee, pubKeyScript) :: Nil
      val tx = Transaction(2, txIn, txOut, expiry)
      (inputInfo, tx)
    }

    val htlcTimeoutTxs = trimOfferedHtlcs(localDustLimit, spec).map { htlc =>
      val offered = htlcOffered(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash))
      val pubKeyScript = Script.pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))
      val (info, tx) = makeHtlcTx(offered, pubKeyScript, Satoshi(htlc.add.amountMsat / 1000), weight2fee(spec.feeratePerKw, htlcTimeoutWeight), htlc.add.expiry)
      HtlcTimeoutTx(info, tx)
    }

    val htlcSuccessTxs: Seq[HtlcSuccessTx] = trimReceivedHtlcs(localDustLimit, spec).map { htlc =>
      val received = htlcReceived(localHtlcPubkey, remoteHtlcPubkey, localRevocationPubkey, ripemd160(htlc.add.paymentHash), htlc.add.expiry)
      val pubKeyScript = Script pay2wsh toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey)
      val (info, tx) = makeHtlcTx(received, pubKeyScript, Satoshi(htlc.add.amountMsat / 1000), weight2fee(spec.feeratePerKw, htlcSuccessWeight), 0L)
      HtlcSuccessTx(info, tx, htlc.add.paymentHash)
    }

    (htlcTimeoutTxs, htlcSuccessTxs)
  }

  private def makeClaimTx(feeratePerKw: Long, weight: Int, redeemScript: BinaryData, prevTx: Transaction, outputIndex: Int,
                          localDustLimit: Satoshi, localFinalScriptPubKey: BinaryData, sequence: Long, lockTime: Long) = {

    val input = InputInfo(OutPoint(prevTx, outputIndex), prevTx.txOut(outputIndex), redeemScript)
    val amount = input.txOut.amount - weight2fee(feeratePerKw, weight)
    if (amount < localDustLimit) {
      throw AmountBelowDustLimit
    }

    val tx = Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, sequence) :: Nil,
      txOut = TxOut(amount, localFinalScriptPubKey) :: Nil,
      lockTime = lockTime)

    (input, tx)
  }

  def makeClaimHtlcSuccessTx(finder: PubKeyScriptIndexFinder, localDustLimit: Satoshi, localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey,
                             remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: BinaryData, htlc: UpdateAddHtlc, feeratePerKw: Long): ClaimHtlcSuccessTx = {

    val redeemScript = write(htlcOffered(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, ripemd160(htlc.paymentHash)))
    val pubkeyScript = write(pay2wsh(redeemScript))

    ClaimHtlcSuccessTx tupled makeClaimTx(feeratePerKw, claimHtlcSuccessWeight, redeemScript, finder.tx,
      outputIndex = finder.findPubKeyScriptIndex(pubkeyScript, Some(Satoshi(htlc.amountMsat / 1000))),
      localDustLimit, localFinalScriptPubKey, sequence = 0xffffffffL, lockTime = 0L)
  }

  def makeClaimHtlcTimeoutTx(finder: PubKeyScriptIndexFinder, localDustLimit: Satoshi, localHtlcPubkey: PublicKey, remoteHtlcPubkey: PublicKey,
                             remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: BinaryData, htlc: UpdateAddHtlc, feeratePerKw: Long): ClaimHtlcTimeoutTx = {

    val redeemScript = write(htlcReceived(remoteHtlcPubkey, localHtlcPubkey, remoteRevocationPubkey, ripemd160(htlc.paymentHash), htlc.expiry))
    val pubkeyScript = write(pay2wsh(redeemScript))

    ClaimHtlcTimeoutTx tupled makeClaimTx(feeratePerKw, claimHtlcTimeoutWeight, redeemScript, finder.tx,
      outputIndex = finder.findPubKeyScriptIndex(pubkeyScript, Some(Satoshi(htlc.amountMsat / 1000))),
      localDustLimit, localFinalScriptPubKey, sequence = 0x00000000L, lockTime = htlc.expiry)
  }

  def makeClaimP2WPKHOutputTx(delayedOutputTx: Transaction, localDustLimit: Satoshi, localPaymentPubkey: PublicKey,
                              localFinalScriptPubKey: BinaryData, feeratePerKw: Long): ClaimP2WPKHOutputTx = {

    val redeemScript = write(Script.pay2pkh(localPaymentPubkey))
    val pubkeyScript = write(pay2wpkh(localPaymentPubkey))

    ClaimP2WPKHOutputTx tupled makeClaimTx(feeratePerKw, claimP2WPKHOutputWeight, redeemScript, delayedOutputTx,
      outputIndex = new PubKeyScriptIndexFinder(delayedOutputTx).findPubKeyScriptIndex(pubkeyScript, None),
      localDustLimit, localFinalScriptPubKey, sequence = 0x00000000L, lockTime = 0L)
  }

  def makeClaimDelayedOutputTx(delayedOutputTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: Int,
                               localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: BinaryData, feeratePerKw: Long): ClaimDelayedOutputTx = {

    val redeemScript = write(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))
    val pubkeyScript = write(pay2wsh(redeemScript))

    ClaimDelayedOutputTx tupled makeClaimTx(feeratePerKw, claimHtlcDelayedWeight, redeemScript, delayedOutputTx,
      outputIndex = new PubKeyScriptIndexFinder(delayedOutputTx).findPubKeyScriptIndex(pubkeyScript, None),
      localDustLimit, localFinalScriptPubKey, sequence = toLocalDelay, lockTime = 0L)
  }

  def makeClaimDelayedOutputPenaltyTx(delayedOutputTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: Int,
                                      localDelayedPaymentPubkey: PublicKey, localFinalScriptPubKey: BinaryData, feeratePerKw: Long): ClaimDelayedOutputPenaltyTx = {

    val redeemScript = write(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPaymentPubkey))
    val pubkeyScript = write(pay2wsh(redeemScript))

    ClaimDelayedOutputPenaltyTx tupled makeClaimTx(feeratePerKw, claimHtlcDelayedWeight, redeemScript, delayedOutputTx,
      outputIndex = new PubKeyScriptIndexFinder(delayedOutputTx).findPubKeyScriptIndex(pubkeyScript, None),
      localDustLimit, localFinalScriptPubKey, sequence = 0xffffffffL, lockTime = 0L)
  }

  def makeMainPenaltyTx(commitTx: Transaction, localDustLimit: Satoshi, remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: BinaryData,
                        toRemoteDelay: Int, remoteDelayedPaymentPubkey: PublicKey, feeratePerKw: Long): MainPenaltyTx = {

    val redeemScript = write(toLocalDelayed(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPaymentPubkey))
    val pubkeyScript = write(pay2wsh(redeemScript))

    MainPenaltyTx tupled makeClaimTx(feeratePerKw, mainPenaltyWeight, redeemScript, commitTx,
      outputIndex = new PubKeyScriptIndexFinder(commitTx).findPubKeyScriptIndex(pubkeyScript, None),
      localDustLimit, localFinalScriptPubKey, sequence = 0xffffffffL, lockTime = 0L)
  }

  /**
    * We already have the redeemScript, no need to build it
    */
  def makeHtlcPenaltyTx(finder: PubKeyScriptIndexFinder, redeemScript: BinaryData, localDustLimit: Satoshi,
                        localFinalScriptPubKey: BinaryData, feeratePerKw: Long): HtlcPenaltyTx = {

    val pubkeyScript = write(pay2wsh(redeemScript))
    HtlcPenaltyTx tupled makeClaimTx(feeratePerKw, htlcPenaltyWeight, redeemScript,
      finder.tx, outputIndex = finder.findPubKeyScriptIndex(pubkeyScript, None),
      localDustLimit, localFinalScriptPubKey, sequence = 0xffffffffL, lockTime = 0L)
  }

  def makeClosingTx(commitTxInput: InputInfo, localScriptPubKey: BinaryData, remoteScriptPubKey: BinaryData, localIsFunder: Boolean, dustLimit: Satoshi, closingFee: Satoshi, spec: CommitmentSpec): ClosingTx = {
    require(spec.htlcs.isEmpty, "there shouldn't be any pending htlcs")

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = if (localIsFunder) {
      (millisatoshi2satoshi(MilliSatoshi(spec.toLocalMsat)) - closingFee, millisatoshi2satoshi(MilliSatoshi(spec.toRemoteMsat)))
    } else {
      (millisatoshi2satoshi(MilliSatoshi(spec.toLocalMsat)), millisatoshi2satoshi(MilliSatoshi(spec.toRemoteMsat)) - closingFee)
    } // NB: we don't care if values are < 0, they will be trimmed if they are < dust limit anyway

    val toLocalOutput_opt = if (toLocalAmount >= dustLimit) Some(TxOut(toLocalAmount, localScriptPubKey)) else None
    val toRemoteOutput_opt = if (toRemoteAmount >= dustLimit) Some(TxOut(toRemoteAmount, remoteScriptPubKey)) else None

    val tx = Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, Array.emptyByteArray, sequence = 0xffffffffL) :: Nil,
      txOut = toLocalOutput_opt.toSeq ++ toRemoteOutput_opt.toSeq ++ Nil,
      lockTime = 0)
    ClosingTx(commitTxInput, LexicographicalOrdering.sort(tx))
  }

  def sign(tx: Transaction, inputIndex: Int, redeemScript: BinaryData, amount: Satoshi, key: PrivateKey): BinaryData = {
    Transaction.signInput(tx, inputIndex, redeemScript, SIGHASH_ALL, amount, SIGVERSION_WITNESS_V0, key)
  }

  def sign(txinfo: TransactionWithInputInfo, key: PrivateKey): BinaryData = {
    require(txinfo.tx.txIn.lengthCompare(1) == 0, "only one input allowed")
    sign(txinfo.tx, inputIndex = 0, txinfo.input.redeemScript, txinfo.input.txOut.amount, key)
  }

  def addSigs(commitTx: CommitTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: BinaryData, remoteSig: BinaryData): CommitTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    commitTx.copy(tx = commitTx.tx.updateWitness(0, witness))
  }

  def addSigs(mainPenaltyTx: MainPenaltyTx, revocationSig: BinaryData): MainPenaltyTx = {
    val witness = Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, mainPenaltyTx.input.redeemScript)
    mainPenaltyTx.copy(tx = mainPenaltyTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcPenaltyTx: HtlcPenaltyTx, revocationSig: BinaryData, revocationPubkey: PublicKey): HtlcPenaltyTx = {
    val witness = Scripts.witnessHtlcWithRevocationSig(revocationSig, revocationPubkey, htlcPenaltyTx.input.redeemScript)
    htlcPenaltyTx.copy(tx = htlcPenaltyTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcSuccessTx: HtlcSuccessTx, localSig: BinaryData, remoteSig: BinaryData, paymentPreimage: BinaryData): HtlcSuccessTx = {
    val witness = witnessHtlcSuccess(localSig, remoteSig, paymentPreimage, htlcSuccessTx.input.redeemScript)
    htlcSuccessTx.copy(tx = htlcSuccessTx.tx.updateWitness(0, witness))
  }

  def addSigs(htlcTimeoutTx: HtlcTimeoutTx, localSig: BinaryData, remoteSig: BinaryData): HtlcTimeoutTx = {
    val witness = witnessHtlcTimeout(localSig, remoteSig, htlcTimeoutTx.input.redeemScript)
    htlcTimeoutTx.copy(tx = htlcTimeoutTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcSuccessTx: ClaimHtlcSuccessTx, localSig: BinaryData, paymentPreimage: BinaryData): ClaimHtlcSuccessTx = {
    val witness = witnessClaimHtlcSuccessFromCommitTx(localSig, paymentPreimage, claimHtlcSuccessTx.input.redeemScript)
    claimHtlcSuccessTx.copy(tx = claimHtlcSuccessTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcTimeoutTx: ClaimHtlcTimeoutTx, localSig: BinaryData): ClaimHtlcTimeoutTx = {
    val witness = witnessClaimHtlcTimeoutFromCommitTx(localSig, claimHtlcTimeoutTx.input.redeemScript)
    claimHtlcTimeoutTx.copy(tx = claimHtlcTimeoutTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimP2WPKHOutputTx: ClaimP2WPKHOutputTx, localPaymentPubkey: BinaryData, localSig: BinaryData): ClaimP2WPKHOutputTx = {
    val witness = ScriptWitness(Seq(localSig, localPaymentPubkey))
    claimP2WPKHOutputTx.copy(tx = claimP2WPKHOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcDelayed: ClaimDelayedOutputTx, localSig: BinaryData): ClaimDelayedOutputTx = {
    val witness = witnessToLocalDelayedAfterDelay(localSig, claimHtlcDelayed.input.redeemScript)
    claimHtlcDelayed.copy(tx = claimHtlcDelayed.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcDelayedPenalty: ClaimDelayedOutputPenaltyTx, revocationSig: BinaryData): ClaimDelayedOutputPenaltyTx = {
    val witness = Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, claimHtlcDelayedPenalty.input.redeemScript)
    claimHtlcDelayedPenalty.copy(tx = claimHtlcDelayedPenalty.tx.updateWitness(0, witness))
  }

  def addSigs(closingTx: ClosingTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: BinaryData, remoteSig: BinaryData): ClosingTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    closingTx.copy(tx = closingTx.tx.updateWitness(0, witness))
  }

  def checkSpendable(txinfo: TransactionWithInputInfo): Try[Unit] =
    Try(Transaction.correctlySpends(txinfo.tx, Map(txinfo.tx.txIn.head.outPoint -> txinfo.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

  def checkSig(txinfo: TransactionWithInputInfo, sig: BinaryData, pubKey: PublicKey): Boolean = {
    val data = Transaction.hashForSigning(txinfo.tx, inputIndex = 0, txinfo.input.redeemScript, SIGHASH_ALL, txinfo.input.txOut.amount, SIGVERSION_WITNESS_V0)
    Crypto.verifySignature(data, sig, pubKey)
  }

}

class PubKeyScriptIndexFinder(val tx: Transaction) {
  private[this] var indexesAlreadyUsed = Set.empty[Long]
  private[this] val indexedOutputs = tx.txOut.zipWithIndex

  def findPubKeyScriptIndex(pubkeyScript: BinaryData, amountOpt: Option[Satoshi] = None): Int = {
    // It is not enough to resolve on pubkeyScript alone because we may have duplicate HTLC payments
    // so we collect an already used output indexes and make sure payment sums are matched if needed

    val index = indexedOutputs indexWhere { case (out, idx) =>
      val isOutputUsedAlready = indexesAlreadyUsed contains idx
      val amountMatches = amountOpt.forall(_ == out.amount)
      val scriptOk = out.publicKeyScript == pubkeyScript
      !isOutputUsedAlready & amountMatches & scriptOk
    }

    if (index < 0) throw OutputNotFound
    indexesAlreadyUsed += index
    index
  }
}