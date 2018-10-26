package fr.acinq.eclair.router

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.ByteOrder
import java.util.zip.{DeflaterOutputStream, InflaterInputStream}

import fr.acinq.bitcoin.{BinaryData, Protocol}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.router.ChannelRangeQueries._

import scala.annotation.tailrec
import scala.collection.SortedSet
import scala.collection.immutable.SortedMap

/**
  *
  * @param firstBlock                  fist block covered by this message
  * @param numBlocks                   number of blocks covered by this message
  * @param shortChannelIdAndTimestamps packed list of short channel id + timestamp #1 + timestamp #2
  */
case class ShortChannelIdAndTimestampsBlock(val firstBlock: Long, val numBlocks: Long, shortChannelIdAndTimestamps: BinaryData)

object ShortChannelIdAndTimestampsBlock {
  /**
    * Compressed a sequence of *sorted* short channel id and timestamps
    *
    * @param shortChannelIds must be sorted beforehand
    * @return a sequence of encoded short channel ids
    */
  def encode(firstBlockIn: Long, numBlocksIn: Long,
             shortChannelIds: SortedSet[ShortChannelId], timestamp: ShortChannelId => (Long, Long),
             format: Byte): List[ShortChannelIdAndTimestampsBlock] = {
    if (shortChannelIds.isEmpty) {
      // special case: reply with an "empty" block
      List(ShortChannelIdAndTimestampsBlock(firstBlockIn, numBlocksIn, BinaryData("00")))
    } else {
      // LN messages must fit in 65 Kb so we split ids into groups to make sure that the output message will be valid
      val count = format match {
        case UNCOMPRESSED_FORMAT => 2000
        case ZLIB_FORMAT => 5000 // TODO: do something less simplistic...
      }
      shortChannelIds.grouped(count).map(ids => {
        val (firstBlock, numBlocks) = if (ids.isEmpty) (firstBlockIn, numBlocksIn) else {
          val firstBlock: Long = ShortChannelId.coordinates(ids.head).blockHeight
          val numBlocks: Long = ShortChannelId.coordinates(ids.last).blockHeight - firstBlock + 1
          (firstBlock, numBlocks)
        }
        val encoded = encodeSingle(ids, timestamp, format)
        ShortChannelIdAndTimestampsBlock(firstBlock, numBlocks, encoded)
      }).toList
    }
  }

  def writeChannelIdAndTimestamps(out: OutputStream, id: ShortChannelId, timestamp1: Long, timestamp2: Long): Unit = {
    Protocol.writeUInt64(id.toLong, out, ByteOrder.BIG_ENDIAN)
    Protocol.writeUInt32(timestamp1, out, ByteOrder.BIG_ENDIAN)
    Protocol.writeUInt32(timestamp2, out, ByteOrder.BIG_ENDIAN)
  }

  def writeChannelIdAndTimestamps(out: OutputStream, id: ShortChannelId, timestamp: ShortChannelId => (Long, Long)): Unit = {
    val (ts1, ts2) = timestamp(id)
    writeChannelIdAndTimestamps(out, id, ts1, ts2)
  }

  def encodeSingle(shortChannelIds: Iterable[ShortChannelId], timestamp: ShortChannelId => (Long, Long), format: Byte): BinaryData = {
    val bos = new ByteArrayOutputStream()
    bos.write(format)
    val out = format match {
      case UNCOMPRESSED_FORMAT => bos
      case ZLIB_FORMAT => new DeflaterOutputStream(bos)
    }
    shortChannelIds.foreach(id => writeChannelIdAndTimestamps(out, id, timestamp))
    out.close()
    bos.toByteArray
  }

  /**
    * Decompress a zipped sequence of sorted [short channel id | timestamp1 | timestamp2 ] values.
    *
    * @param data
    * @return a sorted map of short channel id -> timestamps
    */
  def decode(data: BinaryData): (Byte, SortedMap[ShortChannelId, (Long, Long)]) = {
    val format = data.head
    if (data.tail.isEmpty) (format, SortedMap.empty[ShortChannelId, (Long, Long)]) else {
      val buffer = new Array[Byte](16)

      // read until there's nothing left
      @tailrec
      def loop(input: InputStream, acc: SortedMap[ShortChannelId, (Long, Long)]): SortedMap[ShortChannelId, (Long, Long)] = {
        val check = readBytes(buffer, input)
        if (check <= 0) acc else {
          val id = ShortChannelId(Protocol.uint64(buffer.take(8), ByteOrder.BIG_ENDIAN))
          val ts1 = Protocol.uint32(buffer.drop(8), ByteOrder.BIG_ENDIAN)
          val ts2 = Protocol.uint32(buffer.drop(12), ByteOrder.BIG_ENDIAN)
          loop(input, acc + (id -> (ts1, ts2)))
        }
      }

      def readAll() = {
        val bis = new ByteArrayInputStream(data.tail.toArray)
        val input = format match {
          case UNCOMPRESSED_FORMAT => bis
          case ZLIB_FORMAT => new InflaterInputStream(bis)
        }
        try {
          (format, loop(input, SortedMap.empty[ShortChannelId, (Long, Long)]))
        }
        finally {
          input.close()
        }
      }

      readAll()
    }
  }
}

case class ShortChannelIdAndFlag(id: ShortChannelId, flag: Byte) extends Ordered[ShortChannelIdAndFlag] {
  override def compare(that: ShortChannelIdAndFlag): Int = this.id.compare(that.id)
}

case class ShortChannelIdAndFlagsBlock(val firstBlock: Long, val numBlocks: Long, shortChannelIdAndFlags: BinaryData)

object ShortChannelIdAndFlagsBlock {
  /**
    * Compressed a sequence of *sorted* short channel id.
    *
    * @param shortChannelIds must be sorted beforehand
    * @return a sequence of encoded short channel ids
    */
  def encode(firstBlockIn: Long, numBlocksIn: Long,
             shortChannelIds: SortedSet[ShortChannelId], flag: ShortChannelId => Byte,
             format: Byte): List[ShortChannelIdAndFlagsBlock] = {
    if (shortChannelIds.isEmpty) {
      // special case: reply with an "empty" block
      List(ShortChannelIdAndFlagsBlock(firstBlockIn, numBlocksIn, BinaryData("00")))
    } else {
      // LN messages must fit in 65 Kb so we split ids into groups to make sure that the output message will be valid
      val count = format match {
        case UNCOMPRESSED_FORMAT => 2000
        case ZLIB_FORMAT => 5000 // TODO: do something less simplistic...
      }
      shortChannelIds.grouped(count).map(ids => {
        val (firstBlock, numBlocks) = if (ids.isEmpty) (firstBlockIn, numBlocksIn) else {
          val firstBlock: Long = ShortChannelId.coordinates(ids.head).blockHeight
          val numBlocks: Long = ShortChannelId.coordinates(ids.last).blockHeight - firstBlock + 1
          (firstBlock, numBlocks)
        }
        val encoded = encodeSingle(ids, flag, format)
        ShortChannelIdAndFlagsBlock(firstBlock, numBlocks, encoded)
      }).toList
    }
  }

  def encodeSingle(shortChannelIds: Iterable[ShortChannelId], flag: ShortChannelId => Byte, format: Byte): BinaryData = {
    val bos = new ByteArrayOutputStream()
    bos.write(format)
    val out = format match {
      case UNCOMPRESSED_FORMAT => bos
      case ZLIB_FORMAT => new DeflaterOutputStream(bos)
    }
    shortChannelIds.foreach(id => {
      Protocol.writeUInt64(id.toLong, out, ByteOrder.BIG_ENDIAN)
      Protocol.writeUInt8(flag(id), out)
    })
    out.close()
    bos.toByteArray
  }

  def encodeSingle(shortChannelIdAndFlags: SortedSet[ShortChannelIdAndFlag], format: Byte): BinaryData = {
    val bos = new ByteArrayOutputStream()
    bos.write(format)
    val out = format match {
      case UNCOMPRESSED_FORMAT => bos
      case ZLIB_FORMAT => new DeflaterOutputStream(bos)
    }
    shortChannelIdAndFlags.foreach(value => {
      Protocol.writeUInt64(value.id.toLong, out, ByteOrder.BIG_ENDIAN)
      Protocol.writeUInt8(value.flag, out)
    })
    out.close()
    bos.toByteArray
  }

  /**
    * Decompress a zipped sequence of sorted [short channel id | timestamp1 | timestamp2 ] values.
    *
    * @param data
    * @return a sorted map of short channel id -> timestamps
    */
  def decode(data: BinaryData): (Byte, SortedMap[ShortChannelId, Byte]) = {
    val format = data.head
    if (data.tail.isEmpty) (format, SortedMap.empty[ShortChannelId, Byte]) else {
      val buffer = new Array[Byte](9)

      // read until there's nothing left
      @tailrec
      def loop(input: InputStream, acc: SortedMap[ShortChannelId, Byte]): SortedMap[ShortChannelId, Byte] = {
        val check = readBytes(buffer, input)
        if (check <= 0) acc else {
          val id = ShortChannelId(Protocol.uint64(buffer.take(8), ByteOrder.BIG_ENDIAN))
          val flag = buffer(8)
          loop(input, acc + (id -> flag))
        }
      }

      def readAll() = {
        val bis = new ByteArrayInputStream(data.tail.toArray)
        val input = format match {
          case UNCOMPRESSED_FORMAT => bis
          case ZLIB_FORMAT => new InflaterInputStream(bis)
        }
        try {
          (format, loop(input, SortedMap.empty[ShortChannelId, Byte]))
        }
        finally {
          input.close()
        }
      }

      readAll()
    }
  }
}

