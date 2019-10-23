package fr.acinq.eclair.blockchain.electrum

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.{Base58, Base58Check, Bech32, Block, ByteVector32, Crypto, DeterministicWallet, OP_PUSHDATA, SIGHASH_ALL, Satoshi, Script, ScriptElt, ScriptWitness, SigVersion, Transaction, TxIn}
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet.{Data, Utxo, WalletType, bip49RootPath, bip84RootPath}
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import scodec.bits.ByteVector

import scala.util.Try

trait KeyStore {

  /**
    *
    * @param key public key
    * @return the publicKeyScript for this key
    */
  def computePublicKeyScript(key: PublicKey): Seq[ScriptElt]

  /**
    *
    * @param key public key
    * @return the address for this key
    */
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
  def addUtxosWithDummySig(tx: Transaction, utxos: Seq[Utxo]): Transaction

  /**
    *
    * @param txIn transaction input
    * @return Some(pubkey) if this tx input spends a p2sh-of-p2wpkh(pub) OR p2wpkh(pub), None otherwise
    */
  def extractPubKey(txIn: TxIn): Option[PublicKey]

  /**
    * Compute the wallet's xpub
    *
    * @param master    master key
    * @param chainHash chain hash
    * @return a (xpub, path) tuple where xpub is the encoded account public key, and path is the derivation path for the account key
    */
  def computeRootPub(master: ExtendedPrivateKey, chainHash: ByteVector32): (String, String)

}

class P2SHSegwitKeyStore extends KeyStore {

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

  override def computeAddress(key: PublicKey, chainHash: ByteVector32): String = {
    val script = Script.pay2wpkh(key)
    val hash = Crypto.hash160(Script.write(script))
    chainHash match {
      case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, hash)
      case Block.LivenetGenesisBlock.hash => Base58Check.encode(Base58.Prefix.ScriptAddress, hash)
    }
  }

  def addUtxosWithDummySig(tx: Transaction, utxos: Seq[Utxo]): Transaction = {
    tx.copy(txIn = utxos.map { case utxo =>
      // we use dummy signature here, because the result is only used to estimate fees
      val sig = ByteVector.fill(71)(1)
      val sigScript = Script.write(OP_PUSHDATA(Script.write(Script.pay2wpkh(utxo.key.publicKey))) :: Nil)
      val witness = ScriptWitness(sig :: utxo.key.publicKey.value :: Nil)
      TxIn(utxo.outPoint, signatureScript = sigScript, sequence = TxIn.SEQUENCE_FINAL, witness = witness)
    })
  }

  override def extractPubKey(txIn: TxIn): Option[PublicKey] = {
    Try {
      // we're looking for tx that spend a pay2sh-of-p2wkph output
      require(txIn.witness.stack.size == 2)
      val sig = txIn.witness.stack(0)
      val pub = txIn.witness.stack(1)
      val OP_PUSHDATA(script, _) :: Nil = Script.parse(txIn.signatureScript)
      val publicKey = PublicKey(pub)
      if (Script.write(Script.pay2wpkh(publicKey)) == script) {
        Some(publicKey)
      } else None
    } getOrElse None
  }

  override def computeRootPub(master: ExtendedPrivateKey, chainHash: ByteVector32): (String, String) = {
    val xpub = DeterministicWallet.publicKey(DeterministicWallet.derivePrivateKey(master, bip49RootPath(chainHash)))
    val prefix = chainHash match {
      case Block.LivenetGenesisBlock.hash => DeterministicWallet.ypub
      case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => DeterministicWallet.upub
    }
    (DeterministicWallet.encode(xpub, prefix), xpub.path.toString())
  }
}

class Bech32KeyStore extends KeyStore {

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

  override def addUtxosWithDummySig(tx: Transaction, utxos: Seq[Utxo]): Transaction = {
    tx.copy(txIn = utxos.map { case utxo =>
      // we use dummy signature here, because the result is only used to estimate fees
      val sig = Scripts.der(Transactions.PlaceHolderSig)
      val sigScript = ByteVector.empty
      val witness = ScriptWitness(sig :: utxo.key.publicKey.value :: Nil)
      TxIn(utxo.outPoint, signatureScript = sigScript, sequence = TxIn.SEQUENCE_FINAL, witness = witness)
    })
  }

  override def extractPubKey(txIn: TxIn): Option[PublicKey] = {
    Try {
      // we're looking for tx that spend a p2wkph output
      require(txIn.witness.stack.size == 2)
      require(txIn.signatureScript.isEmpty)

      val pub = txIn.witness.stack(1)
      Some(PublicKey(pub))
    } getOrElse None
  }

  override def computeRootPub(master: ExtendedPrivateKey, chainHash: ByteVector32): (String, String) = {
    val zpub = DeterministicWallet.publicKey(DeterministicWallet.derivePrivateKey(master, ElectrumWallet.bip84RootPath(chainHash)))
    val prefix = chainHash match {
      case Block.LivenetGenesisBlock.hash => DeterministicWallet.zpub
      case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => DeterministicWallet.vpub
    }
    (DeterministicWallet.encode(zpub, prefix), zpub.path.toString())
  }
}

