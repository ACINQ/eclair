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

import java.nio.ByteOrder

import fr.acinq.bitcoin.{Block, Protocol}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.ReplyChannelRange
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.annotation.tailrec

@RunWith(classOf[JUnitRunner])
class ChannelRangeQueriesSpec extends FunSuite {
  val shortChannelIds = ChannelRangeQueriesSpec.readShortChannelIds().map(id => ShortChannelId(id))

  test("create `reply_channel_range` messages (uncompressed format)") {
    val reply = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 0, 2000000, 1, ChannelRangeQueries.encodeShortChannelIds(ChannelRangeQueries.UNCOMPRESSED_FORMAT, shortChannelIds))
    val (_, decoded) = ChannelRangeQueries.decodeShortChannelIds(reply.data)
    assert(decoded == shortChannelIds.toSet)
  }

  test("create `reply_channel_range` messages (GZIP format)") {
    val reply = ReplyChannelRange(Block.RegtestGenesisBlock.blockId, 0, 2000000, 1, ChannelRangeQueries.encodeShortChannelIds(ChannelRangeQueries.GZIP_FORMAT, shortChannelIds))
    val (_, decoded) = ChannelRangeQueries.decodeShortChannelIds(reply.data)
    assert(decoded == shortChannelIds.toSet)
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
