/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.router

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.ByteOrder
import java.util.zip.{DeflaterOutputStream, GZIPInputStream, GZIPOutputStream, InflaterInputStream}

import fr.acinq.bitcoin.{BinaryData, Protocol}
import fr.acinq.eclair.ShortChannelId

import scala.annotation.tailrec
import scala.collection.SortedSet

object ChannelRangeQueries {

  val UNCOMPRESSED_FORMAT = 0.toByte
  val ZLIB_FORMAT = 1.toByte

  case class ShortChannelIdsBlock(val firstBlock: Long, val numBlocks: Long, shortChannelIds: BinaryData)

  /**
    * Compressed a sequence of *sorted* short channel id.
    *
    * @param shortChannelIds must be sorted beforehand
    * @return a sequence of short channel id blocks
    */
  def encodeShortChannelIds(firstBlockIn: Long, numBlocksIn: Long, shortChannelIds: SortedSet[ShortChannelId], format: Byte, useGzip: Boolean = false): List[ShortChannelIdsBlock] = {
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
        val encoded = encodeShortChannelIdsSingle(ids, format, useGzip)
        ShortChannelIdsBlock(firstBlock, numBlocks, encoded)
      }).toList
    }
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
    * @param data
    * @return a sorted set of short channel ids
    */
  def decodeShortChannelIds(data: BinaryData): (Byte, SortedSet[ShortChannelId], Boolean) = {
    val format = data.head
    if (data.tail.isEmpty) (format, SortedSet.empty[ShortChannelId], false) else {
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
      def loop(input: InputStream, acc: SortedSet[ShortChannelId]): SortedSet[ShortChannelId] = {
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
