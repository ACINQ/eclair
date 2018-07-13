package fr.acinq.eclair.router

import fr.acinq.bitcoin.Block
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.ReplyChannelRange
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.collection.{SortedSet, immutable}

@RunWith(classOf[JUnitRunner])
class ChannelRangeQueriesSpec extends FunSuite {
  import ChannelRangeQueriesSpec._

  test("create `reply_channel_range` messages (uncompressed format)") {
    val blocks = ChannelRangeQueries.encodeShortChannelIds(400000, 20000, shortChannelIds, ChannelRangeQueries.UNCOMPRESSED_FORMAT)
    val replies = blocks.map(block  => ReplyChannelRange(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIds))
    var decoded = Set.empty[ShortChannelId]
    replies.foreach(reply => decoded = decoded ++ ChannelRangeQueries.decodeShortChannelIds(reply.data)._2)
    assert(decoded == shortChannelIds)
  }

  test("create `reply_channel_range` messages (ZLIB format)") {
    val blocks = ChannelRangeQueries.encodeShortChannelIds(400000, 20000, shortChannelIds, ChannelRangeQueries.ZLIB_FORMAT, useGzip = false)
    val replies = blocks.map(block  => ReplyChannelRange(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIds))
    var decoded = Set.empty[ShortChannelId]
    replies.foreach(reply => decoded = decoded ++ {
      val (ChannelRangeQueries.ZLIB_FORMAT, ids, false) = ChannelRangeQueries.decodeShortChannelIds(reply.data)
      ids
    })
    assert(decoded == shortChannelIds)
  }

  test("create `reply_channel_range` messages (GZIP format)") {
    val blocks = ChannelRangeQueries.encodeShortChannelIds(400000, 20000, shortChannelIds, ChannelRangeQueries.ZLIB_FORMAT, useGzip = true)
    val replies = blocks.map(block  => ReplyChannelRange(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIds))
    var decoded = Set.empty[ShortChannelId]
    replies.foreach(reply => decoded = decoded ++ {
      val (ChannelRangeQueries.ZLIB_FORMAT, ids, true) = ChannelRangeQueries.decodeShortChannelIds(reply.data)
      ids
    })
    assert(decoded == shortChannelIds)
  }

  test("create empty `reply_channel_range` message") {
    val blocks = ChannelRangeQueries.encodeShortChannelIds(400000, 20000, SortedSet.empty[ShortChannelId], ChannelRangeQueries.ZLIB_FORMAT, useGzip = false)
    val replies = blocks.map(block  => ReplyChannelRange(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIds))
    var decoded = Set.empty[ShortChannelId]
    replies.foreach(reply => decoded = decoded ++ {
      val (format, ids, false) = ChannelRangeQueries.decodeShortChannelIds(reply.data)
      ids
    })
    assert(decoded.isEmpty)
  }
}

object ChannelRangeQueriesSpec {
  lazy val shortChannelIds: immutable.SortedSet[ShortChannelId] = (for {
    block <- 400000 to 420000
    txindex <- 0 to 5
    outputIndex <- 0 to 1
  } yield ShortChannelId(block, txindex, outputIndex)).foldLeft(SortedSet.empty[ShortChannelId])(_ + _)
}
