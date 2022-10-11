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
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.{channelFlagsCodec, messageFlagsCodec}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong}
import scodec.Codec
import scodec.codecs._

import scala.concurrent.duration.FiniteDuration

object CommandCodecs {

  // A trailing tlv stream was added in https://github.com/lightning/bolts/pull/1021 which wasn't handled properly by
  // our previous set of codecs because we didn't prefix failure messages with their length.
  private val legacyFailureMessageCodec = discriminated[FailureMessage].by(uint16)
    .typecase(PERM | 1, provide(InvalidRealm()))
    .typecase(NODE | 2, provide(TemporaryNodeFailure()))
    .typecase(PERM | NODE | 2, provide(PermanentNodeFailure()))
    .typecase(PERM | NODE | 3, provide(RequiredNodeFeatureMissing()))
    .typecase(BADONION | PERM | 4, (sha256 :: provide(TlvStream.empty[FailureMessageTlv])).as[InvalidOnionVersion])
    .typecase(BADONION | PERM | 5, (sha256 :: provide(TlvStream.empty[FailureMessageTlv])).as[InvalidOnionHmac])
    .typecase(BADONION | PERM | 6, (sha256 :: provide(TlvStream.empty[FailureMessageTlv])).as[InvalidOnionKey])
    .typecase(UPDATE | 7, (channelUpdateWithLengthCodec :: provide(TlvStream.empty[FailureMessageTlv])).as[TemporaryChannelFailure])
    .typecase(PERM | 8, provide(PermanentChannelFailure()))
    .typecase(PERM | 9, provide(RequiredChannelFeatureMissing()))
    .typecase(PERM | 10, provide(UnknownNextPeer()))
    .typecase(UPDATE | 11, (("amountMsat" | millisatoshi) :: ("channelUpdate" | channelUpdateWithLengthCodec) :: ("tlvs" | provide(TlvStream.empty[FailureMessageTlv]))).as[AmountBelowMinimum])
    .typecase(UPDATE | 12, (("amountMsat" | millisatoshi) :: ("channelUpdate" | channelUpdateWithLengthCodec) :: ("tlvs" | provide(TlvStream.empty[FailureMessageTlv]))).as[FeeInsufficient])
    .typecase(UPDATE | 13, (("expiry" | cltvExpiry) :: ("channelUpdate" | channelUpdateWithLengthCodec) :: ("tlvs" | provide(TlvStream.empty[FailureMessageTlv]))).as[IncorrectCltvExpiry])
    .typecase(UPDATE | 14, (("channelUpdate" | channelUpdateWithLengthCodec) :: ("tlvs" | provide(TlvStream.empty[FailureMessageTlv]))).as[ExpiryTooSoon])
    .typecase(UPDATE | 20, (messageFlagsCodec :: channelFlagsCodec :: ("channelUpdate" | channelUpdateWithLengthCodec) :: ("tlvs" | provide(TlvStream.empty[FailureMessageTlv]))).as[ChannelDisabled])
    .typecase(PERM | 15, (("amountMsat" | withDefaultValue(optional(bitsRemaining, millisatoshi), 0 msat)) :: ("height" | withDefaultValue(optional(bitsRemaining, blockHeight), BlockHeight(0))) :: ("tlvs" | provide(TlvStream.empty[FailureMessageTlv]))).as[IncorrectOrUnknownPaymentDetails])
    .typecase(18, (("expiry" | cltvExpiry) :: ("tlvs" | provide(TlvStream.empty[FailureMessageTlv]))).as[FinalIncorrectCltvExpiry])
    .typecase(19, (("amountMsat" | millisatoshi) :: ("tlvs" | provide(TlvStream.empty[FailureMessageTlv]))).as[FinalIncorrectHtlcAmount])
    .typecase(21, provide(ExpiryTooFar()))
    .typecase(PERM | 22, (("tag" | varint) :: ("offset" | uint16) :: ("tlvs" | provide(TlvStream.empty[FailureMessageTlv]))).as[InvalidOnionPayload])
    .typecase(23, provide(PaymentTimeout()))
    .typecase(BADONION | PERM | 24, (sha256 :: provide(TlvStream.empty[FailureMessageTlv])).as[InvalidOnionBlinding])
    .typecase(NODE | 51, provide(TrampolineFeeInsufficient()))
    .typecase(NODE | 52, provide(TrampolineExpiryTooSoon()))

  private val legacyCmdFailCodec: Codec[CMD_FAIL_HTLC] =
    (("id" | int64) ::
      ("reason" | either(bool, varsizebinarydata, legacyFailureMessageCodec)) ::
      ("delay_opt" | provide(Option.empty[FiniteDuration])) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).as[CMD_FAIL_HTLC]

  val cmdFulfillCodec: Codec[CMD_FULFILL_HTLC] =
    (("id" | int64) ::
      ("r" | bytes32) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).as[CMD_FULFILL_HTLC]

  val cmdFailCodec: Codec[CMD_FAIL_HTLC] =
    (("id" | int64) ::
      ("reason" | either(bool8, varsizebinarydata, variableSizeBytes(uint16, failureMessageCodec))) ::
      // No need to delay commands after a restart, we've been offline which already created a random delay.
      ("delay_opt" | provide(Option.empty[FiniteDuration])) ::
      ("commit" | provide(false)) ::
      ("replyTo_opt" | provide(Option.empty[ActorRef]))).as[CMD_FAIL_HTLC]

  val cmdFailMalformedCodec: Codec[CMD_FAIL_MALFORMED_HTLC] =
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