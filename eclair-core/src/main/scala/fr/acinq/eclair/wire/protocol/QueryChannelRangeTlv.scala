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
import fr.acinq.eclair.wire.protocol.CommonCodecs.{varint, varintoverflow}
import scodec.Codec
import scodec.codecs._

sealed trait QueryChannelRangeTlv extends Tlv

object QueryChannelRangeTlv {
  /**
    * Optional query flag that is appended to QueryChannelRange
    * @param flag bit 1 set means I want timestamps, bit 2 set means I want checksums
    */
  case class QueryFlags(flag: Long) extends QueryChannelRangeTlv {
    val wantTimestamps = QueryFlags.wantTimestamps(flag)

    val wantChecksums = QueryFlags.wantChecksums(flag)
  }

  case object QueryFlags {
    val WANT_TIMESTAMPS: Long = 1
    val WANT_CHECKSUMS: Long = 2
    val WANT_ALL: Long = (WANT_TIMESTAMPS | WANT_CHECKSUMS)

    def wantTimestamps(flag: Long) = (flag & WANT_TIMESTAMPS) != 0

    def wantChecksums(flag: Long) = (flag & WANT_CHECKSUMS) != 0
  }

  val queryFlagsCodec: Codec[QueryFlags] = Codec(("flag" | varintoverflow)).as[QueryFlags]

  val codec: Codec[TlvStream[QueryChannelRangeTlv]] = TlvCodecs.tlvStream(discriminated.by(varint)
    .typecase(UInt64(1), variableSizeBytesLong(varintoverflow, queryFlagsCodec))
  )

}
