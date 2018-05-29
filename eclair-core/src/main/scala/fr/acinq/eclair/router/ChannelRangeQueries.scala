package fr.acinq.eclair.router

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.ByteOrder
import java.util.zip.{DeflaterOutputStream, GZIPInputStream, GZIPOutputStream, InflaterInputStream}

import fr.acinq.bitcoin.{BinaryData, Protocol}
import fr.acinq.eclair.ShortChannelId

import scala.annotation.tailrec

object ChannelRangeQueries {

  val UNCOMPRESSED_FORMAT = 0.toByte
  val ZLIB_FORMAT = 1.toByte

  case class ShortChannelIdsBlock(val firstBlock: Int, val numBlocks: Int, shortChannelIds: BinaryData)
  /**
    * Compressed a sequence of *sorted* short channel id.
    *
    * @param shortChannelIds must be sorted beforehand
    * @return a sequence of encoded short channel ids
    */
  def encodeShortChannelIds(firstBlockIn: Int, numBlocksIn: Int, shortChannelIds: Iterable[ShortChannelId], format: Byte, useGzip: Boolean = false): List[ShortChannelIdsBlock] = {
    // LN messages must fit in 65 Kb so we split ids into groups to make sure that the output message will be valid
    val count = format match {
      case UNCOMPRESSED_FORMAT => 7000
      case ZLIB_FORMAT => 12000 // TODO: do something less simplistic...
    }
    shortChannelIds.grouped(count).map(ids => {
      val (firstBlock, numBlocks) = if (ids.isEmpty) (firstBlockIn, numBlocksIn) else {
        val firstBlock = ShortChannelId.coordinates(ids.head).blockHeight
        val numBlocks = ShortChannelId.coordinates(ids.last).blockHeight - firstBlock + 1
        (firstBlock, numBlocks)
      }
      val encoded = encodeShortChannelIdsSingle(ids, format, useGzip)
      ShortChannelIdsBlock(firstBlock, numBlocks, encoded)
    }).toList
  }

  def encodeShortChannelIdsSingle(shortChannelIds: Iterable[ShortChannelId], format: Byte, useGzip: Boolean): BinaryData = {
    val bos = new ByteArrayOutputStream()
    bos.write(format)
    format match {
      case UNCOMPRESSED_FORMAT =>
        shortChannelIds.foreach(id => Protocol.writeUInt64(id.toLong, bos, ByteOrder.BIG_ENDIAN))
      case ZLIB_FORMAT =>
        val output = if (useGzip) new GZIPOutputStream(bos) else new DeflaterOutputStream(bos)
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
  def decodeShortChannelIds(data: BinaryData): (Byte, Set[ShortChannelId], Boolean) = {
    val format = data.head
    if (data.tail.isEmpty) (format, Set.empty[ShortChannelId], false) else {
      val buffer = new Array[Byte](8)

      // read 8 bytes from input
      // zipped input stream often returns less bytes than what you want to read
      @tailrec
      def read8(input: InputStream, offset: Int = 0): Int = input.read(buffer, offset, 8 - offset) match {
        case len if len <= 0 => len
        case 8 => 8
        case len if offset + len == 8 => 8
        case len => read8(input, offset + len)
      }

      // read until there's nothing left
      @tailrec
      def loop(input: InputStream, acc: Set[ShortChannelId]): Set[ShortChannelId] = {
        val check = read8(input)
        if (check <= 0) acc else loop(input, acc + ShortChannelId(Protocol.uint64(buffer, ByteOrder.BIG_ENDIAN)))
      }

      def readAll(useGzip: Boolean) = {
        val bis = new ByteArrayInputStream(data.tail.toArray)
        val input = format match {
          case UNCOMPRESSED_FORMAT => bis
          case ZLIB_FORMAT if useGzip => new GZIPInputStream(bis)
          case ZLIB_FORMAT => new InflaterInputStream(bis)
        }
        try {
          (format, loop(input, Set.empty[ShortChannelId]), useGzip)
        }
        finally {
          input.close()
        }
      }

      try {
        readAll(useGzip = false)
      }
      catch {
        case t: Throwable if format == ZLIB_FORMAT => readAll(useGzip = true)
      }
    }
  }
}
