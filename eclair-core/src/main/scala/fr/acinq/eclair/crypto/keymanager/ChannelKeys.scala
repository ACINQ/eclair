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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.{ExtendedPrivateKey, hardened}
import fr.acinq.eclair.crypto.Generators

/**
 * Keys used for a specific channel instance:
 *  - funding keys (channel funding, splicing and closing)
 *  - commitment "base" keys, which are static for the channel lifetime
 *  - per-commitment keys, which change everytime we create a new commitment transaction:
 *    - derived from the commitment "base" keys
 *    - and tweaked with a per-commitment point
 */
case class ChannelKeys(private val fundingMasterKey: ExtendedPrivateKey, private val commitmentMasterKey: ExtendedPrivateKey) {

  private val fundingKeys: LoadingCache[Long, PrivateKey] = CacheBuilder.newBuilder()
    .maximumSize(2) // we cache the current funding key and the funding key of a pending splice
    .build[Long, PrivateKey](new CacheLoader[Long, PrivateKey] {
      override def load(fundingTxIndex: Long): PrivateKey = fundingMasterKey.derivePrivateKey(hardened(fundingTxIndex)).privateKey
    })

  def fundingKey(fundingTxIndex: Long): PrivateKey = fundingKeys.get(fundingTxIndex)

  // Note that we use lazy values here to avoid deriving keys for all of our channels immediately after a restart.
  lazy val revocationBaseKey: PrivateKey = commitmentMasterKey.derivePrivateKey(hardened(1)).privateKey
  lazy val paymentBaseKey: PrivateKey = commitmentMasterKey.derivePrivateKey(hardened(2)).privateKey
  lazy val delayedPaymentBaseKey: PrivateKey = commitmentMasterKey.derivePrivateKey(hardened(3)).privateKey
  lazy val htlcBaseKey: PrivateKey = commitmentMasterKey.derivePrivateKey(hardened(4)).privateKey

  // Per-commitment keys are derived using a sha-chain, which provides efficient storage and retrieval mechanisms.
  private lazy val shaSeed: ByteVector32 = Crypto.sha256(commitmentMasterKey.derivePrivateKey(hardened(5)).privateKey.value :+ 1.toByte)

  def commitmentSecret(localCommitmentNumber: Long): PrivateKey = Generators.perCommitSecret(shaSeed, localCommitmentNumber)

  def commitmentPoint(localCommitmentNumber: Long): PublicKey = Generators.perCommitPoint(shaSeed, localCommitmentNumber)

}
