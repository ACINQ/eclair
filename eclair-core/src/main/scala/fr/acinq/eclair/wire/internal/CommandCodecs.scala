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
import fr.acinq.eclair.TimestampMilli
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.FailureMessageCodecs._
import fr.acinq.eclair.wire.protocol._
import scodec.Codec
import scodec.codecs._
import shapeless.{::, HNil}

import scala.concurrent.duration.FiniteDuration

object CommandCodecs {

  // A trailing tlv stream was added in https://github.com/lightning/bolts/pull/1021 which wasn't handled properly by
  // our previous set of codecs because we didn't prefix failure messages with their length.
  private val cmdFailWithoutLengthCodec: Codec[CMD_FAIL_HTLC] =
    (("id" | int64) ::
      ("reason" | either(bool, varsizebinarydata, provide(TemporaryNodeFailure()).upcast[FailureMessage]).xmap[FailureReason](
        {
          case Left(packet) => FailureReason.EncryptedDownstreamFailure(packet, None)
          case Right(f) => FailureReason.LocalFailure(f)
        },
        {
          case FailureReason.EncryptedDownstreamFailure(packet, _) => Left(packet)
          case FailureReason.LocalFailure(f) => Right(f)
        }
      )) ::
      ("attribution_opt" | provide(Option.empty[FailureAttributionData])) ::
      ("delay_opt" | provide(Option.empty[FiniteDuration])) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).as[CMD_FAIL_HTLC]

  private val cmdFulfillWithPartialAttributionCodec: Codec[CMD_FULFILL_HTLC] =
    (("id" | int64) ::
      ("r" | bytes32) ::
      ("downstreamAttribution_opt" | optional(bool8, bytes(Sphinx.Attribution.totalLength))) ::
      ("htlcReceivedAt_opt" | optional(bool8, uint64overflow.as[TimestampMilli])) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).map {
      case id :: r :: downstreamAttribution_opt :: htlcReceivedAt_opt :: commit :: replyTo_opt :: HNil =>
        val attribution_opt = htlcReceivedAt_opt.map(receivedAt => FulfillAttributionData(receivedAt, None, downstreamAttribution_opt))
        CMD_FULFILL_HTLC(id, r, attribution_opt, commit, replyTo_opt)
    }.decodeOnly

  private val cmdFulfillWithoutAttributionCodec: Codec[CMD_FULFILL_HTLC] =
    (("id" | int64) ::
      ("r" | bytes32) ::
      ("attribution_opt" | provide(Option.empty[FulfillAttributionData])) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).as[CMD_FULFILL_HTLC]

  private val fulfillAttributionCodec: Codec[FulfillAttributionData] =
    (("htlcReceivedAt" | uint64overflow.as[TimestampMilli]) ::
      ("trampolineReceivedAt_opt" | optional(bool8, uint64overflow.as[TimestampMilli])) ::
      ("downstreamAttribution_opt" | optional(bool8, bytes(Sphinx.Attribution.totalLength)))).as[FulfillAttributionData]

  private val cmdFullfillCodec: Codec[CMD_FULFILL_HTLC] =
    (("id" | int64) ::
      ("r" | bytes32) ::
      ("attribution_opt" | optional(bool8, fulfillAttributionCodec)) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).as[CMD_FULFILL_HTLC]

  // We previously supported only two types of HTLC failures, represented by an Either[ByteVector, FailureMessage].
  private val cmdFailEitherCodec: Codec[CMD_FAIL_HTLC] =
    (("id" | int64) ::
      ("reason" | either(bool8, varsizebinarydata, variableSizeBytes(uint16, failureMessageCodec)).xmap[FailureReason](
        {
          case Left(packet) => FailureReason.EncryptedDownstreamFailure(packet, None)
          case Right(f) => FailureReason.LocalFailure(f)
        },
        {
          case FailureReason.EncryptedDownstreamFailure(packet, _) => Left(packet)
          case FailureReason.LocalFailure(f) => Right(f)
        }
      )) ::
      ("attribution_opt" | provide(Option.empty[FailureAttributionData])) ::
      // No need to delay commands after a restart, we've been offline which already created a random delay.
      ("delay_opt" | provide(Option.empty[FiniteDuration])) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).as[CMD_FAIL_HTLC]

  private val cmdFailWithoutAttributionCodec: Codec[CMD_FAIL_HTLC] =
    (("id" | int64) ::
      ("reason" | failureReasonCodec) ::
      ("attribution_opt" | provide(Option.empty[FailureAttributionData])) ::
      // No need to delay commands after a restart, we've been offline which already created a random delay.
      ("delay_opt" | provide(Option.empty[FiniteDuration])) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).as[CMD_FAIL_HTLC]

  private val cmdFailWithPartialAttributionCodec: Codec[CMD_FAIL_HTLC] =
    (("id" | int64) ::
      ("reason" | failureReasonCodec) ::
      ("htlcReceivedAt_opt" | optional(bool8, uint64overflow.as[TimestampMilli])) ::
      // No need to delay commands after a restart, we've been offline which already created a random delay.
      ("delay_opt" | provide(Option.empty[FiniteDuration])) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).map {
      case id :: reason :: htlcReceivedAt_opt :: delay_opt :: commit :: replyTo_opt :: HNil =>
        val attribution_opt = htlcReceivedAt_opt.map(receivedAt => FailureAttributionData(receivedAt, None))
        CMD_FAIL_HTLC(id, reason, attribution_opt, delay_opt, commit, replyTo_opt)
    }.decodeOnly

  private val failureAttributionCodec: Codec[FailureAttributionData] =
    (("htlcReceivedAt" | uint64overflow.as[TimestampMilli]) ::
      ("trampolineReceivedAt_opt" | optional(bool8, uint64overflow.as[TimestampMilli]))).as[FailureAttributionData]

  private val cmdFailCodec: Codec[CMD_FAIL_HTLC] =
    (("id" | int64) ::
      ("reason" | failureReasonCodec) ::
      ("attribution_opt" | optional(bool8, failureAttributionCodec)) ::
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
    .typecase(8, cmdFullfillCodec)
    .typecase(7, cmdFailCodec)
    .typecase(6, cmdFulfillWithPartialAttributionCodec)
    .typecase(5, cmdFailWithPartialAttributionCodec)
    .typecase(4, cmdFailWithoutAttributionCodec)
    .typecase(3, cmdFailEitherCodec)
    .typecase(2, cmdFailMalformedCodec)
    .typecase(1, cmdFailWithoutLengthCodec)
    .typecase(0, cmdFulfillWithoutAttributionCodec)

}