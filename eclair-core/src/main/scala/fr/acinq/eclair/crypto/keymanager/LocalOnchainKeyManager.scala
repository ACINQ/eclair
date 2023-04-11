package fr.acinq.eclair.crypto.keymanager

import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.psbt.{Psbt, SignPsbtResult}
import fr.acinq.bitcoin.scalacompat.DeterministicWallet._
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, DeterministicWallet, MnemonicCode, computeBIP84Address}
import fr.acinq.bitcoin.utils.EitherKt
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import scala.jdk.CollectionConverters.MapHasAsScala

object LocalOnchainKeyManager {
  def descriptorChecksum(span: String): String = fr.acinq.bitcoin.Descriptor.checksum(span)
}

class LocalOnchainKeyManager(entropy: ByteVector, chainHash: ByteVector32, passphrase: String = "") extends OnchainKeyManager with Logging {

  import LocalOnchainKeyManager._

  // master key. we will use it to generate a BIP84 wallet that can be used:
  // - to generate a watch-only wallet with any BIP84-compatible bitcoin wallet
  // - to generate descriptors that can be import into Bitcoin Core to create a watch-only wallet which can be used
  // by Eclair to fund transactions (only Eclair will be able to sign wallet inputs).

  // m / purpose' / coin_type' / account' / change / address_index
  private val mnemonics = MnemonicCode.toMnemonics(entropy)
  private val seed = MnemonicCode.toSeed(mnemonics, passphrase)
  private val master = DeterministicWallet.generate(seed)
  private val fingerprint = DeterministicWallet.fingerprint(master) & 0xFFFFFFFFL
  private val fingerPrintHex = String.format("%8s", fingerprint.toHexString).replace(' ', '0')
  // root bip32 onchain path
  // we use BIP84 (p2wpkh) path: 84'/{0'/1'}
  private val rootPath = chainHash match {
    case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => "84'/1'"
    case Block.LivenetGenesisBlock.hash => "84'/0'"
    case _ => throw new IllegalArgumentException(s"invalid chain hash ${chainHash}")
  }
  private val rootKey = DeterministicWallet.derivePrivateKey(master, KeyPath(rootPath))


  override def getOnchainMasterPubKey(account: Long): String = {
    val prefix = chainHash match {
      case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => vpub
      case Block.LivenetGenesisBlock.hash => zpub
      case _ => throw new IllegalArgumentException(s"invalid chain hash ${chainHash}")
    }

    // master pubkey for account 0 is m/84'/{0'/1'}/0'
    val accountPub = DeterministicWallet.publicKey(DeterministicWallet.derivePrivateKey(rootKey, hardened(account)))
    DeterministicWallet.encode(accountPub, prefix)
  }

  override def getDescriptors(account: Long): (List[String], List[String]) = {
    val keyPath = s"$rootPath/$account'"
    val prefix: Int = chainHash match {
      case Block.LivenetGenesisBlock.hash => xpub
      case _ => tpub
    }
    val accountPub = DeterministicWallet.publicKey(DeterministicWallet.derivePrivateKey(rootKey, hardened(account)))
    // descriptors for account 0 are:
    // 84'/{0'/1'}/0'/0/* for main addresses
    // 84'/{0'/1'}/0'/1/* for change addresses
    val receiveDesc = s"wpkh([${this.fingerPrintHex}/$keyPath]${encode(accountPub, prefix)}/0/*)"
    val changeDesc = s"wpkh([${this.fingerPrintHex}/$keyPath]${encode(accountPub, prefix)}/1/*)"
    (
      List(s"$receiveDesc#${descriptorChecksum(receiveDesc)}"),
      List(s"$changeDesc#${descriptorChecksum(changeDesc)}")
    )
  }

  override def signPsbt(psbt: Psbt, ourInputs: Seq[Int], ourOutputs: Seq[Int]): Psbt = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    val spent = ourInputs.map(i => kmp2scala(psbt.getInput(i).getWitnessUtxo.amount)).sum
    val backtous = ourOutputs.map(i => kmp2scala(psbt.getGlobal.getTx.txOut.get(i).amount)).sum

    logger.info(s"signing ${psbt.getGlobal.getTx.txid} fees ${psbt.computeFees()} spent $spent to_us $backtous")
    ourOutputs.foreach(i => isOurOutput(psbt, i))
    ourInputs.foldLeft(psbt) { (p, i) => sigbnPsbtInput(p, i) }
  }


  override def getPublicKey(keyPath: KeyPath): (Crypto.PublicKey, String) = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._
    val pub = getPrivateKey(keyPath.keyPath).publicKey()
    val address = computeBIP84Address(pub, chainHash)
    (pub, address)
  }

  private def getPrivateKey(keyPath: fr.acinq.bitcoin.KeyPath) = fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey(master.priv, keyPath).getPrivateKey

  // check that an output belongs to us i.e. we can recompute its public from its bip32 path
  private def isOurOutput(psbt: Psbt, outputIndex: Int) = {
    val output = psbt.getOutputs.get(outputIndex)
    val txout = psbt.getGlobal.getTx.txOut.get(outputIndex)
    output.getDerivationPaths.size() match {
      case 1 =>
        output.getDerivationPaths.asScala.foreach { case (pub, keypath) =>
          val check = getPrivateKey(keypath.getKeyPath).publicKey()
          require(pub == check, s"cannot compute public key for $txout")
          require(txout.publicKeyScript.contentEquals(fr.acinq.bitcoin.Script.write(fr.acinq.bitcoin.Script.pay2wpkh(pub))), s"output pubkeyscript does not match ours for $txout")
        }
      case _ => throw new IllegalArgumentException(s"cannot verify that $txout sends to us")
    }
  }

  private def sigbnPsbtInput(psbt: Psbt, pos: Int): Psbt = {
    import fr.acinq.bitcoin.{Script, SigHash}

    val input = psbt.getInput(pos)

    // For each wallet input, Bitcoin Core will provide
    // - the output that was spent, in the PSBT's witness utxo field
    // - the actual transaction that was spent, in the PSBT's non-witness utxo field
    // we check that this fields are consistent and match the outpoint that is spent in the PSBT
    // this prevents attacks where bitcoin core would lie about the amount being spent and make us pay very high fees
    require(input.getNonWitnessUtxo != null, "non-witness utxo is missing")
    require(input.getNonWitnessUtxo.txid == psbt.getGlobal.getTx.txIn.get(pos).outPoint.txid, "utxo txid mismatch")
    require(input.getNonWitnessUtxo.txOut.get(psbt.getGlobal.getTx.txIn.get(pos).outPoint.index.toInt) == input.getWitnessUtxo, "utxo mismatch")

    // not using SIGHASH_ALL would make us vulnerable to "signature reuse" attacks
    // here null means unspecified means SIGHASH_ALL
    require(Option(input.getSighashType).forall(_ == SigHash.SIGHASH_ALL), "input sighashtype must be SIGHASH_ALL")

    // check that we're signing a p2wpkh input and that the keypath is provided and correct
    require(Script.isPay2wpkh(input.getWitnessUtxo.publicKeyScript.toByteArray), "spent input is not p2wpkh")
    require(input.getDerivationPaths.size() == 1, "invalid bip32 path")
    val (pub, keypath) = input.getDerivationPaths.asScala.toSeq.head

    // use provided bip32 path to compute the private key
    val priv = fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey(master.priv, keypath.getKeyPath).getPrivateKey
    require(priv.publicKey() == pub, "cannot compute private key")

    // update the input with the right script for a p2wpkh input, which is a * p2pkh * script
    // then sign and finalize the psbt input
    val updated = psbt.updateWitnessInput(psbt.getGlobal.getTx.txIn.get(pos).outPoint, input.getWitnessUtxo, null, Script.pay2pkh(pub), SigHash.SIGHASH_ALL, input.getDerivationPaths)
    val signed = EitherKt.flatMap(updated, (p: Psbt) => p.sign(priv, pos))
    val finalized = EitherKt.flatMap(signed, (s: SignPsbtResult) => {
      val sig = s.getSig
      require(sig.get(sig.size() - 1).toInt == SigHash.SIGHASH_ALL, "signature must end with SIGHASH_ALL")
      s.getPsbt.finalizeWitnessInput(pos, new ScriptWitness().push(sig).push(pub.value))
    })
    require(finalized.isRight, s"cannot sign psbt input, error = ${finalized.getLeft}")
    finalized.getRight
  }
}
