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

import fr.acinq.eclair.json.MinimalSerializer
import fr.acinq.eclair.plugins.peerswap.SwapResponses.{Response, SwapStatus}
import fr.acinq.eclair.plugins.peerswap.json.PeerSwapJsonSerializers
import org.json4s.{Formats, JField, JObject, JString}

object ApiSerializers {

  object SwapStatusSerializer extends MinimalSerializer({
    case x: SwapStatus => JObject(List(
      JField("swap_id", JString(x.swapId)),
      JField("actor", JString(x.actor)),
      JField("behavior", JString(x.behavior)),
      JField("request", JString(x.request.json)),
      JField("agreement", JString(x.agreement_opt.collect(a => a.json).toString)),
      JField("invoice", JString(x.invoice_opt.toString)),
      JField("openingTxBroadcasted", JString(x.openingTxBroadcasted_opt.collect(o => o.json).toString))
    ))
  })

  object SwapResponseSerializer extends MinimalSerializer({
    case x: Response => JString(x.toString)
  })

  implicit val formats: Formats = PeerSwapJsonSerializers.formats + SwapResponseSerializer + SwapStatusSerializer

}
