package fr.acinq.eclair

import fr.acinq.bitcoin.{Btc, MilliBtc, MilliSatoshi, Satoshi}
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

  test("Convert any BtcAmount to a raw BigDecimal in a given unit") {
    assert(CoinUtils.rawAmountInUnit(MilliSatoshi(-1234), BtcUnit) == BigDecimal(-0.00000001234))
    assert(CoinUtils.rawAmountInUnit(MilliSatoshi(0), BtcUnit) == BigDecimal(0))
    assert(CoinUtils.rawAmountInUnit(MilliSatoshi(123), BtcUnit) == BigDecimal(0.00000000123))
    assert(CoinUtils.rawAmountInUnit(MilliSatoshi(123), MBtcUnit) == BigDecimal(0.00000123))
    assert(CoinUtils.rawAmountInUnit(MilliSatoshi(123), SatUnit) == BigDecimal(0.123))
    assert(CoinUtils.rawAmountInUnit(MilliSatoshi(123), MSatUnit) == BigDecimal(123))
    assert(CoinUtils.rawAmountInUnit(MilliSatoshi(12345678), BtcUnit) == BigDecimal(0.00012345678))

    assert(CoinUtils.rawAmountInUnit(Satoshi(123), BtcUnit) == BigDecimal(0.00000123))
    assert(CoinUtils.rawAmountInUnit(Satoshi(123), MBtcUnit) == BigDecimal(0.00123))
    assert(CoinUtils.rawAmountInUnit(Satoshi(123), SatUnit) == BigDecimal(123))
    assert(CoinUtils.rawAmountInUnit(Satoshi(123), MSatUnit) == BigDecimal(123000))

    assert(CoinUtils.rawAmountInUnit(MilliBtc(123.456), BtcUnit) == BigDecimal(0.123456))
    assert(CoinUtils.rawAmountInUnit(MilliBtc(123.456), MBtcUnit) == BigDecimal(123.456))
    assert(CoinUtils.rawAmountInUnit(MilliBtc(123.456789), SatUnit) == BigDecimal(12345678.9))
    assert(CoinUtils.rawAmountInUnit(MilliBtc(123.45678987), MSatUnit) == BigDecimal(12345678987L))

    assert(CoinUtils.rawAmountInUnit(Btc(123.456), BtcUnit) == BigDecimal(123.456))
    assert(CoinUtils.rawAmountInUnit(Btc(123.45678987654), MBtcUnit) == BigDecimal(123456.78987654))
    assert(CoinUtils.rawAmountInUnit(Btc(1.22233333444), SatUnit) == BigDecimal(122233333.444))
    assert(CoinUtils.rawAmountInUnit(Btc(0.00011111222), MSatUnit) == BigDecimal(11111222L))
  }

  test("Format any BtcAmount to a String with a given unit") {
    assert(CoinUtils.formatAmountInUnit(MilliSatoshi(123456789), BtcUnit, withUnit = true) == "0.00123456789 BTC"
    || CoinUtils.formatAmountInUnit(MilliSatoshi(123456789), BtcUnit, withUnit = true) == "0,00123456789 BTC")
  }
}



