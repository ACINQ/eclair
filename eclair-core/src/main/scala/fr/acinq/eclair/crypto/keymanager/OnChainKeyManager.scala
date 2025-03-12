/*
 * Copyright 2023 ACINQ SAS
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

import fr.acinq.bitcoin.psbt.Psbt
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.{AddressType, Descriptors}

import scala.util.Try

trait OnChainKeyManager {
  def walletName: String

  /**
   * @param account account number (0 is used by most wallets)
   * @return the on-chain pubkey for this account, which can then be imported into a BIP39-compatible wallet such as Electrum
   */
  def masterPubKey(account: Long, addressType: AddressType): String

  /**
   * @param keyPath BIP32 path
   * @return the (public key, address) pair for this BIP32 path starting from the master key
   */
  def derivePublicKey(keyPath: KeyPath): (PublicKey, String)

  def derivePublicKey(keyPath: String): (PublicKey, String) = derivePublicKey(KeyPath(keyPath))

  /**
   * @param account account number
   * @return a pair of (main, change) wallet descriptors that can be imported into an on-chain wallet
   */
  def descriptors(account: Long): Descriptors

  /**
   * Sign the inputs provided in [[ourInputs]] and verifies that [[ourOutputs]] belong to our bitcoin wallet.
   *
   * @param psbt       input psbt
   * @param ourInputs  index of inputs that belong to our on-chain wallet and need to be signed
   * @param ourOutputs index of outputs that belong to our on-chain wallet
   * @return a signed psbt, where all our inputs are signed
   */
  def sign(psbt: Psbt, ourInputs: Seq[Int], ourOutputs: Seq[Int]): Try[Psbt]
}
