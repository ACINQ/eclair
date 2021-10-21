package fr.acinq

package object bitcoin {
  implicit final class SatoshiLong(private val n: Long) extends AnyVal {
    def sat = new Satoshi(n)
  }

  implicit final class MilliBtcDouble(private val n: Double) extends AnyVal {
    def millibtc = MilliBtc(n)
  }

  implicit final class BtcDouble(private val n: Double) extends AnyVal {
    def btc = Btc(n)
  }
}
