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

package fr.acinq.eclair.crypto.keymanager

import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.DeterministicWallet._
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto, DeterministicWallet}
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.crypto.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.{CommitmentFormat, TransactionWithInputInfo, TxOwner}
import fr.acinq.eclair.{KamonExt, randomLong}
import grizzled.slf4j.Logging
import kamon.tag.TagSet
import scodec.bits.ByteVector

object LocalChannelKeyManager {
  def keyBasePath(chainHash: ByteVector32): List[Long] = (chainHash: @unchecked) match {
    case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash | Block.SignetGenesisBlock.hash => DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(1) :: Nil
    case Block.LivenetGenesisBlock.hash => DeterministicWallet.hardened(47) :: DeterministicWallet.hardened(1) :: Nil
  }
}

/**
 * This class manages channel secrets and private keys.
 * It exports points and public keys, and provides signing methods
 *
 * @param seed seed from which the channel keys will be derived
 */
class LocalChannelKeyManager(seed: ByteVector, chainHash: ByteVector32) extends ChannelKeyManager with Logging {
  private val master = DeterministicWallet.generate(seed)

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

  private def internalKeyPath(channelKeyPath: DeterministicWallet.KeyPath, index: Long): KeyPath = KeyPath((LocalChannelKeyManager.keyBasePath(chainHash) ++ channelKeyPath.path) :+ index)

  private def fundingPrivateKey(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPrivateKey = privateKeys.get(internalKeyPath(channelKeyPath, hardened(0)))

  private def revocationSecret(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPrivateKey = privateKeys.get(internalKeyPath(channelKeyPath, hardened(1)))

  private def paymentSecret(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPrivateKey = privateKeys.get(internalKeyPath(channelKeyPath, hardened(2)))

  private def delayedPaymentSecret(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPrivateKey = privateKeys.get(internalKeyPath(channelKeyPath, hardened(3)))

  private def htlcSecret(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPrivateKey = privateKeys.get(internalKeyPath(channelKeyPath, hardened(4)))

  private def shaSeed(channelKeyPath: DeterministicWallet.KeyPath): ByteVector32 = Crypto.sha256(privateKeys.get(internalKeyPath(channelKeyPath, hardened(5))).privateKey.value :+ 1.toByte)

  override def newFundingKeyPath(isInitiator: Boolean): KeyPath = {
    val last = DeterministicWallet.hardened(if (isInitiator) 1 else 0)

    def next(): Long = randomLong() & 0xFFFFFFFFL

    DeterministicWallet.KeyPath(Seq(next(), next(), next(), next(), next(), next(), next(), next(), last))
  }

  override def fundingPublicKey(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = publicKeys.get(internalKeyPath(channelKeyPath, hardened(0)))

  override def revocationPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = publicKeys.get(internalKeyPath(channelKeyPath, hardened(1)))

  override def paymentPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = publicKeys.get(internalKeyPath(channelKeyPath, hardened(2)))

  override def delayedPaymentPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = publicKeys.get(internalKeyPath(channelKeyPath, hardened(3)))

  override def htlcPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey = publicKeys.get(internalKeyPath(channelKeyPath, hardened(4)))

  override def commitmentSecret(channelKeyPath: DeterministicWallet.KeyPath, index: Long): PrivateKey = Generators.perCommitSecret(shaSeed(channelKeyPath), index)

  override def commitmentPoint(channelKeyPath: DeterministicWallet.KeyPath, index: Long): PublicKey = Generators.perCommitPoint(shaSeed(channelKeyPath), index)

  /**
   * @param tx               input transaction
   * @param publicKey        extended public key
   * @param txOwner          owner of the transaction (local/remote)
   * @param commitmentFormat format of the commitment tx
   * @return a signature generated with the private key that matches the input extended public key
   */
  override def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): ByteVector64 = {
    // NB: not all those transactions are actually commit txs (especially during closing), but this is good enough for monitoring purposes
    val tags = TagSet.Empty.withTag(Tags.TxOwner, txOwner.toString).withTag(Tags.TxType, Tags.TxTypes.CommitTx)
    Metrics.SignTxCount.withTags(tags).increment()
    KamonExt.time(Metrics.SignTxDuration.withTags(tags)) {
      val privateKey = privateKeys.get(publicKey.path)
      Transactions.sign(tx, privateKey.privateKey, txOwner, commitmentFormat)
    }
  }

  /**
   * This method is used to spend funds sent to htlc keys/delayed keys
   *
   * @param tx               input transaction
   * @param publicKey        extended public key
   * @param remotePoint      remote point
   * @param txOwner          owner of the transaction (local/remote)
   * @param commitmentFormat format of the commitment tx
   * @return a signature generated with a private key generated from the input key's matching private key and the remote point.
   */
  override def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remotePoint: PublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): ByteVector64 = {
    // NB: not all those transactions are actually htlc txs (especially during closing), but this is good enough for monitoring purposes
    val tags = TagSet.Empty.withTag(Tags.TxOwner, txOwner.toString).withTag(Tags.TxType, Tags.TxTypes.HtlcTx)
    Metrics.SignTxCount.withTags(tags).increment()
    KamonExt.time(Metrics.SignTxDuration.withTags(tags)) {
      val privateKey = privateKeys.get(publicKey.path)
      val currentKey = Generators.derivePrivKey(privateKey.privateKey, remotePoint)
      Transactions.sign(tx, currentKey, txOwner, commitmentFormat)
    }
  }

  /**
   * Ths method is used to spend revoked transactions, with the corresponding revocation key
   *
   * @param tx               input transaction
   * @param publicKey        extended public key
   * @param remoteSecret     remote secret
   * @param txOwner          owner of the transaction (local/remote)
   * @param commitmentFormat format of the commitment tx
   * @return a signature generated with a private key generated from the input key's matching private key and the remote secret.
   */
  override def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remoteSecret: PrivateKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): ByteVector64 = {
    val tags = TagSet.Empty.withTag(Tags.TxOwner, txOwner.toString).withTag(Tags.TxType, Tags.TxTypes.RevokedTx)
    Metrics.SignTxCount.withTags(tags).increment()
    KamonExt.time(Metrics.SignTxDuration.withTags(tags)) {
      val privateKey = privateKeys.get(publicKey.path)
      val currentKey = Generators.revocationPrivKey(privateKey.privateKey, remoteSecret)
      Transactions.sign(tx, currentKey, txOwner, commitmentFormat)
    }
  }

  override def signChannelAnnouncement(witness: ByteVector, fundingKeyPath: KeyPath): ByteVector64 =
    Announcements.signChannelAnnouncement(witness, privateKeys.get(fundingKeyPath).privateKey)
}