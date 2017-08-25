package fr.acinq.eclair.blockchain.wallet

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, Crypto, OP_PUSHDATA, OutPoint, Satoshi, Script, ScriptWitness, SigVersion, Transaction, TxIn, TxOut, _}

import scala.annotation.tailrec

/**
  * Created by PM on 30/05/2017.
  */

case class Utxo(txid: String, n: Int, value: Long)

object MiniWallet {
  /**
    * see https://github.com/bitcoin/bips/blob/master/bip-0141.mediawiki#P2WPKH_nested_in_BIP16_P2SH
    *
    * @param publicKey public key
    * @return the P2SH(P2WPKH(publicKey)) address
    */
  def witnessAddress(publicKey: PublicKey): String = Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, Crypto.hash160(Script.write(Script.pay2wpkh(publicKey))))

  def witnessAddress(privateKey: PrivateKey): String = witnessAddress(privateKey.publicKey)

  /**
    *
    * @param tx         transction to fund. must have no inputs
    * @param utxos      UTXOS to spend from. They must all send to P2SH(P2WPKH(privateKey.publicKey))
    * @param fee        network fee
    * @param privateKey private key that control all utxos
    * @return a signed transaction that may include an additional change outputs (that sends to P2SH(P2WPKH(privateKey.publicKey)))
    */
  def fundTransaction(tx: Transaction, utxos: Seq[Utxo], fee: Satoshi, privateKey: PrivateKey) = {
    require(tx.txIn.isEmpty, s"cannot fund a tx that alray has inputs ")
    val totalOut = tx.txOut.map(_.amount).sum
    val sortedUtxos = utxos.sortBy(_.value)

    @tailrec
    def select(candidates: Seq[Utxo], remaining: Seq[Utxo]): Seq[Utxo] = {
      if (Satoshi(candidates.map(_.value).sum) > totalOut) candidates
      else if (remaining.isEmpty) throw new RuntimeException("not enough funds")
      else select(candidates :+ remaining.head, remaining.tail)
    }

    // select candidates
    val candidates = select(Nil, sortedUtxos)
    val inputs = candidates.map(utxo => TxIn(OutPoint(BinaryData(utxo.txid).reverse, utxo.n), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL))
    val tx1 = tx.copy(txIn = inputs)
    val totalIn = Satoshi(candidates.map(_.value).sum)

    // add a change output if necessary
    var tx2 = if (totalIn - totalOut > fee) {
      val changeOutput = TxOut(amount = totalIn - totalOut - fee, publicKeyScript = Script.pay2sh(Script.pay2wpkh(privateKey.publicKey)))
      tx1.copy(txOut = tx1.txOut :+ changeOutput)
    } else tx1

    // all our utxos are P2SH(P2WPKH) which is the recommended way of using segwit right now
    // so to sign an input we need to provide
    // a witness with the right signature and pubkey
    // and a signature script with a script that is the preimage of our p2sh output
    val script = Script.write(Script.pay2wpkh(privateKey.publicKey))

    for (i <- 0 until tx2.txIn.length) yield {
      val sig = Transaction.signInput(tx2, i, Script.pay2pkh(privateKey.publicKey), SIGHASH_ALL, Satoshi(candidates(i).value), SigVersion.SIGVERSION_WITNESS_V0, privateKey)
      val witness = ScriptWitness(Seq(sig, privateKey.publicKey))
      tx2 = tx2.updateWitness(i, witness).updateSigScript(i, OP_PUSHDATA(script) :: Nil)
    }
    tx2
  }
}
