package fr.acinq.eclair.router

import fr.acinq.bitcoin.Block
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.{ReplyChannelRange, ReplyChannelRangeDeprecated}
import org.scalatest.FunSuite

import scala.collection.immutable.SortedMap
import scala.collection.{SortedSet, immutable}
import scala.util.Random


class ChannelRangeQueriesSpec extends FunSuite {
  import ChannelRangeQueriesSpec._

  val random = new Random()
  val timestamp = shortChannelIds.map(id => id -> random.nextInt(400000).toLong).toMap
  val timestamps = shortChannelIds.map(id => id -> (random.nextInt(400000).toLong, random.nextInt(400000).toLong)).toMap

  /*test("create `reply_channel_range` messages (uncompressed format)") {
    val blocks = ShortChannelIdsBlock.encode(400000, 20000, shortChannelIds, ChannelRangeQueries.UNCOMPRESSED_FORMAT)
    val replies = blocks.map(block  => ReplyChannelRange(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIds))
    var decoded = Set.empty[ShortChannelId]
    replies.foreach(reply => decoded = decoded ++ ShortChannelIdsBlock.decode(reply.data)._2)
    assert(decoded == shortChannelIds)
  }

  test("create `reply_channel_range` messages (ZLIB format)") {
    val blocks = ShortChannelIdsBlock.encode(400000, 20000, shortChannelIds, ChannelRangeQueries.ZLIB_FORMAT, useGzip = false)
    val replies = blocks.map(block  => ReplyChannelRange(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIds))
    var decoded = Set.empty[ShortChannelId]
    replies.foreach(reply => decoded = decoded ++ {
      val (ChannelRangeQueries.ZLIB_FORMAT, ids, false) = ShortChannelIdsBlock.decode(reply.data)
      ids
    })
    assert(decoded == shortChannelIds)
  }

  test("create `reply_channel_range` messages (GZIP format)") {
    val blocks = ShortChannelIdsBlock.encode(400000, 20000, shortChannelIds, ChannelRangeQueries.ZLIB_FORMAT, useGzip = true)
    val replies = blocks.map(block  => ReplyChannelRange(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIds))
    var decoded = Set.empty[ShortChannelId]
    replies.foreach(reply => decoded = decoded ++ {
      val (ChannelRangeQueries.ZLIB_FORMAT, ids, true) = ShortChannelIdsBlock.decode(reply.data)
      ids
    })
    assert(decoded == shortChannelIds)
  }

  test("create empty `reply_channel_range` message") {
    val blocks = ShortChannelIdsBlock.encode(400000, 20000, SortedSet.empty[ShortChannelId], ChannelRangeQueries.ZLIB_FORMAT, useGzip = false)
    val replies = blocks.map(block  => ReplyChannelRange(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIds))
    var decoded = Set.empty[ShortChannelId]
    replies.foreach(reply => decoded = decoded ++ {
      val (format, ids, false) = ShortChannelIdsBlock.decode(reply.data)
      ids
    })
    assert(decoded.isEmpty)
  }

  test("create `reply_channel_range_ex` messages (uncompressed format)") {
    val blocks = ShortChannelIdAndTimestampBlock.encode(400000, 20000, shortChannelIds, id => timestamp(id), ChannelRangeQueries.UNCOMPRESSED_FORMAT)
    val replies = blocks.map(block  => ReplyChannelRangeDeprecated(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIdAndTimestamps))
    var decoded = SortedMap.empty[ShortChannelId, Long]
    replies.foreach(reply => decoded = decoded ++ ShortChannelIdAndTimestampBlock.decode(reply.data)._2)
    assert(decoded.keySet == shortChannelIds)
    shortChannelIds.foreach(id => timestamp(id) == decoded(id))
  }

  test("create `reply_channel_range_ex` messages (zlib format)") {
    val blocks = ShortChannelIdAndTimestampBlock.encode(400000, 20000, shortChannelIds, id => timestamp(id), ChannelRangeQueries.ZLIB_FORMAT)
    val replies = blocks.map(block  => ReplyChannelRangeDeprecated(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIdAndTimestamps))
    var decoded = SortedMap.empty[ShortChannelId, Long]
    replies.foreach(reply => decoded = decoded ++ ShortChannelIdAndTimestampBlock.decode(reply.data)._2)
    assert(decoded.keySet == shortChannelIds)
    shortChannelIds.foreach(id => timestamp(id) == decoded(id))
  }

  test("create `reply_channel_range_ex2` messages (uncompressed format)") {
    val blocks = ShortChannelIdAndTimestampsBlock.encode(400000, 20000, shortChannelIds, id => timestamps(id), ChannelRangeQueries.UNCOMPRESSED_FORMAT)
    val replies = blocks.map(block  => ReplyChannelRangeDeprecated(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIdAndTimestamps))
    var decoded = SortedMap.empty[ShortChannelId, (Long, Long)]
    replies.foreach(reply => decoded = decoded ++ ShortChannelIdAndTimestampsBlock.decode(reply.data)._2)
    assert(decoded.keySet == shortChannelIds)
    shortChannelIds.foreach(id => timestamp(id) == decoded(id))
  }

  test("create `reply_channel_range_ex2` messages (zlib format)") {
    val blocks = ShortChannelIdAndTimestampsBlock.encode(400000, 20000, shortChannelIds, id => timestamps(id), ChannelRangeQueries.ZLIB_FORMAT)
    val replies = blocks.map(block  => ReplyChannelRangeDeprecated(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIdAndTimestamps))
    var decoded = SortedMap.empty[ShortChannelId, (Long, Long)]
    replies.foreach(reply => decoded = decoded ++ ShortChannelIdAndTimestampsBlock.decode(reply.data)._2)
    assert(decoded.keySet == shortChannelIds)
    shortChannelIds.foreach(id => timestamp(id) == decoded(id))
  }*/

}

object ChannelRangeQueriesSpec {
  lazy val shortChannelIds: immutable.SortedSet[ShortChannelId] = (for {
    block <- 400000 to 420000
    txindex <- 0 to 5
    outputIndex <- 0 to 1
  } yield ShortChannelId(block, txindex, outputIndex)).foldLeft(SortedSet.empty[ShortChannelId])(_ + _)
}
