package fr.acinq.eclair.router

import java.nio.ByteOrder

import fr.acinq.bitcoin.{Block, Protocol}
import fr.acinq.eclair.wire.ReplyChannelRange
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.annotation.tailrec

@RunWith(classOf[JUnitRunner])
class ChannelRangeQueriesSpec extends FunSuite {
  val shortChannelIds = ChannelRangeQueriesSpec.readShortChannelIds()

  test("create `reply_channel_range` messages (uncompressed format)") {
    val reply = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 0, 2000000, 1, ChannelRangeQueries.encodeShortChannelIds(ChannelRangeQueries.UNCOMPRESSED_FORMAT, shortChannelIds))
    val (_, decoded) = ChannelRangeQueries.decodeShortChannelIds(reply.data)
    assert(decoded == shortChannelIds)
  }

  test("create `reply_channel_range` messages (GZIP format)") {
    val reply = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 0, 2000000, 1, ChannelRangeQueries.encodeShortChannelIds(ChannelRangeQueries.GZIP_FORMAT, shortChannelIds))
    val (_, decoded) = ChannelRangeQueries.decodeShortChannelIds(reply.data)
    assert(decoded == shortChannelIds)
  }
}

object ChannelRangeQueriesSpec {
  def readShortChannelIds() = {
    val stream = classOf[ChannelRangeQueriesSpec].getResourceAsStream("/short_channels-mainnet.422")

    @tailrec
    def loop(acc: Vector[Long] = Vector()): Vector[Long] = if (stream.available() == 0) acc else loop(acc :+ Protocol.uint64(stream, ByteOrder.BIG_ENDIAN))

    try {
      loop()
    }
    finally {
      stream.close()
    }
  }
}
