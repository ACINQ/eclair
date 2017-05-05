package fr.acinq.eclair

import fr.acinq.eclair.Features._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 27/01/2017.
  */
@RunWith(classOf[JUnitRunner])
class FeaturesSpec extends FunSuite {

  test("'channel_public' feature") {
    assert(isSet("", CHANNELS_PUBLIC_BIT) == false)
    assert(isSet("00", CHANNELS_PUBLIC_BIT) == false)
    assert(isSet("01", CHANNELS_PUBLIC_BIT) == true)
    assert(isSet("a602", CHANNELS_PUBLIC_BIT) == false)
  }

  test("'initial_routing_sync' feature") {
    assert(isSet("", INITIAL_ROUTING_SYNC_BIT) == false)
    assert(isSet("00", INITIAL_ROUTING_SYNC_BIT) == false)
    assert(isSet("04", INITIAL_ROUTING_SYNC_BIT) == true)
    assert(isSet("05", INITIAL_ROUTING_SYNC_BIT) == true)
  }

  test("features compatibility") {
    for (i <- 0 until 16) assert(areSupported(Array[Byte](i.toByte)) == true)
    assert(areSupported("14") == false)
    assert(areSupported("0141") == false)
    assert(areSupported("02af") == true)
  }

}
