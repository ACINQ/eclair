package fr.acinq.eclair.crypto

import java.io.File
import java.nio.file.Files

import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey, Scalar}
import fr.acinq.bitcoin.DeterministicWallet._
import fr.acinq.bitcoin.{BinaryData, Crypto, DeterministicWallet, MnemonicCode}
import DeterministicWallet.derivePrivateKey
import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import fr.acinq.eclair.{NodeParams, randomKey}
import fr.acinq.eclair.channel.Commitments
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo
import fr.acinq.eclair.wire.AnnouncementSignatures

object KeyManager {
  val nodeKeyPath = DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil

  def readOrCreateEntropy(datadir: File): BinaryData = {
    val entropyPath = new File(datadir, "seed.dat")
    val entropy: BinaryData = entropyPath.exists() match {
      case true => Files.readAllBytes(entropyPath.toPath)
      case false =>
        val seed = randomKey.toBin
        Files.write(entropyPath.toPath, seed)
        seed
    }
    entropy
  }
}

/**
  * This class manages secrets and private keys.
  * It exports points and public keys, and provides signing methods
  *
  * @param entropy secret entropy from which keys will be derived
  */
class KeyManager(entropy: BinaryData) {
  def this(datadir: File) = this(KeyManager.readOrCreateEntropy(datadir))

  // entropy -> mnemonics
  // mnemonics + passphrase -> seed
  // seed -> BIP32 master key
  val mnemonicCode = MnemonicCode.toMnemonics(entropy)
  val seed = MnemonicCode.toSeed(mnemonicCode, "")
  val master = DeterministicWallet.generate(seed)
  val nodeKey = DeterministicWallet.derivePrivateKey(master, KeyManager.nodeKeyPath)
  val nodeId = nodeKey.publicKey

  val privateKeys: LoadingCache[KeyPath, ExtendedPrivateKey] = CacheBuilder.newBuilder()
    .maximumSize(6 * 200) // 6 keys per channel * 200 channels
    .build[KeyPath, ExtendedPrivateKey](new CacheLoader[KeyPath, ExtendedPrivateKey] {
    override def load(keyPath: KeyPath): ExtendedPrivateKey = derivePrivateKey(master, keyPath)
  })

  val publicKeys: LoadingCache[KeyPath, ExtendedPublicKey] = CacheBuilder.newBuilder()
    .maximumSize(6 * 200) // 6 keys per channel * 200 channels
    .build[KeyPath, ExtendedPublicKey](new CacheLoader[KeyPath, ExtendedPublicKey] {
    override def load(keyPath: KeyPath): ExtendedPublicKey = publicKey(privateKeys.get(keyPath))
  })

  def channelKeyPath(channelNumber: Long, index: Long): List[Long] = KeyManager.nodeKeyPath ::: channelNumber :: index :: Nil


  private def fundingPrivateKey(channelNumber: Long) = privateKeys.get(channelKeyPath(channelNumber, 0))

  private def revocationSecret(channelNumber: Long) = privateKeys.get(channelKeyPath(channelNumber, 1))

  private def paymentSecret(channelNumber: Long) = privateKeys.get(channelKeyPath(channelNumber, 2))

  private def delayedPaymentSecret(channelNumber: Long) = privateKeys.get(channelKeyPath(channelNumber, 3))

  private def htlcSecret(channelNumber: Long) = privateKeys.get(channelKeyPath(channelNumber, 4))

  private def shaSeed(channelNumber: Long) = Crypto.sha256(privateKeys.get(channelKeyPath(channelNumber, 5)).privateKey.toBin)

  def fundingPublicKey(channelNumber: Long) = publicKeys.get(channelKeyPath(channelNumber, 0))

  def revocationPoint(channelNumber: Long) = publicKeys.get(channelKeyPath(channelNumber, 1))

  def paymentPoint(channelNumber: Long) = publicKeys.get(channelKeyPath(channelNumber, 2))

  def delayedPaymentPoint(channelNumber: Long) = publicKeys.get(channelKeyPath(channelNumber, 3))

  def htlcPoint(channelNumber: Long) = publicKeys.get(channelKeyPath(channelNumber, 4))

  def commitmentSecret(channelNumber: Long, index: Long) = Generators.perCommitSecret(shaSeed(channelNumber), index)

  def commitmentPoint(channelNumber: Long, index: Long) = Generators.perCommitPoint(shaSeed(channelNumber), index)

  /**
    *
    * @param tx        input transaction
    * @param publicKey extended public key
    * @return a signature generated with the private key that matches the input
    *         extended public key
    */
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey): BinaryData = {
    val privateKey = privateKeys.get(publicKey.path)
    Transactions.sign(tx, privateKey.privateKey)
  }

  /**
    * This method is used to spend funds send to htlc keys/delayed keys
    *
    * @param tx          input transaction
    * @param publicKey   extended public key
    * @param remotePoint remote point
    * @return a signature generated with a private key generated from the input keys's matching
    *         private key and the remote point.
    */
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remotePoint: Point): BinaryData = {
    val privateKey = privateKeys.get(publicKey.path)
    val currentKey = Generators.derivePrivKey(privateKey.privateKey, remotePoint)
    Transactions.sign(tx, currentKey)
  }

  /**
    * Ths method is used to spend revoked transactions, with the corresponding revocation key
    *
    * @param tx           input transaction
    * @param publicKey    extended public key
    * @param remoteSecret remote secret
    * @return a signature generated with a private key generated from the input keys's matching
    *         private key and the remote secret.
    */
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remoteSecret: Scalar): BinaryData = {
    val privateKey = privateKeys.get(publicKey.path)
    val currentKey = Generators.revocationPrivKey(privateKey.privateKey, remoteSecret)
    Transactions.sign(tx, currentKey)
  }

  def signChannelAnnouncement(channelNumber: Long, chainHash: BinaryData, shortChannelId: Long, remoteNodeId: PublicKey, remoteFundingKey: PublicKey, features: BinaryData): (BinaryData, BinaryData) = {
    val witness = if (Announcements.isNode1(nodeId.toBin, remoteNodeId.toBin)) {
      Announcements.channelAnnouncementWitnessEncode(chainHash, shortChannelId, nodeId, remoteNodeId, fundingPublicKey(channelNumber).publicKey, remoteFundingKey, features)
    } else {
      Announcements.channelAnnouncementWitnessEncode(chainHash, shortChannelId, remoteNodeId, nodeId, remoteFundingKey, fundingPublicKey(channelNumber).publicKey, features)
    }
    val nodeSig = Crypto.encodeSignature(Crypto.sign(witness, nodeKey.privateKey)) :+ 1.toByte
    val bitcoinSig = Crypto.encodeSignature(Crypto.sign(witness, fundingPrivateKey(channelNumber).privateKey)) :+ 1.toByte
    (nodeSig, bitcoinSig)
  }

  def makeAnnouncementSignatures(nodeParams: NodeParams, commitments: Commitments, shortChannelId: Long) = {
    // TODO: empty features
    val features = BinaryData("")
    val (localNodeSig, localBitcoinSig) = signChannelAnnouncement(commitments.localParams.channelNumber, nodeParams.chainHash, shortChannelId, commitments.remoteParams.nodeId, commitments.remoteParams.fundingPubKey, features)
    AnnouncementSignatures(commitments.channelId, shortChannelId, localNodeSig, localBitcoinSig)
  }
}
