package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.Protocol.varint
import fr.acinq.bitcoin.{Crypto, LexicographicalOrdering, OP_CHECKLOCKTIMEVERIFY, OP_PUSHDATA, OutPoint, Satoshi, Script, ScriptElt, Transaction, TxIn, TxOut}

import scala.annotation.tailrec

object TransactionUtils {

  def isLessThan(a: OutPoint, b: OutPoint): Boolean = {
    if (a.txid == b.txid) a.index < b.index
    else lexicographicalOrder(a.txid, b.txid) < 0
  }

  def isLessThan(a: TxIn, b: TxIn): Boolean = isLessThan(a.outPoint, b.outPoint)

  def isLessOrCLTV(a: (TxOut, Option[List[ScriptElt]]), b: (TxOut, Option[List[ScriptElt]])): Boolean = {
    val amountComparison = compareAmounts(a._1.amount, b._1.amount)
    if(amountComparison != 0){
      amountComparison < 0
    } else {
      lexicographicalOrder(a._1.publicKeyScript, b._1.publicKeyScript) < 0
    }
  }

  def compareAmounts(a: Satoshi, b: Satoshi): Int = a.amount.compareTo(b.amount)

  @tailrec
  def lexicographicalOrder(a: Seq[Byte], b: Seq[Byte]): Int = {
    if (a.isEmpty && b.isEmpty) 0
    else if (a.isEmpty) 1
    else if (b.isEmpty) -1
    else if (a.head == b.head) lexicographicalOrder(a.tail, b.tail)
    else (a.head & 0xff).compareTo(b.head & 0xff)
  }

  /**
    *
    * @param tx input transaction
    * @return the input tx with inputs and outputs sorted in lexicographical order
    */
  def sort(tx: Transaction): Transaction = LexicographicalOrdering.sort(tx)

  def sortByBIP39AndCLTV(tx: Transaction, receivedHtlcRedeemScripts: List[List[ScriptElt]]): Transaction = {

    val txOutAndRedeems: Seq[(TxOut, Option[List[ScriptElt]])] = tx.txOut.map { txOut =>

      //https://github.com/bitcoin/bips/blob/master/bip-0141.mediawiki#p2wsh
      val scriptHash = txOut.publicKeyScript.drop(1) // the script hash in p2wsh is right after the first elem OP_0
      val redeemOpt = receivedHtlcRedeemScripts.find(redeem => {
        val rawRedeem = Script.write(redeem)
        Crypto.sha256(rawRedeem) == scriptHash
      })

      (txOut, redeemOpt)
    }


    tx.copy(
      txIn = tx.txIn.sortWith(isLessThan),
      txOut = txOutAndRedeems.sortWith(isLessOrCLTV).map(_._1)
    )
  }


}
