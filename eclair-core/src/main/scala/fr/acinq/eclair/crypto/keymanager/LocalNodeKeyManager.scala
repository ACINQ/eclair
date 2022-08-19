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

package fr.acinq.eclair.crypto.keymanager

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, Crypto, DeterministicWallet}
import fr.acinq.eclair.router.Announcements
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

object LocalNodeKeyManager {
  // WARNING: if you change this path, you will change your node id even if the seed remains the same!!!
  // Note that the node path and the above channel path are on different branches so even if the
  // node key is compromised there is no way to retrieve the wallet keys
  def keyBasePath(chainHash: ByteVector32): List[Long] = (chainHash: @unchecked) match {
    case Block.RegtestGenesisBlock.hash | Block.TestnetGenesisBlock.hash | Block.SignetGenesisBlock.hash => DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil
    case Block.LivenetGenesisBlock.hash => DeterministicWallet.hardened(47) :: DeterministicWallet.hardened(0) :: Nil
  }
}

/**
 * This class manages node secrets and private keys.
 * It exports the node public key
 *
 * @param seed seed from which the node key will be derived
 */
class LocalNodeKeyManager(seed: ByteVector, chainHash: ByteVector32) extends NodeKeyManager with Logging {
  private val master = DeterministicWallet.generate(seed)

  override val nodeKey: ExtendedPrivateKey = DeterministicWallet.derivePrivateKey(master, LocalNodeKeyManager.keyBasePath(chainHash))
  override val nodeId: PublicKey = nodeKey.publicKey

  override def signDigest(digest: ByteVector32, privateKey: PrivateKey = nodeKey.privateKey): (ByteVector64, Int) = {
    val signature = Crypto.sign(digest, privateKey)
    val (pub1, _) = Crypto.recoverPublicKey(signature, digest)
    val recoveryId = if (nodeId == pub1) 0 else 1
    (signature, recoveryId)
  }

  override def signChannelAnnouncement(witness: ByteVector): ByteVector64 =
    Announcements.signChannelAnnouncement(witness, nodeKey.privateKey)
}