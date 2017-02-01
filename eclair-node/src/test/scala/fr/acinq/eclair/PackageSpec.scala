package fr.acinq.eclair

import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 27/01/2017.
  */
@RunWith(classOf[JUnitRunner])
class PackageSpec extends FunSuite {

  test("calculate simple route") {
    val blockHeight = 42000
    val txIndex = 27
    val outputIndex = 3
    assert(fromShortId(toShortId(blockHeight, txIndex, outputIndex)) === (blockHeight, txIndex, outputIndex))
  }

}
