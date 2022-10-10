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
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.TlvCodecs.{tlvField, tlvStream}
import scodec.Codec
import scodec.codecs.{discriminated, list}

/**
 * Created by t-bast on 13/12/2019.
 */

/** Tlv types used inside Init messages. */
sealed trait InitTlv extends Tlv

object InitTlv {

  /** The chains the node is interested in. */
  case class Networks(chainHashes: List[ByteVector32]) extends InitTlv

  /**
   * When receiving an incoming connection, we can send back the public address our peer is connecting from.
   * This lets our peer discover if its public IP has changed from within its local network.
   */
  case class RemoteAddress(address: NodeAddress) extends InitTlv

}

object InitTlvCodecs {

  import InitTlv._

  private val networks: Codec[Networks] = tlvField(list(bytes32))
  private val remoteAddress: Codec[RemoteAddress] = tlvField(nodeaddress)

  val initTlvCodec = tlvStream(discriminated[InitTlv].by(varint)
    .typecase(UInt64(1), networks)
    .typecase(UInt64(3), remoteAddress)
  )

}

sealed trait WarningTlv extends Tlv

object WarningTlv {
  val warningTlvCodec: Codec[TlvStream[WarningTlv]] = tlvStream(discriminated[WarningTlv].by(varint))
}

sealed trait ErrorTlv extends Tlv

object ErrorTlv {
  val errorTlvCodec: Codec[TlvStream[ErrorTlv]] = tlvStream(discriminated[ErrorTlv].by(varint))
}

sealed trait PingTlv extends Tlv

object PingTlv {
  val pingTlvCodec: Codec[TlvStream[PingTlv]] = tlvStream(discriminated[PingTlv].by(varint))
}

sealed trait PongTlv extends Tlv

object PongTlv {
  val pongTlvCodec: Codec[TlvStream[PongTlv]] = tlvStream(discriminated[PongTlv].by(varint))
}