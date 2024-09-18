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
import fr.acinq.bitcoin.scalacompat.{Block, BlockHash, ByteVector32, ByteVector64, OutPoint, SatoshiLong, ScriptWitness, Transaction, TxHash, TxId}
import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.Features.DataLossProtect
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{ChannelFlags, ChannelTypes}
import fr.acinq.eclair.json.JsonSerializers
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.protocol.ChannelTlv.{ChannelTypeTlv, PushAmountTlv, RequireConfirmedInputsTlv, UpfrontShutdownScriptTlv}
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
import fr.acinq.eclair.wire.protocol.ReplyChannelRangeTlv._
import org.json4s.jackson.Serialization
import org.scalatest.funsuite.AnyFunSuite
import scodec.DecodeResult
import scodec.bits.{BinStringSyntax, BitVector, ByteVector, HexStringSyntax}

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
    case class TestCase(encoded: ByteVector, rawFeatures: ByteVector, networks: List[BlockHash], address: Option[IPAddress], valid: Boolean, reEncoded: Option[ByteVector] = None)
    val chainHash1 = BlockHash(ByteVector32(hex"0101010101010101010101010101010101010101010101010101010101010101"))
    val chainHash2 = BlockHash(ByteVector32(hex"0202020202020202020202020202020202020202020202020202020202020202"))
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

  test("encode/decode stfu") {
    val testCases = Seq(
      Stfu(ByteVector32.One, initiator = true) -> hex"0002 0100000000000000000000000000000000000000000000000000000000000000 01",
      Stfu(ByteVector32.One, initiator = false) -> hex"0002 0100000000000000000000000000000000000000000000000000000000000000 00",
    )
    testCases.foreach { case (expected, bin) =>
      assert(lightningMessageCodec.encode(expected).require.bytes == bin)
      assert(lightningMessageCodec.decode(bin.bits).require.value == expected)
    }
  }

  test("nonreg generic tlv") {
    val channelId = randomBytes32()
    val signature = randomBytes64()
    val key = randomKey()
    val point = randomKey().publicKey
    val txId = randomTxId()
    val randomData = randomBytes(42)
    val tlvTag = UInt64(hex"47010000")

    val refs = Map(
      hex"0023" ++ channelId ++ signature -> FundingSigned(channelId, signature),
      hex"0023" ++ channelId ++ signature ++ hex"fe47010000 00" -> FundingSigned(channelId, signature, TlvStream[FundingSignedTlv](Set.empty[FundingSignedTlv], Set(GenericTlv(tlvTag, ByteVector.empty)))),
      hex"0023" ++ channelId ++ signature ++ hex"fe47010000 07 cccccccccccccc" -> FundingSigned(channelId, signature, TlvStream[FundingSignedTlv](Set.empty[FundingSignedTlv], Set(GenericTlv(tlvTag, hex"cccccccccccccc")))),

      hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point),
      hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value ++ hex"00 20" ++ txId.value.reverse -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point, TlvStream(ChannelReestablishTlv.NextFundingTlv(txId))),
      hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value ++ hex"fe47010000 00" -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point, TlvStream[ChannelReestablishTlv](Set.empty[ChannelReestablishTlv], Set(GenericTlv(tlvTag, ByteVector.empty)))),
      hex"0088" ++ channelId ++ hex"0001020304050607 0809aabbccddeeff" ++ key.value ++ point.value ++ hex"fe47010000 07 bbbbbbbbbbbbbb" -> ChannelReestablish(channelId, 0x01020304050607L, 0x0809aabbccddeeffL, key, point, TlvStream[ChannelReestablishTlv](Set.empty[ChannelReestablishTlv], Set(GenericTlv(tlvTag, hex"bbbbbbbbbbbbbb")))),

      hex"0084" ++ channelId ++ signature ++ hex"0000" -> CommitSig(channelId, signature, Nil),
      hex"0084" ++ channelId ++ signature ++ hex"0000" ++ hex"00 22 0003" ++ txId.value.reverse -> CommitSig(channelId, signature, Nil, TlvStream(CommitSigTlv.BatchTlv(3, txId))),
      hex"0084" ++ channelId ++ signature ++ hex"0000 fe47010000 00" -> CommitSig(channelId, signature, Nil, TlvStream[CommitSigTlv](Set.empty[CommitSigTlv], Set(GenericTlv(tlvTag, ByteVector.empty)))),
      hex"0084" ++ channelId ++ signature ++ hex"0000 fe47010000 07 cccccccccccccc" -> CommitSig(channelId, signature, Nil, TlvStream[CommitSigTlv](Set.empty[CommitSigTlv], Set(GenericTlv(tlvTag, hex"cccccccccccccc")))),

      hex"0085" ++ channelId ++ key.value ++ point.value -> RevokeAndAck(channelId, key, point),
      hex"0085" ++ channelId ++ key.value ++ point.value ++ hex" fe47010000 00" -> RevokeAndAck(channelId, key, point, TlvStream[RevokeAndAckTlv](Set.empty[RevokeAndAckTlv], Set(GenericTlv(tlvTag, ByteVector.empty)))),
      hex"0085" ++ channelId ++ key.value ++ point.value ++ hex" fe47010000 07 cccccccccccccc" -> RevokeAndAck(channelId, key, point, TlvStream[RevokeAndAckTlv](Set.empty[RevokeAndAckTlv], Set(GenericTlv(tlvTag, hex"cccccccccccccc")))),

      hex"0026" ++ channelId ++ hex"002a" ++ randomData -> Shutdown(channelId, randomData),
      hex"0026" ++ channelId ++ hex"002a" ++ randomData ++ hex"fe47010000 00" -> Shutdown(channelId, randomData, TlvStream[ShutdownTlv](Set.empty[ShutdownTlv], Set(GenericTlv(tlvTag, ByteVector.empty)))),
      hex"0026" ++ channelId ++ hex"002a" ++ randomData ++ hex"fe47010000 07 cccccccccccccc" -> Shutdown(channelId, randomData, TlvStream[ShutdownTlv](Set.empty[ShutdownTlv], Set(GenericTlv(tlvTag, hex"cccccccccccccc")))),

      hex"0027" ++ channelId ++ hex"00000000075bcd15" ++ signature -> ClosingSigned(channelId, 123456789.sat, signature),
      hex"0027" ++ channelId ++ hex"00000000075bcd15" ++ signature ++ hex"fe47010000 00" -> ClosingSigned(channelId, 123456789.sat, signature, TlvStream[ClosingSignedTlv](Set.empty[ClosingSignedTlv], Set(GenericTlv(tlvTag, ByteVector.empty)))),
      hex"0027" ++ channelId ++ hex"00000000075bcd15" ++ signature ++ hex"fe47010000 07 cccccccccccccc" -> ClosingSigned(channelId, 123456789.sat, signature, TlvStream[ClosingSignedTlv](Set.empty[ClosingSignedTlv], Set(GenericTlv(tlvTag, hex"cccccccccccccc")))),
    )

    refs.foreach { case (bin, msg) =>
      assert(lightningMessageCodec.decode(bin.bits).require == DecodeResult(msg, BitVector.empty))
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
      TxAddInput(channelId1, UInt64(561), OutPoint(tx1, 1), 5) -> hex"0042 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000231 0000 00000001 00000005 00201f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 fd0451201f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106",
      TxAddOutput(channelId1, UInt64(1105), 2047 sat, hex"00149357014afd0ccd265658c9ae81efa995e771f472") -> hex"0043 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000451 00000000000007ff 0016 00149357014afd0ccd265658c9ae81efa995e771f472",
      TxAddOutput(channelId1, UInt64(1105), 2047 sat, hex"00149357014afd0ccd265658c9ae81efa995e771f472", TlvStream(Set.empty[TxAddOutputTlv], Set(GenericTlv(UInt64(301), hex"2a")))) -> hex"0043 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000451 00000000000007ff 0016 00149357014afd0ccd265658c9ae81efa995e771f472 fd012d012a",
      TxRemoveInput(channelId2, UInt64(561)) -> hex"0044 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 0000000000000231",
      TxRemoveOutput(channelId1, UInt64(1)) -> hex"0045 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000001",
      TxComplete(channelId1) -> hex"0046 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      TxComplete(channelId1, TlvStream(Set.empty[TxCompleteTlv], Set(GenericTlv(UInt64(231), hex"deadbeef"), GenericTlv(UInt64(507), hex"")))) -> hex"0046 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa e704deadbeef fd01fb00",
      TxSignatures(channelId1, tx2, Seq(ScriptWitness(Seq(hex"68656c6c6f2074686572652c2074686973206973206120626974636f6e212121", hex"82012088a820add57dfe5277079d069ca4ad4893c96de91f88ffb981fdc6a2a34d5336c66aff87")), ScriptWitness(Seq(hex"304402207de9ba56bb9f641372e805782575ee840a899e61021c8b1572b3ec1d5b5950e9022069e9ba998915dae193d3c25cb89b5e64370e6a3a7755e7f31cf6d7cbc2a49f6d01", hex"034695f5b7864c580bf11f9f8cb1a94eb336f2ce9ef872d2ae1a90ee276c772484"))), None) -> hex"0047 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa fc7aa8845f192959202c1b7ff704e7cbddded463c05e844676a94ccb4bed69f1 0002 004a 022068656c6c6f2074686572652c2074686973206973206120626974636f6e2121212782012088a820add57dfe5277079d069ca4ad4893c96de91f88ffb981fdc6a2a34d5336c66aff87 006b 0247304402207de9ba56bb9f641372e805782575ee840a899e61021c8b1572b3ec1d5b5950e9022069e9ba998915dae193d3c25cb89b5e64370e6a3a7755e7f31cf6d7cbc2a49f6d0121034695f5b7864c580bf11f9f8cb1a94eb336f2ce9ef872d2ae1a90ee276c772484",
      TxSignatures(channelId2, tx1, Nil, None) -> hex"0047 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 1f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 0000",
      TxSignatures(channelId2, tx1, Nil, Some(signature)) -> hex"0047 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 1f2ec025a33e39ef8e177afcdc1adc855bf128dc906182255aeb64efa825f106 0000 0040aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb fd025940aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      TxInitRbf(channelId1, 8388607, FeeratePerKw(4000 sat)) -> hex"0048 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 007fffff 00000fa0",
      TxInitRbf(channelId1, 0, FeeratePerKw(4000 sat), 1_500_000 sat, requireConfirmedInputs = true) -> hex"0048 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000 00000fa0 0008000000000016e360 0200",
      TxInitRbf(channelId1, 0, FeeratePerKw(4000 sat), 0 sat, requireConfirmedInputs = false) -> hex"0048 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000 00000fa0 00080000000000000000",
      TxInitRbf(channelId1, 0, FeeratePerKw(4000 sat), -25_000 sat, requireConfirmedInputs = false) -> hex"0048 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000 00000fa0 0008ffffffffffff9e58",
      TxAckRbf(channelId2) -> hex"0049 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
      TxAckRbf(channelId2, 450_000 sat, requireConfirmedInputs = false) -> hex"0049 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 0008000000000006ddd0",
      TxAckRbf(channelId2, 0 sat, requireConfirmedInputs = false) -> hex"0049 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 00080000000000000000",
      TxAckRbf(channelId2, -250_000 sat, requireConfirmedInputs = true) -> hex"0049 bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb 0008fffffffffffc2f70 0200",
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
    val defaultOpen = OpenChannel(BlockHash(ByteVector32.Zeroes), ByteVector32.Zeroes, 1 sat, 1 msat, 1 sat, UInt64(1), 1 sat, 1 msat, FeeratePerKw(1 sat), CltvExpiryDelta(1), 1, publicKey(1), point(2), point(3), point(4), point(5), point(6), ChannelFlags(announceChannel = false))
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
    val defaultOpen = OpenDualFundedChannel(BlockHash(ByteVector32.Zeroes), ByteVector32.One, FeeratePerKw(5000 sat), FeeratePerKw(4000 sat), 250_000 sat, 500 sat, UInt64(50_000), 15 msat, CltvExpiryDelta(144), 483, 650_000, publicKey(1), publicKey(2), publicKey(3), publicKey(4), publicKey(5), publicKey(6), publicKey(7), ChannelFlags(true))
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
    val defaultOpen = OpenDualFundedChannel(BlockHash(ByteVector32.Zeroes), ByteVector32.One, FeeratePerKw(5000 sat), FeeratePerKw(4000 sat), 250_000 sat, 500 sat, UInt64(50_000), 15 msat, CltvExpiryDelta(144), 483, 650_000, publicKey(1), publicKey(2), publicKey(3), publicKey(4), publicKey(5), publicKey(6), publicKey(7), ChannelFlags(true))
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

  test("encode/decode splice messages") {
    val channelId = ByteVector32(hex"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    val fundingTxId = TxId(TxHash(ByteVector32(hex"24e1b2c94c4e734dd5b9c5f3c910fbb6b3b436ced6382c7186056a5a23f14566")))
    val fundingPubkey = PublicKey(hex"0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
    val testCases = Seq(
      // @formatter:off
      SpliceInit(channelId, 100_000 sat, FeeratePerKw(2500 sat), 100, fundingPubkey) -> hex"0050 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000000186a0 000009c4 00000064 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
      SpliceInit(channelId, 150_000 sat, 100, FeeratePerKw(2500 sat), fundingPubkey, 25_000_000 msat, requireConfirmedInputs = false) -> hex"0050 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000000249f0 000009c4 00000064 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798 fe4700000704017d7840",
      SpliceInit(channelId, 150_000 sat, 100, FeeratePerKw(2500 sat), fundingPubkey, 25_000_000 msat, requireConfirmedInputs = true) -> hex"0050 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000000249f0 000009c4 00000064 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798 0200 fe4700000704017d7840",
      SpliceInit(channelId, 0 sat, FeeratePerKw(500 sat), 0, fundingPubkey) -> hex"0050 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000000 000001f4 00000000 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
      SpliceInit(channelId, (-50_000).sat, FeeratePerKw(500 sat), 0, fundingPubkey) -> hex"0050 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ffffffffffff3cb0 000001f4 00000000 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
      SpliceAck(channelId, 25_000 sat, fundingPubkey) -> hex"0051 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 00000000000061a8 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
      SpliceAck(channelId, 40_000 sat, fundingPubkey, 10_000_000 msat, requireConfirmedInputs = false) -> hex"0051 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000009c40 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798 fe4700000703989680",
      SpliceAck(channelId, 40_000 sat, fundingPubkey, 10_000_000 msat, requireConfirmedInputs = true) -> hex"0051 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000009c40 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798 0200 fe4700000703989680",
      SpliceAck(channelId, 0.sat, fundingPubkey) -> hex"0051 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 0000000000000000 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
      SpliceAck(channelId, (-25_000).sat, fundingPubkey) -> hex"0051 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa ffffffffffff9e58 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
      SpliceLocked(channelId, fundingTxId) -> hex"004d aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa 24e1b2c94c4e734dd5b9c5f3c910fbb6b3b436ced6382c7186056a5a23f14566",
      // @formatter:on
    )
    testCases.foreach { case (msg, bin) =>
      val decoded = lightningMessageCodec.decode(bin.bits).require.value
      assert(decoded == msg)
      val encoded = lightningMessageCodec.encode(msg).require.bytes
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
      OpenChannel(BlockHash(randomBytes32()), randomBytes32(), 3 sat, 4 msat, 5 sat, UInt64(6), 7 sat, 8 msat, FeeratePerKw(9 sat), CltvExpiryDelta(10), 11, publicKey(1), point(2), point(3), point(4), point(5), point(6), ChannelFlags(announceChannel = false)),
      AcceptChannel(randomBytes32(), 3 sat, UInt64(4), 5 sat, 6 msat, 7, CltvExpiryDelta(8), 9, publicKey(1), point(2), point(3), point(4), point(5), point(6)),
      FundingCreated(randomBytes32(), TxId(ByteVector32.Zeroes), 3, randomBytes64()),
      FundingSigned(randomBytes32(), randomBytes64()),
      ChannelReady(randomBytes32(), point(2)),
      ChannelReady(randomBytes32(), point(2), TlvStream(ChannelReadyTlv.ShortChannelIdTlv(Alias(123456)))),
      UpdateFee(randomBytes32(), FeeratePerKw(2 sat)),
      Shutdown(randomBytes32(), bin(47, 0)),
      ClosingSigned(randomBytes32(), 2 sat, randomBytes64()),
      UpdateAddHtlc(randomBytes32(), 2, 3 msat, bin32(0), CltvExpiry(4), TestConstants.emptyOnionPacket, None, 1.0),
      UpdateFulfillHtlc(randomBytes32(), 2, bin32(0)),
      UpdateFailHtlc(randomBytes32(), 2, bin(154, 0)),
      UpdateFailMalformedHtlc(randomBytes32(), 2, randomBytes32(), 1111),
      CommitSig(randomBytes32(), randomBytes64(), randomBytes64() :: randomBytes64() :: randomBytes64() :: Nil),
      RevokeAndAck(randomBytes32(), scalar(0), point(1)),
      ChannelAnnouncement(randomBytes64(), randomBytes64(), randomBytes64(), randomBytes64(), Features(bin(7, 9)), Block.RegtestGenesisBlock.hash, RealShortChannelId(1), randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey),
      NodeAnnouncement(randomBytes64(), Features(DataLossProtect -> Optional), 1 unixsec, randomKey().publicKey, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", IPv4(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)).asInstanceOf[Inet4Address], 42000) :: Nil),
      ChannelUpdate(randomBytes64(), Block.RegtestGenesisBlock.hash, ShortChannelId(1), 2 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags.DUMMY, CltvExpiryDelta(3), 4 msat, 5 msat, 6, 25_000_000 msat),
      AnnouncementSignatures(randomBytes32(), RealShortChannelId(42), randomBytes64(), randomBytes64()),
      GossipTimestampFilter(Block.RegtestGenesisBlock.hash, 100000 unixsec, 1500),
      QueryShortChannelIds(Block.RegtestGenesisBlock.hash, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), TlvStream.empty),
      QueryChannelRange(Block.RegtestGenesisBlock.hash,
        BlockHeight(100000),
        1500,
        TlvStream(Set[QueryChannelRangeTlv](QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_ALL)), Set(unknownTlv))),
      ReplyChannelRange(Block.RegtestGenesisBlock.hash,
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
        -> QueryShortChannelIds(Block.RegtestGenesisBlock.hash, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), TlvStream.empty),
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001601789c636000833e08659309a65c971d0100126e02e3"
        -> QueryShortChannelIds(Block.RegtestGenesisBlock.hash, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), TlvStream.empty),
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001900000000000000008e0000000000003c69000000000045a6c4010400010204"
        -> QueryShortChannelIds(Block.RegtestGenesisBlock.hash, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.UNCOMPRESSED, List(1, 2, 4)))),
      hex"01050f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206001601789c636000833e08659309a65c971d0100126e02e3010c01789c6364620100000e0008"
        -> QueryShortChannelIds(Block.RegtestGenesisBlock.hash, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, List(1, 2, 4))))
    )

    refs.forall {
      case (bin, obj) =>
        lightningMessageCodec.decode(bin.toBitVector).require.value == obj && lightningMessageCodec.encode(obj).require == bin.toBitVector
    }
  }

  test("test vectors for extended channel queries ") {
    val refs = Map(
      QueryChannelRange(Block.RegtestGenesisBlock.hash, BlockHeight(100000), 1500, TlvStream.empty) ->
        hex"010706226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f000186a0000005dc",
      QueryChannelRange(Block.RegtestGenesisBlock.hash,
        BlockHeight(35000),
        100,
        TlvStream(QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_ALL))) ->
        hex"010706226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f000088b800000064010103",
      ReplyChannelRange(Block.RegtestGenesisBlock.hash, BlockHeight(756230), 1500, 1,
        EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), None, None) ->
        hex"010806226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f000b8a06000005dc01001900000000000000008e0000000000003c69000000000045a6c4",
      ReplyChannelRange(Block.RegtestGenesisBlock.hash, BlockHeight(1600), 110, 1,
        EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(265462))), None, None) ->
        hex"010806226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f000006400000006e01001601789c636000833e08659309a65878be010010a9023a",
      ReplyChannelRange(Block.RegtestGenesisBlock.hash, BlockHeight(122334), 1500, 1,
        EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(12355), RealShortChannelId(489686), RealShortChannelId(4645313))),
        Some(EncodedTimestamps(EncodingType.UNCOMPRESSED, List(Timestamps(164545 unixsec, 948165 unixsec), Timestamps(489645 unixsec, 4786864 unixsec), Timestamps(46456 unixsec, 9788415 unixsec)))),
        Some(EncodedChecksums(List(Checksums(1111, 2222), Checksums(3333, 4444), Checksums(5555, 6666))))) ->
        hex"010806226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f0001ddde000005dc01001900000000000000304300000000000778d6000000000046e1c1011900000282c1000e77c5000778ad00490ab00000b57800955bff031800000457000008ae00000d050000115c000015b300001a0a",
      ReplyChannelRange(Block.RegtestGenesisBlock.hash, BlockHeight(122334), 1500, 1,
        EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(12355), RealShortChannelId(489686), RealShortChannelId(4645313))),
        Some(EncodedTimestamps(EncodingType.COMPRESSED_ZLIB, List(Timestamps(164545 unixsec, 948165 unixsec), Timestamps(489645 unixsec, 4786864 unixsec), Timestamps(46456 unixsec, 9788415 unixsec)))),
        Some(EncodedChecksums(List(Checksums(1111, 2222), Checksums(3333, 4444), Checksums(5555, 6666))))) ->
        hex"010806226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f0001ddde000005dc01001801789c63600001036730c55e710d4cbb3d3c080017c303b1012201789c63606a3ac8c0577e9481bd622d8327d7060686ad150c53a3ff0300554707db031800000457000008ae00000d050000115c000015b300001a0a",
      QueryShortChannelIds(Block.RegtestGenesisBlock.hash, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(142), RealShortChannelId(15465), RealShortChannelId(4564676))), TlvStream.empty) ->
        hex"010506226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f001900000000000000008e0000000000003c69000000000045a6c4",
      QueryShortChannelIds(Block.RegtestGenesisBlock.hash, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(4564), RealShortChannelId(178622), RealShortChannelId(4564676))), TlvStream.empty) ->
        hex"010506226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f001801789c63600001c12b608a69e73e30edbaec0800203b040e",
      QueryShortChannelIds(Block.RegtestGenesisBlock.hash, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(12232), RealShortChannelId(15556), RealShortChannelId(4564676))), TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, List(1, 2, 4)))) ->
        hex"010506226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f0019000000000000002fc80000000000003cc4000000000045a6c4010c01789c6364620100000e0008",
      QueryShortChannelIds(Block.RegtestGenesisBlock.hash, EncodedShortChannelIds(EncodingType.COMPRESSED_ZLIB, List(RealShortChannelId(14200), RealShortChannelId(46645), RealShortChannelId(4564676))), TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(EncodingType.COMPRESSED_ZLIB, List(1, 2, 4)))) ->
        hex"010506226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f001801789c63600001f30a30c5b0cd144cb92e3b020017c6034a010c01789c6364620100000e0008"
    )
    refs.map { case (obj, refbin) =>
      val bin = lightningMessageCodec.encode(obj).require
      assert(refbin.bits == bin)
    }
  }

  test("decode channel_update with htlc_maximum_msat") {
    // this was generated by c-lightning
    val bin = hex"010258fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf1792306226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f0005a100000200005bc75919010100060000000000000001000000010000000a000000003a699d00"
    val update = lightningMessageCodec.decode(bin.bits).require.value.asInstanceOf[ChannelUpdate]
    assert(update == ChannelUpdate(ByteVector64(hex"58fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf17923"), BlockHash(ByteVector32(hex"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f")), ShortChannelId(0x5a10000020000L), 1539791129 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags(isEnabled = true, isNode1 = false), CltvExpiryDelta(6), 1 msat, 1 msat, 10, 980_000_000 msat))
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
        """{"signature":"3b6bb4872825450ff29d0b46f5835751329b0394a10ac792e4ba2a23b4f17bcc4e5834d1424787830be0ee3d22ac99e674d121f25d19ed931aaabb8ed0eec0fb","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"551502x2711x1","timestamp":{"iso":"2021-09-07T22:38:33Z","unix":1631054313},"messageFlags":{"dontForward":false},"channelFlags":{"isEnabled":false,"isNode1":true},"cltvExpiryDelta":144,"htlcMinimumMsat":1,"feeBaseMsat":1000,"feeProportionalMillionths":1,"htlcMaximumMsat":500000000,"tlvStream":{}}""",
      hex"8efb98c939aba422a1f2ccd3e05e5471be41c54ac5d7cb27b9aaaecea45f3abb363907644c44b385d83ef6b577061847396d6d3464e4f1fa9e779395e36703ef6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d61900000000000a79dd00098800006137f9ba0100002800000000000003e800000000000003e800000000938580c0" ->
        """{"signature":"8efb98c939aba422a1f2ccd3e05e5471be41c54ac5d7cb27b9aaaecea45f3abb363907644c44b385d83ef6b577061847396d6d3464e4f1fa9e779395e36703ef","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"686557x2440x0","timestamp":{"iso":"2021-09-07T23:46:02Z","unix":1631058362},"messageFlags":{"dontForward":false},"channelFlags":{"isEnabled":true,"isNode1":true},"cltvExpiryDelta":40,"htlcMinimumMsat":1000,"feeBaseMsat":0,"feeProportionalMillionths":1000,"htlcMaximumMsat":2475000000,"tlvStream":{}}""",
      hex"b212e4d88a5ce3201ec34160d90a07eeb0601207d7d53bcf2b8f99b21146d7eb00d6a5b4b80b878eac0d25c2209eda05c913851730a65260c943fec8956cb22e6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d61900000000000a48ce0006900000613792e40100002800000000000003e8000003e8000000010000000056d35b20" ->
        """{"signature":"b212e4d88a5ce3201ec34160d90a07eeb0601207d7d53bcf2b8f99b21146d7eb00d6a5b4b80b878eac0d25c2209eda05c913851730a65260c943fec8956cb22e","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"673998x1680x0","timestamp":{"iso":"2021-09-07T16:27:16Z","unix":1631032036},"messageFlags":{"dontForward":false},"channelFlags":{"isEnabled":true,"isNode1":true},"cltvExpiryDelta":40,"htlcMinimumMsat":1000,"feeBaseMsat":1000,"feeProportionalMillionths":1,"htlcMaximumMsat":1456692000,"tlvStream":{}}""",
      hex"29396591aee1bfd292193b4329d24eb9f57ddb143f303d029ae004113a7402af015c721ddc3e5d2e36cc67c92af3bdcd22d55eaf1e532503f9972207b226984f6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000096f010006ea000061375a440102002800000000000003e8000003e800000001000000024e160300" ->
        """{"signature":"29396591aee1bfd292193b4329d24eb9f57ddb143f303d029ae004113a7402af015c721ddc3e5d2e36cc67c92af3bdcd22d55eaf1e532503f9972207b226984f","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"618241x1770x0","timestamp":{"iso":"2021-09-07T12:25:40Z","unix":1631017540},"messageFlags":{"dontForward":false},"channelFlags":{"isEnabled":false,"isNode1":true},"cltvExpiryDelta":40,"htlcMinimumMsat":1000,"feeBaseMsat":1000,"feeProportionalMillionths":1,"htlcMaximumMsat":9900000000,"tlvStream":{}}""",
      hex"3c6de66a61f2b8803537a2d92e7b82db1b44eac664ed6b7f5c7b5360b21d7ce32e5238e98d54701fe6d5b9109b2a2d875878a12d254eb6d651843b787f1ba5de6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d61900000000000a921f0003fc000461386d9e0100002800000000000003e8000003e80000000100000000ec08ce00" ->
        """{"signature":"3c6de66a61f2b8803537a2d92e7b82db1b44eac664ed6b7f5c7b5360b21d7ce32e5238e98d54701fe6d5b9109b2a2d875878a12d254eb6d651843b787f1ba5de","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"692767x1020x4","timestamp":{"iso":"2021-09-08T08:00:30Z","unix":1631088030},"messageFlags":{"dontForward":false},"channelFlags":{"isEnabled":true,"isNode1":true},"cltvExpiryDelta":40,"htlcMinimumMsat":1000,"feeBaseMsat":1000,"feeProportionalMillionths":1,"htlcMaximumMsat":3960000000,"tlvStream":{}}""",
      hex"180de159377d68ecc3b327594bfb7408374811f3c98b5982af1520802796025a1430a6049294ebc0030518cc9b56a574c38c316122cb674f972734d7054d0b546fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d61900000000000868f200029a00006137935701030028000000000000000000000001000002bc0000000001c9c380" ->
        """{"signature":"180de159377d68ecc3b327594bfb7408374811f3c98b5982af1520802796025a1430a6049294ebc0030518cc9b56a574c38c316122cb674f972734d7054d0b54","chainHash":"6fe28c0ab6f1b372c1a6a246ae63f74f931e8365e15a089c68d6190000000000","shortChannelId":"551154x666x0","timestamp":{"iso":"2021-09-07T16:29:11Z","unix":1631032151},"messageFlags":{"dontForward":false},"channelFlags":{"isEnabled":false,"isNode1":false},"cltvExpiryDelta":40,"htlcMinimumMsat":0,"feeBaseMsat":1,"feeProportionalMillionths":700,"htlcMaximumMsat":30000000,"tlvStream":{}}""",
      hex"cb6aacede86c15cb64f2513e357e1fe2384dd17b8e613608dfdca48cf884043c3431faaccc1e2417cbe938213686efe15e0d0549c75bb66c1675a4909e8e60ee06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f000000000000023162ea9b1f03010024000000000000003200000064000000fa0000000002faf080" ->
        """{"signature":"cb6aacede86c15cb64f2513e357e1fe2384dd17b8e613608dfdca48cf884043c3431faaccc1e2417cbe938213686efe15e0d0549c75bb66c1675a4909e8e60ee","chainHash":"06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f","shortChannelId":"0x0x561","timestamp":{"iso":"2022-08-03T15:58:23Z","unix":1659542303},"messageFlags":{"dontForward":true},"channelFlags":{"isEnabled":true,"isNode1":false},"cltvExpiryDelta":36,"htlcMinimumMsat":50,"feeBaseMsat":100,"feeProportionalMillionths":250,"htlcMaximumMsat":50000000,"tlvStream":{}}"""
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

  test("encode/decode UpdateAddHtlc") {
    val testCases = Map(
      hex"2e184fc141277ba9a3fbe752206f5714c3cfe50765c258dfbdb10cead1ef57f9 0000000000000001 000000000000b5bb 05fc4f9f94ecb97574a90c9154740a3a6c16195d6d0136b71d60d9dae33ce999 000d5fff 00 02c606691a88f80fdc10d007ef5dfa0b91ce33b7b3fa40a6df84f7285aaf37174e 708636c4c5bab2c45e0e9b94f48791e468a9e54af63ee9dfd09d947d6a845b03133eb69226293754caf18b41b99c66830e327938b2fe44e54e31cd8a2c0ee1c43c6c50a8d29bd3f27eb88f70d6ecd7f1b2afb7d721dbad9ac34a511c5e49e5c44e0beb5e513b930fea34eb3ce22e5cebc55c85efa2b24a698ee4f45207977693cebc59f3cad9088387ea89ae45cbb9700bb3e0d93b82d10ea994979a90f4d6265312ae370c80a0c323f5abaa8dcc09aa637b85f2b40d7324885fc744719ec966154c2cd4a512abc618232b82855261886937af9f92d308eba5a5b03e99f4e96535a7e4aeb29c4a260938b85bc16218eb0fe2c765519dd811e0e633bb6e26004393db285ffd04bf6be33ec410bcad437d515484e910960b3d2b1f719963c215c26a0f29b86dfde41c098780d5aaf9b48c95f7e3d6582955feee058e5d0f87671202caf4899a2f5f238f4938b20d14f3d1e94893666e9c36f9c559f05f065ec547f417b58b81d7f5e71563f0565c30f82e9e8e4755f74b8633fb5c7645a551ebf27b9535aa1bde6f12df2b85cc30b9083d602f7eea0e9093f86aa2346e8900851e884470026f6f46e9748322c786145cd0cc8a0d712aef89466a06e5c2795cf5d326f78a5af746f61f4df3b08f17104b1ce3099a20fab9b2751b3635aec743a986173861d790c31942bd608258a927d75309c15ffd690a0713179d62a60b459be7486d03b774119d12168a9d0761134d789264662ed1e21329c840aa6f958cd0bfddd8cbeb61ac9fb5f379ee8557e3962f85871928d2fae5d9f1026eb95248f38689f44597d1b316a1860597abb77e08fa58778f39fbb13f38c727cdabf58592f3932a272195dde4ccd62d57239cb82d274ed239f39132cf83fa4629435af985ef24a8aecc4e8837ce53658cdbe97951b83f5f3643525f19f3e46312285684631b93e12e47b6922855cdf81ef5459bc26667a17459c537fbc169485bc23daa9c573a86010b9627864842eb3fb01afd90b288e86050c87e5f1e8d49fd6fae7c5c5256d27471baf29017e092b4c5a96f063a8c56ccf90838e2da89f42a9d4af35236e3f12b3253f6981e5db1a4cf453622fcc2e11b51afd2a88b09ed13905bbaeef91b9b80523e13fe6b8c2386f6c83c3cad5de89405e2da894bf30733846266904be964ac8d67e8eff54b9ee3f8e6c88797fdaf9832a2693e7d0b6471bbc234fd72c1f02a8e48f3fb43cf0609802d129e6b46ad542dac1feee2128cf2688a354dacddd0a50ec88b517e9f315c7df81d5002f2809b4009b0ed55b2d960f0390eec4c1824afe013332aa6d0e1f0d65877485471918e7667addd27e592731011e813a5085035f1dc11b4d7dac122f05a033b702d91708e0c708337b3be0fa8463cce23e52f667520908dc5e8a94ff051ffaf50fec11b8a3f880e4d0ededd7b0a6c71f6e939553402eb2ea370334776336e726880a184d7dcfd7c84d12c8feb31a479259f2c6520c5432cf71babc522b7f090cc527df41b1c7d8e3b5e2c1f5a8d4a3e1da578921321e472be5f6f5076be9e9d2255a46e072e19771c461973c44bb47e154e85bb76f0f1152e9bb1209c1707b0f42e6507cd9e4f026156a788ef65b221f0c864efc334132624ba1b96a1ecd7c7d460acb7c1d7b408395410b189c4ae374d143c96c48392abceee4366903a05d9ba884496bbff603b65967fd5a434530fe5accd48f40f00f1a5347f1602dd4abe545e5826f24344bbe88cd3b2ffd1320fb40407ec175f8c17c16a52ade59383357e52eff8b8d5c318172b703c69c4a04786088ee63f2fc9cea63294a33546ea1a954ae9a79c 47de7249c4b76e4db71df5d070dab15ea294f22011fc21a544c26416aaff2682 00 21 0396b9c21b054a49f35ee7abb96e677ebbcaae876d602349cd58b3854380782818 fe0001a147 01 05" ->
        UpdateAddHtlc(ByteVector32(hex"2e184fc141277ba9a3fbe752206f5714c3cfe50765c258dfbdb10cead1ef57f9"), 1, 46523 msat, ByteVector32(hex"05fc4f9f94ecb97574a90c9154740a3a6c16195d6d0136b71d60d9dae33ce999"), CltvExpiry(876543), OnionRoutingPacket(0, hex"02c606691a88f80fdc10d007ef5dfa0b91ce33b7b3fa40a6df84f7285aaf37174e", payload = hex"708636c4c5bab2c45e0e9b94f48791e468a9e54af63ee9dfd09d947d6a845b03133eb69226293754caf18b41b99c66830e327938b2fe44e54e31cd8a2c0ee1c43c6c50a8d29bd3f27eb88f70d6ecd7f1b2afb7d721dbad9ac34a511c5e49e5c44e0beb5e513b930fea34eb3ce22e5cebc55c85efa2b24a698ee4f45207977693cebc59f3cad9088387ea89ae45cbb9700bb3e0d93b82d10ea994979a90f4d6265312ae370c80a0c323f5abaa8dcc09aa637b85f2b40d7324885fc744719ec966154c2cd4a512abc618232b82855261886937af9f92d308eba5a5b03e99f4e96535a7e4aeb29c4a260938b85bc16218eb0fe2c765519dd811e0e633bb6e26004393db285ffd04bf6be33ec410bcad437d515484e910960b3d2b1f719963c215c26a0f29b86dfde41c098780d5aaf9b48c95f7e3d6582955feee058e5d0f87671202caf4899a2f5f238f4938b20d14f3d1e94893666e9c36f9c559f05f065ec547f417b58b81d7f5e71563f0565c30f82e9e8e4755f74b8633fb5c7645a551ebf27b9535aa1bde6f12df2b85cc30b9083d602f7eea0e9093f86aa2346e8900851e884470026f6f46e9748322c786145cd0cc8a0d712aef89466a06e5c2795cf5d326f78a5af746f61f4df3b08f17104b1ce3099a20fab9b2751b3635aec743a986173861d790c31942bd608258a927d75309c15ffd690a0713179d62a60b459be7486d03b774119d12168a9d0761134d789264662ed1e21329c840aa6f958cd0bfddd8cbeb61ac9fb5f379ee8557e3962f85871928d2fae5d9f1026eb95248f38689f44597d1b316a1860597abb77e08fa58778f39fbb13f38c727cdabf58592f3932a272195dde4ccd62d57239cb82d274ed239f39132cf83fa4629435af985ef24a8aecc4e8837ce53658cdbe97951b83f5f3643525f19f3e46312285684631b93e12e47b6922855cdf81ef5459bc26667a17459c537fbc169485bc23daa9c573a86010b9627864842eb3fb01afd90b288e86050c87e5f1e8d49fd6fae7c5c5256d27471baf29017e092b4c5a96f063a8c56ccf90838e2da89f42a9d4af35236e3f12b3253f6981e5db1a4cf453622fcc2e11b51afd2a88b09ed13905bbaeef91b9b80523e13fe6b8c2386f6c83c3cad5de89405e2da894bf30733846266904be964ac8d67e8eff54b9ee3f8e6c88797fdaf9832a2693e7d0b6471bbc234fd72c1f02a8e48f3fb43cf0609802d129e6b46ad542dac1feee2128cf2688a354dacddd0a50ec88b517e9f315c7df81d5002f2809b4009b0ed55b2d960f0390eec4c1824afe013332aa6d0e1f0d65877485471918e7667addd27e592731011e813a5085035f1dc11b4d7dac122f05a033b702d91708e0c708337b3be0fa8463cce23e52f667520908dc5e8a94ff051ffaf50fec11b8a3f880e4d0ededd7b0a6c71f6e939553402eb2ea370334776336e726880a184d7dcfd7c84d12c8feb31a479259f2c6520c5432cf71babc522b7f090cc527df41b1c7d8e3b5e2c1f5a8d4a3e1da578921321e472be5f6f5076be9e9d2255a46e072e19771c461973c44bb47e154e85bb76f0f1152e9bb1209c1707b0f42e6507cd9e4f026156a788ef65b221f0c864efc334132624ba1b96a1ecd7c7d460acb7c1d7b408395410b189c4ae374d143c96c48392abceee4366903a05d9ba884496bbff603b65967fd5a434530fe5accd48f40f00f1a5347f1602dd4abe545e5826f24344bbe88cd3b2ffd1320fb40407ec175f8c17c16a52ade59383357e52eff8b8d5c318172b703c69c4a04786088ee63f2fc9cea63294a33546ea1a954ae9a79c", ByteVector32(hex"47de7249c4b76e4db71df5d070dab15ea294f22011fc21a544c26416aaff2682")), TlvStream(UpdateAddHtlcTlv.BlindingPoint(PublicKey(hex"0396b9c21b054a49f35ee7abb96e677ebbcaae876d602349cd58b3854380782818")), UpdateAddHtlcTlv.Endorsement(5))),
      hex"f865a44f81f02f3539842b863668403a68ddb3703e03cd91045c9ac114dbd28e 0000000000000002 0000000000000092 d3708298fd195572cceb86a1745210543c42f931a1a2baeed7f705d333ebed22 00013368 00 037cb785d5a9de762adc62e3f2407d452bd1f13d368d0429caee5541a89488e3b9 5b90dd3803ff56400a40dc539efa0a0e29736c76e83d2d8b44775adcb4d485be5eb84eefbfedc69b687ee5c7080f83ff31760ec036990d904de480064966bba393af729d57437c91bea905a66464461a6462c5a0c66976ecaeded73d330dfaeb5651ba68e74b210c123e63ac6ee15d6673cf126f046840bbf4364c907f56382870da85101045fc9392357b0b32081cde0e28460d20c1d5d61a5d56fe50d107304e4b184dd005048f9bcd159eff3cfaaef4ab5a8f29d38de109ea41a7ceaa886a0559a4fd0c7448cf28db85c3758fbde23443776e08dd29c435b1195f205aa787f2ec4a7259afeff078faa419c9af5706b9c08e7ce5772326e09df22eb85b0abc625b969aae34a881c12bd9653a08dc62b8e82cf89d0d66974f96149c6dcd7fc4363eecbf4fdac39e500c416b8d6a2e5871d80775acd1c13df4e4ad30a150390d500869ee6a4eab1c4285952d0549c45960a0b1b5ccfb93c75b63923fbc02b1d5a91e53f7424478cc58bf4366a612d40deec5efea700a7b127f7fdded1c4232e5b2eb7190f964e20b156e62d63d097005a24d96457a3db387a6b25e2a65fb169de0323a48f275780dc320d1c7d22642d3371e65559bf5510a3990b67aceb87daccd40e7f82fbba5a0065a63849dbb6600b420e8cb3561154dc69a3b063784737a7eb9d3f770fa1312c03e8518200cefc1be356bf0b0d1928816e712d24b5021b72f20d84a062a47ef50acf5cc9de025378611fbd7fd6473bc0258aa1697b057f6ce4c99f7a6ade34411a008aaccb3e3b2f78e2bc0720c3050960ad8b2988907a410a8fe565b671d3f2a274b9ed230786da769ebc73e9212b41902bec589d602dc941a6dc8a3c37ede467fdb21101cc8befe111b8a365b94612be8eef16a14dc1f163647744c2d0eabc17dc0bd297ba2e9237fea5d8c845e11ae207c4ce8d5d17b2dffdb6ba20c0474ab9e8547b90bdf61d41602b64b84b3d725279e818dccb82f25c6a75dc473af074c97bd6ea775eb575dd07bd2574dfe308748ecc0d39df14659e1958dbc0413fa7f214b6ffb4059b0ef21e9c2602723695988382201c36dfb9bb5916246f0ecad281b1431846e43b5651a85745a4b81586282285f56625fdb8f46a7b752f1a81159f04c12ec20723c22ae2dadd384bcedecc16de9ae253081ec8b5616b94ec716eb2e91c6af58eddcb360841ec942782fe7b44e9ea191e98007faa5722f12b5e0ff23304297f3aeb369a3ff79434f2ff2e7575bd7e7a1d2592043ea245ca0f69cc0c781b42258230b45391d1c1545ed0a9dcdbedc454c6e7dd313b2e6e757f91309d8fa7801bba864f60f04a1b12e0770e4aa62b7d388f8a0b85d38defa8a21a7764388dd7273b941a17f1f1b1a7acefc8f1726a7cb4ea3f19ddb7a70082c1c5cb6921d4c0eea07cf4d226c98ed1c57a92652b2687181da8091db3ac77bbf5634c351990296494868dd1e365af320c88148a0ed887a06f4be3256cf4556f00ce3376a7016ffddc26f36272d48fc5500451a2ea6de5f6778948e9ba856db5a38129ad1367b983d1f2b624ac5e2e35ce9145eee563d047c853a30c6cabb8064ea183dba622adebae8d2149b93752776e55155319009f2f9a3c1aec9a2b266fdb0451c97fbcc8ca7c1adff0806adc1dc8105b678a30af95cfc5f370dd0e4a6db9811b2deac2ed1fc8716b0571210ee7208316f24dc2af7394519401173d36c3f25fbe4ebd965aa19f53e6fef550ab72216dcfd20ac974b40b456ce143bd4e495dd51fd58e23877ac91e28abed7e9a12c7fbf694e5b5cf144f011da4aca445cf5546d68b2341401460bf2d717663c 022406339068be457a4430d0de697ee810f0911afc344bd3d4d662771874d2ce 00 21 02039885cd5b9ffd24b5ce83f464a7d4c0e3f23c1a8061c9fc85730db67ffdbd0c fe0001a147 01 00" ->
        UpdateAddHtlc(ByteVector32(hex"f865a44f81f02f3539842b863668403a68ddb3703e03cd91045c9ac114dbd28e"), 2, 146 msat, ByteVector32(hex"d3708298fd195572cceb86a1745210543c42f931a1a2baeed7f705d333ebed22"), CltvExpiry(78696), OnionRoutingPacket(0, hex"037cb785d5a9de762adc62e3f2407d452bd1f13d368d0429caee5541a89488e3b9", payload = hex"5b90dd3803ff56400a40dc539efa0a0e29736c76e83d2d8b44775adcb4d485be5eb84eefbfedc69b687ee5c7080f83ff31760ec036990d904de480064966bba393af729d57437c91bea905a66464461a6462c5a0c66976ecaeded73d330dfaeb5651ba68e74b210c123e63ac6ee15d6673cf126f046840bbf4364c907f56382870da85101045fc9392357b0b32081cde0e28460d20c1d5d61a5d56fe50d107304e4b184dd005048f9bcd159eff3cfaaef4ab5a8f29d38de109ea41a7ceaa886a0559a4fd0c7448cf28db85c3758fbde23443776e08dd29c435b1195f205aa787f2ec4a7259afeff078faa419c9af5706b9c08e7ce5772326e09df22eb85b0abc625b969aae34a881c12bd9653a08dc62b8e82cf89d0d66974f96149c6dcd7fc4363eecbf4fdac39e500c416b8d6a2e5871d80775acd1c13df4e4ad30a150390d500869ee6a4eab1c4285952d0549c45960a0b1b5ccfb93c75b63923fbc02b1d5a91e53f7424478cc58bf4366a612d40deec5efea700a7b127f7fdded1c4232e5b2eb7190f964e20b156e62d63d097005a24d96457a3db387a6b25e2a65fb169de0323a48f275780dc320d1c7d22642d3371e65559bf5510a3990b67aceb87daccd40e7f82fbba5a0065a63849dbb6600b420e8cb3561154dc69a3b063784737a7eb9d3f770fa1312c03e8518200cefc1be356bf0b0d1928816e712d24b5021b72f20d84a062a47ef50acf5cc9de025378611fbd7fd6473bc0258aa1697b057f6ce4c99f7a6ade34411a008aaccb3e3b2f78e2bc0720c3050960ad8b2988907a410a8fe565b671d3f2a274b9ed230786da769ebc73e9212b41902bec589d602dc941a6dc8a3c37ede467fdb21101cc8befe111b8a365b94612be8eef16a14dc1f163647744c2d0eabc17dc0bd297ba2e9237fea5d8c845e11ae207c4ce8d5d17b2dffdb6ba20c0474ab9e8547b90bdf61d41602b64b84b3d725279e818dccb82f25c6a75dc473af074c97bd6ea775eb575dd07bd2574dfe308748ecc0d39df14659e1958dbc0413fa7f214b6ffb4059b0ef21e9c2602723695988382201c36dfb9bb5916246f0ecad281b1431846e43b5651a85745a4b81586282285f56625fdb8f46a7b752f1a81159f04c12ec20723c22ae2dadd384bcedecc16de9ae253081ec8b5616b94ec716eb2e91c6af58eddcb360841ec942782fe7b44e9ea191e98007faa5722f12b5e0ff23304297f3aeb369a3ff79434f2ff2e7575bd7e7a1d2592043ea245ca0f69cc0c781b42258230b45391d1c1545ed0a9dcdbedc454c6e7dd313b2e6e757f91309d8fa7801bba864f60f04a1b12e0770e4aa62b7d388f8a0b85d38defa8a21a7764388dd7273b941a17f1f1b1a7acefc8f1726a7cb4ea3f19ddb7a70082c1c5cb6921d4c0eea07cf4d226c98ed1c57a92652b2687181da8091db3ac77bbf5634c351990296494868dd1e365af320c88148a0ed887a06f4be3256cf4556f00ce3376a7016ffddc26f36272d48fc5500451a2ea6de5f6778948e9ba856db5a38129ad1367b983d1f2b624ac5e2e35ce9145eee563d047c853a30c6cabb8064ea183dba622adebae8d2149b93752776e55155319009f2f9a3c1aec9a2b266fdb0451c97fbcc8ca7c1adff0806adc1dc8105b678a30af95cfc5f370dd0e4a6db9811b2deac2ed1fc8716b0571210ee7208316f24dc2af7394519401173d36c3f25fbe4ebd965aa19f53e6fef550ab72216dcfd20ac974b40b456ce143bd4e495dd51fd58e23877ac91e28abed7e9a12c7fbf694e5b5cf144f011da4aca445cf5546d68b2341401460bf2d717663c", ByteVector32(hex"022406339068be457a4430d0de697ee810f0911afc344bd3d4d662771874d2ce")), TlvStream(UpdateAddHtlcTlv.BlindingPoint(PublicKey(hex"02039885cd5b9ffd24b5ce83f464a7d4c0e3f23c1a8061c9fc85730db67ffdbd0c")), UpdateAddHtlcTlv.Endorsement(0))),
      hex"2c2c2f7eb2eed5b415aed6671228a90d428d9c9fa1dbf492b0625dc4c7d243c3 0000000000000003 00000000000ba6f3 0b7cb0fb7cedeb92573b8865018730232430c8d2365c4e22017f306ae3853ff7 00001211 00 0344ad77d64a38466f8cabc92a956ccceb64e451d09945a45d4be7a16bcb59d84c a62cd346fe5002786e00d538f85d0606b7e946717c62f0df9cd806c04c27d02ce1e536560633099d81fa158aba333c4ccfb91b30bc7e361fcde9705457a0efb1aa5e5983448833b3603d4459a1322275cc29b79d285b762c38b916e176fb779c5ca8cb1804300342462cf4c10d6229e73e5a382976306d0d65583490a5b595fb7f41f4fef20496791381b16a51840c17e805ee44961316cb0ca62d7a0a23a1d42c0d8098128b09ab21ce4a6b5b8749c45f5e0f761c1e24c2e141714fa32bba27da8a113ea26f9af687dd6bafc902b4fb7e53af3dea9fc675928207c898eb2327cea938b342cab7e57cf9c34ec443ace8e66bdc41f98f934e8ba18db357f49d1bdf540632b369435b2e378cd97304e49ce037f531f2faf381a70aaef06582eb2b0956a6b7e39dcaf469253ed6a508947f1a715f6c42c028af2342ced466d7d65bf7d3282ee403b6f220403abad14541d806b355ac38c262dc943c7c239c23b1f863f87259838288a6b5868da8436a56d4d14e7eca32b92070f95ef332c09f3693e952841d6771cd5904a903910b8333337786acdc3099733534ac237e0fbef3acd8e0b4fd665afae94f886dcd86ba0ffe39b6a2fff7e761125ed48c9ef7340d73c3b52a4acf4336b9cc891196158ad77442b242016c1b2333d4be6708c51e5d4ca42cf90438cf21bb7e63731a3be083b20c74954997114c4d08ca886e93055f0fdb34efc3237ca40d28f386b441a699dbaf27f2abb82a49b864d67bca6db2b8b393c02181ec058e350f0f28037ccdca3815fe3f85af3fbcc9c2bcfbffec9ddbc292539ccd16df09c6c892533b3831d7463ac0091d6e3ddd1a5a282a686ce037d47a7c6e373f98110616a1f5f2031a8d231532beebc1703cbaf262c286db4d42ddcdf11c338a0b15dddf422ca09e76a43d453414b8900db9a1e2a6791543e8d9d3d0e3cbe82f2c6ffaa433bb675322e9ae104a9d7b7af7052a44f4b58b522418563ff4c859d5e954c50a8af1295d71f575a888fc30ce25c9d23f1954636ae6f6b2f987ce15a25fbfd7432ca83d2d6f1292dbb1c557b82b9d5bd0fed0b9e21e15401c23d08dfd613403d9127d6b8e4b7673c6ff6c07c47f806f251e36e71e5778f38e73233008d1968a5e6adab26cf77c6fcadd62ae3304c7ba89614107faeaa8eec0c9e9ea9307abcca1e44e40228991aec789cac3d190bb9ab425ad834b6fc69ca8776d926dc6215de382a1275bc327447a5f5ef6c92ae1a2c45cf27441692a5f5ff13a1d5b365aec77c726923f14e376a6aaa9b4ada3931350b8b7eab50e101a9714b884c73ad4fef78520f2582c4f4328b18b1102ea5c93b96055bdc9c955adbeda29a58acc1937e4cb185a481a7d9ec56050d5216869bdcb7773718c68f36de348043116f6f33d98c9b56f8111a2a08f76cf1dbcb0e86660dab947900ed0c6592429b23a9f1d21c72d9a27544a8e135241eb52e3080fa517efd232d6f7f7fb03f82e9332ab5292be9bdf8d67978dd1bc99eff426e02fb3dc9dd15c660747c88a46dd92aeba448be690bf1659a30b1ef304db9d5d607e82a5120439c36e70225a234ef3e4699920426826098ea215553fcb933a4e1ee8a86e53d9cbbcb1b3da0122cfaa2c245b0c60abd5a6dcbf27d4d5cc3374c0285c973bce4f2ab75c7fe19b1e75694568db79c7181a91f36737bb02d635a831e35afa93cb068e8389c3968443a6d2f679ace7e71c1525689b34cfb714e46843fe268320193ce5afdc9dd7aa506f5ed845292dbdd96f91dcbd436e870d59aa4dcac71b19c9756498b2ac9e4d8c7bb7c456559a0775494c326510a2d84a60ac eb26d892176ae2cddd393e1bb626ec2df1f1ae4c65e89f091cb4201e6aa132a5 fe0001a147 01 03" ->
        UpdateAddHtlc(ByteVector32(hex"2c2c2f7eb2eed5b415aed6671228a90d428d9c9fa1dbf492b0625dc4c7d243c3"), 3, 763635 msat, ByteVector32(hex"0b7cb0fb7cedeb92573b8865018730232430c8d2365c4e22017f306ae3853ff7"), CltvExpiry(4625), OnionRoutingPacket(0, hex"0344ad77d64a38466f8cabc92a956ccceb64e451d09945a45d4be7a16bcb59d84c", payload = hex"a62cd346fe5002786e00d538f85d0606b7e946717c62f0df9cd806c04c27d02ce1e536560633099d81fa158aba333c4ccfb91b30bc7e361fcde9705457a0efb1aa5e5983448833b3603d4459a1322275cc29b79d285b762c38b916e176fb779c5ca8cb1804300342462cf4c10d6229e73e5a382976306d0d65583490a5b595fb7f41f4fef20496791381b16a51840c17e805ee44961316cb0ca62d7a0a23a1d42c0d8098128b09ab21ce4a6b5b8749c45f5e0f761c1e24c2e141714fa32bba27da8a113ea26f9af687dd6bafc902b4fb7e53af3dea9fc675928207c898eb2327cea938b342cab7e57cf9c34ec443ace8e66bdc41f98f934e8ba18db357f49d1bdf540632b369435b2e378cd97304e49ce037f531f2faf381a70aaef06582eb2b0956a6b7e39dcaf469253ed6a508947f1a715f6c42c028af2342ced466d7d65bf7d3282ee403b6f220403abad14541d806b355ac38c262dc943c7c239c23b1f863f87259838288a6b5868da8436a56d4d14e7eca32b92070f95ef332c09f3693e952841d6771cd5904a903910b8333337786acdc3099733534ac237e0fbef3acd8e0b4fd665afae94f886dcd86ba0ffe39b6a2fff7e761125ed48c9ef7340d73c3b52a4acf4336b9cc891196158ad77442b242016c1b2333d4be6708c51e5d4ca42cf90438cf21bb7e63731a3be083b20c74954997114c4d08ca886e93055f0fdb34efc3237ca40d28f386b441a699dbaf27f2abb82a49b864d67bca6db2b8b393c02181ec058e350f0f28037ccdca3815fe3f85af3fbcc9c2bcfbffec9ddbc292539ccd16df09c6c892533b3831d7463ac0091d6e3ddd1a5a282a686ce037d47a7c6e373f98110616a1f5f2031a8d231532beebc1703cbaf262c286db4d42ddcdf11c338a0b15dddf422ca09e76a43d453414b8900db9a1e2a6791543e8d9d3d0e3cbe82f2c6ffaa433bb675322e9ae104a9d7b7af7052a44f4b58b522418563ff4c859d5e954c50a8af1295d71f575a888fc30ce25c9d23f1954636ae6f6b2f987ce15a25fbfd7432ca83d2d6f1292dbb1c557b82b9d5bd0fed0b9e21e15401c23d08dfd613403d9127d6b8e4b7673c6ff6c07c47f806f251e36e71e5778f38e73233008d1968a5e6adab26cf77c6fcadd62ae3304c7ba89614107faeaa8eec0c9e9ea9307abcca1e44e40228991aec789cac3d190bb9ab425ad834b6fc69ca8776d926dc6215de382a1275bc327447a5f5ef6c92ae1a2c45cf27441692a5f5ff13a1d5b365aec77c726923f14e376a6aaa9b4ada3931350b8b7eab50e101a9714b884c73ad4fef78520f2582c4f4328b18b1102ea5c93b96055bdc9c955adbeda29a58acc1937e4cb185a481a7d9ec56050d5216869bdcb7773718c68f36de348043116f6f33d98c9b56f8111a2a08f76cf1dbcb0e86660dab947900ed0c6592429b23a9f1d21c72d9a27544a8e135241eb52e3080fa517efd232d6f7f7fb03f82e9332ab5292be9bdf8d67978dd1bc99eff426e02fb3dc9dd15c660747c88a46dd92aeba448be690bf1659a30b1ef304db9d5d607e82a5120439c36e70225a234ef3e4699920426826098ea215553fcb933a4e1ee8a86e53d9cbbcb1b3da0122cfaa2c245b0c60abd5a6dcbf27d4d5cc3374c0285c973bce4f2ab75c7fe19b1e75694568db79c7181a91f36737bb02d635a831e35afa93cb068e8389c3968443a6d2f679ace7e71c1525689b34cfb714e46843fe268320193ce5afdc9dd7aa506f5ed845292dbdd96f91dcbd436e870d59aa4dcac71b19c9756498b2ac9e4d8c7bb7c456559a0775494c326510a2d84a60ac", ByteVector32(hex"eb26d892176ae2cddd393e1bb626ec2df1f1ae4c65e89f091cb4201e6aa132a5")), TlvStream(UpdateAddHtlcTlv.Endorsement(3))),
      hex"6c963f8e8b9be358f190a3ac3e12a34400bda4796ed9c23daf179794474a9b62 0000000000000004 00000000000000f5 d9b2563807d4830dc7a42e2df0a146b2acecd54ca3870a928f2b4ac5b489d0eb 000b3c4e 00 033df0a97d288ef59a42b68c03083c36f06b75e651f2620275347e49456e924949 afe9ae18f4780afe43a1450247b5c790e47a27983aa63b82356d049c277517f4991776396cfbbbb5905059a8ebcd49a1c63299a40df59bb8e1842025c8644defa4a0f0bd80d159c68b49747ad1625fbb5182a48634238d42b2678d39d5db9a67fbb3624cf10249b286ba780ced9ede8e37d93a248f756dc134401656d787d2106303082d26601a48aa30804632877de8bc721556f30e57caa3787b04f3712b4d320c24afa7891e70e6f76751cc47a09ddf86aea7099c43809c7f244b21e551d63d363f1c6b5db02504c46449fcfc8038e057713ed1bc5e6daa1b44a90a9db259964b963be6cbdfb4aa000caaf9984aa12ae5a2dc2323b9ab57c1ca35f722c29adeb08789aff2f25936070f38b9b390937983ba8d6434fed6cfd9077e6508b85a2ba020ffc9dc2507beb3278fda821f2ae61ef0ec6a4a226f7b067cc7e69122eeb91dda7885bf9d358d1dfd4ea5af1df4bae30eebe79ddc27abb4edfa4882e9167e557bd0aabb71c5b906f4d5c537a816ee958a1d7a76597e262b50198ba25fd0fb4971c5e22ad0724d1686afa1edb8a5ddf8ad57443258d8044f331463c6ce0f278b16a9a11b8c7b88a494c2c524bdfc37d67f0635f36b15356762f825d23e8228602421e065d828d628f3e76a0505be69179772aa62ee481def1ce1621f874e1ea74afcf0f42c3ab559163afd06c493a56ab0e0ce2563f3351dda1096ff7f7215d61689dd3adc51f2204c664c2cb429237423c7cca52d222662577ab5411b1ed05810b2b1e43ade1958fed3b21623cbf19933dd35e6596c886b3fcfb11b7efa78067786740f0ea887921c8d6a6841b74d2166b6cf83d4432b1f17cefdcffebc0ef08fe5416f5f1f5072d44fa835b5f7078723727aba801343669c8a1afc4e9a2ec3c3821af297c5ee5fd6364b866d7f8b47b6709303246a09274f5d17640b6c60fa9eeb2f7e35472f33db8538ca1cff95e39dd09ac3680faa4ca8ba1ed33f9726adc84a50619605a1b763765f1c26d884d74b884351cbca23d935b25095e08b8cce04e360e0587c034d883f1c7a44cfebf82c7c67dc13c6b76d396cb90a8158fa8d270084f716237eb9b6dc464b2c3e857443f0e8f3073079fefdd7f757abaf19b38da991956034ce1be47315022433bbe766e1d6d02c822314706702c2a61234345ff374c4291f5bd00d8d4caeef0a48785c63afb8196d4874f9c19bb53199bc7ed81a0e94108a7b6851b9a2e3a6f4e12a0eaddf16d4ff1cf6ff9f9da6d81cfc167896ecc3b7a2b6f774a1f394a321bdfb40bdaedc2ecc7148a6d6b1ef64e38c6ea35b0bb17986351e82be82aa2233ed069a6913bbf3a87e5b1094bc2c0ff28b918974357217e160562748a2440670ea1055df53e18a9a3afc0f9f34e40f222cb4f9f35a19488f0ca1b23ada14804f32d183971cb918d7b2430b3f2e4b7633204b0793862521d130e926b6583ca466acf4300020e2c85297f617e29e1c4f0e1ea5676062d8fabc8035f71d2598e3cf7f38e5f61b0f4896442b1c0b102f85fbd1068339dafc9debf90b88e89420337ac34643acf017debff60d030de65c22883205327c0af6cdf70349722073195e2597775514a86f766590c43a3b844f78618b7c7a63d2665a800d5bd1edee916c93ede8c0c8dc980ab9f85ff33c3b4740a4b0fc3f3b3e324a349e9c21e0aec8fdcc0a14b0e35b68b3d46cfcfd991eefc8b616f1a376030de33c1662c0210cfbaa27653ff8a814b4acd2ad0a09761db5f0ba8ef2a00cf66053725a4e422b0cd22f9d4881e28573ccfd3b9b3088698c1acb647d8ddeda65303fc57d9ad663a016b1c1a0dd6712 f6514b5e1eae383e2c5ae1ec1820f28583304274fa11ef2d2e2d6f3cafa2ede0 fe0001a147 01 07" ->
        UpdateAddHtlc(ByteVector32(hex"6c963f8e8b9be358f190a3ac3e12a34400bda4796ed9c23daf179794474a9b62"), 4, 245 msat, ByteVector32(hex"d9b2563807d4830dc7a42e2df0a146b2acecd54ca3870a928f2b4ac5b489d0eb"), CltvExpiry(736334), OnionRoutingPacket(0, hex"033df0a97d288ef59a42b68c03083c36f06b75e651f2620275347e49456e924949", payload = hex"afe9ae18f4780afe43a1450247b5c790e47a27983aa63b82356d049c277517f4991776396cfbbbb5905059a8ebcd49a1c63299a40df59bb8e1842025c8644defa4a0f0bd80d159c68b49747ad1625fbb5182a48634238d42b2678d39d5db9a67fbb3624cf10249b286ba780ced9ede8e37d93a248f756dc134401656d787d2106303082d26601a48aa30804632877de8bc721556f30e57caa3787b04f3712b4d320c24afa7891e70e6f76751cc47a09ddf86aea7099c43809c7f244b21e551d63d363f1c6b5db02504c46449fcfc8038e057713ed1bc5e6daa1b44a90a9db259964b963be6cbdfb4aa000caaf9984aa12ae5a2dc2323b9ab57c1ca35f722c29adeb08789aff2f25936070f38b9b390937983ba8d6434fed6cfd9077e6508b85a2ba020ffc9dc2507beb3278fda821f2ae61ef0ec6a4a226f7b067cc7e69122eeb91dda7885bf9d358d1dfd4ea5af1df4bae30eebe79ddc27abb4edfa4882e9167e557bd0aabb71c5b906f4d5c537a816ee958a1d7a76597e262b50198ba25fd0fb4971c5e22ad0724d1686afa1edb8a5ddf8ad57443258d8044f331463c6ce0f278b16a9a11b8c7b88a494c2c524bdfc37d67f0635f36b15356762f825d23e8228602421e065d828d628f3e76a0505be69179772aa62ee481def1ce1621f874e1ea74afcf0f42c3ab559163afd06c493a56ab0e0ce2563f3351dda1096ff7f7215d61689dd3adc51f2204c664c2cb429237423c7cca52d222662577ab5411b1ed05810b2b1e43ade1958fed3b21623cbf19933dd35e6596c886b3fcfb11b7efa78067786740f0ea887921c8d6a6841b74d2166b6cf83d4432b1f17cefdcffebc0ef08fe5416f5f1f5072d44fa835b5f7078723727aba801343669c8a1afc4e9a2ec3c3821af297c5ee5fd6364b866d7f8b47b6709303246a09274f5d17640b6c60fa9eeb2f7e35472f33db8538ca1cff95e39dd09ac3680faa4ca8ba1ed33f9726adc84a50619605a1b763765f1c26d884d74b884351cbca23d935b25095e08b8cce04e360e0587c034d883f1c7a44cfebf82c7c67dc13c6b76d396cb90a8158fa8d270084f716237eb9b6dc464b2c3e857443f0e8f3073079fefdd7f757abaf19b38da991956034ce1be47315022433bbe766e1d6d02c822314706702c2a61234345ff374c4291f5bd00d8d4caeef0a48785c63afb8196d4874f9c19bb53199bc7ed81a0e94108a7b6851b9a2e3a6f4e12a0eaddf16d4ff1cf6ff9f9da6d81cfc167896ecc3b7a2b6f774a1f394a321bdfb40bdaedc2ecc7148a6d6b1ef64e38c6ea35b0bb17986351e82be82aa2233ed069a6913bbf3a87e5b1094bc2c0ff28b918974357217e160562748a2440670ea1055df53e18a9a3afc0f9f34e40f222cb4f9f35a19488f0ca1b23ada14804f32d183971cb918d7b2430b3f2e4b7633204b0793862521d130e926b6583ca466acf4300020e2c85297f617e29e1c4f0e1ea5676062d8fabc8035f71d2598e3cf7f38e5f61b0f4896442b1c0b102f85fbd1068339dafc9debf90b88e89420337ac34643acf017debff60d030de65c22883205327c0af6cdf70349722073195e2597775514a86f766590c43a3b844f78618b7c7a63d2665a800d5bd1edee916c93ede8c0c8dc980ab9f85ff33c3b4740a4b0fc3f3b3e324a349e9c21e0aec8fdcc0a14b0e35b68b3d46cfcfd991eefc8b616f1a376030de33c1662c0210cfbaa27653ff8a814b4acd2ad0a09761db5f0ba8ef2a00cf66053725a4e422b0cd22f9d4881e28573ccfd3b9b3088698c1acb647d8ddeda65303fc57d9ad663a016b1c1a0dd6712", ByteVector32(hex"f6514b5e1eae383e2c5ae1ec1820f28583304274fa11ef2d2e2d6f3cafa2ede0")), TlvStream(UpdateAddHtlcTlv.Endorsement(7))),
      hex"2af2f6410744de2c8c5fe949443d8e137064bd97ef782278c8e02189b6f0231c 0000000000000005 000000002ef6eb30 9fbe04e7e3f8e71768cfc95d1b67a30ff995dbd2a312e44a839123e95f944258 00008e68 00 02c606691a88f80fdc10d007ef5dfa0b91ce33b7b3fa40a6df84f7285aaf37174e cf939423f7ca7c34b07034acef7e19feab1eba59462cf32fb785d4ecf61008247a17c87380ba957f2503ea6a899707f2167ad3972dfc0e7a00d9a0b6aae7b23b5b57903697f74f98808b35f16f546bd4d32395297ed0caddbd744bfe4ab301d8584756d43d6d1e005a35ef9a71ce2e0c88164165f6f125ea3aaed89dba3291e6adfaa29721a9694df0c3cf1f8d09626cf2026e4c10a7bcd42a5220c0f13a5f0caa397c5685aee4bc487f831a28d98b327522c8f63a36e76a367ca82e88ea0ea77a511447142f655fd35e44fb595c96ac7b0c32c79a6bac6d7b3035abbf7fe7ce05c1d2b4b7c25b9626f249a7b9ebc830461b899f460ecd11037c4bec9b0f4c0a4e32b41ae2010fe2c523c56f4d86ef9f0b6aa73b150734f616b8bf20c201620cd05555b7e2e3a822ee02602b3f5f39f9ec933a418b3da707b4f29a42b3346e79a29d9ebb57c14d6c29ad94bc400e2941db92d3f90ebb4af0dd395747f6b0e0bb42648ea37280279ca0d8762ad1af4c96f56f41082cfe446f0a0c923e5fa97fec390382b401940c53ea9ac3f71a20ff61792557ab9d520816e1aa489ea2a38caa20c40e4aff6954e6b8d7469c496f9372a0c031267fa247f1901f1cebe87d2181288740345537f7af69e3f21b65ea1a622e1741cd5504e33afb6922544b3ee022e2be4f9f400ae401db1a43240d9c272a6dc653b647e6d91b392246b98f9a15547bbf6ddf289844c4910c880b362a383728f91379173b527f8ac5bef772267e5633ec6e758d0c3671903b32c8dad8d21ffec3a0b6f27cc5004fc5a12bcf6bd57aa5c6a982f82c8271e9177ccc2b118957d16c760aa1d60cc7e9408040acbfd186d3c76fcdc7b57b180d6e6b8ea311495d7611e73c4548bd3cccc7d12f0ca69b6240f578a635187713aaa9b561e0dff791c437f95a61818ebf55cdc35a44b0860aac41c3abfedccb4eb8970152fa324e10afdbdebc80dbfbb09e4800cbe51c32daf942e3f54f3d3bee352c3c31290a76f07f4b1a079f5f38109b0d0520a253c9feb0b2e0982730ab2b5f3afd41a9f22d7f4380f4ea6f795540daaae771fe6a9119a13ba3f07ca861ed78698447d070dcfc6cb4843c348e33eaf5e576e1ce9412fd72b69673a3c30b9cc528b041b5489c48c265d7f2251a204e8dc40991b3c8f5620e7a207df2eee41f3c42520b21bba2208e5eff594928271250861982334a139738c030e70606c3da9e26c4ec3c286dd678f3b8ffc4dcd4b0bf5ac595764887de862d5c241368d9f2481eb81a529b0cf4d8d75140b440e26e5af4effa7ad05b1b41a2bf223e902e70af44277ebd1690b5d6da0a3dad18485d967dbb77a3d319fd6e4693e6ee9e99ed8d66f2fb0f355560d87fa8d4ed6e1aac8db481be2091b922df75eb738f64246ad5497ef67c0193d7d8cd0a22694ff63b7bdd915217d93bec2c26077f4de4c3a111b19d4455fb4f77ff72c755e89a71f58155fa47b7f8cd775daa881077b908c89be5e7064c588dc6f520f60d4d6816507c156e553775c843a13fafb9db03de8422c36426f3856124f22236dc8151539ae18a927a4a6fa7e4aed21898184c4b9383b0dbaf9b2938bec0a64a6a8cd606eeb72076655ded0d0f71f95f075c03fed936e688c2d202c7c7e93586d8b49eac1dd9871b5fbc2ab854aa4e86119bf317902cb362e03e0c5bb7b79f80071652a64dc1f5172575edcb0e3fac774d853083b6faca3860b661163f4fe4f8595238c76987d088eaedf8c2fafc14a2995b0cfa951c5df92c55e3784215b0722d08bc9a43bb32c6531393465714b190ae78dd1b18b0f3e0c0432c034a11273d36 931651e9b75081bf2acdf3b9b5f1dc9871b47ff39841b5b17e0a2ea2c9bf4fdc" ->
        UpdateAddHtlc(ByteVector32(hex"2af2f6410744de2c8c5fe949443d8e137064bd97ef782278c8e02189b6f0231c"), 5, 787934000 msat, ByteVector32(hex"9fbe04e7e3f8e71768cfc95d1b67a30ff995dbd2a312e44a839123e95f944258"), CltvExpiry(36456), OnionRoutingPacket(0, hex"02c606691a88f80fdc10d007ef5dfa0b91ce33b7b3fa40a6df84f7285aaf37174e", payload = hex"cf939423f7ca7c34b07034acef7e19feab1eba59462cf32fb785d4ecf61008247a17c87380ba957f2503ea6a899707f2167ad3972dfc0e7a00d9a0b6aae7b23b5b57903697f74f98808b35f16f546bd4d32395297ed0caddbd744bfe4ab301d8584756d43d6d1e005a35ef9a71ce2e0c88164165f6f125ea3aaed89dba3291e6adfaa29721a9694df0c3cf1f8d09626cf2026e4c10a7bcd42a5220c0f13a5f0caa397c5685aee4bc487f831a28d98b327522c8f63a36e76a367ca82e88ea0ea77a511447142f655fd35e44fb595c96ac7b0c32c79a6bac6d7b3035abbf7fe7ce05c1d2b4b7c25b9626f249a7b9ebc830461b899f460ecd11037c4bec9b0f4c0a4e32b41ae2010fe2c523c56f4d86ef9f0b6aa73b150734f616b8bf20c201620cd05555b7e2e3a822ee02602b3f5f39f9ec933a418b3da707b4f29a42b3346e79a29d9ebb57c14d6c29ad94bc400e2941db92d3f90ebb4af0dd395747f6b0e0bb42648ea37280279ca0d8762ad1af4c96f56f41082cfe446f0a0c923e5fa97fec390382b401940c53ea9ac3f71a20ff61792557ab9d520816e1aa489ea2a38caa20c40e4aff6954e6b8d7469c496f9372a0c031267fa247f1901f1cebe87d2181288740345537f7af69e3f21b65ea1a622e1741cd5504e33afb6922544b3ee022e2be4f9f400ae401db1a43240d9c272a6dc653b647e6d91b392246b98f9a15547bbf6ddf289844c4910c880b362a383728f91379173b527f8ac5bef772267e5633ec6e758d0c3671903b32c8dad8d21ffec3a0b6f27cc5004fc5a12bcf6bd57aa5c6a982f82c8271e9177ccc2b118957d16c760aa1d60cc7e9408040acbfd186d3c76fcdc7b57b180d6e6b8ea311495d7611e73c4548bd3cccc7d12f0ca69b6240f578a635187713aaa9b561e0dff791c437f95a61818ebf55cdc35a44b0860aac41c3abfedccb4eb8970152fa324e10afdbdebc80dbfbb09e4800cbe51c32daf942e3f54f3d3bee352c3c31290a76f07f4b1a079f5f38109b0d0520a253c9feb0b2e0982730ab2b5f3afd41a9f22d7f4380f4ea6f795540daaae771fe6a9119a13ba3f07ca861ed78698447d070dcfc6cb4843c348e33eaf5e576e1ce9412fd72b69673a3c30b9cc528b041b5489c48c265d7f2251a204e8dc40991b3c8f5620e7a207df2eee41f3c42520b21bba2208e5eff594928271250861982334a139738c030e70606c3da9e26c4ec3c286dd678f3b8ffc4dcd4b0bf5ac595764887de862d5c241368d9f2481eb81a529b0cf4d8d75140b440e26e5af4effa7ad05b1b41a2bf223e902e70af44277ebd1690b5d6da0a3dad18485d967dbb77a3d319fd6e4693e6ee9e99ed8d66f2fb0f355560d87fa8d4ed6e1aac8db481be2091b922df75eb738f64246ad5497ef67c0193d7d8cd0a22694ff63b7bdd915217d93bec2c26077f4de4c3a111b19d4455fb4f77ff72c755e89a71f58155fa47b7f8cd775daa881077b908c89be5e7064c588dc6f520f60d4d6816507c156e553775c843a13fafb9db03de8422c36426f3856124f22236dc8151539ae18a927a4a6fa7e4aed21898184c4b9383b0dbaf9b2938bec0a64a6a8cd606eeb72076655ded0d0f71f95f075c03fed936e688c2d202c7c7e93586d8b49eac1dd9871b5fbc2ab854aa4e86119bf317902cb362e03e0c5bb7b79f80071652a64dc1f5172575edcb0e3fac774d853083b6faca3860b661163f4fe4f8595238c76987d088eaedf8c2fafc14a2995b0cfa951c5df92c55e3784215b0722d08bc9a43bb32c6531393465714b190ae78dd1b18b0f3e0c0432c034a11273d36", ByteVector32(hex"931651e9b75081bf2acdf3b9b5f1dc9871b47ff39841b5b17e0a2ea2c9bf4fdc")), TlvStream()),
    )
    for ((bin, ref) <- testCases) {
      val decoded = updateAddHtlcCodec.decode(bin.bits).require
      assert(decoded.value == ref)
      assert(decoded.remainder.isEmpty)
      assert(updateAddHtlcCodec.encode(decoded.value).require.bytes == bin)
    }
  }
}
