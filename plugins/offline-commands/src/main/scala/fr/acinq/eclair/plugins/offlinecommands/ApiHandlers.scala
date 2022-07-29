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

package fr.acinq.eclair.plugins.offlinecommands

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.adapter.ClassicSchedulerOps
import akka.http.scaladsl.server.{MalformedFormFieldRejection, Route}
import akka.util.Timeout
import fr.acinq.bitcoin.scalacompat.Script
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors._
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.channel.ClosingFeerates
import fr.acinq.eclair.plugins.offlinecommands.OfflineChannelsCloser.CloseChannels
import scodec.bits.ByteVector

import scala.concurrent.duration._

object ApiHandlers {

  import fr.acinq.eclair.api.serde.JsonSupport.{marshaller, serialization}
  import fr.acinq.eclair.plugins.offlinecommands.ApiSerializers.formats

  def registerRoutes(kit: OfflineCommandsKit, eclairDirectives: EclairDirectives): Route = {
    import eclairDirectives._

    val close: Route = postRequest("offlineclose") { implicit t =>
      formFields(channelIdsFormParam, "scriptPubKey".as[ByteVector](binaryDataUnmarshaller).?, "preferredFeerateSatByte".as[FeeratePerByte].?, "minFeerateSatByte".as[FeeratePerByte].?, "maxFeerateSatByte".as[FeeratePerByte].?) {
        (channelIds, scriptPubKey_opt, preferredFeerate_opt, minFeerate_opt, maxFeerate_opt) =>
          val closingFeerates_opt = preferredFeerate_opt.map(preferredPerByte => {
            val preferredFeerate = FeeratePerKw(preferredPerByte)
            val minFeerate = minFeerate_opt.map(feerate => FeeratePerKw(feerate)).getOrElse(preferredFeerate / 2)
            val maxFeerate = maxFeerate_opt.map(feerate => FeeratePerKw(feerate)).getOrElse(preferredFeerate * 2)
            ClosingFeerates(preferredFeerate, minFeerate, maxFeerate)
          })
          if (scriptPubKey_opt.forall(Script.isNativeWitnessScript)) {
            val res = kit.closer.ask(ref => CloseChannels(ref, channelIds, scriptPubKey_opt, closingFeerates_opt))(Timeout(30 seconds), kit.system.scheduler.toTyped)
            complete(res)
          } else {
            reject(MalformedFormFieldRejection("scriptPubKey", "Non-segwit scripts are not allowed"))
          }
      }
    }

    close
  }

}


