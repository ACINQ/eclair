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

package fr.acinq.eclair.api.handlers

import akka.http.scaladsl.server.{MalformedFormFieldRejection, Route}
import akka.util.Timeout
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors._
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.channel.ClosingFeerates
import scodec.bits.ByteVector

trait Channel {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val open: Route = postRequest("open") { implicit t =>
    formFields(nodeIdFormParam, "fundingSatoshis".as[Satoshi], "pushMsat".as[MilliSatoshi].?, "fundingFeerateSatByte".as[FeeratePerByte].?, "feeBaseMsat".as[MilliSatoshi].?,
      "feeProportionalMillionths".as[Int].?, "channelFlags".as[Int].?, "openTimeoutSeconds".as[Timeout].?) {
      (nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, feeBase, feeProportional, channelFlags, openTimeout_opt) =>
        if (feeBase.nonEmpty && feeProportional.isEmpty || feeBase.isEmpty && feeProportional.nonEmpty) {
          reject(MalformedFormFieldRejection("feeBaseMsat/feeProportionalMillionths",
            "All relay fees parameters (feeBaseMsat/feeProportionalMillionths) must be specified to override node defaults"
          ))
        } else {
          val initialRelayFees = (feeBase, feeProportional) match {
            case (Some(feeBase), Some(feeProportional)) => Some(feeBase, feeProportional)
            case _ => None
          }
          complete {
            eclairApi.open(nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, initialRelayFees, channelFlags, openTimeout_opt)
          }
        }
    }
  }

  val close: Route = postRequest("close") { implicit t =>
    withChannelsIdentifier { channels =>
      formFields("scriptPubKey".as[ByteVector](binaryDataUnmarshaller).?, "preferredFeerateSatByte".as[FeeratePerByte].?, "minFeerateSatByte".as[FeeratePerByte].?, "maxFeerateSatByte".as[FeeratePerByte].?) {
        (scriptPubKey_opt, preferredFeerate_opt, minFeerate_opt, maxFeerate_opt) =>
          val closingFeerates = preferredFeerate_opt.map(preferredPerByte => {
            val preferredFeerate = FeeratePerKw(preferredPerByte)
            val minFeerate = minFeerate_opt.map(feerate => FeeratePerKw(feerate)).getOrElse(preferredFeerate / 2)
            val maxFeerate = maxFeerate_opt.map(feerate => FeeratePerKw(feerate)).getOrElse(preferredFeerate * 2)
            ClosingFeerates(preferredFeerate, minFeerate, maxFeerate)
          })
          complete(eclairApi.close(channels, scriptPubKey_opt, closingFeerates))
      }
    }
  }

  val forceClose: Route = postRequest("forceclose") { implicit t =>
    withChannelsIdentifier { channels =>
      complete(eclairApi.forceClose(channels))
    }
  }

  val channel: Route = postRequest("channel") { implicit t =>
    withChannelIdentifier { channel =>
      complete(eclairApi.channelInfo(channel))
    }
  }

  val channels: Route = postRequest("channels") { implicit t =>
    formFields(nodeIdFormParam.?) { toRemoteNodeId_opt =>
      complete(eclairApi.channelsInfo(toRemoteNodeId_opt))
    }
  }

  val allChannels: Route = postRequest("allchannels") { implicit t =>
    complete(eclairApi.allChannels())
  }

  val allUpdates: Route = postRequest("allupdates") { implicit t =>
    formFields(nodeIdFormParam.?) { nodeId_opt =>
      complete(eclairApi.allUpdates(nodeId_opt))
    }
  }

  val channelStats: Route = postRequest("channelstats") { implicit t =>
    formFields(fromFormParam.?, toFormParam.?) { (from_opt, to_opt) =>
      complete(eclairApi.channelStats(from_opt, to_opt))
    }
  }

  val channelRoutes: Route = open ~ close ~ forceClose ~ channel ~ channels ~ allChannels ~ allUpdates ~ channelStats

}
