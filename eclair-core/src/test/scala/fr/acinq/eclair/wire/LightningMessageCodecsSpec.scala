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
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.eclair.wire.ReplyChannelRangeTlv._
import org.scalatest.FunSuite
import scodec.{Codec, DecodeResult}
import scodec.bits.{BitVector, ByteVector, HexStringSyntax}

/**
 * Created by PM on 31/05/2016.
 */

class LightningMessageCodecsSpec extends FunSuite {

  def bin(len: Int, fill: Byte) = ByteVector.fill(len)(fill)

  def bin32(fill: Byte) = ByteVector32(bin(32, fill))

  def scalar(fill: Byte) = PrivateKey(ByteVector.fill(32)(fill))

  def point(fill: Byte) = PrivateKey(ByteVector.fill(32)(fill)).publicKey

  def publicKey(fill: Byte) = PrivateKey(ByteVector.fill(32)(fill)).publicKey

  test("channeldataoptional with truncation") {

    // encoding none
    assert(channeldataoptional.encode(None).require.bytes === ByteVector.empty)

    // decoding unrelated data (no magic)
    val unrelated = randomBytes(42)
    assert(channeldataoptional.decode(unrelated.bits).require === DecodeResult(None, unrelated.bits))

    // decoding empty data
    assert(channeldataoptional.decode(BitVector.empty).require === DecodeResult(None, BitVector.empty))

    // decoding a zero-size channel data with a remainder
    val zerodata = hex"fe47010000 00 deadbeef"
    assert(channeldataoptional.decode(zerodata.bits).require === DecodeResult(Some(ByteVector.empty), hex"deadbeef".bits))

    // nominal case (roundtrip)
    val data = randomBytes(5000)
    val bin = hex"fe47010000 fd1388" ++ data
    assert(channeldataoptional.encode(Some(data)).require.bytes === bin)
    assert(channeldataoptional.decode(bin.bits).require === DecodeResult(Some(data), BitVector.empty))

    // data too large
    val toolong = randomBytes(61000)
    assert(channeldataoptional.encode(Some(toolong)).require === BitVector.empty)
  }

  test("nonreg backup channel data") {

    val channelId = randomBytes32
    val signature = randomBytes64
    val key = randomKey
    val point = randomKey.publicKey
    val randomData = randomBytes(42)

    val refs = Map(
      (hex"0023" ++ channelId ++ signature, hex"") -> FundingSigned(channelId, signature, None),
      (hex"0023" ++ channelId ++ signature, hex"deadbeef") -> FundingSigned(channelId, signature, None),
      (hex"0023" ++ channelId ++ signature ++ hex"fe47010000 00", hex"") -> FundingSigned(channelId, signature, Some(ByteVector.empty)),
      (hex"0023" ++ channelId ++ signature ++ hex"fe47010000 00", hex"deadbeef") -> FundingSigned(channelId, signature, Some(ByteVector.empty)),
      (hex"0023" ++ channelId ++ signature ++ hex"fe47010000 07 cccccccccccccc", hex"") -> FundingSigned(channelId, signature, Some(hex"cccccccccccccc")),
      (hex"0023" ++ channelId ++ signature ++ hex"fe47010000 07 cccccccccccccc", hex"deadbeef") -> FundingSigned(channelId, signature, Some(hex"cccccccccccccc")),

      (hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value, hex"") -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, Some(key), Some(point), None),
      (hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value, hex"deadbeef") -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, Some(key), Some(point), None),
      (hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value ++ hex"fe47010000 00", hex"") -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, Some(key), Some(point), Some(ByteVector.empty)),
      (hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value ++ hex"fe47010000 00", hex"deadbeef") -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, Some(key), Some(point), Some(ByteVector.empty)),
      (hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value ++ hex"fe47010000 07 bbbbbbbbbbbbbb", hex"") -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, Some(key), Some(point), Some(hex"bbbbbbbbbbbbbb")),
      (hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value ++ hex"fe47010000 07 bbbbbbbbbbbbbb", hex"deadbeef") -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, Some(key), Some(point), Some(hex"bbbbbbbbbbbbbb")),

      (hex"0084" ++ channelId ++ signature ++ hex"0000", hex"") -> CommitSig(channelId, signature, Nil, None),
      (hex"0084" ++ channelId ++ signature ++ hex"0000", hex"deadbeef") -> CommitSig(channelId, signature, Nil, None),
      (hex"0084" ++ channelId ++ signature ++ hex"0000 fe47010000 00", hex"") -> CommitSig(channelId, signature, Nil, Some(ByteVector.empty)),
      (hex"0084" ++ channelId ++ signature ++ hex"0000 fe47010000 00", hex"deadbeef") -> CommitSig(channelId, signature, Nil, Some(ByteVector.empty)),
      (hex"0084" ++ channelId ++ signature ++ hex"0000 fe47010000 07 cccccccccccccc", hex"") -> CommitSig(channelId, signature, Nil, Some(hex"cccccccccccccc")),
      (hex"0084" ++ channelId ++ signature ++ hex"0000 fe47010000 07 cccccccccccccc", hex"deadbeef") -> CommitSig(channelId, signature, Nil, Some(hex"cccccccccccccc")),

      (hex"0085" ++ channelId ++ key.value ++ point.value, hex"") -> RevokeAndAck(channelId, key, point, None),
      (hex"0085" ++ channelId ++ key.value ++ point.value, hex"deadbeef") -> RevokeAndAck(channelId, key, point, None),
      (hex"0085" ++ channelId ++ key.value ++ point.value ++ hex" fe47010000 00", hex"") -> RevokeAndAck(channelId, key, point, Some(ByteVector.empty)),
      (hex"0085" ++ channelId ++ key.value ++ point.value ++ hex" fe47010000 00", hex"deadbeef") -> RevokeAndAck(channelId, key, point, Some(ByteVector.empty)),
      (hex"0085" ++ channelId ++ key.value ++ point.value ++ hex" fe47010000 07 cccccccccccccc", hex"") -> RevokeAndAck(channelId, key, point, Some(hex"cccccccccccccc")),
      (hex"0085" ++ channelId ++ key.value ++ point.value ++ hex" fe47010000 07 cccccccccccccc", hex"deadbeef") -> RevokeAndAck(channelId, key, point, Some(hex"cccccccccccccc")),

      (hex"0026" ++ channelId ++ hex"002a" ++ randomData, hex"") -> Shutdown(channelId, randomData, None),
      (hex"0026" ++ channelId ++ hex"002a" ++ randomData, hex"deadbeef") -> Shutdown(channelId, randomData, None),
      (hex"0026" ++ channelId ++ hex"002a" ++ randomData ++ hex"fe47010000 00", hex"") -> Shutdown(channelId, randomData, Some(ByteVector.empty)),
      (hex"0026" ++ channelId ++ hex"002a" ++ randomData ++ hex"fe47010000 00", hex"deadbeef") -> Shutdown(channelId, randomData, Some(ByteVector.empty)),
      (hex"0026" ++ channelId ++ hex"002a" ++ randomData ++ hex"fe47010000 07 cccccccccccccc", hex"") -> Shutdown(channelId, randomData, Some(hex"cccccccccccccc")),
      (hex"0026" ++ channelId ++ hex"002a" ++ randomData ++ hex"fe47010000 07 cccccccccccccc", hex"deadbeef") -> Shutdown(channelId, randomData, Some(hex"cccccccccccccc")),

      (hex"0027" ++ channelId ++ hex"00000000075bcd15" ++ signature, hex"") -> ClosingSigned(channelId, 123456789.sat, signature, None),
      (hex"0027" ++ channelId ++ hex"00000000075bcd15" ++ signature, hex"deadbeef") -> ClosingSigned(channelId, 123456789.sat, signature, None),
      (hex"0027" ++ channelId ++ hex"00000000075bcd15" ++ signature ++ hex"fe47010000 00", hex"") -> ClosingSigned(channelId, 123456789.sat, signature, Some(ByteVector.empty)),
      (hex"0027" ++ channelId ++ hex"00000000075bcd15" ++ signature ++ hex"fe47010000 00", hex"deadbeef") -> ClosingSigned(channelId, 123456789.sat, signature, Some(ByteVector.empty)),
      (hex"0027" ++ channelId ++ hex"00000000075bcd15" ++ signature ++ hex"fe47010000 07 cccccccccccccc", hex"") -> ClosingSigned(channelId, 123456789.sat, signature, Some(hex"cccccccccccccc")),
      (hex"0027" ++ channelId ++ hex"00000000075bcd15" ++ signature ++ hex"fe47010000 07 cccccccccccccc", hex"deadbeef") -> ClosingSigned(channelId, 123456789.sat, signature, Some(hex"cccccccccccccc"))
    )

    refs.foreach { case ((bin, remainder), init) =>
      assert(lightningMessageCodec.decode(bin.bits ++ remainder.bits).require === DecodeResult(init, remainder.bits))
      assert(lightningMessageCodec.encode(init).require === bin.bits)
    }
  }

  test("encode/decode init message") {
    case class TestCase(encoded: ByteVector, features: ByteVector, networks: List[ByteVector32], valid: Boolean, reEncoded: Option[ByteVector] = None)
    val chainHash1 = ByteVector32(hex"0101010101010101010101010101010101010101010101010101010101010101")
    val chainHash2 = ByteVector32(hex"0202020202020202020202020202020202020202020202020202020202020202")
    val testCases = Seq(
      TestCase(hex"0000 0000", hex"", Nil, valid = true), // no features
      TestCase(hex"0000 0002088a", hex"088a", Nil, valid = true), // no global features
      TestCase(hex"00020200 0000", hex"0200", Nil, valid = true, Some(hex"0000 00020200")), // no local features
      TestCase(hex"00020200 0002088a", hex"0a8a", Nil, valid = true, Some(hex"0000 00020a8a")), // local and global - no conflict - same size
      TestCase(hex"00020200 0003020002", hex"020202", Nil, valid = true, Some(hex"0000 0003020202")), // local and global - no conflict - different sizes
      TestCase(hex"00020a02 0002088a", hex"0a8a", Nil, valid = true, Some(hex"0000 00020a8a")), // local and global - conflict - same size
      TestCase(hex"00022200 000302aaa2", hex"02aaa2", Nil, valid = true, Some(hex"0000 000302aaa2")), // local and global - conflict - different sizes
      TestCase(hex"0000 0002088a 03012a05022aa2", hex"088a", Nil, valid = true), // unknown odd records
      TestCase(hex"0000 0002088a 03012a04022aa2", hex"088a", Nil, valid = false), // unknown even records
      TestCase(hex"0000 0002088a 0120010101010101010101010101010101010101010101010101010101010101", hex"088a", Nil, valid = false), // invalid tlv stream
      TestCase(hex"0000 0002088a 01200101010101010101010101010101010101010101010101010101010101010101", hex"088a", List(chainHash1), valid = true), // single network
      TestCase(hex"0000 0002088a 014001010101010101010101010101010101010101010101010101010101010101010202020202020202020202020202020202020202020202020202020202020202", hex"088a", List(chainHash1, chainHash2), valid = true), // multiple networks
      TestCase(hex"0000 0002088a 0120010101010101010101010101010101010101010101010101010101010101010103012a", hex"088a", List(chainHash1), valid = true), // network and unknown odd records
      TestCase(hex"0000 0002088a 0120010101010101010101010101010101010101010101010101010101010101010102012a", hex"088a", Nil, valid = false) // network and unknown even records
    )

    for (testCase <- testCases) {
      if (testCase.valid) {
        val init = initCodec.decode(testCase.encoded.bits).require.value
        assert(init.features === testCase.features)
        assert(init.networks === testCase.networks)
        val encoded = initCodec.encode(init).require
        assert(encoded.bytes === testCase.reEncoded.getOrElse(testCase.encoded))
        assert(initCodec.decode(encoded).require.value === init)
      } else {
        assert(initCodec.decode(testCase.encoded.bits).isFailure)
      }
    }
  }

  test("encode/decode live node_announcements") {
    val ann = hex"a58338c9660d135fd7d087eb62afd24a33562c54507a9334e79f0dc4f17d407e6d7c61f0e2f3d0d38599502f61704cf1ae93608df027014ade7ff592f27ce2690001025acdf50702d2eabbbacc7c25bbd73b39e65d28237705f7bde76f557e94fb41cb18a9ec00841122116c6e302e646563656e7465722e776f726c64000000000000000000000000000000130200000000000000000000ffffae8a0b082607"
    val bin = ann.bits

    val node = nodeAnnouncementCodec.decode(bin).require.value
    val bin2 = nodeAnnouncementCodec.encode(node).require
    assert(bin === bin2)
  }

  test("encode/decode open_channel") {
    val defaultOpen = OpenChannel(ByteVector32.Zeroes, ByteVector32.Zeroes, 1 sat, 1 msat, 1 sat, UInt64(1), 1 sat, 1 msat, 1, CltvExpiryDelta(1), 1, publicKey(1), point(2), point(3), point(4), point(5), point(6), 0.toByte)
    // Default encoding that completely omits the upfront_shutdown_script and trailing tlv stream.
    // To allow extending all messages with TLV streams, the upfront_shutdown_script was made mandatory in https://github.com/lightningnetwork/lightning-rfc/pull/714
    val defaultEncoded = hex"000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000100000000000000010000000000000001000000000000000100000000000000010000000100010001031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d076602531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe33703462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f703f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a00"
    case class TestCase(encoded: ByteVector, decoded: OpenChannel, reEncoded: Option[ByteVector] = None)
    val testCases = Seq(
      // legacy encoding without upfront_shutdown_script
      TestCase(defaultEncoded, defaultOpen, Some(defaultEncoded ++ hex"0000")),
      // empty upfront_shutdown_script
      TestCase(defaultEncoded ++ hex"0000", defaultOpen),
      // non-empty upfront_shutdown_script
      TestCase(defaultEncoded ++ hex"0004 01abcdef", defaultOpen.copy(upfrontShutdownScript = Some(hex"01abcdef"))),
      // missing upfront_shutdown_script + unknown odd tlv records
      TestCase(defaultEncoded ++ hex"0302002a 050102", defaultOpen.copy(tlvStream_opt = Some(TlvStream(Nil, Seq(GenericTlv(UInt64(3), hex"002a"), GenericTlv(UInt64(5), hex"02")))))),
      // empty upfront_shutdown_script + unknown odd tlv records: we don't encode the upfront_shutdown_script when a tlv stream is provided
      TestCase(defaultEncoded ++ hex"0000 0302002a 050102", defaultOpen.copy(tlvStream_opt = Some(TlvStream(Nil, Seq(GenericTlv(UInt64(3), hex"002a"), GenericTlv(UInt64(5), hex"02"))))), Some(defaultEncoded ++ hex"0302002a 050102")),
      // non-empty upfront_shutdown_script + unknown odd tlv records: we don't encode the upfront_shutdown_script when a tlv stream is provided
      TestCase(defaultEncoded ++ hex"0002 1234 0303010203", defaultOpen.copy(upfrontShutdownScript = Some(hex"1234"), tlvStream_opt = Some(TlvStream(Nil, Seq(GenericTlv(UInt64(3), hex"010203"))))), Some(defaultEncoded ++ hex"0303010203"))
    )

    for (testCase <- testCases) {
      val decoded = openChannelCodec.decode(testCase.encoded.bits).require.value
      assert(decoded === testCase.decoded)
      val reEncoded = openChannelCodec.encode(decoded).require.bytes
      assert(reEncoded === testCase.reEncoded.getOrElse(testCase.encoded))
    }
  }

  test("decode invalid open_channel") {
    val defaultEncoded = hex"000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000100000000000000010000000000000001000000000000000100000000000000010000000100010001031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d076602531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe33703462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f703f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a00"
    val testCases = Seq(
      defaultEncoded ++ hex"00", // truncated length
      defaultEncoded ++ hex"01", // truncated length
      defaultEncoded ++ hex"0004 123456", // truncated script
      defaultEncoded ++ hex"0000 02012a", // invalid tlv stream (unknown even record)
      defaultEncoded ++ hex"0000 01012a 030201", // invalid tlv stream (truncated)
      defaultEncoded ++ hex"02012a", // invalid tlv stream (unknown even record)
      defaultEncoded ++ hex"01012a 030201" // invalid tlv stream (truncated)
    )

    for (testCase <- testCases) {
      assert(openChannelCodec.decode(testCase.bits).isFailure, testCase.toHex)
    }
  }

  test("encode/decode accept_channel") {
    val defaultAccept = AcceptChannel(ByteVector32.Zeroes, 1 sat, UInt64(1), 1 sat, 1 msat, 1, CltvExpiryDelta(1), 1, publicKey(1), point(2), point(3), point(4), point(5), point(6))
    // Default encoding that completely omits the upfront_shutdown_script (nodes were supposed to encode it only if both
    // sides advertised support for option_upfront_shutdown_script).
    // To allow extending all messages with TLV streams, the upfront_shutdown_script was made mandatory in https://github.com/lightningnetwork/lightning-rfc/pull/714
    val defaultEncoded = hex"000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000001000000000000000100000000000000010000000100010001031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d076602531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe33703462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f703f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a"
    case class TestCase(encoded: ByteVector, decoded: AcceptChannel, reEncoded: Option[ByteVector] = None)
    val testCases = Seq(
      TestCase(defaultEncoded, defaultAccept, Some(defaultEncoded ++ hex"0000")), // legacy encoding without upfront_shutdown_script
      TestCase(defaultEncoded ++ hex"0000", defaultAccept), // empty upfront_shutdown_script
      TestCase(defaultEncoded ++ hex"0004 01abcdef", defaultAccept.copy(upfrontShutdownScript = Some(hex"01abcdef"))), // non-empty upfront_shutdown_script
      TestCase(defaultEncoded ++ hex"0000 010202a 030102", defaultAccept, Some(defaultEncoded ++ hex"0000")), // empty upfront_shutdown_script + unknown odd tlv records
      TestCase(defaultEncoded ++ hex"0002 1234 0303010203", defaultAccept.copy(upfrontShutdownScript = Some(hex"1234")), Some(defaultEncoded ++ hex"0002 1234")) // non-empty upfront_shutdown_script + unknown odd tlv records
    )

    for (testCase <- testCases) {
      val decoded = acceptChannelCodec.decode(testCase.encoded.bits).require.value
      assert(decoded === testCase.decoded)
      val reEncoded = acceptChannelCodec.encode(decoded).require.bytes
      assert(reEncoded === testCase.reEncoded.getOrElse(testCase.encoded))
    }
  }

  test("encode/decode all channel messages") {
    val open = OpenChannel(randomBytes32, randomBytes32, 3 sat, 4 msat, 5 sat, UInt64(6), 7 sat, 8 msat, 9, CltvExpiryDelta(10), 11, publicKey(1), point(2), point(3), point(4), point(5), point(6), 0.toByte)
    val accept = AcceptChannel(randomBytes32, 3 sat, UInt64(4), 5 sat, 6 msat, 7, CltvExpiryDelta(8), 9, publicKey(1), point(2), point(3), point(4), point(5), point(6))
    val funding_created = FundingCreated(randomBytes32, bin32(0), 3, randomBytes64)
    val funding_signed = FundingSigned(randomBytes32, randomBytes64)
    val funding_locked = FundingLocked(randomBytes32, point(2))
    val update_fee = UpdateFee(randomBytes32, 2)
    val shutdown = Shutdown(randomBytes32, bin(47, 0))
    val closing_signed = ClosingSigned(randomBytes32, 2 sat, randomBytes64)
    val update_add_htlc = UpdateAddHtlc(randomBytes32, 2, 3 msat, bin32(0), CltvExpiry(4), TestConstants.emptyOnionPacket)
    val update_fulfill_htlc = UpdateFulfillHtlc(randomBytes32, 2, bin32(0))
    val update_fail_htlc = UpdateFailHtlc(randomBytes32, 2, bin(154, 0))
    val update_fail_malformed_htlc = UpdateFailMalformedHtlc(randomBytes32, 2, randomBytes32, 1111)
    val commit_sig = CommitSig(randomBytes32, randomBytes64, randomBytes64 :: randomBytes64 :: randomBytes64 :: Nil)
    val revoke_and_ack = RevokeAndAck(randomBytes32, scalar(0), point(1))
    val channel_announcement = ChannelAnnouncement(randomBytes64, randomBytes64, randomBytes64, randomBytes64, bin(7, 9), Block.RegtestGenesisBlock.hash, ShortChannelId(1), randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)
    val node_announcement = NodeAnnouncement(randomBytes64, bin(1, 2), 1, randomKey.publicKey, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", IPv4(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)).asInstanceOf[Inet4Address], 42000) :: Nil)
    val channel_update = ChannelUpdate(randomBytes64, Block.RegtestGenesisBlock.hash, ShortChannelId(1), 2, 42, 0, CltvExpiryDelta(3), 4 msat, 5 msat, 6, None)
    val announcement_signatures = AnnouncementSignatures(randomBytes32, ShortChannelId(42), randomBytes64, randomBytes64)
    val gossip_timestamp_filter = GossipTimestampFilter(Block.RegtestGenesisBlock.blockId, 100000, 1500)
    val query_short_channel_id = QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), TlvStream.empty)
    val unknownTlv = GenericTlv(UInt64(5), ByteVector.fromValidHex("deadbeef"))
    val query_channel_range = QueryChannelRange(Block.RegtestGenesisBlock.blockId,
      100000,
      1500,
      TlvStream(QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_ALL) :: Nil, unknownTlv :: Nil))
    val reply_channel_range = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 100000, 1500, 1,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))),
      TlvStream(
        EncodedTimestamps(EncodingType.UNCOMPRESSED, List(Timestamps(1, 1), Timestamps(2, 2), Timestamps(3, 3))) :: EncodedChecksums(List(Checksums(1, 1), Checksums(2, 2), Checksums(3, 3))) :: Nil,
        unknownTlv :: Nil)
    )
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
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001900000000000000008e0000000000003c69000000000045a6c4"
        -> QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), TlvStream.empty),
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001601789c636000833e08659309a65c971d0100126e02e3"
        -> QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), TlvStream.empty),
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001900000000000000008e0000000000003c69000000000045a6c4010400010204"
        -> QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.UNCOMPRESSED, List(1, 2, 4)))),
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001601789c636000833e08659309a65c971d0100126e02e3010c01789c6364620100000e0008"
        -> QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, List(1, 2, 4))))
    )

    refs.forall {
      case (bin, obj) =>
        lightningMessageCodec.decode(bin.toBitVector).require.value == obj && lightningMessageCodec.encode(obj).require == bin.toBitVector
    }
  }

  case class TestItem(msg: Any, hex: String)

  test("test vectors for extended channel queries ") {
    val query_channel_range = QueryChannelRange(Block.RegtestGenesisBlock.blockId, 100000, 1500, TlvStream.empty)
    val query_channel_range_timestamps_checksums = QueryChannelRange(Block.RegtestGenesisBlock.blockId,
      35000,
      100,
      TlvStream(QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_ALL)))
    val reply_channel_range = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 756230, 1500, 1,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), None, None)
    val reply_channel_range_zlib = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 1600, 110, 1,
      EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(265462))), None, None)
    val reply_channel_range_timestamps_checksums = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 122334, 1500, 1,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(12355), ShortChannelId(489686), ShortChannelId(4645313))),
      Some(EncodedTimestamps(EncodingType.UNCOMPRESSED, List(Timestamps(164545, 948165), Timestamps(489645, 4786864), Timestamps(46456, 9788415)))),
      Some(EncodedChecksums(List(Checksums(1111, 2222), Checksums(3333, 4444), Checksums(5555, 6666)))))
    val reply_channel_range_timestamps_checksums_zlib = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 122334, 1500, 1,
      EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(ShortChannelId(12355), ShortChannelId(489686), ShortChannelId(4645313))),
      Some(EncodedTimestamps(EncodingType.COMPRESSED_ZLIB, List(Timestamps(164545, 948165), Timestamps(489645, 4786864), Timestamps(46456, 9788415)))),
      Some(EncodedChecksums(List(Checksums(1111, 2222), Checksums(3333, 4444), Checksums(5555, 6666)))))
    val query_short_channel_id = QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(142), ShortChannelId(15465), ShortChannelId(4564676))), TlvStream.empty)
    val query_short_channel_id_zlib = QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(ShortChannelId(4564), ShortChannelId(178622), ShortChannelId(4564676))), TlvStream.empty)
    val query_short_channel_id_flags = QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(12232), ShortChannelId(15556), ShortChannelId(4564676))), TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, List(1, 2, 4))))
    val query_short_channel_id_flags_zlib = QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(ShortChannelId(14200), ShortChannelId(46645), ShortChannelId(4564676))), TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, List(1, 2, 4))))

    val refs = Map(
      query_channel_range -> hex"01070f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206000186a0000005dc",
      query_channel_range_timestamps_checksums -> hex"01070f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206000088b800000064010103",
      reply_channel_range -> hex"01080f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206000b8a06000005dc01001900000000000000008e0000000000003c69000000000045a6c4",
      reply_channel_range_zlib -> hex"01080f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206000006400000006e01001601789c636000833e08659309a65878be010010a9023a",
      reply_channel_range_timestamps_checksums -> hex"01080f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e22060001ddde000005dc01001900000000000000304300000000000778d6000000000046e1c1011900000282c1000e77c5000778ad00490ab00000b57800955bff031800000457000008ae00000d050000115c000015b300001a0a",
      reply_channel_range_timestamps_checksums_zlib -> hex"01080f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e22060001ddde000005dc01001801789c63600001036730c55e710d4cbb3d3c080017c303b1012201789c63606a3ac8c0577e9481bd622d8327d7060686ad150c53a3ff0300554707db031800000457000008ae00000d050000115c000015b300001a0a",
      query_short_channel_id -> hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001900000000000000008e0000000000003c69000000000045a6c4",
      query_short_channel_id_zlib -> hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001801789c63600001c12b608a69e73e30edbaec0800203b040e",
      query_short_channel_id_flags -> hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e22060019000000000000002fc80000000000003cc4000000000045a6c4010c01789c6364620100000e0008",
      query_short_channel_id_flags_zlib -> hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001801789c63600001f30a30c5b0cd144cb92e3b020017c6034a010c01789c6364620100000e0008"
    )

    val items = refs.map { case (obj, refbin) =>
      val bin = lightningMessageCodec.encode(obj).require
      assert(refbin.bits === bin)
      TestItem(obj, bin.toHex)
    }

    // NB: uncomment this to update the test vectors

    /*class EncodingTypeSerializer extends CustomSerializer[EncodingType](format => ( {
      null
    }, {
      case EncodingType.UNCOMPRESSED => JString("UNCOMPRESSED")
      case EncodingType.COMPRESSED_ZLIB => JString("COMPRESSED_ZLIB")
    }))

    class ExtendedQueryFlagsSerializer extends CustomSerializer[QueryChannelRangeTlv.QueryFlags](format => ( {
      null
    }, {
      case QueryChannelRangeTlv.QueryFlags(flag) =>
        JString(((if (QueryChannelRangeTlv.QueryFlags.wantTimestamps(flag)) List("WANT_TIMESTAMPS") else List()) ::: (if (QueryChannelRangeTlv.QueryFlags.wantChecksums(flag)) List("WANT_CHECKSUMS") else List())) mkString (" | "))
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

    val json = Serialization.writePretty(items)
    println(json)*/
  }

  test("decode channel_update with htlc_maximum_msat") {
    // this was generated by c-lightning
    val bin = hex"010258fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf1792306226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f0005a100000200005bc75919010100060000000000000001000000010000000a000000003a699d00"
    val update = lightningMessageCodec.decode(bin.bits).require.value.asInstanceOf[ChannelUpdate]
    assert(update === ChannelUpdate(ByteVector64(hex"58fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf17923"), ByteVector32(hex"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"), ShortChannelId(0x5a10000020000L), 1539791129, 1, 1, CltvExpiryDelta(6), 1 msat, 1 msat, 10, Some(980000000 msat)))
    val nodeId = PublicKey(hex"03370c9bac836e557eb4f017fe8f9cc047f44db39c1c4e410ff0f7be142b817ae4")
    assert(Announcements.checkSig(update, nodeId))
    val bin2 = ByteVector(lightningMessageCodec.encode(update).require.toByteArray)
    assert(bin === bin2)
  }

  test("non-reg pay-to-open") {
    // we just need to make sure that old phoenix can decode new pay-to-open requests
    case class OldPayToOpenRequest(chainHash: ByteVector32,
                                   fundingSatoshis: Satoshi,
                                   amountMsat: MilliSatoshi,
                                   feeSatoshis: Satoshi,
                                   paymentHash: ByteVector32)

    import fr.acinq.eclair.wire.CommonCodecs._
    import scodec.codecs._
    val oldPayToOpenRequestCodec: Codec[OldPayToOpenRequest] = (
      ("chainHash" | bytes32) ::
        ("fundingSatoshis" | satoshi) ::
        ("pushMsat" | millisatoshi) ::
        ("feeSatoshis" | satoshi) ::
        ("paymentHash" | bytes32)).as[OldPayToOpenRequest]

    val p = PayToOpenRequest(randomBytes32, 12 mbtc, 12345 msat, 7 sat, randomBytes32, 10000 sat, 1000, 1234567890L, Some(UpdateAddHtlc(randomBytes32, 42, 12345 msat, randomBytes32, CltvExpiry(420), TestConstants.emptyOnionPacket)))
    val bits = payToOpenRequestCodec.encode(p).require
    val DecodeResult(oldp, remainder) = oldPayToOpenRequestCodec.decode(bits).require
    assert(oldp === OldPayToOpenRequest(p.chainHash, p.fundingSatoshis, p.amountMsat, p.feeSatoshis, p.paymentHash))
    assert(remainder.nonEmpty)
  }

}
