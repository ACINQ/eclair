package fr.acinq.eclair.gui.utils

import java.text.{DecimalFormat, NumberFormat}
import java.util.Locale

object CoinFormat {
  /**
    * Always 3 decimals, maximum of 8
    */
  val BTC_PATTERN = "###,##0.000#####"

  /**
    * Always 5 decimals
    */
  val MILLI_BTC_PATTERN = "###,##0.00000"

  /**
    * Localized formatter for BTC amounts. Uses `BTC_PATTERN`.
    */
  val BTC_FORMAT: DecimalFormat = NumberFormat.getNumberInstance(Locale.getDefault).asInstanceOf[DecimalFormat]
  BTC_FORMAT.applyPattern(BTC_PATTERN)

  /**
    * Localized formatter for milli-bitcoin amounts. Uses `MILLI_BTC_PATTERN`.
    */
  val MILLI_BTC_FORMAT: DecimalFormat = NumberFormat.getNumberInstance(Locale.getDefault).asInstanceOf[DecimalFormat]
  MILLI_BTC_FORMAT.applyPattern(MILLI_BTC_PATTERN)
}
