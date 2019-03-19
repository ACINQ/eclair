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

import fr.acinq.eclair.ShortChannelId
import org.scalatest.FunSuite

import scala.collection.{SortedSet, immutable}
import fr.acinq.eclair.randomKey
import fr.acinq.eclair.wire.{ExtendedInfo, QueryFlagTypes, TimestampsAndChecksums}

import scala.collection.immutable.SortedMap
import scala.compat.Platform
import scala.util.Random


class ChannelRangeQueriesSpec extends FunSuite {
  import ChannelRangeQueriesSpec._

  val random = new Random()
  val timestamp = shortChannelIds.map(id => id -> random.nextInt(400000).toLong).toMap
  val timestamps = shortChannelIds.map(id => id -> (random.nextInt(400000).toLong, random.nextInt(400000).toLong)).toMap

  test("compute flag tests") {

    val now = Platform.currentTime / 1000

    val a = randomKey.publicKey
    val b = randomKey.publicKey
    val ab = RouteCalculationSpec.makeChannel(123466L, a, b)
    val (ab1, uab1) = RouteCalculationSpec.makeUpdateShort(ab.shortChannelId, ab.nodeId1, ab.nodeId2, 0, 0, timestamp = now)
    val (ab2, uab2) = RouteCalculationSpec.makeUpdateShort(ab.shortChannelId, ab.nodeId2, ab.nodeId1, 0, 0, timestamp = now)

    val c = randomKey.publicKey
    val d = randomKey.publicKey
    val cd = RouteCalculationSpec.makeChannel(451312L, c, d)
    val (cd1, ucd1) = RouteCalculationSpec.makeUpdateShort(cd.shortChannelId, cd.nodeId1, cd.nodeId2, 0, 0, timestamp = now)
    val (_, ucd2) = RouteCalculationSpec.makeUpdateShort(cd.shortChannelId, cd.nodeId2, cd.nodeId1, 0, 0, timestamp = now)

    val e = randomKey.publicKey
    val f = randomKey.publicKey
    val ef = RouteCalculationSpec.makeChannel(167514L, e, f)

    val channels = SortedMap(
      ab.shortChannelId -> ab,
      cd.shortChannelId -> cd
    )

    val updates = Map(
      ab1 -> uab1,
      ab2 -> uab2,
      cd1 -> ucd1
    )

    import fr.acinq.eclair.wire.QueryFlagTypes._

    assert(Router.getChannelDigestInfo(channels, updates)(ab.shortChannelId) == TimestampsAndChecksums(now, 714408668, now, 714408668))

    // no extended info but we know the channel: we ask for the updates
    assert(Router.computeFlag(channels, updates)(ab.shortChannelId, None) === (INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2).toByte)
    // same checksums, newer timestamps: we don't ask anything
    assert(Router.computeFlag(channels, updates)(ab.shortChannelId, Some(TimestampsAndChecksums(now + 1, 714408668, now + 1, 714408668))) === 0.toByte)
    // different checksums, newer timestamps: we ask for the updates
    assert(Router.computeFlag(channels, updates)(ab.shortChannelId, Some(TimestampsAndChecksums(now + 1, 154654604, now, 714408668))) === INCLUDE_CHANNEL_UPDATE_1)
    assert(Router.computeFlag(channels, updates)(ab.shortChannelId, Some(TimestampsAndChecksums(now, 714408668, now + 1, 45664546))) === INCLUDE_CHANNEL_UPDATE_2)
    assert(Router.computeFlag(channels, updates)(ab.shortChannelId, Some(TimestampsAndChecksums(now + 1, 154654604, now + 1, 45664546+6))) === (INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2).toByte)
    // different checksums, older timestamps: we don't ask anything
    assert(Router.computeFlag(channels, updates)(ab.shortChannelId, Some(TimestampsAndChecksums(now - 1, 154654604, now, 714408668))) === 0.toByte)
    assert(Router.computeFlag(channels, updates)(ab.shortChannelId, Some(TimestampsAndChecksums(now, 714408668, now - 1, 45664546))) === 0.toByte)
    assert(Router.computeFlag(channels, updates)(ab.shortChannelId, Some(TimestampsAndChecksums(now - 1, 154654604, now - 1, 45664546))) === 0.toByte)

    // missing channel update: we ask for it
    assert(Router.computeFlag(channels, updates)(cd.shortChannelId, Some(TimestampsAndChecksums(now, 714408668, now, 714408668))) === INCLUDE_CHANNEL_UPDATE_2)

    // unknown channel: we ask everything
    assert(Router.computeFlag(channels, updates)(ef.shortChannelId, None) === QueryFlagTypes.INCLUDE_ALL)

  }

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
