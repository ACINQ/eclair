package fr.acinq.eclair

import java.nio.ByteOrder

import fr.acinq.bitcoin.{BinaryData, Protocol}
import fr.acinq.eclair.Features._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 27/01/2017.
  */
@RunWith(classOf[JUnitRunner])
class FeaturesSpec extends FunSuite {

  test("'initial_routing_sync' feature") {
    assert(initialRoutingSync("08"))
  }

  test("features compatibility") {
    assert(!areSupported(Protocol.writeUInt64(1L << INITIAL_ROUTING_SYNC_BIT_MANDATORY, ByteOrder.BIG_ENDIAN)))
    assert(areSupported(Protocol.writeUInt64(1l << INITIAL_ROUTING_SYNC_BIT_OPTIONAL, ByteOrder.BIG_ENDIAN)))
    assert(areSupported("14") == false)
    assert(areSupported("0141") == false)
  }

}
