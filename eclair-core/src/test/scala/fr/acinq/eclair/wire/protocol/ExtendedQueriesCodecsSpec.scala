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

import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64}
import fr.acinq.eclair.router.Sync
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol.ReplyChannelRangeTlv._
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, MilliSatoshiLong, RealShortChannelId, ShortChannelId, TimestampSecond, TimestampSecondLong, UInt64}
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

class ExtendedQueriesCodecsSpec extends AnyFunSuite {

  test("encode a list of short channel ids") {
    {
      // encode/decode with encoding 'uncompressed'
      val ids = EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676)))
      val encoded = encodedShortChannelIdsCodec.encode(ids).require
      val decoded = encodedShortChannelIdsCodec.decode(encoded).require.value
      assert(decoded == ids)
    }

    {
      // encode/decode with encoding 'zlib'
      val ids = EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676)))
      val encoded = encodedShortChannelIdsCodec.encode(ids).require
      val decoded = encodedShortChannelIdsCodec.decode(encoded).require.value
      assert(decoded == ids)
    }

    {
      // encode/decode empty list with encoding 'uncompressed'
      val ids = EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List.empty)
      val encoded = encodedShortChannelIdsCodec.encode(ids).require
      assert(encoded.bytes == hex"00")
      val decoded = encodedShortChannelIdsCodec.decode(encoded).require.value
      assert(decoded == ids)
    }

    {
      // encode/decode empty list with encoding 'zlib'
      val ids = EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List.empty)
      val encoded = encodedShortChannelIdsCodec.encode(ids).require
      assert(encoded.bytes == hex"00") // NB: empty list is always encoded with encoding type 'uncompressed'
      val decoded = encodedShortChannelIdsCodec.decode(encoded).require.value
      assert(decoded == EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List.empty))
    }
  }

  test("encode query_short_channel_ids (no optional data)") {
    val query_short_channel_id = QueryShortChannelIds(
      Block.RegtestGenesisBlock.blockId,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))),
      TlvStream.empty)

    val encoded = queryShortChannelIdsCodec.encode(query_short_channel_id).require
    val decoded = queryShortChannelIdsCodec.decode(encoded).require.value
    assert(decoded == query_short_channel_id)
  }

  test("encode query_short_channel_ids (with optional data)") {
    val query_short_channel_id = QueryShortChannelIds(
      Block.RegtestGenesisBlock.blockId,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))),
      TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.UNCOMPRESSED, List(1.toByte, 2.toByte, 3.toByte, 4.toByte, 5.toByte))))

    val encoded = queryShortChannelIdsCodec.encode(query_short_channel_id).require
    val decoded = queryShortChannelIdsCodec.decode(encoded).require.value
    assert(decoded == query_short_channel_id)
  }

  test("encode query_short_channel_ids (with optional data including unknown data)") {
    val query_short_channel_id = QueryShortChannelIds(
      Block.RegtestGenesisBlock.blockId,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))),
      TlvStream(
        Set[QueryShortChannelIdsTlv](QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.UNCOMPRESSED, List(1.toByte, 2.toByte, 3.toByte, 4.toByte, 5.toByte))),
        Set(GenericTlv(UInt64(43), ByteVector.fromValidHex("deadbeef")))
      )
    )

    val encoded = queryShortChannelIdsCodec.encode(query_short_channel_id).require
    val decoded = queryShortChannelIdsCodec.decode(encoded).require.value
    assert(decoded == query_short_channel_id)
  }

  test("encode reply_channel_range (no optional data)") {
    val replyChannelRange = ReplyChannelRange(
      Block.RegtestGenesisBlock.blockId,
      BlockHeight(1), 100,
      1.toByte,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))),
      None, None)

    val encoded = replyChannelRangeCodec.encode(replyChannelRange).require
    val decoded = replyChannelRangeCodec.decode(encoded).require.value
    assert(decoded == replyChannelRange)
  }

  test("encode reply_channel_range (with optional timestamps)") {
    val replyChannelRange = ReplyChannelRange(
      Block.RegtestGenesisBlock.blockId,
      BlockHeight(1), 100,
      1.toByte,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))),
      Some(EncodedTimestamps(EncodingType.COMPRESSED_ZLIB, List(Timestamps(1 unixsec, 1 unixsec), Timestamps(2 unixsec, 2 unixsec), Timestamps(3 unixsec, 3 unixsec)))),
      None)

    val encoded = replyChannelRangeCodec.encode(replyChannelRange).require
    val decoded = replyChannelRangeCodec.decode(encoded).require.value
    assert(decoded == replyChannelRange)
  }

  test("encode reply_channel_range (with optional timestamps, checksums, and unknown data)") {
    val replyChannelRange = ReplyChannelRange(
      Block.RegtestGenesisBlock.blockId,
      BlockHeight(1), 100,
      1.toByte,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))),
      TlvStream(
        Set[ReplyChannelRangeTlv](
          EncodedTimestamps(EncodingType.COMPRESSED_ZLIB, List(Timestamps(1 unixsec, 1 unixsec), Timestamps(2 unixsec, 2 unixsec), Timestamps(3 unixsec, 3 unixsec))),
          EncodedChecksums(List(Checksums(1, 1), Checksums(2, 2), Checksums(3, 3)))
        ),
        Set(GenericTlv(UInt64(7), ByteVector.fromValidHex("deadbeef")))
      )
    )

    val encoded = replyChannelRangeCodec.encode(replyChannelRange).require
    val decoded = replyChannelRangeCodec.decode(encoded).require.value
    assert(decoded == replyChannelRange)
  }

  test("compute checksums correctly") {
    val update = ChannelUpdate(
      chainHash = ByteVector32.fromValidHex("06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"),
      signature = ByteVector64.fromValidHex("76df7e70c63cc2b63ef1c062b99c6d934a80ef2fd4dae9e1d86d277f47674af3255a97fa52ade7f129263f591ed784996eba6383135896cc117a438c80293282"),
      shortChannelId = ShortChannelId.fromCoordinates("103x1x0").success.value,
      timestamp = TimestampSecond(1565587763L),
      messageFlags = ChannelUpdate.MessageFlags(dontForward = false),
      channelFlags = ChannelUpdate.ChannelFlags.DUMMY,
      cltvExpiryDelta = CltvExpiryDelta(144),
      htlcMinimumMsat = 0 msat,
      htlcMaximumMsat = 250_000_000 msat,
      feeBaseMsat = 1000 msat,
      feeProportionalMillionths = 10
    )
    val check = ByteVector.fromValidHex("010276df7e70c63cc2b63ef1c062b99c6d934a80ef2fd4dae9e1d86d277f47674af3255a97fa52ade7f129263f591ed784996eba6383135896cc117a438c8029328206226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f00006700000100005d50f933010000900000000000000000000003e80000000a000000000ee6b280")
    assert(LightningMessageCodecs.channelUpdateCodec.encode(update).require.bytes == check.drop(2))

    val checksum = Sync.getChecksum(update)
    assert(checksum == 0xeb8277a6L)
  }

  test("compute checksums correctly (cln test)") {
    val update = ChannelUpdate(
      chainHash = ByteVector32.fromValidHex("06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"),
      signature = ByteVector64.fromValidHex("06737e9e18d3e4d0ab4066ccaecdcc10e648c5f1c5413f1610747e0d463fa7fa39c1b02ea2fd694275ecfefe4fe9631f24afd182ab75b805e16cd550941f858c"),
      shortChannelId = ShortChannelId.fromCoordinates("109x1x0").success.value,
      timestamp = TimestampSecond(1565587765L),
      messageFlags = ChannelUpdate.MessageFlags(dontForward = false),
      channelFlags = ChannelUpdate.ChannelFlags.DUMMY,
      cltvExpiryDelta = CltvExpiryDelta(48),
      htlcMinimumMsat = 0 msat,
      htlcMaximumMsat = 100_000 msat,
      feeBaseMsat = 100 msat,
      feeProportionalMillionths = 11
    )
    val check = ByteVector.fromValidHex("010206737e9e18d3e4d0ab4066ccaecdcc10e648c5f1c5413f1610747e0d463fa7fa39c1b02ea2fd694275ecfefe4fe9631f24afd182ab75b805e16cd550941f858c06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f00006d00000100005d50f935010000300000000000000000000000640000000b00000000000186a0")
    assert(LightningMessageCodecs.channelUpdateCodec.encode(update).require.bytes == check.drop(2))

    val checksum = Sync.getChecksum(update)
    assert(checksum == 0xf32ce968L)
  }
}
