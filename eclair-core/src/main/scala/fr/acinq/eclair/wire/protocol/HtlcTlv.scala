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

import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.TlvCodecs.tlvStream
import scodec.Codec
import scodec.bits.ByteVector
import scodec.codecs._

sealed trait HtlcTlv extends Tlv

object HtlcTlv {
  case class Data(data: ByteVector) extends HtlcTlv

  val codec: Codec[TlvStream[HtlcTlv]] = tlvStream(
    discriminated[HtlcTlv].by(varint)
      .typecase(UInt64(0), variableSizeBytesLong(varintoverflow, bytes).as[Data])
  )
}

