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

import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, SatoshiLong}
import fr.acinq.eclair.RealShortChannelId
import fr.acinq.eclair.router.Router.{ChannelMeta, PublicChannel}
import fr.acinq.eclair.router.Sync._
import fr.acinq.eclair.wire.protocol.QueryChannelRangeTlv.QueryFlags
import fr.acinq.eclair.wire.protocol.QueryShortChannelIdsTlv.QueryFlagType._
import fr.acinq.eclair.wire.protocol.ReplyChannelRangeTlv._
import fr.acinq.eclair.wire.protocol.{EncodedShortChannelIds, EncodingType, LightningMessageCodecs, ReplyChannelRange}
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, ShortChannelId, TimestampSecond, TimestampSecondLong, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

import scala.annotation.tailrec
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class ChannelRangeQueriesSpec extends AnyFunSuite {

  test("ask for update test") {
    // they don't provide anything => we always ask for the update
    assert(shouldRequestUpdate(0 unixsec, 0, None, None))
    assert(shouldRequestUpdate(TimestampSecond(Int.MaxValue), 12345, None, None))

    // their update is older => don't ask
    val now = TimestampSecond.now()
    assert(!shouldRequestUpdate(now, 0, Some(now - 1), None))
    assert(!shouldRequestUpdate(now, 0, Some(now - 1), Some(12345)))
    assert(!shouldRequestUpdate(now, 12344, Some(now - 1), None))
    assert(!shouldRequestUpdate(now, 12344, Some(now - 1), Some(12345)))

    // their update is newer but stale => don't ask
    val old = now - 4 * 2016 * 24 * 3600
    assert(!shouldRequestUpdate(old - 1, 0, Some(old), None))
    assert(!shouldRequestUpdate(old - 1, 0, Some(old), Some(12345)))
    assert(!shouldRequestUpdate(old - 1, 12344, Some(old), None))
    assert(!shouldRequestUpdate(old - 1, 12344, Some(old), Some(12345)))

    // their update is newer but with the same checksum, and ours is stale or about to be => ask (we want to renew our update)
    assert(shouldRequestUpdate(old, 12345, Some(now), Some(12345)))

    // their update is newer but with the same checksum => don't ask
    assert(!shouldRequestUpdate(now - 1, 12345, Some(now), Some(12345)))

    // their update is newer with a different checksum => always ask
    assert(shouldRequestUpdate(now - 1, 0, Some(now), None))
    assert(shouldRequestUpdate(now - 1, 0, Some(now), Some(12345)))
    assert(shouldRequestUpdate(now - 1, 12344, Some(now), None))
    assert(shouldRequestUpdate(now - 1, 12344, Some(now), Some(12345)))

    // they just provided a 0 checksum => don't ask
    assert(!shouldRequestUpdate(0 unixsec, 0, None, Some(0)))
    assert(!shouldRequestUpdate(now, 1234, None, Some(0)))

    // they just provided a checksum that is the same as us => don't ask
    assert(!shouldRequestUpdate(now, 1234, None, Some(1234)))

    // they just provided a different checksum that is the same as us => ask
    assert(shouldRequestUpdate(now, 1234, None, Some(1235)))
  }

  test("compute checksums") {
    assert(crc32c(ByteVector.fromValidHex("00" * 32)) == 0x8a9136aaL)
    assert(crc32c(ByteVector.fromValidHex("FF" * 32)) == 0x62a8ab43L)
    assert(crc32c(ByteVector((0 to 31).map(_.toByte))) == 0x46dd794eL)
    assert(crc32c(ByteVector((31 to 0 by -1).map(_.toByte))) == 0x113fdb5cL)
  }

  test("compute flag tests") {
    val now = TimestampSecond.now()

    val a = randomKey().publicKey
    val b = randomKey().publicKey
    val ab = RouteCalculationSpec.makeChannel(123466L, a, b)
    val uab1 = RouteCalculationSpec.makeUpdateShort(ab.shortChannelId, ab.nodeId1, ab.nodeId2, 0 msat, 0, timestamp = now)
    val uab2 = RouteCalculationSpec.makeUpdateShort(ab.shortChannelId, ab.nodeId2, ab.nodeId1, 0 msat, 0, timestamp = now)

    val c = randomKey().publicKey
    val d = randomKey().publicKey
    val cd = RouteCalculationSpec.makeChannel(451312L, c, d)
    val ucd1 = RouteCalculationSpec.makeUpdateShort(cd.shortChannelId, cd.nodeId1, cd.nodeId2, 0 msat, 0, timestamp = now)

    val e = randomKey().publicKey
    val f = randomKey().publicKey
    val ef = RouteCalculationSpec.makeChannel(167514L, e, f)

    val channels = SortedMap(
      ab.shortChannelId -> PublicChannel(ab, ByteVector32.Zeroes, 0 sat, Some(uab1), Some(uab2), Some(ChannelMeta(1000 msat, 400 msat))),
      cd.shortChannelId -> PublicChannel(cd, ByteVector32.Zeroes, 0 sat, Some(ucd1), None, None)
    )

    assert(getChannelDigestInfo(channels)(ab.shortChannelId) == (Timestamps(now, now), Checksums(3352963162L, 2581904122L)))

    // no extended info but we know the channel: we ask for the updates
    assert(computeFlag(channels)(ab.shortChannelId, None, None, includeNodeAnnouncements = false) == (INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2))
    assert(computeFlag(channels)(ab.shortChannelId, None, None, includeNodeAnnouncements = true) == (INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2 | INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2))
    // same checksums, newer timestamps: we don't ask anything
    assert(computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now + 1, now + 1)), Some(Checksums(3352963162L, 2581904122L)), includeNodeAnnouncements = true) == 0)
    // different checksums, newer timestamps: we ask for the updates
    assert(computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now + 1, now)), Some(Checksums(154654604, 2581904122L)), includeNodeAnnouncements = true) == (INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2))
    assert(computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now, now + 1)), Some(Checksums(3352963162L, 45664546)), includeNodeAnnouncements = true) == (INCLUDE_CHANNEL_UPDATE_2 | INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2))
    assert(computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now + 1, now + 1)), Some(Checksums(154654604, 45664546 + 6)), includeNodeAnnouncements = true) == (INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2 | INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2))
    // different checksums, older timestamps: we don't ask anything
    assert(computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now - 1, now)), Some(Checksums(154654604, 2581904122L)), includeNodeAnnouncements = true) == 0)
    assert(computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now, now - 1)), Some(Checksums(3352963162L, 45664546)), includeNodeAnnouncements = true) == 0)
    assert(computeFlag(channels)(ab.shortChannelId, Some(Timestamps(now - 1, now - 1)), Some(Checksums(154654604, 45664546)), includeNodeAnnouncements = true) == 0)

    // missing channel update: we ask for it
    assert(computeFlag(channels)(cd.shortChannelId, Some(Timestamps(now, now)), Some(Checksums(3297511804L, 3297511804L)), includeNodeAnnouncements = true) == (INCLUDE_CHANNEL_UPDATE_2 | INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2))

    // unknown channel: we ask everything
    assert(computeFlag(channels)(ef.shortChannelId, None, None, includeNodeAnnouncements = false) == (INCLUDE_CHANNEL_ANNOUNCEMENT | INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2))
    assert(computeFlag(channels)(ef.shortChannelId, None, None, includeNodeAnnouncements = true) == (INCLUDE_CHANNEL_ANNOUNCEMENT | INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2 | INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2))
  }

  def makeShortChannelIds(height: BlockHeight, count: Int): List[RealShortChannelId] = {
    val output = ArrayBuffer.empty[RealShortChannelId]
    var txIndex = 0
    var outputIndex = 0
    while (output.size < count) {
      if (Random.nextBoolean()) {
        txIndex = txIndex + 1
        outputIndex = 0
      } else {
        outputIndex = outputIndex + 1
      }
      output += RealShortChannelId(height, txIndex, outputIndex)
    }
    output.toList
  }

  def validate(chunk: ShortChannelIdsChunk): Unit = {
    require(chunk.shortChannelIds.forall(keep(chunk.firstBlock, chunk.numBlocks, _)))
  }

  // check that chunks contain exactly the ids they were built from are are consistent i.e each chunk covers a range that immediately follows
  // the previous one even if there are gaps in block heights
  def validate(ids: SortedSet[RealShortChannelId], firstBlock: BlockHeight, numberOfBlocks: Long, chunks: List[ShortChannelIdsChunk]): Unit = {

    @tailrec
    def noOverlap(chunks: List[ShortChannelIdsChunk]): Boolean = chunks match {
      case Nil => true
      case a :: b :: _ if b.firstBlock != a.firstBlock + a.numBlocks => false
      case _ => noOverlap(chunks.tail)
    }

    // aggregate ids from all chunks, to check that they match our input ids exactly
    val chunkIds = SortedSet.empty[ShortChannelId] ++ chunks.flatMap(_.shortChannelIds).toSet
    val expected = ids.filter(keep(firstBlock, numberOfBlocks, _))

    if (expected.isEmpty) require(chunks == List(ShortChannelIdsChunk(firstBlock, numberOfBlocks, Nil)))
    chunks.foreach(validate)
    require(chunks.head.firstBlock == firstBlock)
    require(chunks.last.firstBlock + chunks.last.numBlocks == firstBlock + numberOfBlocks)
    require(chunkIds == expected)
    require(noOverlap(chunks))
  }

  test("limit channel ids chunk size") {
    val ids = makeShortChannelIds(BlockHeight(1), count = 3)
    val chunk = ShortChannelIdsChunk(BlockHeight(0), 10, ids)

    val res1 = for (_ <- 0 until 100) yield chunk.enforceMaximumSize(1).shortChannelIds
    assert(res1.toSet == Set(List(ids(0)), List(ids(1)), List(ids(2))))

    val res2 = for (_ <- 0 until 100) yield chunk.enforceMaximumSize(2).shortChannelIds
    assert(res2.toSet == Set(List(ids(0), ids(1)), List(ids(1), ids(2))))

    val res3 = for (_ <- 0 until 100) yield chunk.enforceMaximumSize(3).shortChannelIds
    assert(res3.toSet == Set(List(ids(0), ids(1), ids(2))))
  }

  test("split short channel ids correctly (basic tests") {

    def id(blockHeight: Int, txIndex: Int = 0, outputIndex: Int = 0) = RealShortChannelId(BlockHeight(blockHeight), txIndex, outputIndex)

    // no ids to split
    {
      val ids = Nil
      val firstBlock = BlockHeight(10)
      val numberOfBlocks = 100
      val chunks = split(SortedSet.empty[RealShortChannelId] ++ ids, firstBlock, numberOfBlocks, ids.size)
      assert(chunks == ShortChannelIdsChunk(firstBlock, numberOfBlocks, Nil) :: Nil)
    }

    // ids are all atfer the requested range
    {
      val ids = List(id(1000), id(1001), id(1002), id(1003), id(1004), id(1005))
      val firstBlock = BlockHeight(10)
      val numberOfBlocks = 100
      val chunks = split(SortedSet.empty[RealShortChannelId] ++ ids, firstBlock, numberOfBlocks, ids.size)
      assert(chunks == ShortChannelIdsChunk(firstBlock, numberOfBlocks, Nil) :: Nil)
    }

    // ids are all before the requested range
    {
      val ids = List(id(1000), id(1001), id(1002), id(1003), id(1004), id(1005))
      val firstBlock = BlockHeight(1100)
      val numberOfBlocks = 100
      val chunks = split(SortedSet.empty[RealShortChannelId] ++ ids, firstBlock, numberOfBlocks, ids.size)
      assert(chunks == ShortChannelIdsChunk(firstBlock, numberOfBlocks, Nil) :: Nil)
    }

    // all ids in different blocks, but they all fit in a single chunk
    {
      val ids = List(id(1000), id(1001), id(1002), id(1003), id(1004), id(1005))
      val firstBlock = BlockHeight(900)
      val numberOfBlocks = 200
      val chunks = split(SortedSet.empty[RealShortChannelId] ++ ids, firstBlock, numberOfBlocks, ids.size)
      assert(chunks == ShortChannelIdsChunk(firstBlock, numberOfBlocks, ids) :: Nil)
    }

    // all ids in the same block, chunk size == 2
    // chunk size will not be enforced and a single chunk should be created
    {
      val ids = List(id(1000, 0), id(1000, 1), id(1000, 2), id(1000, 3), id(1000, 4), id(1000, 5))
      val firstBlock = BlockHeight(900)
      val numberOfBlocks = 200
      val chunks = split(SortedSet.empty[RealShortChannelId] ++ ids, firstBlock, numberOfBlocks, 2)
      assert(chunks == ShortChannelIdsChunk(firstBlock, numberOfBlocks, ids) :: Nil)
    }

    // all ids in different blocks, chunk size == 2
    {
      val ids = List(id(1000), id(1005), id(1012), id(1013), id(1040), id(1050))
      val firstBlock = BlockHeight(900)
      val numberOfBlocks = 200
      val chunks = split(SortedSet.empty[RealShortChannelId] ++ ids, firstBlock, numberOfBlocks, 2)
      assert(chunks == List(
        ShortChannelIdsChunk(firstBlock, 100 + 6, List(ids(0), ids(1))),
        ShortChannelIdsChunk(BlockHeight(1006), 8, List(ids(2), ids(3))),
        ShortChannelIdsChunk(BlockHeight(1014), numberOfBlocks - (BlockHeight(1014) - firstBlock), List(ids(4), ids(5)))
      ))
    }

    // all ids in different blocks, chunk size == 2, first id outside of range
    {
      val ids = List(id(1000), id(1005), id(1012), id(1013), id(1040), id(1050))
      val firstBlock = BlockHeight(1001)
      val numberOfBlocks = 200
      val chunks = split(SortedSet.empty[RealShortChannelId] ++ ids, firstBlock, numberOfBlocks, 2)
      assert(chunks == List(
        ShortChannelIdsChunk(firstBlock, 12, List(ids(1), ids(2))),
        ShortChannelIdsChunk(BlockHeight(1013), 1040 - 1013 + 1, List(ids(3), ids(4))),
        ShortChannelIdsChunk(BlockHeight(1041), numberOfBlocks - (BlockHeight(1041) - firstBlock), List(ids(5)))
      ))
    }

    // all ids in different blocks, chunk size == 2, last id outside of range
    {
      val ids = List(id(1000), id(1001), id(1002), id(1003), id(1004), id(1005))
      val firstBlock = BlockHeight(900)
      val numberOfBlocks = 105
      val chunks = split(SortedSet.empty[RealShortChannelId] ++ ids, firstBlock, numberOfBlocks, 2)
      assert(chunks == List(
        ShortChannelIdsChunk(firstBlock, 100 + 2, List(ids(0), ids(1))),
        ShortChannelIdsChunk(BlockHeight(1002), 2, List(ids(2), ids(3))),
        ShortChannelIdsChunk(BlockHeight(1004), numberOfBlocks - (BlockHeight(1004) - firstBlock), List(ids(4)))
      ))
    }

    // all ids in different blocks, chunk size == 2, first and last id outside of range
    {
      val ids = List(id(1000), id(1001), id(1002), id(1003), id(1004), id(1005))
      val firstBlock = BlockHeight(1001)
      val numberOfBlocks = 4
      val chunks = split(SortedSet.empty[RealShortChannelId] ++ ids, firstBlock, numberOfBlocks, 2)
      assert(chunks == List(
        ShortChannelIdsChunk(firstBlock, 2, List(ids(1), ids(2))),
        ShortChannelIdsChunk(BlockHeight(1003), 2, List(ids(3), ids(4)))
      ))
    }

    // all ids in the same block
    {
      val ids = makeShortChannelIds(BlockHeight(1000), 100)
      val firstBlock = BlockHeight(900)
      val numberOfBlocks = 200
      val chunks = split(SortedSet.empty[RealShortChannelId] ++ ids, firstBlock, numberOfBlocks, 10)
      assert(chunks == ShortChannelIdsChunk(firstBlock, numberOfBlocks, ids) :: Nil)
    }
  }

  test("split short channel ids correctly") {
    val ids = SortedSet.empty[RealShortChannelId] ++ makeShortChannelIds(BlockHeight(42), 100) ++ makeShortChannelIds(BlockHeight(43), 70) ++ makeShortChannelIds(BlockHeight(44), 50) ++ makeShortChannelIds(BlockHeight(45), 30) ++ makeShortChannelIds(BlockHeight(50), 120)
    val firstBlock = BlockHeight(0)
    val numberOfBlocks = 1000

    validate(ids, firstBlock, numberOfBlocks, split(ids, firstBlock, numberOfBlocks, 1))
    validate(ids, firstBlock, numberOfBlocks, split(ids, firstBlock, numberOfBlocks, 20))
    validate(ids, firstBlock, numberOfBlocks, split(ids, firstBlock, numberOfBlocks, 50))
    validate(ids, firstBlock, numberOfBlocks, split(ids, firstBlock, numberOfBlocks, 100))
    validate(ids, firstBlock, numberOfBlocks, split(ids, firstBlock, numberOfBlocks, 1000))
  }

  test("split short channel ids correctly (comprehensive tests)") {
    val ids = SortedSet.empty[RealShortChannelId] ++ makeShortChannelIds(BlockHeight(42), 100) ++ makeShortChannelIds(BlockHeight(43), 70) ++ makeShortChannelIds(BlockHeight(45), 50) ++ makeShortChannelIds(BlockHeight(47), 30) ++ makeShortChannelIds(BlockHeight(50), 120)
    for (firstBlock <- 0 to 60) {
      for (numberOfBlocks <- 1 to 60) {
        for (chunkSize <- 1 :: 2 :: 20 :: 50 :: 100 :: 1000 :: Nil) {
          validate(ids, BlockHeight(firstBlock), numberOfBlocks, split(ids, BlockHeight(firstBlock), numberOfBlocks, chunkSize))
        }
      }
    }
  }

  test("enforce maximum size of short channel lists") {

    def makeChunk(startBlock: Int, count: Int): ShortChannelIdsChunk = ShortChannelIdsChunk(BlockHeight(startBlock), count, makeShortChannelIds(BlockHeight(startBlock), count))

    def validate(before: ShortChannelIdsChunk, after: ShortChannelIdsChunk): Unit = {
      require(before.shortChannelIds.containsSlice(after.shortChannelIds))
      require(after.shortChannelIds.size <= Sync.MAXIMUM_CHUNK_SIZE)
    }

    def validateChunks(before: List[ShortChannelIdsChunk], after: List[ShortChannelIdsChunk]): Unit = {
      before.zip(after).foreach { case (b, a) => validate(b, a) }
    }

    // empty chunk
    {
      val chunks = makeChunk(0, 0) :: Nil
      assert(enforceMaximumSize(chunks) == chunks)
    }

    // chunks are just below the limit
    {
      val chunks = makeChunk(0, Sync.MAXIMUM_CHUNK_SIZE) :: makeChunk(Sync.MAXIMUM_CHUNK_SIZE, Sync.MAXIMUM_CHUNK_SIZE) :: Nil
      assert(enforceMaximumSize(chunks) == chunks)
    }

    // fuzzy tests
    {
      val chunks = collection.mutable.ArrayBuffer.empty[ShortChannelIdsChunk]
      // we select parameters to make sure that some chunks will have too many ids
      for (_ <- 0 until 100) chunks += makeChunk(0, Sync.MAXIMUM_CHUNK_SIZE - 500 + Random.nextInt(1000))
      val pruned = enforceMaximumSize(chunks.toList)
      validateChunks(chunks.toList, pruned)
    }
  }

  test("encode maximum size reply_channel_range") {
    val scids = (1 to Sync.MAXIMUM_CHUNK_SIZE).map(i => RealShortChannelId(i)).toList
    val timestamps = (1 to Sync.MAXIMUM_CHUNK_SIZE).map(i => Timestamps(i.unixsec, (i + 1).unixsec)).toList
    val checksums = (1 to Sync.MAXIMUM_CHUNK_SIZE).map(i => Checksums(i, i + 1)).toList
    val reply = ReplyChannelRange(Block.RegtestGenesisBlock.hash, BlockHeight(0), 100, 0, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, scids), Some(EncodedTimestamps(EncodingType.UNCOMPRESSED, timestamps)), Some(EncodedChecksums(checksums)))
    val encoded = LightningMessageCodecs.lightningMessageCodec.encode(reply)
    assert(encoded.isSuccessful)
    assert(encoded.require.bytes.length <= 0xffff)
  }

  test("do not encode empty lists as COMPRESSED_ZLIB") {
    {
      val reply = buildReplyChannelRange(ShortChannelIdsChunk(BlockHeight(0), 42, Nil), syncComplete = true, Block.RegtestGenesisBlock.hash, EncodingType.COMPRESSED_ZLIB, Some(QueryFlags(QueryFlags.WANT_ALL)), SortedMap())
      assert(reply == ReplyChannelRange(Block.RegtestGenesisBlock.hash, BlockHeight(0), 42L, 1, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, Nil), Some(EncodedTimestamps(EncodingType.UNCOMPRESSED, Nil)), Some(EncodedChecksums(Nil))))
    }
    {
      val reply = buildReplyChannelRange(ShortChannelIdsChunk(BlockHeight(0), 42, Nil), syncComplete = false, Block.RegtestGenesisBlock.hash, EncodingType.COMPRESSED_ZLIB, Some(QueryFlags(QueryFlags.WANT_TIMESTAMPS)), SortedMap())
      assert(reply == ReplyChannelRange(Block.RegtestGenesisBlock.hash, BlockHeight(0), 42L, 0, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, Nil), Some(EncodedTimestamps(EncodingType.UNCOMPRESSED, Nil)), None))
    }
    {
      val reply = buildReplyChannelRange(ShortChannelIdsChunk(BlockHeight(0), 42, Nil), syncComplete = false, Block.RegtestGenesisBlock.hash, EncodingType.COMPRESSED_ZLIB, Some(QueryFlags(QueryFlags.WANT_CHECKSUMS)), SortedMap())
      assert(reply == ReplyChannelRange(Block.RegtestGenesisBlock.hash, BlockHeight(0), 42L, 0, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, Nil), None, Some(EncodedChecksums(Nil))))
    }
    {
      val reply = buildReplyChannelRange(ShortChannelIdsChunk(BlockHeight(0), 42, Nil), syncComplete = true, Block.RegtestGenesisBlock.hash, EncodingType.COMPRESSED_ZLIB, None, SortedMap())
      assert(reply == ReplyChannelRange(Block.RegtestGenesisBlock.hash, BlockHeight(0), 42L, 1, EncodedShortChannelIds(EncodingType.UNCOMPRESSED, Nil), None, None))
    }
  }

}
