package fr.acinq.eclair.gui.utils

import fr.acinq.bitcoin.MilliSatoshi
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class CoinUtilsSpec  extends FunSuite {

  test("Convert string amount to the correct BtcAmount") {
    val am_btc: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1", BtcUnit.code)
    assert(am_btc == MilliSatoshi(100000000000L))
    val am_mbtc: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1", MBtcUnit.code)
    assert(am_mbtc == MilliSatoshi(100000000L))
    val am_sat: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1", SatUnit.code)
    assert(am_sat == MilliSatoshi(1000))
    val am_msat: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1", MSatUnit.code)
    assert(am_msat == MilliSatoshi(1))
    val am_zero: MilliSatoshi = CoinUtils.convertStringAmountToMsat("0", MBtcUnit.code)
    assert(am_zero == MilliSatoshi(0))
  }

  test("Convert decimal string amount to the correct BtcAmount") {
    val am_btc_dec: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1.23456789876", BtcUnit.code)
    assert(am_btc_dec == MilliSatoshi(123456789876L))
    val am_mbtc_dec_nozero: MilliSatoshi = CoinUtils.convertStringAmountToMsat(".25", MBtcUnit.code)
    assert(am_mbtc_dec_nozero == MilliSatoshi(25000000L))
    val am_mbtc_dec: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1.23456789", MBtcUnit.code)
    assert(am_mbtc_dec == MilliSatoshi(123456789L))
    val am_sat_dec: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1.23456789", SatUnit.code)
    assert(am_sat_dec == MilliSatoshi(1234))
    val am_msat_dec: MilliSatoshi = CoinUtils.convertStringAmountToMsat("1.234", MSatUnit.code)
    assert(am_msat_dec == MilliSatoshi(1))
  }

  test("Convert string amount with multiple decimal") {
    intercept[IllegalArgumentException](CoinUtils.convertStringAmountToMsat(".12.3456789876", "foo"))
  }

  test("Convert string amount with unknown unit") {
    intercept[IllegalArgumentException](CoinUtils.convertStringAmountToMsat("1.23456789876", "foo"))
  }

  test("Convert string amount with a non numerical amount") {
    intercept[NumberFormatException](CoinUtils.convertStringAmountToMsat("1.abcd", MBtcUnit.code))
  }

  test("Convert string amount with an empty amount") {
    intercept[NumberFormatException](CoinUtils.convertStringAmountToMsat("", MBtcUnit.code))
  }

  test("Convert string amount with a invalid numerical amount") {
    intercept[NumberFormatException](CoinUtils.convertStringAmountToMsat("1.23.45", MBtcUnit.code))
  }

  test("Convert string amount with a negative numerical amount") {
    intercept[IllegalArgumentException](CoinUtils.convertStringAmountToMsat("-1", MBtcUnit.code))
  }

}



