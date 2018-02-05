package fr.acinq.eclair.crypto

import org.spongycastle.util.encoders.Hex

import scala.annotation.tailrec

/**
  * Bit stream that can be written to and read at both ends (i.e. you can read from the end or the beginning of the stream)
  *
  * @param bytes    bits packed as bytes, the last byte is padded with 0s
  * @param offstart offset at which the first bit is in the first byte
  * @param offend   offset at which the last bit is in the last byte
  */
case class BitStream(bytes: Vector[Byte], offstart: Int, offend: Int) {

  // offstart: 0 1 2 3 4 5 6 7
  // offend: 7 6 5 4 3 2 1 0
  import BitStream._

  def bitCount = 8 * bytes.length - offstart - offend

  def isEmpty = bitCount == 0

  /**
    * append a byte to a bitstream
    *
    * @param input byte to append
    * @return an updated bitstream
    */
  def writeByte(input: Byte): BitStream = offend match {
    case 0 => this.copy(bytes = this.bytes :+ input)
    case shift =>
      val input1 = input & 0xff
      val last = ((bytes.last | (input1 >>> (8 - shift))) & 0xff).toByte
      val next = ((input1 << shift) & 0xff).toByte
      this.copy(bytes = bytes.dropRight(1) ++ Vector(last, next))
  }

  /**
    * append bytes to a bitstream
    *
    * @param input bytes to append
    * @return an updated bitstream
    */
  def writeBytes(input: Seq[Byte]): BitStream = input.foldLeft(this) { case (bs, b) => bs.writeByte(b) }

  /**
    * append a bit to a bitstream
    *
    * @param bit bit to append
    * @return an update bitstream
    */
  def writeBit(bit: Bit): BitStream = offend match {
    case 0 if bit =>
      BitStream(bytes :+ 0x80.toByte, offstart, 7)
    case 0 =>
      BitStream(bytes :+ 0x00.toByte, offstart, 7)
    case n if bit =>
      val last = (bytes.last + (1 << (offend - 1))).toByte
      BitStream(bytes.updated(bytes.length - 1, last), offstart, offend - 1)
    case n =>
      BitStream(bytes, offstart, offend - 1)
  }

  /**
    * append bits to a bitstream
    *
    * @param input bits to append
    * @return an update bitstream
    */
  def writeBits(input: Seq[Bit]): BitStream = input.foldLeft(this) { case (bs, b) => bs.writeBit(b) }

  /**
    * read the last bit from a bitstream
    *
    * @return a (stream, bit) pair where stream is an updated bitstream and bit is the last bit
    */
  def popBit: (BitStream, Bit) = offend match {
    case 7 => BitStream(bytes.dropRight(1), offstart, 0) -> lastBit
    case n =>
      val shift = n + 1
      val last = (bytes.last >>> shift) << shift
      BitStream(bytes.updated(bytes.length - 1, last.toByte), offstart, offend + 1) -> lastBit
  }

  /**
    * read the last byte from a bitstream
    *
    * @return a (stream, byte) pair where stream is an updated bitstream and byte is the last byte
    */
  def popByte: (BitStream, Byte) = offend match {
    case 0 => BitStream(bytes.dropRight(1), offstart, offend) -> bytes.last
    case shift =>
      val a = bytes(bytes.length - 2) & 0xff
      val b = bytes(bytes.length - 1) & 0xff
      val byte = ((a << (8 - shift)) | (b >>> shift)) & 0xff
      val a1 = (a >>> shift) << shift
      BitStream(bytes.dropRight(2) :+ a1.toByte, offstart, offend) -> byte.toByte
  }

  def popBytes(n: Int): (BitStream, Seq[Byte]) = {
    @tailrec
    def loop(stream: BitStream, acc: Seq[Byte]): (BitStream, Seq[Byte]) =
      if (acc.length == n) (stream, acc) else {
        val (stream1, value) = stream.popByte
        loop(stream1, acc :+ value)
      }

    loop(this, Nil)
  }

  /**
    * read the first bit from a bitstream
    *
    * @return
    */
  def readBit: (BitStream, Bit) = offstart match {
    case 7 => BitStream(bytes.tail, 0, offend) -> firstBit
    case _ => BitStream(bytes, offstart + 1, offend) -> firstBit
  }

  def readBits(count: Int): (BitStream, Seq[Bit]) = {
    @tailrec
    def loop(stream: BitStream, acc: Seq[Bit]): (BitStream, Seq[Bit]) = if (acc.length == count) (stream, acc) else {
      val (stream1, bit) = stream.readBit
      loop(stream1, acc :+ bit)
    }

    loop(this, Nil)
  }

  /**
    * read the first byte from a bitstream
    *
    * @return
    */
  def readByte: (BitStream, Byte) = {
    val byte = ((bytes(0) << offstart) | (bytes(1) >>> (7 - offstart))) & 0xff
    BitStream(bytes.tail, offstart, offend) -> byte.toByte
  }

  def isSet(pos: Int): Boolean = {
    val pos1 = pos + offstart
    (bytes(pos1 / 8) & (1 << (7 - (pos1 % 8)))) != 0
  }

  def firstBit = (bytes.head & (1 << (7 - offstart))) != 0

  def lastBit = (bytes.last & (1 << offend)) != 0

  def toBinString: String = "0b" + (for (i <- 0 until bitCount) yield if (isSet(i)) '1' else '0').mkString

  def toHexString: String = "0x" + Hex.toHexString(bytes.toArray).toLowerCase
}

object BitStream {
  type Bit = Boolean
  val Zero = false
  val One = true
  val empty = BitStream(Vector.empty[Byte], 0, 0)
}