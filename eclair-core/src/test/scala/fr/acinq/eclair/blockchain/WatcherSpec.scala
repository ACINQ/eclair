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

package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{OutPoint, Satoshi, SatoshiLong, Script, ScriptFlags, ScriptWitness, SigVersion, Transaction, TxIn, TxOut}
import fr.acinq.bitcoin.SigHash.SIGHASH_ALL
import fr.acinq.eclair.KotlinUtils._
import org.scalatest.funsuite.AnyFunSuiteLike

/**
 * Created by PM on 27/01/2017.
 */

object WatcherSpec {

  /**
   * Create a transaction that spends a p2wpkh output from an input transaction and sends it to another p2wpkh output.
   *
   * @param tx   tx that sends funds to a p2wpkh of priv
   * @param priv private key that tx sends funds to
   * @param to   p2wpkh to send to
   * @param fee  amount in - amount out
   * @return a tx spending the input tx
   */
  def createSpendP2WPKH(tx: Transaction, priv: PrivateKey, to: PublicKey, fee: Satoshi, sequence: Long, lockTime: Long): Transaction = {
    createSpendManyP2WPKH(tx :: Nil, priv, to, fee, sequence, lockTime)
  }

  /**
   * Create a transaction that spends p2wpkh outputs from input transactions and sends the funds to another p2wpkh output.
   *
   * @param txs  txs that send funds to a p2wpkh of priv
   * @param priv private key that tx sends funds to
   * @param to   p2wpkh to send to
   * @param fee  amount in - amount out
   * @return a tx spending the input txs
   */
  def createSpendManyP2WPKH(txs: Seq[Transaction], priv: PrivateKey, to: PublicKey, fee: Satoshi, sequence: Long, lockTime: Long): Transaction = {
    // txs send funds to our key
    val pub = priv.publicKey
    val inputs = txs.map(tx => {
      val outputIndex = tx.txOut.indexWhere(_.publicKeyScript.contentEquals(Script.write(Script.pay2wpkh(pub))))
      (new OutPoint(tx, outputIndex), tx.txOut(outputIndex).amount)
    })
    // we spend these inputs and create a similar output with a smaller amount
    val unsigned = new Transaction(
      2,
      inputs.map(_._1).map(outPoint => new TxIn(outPoint, Nil, sequence)),
      new TxOut(inputs.map(_._2).sum minus fee, Script.pay2wpkh(to)) :: Nil,
      lockTime
    )
    val signed = inputs.map(_._2).zipWithIndex.foldLeft(unsigned) {
      case (tx, (amount, i)) =>
        val sig = Transaction.signInput(tx, i, Script.pay2pkh(pub), SIGHASH_ALL, amount, SigVersion.SIGVERSION_WITNESS_V0, priv)
        tx.updateWitness(i, new ScriptWitness().push(sig).push(pub.value))
    }
    Transaction.correctlySpends(signed, txs, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    signed
  }

  /**
   * Create a chain of unspent txs.
   *
   * @param tx   tx that sends funds to a p2wpkh of priv
   * @param priv private key that tx sends funds to
   * @return a (tx1, tx2) tuple where tx2 spends tx1 which spends tx
   */
  def createUnspentTxChain(tx: Transaction, priv: PrivateKey): (Transaction, Transaction) = {
    // tx1 spends tx
    val tx1 = createSpendP2WPKH(tx, priv, priv.publicKey, 10000 sat, TxIn.SEQUENCE_FINAL, 0)
    // and tx2 spends tx1
    val tx2 = createSpendP2WPKH(tx1, priv, priv.publicKey, 10000 sat, TxIn.SEQUENCE_FINAL, 0)
    (tx1, tx2)
  }

}
