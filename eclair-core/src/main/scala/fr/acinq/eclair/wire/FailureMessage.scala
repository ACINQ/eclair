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

package fr.acinq.eclair.wire

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.wire.CommonCodecs.{sha256, uint64overflow}
import fr.acinq.eclair.wire.LightningMessageCodecs.channelUpdateCodec
import scodec.codecs._
import scodec.Attempt

/**
  * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
  * Created by fabrice on 14/03/17.
  */

// @formatter:off
sealed trait FailureMessage { def message: String }
sealed trait BadOnion extends FailureMessage { def onionHash: ByteVector32 }
sealed trait Perm extends FailureMessage
sealed trait Node extends FailureMessage
sealed trait Update extends FailureMessage { def update: ChannelUpdate }

case object InvalidRealm extends Perm { def message = "realm was not understood by the processing node" }
case object TemporaryNodeFailure extends Node { def message = "general temporary failure of the processing node" }
case object PermanentNodeFailure extends Perm with Node { def message = "general permanent failure of the processing node" }
case object RequiredNodeFeatureMissing extends Perm with Node { def message = "processing node requires features that are missing from this onion" }
case class InvalidOnionVersion(onionHash: ByteVector32) extends BadOnion with Perm { def message = "onion version was not understood by the processing node" }
case class InvalidOnionHmac(onionHash: ByteVector32) extends BadOnion with Perm { def message = "onion HMAC was incorrect when it reached the processing node" }
case class InvalidOnionKey(onionHash: ByteVector32) extends BadOnion with Perm { def message = "ephemeral key was unparsable by the processing node" }
case class TemporaryChannelFailure(update: ChannelUpdate) extends Update { def message = s"channel ${update.shortChannelId} is currently unavailable" }
case object PermanentChannelFailure extends Perm { def message = "channel is permanently unavailable" }
case object RequiredChannelFeatureMissing extends Perm { def message = "channel requires features not present in the onion" }
case object UnknownNextPeer extends Perm { def message = "processing node does not know the next peer in the route" }
case class AmountBelowMinimum(amountMsat: Long, update: ChannelUpdate) extends Update { def message = s"payment amount was below the minimum required by the channel" }
case class FeeInsufficient(amountMsat: Long, update: ChannelUpdate) extends Update { def message = s"payment fee was below the minimum required by the channel" }
case class ChannelDisabled(messageFlags: Byte, channelFlags: Byte, update: ChannelUpdate) extends Update { def message = "channel is currently disabled" }
case class IncorrectCltvExpiry(expiry: Long, update: ChannelUpdate) extends Update { def message = "payment expiry doesn't match the value in the onion" }
case class IncorrectOrUnknownPaymentDetails(amountMsat: Long) extends Perm { def message = "incorrect payment amount or unknown payment hash" }
case object IncorrectPaymentAmount extends Perm { def message = "payment amount is incorrect" }
case class ExpiryTooSoon(update: ChannelUpdate) extends Update { def message = "payment expiry is too close to the current block height for safe handling by the relaying node" }
case object FinalExpiryTooSoon extends FailureMessage { def message = "payment expiry is too close to the current block height for safe handling by the final node" }
case class FinalIncorrectCltvExpiry(expiry: Long) extends FailureMessage { def message = "payment expiry doesn't match the value in the onion" }
case class FinalIncorrectHtlcAmount(amountMsat: Long) extends FailureMessage { def message = "payment amount is incorrect in the final htlc" }
case object ExpiryTooFar extends FailureMessage { def message = "payment expiry is too far in the future" }
// @formatter:on

object FailureMessageCodecs {
  val BADONION = 0x8000
  val PERM = 0x4000
  val NODE = 0x2000
  val UPDATE = 0x1000

  val channelUpdateCodecWithType = LightningMessageCodecs.lightningMessageCodec.narrow[ChannelUpdate](f => Attempt.successful(f.asInstanceOf[ChannelUpdate]), g => g)

  // NB: for historical reasons some implementations were including/ommitting the message type (258 for ChannelUpdate)
  // this codec supports both versions for decoding, and will encode with the message type
  val channelUpdateWithLengthCodec = variableSizeBytes(uint16, choice(channelUpdateCodecWithType, channelUpdateCodec))

  val failureMessageCodec = discriminated[FailureMessage].by(uint16)
    .typecase(PERM | 1, provide(InvalidRealm))
    .typecase(NODE | 2, provide(TemporaryNodeFailure))
    .typecase(PERM | 2, provide(PermanentNodeFailure))
    .typecase(PERM | NODE | 3, provide(RequiredNodeFeatureMissing))
    .typecase(BADONION | PERM | 4, sha256.as[InvalidOnionVersion])
    .typecase(BADONION | PERM | 5, sha256.as[InvalidOnionHmac])
    .typecase(BADONION | PERM | 6, sha256.as[InvalidOnionKey])
    .typecase(UPDATE | 7, ("channelUpdate" | channelUpdateWithLengthCodec).as[TemporaryChannelFailure])
    .typecase(PERM | 8, provide(PermanentChannelFailure))
    .typecase(PERM | 9, provide(RequiredChannelFeatureMissing))
    .typecase(PERM | 10, provide(UnknownNextPeer))
    .typecase(UPDATE | 11, (("amountMsat" | uint64overflow) :: ("channelUpdate" | channelUpdateWithLengthCodec)).as[AmountBelowMinimum])
    .typecase(UPDATE | 12, (("amountMsat" | uint64overflow) :: ("channelUpdate" | channelUpdateWithLengthCodec)).as[FeeInsufficient])
    .typecase(UPDATE | 13, (("expiry" | uint32) :: ("channelUpdate" | channelUpdateWithLengthCodec)).as[IncorrectCltvExpiry])
    .typecase(UPDATE | 14, ("channelUpdate" | channelUpdateWithLengthCodec).as[ExpiryTooSoon])
    .typecase(UPDATE | 20, (("messageFlags" | byte) :: ("channelFlags" | byte) :: ("channelUpdate" | channelUpdateWithLengthCodec)).as[ChannelDisabled])
    .typecase(PERM | 15, ("amountMsat" | withDefaultValue(optional(bitsRemaining, uint64overflow), 0L)).as[IncorrectOrUnknownPaymentDetails])
    .typecase(PERM | 16, provide(IncorrectPaymentAmount))
    .typecase(17, provide(FinalExpiryTooSoon))
    .typecase(18, ("expiry" | uint32).as[FinalIncorrectCltvExpiry])
    .typecase(19, ("amountMsat" | uint64overflow).as[FinalIncorrectHtlcAmount])
    .typecase(21, provide(ExpiryTooFar))
}
