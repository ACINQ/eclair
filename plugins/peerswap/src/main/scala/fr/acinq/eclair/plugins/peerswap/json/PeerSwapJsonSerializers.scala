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

package fr.acinq.eclair.plugins.peerswap.json

import fr.acinq.eclair.json.MinimalSerializer
import fr.acinq.eclair.plugins.peerswap.wire.protocol._
import org.json4s.JsonAST._
import org.json4s.jackson.Serialization
import org.json4s.{Formats, JField, JObject, JString, jackson}

object SwapInRequestMessageSerializer extends MinimalSerializer({
  case x: SwapInRequest => JObject(List(
    JField("protocol_version", JInt(x.protocolVersion)),
    JField("swap_id", JString(x.swapId)),
    JField("asset", JString(x.asset)),
    JField("network", JString(x.network)),
    JField("scid", JString(x.scid)),
    JField("amount", JInt(x.amount)),
    JField("pubkey", JString(x.pubkey))
  ))
})

object SwapOutRequestMessageSerializer extends MinimalSerializer({
  case x: SwapOutRequest => JObject(List(
    JField("protocol_version", JInt(x.protocolVersion)),
    JField("swap_id", JString(x.swapId)),
    JField("asset", JString(x.asset)),
    JField("network", JString(x.network)),
    JField("scid", JString(x.scid)),
    JField("amount", JInt(x.amount)),
    JField("pubkey", JString(x.pubkey))
  ))
})

object SwapInAgreementMessageSerializer extends MinimalSerializer({
  case x: SwapInAgreement => JObject(List(
    JField("protocol_version", JInt(x.protocolVersion)),
    JField("swap_id", JString(x.swapId)),
    JField("pubkey", JString(x.pubkey)),
    JField("premium", JInt(x.premium))
  ))
})

object SwapOutAgreementMessageSerializer extends MinimalSerializer({
  case x: SwapOutAgreement => JObject(List(
    JField("protocol_version", JLong(x.protocolVersion)),
    JField("swap_id", JString(x.swapId)),
    JField("pubkey", JString(x.pubkey)),
    JField("payreq", JString(x.payreq))
  ))
})

object OpeningTxBroadcastedMessageSerializer extends MinimalSerializer({
  case x: OpeningTxBroadcasted => JObject(List(
    JField("swap_id", JString(x.swapId)),
    JField("payreq", JString(x.payreq)),
    JField("tx_id", JString(x.txId)),
    JField("script_out", JInt(x.scriptOut)),
    JField("blinding_key", JString(x.blindingKey))
  ))
})

object CancelMessageSerializer extends MinimalSerializer({
  case x: CancelSwap => JObject(List(
    JField("swap_id", JString(x.swapId)),
    JField("message", JString(x.message))
  ))
})

object CoopCloseMessageSerializer extends MinimalSerializer({
  case x: CoopClose => JObject(List(
    JField("swap_id", JString(x.swapId)),
    JField("message", JString(x.message)),
    JField("privkey", JString(x.privkey))
  ))
})

object PeerSwapJsonSerializers {

  implicit val serialization: Serialization.type = jackson.Serialization

  implicit val formats: Formats = org.json4s.DefaultFormats +
    SwapInRequestMessageSerializer +
    SwapInAgreementMessageSerializer +
    SwapOutAgreementMessageSerializer +
    SwapOutRequestMessageSerializer +
    OpeningTxBroadcastedMessageSerializer +
    CancelMessageSerializer +
    CoopCloseMessageSerializer
}
