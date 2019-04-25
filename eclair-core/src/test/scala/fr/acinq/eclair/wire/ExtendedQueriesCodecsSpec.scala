package fr.acinq.eclair.wire

import fr.acinq.bitcoin.Block
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.LightningMessageCodecs._
import org.scalatest.FunSuite
import scodec.bits.ByteVector

class ExtendedQueriesCodecsSpec extends FunSuite {
  test("encode query_short_channel_ids (no optional data)") {
    val query_short_channel_id = QueryShortChannelIds(
      Block.RegtestGenesisBlock.blockId,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      List())

    val encoded = queryShortChannelIdsCodec.encode(query_short_channel_id).require
    val decoded = queryShortChannelIdsCodec.decode(encoded).require.value
    assert(decoded === query_short_channel_id)
  }

  test("encode query_short_channel_ids (with optional data)") {
    val query_short_channel_id = QueryShortChannelIds(
      Block.RegtestGenesisBlock.blockId,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      List(EncodedQueryFlags(EncodingType.UNCOMPRESSED, List(1.toByte, 2.toByte, 3.toByte, 4.toByte, 5.toByte))))

    val encoded = queryShortChannelIdsCodec.encode(query_short_channel_id).require
    val decoded = queryShortChannelIdsCodec.decode(encoded).require.value
    assert(decoded === query_short_channel_id)
  }

  test("encode query_short_channel_ids (with optional data including unknown data)") {
    val query_short_channel_id = QueryShortChannelIds(
      Block.RegtestGenesisBlock.blockId,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      List(
        EncodedQueryFlags(EncodingType.UNCOMPRESSED, List(1.toByte, 2.toByte, 3.toByte, 4.toByte, 5.toByte)),
        GenericTLV(43.toByte, ByteVector.fromValidHex("deadbeef"))))

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
      List(
        EncodedTimestamps(EncodingType.COMPRESSED_ZLIB, List(Timestamps(1, 1), Timestamps(2, 2), Timestamps(3, 3))),
        EncodedChecksums(List(Checksums(1, 1), Checksums(2, 2), Checksums(3, 3))),
        GenericTLV(7.toByte, ByteVector.fromValidHex("deadbeef"))
      )
    )

    val encoded = replyChannelRangeCodec.encode(replyChannelRange).require
    val decoded = replyChannelRangeCodec.decode(encoded).require.value
    assert(decoded === replyChannelRange)
  }
}

//object ExtendedQueriesCodecsSpec {
//
//  val varIntCodec = Codec[Long](
//    (n: Long) =>
//      n match {
//        case i if (i < 0xfd) =>
//          uint8L.encode(i.toInt)
//        case i if (i < 0xffff) =>
//          for {
//            a <- uint8L.encode(0xfd)
//            b <- uint16L.encode(i.toInt)
//          } yield a ++ b
//        case i if (i < 0xffffffffL) =>
//          for {
//            a <- uint8L.encode(0xfe)
//            b <- uint32L.encode(i)
//          } yield a ++ b
//        case i =>
//          for {
//            a <- uint8L.encode(0xff)
//            b <- uint64.encode(i)
//          } yield a ++ b
//      },
//    (buf: BitVector) => {
//      uint8L.decode(buf) match {
//        case Successful(byte) =>
//          byte.value match {
//            case 0xff =>
//              uint64.decode(byte.remainder)
//            case 0xfe =>
//              uint32L.decode(byte.remainder)
//            case 0xfd =>
//              uint16L.decode(byte.remainder)
//                .map { case b => b.map(_.toLong) }
//            case _ =>
//              Successful(scodec.DecodeResult(byte.value.toLong, byte.remainder))
//          }
//        case Failure(err) =>
//          Failure(err)
//      }
//    })
//
//  sealed trait TLV
//
//  case class GenericTLV(`type`: Byte, value: ByteVector) extends TLV
//
//  case class EncodedQueryFlags(encoding: EncodingType, array: List[Byte]) extends TLV
//
//  case class QueryFlag(flag: Byte) extends TLV
//
//  case class Timestamps(timestamp1: Long, timestamp2: Long)
//
//  case class EncodedTimestamps(encoding: EncodingType, timestamps: List[Timestamps]) extends TLV
//
//  case class Checksums(checksum1: Long, checksum2: Long)
//
//  case class EncodedChecksums(checksums: List[Checksums]) extends TLV
//
//  val genericTlvCodec: Codec[GenericTLV] = (
//    ("type" | byte) :: variableSizeBytesLong(varIntCodec, bytes)).as[GenericTLV]
//
//  def fallbackCodec(codec: Codec[TLV]): Codec[TLV] = discriminatorFallback(genericTlvCodec, codec).xmap(_ match {
//    case Left(l) => l
//    case Right(r) => r
//  }, _ match {
//    case g: GenericTLV => Left(g)
//    case o => Right(o)
//  })
//
//  val rawQueryFlagCodec: Codec[QueryFlag] = Codec(("flag" | byte)).as[QueryFlag]
//
//  val queryFlagCodec: Codec[QueryFlag] = variableSizeBytesLong(varIntCodec, rawQueryFlagCodec)
//
//  val rawEncodedQueryFlagsCodec: Codec[EncodedQueryFlags] =
//    discriminated[EncodedQueryFlags].by(byte)
//      .\(0) { case a@EncodedQueryFlags(EncodingType.UNCOMPRESSED, _) => a }((provide[EncodingType](EncodingType.UNCOMPRESSED) :: list(byte)).as[EncodedQueryFlags])
//      .\(1) { case a@EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, _) => a }((provide[EncodingType](EncodingType.COMPRESSED_ZLIB) :: zlib(list(byte))).as[EncodedQueryFlags])
//
//  sealed trait RoutingMessage
//
//  case class QueryShortChannelIds(chainHash: ByteVector32,
//                                  shortChannelIds: EncodedShortChannelIds,
//                                  extensions: List[TLV]) extends RoutingMessage {
//    val queryFlags: Option[EncodedQueryFlags] = extensions collectFirst { case flags: EncodedQueryFlags => flags }
//  }
//
//  object QueryShortChannelIds {
//    def apply(chainHash: ByteVector32, shortChannelIds: EncodedShortChannelIds, flags: EncodedQueryFlags) = new QueryShortChannelIds(chainHash, shortChannelIds, List(flags))
//
//    val extensionsCodec: Codec[TLV] = fallbackCodec(
//      discriminated[TLV].by(byte)
//        .typecase(1, variableSizeBytesLong(varIntCodec, rawEncodedQueryFlagsCodec))
//    )
//
//    val codec: Codec[QueryShortChannelIds] = Codec(
//      ("chainHash" | bytes32) ::
//        ("shortChannelIds" | variableSizeBytes(uint16, encodedShortChannelIdsCodec)) ::
//        ("extensions" | list(extensionsCodec))
//    ).as[QueryShortChannelIds]
//  }
//
//  case class QueryChannelRange(chainHash: ByteVector32,
//                               firstBlockNum: Long,
//                               numberOfBlocks: Long,
//                               extensions: List[TLV]) extends RoutingMessage {
//    val queryFlag: Option[QueryFlag] = extensions collectFirst { case q: QueryFlag => q }
//  }
//
//  object QueryChannelRange {
//    val extensionsCodec: Codec[TLV] = fallbackCodec(
//      discriminated[TLV].by(byte)
//        .typecase(1, variableSizeBytesLong(varIntCodec, rawQueryFlagCodec))
//    )
//    val codec: Codec[QueryChannelRange] = (
//      ("chainHash" | bytes32) ::
//        ("firstBlockNum" | uint32) ::
//        ("numberOfBlocks" | uint32) ::
//        ("extensions" | list(extensionsCodec))
//      ).as[QueryChannelRange]
//  }
//
//  val timestampsCodec: Codec[Timestamps] = (
//    ("checksum1" | uint32) ::
//      ("checksum2" | uint32)
//    ).as[Timestamps]
//
//  val rawEncodedTimestampsCodec: Codec[EncodedTimestamps] =
//    discriminated[EncodedTimestamps].by(byte)
//      .\(0) { case a@EncodedTimestamps(EncodingType.UNCOMPRESSED, _) => a }((provide[EncodingType](EncodingType.UNCOMPRESSED) :: list(timestampsCodec)).as[EncodedTimestamps])
//      .\(1) { case a@EncodedTimestamps(EncodingType.COMPRESSED_ZLIB, _) => a }((provide[EncodingType](EncodingType.COMPRESSED_ZLIB) :: zlib(list(timestampsCodec))).as[EncodedTimestamps])
//
//  val checksumsCodec: Codec[Checksums] = (
//    ("checksum1" | uint32) ::
//      ("checksum2" | uint32)
//    ).as[Checksums]
//
//  val rawChecksumsCodec: Codec[EncodedChecksums] = Codec(("checksums" | list(checksumsCodec))).as[EncodedChecksums]
//
//  case class ReplyChannelRange(chainHash: ByteVector32,
//                               firstBlockNum: Long,
//                               numberOfBlocks: Long,
//                               complete: Byte,
//                               shortChannelIds: EncodedShortChannelIds,
//                               extensions: List[TLV]) extends RoutingMessage {
//    val timestamps: Option[EncodedTimestamps] = extensions collectFirst { case ts: EncodedTimestamps => ts }
//
//    val checksums: Option[EncodedChecksums] = extensions collectFirst { case cs: EncodedChecksums => cs }
//  }
//
//  object ReplyChannelRange {
//    def apply(chainHash: ByteVector32,
//              firstBlockNum: Long,
//              numberOfBlocks: Long,
//              complete: Byte,
//              shortChannelIds: EncodedShortChannelIds,
//              timestamps: Option[EncodedTimestamps],
//              checksums: Option[EncodedChecksums]) = {
//      timestamps.foreach(ts => require(ts.timestamps.length == shortChannelIds.array.length))
//      checksums.foreach(cs => require(cs.checksums.length == shortChannelIds.array.length))
//      new ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, complete, shortChannelIds, timestamps.toList ::: checksums.toList)
//    }
//
//    val extensionsCodec: Codec[TLV] = fallbackCodec(
//      discriminated[TLV].by(byte)
//        .typecase(1, variableSizeBytesLong(varIntCodec, rawEncodedTimestampsCodec))
//        .typecase(3, variableSizeBytesLong(varIntCodec, rawChecksumsCodec))
//    )
//
//    val codec: Codec[ReplyChannelRange] = (
//      ("chainHash" | bytes32) ::
//        ("firstBlockNum" | uint32) ::
//        ("numberOfBlocks" | uint32) ::
//        ("complete" | byte) ::
//        ("shortChannelIds" | variableSizeBytes(uint16, encodedShortChannelIdsCodec)) ::
//        ("extensions" | list(extensionsCodec))
//      ).as[ReplyChannelRange]
//  }
//
//}