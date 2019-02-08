/*
 * Copyright 2018 ACINQ SAS
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

import java.net.{Inet4Address, Inet6Address, InetAddress}

import com.google.common.net.InetAddresses
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, Block, Crypto}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.eclair.{ShortChannelId, UInt64, randomBytes, randomKey}
import org.scalatest.FunSuite
import scodec.bits.{BitVector, ByteVector, HexStringSyntax}

/**
  * Created by PM on 31/05/2016.
  */

class LightningMessageCodecsSpec extends FunSuite {

  import LightningMessageCodecsSpec._

  def bin(size: Int, fill: Byte): BinaryData = Array.fill[Byte](size)(fill)

  def scalar(fill: Byte) = Scalar(bin(32, fill))

  def point(fill: Byte) = Scalar(bin(32, fill)).toPoint

  def publicKey(fill: Byte) = PrivateKey(bin(32, fill), compressed = true).publicKey

  test("encode/decode with uint64 codec") {
    val expected = Map(
      UInt64(0) -> hex"00 00 00 00 00 00 00 00",
      UInt64(42) -> hex"00 00 00 00 00 00 00 2a",
      UInt64("0xffffffffffffffff") -> hex"ff ff ff ff ff ff ff ff"
    ).mapValues(_.toBitVector)
    for ((uint, ref) <- expected) {
      val encoded = uint64ex.encode(uint).require
      assert(ref === encoded)
      val decoded = uint64ex.decode(encoded).require.value
      assert(uint === decoded)
    }
  }

  test("encode/decode with rgb codec") {
    val color = Color(47.toByte, 255.toByte, 142.toByte)
    val bin = rgb.encode(color).require
    assert(bin === hex"2f ff 8e".toBitVector)
    val color2 = rgb.decode(bin).require.value
    assert(color === color2)
  }

  test("encode/decode all kind of IPv6 addresses with ipv6address codec") {
    {
      // IPv4 mapped
      val bin = hex"00000000000000000000ffffae8a0b08".toBitVector
      val ipv6 = Inet6Address.getByAddress(null, bin.toByteArray, null)
      val bin2 = ipv6address.encode(ipv6).require
      assert(bin === bin2)
    }

    {
      // regular IPv6 address
      val ipv6 = InetAddresses.forString("1080:0:0:0:8:800:200C:417A").asInstanceOf[Inet6Address]
      val bin = ipv6address.encode(ipv6).require
      val ipv62 = ipv6address.decode(bin).require.value
      assert(ipv6 === ipv62)
    }
  }

  test("encode/decode with nodeaddress codec") {
    {
      val ipv4addr = InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)).asInstanceOf[Inet4Address]
      val nodeaddr = IPv4(ipv4addr, 4231)
      val bin = nodeaddress.encode(nodeaddr).require
      assert(bin === hex"01 C0 A8 01 2A 10 87".toBitVector)
      val nodeaddr2 = nodeaddress.decode(bin).require.value
      assert(nodeaddr === nodeaddr2)
    }
    {
      val ipv6addr = InetAddress.getByAddress(hex"2001 0db8 0000 85a3 0000 0000 ac1f 8001".toArray).asInstanceOf[Inet6Address]
      val nodeaddr = IPv6(ipv6addr, 4231)
      val bin = nodeaddress.encode(nodeaddr).require
      assert(bin === hex"02 2001 0db8 0000 85a3 0000 0000 ac1f 8001 1087".toBitVector)
      val nodeaddr2 = nodeaddress.decode(bin).require.value
      assert(nodeaddr === nodeaddr2)
    }
    {
      val nodeaddr = Tor2("z4zif3fy7fe7bpg3", 4231)
      val bin = nodeaddress.encode(nodeaddr).require
      assert(bin === hex"03 cf3282ecb8f949f0bcdb 1087".toBitVector)
      val nodeaddr2 = nodeaddress.decode(bin).require.value
      assert(nodeaddr === nodeaddr2)
    }
    {
      val nodeaddr = Tor3("mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd", 4231)
      val bin = nodeaddress.encode(nodeaddr).require
      assert(bin === hex"04 6457a1ed0b38a73d56dc866accec93ca6af68bc316568874478dc9399cc1a0b3431b03 1087".toBitVector)
      val nodeaddr2 = nodeaddress.decode(bin).require.value
      assert(nodeaddr === nodeaddr2)
    }
  }

  test("encode/decode with signature codec") {
    val sig = randomSignature
    val wire = LightningMessageCodecs.signature.encode(sig).require
    val sig1 = LightningMessageCodecs.signature.decode(wire).require.value
    assert(sig1 == sig)
  }

  test("encode/decode with optional signature codec") {
    {
      val sig = randomSignature
      val wire = LightningMessageCodecs.optionalSignature.encode(Some(sig)).require
      val Some(sig1) = LightningMessageCodecs.optionalSignature.decode(wire).require.value
      assert(sig1 == sig)
    }
    {
      val wire = LightningMessageCodecs.optionalSignature.encode(None).require
      assert(LightningMessageCodecs.optionalSignature.decode(wire).require.value == None)
    }
  }

  test("encode/decode with scalar codec") {
    val value = Scalar(randomBytes(32))
    val wire = LightningMessageCodecs.scalar.encode(value).require
    assert(wire.length == 256)
    val value1 = LightningMessageCodecs.scalar.decode(wire).require.value
    assert(value1 == value)
  }

  test("encode/decode with point codec") {
    val value = Scalar(randomBytes(32)).toPoint
    val wire = LightningMessageCodecs.point.encode(value).require
    assert(wire.length == 33 * 8)
    val value1 = LightningMessageCodecs.point.decode(wire).require.value
    assert(value1 == value)
  }

  test("encode/decode with public key codec") {
    val value = PrivateKey(randomBytes(32), true).publicKey
    val wire = LightningMessageCodecs.publicKey.encode(value).require
    assert(wire.length == 33 * 8)
    val value1 = LightningMessageCodecs.publicKey.decode(wire).require.value
    assert(value1 == value)
  }

  test("encode/decode with zeropaddedstring codec") {
    val c = zeropaddedstring(32)

    {
      val alias = "IRATEMONK"
      val bin = c.encode(alias).require
      assert(bin === BitVector(alias.getBytes("UTF-8") ++ Array.fill[Byte](32 - alias.size)(0)))
      val alias2 = c.decode(bin).require.value
      assert(alias === alias2)
    }

    {
      val alias = "this-alias-is-exactly-32-B-long."
      val bin = c.encode(alias).require
      assert(bin === BitVector(alias.getBytes("UTF-8") ++ Array.fill[Byte](32 - alias.size)(0)))
      val alias2 = c.decode(bin).require.value
      assert(alias === alias2)
    }

    {
      val alias = "this-alias-is-far-too-long-because-we-are-limited-to-32-bytes"
      assert(c.encode(alias).isFailure)
    }
  }

  test("encode/decode UInt64") {
    val codec = uint64ex
    Seq(
      UInt64("0xffffffffffffffff"),
      UInt64("0xfffffffffffffffe"),
      UInt64("0xefffffffffffffff"),
      UInt64("0xeffffffffffffffe")
    ).map(value => {
      assert(codec.decode(codec.encode(value).require).require.value === value)
    })
  }

  test("encode/decode live node_announcements") {
    val anns = List(
      BinaryData("a58338c9660d135fd7d087eb62afd24a33562c54507a9334e79f0dc4f17d407e6d7c61f0e2f3d0d38599502f61704cf1ae93608df027014ade7ff592f27ce26900005acdf50702d2eabbbacc7c25bbd73b39e65d28237705f7bde76f557e94fb41cb18a9ec00841122116c6e302e646563656e7465722e776f726c64000000000000000000000000000000130200000000000000000000ffffae8a0b082607")
      //BinaryData("d5bfb0be26412eed9bbab186772bd3885610e289ed305e729869a5bcbd97ea431863b6fa884b021162ed5e66264c4087630e4403669bab29f3c533c4089e508c00005ab521eb030e9226f19cd3ba8a58fb280d00f5f94f3c10f1b4618a5f9bffd43534c966ebd4030e9256495247494e41574f4c465f3200000000000000000000000000000000000000000f03cec0cb03c68094bbb48792002608")
      //BinaryData("9746cd4d25a5cf2b04f3d986a073973b0318282e32e2758939b6650cd13cf65e4225ceaa98b02f070614e907661278a1479542afb12b9867511e0d31d995209800005ab646a302dc523b9db431de52d7adb79cf81dd3d780002f4ce952706053edc9da30d9b9f702dc5256495247494e41574f4c460000000000000000000000000000000000000000000016031bb5481aa82769f4446e1002260701584473f82607"),
      //BinaryData("a483677744b63d892a85fb7460fd6cb0504f802600956eb18cfaad05fbbe775328e4a7060476d2c0f3b7a6d505bb4de9377a55b27d1477baf14c367287c3de7900005abb440002dc523b9db431de52d7adb79cf81dd3d780002f4ce952706053edc9da30d9b9f702dc5256495247494e41574f4c460000000000000000000000000000000000000000000016031bb5481aa82769f4446e1002260701584473f82607"),
      //BinaryData("3ecfd85bcb3bafb5bad14ab7f6323a2df33e161c37c2897e576762fa90ffe46078d231ebbf7dce3eff4b440d091a10ea9d092e698a321bb9c6b30869e2782c9900005abbebe202dc523b9db431de52d7adb79cf81dd3d780002f4ce952706053edc9da30d9b9f702dc5256495247494e41574f4c460000000000000000000000000000000000000000000016031bb5481aa82769f4446e1002260701584473f82607"),
      //BinaryData("ad40baf5c7151777cc8896bc70ad2d0fd2afff47f4befb3883a78911b781a829441382d82625b77a47b9c2c71d201aab7187a6dc80e7d2d036dcb1186bac273c00005abffc330341f5ff2992997613aff5675d6796232a63ab7f30136219774da8aba431df37c80341f563377a6763723364776d777a7a3261652e6f6e696f6e00000000000000000000000f0317f2614763b32d9ce804fc002607")
    )

    anns.foreach { ann =>
      val bin = ByteVector(ann.data.toArray).toBitVector
      val node = nodeAnnouncementCodec.decode(bin).require.value
      val bin2 = nodeAnnouncementCodec.encode(node).require
      assert(bin === bin2)
    }
  }

  test("encode/decode all channel messages") {

    val open = OpenChannel(randomBytes(32), randomBytes(32), 3, 4, 5, UInt64(6), 7, 8, 9, 10, 11, publicKey(1), point(2), point(3), point(4), point(5), point(6), 0.toByte)
    val accept = AcceptChannel(randomBytes(32), 3, UInt64(4), 5, 6, 7, 8, 9, publicKey(1), point(2), point(3), point(4), point(5), point(6))
    val funding_created = FundingCreated(randomBytes(32), bin(32, 0), 3, randomSignature)
    val funding_signed = FundingSigned(randomBytes(32), randomSignature)
    val funding_locked = FundingLocked(randomBytes(32), point(2))
    val update_fee = UpdateFee(randomBytes(32), 2)
    val shutdown = Shutdown(randomBytes(32), bin(47, 0))
    val closing_signed = ClosingSigned(randomBytes(32), 2, randomSignature)
    val update_add_htlc = UpdateAddHtlc(randomBytes(32), 2, 3, bin(32, 0), 4, bin(Sphinx.PacketLength, 0))
    val update_fulfill_htlc = UpdateFulfillHtlc(randomBytes(32), 2, bin(32, 0))
    val update_fail_htlc = UpdateFailHtlc(randomBytes(32), 2, bin(154, 0))
    val update_fail_malformed_htlc = UpdateFailMalformedHtlc(randomBytes(32), 2, randomBytes(32), 1111)
    val commit_sig = CommitSig(randomBytes(32), randomSignature, randomSignature :: randomSignature :: randomSignature :: Nil)
    val revoke_and_ack = RevokeAndAck(randomBytes(32), scalar(0), point(1))
    val channel_announcement = ChannelAnnouncement(randomSignature, randomSignature, randomSignature, randomSignature, bin(7, 9), Block.RegtestGenesisBlock.hash, ShortChannelId(1), randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)
    val node_announcement = NodeAnnouncement(randomSignature, bin(0, 0), 1, randomKey.publicKey, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", IPv4(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)).asInstanceOf[Inet4Address], 42000) :: Nil)
    val channel_update = ChannelUpdate(randomSignature, Block.RegtestGenesisBlock.hash, ShortChannelId(1), 2, 42, 0, 3, 4, 5, 6, None)
    val announcement_signatures = AnnouncementSignatures(randomBytes(32), ShortChannelId(42), randomSignature, randomSignature)
    val gossip_timestamp_filter = GossipTimestampFilter(Block.RegtestGenesisBlock.blockId, 100000, 1500)
    val query_short_channel_id = QueryShortChannelIds(Block.RegtestGenesisBlock.blockId, randomBytes(7515))
    val query_channel_range = QueryChannelRange(Block.RegtestGenesisBlock.blockId, 100000, 1500)
    val reply_channel_range = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 100000, 1500, 1, randomBytes(3200))
    val ping = Ping(100, BinaryData("01" * 10))
    val pong = Pong(BinaryData("01" * 10))
    val channel_reestablish = ChannelReestablish(randomBytes(32), 242842L, 42L)

    val msgs: List[LightningMessage] =
      open :: accept :: funding_created :: funding_signed :: funding_locked :: update_fee :: shutdown :: closing_signed ::
        update_add_htlc :: update_fulfill_htlc :: update_fail_htlc :: update_fail_malformed_htlc :: commit_sig :: revoke_and_ack ::
        channel_announcement :: node_announcement :: channel_update :: gossip_timestamp_filter :: query_short_channel_id :: query_channel_range :: reply_channel_range :: announcement_signatures :: ping :: pong :: channel_reestablish :: Nil

    msgs.foreach {
      case msg => {
        val encoded = lightningMessageCodec.encode(msg).require
        val decoded = lightningMessageCodec.decode(encoded).require
        assert(msg === decoded.value)
      }
    }
  }

  test("encode/decode per-hop payload") {
    val payload = PerHopPayload(shortChannelId = ShortChannelId(42), amtToForward = 142000, outgoingCltvValue = 500000)
    val bin = LightningMessageCodecs.perHopPayloadCodec.encode(payload).require
    assert(bin.toByteVector.size === 33)
    val payload1 = LightningMessageCodecs.perHopPayloadCodec.decode(bin).require.value
    assert(payload === payload1)

    // realm (the first byte) should be 0
    val bin1 = bin.toByteVector.update(0, 1)
    intercept[IllegalArgumentException] {
      val payload2 = LightningMessageCodecs.perHopPayloadCodec.decode(bin1.toBitVector).require.value
      assert(payload2 === payload1)
    }
  }

  test("encode/decode using cached codec") {
    val codec = cachedLightningMessageCodec

    val commit_sig = CommitSig(randomBytes(32), randomSignature, randomSignature :: randomSignature :: randomSignature :: Nil)
    val revoke_and_ack = RevokeAndAck(randomBytes(32), scalar(0), point(1))
    val channel_announcement = ChannelAnnouncement(randomSignature, randomSignature, randomSignature, randomSignature, bin(7, 9), Block.RegtestGenesisBlock.hash, ShortChannelId(1), randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)
    val node_announcement = NodeAnnouncement(randomSignature, bin(0, 0), 1, randomKey.publicKey, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", IPv4(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)).asInstanceOf[Inet4Address], 42000) :: Nil)
    val channel_update1 = ChannelUpdate(randomSignature, Block.RegtestGenesisBlock.hash, ShortChannelId(1), 2, 1, 0, 3, 4, 5, 6, Some(50000000L))
    val channel_update2 = ChannelUpdate(randomSignature, Block.RegtestGenesisBlock.hash, ShortChannelId(1), 2, 0, 0, 3, 4, 5, 6, None)
    val announcement_signatures = AnnouncementSignatures(randomBytes(32), ShortChannelId(42), randomSignature, randomSignature)
    val ping = Ping(100, BinaryData("01" * 10))
    val pong = Pong(BinaryData("01" * 10))

    val cached = channel_announcement :: node_announcement :: channel_update1 :: channel_update2 :: Nil
    val nonCached = commit_sig :: revoke_and_ack :: announcement_signatures :: ping :: pong :: Nil
    val msgs: List[LightningMessage] = cached ::: nonCached

    msgs.foreach {
      case msg => {
        val encoded = codec.encode(msg).require
        val decoded = codec.decode(encoded).require
        assert(msg === decoded.value)
      }
    }

    import scala.language.reflectiveCalls
    val cachedKeys = codec.cache.asMap().keySet()
    assert(cached.forall(msg => cachedKeys.contains(msg)))
    assert(nonCached.forall(msg => !cachedKeys.contains(msg)))

  }

  test("decode channel_update with htlc_maximum_msat") {
    // this was generated by c-lightning
    val bin = BinaryData("010258fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf1792306226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f0005a100000200005bc75919010100060000000000000001000000010000000a000000003a699d00")
    val update = LightningMessageCodecs.lightningMessageCodec.decode(BitVector(bin.toArray)).require.value.asInstanceOf[ChannelUpdate]
    assert(update === ChannelUpdate("3044022058fff7d0e987e2cdd560e3bb5a046b4efe7b26c969c2f51da1dceec7bcb8ae1b0220634790503d5290c1a6c51d681cf8f4211d27ed33a257dcc1102862571bf1792301", "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f", ShortChannelId(0x5a10000020000L), 1539791129, 1, 1, 6, 1, 1, 10, Some(980000000L)))
    val nodeId = PublicKey("03370c9bac836e557eb4f017fe8f9cc047f44db39c1c4e410ff0f7be142b817ae4")
    assert(Announcements.checkSig(update, nodeId))
    val bin2 = BinaryData(LightningMessageCodecs.lightningMessageCodec.encode(update).require.toByteArray)
    assert(bin === bin2)
  }

}

object LightningMessageCodecsSpec {
  def randomSignature: BinaryData = {
    val priv = randomBytes(32)
    val data = randomBytes(32)
    val (r, s) = Crypto.sign(data, PrivateKey(priv, true))
    Crypto.encodeSignature(r, s) :+ fr.acinq.bitcoin.SIGHASH_ALL.toByte
  }
}