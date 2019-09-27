package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{Crypto, LexicographicalOrdering, OutPoint, Satoshi, Script, ScriptElt, Transaction, TxIn, TxOut}
import scodec.bits.ByteVector

import scala.annotation.tailrec

object TransactionUtils {

  def isLessThan(a: OutPoint, b: OutPoint): Boolean = {
    if (a.txid == b.txid) a.index < b.index
    else lexicographicalOrder(a.txid, b.txid) < 0
  }

  def isLessThan(a: TxIn, b: TxIn): Boolean = isLessThan(a.outPoint, b.outPoint)

  def isLessOrCLTV(a: (SpecItem, TxOut), b: (SpecItem, TxOut)): Boolean = {
    val amountComparison = a._2.amount.compare(b._2.amount)
    if(amountComparison != 0){
      amountComparison < 0
    } else {
      val lexicographicalComparison = lexicographicalOrder(a._2.publicKeyScript, b._2.publicKeyScript)
      if(lexicographicalComparison == 0){
        (a._1, b._1) match {
          case (DirectedHtlc(OUT, addA), DirectedHtlc(OUT, addB)) => addA.cltvExpiry < addB.cltvExpiry
          case _ => lexicographicalComparison < 0
        }
      } else {
        lexicographicalComparison < 0
      }
    }
  }


  @tailrec
  def lexicographicalOrder(a: ByteVector, b: ByteVector): Int = {
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

  /**
    * Returns a tuple with: sorted_transaction and a map of htlc output index and their cltv - applies only to outputs of offered HTLCs
    *
    * @param tx
    * @param specItems
    * @return
    */
  def sortByBIP69AndCLTV(tx: Transaction, specItems: Seq[(SpecItem, TxOut)]): (Transaction, Seq[(SpecItem, Int)])= {
    assert(tx.txOut.size == specItems.size, "Unable to sort with incomplete information")

    val sortedSpecItems = specItems.sortWith(isLessOrCLTV)

    val sortedTx = tx.copy(
      txIn = tx.txIn.sortWith(isLessThan),
      txOut = sortedSpecItems.map(_._2)
    )

    val specItemsWithIndex = sortedSpecItems.zipWithIndex.map(el => (el._1._1, el._2))
    (sortedTx, specItemsWithIndex)
  }


}
