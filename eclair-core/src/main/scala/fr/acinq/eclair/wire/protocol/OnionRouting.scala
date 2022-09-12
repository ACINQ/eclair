/*
 * Copyright 2021 ACINQ SAS
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
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.protocol.CommonCodecs.bytes32
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._

/**
 * Created by t-bast on 05/07/2019.
 */

case class OnionRoutingPacket(version: Int, publicKey: ByteVector, payload: ByteVector, hmac: ByteVector32)

object OnionRoutingCodecs {

  // @formatter:off
  sealed trait InvalidTlvPayload {
    def tag: UInt64
    def failureMessage: FailureMessage = InvalidOnionPayload(tag, 0)
  }
  case class MissingRequiredTlv(tag: UInt64) extends InvalidTlvPayload
  case class ForbiddenTlv(tag: UInt64) extends InvalidTlvPayload
  // @formatter:on

  def onionRoutingPacketCodec(payloadLength: Int): Codec[OnionRoutingPacket] = (
    ("version" | uint8) ::
      ("publicKey" | bytes(33)) ::
      ("onionPayload" | bytes(payloadLength)) ::
      ("hmac" | bytes32)).as[OnionRoutingPacket]

}
