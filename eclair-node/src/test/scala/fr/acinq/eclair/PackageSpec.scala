package fr.acinq.eclair

import fr.acinq.bitcoin.BinaryData
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 27/01/2017.
  */
@RunWith(classOf[JUnitRunner])
class PackageSpec extends FunSuite {

  test("compute long channel id") {
    val data = (BinaryData("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"), 0, BinaryData("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")) ::
      (BinaryData("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF"), 1, BinaryData("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFE")) ::
      (BinaryData("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF0000"), 2, BinaryData("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF0002")) ::
      (BinaryData("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00F0"), 0x0F00, BinaryData("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF0FF0")) :: Nil

    data.foreach(x => assert(toLongId(x._1, x._2) === x._3))
  }

  test("calculate simple route") {
    val blockHeight = 42000
    val txIndex = 27
    val outputIndex = 3
    assert(fromShortId(toShortId(blockHeight, txIndex, outputIndex)) === (blockHeight, txIndex, outputIndex))
  }

}
