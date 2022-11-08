package fr.acinq.eclair.crypto.keymanager

import fr.acinq.bitcoin.ScriptWitness
import fr.acinq.bitcoin.psbt.{Psbt, SignPsbtResult}
import fr.acinq.bitcoin.scalacompat.DeterministicWallet._
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, DeterministicWallet, MnemonicCode}
import fr.acinq.bitcoin.utils.EitherKt
import scodec.bits.ByteVector

import scala.jdk.CollectionConverters.MapHasAsScala

object LocalOnchainKeyManager {
  def descriptorChecksum(span: String): String = fr.acinq.bitcoin.Descriptor.checksum(span)
}

class LocalOnchainKeyManager(entropy: ByteVector, chainHash: ByteVector32, passphrase: String = "") extends OnchainKeyManager {

  import LocalOnchainKeyManager._

  // master key used for HWI wallets
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

  override def getDescriptors(fingerprint: Long, chain_opt: Option[String], account: Long): (List[String], List[String]) = {
    val chain = chain_opt.getOrElse("mainnet")
    val keyPath = s"$rootPath/$account'"
    val prefix: Int = chainHash match {
      case Block.RegtestGenesisBlock.hash if chain == "regtest" => tpub
      case Block.TestnetGenesisBlock.hash if chain == "testnet" | chain == "test" => tpub
      case Block.LivenetGenesisBlock.hash if chain == "mainnet" | chain == "main" => xpub
      case _ => throw new IllegalArgumentException(s"chain $chain and chain hash ${chainHash} mismatch")
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
    ourOutputs.foreach(i => isOurOutput(psbt, i))
    ourInputs.foldLeft(psbt) { (p, i) => sigbnPsbtInput(p, i) }
  }

  // check that an output belongs to us i.e. we can recompute its public from its bip32 path
  private def isOurOutput(psbt: Psbt, outputIndex: Int) = {

    val output = psbt.getOutputs.get(outputIndex)
    val txout = psbt.getGlobal.getTx.txOut.get(outputIndex)
    output.getDerivationPaths.size() match {
      case 1 =>
        output.getDerivationPaths.asScala.foreach { case (pub, keypath) =>
          val priv = fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey(master.priv, keypath.getKeyPath).getPrivateKey
          val check = priv.publicKey()
          require(pub == check, s"cannot compute public key for $txout")
        }
      case _ => throw new IllegalArgumentException(s"cannot verify that $txout sends to us")
    }
  }

  private def sigbnPsbtInput(psbt: Psbt, pos: Int): Psbt = {
    import fr.acinq.bitcoin.{Script, SigHash}

    val input = psbt.getInput(pos)

    // check that we're signing a p2wpkh input and that the keypath is provided and correct
    require(Script.isPay2wpkh(input.getWitnessUtxo.publicKeyScript.toByteArray), "spent input is not p2wpkh")
    require(input.getDerivationPaths.size() == 1, "invalid bip32 path")
    val (pub, keypath) = input.getDerivationPaths.asScala.toSeq.head

    // use provided bip32 path to compute the private key
    val priv = fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey(master.priv, keypath.getKeyPath).getPrivateKey
    require(priv.publicKey() == pub, "cannot compute private key")

    // update the input which the right script for a p2wpkh input, which is a * p2pkh * script
    // then sign and finalized the psbt input
    val updated = psbt.updateWitnessInput(psbt.getGlobal.getTx.txIn.get(pos).outPoint, input.getWitnessUtxo, null, Script.pay2pkh(pub), SigHash.SIGHASH_ALL, input.getDerivationPaths)
    val signed = EitherKt.flatMap(updated, (p: Psbt) => p.sign(priv, pos))
    val finalized = EitherKt.flatMap(signed, (s: SignPsbtResult) => s.getPsbt.finalizeWitnessInput(pos, new ScriptWitness().push(s.getSig).push(pub.value)))
    require(finalized.isRight, s"cannot sign psbt input, error = ${finalized.getLeft}")
    finalized.getRight
  }

  override def getOnchainMasterMasterFingerprint: Long = fingerprint
}
