/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair

import java.text.{DecimalFormat, NumberFormat}

import fr.acinq.bitcoin.{Btc, BtcAmount, MilliBtc, Satoshi}
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

private case class GUIMSat(amount_msat: Long) extends BtcAmountGUILossless {
  override def unit: CoinUnit = MSatUnit
}
private case class GUISat(amount_msat: Long) extends BtcAmountGUILossless {
  override def unit: CoinUnit = SatUnit
}
private case class GUIBits(amount_msat: Long) extends BtcAmountGUILossless {
  override def unit: CoinUnit = BitUnit
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

case object BitUnit extends CoinUnit {
  override def code: String = "bits"
  override def shortLabel: String = "bits"
  override def label: String = "Bits"
  override def factorToMsat: Long = 100 * 1000L // 1 bit = 100 sat = 100 000 msat
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

  // msat pattern, no decimals allowed
  val MILLI_SAT_PATTERN = "#,###,###,###,###,###,##0"

  // sat pattern decimals are optional
  val SAT_PATTERN = "#,###,###,###,###,##0.###"

  // bits pattern always shows 2 decimals (msat optional)
  val BITS_PATTERN = "##,###,###,###,##0.00###"

  // milli btc pattern always shows 5 decimals (msat optional)
  val MILLI_BTC_PATTERN = "##,###,###,##0.00000###"

  // btc pattern always shows 8 decimals (msat optional). This is the default pattern.
  val BTC_PATTERN = "##,###,##0.00000000###"

  var COIN_FORMAT: NumberFormat = new DecimalFormat(BTC_PATTERN)

  def setCoinPattern(pattern: String): Unit = {
    COIN_FORMAT = new DecimalFormat(pattern)
  }

  def getPatternFromUnit(unit: CoinUnit): String = {
    unit match {
      case MSatUnit => MILLI_SAT_PATTERN
      case SatUnit => SAT_PATTERN
      case BitUnit => BITS_PATTERN
      case MBtcUnit => MILLI_BTC_PATTERN
      case BtcUnit => BTC_PATTERN
      case _ => throw new IllegalArgumentException("unhandled unit")
    }
  }

  /**
   * Converts a string amount denominated in a bitcoin unit to a Millisatoshi amount. The amount might be truncated if
   * it has too many decimals because MilliSatoshi only accepts Long amount.
   *
   * @param amount numeric String, can be decimal.
   * @param unit   bitcoin unit, can be milliSatoshi, Satoshi, Bits, milliBTC, BTC.
   * @return amount as a MilliSatoshi object.
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
    // note: we can't use the fr.acinq.bitcoin._ conversion methods because they truncate the sub-satoshi part
    getUnitFromString(unit) match {
      case MSatUnit => MilliSatoshi((amountDecimal * MSatUnit.factorToMsat).longValue())
      case SatUnit => MilliSatoshi((amountDecimal * SatUnit.factorToMsat).longValue())
      case BitUnit => MilliSatoshi((amountDecimal * BitUnit.factorToMsat).longValue())
      case MBtcUnit => MilliSatoshi((amountDecimal * MBtcUnit.factorToMsat).longValue())
      case BtcUnit => MilliSatoshi((amountDecimal * BtcUnit.factorToMsat).longValue())
      case _ => throw new IllegalArgumentException("unhandled unit")
    }
  }

  def convertStringAmountToSat(amount: String, unit: String): Satoshi =
    CoinUtils.convertStringAmountToMsat(amount, unit).truncateToSatoshi

  /**
   * Only BtcUnit, MBtcUnit, BitUnit, SatUnit and MSatUnit codes or label are supported.
   */
  def getUnitFromString(unit: String): CoinUnit = unit.toLowerCase() match {
    case u if u == MSatUnit.code || u == MSatUnit.label.toLowerCase() => MSatUnit
    case u if u == SatUnit.code || u == SatUnit.label.toLowerCase() => SatUnit
    case u if u == BitUnit.code || u == BitUnit.label.toLowerCase() => BitUnit
    case u if u == MBtcUnit.code || u == MBtcUnit.label.toLowerCase() => MBtcUnit
    case u if u == BtcUnit.code || u == BtcUnit.label.toLowerCase() => BtcUnit
    case u => throw new IllegalArgumentException(s"unhandled unit=$u")
  }

  /**
   * Converts BtcAmount to a GUI Unit (wrapper containing amount as a millisatoshi long)
   *
   * @param amount BtcAmount
   * @param unit   unit to convert to
   * @return a GUICoinAmount
   */
  private def convertAmountToGUIUnit(amount: BtcAmount, unit: CoinUnit): BtcAmountGUILossless = (amount, unit) match {
    // amount is msat, so no conversion required
    case (a: MilliSatoshi, MSatUnit) => GUIMSat(a.toLong * MSatUnit.factorToMsat)
    case (a: MilliSatoshi, SatUnit) => GUISat(a.toLong * MSatUnit.factorToMsat)
    case (a: MilliSatoshi, BitUnit) => GUIBits(a.toLong * MSatUnit.factorToMsat)
    case (a: MilliSatoshi, MBtcUnit) => GUIMBtc(a.toLong * MSatUnit.factorToMsat)
    case (a: MilliSatoshi, BtcUnit) => GUIBtc(a.toLong * MSatUnit.factorToMsat)

    // amount is satoshi, convert sat -> msat
    case (a: Satoshi, MSatUnit) => GUIMSat(a.toLong * SatUnit.factorToMsat)
    case (a: Satoshi, SatUnit) => GUISat(a.toLong * SatUnit.factorToMsat)
    case (a: Satoshi, BitUnit) => GUIBits(a.toLong * SatUnit.factorToMsat)
    case (a: Satoshi, MBtcUnit) => GUIMBtc(a.toLong * SatUnit.factorToMsat)
    case (a: Satoshi, BtcUnit) => GUIBtc(a.toLong * SatUnit.factorToMsat)

    // amount is mbtc
    case (a: MilliBtc, MSatUnit) => GUIMSat((a.toBigDecimal * MBtcUnit.factorToMsat).toLong)
    case (a: MilliBtc, SatUnit) => GUISat((a.toBigDecimal * MBtcUnit.factorToMsat).toLong)
    case (a: MilliBtc, BitUnit) => GUIBits((a.toBigDecimal * MBtcUnit.factorToMsat).toLong)
    case (a: MilliBtc, MBtcUnit) => GUIMBtc((a.toBigDecimal * MBtcUnit.factorToMsat).toLong)
    case (a: MilliBtc, BtcUnit) => GUIBtc((a.toBigDecimal * MBtcUnit.factorToMsat).toLong)

    // amount is mbtc
    case (a: Btc, MSatUnit) => GUIMSat((a.toBigDecimal * BtcUnit.factorToMsat).toLong)
    case (a: Btc, SatUnit) => GUISat((a.toBigDecimal * BtcUnit.factorToMsat).toLong)
    case (a: Btc, BitUnit) => GUIBits((a.toBigDecimal * BtcUnit.factorToMsat).toLong)
    case (a: Btc, MBtcUnit) => GUIMBtc((a.toBigDecimal * BtcUnit.factorToMsat).toLong)
    case (a: Btc, BtcUnit) => GUIBtc((a.toBigDecimal * BtcUnit.factorToMsat).toLong)

    case (_, _) =>
      throw new IllegalArgumentException(s"unhandled conversion from $amount to $unit")
  }

  /**
   * Converts the amount to the user preferred unit and returns a localized formatted String.
   * This method is useful for read only displays.
   *
   * @param amount   BtcAmount
   * @param withUnit if true, append the user unit shortLabel (mBTC, BTC, mSat...)
   * @return formatted amount
   */
  def formatAmountInUnit(amount: BtcAmount, unit: CoinUnit, withUnit: Boolean = false): String = {
    val formatted = COIN_FORMAT.format(rawAmountInUnit(amount, unit))
    if (withUnit) s"$formatted ${unit.shortLabel}" else formatted
  }

  def formatAmountInUnit(amount: MilliSatoshi, unit: CoinUnit, withUnit: Boolean): String = {
    val formatted = COIN_FORMAT.format(rawAmountInUnit(amount, unit))
    if (withUnit) s"$formatted ${unit.shortLabel}" else formatted
  }

  /**
   * Converts the amount to the user preferred unit and returns the BigDecimal value.
   * This method is useful to feed numeric text input without formatting.
   *
   * Returns -1 if the given amount can not be converted.
   *
   * @param amount BtcAmount
   * @return BigDecimal value of the BtcAmount
   */
  def rawAmountInUnit(amount: BtcAmount, unit: CoinUnit): BigDecimal = Try(convertAmountToGUIUnit(amount, unit) match {
    case a: BtcAmountGUILossless => BigDecimal(a.amount_msat) / a.unit.factorToMsat
    case a => throw new IllegalArgumentException(s"unhandled unit $a")
  }) match {
    case Success(b) => b
    case Failure(t) =>
      logger.error("can not convert amount to user unit", t)
      -1
  }

  def rawAmountInUnit(msat: MilliSatoshi, unit: CoinUnit): BigDecimal = BigDecimal(msat.toLong) / unit.factorToMsat
}
