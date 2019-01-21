package fr.acinq.eclair.router

import fr.acinq.bitcoin.Block
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.ReplyChannelRangeDeprecated
import org.scalatest.FunSuite

import scala.collection.immutable.SortedMap
import scala.util.Random

class ChannelRangeQueriesWithTimestampsSpec extends FunSuite {
  val random = new Random()
  val shortChannelIds = ChannelRangeQueriesSpec.shortChannelIds
  val timestamps = shortChannelIds.map(id => id -> random.nextInt(400000).toLong).toMap

  /*test("create `reply_channel_range_ex` messages (uncompressed format)") {
    val blocks = ShortChannelIdAndTimestampBlock.encode(400000, 20000, shortChannelIds, id => timestamps(id), ChannelRangeQueries.UNCOMPRESSED_FORMAT)
    val replies = blocks.map(block  => ReplyChannelRangeDeprecated(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIdAndTimestamps))
    var decoded = SortedMap.empty[ShortChannelId, Long]
    replies.foreach(reply => decoded = decoded ++ ShortChannelIdAndTimestampBlock.decode(reply.data)._2)
    assert(decoded.keySet == shortChannelIds)
    shortChannelIds.foreach(id => timestamps(id) == decoded(id))
  }

  test("create `reply_channel_range_ex` messages (zlib format)") {
    val blocks = ShortChannelIdAndTimestampBlock.encode(400000, 20000, shortChannelIds, id => timestamps(id), ChannelRangeQueries.ZLIB_FORMAT)
    val replies = blocks.map(block  => ReplyChannelRangeDeprecated(Block.RegtestGenesisBlock.blockId, block.firstBlock, block.numBlocks, 1, block.shortChannelIdAndTimestamps))
    var decoded = SortedMap.empty[ShortChannelId, Long]
    replies.foreach(reply => decoded = decoded ++ ShortChannelIdAndTimestampBlock.decode(reply.data)._2)
    assert(decoded.keySet == shortChannelIds)
    shortChannelIds.foreach(id => timestamps(id) == decoded(id))
  }*/
}
