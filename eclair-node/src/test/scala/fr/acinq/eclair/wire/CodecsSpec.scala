package fr.acinq.eclair.wire

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.wire.Codecs.lightningMessageCodec
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class CodecsSpec extends FunSuite {

  def bin(size: Int, fill: Byte): BinaryData = Array.fill[Byte](size)(fill)

  test("encode/decode all channel messages") {

    val open = OpenChannel(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, bin(33, 1), bin(33, 2), bin(33, 3), bin(33, 3), bin(33, 4))
    val accept = AcceptChannel(2, 3, 4, 5, 6, 7, 8, 9, bin(33, 1), bin(33, 2), bin(33, 3), bin(33, 4), bin(33, 5))
    val funding_created = FundingCreated(2, bin(32, 0), 3, bin(64, 1))
    val funding_signed = FundingSigned(2, bin(64, 1))
    val funding_locked = FundingLocked(1, 2, bin(33, 1))
    val update_fee = UpdateFee(1, 2)
    val shutdown = Shutdown(1, bin(47, 0))
    val closing_signed = ClosingSigned(1, 2, bin(64, 0))
    val add_htlc = UpdateAddHtlc(1, 2, 3, 4, bin(32, 0), bin(1254, 0))
    val update_fulfill_htlc = UpdateFulfillHtlc(1, 2, bin(32, 0))
    val update_fail_htlc = UpdateFailHtlc(1, 2, bin(154, 0))
    val commit_sig = CommitSig(1, bin(64, 0), bin(64, 1) :: bin(64, 2) :: bin(64, 3) :: Nil)
    val revoke_and_ack = RevokeAndAck(1, bin(32, 0), bin(33, 1), bin(3, 2), bin(64, 1) :: bin(64, 2) :: bin(64, 3) :: bin(64, 4) :: bin(64, 5) :: Nil)

    val msgs: List[LightningMessage] =
      open :: accept :: funding_created :: funding_signed :: funding_locked :: update_fee :: shutdown :: closing_signed ::
        add_htlc :: update_fulfill_htlc :: update_fail_htlc :: commit_sig :: revoke_and_ack :: Nil

    msgs.foreach {
      case msg => assert(msg === lightningMessageCodec.encode(msg).flatMap(lightningMessageCodec.decode(_)).toOption.get.value)
    }
  }

}
