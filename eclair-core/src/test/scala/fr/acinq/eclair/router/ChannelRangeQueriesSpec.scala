/*
 * Copyright 2019 ACINQ SAS
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

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.router.Router.ShortChannelIdsChunk
import fr.acinq.eclair.wire.ReplyChannelRangeTlv._
import fr.acinq.eclair.{LongToBtcAmount, ShortChannelId, randomKey}
import org.scalatest.FunSuite
import scodec.bits.ByteVector

import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.compat.Platform


class ChannelRangeQueriesSpec extends FunSuite {

  test("ask for update test") {
    // they don't provide anything => we always ask for the update
    assert(Router.shouldRequestUpdate(0, 0, None, None))
    assert(Router.shouldRequestUpdate(Int.MaxValue, 12345, None, None))

    // their update is older => don't ask
    val now = Platform.currentTime / 1000
    assert(!Router.shouldRequestUpdate(now, 0, Some(now - 1), None))
    assert(!Router.shouldRequestUpdate(now, 0, Some(now - 1), Some(12345)))
    assert(!Router.shouldRequestUpdate(now, 12344, Some(now - 1), None))
    assert(!Router.shouldRequestUpdate(now, 12344, Some(now - 1), Some(12345)))

    // their update is newer but stale => don't ask
    val old = now - 4 * 2016 * 24 * 3600
    assert(!Router.shouldRequestUpdate(old - 1, 0, Some(old), None))
    assert(!Router.shouldRequestUpdate(old - 1, 0, Some(old), Some(12345)))
    assert(!Router.shouldRequestUpdate(old - 1, 12344, Some(old), None))
    assert(!Router.shouldRequestUpdate(old - 1, 12344, Some(old), Some(12345)))

    // their update is newer but with the same checksum, and ours is stale or about to be => ask (we want to renew our update)
    assert(Router.shouldRequestUpdate(old, 12345, Some(now), Some(12345)))

    // their update is newer but with the same checksum => don't ask
    assert(!Router.shouldRequestUpdate(now - 1, 12345, Some(now), Some(12345)))

    // their update is newer with a different checksum => always ask
    assert(Router.shouldRequestUpdate(now - 1, 0, Some(now), None))
    assert(Router.shouldRequestUpdate(now - 1, 0, Some(now), Some(12345)))
    assert(Router.shouldRequestUpdate(now - 1, 12344, Some(now), None))
    assert(Router.shouldRequestUpdate(now - 1, 12344, Some(now), Some(12345)))

    // they just provided a 0 checksum => don't ask
    assert(!Router.shouldRequestUpdate(0, 0, None, Some(0)))
    assert(!Router.shouldRequestUpdate(now, 1234, None, Some(0)))

    // they just provided a checksum that is the same as us => don't ask
    assert(!Router.shouldRequestUpdate(now, 1234, None, Some(1234)))

    // they just provided a different checksum that is the same as us => ask
    assert(Router.shouldRequestUpdate(now, 1234, None, Some(1235)))
  }

  test("compute checksums") {
    assert(Router.crc32c(ByteVector.fromValidHex("00" * 32)) == 0x8a9136aaL)
    assert(Router.crc32c(ByteVector.fromValidHex("FF" * 32)) == 0x62a8ab43L)
    assert(Router.crc32c(ByteVector((0 to 31).map(_.toByte))) == 0x46dd794eL)
    assert(Router.crc32c(ByteVector((31 to 0 by -1).map(_.toByte))) == 0x113fdb5cL)
  }

  test("compute flag tests") {

    val now = Platform.currentTime / 1000

    val a = randomKey.publicKey
    val b = randomKey.publicKey
    val ab = RouteCalculationSpec.makeChannel(123466L, a, b)
    val (ab1, uab1) = RouteCalculationSpec.makeUpdateShort(ab.shortChannelId, ab.nodeId1, ab.nodeId2, 0 msat, 0, timestamp = now)
    val (ab2, uab2) = RouteCalculationSpec.makeUpdateShort(ab.shortChannelId, ab.nodeId2, ab.nodeId1, 0 msat, 0, timestamp = now)

    val c = randomKey.publicKey
    val d = randomKey.publicKey
    val cd = RouteCalculationSpec.makeChannel(451312L, c, d)
    val (cd1, ucd1) = RouteCalculationSpec.makeUpdateShort(cd.shortChannelId, cd.nodeId1, cd.nodeId2, 0 msat, 0, timestamp = now)
    val (_, ucd2) = RouteCalculationSpec.makeUpdateShort(cd.shortChannelId, cd.nodeId2, cd.nodeId1, 0 msat, 0, timestamp = now)

    val e = randomKey.publicKey
    val f = randomKey.publicKey
    val ef = RouteCalculationSpec.makeChannel(167514L, e, f)

    val channels = SortedMap(
      ab.shortChannelId -> PublicChannel(ab, ByteVector32.Zeroes, 0 sat, Some(uab1), Some(uab2)),
      cd.shortChannelId -> PublicChannel(cd, ByteVector32.Zeroes, 0 sat, Some(ucd1), None)
    )

    import fr.acinq.eclair.wire.QueryShortChannelIdsTlv.QueryFlagType._

    assert(Router.getChannelDigestInfo(channels)(ab.shortChannelId) == (Timestamps(now, now), Checksums(1697591108L, 3692323747L)))

    // no extended info but we know the channel: we ask for the updates
    assert(Router.computeFlag(channels)(ab.shortChannelId, None, None, false) === (INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2))
    assert(Router.computeFlag(channels)(ab.shortChannelId, None, None, true) === (INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2 | INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2))
    // same checksums, newer timestamps: we don't ask anything
    assert(Router.computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now + 1, now + 1)), Some(Checksums(1697591108L, 3692323747L)), true) === 0)
    // different checksums, newer timestamps: we ask for the updates
    assert(Router.computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now + 1, now)), Some(Checksums(154654604, 3692323747L)), true) === (INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2))
    assert(Router.computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now, now + 1)), Some(Checksums(1697591108L, 45664546)), true) === (INCLUDE_CHANNEL_UPDATE_2 | INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2))
    assert(Router.computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now + 1, now + 1)), Some(Checksums(154654604, 45664546 + 6)), true) === (INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2 | INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2))
    // different checksums, older timestamps: we don't ask anything
    assert(Router.computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now - 1, now)), Some(Checksums(154654604, 3692323747L)), true) === 0)
    assert(Router.computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now, now - 1)), Some(Checksums(1697591108L, 45664546)), true) === 0)
    assert(Router.computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now - 1, now - 1)), Some(Checksums(154654604, 45664546)), true) === 0)

    // missing channel update: we ask for it
    assert(Router.computeFlag(channels)(cd.shortChannelId, Some(Timestamps(now, now)), Some(Checksums(3297511804L, 3297511804L)), true) === (INCLUDE_CHANNEL_UPDATE_2 | INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2))

    // unknown channel: we ask everything
    assert(Router.computeFlag(channels)(ef.shortChannelId, None, None, false) === (INCLUDE_CHANNEL_ANNOUNCEMENT | INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2))
    assert(Router.computeFlag(channels)(ef.shortChannelId, None, None, true) === (INCLUDE_CHANNEL_ANNOUNCEMENT | INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2 | INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2))
  }

  def makeShortChannelIds(height: Int, count: Int): List[ShortChannelId] = (0 until count).map(c => ShortChannelId(height, c, 0)).toList

  def validate(chunk: ShortChannelIdsChunk) = {
    require(!chunk.shortChannelIds.exists(!Router.keep(chunk.firstBlock, chunk.numBlocks, _)))
  }

  // check that chunks do not overlap and contain exactly the ids they were built from
  def validate(ids: SortedSet[ShortChannelId], firstBlockNum: Long, numberOfBlocks: Long, chunks: List[ShortChannelIdsChunk]): Unit = {

    @tailrec
    def noOverloap(chunks: List[ShortChannelIdsChunk]): Boolean = chunks match {
      case Nil => true
      case a :: b :: _ if b.firstBlock < a.firstBlock + a.numBlocks => false
      case _ => noOverloap(chunks.tail)
    }

    // aggregate ids from all chunks, to check that they match our input ids exactly
    val chunkIds = SortedSet.empty[ShortChannelId] ++ chunks.map(_.shortChannelIds).flatten.toSet
    val expected = ids.filter(Router.keep(firstBlockNum, numberOfBlocks, _))

    if (expected.isEmpty) require(chunks == List(ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, Nil)))
    chunks.foreach(validate)
    require(chunks.head.firstBlock == firstBlockNum)
    require(chunks.last.firstBlock + chunks.last.numBlocks == firstBlockNum + numberOfBlocks)
    require(chunkIds == expected)
    require(noOverloap(chunks))
  }

  test("split short channel ids correctly (basic tests") {
    // no ids to split
    {
      val ids = Nil
      val firstBlockNum = 10
      val numberOfBlocks = 100
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, ids.size)
      assert(chunks == ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, Nil) :: Nil)
    }
    assert(Router.split(SortedSet.empty[ShortChannelId], 100, 1000, 100) == ShortChannelIdsChunk(100, 1000, Nil) :: Nil)

    // ids are all before the requested range
    {
      val ids = List(ShortChannelId(1000, 0, 0), ShortChannelId(1001, 0, 0), ShortChannelId(1002, 0, 0), ShortChannelId(1003, 0, 0), ShortChannelId(1004, 0, 0), ShortChannelId(1005, 0, 0))
      val firstBlockNum = 10
      val numberOfBlocks = 100
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, ids.size)
      assert(chunks == ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, Nil) :: Nil)
    }

    // ids are all after the requested range
    {
      val ids = List(ShortChannelId(1000, 0, 0), ShortChannelId(1001, 0, 0), ShortChannelId(1002, 0, 0), ShortChannelId(1003, 0, 0), ShortChannelId(1004, 0, 0), ShortChannelId(1005, 0, 0))
      val firstBlockNum = 1100
      val numberOfBlocks = 100
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, ids.size)
      assert(chunks == ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, Nil) :: Nil)
    }

    // all ids in different blocks, but they all fit in a single chunk
    {
      val ids = List(ShortChannelId(1000, 0, 0), ShortChannelId(1001, 0, 0), ShortChannelId(1002, 0, 0), ShortChannelId(1003, 0, 0), ShortChannelId(1004, 0, 0), ShortChannelId(1005, 0, 0))
      val firstBlockNum = 900
      val numberOfBlocks = 200
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, ids.size)
      assert(chunks == ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, ids) :: Nil)
    }

    // all ids in the same block, chunk size == 2
    {
      val ids = List(ShortChannelId(1000, 0, 0), ShortChannelId(1000, 1, 0), ShortChannelId(1000, 2, 0), ShortChannelId(1000, 3, 0), ShortChannelId(1000, 4, 0), ShortChannelId(1000, 5, 0))
      val firstBlockNum = 900
      val numberOfBlocks = 200
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, 2)
      assert(chunks == ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, ids) :: Nil)
    }

    // all ids in different blocks, chunk size == 2
    {
      val ids = List(ShortChannelId(1000, 0, 0), ShortChannelId(1001, 0, 0), ShortChannelId(1002, 0, 0), ShortChannelId(1003, 0, 0), ShortChannelId(1004, 0, 0), ShortChannelId(1005, 0, 0))
      val firstBlockNum = 900
      val numberOfBlocks = 200
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, 2)
      assert(chunks == List(
        ShortChannelIdsChunk(firstBlockNum, 100 + 2, List(ids(0), ids(1))),
        ShortChannelIdsChunk(1002, 2, List(ids(2), ids(3))),
        ShortChannelIdsChunk(1004, numberOfBlocks - 1004 + firstBlockNum, List(ids(4), ids(5)))
      ))
    }

    // all ids in different blocks, chunk size == 2, first id outside of range
    {
      val ids = List(ShortChannelId(1000, 0, 0), ShortChannelId(1001, 0, 0), ShortChannelId(1002, 0, 0), ShortChannelId(1003, 0, 0), ShortChannelId(1004, 0, 0), ShortChannelId(1005, 0, 0))
      val firstBlockNum = 1001
      val numberOfBlocks = 200
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, 2)
      assert(chunks == List(
        ShortChannelIdsChunk(firstBlockNum, 2, List(ids(1), ids(2))),
        ShortChannelIdsChunk(1003, 2, List(ids(3), ids(4))),
        ShortChannelIdsChunk(1005, numberOfBlocks - 1005 + firstBlockNum, List(ids(5)))
      ))
    }

    // all ids in different blocks, chunk size == 2, last id outside of range
    {
      val ids = List(ShortChannelId(1000, 0, 0), ShortChannelId(1001, 0, 0), ShortChannelId(1002, 0, 0), ShortChannelId(1003, 0, 0), ShortChannelId(1004, 0, 0), ShortChannelId(1005, 0, 0))
      val firstBlockNum = 900
      val numberOfBlocks = 105
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, 2)
      assert(chunks == List(
        ShortChannelIdsChunk(firstBlockNum, 100 + 2, List(ids(0), ids(1))),
        ShortChannelIdsChunk(1002, 2, List(ids(2), ids(3))),
        ShortChannelIdsChunk(1004, numberOfBlocks - 1004 + firstBlockNum, List(ids(4)))
      ))
   }

    // all ids in different blocks, chunk size == 2, first and last id outside of range
    {
      val ids = List(ShortChannelId(1000, 0, 0), ShortChannelId(1001, 0, 0), ShortChannelId(1002, 0, 0), ShortChannelId(1003, 0, 0), ShortChannelId(1004, 0, 0), ShortChannelId(1005, 0, 0))
      val firstBlockNum = 1001
      val numberOfBlocks = 4
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, 2)
      assert(chunks == List(
        ShortChannelIdsChunk(firstBlockNum, 2, List(ids(1), ids(2))),
        ShortChannelIdsChunk(1003, 2, List(ids(3), ids(4)))
      ))
    }

    // all ids in the same block
    {
      val ids = makeShortChannelIds(1000, 100)
      val firstBlockNum = 900
      val numberOfBlocks = 200
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, 10)
      assert(chunks == ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, ids) :: Nil)
    }
  }

  test("split short channel ids correctly ") {
    val ids = SortedSet.empty[ShortChannelId] ++ makeShortChannelIds(42, 100) ++ makeShortChannelIds(43, 70) ++ makeShortChannelIds(44, 50) ++ makeShortChannelIds(45, 30) ++ makeShortChannelIds(50, 120)
    val firstBlockNum = 0
    val numberOfBlocks = 1000

    validate(ids, firstBlockNum, numberOfBlocks, Router.split(ids, firstBlockNum, numberOfBlocks, 1))
    validate(ids, firstBlockNum, numberOfBlocks, Router.split(ids, firstBlockNum, numberOfBlocks, 20))
    validate(ids, firstBlockNum, numberOfBlocks, Router.split(ids, firstBlockNum, numberOfBlocks, 50))
    validate(ids, firstBlockNum, numberOfBlocks, Router.split(ids, firstBlockNum, numberOfBlocks, 100))
    validate(ids, firstBlockNum, numberOfBlocks, Router.split(ids, firstBlockNum, numberOfBlocks, 1000))
  }

  test("fuzzy test") {
    val ids = SortedSet.empty[ShortChannelId] ++ makeShortChannelIds(42, 100) ++ makeShortChannelIds(43, 70) ++ makeShortChannelIds(44, 50) ++ makeShortChannelIds(45, 30) ++ makeShortChannelIds(50, 120)
    for (firstBlockNum <- 0 to 60) {
      for (numberOfBlocks <- 1 to 60) {
        for (chunkSize <- 1 :: 2 :: 20 :: 50 :: 100 :: 1000 :: Nil) {
          validate(ids, firstBlockNum, numberOfBlocks, Router.split(ids, firstBlockNum, numberOfBlocks, chunkSize))
        }
      }
    }
  }
}
