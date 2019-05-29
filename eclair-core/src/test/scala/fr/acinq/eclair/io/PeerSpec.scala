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

package fr.acinq.eclair.io

import java.net.{Inet4Address, InetSocketAddress}

import akka.actor.{ActorRef, ActorSystem}
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.{EventFilter, TestFSMRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.db.ChannelStateSpec
import fr.acinq.eclair.io.Peer._
import fr.acinq.eclair.router.RoutingSyncSpec.makeFakeRoutingInfo
import fr.acinq.eclair.router.{ChannelRangeQueries, ChannelRangeQueriesSpec, Rebroadcast}
import fr.acinq.eclair.wire.LightningMessageCodecsSpec.randomSignature
import fr.acinq.eclair.wire.{Color, Error, IPv4, NodeAddress, NodeAnnouncement, Ping, Pong}
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector
import scala.concurrent.duration._

class PeerSpec extends TestkitBaseClass {

  def ipv4FromInet4(address: InetSocketAddress) = IPv4.apply(address.getAddress.asInstanceOf[Inet4Address], address.getPort)

  val fakeIPAddress = NodeAddress.fromParts("1.2.3.4", 42000).get
  val shortChannelIds = ChannelRangeQueriesSpec.shortChannelIds.take(100)
  val fakeRoutingInfo = shortChannelIds.map(makeFakeRoutingInfo)
  val channels = fakeRoutingInfo.map(_._1).toList
  val updates = (fakeRoutingInfo.map(_._2) ++ fakeRoutingInfo.map(_._3)).toList
  val nodes = (fakeRoutingInfo.map(_._4) ++ fakeRoutingInfo.map(_._5)).toList

  case class FixtureParam(remoteNodeId: PublicKey, authenticator: TestProbe, watcher: TestProbe, router: TestProbe, relayer: TestProbe, connection: TestProbe, transport: TestProbe, peer: TestFSMRef[Peer.State, Peer.Data, Peer])

  override protected def withFixture(test: OneArgTest): Outcome = {
    val aParams = Alice.nodeParams
    val aliceParams = test.tags.contains("with_node_announcements") match {
      case true =>
        val aliceAnnouncement = NodeAnnouncement(randomSignature, ByteVector.empty, 1, Bob.nodeParams.nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", fakeIPAddress :: Nil)
        aParams.db.network.addNode(aliceAnnouncement)
        aParams
      case false => aParams
    }

    val authenticator = TestProbe()
    val watcher = TestProbe()
    val router = TestProbe()
    val relayer = TestProbe()
    val connection = TestProbe()
    val transport = TestProbe()
    val wallet: EclairWallet = null // unused
    val remoteNodeId = Bob.nodeParams.nodeId
    val peer: TestFSMRef[Peer.State, Peer.Data, Peer] = TestFSMRef(new Peer(aliceParams, remoteNodeId, authenticator.ref, watcher.ref, router.ref, relayer.ref, wallet))
    withFixture(test.toNoArgTest(FixtureParam(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)))
  }

  def connect(remoteNodeId: PublicKey, authenticator: TestProbe, watcher: TestProbe, router: TestProbe, relayer: TestProbe, connection: TestProbe, transport: TestProbe, peer: ActorRef, channels: Set[HasCommitments] = Set.empty): Unit = {
    // let's simulate a connection
    val probe = TestProbe()
    probe.send(peer, Peer.Init(None, channels))
    authenticator.send(peer, Authenticator.Authenticated(connection.ref, transport.ref, remoteNodeId, fakeIPAddress.socketAddress, outgoing = true, None))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[wire.Init]
    transport.send(peer, wire.Init(Bob.nodeParams.globalFeatures, Bob.nodeParams.localFeatures))
    transport.expectMsgType[TransportHandler.ReadAck]
    router.expectNoMsg(1 second) // bob's features require no sync
    probe.send(peer, Peer.GetPeerInfo)
    assert(probe.expectMsgType[Peer.PeerInfo].state == "CONNECTED")
  }

  test("restore existing channels") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer, channels = Set(ChannelStateSpec.normal))
    probe.send(peer, Peer.GetPeerInfo)
    probe.expectMsg(PeerInfo(remoteNodeId, "CONNECTED", Some(fakeIPAddress.socketAddress), 1))
  }

  test("fail to connect if no address provided or found") { f =>
    import f._

    val probe = TestProbe()
    val monitor = TestProbe()

    peer ! SubscribeTransitionCallBack(monitor.ref)

    probe.send(peer, Peer.Init(None, Set.empty))
    val CurrentState(_, INSTANTIATING) = monitor.expectMsgType[CurrentState[_]]
    val Transition(_, INSTANTIATING, DISCONNECTED) = monitor.expectMsgType[Transition[_]]
    probe.send(peer, Peer.Connect(remoteNodeId, address_opt = None))
    probe.expectMsg(s"Unable to connect to $remoteNodeId no address found")
    //monitor.expectMsgType[Transition[_]]
  }

  test("if no address was specified during connection use the one from node_announcement", Tag("with_node_announcements")) { f =>
    import f._

    val probe = TestProbe()
    val monitor = TestProbe()

    peer ! SubscribeTransitionCallBack(monitor.ref)

    probe.send(peer, Peer.Init(None, Set.empty))
    val CurrentState(_, INSTANTIATING) = monitor.expectMsgType[CurrentState[_]]
    val Transition(_, INSTANTIATING, DISCONNECTED) = monitor.expectMsgType[Transition[_]]

    probe.send(peer, Peer.Connect(remoteNodeId, None))
    awaitCond(peer.stateData.address_opt == Some(fakeIPAddress.socketAddress))
  }

  test("ignore connect to same address") { f =>
    import f._
    val probe = TestProbe()
    val previouslyKnownAddress = new InetSocketAddress("1.2.3.4", 9735)
    probe.send(peer, Peer.Init(Some(previouslyKnownAddress), Set.empty))
    probe.send(peer, Peer.Connect(NodeURI.parse("03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134@1.2.3.4:9735")))
    probe.expectMsg("reconnection in progress")
  }

  test("ignore reconnect (no known address)") { f =>
    import f._
    val probe = TestProbe()
    probe.send(peer, Peer.Init(None, Set(ChannelStateSpec.normal)))
    probe.send(peer, Peer.Reconnect)
    probe.expectNoMsg()
  }

  test("ignore reconnect (no channel)") { f =>
    import f._
    val probe = TestProbe()
    val previouslyKnownAddress = new InetSocketAddress("1.2.3.4", 9735)
    probe.send(peer, Peer.Init(Some(previouslyKnownAddress), Set.empty))
    probe.send(peer, Peer.Reconnect)
    probe.expectNoMsg()
  }

  test("reconnect using the address from node_announcement", Tag("with_node_announcements")) { f =>
    import f._

    val probe = TestProbe()
    awaitCond({peer.stateName.toString == "INSTANTIATING"}, 10 seconds)
    probe.send(peer, Peer.Init(None, Set(ChannelStateSpec.normal)))
    awaitCond({peer.stateName.toString == "DISCONNECTED" && peer.stateData.address_opt.isEmpty}, 10 seconds)
    EventFilter.info(message = s"reconnecting to ${fakeIPAddress.socketAddress}", occurrences = 1) intercept {
      probe.send(peer, Peer.Reconnect)
    }
  }

  test("count reconnections") { f =>
    import f._
    val probe = TestProbe()
    val previouslyKnownAddress = new InetSocketAddress("1.2.3.4", 9735)
    probe.send(peer, Peer.Init(Some(previouslyKnownAddress), Set(ChannelStateSpec.normal)))
    probe.send(peer, Peer.Reconnect)
    awaitCond(peer.stateData.asInstanceOf[DisconnectedData].attempts == 1)
    probe.send(peer, Peer.Reconnect)
    awaitCond(peer.stateData.asInstanceOf[DisconnectedData].attempts == 2)
    probe.send(peer, Peer.Reconnect)
    awaitCond(peer.stateData.asInstanceOf[DisconnectedData].attempts == 3)
  }

  test("disconnect if incompatible features") { f =>
    import f._
    val probe = TestProbe()
    probe.watch(transport.ref)
    probe.send(peer, Peer.Init(None, Set.empty))
    authenticator.send(peer, Authenticator.Authenticated(connection.ref, transport.ref, remoteNodeId, new InetSocketAddress("1.2.3.4", 42000), outgoing = true, None))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[wire.Init]
    import scodec.bits._
    transport.send(peer, wire.Init(Bob.nodeParams.globalFeatures, bin"01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00".toByteVector))
    transport.expectMsgType[TransportHandler.ReadAck]
    probe.expectTerminated(transport.ref)
  }

  test("reply to ping") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)
    val ping = Ping(42, randomBytes(127))
    probe.send(peer, ping)
    transport.expectMsg(TransportHandler.ReadAck(ping))
    assert(transport.expectMsgType[Pong].data.size === ping.pongLength)
  }

  test("ignore malicious ping") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)
    // huge requested pong length
    val ping = Ping(Int.MaxValue, randomBytes(127))
    probe.send(peer, ping)
    transport.expectMsg(TransportHandler.ReadAck(ping))
    transport.expectNoMsg()
  }

  test("disconnect if no reply to ping") { f =>
    import f._
    val sender = TestProbe()
    val deathWatcher = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)
    // we manually trigger a ping because we don't want to wait too long in tests
    sender.send(peer, SendPing)
    transport.expectMsgType[Ping]
    deathWatcher.watch(transport.ref)
    deathWatcher.expectTerminated(transport.ref, max = 11 seconds)
  }

  test("filter gossip message (no filtering)") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)
    val rebroadcast = Rebroadcast(channels.map(_ -> Set.empty[ActorRef]).toMap, updates.map(_ -> Set.empty[ActorRef]).toMap, nodes.map(_ -> Set.empty[ActorRef]).toMap)
    probe.send(peer, rebroadcast)
    transport.expectNoMsg(2 seconds)
  }

  test("filter gossip message (filtered by origin)") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)
    val peerActor: ActorRef = peer
    val rebroadcast = Rebroadcast(
      channels.map(_ -> Set.empty[ActorRef]).toMap + (channels(5) -> Set(peerActor)),
      updates.map(_ -> Set.empty[ActorRef]).toMap + (updates(6) -> Set(peerActor)) + (updates(10) -> Set(peerActor)),
      nodes.map(_ -> Set.empty[ActorRef]).toMap + (nodes(4) -> Set(peerActor)))
    val filter = wire.GossipTimestampFilter(Alice.nodeParams.chainHash, 0, Long.MaxValue) // no filtering on timestamps
    probe.send(peer, filter)
    probe.send(peer, rebroadcast)
    // peer won't send out announcements that came from itself
    (channels.toSet - channels(5)).foreach(transport.expectMsg(_))
    (updates.toSet - updates(6) - updates(10)).foreach(transport.expectMsg(_))
    (nodes.toSet - nodes(4)).foreach(transport.expectMsg(_))
  }

  test("filter gossip message (filtered by timestamp)") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)
    val rebroadcast = Rebroadcast(channels.map(_ -> Set.empty[ActorRef]).toMap, updates.map(_ -> Set.empty[ActorRef]).toMap, nodes.map(_ -> Set.empty[ActorRef]).toMap)
    val timestamps = updates.map(_.timestamp).sorted.drop(10).take(20)
    val filter = wire.GossipTimestampFilter(Alice.nodeParams.chainHash, timestamps.head, timestamps.last - timestamps.head)
    probe.send(peer, filter)
    probe.send(peer, rebroadcast)
    // peer doesn't filter channel announcements
    channels.foreach(transport.expectMsg(10 seconds, _))
    // but it will only send updates and node announcements matching the filter
    updates.filter(u => timestamps.contains(u.timestamp)).foreach(transport.expectMsg(_))
    nodes.filter(u => timestamps.contains(u.timestamp)).foreach(transport.expectMsg(_))
  }

  test("react to peer's bad behavior") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)

    val query = wire.QueryShortChannelIds(Alice.nodeParams.chainHash, ChannelRangeQueries.encodeShortChannelIdsSingle(Seq(ShortChannelId(42000)), ChannelRangeQueries.UNCOMPRESSED_FORMAT, useGzip = false))

    // make sure that routing messages go through
    for (ann <- channels ++ updates) {
      transport.send(peer, ann)
      router.expectMsg(Peer.PeerRoutingMessage(transport.ref, remoteNodeId, ann))
    }
    transport.expectNoMsg(1 second) // peer hasn't acknowledged the messages

    // let's assume that the router isn't happy with those channels because the funding tx is already spent
    for (c <- channels) {
      router.send(peer, Peer.ChannelClosed(c))
    }
    // peer will temporary ignore announcements coming from bob
    for (ann <- channels ++ updates) {
      transport.send(peer, ann)
      transport.expectMsg(TransportHandler.ReadAck(ann))
    }
    router.expectNoMsg(1 second)
    // other routing messages go through
    transport.send(peer, query)
    router.expectMsg(Peer.PeerRoutingMessage(transport.ref, remoteNodeId, query))

    // after a while the ban is lifted
    probe.send(peer, ResumeAnnouncements)

    // and announcements are processed again
    for (ann <- channels ++ updates) {
      transport.send(peer, ann)
      router.expectMsg(Peer.PeerRoutingMessage(transport.ref, remoteNodeId, ann))
    }
    transport.expectNoMsg(1 second) // peer hasn't acknowledged the messages

    // now let's assume that the router isn't happy with those channels because the announcement is invalid
    router.send(peer, Peer.InvalidAnnouncement(channels(0)))
    // peer will return a connection-wide error, including the hex-encoded representation of the bad message
    val error1 = transport.expectMsgType[Error]
    assert(error1.channelId === CHANNELID_ZERO)
    assert(new String(error1.data.toArray).startsWith("couldn't verify channel! shortChannelId="))


    // let's assume that one of the sigs were invalid
    router.send(peer, Peer.InvalidSignature(channels(0)))
    // peer will return a connection-wide error, including the hex-encoded representation of the bad message
    val error2 = transport.expectMsgType[Error]
    assert(error2.channelId === CHANNELID_ZERO)
    assert(new String(error2.data.toArray).startsWith("bad announcement sig! bin=0100"))
  }

}
