package fr.acinq.eclair.wire

import fr.acinq.bitcoin.BinaryData
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.util.Random

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class FailureMessageLightningMessageCodecsSpec extends FunSuite {

  def randomBytes(size: Int): BinaryData = {
    val bin = new Array[Byte](size)
    Random.nextBytes(bin)
    bin
  }

  test("encode/decode all channel messages") {

    val invalidRealm = InvalidRealm
    val temporaryNodeFailure = TemporaryNodeFailure
    val permanentNodeFailure = PermanentNodeFailure


    val msgs: List[FailureMessage] =
      invalidRealm :: temporaryNodeFailure :: permanentNodeFailure :: Nil

    msgs.foreach {
      case msg => {
        val encoded = FailureMessageCodecs.failureMessageCodec.encode(msg).require
        val decoded = FailureMessageCodecs.failureMessageCodec.decode(encoded).require
        assert(msg === decoded.value)
      }
    }
  }

}
