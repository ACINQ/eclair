package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{Crypto, LexicographicalOrdering, OutPoint, Satoshi, Script, ScriptElt, Transaction, TxIn, TxOut}

import scala.annotation.tailrec

object TransactionUtils {

  def isLessThan(a: OutPoint, b: OutPoint): Boolean = {
    if (a.txid == b.txid) a.index < b.index
    else lexicographicalOrder(a.txid, b.txid) < 0
  }

  def isLessThan(a: TxIn, b: TxIn): Boolean = isLessThan(a.outPoint, b.outPoint)

  def isLessOrCLTV(a: (TxOut, Option[Long]), b: (TxOut, Option[Long])): Boolean = {
    val amountComparison = compareAmounts(a._1.amount, b._1.amount)
    if(amountComparison != 0){
      amountComparison < 0
    } else {
      val lexicographicalComparison = lexicographicalOrder(a._1.publicKeyScript, b._1.publicKeyScript)
      if(lexicographicalComparison == 0 && a._2.isDefined && b._2.isDefined) {
        a._2.get < b._2.get // compare the CLTVs
      } else {
        lexicographicalComparison < 0
      }
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

  def sortByBIP39AndCLTV(tx: Transaction, offeredHtlcAndCltv:Seq[(TxOut,Long)]): Transaction = {

    // transaction outputs with optionally a CLTV value attached, only outputs corresponding to offered HTLCs will have it.
    val txOutsAndCLTV_opt = tx.txOut.map { out =>
      offeredHtlcAndCltv.find(_._1.publicKeyScript == out.publicKeyScript) match {
        case Some((txOut, cltv)) => (txOut, Some(cltv))
        case None                => (out, None)
      }
    }

    tx.copy(
      txIn = tx.txIn.sortWith(isLessThan),
      txOut = txOutsAndCLTV_opt.sortWith(isLessOrCLTV).map(_._1)
    )
  }


}
