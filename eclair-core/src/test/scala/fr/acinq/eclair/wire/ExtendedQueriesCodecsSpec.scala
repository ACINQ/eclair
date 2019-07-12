package fr.acinq.eclair.wire

import fr.acinq.bitcoin.Block
import fr.acinq.eclair.{ShortChannelId, UInt64}
import fr.acinq.eclair.wire.LightningMessageCodecs._
import org.scalatest.FunSuite
import scodec.bits.ByteVector

class ExtendedQueriesCodecsSpec extends FunSuite {
  test("encode query_short_channel_ids (no optional data)") {
    val query_short_channel_id = QueryShortChannelIds(
      Block.RegtestGenesisBlock.blockId,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      TlvStream(List.empty[Tlv]))

    val encoded = queryShortChannelIdsCodec.encode(query_short_channel_id).require
    val decoded = queryShortChannelIdsCodec.decode(encoded).require.value
    assert(decoded === query_short_channel_id)
  }

  test("encode query_short_channel_ids (with optional data)") {
    val query_short_channel_id = QueryShortChannelIds(
      Block.RegtestGenesisBlock.blockId,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      TlvStream[Tlv](List(EncodedQueryFlags(EncodingType.UNCOMPRESSED, List(1.toByte, 2.toByte, 3.toByte, 4.toByte, 5.toByte)))))

    val encoded = queryShortChannelIdsCodec.encode(query_short_channel_id).require
    val decoded = queryShortChannelIdsCodec.decode(encoded).require.value
    assert(decoded === query_short_channel_id)
  }

  test("encode query_short_channel_ids (with optional data including unknown data)") {
    val query_short_channel_id = QueryShortChannelIds(
      Block.RegtestGenesisBlock.blockId,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      TlvStream[Tlv](
        EncodedQueryFlags(EncodingType.UNCOMPRESSED, List(1.toByte, 2.toByte, 3.toByte, 4.toByte, 5.toByte)) :: Nil,
        GenericTlv(UInt64(43), ByteVector.fromValidHex("deadbeef")) :: Nil)
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
}
