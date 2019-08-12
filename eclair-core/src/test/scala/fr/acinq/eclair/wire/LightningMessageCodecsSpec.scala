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

import java.net.{Inet4Address, InetAddress}

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.api._
import fr.acinq.eclair.channel.State
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.LightningMessageCodecs._
import org.json4s.JsonAST.{JNothing, JString}
import org.json4s.{CustomSerializer, ShortTypeHints}
import org.json4s.jackson.Serialization
import org.scalatest.FunSuite
import scodec.bits.{ByteVector, HexStringSyntax}

/**
  * Created by PM on 31/05/2016.
  */

class LightningMessageCodecsSpec extends FunSuite {

  def bin(len: Int, fill: Byte) = ByteVector.fill(len)(fill)

  def bin32(fill: Byte) = ByteVector32(bin(32, fill))

  def scalar(fill: Byte) = PrivateKey(ByteVector.fill(32)(fill))

  def point(fill: Byte) = PrivateKey(ByteVector.fill(32)(fill)).publicKey

  def publicKey(fill: Byte) = PrivateKey(ByteVector.fill(32)(fill)).publicKey

  test("encode/decode live node_announcements") {
    val ann = hex"a58338c9660d135fd7d087eb62afd24a33562c54507a9334e79f0dc4f17d407e6d7c61f0e2f3d0d38599502f61704cf1ae93608df027014ade7ff592f27ce2690001025acdf50702d2eabbbacc7c25bbd73b39e65d28237705f7bde76f557e94fb41cb18a9ec00841122116c6e302e646563656e7465722e776f726c64000000000000000000000000000000130200000000000000000000ffffae8a0b082607"
    val bin = ann.bits

    val node = nodeAnnouncementCodec.decode(bin).require.value
    val bin2 = nodeAnnouncementCodec.encode(node).require
    assert(bin === bin2)
  }

  test("encode/decode all channel messages") {
    val open = OpenChannel(randomBytes32, randomBytes32, Satoshi(3), MilliSatoshi(4), Satoshi(5), UInt64(6), Satoshi(7), MilliSatoshi(8), 9, 10, 11, publicKey(1), point(2), point(3), point(4), point(5), point(6), 0.toByte)
    val accept = AcceptChannel(randomBytes32, Satoshi(3), UInt64(4), Satoshi(5), MilliSatoshi(6), 7, 8, 9, publicKey(1), point(2), point(3), point(4), point(5), point(6))
    val funding_created = FundingCreated(randomBytes32, bin32(0), 3, randomBytes64)
    val funding_signed = FundingSigned(randomBytes32, randomBytes64)
    val funding_locked = FundingLocked(randomBytes32, point(2))
    val update_fee = UpdateFee(randomBytes32, 2)
    val shutdown = Shutdown(randomBytes32, bin(47, 0))
    val closing_signed = ClosingSigned(randomBytes32, Satoshi(2), randomBytes64)
    val update_add_htlc = UpdateAddHtlc(randomBytes32, 2, MilliSatoshi(3), bin32(0), 4, TestConstants.emptyOnionPacket)
    val update_fulfill_htlc = UpdateFulfillHtlc(randomBytes32, 2, bin32(0))
    val update_fail_htlc = UpdateFailHtlc(randomBytes32, 2, bin(154, 0))
    val update_fail_malformed_htlc = UpdateFailMalformedHtlc(randomBytes32, 2, randomBytes32, 1111)
    val commit_sig = CommitSig(randomBytes32, randomBytes64, randomBytes64 :: randomBytes64 :: randomBytes64 :: Nil)
    val revoke_and_ack = RevokeAndAck(randomBytes32, scalar(0), point(1))
    val channel_announcement = ChannelAnnouncement(randomBytes64, randomBytes64, randomBytes64, randomBytes64, bin(7, 9), Block.RegtestGenesisBlock.hash, ShortChannelId(1), randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)
    val node_announcement = NodeAnnouncement(randomBytes64, bin(1, 2), 1, randomKey.publicKey, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", IPv4(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)).asInstanceOf[Inet4Address], 42000) :: Nil)
    val channel_update = ChannelUpdate(randomBytes64, Block.RegtestGenesisBlock.hash, ShortChannelId(1), 2, 42, 0, 3, MilliSatoshi(4), MilliSatoshi(5), 6, None)
    val announcement_signatures = AnnouncementSignatures(randomBytes32, ShortChannelId(42), randomBytes64, randomBytes64)
    val gossip_timestamp_filter = GossipTimestampFilter(Block.RegtestGenesisBlock.blockId, 100000, 1500)
    val query_short_channel_id = QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), None)
    val query_channel_range = QueryChannelRange(Block.RegtestGenesisBlock.blockId, 100000, 1500, Some(ExtendedQueryFlags.TIMESTAMPS_AND_CHECKSUMS))
    val reply_channel_range = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 100000, 1500, 1, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), Some(ExtendedQueryFlags.TIMESTAMPS_AND_CHECKSUMS), Some(ExtendedInfo(List(TimestampsAndChecksums(1, 1, 1, 1), TimestampsAndChecksums(2, 2, 2, 2), TimestampsAndChecksums(3, 3, 3, 3)))))
    val ping = Ping(100, bin(10, 1))
    val pong = Pong(bin(10, 1))
    val channel_reestablish = ChannelReestablish(randomBytes32, 242842L, 42L)

    val msgs: List[LightningMessage] =
      open :: accept :: funding_created :: funding_signed :: funding_locked :: update_fee :: shutdown :: closing_signed ::
        update_add_htlc :: update_fulfill_htlc :: update_fail_htlc :: update_fail_malformed_htlc :: commit_sig :: revoke_and_ack ::
        channel_announcement :: node_announcement :: channel_update :: gossip_timestamp_filter :: query_short_channel_id :: query_channel_range :: reply_channel_range :: announcement_signatures :: ping :: pong :: channel_reestablish :: Nil

    msgs.foreach {
      msg => {
        val encoded = lightningMessageCodec.encode(msg).require
        val decoded = lightningMessageCodec.decode(encoded).require
        assert(msg === decoded.value)
      }
    }
  }

  test("non-reg encoding type") {
    val refs = Map(
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001900000000000000008e0000000000003c69000000000045a6c4" -> QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), None),
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001601789c636000833e08659309a65c971d0100126e02e3" -> QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), None),
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001900000000000000008e0000000000003c69000000000045a6c4000400010204" -> QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), Some(EncodedQueryFlags(EncodingType.UNCOMPRESSED, List(1, 2, 4)))),
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001601789c636000833e08659309a65c971d0100126e02e3000c01789c6364620100000e0008" -> QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), Some(EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, List(1, 2, 4))))
    )
    refs.forall {
      case (bin, obj) =>
        lightningMessageCodec.decode(bin.toBitVector).require.value == obj && lightningMessageCodec.encode(obj).require == bin.toBitVector
    }
  }

  case class TestItem(msg: Any, hex: String)

  ignore("test vectors") {

    val query_channel_range = QueryChannelRange(Block.RegtestGenesisBlock.blockId, 100000, 1500, None)
    val query_channel_range_timestamps_checksums = QueryChannelRange(Block.RegtestGenesisBlock.blockId, 35000, 100, Some(ExtendedQueryFlags.TIMESTAMPS_AND_CHECKSUMS))
    val reply_channel_range = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 756230, 1500, 1, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), Some(ExtendedQueryFlags.TIMESTAMPS_AND_CHECKSUMS), None)
    val reply_channel_range_zlib = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 1600, 110, 1, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(265462))), Some(ExtendedQueryFlags.TIMESTAMPS_AND_CHECKSUMS), None)
    val reply_channel_range_timestamps_checksums = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 122334, 1500, 1, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(12355), ShortChannelId(489686), ShortChannelId(4645313))), Some(ExtendedQueryFlags.TIMESTAMPS_AND_CHECKSUMS), Some(ExtendedInfo(List(TimestampsAndChecksums(164545, 1111, 948165, 2222), TimestampsAndChecksums(489645, 3333, 4786864, 4444), TimestampsAndChecksums(46456, 5555, 9788415, 6666)))))
    val reply_channel_range_timestamps_checksums_zlib = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 500, 100, 1, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(ShortChannelId(1234545), ShortChannelId(4897484), ShortChannelId(4564676))), Some(ExtendedQueryFlags.TIMESTAMPS_AND_CHECKSUMS), Some(ExtendedInfo(List(TimestampsAndChecksums(164545, 1111, 948165, 2222), TimestampsAndChecksums(489645, 3333, 4786864, 4444), TimestampsAndChecksums(46456, 5555, 9788415, 6666)))))
    val query_short_channel_id = QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), None)
    val query_short_channel_id_zlib = QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(ShortChannelId(4564), ShortChannelId(178622), ShortChannelId(4564676))), None)
    val query_short_channel_id_flags = QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(12232), ShortChannelId(15556), ShortChannelId(4564676))), Some(EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, List(1, 2, 4))))
    val query_short_channel_id_flags_zlib = QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(ShortChannelId(14200), ShortChannelId(46645), ShortChannelId(4564676))), Some(EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, List(1, 2, 4))))

    val refs = List(
      query_channel_range,
      query_channel_range_timestamps_checksums,
      reply_channel_range,
      reply_channel_range_zlib,
      reply_channel_range_timestamps_checksums,
      reply_channel_range_timestamps_checksums_zlib,
      query_short_channel_id,
      query_short_channel_id_zlib,
      query_short_channel_id_flags,
      query_short_channel_id_flags_zlib
    )

    class EncodingTypeSerializer extends CustomSerializer[EncodingType](format => ({ null }, {
      case EncodingType.UNCOMPRESSED => JString("UNCOMPRESSED")
      case EncodingType.COMPRESSED_ZLIB => JString("COMPRESSED_ZLIB")
    }))

    class ExtendedQueryFlagsSerializer extends CustomSerializer[ExtendedQueryFlags](format => ({ null }, {
      case ExtendedQueryFlags.TIMESTAMPS_AND_CHECKSUMS => JString("TIMESTAMPS_AND_CHECKSUMS")
    }))

    implicit val formats = org.json4s.DefaultFormats.withTypeHintFieldName("type") +
      new EncodingTypeSerializer +
      new ExtendedQueryFlagsSerializer +
      new ByteVectorSerializer +
      new ByteVector32Serializer +
      new UInt64Serializer +
      new MilliSatoshiSerializer +
      new ShortChannelIdSerializer +
      new StateSerializer +
      new ShaChainSerializer +
      new PublicKeySerializer +
      new PrivateKeySerializer +
      new TransactionSerializer +
      new TransactionWithInputInfoSerializer +
      new InetSocketAddressSerializer +
      new OutPointSerializer +
      new OutPointKeySerializer +
      new InputInfoSerializer +
      new ColorSerializer +
      new RouteResponseSerializer +
      new ThrowableSerializer +
      new FailureMessageSerializer +
      new NodeAddressSerializer +
      new DirectionSerializer +
      new PaymentRequestSerializer +
      ShortTypeHints(List(
      classOf[QueryChannelRange],
      classOf[ReplyChannelRange],
      classOf[QueryShortChannelIds]))

    refs.foreach {
      obj =>
        val bin = lightningMessageCodec.encode(obj).require
        println(Serialization.writePretty(TestItem(obj, bin.toHex)))
    }

  }

  test("decode channel_update with htlc_maximum_msat") {
    // this was generated by c-lightning
    val bin = hex"010258fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf1792306226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f0005a100000200005bc75919010100060000000000000001000000010000000a000000003a699d00"
    val update = lightningMessageCodec.decode(bin.bits).require.value.asInstanceOf[ChannelUpdate]
    assert(update === ChannelUpdate(ByteVector64(hex"58fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf17923"), ByteVector32(hex"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"), ShortChannelId(0x5a10000020000L), 1539791129, 1, 1, 6, MilliSatoshi(1), MilliSatoshi(1), 10, Some(MilliSatoshi(980000000L))))
    val nodeId = PublicKey(hex"03370c9bac836e557eb4f017fe8f9cc047f44db39c1c4e410ff0f7be142b817ae4")
    assert(Announcements.checkSig(update, nodeId))
    val bin2 = ByteVector(lightningMessageCodec.encode(update).require.toByteArray)
    assert(bin === bin2)
  }
}
