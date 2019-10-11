package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.{Base58, Base58Check, Bech32, Block, ByteVector32, Crypto, OP_PUSHDATA, SIGHASH_ALL, Satoshi, Script, ScriptElt, ScriptWitness, SigVersion, Transaction, TxIn}
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.{Data, Utxo, extractPubKey}
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import scodec.bits.ByteVector

trait ElectrumAddressStrategy {

  /**
    *
    * @param key public key
    * @return the publicKeyScript for this key
    */
  def computePublicKeyScript(key: PublicKey): Seq[ScriptElt]

  def computeAddress(key: PublicKey, chainHash: ByteVector32): String
  def computeAddress(key: ExtendedPrivateKey, chainHash: ByteVector32): String = computeAddress(key.publicKey, chainHash)
  def computeAddress(key: PrivateKey, chainHash: ByteVector32): String = computeAddress(key.publicKey, chainHash)

  def signTx(tx: Transaction, d: Data): Transaction

  /**
    *
    * @param tx    input transaction
    * @param utxos input uxtos
    * @return a tx where all utxos have been added as inputs, signed with dummy invalid signatures. This
    *         is used to estimate the weight of the signed transaction
    */
  def addUtxos(tx: Transaction, utxos: Seq[Utxo]): Transaction

}

class P2SHStrategy extends ElectrumAddressStrategy {

  override def signTx(tx: Transaction, d: Data): Transaction = {
    tx.copy(txIn = tx.txIn.zipWithIndex.map { case (txIn, i) =>
      val utxo = d.utxos.find(_.outPoint == txIn.outPoint).getOrElse(throw new RuntimeException(s"cannot sign input that spends from ${txIn.outPoint}"))
      val key = utxo.key
      val sig = Transaction.signInput(tx, i, Script.pay2pkh(key.publicKey), SIGHASH_ALL, Satoshi(utxo.item.value), SigVersion.SIGVERSION_WITNESS_V0, key.privateKey)
      val sigScript = Script.write(OP_PUSHDATA(Script.write(Script.pay2wpkh(key.publicKey))) :: Nil)
      val witness = ScriptWitness(sig :: key.publicKey.value :: Nil)
      txIn.copy(signatureScript = sigScript, witness = witness)
    })
  }

  override def computePublicKeyScript(key: PublicKey) = Script.pay2sh(Script.pay2wpkh(key))

  /**
    *
    * @param key public key
    * @return the address of the p2sh-of-p2wpkh script for this key
    */
  override def computeAddress(key: PublicKey, chainHash: ByteVector32): String = {
    val script = Script.pay2wpkh(key)
    val hash = Crypto.hash160(Script.write(script))
    chainHash match {
      case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, hash)
      case Block.LivenetGenesisBlock.hash => Base58Check.encode(Base58.Prefix.ScriptAddress, hash)
    }
  }

  def addUtxos(tx: Transaction, utxos: Seq[Utxo]): Transaction = {
    tx.copy(txIn = utxos.map { case utxo =>
      // we use dummy signature here, because the result is only used to estimate fees
      val sig = ByteVector.fill(71)(1)
      val sigScript = Script.write(OP_PUSHDATA(Script.write(Script.pay2wpkh(utxo.key.publicKey))) :: Nil)
      val witness = ScriptWitness(sig :: utxo.key.publicKey.value :: Nil)
      TxIn(utxo.outPoint, signatureScript = sigScript, sequence = TxIn.SEQUENCE_FINAL, witness = witness)
    })
  }

}

class NativeSegwitStrategy extends ElectrumAddressStrategy {

  /**
    * @param key the public key
    * @return the bech32 encoded witness program for the p2wpkh script of this key
    */
  override def computeAddress(key: PublicKey, chainHash: ByteVector32): String = chainHash match {
    case Block.RegtestGenesisBlock.hash => Bech32.encodeWitnessAddress("bcrt", 0, Crypto.hash160(key.value))
    case Block.TestnetGenesisBlock.hash => Bech32.encodeWitnessAddress("tb", 0, Crypto.hash160(key.value))
    case Block.LivenetGenesisBlock.hash => Bech32.encodeWitnessAddress("bc", 0, Crypto.hash160(key.value))
  }

  override def computePublicKeyScript(key: PublicKey) = Script.pay2wpkh(key)

  override def signTx(tx: Transaction, d: Data): Transaction = {
    tx.copy(txIn = tx.txIn.zipWithIndex.map { case (txIn, i) =>
      val utxo = d.utxos.find(_.outPoint == txIn.outPoint).getOrElse(throw new RuntimeException(s"cannot sign input that spends from ${txIn.outPoint}"))
      val key = utxo.key
      val sig = Transaction.signInput(tx, i, Script.pay2pkh(key.publicKey), SIGHASH_ALL, Satoshi(utxo.item.value), SigVersion.SIGVERSION_WITNESS_V0, key.privateKey)
      val sigScript = ByteVector.empty
      val witness = ScriptWitness(sig :: key.publicKey.value :: Nil)
      txIn.copy(signatureScript = sigScript, witness = witness)
    })
  }

  override def addUtxos(tx: Transaction, utxos: Seq[Utxo]): Transaction = {
    tx.copy(txIn = utxos.map { case utxo =>
      // we use dummy signature here, because the result is only used to estimate fees
      val sig = Scripts.der(Transactions.PlaceHolderSig)
      val sigScript = ByteVector.empty
      val witness = ScriptWitness(sig :: utxo.key.publicKey.value :: Nil)
      TxIn(utxo.outPoint, signatureScript = sigScript, sequence = TxIn.SEQUENCE_FINAL, witness = witness)
    })
  }
}

