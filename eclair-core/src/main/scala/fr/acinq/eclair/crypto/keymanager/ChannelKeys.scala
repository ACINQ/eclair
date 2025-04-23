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
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.{ExtendedPrivateKey, hardened}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto}
import fr.acinq.eclair.crypto.ShaChain

/**
 * Keys used for a specific channel instance:
 *  - funding keys (channel funding, splicing and closing)
 *  - commitment "base" keys, which are static for the channel lifetime
 *  - per-commitment keys, which change everytime we create a new commitment transaction:
 *    - derived from the commitment "base" keys
 *    - and tweaked with a per-commitment point
 *
 * WARNING: these private keys must never be stored on disk, in a database, or logged.
 */
case class ChannelKeys(private val fundingMasterKey: ExtendedPrivateKey, private val commitmentMasterKey: ExtendedPrivateKey) {

  private val fundingKeys: LoadingCache[Long, PrivateKey] = CacheBuilder.newBuilder()
    .maximumSize(2) // we cache the current funding key and the funding key of a pending splice
    .build[Long, PrivateKey](new CacheLoader[Long, PrivateKey] {
      override def load(fundingTxIndex: Long): PrivateKey = fundingMasterKey.derivePrivateKey(hardened(fundingTxIndex)).privateKey
    })

  def fundingKey(fundingTxIndex: Long): PrivateKey = fundingKeys.get(fundingTxIndex)

  // Note that we use lazy values here to avoid deriving keys for all of our channels immediately after a restart.
  lazy val revocationBaseSecret: PrivateKey = commitmentMasterKey.derivePrivateKey(hardened(1)).privateKey
  lazy val revocationBasePoint: PublicKey = revocationBaseSecret.publicKey
  lazy val paymentBaseSecret: PrivateKey = commitmentMasterKey.derivePrivateKey(hardened(2)).privateKey
  lazy val paymentBasePoint: PublicKey = paymentBaseSecret.publicKey
  lazy val delayedPaymentBaseSecret: PrivateKey = commitmentMasterKey.derivePrivateKey(hardened(3)).privateKey
  lazy val delayedPaymentBasePoint: PublicKey = delayedPaymentBaseSecret.publicKey
  lazy val htlcBaseSecret: PrivateKey = commitmentMasterKey.derivePrivateKey(hardened(4)).privateKey
  lazy val htlcBasePoint: PublicKey = htlcBaseSecret.publicKey

  // @formatter:off
  // Per-commitment keys are derived using a sha-chain, which provides efficient storage and retrieval mechanisms.
  private lazy val shaSeed: ByteVector32 = Crypto.sha256(commitmentMasterKey.derivePrivateKey(hardened(5)).privateKey.value :+ 1.toByte)
  def commitmentSecret(localCommitmentNumber: Long): PrivateKey = PrivateKey(ShaChain.shaChainFromSeed(shaSeed, 0xFFFFFFFFFFFFL - localCommitmentNumber))
  def commitmentPoint(localCommitmentNumber: Long): PublicKey = commitmentSecret(localCommitmentNumber).publicKey
  // @formatter:on

  /**
   * Derive our local payment key for our main output in the remote commitment transaction.
   * Warning: when using option_staticremotekey or anchor_outputs, we must always use the base key instead of a per-commitment key.
   */
  def paymentKey(commitmentPoint: PublicKey): PrivateKey = ChannelKeys.derivePerCommitmentKey(paymentBaseSecret, commitmentPoint)

  /** Derive our local delayed payment key for our main output in the local commitment transaction. */
  def delayedPaymentKey(commitmentPoint: PublicKey): PrivateKey = ChannelKeys.derivePerCommitmentKey(delayedPaymentBaseSecret, commitmentPoint)

  /** Derive our HTLC key for our HTLC transactions, in either the local or remote commitment transaction. */
  def htlcKey(commitmentPoint: PublicKey): PrivateKey = ChannelKeys.derivePerCommitmentKey(htlcBaseSecret, commitmentPoint)

  /** With the remote per-commitment secret, we can derive the private key to spend revoked commitments. */
  def revocationKey(remoteCommitmentSecret: PrivateKey): PrivateKey = ChannelKeys.revocationKey(revocationBaseSecret, remoteCommitmentSecret)

}

object ChannelKeys {

  /** Derive the local per-commitment key for the base key provided. */
  def derivePerCommitmentKey(baseSecret: PrivateKey, commitmentPoint: PublicKey): PrivateKey = {
    // secretkey = basepoint-secret + SHA256(per-commitment-point || basepoint)
    baseSecret + PrivateKey(Crypto.sha256(commitmentPoint.value ++ baseSecret.publicKey.value))
  }

  /** Derive the remote per-commitment key for the base point provided. */
  def remotePerCommitmentPublicKey(basePoint: PublicKey, commitmentPoint: PublicKey): PublicKey = {
    // pubkey = basepoint + SHA256(per-commitment-point || basepoint)*G
    basePoint + PrivateKey(Crypto.sha256(commitmentPoint.value ++ basePoint.value)).publicKey
  }

  /** Derive the revocation private key from our local base revocation key and the remote per-commitment secret. */
  def revocationKey(baseKey: PrivateKey, remoteCommitmentSecret: PrivateKey): PrivateKey = {
    val a = PrivateKey(Crypto.sha256(baseKey.publicKey.value ++ remoteCommitmentSecret.publicKey.value))
    val b = PrivateKey(Crypto.sha256(remoteCommitmentSecret.publicKey.value ++ baseKey.publicKey.value))
    (baseKey * a) + (remoteCommitmentSecret * b)
  }

  /**
   * We create two distinct revocation public keys:
   *   - one for the local commitment using the remote revocation base point and our local per-commitment point
   *   - one for the remote commitment using our revocation base point and the remote per-commitment point
   *
   * The owner of the commitment transaction is providing the per-commitment point, which ensures that they can revoke
   * their previous commitment transactions by revealing the corresponding secret.
   */
  def revocationPublicKey(revocationBasePoint: PublicKey, commitmentPoint: PublicKey): PublicKey = {
    val a = PrivateKey(Crypto.sha256(revocationBasePoint.value ++ commitmentPoint.value))
    val b = PrivateKey(Crypto.sha256(commitmentPoint.value ++ revocationBasePoint.value))
    (revocationBasePoint * a) + (commitmentPoint * b)
  }

}
