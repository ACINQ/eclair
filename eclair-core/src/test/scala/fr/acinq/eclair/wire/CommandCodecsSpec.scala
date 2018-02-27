package fr.acinq.eclair.wire

import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FAIL_MALFORMED_HTLC, CMD_FULFILL_HTLC, Command}
import fr.acinq.eclair.randomBytes
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class CommandCodecsSpec extends FunSuite {

  test("encode/decode all channel messages") {
    val msgs: List[Command] =
      CMD_FULFILL_HTLC(1573L, randomBytes(32)) ::
    CMD_FAIL_HTLC(42456L, Left(randomBytes(145))) ::
    CMD_FAIL_HTLC(253, Right(TemporaryNodeFailure)) ::
    CMD_FAIL_MALFORMED_HTLC(7984, randomBytes(32), FailureMessageCodecs.BADONION) :: Nil

    msgs.foreach {
      case msg => {
        val encoded = CommandCodecs.cmdCodec.encode(msg).require
        val decoded = CommandCodecs.cmdCodec.decode(encoded).require
        assert(msg === decoded.value)
      }
    }
  }
}
