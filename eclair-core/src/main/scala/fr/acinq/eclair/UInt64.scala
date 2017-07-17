package fr.acinq.eclair

import java.math.BigInteger

import fr.acinq.bitcoin.BinaryData

case class UInt64(value: BigInteger) extends Ordered[UInt64] {
  override def compare(o: UInt64): Int = value.compareTo(o.value)

  def toBin: BinaryData = {
    val bin = value.toByteArray.dropWhile(_ == 0.toByte)
    bin.size match {
      case 8 => bin
      case n if n > 8 => throw new IllegalArgumentException("value does not fit on 8 bytes")
      case n => Array.fill(8 - n)(0.toByte) ++ bin
    }
  }
}


object UInt64 {
  def apply(bin: BinaryData) = {
    require(bin.length <= 8)
    new UInt64(new BigInteger(1, bin))
  }

  def apply(value: Long) = {
    require(value >= 0)
    new UInt64(BigInteger.valueOf(value))
  }
}
