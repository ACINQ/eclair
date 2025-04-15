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

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Crypto, DeterministicWallet, Protocol}
import fr.acinq.eclair.channel.{ChannelConfig, LocalParams}

import java.io.ByteArrayInputStream
import java.nio.ByteOrder

trait ChannelKeyManager {

  /**
   * @param isChannelOpener true if we initiated the channel open
   * @return a *partial* key path for a new funding public key. This key path will be extended:
   *         - with a specific "chain" prefix
   *         - with a specific "funding pubkey" suffix
   *         - using the [[channelKeyPath]] function
   */
  def newFundingKeyPath(isChannelOpener: Boolean): DeterministicWallet.KeyPath

  /**
   * @return the base key path used for the provided channel: we will derive per-commitment keys based on this path.
   */
  def channelKeyPath(localParams: LocalParams, channelConfig: ChannelConfig): DeterministicWallet.KeyPath = {
    if (channelConfig.hasOption(ChannelConfig.FundingPubKeyBasedChannelKeyPath)) {
      // Deterministic mode: use the funding public key itself to compute the channel key path.
      val fundingPublicKey = fundingKey(localParams.fundingKeyPath, fundingTxIndex = 0).publicKey
      ChannelKeyManager.keyPathFromPublicKey(fundingPublicKey)
    } else {
      // Legacy mode: we simply reuse the funding key path as our channel key path.
      localParams.fundingKeyPath
    }
  }

  /** Get the private key for the channel funding transaction at the funding index requested. */
  def fundingKey(fundingKeyPath: DeterministicWallet.KeyPath, fundingTxIndex: Long): PrivateKey

  /** Get the base revocation key that will then be tweaked everytime the commitment changes. */
  def revocationBaseKey(channelKeyPath: DeterministicWallet.KeyPath): PrivateKey

  /** Get the base payment key that will then be tweaked everytime the commitment changes. */
  def paymentBaseKey(channelKeyPath: DeterministicWallet.KeyPath): PrivateKey

  /** Get the base delayed payment key that will then be tweaked everytime the commitment changes. */
  def delayedPaymentBaseKey(channelKeyPath: DeterministicWallet.KeyPath): PrivateKey

  /** Get the base HTLC key that will then be tweaked everytime the commitment changes. */
  def htlcBaseKey(channelKeyPath: DeterministicWallet.KeyPath): PrivateKey

  /** Get the commitment secret for the given commitment number. */
  def commitmentSecret(channelKeyPath: DeterministicWallet.KeyPath, commitmentNumber: Long): PrivateKey

  /** Get the commitment point for the given commitment number (public key of [[commitmentSecret]]). */
  def commitmentPoint(channelKeyPath: DeterministicWallet.KeyPath, commitmentNumber: Long): PublicKey

}

object ChannelKeyManager {

  /**
   * Create a BIP32 path from a public key. This path will be used to derive channel keys.
   * Having channel keys derived from the funding public keys makes it very easy to retrieve your funds when you've lost your data:
   * - connect to your peer and use DLP to get them to publish their remote commit tx
   * - retrieve the commit tx from the bitcoin network, extract your funding pubkey from its witness data
   * - recompute your channel keys and spend your output
   *
   * @param fundingPubKey funding public key
   * @return a BIP32 path
   */
  def keyPathFromPublicKey(fundingPubKey: PublicKey): DeterministicWallet.KeyPath = {
    // We simply hash the public key, and then read the result in 4-bytes chunks to create a BIP32 path.
    val buffer = Crypto.sha256(fundingPubKey.value)
    val bis = new ByteArrayInputStream(buffer.toArray)

    def next(): Long = Protocol.uint32(bis, ByteOrder.BIG_ENDIAN)

    DeterministicWallet.KeyPath(Seq(next(), next(), next(), next(), next(), next(), next(), next()))
  }

}