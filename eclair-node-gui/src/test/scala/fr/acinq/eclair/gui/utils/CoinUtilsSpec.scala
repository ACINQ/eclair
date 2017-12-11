package fr.acinq.eclair.gui.utils

import fr.acinq.bitcoin.MilliSatoshi
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CoinUtilsSpec  extends FunSuite {

  test("Convert string amount to the correct BtcAmount") {
    val am_btc: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1", CoinUtils.BTC_LABEL)
    assert(am_btc == MilliSatoshi(100000000000L))
    val am_mbtc: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1", CoinUtils.MILLI_BTC_LABEL)
    assert(am_mbtc == MilliSatoshi(100000000L))
    val am_sat: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1", CoinUtils.SATOSHI_LABEL)
    assert(am_sat == MilliSatoshi(1000))
    val am_msat: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1", CoinUtils.MILLI_SATOSHI_LABEL)
    assert(am_msat == MilliSatoshi(1))
    val am_zero: MilliSatoshi = CoinUtils.convertStringAmountToMsat("0", CoinUtils.MILLI_BTC_LABEL)
    assert(am_zero == MilliSatoshi(0))
  }

  test("Convert decimal string amount to the correct BtcAmount") {
    val am_btc_dec: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1.23456789876", CoinUtils.BTC_LABEL)
    assert(am_btc_dec == MilliSatoshi(123456789876L))
    val am_mbtc_dec: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1.23456789", CoinUtils.MILLI_BTC_LABEL)
    assert(am_mbtc_dec == MilliSatoshi(123456789L))
    val am_sat_dec: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1.23456789", CoinUtils.SATOSHI_LABEL)
    assert(am_sat_dec == MilliSatoshi(1234))
    val am_msat_dec: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1.234", CoinUtils.MILLI_SATOSHI_LABEL)
    assert(am_msat_dec == MilliSatoshi(1))
  }

  test("Convert string amount with unknown unit") {
    intercept[IllegalArgumentException](CoinUtils.convertStringAmountToMsat("1.23456789876", "foo"))
  }

  test("Convert string amount with a non numerical amount") {
    intercept[NumberFormatException](CoinUtils.convertStringAmountToMsat("1.abcd", CoinUtils.MILLI_BTC_LABEL))
  }

  test("Convert string amount with an empty amount") {
    intercept[NumberFormatException](CoinUtils.convertStringAmountToMsat("", CoinUtils.MILLI_BTC_LABEL))
  }

  test("Convert string amount with a invalid numerical amount") {
    intercept[NumberFormatException](CoinUtils.convertStringAmountToMsat("1.23.45", CoinUtils.MILLI_BTC_LABEL))
  }

  test("Convert string amount with a negative numerical amount") {
    intercept[IllegalArgumentException](CoinUtils.convertStringAmountToMsat("-1", CoinUtils.MILLI_BTC_LABEL))
  }
}



