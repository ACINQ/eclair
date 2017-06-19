package fr.acinq.eclair.wire

import java.net.{InetAddress, InetSocketAddress}

import fr.acinq.bitcoin.Crypto.{PrivateKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.randomBytes
import fr.acinq.eclair.wire.LightningMessageCodecs.{lightningMessageCodec, rgb, socketaddress, zeropaddedstring}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import scodec.bits.{BitVector, HexStringSyntax}

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class LightningMessageCodecsSpec extends FunSuite {

  def bin(size: Int, fill: Byte): BinaryData = Array.fill[Byte](size)(fill)

  def scalar(fill: Byte) = Scalar(bin(32, fill))

  def point(fill: Byte) = Scalar(bin(32, fill)).toPoint

  def publicKey(fill: Byte) = PrivateKey(bin(32, fill), compressed = true).publicKey

  def randomSignature: BinaryData = {
    val priv = randomBytes(32)
    val data = randomBytes(50)
    val (r, s) = Crypto.sign(data, PrivateKey(priv, true))
    Crypto.encodeSignature(r, s) :+ fr.acinq.bitcoin.SIGHASH_ALL.toByte
  }

  test("encode/decode with rgb codec") {
    val color = (47.toByte, 255.toByte, 142.toByte)
    val bin = rgb.encode(color).toOption.get
    assert(bin === hex"2f ff 8e".toBitVector)
    val color2 = rgb.decode(bin).toOption.get.value
    assert(color === color2)
  }

  test("encode/decode with socketaddress codec") {
    {
      val ipv4addr = InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte))
      val isa = new InetSocketAddress(ipv4addr, 4231)
      val bin = socketaddress.encode(isa).toOption.get
      assert(bin === hex"01 C0 A8 01 2A 10 87".toBitVector)
      val isa2 = socketaddress.decode(bin).toOption.get.value
      assert(isa === isa2)
    }
    {
      val ipv6addr = InetAddress.getByAddress(hex"2001 0db8 0000 85a3 0000 0000 ac1f 8001".toArray)
      val isa = new InetSocketAddress(ipv6addr, 4231)
      val bin = socketaddress.encode(isa).toOption.get
      assert(bin === hex"02 2001 0db8 0000 85a3 0000 0000 ac1f 8001 1087".toBitVector)
      val isa2 = socketaddress.decode(bin).toOption.get.value
      assert(isa === isa2)
    }
  }

  test("encode/decode with signature codec") {
    val sig = randomSignature
    val wire = LightningMessageCodecs.signature.encode(sig).toOption.get
    val sig1 = LightningMessageCodecs.signature.decode(wire).toOption.get.value
    assert(sig1 == sig)
  }

  test("encode/decode with optional signature codec") {
    {
      val sig = randomSignature
      val wire = LightningMessageCodecs.optionalSignature.encode(Some(sig)).toOption.get
      val Some(sig1) = LightningMessageCodecs.optionalSignature.decode(wire).toOption.get.value
      assert(sig1 == sig)
    }
    {
      val wire = LightningMessageCodecs.optionalSignature.encode(None).toOption.get
      assert(LightningMessageCodecs.optionalSignature.decode(wire).toOption.get.value == None)
    }
  }

  test("encode/decode with scalar codec") {
    val value = Scalar(randomBytes(32))
    val wire = LightningMessageCodecs.scalar.encode(value).toOption.get
    assert(wire.length == 256)
    val value1 = LightningMessageCodecs.scalar.decode(wire).toOption.get.value
    assert(value1 == value)
  }

  test("encode/decode with point codec") {
    val value = Scalar(randomBytes(32)).toPoint
    val wire = LightningMessageCodecs.point.encode(value).toOption.get
    assert(wire.length == 33 * 8)
    val value1 = LightningMessageCodecs.point.decode(wire).toOption.get.value
    assert(value1 == value)
  }

  test("encode/decode with public key codec") {
    val value = PrivateKey(randomBytes(32), true).publicKey
    val wire = LightningMessageCodecs.publicKey.encode(value).toOption.get
    assert(wire.length == 33 * 8)
    val value1 = LightningMessageCodecs.publicKey.decode(wire).toOption.get.value
    assert(value1 == value)
  }

  test("encode/decode with zeropaddedstring codec") {
    val c = zeropaddedstring(32)

    {
      val alias = "IRATEMONK"
      val bin = c.encode(alias).toOption.get
      assert(bin === BitVector(alias.getBytes("UTF-8") ++ Array.fill[Byte](32 - alias.size)(0)))
      val alias2 = c.decode(bin).toOption.get.value
      assert(alias === alias2)
    }

    {
      val alias = "this-alias-is-exactly-32-B-long."
      val bin = c.encode(alias).toOption.get
      assert(bin === BitVector(alias.getBytes("UTF-8") ++ Array.fill[Byte](32 - alias.size)(0)))
      val alias2 = c.decode(bin).toOption.get.value
      assert(alias === alias2)
    }

    {
      val alias = "this-alias-is-far-too-long-because-we-are-limited-to-32-bytes"
      assert(c.encode(alias).isFailure)
    }
  }

  test("encode/decode all channel messages") {

    val open = OpenChannel(randomBytes(32), randomBytes(32), 3, 4, 5, 6, 7, 8, 9, 10, 11, publicKey(1), point(2), point(3), point(4), point(5))
    val accept = AcceptChannel(randomBytes(32), 3, 4, 5, 6, 7, 8, 9, publicKey(1), point(2), point(3), point(4), point(5))
    val funding_created = FundingCreated(randomBytes(32), bin(32, 0), 3, randomSignature)
    val funding_signed = FundingSigned(randomBytes(32), randomSignature)
    val funding_locked = FundingLocked(randomBytes(32), point(2))
    val update_fee = UpdateFee(randomBytes(32), 2)
    val shutdown = Shutdown(randomBytes(32), bin(47, 0))
    val closing_signed = ClosingSigned(randomBytes(32), 2, randomSignature)
    val update_add_htlc = UpdateAddHtlc(randomBytes(32), 2, 3, 4, bin(32, 0), bin(Sphinx.PacketLength, 0))
    val update_fulfill_htlc = UpdateFulfillHtlc(randomBytes(32), 2, bin(32, 0))
    val update_fail_htlc = UpdateFailHtlc(randomBytes(32), 2, bin(154, 0))
    val update_fail_malformed_htlc = UpdateFailMalformedHtlc(randomBytes(32), 2, randomBytes(32), 1111)
    val commit_sig = CommitSig(randomBytes(32), randomSignature, randomSignature :: randomSignature :: randomSignature :: Nil)
    val revoke_and_ack = RevokeAndAck(randomBytes(32), scalar(0), point(1))
    val channel_announcement = ChannelAnnouncement(randomSignature, randomSignature, randomSignature, randomSignature, 1, bin(33, 5), bin(33, 6), bin(33, 7), bin(33, 8), bin(7, 9))
    val node_announcement = NodeAnnouncement(randomSignature, 1, bin(33, 2), (100.toByte, 200.toByte, 300.toByte), "node-alias", bin(0, 0), new InetSocketAddress(InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)), 42000) :: Nil)
    val channel_update = ChannelUpdate(randomSignature, 1, 2, bin(2, 2), 3, 4, 5, 6)
    val announcement_signatures = AnnouncementSignatures(randomBytes(32), 42, randomSignature, randomSignature)
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
    val payload = PerHopPayload(channel_id = 42, amt_to_forward = 142000, outgoing_cltv_value = 500000)
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
