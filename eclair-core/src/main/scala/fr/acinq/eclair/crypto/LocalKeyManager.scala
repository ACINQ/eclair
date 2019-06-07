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

package fr.acinq.eclair.crypto

import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import fr.acinq.bitcoin.DeterministicWallet.{derivePrivateKey, _}
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto, DeterministicWallet}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.channel.LocalParams
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.TransactionWithInputInfo
import scodec.bits.{ByteOrdering, ByteVector}

object LocalKeyManager {
  def channelKeyBasePath(chainHash: ByteVector32) = chainHash match {
    case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(2) :: Nil
    case Block.LivenetGenesisBlock.hash => DeterministicWallet.hardened(47) :: DeterministicWallet.hardened(2) :: Nil
  }


  // WARNING: if you change this path, you will change your node id even if the seed remains the same!!!
  // Note that the node path and the above channel path are on different branches so even if the
  // node key is compromised there is no way to retrieve the wallet keys
  def nodeKeyBasePath(chainHash: ByteVector32) = chainHash match {
    case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash => DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil
    case Block.LivenetGenesisBlock.hash => DeterministicWallet.hardened(47) :: DeterministicWallet.hardened(0) :: Nil
  }

  // split the SHA into 8 groups of 4 bytes and convert to uint32
  def fourByteGroupsFromSha(input: ByteVector): List[Long] = Crypto.sha256(input).toArray.grouped(4).map(ByteVector(_).toLong(signed = false)).toList

  def makeChannelKeyPathFunder(entropy: ByteVector) = KeyPath(fourByteGroupsFromSha(entropy) :+ 0L)
  def makeChannelKeyPathFundee(entropy: ByteVector) = KeyPath(fourByteGroupsFromSha(entropy) :+ 1L)
  def makeChannelKeyPathFundeePubkey(entropy: ByteVector) = KeyPath(fourByteGroupsFromSha(entropy) :+ 2L)

  def makeChannelKeyPathFundeePubkey(blockHeight: Long, counter: Long): KeyPath = {
    val blockHeightBytes = ByteVector.fromLong(blockHeight, size = 4, ordering = ByteOrdering.LittleEndian)
    val counterBytes = ByteVector.fromLong(counter, size = 4, ordering = ByteOrdering.LittleEndian)

    makeChannelKeyPathFundeePubkey(blockHeightBytes ++ counterBytes)
  }

}

/**
  * This class manages secrets and private keys.
  * It exports points and public keys, and provides signing methods
  *
  * @param seed seed from which keys will be derived
  */
class LocalKeyManager(seed: ByteVector, chainHash: ByteVector32) extends KeyManager {

  private val master = DeterministicWallet.generate(seed)

  override val nodeKey = DeterministicWallet.derivePrivateKey(master, LocalKeyManager.nodeKeyBasePath(chainHash))
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

  private def internalKeyPath(channelKeyPath: DeterministicWallet.KeyPath, index: Long): List[Long] = (LocalKeyManager.channelKeyBasePath(chainHash) ++ channelKeyPath.path) :+ index

  private def fundingPrivateKey(channelKeyPath: DeterministicWallet.KeyPath) = privateKeys.get(internalKeyPath(channelKeyPath, 0))

  private def revocationSecret(channelKeyPath: DeterministicWallet.KeyPath) = privateKeys.get(internalKeyPath(channelKeyPath, 1))

  private def paymentSecret(channelKeyPath: DeterministicWallet.KeyPath) = privateKeys.get(internalKeyPath(channelKeyPath, 2))

  private def delayedPaymentSecret(channelKeyPath: DeterministicWallet.KeyPath) = privateKeys.get(internalKeyPath(channelKeyPath, 3))

  private def htlcSecret(channelKeyPath: DeterministicWallet.KeyPath) = privateKeys.get(internalKeyPath(channelKeyPath, 4))

  private def shaSeed(channelKeyPath: DeterministicWallet.KeyPath) = Crypto.sha256(privateKeys.get(internalKeyPath(channelKeyPath, 5)).privateKey.toBin)

  def shaSeedPub(channelKeyPath: DeterministicWallet.KeyPath) = publicKeys.get(internalKeyPath(channelKeyPath, 5))

  override def fundingPublicKey(channelKeyPath: DeterministicWallet.KeyPath) = publicKeys.get(internalKeyPath(channelKeyPath, 0))

  override def revocationPoint(channelKeyPath: DeterministicWallet.KeyPath) = publicKeys.get(internalKeyPath(channelKeyPath, 1))

  override def paymentPoint(channelKeyPath: DeterministicWallet.KeyPath) = publicKeys.get(internalKeyPath(channelKeyPath, 2))

  override def delayedPaymentPoint(channelKeyPath: DeterministicWallet.KeyPath) = publicKeys.get(internalKeyPath(channelKeyPath, 3))

  override def htlcPoint(channelKeyPath: DeterministicWallet.KeyPath) = publicKeys.get(internalKeyPath(channelKeyPath, 4))

  override def commitmentSecret(channelKeyPath: DeterministicWallet.KeyPath, index: Long) = Generators.perCommitSecret(shaSeed(channelKeyPath), index)

  override def commitmentPoint(channelKeyPath: DeterministicWallet.KeyPath, index: Long) = Generators.perCommitPoint(shaSeed(channelKeyPath), index)

  /**
    * DETERMINISTIC
    */

  override def deterministicFundingPublicKey(localParams: LocalParams): ExtendedPublicKey = {
    val keyPath = localParams.channelKeyPath match {
      case Left(kp) => kp
      case Right(keyPathFundee) => keyPathFundee.publicKeyPath
    }

    fundingPublicKey(keyPath)
  }

  override def deterministicRevocationPoint(localParams: LocalParams): ExtendedPublicKey = {
    val keyPath = localParams.channelKeyPath match {
      case Left(kp) => kp
      case Right(keyPathFundee) => keyPathFundee.pointsKeyPath
    }

    revocationPoint(keyPath)
  }

  override def deterministicPaymentPoint(localParams: LocalParams): ExtendedPublicKey = {
    val keyPath = localParams.channelKeyPath match {
      case Left(kp) => kp
      case Right(keyPathFundee) => keyPathFundee.pointsKeyPath
    }

    paymentPoint(keyPath)

  }

  override def deterministicDelayedPaymentPoint(localParams: LocalParams): ExtendedPublicKey = {
    val keyPath = localParams.channelKeyPath match {
      case Left(kp) => kp
      case Right(keyPathFundee) => keyPathFundee.pointsKeyPath
    }

    delayedPaymentPoint(keyPath)

  }

  override def deterministicHtlcPoint(localParams: LocalParams): ExtendedPublicKey = {
    val keyPath = localParams.channelKeyPath match {
      case Left(kp) => kp
      case Right(keyPathFundee) => keyPathFundee.pointsKeyPath
    }

    htlcPoint(keyPath)

  }

  override def deterministicCommitmentSecret(localParams: LocalParams, index: Long): Scalar = {
    val keyPath = localParams.channelKeyPath match {
      case Left(kp) => kp
      case Right(keyPathFundee) => keyPathFundee.pointsKeyPath
    }

    commitmentSecret(keyPath, index)
  }

  override def deterministicCommitmentPoint(localParams: LocalParams, index: Long): Point = {
    val keyPath = localParams.channelKeyPath match {
      case Left(kp) => kp
      case Right(keyPathFundee) => keyPathFundee.pointsKeyPath
    }

    commitmentPoint(keyPath, index)
  }

  /**
    *
    * @param tx        input transaction
    * @param publicKey extended public key
    * @return a signature generated with the private key that matches the input
    *         extended public key
    */
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey): ByteVector = {
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
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remotePoint: Point): ByteVector = {
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
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remoteSecret: Scalar): ByteVector = {
    val privateKey = privateKeys.get(publicKey.path)
    val currentKey = Generators.revocationPrivKey(privateKey.privateKey, remoteSecret)
    Transactions.sign(tx, currentKey)
  }

  override def signChannelAnnouncement(channelKeyPath: DeterministicWallet.KeyPath, chainHash: ByteVector32, shortChannelId: ShortChannelId, remoteNodeId: PublicKey, remoteFundingKey: PublicKey, features: ByteVector): (ByteVector, ByteVector) = {

    val witness = if (Announcements.isNode1(nodeId, remoteNodeId)) {
      Announcements.channelAnnouncementWitnessEncode(chainHash, shortChannelId, nodeId, remoteNodeId, fundingPublicKey(channelKeyPath).publicKey, remoteFundingKey, features)
    } else {
      Announcements.channelAnnouncementWitnessEncode(chainHash, shortChannelId, remoteNodeId, nodeId, remoteFundingKey, fundingPublicKey(channelKeyPath).publicKey, features)
    }
    val nodeSig = Crypto.encodeSignature(Crypto.sign(witness, nodeKey.privateKey)) :+ 1.toByte
    val bitcoinSig = Crypto.encodeSignature(Crypto.sign(witness, fundingPrivateKey(channelKeyPath).privateKey)) :+ 1.toByte
    (nodeSig, bitcoinSig)
  }

  override def deterministicSignChannelAnnouncement(localParams: LocalParams, chainHash: ByteVector32, shortChannelId: ShortChannelId, remoteNodeId: PublicKey, remoteFundingKey: PublicKey, features: ByteVector): (ByteVector, ByteVector) = {

    val keyPath = localParams.channelKeyPath match {
      case Left(kp) => kp
      case Right(keyPathFundee) => keyPathFundee.publicKeyPath
    }

    signChannelAnnouncement(keyPath, chainHash, shortChannelId, remoteNodeId, remoteFundingKey, features)
  }

}
