package fr.acinq.eclair

import java.math.BigInteger

import fr.acinq.bitcoin.BinaryData

case class UInt64(private val underlying: BigInt) extends Ordered[UInt64] {

  require(underlying >= 0, s"uint64 must be positive (actual=$underlying)")
  require(underlying <= UInt64.MaxValueBigInt, s"uint64 must be < 2^64 -1 (actual=$underlying)")

  override def compare(o: UInt64): Int = underlying.compare(o.underlying)

  def toByteArray: Array[Byte] = underlying.toByteArray.takeRight(8)

  override def toString: String = underlying.toString
}


object UInt64 {

  private val MaxValueBigInt = BigInt(new BigInteger("ffffffffffffffff", 16))

  val MaxValue = UInt64(MaxValueBigInt)

  def apply(bin: BinaryData) = new UInt64(new BigInteger(1, bin))

  def apply(value: Long) = new UInt64(BigInt(value))

  object Conversions {

    implicit def intToUint64(l: Int) = UInt64(l)

    implicit def longToUint64(l: Long) = UInt64(l)
  }

}
