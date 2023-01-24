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

import fr.acinq.bitcoin.scalacompat.Satoshi
import fr.acinq.eclair.UInt64.Conversions._
import fr.acinq.eclair.wire.protocol.CommonCodecs.{minimalvalue, uint64, varint, varintoverflow}
import fr.acinq.eclair.{MilliSatoshi, UInt64}
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.{Attempt, Codec, Err}

/**
 * Created by t-bast on 20/06/2019.
 */
object TlvCodecs {
  // high range types are greater than or equal 2^16, see https://github.com/lightningnetwork/lightning-rfc/blob/master/01-messaging.md#type-length-value-format
  private val TLV_TYPE_HIGH_RANGE = 65536

  /**
   * Truncated uint64 (0 to 8 bytes unsigned integer).
   * The encoder minimally-encodes every value, and the decoder verifies that values are minimally-encoded.
   * Note that this codec can only be used at the very end of a TLV record.
   */
  val tu64: Codec[UInt64] = Codec(
    (u: UInt64) => {
      val b = (u: @unchecked) match {
        case u if u < 0x01 => ByteVector.empty
        case u if u < 0x0100 => u.toByteVector.takeRight(1)
        case u if u < 0x010000 => u.toByteVector.takeRight(2)
        case u if u < 0x01000000 => u.toByteVector.takeRight(3)
        case u if u < 0x0100000000L => u.toByteVector.takeRight(4)
        case u if u < 0x010000000000L => u.toByteVector.takeRight(5)
        case u if u < 0x01000000000000L => u.toByteVector.takeRight(6)
        case u if u < 0x0100000000000000L => u.toByteVector.takeRight(7)
        case u if u <= UInt64.MaxValue => u.toByteVector.takeRight(8)
      }
      Attempt.successful(b.bits)
    },
    b => b.length match {
      case l if l <= 0 => minimalvalue(uint64, UInt64(0x00)).decode(b.padLeft(64))
      case l if l <= 8 => minimalvalue(uint64, UInt64(0x01)).decode(b.padLeft(64))
      case l if l <= 16 => minimalvalue(uint64, UInt64(0x0100)).decode(b.padLeft(64))
      case l if l <= 24 => minimalvalue(uint64, UInt64(0x010000)).decode(b.padLeft(64))
      case l if l <= 32 => minimalvalue(uint64, UInt64(0x01000000)).decode(b.padLeft(64))
      case l if l <= 40 => minimalvalue(uint64, UInt64(0x0100000000L)).decode(b.padLeft(64))
      case l if l <= 48 => minimalvalue(uint64, UInt64(0x010000000000L)).decode(b.padLeft(64))
      case l if l <= 56 => minimalvalue(uint64, UInt64(0x01000000000000L)).decode(b.padLeft(64))
      case l if l <= 64 => minimalvalue(uint64, UInt64(0x0100000000000000L)).decode(b.padLeft(64))
      case _ => Attempt.failure(Err(s"too many bytes to decode for truncated uint64 (${b.toHex})"))
    })

  /**
   * Truncated long (0 to 8 bytes unsigned integer).
   * This codec can be safely used for values < `2^63` and will fail otherwise.
   */
  val tu64overflow: Codec[Long] = tu64.exmap(
    u => if (u <= Long.MaxValue) Attempt.Successful(u.toBigInt.toLong) else Attempt.Failure(Err(s"overflow for value $u")),
    l => if (l >= 0) Attempt.Successful(UInt64(l)) else Attempt.Failure(Err(s"uint64 must be positive (actual=$l)")))

  /** Truncated uint32 (0 to 4 bytes unsigned integer). */
  val tu32: Codec[Long] = tu64.exmap({
    case i if i > 0xffffffffL => Attempt.Failure(Err("tu32 overflow"))
    case i => Attempt.Successful(i.toBigInt.toLong)
  }, l => Attempt.Successful(l))

  /** Truncated uint16 (0 to 2 bytes unsigned integer). */
  val tu16: Codec[Int] = tu32.exmap({
    case i if i > 0xffff => Attempt.Failure(Err("tu16 overflow"))
    case i => Attempt.Successful(i.toInt)
  }, l => Attempt.Successful(l))

  /**
   * Truncated millisatoshi (0 to 8 bytes unsigned).
   * This codec can be safely used for values < `2^63` and will fail otherwise.
   */
  val tmillisatoshi: Codec[MilliSatoshi] = tu64overflow.xmap(l => MilliSatoshi(l), m => m.toLong)

  /**
   * Truncated millisatoshi (0 to 4 bytes unsigned).
   */
  val tmillisatoshi32: Codec[MilliSatoshi] = tu32.xmap(l => MilliSatoshi(l), m => m.toLong)

  /** Truncated satoshi (0 to 8 bytes unsigned). */
  val tsatoshi: Codec[Satoshi] = tu64overflow.xmap(l => Satoshi(l), s => s.toLong)

  private def validateUnknownTlv(g: GenericTlv): Attempt[GenericTlv] = {
    if (g.tag < TLV_TYPE_HIGH_RANGE && g.tag.toBigInt % 2 == 0) {
      Attempt.Failure(Err("unknown even tlv type"))
    } else {
      Attempt.Successful(g)
    }
  }

  /** Codec for a tlv field that contains the field length and its value, without its tag. */
  def tlvField[T <: Tlv, A](valueCodec: Codec[A])(implicit as: scodec.Transformer[A, T]): Codec[T] = variableSizeBytesLong(varintoverflow, valueCodec.as[T].complete)

  /** Codec for a tlv field that has a known, fixed length. */
  def fixedLengthTlvField[T <: Tlv, A](length: Long, valueCodec: Codec[A])(implicit as: scodec.Transformer[A, T]): Codec[T] = ("length" | constant(varintoverflow.encode(length).require)) ~> ("value" | valueCodec.as[T])

  val genericTlv: Codec[GenericTlv] = (("tag" | varint) :: variableSizeBytesLong(varintoverflow, bytes)).as[GenericTlv]

  private val unknownTlv = genericTlv.exmap[GenericTlv](validateUnknownTlv, validateUnknownTlv)

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
      Attempt.Successful(TlvStream(records.collect { case Right(tlv) => tlv }.toSet, records.collect { case Left(generic) => generic }.toSet))
    }
  }

  /**
   * A tlv stream codec relies on an underlying tlv codec.
   * This allows tlv streams to have different namespaces, increasing the total number of tlv types available.
   *
   * @param codec codec used for the tlv records contained in the stream.
   * @tparam T stream namespace.
   */
  def tlvStream[T <: Tlv](codec: DiscriminatorCodec[T, UInt64]): Codec[TlvStream[T]] = list(discriminatorFallback(unknownTlv, codec)).exmap(
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
