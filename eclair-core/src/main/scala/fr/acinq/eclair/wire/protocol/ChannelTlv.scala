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

import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.TlvCodecs.tlvStream
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

  val openTlvCodec: Codec[TlvStream[OpenChannelTlv]] = tlvStream(discriminated[OpenChannelTlv].by(varint)
    .typecase(UInt64(0), variableSizeBytesLong(varintoverflow, bytes).as[UpfrontShutdownScript])
  )

}

object AcceptChannelTlv {

  import ChannelTlv._

  val acceptTlvCodec: Codec[TlvStream[AcceptChannelTlv]] = tlvStream(discriminated[AcceptChannelTlv].by(varint)
    .typecase(UInt64(0), variableSizeBytesLong(varintoverflow, bytes).as[UpfrontShutdownScript])
  )

}

sealed trait ClosingSignedTlv extends Tlv

object ClosingSignedTlv {

  case class FeeRange(min: Satoshi, max: Satoshi) extends ClosingSignedTlv

  private val feeRange: Codec[FeeRange] = (("min_fee_satoshis" | satoshi) :: ("max_fee_satoshis" | satoshi)).as[FeeRange]

  val closingSignedTlvCodec: Codec[TlvStream[ClosingSignedTlv]] = tlvStream(discriminated[ClosingSignedTlv].by(varint)
    .typecase(UInt64(1), variableSizeBytesLong(varintoverflow, feeRange))
  )

}
