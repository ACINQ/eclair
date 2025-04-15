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
import fr.acinq.bitcoin.scalacompat.{Block, BlockHash, ByteVector32, Crypto, DeterministicWallet}
import fr.acinq.eclair.crypto.Generators
import fr.acinq.eclair.randomLong
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

object LocalChannelKeyManager {
  def keyBasePath(chainHash: BlockHash): List[Long] = (chainHash: @unchecked) match {
    case Block.RegtestGenesisBlock.hash | Block.Testnet4GenesisBlock.hash | Block.Testnet3GenesisBlock.hash | Block.SignetGenesisBlock.hash => DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(1) :: Nil
    case Block.LivenetGenesisBlock.hash => DeterministicWallet.hardened(47) :: DeterministicWallet.hardened(1) :: Nil
  }
}

/**
 * An implementation of [[ChannelKeyManager]] that supports deterministic derivation of keys, based on the initial
 * funding pubkey.
 *
 * Specifically, there are two paths both of length 8 (256 bits):
 *   - `fundingKeyPath`: chosen at random using `newFundingKeyPath()`
 *   - `channelKeyPath`: sha(fundingPubkey(0)) using `ChannelKeyManager.keyPath()`
 *
 * The resulting paths looks like so on mainnet:
 * {{{
 *  funding txs:
 *     47' / 1' / <fundingKeyPath> / <1' or 0'> / <index>'
 *
 *  other channel basepoint keys (payment, revocation, htlc, etc.):
 *     47' / 1' / <channelKeyPath> / <1'-5'>
 * }}}
 *
 * Note that the channel basepoint keys are usually not used directly: they are instead tweaked every time the channel
 * is updated using the [[ChannelKeyManager.commitmentPoint]]. This is why they are called basepoint keys (from which
 * key derivation starts).
 *
 * @param seed seed from which the channel keys will be derived
 */
class LocalChannelKeyManager(seed: ByteVector, chainHash: BlockHash) extends ChannelKeyManager with Logging {
  private val master = DeterministicWallet.generate(seed)

  private val privateKeys: LoadingCache[KeyPath, ExtendedPrivateKey] = CacheBuilder.newBuilder()
    .maximumSize(6 * 200) // 6 keys per channel * 200 channels
    .build[KeyPath, ExtendedPrivateKey](new CacheLoader[KeyPath, ExtendedPrivateKey] {
      override def load(keyPath: KeyPath): ExtendedPrivateKey = derivePrivateKey(master, keyPath)
    })

  private def internalKeyPath(keyPath: DeterministicWallet.KeyPath, index: Long): KeyPath = KeyPath((LocalChannelKeyManager.keyBasePath(chainHash) ++ keyPath.path) :+ index)

  override def newFundingKeyPath(isInitiator: Boolean): KeyPath = {
    val last = DeterministicWallet.hardened(if (isInitiator) 1 else 0)

    def next(): Long = randomLong() & 0xFFFFFFFFL

    DeterministicWallet.KeyPath(Seq(next(), next(), next(), next(), next(), next(), next(), next(), last))
  }

  override def fundingKey(fundingKeyPath: DeterministicWallet.KeyPath, fundingTxIndex: Long): PrivateKey = {
    val keyPath = internalKeyPath(fundingKeyPath, hardened(fundingTxIndex))
    privateKeys.get(keyPath).privateKey
  }

  override def revocationBaseKey(channelKeyPath: DeterministicWallet.KeyPath): PrivateKey = privateKeys.get(internalKeyPath(channelKeyPath, hardened(1))).privateKey

  override def paymentBaseKey(channelKeyPath: DeterministicWallet.KeyPath): PrivateKey = privateKeys.get(internalKeyPath(channelKeyPath, hardened(2))).privateKey

  override def delayedPaymentBaseKey(channelKeyPath: DeterministicWallet.KeyPath): PrivateKey = privateKeys.get(internalKeyPath(channelKeyPath, hardened(3))).privateKey

  override def htlcBaseKey(channelKeyPath: DeterministicWallet.KeyPath): PrivateKey = privateKeys.get(internalKeyPath(channelKeyPath, hardened(4))).privateKey

  private def shaSeed(channelKeyPath: DeterministicWallet.KeyPath): ByteVector32 = Crypto.sha256(privateKeys.get(internalKeyPath(channelKeyPath, hardened(5))).privateKey.value :+ 1.toByte)

  override def commitmentSecret(channelKeyPath: DeterministicWallet.KeyPath, index: Long): PrivateKey = Generators.perCommitSecret(shaSeed(channelKeyPath), index)

  override def commitmentPoint(channelKeyPath: DeterministicWallet.KeyPath, index: Long): PublicKey = Generators.perCommitPoint(shaSeed(channelKeyPath), index)

}