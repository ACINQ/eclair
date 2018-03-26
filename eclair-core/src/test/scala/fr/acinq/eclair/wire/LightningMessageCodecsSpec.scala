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

import java.net.{InetAddress, InetSocketAddress}

import fr.acinq.bitcoin.Crypto.{PrivateKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, Block, Crypto}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire.LightningMessageCodecs._
import fr.acinq.eclair.{ShortChannelId, UInt64, randomBytes, randomKey}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import scodec.bits.{BitVector, HexStringSyntax}

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
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

  test("encode/decode with socketaddress codec") {
    {
      val ipv4addr = InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte))
      val isa = new InetSocketAddress(ipv4addr, 4231)
      val bin = socketaddress.encode(isa).require
      assert(bin === hex"01 C0 A8 01 2A 10 87".toBitVector)
      val isa2 = socketaddress.decode(bin).require.value
      assert(isa === isa2)
    }
    {
      val ipv6addr = InetAddress.getByAddress(hex"2001 0db8 0000 85a3 0000 0000 ac1f 8001".toArray)
      val isa = new InetSocketAddress(ipv6addr, 4231)
      val bin = socketaddress.encode(isa).require
      assert(bin === hex"02 2001 0db8 0000 85a3 0000 0000 ac1f 8001 1087".toBitVector)
      val isa2 = socketaddress.decode(bin).require.value
      assert(isa === isa2)
    }
    {
      // decoding ipv4 addressed mapped as ipv6
      val bad = hex"02 0000 0000 0000 0000 0000 ffff A8 01 2A 10 87".toBitVector
      val isa = socketaddress.decode(bad).require.value
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
    val node_announcement = NodeAnnouncement(randomSignature, bin(0, 0), 1, randomKey.publicKey, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", new InetSocketAddress(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)), 42000) :: Nil)
    val channel_update = ChannelUpdate(randomSignature, Block.RegtestGenesisBlock.hash, ShortChannelId(1), 2, bin(2, 2), 3, 4, 5, 6)
    val announcement_signatures = AnnouncementSignatures(randomBytes(32), ShortChannelId(42), randomSignature, randomSignature)
    val ping = Ping(100, BinaryData("01" * 10))
    val pong = Pong(BinaryData("01" * 10))
    val channel_reestablish = ChannelReestablish(randomBytes(32), 242842L, 42L)

    val msgs: List[LightningMessage] =
      open :: accept :: funding_created :: funding_signed :: funding_locked :: update_fee :: shutdown :: closing_signed ::
        update_add_htlc :: update_fulfill_htlc :: update_fail_htlc :: update_fail_malformed_htlc :: commit_sig :: revoke_and_ack ::
        channel_announcement :: node_announcement :: channel_update :: announcement_signatures :: ping :: pong :: channel_reestablish :: Nil

    msgs.foreach {
      case msg => {
        val encoded = lightningMessageCodec.encode(msg).require
        val decoded = lightningMessageCodec.decode(encoded).require
        assert(msg === decoded.value)
      }
    }
  }

  test("encode/decode per-hop payload") {
    val payload = PerHopPayload(channel_id = ShortChannelId(42), amtToForward = 142000, outgoingCltvValue = 500000)
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
}

object LightningMessageCodecsSpec {
  def randomSignature: BinaryData = {
    val priv = randomBytes(32)
    val data = randomBytes(32)
    val (r, s) = Crypto.sign(data, PrivateKey(priv, true))
    Crypto.encodeSignature(r, s) :+ fr.acinq.bitcoin.SIGHASH_ALL.toByte
  }
}