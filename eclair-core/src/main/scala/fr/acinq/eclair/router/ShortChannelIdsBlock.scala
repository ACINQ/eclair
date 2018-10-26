package fr.acinq.eclair.router

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.ByteOrder
import java.util.zip.{DeflaterOutputStream, GZIPInputStream, GZIPOutputStream, InflaterInputStream}

import fr.acinq.bitcoin.{BinaryData, Protocol}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.router.ChannelRangeQueries.{UNCOMPRESSED_FORMAT, ZLIB_FORMAT, readBytes}

import scala.annotation.tailrec
import scala.collection.SortedSet

case class ShortChannelIdsBlock(val firstBlock: Long, val numBlocks: Long, shortChannelIds: BinaryData)

object ShortChannelIdsBlock {
  /**
    * Compressed a sequence of *sorted* short channel id.
    *
    * @param shortChannelIds must be sorted beforehand
    * @return a sequence of short channel id blocks
    */
  def encode(firstBlockIn: Long, numBlocksIn: Long, shortChannelIds: SortedSet[ShortChannelId], format: Byte, useGzip: Boolean = false): List[ShortChannelIdsBlock] = {
    if (shortChannelIds.isEmpty) {
      // special case: reply with an "empty" block
      List(ShortChannelIdsBlock(firstBlockIn, numBlocksIn, BinaryData("00")))
    } else {
      // LN messages must fit in 65 Kb so we split ids into groups to make sure that the output message will be valid
      val count = format match {
        case UNCOMPRESSED_FORMAT => 7000
        case ZLIB_FORMAT => 12000 // TODO: do something less simplistic...
      }
      shortChannelIds.grouped(count).map(ids => {
        val (firstBlock, numBlocks) = if (ids.isEmpty) (firstBlockIn, numBlocksIn) else {
          val firstBlock: Long = ShortChannelId.coordinates(ids.head).blockHeight
          val numBlocks: Long = ShortChannelId.coordinates(ids.last).blockHeight - firstBlock + 1
          (firstBlock, numBlocks)
        }
        val encoded = encodeSingle(ids, format, useGzip)
        ShortChannelIdsBlock(firstBlock, numBlocks, encoded)
      }).toList
    }
  }

  def encodeSingle(shortChannelIds: Iterable[ShortChannelId], format: Byte, useGzip: Boolean): BinaryData = {
    val bos = new ByteArrayOutputStream()
    bos.write(format)
    val out = format match {
      case UNCOMPRESSED_FORMAT => bos
      case ZLIB_FORMAT => if (useGzip) new GZIPOutputStream(bos) else new DeflaterOutputStream(bos)
    }
    shortChannelIds.foreach(id => Protocol.writeUInt64(id.toLong, out, ByteOrder.BIG_ENDIAN))
    out.close()
    bos.toByteArray
  }

  /**
    * Decompress a zipped sequence of sorted short channel ids.
    *
    * @param data
    * @return a sorted set of short channel ids
    */
  def decode(data: BinaryData): (Byte, SortedSet[ShortChannelId], Boolean) = {
    val format = data.head
    if (data.tail.isEmpty) (format, SortedSet.empty[ShortChannelId], false) else {
      val buffer = new Array[Byte](8)

      // read until there's nothing left
      @tailrec
      def loop(input: InputStream, acc: SortedSet[ShortChannelId]): SortedSet[ShortChannelId] = {
        val check = readBytes(buffer, input)
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
          (format, loop(input, SortedSet.empty[ShortChannelId]), useGzip)
        }
        finally {
          input.close()
        }
      }

      try {
        readAll(useGzip = false)
      }
      catch {
        case _: Throwable if format == ZLIB_FORMAT => readAll(useGzip = true)
      }
    }
  }
}

