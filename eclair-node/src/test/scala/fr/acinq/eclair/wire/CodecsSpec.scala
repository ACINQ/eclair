package fr.acinq.eclair.wire

import java.net.InetAddress

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.wire.Codecs.{ipv6, lightningMessageCodec, rgb, string21}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import scodec.Attempt
import scodec.bits.{BitVector, HexStringSyntax}

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class CodecsSpec extends FunSuite {

  def bin(size: Int, fill: Byte): BinaryData = Array.fill[Byte](size)(fill)

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

  test("encode/decode with string21 codec") {
    {
      val alias = "IRATEMONK"
      val bin = string21.encode(alias).toOption.get
      assert(bin === hex"49524154454d4f4e4b000000000000000000000000".toBitVector)
      val alias2 = string21.decode(bin).toOption.get.value
      assert(alias === alias2)
    }
    {
      val alias = "this-alias-is-far-too-long"
      assert(string21.encode(alias).isFailure)
    }
  }

  test("encode/decode all channel messages") {

    val open = OpenChannel(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, bin(33, 1), bin(33, 2), bin(33, 3), bin(33, 3), bin(33, 4))
    val accept = AcceptChannel(2, 3, 4, 5, 6, 7, 8, 9, bin(33, 1), bin(33, 2), bin(33, 3), bin(33, 4), bin(33, 5))
    val funding_created = FundingCreated(2, bin(32, 0), 3, bin(64, 1))
    val funding_signed = FundingSigned(2, bin(64, 1))
    val funding_locked = FundingLocked(1, 2, bin(64, 0), bin(64, 1), bin(33, 2))
    val update_fee = UpdateFee(1, 2)
    val shutdown = Shutdown(1, bin(47, 0))
    val closing_signed = ClosingSigned(1, 2, bin(64, 0))
    val add_htlc = UpdateAddHtlc(1, 2, 3, 4, bin(32, 0), bin(1254, 0))
    val update_fulfill_htlc = UpdateFulfillHtlc(1, 2, bin(32, 0))
    val update_fail_htlc = UpdateFailHtlc(1, 2, bin(154, 0))
    val commit_sig = CommitSig(1, bin(64, 0), bin(64, 1) :: bin(64, 2) :: bin(64, 3) :: Nil)
    val revoke_and_ack = RevokeAndAck(1, bin(32, 0), bin(33, 1), bin(64, 1) :: bin(64, 2) :: bin(64, 3) :: bin(64, 4) :: bin(64, 5) :: Nil)

    val msgs: List[LightningMessage] =
      open :: accept :: funding_created :: funding_signed :: funding_locked :: update_fee :: shutdown :: closing_signed ::
        add_htlc :: update_fulfill_htlc :: update_fail_htlc :: commit_sig :: revoke_and_ack :: Nil

    msgs.foreach {
      case msg => assert(msg === lightningMessageCodec.encode(msg).flatMap(lightningMessageCodec.decode(_)).toOption.get.value)
    }
  }

}
