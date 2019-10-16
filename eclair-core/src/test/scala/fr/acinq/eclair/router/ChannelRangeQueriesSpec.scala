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
import fr.acinq.eclair.wire.ReplyChannelRangeTlv._
import fr.acinq.eclair.{LongToBtcAmount, randomKey}
import org.scalatest.FunSuite
import scodec.bits.ByteVector

import scala.collection.immutable.SortedMap
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
}
