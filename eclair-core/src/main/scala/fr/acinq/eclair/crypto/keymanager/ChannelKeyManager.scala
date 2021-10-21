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

import fr.acinq.bitcoin.{ByteVector64, Crypto, DeterministicWallet, KeyPath, PrivateKey, Protocol, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPublicKey
import fr.acinq.bitcoin.crypto.Pack
import fr.acinq.eclair.channel.{ChannelConfig, LocalParams}
import fr.acinq.eclair.transactions.Transactions.{CommitmentFormat, TransactionWithInputInfo, TxOwner}
import scodec.bits.ByteVector
import fr.acinq.eclair.KotlinUtils._

import scala.jdk.CollectionConverters._
import java.io.ByteArrayInputStream
import java.nio.ByteOrder

trait ChannelKeyManager {
  def fundingPublicKey(keyPath: KeyPath): ExtendedPublicKey

  def revocationPoint(channelKeyPath: KeyPath): ExtendedPublicKey

  def paymentPoint(channelKeyPath: KeyPath): ExtendedPublicKey

  def delayedPaymentPoint(channelKeyPath: KeyPath): ExtendedPublicKey

  def htlcPoint(channelKeyPath: KeyPath): ExtendedPublicKey

  def commitmentSecret(channelKeyPath: KeyPath, index: Long): PrivateKey

  def commitmentPoint(channelKeyPath: KeyPath, index: Long): PublicKey

  def keyPath(localParams: LocalParams, channelConfig: ChannelConfig): KeyPath = {
    if (channelConfig.hasOption(ChannelConfig.FundingPubKeyBasedChannelKeyPath)) {
      // deterministic mode: use the funding pubkey to compute the channel key path
      ChannelKeyManager.keyPath(fundingPublicKey(localParams.fundingKeyPath))
    } else {
      // legacy mode:  we reuse the funding key path as our channel key path
      localParams.fundingKeyPath
    }
  }

  /**
   * @param isFunder true if we're funding this channel
   * @return a partial key path for a new funding public key. This key path will be extended:
   *         - with a specific "chain" prefix
   *         - with a specific "funding pubkey" suffix
   */
  def newFundingKeyPath(isFunder: Boolean): KeyPath

  /**
   * @param tx               input transaction
   * @param publicKey        extended public key
   * @param txOwner          owner of the transaction (local/remote)
   * @param commitmentFormat format of the commitment tx
   * @return a signature generated with the private key that matches the input extended public key
   */
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): ByteVector64

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
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remotePoint: PublicKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): ByteVector64

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
  def sign(tx: TransactionWithInputInfo, publicKey: ExtendedPublicKey, remoteSecret: PrivateKey, txOwner: TxOwner, commitmentFormat: CommitmentFormat): ByteVector64

  /**
   * Sign a channel announcement message
   *
   * @param witness channel announcement message
   * @return the signature of the channel announcement with the channel's funding private key
   */
  def signChannelAnnouncement(witness: ByteVector, fundingKeyPath: KeyPath): ByteVector64
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
  def keyPath(fundingPubKey: PublicKey): KeyPath = {
    val buffer = Crypto.sha256(fundingPubKey.value)
    var offset = -4

    def next() = {
      offset = offset + 4
      (Pack.int32BE(buffer, offset) & 0xffffffffL).asInstanceOf[java.lang.Long]
    }

    new KeyPath(List(next(), next(), next(), next(), next(), next(), next(), next()).asJava)
  }

  def keyPath(fundingPubKey: DeterministicWallet.ExtendedPublicKey): KeyPath = keyPath(fundingPubKey.getPublicKey)
}