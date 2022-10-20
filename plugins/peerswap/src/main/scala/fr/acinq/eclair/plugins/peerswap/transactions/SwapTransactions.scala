/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.plugins.peerswap.transactions

import fr.acinq.bitcoin.SigHash.SIGHASH_ALL
import fr.acinq.bitcoin.SigVersion.SIGVERSION_WITNESS_V0
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.Script._
import fr.acinq.bitcoin.scalacompat.{TxOut, _}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.plugins.peerswap.SwapScripts._
import fr.acinq.eclair.transactions.Scripts.der
import fr.acinq.eclair.transactions.Transactions.{InputInfo, TransactionWithInputInfo, weight2fee}
import scodec.bits.ByteVector

object SwapTransactions {

  // TODO: find alternative to unsealing TransactionWithInputInfo
  case class SwapClaimByInvoiceTx(override val input: InputInfo, override val tx: Transaction) extends TransactionWithInputInfo { override def desc: String = "swap-claimbyinvoice-tx" }
  case class SwapClaimByCoopTx(override val input: InputInfo, override val tx: Transaction) extends TransactionWithInputInfo { override def desc: String = "swap-claimbycoop-tx" }
  case class SwapClaimByCsvTx(override val input: InputInfo, override val tx: Transaction) extends TransactionWithInputInfo { override def desc: String = "swap-claimbycsv-tx" }

  /**
   * This default sig takes 72B when encoded in DER (incl. 1B for the trailing sig hash), it is used for fee estimation
   * It is 72 bytes because our signatures are normalized (low-s) and will take up 72 bytes at most in DER format
   */
  val PlaceHolderSig: ByteVector64 = ByteVector64(ByteVector.fill(64)(0xaa))
  assert(der(PlaceHolderSig).size == 72)

  val claimByInvoiceTxWeight = 593 // TODO: add test to confirm this is the actual weight of the claimByInvoice tx in vBytes
  val openingTxWeight = 610 // TODO: compute and add test to confirm this is the actual weight of the opening tx in vBytes

  def makeSwapOpeningInputInfo(fundingTxId: ByteVector32, fundingTxOutputIndex: Int, amount: Satoshi, makerPubkey: PublicKey, takerPubkey: PublicKey, paymentHash: ByteVector32): InputInfo = {
    val redeemScript = swapOpening(makerPubkey, takerPubkey, paymentHash)
    val openingTxOut = makeSwapOpeningTxOut(amount, makerPubkey, takerPubkey, paymentHash)
    InputInfo(OutPoint(fundingTxId.reverse, fundingTxOutputIndex), openingTxOut, write(redeemScript))
  }

  def makeSwapOpeningTxOut(amount: Satoshi, makerPubkey: PublicKey, takerPubkey: PublicKey, paymentHash: ByteVector32): TxOut = {
    val redeemScript = swapOpening(makerPubkey, takerPubkey, paymentHash)
    TxOut(amount, pay2wsh(redeemScript))
  }

  def validOpeningTx(openingTx: Transaction, scriptOut: Long, amount: Satoshi, makerPubkey: PublicKey, takerPubkey: PublicKey, paymentHash: ByteVector32): Boolean =
    openingTx match {
      case Transaction(2, _, txOut, 0) if txOut(scriptOut.toInt) == makeSwapOpeningTxOut(amount, makerPubkey, takerPubkey, paymentHash) => true
      case _ => false
    }

  /**
   * This is the desired way to finish a swap. The taker sends the funds to its address by revealing the preimage of the swap invoice.
   *
   * txin count: 1
   * txin[0] outpoint: tx_id and script_output from the opening_tx_broadcasted message
   * txin[0] sequence: 0
   * txin[0] script bytes: 0
   * txin[0] witness: <signature_for_taker> <preimage> <> <> <redeem_script>
   *
   */
  def makeSwapClaimByInvoiceTx(amount: Satoshi, makerPubkey: PublicKey, takerPrivkey: PrivateKey, paymentPreimage: ByteVector32, feeratePerKw: FeeratePerKw, openingTxId: ByteVector32, openingOutIndex: Int): Transaction = {
    val redeemScript = swapOpening(makerPubkey, takerPrivkey.publicKey, Crypto.sha256(paymentPreimage))

    val tx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(openingTxId.reverse, openingOutIndex), ByteVector.empty, 0) :: Nil,
      txOut = TxOut(0 sat, pay2wpkh(takerPrivkey.publicKey)) :: Nil,
      lockTime = 0)

    // spend input less tx fee
    val weight = tx.updateWitness(0, witnessClaimByInvoice(PlaceHolderSig, paymentPreimage, write(redeemScript))).weight()
    val fee = weight2fee(feeratePerKw, weight)
    val amountLessFee = amount - fee
    val txLessFees = tx.copy(txOut = tx.txOut.head.copy(amount = amountLessFee) :: Nil)

    val sigDER = Transaction.signInput(txLessFees, inputIndex = 0, previousOutputScript = redeemScript, SIGHASH_ALL, amount, SIGVERSION_WITNESS_V0, takerPrivkey)
    val takerSig = Crypto.der2compact(sigDER)

    txLessFees.updateWitness(0, witnessClaimByInvoice(takerSig, paymentPreimage, write(redeemScript)))
  }

  /**
   * This is the way to cooperatively finish a swap. The maker refunds to its address without waiting for the CSV.
   *
   * txin count: 1
   * txin[0] outpoint: tx_id and script_output from the opening_tx_broadcasted message
   * txin[0] sequence: 0
   * txin[0] script bytes: 0
   * txin[0] witness: <signature_for_taker> <signature_for_maker> <> <redeem_script>
   *
   */
  def makeSwapClaimByCoopTx(amount: Satoshi, makerPrivkey: PrivateKey, takerPrivkey: PrivateKey, paymentHash: ByteVector, feeratePerKw: FeeratePerKw, openingTxId: ByteVector32, openingOutIndex: Int): Transaction = {
    val redeemScript = swapOpening(makerPrivkey.publicKey, takerPrivkey.publicKey, paymentHash)

    val tx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(openingTxId.reverse, openingOutIndex), ByteVector.empty, 0) :: Nil,
      txOut = TxOut(0 sat, pay2wpkh(makerPrivkey.publicKey)) :: Nil,
      lockTime = 0)

    // spend input less tx fee
    val weight = tx.updateWitness(0, witnessClaimByCoop(PlaceHolderSig, PlaceHolderSig, write(redeemScript))).weight()
    val fee = weight2fee(feeratePerKw, weight)
    val amountLessFee = amount - fee
    val txLessFees = tx.copy(txOut = tx.txOut.head.copy(amount = amountLessFee) :: Nil)

    val takerSigDER = Transaction.signInput(txLessFees, inputIndex = 0, previousOutputScript = redeemScript, SIGHASH_ALL, amount, SIGVERSION_WITNESS_V0, takerPrivkey)
    val takerSig = Crypto.der2compact(takerSigDER)
    val makerSigDER = Transaction.signInput(txLessFees, inputIndex = 0, previousOutputScript = redeemScript, SIGHASH_ALL, amount, SIGVERSION_WITNESS_V0, makerPrivkey)
    val makerSig = Crypto.der2compact(makerSigDER)

    txLessFees.updateWitness(0, witnessClaimByCoop(takerSig, makerSig, write(redeemScript)))
  }

  /**
   * This is the way to finish a swap if the invoice was not paid and the taker did not send a coop_close message. After the relative locktime has passed, the maker refunds to them.
   *
   * txin count: 1
   * txin[0] outpoint: tx_id and script_output from the opening_tx_broadcasted message
   * txin[0] sequence:
   * for btc as asset: 0x3F0 corresponding to the CSV of 1008
   * for lbtc as asset: 0x3C corresponding to the CSV of 60
   * txin[0] script bytes: 0
   * txin[0] witness: <signature_for_maker> <redeem_script>
   *
   */
  def makeSwapClaimByCsvTx(amount: Satoshi, makerPrivkey: PrivateKey, takerPubkey: PublicKey, paymentHash: ByteVector, feeratePerKw: FeeratePerKw, openingTxId: ByteVector32, openingOutIndex: Int): Transaction = {

    val redeemScript = swapOpening(makerPrivkey.publicKey, takerPubkey, paymentHash)

    val tx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(openingTxId.reverse, openingOutIndex), ByteVector.empty, claimByCsvDelta.toInt) :: Nil,
      txOut = TxOut(0 sat, pay2wpkh(makerPrivkey.publicKey)) :: Nil,
      lockTime = 0)

    // spend input less tx fee
    val weight = tx.updateWitness(0, witnessClaimByCsv(PlaceHolderSig, write(redeemScript))).weight()
    val fee = weight2fee(feeratePerKw, weight)
    val amountLessFee = amount - fee
    val txLessFees = tx.copy(txOut = tx.txOut.head.copy(amount = amountLessFee) :: Nil)

    val makerSigDER = Transaction.signInput(txLessFees, inputIndex = 0, previousOutputScript = redeemScript, SIGHASH_ALL, amount, SIGVERSION_WITNESS_V0, makerPrivkey)
    val makerSig = Crypto.der2compact(makerSigDER)

    txLessFees.updateWitness(0, witnessClaimByCsv(makerSig, write(redeemScript)))
  }

}
