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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{BlockHash, ByteVector32, ByteVector64, Satoshi, TxId}
import fr.acinq.eclair.wire.protocol.CommonCodecs._
import fr.acinq.eclair.wire.protocol.TlvCodecs.{tlvField, tlvStream}
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, Feature, Features, MilliSatoshi, RealShortChannelId, ShortChannelId, TimestampSecond, UInt64}
import scodec.Codec
import scodec.codecs._

/**
 * Created by t-bast on 23/08/2021.
 */

sealed trait AnnouncementSignaturesTlv extends Tlv

object AnnouncementSignaturesTlv {
  val announcementSignaturesTlvCodec: Codec[TlvStream[AnnouncementSignaturesTlv]] = tlvStream(discriminated[AnnouncementSignaturesTlv].by(varint))
}

sealed trait AnnouncementSignatures2Tlv extends Tlv

object AnnouncementSignatures2Tlv {
  /** The channel_id that applies to the channel's lifetime. */
  case class ChannelId(id: ByteVector32) extends AnnouncementSignatures2Tlv

  /** A real short_channel_id pointing to the on-chain output for the announced funding must be provided. */
  case class ChannelShortChannelId(scid: RealShortChannelId) extends AnnouncementSignatures2Tlv

  /** Musig2 partial signature for the [[ChannelAnnouncement2]] message. */
  case class PartialSignature(partialSig: ByteVector32) extends AnnouncementSignatures2Tlv

  /** TxId of the on-chain channel funding transaction. */
  case class FundingTxId(txId: TxId) extends AnnouncementSignatures2Tlv

  val codec: Codec[TlvStream[AnnouncementSignatures2Tlv]] = tlvStream(discriminated[AnnouncementSignatures2Tlv].by(varint)
    .typecase(UInt64(0), tlvField(bytes32.as[ChannelId]))
    .typecase(UInt64(2), tlvField(realshortchannelid.as[ChannelShortChannelId]))
    .typecase(UInt64(4), tlvField(bytes32.as[PartialSignature]))
    .typecase(UInt64(6), tlvField(txIdAsHash.as[FundingTxId]))
  )
}

sealed trait NodeAnnouncementTlv extends Tlv

object NodeAnnouncementTlv {
  /** Rates at which the announced node sells inbound liquidity to remote peers. */
  case class OptionWillFund(rates: LiquidityAds.WillFundRates) extends NodeAnnouncementTlv

  val nodeAnnouncementTlvCodec: Codec[TlvStream[NodeAnnouncementTlv]] = tlvStream(discriminated[NodeAnnouncementTlv].by(varint)
    // We use a temporary TLV while the spec is being reviewed.
    .typecase(UInt64(1339), tlvField(LiquidityAds.Codecs.willFundRates.as[OptionWillFund]))
  )
}

sealed trait NodeAnnouncement2Tlv extends Tlv

object NodeAnnouncement2Tlv {
  /** Node features, that apply until the next node announcement. */
  case class AnnouncementFeatures(features: Features[Feature]) extends NodeAnnouncement2Tlv

  /** (Optional) recommended color used for this node. */
  case class Color(r: Byte, g: Byte, b: Byte) extends NodeAnnouncement2Tlv

  /** Block height after which this announcement becomes active (used for rate-limiting). */
  case class LastBlockHeight(blockHeight: BlockHeight) extends NodeAnnouncement2Tlv

  /** (Optional) node alias (friendly name). */
  case class Alias(alias: String) extends NodeAnnouncement2Tlv

  /** Id of this node. */
  case class NodeId(publicKey: PublicKey) extends NodeAnnouncement2Tlv

  /** IPv4 addresses that can be used to reach the node. */
  case class IPv4Addresses(addresses: List[IPv4]) extends NodeAnnouncement2Tlv

  /** IPv6 addresses that can be used to reach the node. */
  case class IPv6Addresses(addresses: List[IPv6]) extends NodeAnnouncement2Tlv

  /** Tor v3 addresses that can be used to reach the node. */
  case class TorAddresses(addresses: List[Tor3]) extends NodeAnnouncement2Tlv

  /** DNS hostnames that can be used to reach the node. */
  case class DnsAddresses(addresses: List[DnsHostname]) extends NodeAnnouncement2Tlv

  /** Signature of the [[NodeAnnouncement2]] message, as detailed in the BOLTs. */
  case class Signature(sig: ByteVector64) extends NodeAnnouncement2Tlv

  val codec: Codec[TlvStream[NodeAnnouncement2Tlv]] = tlvStream(discriminated[NodeAnnouncement2Tlv].by(varint)
    .typecase(UInt64(0), tlvField(featuresCodec.as[AnnouncementFeatures]))
    .typecase(UInt64(1), tlvField[Color, Color]((byte :: byte :: byte).as[Color]))
    .typecase(UInt64(2), tlvField(blockHeight.as[LastBlockHeight]))
    .typecase(UInt64(3), tlvField(utf8.as[Alias]))
    .typecase(UInt64(4), tlvField(publicKey.as[NodeId]))
    .typecase(UInt64(5), tlvField(list((ipv4address :: uint16).as[IPv4]).as[IPv4Addresses]))
    .typecase(UInt64(7), tlvField(list((ipv6address :: uint16).as[IPv6]).as[IPv6Addresses]))
    .typecase(UInt64(9), tlvField(list((base32(35) :: uint16).as[Tor3]).as[TorAddresses]))
    .typecase(UInt64(11), tlvField(list((variableSizeBytes(uint16, ascii) :: uint16).as[DnsHostname]).as[DnsAddresses]))
    .typecase(UInt64(160), tlvField(bytes64.as[Signature]))
  )
}

sealed trait ChannelAnnouncementTlv extends Tlv

object ChannelAnnouncementTlv {
  val channelAnnouncementTlvCodec: Codec[TlvStream[ChannelAnnouncementTlv]] = tlvStream(discriminated[ChannelAnnouncementTlv].by(varint))
}

sealed trait ChannelAnnouncement2Tlv extends Tlv

object ChannelAnnouncement2Tlv {
  /** The chain_hash must be provided if different from the mainnet bitcoin blockchain. */
  case class ChainHash(chain: BlockHash) extends ChannelAnnouncement2Tlv

  /** Channel features, that apply until the channel output is spent. */
  case class AnnouncementFeatures(features: Features[Feature]) extends ChannelAnnouncement2Tlv

  /** A real short_channel_id pointing to the on-chain output for the announced funding must be provided. */
  case class ChannelShortChannelId(scid: RealShortChannelId) extends ChannelAnnouncement2Tlv

  /** Channel funding amount. */
  case class ChannelCapacity(amount: Satoshi) extends ChannelAnnouncement2Tlv

  /** NodeId of the first node, using lexicographic ordering. */
  case class NodeId1(publicKey: PublicKey) extends ChannelAnnouncement2Tlv

  /** NodeId of the second node, using lexicographic ordering. */
  case class NodeId2(publicKey: PublicKey) extends ChannelAnnouncement2Tlv

  /** (Optional) If set, the musig2 announcement signature will include this key in the aggregated signature. */
  case class BitcoinKey1(publicKey: PublicKey) extends ChannelAnnouncement2Tlv

  /** (Optional) If set, the musig2 announcement signature will include this key in the aggregated signature. */
  case class BitcoinKey2(publicKey: PublicKey) extends ChannelAnnouncement2Tlv

  /** (Optional) If set, the channel output is a taproot output with this root hash. */
  case class MerkleRootHash(hash: ByteVector32) extends ChannelAnnouncement2Tlv

  /** The on-chain outpoint must be provided and must match the [[ShortChannelId]]. */
  case class ChannelOutpoint(txId: TxId, index: Int) extends ChannelAnnouncement2Tlv

  /** Signature of the [[ChannelAnnouncement2]] message, as detailed in the BOLTs. */
  case class Signature(sig: ByteVector64) extends ChannelAnnouncement2Tlv

  val codec: Codec[TlvStream[ChannelAnnouncement2Tlv]] = tlvStream(discriminated[ChannelAnnouncement2Tlv].by(varint)
    .typecase(UInt64(0), tlvField(blockHash.as[ChainHash]))
    .typecase(UInt64(2), tlvField(featuresCodec.as[AnnouncementFeatures]))
    .typecase(UInt64(4), tlvField(realshortchannelid.as[ChannelShortChannelId]))
    .typecase(UInt64(6), tlvField(satoshi.as[ChannelCapacity]))
    .typecase(UInt64(8), tlvField(publicKey.as[NodeId1]))
    .typecase(UInt64(10), tlvField(publicKey.as[NodeId2]))
    .typecase(UInt64(12), tlvField(publicKey.as[BitcoinKey1]))
    .typecase(UInt64(14), tlvField(publicKey.as[BitcoinKey2]))
    .typecase(UInt64(16), tlvField(bytes32.as[MerkleRootHash]))
    .typecase(UInt64(18), tlvField[ChannelOutpoint, ChannelOutpoint]((txIdAsHash :: uint16).as[ChannelOutpoint]))
    .typecase(UInt64(160), tlvField(bytes64.as[Signature]))
  )
}

sealed trait ChannelUpdateTlv extends Tlv

object ChannelUpdateTlv {
  val channelUpdateTlvCodec: Codec[TlvStream[ChannelUpdateTlv]] = tlvStream(discriminated[ChannelUpdateTlv].by(varint))
}

sealed trait ChannelUpdate2Tlv extends Tlv

object ChannelUpdate2Tlv {
  /** The chain_hash must be provided if different from the mainnet bitcoin blockchain. */
  case class ChainHash(chain: BlockHash) extends ChannelUpdate2Tlv

  /** A real short_channel_id pointing to the on-chain output for the channel must be provided. */
  case class ChannelShortChannelId(scid: ShortChannelId) extends ChannelUpdate2Tlv

  /** Block height after which this update becomes active (used for rate-limiting). */
  case class LastBlockHeight(blockHeight: BlockHeight) extends ChannelUpdate2Tlv

  /** Bitfields detailing why a channel is disabled and in which direction. */
  case class DisableFlags(outgoing: Boolean, incoming: Boolean, permanent: Boolean) extends ChannelUpdate2Tlv

  case class IsSecondPeer() extends ChannelUpdate2Tlv

  /** [[CltvExpiryDelta]] used when relaying through the channel. */
  case class ExpiryDelta(cltv: CltvExpiryDelta) extends ChannelUpdate2Tlv

  /** Minimum amount that can be relayed through the channel. */
  case class HtlcMinimum(amount: MilliSatoshi) extends ChannelUpdate2Tlv

  /** Maximum amount that can be relayed through the channel. */
  case class HtlcMaximum(amount: MilliSatoshi) extends ChannelUpdate2Tlv

  /** Flat fee collected when relaying payments. */
  case class BaseFee(amount: MilliSatoshi) extends ChannelUpdate2Tlv

  /** Proportional fee collected when relaying payments. */
  case class ProportionalFee(feeProportionalMillionths: Long) extends ChannelUpdate2Tlv

  /** Signature of the [[ChannelUpdate2]] message, as detailed in the BOLTs. */
  case class Signature(sig: ByteVector64) extends ChannelUpdate2Tlv

  val codec: Codec[TlvStream[ChannelUpdate2Tlv]] = tlvStream(discriminated[ChannelUpdate2Tlv].by(varint)
    .typecase(UInt64(0), tlvField(blockHash.as[ChainHash]))
    .typecase(UInt64(2), tlvField(shortchannelid.as[ChannelShortChannelId]))
    .typecase(UInt64(4), tlvField(blockHeight.as[LastBlockHeight]))
    .typecase(UInt64(6), tlvField[DisableFlags, DisableFlags]((ignore(5) :: bool :: bool :: bool).as[DisableFlags]))
    .typecase(UInt64(8), tlvField(provide(IsSecondPeer())))
    .typecase(UInt64(10), tlvField(cltvExpiryDelta.as[ExpiryDelta]))
    .typecase(UInt64(12), tlvField(millisatoshi.as[HtlcMinimum]))
    .typecase(UInt64(14), tlvField(millisatoshi.as[HtlcMaximum]))
    .typecase(UInt64(16), tlvField(millisatoshi32.as[BaseFee]))
    .typecase(UInt64(18), tlvField(uint32.as[ProportionalFee]))
    .typecase(UInt64(160), tlvField(bytes64.as[Signature]))
  )
}

sealed trait GossipTimestampFilterTlv extends Tlv

object GossipTimestampFilterTlv {

  /**
   * When using [[ChannelAnnouncement2]], [[ChannelUpdate2]] and [[NodeAnnouncement2]], we use block heights instead of
   * timestamps. This TLV is used to filter gossip that is included in the provided block range.
   */
  case class BlockRange(firstBlock: BlockHeight, numBlocks: Long) extends GossipTimestampFilterTlv

  private val blockRangeCodec: Codec[BlockRange] = tlvField((("first_block" | blockHeight) :: ("num_blocks" | uint32)).as[BlockRange])

  val codec: Codec[TlvStream[GossipTimestampFilterTlv]] = tlvStream(discriminated.by(varint)
    .typecase(UInt64(2), blockRangeCodec)
  )

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

  val codec: Codec[TlvStream[QueryChannelRangeTlv]] = tlvStream(discriminated.by(varint)
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
  )

  val checksumsCodec: Codec[Checksums] = (
    ("checksum1" | uint32) ::
      ("checksum2" | uint32)
    ).as[Checksums]

  val encodedChecksumsCodec: Codec[EncodedChecksums] = tlvField(list(checksumsCodec))

  val innerCodec = discriminated[ReplyChannelRangeTlv].by(varint)
    .typecase(UInt64(1), encodedTimestampsCodec)
    .typecase(UInt64(3), encodedChecksumsCodec)

  val codec: Codec[TlvStream[ReplyChannelRangeTlv]] = tlvStream(innerCodec)
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

  val codec: Codec[TlvStream[QueryShortChannelIdsTlv]] = tlvStream(discriminated.by(varint)
    .typecase(UInt64(1), tlvField[EncodedQueryFlags, EncodedQueryFlags](encodedQueryFlagsCodec))
  )
}

sealed trait ReplyShortChannelIdsEndTlv extends Tlv

object ReplyShortChannelIdsEndTlv {
  val replyShortChannelIdsEndTlvCodec: Codec[TlvStream[ReplyShortChannelIdsEndTlv]] = tlvStream(discriminated[ReplyShortChannelIdsEndTlv].by(varint))
}
