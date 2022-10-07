/*
 * Copyright 2021 ACINQ SAS
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

import fr.acinq.eclair.wire.protocol.CommonCodecs.{timestampSecond, varint, varintoverflow}
import fr.acinq.eclair.wire.protocol.TlvCodecs.{tlvField, tlvStream}
import fr.acinq.eclair.{TimestampSecond, UInt64}
import scodec.Codec
import scodec.codecs._

/**
 * Created by t-bast on 23/08/2021.
 */

sealed trait AnnouncementSignaturesTlv extends Tlv

object AnnouncementSignaturesTlv {
  val announcementSignaturesTlvCodec: Codec[TlvStream[AnnouncementSignaturesTlv]] = tlvStream(discriminated[AnnouncementSignaturesTlv].by(varint))
}

sealed trait NodeAnnouncementTlv extends Tlv

object NodeAnnouncementTlv {
  val nodeAnnouncementTlvCodec: Codec[TlvStream[NodeAnnouncementTlv]] = tlvStream(discriminated[NodeAnnouncementTlv].by(varint))
}

sealed trait ChannelAnnouncementTlv extends Tlv

object ChannelAnnouncementTlv {
  val channelAnnouncementTlvCodec: Codec[TlvStream[ChannelAnnouncementTlv]] = tlvStream(discriminated[ChannelAnnouncementTlv].by(varint))
}

sealed trait ChannelUpdateTlv extends Tlv

object ChannelUpdateTlv {
  val channelUpdateTlvCodec: Codec[TlvStream[ChannelUpdateTlv]] = tlvStream(discriminated[ChannelUpdateTlv].by(varint))
}

sealed trait GossipTimestampFilterTlv extends Tlv

object GossipTimestampFilterTlv {
  val gossipTimestampFilterTlvCodec: Codec[TlvStream[GossipTimestampFilterTlv]] = tlvStream(discriminated[GossipTimestampFilterTlv].by(varint))
}

sealed trait QueryChannelRangeTlv extends Tlv

object QueryChannelRangeTlv {

  /**
   * Optional query flag that is appended to QueryChannelRange
   *
   * @param flag bit 1 set means I want timestamps, bit 2 set means I want checksums
   */
  case class QueryFlags(flag: Long) extends QueryChannelRangeTlv {
    val wantTimestamps = QueryFlags.wantTimestamps(flag)

    val wantChecksums = QueryFlags.wantChecksums(flag)
  }

  case object QueryFlags {
    val WANT_TIMESTAMPS: Long = 1
    val WANT_CHECKSUMS: Long = 2
    val WANT_ALL: Long = WANT_TIMESTAMPS | WANT_CHECKSUMS

    def wantTimestamps(flag: Long): Boolean = (flag & WANT_TIMESTAMPS) != 0

    def wantChecksums(flag: Long): Boolean = (flag & WANT_CHECKSUMS) != 0
  }

  val queryFlagsCodec: Codec[QueryFlags] = Codec("flag" | varintoverflow).as[QueryFlags]

  val codec: Codec[TlvStream[QueryChannelRangeTlv]] = TlvCodecs.tlvStream(discriminated.by(varint)
    .typecase(UInt64(1), tlvField(queryFlagsCodec))
  )

}

sealed trait ReplyChannelRangeTlv extends Tlv

object ReplyChannelRangeTlv {

  /**
   * @param timestamp1 timestamp for node 1, or 0
   * @param timestamp2 timestamp for node 2, or 0
   */
  case class Timestamps(timestamp1: TimestampSecond, timestamp2: TimestampSecond)

  /**
   * Optional timestamps TLV that can be appended to ReplyChannelRange
   *
   * @param encoding same convention as for short channel ids
   */
  case class EncodedTimestamps(encoding: EncodingType, timestamps: List[Timestamps]) extends ReplyChannelRangeTlv {
    /** custom toString because it can get huge in logs */
    override def toString: String = s"EncodedTimestamps($encoding, size=${timestamps.size})"
  }

  /**
   * @param checksum1 checksum for node 1, or 0
   * @param checksum2 checksum for node 2, or 0
   */
  case class Checksums(checksum1: Long, checksum2: Long)

  /**
   * Optional checksums TLV that can be appended to ReplyChannelRange
   */
  case class EncodedChecksums(checksums: List[Checksums]) extends ReplyChannelRangeTlv {
    /** custom toString because it can get huge in logs */
    override def toString: String = s"EncodedChecksums(size=${checksums.size})"
  }

  val timestampsCodec: Codec[Timestamps] = (
    ("timestamp1" | timestampSecond) ::
      ("timestamp2" | timestampSecond)
    ).as[Timestamps]

  val encodedTimestampsCodec: Codec[EncodedTimestamps] = tlvField(discriminated[EncodedTimestamps].by(byte)
    .\(0) { case a@EncodedTimestamps(EncodingType.UNCOMPRESSED, _) => a }((provide[EncodingType](EncodingType.UNCOMPRESSED) :: list(timestampsCodec)).as[EncodedTimestamps])
    .\(1) { case a@EncodedTimestamps(EncodingType.COMPRESSED_ZLIB, _) => a }((provide[EncodingType](EncodingType.COMPRESSED_ZLIB) :: zlib(list(timestampsCodec))).as[EncodedTimestamps])
  )

  val checksumsCodec: Codec[Checksums] = (
    ("checksum1" | uint32) ::
      ("checksum2" | uint32)
    ).as[Checksums]

  val encodedChecksumsCodec: Codec[EncodedChecksums] = tlvField(list(checksumsCodec))

  val innerCodec = discriminated[ReplyChannelRangeTlv].by(varint)
    .typecase(UInt64(1), encodedTimestampsCodec)
    .typecase(UInt64(3), encodedChecksumsCodec)

  val codec: Codec[TlvStream[ReplyChannelRangeTlv]] = TlvCodecs.tlvStream(innerCodec)
}

sealed trait QueryShortChannelIdsTlv extends Tlv

object QueryShortChannelIdsTlv {

  /**
   * Optional TLV-based query message that can be appended to QueryShortChannelIds
   *
   * @param encoding 0 means uncompressed, 1 means compressed with zlib
   * @param array    array of query flags, each flags specifies the info we want for a given channel
   */
  case class EncodedQueryFlags(encoding: EncodingType, array: List[Long]) extends QueryShortChannelIdsTlv {
    /** custom toString because it can get huge in logs */
    override def toString: String = s"EncodedQueryFlags($encoding, size=${array.size})"
  }

  case object QueryFlagType {
    val INCLUDE_CHANNEL_ANNOUNCEMENT: Long = 1
    val INCLUDE_CHANNEL_UPDATE_1: Long = 2
    val INCLUDE_CHANNEL_UPDATE_2: Long = 4
    val INCLUDE_NODE_ANNOUNCEMENT_1: Long = 8
    val INCLUDE_NODE_ANNOUNCEMENT_2: Long = 16

    def includeChannelAnnouncement(flag: Long): Boolean = (flag & INCLUDE_CHANNEL_ANNOUNCEMENT) != 0

    def includeUpdate1(flag: Long): Boolean = (flag & INCLUDE_CHANNEL_UPDATE_1) != 0

    def includeUpdate2(flag: Long): Boolean = (flag & INCLUDE_CHANNEL_UPDATE_2) != 0

    def includeNodeAnnouncement1(flag: Long): Boolean = (flag & INCLUDE_NODE_ANNOUNCEMENT_1) != 0

    def includeNodeAnnouncement2(flag: Long): Boolean = (flag & INCLUDE_NODE_ANNOUNCEMENT_2) != 0
  }

  val encodedQueryFlagsCodec: Codec[EncodedQueryFlags] =
    discriminated[EncodedQueryFlags].by(byte)
      .\(0) { case a@EncodedQueryFlags(EncodingType.UNCOMPRESSED, _) => a }((provide[EncodingType](EncodingType.UNCOMPRESSED) :: list(varintoverflow)).as[EncodedQueryFlags])
      .\(1) { case a@EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, _) => a }((provide[EncodingType](EncodingType.COMPRESSED_ZLIB) :: zlib(list(varintoverflow))).as[EncodedQueryFlags])


  val codec: Codec[TlvStream[QueryShortChannelIdsTlv]] = TlvCodecs.tlvStream(discriminated.by(varint)
    .typecase(UInt64(1), tlvField[EncodedQueryFlags, EncodedQueryFlags](encodedQueryFlagsCodec))
  )
}

sealed trait ReplyShortChannelIdsEndTlv extends Tlv

object ReplyShortChannelIdsEndTlv {
  val replyShortChannelIdsEndTlvCodec: Codec[TlvStream[ReplyShortChannelIdsEndTlv]] = tlvStream(discriminated[ReplyShortChannelIdsEndTlv].by(varint))
}
