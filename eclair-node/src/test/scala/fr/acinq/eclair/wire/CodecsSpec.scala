package fr.acinq.eclair.wire

import fr.acinq.eclair.wire.Codecs.lightningMessageCodec
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class CodecsSpec extends FunSuite {

  test("encode/decode all messages") {

    val open = OpenChannel(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, Array.fill[Byte](33)(1), Array.fill[Byte](33)(2), Array.fill[Byte](33)(3))
    val accept = AcceptChannel(2, 3, 4, 5, 6, 7, 8, Array.fill[Byte](32)(0), 9, Array.fill[Byte](33)(1), Array.fill[Byte](33)(2), Array.fill[Byte](33)(3))
    val funding_created = FundingCreated(2, Array.fill[Byte](32)(0), 3, Array.fill[Byte](64)(1))
    val funding_signed = FundingSigned(2, Array.fill[Byte](64)(1))
    val funding_locked = FundingLocked(1, 2, Array.fill[Byte](32)(1), Array.fill[Byte](33)(2))

    val msgs: List[LightningMessage] = open :: accept :: funding_created :: funding_signed :: funding_locked :: Nil

    msgs.foreach {
      case msg => assert(msg === lightningMessageCodec.encode(msg).flatMap(lightningMessageCodec.decode(_)).toOption.get.value)
    }
  }

}
