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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, TxId}
import fr.acinq.eclair.channel.{ChannelLiquidityPurchased, LiquidityPurchase}
import fr.acinq.eclair.payment.relay.OnTheFlyFunding

/**
 * Created by t-bast on 13/09/2024. 
 */

trait LiquidityDb {

  /** We save liquidity purchases as soon as the corresponding transaction is signed. */
  def addPurchase(liquidityPurchase: ChannelLiquidityPurchased): Unit

  /** When a transaction confirms, we mark the corresponding liquidity purchase (if any) as confirmed. */
  def setConfirmed(remoteNodeId: PublicKey, txId: TxId): Unit

  /** List all liquidity purchases with the given remote node. */
  def listPurchases(remoteNodeId: PublicKey): Seq[LiquidityPurchase]

  /** We save on-the-fly funded proposals to allow completing the payment after a disconnection or a restart. */
  def addPendingOnTheFlyFunding(remoteNodeId: PublicKey, pending: OnTheFlyFunding.Pending): Unit

  /** Once complete (succeeded or failed), we forget the pending on-the-fly funding proposal. */
  def removePendingOnTheFlyFunding(remoteNodeId: PublicKey, paymentHash: ByteVector32): Unit

  /** List pending on-the-fly funding proposals we funded for the given remote node. */
  def listPendingOnTheFlyFunding(remoteNodeId: PublicKey): Map[ByteVector32, OnTheFlyFunding.Pending]

  /** List all pending on-the-fly funding proposals we funded. */
  def listPendingOnTheFlyFunding(): Map[PublicKey, Map[ByteVector32, OnTheFlyFunding.Pending]]

  /** List the payment_hashes of pending on-the-fly funding proposals we funded for all remote nodes. */
  def listPendingOnTheFlyPayments(): Map[PublicKey, Set[ByteVector32]]

  /** When we receive the preimage for an on-the-fly payment, we save it to protect against replays. */
  def addOnTheFlyFundingPreimage(preimage: ByteVector32): Unit

  /** Check if we received the preimage for the given payment hash of an on-the-fly payment. */
  def getOnTheFlyFundingPreimage(paymentHash: ByteVector32): Option[ByteVector32]

}
