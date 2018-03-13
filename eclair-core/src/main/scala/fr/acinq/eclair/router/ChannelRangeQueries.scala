package fr.acinq.eclair.router

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.ByteOrder
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

import fr.acinq.bitcoin.{BinaryData, Protocol}

import scala.annotation.tailrec

object ChannelRangeQueries {

  val UNCOMPRESSED_FORMAT = 0.toByte
  val GZIP_FORMAT = 1.toByte

  def encodeShortChannelIds(format: Byte, shortChannelIds: Iterable[Long]): BinaryData = {
    val bos = new ByteArrayOutputStream()
    bos.write(format)
    format match {
      case UNCOMPRESSED_FORMAT =>
        shortChannelIds.map(id => Protocol.writeUInt64(id, bos, ByteOrder.BIG_ENDIAN))
      case GZIP_FORMAT =>
        val output = new GZIPOutputStream(bos)
        shortChannelIds.map(id => Protocol.writeUInt64(id, output, ByteOrder.BIG_ENDIAN))
        output.finish()
    }
    bos.toByteArray
  }

  def decodeShortChannelIds(data: BinaryData): (Byte, Vector[Long]) = {
    val format = data.head
    val bis = new ByteArrayInputStream(data.tail.toArray)
    val input = format match {
      case UNCOMPRESSED_FORMAT => bis
      case GZIP_FORMAT => new GZIPInputStream(bis)
    }
    val buffer = new Array[Byte](8)

    // read 8 bytes from input
    // zipped input stream often returns less bytes than what you want to read
    @tailrec
    def read8(offset: Int = 0): Int = input.read(buffer, offset, 8 - offset) match {
      case len if len <= 0 => len
      case 8 => 8
      case len if offset + len == 8 => 8
      case len => read8(offset + len)
    }

    // read until there's nothing left
    @tailrec
    def loop(acc: Vector[Long] = Vector()): Vector[Long] = {
      if (read8() <= 0) acc else loop(acc :+ Protocol.uint64(buffer, ByteOrder.BIG_ENDIAN))
    }

    try {
      (format, loop(Vector.empty[Long]))
    }
    finally {
      input.close()
    }
  }

//  /**
//    * Compressed a sequence of *sorted* short channel id.
//    *
//    * @param shortChannelIds must be sorted beforehand
//    * @return
//    */
//  def zip(shortChannelIds: Iterable[Long]): BinaryData = {
//    val bos = new ByteArrayOutputStream()
//    val output = new GZIPOutputStream(bos)
//    shortChannelIds.map(id => Protocol.writeUInt64(id, output, ByteOrder.BIG_ENDIAN))
//    output.finish()
//    bos.toByteArray
//  }
//
//  /**
//    * Uncompresses a zipped sequence of sorted short channel ids.
//    *
//    * Does *not* preserve the order
//    *
//    * @param input
//    * @return
//    */
//  private def unzip(input: GZIPInputStream): Set[Long] = {
//    val buffer = new Array[Byte](8)
//
//    // read 8 bytes from input
//    // zipped input stream often returns less bytes than what you want to read
//    @tailrec
//    def read8(offset: Int = 0): Int = input.read(buffer, offset, 8 - offset) match {
//      case len if len <= 0 => len
//      case 8 => 8
//      case len if offset + len == 8 => 8
//      case len => read8(offset + len)
//    }
//
//    // read until there's nothing left
//    @tailrec
//    def loop(acc: Set[Long] = Set()): Set[Long] = {
//      if (read8() <= 0) acc else loop(acc + Protocol.uint64(buffer, ByteOrder.BIG_ENDIAN))
//    }
//
//    loop()
//  }
//
//  def unzip(input: BinaryData): Set[Long] = {
//    val stream = new GZIPInputStream(new ByteArrayInputStream(input))
//    try {
//      unzip(stream)
//    }
//    finally {
//      stream.close()
//    }
//  }
}
