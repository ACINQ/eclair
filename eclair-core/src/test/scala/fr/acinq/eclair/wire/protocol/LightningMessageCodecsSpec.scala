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

import com.google.common.base.Charsets
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, ByteVector64, OutPoint, SatoshiLong, ScriptWitness, Transaction}
import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.Features.DataLossProtect
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{ChannelFlags, ChannelTypes}
import fr.acinq.eclair.json.JsonSerializers
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.protocol.ChannelTlv.{ChannelTypeTlv, PushAmountTlv, RequireConfirmedInputsTlv, UpfrontShutdownScriptTlv}
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol.ReplyChannelRangeTlv._
import fr.acinq.eclair.wire.protocol.TxRbfTlv.SharedOutputContributionTlv
import org.json4s.jackson.Serialization
import org.scalatest.funsuite.AnyFunSuite
import scodec.DecodeResult
import scodec.bits.{BinStringSyntax, ByteVector, HexStringSyntax}

import java.net.{Inet4Address, Inet6Address, InetAddress}

/**
 * Created by PM on 31/05/2016.
 */

class LightningMessageCodecsSpec extends AnyFunSuite {

  def bin(len: Int, fill: Byte) = ByteVector.fill(len)(fill)

  def bin32(fill: Byte) = ByteVector32(bin(32, fill))

  def scalar(fill: Byte) = PrivateKey(ByteVector.fill(32)(fill))

  def point(fill: Byte) = PrivateKey(ByteVector.fill(32)(fill)).publicKey

  def publicKey(fill: Byte) = PrivateKey(ByteVector.fill(32)(fill)).publicKey

  test("encode/decode init message") {
    case class TestCase(encoded: ByteVector, rawFeatures: ByteVector, networks: List[ByteVector32], address: Option[IPAddress], valid: Boolean, reEncoded: Option[ByteVector] = None)
    val chainHash1 = ByteVector32(hex"0101010101010101010101010101010101010101010101010101010101010101")
    val chainHash2 = ByteVector32(hex"0202020202020202020202020202020202020202020202020202020202020202")
    val remoteAddress1 = IPv4(InetAddress.getByAddress(Array[Byte](140.toByte, 82.toByte, 121.toByte, 3.toByte)).asInstanceOf[Inet4Address], 9735)
    val remoteAddress2 = IPv6(InetAddress.getByAddress(hex"b643 8bb1 c1f9 0556 487c 0acb 2ba3 3cc2".toArray).asInstanceOf[Inet6Address], 9736)
    val testCases = Seq(
      TestCase(hex"0000 0000", hex"", Nil, None, valid = true), // no features
      TestCase(hex"0000 0002088a", hex"088a", Nil, None, valid = true), // no global features
      TestCase(hex"00020200 0000", hex"0200", Nil, None, valid = true, Some(hex"0000 00020200")), // no local features
      TestCase(hex"00020200 0002088a", hex"0a8a", Nil, None, valid = true, Some(hex"0000 00020a8a")), // local and global - no conflict - same size
      TestCase(hex"00020200 0003020002", hex"020202", Nil, None, valid = true, Some(hex"0000 0003020202")), // local and global - no conflict - different sizes
      TestCase(hex"00020a02 0002088a", hex"0a8a", Nil, None, valid = true, Some(hex"0000 00020a8a")), // local and global - conflict - same size
      TestCase(hex"00022200 000302aaa2", hex"02aaa2", Nil, None, valid = true, Some(hex"0000 000302aaa2")), // local and global - conflict - different sizes
      TestCase(hex"0000 0002088a 03012a05022aa2", hex"088a", Nil, None, valid = true), // unknown odd records
      TestCase(hex"0000 0002088a 03012a04022aa2", hex"088a", Nil, None, valid = false), // unknown even records
      TestCase(hex"0000 0002088a 0120010101010101010101010101010101010101010101010101010101010101", hex"088a", Nil, None, valid = false), // invalid tlv stream
      TestCase(hex"0000 0002088a 01200101010101010101010101010101010101010101010101010101010101010101", hex"088a", List(chainHash1), None, valid = true), // single network
      TestCase(hex"0000 0002088a 01200101010101010101010101010101010101010101010101010101010101010101 0307018c5279032607", hex"088a", List(chainHash1), Some(remoteAddress1), valid = true), // single network and IPv4 address
      TestCase(hex"0000 0002088a 01200101010101010101010101010101010101010101010101010101010101010101 031302b6438bb1c1f90556487c0acb2ba33cc22608", hex"088a", List(chainHash1), Some(remoteAddress2), valid = true), // single network and IPv6 address
      TestCase(hex"0000 0002088a 014001010101010101010101010101010101010101010101010101010101010101010202020202020202020202020202020202020202020202020202020202020202", hex"088a", List(chainHash1, chainHash2), None, valid = true), // multiple networks
      TestCase(hex"0000 0002088a 01200101010101010101010101010101010101010101010101010101010101010101 c9012a", hex"088a", List(chainHash1), None, valid = true), // network and unknown odd records
      TestCase(hex"0000 0002088a 01200101010101010101010101010101010101010101010101010101010101010101 02012a", hex"088a", Nil, None, valid = false) // network and unknown even records
    )

    for (testCase <- testCases) {
      if (testCase.valid) {
        val init = initCodec.decode(testCase.encoded.bits).require.value
        assert(init.features.toByteVector == testCase.rawFeatures)
        assert(init.networks == testCase.networks)
        assert(init.remoteAddress_opt == testCase.address)
        val encoded = initCodec.encode(init).require
        assert(encoded.bytes == testCase.reEncoded.getOrElse(testCase.encoded))
        assert(initCodec.decode(encoded).require.value == init)
      } else {
        assert(initCodec.decode(testCase.encoded.bits).isFailure)
      }
    }
  }

  test("encode/decode warning") {
    val testCases = Seq(
      Warning("") -> hex"000100000000000000000000000000000000000000000000000000000000000000000000",
      Warning("connection-level issue") -> hex"0x000100000000000000000000000000000000000000000000000000000000000000000016636f6e6e656374696f6e2d6c6576656c206973737565",
      Warning(ByteVector32.One, "") -> hex"000101000000000000000000000000000000000000000000000000000000000000000000",
      Warning(ByteVector32.One, "channel-specific issue") -> hex"0x0001010000000000000000000000000000000000000000000000000000000000000000166368616e6e656c2d7370656369666963206973737565"
    )

    for ((warning, expected) <- testCases) {
      assert(lightningMessageCodec.encode(warning).require.bytes == expected)
      assert(lightningMessageCodec.decode(expected.bits).require.value == warning)
    }
  }

  test("nonreg generic tlv") {
    val channelId = randomBytes32()
    val signature = randomBytes64()
    val key = randomKey()
    val point = randomKey().publicKey
    val randomData = randomBytes(42)
    val tlvTag = UInt64(hex"47010000")

    val refs = Map(
      (hex"0023" ++ channelId ++ signature, hex"") -> FundingSigned(channelId, signature),
      (hex"0023" ++ channelId ++ signature ++ hex"fe47010000 00", hex"") -> FundingSigned(channelId, signature, TlvStream[FundingSignedTlv](Set.empty[FundingSignedTlv], Set(GenericTlv(tlvTag, ByteVector.empty)))),
      (hex"0023" ++ channelId ++ signature ++ hex"fe47010000 07 cccccccccccccc", hex"") -> FundingSigned(channelId, signature, TlvStream[FundingSignedTlv](Set.empty[FundingSignedTlv], Set(GenericTlv(tlvTag, hex"cccccccccccccc")))),

      (hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value, hex"") -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point),
      (hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value ++ hex"fe47010000 00", hex"") -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point, TlvStream[ChannelReestablishTlv](Set.empty[ChannelReestablishTlv], Set(GenericTlv(tlvTag, ByteVector.empty)))),
      (hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value ++ hex"fe47010000 07 bbbbbbbbbbbbbb", hex"") -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point, TlvStream[ChannelReestablishTlv](Set.empty[ChannelReestablishTlv], Set(GenericTlv(tlvTag, hex"bbbbbbbbbbbbbb")))),

      (hex"0084" ++ channelId ++ signature ++ hex"0000", hex"") -> CommitSig(channelId, signature, Nil),
      (hex"0084" ++ channelId ++ signature ++ hex"0000 fe47010000 00", hex"") -> CommitSig(channelId, signature, Nil, TlvStream[CommitSigTlv](Set.empty[CommitSigTlv], Set(GenericTlv(tlvTag, ByteVector.empty)))),
      (hex"0084" ++ channelId ++ signature ++ hex"0000 fe47010000 07 cccccccccccccc", hex"") -> CommitSig(channelId, signature, Nil, TlvStream[CommitSigTlv](Set.empty[CommitSigTlv], Set(GenericTlv(tlvTag, hex"cccccccccccccc")))),

      (hex"0085" ++ channelId ++ key.value ++ point.value, hex"") -> RevokeAndAck(channelId, key, point),
      (hex"0085" ++ channelId ++ key.value ++ point.value ++ hex" fe47010000 00", hex"") -> RevokeAndAck(channelId, key, point, TlvStream[RevokeAndAckTlv](Set.empty[RevokeAndAckTlv], Set(GenericTlv(tlvTag, ByteVector.empty)))),
      (hex"0085" ++ channelId ++ key.value ++ point.value ++ hex" fe47010000 07 cccccccccccccc", hex"") -> RevokeAndAck(channelId, key, point, TlvStream[RevokeAndAckTlv](Set.empty[RevokeAndAckTlv], Set(GenericTlv(tlvTag, hex"cccccccccccccc")))),

      (hex"0026" ++ channelId ++ hex"002a" ++ randomData, hex"") -> Shutdown(channelId, randomData),
      (hex"0026" ++ channelId ++ hex"002a" ++ randomData ++ hex"fe47010000 00", hex"") -> Shutdown(channelId, randomData, TlvStream[ShutdownTlv](Set.empty[ShutdownTlv], Set(GenericTlv(tlvTag, ByteVector.empty)))),
      (hex"0026" ++ channelId ++ hex"002a" ++ randomData ++ hex"fe47010000 07 cccccccccccccc", hex"") -> Shutdown(channelId, randomData, TlvStream[ShutdownTlv](Set.empty[ShutdownTlv], Set(GenericTlv(tlvTag, hex"cccccccccccccc")))),

      (hex"0027" ++ channelId ++ hex"00000000075bcd15" ++ signature, hex"") -> ClosingSigned(channelId, 123456789.sat, signature),
      (hex"0027" ++ channelId ++ hex"00000000075bcd15" ++ signature ++ hex"fe47010000 00", hex"") -> ClosingSigned(channelId, 123456789.sat, signature, TlvStream[ClosingSignedTlv](Set.empty[ClosingSignedTlv], Set(GenericTlv(tlvTag, ByteVector.empty)))),
      (hex"0027" ++ channelId ++ hex"00000000075bcd15" ++ signature ++ hex"fe47010000 07 cccccccccccccc", hex"") -> ClosingSigned(channelId, 123456789.sat, signature, TlvStream[ClosingSignedTlv](Set.empty[ClosingSignedTlv], Set(GenericTlv(tlvTag, hex"cccccccccccccc")))),
    )

    refs.foreach { case ((bin, remainder), msg) =>
      assert(lightningMessageCodec.decode(bin.bits ++ remainder.bits).require == DecodeResult(msg, remainder.bits))
      assert(lightningMessageCodec.encode(msg).require == bin.bits)
    }
  }

  test("encode/decode live node_announcements") {
    val ann = hex"a58338c9660d135fd7d087eb62afd24a33562c54507a9334e79f0dc4f17d407e6d7c61f0e2f3d0d38599502f61704cf1ae93608df027014ade7ff592f27ce2690001025acdf50702d2eabbbacc7c25bbd73b39e65d28237705f7bde76f557e94fb41cb18a9ec00841122116c6e302e646563656e7465722e776f726c64000000000000000000000000000000130200000000000000000000ffffae8a0b082607"
    val bin = ann.bits

    val node = nodeAnnouncementCodec.decode(bin).require.value
    val bin2 = nodeAnnouncementCodec.encode(node).require
    assert(bin == bin2)
  }

  test("encode/decode interactive-tx messages") {
    val channelId1 = ByteVector32(hex"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    val channelId2 = ByteVector32(hex"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    val signature = ByteVector64(hex"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    // This is a random mainnet transaction.
    val txBin1 = hex"020000000001014ade359c5deb7c1cde2e94f401854658f97d7fa31c17ce9a831db253120a0a410100000017160014eb9a5bd79194a23d19d6ec473c768fb74f9ed32cffffffff021ca408000000000017a914946118f24bb7b37d5e9e39579e4a411e70f5b6a08763e703000000000017a9143638b2602d11f934c04abc6adb1494f69d1f14af8702473044022059ddd943b399211e4266a349f26b3289979e29f9b067792c6cfa8cc5ae25f44602204d627a5a5b603d0562e7969011fb3d64908af90a3ec7c876eaa9baf61e1958af012102f5188df1da92ed818581c29778047800ed6635788aa09d9469f7d17628f7323300000000"
    val tx1 = Transaction.read(txBin1.toArray)
    // This is random, longer mainnet transaction.
    val txBin2 = hex"0200000000010142180a8812fc79a3da7fb2471eff3e22d7faee990604c2ba7f2fc8dfb15b550a0200000000feffffff030f241800000000001976a9146774040642a78ca3b8b395e70f8391b21ec026fc88ac4a155801000000001600148d2e0b57adcb8869e603fd35b5179caf053361253b1d010000000000160014e032f4f4b9f8611df0d30a20648c190c263bbc33024730440220506005aa347f5b698542cafcb4f1a10250aeb52a609d6fd67ef68f9c1a5d954302206b9bb844343f4012bccd9d08a0f5430afb9549555a3252e499be7df97aae477a012103976d6b3eea3de4b056cd88cdfd50a22daf121e0fb5c6e45ba0f40e1effbd275a00000000"
    val tx2 = Transaction.read(txBin2.toArray)
    val testCases = Seq(
      TxAddInput(channelId1, UInt64(561), Some(tx1), 1, 5) -> hex"0042 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000231 00f7 020000000001014ade359c5deb7c1cde2e94f401854658f97d7fa31c17ce9a831db253120a0a410100000017160014eb9a5bd79194a23d19d6ec473c768fb74f9ed32cffffffff021ca408000000000017a914946118f24bb7b37d5e9e39579e4a411e70f5b6a08763e703000000000017a9143638b2602d11f934c04abc6adb1494f69d1f14af8702473044022059ddd943b399211e4266a349f26b3289979e29f9b067792c6cfa8cc5ae25f44602204d627a5a5b603d0562e7969011fb3d64908af90a3ec7c876eaa9baf61e1958af012102f5188df1da92ed818581c29778047800ed6635788aa09d9469f7d17628f7323300000000 00000001 00000005",
      TxAddInput(channelId2, UInt64(0), Some(tx2), 2, 0) -> hex"0042 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 0000000000000000 0100 0200000000010142180a8812fc79a3da7fb2471eff3e22d7faee990604c2ba7f2fc8dfb15b550a0200000000feffffff030f241800000000001976a9146774040642a78ca3b8b395e70f8391b21ec026fc88ac4a155801000000001600148d2e0b57adcb8869e603fd35b5179caf053361253b1d010000000000160014e032f4f4b9f8611df0d30a20648c190c263bbc33024730440220506005aa347f5b698542cafcb4f1a10250aeb52a609d6fd67ef68f9c1a5d954302206b9bb844343f4012bccd9d08a0f5430afb9549555a3252e499be7df97aae477a012103976d6b3eea3de4b056cd88cdfd50a22daf121e0fb5c6e45ba0f40e1effbd275a00000000 00000002 00000000",
      TxAddInput(channelId1, UInt64(561), Some(tx1), 0, 0) -> hex"0042 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000231 00f7 020000000001014ade359c5deb7c1cde2e94f401854658f97d7fa31c17ce9a831db253120a0a410100000017160014eb9a5bd79194a23d19d6ec473c768fb74f9ed32cffffffff021ca408000000000017a914946118f24bb7b37d5e9e39579e4a411e70f5b6a08763e703000000000017a9143638b2602d11f934c04abc6adb1494f69d1f14af8702473044022059ddd943b399211e4266a349f26b3289979e29f9b067792c6cfa8cc5ae25f44602204d627a5a5b603d0562e7969011fb3d64908af90a3ec7c876eaa9baf61e1958af012102f5188df1da92ed818581c29778047800ed6635788aa09d9469f7d17628f7323300000000 00000000 00000000",
      TxAddInput(channelId1, UInt64(561), OutPoint(tx1, 1), 5) -> hex"0042 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000231 0000 00000001 00000005 fd04512006f125a8ef64eb5a25826190dc28f15b85dc1adcfc7a178eef393ea325c02e1f",
      TxAddOutput(channelId1, UInt64(1105), 2047 sat, hex"00149357014afd0ccd265658c9ae81efa995e771f472") -> hex"0043 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000451 00000000000007ff 0016 00149357014afd0ccd265658c9ae81efa995e771f472",
      TxAddOutput(channelId1, UInt64(1105), 2047 sat, hex"00149357014afd0ccd265658c9ae81efa995e771f472", TlvStream(Set.empty[TxAddOutputTlv], Set(GenericTlv(UInt64(301), hex"2a")))) -> hex"0043 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000451 00000000000007ff 0016 00149357014afd0ccd265658c9ae81efa995e771f472 fd012d012a",
      TxRemoveInput(channelId2, UInt64(561)) -> hex"0044 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 0000000000000231",
      TxRemoveOutput(channelId1, UInt64(1)) -> hex"0045 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000001",
      TxComplete(channelId1) -> hex"0046 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      TxComplete(channelId1, TlvStream(Set.empty[TxCompleteTlv], Set(GenericTlv(UInt64(231), hex"deadbeef"), GenericTlv(UInt64(507), hex"")))) -> hex"0046 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa e704deadbeef fd01fb00",
      TxSignatures(channelId1, tx2, Seq(ScriptWitness(Seq(hex"dead", hex"beef")), ScriptWitness(Seq(hex"", hex"01010101", hex"", hex"02"))), None) -> hex"0047 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa fc7aa8845f192959202c1b7ff704e7cbddded463c05e844676a94ccb4bed69f1 0002 00020002dead0002beef 0004 00000004010101010000000102",
      TxSignatures(channelId1, tx2, Seq(ScriptWitness(Seq(hex"dead", hex"beef")), ScriptWitness(Seq(hex"", hex"01010101", hex"", hex"02"))), Some(signature)) -> hex"0047 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa fc7aa8845f192959202c1b7ff704e7cbddded463c05e844676a94ccb4bed69f1 0002 00020002dead0002beef 0004 00000004010101010000000102 fd0259 40 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      TxSignatures(channelId2, tx1, Nil, None) -> hex"0047 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 1f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 0000",
      TxSignatures(channelId2, tx1, Nil, Some(signature)) -> hex"0047 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 1f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 0000 fd0259 40 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      TxInitRbf(channelId1, 8388607, FeeratePerKw(4000 sat)) -> hex"0048 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 007fffff 00000fa0",
      TxInitRbf(channelId1, 0, FeeratePerKw(4000 sat), TlvStream[TxInitRbfTlv](SharedOutputContributionTlv(5000 sat))) -> hex"0048 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000 00000fa0 00021388",
      TxAckRbf(channelId2) -> hex"0049 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      TxAckRbf(channelId2, TlvStream[TxAckRbfTlv](SharedOutputContributionTlv(450000 sat))) -> hex"0049 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 000306ddd0",
      TxAbort(channelId1, hex"") -> hex"004a aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000",
      TxAbort(channelId1, ByteVector.view("internal error".getBytes(Charsets.US_ASCII))) -> hex"004a aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 000e 696e7465726e616c206572726f72",
    )
    testCases.foreach { case (message, bin) =>
      val decoded = lightningMessageCodec.decode(bin.bits).require
      assert(decoded.remainder.isEmpty)
      assert(decoded.value == message)
      val encoded = lightningMessageCodec.encode(message).require.bytes
      assert(encoded == bin)
    }
  }

  test("encode/decode open_channel") {
    val defaultOpen = OpenChannel(ByteVector32.Zeroes, ByteVector32.Zeroes, 1 sat, 1 msat, 1 sat, UInt64(1), 1 sat, 1 msat, FeeratePerKw(1 sat), CltvExpiryDelta(1), 1, publicKey(1), point(2), point(3), point(4), point(5), point(6), ChannelFlags.Private)
    // Legacy encoding that omits the upfront_shutdown_script and trailing tlv stream.
    // To allow extending all messages with TLV streams, the upfront_shutdown_script was moved to a TLV stream extension
    // in https://github.com/lightningnetwork/lightning-rfc/pull/714 and made mandatory when including a TLV stream.
    // We don't make it mandatory at the codec level: it's the job of the actor creating the message to include it.
    val defaultEncoded = hex"000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000100000000000000010000000000000001000000000000000100000000000000010000000100010001031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d076602531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe33703462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f703f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a00"
    val testCases = Map(
      // legacy encoding without upfront_shutdown_script
      defaultEncoded -> defaultOpen,
      // empty upfront_shutdown_script
      defaultEncoded ++ hex"0000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty))),
      // non-empty upfront_shutdown_script
      defaultEncoded ++ hex"0004 01abcdef" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(hex"01abcdef"))),
      // missing upfront_shutdown_script + unknown odd tlv records
      defaultEncoded ++ hex"0302002a 050102" -> defaultOpen.copy(tlvStream = TlvStream(Set.empty[OpenChannelTlv], Set(GenericTlv(UInt64(3), hex"002a"), GenericTlv(UInt64(5), hex"02")))),
      // empty upfront_shutdown_script + unknown odd tlv records
      defaultEncoded ++ hex"0000 0302002a 050102" -> defaultOpen.copy(tlvStream = TlvStream(Set[OpenChannelTlv](ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty)), Set(GenericTlv(UInt64(3), hex"002a"), GenericTlv(UInt64(5), hex"02")))),
      // non-empty upfront_shutdown_script + unknown odd tlv records
      defaultEncoded ++ hex"0002 1234 0303010203" -> defaultOpen.copy(tlvStream = TlvStream(Set[OpenChannelTlv](ChannelTlv.UpfrontShutdownScriptTlv(hex"1234")), Set(GenericTlv(UInt64(3), hex"010203")))),
      // empty upfront_shutdown_script + default channel type
      defaultEncoded ++ hex"0000" ++ hex"0100" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.Standard()))),
      // empty upfront_shutdown_script + unsupported channel type
      defaultEncoded ++ hex"0000" ++ hex"0103501000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.UnsupportedChannelType(Features(Features.StaticRemoteKey -> FeatureSupport.Mandatory, Features.AnchorOutputs -> FeatureSupport.Mandatory, Features.AnchorOutputsZeroFeeHtlcTx -> FeatureSupport.Mandatory))))),
      // empty upfront_shutdown_script + channel type
      defaultEncoded ++ hex"0000" ++ hex"01021000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.StaticRemoteKey()))),
      // non-empty upfront_shutdown_script + channel type
      defaultEncoded ++ hex"0004 01abcdef" ++ hex"0103101000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(hex"01abcdef"), ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputs()))),
      defaultEncoded ++ hex"0002 abcd" ++ hex"0103401000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(hex"abcd"), ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()))),
      // empty upfront_shutdown_script + channel type (scid-alias)
      defaultEncoded ++ hex"0000" ++ hex"0106 400000000000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.Standard(scidAlias = true)))),
      defaultEncoded ++ hex"0000" ++ hex"0106 400000001000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.StaticRemoteKey(scidAlias = true)))),
      defaultEncoded ++ hex"0000" ++ hex"0106 400000101000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputs(scidAlias = true)))),
      defaultEncoded ++ hex"0000" ++ hex"0106 400000401000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true)))),
      // empty upfront_shutdown_script + channel type (zeroconf)
      defaultEncoded ++ hex"0000" ++ hex"0107 04000000000000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.Standard(zeroConf = true)))),
      defaultEncoded ++ hex"0000" ++ hex"0107 04000000001000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.StaticRemoteKey(zeroConf = true)))),
      defaultEncoded ++ hex"0000" ++ hex"0107 04000000101000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputs(zeroConf = true)))),
      defaultEncoded ++ hex"0000" ++ hex"0107 04000000401000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(zeroConf = true)))),
      // empty upfront_shutdown_script + channel type (scid-alias + zeroconf)
      defaultEncoded ++ hex"0000" ++ hex"0107 04400000000000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.Standard(scidAlias = true, zeroConf = true)))),
      defaultEncoded ++ hex"0000" ++ hex"0107 04400000001000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.StaticRemoteKey(scidAlias = true, zeroConf = true)))),
      defaultEncoded ++ hex"0000" ++ hex"0107 04400000101000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputs(scidAlias = true, zeroConf = true)))),
      defaultEncoded ++ hex"0000" ++ hex"0107 04400000401000" -> defaultOpen.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true, zeroConf = true)))),
    )

    for ((encoded, expected) <- testCases) {
      val decoded = openChannelCodec.decode(encoded.bits).require.value
      assert(decoded == expected)
      val reEncoded = openChannelCodec.encode(decoded).require.bytes
      assert(reEncoded == encoded)
    }
  }

  test("decode invalid open_channel") {
    val defaultEncoded = hex"000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001000000000000000100000000000000010000000000000001000000000000000100000000000000010000000100010001031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d076602531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe33703462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f703f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a00"
    val testCases = Seq(
      defaultEncoded ++ hex"00", // truncated length
      defaultEncoded ++ hex"01", // truncated length
      defaultEncoded ++ hex"0004 123456", // truncated upfront_shutdown_script
      defaultEncoded ++ hex"0000 01040101", // truncated channel type
      defaultEncoded ++ hex"0000 02012a", // invalid tlv stream (unknown even record)
      defaultEncoded ++ hex"0000 01012a 030201", // invalid tlv stream (truncated)
      defaultEncoded ++ hex"02012a", // invalid tlv stream (unknown even record)
      defaultEncoded ++ hex"01012a 030201" // invalid tlv stream (truncated)
    )

    for (testCase <- testCases) {
      assert(openChannelCodec.decode(testCase.bits).isFailure, testCase.toHex)
    }
  }

  test("encode/decode open_channel (dual funding)") {
    val defaultOpen = OpenDualFundedChannel(ByteVector32.Zeroes, ByteVector32.One, FeeratePerKw(5000 sat), FeeratePerKw(4000 sat), 250_000 sat, 500 sat, UInt64(50_000), 15 msat, CltvExpiryDelta(144), 483, 650_000, publicKey(1), publicKey(2), publicKey(3), publicKey(4), publicKey(5), publicKey(6), publicKey(7), ChannelFlags(true))
    val defaultEncoded = hex"0040 0000000000000000000000000000000000000000000000000000000000000000 0100000000000000000000000000000000000000000000000000000000000000 00001388 00000fa0 000000000003d090 00000000000001f4 000000000000c350 000000000000000f 0090 01e3 0009eb10 031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f 024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766 02531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe337 03462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b 0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f7 03f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a 02989c0b76cb563971fdc9bef31ec06c3560f3249d6ee9e5d83c57625596e05f6f 01"
    val testCases = Seq(
      defaultOpen -> defaultEncoded,
      defaultOpen.copy(tlvStream = TlvStream(ChannelTypeTlv(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()))) -> (defaultEncoded ++ hex"0103401000"),
      defaultOpen.copy(tlvStream = TlvStream(UpfrontShutdownScriptTlv(hex"00143adb2d0445c4d491cc7568b10323bd6615a91283"), ChannelTypeTlv(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()))) -> (defaultEncoded ++ hex"001600143adb2d0445c4d491cc7568b10323bd6615a91283 0103401000"),
      defaultOpen.copy(tlvStream = TlvStream(ChannelTypeTlv(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()), PushAmountTlv(1105 msat))) -> (defaultEncoded ++ hex"0103401000 fe47000007020451"),
      defaultOpen.copy(tlvStream = TlvStream(ChannelTypeTlv(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()), RequireConfirmedInputsTlv())) -> (defaultEncoded ++ hex"0103401000 0200")
    )
    testCases.foreach { case (open, bin) =>
      val decoded = lightningMessageCodec.decode(bin.bits).require.value
      assert(decoded == open)
      val encoded = lightningMessageCodec.encode(open).require.bytes
      assert(encoded == bin)
    }
  }

  test("decode open_channel with unknown channel flags (dual funding)") {
    val defaultOpen = OpenDualFundedChannel(ByteVector32.Zeroes, ByteVector32.One, FeeratePerKw(5000 sat), FeeratePerKw(4000 sat), 250_000 sat, 500 sat, UInt64(50_000), 15 msat, CltvExpiryDelta(144), 483, 650_000, publicKey(1), publicKey(2), publicKey(3), publicKey(4), publicKey(5), publicKey(6), publicKey(7), ChannelFlags(true))
    val defaultEncodedWithoutFlags = hex"0040 0000000000000000000000000000000000000000000000000000000000000000 0100000000000000000000000000000000000000000000000000000000000000 00001388 00000fa0 000000000003d090 00000000000001f4 000000000000c350 000000000000000f 0090 01e3 0009eb10 031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f 024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766 02531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe337 03462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b 0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f7 03f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a 02989c0b76cb563971fdc9bef31ec06c3560f3249d6ee9e5d83c57625596e05f6f"
    val testCases = Seq(
      defaultEncodedWithoutFlags ++ hex"00" -> ChannelFlags(false),
      defaultEncodedWithoutFlags ++ hex"a2" -> ChannelFlags(false),
      defaultEncodedWithoutFlags ++ hex"ff" -> ChannelFlags(true),
    )
    testCases.foreach { case (bin, flags) =>
      val decoded = lightningMessageCodec.decode(bin.bits).require.value
      assert(decoded == defaultOpen.copy(channelFlags = flags))
    }
  }

  test("encode/decode accept_channel") {
    val defaultAccept = AcceptChannel(ByteVector32.Zeroes, 1 sat, UInt64(1), 1 sat, 1 msat, 1, CltvExpiryDelta(1), 1, publicKey(1), point(2), point(3), point(4), point(5), point(6))
    // Legacy encoding that omits the upfront_shutdown_script and trailing tlv stream.
    // To allow extending all messages with TLV streams, the upfront_shutdown_script was moved to a TLV stream extension
    // in https://github.com/lightningnetwork/lightning-rfc/pull/714 and made mandatory when including a TLV stream.
    // We don't make it mandatory at the codec level: it's the job of the actor creating the message to include it.
    val defaultEncoded = hex"000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000001000000000000000100000000000000010000000100010001031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d076602531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe33703462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f703f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a"
    val testCases = Map(
      defaultEncoded -> defaultAccept, // legacy encoding without upfront_shutdown_script
      defaultEncoded ++ hex"0000" -> defaultAccept.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty))), // empty upfront_shutdown_script
      defaultEncoded ++ hex"0000" ++ hex"0100" -> defaultAccept.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.Standard()))), // empty upfront_shutdown_script with channel type
      defaultEncoded ++ hex"0004 01abcdef" -> defaultAccept.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(hex"01abcdef"))), // non-empty upfront_shutdown_script
      defaultEncoded ++ hex"0004 01abcdef" ++ hex"01021000" -> defaultAccept.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(hex"01abcdef"), ChannelTlv.ChannelTypeTlv(ChannelTypes.StaticRemoteKey()))), // non-empty upfront_shutdown_script with channel type
      defaultEncoded ++ hex"0000 0302002a 050102" -> defaultAccept.copy(tlvStream = TlvStream(Set[AcceptChannelTlv](ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty)), Set(GenericTlv(UInt64(3), hex"002a"), GenericTlv(UInt64(5), hex"02")))), // empty upfront_shutdown_script + unknown odd tlv records
      defaultEncoded ++ hex"0002 1234 0303010203" -> defaultAccept.copy(tlvStream = TlvStream(Set[AcceptChannelTlv](ChannelTlv.UpfrontShutdownScriptTlv(hex"1234")), Set(GenericTlv(UInt64(3), hex"010203")))), // non-empty upfront_shutdown_script + unknown odd tlv records
      defaultEncoded ++ hex"0303010203 05020123" -> defaultAccept.copy(tlvStream = TlvStream(Set.empty[AcceptChannelTlv], Set(GenericTlv(UInt64(3), hex"010203"), GenericTlv(UInt64(5), hex"0123")))) // no upfront_shutdown_script + unknown odd tlv records
    )

    for ((encoded, expected) <- testCases) {
      val decoded = acceptChannelCodec.decode(encoded.bits).require.value
      assert(decoded == expected)
      val reEncoded = acceptChannelCodec.encode(decoded).require.bytes
      assert(reEncoded == encoded)
    }
  }

  test("encode/decode accept_channel (dual funding)") {
    val defaultAccept = AcceptDualFundedChannel(ByteVector32.One, 50_000 sat, 473 sat, UInt64(100_000_000), 1 msat, 6, CltvExpiryDelta(144), 50, publicKey(1), point(2), point(3), point(4), point(5), point(6), point(7))
    val defaultEncoded = hex"0041 0100000000000000000000000000000000000000000000000000000000000000 000000000000c350 00000000000001d9 0000000005f5e100 0000000000000001 00000006 0090 0032 031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f 024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766 02531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe337 03462779ad4aad39514614751a71085f2f10e1c7a593e4e030efb5b8721ce55b0b 0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f7 03f006a18d5653c4edf5391ff23a61f03ff83d237e880ee61187fa9f379a028e0a 02989c0b76cb563971fdc9bef31ec06c3560f3249d6ee9e5d83c57625596e05f6f"
    val testCases = Seq(
      defaultAccept -> defaultEncoded,
      defaultAccept.copy(tlvStream = TlvStream(ChannelTypeTlv(ChannelTypes.StaticRemoteKey()))) -> (defaultEncoded ++ hex"01021000"),
      defaultAccept.copy(tlvStream = TlvStream(ChannelTypeTlv(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()), PushAmountTlv(1729 msat))) -> (defaultEncoded ++ hex"0103401000 fe470000070206c1"),
      defaultAccept.copy(tlvStream = TlvStream(ChannelTypeTlv(ChannelTypes.StaticRemoteKey()), RequireConfirmedInputsTlv())) -> (defaultEncoded ++ hex"01021000 0200")
    )
    testCases.foreach { case (accept, bin) =>
      val decoded = lightningMessageCodec.decode(bin.bits).require.value
      assert(decoded == accept)
      val encoded = lightningMessageCodec.encode(accept).require.bytes
      assert(encoded == bin)
    }
  }

  test("encode/decode closing_signed") {
    val defaultSig = ByteVector64(hex"01010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101")
    val testCases = Seq(
      hex"0100000000000000000000000000000000000000000000000000000000000000 0000000000000000 00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" -> ClosingSigned(ByteVector32.One, 0 sat, ByteVector64.Zeroes),
      hex"0100000000000000000000000000000000000000000000000000000000000000 00000000000003e8 00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" -> ClosingSigned(ByteVector32.One, 1000 sat, ByteVector64.Zeroes),
      hex"0100000000000000000000000000000000000000000000000000000000000000 00000000000005dc 01010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101" -> ClosingSigned(ByteVector32.One, 1500 sat, defaultSig),
      hex"0100000000000000000000000000000000000000000000000000000000000000 00000000000005dc 00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000 0110000000000000006400000000000007d0" -> ClosingSigned(ByteVector32.One, 1500 sat, ByteVector64.Zeroes, TlvStream(ClosingSignedTlv.FeeRange(100 sat, 2000 sat))),
      hex"0100000000000000000000000000000000000000000000000000000000000000 00000000000003e8 01010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101 0110000000000000006400000000000007d0" -> ClosingSigned(ByteVector32.One, 1000 sat, defaultSig, TlvStream(ClosingSignedTlv.FeeRange(100 sat, 2000 sat))),
      hex"0100000000000000000000000000000000000000000000000000000000000000 0000000000000064 00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000 0110000000000000006400000000000003e8 030401020304" -> ClosingSigned(ByteVector32.One, 100 sat, ByteVector64.Zeroes, TlvStream(Set[ClosingSignedTlv](ClosingSignedTlv.FeeRange(100 sat, 1000 sat)), Set(GenericTlv(UInt64(3), hex"01020304")))),
    )

    for ((encoded, expected) <- testCases) {
      val decoded = closingSignedCodec.decode(encoded.bits).require.value
      assert(decoded == expected)
      val reEncoded = closingSignedCodec.encode(decoded).require.bytes
      assert(reEncoded == encoded)
    }
  }

  test("encode/decode all channel messages") {
    val unknownTlv = GenericTlv(UInt64(5), ByteVector.fromValidHex("deadbeef"))
    val msgs = List(
      OpenChannel(randomBytes32(), randomBytes32(), 3 sat, 4 msat, 5 sat, UInt64(6), 7 sat, 8 msat, FeeratePerKw(9 sat), CltvExpiryDelta(10), 11, publicKey(1), point(2), point(3), point(4), point(5), point(6), ChannelFlags.Private),
      AcceptChannel(randomBytes32(), 3 sat, UInt64(4), 5 sat, 6 msat, 7, CltvExpiryDelta(8), 9, publicKey(1), point(2), point(3), point(4), point(5), point(6)),
      FundingCreated(randomBytes32(), bin32(0), 3, randomBytes64()),
      FundingSigned(randomBytes32(), randomBytes64()),
      ChannelReady(randomBytes32(), point(2)),
      ChannelReady(randomBytes32(), point(2), TlvStream(ChannelReadyTlv.ShortChannelIdTlv(Alias(123456)))),
      UpdateFee(randomBytes32(), FeeratePerKw(2 sat)),
      Shutdown(randomBytes32(), bin(47, 0)),
      ClosingSigned(randomBytes32(), 2 sat, randomBytes64()),
      UpdateAddHtlc(randomBytes32(), 2, 3 msat, bin32(0), CltvExpiry(4), TestConstants.emptyOnionPacket, None),
      UpdateFulfillHtlc(randomBytes32(), 2, bin32(0)),
      UpdateFailHtlc(randomBytes32(), 2, bin(154, 0)),
      UpdateFailMalformedHtlc(randomBytes32(), 2, randomBytes32(), 1111),
      CommitSig(randomBytes32(), randomBytes64(), randomBytes64() :: randomBytes64() :: randomBytes64() :: Nil),
      RevokeAndAck(randomBytes32(), scalar(0), point(1)),
      ChannelAnnouncement(randomBytes64(), randomBytes64(), randomBytes64(), randomBytes64(), Features(bin(7, 9)), Block.RegtestGenesisBlock.hash, RealShortChannelId(1), randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey),
      NodeAnnouncement(randomBytes64(), Features(DataLossProtect -> Optional), 1 unixsec, randomKey().publicKey, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", IPv4(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)).asInstanceOf[Inet4Address], 42000) :: Nil),
      ChannelUpdate(randomBytes64(), Block.RegtestGenesisBlock.hash, ShortChannelId(1), 2 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags.DUMMY, CltvExpiryDelta(3), 4 msat, 5 msat, 6, 25_000_000 msat),
      AnnouncementSignatures(randomBytes32(), RealShortChannelId(42), randomBytes64(), randomBytes64()),
      GossipTimestampFilter(Block.RegtestGenesisBlock.blockId, 100000 unixsec, 1500),
      QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), TlvStream.empty),
      QueryChannelRange(Block.RegtestGenesisBlock.blockId,
        BlockHeight(100000),
        1500,
        TlvStream(Set[QueryChannelRangeTlv](QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_ALL)), Set(unknownTlv))),
      ReplyChannelRange(Block.RegtestGenesisBlock.blockId,
        BlockHeight(100000), 1500, 1,
        EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))),
        TlvStream(
          Set[ReplyChannelRangeTlv](EncodedTimestamps(EncodingType.UNCOMPRESSED, List(Timestamps(1 unixsec, 1 unixsec), Timestamps(2 unixsec, 2 unixsec), Timestamps(3 unixsec, 3 unixsec))), EncodedChecksums(List(Checksums(1, 1), Checksums(2, 2), Checksums(3, 3)))),
          Set(unknownTlv))
      ),
      Ping(100, bin(10, 1)),
      Pong(bin(10, 1)),
      ChannelReestablish(randomBytes32(), 242842L, 42L, randomKey(), randomKey().publicKey),
      UnknownMessage(tag = 60000, data = ByteVector32.One.bytes),
    )

    msgs.foreach {
      msg => {
        val encoded = lightningMessageCodecWithFallback.encode(msg).require
        val decoded = lightningMessageCodecWithFallback.decode(encoded).require
        assert(msg == decoded.value)
      }
    }
  }

  test("unknown messages") {
    // Non-standard tag number so this message can only be handled by a codec with a fallback
    val unknown = UnknownMessage(tag = 47282, data = ByteVector32.Zeroes.bytes)
    assert(lightningMessageCodec.encode(unknown).isFailure)
    val encoded1 = lightningMessageCodecWithFallback.encode(unknown).require
    val decoded1 = lightningMessageCodecWithFallback.decode(encoded1).require.value
    assert(lightningMessageCodec.decode(encoded1).isFailure)
    assert(decoded1 == unknown)
  }

  test("non-reg encoding type") {
    val refs = Map(
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001900000000000000008e0000000000003c69000000000045a6c4"
        -> QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), TlvStream.empty),
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001601789c636000833e08659309a65c971d0100126e02e3"
        -> QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), TlvStream.empty),
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001900000000000000008e0000000000003c69000000000045a6c4010400010204"
        -> QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.UNCOMPRESSED, List(1, 2, 4)))),
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001601789c636000833e08659309a65c971d0100126e02e3010c01789c6364620100000e0008"
        -> QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, List(1, 2, 4))))
    )

    refs.forall {
      case (bin, obj) =>
        lightningMessageCodec.decode(bin.toBitVector).require.value == obj && lightningMessageCodec.encode(obj).require == bin.toBitVector
    }
  }

  case class TestItem(msg: Any, hex: String)

  test("test vectors for extended channel queries ") {

    val refs = Map(
      QueryChannelRange(Block.RegtestGenesisBlock.blockId, BlockHeight(100000), 1500, TlvStream.empty) ->
        hex"01070f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206000186a0000005dc",
      QueryChannelRange(Block.RegtestGenesisBlock.blockId,
        BlockHeight(35000),
        100,
        TlvStream(QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_ALL))) ->
        hex"01070f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206000088b800000064010103",
      ReplyChannelRange(Block.RegtestGenesisBlock.blockId, BlockHeight(756230), 1500, 1,
        EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), None, None) ->
        hex"01080f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206000b8a06000005dc01001900000000000000008e0000000000003c69000000000045a6c4",
      ReplyChannelRange(Block.RegtestGenesisBlock.blockId, BlockHeight(1600), 110, 1,
        EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(265462))), None, None) ->
        hex"01080f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206000006400000006e01001601789c636000833e08659309a65878be010010a9023a",
      ReplyChannelRange(Block.RegtestGenesisBlock.blockId, BlockHeight(122334), 1500, 1,
        EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(12355), RealShortChannelId(489686), RealShortChannelId(4645313))),
        Some(EncodedTimestamps(EncodingType.UNCOMPRESSED, List(Timestamps(164545 unixsec, 948165 unixsec), Timestamps(489645 unixsec, 4786864 unixsec), Timestamps(46456 unixsec, 9788415 unixsec)))),
        Some(EncodedChecksums(List(Checksums(1111, 2222), Checksums(3333, 4444), Checksums(5555, 6666))))) ->
        hex"01080f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e22060001ddde000005dc01001900000000000000304300000000000778d6000000000046e1c1011900000282c1000e77c5000778ad00490ab00000b57800955bff031800000457000008ae00000d050000115c000015b300001a0a",
      ReplyChannelRange(Block.RegtestGenesisBlock.blockId, BlockHeight(122334), 1500, 1,
        EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(12355), RealShortChannelId(489686), RealShortChannelId(4645313))),
        Some(EncodedTimestamps(EncodingType.COMPRESSED_ZLIB, List(Timestamps(164545 unixsec, 948165 unixsec), Timestamps(489645 unixsec, 4786864 unixsec), Timestamps(46456 unixsec, 9788415 unixsec)))),
        Some(EncodedChecksums(List(Checksums(1111, 2222), Checksums(3333, 4444), Checksums(5555, 6666))))) ->
        hex"01080f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e22060001ddde000005dc01001801789c63600001036730c55e710d4cbb3d3c080017c303b1012201789c63606a3ac8c0577e9481bd622d8327d7060686ad150c53a3ff0300554707db031800000457000008ae00000d050000115c000015b300001a0a",
      QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), TlvStream.empty) ->
        hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001900000000000000008e0000000000003c69000000000045a6c4",
      QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(4564), RealShortChannelId(178622), RealShortChannelId(4564676))), TlvStream.empty) ->
        hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001801789c63600001c12b608a69e73e30edbaec0800203b040e",
      QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(12232), RealShortChannelId(15556), RealShortChannelId(4564676))), TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, List(1, 2, 4)))) ->
        hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e22060019000000000000002fc80000000000003cc4000000000045a6c4010c01789c6364620100000e0008",
      QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(14200), RealShortChannelId(46645), RealShortChannelId(4564676))), TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, List(1, 2, 4)))) ->
        hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001801789c63600001f30a30c5b0cd144cb92e3b020017c6034a010c01789c6364620100000e0008"
    )

    val items = refs.map { case (obj, refbin) =>
      val bin = lightningMessageCodec.encode(obj).require
      assert(refbin.bits == bin)
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
      new InvoiceSerializer +
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
    assert(update == ChannelUpdate(ByteVector64(hex"58fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf17923"), ByteVector32(hex"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f"), ShortChannelId(0x5a10000020000L), 1539791129 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags(isEnabled = true, isNode1 = false), CltvExpiryDelta(6), 1 msat, 1 msat, 10, 980_000_000 msat))
    val nodeId = PublicKey(hex"03370c9bac836e557eb4f017fe8f9cc047f44db39c1c4e410ff0f7be142b817ae4")
    assert(Announcements.checkSig(update, nodeId))
    val bin2 = ByteVector(lightningMessageCodec.encode(update).require.toByteArray)
    assert(bin == bin2)
  }

  test("reject channel_update without htlc_maximum_msat") {
    // {"signature":"12540b6a236e21932622d61432f52913d9442cc09a1057c386119a286153f8681c66d2a0f17d32505ba71bb37c8edcfa9c11e151b2b38dae98b825eff1c040b3","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"558351x1422x1","timestamp":{"iso":"2020-03-12T17:58:06Z","unix":1584035886},"channelFlags":{"isEnabled":true,"isNode1":true},"cltvExpiryDelta":144,"htlcMinimumMsat":1000,"feeBaseMsat":1000,"feeProportionalMillionths":2}
    val bin = hex"12540b6a236e21932622d61432f52913d9442cc09a1057c386119a286153f8681c66d2a0f17d32505ba71bb37c8edcfa9c11e151b2b38dae98b825eff1c040b36fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d619000000000008850f00058e00015e6a782e0000009000000000000003e8000003e800000002"
    assert(channelUpdateCodec.decode(bin.bits).isFailure)
  }

  test("non-regression on channel_update") {
    val bins = Map(
      hex"3b6bb4872825450ff29d0b46f5835751329b0394a10ac792e4ba2a23b4f17bcc4e5834d1424787830be0ee3d22ac99e674d121f25d19ed931aaabb8ed0eec0fb6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000086a4e000a9700016137e9e9010200900000000000000001000003e800000001000000001dcd6500" ->
        """{"signature":"3b6bb4872825450ff29d0b46f5835751329b0394a10ac792e4ba2a23b4f17bcc4e5834d1424787830be0ee3d22ac99e674d121f25d19ed931aaabb8ed0eec0fb","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"551502x2711x1","timestamp":{"iso":"2021-09-07T22:38:33Z","unix":1631054313},"messageFlags":{"dontForward":false},"channelFlags":{"isEnabled":false,"isNode1":true},"cltvExpiryDelta":144,"htlcMinimumMsat":1,"feeBaseMsat":1000,"feeProportionalMillionths":1,"htlcMaximumMsat":500000000,"tlvStream":{"records":[],"unknown":[]}}""",
      hex"8efb98c939aba422a1f2ccd3e05e5471be41c54ac5d7cb27b9aaaecea45f3abb363907644c44b385d83ef6b577061847396d6d3464e4f1fa9e779395e36703ef6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d61900000000000a79dd00098800006137f9ba0100002800000000000003e800000000000003e800000000938580c0" ->
        """{"signature":"8efb98c939aba422a1f2ccd3e05e5471be41c54ac5d7cb27b9aaaecea45f3abb363907644c44b385d83ef6b577061847396d6d3464e4f1fa9e779395e36703ef","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"686557x2440x0","timestamp":{"iso":"2021-09-07T23:46:02Z","unix":1631058362},"messageFlags":{"dontForward":false},"channelFlags":{"isEnabled":true,"isNode1":true},"cltvExpiryDelta":40,"htlcMinimumMsat":1000,"feeBaseMsat":0,"feeProportionalMillionths":1000,"htlcMaximumMsat":2475000000,"tlvStream":{"records":[],"unknown":[]}}""",
      hex"b212e4d88a5ce3201ec34160d90a07eeb0601207d7d53bcf2b8f99b21146d7eb00d6a5b4b80b878eac0d25c2209eda05c913851730a65260c943fec8956cb22e6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d61900000000000a48ce0006900000613792e40100002800000000000003e8000003e8000000010000000056d35b20" ->
        """{"signature":"b212e4d88a5ce3201ec34160d90a07eeb0601207d7d53bcf2b8f99b21146d7eb00d6a5b4b80b878eac0d25c2209eda05c913851730a65260c943fec8956cb22e","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"673998x1680x0","timestamp":{"iso":"2021-09-07T16:27:16Z","unix":1631032036},"messageFlags":{"dontForward":false},"channelFlags":{"isEnabled":true,"isNode1":true},"cltvExpiryDelta":40,"htlcMinimumMsat":1000,"feeBaseMsat":1000,"feeProportionalMillionths":1,"htlcMaximumMsat":1456692000,"tlvStream":{"records":[],"unknown":[]}}""",
      hex"29396591aee1bfd292193b4329d24eb9f57ddb143f303d029ae004113a7402af015c721ddc3e5d2e36cc67c92af3bdcd22d55eaf1e532503f9972207b226984f6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000096f010006ea000061375a440102002800000000000003e8000003e800000001000000024e160300" ->
        """{"signature":"29396591aee1bfd292193b4329d24eb9f57ddb143f303d029ae004113a7402af015c721ddc3e5d2e36cc67c92af3bdcd22d55eaf1e532503f9972207b226984f","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"618241x1770x0","timestamp":{"iso":"2021-09-07T12:25:40Z","unix":1631017540},"messageFlags":{"dontForward":false},"channelFlags":{"isEnabled":false,"isNode1":true},"cltvExpiryDelta":40,"htlcMinimumMsat":1000,"feeBaseMsat":1000,"feeProportionalMillionths":1,"htlcMaximumMsat":9900000000,"tlvStream":{"records":[],"unknown":[]}}""",
      hex"3c6de66a61f2b8803537a2d92e7b82db1b44eac664ed6b7f5c7b5360b21d7ce32e5238e98d54701fe6d5b9109b2a2d875878a12d254eb6d651843b787f1ba5de6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d61900000000000a921f0003fc000461386d9e0100002800000000000003e8000003e80000000100000000ec08ce00" ->
        """{"signature":"3c6de66a61f2b8803537a2d92e7b82db1b44eac664ed6b7f5c7b5360b21d7ce32e5238e98d54701fe6d5b9109b2a2d875878a12d254eb6d651843b787f1ba5de","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"692767x1020x4","timestamp":{"iso":"2021-09-08T08:00:30Z","unix":1631088030},"messageFlags":{"dontForward":false},"channelFlags":{"isEnabled":true,"isNode1":true},"cltvExpiryDelta":40,"htlcMinimumMsat":1000,"feeBaseMsat":1000,"feeProportionalMillionths":1,"htlcMaximumMsat":3960000000,"tlvStream":{"records":[],"unknown":[]}}""",
      hex"180de159377d68ecc3b327594bfb7408374811f3c98b5982af1520802796025a1430a6049294ebc0030518cc9b56a574c38c316122cb674f972734d7054d0b546fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d61900000000000868f200029a00006137935701030028000000000000000000000001000002bc0000000001c9c380" ->
        """{"signature":"180de159377d68ecc3b327594bfb7408374811f3c98b5982af1520802796025a1430a6049294ebc0030518cc9b56a574c38c316122cb674f972734d7054d0b54","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"551154x666x0","timestamp":{"iso":"2021-09-07T16:29:11Z","unix":1631032151},"messageFlags":{"dontForward":false},"channelFlags":{"isEnabled":false,"isNode1":false},"cltvExpiryDelta":40,"htlcMinimumMsat":0,"feeBaseMsat":1,"feeProportionalMillionths":700,"htlcMaximumMsat":30000000,"tlvStream":{"records":[],"unknown":[]}}""",
      hex"cb6aacede86c15cb64f2513e357e1fe2384dd17b8e613608dfdca48cf884043c3431faaccc1e2417cbe938213686efe15e0d0549c75bb66c1675a4909e8e60ee06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f000000000000023162ea9b1f03010024000000000000003200000064000000fa0000000002faf080" ->
        """{"signature":"cb6aacede86c15cb64f2513e357e1fe2384dd17b8e613608dfdca48cf884043c3431faaccc1e2417cbe938213686efe15e0d0549c75bb66c1675a4909e8e60ee","chainHash":"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f","shortChannelId":"0x0x561","timestamp":{"iso":"2022-08-03T15:58:23Z","unix":1659542303},"messageFlags":{"dontForward":true},"channelFlags":{"isEnabled":true,"isNode1":false},"cltvExpiryDelta":36,"htlcMinimumMsat":50,"feeBaseMsat":100,"feeProportionalMillionths":250,"htlcMaximumMsat":50000000,"tlvStream":{"records":[],"unknown":[]}}"""
    )
    for ((bin, ref) <- bins) {
      val decoded = channelUpdateCodec.decode(bin.bits).require
      // not only must decoding succeed, it must also produce the same object
      assert(Serialization.write(decoded.value)(JsonSerializers.formats) == ref)
      assert(decoded.remainder.isEmpty)
      // encoding must produce the same buffer
      assert(channelUpdateCodec.encode(decoded.value).require.bytes == bin)
    }
  }

  test("non-regression on channel flags") {
    val testCases = Map(
      bin"0000 0000" -> ChannelUpdate.ChannelFlags(isEnabled = true, isNode1 = true),
      bin"0000 0001" -> ChannelUpdate.ChannelFlags(isEnabled = true, isNode1 = false),
      bin"0000 0010" -> ChannelUpdate.ChannelFlags(isEnabled = false, isNode1 = true),
      bin"0000 0011" -> ChannelUpdate.ChannelFlags(isEnabled = false, isNode1 = false),
    )
    for ((bin, ref) <- testCases) {
      val decoded = channelFlagsCodec.decode(bin).require
      assert(decoded.value == ref)
      assert(decoded.remainder.isEmpty)
      assert(channelFlagsCodec.encode(ref).require == bin)
    }
  }

}
