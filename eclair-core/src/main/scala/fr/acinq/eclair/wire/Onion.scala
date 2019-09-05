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
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.TlvCodecs._
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, ShortChannelId, UInt64}
import scodec.bits.{BitVector, ByteVector, HexStringSyntax}

/**
 * Created by t-bast on 05/07/2019.
 */

case class OnionRoutingPacket(version: Int, publicKey: ByteVector, payload: ByteVector, hmac: ByteVector32)

/** Tlv types used inside onion messages. */
sealed trait OnionTlv extends Tlv

object OnionTlv {

  /** Amount to forward to the next node. */
  case class AmountToForward(amount: MilliSatoshi) extends OnionTlv

  /** CLTV value to use for the HTLC offered to the next node. */
  case class OutgoingCltv(cltv: CltvExpiry) extends OnionTlv

  /** Id of the channel to use to forward a payment to the next node. */
  case class OutgoingChannelId(shortChannelId: ShortChannelId) extends OnionTlv

}

object Onion {

  import OnionTlv._

  sealed trait PerHopPayloadFormat

  /** Legacy fixed-size 65-bytes onion payload. */
  sealed trait LegacyFormat extends PerHopPayloadFormat

  /** Variable-length onion payload with optional additional tlv records. */
  sealed trait TlvFormat extends PerHopPayloadFormat {
    def records: TlvStream[OnionTlv]
  }

  /** Per-hop payload from an HTLC's payment onion (after decryption and decoding). */
  sealed trait PerHopPayload

  /** Per-hop payload for an intermediate node. */
  sealed trait RelayPayload extends PerHopPayload with PerHopPayloadFormat {
    /** Amount to forward to the next node. */
    val amountToForward: MilliSatoshi
    /** CLTV value to use for the HTLC offered to the next node. */
    val outgoingCltv: CltvExpiry
    /** Id of the channel to use to forward a payment to the next node. */
    val outgoingChannelId: ShortChannelId
  }

  /** Per-hop payload for a final node. */
  sealed trait FinalPayload extends PerHopPayload with PerHopPayloadFormat {
    val amount: MilliSatoshi
    val expiry: CltvExpiry
  }

  case class RelayLegacyPayload(outgoingChannelId: ShortChannelId, amountToForward: MilliSatoshi, outgoingCltv: CltvExpiry) extends RelayPayload with LegacyFormat

  case class FinalLegacyPayload(amount: MilliSatoshi, expiry: CltvExpiry) extends FinalPayload with LegacyFormat

  case class RelayTlvPayload(records: TlvStream[OnionTlv]) extends RelayPayload with TlvFormat {
    override val amountToForward = records.get[AmountToForward].get.amount
    override val outgoingCltv = records.get[OutgoingCltv].get.cltv
    override val outgoingChannelId = records.get[OutgoingChannelId].get.shortChannelId
  }

  case class FinalTlvPayload(records: TlvStream[OnionTlv]) extends FinalPayload with TlvFormat {
    override val amount = records.get[AmountToForward].get.amount
    override val expiry = records.get[OutgoingCltv].get.cltv
  }

}

object OnionCodecs {

  import Onion._
  import OnionTlv._
  import scodec.codecs._
  import scodec.{Attempt, Codec, DecodeResult, Decoder, Err}

  def onionRoutingPacketCodec(payloadLength: Int): Codec[OnionRoutingPacket] = (
    ("version" | uint8) ::
      ("publicKey" | bytes(33)) ::
      ("onionPayload" | bytes(payloadLength)) ::
      ("hmac" | bytes32)).as[OnionRoutingPacket]

  val paymentOnionPacketCodec: Codec[OnionRoutingPacket] = onionRoutingPacketCodec(Sphinx.PaymentPacket.PayloadLength)

  /**
   * The 1.1 BOLT spec changed the onion frame format to use variable-length per-hop payloads.
   * The first bytes contain a varint encoding the length of the payload data (not including the trailing mac).
   * That varint is considered to be part of the payload, so the payload length includes the number of bytes used by
   * the varint prefix.
   */
  val payloadLengthDecoder = Decoder[Long]((bits: BitVector) =>
    varintoverflow.decode(bits).map(d => DecodeResult(d.value + (bits.length - d.remainder.length) / 8, d.remainder)))

  private val amountToForward: Codec[AmountToForward] = ("amount_msat" | tu64overflow).xmap(amountMsat => AmountToForward(MilliSatoshi(amountMsat)), (a: AmountToForward) => a.amount.toLong)

  private val outgoingCltv: Codec[OutgoingCltv] = ("cltv" | tu32).xmap(cltv => OutgoingCltv(CltvExpiry(cltv)), (c: OutgoingCltv) => c.cltv.toLong)

  private val outgoingChannelId: Codec[OutgoingChannelId] = (("length" | constant(hex"08")) :: ("short_channel_id" | shortchannelid)).as[OutgoingChannelId]

  private val onionTlvCodec = discriminated[OnionTlv].by(varint)
    .typecase(UInt64(2), amountToForward)
    .typecase(UInt64(4), outgoingCltv)
    .typecase(UInt64(6), outgoingChannelId)

  val tlvPerHopPayloadCodec: Codec[TlvStream[OnionTlv]] = TlvCodecs.lengthPrefixedTlvStream[OnionTlv](onionTlvCodec).complete

  private val legacyRelayPerHopPayloadCodec: Codec[RelayLegacyPayload] = (
    ("realm" | constant(ByteVector.fromByte(0))) ::
      ("short_channel_id" | shortchannelid) ::
      ("amt_to_forward" | millisatoshi) ::
      ("outgoing_cltv_value" | cltvExpiry) ::
      ("unused_with_v0_version_on_header" | ignore(8 * 12))).as[RelayLegacyPayload]

  private val legacyFinalPerHopPayloadCodec: Codec[FinalLegacyPayload] = (
    ("realm" | constant(ByteVector.fromByte(0))) ::
      ("short_channel_id" | ignore(8 * 8)) ::
      ("amount" | millisatoshi) ::
      ("expiry" | cltvExpiry) ::
      ("unused_with_v0_version_on_header" | ignore(8 * 12))).as[FinalLegacyPayload]

  case class MissingRequiredTlv(tag: UInt64) extends Err {
    // @formatter:off
    val failureMessage: FailureMessage = InvalidOnionPayload(tag, 0)
    override def message = failureMessage.message
    override def context: List[String] = Nil
    override def pushContext(ctx: String): Err = this
    // @formatter:on
  }

  val relayPerHopPayloadCodec: Codec[RelayPayload] = fallback(tlvPerHopPayloadCodec, legacyRelayPerHopPayloadCodec).narrow({
    case Left(tlvs) if tlvs.get[AmountToForward].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(2)))
    case Left(tlvs) if tlvs.get[OutgoingCltv].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(4)))
    case Left(tlvs) if tlvs.get[OutgoingChannelId].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(6)))
    case Left(tlvs) => Attempt.successful(RelayTlvPayload(tlvs))
    case Right(legacy) => Attempt.successful(legacy)
  }, {
    case legacy: RelayLegacyPayload => Right(legacy)
    case RelayTlvPayload(tlvs) => Left(tlvs)
  })

  val finalPerHopPayloadCodec: Codec[FinalPayload] = fallback(tlvPerHopPayloadCodec, legacyFinalPerHopPayloadCodec).narrow({
    case Left(tlvs) if tlvs.get[AmountToForward].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(2)))
    case Left(tlvs) if tlvs.get[OutgoingCltv].isEmpty => Attempt.failure(MissingRequiredTlv(UInt64(4)))
    case Left(tlvs) => Attempt.successful(FinalTlvPayload(tlvs))
    case Right(legacy) => Attempt.successful(legacy)
  }, {
    case legacy: FinalLegacyPayload => Right(legacy)
    case FinalTlvPayload(tlvs) => Left(tlvs)
  })

}