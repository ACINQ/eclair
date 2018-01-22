package fr.acinq.eclair.gui.utils

import java.text.{DecimalFormat, NumberFormat}
import javafx.collections.FXCollections

import fr.acinq.bitcoin.{MilliSatoshi, Satoshi, _}
import fr.acinq.eclair.gui.FxApp
import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

sealed trait CoinUnit {
  def code: String
  def shortLabel: String
  def label: String
}

case object MSatUnit extends CoinUnit {
  override def code: String = "msat"
  override def shortLabel: String = "mSat"
  override def label: String = "MilliSatoshi"
}

case object SatUnit extends CoinUnit {
  override def code: String = "sat"
  override def shortLabel: String = "sat"
  override def label: String = "Satoshi"
}
case object BitsUnit extends CoinUnit {
  override def code: String = "bits"
  override def shortLabel: String = "bits"
  override def label: String = "Bits"
}
case object MBtcUnit extends CoinUnit {
  override def code: String = "mbtc"
  override def shortLabel: String = "mBTC"
  override def label: String = "MilliBitcoin"
}

case object BtcUnit extends CoinUnit {
  override def code: String = "btc"
  override def shortLabel: String = "BTC"
  override def label: String = "Bitcoin"
}

object CoinUtils extends Logging {

  val MILLI_BTC_PATTERN = "###,##0.00######"
  val BTC_PATTERN = "###,##0.000########"

  val SATOSHI_FORMAT = NumberFormat.getInstance()
  val MILLI_BTC_FORMAT: DecimalFormat = new DecimalFormat(MILLI_BTC_PATTERN)
  val BTC_FORMAT: DecimalFormat = new DecimalFormat(BTC_PATTERN)

  val FX_UNITS_ARRAY = FXCollections.observableArrayList(SatUnit.label, MBtcUnit.label, BtcUnit.label)

  /**
    * Converts a string amount denominated in a bitcoin unit to a Millisatoshi amount. The amount might be truncated if
    * it has too many decimals because MilliSatoshi only accepts Long amount.
    *
    * @param amount numeric String, can be decimal.
    * @param unit   bitcoin unit, can be milliSatoshi, Satoshi, milliBTC, BTC.
    * @return       amount as a MilliSatoshi object.
    * @throws NumberFormatException    if the amount parameter is not numeric.
    * @throws IllegalArgumentException if the unit is not equals to milliSatoshi, Satoshi or milliBTC.
    */
  @throws(classOf[NumberFormatException])
  @throws(classOf[IllegalArgumentException])
  def convertStringAmountToMsat(amount: String, unit: String): MilliSatoshi = {
    val amountDecimal = BigDecimal(amount)
    if (amountDecimal < 0) {
      throw new IllegalArgumentException("amount must be equal or greater than 0")
    }
    logger.debug(s"amount=$amountDecimal with unit=$unit")
    // note: we can't use the fr.acinq.bitcoin._ conversion methods because they truncate the sub-satoshi part
    getUnitFromString(unit) match {
      case MSatUnit => MilliSatoshi(amountDecimal.longValue())
      case SatUnit => MilliSatoshi((amountDecimal * 1000).longValue())
      case MBtcUnit => MilliSatoshi((amountDecimal * 1000 * 100000).longValue())
      case BtcUnit => MilliSatoshi((amountDecimal * 1000 * 100000 * 1000).longValue())
      case _ => throw new IllegalArgumentException("unhandled unit")
    }
  }

  def convertStringAmountToSat(amount: String, unit: String): Satoshi =
    millisatoshi2satoshi(CoinUtils.convertStringAmountToMsat(amount, unit))

  /**
    * Only BtcUnit, MBtcUnit and SatUnit are supported.
    * @param unit
    * @return
    */
  def getUnitFromString(unit: String, accept_msat: Boolean = true): CoinUnit = unit.toLowerCase() match {
    case u if accept_msat && (u == MSatUnit.code || u == MSatUnit.label.toLowerCase()) => MSatUnit
    case u if u == SatUnit.code || u == SatUnit.label.toLowerCase() => SatUnit
    case u if u == MBtcUnit.code || u == MBtcUnit.label.toLowerCase() => MBtcUnit
    case u if u == BtcUnit.code.toLowerCase() || u == BtcUnit.label => BtcUnit
    case u => throw new IllegalArgumentException(s"unhandled unit=$u")
  }

  def convertAmountToUserUnit(amount: BtcAmount): BtcAmount = {
    val unit = FxApp.getUnit
    (amount, unit) match {

      // amount is msat
      case (a: MilliSatoshi, MSatUnit) => a
      case (a: MilliSatoshi, SatUnit) => millisatoshi2satoshi(a)
      case (a: MilliSatoshi, MBtcUnit) => millisatoshi2millibtc(a)
      case (a: MilliSatoshi, BtcUnit) => millisatoshi2btc(a)

      // amount is satoshi
      case (a: Satoshi, MSatUnit) => satoshi2millisatoshi(a)
      case (a: Satoshi, SatUnit) => a
      case (a: Satoshi, MBtcUnit) => satoshi2millibtc(a)
      case (a: Satoshi, BtcUnit) => satoshi2btc(a)

      // amount is mbtc
      case (a: MilliBtc, MSatUnit) => millibtc2millisatoshi(a)
      case (a: MilliBtc, SatUnit) => millibtc2satoshi(a)
      case (a: MilliBtc, MBtcUnit) => a
      case (a: MilliBtc, BtcUnit) => millibtc2btc(a)

      // amount is mbtc
      case (a: Btc, MSatUnit) => btc2millisatoshi(a)
      case (a: Btc, SatUnit) => btc2satoshi(a)
      case (a: Btc, MBtcUnit) => btc2millibtc(a)
      case (a: Btc, BtcUnit) => a

      case (a, _) =>
        throw new IllegalArgumentException(s"unhandled conversion from $amount to $unit")
    }
  }

  /**
    * Converts the amount to the user preferred unit and returns a localized formatted String.
    * This method is useful for read only displays.
    *
    * @param amount BtcAmount
    * @param withUnit if true, append the user unit shortLabel (mBTC, BTC, mSat...)
    * @return formatted amount
    */
  def formatAmountInUserUnit(amount: BtcAmount, withUnit: Boolean = false): String = {
    Try(convertAmountToUserUnit(amount) match {
      case a: MilliSatoshi => SATOSHI_FORMAT.format(a.amount)
      case a: Satoshi => SATOSHI_FORMAT.format(a.amount)
      case a: MilliBtc => MILLI_BTC_FORMAT.format(a.amount)
      case a: Btc => BTC_FORMAT.format(a.amount)
      case a => throw new IllegalArgumentException(s"unhandled unit $a")
    }) match {
      case Success(s) => if (withUnit) s"$s ${FxApp.getUnit.shortLabel}" else s
      case Failure(t) => logger.error("can not convert amount to user unit", t)
        amount.toString
    }
  }

  /**
    * Converts the amount to the user preferred unit and returns the Long value.
    * This method is useful to feed numeric text input without formatting.
    *
    * Returns -1 if the given amount can not be converted.
    *
    * @param amount BtcAmount
    * @return Long value of the BtcAmount
    */
  def rawAmountInUserUnit(amount: BtcAmount): BigDecimal = {
    Try(convertAmountToUserUnit(amount) match {
      case a: MilliSatoshi => BigDecimal(a.amount)
      case a: Satoshi => BigDecimal(a.amount)
      case a: MilliBtc => a.amount
      case a: Btc => a.amount
      case a => throw new IllegalArgumentException(s"unhandled unit $a")
    }) match {
      case Success(l) => l
      case Failure(t) => logger.error("can not convert amount to user unit", t)
        -1
    }
  }
}
