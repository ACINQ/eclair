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
import fr.acinq.eclair.channel.ChannelConfig

import java.io.ByteArrayInputStream
import java.nio.ByteOrder

trait ChannelKeyManager {

  /**
   * Create a BIP32 funding key path a new channel.
   * This function must return a unique path every time it is called.
   * This guarantees that unrelated channels use different BIP32 key paths and thus unrelated keys.
   *
   * @param isChannelOpener true if we initiated the channel open: this must be used to derive different key paths.
   */
  def newFundingKeyPath(isChannelOpener: Boolean): DeterministicWallet.KeyPath

  /**
   * Create channel keys based on a funding key path obtained using [[newFundingKeyPath]].
   * This function is deterministic: it must always return the same result when called with the same arguments.
   * This allows re-creating the channel keys based on the seed and its main parameters.
   */
  def channelKeys(channelConfig: ChannelConfig, fundingKeyPath: DeterministicWallet.KeyPath): ChannelKeys

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