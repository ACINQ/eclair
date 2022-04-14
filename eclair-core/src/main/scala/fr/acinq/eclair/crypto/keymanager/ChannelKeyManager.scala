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
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.ExtendedPublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector64, Crypto, DeterministicWallet, Protocol}
import fr.acinq.eclair.channel.{ChannelConfig, LocalParams}
import fr.acinq.eclair.transactions.Transactions.{CommitmentFormat, TransactionWithInputInfo, TxOwner}
import scodec.bits.ByteVector

import java.io.ByteArrayInputStream
import java.nio.ByteOrder

trait ChannelKeyManager {
  def fundingPublicKey(keyPath: DeterministicWallet.KeyPath): ExtendedPublicKey

  def revocationPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey

  def paymentPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey

  def delayedPaymentPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey

  def htlcPoint(channelKeyPath: DeterministicWallet.KeyPath): ExtendedPublicKey

  def commitmentSecret(channelKeyPath: DeterministicWallet.KeyPath, index: Long): Crypto.PrivateKey

  def commitmentPoint(channelKeyPath: DeterministicWallet.KeyPath, index: Long): Crypto.PublicKey

  def keyPath(localParams: LocalParams, channelConfig: ChannelConfig): DeterministicWallet.KeyPath = {
    if (channelConfig.hasOption(ChannelConfig.FundingPubKeyBasedChannelKeyPath)) {
      // deterministic mode: use the funding pubkey to compute the channel key path
      ChannelKeyManager.keyPath(fundingPublicKey(localParams.fundingKeyPath))
    } else {
      // legacy mode:  we reuse the funding key path as our channel key path
      localParams.fundingKeyPath
    }
  }

  /**
   * @param isInitiator true if we initiated the channel open
   * @return a partial key path for a new funding public key. This key path will be extended:
   *         - with a specific "chain" prefix
   *         - with a specific "funding pubkey" suffix
   */
  def newFundingKeyPath(isInitiator: Boolean): DeterministicWallet.KeyPath

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
  def signChannelAnnouncement(witness: ByteVector, fundingKeyPath: DeterministicWallet.KeyPath): ByteVector64
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
  def keyPath(fundingPubKey: PublicKey): DeterministicWallet.KeyPath = {
    val buffer = Crypto.sha256(fundingPubKey.value)
    val bis = new ByteArrayInputStream(buffer.toArray)

    def next(): Long = Protocol.uint32(bis, ByteOrder.BIG_ENDIAN)

    DeterministicWallet.KeyPath(Seq(next(), next(), next(), next(), next(), next(), next(), next()))
  }

  def keyPath(fundingPubKey: DeterministicWallet.ExtendedPublicKey): DeterministicWallet.KeyPath = keyPath(fundingPubKey.publicKey)
}