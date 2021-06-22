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

import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.featuresCodec
import fr.acinq.eclair.wire.protocol.TlvCodecs.tlvStream
import fr.acinq.eclair.{Features, UInt64}
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._

sealed trait OpenChannelTlv extends Tlv

sealed trait AcceptChannelTlv extends Tlv

object ChannelTlv {

  /** Commitment to where the funds will go in case of a mutual close, which remote node will enforce in case we're compromised. */
  case class UpfrontShutdownScript(script: ByteVector) extends OpenChannelTlv with AcceptChannelTlv {
    val isEmpty: Boolean = script.isEmpty
  }

}

object OpenChannelTlv {

  import ChannelTlv._

  case class ChannelTypes(proposed: List[Features]) extends OpenChannelTlv

  val openTlvCodec: Codec[TlvStream[OpenChannelTlv]] = tlvStream(discriminated[OpenChannelTlv].by(varint)
    .typecase(UInt64(0), variableSizeBytesLong(varintoverflow, bytes).as[UpfrontShutdownScript])
    .typecase(UInt64(1), variableSizeBytesLong(varintoverflow, list(featuresCodec)).as[ChannelTypes])
  )

}

object AcceptChannelTlv {

  import ChannelTlv._

  case class ChannelType(features: Features) extends AcceptChannelTlv

  val acceptTlvCodec: Codec[TlvStream[AcceptChannelTlv]] = tlvStream(discriminated[AcceptChannelTlv].by(varint)
    .typecase(UInt64(0), variableSizeBytesLong(varintoverflow, bytes).as[UpfrontShutdownScript])
    .typecase(UInt64(1), variableSizeBytesLong(varintoverflow, featuresCodec).as[ChannelType])
  )

}