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

import fr.acinq.eclair.wire.CommonCodecs._
import scodec.{Attempt, Codec}
import scodec.codecs._

import scala.util.Try

/**
  * Created by t-bast on 20/06/2019.
  */

object TlvCodecs {

  private val genericTlv: Codec[GenericTlv] = (("type" | varint) :: variableSizeBytesLong(varintoverflow, bytes)).as[GenericTlv]

  private def tlvFallback(codec: Codec[Tlv]): Codec[Tlv] = discriminatorFallback(genericTlv, codec).xmap({
    case Left(l) => l
    case Right(r) => r
  }, {
    case g: GenericTlv => Left(g)
    case o => Right(o)
  })

  /**
    * A tlv stream codec relies on an underlying tlv codec.
    * This allows tlv streams to have different namespaces, increasing the total number of tlv types available.
    *
    * @param codec codec used for the tlv records contained in the stream.
    */
  def tlvStream(codec: Codec[Tlv]): Codec[TlvStream] = list(tlvFallback(codec)).exmap(
    records => Attempt.fromTry(Try(TlvStream(records))),
    stream => Attempt.successful(stream.records.toList)
  )

  /**
    * When used inside a message, a tlv stream needs to specify its length.
    * Note that some messages will have an independent length field and won't need this codec.
    */
  def lengthPrefixedTlvStream(codec: Codec[Tlv]): Codec[TlvStream] = variableSizeBytesLong(CommonCodecs.varintoverflow, tlvStream(codec))

}
