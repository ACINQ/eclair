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

import fr.acinq.eclair.randomKey
import fr.acinq.eclair.wire._
import org.scalatest.FunSuite

import scala.collection.immutable.SortedMap
import scala.compat.Platform


class ChannelRangeQueriesSpec extends FunSuite {

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
}
