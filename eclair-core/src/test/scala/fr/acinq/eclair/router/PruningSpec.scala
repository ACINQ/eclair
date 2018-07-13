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

import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, Satoshi}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.router.RoutingSyncSpec.{FakeWatcher, makeFakeRoutingInfo}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{ShortChannelId, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, Outcome}

import scala.collection.{SortedSet, immutable}
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class PruningSpec extends TestkitBaseClass with BeforeAndAfterAll {

  val txid = BinaryData("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
  val remoteNodeId = PrivateKey(BinaryData("01" * 32), true).publicKey

  val startHeight = 400000 - 25 * 2016
  val shortChannelIds: immutable.SortedSet[ShortChannelId] = (for {
    block <- startHeight to startHeight + 50 * 50 by 50
    txindex <- 0 to 3
    outputIndex <- 0 to 1
  } yield ShortChannelId(block, txindex, outputIndex)).foldLeft(SortedSet.empty[ShortChannelId])(_ + _)

  val fakeRoutingInfo = shortChannelIds.map(makeFakeRoutingInfo)

  override type FixtureParam = ActorRef

  override protected def withFixture(test: OneArgTest): Outcome = {
    val watcherA = system.actorOf(Props(new FakeWatcher()))
    val paramsA = Alice.nodeParams
    val routingInfoA = fakeRoutingInfo
    routingInfoA.map {
      case (a, u1, u2, n1, n2) =>
        paramsA.networkDb.addChannel(a, txid, Satoshi(100000))
        paramsA.networkDb.addChannelUpdate(u1)
        paramsA.networkDb.addChannelUpdate(u2)
        paramsA.networkDb.addNode(n1)
        paramsA.networkDb.addNode(n2)
    }
    val probe = TestProbe()
    val switchboard = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case msg => probe.ref forward msg
      }
    }), "switchboard")

    val routerA = system.actorOf(Props(new Router(paramsA, watcherA)), "routerA")

    val sender = TestProbe()
    awaitCond({
      sender.send(routerA, 'channels)
      val channelsA = sender.expectMsgType[Iterable[ChannelAnnouncement]]
      channelsA.size == routingInfoA.size
    }, max = 30 seconds)

    test(routerA)
  }

  test("prune stale channel") {
    router => {
      val probe = TestProbe()
      probe.ignoreMsg { case TransportHandler.ReadAck(_) => true }
      val remoteNodeId = PrivateKey("01" * 32, true).publicKey

      // tell router to ask for our channel ids
      probe.send(router, SendChannelQuery(remoteNodeId, probe.ref))
      val QueryChannelRange(chainHash, firstBlockNum, numberOfBlocks) = probe.expectMsgType[QueryChannelRange]
      probe.expectMsgType[GossipTimestampFilter]

      // we don't send the first 10 channels, which are stale
      val shortChannelIds1 = shortChannelIds.drop(10)
      val reply = ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, 1.toByte, ChannelRangeQueries.encodeShortChannelIdsSingle(shortChannelIds1, ChannelRangeQueries.ZLIB_FORMAT, false))
      probe.send(router, PeerRoutingMessage(remoteNodeId, reply))
      probe.expectMsgType[QueryShortChannelIds]

      // router should see that it has 10 channels that we don't have, check if they're stale, and prune them
      awaitCond({
        probe.send(router, 'channels)
        val channels = probe.expectMsgType[Iterable[ChannelAnnouncement]]
        channels.size == fakeRoutingInfo.size - 10
      }, max = 30 seconds)
    }
  }
}
