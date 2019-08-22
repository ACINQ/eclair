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
import fr.acinq.eclair.crypto.Mac32
import fr.acinq.eclair.wire.CommonCodecs.{cltvExpiry, millisatoshi, sha256}
import fr.acinq.eclair.wire.LightningMessageCodecs.{channelUpdateCodec, lightningMessageCodec}
import fr.acinq.eclair.{CltvExpiry, LongToBtcAmount, MilliSatoshi}
import scodec.codecs._
import scodec.{Attempt, Codec}

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
case class InvalidOnionPayload(onionHash: ByteVector32) extends BadOnion with Perm { def message = "onion per-hop payload could not be parsed" }
case class TemporaryChannelFailure(update: ChannelUpdate) extends Update { def message = s"channel ${update.shortChannelId} is currently unavailable" }
case object PermanentChannelFailure extends Perm { def message = "channel is permanently unavailable" }
case object RequiredChannelFeatureMissing extends Perm { def message = "channel requires features not present in the onion" }
case object UnknownNextPeer extends Perm { def message = "processing node does not know the next peer in the route" }
case class AmountBelowMinimum(amount: MilliSatoshi, update: ChannelUpdate) extends Update { def message = s"payment amount was below the minimum required by the channel" }
case class FeeInsufficient(amount: MilliSatoshi, update: ChannelUpdate) extends Update { def message = s"payment fee was below the minimum required by the channel" }
case class ChannelDisabled(messageFlags: Byte, channelFlags: Byte, update: ChannelUpdate) extends Update { def message = "channel is currently disabled" }
case class IncorrectCltvExpiry(expiry: CltvExpiry, update: ChannelUpdate) extends Update { def message = "payment expiry doesn't match the value in the onion" }
case class IncorrectOrUnknownPaymentDetails(amount: MilliSatoshi, height: Long) extends Perm { def message = "incorrect payment details or unknown payment hash" }
/** Deprecated: this failure code allows probing attacks: IncorrectOrUnknownPaymentDetails should be used instead. */
case object IncorrectPaymentAmount extends Perm { def message = "payment amount is incorrect" }
case class ExpiryTooSoon(update: ChannelUpdate) extends Update { def message = "payment expiry is too close to the current block height for safe handling by the relaying node" }
/** Deprecated: this failure code allows probing attacks: IncorrectOrUnknownPaymentDetails should be used instead. */
case object FinalExpiryTooSoon extends FailureMessage { def message = "payment expiry is too close to the current block height for safe handling by the final node" }
case class FinalIncorrectCltvExpiry(expiry: CltvExpiry) extends FailureMessage { def message = "payment expiry doesn't match the value in the onion" }
case class FinalIncorrectHtlcAmount(amount: MilliSatoshi) extends FailureMessage { def message = "payment amount is incorrect in the final htlc" }
case object ExpiryTooFar extends FailureMessage { def message = "payment expiry is too far in the future" }
// @formatter:on

object FailureMessageCodecs {
  val BADONION = 0x8000
  val PERM = 0x4000
  val NODE = 0x2000
  val UPDATE = 0x1000

  val channelUpdateCodecWithType = lightningMessageCodec.narrow[ChannelUpdate](f => Attempt.successful(f.asInstanceOf[ChannelUpdate]), g => g)

  // NB: for historical reasons some implementations were including/omitting the message type (258 for ChannelUpdate)
  // this codec supports both versions for decoding, and will encode with the message type
  val channelUpdateWithLengthCodec = variableSizeBytes(uint16, choice(channelUpdateCodecWithType, channelUpdateCodec))

  val failureMessageCodec = discriminated[FailureMessage].by(uint16)
    .typecase(PERM | 1, provide(InvalidRealm))
    .typecase(NODE | 2, provide(TemporaryNodeFailure))
    .typecase(PERM | 2, provide(PermanentNodeFailure))
    .typecase(PERM | NODE | 3, provide(RequiredNodeFeatureMissing))
    .typecase(BADONION | PERM, sha256.as[InvalidOnionPayload])
    .typecase(BADONION | PERM | 4, sha256.as[InvalidOnionVersion])
    .typecase(BADONION | PERM | 5, sha256.as[InvalidOnionHmac])
    .typecase(BADONION | PERM | 6, sha256.as[InvalidOnionKey])
    .typecase(UPDATE | 7, ("channelUpdate" | channelUpdateWithLengthCodec).as[TemporaryChannelFailure])
    .typecase(PERM | 8, provide(PermanentChannelFailure))
    .typecase(PERM | 9, provide(RequiredChannelFeatureMissing))
    .typecase(PERM | 10, provide(UnknownNextPeer))
    .typecase(UPDATE | 11, (("amountMsat" | millisatoshi) :: ("channelUpdate" | channelUpdateWithLengthCodec)).as[AmountBelowMinimum])
    .typecase(UPDATE | 12, (("amountMsat" | millisatoshi) :: ("channelUpdate" | channelUpdateWithLengthCodec)).as[FeeInsufficient])
    .typecase(UPDATE | 13, (("expiry" | cltvExpiry) :: ("channelUpdate" | channelUpdateWithLengthCodec)).as[IncorrectCltvExpiry])
    .typecase(UPDATE | 14, ("channelUpdate" | channelUpdateWithLengthCodec).as[ExpiryTooSoon])
    .typecase(UPDATE | 20, (("messageFlags" | byte) :: ("channelFlags" | byte) :: ("channelUpdate" | channelUpdateWithLengthCodec)).as[ChannelDisabled])
    .typecase(PERM | 15, (("amountMsat" | withDefaultValue(optional(bitsRemaining, millisatoshi), 0 msat)) :: ("height" | withDefaultValue(optional(bitsRemaining, uint32), 0L))).as[IncorrectOrUnknownPaymentDetails])
    .typecase(PERM | 16, provide(IncorrectPaymentAmount))
    .typecase(17, provide(FinalExpiryTooSoon))
    .typecase(18, ("expiry" | cltvExpiry).as[FinalIncorrectCltvExpiry])
    .typecase(19, ("amountMsat" | millisatoshi).as[FinalIncorrectHtlcAmount])
    .typecase(21, provide(ExpiryTooFar))

  /**
   * Return the failure code for a given failure message. This method actually encodes the failure message, which is a
   * bit clunky and not particularly efficient. It shouldn't be used on the application's hot path.
   */
  def failureCode(failure: FailureMessage): Int = failureMessageCodec.encode(failure).flatMap(uint16.decode).require.value

  /**
   * An onion-encrypted failure from an intermediate node:
   * +----------------+----------------------------------+-----------------+----------------------+-----+
   * | HMAC(32 bytes) | failure message length (2 bytes) | failure message | pad length (2 bytes) | pad |
   * +----------------+----------------------------------+-----------------+----------------------+-----+
   * with failure message length + pad length = 256
   */
  def failureOnionCodec(mac: Mac32): Codec[FailureMessage] = CommonCodecs.prependmac(
    paddedFixedSizeBytesDependent(
      260,
      "failureMessage" | variableSizeBytes(uint16, FailureMessageCodecs.failureMessageCodec),
      nBits => "padding" | variableSizeBytes(uint16, ignore(nBits - 2 * 8)) // two bytes are used to encode the padding length
    ).as[FailureMessage], mac)
}
