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

import akka.http.scaladsl.common.{NameReceptacle, NameUnmarshallerReceptacle}
import akka.http.scaladsl.server.Route
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors._

object ApiHandlers {

  import fr.acinq.eclair.api.serde.JsonSupport.{marshaller, serialization}
  import fr.acinq.eclair.plugins.peerswap.ApiSerializers.formats

  def registerRoutes(kit: PeerSwapKit, eclairDirectives: EclairDirectives): Route = {
    import eclairDirectives._

    val swapIdFormParam: NameUnmarshallerReceptacle[ByteVector32] = "swapId".as[ByteVector32](sha256HashUnmarshaller)

    val amountSatFormParam: NameReceptacle[Satoshi] = "amountSat".as[Satoshi]

    val swapIn: Route = postRequest("swapin") { implicit t =>
      formFields(shortChannelIdFormParam, amountSatFormParam) { (channelId, amount) =>
        complete(kit.swapIn(channelId, amount))
      }
    }

    val swapOut: Route = postRequest("swapout") { implicit t =>
      formFields(shortChannelIdFormParam, amountSatFormParam) { (channelId, amount) =>
        complete(kit.swapOut(channelId, amount))
      }
    }

    val listSwaps: Route = postRequest("listswaps") { implicit t =>
      complete(kit.listSwaps())
    }

    val cancelSwap: Route = postRequest("cancelswap") { implicit t =>
      formFields(swapIdFormParam) { swapId =>
        complete(kit.cancelSwap(swapId.toString()))
      }
    }

    val peerSwapRoutes: Route = swapIn ~ swapOut ~ listSwaps ~ cancelSwap

    peerSwapRoutes
  }

}


