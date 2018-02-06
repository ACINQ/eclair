package fr.acinq.eclair.crypto

import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import fr.acinq.bitcoin.DeterministicWallet.{derivePrivateKey, _}
import fr.acinq.bitcoin.{BinaryData, Crypto, DeterministicWallet}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo

object LocalKeyManager {
  val nodeKeyPath = DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil
}

/**
  * This class manages secrets and private keys.
  * It exports points and public keys, and provides signing methods
  *
  * @param seed seed from which keys will be derived
  */
class LocalKeyManager(seed: BinaryData) extends KeyManager {
  private val master = DeterministicWallet.generate(seed)

  override val nodeKey = DeterministicWallet.derivePrivateKey(master, LocalKeyManager.nodeKeyPath)
  override val nodeId = nodeKey.publicKey

  private val privateKeys: LoadingCache[KeyPath, ExtendedPrivateKey] = CacheBuilder.newBuilder()
    .maximumSize(6 * 200) // 6 keys per channel * 200 channels
    .build[KeyPath, ExtendedPrivateKey](new CacheLoader[KeyPath, ExtendedPrivateKey] {
    override def load(keyPath: KeyPath): ExtendedPrivateKey = derivePrivateKey(master, keyPath)
  })

  private val publicKeys: LoadingCache[KeyPath, ExtendedPublicKey] = CacheBuilder.newBuilder()
    .maximumSize(6 * 200) // 6 keys per channel * 200 channels
    .build[KeyPath, ExtendedPublicKey](new CacheLoader[KeyPath, ExtendedPublicKey] {
    override def load(keyPath: KeyPath): ExtendedPublicKey = publicKey(privateKeys.get(keyPath))
  })

  private def channelKeyPath(channelNumber: Long, index: Long): List[Long] = LocalKeyManager.nodeKeyPath ::: channelNumber :: index :: Nil

  private def fundingPrivateKey(channelNumber: Long) = privateKeys.get(channelKeyPath(channelNumber, 0))

  private def revocationSecret(channelNumber: Long) = privateKeys.get(channelKeyPath(channelNumber, 1))

  private def paymentSecret(channelNumber: Long) = privateKeys.get(channelKeyPath(channelNumber, 2))

  private def delayedPaymentSecret(channelNumber: Long) = privateKeys.get(channelKeyPath(channelNumber, 3))

  private def htlcSecret(channelNumber: Long) = privateKeys.get(channelKeyPath(channelNumber, 4))

  private def shaSeed(channelNumber: Long) = Crypto.sha256(privateKeys.get(channelKeyPath(channelNumber, 5)).privateKey.toBin)

  override def fundingPublicKey(channelNumber: Long) = publicKeys.get(channelKeyPath(channelNumber, 0))

  override def revocationPoint(channelNumber: Long) = publicKeys.get(channelKeyPath(channelNumber, 1))

  override def paymentPoint(channelNumber: Long) = publicKeys.get(channelKeyPath(channelNumber, 2))

  override def delayedPaymentPoint(channelNumber: Long) = publicKeys.get(channelKeyPath(channelNumber, 3))

  override def htlcPoint(channelNumber: Long) = publicKeys.get(channelKeyPath(channelNumber, 4))

  override def commitmentSecret(channelNumber: Long, index: Long) = Generators.perCommitSecret(shaSeed(channelNumber), index)

  override def commitmentPoint(channelNumber: Long, index: Long) = Generators.perCommitPoint(shaSeed(channelNumber), index)

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
}
