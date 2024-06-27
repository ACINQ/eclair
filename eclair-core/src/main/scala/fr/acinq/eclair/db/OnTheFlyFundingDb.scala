/*
 * Copyright 2024 ACINQ SAS
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

package fr.acinq.eclair.db

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.payment.relay.OnTheFlyFunding

/**
 * Created by t-bast on 25/06/2024.
 */

trait OnTheFlyFundingDb {

  /** When we receive the preimage for an on-the-fly payment, we save it to protect against replays. */
  def addPreimage(preimage: ByteVector32): Unit

  /** Check if we received the preimage for the given payment hash. */
  def getPreimage(paymentHash: ByteVector32): Option[ByteVector32]

  /** We save funded proposals to allow completing the payment after a disconnection or a restart. */
  def addPending(remoteNodeId: PublicKey, pending: OnTheFlyFunding.Pending): Unit

  /** Once complete (succeeded or failed), we forget the pending on-the-fly funding proposal. */
  def removePending(remoteNodeId: PublicKey, paymentHash: ByteVector32): Unit

  /** List pending proposals we funded for the given remote node. */
  def listPending(remoteNodeId: PublicKey): Map[ByteVector32, OnTheFlyFunding.Pending]

  /** List the payment_hashes of pending proposals we funded for all remote nodes. */
  def listPendingPayments(): Map[PublicKey, Set[ByteVector32]]

}
