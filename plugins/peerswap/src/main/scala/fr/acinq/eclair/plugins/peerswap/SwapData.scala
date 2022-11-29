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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.plugins.peerswap.SwapRole.SwapRole
import fr.acinq.eclair.plugins.peerswap.wire.protocol.{OpeningTxBroadcasted, SwapAgreement, SwapRequest}

object SwapRole extends Enumeration {
  type SwapRole = Value
  val Maker: SwapRole.Value = Value(1, "Maker")
  val Taker: SwapRole.Value = Value(2, "Taker")
}

case class SwapData(request: SwapRequest, agreement: SwapAgreement, invoice: Bolt11Invoice, openingTxBroadcasted: OpeningTxBroadcasted, swapRole: SwapRole, isInitiator: Boolean, remoteNodeId: PublicKey, result: String = "") {
  val swapId: String = request.swapId
  val scid: String = request.scid
}

object SwapData {
}
