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

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{OutPoint, Satoshi, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.bitcoin.{SigVersion, ScriptFlags}
import fr.acinq.bitcoin.SigHash.SIGHASH_ALL
import fr.acinq.bitcoin.TxIn.SEQUENCE_FINAL

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
      val outputIndex = tx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(pub)))
      (OutPoint(tx, outputIndex), tx.txOut(outputIndex).amount)
    })
    // we spend these inputs and create a similar output with a smaller amount
    val unsigned = Transaction(
      2,
      inputs.map(_._1).map(outPoint => TxIn(outPoint, Nil, sequence)),
      TxOut(inputs.map(_._2).sum - fee, Script.pay2wpkh(to)) :: Nil,
      lockTime
    )
    val signed = inputs.map(_._2).zipWithIndex.foldLeft(unsigned) {
      case (tx, (amount, i)) =>
        val sig = Transaction.signInput(tx, i, Script.pay2pkh(pub), SIGHASH_ALL, amount, SigVersion.SIGVERSION_WITNESS_V0, priv)
        tx.updateWitness(i, ScriptWitness(sig :: pub.value :: Nil))
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
    val tx1 = createSpendP2WPKH(tx, priv, priv.publicKey, 10000 sat, SEQUENCE_FINAL, 0)
    // and tx2 spends tx1
    val tx2 = createSpendP2WPKH(tx1, priv, priv.publicKey, 10000 sat, SEQUENCE_FINAL, 0)
    (tx1, tx2)
  }

}
