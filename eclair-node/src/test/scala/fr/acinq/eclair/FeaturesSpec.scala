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
    assert(channelPublic("") === Unset)
    assert(channelPublic("00") === Unset)
    assert(channelPublic("01") === Mandatory)
    assert(channelPublic("02") === Optional)
  }

  test("'initial_routing_sync' feature") {
    assert(initialRoutingSync("") === Unset)
    assert(initialRoutingSync("00") === Unset)
    assert(initialRoutingSync("01") === Unset)
    assert(initialRoutingSync("04") === Mandatory)
    assert(initialRoutingSync("05") === Mandatory)
    assert(initialRoutingSync("08") === Optional)
    assert(initialRoutingSync("09") === Optional)
  }

  test("features compatibility") {
    // if one node wants channels to be public the other must at least make it optional
    assert(areFeaturesCompatible("01", "00") == false)
    assert(areFeaturesCompatible("01", "02") == true)
    assert(areFeaturesCompatible("02", "02") == true)
    // eclair supports initial routing sync so we always accept
    assert(areFeaturesCompatible("00", "04") == true)
    // if we request initial routing sync and they don't support it they will close the connection
    assert(areFeaturesCompatible("04", "00") == true)
  }

}
