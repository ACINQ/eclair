package fr.acinq.eclair.gui.utils

import java.text.DecimalFormat

object CoinFormat {
  /**
    * Always 5 decimals
    */
  val MILLI_BTC_PATTERN = "###,##0.00000"

  /**
    * Localized formatter for milli-bitcoin amounts. Uses `MILLI_BTC_PATTERN`.
    */
  val MILLI_BTC_FORMAT: DecimalFormat = new DecimalFormat(MILLI_BTC_PATTERN)
}
