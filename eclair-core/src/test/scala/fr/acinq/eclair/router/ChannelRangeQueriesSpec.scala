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

import fr.acinq.bitcoin.{Block, ByteVector32}
import fr.acinq.eclair.router.Router.ShortChannelIdsChunk
import fr.acinq.eclair.wire.QueryChannelRangeTlv.QueryFlags
import fr.acinq.eclair.wire.{EncodedShortChannelIds, EncodingType, QueryChannelRange, QueryChannelRangeTlv, ReplyChannelRange}
import fr.acinq.eclair.wire.ReplyChannelRangeTlv._
import fr.acinq.eclair.{LongToBtcAmount, ShortChannelId, randomKey}
import org.scalatest.FunSuite
import scodec.bits.ByteVector

import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.collection.mutable.ArrayBuffer
import scala.compat.Platform
import scala.util.Random


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

  def makeShortChannelIds(height: Int, count: Int): List[ShortChannelId] = {
    val output = ArrayBuffer.empty[ShortChannelId]
    var txIndex = 0
    var outputIndex = 0
    while (output.size < count) {
      if (Random.nextBoolean()) {
        txIndex = txIndex + 1
        outputIndex = 0
      } else {
        outputIndex = outputIndex + 1
      }
      output += ShortChannelId(height, txIndex, outputIndex)
    }
    output.toList
  }

  def validate(chunk: ShortChannelIdsChunk) = {
    require(chunk.shortChannelIds.forall(Router.keep(chunk.firstBlock, chunk.numBlocks, _)))
  }

  // check that chunks contain exactly the ids they were built from are are consistent i.e each chunk covers a range that immediately follows
  // the previous one even if there are gaps in block heights
  def validate(ids: SortedSet[ShortChannelId], firstBlockNum: Long, numberOfBlocks: Long, chunks: List[ShortChannelIdsChunk]): Unit = {

    @tailrec
    def noOverlap(chunks: List[ShortChannelIdsChunk]): Boolean = chunks match {
      case Nil => true
      case a :: b :: _ if b.firstBlock != a.firstBlock + a.numBlocks => false
      case _ => noOverlap(chunks.tail)
    }

    // aggregate ids from all chunks, to check that they match our input ids exactly
    val chunkIds = SortedSet.empty[ShortChannelId] ++ chunks.flatMap(_.shortChannelIds).toSet
    val expected = ids.filter(Router.keep(firstBlockNum, numberOfBlocks, _))

    if (expected.isEmpty) require(chunks == List(ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, Nil)))
    chunks.foreach(validate)
    require(chunks.head.firstBlock == firstBlockNum)
    require(chunks.last.firstBlock + chunks.last.numBlocks == firstBlockNum + numberOfBlocks)
    require(chunkIds == expected)
    require(noOverlap(chunks))
  }

    test("limit channel ids chunk size") {
    val ids = makeShortChannelIds(1, 3)
    val chunk = ShortChannelIdsChunk(0, 10, ids)

    val res1 = for (_ <- 0 until 100) yield chunk.enforceMaximumSize(1).shortChannelIds
    assert(res1.toSet == Set(List(ids(0)), List(ids(1)), List(ids(2))))

    val res2 = for (_ <- 0 until 100) yield chunk.enforceMaximumSize(2).shortChannelIds
    assert(res2.toSet == Set(List(ids(0), ids(1)), List(ids(1), ids(2))))

    val res3 = for (_ <- 0 until 100) yield chunk.enforceMaximumSize(3).shortChannelIds
    assert(res3.toSet == Set(List(ids(0), ids(1), ids(2))))
  }

  test("split short channel ids correctly (basic tests") {

    def id(blockHeight: Int, txIndex: Int = 0, outputIndex: Int = 0) = ShortChannelId(blockHeight, txIndex, outputIndex)

    // no ids to split
    {
      val ids = Nil
      val firstBlockNum = 10
      val numberOfBlocks = 100
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, ids.size)
      assert(chunks == ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, Nil) :: Nil)
    }

    // ids are all atfer the requested range
    {
      val ids = List(id(1000), id(1001), id(1002), id(1003), id(1004), id(1005))
      val firstBlockNum = 10
      val numberOfBlocks = 100
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, ids.size)
      assert(chunks == ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, Nil) :: Nil)
    }

    // ids are all before the requested range
    {
      val ids = List(id(1000), id(1001), id(1002), id(1003), id(1004), id(1005))
      val firstBlockNum = 1100
      val numberOfBlocks = 100
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, ids.size)
      assert(chunks == ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, Nil) :: Nil)
    }

    // all ids in different blocks, but they all fit in a single chunk
    {
      val ids = List(id(1000), id(1001), id(1002), id(1003), id(1004), id(1005))
      val firstBlockNum = 900
      val numberOfBlocks = 200
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, ids.size)
      assert(chunks == ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, ids) :: Nil)
    }

    // all ids in the same block, chunk size == 2
    // chunk size will not be enforced and a single chunk should be created
    {
      val ids = List(id(1000, 0), id(1000, 1), id(1000, 2), id(1000, 3), id(1000, 4), id(1000, 5))
      val firstBlockNum = 900
      val numberOfBlocks = 200
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, 2)
      assert(chunks == ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, ids) :: Nil)
    }

    // all ids in different blocks, chunk size == 2
    {
      val ids = List(id(1000), id(1005), id(1012), id(1013), id(1040), id(1050))
      val firstBlockNum = 900
      val numberOfBlocks = 200
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, 2)
      assert(chunks == List(
        ShortChannelIdsChunk(firstBlockNum, 100 + 6, List(ids(0), ids(1))),
        ShortChannelIdsChunk(1006, 8, List(ids(2), ids(3))),
        ShortChannelIdsChunk(1014, numberOfBlocks - 1014 + firstBlockNum, List(ids(4), ids(5)))
      ))
    }

    // all ids in different blocks, chunk size == 2, first id outside of range
    {
      val ids = List(id(1000), id(1005), id(1012), id(1013), id(1040), id(1050))
      val firstBlockNum = 1001
      val numberOfBlocks = 200
      val chunks = Router.split(SortedSet.empty[ShortChannelId] ++ ids, firstBlockNum, numberOfBlocks, 2)
      assert(chunks == List(
        ShortChannelIdsChunk(firstBlockNum, 12, List(ids(1), ids(2))),
        ShortChannelIdsChunk(1013, 1040 - 1013 + 1, List(ids(3), ids(4))),
        ShortChannelIdsChunk(1041, numberOfBlocks - 1041 + firstBlockNum, List(ids(5)))
      ))
    }

    // all ids in different blocks, chunk size == 2, last id outside of range
    {
      val ids = List(id(1000), id(1001), id(1002), id(1003), id(1004), id(1005))
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
      val ids = List(id(1000), id(1001), id(1002), id(1003), id(1004), id(1005))
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

  test("split short channel ids correctly") {
    val ids = SortedSet.empty[ShortChannelId] ++ makeShortChannelIds(42, 100) ++ makeShortChannelIds(43, 70) ++ makeShortChannelIds(44, 50) ++ makeShortChannelIds(45, 30) ++ makeShortChannelIds(50, 120)
    val firstBlockNum = 0
    val numberOfBlocks = 1000

    validate(ids, firstBlockNum, numberOfBlocks, Router.split(ids, firstBlockNum, numberOfBlocks, 1))
    validate(ids, firstBlockNum, numberOfBlocks, Router.split(ids, firstBlockNum, numberOfBlocks, 20))
    validate(ids, firstBlockNum, numberOfBlocks, Router.split(ids, firstBlockNum, numberOfBlocks, 50))
    validate(ids, firstBlockNum, numberOfBlocks, Router.split(ids, firstBlockNum, numberOfBlocks, 100))
    validate(ids, firstBlockNum, numberOfBlocks, Router.split(ids, firstBlockNum, numberOfBlocks, 1000))
  }

  test("split short channel ids correctly (comprehensive tests)") {
    val ids = SortedSet.empty[ShortChannelId] ++ makeShortChannelIds(42, 100) ++ makeShortChannelIds(43, 70) ++ makeShortChannelIds(45, 50) ++ makeShortChannelIds(47, 30) ++ makeShortChannelIds(50, 120)
    for (firstBlockNum <- 0 to 60) {
      for (numberOfBlocks <- 1 to 60) {
        for (chunkSize <- 1 :: 2 :: 20 :: 50 :: 100 :: 1000 :: Nil) {
          validate(ids, firstBlockNum, numberOfBlocks, Router.split(ids, firstBlockNum, numberOfBlocks, chunkSize))
        }
      }
    }
  }

  test("enforce maximum size of short channel lists") {

    def makeChunk(startBlock: Int, count : Int) = ShortChannelIdsChunk(startBlock, count, makeShortChannelIds(startBlock, count))

    def validate(before: ShortChannelIdsChunk, after: ShortChannelIdsChunk) = {
      require(before.shortChannelIds.containsSlice(after.shortChannelIds))
      require(after.shortChannelIds.size <= Router.MAXIMUM_CHUNK_SIZE)
    }

    def validateChunks(before: List[ShortChannelIdsChunk], after: List[ShortChannelIdsChunk]): Unit = {
      before.zip(after).foreach { case (b, a) => validate(b, a) }
    }

    // empty chunk
    {
      val chunks = makeChunk(0, 0) :: Nil
      assert(Router.enforceMaximumSize(chunks) == chunks)
    }

    // chunks are just below the limit
    {
      val chunks = makeChunk(0, Router.MAXIMUM_CHUNK_SIZE) :: makeChunk(Router.MAXIMUM_CHUNK_SIZE, Router.MAXIMUM_CHUNK_SIZE) :: Nil
      assert(Router.enforceMaximumSize(chunks) == chunks)
    }
    
    // fuzzy tests
    {
      val chunks = collection.mutable.ArrayBuffer.empty[ShortChannelIdsChunk]
      // we select parameters to make sure that some chunks will have too many ids
      for (i <- 0 until 100) chunks += makeChunk(0, Router.MAXIMUM_CHUNK_SIZE - 500 + Random.nextInt(1000))
      val pruned = Router.enforceMaximumSize(chunks.toList)
      validateChunks(chunks.toList, pruned)
    }
  }

  test("do not encode empty lists as COMPRESSED_ZLIB") {
    {
      val reply = Router.buildReplyChannelRange(ShortChannelIdsChunk(0, 42, Nil), Block.RegtestGenesisBlock.hash, EncodingType.COMPRESSED_ZLIB, Some(QueryFlags(QueryFlags.WANT_ALL)), SortedMap())
      assert(reply == ReplyChannelRange(Block.RegtestGenesisBlock.hash, 0L, 42L, 1.toByte, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, Nil), Some(EncodedTimestamps(EncodingType.UNCOMPRESSED, Nil)), Some(EncodedChecksums(Nil))))
    }
    {
      val reply = Router.buildReplyChannelRange(ShortChannelIdsChunk(0, 42, Nil), Block.RegtestGenesisBlock.hash, EncodingType.COMPRESSED_ZLIB, Some(QueryFlags(QueryFlags.WANT_TIMESTAMPS)), SortedMap())
      assert(reply == ReplyChannelRange(Block.RegtestGenesisBlock.hash, 0L, 42L, 1.toByte, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, Nil), Some(EncodedTimestamps(EncodingType.UNCOMPRESSED, Nil)), None))
    }
    {
      val reply = Router.buildReplyChannelRange(ShortChannelIdsChunk(0, 42, Nil), Block.RegtestGenesisBlock.hash, EncodingType.COMPRESSED_ZLIB, Some(QueryFlags(QueryFlags.WANT_CHECKSUMS)), SortedMap())
      assert(reply == ReplyChannelRange(Block.RegtestGenesisBlock.hash, 0L, 42L, 1.toByte, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, Nil), None, Some(EncodedChecksums(Nil))))
    }
    {
      val reply = Router.buildReplyChannelRange(ShortChannelIdsChunk(0, 42, Nil), Block.RegtestGenesisBlock.hash, EncodingType.COMPRESSED_ZLIB, None, SortedMap())
      assert(reply == ReplyChannelRange(Block.RegtestGenesisBlock.hash, 0L, 42L, 1.toByte, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, Nil), None, None))
    }
  }
}
