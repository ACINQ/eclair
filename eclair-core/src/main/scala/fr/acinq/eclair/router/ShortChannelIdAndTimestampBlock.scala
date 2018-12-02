package fr.acinq.eclair.router

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.ByteOrder
import java.util.zip.{DeflaterOutputStream, InflaterInputStream}

import fr.acinq.bitcoin.{BinaryData, Protocol}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.router.ChannelRangeQueries.{UNCOMPRESSED_FORMAT, ZLIB_FORMAT, readBytes}

import scala.annotation.tailrec
import scala.collection.SortedSet
import scala.collection.immutable.SortedMap

/**
  *
  * README: this is an obsolete design for channel range queries, used by eclair only, and to be dropped ASAP
  * @param firstBlock                  fist block covered by this message
  * @param numBlocks                   number of blocks covered by this message
  * @param shortChannelIdAndTimestamps packed list of short channel id + the most recent channel update timestamp
  */
case class ShortChannelIdAndTimestampBlock(val firstBlock: Long, val numBlocks: Long, shortChannelIdAndTimestamps: BinaryData)

object ShortChannelIdAndTimestampBlock {
  /**
    * Compressed a sequence of *sorted* short channel id.
    *
    * @param shortChannelIds must be sorted beforehand
    * @return a sequence of encoded short channel ids
    */
  def encode(firstBlockIn: Long, numBlocksIn: Long,
             shortChannelIds: SortedSet[ShortChannelId], timestamp: ShortChannelId => Long,
             format: Byte): List[ShortChannelIdAndTimestampBlock] = {
    if (shortChannelIds.isEmpty) {
      // special case: reply with an "empty" block
      List(ShortChannelIdAndTimestampBlock(firstBlockIn, numBlocksIn, BinaryData("00")))
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
        val encoded = encodeSingle(ids, timestamp, format)
        ShortChannelIdAndTimestampBlock(firstBlock, numBlocks, encoded)
      }).toList
    }
  }

  def encodeSingle(shortChannelIds: Iterable[ShortChannelId], timestamp: ShortChannelId => Long, format: Byte): BinaryData = {
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
  def decode(data: BinaryData): (Byte, SortedMap[ShortChannelId, Long]) = {
    val format = data.head
    if (data.tail.isEmpty) (format, SortedMap.empty[ShortChannelId, Long]) else {
      val buffer = new Array[Byte](12)

      // read until there's nothing left
      @tailrec
      def loop(input: InputStream, acc: SortedMap[ShortChannelId, Long]): SortedMap[ShortChannelId, Long] = {
        val check = readBytes(buffer, input)
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

