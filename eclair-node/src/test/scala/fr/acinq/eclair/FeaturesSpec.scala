package fr.acinq.eclair

import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import Features._

/**
  * Created by PM on 27/01/2017.
  */
@RunWith(classOf[JUnitRunner])
class FeaturesSpec extends FunSuite {

  test("'channel_public' feature") {
    assert(isChannelPublic("") === false)
    assert(isChannelPublic("00") === false)
    assert(isChannelPublic("01") === true)
  }

  test("'initial_routing_sync' feature") {
    assert(requiresInitialRoutingSync("") === false)
    assert(requiresInitialRoutingSync("01") === false)
    assert(requiresInitialRoutingSync("0000") === false)
    assert(requiresInitialRoutingSync("0001") === true)
  }

}
