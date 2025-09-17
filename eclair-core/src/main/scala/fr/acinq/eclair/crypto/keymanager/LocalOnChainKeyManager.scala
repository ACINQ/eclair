/*
 * Copyright 2023 ACINQ SAS
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

package fr.acinq.eclair.crypto.keymanager

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.ScriptTree
import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.scalacompat.DeterministicWallet._
import fr.acinq.bitcoin.scalacompat.{Block, BlockHash, ByteVector64, Crypto, DeterministicWallet, MnemonicCode, Satoshi, Script, computeBIP84Address}
import fr.acinq.eclair.TimestampSecond
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.{AddressType, Descriptor, Descriptors}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import java.io.File
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.util.{Failure, Success, Try}

object LocalOnChainKeyManager extends Logging {

  /**
   * Load a configuration file and create an on-chain key manager
   *
   * @param datadir   eclair data directory
   * @param chainHash chain we're on
   * @return a LocalOnChainKeyManager instance if a configuration file exists
   */
  def load(datadir: File, chainHash: BlockHash): Option[LocalOnChainKeyManager] = {
    // we use a specific file instead of adding values to eclair's configuration file because it is available everywhere
    // in the code through the actor system's settings and we'd like to restrict access to the on-chain wallet seed
    val file = new File(datadir, "eclair-signer.conf")
    if (file.exists()) {
      val config = ConfigFactory.parseFile(file)
      val wallet = config.getString("eclair.signer.wallet")
      val mnemonics = config.getString("eclair.signer.mnemonics")
      val passphrase = config.getString("eclair.signer.passphrase")
      val timestamp = config.getLong("eclair.signer.timestamp")
      val keyManager = new LocalOnChainKeyManager(wallet, MnemonicCode.toSeed(mnemonics, passphrase), TimestampSecond(timestamp), chainHash)
      logger.info(s"using on-chain key manager wallet=$wallet with xpub: bip84=${keyManager.masterPubKey(0, AddressType.P2wpkh)}, bip86=${keyManager.masterPubKey(0, AddressType.P2tr)}")
      Some(keyManager)
    } else {
      None
    }
  }
}

/**
 * A manager for on-chain keys used by Eclair, to be used in combination with a watch-only descriptor-based wallet managed by Bitcoin Core.
 * In this setup, Bitcoin Core handles all non-sensitive wallet tasks (including watching the blockchain and building transactions), while
 * Eclair is in charge of signing transactions.
 * This is an advanced feature particularly suited when Eclair runs in a secure runtime.
 */
class LocalOnChainKeyManager(override val walletName: String, seed: ByteVector, val walletTimestamp: TimestampSecond, chainHash: BlockHash) extends OnChainKeyManager with Logging {

  // Master key derived from our seed. We use it to generate BIP84 and BIP86 wallets that can be used:
  // - to generate watch-only wallets with any BIP84/BIP86-compatible bitcoin wallet
  // - to generate descriptors that can be imported into Bitcoin Core to create a watch-only wallet which can be used
  //   by Eclair to fund transactions (only Eclair will be able to sign wallet inputs)
  private val master = DeterministicWallet.generate(seed)
  private val fingerprint = DeterministicWallet.fingerprint(master) & 0xFFFFFFFFL
  private val fingerPrintHex = String.format("%8s", fingerprint.toHexString).replace(' ', '0')

  // Root BIP32 on-chain path: we use BIP84 (p2wpkh) paths: m/84h/{0h/1h}
  private val rootPathBIP84 = chainHash match {
    case Block.RegtestGenesisBlock.hash | Block.Testnet3GenesisBlock.hash | Block.Testnet4GenesisBlock.hash | Block.SignetGenesisBlock.hash => "84h/1h"
    case Block.LivenetGenesisBlock.hash => "84h/0h"
    case _ => throw new IllegalArgumentException(s"invalid chain hash $chainHash")
  }
  private val rootKeyBIP84 = DeterministicWallet.derivePrivateKey(master, KeyPath(rootPathBIP84))

  // Root BIP32 on-chain path: we use BIP86 (p2tr) paths: m/86h/{0h/1h}
  private val rootPathBIP86 = chainHash match {
    case Block.RegtestGenesisBlock.hash | Block.Testnet3GenesisBlock.hash | Block.Testnet4GenesisBlock.hash | Block.SignetGenesisBlock.hash => "86h/1h"
    case Block.LivenetGenesisBlock.hash => "86h/0h"
    case _ => throw new IllegalArgumentException(s"invalid chain hash $chainHash")
  }
  private val rootKeyBIP86 = DeterministicWallet.derivePrivateKey(master, KeyPath(rootPathBIP86))

  private def addressType(keyPath: KeyPath): AddressType = keyPath.path.headOption match {
    case Some(prefix) if prefix == hardened(86) => AddressType.P2tr
    case _ => AddressType.P2wpkh
  }

  override def masterPubKey(account: Long, addressType: AddressType): String = addressType match {
    case AddressType.P2tr =>
      val prefix = chainHash match {
        case Block.RegtestGenesisBlock.hash | Block.Testnet3GenesisBlock.hash | Block.Testnet4GenesisBlock.hash | Block.SignetGenesisBlock.hash => tpub
        case Block.LivenetGenesisBlock.hash => xpub
        case _ => throw new IllegalArgumentException(s"invalid chain hash $chainHash")
      }
      // master pubkey for account 0 is m/86h/{0h/1h}/0h
      val accountPub = DeterministicWallet.publicKey(DeterministicWallet.derivePrivateKey(rootKeyBIP86, hardened(account)))
      DeterministicWallet.encode(accountPub, prefix)
    case AddressType.P2wpkh =>
      val prefix = chainHash match {
        case Block.RegtestGenesisBlock.hash | Block.Testnet3GenesisBlock.hash | Block.Testnet4GenesisBlock.hash | Block.SignetGenesisBlock.hash => vpub
        case Block.LivenetGenesisBlock.hash => zpub
        case _ => throw new IllegalArgumentException(s"invalid chain hash $chainHash")
      }
      // master pubkey for account 0 is m/84h/{0h/1h}/0h
      val accountPub = DeterministicWallet.publicKey(DeterministicWallet.derivePrivateKey(rootKeyBIP84, hardened(account)))
      DeterministicWallet.encode(accountPub, prefix)
  }

  override def derivePublicKey(keyPath: KeyPath): (Crypto.PublicKey, String) = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._
    val pub = DeterministicWallet.derivePrivateKey(master, keyPath).publicKey
    val address = addressType(keyPath) match {
      case AddressType.P2tr => fr.acinq.bitcoin.Bitcoin.computeBIP86Address(pub, chainHash)
      case AddressType.P2wpkh => computeBIP84Address(pub, chainHash)
    }
    (pub, address)
  }

  override def descriptors(account: Long): Descriptors = {
    val prefix = chainHash match {
      case Block.LivenetGenesisBlock.hash => xpub
      case _ => tpub
    }
    val descriptorsBIP84 = {
      val keyPath = s"$rootPathBIP84/${account}h"
      val accountPub = DeterministicWallet.publicKey(DeterministicWallet.derivePrivateKey(rootKeyBIP84, hardened(account)))
      // descriptors for account 0 are:
      // 84h/{0h/1h}/0h/0/* for main addresses
      // 84h/{0h/1h}/0h/1/* for change addresses
      val receiveDesc = s"wpkh([$fingerPrintHex/$keyPath]${encode(accountPub, prefix)}/0/*)"
      val changeDesc = s"wpkh([$fingerPrintHex/$keyPath]${encode(accountPub, prefix)}/1/*)"
      List(
        Descriptor(desc = s"$receiveDesc#${fr.acinq.bitcoin.Descriptor.checksum(receiveDesc)}", internal = false, active = true, timestamp = walletTimestamp.toLong),
        Descriptor(desc = s"$changeDesc#${fr.acinq.bitcoin.Descriptor.checksum(changeDesc)}", internal = true, active = true, timestamp = walletTimestamp.toLong),
      )
    }
    val descriptorsBIP86 = {
      val keyPath = s"$rootPathBIP86/${account}h"
      val accountPub = DeterministicWallet.publicKey(DeterministicWallet.derivePrivateKey(rootKeyBIP86, hardened(account)))
      // descriptors for account 0 are:
      // 86h/{0h/1h}/0h/0/* for main addresses
      // 86h/{0h/1h}/0h/1/* for change addresses
      val receiveDesc = s"tr([$fingerPrintHex/$keyPath]${encode(accountPub, prefix)}/0/*)"
      val changeDesc = s"tr([$fingerPrintHex/$keyPath]${encode(accountPub, prefix)}/1/*)"
      List(
        Descriptor(desc = s"$receiveDesc#${fr.acinq.bitcoin.Descriptor.checksum(receiveDesc)}", internal = false, active = true, timestamp = walletTimestamp.toLong),
        Descriptor(desc = s"$changeDesc#${fr.acinq.bitcoin.Descriptor.checksum(changeDesc)}", internal = true, active = true, timestamp = walletTimestamp.toLong),
      )
    }
    Descriptors(wallet_name = walletName, descriptors = descriptorsBIP84 ++ descriptorsBIP86)
  }

  override def sign(psbt: Psbt, ourInputs: Seq[Int], ourOutputs: Seq[Int]): Try[Psbt] = {
    for {
      spent <- spentAmount(psbt, ourInputs)
      change <- changeAmount(psbt, ourOutputs)
      _ = logger.debug(s"signing txid=${psbt.global.tx.txid} fees=${psbt.computeFees()} spent=$spent change=$change")
      _ <- Try {
        ourOutputs.foreach(i => require(isOurOutput(psbt, i), s"could not verify output $i: bitcoin core may be malicious"))
      }
      signed <- ourInputs.foldLeft(Try(psbt)) {
        case (Failure(psbt), _) => Failure(psbt)
        case (Success(psbt), i) => signPsbtInput(psbt, i)
      }
    } yield signed
  }

  private def spentAmount(psbt: Psbt, ourInputs: Seq[Int]): Try[Satoshi] = Try {
    ourInputs.map(i => {
      val input = psbt.getInput(i)
      require(input != null, s"input $i is missing from psbt: bitcoin core may be malicious")
      require(input.getWitnessUtxo != null, s"input $i does not have a witness utxo: bitcoin core may be malicious")
      fr.acinq.bitcoin.scalacompat.KotlinUtils.kmp2scala(input.getWitnessUtxo.amount)
    }).sum
  }

  private def changeAmount(psbt: Psbt, ourOutputs: Seq[Int]): Try[Satoshi] = Try {
    ourOutputs.map(i => {
      require(psbt.global.tx.txOut.size() > i, s"output $i is missing from psbt: bitcoin core may be malicious")
      fr.acinq.bitcoin.scalacompat.KotlinUtils.kmp2scala(psbt.global.tx.txOut.get(i).amount)
    }).sum
  }

  /** Check that an output belongs to us (i.e. we can recompute its public key from its bip32 path). */
  private def isOurOutput(psbt: Psbt, outputIndex: Int): Boolean = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._

    if (psbt.outputs.size() <= outputIndex || psbt.global.tx.txOut.size() <= outputIndex) {
      return false
    }
    val output = psbt.outputs.get(outputIndex)
    val txOut = psbt.global.tx.txOut.get(outputIndex)

    // We first check segwit v0.
    output.getDerivationPaths.asScala.headOption match {
      case Some((pub, keypath)) =>
        val expectedKey = derivePublicKey(keypath.keyPath)._1
        if (pub != KotlinUtils.scala2kmp(expectedKey)) {
          logger.warn(s"public key mismatch (expected=$expectedKey, actual=$pub): bitcoin core may be malicious")
          false
        } else if (txOut.publicKeyScript != KotlinUtils.scala2kmp(Script.write(Script.pay2wpkh(expectedKey)))) {
          logger.warn(s"script mismatch (expected=${Script.write(Script.pay2wpkh(expectedKey))}, actual=${txOut.publicKeyScript}): bitcoin core may be malicious")
          false
        } else {
          true
        }
      case None =>
        // Otherwise, this may be a taproot input.
        output.getTaprootDerivationPaths.asScala.headOption match {
          case Some((pub, keypath)) =>
            val expectedKey = derivePublicKey(keypath.keyPath)._1
            if (pub != output.getTaprootInternalKey) {
              logger.warn("internal key mismatch: bitcoin core may be malicious")
              false
            } else if (pub != KotlinUtils.scala2kmp(expectedKey.xOnly)) {
              logger.warn(s"public key mismatch (expected=$expectedKey, actual=$pub): bitcoin core may be malicious")
              false
            } else if (txOut.publicKeyScript != KotlinUtils.scala2kmp(Script.write(Script.pay2tr(expectedKey.xOnly, None)))) {
              logger.warn(s"script mismatch (expected=${Script.write(Script.pay2tr(expectedKey.xOnly, None))}, actual=${txOut.publicKeyScript}): bitcoin core may be malicious")
              false
            } else {
              true
            }
          case None =>
            logger.warn("derivation path is missing: bitcoin core may be malicious")
            false
        }
    }
  }

  private def signPsbtInput(psbt: Psbt, pos: Int): Try[Psbt] = Try {
    val input = psbt.getInput(pos)
    require(input != null, s"input $pos is missing from psbt: bitcoin core may be malicious")
    if (input.getTaprootInternalKey != null) signPsbtInput86(psbt, pos) else signPsbtInput84(psbt, pos)
  }

  private def signPsbtInput84(psbt: Psbt, pos: Int): Psbt = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._
    import fr.acinq.bitcoin.{Script, SigHash}

    val input = psbt.getInput(pos)
    require(input != null, s"input $pos is missing from psbt: bitcoin core may be malicious")

    // For each wallet input, Bitcoin Core will provide:
    // - the output that was spent, in the PSBT's witness utxo field
    // - the actual transaction that was spent, in the PSBT's non-witness utxo field
    // We check that these fields are consistent and match the outpoint that is spent in the PSBT.
    // This prevents attacks where Bitcoin Core would lie about the amount being spent and make us pay very high fees.
    require(input.getNonWitnessUtxo != null, "non-witness utxo is missing: bitcoin core may be malicious")
    require(input.getNonWitnessUtxo.txid == psbt.global.tx.txIn.get(pos).outPoint.txid, "utxo txid mismatch: bitcoin core may be malicious")
    require(input.getNonWitnessUtxo.txOut.get(psbt.global.tx.txIn.get(pos).outPoint.index.toInt) == input.getWitnessUtxo, "utxo mismatch: bitcoin core may be malicious")

    // We must use SIGHASH_ALL, otherwise we would be vulnerable to "signature reuse" attacks.
    // When unspecified, the sighash used will be SIGHASH_ALL.
    require(Option(input.getSighashType).forall(_ == SigHash.SIGHASH_ALL), s"input sighash must be SIGHASH_ALL (got=${input.getSighashType}): bitcoin core may be malicious")

    // Check that we're signing a p2wpkh input and that the keypath is provided and correct.
    require(input.getDerivationPaths.size() == 1, "bip32 derivation path is missing: bitcoin core may be malicious")
    val (pub, keypath) = input.getDerivationPaths.asScala.toSeq.head
    val priv = fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey(master.priv, keypath.keyPath).getPrivateKey
    require(priv.publicKey() == pub, s"derived public key doesn't match (expected=$pub actual=${priv.publicKey()}): bitcoin core may be malicious")
    val expectedScript = ByteVector(Script.write(Script.pay2wpkh(pub)))
    require(kmp2scala(input.getWitnessUtxo.publicKeyScript) == expectedScript, s"script mismatch (expected=$expectedScript, actual=${input.getWitnessUtxo.publicKeyScript}): bitcoin core may be malicious")

    // Update the input with the right script for a p2wpkh input, which is a *p2pkh* script, then sign and finalize.
    psbt.updateWitnessInput(
        psbt.global.tx.txIn.get(pos).outPoint,
        input.getWitnessUtxo,
        null,
        Script.pay2pkh(pub),
        SigHash.SIGHASH_ALL,
        input.getDerivationPaths,
        null,
        null,
        java.util.Map.of())
      .flatMap(_.sign(priv, pos))
      .flatMap(s => {
        require(s.getSig.last.toInt == SigHash.SIGHASH_ALL, "signature must end with SIGHASH_ALL")
        s.getPsbt.finalizeWitnessInput(pos, Script.witnessPay2wpkh(pub, s.getSig))
      }) match {
      case Right(psbt) => psbt
      case Left(failure) => throw new RuntimeException(s"cannot sign psbt input, error = $failure")
    }
  }

  private def signPsbtInput86(psbt: Psbt, pos: Int): Psbt = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._
    import fr.acinq.bitcoin.{Script, SigHash}

    val input = psbt.getInput(pos)
    require(input != null, s"input $pos is missing from psbt: bitcoin core may be malicious")
    require(Option(input.getSighashType).forall(_ == SigHash.SIGHASH_DEFAULT), s"input sighash must be SIGHASH_DEFAULT (got=${input.getSighashType}): bitcoin core may be malicious")

    // Check that we're signing a p2tr input and that the keypath is provided and correct.
    require(input.getTaprootDerivationPaths.size() == 1, "bip32 derivation path is missing: bitcoin core may be malicious")
    val (pub, keypath) = input.getTaprootDerivationPaths.asScala.toSeq.head
    val priv = fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey(master.priv, keypath.keyPath).getPrivateKey
    require(priv.publicKey().xOnly() == pub, s"derived public key doesn't match (expected=$pub actual=${priv.publicKey().xOnly()}): bitcoin core may be malicious")
    val expectedScript = ByteVector(Script.write(Script.pay2tr(pub, null.asInstanceOf[ScriptTree])))
    require(kmp2scala(input.getWitnessUtxo.publicKeyScript) == expectedScript, s"script mismatch (expected=$expectedScript, actual=${input.getWitnessUtxo.publicKeyScript}): bitcoin core may be malicious")

    // No need to update the input, we can directly sign and finalize.
    psbt
      .sign(priv, pos)
      .flatMap(s => s.getPsbt.finalizeWitnessInput(pos, Script.witnessKeyPathPay2tr(ByteVector64(s.getSig), SigHash.SIGHASH_DEFAULT))) match {
      case Right(psbt) => psbt
      case Left(failure) => throw new RuntimeException(s"cannot sign psbt input, error = $failure")
    }
  }
}
