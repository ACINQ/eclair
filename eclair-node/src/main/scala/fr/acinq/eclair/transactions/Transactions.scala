package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.Crypto.ripemd160
import fr.acinq.bitcoin.SigVersion.SIGVERSION_WITNESS_V0
import fr.acinq.bitcoin.{BinaryData, OutPoint, SIGHASH_ALL, Satoshi, Script, ScriptElt, ScriptFlags, Transaction, TxIn, TxOut}
import fr.acinq.eclair.crypto.Generators.Point
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
  case class ClaimHtlcDelayed(input: InputInfo, tx: Transaction) extends TransactionWithInputInfo
  // @formatter:on

  def makeCommitTx(commitTxInput: InputInfo, localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData, remotePubkey: BinaryData, spec: CommitmentSpec): CommitTx = {
    // TODO: no fees!!!
    val toLocalDelayedOutput_opt = if (spec.to_local_msat >= 546000) Some(TxOut(Satoshi(spec.to_local_msat / 1000), pay2wsh(toLocal(localRevocationPubkey, toLocalDelay, localPubkey)))) else None
    val toRemoteOutput_opt = if (spec.to_remote_msat >= 546000) Some(TxOut(Satoshi(spec.to_remote_msat / 1000), pay2wpkh(toRemote(remotePubkey)))) else None
    val htlcOfferedOutputs = spec.htlcs.
      filter(_.direction == OUT)
      .map(htlc => TxOut(Satoshi(htlc.add.amountMsat / 1000), pay2wsh(htlcOffered(localPubkey, remotePubkey, ripemd160(htlc.add.paymentHash)))))
    val htlcReceivedOutputs = spec.htlcs.
      filter(_.direction == IN)
      .map(htlc => TxOut(Satoshi(htlc.add.amountMsat / 1000), pay2wsh(htlcReceived(localPubkey, remotePubkey, ripemd160(htlc.add.paymentHash), htlc.add.expiry))))
    val tx = Transaction(
      version = 2,
      txIn = TxIn(commitTxInput.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = toLocalDelayedOutput_opt.toSeq ++ toRemoteOutput_opt.toSeq ++ htlcOfferedOutputs.toSeq ++ htlcReceivedOutputs.toSeq,
      lockTime = 0)
    CommitTx(commitTxInput, permuteOutputs(tx))
  }

  def makeHtlcTimeoutTx(commitTx: Transaction, localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData, remotePubkey: BinaryData, htlc: UpdateAddHtlc): HtlcTimeoutTx = {
    val redeemScript = htlcOffered(localPubkey, remotePubkey, ripemd160(htlc.paymentHash))
    val pubkeyScript = Script.write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), Script.write(redeemScript))
    HtlcTimeoutTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(Satoshi(htlc.amountMsat / 1000), pay2wsh(htlcSuccessOrTimeout(localRevocationPubkey, toLocalDelay, localPubkey))) :: Nil,
      lockTime = htlc.expiry))
  }

  def makeHtlcSuccessTx(commitTx: Transaction, localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData, remotePubkey: BinaryData, htlc: UpdateAddHtlc): HtlcSuccessTx = {
    val redeemScript = htlcReceived(localPubkey, remotePubkey, ripemd160(htlc.paymentHash), htlc.expiry)
    val pubkeyScript = Script.write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), Script.write(redeemScript))
    HtlcSuccessTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(Satoshi(htlc.amountMsat / 1000), pay2wsh(htlcSuccessOrTimeout(localRevocationPubkey, toLocalDelay, localPubkey))) :: Nil,
      lockTime = 0))
  }

  def makeHtlcTxs(commitTx: Transaction, localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData, remotePubkey: BinaryData, spec: CommitmentSpec): (Seq[HtlcTimeoutTx], Seq[HtlcSuccessTx]) = {
    // TODO: no fees!!!
    val htlcTimeoutTxs = spec.htlcs
      .filter(_.direction == OUT)
      .map(htlc => makeHtlcTimeoutTx(commitTx, localRevocationPubkey, toLocalDelay, localPubkey, remotePubkey, htlc.add))
      .toSeq
    val htlcSuccessTxs = spec.htlcs
      .filter(_.direction == IN)
      .map(htlc => makeHtlcSuccessTx(commitTx, localRevocationPubkey, toLocalDelay, localPubkey, remotePubkey, htlc.add))
      .toSeq
    (htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeClaimHtlcSuccessTx(commitTx: Transaction, localPubkey: BinaryData, remotePubkey: BinaryData, finalLocalPubkey: BinaryData, htlc: UpdateAddHtlc): ClaimHtlcSuccessTx = {
    val redeemScript = htlcOffered(remotePubkey, localPubkey, ripemd160(htlc.paymentHash))
    val pubkeyScript = Script.write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), Script.write(redeemScript))
    ClaimHtlcSuccessTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0xffffffffL) :: Nil,
      txOut = TxOut(Satoshi(htlc.amountMsat / 1000), pay2wpkh(finalLocalPubkey)) :: Nil,
      lockTime = 0))
  }

  def makeClaimHtlcTimeoutTx(commitTx: Transaction, localPubkey: BinaryData, remotePubkey: BinaryData, finalLocalPubkey: BinaryData, htlc: UpdateAddHtlc): ClaimHtlcTimeoutTx = {
    val redeemScript = htlcReceived(remotePubkey, localPubkey, ripemd160(htlc.paymentHash), htlc.expiry)
    val pubkeyScript = Script.write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(commitTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(commitTx, outputIndex), commitTx.txOut(outputIndex), Script.write(redeemScript))
    ClaimHtlcTimeoutTx(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, 0x00000000L) :: Nil,
      txOut = TxOut(Satoshi(htlc.amountMsat / 1000), pay2wpkh(finalLocalPubkey)) :: Nil,
      lockTime = htlc.expiry))
  }

  def makeClaimHtlcDelayed(htlcSuccessOrTimeoutTx: Transaction, localRevocationPubkey: BinaryData, toLocalDelay: Int, localPubkey: BinaryData, finalLocalPubkey: BinaryData, htlc: UpdateAddHtlc): ClaimHtlcDelayed = {
    val redeemScript = htlcSuccessOrTimeout(localRevocationPubkey, toLocalDelay, localPubkey)
    val pubkeyScript = Script.write(pay2wsh(redeemScript))
    val outputIndex = findPubKeyScriptIndex(htlcSuccessOrTimeoutTx, pubkeyScript)
    require(outputIndex >= 0, "output not found")
    val input = InputInfo(OutPoint(htlcSuccessOrTimeoutTx, outputIndex), htlcSuccessOrTimeoutTx.txOut(outputIndex), Script.write(redeemScript))
    ClaimHtlcDelayed(input, Transaction(
      version = 2,
      txIn = TxIn(input.outPoint, Array.emptyByteArray, toLocalDelay) :: Nil,
      txOut = TxOut(Satoshi(htlc.amountMsat / 1000), pay2wpkh(finalLocalPubkey)) :: Nil,
      lockTime = 0))
  }

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: BinaryData): Int = tx.txOut.indexWhere(_.publicKeyScript == pubkeyScript)

  def findPubKeyScriptIndex(tx: Transaction, pubkeyScript: Seq[ScriptElt]): Int = findPubKeyScriptIndex(tx, Script.write(pubkeyScript))

  def sign(tx: Transaction, inputIndex: Int, redeemScript: BinaryData, amount: Satoshi, key: BinaryData): BinaryData = {
    // this is because by convention in bitcoin-core-speak 32B keys are 'uncompressed' and 33B keys (ending by 0x01) are 'compressed'
    val compressedKey: Seq[Byte] = key.data :+ 1.toByte
    Transaction.signInput(tx, inputIndex, redeemScript, SIGHASH_ALL, amount, SIGVERSION_WITNESS_V0, compressedKey)
  }

  def sign(txinfo: TransactionWithInputInfo, key: BinaryData): BinaryData = {
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

  def addSigs(claimHtlcDelayed: ClaimHtlcDelayed, localSig: BinaryData): ClaimHtlcDelayed = {
    val witness = witnessHtlcDelayed(localSig, claimHtlcDelayed.input.redeemScript)
    claimHtlcDelayed.copy(tx = claimHtlcDelayed.tx.updateWitness(0, witness))
  }

  def checkSig(txinfo: TransactionWithInputInfo): Try[Unit] =
    Try(Transaction.correctlySpends(txinfo.tx, Map(txinfo.tx.txIn(0).outPoint -> txinfo.input.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

}
