package fr.acinq.eclair.router

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.ByteOrder
import java.util.zip.{DeflaterOutputStream, GZIPInputStream, GZIPOutputStream, InflaterInputStream}

import fr.acinq.bitcoin.{BinaryData, Protocol}
import fr.acinq.eclair.ShortChannelId

import scala.annotation.tailrec
import scala.collection.SortedSet
import scala.collection.immutable.SortedMap

object ChannelRangeQueriesEx {
  val UNCOMPRESSED_FORMAT = 0.toByte
  val ZLIB_FORMAT = 1.toByte

  case class ShortChannelIdAndTimestampsBlock(val firstBlock: Long, val numBlocks: Long, shortChannelIdAndTimestamps: BinaryData)

  /**
    * Compressed a sequence of *sorted* short channel id.
    *
    * @param shortChannelIds must be sorted beforehand
    * @return a sequence of encoded short channel ids
    */
  def encodeShortChannelIdAndTimestamps(firstBlockIn: Long, numBlocksIn: Long,
                                        shortChannelIds: SortedSet[ShortChannelId], timestamp: ShortChannelId => Long,
                                        format: Byte): List[ShortChannelIdAndTimestampsBlock] = {
    if (shortChannelIds.isEmpty) {
      // special case: reply with an "empty" block
      List(ShortChannelIdAndTimestampsBlock(firstBlockIn, numBlocksIn, BinaryData("00")))
    } else {
      // LN messages must fit in 65 Kb so we split ids into groups to make sure that the output message will be valid
      val count = format match {
        case UNCOMPRESSED_FORMAT => 4000
        case ZLIB_FORMAT => 8000 // TODO: do something less simplistic...
      }
      shortChannelIds.grouped(count).map(ids => {
        val (firstBlock, numBlocks) = if (ids.isEmpty) (firstBlockIn, numBlocksIn) else {
          val firstBlock: Long = ShortChannelId.coordinates(ids.head).blockHeight
          val numBlocks: Long = ShortChannelId.coordinates(ids.last).blockHeight - firstBlock + 1
          (firstBlock, numBlocks)
        }
        val encoded = encodeShortChannelIdAndTimestampsSingle(ids, timestamp, format)
        ShortChannelIdAndTimestampsBlock(firstBlock, numBlocks, encoded)
      }).toList
    }
  }

  def encodeShortChannelIdAndTimestampsSingle(shortChannelIds: Iterable[ShortChannelId], timestamp: ShortChannelId => Long, format: Byte): BinaryData = {
    val bos = new ByteArrayOutputStream()
    bos.write(format)
    val out = format match {
      case UNCOMPRESSED_FORMAT => bos
      case ZLIB_FORMAT => new DeflaterOutputStream(bos)
    }
    shortChannelIds.foreach(id => {
      Protocol.writeUInt64(id.toLong, out, ByteOrder.BIG_ENDIAN)
      Protocol.writeUInt32(timestamp(id), out, ByteOrder.BIG_ENDIAN)
    })
    out.close()
    bos.toByteArray
  }
  
  /**
    * Decompress a zipped sequence of sorted [short channel id | timestamp] values.
    *
    * @param data
    * @return a sorted map of short channel id -> timestamp
    */
  def decodeShortChannelIdAndTimestamps(data: BinaryData): (Byte, SortedMap[ShortChannelId, Long]) = {
    val format = data.head
    if (data.tail.isEmpty) (format, SortedMap.empty[ShortChannelId, Long]) else {
      val buffer = new Array[Byte](12)

      // read 12 bytes from input
      // zipped input stream often returns less bytes than what you want to read
      @tailrec
      def read12(input: InputStream, offset: Int = 0): Int = input.read(buffer, offset, 12 - offset) match {
        case len if len <= 0 => len
        case 12 => 12
        case len if offset + len == 12 => 12
        case len => read12(input, offset + len)
      }


      // read until there's nothing left
      @tailrec
      def loop(input: InputStream, acc: SortedMap[ShortChannelId, Long]): SortedMap[ShortChannelId, Long] = {
        val check = read12(input)
        if (check <= 0) acc else loop(input, acc + (ShortChannelId(Protocol.uint64(buffer.take(8), ByteOrder.BIG_ENDIAN)) -> Protocol.uint32(buffer.drop(8), ByteOrder.BIG_ENDIAN)))
      }

      def readAll() = {
        val bis = new ByteArrayInputStream(data.tail.toArray)
        val input = format match {
          case UNCOMPRESSED_FORMAT => bis
          case ZLIB_FORMAT => new InflaterInputStream(bis)
        }
        try {
          (format, loop(input, SortedMap.empty[ShortChannelId, Long]))
        }
        finally {
          input.close()
        }
      }

      readAll()
    }
  }
}
