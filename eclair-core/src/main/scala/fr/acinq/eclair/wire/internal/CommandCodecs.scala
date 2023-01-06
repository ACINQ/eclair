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

package fr.acinq.eclair.wire.internal

import akka.actor.ActorRef
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.FailureMessageCodecs._
import fr.acinq.eclair.wire.protocol._
import scodec.Codec
import scodec.codecs._

import scala.concurrent.duration.FiniteDuration

object CommandCodecs {

  // A trailing tlv stream was added in https://github.com/lightning/bolts/pull/1021 which wasn't handled properly by
  // our previous set of codecs because we didn't prefix failure messages with their length.
  private val legacyCmdFailCodec: Codec[CMD_FAIL_HTLC] =
    (("id" | int64) ::
      ("reason" | either(bool, varsizebinarydata, provide(TemporaryNodeFailure()).upcast[FailureMessage])) ::
      ("delay_opt" | provide(Option.empty[FiniteDuration])) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).as[CMD_FAIL_HTLC]

  private val cmdFulfillCodec: Codec[CMD_FULFILL_HTLC] =
    (("id" | int64) ::
      ("r" | bytes32) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).as[CMD_FULFILL_HTLC]

  private val cmdFailCodec: Codec[CMD_FAIL_HTLC] =
    (("id" | int64) ::
      ("reason" | either(bool8, varsizebinarydata, variableSizeBytes(uint16, failureMessageCodec))) ::
      // No need to delay commands after a restart, we've been offline which already created a random delay.
      ("delay_opt" | provide(Option.empty[FiniteDuration])) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).as[CMD_FAIL_HTLC]

  private val cmdFailMalformedCodec: Codec[CMD_FAIL_MALFORMED_HTLC] =
    (("id" | int64) ::
      ("onionHash" | bytes32) ::
      ("failureCode" | uint16) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).as[CMD_FAIL_MALFORMED_HTLC]

  val cmdCodec: Codec[HtlcSettlementCommand] = discriminated[HtlcSettlementCommand].by(uint16)
    // NB: order matters!
    .typecase(3, cmdFailCodec)
    .typecase(2, cmdFailMalformedCodec)
    .typecase(1, legacyCmdFailCodec)
    .typecase(0, cmdFulfillCodec)

}