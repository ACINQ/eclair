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
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.crypto.Sphinx
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._

/**
  * Created by t-bast on 05/07/2019.
  */

case class OnionPacket(version: Int,
                       publicKey: ByteVector,
                       payload: ByteVector,
                       hmac: ByteVector32)

case class PerHopPayload(shortChannelId: ShortChannelId,
                         amtToForward: Long,
                         outgoingCltvValue: Long)

object OnionCodecs {

  def onionPacketCodec(payloadLength: Int): Codec[OnionPacket] = (
    ("version" | uint8) ::
      ("publicKey" | bytes(33)) ::
      ("onionPayload" | bytes(payloadLength)) ::
      ("hmac" | CommonCodecs.bytes32)).as[OnionPacket]

  val paymentOnionPacketCodec: Codec[OnionPacket] = onionPacketCodec(Sphinx.PaymentPacket.PayloadLength)

  val perHopPayloadCodec: Codec[PerHopPayload] = (
    ("realm" | constant(ByteVector.fromByte(0))) ::
      ("short_channel_id" | CommonCodecs.shortchannelid) ::
      ("amt_to_forward" | CommonCodecs.uint64overflow) ::
      ("outgoing_cltv_value" | uint32) ::
      ("unused_with_v0_version_on_header" | ignore(8 * 12))).as[PerHopPayload]

}