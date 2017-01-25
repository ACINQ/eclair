package fr.acinq.eclair.transactions

import java.nio.ByteOrder

import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, ripemd160}
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin.SigVersion._
import fr.acinq.bitcoin.{BinaryData, Crypto, LexicographicalOrdering, MilliSatoshi, OP_PUSHDATA, OutPoint, Protocol, SIGHASH_ALL, Satoshi, Script, ScriptElt, ScriptFlags, ScriptWitness, Transaction, TxIn, TxOut, millisatoshi2satoshi}
import fr.acinq.eclair.transactions.Scripts._
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
  case class ClaimP2PKHOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimP2WPKHOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClaimDelayedOutputTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class MainPunishmentTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class HtlcPunishmentTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  case class ClosingTx(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
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
    *   - [[MainPunishmentTx]] spends remote main output using the per-commitment secret
    *   - [[HtlcSuccessTx]] spends htlc-sent outputs of [[CommitTx]] for which they have the preimage (published by remote)
    *     - [[HtlcPunishmentTx]] spends [[HtlcSuccessTx]] using the per-commitment secret
    *   - [[ClaimHtlcTimeoutTx]] spends htlc-sent outputs of [[CommitTx]] after a timeout
    *   - [[HtlcTimeoutTx]] spends htlc-received outputs of [[CommitTx]] after a timeout (published by local or remote)
    *     - [[HtlcPunishmentTx]] spends [[HtlcTimeoutTx]] using the per-commitment secret
    */

  val commitWeight = 724
  val htlcTimeoutWeight = 634
  val htlcSuccessWeight = 671
  val claimP2PKHOutputWeight = 764
  val claimP2WPKHOutputWeight = 437
  val claimHtlcDelayedWeight = 482
  val mainPunishmentWeight = 483

  def weight2fee(feeRatePerKw: Long, weight: Int) = Satoshi((feeRatePerKw * weight) / 1000)

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
    val h = Crypto.sha256(localPaymentBasePoint.toBin(true) ++ remotePaymentBasePoint.toBin(true))
    val blind = Protocol.uint64(h.takeRight(6).reverse ++ BinaryData("0x0000"), ByteOrder.LITTLE_ENDIAN)
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
    *
    * @param txnumber
    * @return (sequence, locktime)
    */
  def encodeTxNumber(txnumber: Long) = {
    require(txnumber <= 0xffffffffffffL, "txnumber must be lesser than 48 bits long")
    (0x80000000L | (txnumber >> 24), (txnumber & 0xffffffL) | 0x20000000)
  }

  def decodeTxNumber(sequence: Long, locktime: Long) = ((sequence & 0xffffffL) << 24) + (locktime & 0xffffffL)

  def makeCommitTx(commitTxInput: InputInfo, commitTxNumber: Long, localPaymentBasePoint: Point, remotePaymentBasePoint: Point, localIsFunder: Boolean, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: Int, localDelayedPubkey: PublicKey, remotePubkey: PublicKey, spec: CommitmentSpec): CommitTx = {

    val commitFee = commitTxFee(spec.feeRatePerKw, localDustLimit, spec)

    val (toLocalAmount: Satoshi, toRemoteAmount: Satoshi) = (MilliSatoshi(spec.toLocalMsat), MilliSatoshi(spec.toRemoteMsat)) match {
      case (local, remote) if localIsFunder && local.compare(commitFee) <= 0 => ??? //TODO: can't pay fees!
      case (local, remote) if localIsFunder && local.compare(commitFee) > 0 => (local - commitFee, millisatoshi2satoshi(remote))
      case (local, remote) if !localIsFunder && remote.compare(commitFee) <= 0 => ??? //TODO: can't pay fees!
      case (local, remote) if !localIsFunder && remote.compare(commitFee) > 0 => (millisatoshi2satoshi(local), remote - commitFee)
    }
    val toLocalDelayedOutput_opt = if (toLocalAmount.compare(localDustLimit) > 0) Some(TxOut(toLocalAmount, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPubkey)))) else None
    val toRemoteOutput_opt = if (toRemoteAmount.compare(localDustLimit) > 0) Some(TxOut(toRemoteAmount, pay2pkh(remotePubkey))) else None

    val htlcTimeoutFee = weight2fee(spec.feeRatePerKw, htlcTimeoutWeight)
    val htlcSuccessFee = weight2fee(spec.feeRatePerKw, htlcSuccessWeight)
    val htlcOfferedOutputs = spec.htlcs.toSeq
      .filter(_.direction == OUT)
      .filter(htlc => (MilliSatoshi(htlc.add.amountMsat) - htlcTimeoutFee).compare(localDustLimit) > 0)
      .map(htlc => TxOut(MilliSatoshi(htlc.add.amountMsat), pay2wsh(htlcOffered(localDelayedPubkey, remotePubkey, ripemd160(htlc.add.paymentHash)))))
    val htlcReceivedOutputs = spec.htlcs.toSeq
      .filter(_.direction == IN)
      .filter(htlc => (MilliSatoshi(htlc.add.amountMsat) - htlcSuccessFee).compare(localDustLimit) > 0)
      .map(htlc => TxOut(MilliSatoshi(htlc.add.amountMsat), pay2wsh(htlcReceived(localDelayedPubkey, remotePubkey, ripemd160(htlc.add.paymentHash), htlc.add.expiry))))

    val txnumber = obscuredCommitTxNumber(commitTxNumber, localPaymentBasePoint, remotePaymentBasePoint)
    val (sequence, locktime) = encodeTxNumber(txnumber)

    val tx = Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, Array.emptyByteArray, sequence = sequence) :: Nil,
      txOut = toLocalDelayedOutput_opt.toSeq ++ toRemoteOutput_opt.toSeq ++ htlcOfferedOutputs ++ htlcReceivedOutputs,
      lockTime = locktime)
    CommitTx(commitTxInput, LexicographicalOrdering.sort(tx))
  }

  def makeHtlcTimeoutTx(commitTx: Transaction, localRevocationPubkey: PublicKey, toLocalDelay: Int, localDelayedPubkey: PublicKey, remotePubkey: PublicKey, feeRatePerKw: Long, htlc: UpdateAddHtlc): HtlcTimeoutTx = {
    val fee = weight2fee(feeRatePerKw, htlcTimeoutWeight)
    val redeemScript = htlcOffered(localDelayedPubkey, remotePubkey, ripemd160(htlc.paymentHash))
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    HtlcTimeoutTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(MilliSatoshi(htlc.amountMsat) - fee, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPubkey))) :: Nil,
      lockTime = htlc.expiry))
  }

  def makeHtlcSuccessTx(commitTx: Transaction, localRevocationPubkey: PublicKey, toLocalDelay: Int, localDelayedPubkey: PublicKey, remotePubkey: PublicKey, feeRatePerKw: Long, htlc: UpdateAddHtlc): HtlcSuccessTx = {
    val fee = weight2fee(feeRatePerKw, htlcSuccessWeight)
    val redeemScript = htlcReceived(localDelayedPubkey, remotePubkey, ripemd160(htlc.paymentHash), htlc.expiry)
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    HtlcSuccessTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(MilliSatoshi(htlc.amountMsat) - fee, pay2wsh(toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPubkey))) :: Nil,
      lockTime = 0), htlc.paymentHash)
  }

  def makeHtlcTxs(commitTx: Transaction, localDustLimit: Satoshi, localRevocationPubkey: PublicKey, toLocalDelay: Int, localDelayedPubkey: PublicKey, remotePubkey: PublicKey, spec: CommitmentSpec): (Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    val htlcTimeoutFee = weight2fee(spec.feeRatePerKw, htlcTimeoutWeight)
    val htlcSuccessFee = weight2fee(spec.feeRatePerKw, htlcSuccessWeight)
    val htlcTimeoutTxs = spec.htlcs
      .filter(_.direction == OUT)
      .filter(htlc => (MilliSatoshi(htlc.add.amountMsat) - htlcTimeoutFee).compare(localDustLimit) > 0)
      .map(htlc => makeHtlcTimeoutTx(commitTx, localRevocationPubkey, toLocalDelay, localDelayedPubkey, remotePubkey, spec.feeRatePerKw, htlc.add))
      .toSeq
    val htlcSuccessTxs = spec.htlcs
      .filter(_.direction == IN)
      .filter(htlc => (MilliSatoshi(htlc.add.amountMsat) - htlcSuccessFee).compare(localDustLimit) > 0)
      .map(htlc => makeHtlcSuccessTx(commitTx, localRevocationPubkey, toLocalDelay, localDelayedPubkey, remotePubkey, spec.feeRatePerKw, htlc.add))
      .toSeq
    (htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeClaimHtlcSuccessTx(commitTx: Transaction, localPubkey: PublicKey, remotePubkey: PublicKey, localFinalScriptPubKey: Seq[ScriptElt], htlc: UpdateAddHtlc): ClaimHtlcSuccessTx = {
    val redeemScript = htlcOffered(remotePubkey, localPubkey, ripemd160(htlc.paymentHash))
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    ClaimHtlcSuccessTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      // the fee was pre-computed at the upper stage: fee = input.txOut.amount - htlc.amountMsat
      txOut = TxOut(MilliSatoshi(htlc.amountMsat), localFinalScriptPubKey) :: Nil,
      lockTime = 0))
  }

  def makeClaimHtlcTimeoutTx(commitTx: Transaction, localPubkey: PublicKey, remotePubkey: PublicKey, localFinalScriptPubKey: Seq[ScriptElt], htlc: UpdateAddHtlc): ClaimHtlcTimeoutTx = {
    val redeemScript = htlcReceived(remotePubkey, localPubkey, ripemd160(htlc.paymentHash), htlc.expiry)
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    ClaimHtlcTimeoutTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0x00000000L) :: Nil,
      // the fee was pre-computed at the upper stage: fee = input.txOut.amount - htlc.amountMsat
      txOut = TxOut(MilliSatoshi(htlc.amountMsat), localFinalScriptPubKey) :: Nil,
      lockTime = htlc.expiry))
  }

  def makeClaimP2PKHOutputTx(delayedOutputTx: Transaction, localPubkey: PublicKey, localFinalScriptPubKey: Seq[ScriptElt], feeRatePerKw: Long): ClaimP2PKHOutputTx = {
    val fee = weight2fee(feeRatePerKw, claimP2PKHOutputWeight)
    val redeemScript = Script.pay2pkh(localPubkey)
    val outputIndex = findPubKeyScriptIndex(delayedOutputTx, redeemScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(delayedOutputTx, outputIndex), delayedOutputTx.txOut(outputIndex), write(redeemScript))
    ClaimP2PKHOutputTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0x00000000L) :: Nil,
      txOut = TxOut(input.txOut.amount - fee, localFinalScriptPubKey) :: Nil,
      lockTime = 0))
  }

  def makeClaimP2WPKHOutputTx(delayedOutputTx: Transaction, localPubkey: PublicKey, localFinalScriptPubKey: Seq[ScriptElt], feeRatePerKw: Long): ClaimP2WPKHOutputTx = {
    val fee = weight2fee(feeRatePerKw, claimP2WPKHOutputWeight)
    val redeemScript = Script.pay2pkh(localPubkey)
    val pubkeyScript = write(pay2wpkh(localPubkey))
    val outputIndex = findPubKeyScriptIndex(delayedOutputTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(delayedOutputTx, outputIndex), delayedOutputTx.txOut(outputIndex), write(redeemScript))
    ClaimP2WPKHOutputTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0x00000000L) :: Nil,
      txOut = TxOut(input.txOut.amount - fee, localFinalScriptPubKey) :: Nil,
      lockTime = 0))
  }

  def makeClaimDelayedOutputTx(delayedOutputTx: Transaction, localRevocationPubkey: PublicKey, toLocalDelay: Int, localDelayedPubkey: PublicKey, localFinalScriptPubKey: Seq[ScriptElt], feeRatePerKw: Long): ClaimDelayedOutputTx = {
    val fee = weight2fee(feeRatePerKw, claimHtlcDelayedWeight)
    val redeemScript = toLocalDelayed(localRevocationPubkey, toLocalDelay, localDelayedPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(delayedOutputTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(delayedOutputTx, outputIndex), delayedOutputTx.txOut(outputIndex), write(redeemScript))
    ClaimDelayedOutputTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, toLocalDelay) :: Nil,
      txOut = TxOut(input.txOut.amount - fee, localFinalScriptPubKey) :: Nil,
      lockTime = 0))
  }

  def makeMainPunishmentTx(commitTx: Transaction, remoteRevocationPubkey: PublicKey, localFinalScriptPubKey: Seq[ScriptElt], toRemoteDelay: Int, remoteDelayedPubkey: PublicKey, feeRatePerKw: Long): MainPunishmentTx = {
    val fee = weight2fee(feeRatePerKw, mainPunishmentWeight)
    val redeemScript = toLocalDelayed(remoteRevocationPubkey, toRemoteDelay, remoteDelayedPubkey)
    val pubkeyScript = write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), write(redeemScript))
    MainPunishmentTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(input.txOut.amount - fee, localFinalScriptPubKey) :: Nil,
      lockTime = 0))
  }

  def makeHtlcPunishmentTx(commitTx: Transaction): HtlcPunishmentTx = ???

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

  // when the amount is not specified, we used the legacy (pre-segwit) signature scheme
  // this is only used to spend the to-remote output of a commit tx, which is the only non-segwit output
  // that we use
  // TODO: change this if the decide to use P2WPKH in the to-remote output
  def sign(tx: Transaction, inputIndex: Int, redeemScript: BinaryData, key: PrivateKey): BinaryData = {
    Transaction.signInput(tx, inputIndex, redeemScript, SIGHASH_ALL, Satoshi(0), SIGVERSION_BASE, key)
  }

  def sign(txinfo: TransactionWithInputInfo, key: PrivateKey): BinaryData = {
    require(txinfo.tx.txIn.size == 1, "only one input allowed")
    sign(txinfo.tx, inputIndex = 0, txinfo.input.redeemScript, txinfo.input.txOut.amount, key)
  }

  def sign(txinfo: ClaimP2PKHOutputTx, key: PrivateKey): BinaryData = {
    require(txinfo.tx.txIn.size == 1, "only one input allowed")
    sign(txinfo.tx, inputIndex = 0, txinfo.input.redeemScript, key)
  }

  def addSigs(commitTx: CommitTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: BinaryData, remoteSig: BinaryData): CommitTx = {
    val witness = Scripts.witness2of2(localSig, remoteSig, localFundingPubkey, remoteFundingPubkey)
    commitTx.copy(tx = commitTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimMainDelayedRevokedTx: MainPunishmentTx, revocationSig: BinaryData): MainPunishmentTx = {
    val witness = Scripts.witnessToLocalDelayedWithRevocationSig(revocationSig, claimMainDelayedRevokedTx.input.redeemScript)
    claimMainDelayedRevokedTx.copy(tx = claimMainDelayedRevokedTx.tx.updateWitness(0, witness))
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

  def addSigs(claimP2PKHOutputTx: ClaimP2PKHOutputTx, localPubkey: BinaryData, localSig: BinaryData): ClaimP2PKHOutputTx = {
    claimP2PKHOutputTx.copy(tx = claimP2PKHOutputTx.tx.updateSigScript(0, OP_PUSHDATA(localSig) :: OP_PUSHDATA(localPubkey) :: Nil))
  }

  def addSigs(claimP2WPKHOutputTx: ClaimP2WPKHOutputTx, localPubkey: BinaryData, localSig: BinaryData): ClaimP2WPKHOutputTx = {
    val witness = ScriptWitness(Seq(localSig, localPubkey))
    claimP2WPKHOutputTx.copy(tx = claimP2WPKHOutputTx.tx.updateWitness(0, witness))
  }

  def addSigs(claimHtlcDelayed: ClaimDelayedOutputTx, localSig: BinaryData): ClaimDelayedOutputTx = {
    val witness = witnessToLocalDelayedAfterDelay(localSig, claimHtlcDelayed.input.redeemScript)
    claimHtlcDelayed.copy(tx = claimHtlcDelayed.tx.updateWitness(0, witness))
  }

  def addSigs(closingTx: ClosingTx, localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, localSig: BinaryData, remoteSig: BinaryData): ClosingTx = {
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
