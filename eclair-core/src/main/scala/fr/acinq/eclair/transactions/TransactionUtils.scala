package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.{OutPoint, Transaction, TxIn, TxOut}
import scala.annotation.tailrec

object TransactionUtils {

  @tailrec
  def isLessThan(a: Seq[Byte], b: Seq[Byte]): Boolean = {
    if (a.isEmpty && b.isEmpty) false
    else if (a.isEmpty) true
    else if (b.isEmpty) false
    else if (a.head == b.head) isLessThan(a.tail, b.tail)
    else ((a.head & 0xff) < (b.head & 0xff))
  }

  def isLessThan(a: OutPoint, b: OutPoint): Boolean = {
    if (a.txid == b.txid) a.index < b.index
    else isLessThan(a.txid, b.txid)
  }

  def isLessThan(a: TxIn, b: TxIn): Boolean = isLessThan(a.outPoint, b.outPoint)

  def isLessThan(a: TxOut, b: TxOut): Boolean = {
    if (a.amount == b.amount) isLessThan(a.publicKeyScript, b.publicKeyScript)
    else (a.amount.compare(b.amount) < 0)
  }

  /**
    *
    * @param tx input transaction
    * @return the input tx with inputs and outputs sorted in lexicographical order
    */
  def sort(tx: Transaction): Transaction = tx.copy(txIn = tx.txIn.sortWith(isLessThan), txOut = tx.txOut.sortWith(isLessThan))

}

