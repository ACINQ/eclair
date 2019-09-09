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
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.FailureMessageCodecs.failureMessageCodec
import fr.acinq.eclair.wire.LightningMessageCodecs.{channelUpdateCodec, lightningMessageCodec}
import fr.acinq.eclair.{CltvExpiry, LongToBtcAmount, MilliSatoshi, UInt64}
import scodec.codecs._
import scodec.{Attempt, Codec}

/**
 * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
 * Created by fabrice on 14/03/17.
 */

// @formatter:off
sealed trait FailureMessage {
  def message: String
  // We actually encode the failure message, which is a bit clunky and not particularly efficient.
  // It would be nice to be able to get that value from the discriminated codec directly.
  lazy val code: Int = failureMessageCodec.encode(this).flatMap(uint16.decode).require.value
}
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
case class AmountBelowMinimum(amount: MilliSatoshi, update: ChannelUpdate) extends Update { def message = s"payment amount was below the minimum required by the channel" }
case class FeeInsufficient(amount: MilliSatoshi, update: ChannelUpdate) extends Update { def message = s"payment fee was below the minimum required by the channel" }
case class ChannelDisabled(messageFlags: Byte, channelFlags: Byte, update: ChannelUpdate) extends Update { def message = "channel is currently disabled" }
case class IncorrectCltvExpiry(expiry: CltvExpiry, update: ChannelUpdate) extends Update { def message = "payment expiry doesn't match the value in the onion" }
case class IncorrectOrUnknownPaymentDetails(amount: MilliSatoshi, height: Long) extends Perm { def message = "incorrect payment details or unknown payment hash" }
case class ExpiryTooSoon(update: ChannelUpdate) extends Update { def message = "payment expiry is too close to the current block height for safe handling by the relaying node" }
case class FinalIncorrectCltvExpiry(expiry: CltvExpiry) extends FailureMessage { def message = "payment expiry doesn't match the value in the onion" }
case class FinalIncorrectHtlcAmount(amount: MilliSatoshi) extends FailureMessage { def message = "payment amount is incorrect in the final htlc" }
case object ExpiryTooFar extends FailureMessage { def message = "payment expiry is too far in the future" }
case class InvalidOnionPayload(tag: UInt64, offset: Int) extends Perm { def message = "onion per-hop payload is invalid" }

/**
 * We allow remote nodes to send us unknown failure codes (e.g. deprecated failure codes).
 * By reading the PERM and NODE bits we can still extract useful information for payment retry even without knowing how
 * to decode the failure payload (but we can't extract a channel update or onion hash).
 */
sealed trait UnknownFailureMessage extends FailureMessage {
  def message = "unknown failure message"
  override def toString = s"$message (${code.toHexString})"
  override def equals(obj: Any): Boolean = obj match {
    case f: UnknownFailureMessage => f.code == code
    case _ => false
  }
}
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

  val failureMessageCodec = discriminatorWithDefault(
    discriminated[FailureMessage].by(uint16)
      .typecase(PERM | 1, provide(InvalidRealm))
      .typecase(NODE | 2, provide(TemporaryNodeFailure))
      .typecase(PERM | NODE | 2, provide(PermanentNodeFailure))
      .typecase(PERM | NODE | 3, provide(RequiredNodeFeatureMissing))
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
      // PERM | 16 (incorrect_payment_amount) has been deprecated because it allowed probing attacks: IncorrectOrUnknownPaymentDetails should be used instead.
      // PERM | 17 (final_expiry_too_soon) has been deprecated because it allowed probing attacks: IncorrectOrUnknownPaymentDetails should be used instead.
      .typecase(18, ("expiry" | cltvExpiry).as[FinalIncorrectCltvExpiry])
      .typecase(19, ("amountMsat" | millisatoshi).as[FinalIncorrectHtlcAmount])
      .typecase(21, provide(ExpiryTooFar))
      .typecase(PERM | 22, (("tag" | varint) :: ("offset" | uint16)).as[InvalidOnionPayload]),
    uint16.xmap(code => {
      val failureMessage = code match {
        // @formatter:off
        case fc if (fc & PERM) != 0 && (fc & NODE) != 0 => new UnknownFailureMessage with Perm with Node { override lazy val code = fc }
        case fc if (fc & NODE) != 0 => new UnknownFailureMessage with Node { override lazy val code = fc }
        case fc if (fc & PERM) != 0 => new UnknownFailureMessage with Perm { override lazy val code = fc }
        case fc => new UnknownFailureMessage { override lazy val code  = fc }
        // @formatter:on
      }
      failureMessage.asInstanceOf[FailureMessage]
    }, (_: FailureMessage).code)
  )

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
