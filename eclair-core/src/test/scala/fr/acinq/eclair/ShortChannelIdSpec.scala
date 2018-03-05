package fr.acinq.eclair

import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class ShortChannelIdSpec extends FunSuite {

  test("handle values from 0 to 0xffffffffffff") {

    val expected = Map(
      TxCoordinates(0, 0, 0) -> ShortChannelId(0),
      TxCoordinates(42000, 27, 3) -> ShortChannelId(0x0000a41000001b0003L),
      TxCoordinates(1258612, 63, 0) -> ShortChannelId(0x13347400003f0000L),
      TxCoordinates(0xffffff, 0x000000, 0xffff) -> ShortChannelId(0xffffff000000ffffL),
      TxCoordinates(0x000000, 0xffffff, 0xffff) -> ShortChannelId(0x000000ffffffffffL),
      TxCoordinates(0xffffff, 0xffffff, 0x0000) -> ShortChannelId(0xffffffffffff0000L),
      TxCoordinates(0xffffff, 0xffffff, 0xffff) -> ShortChannelId(0xffffffffffffffffL)
    )
    for ((coord, shortChannelId) <- expected) {
      assert(shortChannelId == ShortChannelId(coord.blockHeight, coord.txIndex, coord.outputIndex))
      assert(coord == ShortChannelId.coordinates(shortChannelId))
    }
  }

}