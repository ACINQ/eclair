package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, ripemd160}
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin.SigVersion.SIGVERSION_WITNESS_V0
import fr.acinq.bitcoin.{BinaryData, Crypto, LexicographicalOrdering, MilliSatoshi, OutPoint, Protocol, SIGHASH_ALL, Satoshi, ScriptElt, ScriptFlags, Transaction, TxIn, TxOut, millisatoshi2satoshi}
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.wire.UpdateAddHtlc

import scala.util.Try

/**
  * Created by PM on 15/12/2016.
  */
object Transactions {

  // @formatter:off
  case class InputInfo(outPoint: OutPoint, txOut: TxOut, redeemScript: BinaryData)
  sealed trait TransactionWithInputInfo {
    def input: InputInfo
    def tx: Transaction
  }

  case class CommitTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcSuccessTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcTimeoutTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcSuccessTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcTimeoutTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimHtlcDelayedTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClosingTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  // @formatter:on

  /**
    * When *local* *current* [[CommitTx]] is published:
    *   - [[HtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
    *     - [[ClaimHtlcDelayedTx]] spends [[HtlcSuccessTx]] after a delay
    *   - [[HtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
    *     - [[ClaimHtlcDelayedTx]] spends [[HtlcTimeoutTx]] after a delay
    *
    * When *remote* *current* [[CommitTx]] is published:
    *   - [[ClaimHtlcSuccessTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
    *   - [[ClaimHtlcTimeoutTx]] spends htlc-received outputs of [[CommitTx]] for which we have the preimage
    */

  val commitWeight = 724
  val htlcTimeoutWeight = 634
  val htlcSuccessWeight = 671

  def weight2fee(feeRatePerKw: Long, weight: Int) = Satoshi((feeRatePerKw * weight) / 1024)

  def commitTxFee(feeRatePerKw: Long, dustLimit: Satoshi, spec: CommitmentSpec): Satoshi = {

    case class Fee(weight: Int, amount: Satoshi)

    val fee1 = Fee(commitWeight, Satoshi(0))

    val fee2 = spec.htlcs
      .filter(_.direction == OUT)
      .map(htlc => MilliSatoshi(htlc.add.amountMsat))
      .foldLeft(fee1) {
        case (fee, htlcAmount) if (htlcAmount + weight2fee(feeRatePerKw, htlcTimeoutWeight)).compare(dustLimit) >= 0 =>
          fee.copy(weight = fee.weight + 172)
        case (fee, htlcAmount) =>
          fee.copy(amount = fee.amount + htlcAmount)
      }

    val fee3 = spec.htlcs
      .filter(_.direction == IN)
      .map(htlc => MilliSatoshi(htlc.add.amountMsat))
      .foldLeft(fee2) {
        case (fee, htlcAmount) if (htlcAmount + weight2fee(feeRatePerKw, htlcSuccessWeight)).compare(dustLimit) >= 0 =>
          fee.copy(weight = fee.weight + 172)
        case (fee, htlcAmount) =>
          fee.copy(amount = fee.amount + htlcAmount)
      }

    weight2fee(feeRatePerKw, fee3.weight) + fee3.amount
  }

  /**
    *
    * @param commitTxNumber         commit tx number
    * @param localPaymentBasePoint  local payment base point
    * @param remotePaymentBasePoint remote payment base point
    * @return the obscured tx number as defined in BOLT #3 (a 48 bits integer)
    */
  def obscuredCommitTxNumber(commitTxNumber: Long, localPaymentBasePoint: Point, remotePaymentBasePoint: Point): Long = {
    val h = Crypto.sha256(localPaymentBasePoint.toBin ++ remotePaymentBasePoint.toBin)
    val blind = Protocol.uint64(h.takeRight(6).reverse ++ BinaryData("0x0000"))
    commitTxNumber ^ blind
  }

  /**
    *
    * @param commitTx               commit tx
    * @param localPaymentBasePoint  local payment base point
    * @param remotePaymentBasePoint remote payment base point
    * @return the actual commit tx number that was blinded and stored in locktime and sequence fields
    */
  def getCommitTxNumber(commitTx: Transaction, localPaymentBasePoint: Point, remotePaymentBasePoint: Point): Long = {
    val blind = obscuredCommitTxNumber(0, localPaymentBasePoint, remotePaymentBasePoint)
    val obscured = commitTx.lockTime | ((commitTx.txIn(0).sequence & 0xffffff) << 24)
    obscured ^ blind
  }

  /**
    * This is a trick to split and encode a 48-bit txnumber into the sequence and locktime fields of a tx
    * @param txnumber
    * @return (sequence, locktime)
    */
  def encodeTxNumber(txnumber: Long) = {
    require(txnumber <= 0xffffffffffffL, "txnumber must be lesser than 48 bits long")
    (0x80000000L | (txnumber >> 24), (txnumber & 0xffffffL) | 0x20000000)
  }

  def decodeTxNumber(sequence: Long, locktime: Long) = ((sequence & 0xffffffL) << 24) + (locktime & 0xffffffL)

  def makeCommitTx(commitTxInput: InputInfo, commitTxNumber: Long, localPaymentBasePoint: Point, remotePaymentBasePoint: Point, localIsFunder: Boolean, localDustLimit: Satoshi, localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData, remotePubkey: BinaryData, spec: CommitmentSpec): CommitTx = {

    val commitFee = commitTxFee(spec.feeRatePerKw, localDustLimit, spec)

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = (MilliSatoshi(spec.toLocalMsat), MilliSatoshi(spec.toRemoteMsat)) match {
      case (local, remote) if localIsFunder && local.compare(commitFee) <= 0 => ??? //TODO: can't pay fees!
      case (local, remote) if localIsFunder && local.compare(commitFee) > 0 => (local - commitFee, millisatoshi2satoshi(remote))
      case (local, remote) if !localIsFunder && remote.compare(commitFee) <= 0 => ??? //TODO: can't pay fees!
      case (local, remote) if !localIsFunder && remote.compare(commitFee) > 0 => (millisatoshi2satoshi(local), remote - commitFee)
    }
    val toLocalDelayedOutput_opt = if (toLocalAmount.compare(localDustLimit) > 0) Some(TxOut(toLocalAmount, pay2wsh(toLocal(localRevocationPubkey, toLocalDelay, localPubkey)))) else None
    val toRemoteOutput_opt = if (toRemoteAmount.compare(localDustLimit) > 0) Some(TxOut(toRemoteAmount, pay2wpkh(toRemote(remotePubkey)))) else None

    val htlcTimeoutFee = weight2fee(spec.feeRatePerKw, htlcTimeoutWeight)
    val htlcSuccessFee = weight2fee(spec.feeRatePerKw, htlcSuccessWeight)
    val htlcOfferedOutputs = spec.htlcs.toSeq
      .filter(_.direction == OUT)
      .filter(htlc => (MilliSatoshi(htlc.add.amountMsat) - htlcTimeoutFee).compare(localDustLimit) > 0)
      .map(htlc => TxOut(MilliSatoshi(htlc.add.amountMsat), pay2wsh(htlcOffered(localPubkey, remotePubkey, ripemd160(htlc.add.paymentHash)))))
    val htlcReceivedOutputs = spec.htlcs.toSeq
      .filter(_.direction == IN)
      .filter(htlc => (MilliSatoshi(htlc.add.amountMsat) - htlcSuccessFee).compare(localDustLimit) > 0)
      .map(htlc => TxOut(MilliSatoshi(htlc.add.amountMsat), pay2wsh(htlcReceived(localPubkey, remotePubkey, ripemd160(htlc.add.paymentHash), htlc.add.expiry))))

    val txnumber = obscuredCommitTxNumber(commitTxNumber, localPaymentBasePoint, remotePaymentBasePoint)
    val (sequence, locktime) = encodeTxNumber(txnumber)

    val tx = Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, Array.emptyByteArray, sequence = sequence) :: Nil,
      txOut = toLocalDelayedOutput_opt.toSeq ++ toRemoteOutput_opt.toSeq ++ htlcOfferedOutputs ++ htlcReceivedOutputs,
      lockTime = locktime)
    CommitTx(commitTxInput, LexicographicalOrdering.sort(tx))
  }

  def makeHtlcTimeoutTx(commitTx: Transaction, localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData, remotePubkey: BinaryData, feeRatePerKw: Long, htlc: UpdateAddHtlc): HtlcTimeoutTx = {
    val htlcTimeoutFee = weight2fee(feeRatePerKw, htlcTimeoutWeight)
    val redeemScript = htlcOffered(localPubkey, remotePubkey, ripemd160(htlc.paymentHash))
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    HtlcTimeoutTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(MilliSatoshi(htlc.amountMsat) - htlcTimeoutFee, pay2wsh(htlcSuccessOrTimeout(localRevocationPubkey, toLocalDelay, localPubkey))) :: Nil,
      lockTime = htlc.expiry))
  }

  def makeHtlcSuccessTx(commitTx: Transaction, localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData, remotePubkey: BinaryData, feeRatePerKw: Long, htlc: UpdateAddHtlc): HtlcSuccessTx = {
    val htlcSuccessFee = weight2fee(feeRatePerKw, htlcSuccessWeight)
    val redeemScript = htlcReceived(localPubkey, remotePubkey, ripemd160(htlc.paymentHash), htlc.expiry)
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    HtlcSuccessTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(MilliSatoshi(htlc.amountMsat) - htlcSuccessFee, pay2wsh(htlcSuccessOrTimeout(localRevocationPubkey, toLocalDelay, localPubkey))) :: Nil,
      lockTime = 0))
  }

  def makeHtlcTxs(commitTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData, remotePubkey: BinaryData, spec: CommitmentSpec): (Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val htlcTimeoutFee = weight2fee(spec.feeRatePerKw, htlcTimeoutWeight)
    val htlcSuccessFee = weight2fee(spec.feeRatePerKw, htlcSuccessWeight)
    val htlcTimeoutTxs = spec.htlcs
      .filter(_.direction == OUT)
      .filter(htlc => (MilliSatoshi(htlc.add.amountMsat) - htlcTimeoutFee).compare(localDustLimit) > 0)
      .map(htlc => makeHtlcTimeoutTx(commitTx, localRevocationPubkey, toLocalDelay, localPubkey, remotePubkey, spec.feeRatePerKw, htlc.add))
      .toSeq
    val htlcSuccessTxs = spec.htlcs
      .filter(_.direction == IN)
      .filter(htlc => (MilliSatoshi(htlc.add.amountMsat) - htlcSuccessFee).compare(localDustLimit) > 0)
      .map(htlc => makeHtlcSuccessTx(commitTx, localRevocationPubkey, toLocalDelay, localPubkey, remotePubkey, spec.feeRatePerKw, htlc.add))
      .toSeq
    (htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeClaimHtlcSuccessTx(commitTx: Transaction, localPubkey: BinaryData, remotePubkey: BinaryData, finalLocalPubkey: BinaryData, htlc: UpdateAddHtlc): ClaimHtlcSuccessTx = {
    val redeemScript = htlcOffered(remotePubkey, localPubkey, ripemd160(htlc.paymentHash))
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    ClaimHtlcSuccessTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(MilliSatoshi(htlc.amountMsat), pay2wpkh(finalLocalPubkey)) :: Nil,
      lockTime = 0))
  }

  def makeClaimHtlcTimeoutTx(commitTx: Transaction, localPubkey: BinaryData, remotePubkey: BinaryData, finalLocalPubkey: BinaryData, htlc: UpdateAddHtlc): ClaimHtlcTimeoutTx = {
    val redeemScript = htlcReceived(remotePubkey, localPubkey, ripemd160(htlc.paymentHash), htlc.expiry)
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    ClaimHtlcTimeoutTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0x00000000L) :: Nil,
      txOut = TxOut(MilliSatoshi(htlc.amountMsat), pay2wpkh(finalLocalPubkey)) :: Nil,
      lockTime = htlc.expiry))
  }

  def makeClaimHtlcDelayed(htlcSuccessOrTimeoutTx: Transaction, localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData, finalLocalPubkey: BinaryData, htlc: UpdateAddHtlc): ClaimHtlcDelayedTx = {
    val redeemScript = htlcSuccessOrTimeout(localRevocationPubkey, toLocalDelay, localPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(htlcSuccessOrTimeoutTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(htlcSuccessOrTimeoutTx, outputIndex), htlcSuccessOrTimeoutTx.txOut(outputIndex), write(redeemScript))
    ClaimHtlcDelayedTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, toLocalDelay) :: Nil,
      txOut = TxOut(MilliSatoshi(htlc.amountMsat), pay2wpkh(finalLocalPubkey)) :: Nil,
      lockTime = 0))
  }

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: BinaryData): Int = tx.txOut.indexWhere(_.publicKeyScript == pubkeyScript)

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: Seq[ScriptElt]): Int = findPubKeyScriptIndex(tx, write(pubkeyScript))

  def makeClosingTx(commitTxInput: InputInfo, localScriptPubKey: BinaryData, remoteScriptPubKey: BinaryData, localIsFunder: Boolean, dustLimit: Satoshi, closingFee: Satoshi, spec: CommitmentSpec): ClosingTx = {
    require(spec.htlcs.size == 0, "there shouldn't be any pending htlcs")

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = (MilliSatoshi(spec.toLocalMsat), MilliSatoshi(spec.toRemoteMsat)) match {
      case (local, remote) if localIsFunder && local.compare(closingFee) <= 0 => ??? //TODO: can't pay fees!
      case (local, remote) if localIsFunder && local.compare(closingFee) > 0 => (local - closingFee, millisatoshi2satoshi(remote))
      case (local, remote) if !localIsFunder && remote.compare(closingFee) <= 0 => ??? //TODO: can't pay fees!
      case (local, remote) if !localIsFunder && remote.compare(closingFee) > 0 => (millisatoshi2satoshi(local), remote - closingFee)
    }

    val toLocalOutput_opt = if (toLocalAmount.compare(dustLimit) > 0) Some(TxOut(toLocalAmount, localScriptPubKey)) else None
    val toRemoteOutput_opt = if (toRemoteAmount.compare(dustLimit) > 0) Some(TxOut(toRemoteAmount, remoteScriptPubKey)) else None

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
    require(txinfo.tx.txIn.size == 1, "only one input allowed")
    sign(txinfo.tx, inputIndex = 0, txinfo.input.redeemScript, txinfo.input.txOut.amount, key)
  }

  def addSigs(commitTx: CommitTx, localFundingPubkey: Point, remoteFundingPubkey: Point, localSig: BinaryData, remoteSig: BinaryData): CommitTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    commitTx.copy(tx = commitTx.tx.updateWitness(0, witness))
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

  def addSigs(claimHtlcDelayed: ClaimHtlcDelayedTx, localSig: BinaryData): ClaimHtlcDelayedTx = {
    val witness = witnessHtlcDelayed(localSig, claimHtlcDelayed.input.redeemScript)
    claimHtlcDelayed.copy(tx = claimHtlcDelayed.tx.updateWitness(0, witness))
  }

  def addSigs(closingTx: ClosingTx, localFundingPubkey: Point, remoteFundingPubkey: Point, localSig: BinaryData, remoteSig: BinaryData): ClosingTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    closingTx.copy(tx = closingTx.tx.updateWitness(0, witness))
  }

  def checkSpendable(txinfo: TransactionWithInputInfo): Try[Unit] =
    Try(Transaction.correctlySpends(txinfo.tx, Map(txinfo.tx.txIn(0).outPoint -> txinfo.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

  def checkSig(txinfo: TransactionWithInputInfo, sig: BinaryData, pubKey: PublicKey): Boolean = {
    val data = Transaction.hashForSigning(txinfo.tx, inputIndex = 0, txinfo.input.redeemScript, SIGHASH_ALL, txinfo.input.txOut.amount, SIGVERSION_WITNESS_V0)
    Crypto.verifySignature(data, sig, pubKey)
  }

}
