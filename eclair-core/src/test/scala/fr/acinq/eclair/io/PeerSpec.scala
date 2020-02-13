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

import java.net.{Inet4Address, InetAddress, InetSocketAddress, ServerSocket}

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.{ActorRef, PoisonPill}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, Crypto}
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{EclairWallet, TestWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer._
import fr.acinq.eclair.router.RoutingSyncSpec.makeFakeRoutingInfo
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.{ChannelCodecsSpec, Color, EncodedShortChannelIds, EncodingType, Error, IPv4, InitTlv, LightningMessageCodecs, NodeAddress, NodeAnnouncement, Ping, Pong, QueryShortChannelIds, TlvStream}
import org.scalatest.{Outcome, Tag}
import scodec.bits.{ByteVector, _}

import scala.concurrent.duration._

class PeerSpec extends TestkitBaseClass with StateTestsHelperMethods {

  def ipv4FromInet4(address: InetSocketAddress) = IPv4.apply(address.getAddress.asInstanceOf[Inet4Address], address.getPort)

  val fakeIPAddress = NodeAddress.fromParts("1.2.3.4", 42000).get
  val shortChannelIds = RoutingSyncSpec.shortChannelIds.take(100)
  val fakeRoutingInfo = shortChannelIds.map(makeFakeRoutingInfo)
  val channels = fakeRoutingInfo.map(_._1.ann).toList
  val updates = (fakeRoutingInfo.flatMap(_._1.update_1_opt) ++ fakeRoutingInfo.flatMap(_._1.update_2_opt)).toList
  val nodes = (fakeRoutingInfo.map(_._1.ann.nodeId1) ++ fakeRoutingInfo.map(_._1.ann.nodeId2)).map(RoutingSyncSpec.makeFakeNodeAnnouncement).toList

  case class FixtureParam(remoteNodeId: PublicKey, authenticator: TestProbe, watcher: TestProbe, router: TestProbe, relayer: TestProbe, connection: TestProbe, transport: TestProbe, peer: TestFSMRef[Peer.State, Peer.Data, Peer])

  override protected def withFixture(test: OneArgTest): Outcome = {
    val authenticator = TestProbe()
    val watcher = TestProbe()
    val router = TestProbe()
    val relayer = TestProbe()
    val connection = TestProbe()
    val transport = TestProbe()
    val paymentHandler = TestProbe()
    val wallet: EclairWallet = new TestWallet()
    val remoteNodeId = Bob.nodeParams.nodeId

    import com.softwaremill.quicklens._
    val aliceParams = TestConstants.Alice.nodeParams
      .modify(_.syncWhitelist).setToIf(test.tags.contains("sync-whitelist-bob"))(Set(remoteNodeId))
      .modify(_.syncWhitelist).setToIf(test.tags.contains("sync-whitelist-random"))(Set(randomKey.publicKey))

    if (test.tags.contains("with_node_announcements")) {
      val bobAnnouncement = NodeAnnouncement(randomBytes64, ByteVector.empty, 1, Bob.nodeParams.nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", fakeIPAddress :: Nil)
      aliceParams.db.network.addNode(bobAnnouncement)
    }

    val peer: TestFSMRef[Peer.State, Peer.Data, Peer] = TestFSMRef(new Peer(aliceParams, remoteNodeId, authenticator.ref, watcher.ref, router.ref, relayer.ref, paymentHandler.ref, wallet))
    withFixture(test.toNoArgTest(FixtureParam(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)))
  }

  def connect(remoteNodeId: PublicKey, authenticator: TestProbe, watcher: TestProbe, router: TestProbe, relayer: TestProbe, connection: TestProbe, transport: TestProbe, peer: ActorRef, channels: Set[HasCommitments] = Set.empty, remoteInit: wire.Init = wire.Init(Bob.nodeParams.features), expectSync: Boolean = false): Unit = {
    // let's simulate a connection
    val probe = TestProbe()
    probe.send(peer, Peer.Init(None, channels))
    authenticator.send(peer, Authenticator.Authenticated(connection.ref, transport.ref, remoteNodeId, fakeIPAddress.socketAddress, outgoing = true, None))
    transport.expectMsgType[TransportHandler.Listener]
    val localInit = transport.expectMsgType[wire.Init]
    assert(localInit.networks === List(Block.RegtestGenesisBlock.hash))
    transport.send(peer, remoteInit)
    transport.expectMsgType[TransportHandler.ReadAck]
    if (expectSync) {
      router.expectMsgType[SendChannelQuery]
    } else {
      router.expectNoMsg(1 second)
    }
    probe.send(peer, Peer.GetPeerInfo)
    assert(probe.expectMsgType[Peer.PeerInfo].state == "CONNECTED")
  }

  test("restore existing channels") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer, channels = Set(ChannelCodecsSpec.normal))
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
    probe.expectMsg(s"no address found")
  }

  // On Android we don't store node announcements
  ignore("if no address was specified during connection use the one from node_announcement", Tag("with_node_announcements")) { f =>
    import f._

    val probe = TestProbe()
    val monitor = TestProbe()

    peer ! SubscribeTransitionCallBack(monitor.ref)

    probe.send(peer, Peer.Init(None, Set.empty))
    val CurrentState(_, INSTANTIATING) = monitor.expectMsgType[CurrentState[_]]
    val Transition(_, INSTANTIATING, DISCONNECTED) = monitor.expectMsgType[Transition[_]]

    probe.send(peer, Peer.Connect(remoteNodeId, None))
    awaitCond(peer.stateData.address_opt === Some(fakeIPAddress.socketAddress))
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
    probe.send(peer, Peer.Init(None, Set(ChannelCodecsSpec.normal)))
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

  test("reconnect using the address from node_announcement") { f =>
    import f._

    // we create a dummy tcp server and update bob's announcement to point to it
    val mockServer = new ServerSocket(0, 1, InetAddress.getLocalHost) // port will be assigned automatically
    val mockAddress = NodeAddress.fromParts(mockServer.getInetAddress.getHostAddress, mockServer.getLocalPort).get
    val bobAnnouncement = NodeAnnouncement(randomBytes64, ByteVector.empty, 1, Bob.nodeParams.nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", mockAddress :: Nil)
    peer.underlyingActor.nodeParams.db.network.addNode(bobAnnouncement)

    val probe = TestProbe()
    awaitCond(peer.stateName == INSTANTIATING)
    probe.send(peer, Peer.Init(None, Set(ChannelCodecsSpec.normal)))
    awaitCond(peer.stateName == DISCONNECTED)

    // we have auto-reconnect=false so we need to manually tell the peer to reconnect
    probe.send(peer, Reconnect)

    // assert our mock server got an incoming connection (the client was spawned with the address from node_announcement)
    within(30 seconds) {
      mockServer.accept()
    }
  }

  test("only reconnect once with a randomized delay after startup") { f =>
    import f._
    val probe = TestProbe()
    val previouslyKnownAddress = new InetSocketAddress("1.2.3.4", 9735)
    probe.send(peer, Peer.Init(Some(previouslyKnownAddress), Set(ChannelCodecsSpec.normal)))
    probe.send(peer, Peer.Reconnect)
    val interval = (peer.underlyingActor.nodeParams.maxReconnectInterval.toSeconds / 2) to peer.underlyingActor.nodeParams.maxReconnectInterval.toSeconds
    awaitCond(interval contains peer.stateData.asInstanceOf[DisconnectedData].nextReconnectionDelay.toSeconds)
  }

  test("reconnect with increasing delays") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer, channels = Set(ChannelCodecsSpec.normal))
    probe.send(transport.ref, PoisonPill)
    awaitCond(peer.stateName === DISCONNECTED)
    val initialReconnectDelay = peer.stateData.asInstanceOf[DisconnectedData].nextReconnectionDelay
    assert(initialReconnectDelay >= (200 milliseconds))
    assert(initialReconnectDelay <= (10 seconds))
    probe.send(peer, Reconnect)
    assert(peer.stateData.asInstanceOf[DisconnectedData].nextReconnectionDelay === (initialReconnectDelay * 2))
    probe.send(peer, Reconnect)
    assert(peer.stateData.asInstanceOf[DisconnectedData].nextReconnectionDelay === (initialReconnectDelay * 4))
  }

  test("disconnect if incompatible local features") { f =>
    import f._
    val probe = TestProbe()
    probe.watch(transport.ref)
    probe.send(peer, Peer.Init(None, Set.empty))
    authenticator.send(peer, Authenticator.Authenticated(connection.ref, transport.ref, remoteNodeId, new InetSocketAddress("1.2.3.4", 42000), outgoing = true, None))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[wire.Init]
    transport.send(peer, LightningMessageCodecs.initCodec.decode(hex"0000 00050100000000".bits).require.value)
    transport.expectMsgType[TransportHandler.ReadAck]
    probe.expectTerminated(transport.ref)
  }

  test("disconnect if incompatible global features") { f =>
    import f._
    val probe = TestProbe()
    probe.watch(transport.ref)
    probe.send(peer, Peer.Init(None, Set.empty))
    authenticator.send(peer, Authenticator.Authenticated(connection.ref, transport.ref, remoteNodeId, new InetSocketAddress("1.2.3.4", 42000), outgoing = true, None))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[wire.Init]
    transport.send(peer, LightningMessageCodecs.initCodec.decode(hex"00050100000000 0000".bits).require.value)
    transport.expectMsgType[TransportHandler.ReadAck]
    probe.expectTerminated(transport.ref)
  }

  test("masks off MPP and PaymentSecret features") { f =>
    import f._
    val wallet = new TestWallet
    val probe = TestProbe()

    val testCases = Seq(
      (bin"                00000010", bin"                00000010"), // option_data_loss_protect
      (bin"        0000101010001010", bin"        0000101010001010"), // option_data_loss_protect, initial_routing_sync, gossip_queries, var_onion_optin, gossip_queries_ex
      (bin"        1000101010001010", bin"        0000101010001010"), // option_data_loss_protect, initial_routing_sync, gossip_queries, var_onion_optin, gossip_queries_ex, payment_secret
      (bin"        0100101010001010", bin"        0000101010001010"), // option_data_loss_protect, initial_routing_sync, gossip_queries, var_onion_optin, gossip_queries_ex, payment_secret
      (bin"000000101000101010001010", bin"        0000101010001010"), // option_data_loss_protect, initial_routing_sync, gossip_queries, var_onion_optin, gossip_queries_ex, payment_secret, basic_mpp
      (bin"000010101000101010001010", bin"000010000000101010001010") // option_data_loss_protect, initial_routing_sync, gossip_queries, var_onion_optin, gossip_queries_ex, payment_secret, basic_mpp and 19
    )

    for ((configuredFeatures, sentFeatures) <- testCases) {
      val nodeParams = TestConstants.Alice.nodeParams.copy(features = configuredFeatures.bytes)
      val peer = TestFSMRef(new Peer(nodeParams, remoteNodeId, authenticator.ref, watcher.ref, router.ref, relayer.ref, TestProbe().ref, wallet))
      probe.send(peer, Peer.Init(None, Set.empty))
      authenticator.send(peer, Authenticator.Authenticated(connection.ref, transport.ref, remoteNodeId, new InetSocketAddress("1.2.3.4", 42000), outgoing = true, None))
      transport.expectMsgType[TransportHandler.Listener]
      val init = transport.expectMsgType[wire.Init]
      assert(init.features === sentFeatures.bytes)
    }
  }

  test("disconnect if incompatible networks") { f =>
    import f._
    val probe = TestProbe()
    probe.watch(transport.ref)
    probe.send(peer, Peer.Init(None, Set.empty))
    authenticator.send(peer, Authenticator.Authenticated(connection.ref, transport.ref, remoteNodeId, new InetSocketAddress("1.2.3.4", 42000), outgoing = true, None))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[wire.Init]
    transport.send(peer, wire.Init(Bob.nodeParams.features, TlvStream(InitTlv.Networks(Block.LivenetGenesisBlock.hash :: Block.SegnetGenesisBlock.hash :: Nil))))
    transport.expectMsgType[TransportHandler.ReadAck]
    probe.expectTerminated(transport.ref)
  }

  test("handle disconnect in status INITIALIZING") { f =>
    import f._

    val probe = TestProbe()
    probe.send(peer, Peer.Init(None, Set(ChannelCodecsSpec.normal)))
    authenticator.send(peer, Authenticator.Authenticated(connection.ref, transport.ref, remoteNodeId, fakeIPAddress.socketAddress, outgoing = true, None))

    probe.send(peer, Peer.GetPeerInfo)
    assert(probe.expectMsgType[Peer.PeerInfo].state == "INITIALIZING")

    probe.send(peer, Peer.Disconnect(f.remoteNodeId))
    probe.expectMsg("disconnecting")
  }

  test("handle disconnect in status CONNECTED") { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer, channels = Set(ChannelCodecsSpec.normal))

    probe.send(peer, Peer.GetPeerInfo)
    assert(probe.expectMsgType[Peer.PeerInfo].state == "CONNECTED")

    probe.send(peer, Peer.Disconnect(f.remoteNodeId))
    probe.expectMsg("disconnecting")
  }

  test("use correct fee rates when spawning a channel") { f =>
    import f._

    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 12300 sat, 0 msat, None, None, None))
    awaitCond(peer.stateData.channels.nonEmpty)

    val channelCreated = probe.expectMsgType[ChannelCreated]
    assert(channelCreated.initialFeeratePerKw == peer.feeEstimator.getFeeratePerKw(peer.feeTargets.commitmentBlockTarget))
    assert(channelCreated.fundingTxFeeratePerKw.get == peer.feeEstimator.getFeeratePerKw(peer.feeTargets.fundingBlockTarget))
  }

  // ignored on Android
  ignore("sync if no whitelist is defined") { f =>
    import f._
    val remoteInit = wire.Init(bin"10000000".bytes) // bob supports channel range queries
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer, Set.empty, remoteInit, expectSync = true)
  }

  // ignored on Android
  ignore("sync if whitelist contains peer", Tag("sync-whitelist-bob")) { f =>
    import f._
    val remoteInit = wire.Init(bin"0000001010000000".bytes) // bob supports channel range queries and variable length onion
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer, Set.empty, remoteInit, expectSync = true)
  }

  test("don't sync if whitelist doesn't contain peer", Tag("sync-whitelist-random")) { f =>
    import f._
    val remoteInit = wire.Init(bin"0000001010000000".bytes) // bob supports channel range queries
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer, Set.empty, remoteInit, expectSync = false)
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
    val gossipOrigin = Set[GossipOrigin](RemoteGossip(TestProbe().ref))
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)
    val rebroadcast = Rebroadcast(channels.map(_ -> gossipOrigin).toMap, updates.map(_ -> gossipOrigin).toMap, nodes.map(_ -> gossipOrigin).toMap)
    probe.send(peer, rebroadcast)
    transport.expectNoMsg(10 seconds)
  }

  test("filter gossip message (filtered by origin)") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)
    val gossipOrigin = Set[GossipOrigin](RemoteGossip(TestProbe().ref))
    val peerActor: ActorRef = peer
    val rebroadcast = Rebroadcast(
      channels.map(_ -> gossipOrigin).toMap + (channels(5) -> Set(RemoteGossip(peerActor))),
      updates.map(_ -> gossipOrigin).toMap + (updates(6) -> (gossipOrigin + RemoteGossip(peerActor))) + (updates(10) -> Set(RemoteGossip(peerActor))),
      nodes.map(_ -> gossipOrigin).toMap + (nodes(4) -> Set(RemoteGossip(peerActor))))
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
    val gossipOrigin = Set[GossipOrigin](RemoteGossip(TestProbe().ref))
    val rebroadcast = Rebroadcast(channels.map(_ -> gossipOrigin).toMap, updates.map(_ -> gossipOrigin).toMap, nodes.map(_ -> gossipOrigin).toMap)
    val timestamps = updates.map(_.timestamp).sorted.slice(10, 30)
    val filter = wire.GossipTimestampFilter(Alice.nodeParams.chainHash, timestamps.head, timestamps.last - timestamps.head)
    probe.send(peer, filter)
    probe.send(peer, rebroadcast)
    // peer doesn't filter channel announcements
    channels.foreach(transport.expectMsg(10 seconds, _))
    // but it will only send updates and node announcements matching the filter
    updates.filter(u => timestamps.contains(u.timestamp)).foreach(transport.expectMsg(_))
    nodes.filter(u => timestamps.contains(u.timestamp)).foreach(transport.expectMsg(_))
  }

  test("does not filter our own gossip message") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)
    val gossipOrigin = Set[GossipOrigin](RemoteGossip(TestProbe().ref))
    val rebroadcast = Rebroadcast(
      channels.map(_ -> gossipOrigin).toMap + (channels(5) -> Set(LocalGossip)),
      updates.map(_ -> gossipOrigin).toMap + (updates(6) -> (gossipOrigin + LocalGossip)) + (updates(10) -> Set(LocalGossip)),
      nodes.map(_ -> gossipOrigin).toMap + (nodes(4) -> Set(LocalGossip)))
    // No timestamp filter set -> the only gossip we should broadcast is our own.
    probe.send(peer, rebroadcast)
    transport.expectMsg(channels(5))
    transport.expectMsg(updates(6))
    transport.expectMsg(updates(10))
    transport.expectMsg(nodes(4))
    transport.expectNoMsg(10 seconds)
  }

  test("react to peer's bad behavior") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, authenticator, watcher, router, relayer, connection, transport, peer)

    val query = QueryShortChannelIds(
      Alice.nodeParams.chainHash,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(ShortChannelId(42000))),
      TlvStream.empty)

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

object PeerSpec {

  val preimage = randomBytes32
  val paymentHash = Crypto.sha256(preimage)

}