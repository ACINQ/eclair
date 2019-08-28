package fr.acinq.eclair.wire

import fr.acinq.eclair.UInt64
import fr.acinq.eclair.wire.CommonCodecs.{varint, varintoverflow}
import scodec.Codec
import scodec.codecs.{byte, discriminated, list, provide, variableSizeBytesLong, zlib}

sealed trait QueryShortChannelIdsTlv extends Tlv

object QueryShortChannelIdsTlv {

  /**
    * Optional TLV-based query message that can be appended to QueryShortChannelIds
    * @param encoding 0 means uncompressed, 1 means compressed with zlib
    * @param array array of query flags, each flags specifies the info we want for a given channel
    */
  case class EncodedQueryFlags(encoding: EncodingType, array: List[Long]) extends QueryShortChannelIdsTlv

  case object QueryFlagType {
    val INCLUDE_CHANNEL_ANNOUNCEMENT: Long = 1
    val INCLUDE_CHANNEL_UPDATE_1: Long = 2
    val INCLUDE_CHANNEL_UPDATE_2: Long = 4
    val INCLUDE_NODE_ANNOUNCEMENT_1: Long = 8
    val INCLUDE_NODE_ANNOUNCEMENT_2: Long = 16

    def includeChannelAnnouncement(flag: Long) = (flag & INCLUDE_CHANNEL_ANNOUNCEMENT) != 0

    def includeUpdate1(flag: Long) = (flag & INCLUDE_CHANNEL_UPDATE_1) != 0

    def includeUpdate2(flag: Long) = (flag & INCLUDE_CHANNEL_UPDATE_2) != 0

    def includeNodeAnnouncement1(flag: Long) = (flag & INCLUDE_NODE_ANNOUNCEMENT_1) != 0

    def includeNodeAnnouncement2(flag: Long) = (flag & INCLUDE_NODE_ANNOUNCEMENT_2) != 0
  }

  val encodedQueryFlagsCodec: Codec[EncodedQueryFlags] =
    discriminated[EncodedQueryFlags].by(byte)
      .\(0) { case a@EncodedQueryFlags(EncodingType.UNCOMPRESSED, _) => a }((provide[EncodingType](EncodingType.UNCOMPRESSED) :: list(varintoverflow)).as[EncodedQueryFlags])
      .\(1) { case a@EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, _) => a }((provide[EncodingType](EncodingType.COMPRESSED_ZLIB) :: zlib(list(varintoverflow))).as[EncodedQueryFlags])


  val codec: Codec[TlvStream[QueryShortChannelIdsTlv]] = TlvCodecs.tlvStream(discriminated.by(varint)
    .typecase(UInt64(1), variableSizeBytesLong(varintoverflow, encodedQueryFlagsCodec))
  )
}
