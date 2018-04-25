package fr.acinq.eclair.router

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.ByteOrder
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

import fr.acinq.bitcoin.{BinaryData, Protocol}
import fr.acinq.eclair.ShortChannelId

import scala.annotation.tailrec

object ChannelRangeQueries {

  val UNCOMPRESSED_FORMAT = 0.toByte
  val GZIP_FORMAT = 1.toByte

  /**
    * Compressed a sequence of *sorted* short channel id.
    *
    * @param shortChannelIds must be sorted beforehand
    * @return a sequence of encoded short channel ids
    */
  def encodeShortChannelIds(format: Byte, shortChannelIds: Iterable[ShortChannelId]): List[BinaryData] = {
    // LN messages must fit in 65 Kb so we split ids into groups to make sure that the output message will be valid
    val count = format match {
      case UNCOMPRESSED_FORMAT => 7000
      case GZIP_FORMAT => 12000 // TODO: do something less simplistic...
    }
    shortChannelIds.grouped(count).map(encodeShortChannelIdsSingle(format, _)).toList
  }

  def encodeShortChannelIdsSingle(format: Byte, shortChannelIds: Iterable[ShortChannelId]): BinaryData = {
    val bos = new ByteArrayOutputStream()
    bos.write(format)
    format match {
      case UNCOMPRESSED_FORMAT =>
        shortChannelIds.foreach(id => Protocol.writeUInt64(id.toLong, bos, ByteOrder.BIG_ENDIAN))
      case GZIP_FORMAT =>
        val output = new GZIPOutputStream(bos)
        shortChannelIds.foreach(id => Protocol.writeUInt64(id.toLong, output, ByteOrder.BIG_ENDIAN))
        output.finish()
    }
    bos.toByteArray
  }

  /**
    * Decompress a zipped sequence of sorted short channel ids.
    *
    * Does *not* preserve the order, we don't need it and a Set is better suited to our access patterns
    *
    * @param data
    * @return
    */
  def decodeShortChannelIds(data: BinaryData): (Byte, Set[ShortChannelId]) = {
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
    def loop(acc: Set[ShortChannelId]): Set[ShortChannelId] = {
      if (read8() <= 0) acc else loop(acc + ShortChannelId(Protocol.uint64(buffer, ByteOrder.BIG_ENDIAN)))
    }

    try {
      (format, loop(Set.empty[ShortChannelId]))
    }
    finally {
      input.close()
    }
  }
}
