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

import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.CommonCodecs._
import fr.acinq.eclair.wire.TlvCodecs.tlvStream
import scodec.Codec
import scodec.codecs._

sealed trait OpenTlv extends Tlv

object OpenTlv {

  // Phoenix versions <= 1.1.0 expect a TLV stream for pay-to-open, but no upfront_shutdown_script. This makes them
  // incompatible with the latest update to the encoding of OpenChannel, so we discriminate them and send them the old
  // encoding. We should remove that work-around as soon as enough users have updated to > 1.1.0.
  case class Encoding(useLegacy: Boolean) extends OpenTlv

  val openTlvCodec: Codec[TlvStream[OpenTlv]] = tlvStream(discriminated.by(varint)
    .typecase(UInt64(0x47000001), variableSizeBytesLong(varintoverflow, bool).as[Encoding])
  )

}