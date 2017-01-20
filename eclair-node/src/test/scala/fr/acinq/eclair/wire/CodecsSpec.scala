package fr.acinq.eclair.wire

import java.net.InetAddress

import fr.acinq.bitcoin.Crypto.{PrivateKey, Scalar}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.wire.Codecs.{ipv6, lightningMessageCodec, rgb, zeropaddedstring}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import scodec.bits.{BitVector, HexStringSyntax}

import scala.util.Random

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class CodecsSpec extends FunSuite {

  def bin(size: Int, fill: Byte): BinaryData = Array.fill[Byte](size)(fill)

  def scalar(fill: Byte) = Scalar(bin(32, fill))

  def point(fill: Byte) = Scalar(bin(32, fill)).toPoint

  def publicKey(fill: Byte) = PrivateKey(bin(32, fill), compressed = true).publicKey

  def randomBytes(size: Int): BinaryData = {
    val bin = new Array[Byte](size)
    Random.nextBytes(bin)
    bin
  }

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

  test("encode/decode with ipv6 codec") {
    {
      val ipv4addr = InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte))
      val bin = ipv6.encode(ipv4addr).toOption.get
      assert(bin === hex"00 00 00 00 00 00 00 00 00 00 FF FF C0 A8 01 2A".toBitVector)
      val ipv4addr2 = ipv6.decode(bin).toOption.get.value
      assert(ipv4addr === ipv4addr2)
    }
    {
      val ipv6addr = InetAddress.getByAddress(hex"2001 0db8 0000 85a3 0000 0000 ac1f 8001".toArray)
      val bin = ipv6.encode(ipv6addr).toOption.get
      assert(bin === hex"2001 0db8 0000 85a3 0000 0000 ac1f 8001".toBitVector)
      val ipv6addr2 = ipv6.decode(bin).toOption.get.value
      assert(ipv6addr === ipv6addr2)
    }
  }

  test("encode/decode with signature codec") {
    val sig = randomSignature
    val wire = Codecs.signature.encode(sig).toOption.get
    val sig1 = Codecs.signature.decode(wire).toOption.get.value
    assert(sig1 == sig)
  }

  test("encode/decode with optional signature codec") {
    {
      val sig = randomSignature
      val wire = Codecs.optionalSignature.encode(Some(sig)).toOption.get
      val Some(sig1) = Codecs.optionalSignature.decode(wire).toOption.get.value
      assert(sig1 == sig)
    }
    {
      val wire = Codecs.optionalSignature.encode(None).toOption.get
      assert(Codecs.optionalSignature.decode(wire).toOption.get.value == None)
    }
  }

  test("encode/decode with scalar codec") {
    val value = Scalar(randomBytes(32))
    val wire = Codecs.scalar.encode(value).toOption.get
    assert(wire.length == 256)
    val value1 = Codecs.scalar.decode(wire).toOption.get.value
    assert(value1 == value)
  }

  test("encode/decode with point codec") {
    val value = Scalar(randomBytes(32)).toPoint
    val wire = Codecs.point.encode(value).toOption.get
    assert(wire.length == 33 * 8)
    val value1 = Codecs.point.decode(wire).toOption.get.value
    assert(value1 == value)
  }

  test("encode/decode with public key codec") {
    val value = PrivateKey(randomBytes(32), true).publicKey
    val wire = Codecs.publicKey.encode(value).toOption.get
    assert(wire.length == 33 * 8)
    val value1 = Codecs.publicKey.decode(wire).toOption.get.value
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

    val open = OpenChannel(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, publicKey(1), point(2), point(3), point(4), point(5))
    val accept = AcceptChannel(2, 3, 4, 5, 6, 7, 8, 9, publicKey(1), point(2), point(3), point(4), point(5))
    val funding_created = FundingCreated(2, bin(32, 0), 3, randomSignature)
    val funding_signed = FundingSigned(2, randomSignature)
    val funding_locked = FundingLocked(1, 2, Some(randomSignature), Some(randomSignature), point(2))
    val update_fee = UpdateFee(1, 2)
    val shutdown = Shutdown(1, bin(47, 0))
    val closing_signed = ClosingSigned(1, 2, randomSignature)
    val update_add_htlc = UpdateAddHtlc(1, 2, 3, 4, bin(32, 0), bin(1254, 0))
    val update_fulfill_htlc = UpdateFulfillHtlc(1, 2, bin(32, 0))
    val update_fail_htlc = UpdateFailHtlc(1, 2, bin(154, 0))
    val commit_sig = CommitSig(1, randomSignature, randomSignature :: randomSignature :: randomSignature :: Nil)
    val revoke_and_ack = RevokeAndAck(1, scalar(0), point(1), randomSignature :: randomSignature :: randomSignature :: randomSignature :: randomSignature :: Nil)
    val channel_announcement = ChannelAnnouncement(randomSignature, randomSignature, 1, randomSignature, randomSignature, bin(33, 5), bin(33, 6), bin(33, 7), bin(33, 8))
    val node_announcement = NodeAnnouncement(randomSignature, 1, InetAddress.getByAddress(Array[Byte](192.toByte, 168.toByte, 1.toByte, 42.toByte)), 2, bin(33, 2), (100.toByte, 200.toByte, 300.toByte), "node-alias")
    val channel_update = ChannelUpdate(randomSignature, 1, 2, bin(2, 2), 3, 4, 5, 6)

    val msgs: List[LightningMessage] =
      open :: accept :: funding_created :: funding_signed :: funding_locked :: update_fee :: shutdown :: closing_signed ::
        update_add_htlc :: update_fulfill_htlc :: update_fail_htlc :: commit_sig :: revoke_and_ack ::
        channel_announcement :: node_announcement :: channel_update :: Nil

    msgs.foreach {
      case msg => {
        val encoded = lightningMessageCodec.encode(msg)
        val decoded = encoded.flatMap(lightningMessageCodec.decode(_))
        assert(msg === decoded.toOption.get.value)
      }
    }
  }
}
