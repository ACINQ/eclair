package fr.acinq.bitcoin

import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.KotlinUtils._

sealed trait BtcAmount {
  def toSatoshi: Satoshi
}

case class PimpSatoshi(private val underlying: Satoshi) extends BtcAmount with Ordered[PimpSatoshi] {
  // @formatter:off
  def +(other: PimpSatoshi) = PimpSatoshi(underlying plus other.underlying)
  def -(other: PimpSatoshi) = PimpSatoshi(underlying minus other.underlying)
  def unary_- = PimpSatoshi(underlying.unaryMinus())
  def *(m: Long) = PimpSatoshi(underlying times m)
  def *(m: Double) = PimpSatoshi(underlying times m)
  def /(d: Long) = PimpSatoshi(underlying div d)
  def compare(other: PimpSatoshi): Int = underlying.compare(other.underlying)
  def max(other: BtcAmount): PimpSatoshi = other match {
    case other: PimpSatoshi => if (underlying > other.underlying) this else other
    case other: MilliBtc => if (underlying > other.toSatoshi) this else other.toSatoshi
    case other: Btc => if (underlying > other.toSatoshi) this else other.toSatoshi
  }
  def min(other: BtcAmount): PimpSatoshi = other match {
    case other: PimpSatoshi => if (underlying < other.underlying) this else other
    case other: MilliBtc => if (underlying < other.toSatoshi) this else other.toSatoshi
    case other: Btc => if (underlying < other.toSatoshi) this else other.toSatoshi
  }
  def toBtc: Btc = Btc(BigDecimal(underlying) / BtcAmount.Coin)
  def toMilliBtc: MilliBtc = toBtc.toMilliBtc
  def toLong = underlying
  override def toSatoshi: Satoshi = underlying
  def toMilliSatoshi = MilliSatoshi(underlying.toLong * 1000)
  // @formatter:on
}

object PimpSatoshi {
  def apply(input: Satoshi) = new PimpSatoshi(input)
}

case class MilliBtc(private val underlying: BigDecimal) extends BtcAmount with Ordered[MilliBtc] {
  // @formatter:off
  def +(other: MilliBtc) = MilliBtc(underlying + other.underlying)
  def -(other: MilliBtc) = MilliBtc(underlying - other.underlying)
  def unary_ = MilliBtc(-underlying)
  def *(m: Long) = MilliBtc(underlying * m)
  def *(m: Double) = MilliBtc(underlying * m)
  def /(d: Long) = MilliBtc(underlying / d)
  def compare(other: MilliBtc): Int = underlying.compare(other.underlying)
  def max(other: BtcAmount): MilliBtc = other match {
    case other: PimpSatoshi => if (underlying > other.toMilliBtc.underlying) this else other.toMilliBtc
    case other: MilliBtc => if (underlying > other.underlying) this else other
    case other: Btc => if (underlying > other.toMilliBtc.underlying) this else other.toMilliBtc
  }
  def min(other: BtcAmount): MilliBtc = other match {
    case other: PimpSatoshi => if (underlying < other.toMilliBtc.underlying) this else other.toMilliBtc
    case other: MilliBtc => if (underlying < other.underlying) this else other
    case other: Btc => if (underlying < other.toMilliBtc.underlying) this else other.toMilliBtc
  }
  def toBtc: Btc = Btc(underlying / 1000)
  def toSatoshi: Satoshi = toBtc.toSatoshi
  def toBigDecimal = underlying
  def toDouble: Double = underlying.toDouble
  def toLong: Long = underlying.toLong
  // @formatter:on
}

case class Btc(private val underlying: BigDecimal) extends BtcAmount with Ordered[Btc] {
  require(underlying.abs <= 21e6, "amount must not be greater than 21 millions")

  // @formatter:off
  def +(other: Btc) = Btc(underlying + other.underlying)
  def -(other: Btc) = Btc(underlying - other.underlying)
  def unary_- = Btc(-underlying)
  def *(m: Long) = Btc(underlying * m)
  def *(m: Double) = Btc(underlying * m)
  def /(d: Long) = Btc(underlying / d)
  def compare(other: Btc): Int = underlying.compare(other.underlying)
  def max(other: BtcAmount): Btc = other match {
    case other: PimpSatoshi => if (underlying > other.toBtc.underlying) this else other.toBtc
    case other: MilliBtc => if (underlying > other.toBtc.underlying) this else other.toBtc
    case other: Btc => if (underlying > other.underlying) this else other
  }
  def min(other: BtcAmount): Btc = other match {
    case other: PimpSatoshi => if (underlying < other.toBtc.underlying) this else other.toBtc
    case other: MilliBtc => if (underlying < other.toBtc.underlying) this else other.toBtc
    case other: Btc => if (underlying < other.underlying) this else other
  }
  def toMilliBtc: MilliBtc = MilliBtc(underlying * 1000)
  def toSatoshi: Satoshi = new Satoshi((underlying * BtcAmount.Coin).toLong)
  def toBigDecimal = underlying
  def toDouble: Double = underlying.toDouble
  def toLong: Long = underlying.toLong
  // @formatter:on
}

object Btc {
  def apply(input: Satoshi): Btc = new Btc(BigDecimal(input.toLong) / BtcAmount.Coin)
}

object BtcAmount {
  val Coin = 100000000L
  val Cent = 1000000L
  val MaxMoney = 21e6 * Coin
}
