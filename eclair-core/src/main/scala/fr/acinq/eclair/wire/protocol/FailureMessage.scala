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

package fr.acinq.eclair.wire.protocol

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.crypto.Mac32
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.FailureMessageCodecs.failureMessageCodec
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.{channelFlagsCodec, channelUpdateCodec, messageFlagsCodec, meteredLightningMessageCodec}
import fr.acinq.eclair.{BlockHeight, CltvExpiry, MilliSatoshi, MilliSatoshiLong, UInt64}
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.{Attempt, Codec, Err}

/**
 * see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md
 * Created by fabrice on 14/03/17.
 */

sealed trait FailureMessageTlv extends Tlv

// @formatter:off
sealed trait FailureMessage {
  def message: String
  def tlvs: TlvStream[FailureMessageTlv]
  // We actually encode the failure message, which is a bit clunky and not particularly efficient.
  // It would be nice to be able to get that value from the discriminated codec directly.
  lazy val code: Int = failureMessageCodec.encode(this).flatMap(uint16.decode).require.value
}
sealed trait BadOnion extends FailureMessage { def onionHash: ByteVector32 }
sealed trait Perm extends FailureMessage
sealed trait Node extends FailureMessage
sealed trait Update extends FailureMessage { def update: ChannelUpdate }

case class InvalidRealm(tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Perm { def message = "realm was not understood by the processing node" }
case class TemporaryNodeFailure(tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Node { def message = "general temporary failure of the processing node" }
case class PermanentNodeFailure(tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Perm with Node { def message = "general permanent failure of the processing node" }
case class RequiredNodeFeatureMissing(tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Perm with Node { def message = "processing node requires features that are missing from this onion" }
case class InvalidOnionVersion(onionHash: ByteVector32, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends BadOnion with Perm { def message = "onion version was not understood by the processing node" }
case class InvalidOnionHmac(onionHash: ByteVector32, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends BadOnion with Perm { def message = "onion HMAC was incorrect when it reached the processing node" }
case class InvalidOnionKey(onionHash: ByteVector32, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends BadOnion with Perm { def message = "ephemeral key was unparsable by the processing node" }
case class InvalidOnionBlinding(onionHash: ByteVector32, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends BadOnion with Perm { def message = "the blinded onion didn't match the processing node's requirements" }
case class TemporaryChannelFailure(update: ChannelUpdate, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Update { def message = s"channel ${update.shortChannelId} is currently unavailable" }
case class PermanentChannelFailure(tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Perm { def message = "channel is permanently unavailable" }
case class RequiredChannelFeatureMissing(tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Perm { def message = "channel requires features not present in the onion" }
case class UnknownNextPeer(tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Perm { def message = "processing node does not know the next peer in the route" }
case class AmountBelowMinimum(amount: MilliSatoshi, update: ChannelUpdate, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Update { def message = "payment amount was below the minimum required by the channel" }
case class FeeInsufficient(amount: MilliSatoshi, update: ChannelUpdate, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Update { def message = "payment fee was below the minimum required by the channel" }
case class TrampolineFeeInsufficient(tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Node { def message = "payment fee was below the minimum required by the trampoline node" }
case class ChannelDisabled(messageFlags: ChannelUpdate.MessageFlags, channelFlags: ChannelUpdate.ChannelFlags, update: ChannelUpdate, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Update { def message = "channel is currently disabled" }
case class IncorrectCltvExpiry(expiry: CltvExpiry, update: ChannelUpdate, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Update { def message = "payment expiry doesn't match the value in the onion" }
case class IncorrectOrUnknownPaymentDetails(amount: MilliSatoshi, height: BlockHeight, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Perm { def message = "incorrect payment details or unknown payment hash" }
case class ExpiryTooSoon(update: ChannelUpdate, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Update { def message = "payment expiry is too close to the current block height for safe handling by the relaying node" }
case class TrampolineExpiryTooSoon(tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Node { def message = "payment expiry is too close to the current block height for safe handling by the relaying node" }
case class FinalIncorrectCltvExpiry(expiry: CltvExpiry, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends FailureMessage { def message = "payment expiry doesn't match the value in the onion" }
case class FinalIncorrectHtlcAmount(amount: MilliSatoshi, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends FailureMessage { def message = "payment amount is incorrect in the final htlc" }
case class ExpiryTooFar(tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends FailureMessage { def message = "payment expiry is too far in the future" }
case class InvalidOnionPayload(tag: UInt64, offset: Int, tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends Perm { def message = "onion per-hop payload is invalid" }
case class PaymentTimeout(tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty) extends FailureMessage { def message = "the complete payment amount was not received within a reasonable time" }

/**
 * We allow remote nodes to send us unknown failure codes (e.g. deprecated failure codes).
 * By reading the PERM and NODE bits we can still extract useful information for payment retry even without knowing how
 * to decode the failure payload (but we can't extract a channel update or onion hash).
 */
sealed trait UnknownFailureMessage extends FailureMessage {
  override def message = "unknown failure message"
  override def tlvs: TlvStream[FailureMessageTlv] = TlvStream.empty
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

  val channelUpdateCodecWithType = meteredLightningMessageCodec.narrow[ChannelUpdate]({
    case f: ChannelUpdate => Attempt.successful(f)
    case _ => Attempt.failure(Err("not a ChanelUpdate message"))
  }, g => g)

  // NB: for historical reasons some implementations were including/omitting the message type (258 for ChannelUpdate)
  // this codec supports both versions for decoding, and will encode with the message type
  val channelUpdateWithLengthCodec = variableSizeBytes(uint16, choice(channelUpdateCodecWithType, channelUpdateCodec))

  val failureTlvsCodec: Codec[TlvStream[FailureMessageTlv]] = TlvCodecs.tlvStream(discriminated[FailureMessageTlv].by(varint))

  val unknownFailureMessageCodec: Codec[UnknownFailureMessage] = uint16.xmap(
    code => {
      val failureMessage = code match {
        // @formatter:off
        case fc if (fc & PERM) != 0 && (fc & NODE) != 0 => new UnknownFailureMessage with Perm with Node { override lazy val code = fc }
        case fc if (fc & NODE) != 0 => new UnknownFailureMessage with Node { override lazy val code = fc }
        case fc if (fc & PERM) != 0 => new UnknownFailureMessage with Perm { override lazy val code = fc }
        case fc => new UnknownFailureMessage { override lazy val code  = fc }
        // @formatter:on
      }
      failureMessage
    },
    failure => failure.code
  )

  val failureMessageCodec = discriminatorWithDefault(
    discriminated[FailureMessage].by(uint16)
      .typecase(PERM | 1, failureTlvsCodec.as[InvalidRealm])
      .typecase(NODE | 2, failureTlvsCodec.as[TemporaryNodeFailure])
      .typecase(PERM | NODE | 2, failureTlvsCodec.as[PermanentNodeFailure])
      .typecase(PERM | NODE | 3, failureTlvsCodec.as[RequiredNodeFeatureMissing])
      .typecase(BADONION | PERM | 4, (sha256 :: failureTlvsCodec).as[InvalidOnionVersion])
      .typecase(BADONION | PERM | 5, (sha256 :: failureTlvsCodec).as[InvalidOnionHmac])
      .typecase(BADONION | PERM | 6, (sha256 :: failureTlvsCodec).as[InvalidOnionKey])
      .typecase(UPDATE | 7, (("channelUpdate" | channelUpdateWithLengthCodec) :: ("tlvs" | failureTlvsCodec)).as[TemporaryChannelFailure])
      .typecase(PERM | 8, failureTlvsCodec.as[PermanentChannelFailure])
      .typecase(PERM | 9, failureTlvsCodec.as[RequiredChannelFeatureMissing])
      .typecase(PERM | 10, failureTlvsCodec.as[UnknownNextPeer])
      .typecase(UPDATE | 11, (("amountMsat" | millisatoshi) :: ("channelUpdate" | channelUpdateWithLengthCodec) :: ("tlvs" | failureTlvsCodec)).as[AmountBelowMinimum])
      .typecase(UPDATE | 12, (("amountMsat" | millisatoshi) :: ("channelUpdate" | channelUpdateWithLengthCodec) :: ("tlvs" | failureTlvsCodec)).as[FeeInsufficient])
      .typecase(UPDATE | 13, (("expiry" | cltvExpiry) :: ("channelUpdate" | channelUpdateWithLengthCodec) :: ("tlvs" | failureTlvsCodec)).as[IncorrectCltvExpiry])
      .typecase(UPDATE | 14, (("channelUpdate" | channelUpdateWithLengthCodec) :: ("tlvs" | failureTlvsCodec)).as[ExpiryTooSoon])
      .typecase(UPDATE | 20, (messageFlagsCodec :: channelFlagsCodec :: ("channelUpdate" | channelUpdateWithLengthCodec) :: ("tlvs" | failureTlvsCodec)).as[ChannelDisabled])
      .typecase(PERM | 15, (("amountMsat" | withDefaultValue(optional(bitsRemaining, millisatoshi), 0 msat)) :: ("height" | withDefaultValue(optional(bitsRemaining, blockHeight), BlockHeight(0))) :: ("tlvs" | failureTlvsCodec)).as[IncorrectOrUnknownPaymentDetails])
      // PERM | 16 (incorrect_payment_amount) has been deprecated because it allowed probing attacks: IncorrectOrUnknownPaymentDetails should be used instead.
      // PERM | 17 (final_expiry_too_soon) has been deprecated because it allowed probing attacks: IncorrectOrUnknownPaymentDetails should be used instead.
      .typecase(18, (("expiry" | cltvExpiry) :: ("tlvs" | failureTlvsCodec)).as[FinalIncorrectCltvExpiry])
      .typecase(19, (("amountMsat" | millisatoshi) :: ("tlvs" | failureTlvsCodec)).as[FinalIncorrectHtlcAmount])
      .typecase(21, failureTlvsCodec.as[ExpiryTooFar])
      .typecase(PERM | 22, (("tag" | varint) :: ("offset" | uint16) :: ("tlvs" | failureTlvsCodec)).as[InvalidOnionPayload])
      .typecase(23, failureTlvsCodec.as[PaymentTimeout])
      .typecase(BADONION | PERM | 24, (sha256 :: failureTlvsCodec).as[InvalidOnionBlinding])
      // TODO: @t-bast: once fully spec-ed, these should probably include a NodeUpdate and use a different ID.
      // We should update Phoenix and our nodes at the same time, or first update Phoenix to understand both new and old errors.
      .typecase(NODE | 51, failureTlvsCodec.as[TrampolineFeeInsufficient])
      .typecase(NODE | 52, failureTlvsCodec.as[TrampolineExpiryTooSoon]),
    fallback = unknownFailureMessageCodec.upcast[FailureMessage]
  )

  private def failureOnionPayload(payloadAndPadLength: Int): Codec[FailureMessage] = Codec(
    encoder = f => variableSizeBytes(uint16, failureMessageCodec).encode(f).flatMap(bits => {
      val payloadLength = bits.bytes.length - 2
      val padLen = payloadAndPadLength - payloadLength
      variableSizeBytes(uint16, bytes).encode(ByteVector.fill(padLen)(0)).map(pad => bits ++ pad)
    }),
    // We accept payloads of any size in case more space is needed for future failure messages.
    decoder = bits => (
      ("failure" | variableSizeBytes(uint16, failureMessageCodec))
        :: ("padding" | variableSizeBytes(uint16, bytes))
      ).map(_.head).decode(bits)
  )

  /**
   * An onion-encrypted failure from an intermediate node:
   * +----------------+----------------------------------+-----------------+----------------------+-----+
   * | HMAC(32 bytes) | failure message length (2 bytes) | failure message | pad length (2 bytes) | pad |
   * +----------------+----------------------------------+-----------------+----------------------+-----+
   * Bolt 4: SHOULD set pad such that the failure_len plus pad_len is equal to 256: by always using the same size we
   * ensure error messages are indistinguishable.
   */
  def failureOnionCodec(mac: Mac32, payloadAndPadLength: Int = 256): Codec[FailureMessage] = CommonCodecs.prependmac(failureOnionPayload(payloadAndPadLength).complete, mac)

  /** Create a BadOnion failure matching the failure code provided. */
  def createBadOnionFailure(onionHash: ByteVector32, failureCode: Int): BadOnion = {
    if (failureCode == (BADONION | PERM | 4)) {
      InvalidOnionVersion(onionHash)
    } else if (failureCode == (BADONION | PERM | 5)) {
      InvalidOnionHmac(onionHash)
    } else if (failureCode == (BADONION | PERM | 6)) {
      InvalidOnionKey(onionHash)
    } else if (failureCode == (BADONION | PERM | 24)) {
      InvalidOnionBlinding(onionHash)
    } else {
      // unknown failure code, we default to a generic error
      InvalidOnionVersion(onionHash)
    }
  }

}
