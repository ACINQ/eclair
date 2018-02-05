package fr.acinq.eclair.gui.utils

import java.text.DecimalFormat
import javafx.collections.FXCollections

import fr.acinq.bitcoin.{Btc, BtcAmount, MilliBtc, MilliSatoshi, Satoshi}
import fr.acinq.eclair.gui.FxApp
import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

/**
  * Internal UI utility class, useful for lossless conversion between BtcAmount.
  * The issue being that Satoshi contains a Long amount and it can not be converted to MilliSatoshi without losing the decimal part.
  */
private sealed trait BtcAmountGUILossless {
  def amount_msat: Long
  def unit: CoinUnit
  def toMilliSatoshi: MilliSatoshi = MilliSatoshi(amount_msat)
}

private case class MSatWrapper(amount_msat: Long) extends BtcAmountGUILossless {
  override def unit: CoinUnit = MSatUnit
}
private case class GUISat(amount_msat: Long) extends BtcAmountGUILossless {
  override def unit: CoinUnit = SatUnit
}
private case class GUIMBtc(amount_msat: Long) extends BtcAmountGUILossless {
  override def unit: CoinUnit = MBtcUnit
}
private case class GUIBtc(amount_msat: Long) extends BtcAmountGUILossless {
  override def unit: CoinUnit = BtcUnit
}

sealed trait CoinUnit {
  def code: String
  def shortLabel: String
  def label: String
  def factorToMsat: Long
}

case object MSatUnit extends CoinUnit {
  override def code: String = "msat"
  override def shortLabel: String = "mSat"
  override def label: String = "MilliSatoshi"
  override def factorToMsat: Long = 1L
}

case object SatUnit extends CoinUnit {
  override def code: String = "sat"
  override def shortLabel: String = "sat"
  override def label: String = "Satoshi"
  override def factorToMsat: Long = 1000L // 1 sat = 1 000 msat
}

case object MBtcUnit extends CoinUnit {
  override def code: String = "mbtc"
  override def shortLabel: String = "mBTC"
  override def label: String = "MilliBitcoin"
  override def factorToMsat: Long = 1000 * 100000L // 1 mbtc = 1 00000 000 msat
}

case object BtcUnit extends CoinUnit {
  override def code: String = "btc"
  override def shortLabel: String = "BTC"
  override def label: String = "Bitcoin"
  override def factorToMsat: Long = 1000 * 100000 * 1000L // 1 btc = 1 000 00000 000 msat
}

object CoinUtils extends Logging {

  val COIN_PATTERN = "###,###,###,##0.###########"
  val COIN_FORMAT = new DecimalFormat(COIN_PATTERN)

  val FX_UNITS_ARRAY_NO_MSAT = FXCollections.observableArrayList(SatUnit.label, MBtcUnit.label, BtcUnit.label)
  val FX_UNITS_ARRAY = FXCollections.observableArrayList(MSatUnit.label, SatUnit.label, MBtcUnit.label, BtcUnit.label)

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
      case MSatUnit => MilliSatoshi((amountDecimal * MSatUnit.factorToMsat).longValue())
      case SatUnit => MilliSatoshi((amountDecimal * SatUnit.factorToMsat).longValue())
      case MBtcUnit => MilliSatoshi((amountDecimal * MBtcUnit.factorToMsat).longValue())
      case BtcUnit => MilliSatoshi((amountDecimal * BtcUnit.factorToMsat).longValue())
      case _ => throw new IllegalArgumentException("unhandled unit")
    }
  }

  def convertStringAmountToSat(amount: String, unit: String): Satoshi =
    fr.acinq.bitcoin.millisatoshi2satoshi(CoinUtils.convertStringAmountToMsat(amount, unit))

  /**
    * Only BtcUnit, MBtcUnit and SatUnit are supported.
    * @param unit
    * @return
    */
  def getUnitFromString(unit: String): CoinUnit = unit.toLowerCase() match {
    case u if u == MSatUnit.code || u == MSatUnit.label.toLowerCase() => MSatUnit
    case u if u == SatUnit.code || u == SatUnit.label.toLowerCase() => SatUnit
    case u if u == MBtcUnit.code || u == MBtcUnit.label.toLowerCase() => MBtcUnit
    case u if u == BtcUnit.code || u == BtcUnit.label.toLowerCase() => BtcUnit
    case u => throw new IllegalArgumentException(s"unhandled unit=$u")
  }

  /**
    * Converts BtcAmount to a GUI Unit (wrapper containing amount as a millisatoshi long)
    *
    * @param amount BtcAmount
    * @param unit unit to convert to
    * @return a GUICoinAmount
    */
  private def convertAmountToGUIUnit(amount: BtcAmount, unit: CoinUnit): BtcAmountGUILossless = (amount, unit) match {
    // amount is msat, so no conversion required
    case (a: MilliSatoshi, MSatUnit) => MSatWrapper(a.amount * MSatUnit.factorToMsat)
    case (a: MilliSatoshi, SatUnit) => GUISat(a.amount * MSatUnit.factorToMsat)
    case (a: MilliSatoshi, MBtcUnit) => GUIMBtc(a.amount * MSatUnit.factorToMsat)
    case (a: MilliSatoshi, BtcUnit) => GUIBtc(a.amount * MSatUnit.factorToMsat)

    // amount is satoshi, convert sat -> msat
    case (a: Satoshi, MSatUnit) => MSatWrapper(a.amount * SatUnit.factorToMsat)
    case (a: Satoshi, SatUnit) => GUISat(a.amount * SatUnit.factorToMsat)
    case (a: Satoshi, MBtcUnit) => GUIMBtc(a.amount * SatUnit.factorToMsat)
    case (a: Satoshi, BtcUnit) => GUIBtc(a.amount * SatUnit.factorToMsat)

    // amount is mbtc
    case (a: MilliBtc, MSatUnit) => MSatWrapper((a.amount * MBtcUnit.factorToMsat).toLong)
    case (a: MilliBtc, SatUnit) => GUISat((a.amount * MBtcUnit.factorToMsat).toLong)
    case (a: MilliBtc, MBtcUnit) => GUIMBtc((a.amount * MBtcUnit.factorToMsat).toLong)
    case (a: MilliBtc, BtcUnit) => GUIBtc((a.amount * MBtcUnit.factorToMsat).toLong)

    // amount is mbtc
    case (a: Btc, MSatUnit) => MSatWrapper((a.amount * BtcUnit.factorToMsat).toLong)
    case (a: Btc, SatUnit) => GUISat((a.amount * BtcUnit.factorToMsat).toLong)
    case (a: Btc, MBtcUnit) => GUIMBtc((a.amount * BtcUnit.factorToMsat).toLong)
    case (a: Btc, BtcUnit) => GUIBtc((a.amount * BtcUnit.factorToMsat).toLong)

    case (a, _) =>
      throw new IllegalArgumentException(s"unhandled conversion from $amount to $unit")
  }

  /**
    * Converts the amount to the user preferred unit and returns a localized formatted String.
    * This method is useful for read only displays.
    *
    * @param amount BtcAmount
    * @param withUnit if true, append the user unit shortLabel (mBTC, BTC, mSat...)
    * @return formatted amount
    */
  def formatAmountInUnit(amount: BtcAmount, unit: CoinUnit, withUnit: Boolean = false): String = {
    val formatted = COIN_FORMAT.format(rawAmountInUnit(amount, unit))
    if (withUnit) s"$formatted ${FxApp.getUnit.shortLabel}" else formatted
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
  def rawAmountInUnit(amount: BtcAmount, unit: CoinUnit): BigDecimal = Try(convertAmountToGUIUnit(amount, unit) match {
    case a: BtcAmountGUILossless => BigDecimal(a.amount_msat) / a.unit.factorToMsat
    case a => throw new IllegalArgumentException(s"unhandled unit $a")
  }) match {
    case Success(b) => b
    case Failure(t) => logger.error("can not convert amount to user unit", t)
      -1
  }
}
