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
import scodec.{Attempt, Codec, DecodeResult, Decoder, Encoder}
import scodec.bits.BitVector
import scodec.codecs._

import scala.collection.compat._

/**
  * Created by t-bast on 20/06/2019.
  */

object TlvCodecs {

  val genericTlv: Codec[GenericTlv] = (("type" | varint) :: variableSizeBytesLong(varlong, bytes)).as[GenericTlv]

  def tlvFallback(codec: Codec[Tlv]): Codec[Tlv] = discriminatorFallback(genericTlv, codec).xmap(_ match {
    case Left(l) => l
    case Right(r) => r
  }, _ match {
    case g: GenericTlv => Left(g)
    case o => Right(o)
  })

  /**
    * A tlv stream codec relies on an underlying tlv codec.
    * This allows tlv streams to have different namespaces, increasing the total number of tlv types available.
    *
    * @param codec codec used for the tlv records contained in the stream.
    */
  def tlvStream(codec: Codec[Tlv]): Codec[TlvStream] = {
    Codec[TlvStream](
      (s: TlvStream) => {
        val recordTypes = s.records.map(_.`type`)
        if (recordTypes.length != recordTypes.distinct.length) {
          Attempt.Failure(scodec.Err("duplicate tlv records aren't allowed"))
        } else {
          Encoder.encodeSeq(codec)(s.records.sortBy(_.`type`).toList)
        }
      },
      (buf: BitVector) => {
        Decoder.decodeCollect[List, Tlv](codec, None)(buf).map(_.map(TlvStream(_))) match {
          case Attempt.Failure(err) => Attempt.Failure(err)
          case Attempt.Successful(res@DecodeResult(stream, _)) => stream.validate match {
            case None => Attempt.Successful(res)
            case Some(err) => Attempt.Failure(scodec.Err(err.message))
          }
        }
      }
    )
  }

}
