/*
 * Copyright 2020 ACINQ SAS
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

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, DeterministicWallet}
import scodec.bits.ByteVector

trait NodeKeyManager {
  def nodeKey: DeterministicWallet.ExtendedPrivateKey

  def nodeId: PublicKey

  /**
   * Sign a channel announcement message
   *
   * @param witness channel announcement message
   * @return the signature of the channel announcement with our node's private key
   */
  def signChannelAnnouncement(witness: ByteVector): ByteVector64

  /**
   * Sign a digest, primarily used to prove ownership of the current node
   *
   * When recovering a public key from an ECDSA signature for secp256k1, there are 4 possible matching curve points
   * that can be found. The recoveryId identifies which of these points is the correct.
   *
   * @param digest     SHA256 digest
   * @param privateKey private key to sign with, default the one from the current node
   * @return a (signature, recoveryId) pair. signature is a signature of the digest parameter generated with the
   *         private key given in parameter. recoveryId is the corresponding recoveryId of the signature
   */
  def signDigest(digest: ByteVector32, privateKey: PrivateKey = nodeKey.privateKey): (ByteVector64, Int)
}
