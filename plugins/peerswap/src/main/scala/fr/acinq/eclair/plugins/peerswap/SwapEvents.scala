/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.plugins.peerswap

import fr.acinq.bitcoin.scalacompat.Transaction
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchTxConfirmedTriggered

object SwapEvents {
  sealed trait SwapEvent {
    def swapId: String
  }

  case class Canceled(swapId: String, reason: String) extends SwapEvent
  case class TransactionPublished(swapId: String, tx: Transaction, desc: String) extends SwapEvent
  case class ClaimByInvoiceConfirmed(swapId: String, confirmation: WatchTxConfirmedTriggered) extends SwapEvent {
    override def toString: String = s"Claimed by paid invoice: $confirmation"
  }
  case class ClaimByCoopOffered(swapId: String, reason: String) extends SwapEvent {
    override def toString: String = s"Coop close offered to peer: $reason"
  }
  case class ClaimByInvoicePaid(swapId: String) extends SwapEvent {
    override def toString: String = s"Invoice payment received"
  }
  case class ClaimByCoopConfirmed(swapId: String, confirmation: WatchTxConfirmedTriggered) extends SwapEvent {
    override def toString: String = s"Claimed by coop: $confirmation"
  }
  case class ClaimByCsvConfirmed(swapId: String, confirmation: WatchTxConfirmedTriggered) extends SwapEvent {
    override def toString: String = s"Claimed by csv: $confirmation"
  }
  case class CouldNotRestore(swapId: String, checkpoint: SwapData) extends SwapEvent {
    override def toString: String = s"Could not restore from checkpoint: $checkpoint"
  }

}
