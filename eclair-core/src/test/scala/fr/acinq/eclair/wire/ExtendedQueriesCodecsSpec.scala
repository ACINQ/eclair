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

import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.eclair.wire.ReplyChannelRangeTlv._
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, ShortChannelId, UInt64}
import org.scalatest.FunSuite
import scodec.bits.ByteVector

class ExtendedQueriesCodecsSpec extends FunSuite {
  test("encode query_short_channel_ids (no optional data)") {
    val query_short_channel_id = QueryShortChannelIds(
      Block.RegtestGenesisBlock.blockId,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      TlvStream.empty)

    val encoded = queryShortChannelIdsCodec.encode(query_short_channel_id).require
    val decoded = queryShortChannelIdsCodec.decode(encoded).require.value
    assert(decoded === query_short_channel_id)
  }

  test("encode query_short_channel_ids (with optional data)") {
    val query_short_channel_id = QueryShortChannelIds(
      Block.RegtestGenesisBlock.blockId,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.UNCOMPRESSED, List(1.toByte, 2.toByte, 3.toByte, 4.toByte, 5.toByte))))

    val encoded = queryShortChannelIdsCodec.encode(query_short_channel_id).require
    val decoded = queryShortChannelIdsCodec.decode(encoded).require.value
    assert(decoded === query_short_channel_id)
  }

  test("encode query_short_channel_ids (with optional data including unknown data)") {
    val query_short_channel_id = QueryShortChannelIds(
      Block.RegtestGenesisBlock.blockId,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      TlvStream(
        QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.UNCOMPRESSED, List(1.toByte, 2.toByte, 3.toByte, 4.toByte, 5.toByte)) :: Nil,
        GenericTlv(UInt64(43), ByteVector.fromValidHex("deadbeef")) :: Nil
      )
    )

    val encoded = queryShortChannelIdsCodec.encode(query_short_channel_id).require
    val decoded = queryShortChannelIdsCodec.decode(encoded).require.value
    assert(decoded === query_short_channel_id)
  }

  test("encode reply_channel_range (no optional data)") {
    val replyChannelRange = ReplyChannelRange(
      Block.RegtestGenesisBlock.blockId,
      1, 100,
      1.toByte,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      None, None)

    val encoded = replyChannelRangeCodec.encode(replyChannelRange).require
    val decoded = replyChannelRangeCodec.decode(encoded).require.value
    assert(decoded === replyChannelRange)
  }

  test("encode reply_channel_range (with optional timestamps)") {
    val replyChannelRange = ReplyChannelRange(
      Block.RegtestGenesisBlock.blockId,
      1, 100,
      1.toByte,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      Some(EncodedTimestamps(EncodingType.COMPRESSED_ZLIB, List(Timestamps(1, 1), Timestamps(2, 2), Timestamps(3, 3)))),
      None)

    val encoded = replyChannelRangeCodec.encode(replyChannelRange).require
    val decoded = replyChannelRangeCodec.decode(encoded).require.value
    assert(decoded === replyChannelRange)
  }

  test("encode reply_channel_range (with optional timestamps, checksums, and unknown data)") {
    val replyChannelRange = ReplyChannelRange(
      Block.RegtestGenesisBlock.blockId,
      1, 100,
      1.toByte,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      TlvStream(
        List(
          EncodedTimestamps(EncodingType.COMPRESSED_ZLIB, List(Timestamps(1, 1), Timestamps(2, 2), Timestamps(3, 3))),
          EncodedChecksums(List(Checksums(1, 1), Checksums(2, 2), Checksums(3, 3)))
        ),
        GenericTlv(UInt64(7), ByteVector.fromValidHex("deadbeef")) :: Nil
      )
    )

    val encoded = replyChannelRangeCodec.encode(replyChannelRange).require
    val decoded = replyChannelRangeCodec.decode(encoded).require.value
    assert(decoded === replyChannelRange)
  }

  test("compute checksums correctly (CL test #1)") {
    val update = ChannelUpdate(
      chainHash = ByteVector32.fromValidHex("06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"),
      signature = ByteVector64.fromValidHex("76df7e70c63cc2b63ef1c062b99c6d934a80ef2fd4dae9e1d86d277f47674af3255a97fa52ade7f129263f591ed784996eba6383135896cc117a438c80293282"),
      shortChannelId = ShortChannelId("103x1x0"),
      timestamp = 1565587763L,
      messageFlags = 0,
      channelFlags = 0,
      cltvExpiryDelta = CltvExpiryDelta(144),
      htlcMinimumMsat = 0 msat,
      htlcMaximumMsat = None,
      feeBaseMsat = 1000 msat,
      feeProportionalMillionths = 10
    )
    val check = ByteVector.fromValidHex("010276df7e70c63cc2b63ef1c062b99c6d934a80ef2fd4dae9e1d86d277f47674af3255a97fa52ade7f129263f591ed784996eba6383135896cc117a438c8029328206226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f00006700000100005d50f933000000900000000000000000000003e80000000a")
    assert(LightningMessageCodecs.channelUpdateCodec.encode(update).require.bytes == check.drop(2))

    val checksum = Router.getChecksum(update)
    assert(checksum == 0x1112fa30L)
  }

  test("compute checksums correctly (CL test #2)") {
    val update = ChannelUpdate(
      chainHash = ByteVector32.fromValidHex("06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"),
      signature = ByteVector64.fromValidHex("06737e9e18d3e4d0ab4066ccaecdcc10e648c5f1c5413f1610747e0d463fa7fa39c1b02ea2fd694275ecfefe4fe9631f24afd182ab75b805e16cd550941f858c"),
      shortChannelId = ShortChannelId("109x1x0"),
      timestamp = 1565587765L,
      messageFlags = 1,
      channelFlags = 0,
      cltvExpiryDelta = CltvExpiryDelta(48),
      htlcMinimumMsat = 0 msat,
      htlcMaximumMsat = Some(100000 msat),
      feeBaseMsat = 100 msat,
      feeProportionalMillionths = 11
    )
    val check = ByteVector.fromValidHex("010206737e9e18d3e4d0ab4066ccaecdcc10e648c5f1c5413f1610747e0d463fa7fa39c1b02ea2fd694275ecfefe4fe9631f24afd182ab75b805e16cd550941f858c06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f00006d00000100005d50f935010000300000000000000000000000640000000b00000000000186a0")
    assert(LightningMessageCodecs.channelUpdateCodec.encode(update).require.bytes == check.drop(2))

    val checksum = Router.getChecksum(update)
    assert(checksum == 0xf32ce968L)
  }
}
