package fr.acinq.eclair.wire

import fr.acinq.eclair.{UInt64, wire}
import fr.acinq.eclair.wire.CommonCodecs.{varint, varintoverflow}
import scodec.Codec
import scodec.codecs._

sealed trait ReplyChannelRangeTlv extends Tlv

object ReplyChannelRangeTlv {

  /**
    *
    * @param timestamp1 timestamp for node 1, or 0
    * @param timestamp2 timestamp for node 2, or 0
    */
  case class Timestamps(timestamp1: Long, timestamp2: Long)

  /**
    * Optional timestamps TLV that can be appended to ReplyChannelRange
    *
    * @param encoding same convention as for short channel ids
    * @param timestamps
    */
  case class EncodedTimestamps(encoding: EncodingType, timestamps: List[Timestamps]) extends ReplyChannelRangeTlv

  /**
    *
    * @param checksum1 checksum for node 1, or 0
    * @param checksum2 checksum for node 2, or 0
    */
  case class Checksums(checksum1: Long, checksum2: Long)

  /**
    * Optional checksums TLV that can be appended to ReplyChannelRange
    *
    * @param checksums
    */
  case class EncodedChecksums(checksums: List[Checksums]) extends ReplyChannelRangeTlv

  val timestampsCodec: Codec[Timestamps] = (
    ("timestamp1" | uint32) ::
      ("timestamp2" | uint32)
    ).as[Timestamps]

  val encodedTimestampsCodec: Codec[EncodedTimestamps] = variableSizeBytesLong(varintoverflow,
    discriminated[EncodedTimestamps].by(byte)
      .\(0) { case a@EncodedTimestamps(EncodingType.UNCOMPRESSED, _) => a }((provide[EncodingType](EncodingType.UNCOMPRESSED) :: list(timestampsCodec)).as[EncodedTimestamps])
      .\(1) { case a@EncodedTimestamps(EncodingType.COMPRESSED_ZLIB, _) => a }((provide[EncodingType](EncodingType.COMPRESSED_ZLIB) :: zlib(list(timestampsCodec))).as[EncodedTimestamps])
  )

  val checksumsCodec: Codec[Checksums] = (
    ("checksum1" | uint32) ::
      ("checksum2" | uint32)
    ).as[Checksums]

  val encodedChecksumsCodec: Codec[EncodedChecksums] = variableSizeBytesLong(varintoverflow, list(checksumsCodec)).as[EncodedChecksums]

  val innerCodec = discriminated[ReplyChannelRangeTlv].by(varint)
    .typecase(UInt64(1), encodedTimestampsCodec)
    .typecase(UInt64(3), encodedChecksumsCodec)

  val codec: Codec[TlvStream[ReplyChannelRangeTlv]] = TlvCodecs.tlvStream(innerCodec)
}
