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
import fr.acinq.bitcoin.psbt.{Psbt, UpdateFailure}
import fr.acinq.bitcoin.scalacompat.DeterministicWallet._
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, DeterministicWallet, MnemonicCode, Satoshi, Script, computeBIP84Address}
import fr.acinq.eclair.TimestampSecond
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.{Descriptor, Descriptors}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCClient
import grizzled.slf4j.Logging
import org.json4s.{JArray, JBool}
import scodec.bits.ByteVector

import java.io.File
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.{CollectionHasAsScala, MapHasAsScala}
import scala.util.{Failure, Success, Try}

object LocalOnChainKeyManager extends Logging {

  /**
   * Load a configuration file and create an on-chain key manager
   *
   * @param datadir   eclair data directory
   * @param chainHash chain we're on
   * @return a LocalOnChainKeyManager instance if a configuration file exists
   */
  def load(datadir: File, chainHash: ByteVector32): Option[LocalOnChainKeyManager] = {
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
      logger.info(s"using on-chain key manager wallet=$wallet xpub=${keyManager.masterPubKey(0)}")
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
class LocalOnChainKeyManager(override val walletName: String, seed: ByteVector, val walletTimestamp: TimestampSecond, chainHash: ByteVector32) extends OnChainKeyManager with Logging {

  import LocalOnChainKeyManager._

  // Master key derived from our seed. We use it to generate a BIP84 wallet that can be used:
  // - to generate a watch-only wallet with any BIP84-compatible bitcoin wallet
  // - to generate descriptors that can be imported into Bitcoin Core to create a watch-only wallet which can be used
  //   by Eclair to fund transactions (only Eclair will be able to sign wallet inputs)
  private val master = DeterministicWallet.generate(seed)
  private val fingerprint = DeterministicWallet.fingerprint(master) & 0xFFFFFFFFL
  private val fingerPrintHex = String.format("%8s", fingerprint.toHexString).replace(' ', '0')
  // Root BIP32 on-chain path: we use BIP84 (p2wpkh) paths: m/84'/{0'/1'}
  private val rootPath = chainHash match {
    case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash | Block.SignetGenesisBlock.hash => "84'/1'"
    case Block.LivenetGenesisBlock.hash => "84'/0'"
    case _ => throw new IllegalArgumentException(s"invalid chain hash $chainHash")
  }
  private val rootKey = DeterministicWallet.derivePrivateKey(master, KeyPath(rootPath))

  override def masterPubKey(account: Long): String = {
    val prefix = chainHash match {
      case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash | Block.SignetGenesisBlock.hash => vpub
      case Block.LivenetGenesisBlock.hash => zpub
      case _ => throw new IllegalArgumentException(s"invalid chain hash $chainHash")
    }
    // master pubkey for account 0 is m/84'/{0'/1'}/0'
    val accountPub = DeterministicWallet.publicKey(DeterministicWallet.derivePrivateKey(rootKey, hardened(account)))
    DeterministicWallet.encode(accountPub, prefix)
  }

  override def derivePublicKey(keyPath: KeyPath): (Crypto.PublicKey, String) = {
    val pub = DeterministicWallet.derivePrivateKey(master, keyPath).publicKey
    val address = computeBIP84Address(pub, chainHash)
    (pub, address)
  }

  override def descriptors(account: Long): Descriptors = {
    // TODO: we should use 'h' everywhere once bitcoin-kmp supports it.
    val keyPath = s"$rootPath/$account'".replace('\'', 'h')
    val prefix = chainHash match {
      case Block.LivenetGenesisBlock.hash => xpub
      case _ => tpub
    }
    val accountPub = DeterministicWallet.publicKey(DeterministicWallet.derivePrivateKey(rootKey, hardened(account)))
    // descriptors for account 0 are:
    // 84'/{0'/1'}/0'/0/* for main addresses
    // 84'/{0'/1'}/0'/1/* for change addresses
    val receiveDesc = s"wpkh([$fingerPrintHex/$keyPath]${encode(accountPub, prefix)}/0/*)"
    val changeDesc = s"wpkh([$fingerPrintHex/$keyPath]${encode(accountPub, prefix)}/1/*)"
    Descriptors(wallet_name = walletName, descriptors = List(
      Descriptor(desc = s"$receiveDesc#${fr.acinq.bitcoin.Descriptor.checksum(receiveDesc)}", internal = false, active = true, timestamp = walletTimestamp.toLong),
      Descriptor(desc = s"$changeDesc#${fr.acinq.bitcoin.Descriptor.checksum(changeDesc)}", internal = true, active = true, timestamp = walletTimestamp.toLong),
    ))
  }

  override def sign(psbt: Psbt, ourInputs: Seq[Int], ourOutputs: Seq[Int]): Try[Psbt] = {
    for {
      spent <- spentAmount(psbt, ourInputs)
      change <- changeAmount(psbt, ourOutputs)
      _ = logger.debug(s"signing txid=${psbt.getGlobal.getTx.txid} fees=${psbt.computeFees()} spent=$spent change=$change")
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
      require(psbt.getGlobal.getTx.txOut.size() > i, s"output $i is missing from psbt: bitcoin core may be malicious")
      fr.acinq.bitcoin.scalacompat.KotlinUtils.kmp2scala(psbt.getGlobal.getTx.txOut.get(i).amount)
    }).sum
  }

  /** Check that an output belongs to us (i.e. we can recompute its public key from its bip32 path). */
  private def isOurOutput(psbt: Psbt, outputIndex: Int): Boolean = {
    import fr.acinq.bitcoin.scalacompat.KotlinUtils._
    if (psbt.getOutputs.size() <= outputIndex || psbt.getGlobal.getTx.txOut.size() <= outputIndex) {
      return false
    }
    val output = psbt.getOutputs.get(outputIndex)
    val txOut = psbt.getGlobal.getTx.txOut.get(outputIndex)
    output.getDerivationPaths.asScala.headOption match {
      case Some((pub, keypath)) =>
        val (expectedPubKey, _) = derivePublicKey(KeyPath(keypath.getKeyPath.path.asScala.toSeq.map(_.longValue())))
        val expectedScript = Script.write(Script.pay2wpkh(expectedPubKey))
        if (expectedPubKey != kmp2scala(pub)) {
          logger.warn(s"public key mismatch (expected=$expectedPubKey, actual=$pub): bitcoin core may be malicious")
          false
        } else if (kmp2scala(txOut.publicKeyScript) != expectedScript) {
          logger.warn(s"script mismatch (expected=$expectedScript, actual=${txOut.publicKeyScript}): bitcoin core may be malicious")
          false
        } else {
          true
        }
      case None =>
        logger.warn("derivation path is missing: bitcoin core may be malicious")
        false
    }
  }

  private def signPsbtInput(psbt: Psbt, pos: Int): Try[Psbt] = Try {
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
    require(input.getNonWitnessUtxo.txid == psbt.getGlobal.getTx.txIn.get(pos).outPoint.txid, "utxo txid mismatch: bitcoin core may be malicious")
    require(input.getNonWitnessUtxo.txOut.get(psbt.getGlobal.getTx.txIn.get(pos).outPoint.index.toInt) == input.getWitnessUtxo, "utxo mismatch: bitcoin core may be malicious")

    // We must use SIGHASH_ALL, otherwise we would be vulnerable to "signature reuse" attacks.
    // When unspecified, the sighash used will be SIGHASH_ALL.
    require(Option(input.getSighashType).forall(_ == SigHash.SIGHASH_ALL), s"input sighash must be SIGHASH_ALL (got=${input.getSighashType}): bitcoin core may be malicious")

    // Check that we're signing a p2wpkh input and that the keypath is provided and correct.
    require(input.getDerivationPaths.size() == 1, "bip32 derivation path is missing: bitcoin core may be malicious")
    val (pub, keypath) = input.getDerivationPaths.asScala.toSeq.head
    val priv = fr.acinq.bitcoin.DeterministicWallet.derivePrivateKey(master.priv, keypath.getKeyPath).getPrivateKey
    require(priv.publicKey() == pub, s"derived public key doesn't match (expected=$pub actual=${priv.publicKey()}): bitcoin core may be malicious")
    val expectedScript = ByteVector(Script.write(Script.pay2wpkh(pub)))
    require(kmp2scala(input.getWitnessUtxo.publicKeyScript) == expectedScript, s"script mismatch (expected=$expectedScript, actual=${input.getWitnessUtxo.publicKeyScript}): bitcoin core may be malicious")

    // Update the input with the right script for a p2wpkh input, which is a *p2pkh* script, then sign and finalize.
    val updated: Either[UpdateFailure, Psbt] = psbt.updateWitnessInput(psbt.getGlobal.getTx.txIn.get(pos).outPoint, input.getWitnessUtxo, null, Script.pay2pkh(pub), SigHash.SIGHASH_ALL, input.getDerivationPaths)
    val signed = updated.flatMap(_.sign(priv, pos))
    val finalized = signed.flatMap(s => {
      require(s.getSig.last.toInt == SigHash.SIGHASH_ALL, "signature must end with SIGHASH_ALL")
      s.getPsbt.finalizeWitnessInput(pos, Script.witnessPay2wpkh(pub, s.getSig))
    })
    finalized match {
      case Right(psbt) => psbt
      case Left(failure) => throw new RuntimeException(s"cannot sign psbt input, error = $failure")
    }
  }
}
