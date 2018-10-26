package fr.acinq.eclair.router

import java.io.InputStream

import scala.annotation.tailrec

object ChannelRangeQueries {

  val UNCOMPRESSED_FORMAT = 0.toByte
  val ZLIB_FORMAT = 1.toByte
  val INCLUDE_CHANNEL_UPDATE_1 = 1.toByte
  val INCLUDE_CHANNEL_UPDATE_2 = 2.toByte
  val INCLUDE_ANNOUNCEMENT = 4.toByte

  def includeAnnoucement(flag: Byte) = (flag & INCLUDE_ANNOUNCEMENT) != 0

  def includeUpdate1(flag: Byte) = (flag & INCLUDE_CHANNEL_UPDATE_1) != 0

  def includeUpdate2(flag: Byte) = (flag & INCLUDE_CHANNEL_UPDATE_2) != 0

  // read bytes from an input stream
  // zipped input stream often returns less bytes than what you want to read
  @tailrec
  def readBytes(buffer: Array[Byte], input: InputStream, offset: Int = 0): Int = input.read(buffer, offset, buffer.length - offset) match {
    case len if len <= 0 => len
    case len if len == buffer.length => buffer.length
    case len if offset + len == buffer.length => buffer.length
    case len => readBytes(buffer, input, offset + len)
  }

}
