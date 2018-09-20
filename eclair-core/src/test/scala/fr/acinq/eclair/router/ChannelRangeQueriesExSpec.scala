package fr.acinq.eclair.router

import fr.acinq.bitcoin.Block
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.router.ChannelRangeQueriesSpec.shortChannelIds
import fr.acinq.eclair.wire.{ReplyChannelRange, ReplyChannelRangeEx}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.collection.immutable.SortedMap
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class ChannelRangeQueriesExSpec extends FunSuite {
  import ChannelRangeQueriesEx._
  val random = new Random()
  val shortChannelIds = ChannelRangeQueriesSpec.shortChannelIds
  val timestamps = shortChannelIds.map(id => id -> random.nextInt(400000).toLong).toMap

  test("create `reply_channel_range_ex` messages (uncompressed format)") {
    val blocks = ChannelRangeQueriesEx.encodeShortChannelIdAndTimestamps(400000, 20000, shortChannelIds, id => timestamps(id), ChannelRangeQueries.UNCOMPRESSED_FORMAT)
    val replies = blocks.map(block  => ReplyChannelRangeEx(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIdAndTimestamps))
    var decoded = SortedMap.empty[ShortChannelId, Long]
    replies.foreach(reply => decoded = decoded ++ ChannelRangeQueriesEx.decodeShortChannelIdAndTimestamps(reply.data)._2)
    assert(decoded.keySet == shortChannelIds)
    shortChannelIds.foreach(id => timestamps(id) == decoded(id))
  }

  test("create `reply_channel_range_ex` messages (zlib format)") {
    val blocks = ChannelRangeQueriesEx.encodeShortChannelIdAndTimestamps(400000, 20000, shortChannelIds, id => timestamps(id), ChannelRangeQueries.ZLIB_FORMAT)
    val replies = blocks.map(block  => ReplyChannelRangeEx(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIdAndTimestamps))
    var decoded = SortedMap.empty[ShortChannelId, Long]
    replies.foreach(reply => decoded = decoded ++ ChannelRangeQueriesEx.decodeShortChannelIdAndTimestamps(reply.data)._2)
    assert(decoded.keySet == shortChannelIds)
    shortChannelIds.foreach(id => timestamps(id) == decoded(id))
  }

}
