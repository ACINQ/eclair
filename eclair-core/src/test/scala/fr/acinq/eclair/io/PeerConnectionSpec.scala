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

import akka.actor.{ActorRef, PoisonPill}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.PeerConnection.PeerConnectionConf
import fr.acinq.eclair.router.{RoutingSyncSpec, _}
import fr.acinq.eclair.wire._
import org.scalatest.{Outcome, Tag}
import scodec.bits._

import scala.collection.mutable
import scala.concurrent.duration._

class PeerConnectionSpec extends TestkitBaseClass with StateTestsHelperMethods {

  def ipv4FromInet4(address: InetSocketAddress) = IPv4.apply(address.getAddress.asInstanceOf[Inet4Address], address.getPort)

  val address = new InetSocketAddress("localhost", 42000)
  val fakeIPAddress = NodeAddress.fromParts("1.2.3.4", 42000).get
  // this map will store private keys so that we can sign new announcements at will
  val pub2priv: mutable.Map[PublicKey, PrivateKey] = mutable.HashMap.empty
  val shortChannelIds = RoutingSyncSpec.shortChannelIds.take(100)
  val fakeRoutingInfo = shortChannelIds.map(RoutingSyncSpec.makeFakeRoutingInfo(pub2priv))
  val channels = fakeRoutingInfo.map(_._1.ann).toList
  val updates = (fakeRoutingInfo.flatMap(_._1.update_1_opt) ++ fakeRoutingInfo.flatMap(_._1.update_2_opt)).toList
  val nodes = (fakeRoutingInfo.map(_._1.ann.nodeId1) ++ fakeRoutingInfo.map(_._1.ann.nodeId2)).map(RoutingSyncSpec.makeFakeNodeAnnouncement(pub2priv)).toList

  case class FixtureParam(conf: PeerConnectionConf, remoteNodeId: PublicKey, switchboard: TestProbe, router: TestProbe, connection: TestProbe, transport: TestProbe, peerConnection: TestFSMRef[PeerConnection.State, PeerConnection.Data, PeerConnection], peer: TestProbe)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val switchboard = TestProbe()
    val router = TestProbe()
    val connection = TestProbe()
    val transport = TestProbe()
    val peer = TestProbe()
    val remoteNodeId = Bob.nodeParams.nodeId

    import com.softwaremill.quicklens._
    val aliceConf = TestConstants.Alice.nodeParams.peerConnectionConf
      .modify(_.syncWhitelist).setToIf(test.tags.contains("sync-whitelist-bob"))(Set(remoteNodeId))
      .modify(_.syncWhitelist).setToIf(test.tags.contains("sync-whitelist-random"))(Set(randomKey.publicKey))

    val peerConnection: TestFSMRef[PeerConnection.State, PeerConnection.Data, PeerConnection] = TestFSMRef(new PeerConnection(TestConstants.Alice.nodeParams.keyPair, aliceConf, switchboard.ref, router.ref))
    withFixture(test.toNoArgTest(FixtureParam(aliceConf, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)))
  }

  def connect(remoteNodeId: PublicKey, switchboard: TestProbe, router: TestProbe, connection: TestProbe, transport: TestProbe, peerConnection: TestFSMRef[PeerConnection.State, PeerConnection.Data, PeerConnection], peer: TestProbe, remoteInit: wire.Init = wire.Init(Bob.nodeParams.features), expectSync: Boolean = false): Unit = {
    // let's simulate a connection
    val probe = TestProbe()
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = None, transport_opt = Some(transport.ref)))
    transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
    switchboard.expectMsg(PeerConnection.Authenticated(peerConnection, remoteNodeId, address, outgoing = true, origin_opt = None))
    probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref))
    transport.expectMsgType[TransportHandler.Listener]
    val localInit = transport.expectMsgType[wire.Init]
    assert(localInit.networks === List(Block.RegtestGenesisBlock.hash))
    transport.send(peerConnection, remoteInit)
    transport.expectMsgType[TransportHandler.ReadAck]
    if (expectSync) {
      router.expectMsgType[SendChannelQuery]
    } else {
      router.expectNoMsg(1 second)
    }
    peer.expectMsg(PeerConnection.ConnectionReady(peerConnection, remoteNodeId, address, outgoing = true, localInit, remoteInit))
    assert(peerConnection.stateName === PeerConnection.CONNECTED)
  }

  test("establish connection") { f =>
    import f._
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
  }

  test("handle connection closed during authentication") { f =>
    import f._
    val probe = TestProbe()
    probe.watch(peerConnection)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = None, transport_opt = Some(transport.ref)))
    transport.ref ! PoisonPill
    probe.expectTerminated(peerConnection, 100 millis)
  }

  test("disconnect if authentication timeout") { f =>
    import f._
    val probe = TestProbe()
    probe.watch(peerConnection)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = None, transport_opt = Some(transport.ref)))
    probe.expectTerminated(peerConnection, conf.authTimeout / transport.testKitSettings.TestTimeFactor  + 1.second) // we don't want dilated time here
  }

  test("disconnect if init timeout") { f =>
    import f._
    val probe = TestProbe()
    probe.watch(peerConnection)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = None, transport_opt = Some(transport.ref)))
    transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
    probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref))
    probe.expectTerminated(peerConnection, conf.initTimeout / transport.testKitSettings.TestTimeFactor  + 1.second) // we don't want dilated time here
  }

  test("disconnect if incompatible local features") { f =>
    import f._
    val probe = TestProbe()
    probe.watch(transport.ref)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = None, transport_opt = Some(transport.ref)))
    transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
    probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[wire.Init]
    transport.send(peerConnection, LightningMessageCodecs.initCodec.decode(hex"0000 00050100000000".bits).require.value)
    transport.expectMsgType[TransportHandler.ReadAck]
    probe.expectTerminated(transport.ref)
  }

  test("disconnect if incompatible global features") { f =>
    import f._
    val probe = TestProbe()
    probe.watch(transport.ref)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = None, transport_opt = Some(transport.ref)))
    transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
    probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[wire.Init]
    transport.send(peerConnection, LightningMessageCodecs.initCodec.decode(hex"00050100000000 0000".bits).require.value)
    transport.expectMsgType[TransportHandler.ReadAck]
    probe.expectTerminated(transport.ref)
  }

  test("masks off MPP and PaymentSecret features") { f =>
    import f._
    val probe = TestProbe()

    val testCases = Seq(
      (bin"                00000010", bin"                00000010"), // option_data_loss_protect
      (bin"        0000101010001010", bin"        0000101010001010"), // option_data_loss_protect, initial_routing_sync, gossip_queries, var_onion_optin, gossip_queries_ex
      (bin"        1000101010001010", bin"        0000101010001010"), // option_data_loss_protect, initial_routing_sync, gossip_queries, var_onion_optin, gossip_queries_ex, payment_secret
      (bin"        0100101010001010", bin"        0000101010001010"), // option_data_loss_protect, initial_routing_sync, gossip_queries, var_onion_optin, gossip_queries_ex, payment_secret
      (bin"000000101000101010001010", bin"        0000101010001010"), // option_data_loss_protect, initial_routing_sync, gossip_queries, var_onion_optin, gossip_queries_ex, payment_secret, basic_mpp
      (bin"000010101000101010001010", bin"000010000000101010001010") // option_data_loss_protect, initial_routing_sync, gossip_queries, var_onion_optin, gossip_queries_ex, payment_secret, basic_mpp and large_channel_support (optional)
    )

    for ((configuredFeatures, sentFeatures) <- testCases) {
      val conf = TestConstants.Alice.nodeParams.peerConnectionConf.copy(features = configuredFeatures.bytes)
      val peerConnection = TestFSMRef(new PeerConnection(TestConstants.Alice.nodeParams.keyPair, conf, switchboard.ref, router.ref))
      probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = None, transport_opt = Some(transport.ref)))
      transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
      probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref))
      transport.expectMsgType[TransportHandler.Listener]
      val init = transport.expectMsgType[wire.Init]
      assert(init.features === sentFeatures.bytes)
    }
  }

  test("disconnect if incompatible networks") { f =>
    import f._
    val probe = TestProbe()
    probe.watch(transport.ref)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = None, transport_opt = Some(transport.ref)))
    transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
    probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[wire.Init]
    transport.send(peerConnection, wire.Init(Bob.nodeParams.features, TlvStream(InitTlv.Networks(Block.LivenetGenesisBlock.hash :: Block.SegnetGenesisBlock.hash :: Nil))))
    transport.expectMsgType[TransportHandler.ReadAck]
    probe.expectTerminated(transport.ref)
  }

  test("sync if no whitelist is defined") { f =>
    import f._
    val remoteInit = wire.Init(bin"10000000".bytes) // bob supports channel range queries
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer, remoteInit, expectSync = true)
  }

  test("sync if whitelist contains peer", Tag("sync-whitelist-bob")) { f =>
    import f._
    val remoteInit = wire.Init(bin"0000001010000000".bytes) // bob supports channel range queries and variable length onion
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer, remoteInit, expectSync = true)
  }

  test("don't sync if whitelist doesn't contain peer", Tag("sync-whitelist-random")) { f =>
    import f._
    val remoteInit = wire.Init(bin"0000001010000000".bytes) // bob supports channel range queries
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer, remoteInit, expectSync = false)
  }

  test("reply to ping") { f =>
    import f._
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    val ping = Ping(42, randomBytes(127))
    transport.send(peerConnection, ping)
    transport.expectMsg(TransportHandler.ReadAck(ping))
    assert(transport.expectMsgType[Pong].data.size === ping.pongLength)
  }

  test("send a ping if no message after init") { f =>
    import f._
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    // ~30s without an incoming message: peer should send a ping
    transport.expectMsgType[Ping](35 / transport.testKitSettings.TestTimeFactor seconds) // we don't want dilated time here
  }

  test("send a ping if no message received for 30s") { f =>
    import f._
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    // we make the transport send a message, this will delay the sending of a ping
    val dummy = updates.head
    for (_ <- 1 to 5) { // the goal of this loop is to make sure that we don't send pings when we receive messages
      // we make the transport send a message, this will delay the sending of a ping --again
      transport.expectNoMsg(10 / transport.testKitSettings.TestTimeFactor seconds) // we don't want dilated time here
      transport.send(peerConnection, dummy)
    }
    // ~30s without an incoming message: peer should send a ping
    transport.expectMsgType[Ping](35 / transport.testKitSettings.TestTimeFactor seconds) // we don't want dilated time here
  }

  test("ignore malicious ping") { f =>
    import f._
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    // huge requested pong length
    val ping = Ping(Int.MaxValue, randomBytes(127))
    transport.send(peerConnection, ping)
    transport.expectMsg(TransportHandler.ReadAck(ping))
    transport.expectNoMsg()
  }

  test("disconnect if no reply to ping") { f =>
    import f._
    val sender = TestProbe()
    val deathWatcher = TestProbe()
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    // we manually trigger a ping because we don't want to wait too long in tests
    sender.send(peerConnection, PeerConnection.SendPing)
    transport.expectMsgType[Ping]
    deathWatcher.watch(transport.ref)
    deathWatcher.expectTerminated(transport.ref, max = 11 seconds)
  }

  test("filter gossip message (no filtering)") { f =>
    import f._
    val probe = TestProbe()
    val gossipOrigin = Set[GossipOrigin](RemoteGossip(TestProbe().ref))
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    val rebroadcast = Rebroadcast(channels.map(_ -> gossipOrigin).toMap, updates.map(_ -> gossipOrigin).toMap, nodes.map(_ -> gossipOrigin).toMap)
    probe.send(peerConnection, rebroadcast)
    transport.expectNoMsg(10 / transport.testKitSettings.TestTimeFactor seconds) // we don't want dilated time here
  }

  test("filter gossip message (filtered by origin)") { f =>
    import f._
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    val gossipOrigin = Set[GossipOrigin](RemoteGossip(TestProbe().ref))
    val pcActor: ActorRef = peerConnection
    val rebroadcast = Rebroadcast(
      channels.map(_ -> gossipOrigin).toMap + (channels(5) -> Set(RemoteGossip(pcActor))),
      updates.map(_ -> gossipOrigin).toMap + (updates(6) -> (gossipOrigin + RemoteGossip(pcActor))) + (updates(10) -> Set(RemoteGossip(pcActor))),
      nodes.map(_ -> gossipOrigin).toMap + (nodes(4) -> Set(RemoteGossip(pcActor))))
    val filter = wire.GossipTimestampFilter(Alice.nodeParams.chainHash, 0, Long.MaxValue) // no filtering on timestamps
    transport.send(peerConnection, filter)
    transport.expectMsg(TransportHandler.ReadAck(filter))
    transport.send(peerConnection, rebroadcast)
    // peer won't send out announcements that came from itself
    (channels.toSet - channels(5)).foreach(transport.expectMsg(_))
    (updates.toSet - updates(6) - updates(10)).foreach(transport.expectMsg(_))
    (nodes.toSet - nodes(4)).foreach(transport.expectMsg(_))
  }

  test("filter gossip message (filtered by timestamp)") { f =>
    import f._
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    val gossipOrigin = Set[GossipOrigin](RemoteGossip(TestProbe().ref))
    val rebroadcast = Rebroadcast(channels.map(_ -> gossipOrigin).toMap, updates.map(_ -> gossipOrigin).toMap, nodes.map(_ -> gossipOrigin).toMap)
    val timestamps = updates.map(_.timestamp).sorted.slice(10, 30)
    val filter = wire.GossipTimestampFilter(Alice.nodeParams.chainHash, timestamps.head, timestamps.last - timestamps.head)
    transport.send(peerConnection, filter)
    transport.expectMsg(TransportHandler.ReadAck(filter))
    transport.send(peerConnection, rebroadcast)
    // peer doesn't filter channel announcements
    channels.foreach(transport.expectMsg(10 seconds, _))
    // but it will only send updates and node announcements matching the filter
    updates.filter(u => timestamps.contains(u.timestamp)).foreach(transport.expectMsg(_))
    nodes.filter(u => timestamps.contains(u.timestamp)).foreach(transport.expectMsg(_))
  }

  test("does not filter our own gossip message") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    val gossipOrigin = Set[GossipOrigin](RemoteGossip(TestProbe().ref))
    val rebroadcast = Rebroadcast(
      channels.map(_ -> gossipOrigin).toMap + (channels(5) -> Set(LocalGossip)),
      updates.map(_ -> gossipOrigin).toMap + (updates(6) -> (gossipOrigin + LocalGossip)) + (updates(10) -> Set(LocalGossip)),
      nodes.map(_ -> gossipOrigin).toMap + (nodes(4) -> Set(LocalGossip)))
    // No timestamp filter set -> the only gossip we should broadcast is our own.
    probe.send(peerConnection, rebroadcast)
    transport.expectMsg(channels(5))
    transport.expectMsg(updates(6))
    transport.expectMsg(updates(10))
    transport.expectMsg(nodes(4))
    transport.expectNoMsg(10 / transport.testKitSettings.TestTimeFactor seconds) // we don't want dilated time here
  }

  test("react to peer's bad behavior") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)

    val query = QueryShortChannelIds(
      Alice.nodeParams.chainHash,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(42000))),
      TlvStream.empty)

    // make sure that routing messages go through
    for (ann <- channels ++ updates) {
      transport.send(peerConnection, ann)
      router.expectMsg(Peer.PeerRoutingMessage(peerConnection, remoteNodeId, ann))
    }
    transport.expectNoMsg(1 second) // peer hasn't acknowledged the messages

    // let's assume that the router isn't happy with those channels because the funding tx is already spent
    for (c <- channels) {
      router.send(peerConnection, PeerConnection.ChannelClosed(c))
    }
    // peer will temporary ignore announcements coming from bob
    for (ann <- channels ++ updates) {
      transport.send(peerConnection, ann)
      transport.expectMsg(TransportHandler.ReadAck(ann))
    }
    router.expectNoMsg(1 second)
    // other routing messages go through
    transport.send(peerConnection, query)
    router.expectMsg(Peer.PeerRoutingMessage(peerConnection, remoteNodeId, query))

    // after a while the ban is lifted
    probe.send(peerConnection, PeerConnection.ResumeAnnouncements)

    // and announcements are processed again
    for (ann <- channels ++ updates) {
      transport.send(peerConnection, ann)
      router.expectMsg(Peer.PeerRoutingMessage(peerConnection, remoteNodeId, ann))
    }
    transport.expectNoMsg(1 second) // peer hasn't acknowledged the messages

    // now let's assume that the router isn't happy with those channels because the announcement is invalid
    router.send(peerConnection, PeerConnection.InvalidAnnouncement(channels(0)))
    // peer will return a connection-wide error, including the hex-encoded representation of the bad message
    val error1 = transport.expectMsgType[Error]
    assert(error1.channelId === Peer.CHANNELID_ZERO)
    assert(new String(error1.data.toArray).startsWith("couldn't verify channel! shortChannelId="))

    // let's assume that one of the sigs were invalid
    router.send(peerConnection, PeerConnection.InvalidSignature(channels(0)))
    // peer will return a connection-wide error, including the hex-encoded representation of the bad message
    val error2 = transport.expectMsgType[Error]
    assert(error2.channelId === Peer.CHANNELID_ZERO)
    assert(new String(error2.data.toArray).startsWith("bad announcement sig! bin=0100"))
  }

}

