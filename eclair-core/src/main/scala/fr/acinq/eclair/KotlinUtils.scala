package fr.acinq.eclair

import fr.acinq.bitcoin._
import scala.jdk.CollectionConverters._

object KotlinUtils {

  // We implement Numeric to take advantage of operations such as sum, sort or min/max on iterables.
  implicit object NumericSatoshi extends Numeric[Satoshi] {
    // @formatter:off
    override def plus(x: Satoshi, y: Satoshi): Satoshi = x plus y
    override def minus(x: Satoshi, y: Satoshi): Satoshi = x minus y
    override def times(x: Satoshi, y: Satoshi): Satoshi = new Satoshi(x.toLong * y.toLong)
    override def negate(x: Satoshi): Satoshi = x.unaryMinus()
    override def fromInt(x: Int): Satoshi = new Satoshi(x)
    override def toInt(x: Satoshi): Int = x.toLong.toInt
    override def toLong(x: Satoshi): Long = x.toLong
    override def toFloat(x: Satoshi): Float = x.toLong.toFloat
    override def toDouble(x: Satoshi): Double = x.toLong.toDouble
    override def compare(x: Satoshi, y: Satoshi): Int = x.compareTo(y)
    override def parseString(str: String): Option[Satoshi] = ???
    // @formatter:on
  }

  // We implement Numeric to take advantage of operations such as sum, sort or min/max on iterables.
  implicit object NumericMilliBtc extends Numeric[MilliBtc] {
    // @formatter:off
    override def plus(x: MilliBtc, y: MilliBtc): MilliBtc = x + y
    override def minus(x: MilliBtc, y: MilliBtc): MilliBtc = x - y
    override def times(x: MilliBtc, y: MilliBtc): MilliBtc = x * y
    override def negate(x: MilliBtc): MilliBtc = -x
    override def fromInt(x: Int): MilliBtc = MilliBtc(x)
    override def toInt(x: MilliBtc): Int = x.toLong.toInt
    override def toLong(x: MilliBtc): Long = x.toLong
    override def toFloat(x: MilliBtc): Float = x.toLong.toFloat
    override def toDouble(x: MilliBtc): Double = x.toLong.toDouble
    override def compare(x: MilliBtc, y: MilliBtc): Int = x.compareTo(y)
    override def parseString(str: String): Option[MilliBtc] = ???
    // @formatter:on
  }

  implicit class OrderedSatoshi(value: Satoshi) extends Ordered[Satoshi] {
    override def compare(that: Satoshi): Int = value.compareTo(that)
  }

  //  implicit class SatoshiToMilliSatoshiConversion(amount: Satoshi) {
  //    // @formatter:off
  //    def toMilliSatoshi: MilliSatoshi = MilliSatoshi.toMilliSatoshi(amount)
  //    def +(other: MilliSatoshi): MilliSatoshi = amount.toMilliSatoshi + other
  //    def -(other: MilliSatoshi): MilliSatoshi = amount.toMilliSatoshi - other
  //    // @formatter:on
  //  }

  implicit class IntToSatoshi(input: Int) {
    def sat: Satoshi = new Satoshi(input)
  }

  implicit class LongToSatoshi(input: Long) {
    def sat: Satoshi = new Satoshi(input)
  }

  implicit def javatx2scala(input: java.util.List[Transaction]): List[Transaction] = input.asScala.toList

  implicit def scalatx2java(input: Seq[Transaction]): java.util.List[Transaction] = input.asJava

  implicit def javain2scala(input: java.util.List[TxIn]): List[TxIn] = input.asScala.toList

  implicit def scalain2java(input: Seq[TxIn]): java.util.List[TxIn] = input.asJava

  implicit def javaout2scala(input: java.util.List[TxOut]): List[TxOut] = input.asScala.toList

  implicit def scalaout2java(input: Seq[TxOut]): java.util.List[TxOut] = input.asJava

  implicit def javascriptelt2scala(input: java.util.List[ScriptElt]): List[ScriptElt] = input.asScala.toList

  implicit def scalascriptelt2java(input: Seq[ScriptElt]): java.util.List[ScriptElt] = input.asJava

  implicit def scalapubkey2java(input: Seq[PublicKey]): java.util.List[PublicKey] = input.asJava

  implicit def scalabytes2java(input: Seq[fr.acinq.bitcoin.ByteVector]): java.util.List[fr.acinq.bitcoin.ByteVector] = input.asJava

  implicit def pair2tuple[A](input: kotlin.Pair[A, java.lang.Boolean]): Tuple2[A, Boolean] = (input.getFirst, if (input.getSecond) true else false)

  implicit def satoshi2long(input: Satoshi): Long = input.toLong

  implicit def btcamount2satoshi(input: BtcAmount): Satoshi = input.toSatoshi

  //implicit def scodecbytevector2bytearray(input: scodec.bits.ByteVector): Array[Byte] = input.toArray

  implicit def scodecbytevector2acinqbytevector(input: scodec.bits.ByteVector): fr.acinq.bitcoin.ByteVector = new ByteVector(input.toArray)

  implicit def acinqbytevector2scodecbytevector(input: fr.acinq.bitcoin.ByteVector): scodec.bits.ByteVector = scodec.bits.ByteVector(input.toByteArray)

  implicit def array2acinqbytevector(input: Array[Byte]): fr.acinq.bitcoin.ByteVector = new ByteVector(input)

  implicit def acinqbytevector2bytearray(input: fr.acinq.bitcoin.ByteVector): Array[Byte] = input.toByteArray

  implicit def array2bytevector32(input: Array[Byte]): ByteVector32 = new ByteVector32(input)

  implicit def satsohi2pimp(input: Satoshi) = PimpSatoshi(input)

  implicit def pimpsatsohi2sat(input: PimpSatoshi) = input.toSatoshi
}
