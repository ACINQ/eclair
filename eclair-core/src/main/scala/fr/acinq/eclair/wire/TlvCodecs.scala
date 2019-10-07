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
import fr.acinq.eclair.UInt64.Conversions._
import fr.acinq.eclair.wire.CommonCodecs._
import scodec.codecs._
import scodec.{Attempt, Codec, Err}

/**
  * Created by t-bast on 20/06/2019.
  */

object TlvCodecs {

  private def variableSizeUInt64(size: Int, min: UInt64): Codec[UInt64] = minimalvalue(bytes(size).xmap(UInt64(_), _.toByteVector.takeRight(size)), min)

  /**
    * Length-prefixed truncated uint64 (1 to 9 bytes unsigned integer).
    */
  val tu64: Codec[UInt64] = discriminated[UInt64].by(uint8)
    .\(0x00) { case i if i < 0x01 => i }(variableSizeUInt64(0, 0x00))
    .\(0x01) { case i if i < 0x0100 => i }(variableSizeUInt64(1, 0x01))
    .\(0x02) { case i if i < 0x010000 => i }(variableSizeUInt64(2, 0x0100))
    .\(0x03) { case i if i < 0x01000000 => i }(variableSizeUInt64(3, 0x010000))
    .\(0x04) { case i if i < 0x0100000000L => i }(variableSizeUInt64(4, 0x01000000))
    .\(0x05) { case i if i < 0x010000000000L => i }(variableSizeUInt64(5, 0x0100000000L))
    .\(0x06) { case i if i < 0x01000000000000L => i }(variableSizeUInt64(6, 0x010000000000L))
    .\(0x07) { case i if i < 0x0100000000000000L => i }(variableSizeUInt64(7, 0x01000000000000L))
    .\(0x08) { case i if i <= UInt64.MaxValue => i }(variableSizeUInt64(8, 0x0100000000000000L))

  /**
    * Length-prefixed truncated long (1 to 9 bytes unsigned integer).
    * This codec can be safely used for values < `2^63` and will fail otherwise.
    */
  val tu64overflow: Codec[Long] = tu64.exmap(
    u => if (u <= Long.MaxValue) Attempt.Successful(u.toBigInt.toLong) else Attempt.Failure(Err(s"overflow for value $u")),
    l => if (l >= 0) Attempt.Successful(UInt64(l)) else Attempt.Failure(Err(s"uint64 must be positive (actual=$l)")))

  /**
    * Length-prefixed truncated uint32 (1 to 5 bytes unsigned integer).
    */
  val tu32: Codec[Long] = tu64.exmap({
    case i if i > 0xffffffffL => Attempt.Failure(Err("tu32 overflow"))
    case i => Attempt.Successful(i.toBigInt.toLong)
  }, l => Attempt.Successful(l))

  /**
    * Length-prefixed truncated uint16 (1 to 3 bytes unsigned integer).
    */
  val tu16: Codec[Int] = tu32.exmap({
    case i if i > 0xffff => Attempt.Failure(Err("tu16 overflow"))
    case i => Attempt.Successful(i.toInt)
  }, l => Attempt.Successful(l))

  private def validateGenericTlv(g: GenericTlv): Attempt[GenericTlv] = {
    if (g.tag.toBigInt % 2 == 0) {
      Attempt.Failure(Err("unknown even tlv type"))
    } else {
      Attempt.Successful(g)
    }
  }

  private val genericTlv: Codec[GenericTlv] = (("tag" | varint) :: variableSizeBytesLong(varintoverflow, bytes)).as[GenericTlv].exmap(validateGenericTlv, validateGenericTlv)

  private def tag[T <: Tlv](codec: DiscriminatorCodec[T, UInt64], record: Either[GenericTlv, T]): UInt64 = record match {
    case Left(generic) => generic.tag
    case Right(tlv) => tag(codec, tlv)
  }

  private def tag[T <: Tlv](codec: DiscriminatorCodec[T, UInt64], record: T): UInt64 =
    codec.encode(record).flatMap(bits => varint.decode(bits)).require.value

  private def validateStream[T <: Tlv](codec: DiscriminatorCodec[T, UInt64], records: List[Either[GenericTlv, T]]): Attempt[TlvStream[T]] = {
    val tags = records.map(r => tag(codec, r))
    if (tags.length != tags.distinct.length) {
      Attempt.Failure(Err("tlv streams must not contain duplicate records"))
    } else if (tags != tags.sorted) {
      Attempt.Failure(Err("tlv records must be ordered by monotonically-increasing types"))
    } else {
      Attempt.Successful(TlvStream(records.collect { case Right(tlv) => tlv }, records.collect { case Left(generic) => generic }))
    }
  }

  /**
    * A tlv stream codec relies on an underlying tlv codec.
    * This allows tlv streams to have different namespaces, increasing the total number of tlv types available.
    *
    * @param codec codec used for the tlv records contained in the stream.
    * @tparam T stream namespace.
    */
  def tlvStream[T <: Tlv](codec: DiscriminatorCodec[T, UInt64]): Codec[TlvStream[T]] = list(discriminatorFallback(genericTlv, codec)).exmap(
    records => validateStream(codec, records),
    (stream: TlvStream[T]) => {
      val records = (stream.records.map(Right(_)) ++ stream.unknown.map(Left(_))).toList
      val tags = records.map(r => tag(codec, r))
      if (tags.length != tags.distinct.length) {
        Attempt.Failure(Err("tlv streams must not contain duplicate records"))
      } else {
        Attempt.Successful(tags.zip(records).sortBy(_._1).map(_._2))
      }
    }
  )

  /**
    * When used inside a message, most of the time a tlv stream needs to specify its length.
    * Note that some messages will have an independent length field and won't need this codec.
    *
    * @param codec codec used for the tlv records contained in the stream.
    * @tparam T stream namespace.
    */
  def lengthPrefixedTlvStream[T <: Tlv](codec: DiscriminatorCodec[T, UInt64]): Codec[TlvStream[T]] = variableSizeBytesLong(CommonCodecs.varintoverflow, tlvStream(codec))

}
