package fr.acinq.eclair.gui.utils

import java.text.DecimalFormat

import fr.acinq.bitcoin.{MilliSatoshi, Satoshi}
import grizzled.slf4j.Logging
import fr.acinq.bitcoin._

object CoinUtils extends Logging {
  /**
    * Always 5 decimals
    */
  val MILLI_BTC_PATTERN = "###,##0.00000"

  /**
    * Localized formatter for milli-bitcoin amounts. Uses `MILLI_BTC_PATTERN`.
    */
  val MILLI_BTC_FORMAT: DecimalFormat = new DecimalFormat(MILLI_BTC_PATTERN)

  val MILLI_SATOSHI_LABEL = "milliSatoshi"
  val SATOSHI_LABEL = "satoshi"
  val MILLI_BTC_LABEL = "milliBTC"

  /**
    * Converts a string amount denominated in a bitcoin unit to a Millisatoshi amount. The amount might be truncated if
    * it has too many decimals because MilliSatoshi only accepts Long amount.
    *
    * @param amount numeric String, can be decimal.
    * @param unit   bitcoin unit, can be milliSatoshi, Satoshi or milliBTC.
    * @return amount as a MilliSatoshi object.
    * @throws NumberFormatException    if the amount parameter is not numeric.
    * @throws IllegalArgumentException if the unit is not equals to milliSatoshi, Satoshi or milliBTC.
    */
  def convertStringAmountToMsat(amount: String, unit: String): MilliSatoshi = {
    val amountDecimal = BigDecimal(amount)
    logger.debug(s"amount=$amountDecimal with unit=$unit")
    unit match {
      case MILLI_SATOSHI_LABEL => MilliSatoshi(amountDecimal.longValue())
      case SATOSHI_LABEL => MilliSatoshi((amountDecimal * 1000).longValue())
      case MILLI_BTC_LABEL => MilliSatoshi((amountDecimal * 1000 * 100000).longValue())
      case _ => throw new IllegalArgumentException("unknown unit")
    }
  }

  def convertStringAmountToSat(amount: String, unit: String): Satoshi =
    millisatoshi2satoshi(CoinUtils.convertStringAmountToMsat(amount, unit))
}
