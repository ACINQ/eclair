package fr.acinq.eclair.router

import fr.acinq.bitcoin.Block
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.ReplyChannelRange
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ChannelRangeQueriesSpec extends FunSuite {
  import ChannelRangeQueriesSpec._

  test("create `reply_channel_range` messages (uncompressed format)") {
    val blobs = ChannelRangeQueries.encodeShortChannelIds(ChannelRangeQueries.UNCOMPRESSED_FORMAT, shortChannelIds)
    val replies = blobs.map(blob  => ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 0, 2000000, 1, blob))
    var decoded = Set.empty[ShortChannelId]
    replies.foreach(reply => decoded = decoded ++ ChannelRangeQueries.decodeShortChannelIds(reply.data)._2)
    assert(decoded == shortChannelIds.toSet)
  }

  test("create `reply_channel_range` messages (GZIP format)") {
    val blobs = ChannelRangeQueries.encodeShortChannelIds(ChannelRangeQueries.GZIP_FORMAT, shortChannelIds)
    val replies = blobs.map(blob  => ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 0, 2000000, 1, blob))
    var decoded = Set.empty[ShortChannelId]
    replies.foreach(reply => decoded = decoded ++ ChannelRangeQueries.decodeShortChannelIds(reply.data)._2)
    assert(decoded == shortChannelIds.toSet)
  }
}

object ChannelRangeQueriesSpec {
  lazy val shortChannelIds = for {
    block <- 400000 to 420000
    txindex <- 0 to 5
    outputIndex <- 0 to 1
  } yield ShortChannelId(block, txindex, outputIndex)
}
