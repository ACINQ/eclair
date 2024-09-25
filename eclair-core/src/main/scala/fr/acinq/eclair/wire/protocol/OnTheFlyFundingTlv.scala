/*
 * Copyright 2024 ACINQ SAS
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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.protocol.CommonCodecs.{publicKey, varint}
import fr.acinq.eclair.wire.protocol.TlvCodecs.tlvStream
import scodec.Codec
import scodec.bits.HexStringSyntax
import scodec.codecs._

/**
 * Created by t-bast on 07/06/2024.
 */

sealed trait WillAddHtlcTlv extends Tlv

object WillAddHtlcTlv {
  /** Blinding ephemeral public key that should be used to derive shared secrets when using route blinding. */
  case class BlindingPoint(publicKey: PublicKey) extends WillAddHtlcTlv

  private val blindingPoint: Codec[BlindingPoint] = (("length" | constant(hex"21")) :: ("blinding" | publicKey)).as[BlindingPoint]

  val willAddHtlcTlvCodec: Codec[TlvStream[WillAddHtlcTlv]] = tlvStream(discriminated[WillAddHtlcTlv].by(varint)
    .typecase(UInt64(0), blindingPoint)
  )
}
