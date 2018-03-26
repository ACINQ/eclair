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

package fr.acinq.eclair.io

import akka.actor.ActorRef
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.{ShortChannelId, TestkitBaseClass}
import fr.acinq.eclair.router.RoutingSyncSpec.makeFakeRoutingInfo
import fr.acinq.eclair.router.{ChannelRangeQueriesSpec, Rebroadcast}
import fr.acinq.eclair.wire.GossipTimeRange
import org.scalatest.Outcome

class PeerSpec extends TestkitBaseClass {
  val shortChannelIds = ChannelRangeQueriesSpec.readShortChannelIds().take(100).map(id => ShortChannelId(id))
  val fakeRoutingInfo = shortChannelIds.map(makeFakeRoutingInfo)
  val channels = fakeRoutingInfo.map(_._1)
  val updates = fakeRoutingInfo.map(_._2) ++ fakeRoutingInfo.map(_._3)
  val nodes = fakeRoutingInfo.map(_._4) ++ fakeRoutingInfo.map(_._5)

  override type FixtureParam = TestProbe

  override protected def withFixture(test: OneArgTest): Outcome = {
    val probe = TestProbe()
    test(probe)
  }

  test("filter gossip message (no filtering)") { probe =>
    val rebroadcast = Rebroadcast(channels.map(_ -> Set.empty[ActorRef]).toMap, updates.map(_ -> Set.empty[ActorRef]).toMap, nodes.map(_ -> Set.empty[ActorRef]).toMap)
    val (channels1, updates1, nodes1) = Peer.filterGossipMessages(rebroadcast, probe.ref, None)
    assert(channels1.toSet == channels.toSet)
    assert(updates1.toSet == updates.toSet)
    assert(nodes1.toSet == nodes.toSet)
  }

  test("filter gossip message (filtered by origin)") { probe =>
    val rebroadcast = Rebroadcast(
      channels.map(_ -> Set.empty[ActorRef]).toMap + (channels(5) -> Set(probe.ref)),
      updates.map(_ -> Set.empty[ActorRef]).toMap + (updates(6) -> Set(probe.ref)) + (updates(10) -> Set(probe.ref)),
      nodes.map(_ -> Set.empty[ActorRef]).toMap + (nodes(4) -> Set(probe.ref)))
    val (channels1, updates1, nodes1) = Peer.filterGossipMessages(rebroadcast, probe.ref, None)
    assert(channels1.toSet == channels.toSet - channels(5))
    assert(updates1.toSet == updates.toSet - updates(6) - updates(10))
    assert(nodes1.toSet == nodes.toSet - nodes(4))
  }

  test("filter gossip message (filtered by timestamp)") { probe =>
    val rebroadcast = Rebroadcast(channels.map(_ -> Set.empty[ActorRef]).toMap, updates.map(_ -> Set.empty[ActorRef]).toMap, nodes.map(_ -> Set.empty[ActorRef]).toMap)
    val timestamps = updates.map(_.timestamp).sorted.drop(10).take(20)
    val (channels1, updates1, nodes1) = Peer.filterGossipMessages(rebroadcast, probe.ref, Some(GossipTimeRange(Block.RegtestGenesisBlock.blockId, timestamps.head, timestamps.last - timestamps.head)))
    assert(updates1.toSet == updates.toSet.filter(u => timestamps.contains(u.timestamp)))
    assert(nodes1.toSet == nodes.toSet.filter(u => timestamps.contains(u.timestamp)))
  }
}
