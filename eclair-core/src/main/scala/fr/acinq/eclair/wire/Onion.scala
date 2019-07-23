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
import fr.acinq.eclair.wire.OnionTlv._
import fr.acinq.eclair.wire.TlvCodecs._
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, ShortChannelId, UInt64}
import scodec.bits.{BitVector, ByteVector, HexStringSyntax}
import scodec.codecs._
import scodec.{Codec, DecodeResult, Decoder}

/**
 * Created by t-bast on 05/07/2019.
 */

/**
 * Tlv types used inside onion messages.
 */
sealed trait OnionTlv extends Tlv

case class OnionRoutingPacket(version: Int,
                              publicKey: ByteVector,
                              payload: ByteVector,
                              hmac: ByteVector32)

case class OnionForwardInfo(shortChannelId: ShortChannelId,
                            amtToForward: MilliSatoshi,
                            outgoingCltvValue: CltvExpiry)

case class OnionPaymentInfo(amount: MilliSatoshi, cltvExpiry: CltvExpiry)

case class OnionPerHopPayload(payload: Either[TlvStream[OnionTlv], OnionForwardInfo]) {

  lazy val paymentInfo: Option[OnionPaymentInfo] = payload match {
    case Right(OnionForwardInfo(_, amount, cltv)) => Some(OnionPaymentInfo(amount, cltv))
    case Left(tlv) => for {
      amount <- tlv.get[AmountToForward].map(_.amount)
      cltv <- tlv.get[OutgoingCltv].map(_.cltv)
    } yield OnionPaymentInfo(amount, cltv)
  }

  lazy val forwardInfo: Option[OnionForwardInfo] = payload match {
    case Right(onionForwardInfo) => Some(onionForwardInfo)
    case Left(tlv) => for {
      shortChannelId <- tlv.get[OutgoingChannelId].map(_.shortChannelId)
      amount <- tlv.get[AmountToForward].map(_.amount)
      cltv <- tlv.get[OutgoingCltv].map(_.cltv)
    } yield OnionForwardInfo(shortChannelId, amount, cltv)
  }

}

object OnionPerHopPayload {

  // @formatter:off
  implicit def legacyToPerHopPayload(legacy: OnionForwardInfo): OnionPerHopPayload = OnionPerHopPayload(Right(legacy))
  implicit def tlvToPerHopPayload(tlv: TlvStream[OnionTlv]): OnionPerHopPayload = OnionPerHopPayload(Left(tlv))
  // @formatter:on

}

object OnionTlv {

  /**
   * Amount to forward to the next node.
   */
  case class AmountToForward(amount: MilliSatoshi) extends OnionTlv

  /**
   * CLTV value to use for the HTLC offered to the next node.
   */
  case class OutgoingCltv(cltv: CltvExpiry) extends OnionTlv

  /**
   * Id of the channel to use to forward a payment to the next node.
   */
  case class OutgoingChannelId(shortChannelId: ShortChannelId) extends OnionTlv

}

object OnionCodecs {

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

  val legacyPerHopPayloadCodec: Codec[OnionForwardInfo] = (
    ("realm" | constant(ByteVector.fromByte(0))) ::
      ("short_channel_id" | shortchannelid) ::
      ("amt_to_forward" | millisatoshi) ::
      ("outgoing_cltv_value" | cltvExpiry) ::
      ("unused_with_v0_version_on_header" | ignore(8 * 12))).as[OnionForwardInfo]

  val perHopPayloadCodec: Codec[OnionPerHopPayload] = fallback(tlvPerHopPayloadCodec, legacyPerHopPayloadCodec).as[OnionPerHopPayload]

}