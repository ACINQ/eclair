/*
 * Copyright 2025 ACINQ SAS
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

package fr.acinq.eclair.transactions

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}

/**
 * Created by t-bast on 10/04/2025.
 */

/**
 * Each commitment transaction uses a set of many cryptographic keys for the various spending paths of all its outputs.
 * Some of those keys are static for the channel lifetime, but others change every time we update the commitment
 * transaction.
 *
 * This class can be used indifferently for the local or remote commitment transaction. Beware that when it applies to
 * the remote transaction, the "local" prefix is for their keys and the "remote" prefix is for our keys.
 *
 * See Bolt 3 for more details.
 *
 * @param localDelayedPaymentPublicKey key used for delayed outputs of the transaction owner (main balance and outputs of HTLC transactions).
 * @param remotePaymentPublicKey       key used for the main balance of the transaction non-owner (not delayed).
 * @param localHtlcPublicKey           key used to sign HTLC transactions by the transaction owner.
 * @param remoteHtlcPublicKey          key used to sign HTLC transactions by the transaction non-owner.
 * @param revocationPublicKey          key used to revoke this commitment after signing the next one (by revealing the private key).
 */
case class CommitmentPublicKeys(localDelayedPaymentPublicKey: PublicKey,
                                remotePaymentPublicKey: PublicKey,
                                localHtlcPublicKey: PublicKey,
                                remoteHtlcPublicKey: PublicKey,
                                revocationPublicKey: PublicKey)

/**
 * Keys used for our local commitment.
 */
case class LocalCommitmentKeys(ourDelayedPaymentKey: PrivateKey,
                               theirPaymentPublicKey: PublicKey,
                               ourHtlcKey: PrivateKey,
                               theirHtlcPublicKey: PublicKey,
                               revocationPublicKey: PublicKey) {
  val publicKeys: CommitmentPublicKeys = CommitmentPublicKeys(
    localDelayedPaymentPublicKey = ourDelayedPaymentKey.publicKey,
    remotePaymentPublicKey = theirPaymentPublicKey,
    localHtlcPublicKey = ourHtlcKey.publicKey,
    remoteHtlcPublicKey = theirHtlcPublicKey,
    revocationPublicKey = revocationPublicKey
  )
}

/**
 * Keys used for the remote commitment.
 *
 * There is a subtlety for [[ourPaymentKey]]: when using option_static_remotekey, our output will directly send funds
 * to a p2wpkh address created by our bitcoin node. We thus don't need the private key, as the output can immediately
 * be spent by our bitcoin node (no need for 2nd-stage transactions to send it back to our wallet).
 */
case class RemoteCommitmentKeys(ourPaymentKey: Either[PublicKey, PrivateKey],
                                theirDelayedPaymentPublicKey: PublicKey,
                                ourHtlcKey: PrivateKey,
                                theirHtlcPublicKey: PublicKey,
                                revocationPublicKey: PublicKey) {
  val ourPaymentPublicKey: PublicKey = ourPaymentKey match {
    case Left(publicKey) => publicKey
    case Right(privateKey) => privateKey.publicKey
  }
  // Since this is the remote commitment, local is them and remote is us.
  val publicKeys: CommitmentPublicKeys = CommitmentPublicKeys(
    localDelayedPaymentPublicKey = theirDelayedPaymentPublicKey,
    remotePaymentPublicKey = ourPaymentKey match {
      case Left(publicKey) => publicKey
      case Right(privateKey) => privateKey.publicKey
    },
    localHtlcPublicKey = theirHtlcPublicKey,
    remoteHtlcPublicKey = ourHtlcKey.publicKey,
    revocationPublicKey = revocationPublicKey
  )
}