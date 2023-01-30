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

import akka.actor.PoisonPill
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{BasicMultiPartPayment, ChannelRangeQueries, PaymentSecret, VariableLengthOnion}
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.message.OnionMessages.{Recipient, buildMessage}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.router.RoutingSyncSpec
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{RealShortChannelId, _}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, ParallelTestExecution}
import scodec.bits._

import java.net.{Inet4Address, InetSocketAddress}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Success

class PeerConnectionSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ParallelTestExecution {

  def ipv4FromInet4(address: InetSocketAddress): IPv4 = IPv4.apply(address.getAddress.asInstanceOf[Inet4Address], address.getPort)

  val address = NodeAddress.fromParts("localhost", 42000).get
  val fakeIPAddress = NodeAddress.fromParts("1.2.3.4", 42000).get
  // this map will store private keys so that we can sign new announcements at will
  val pub2priv: mutable.Map[PublicKey, PrivateKey] = mutable.HashMap.empty
  val shortChannelIds = RoutingSyncSpec.shortChannelIds.take(100)
  val fakeRoutingInfo = shortChannelIds.unsorted.map(RoutingSyncSpec.makeFakeRoutingInfo(pub2priv))
  val channels = fakeRoutingInfo.map(_._1.ann).toList
  val updates = (fakeRoutingInfo.flatMap(_._1.update_1_opt) ++ fakeRoutingInfo.flatMap(_._1.update_2_opt)).toList
  val nodes = (fakeRoutingInfo.map(_._1.ann.nodeId1) ++ fakeRoutingInfo.map(_._1.ann.nodeId2)).map(RoutingSyncSpec.makeFakeNodeAnnouncement(pub2priv)).toList

  case class FixtureParam(nodeParams: NodeParams, remoteNodeId: PublicKey, switchboard: TestProbe, router: TestProbe, connection: TestProbe, transport: TestProbe, peerConnection: TestFSMRef[PeerConnection.State, PeerConnection.Data, PeerConnection], peer: TestProbe)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val switchboard = TestProbe()
    val router = TestProbe()
    val connection = TestProbe()
    val transport = TestProbe()
    val peer = TestProbe()
    val remoteNodeId = Bob.nodeParams.nodeId
    val aliceParams = TestConstants.Alice.nodeParams

    val peerConnection: TestFSMRef[PeerConnection.State, PeerConnection.Data, PeerConnection] = TestFSMRef(new PeerConnection(aliceParams.keyPair, aliceParams.peerConnectionConf, switchboard.ref, router.ref))
    withFixture(test.toNoArgTest(FixtureParam(aliceParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)))
  }

  def connect(aliceParams: NodeParams, remoteNodeId: PublicKey, switchboard: TestProbe, router: TestProbe, connection: TestProbe, transport: TestProbe, peerConnection: TestFSMRef[PeerConnection.State, PeerConnection.Data, PeerConnection], peer: TestProbe, remoteInit: protocol.Init = protocol.Init(Bob.nodeParams.features.initFeatures()), doSync: Boolean = false, isPersistent: Boolean = true): Unit = {
    // let's simulate a connection
    val probe = TestProbe()
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = None, transport_opt = Some(transport.ref), isPersistent = isPersistent))
    transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
    switchboard.expectMsg(PeerConnection.Authenticated(peerConnection, remoteNodeId))
    probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref, aliceParams.chainHash, aliceParams.features.initFeatures(), doSync))
    transport.expectMsgType[TransportHandler.Listener]
    val localInit = transport.expectMsgType[protocol.Init]
    assert(localInit.networks == List(Block.RegtestGenesisBlock.hash))
    transport.send(peerConnection, remoteInit)
    transport.expectMsgType[TransportHandler.ReadAck]
    if (doSync) {
      router.expectMsgType[SendChannelQuery]
    } else {
      router.expectNoMessage(1 second)
    }
    peer.expectMsg(PeerConnection.ConnectionReady(peerConnection, remoteNodeId, address, outgoing = true, localInit, remoteInit))
    assert(peerConnection.stateName == PeerConnection.CONNECTED)
  }

  test("establish connection") { f =>
    import f._
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
  }

  test("send incoming connection's remote address in init") { f =>
    import f._
    val probe = TestProbe()
    val incomingConnection = PeerConnection.PendingAuth(connection.ref, None, fakeIPAddress, origin_opt = None, transport_opt = Some(transport.ref), isPersistent = true)
    assert(!incomingConnection.outgoing)
    probe.send(peerConnection, incomingConnection)
    transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
    switchboard.expectMsg(PeerConnection.Authenticated(peerConnection, remoteNodeId))
    probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref, nodeParams.chainHash, nodeParams.features.initFeatures(), doSync = false))
    transport.expectMsgType[TransportHandler.Listener]
    val localInit = transport.expectMsgType[protocol.Init]
    assert(localInit.remoteAddress_opt == Some(fakeIPAddress))
  }

  test("handle connection closed during authentication") { f =>
    import f._
    val probe = TestProbe()
    probe.watch(peerConnection)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = None, transport_opt = Some(transport.ref), isPersistent = true))
    transport.ref ! PoisonPill
    probe.expectTerminated(peerConnection, 100 millis)
  }

  test("disconnect if authentication timeout") { f =>
    import f._
    val probe = TestProbe()
    val origin = TestProbe()
    probe.watch(peerConnection)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = Some(origin.ref), transport_opt = Some(transport.ref), isPersistent = true))
    probe.expectTerminated(peerConnection, nodeParams.peerConnectionConf.authTimeout / transport.testKitSettings.TestTimeFactor + 1.second) // we don't want dilated time here
    origin.expectMsg(PeerConnection.ConnectionResult.AuthenticationFailed("authentication timed out"))
  }

  test("disconnect if init timeout") { f =>
    import f._
    val probe = TestProbe()
    val origin = TestProbe()
    probe.watch(peerConnection)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = Some(origin.ref), transport_opt = Some(transport.ref), isPersistent = true))
    transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
    probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref, nodeParams.chainHash, nodeParams.features.initFeatures(), doSync = true))
    probe.expectTerminated(peerConnection, nodeParams.peerConnectionConf.initTimeout / transport.testKitSettings.TestTimeFactor + 1.second) // we don't want dilated time here
    origin.expectMsg(PeerConnection.ConnectionResult.InitializationFailed("initialization timed out"))
  }

  test("disconnect if incompatible local features") { f =>
    import f._
    val probe = TestProbe()
    val origin = TestProbe()
    probe.watch(transport.ref)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = Some(origin.ref), transport_opt = Some(transport.ref), isPersistent = true))
    transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
    probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref, nodeParams.chainHash, nodeParams.features.initFeatures(), doSync = true))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[protocol.Init]
    transport.send(peerConnection, LightningMessageCodecs.initCodec.decode(hex"0000 00050100000000".bits).require.value)
    transport.expectMsgType[TransportHandler.ReadAck]
    probe.expectTerminated(transport.ref)
    origin.expectMsg(PeerConnection.ConnectionResult.InitializationFailed("incompatible features"))
  }

  test("disconnect if incompatible global features") { f =>
    import f._
    val probe = TestProbe()
    val origin = TestProbe()
    probe.watch(transport.ref)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = Some(origin.ref), transport_opt = Some(transport.ref), isPersistent = true))
    transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
    probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref, nodeParams.chainHash, nodeParams.features.initFeatures(), doSync = true))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[protocol.Init]
    transport.send(peerConnection, LightningMessageCodecs.initCodec.decode(hex"00050100000000 0000".bits).require.value)
    transport.expectMsgType[TransportHandler.ReadAck]
    probe.expectTerminated(transport.ref)
    origin.expectMsg(PeerConnection.ConnectionResult.InitializationFailed("incompatible features"))
  }

  test("disconnect if features dependencies not met") { f =>
    import f._
    val probe = TestProbe()
    val origin = TestProbe()
    probe.watch(transport.ref)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = Some(origin.ref), transport_opt = Some(transport.ref), isPersistent = true))
    transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
    probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref, nodeParams.chainHash, nodeParams.features.initFeatures(), doSync = true))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[protocol.Init]
    // remote activated MPP but forgot payment secret
    transport.send(peerConnection, Init(Features(BasicMultiPartPayment -> Optional, VariableLengthOnion -> Optional)))
    transport.expectMsgType[TransportHandler.ReadAck]
    probe.expectTerminated(transport.ref)
    origin.expectMsg(PeerConnection.ConnectionResult.InitializationFailed("basic_mpp is set but is missing a dependency (payment_secret)"))
  }

  test("disconnect if incompatible networks") { f =>
    import f._
    val probe = TestProbe()
    val origin = TestProbe()
    probe.watch(transport.ref)
    probe.send(peerConnection, PeerConnection.PendingAuth(connection.ref, Some(remoteNodeId), address, origin_opt = Some(origin.ref), transport_opt = Some(transport.ref), isPersistent = true))
    transport.send(peerConnection, TransportHandler.HandshakeCompleted(remoteNodeId))
    probe.send(peerConnection, PeerConnection.InitializeConnection(peer.ref, nodeParams.chainHash, nodeParams.features.initFeatures(), doSync = true))
    transport.expectMsgType[TransportHandler.Listener]
    transport.expectMsgType[protocol.Init]
    transport.send(peerConnection, protocol.Init(Bob.nodeParams.features.initFeatures(), TlvStream(InitTlv.Networks(Block.LivenetGenesisBlock.hash :: Block.SignetGenesisBlock.hash :: Nil))))
    transport.expectMsgType[TransportHandler.ReadAck]
    probe.expectTerminated(transport.ref)
    origin.expectMsg(PeerConnection.ConnectionResult.InitializationFailed("incompatible networks"))
  }

  test("sync when requested") { f =>
    import f._
    val remoteInit = protocol.Init(Features(ChannelRangeQueries -> Optional, VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory))
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer, remoteInit, doSync = true)
  }

  test("reply to ping") { f =>
    import f._
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    val ping = Ping(42, randomBytes(127))
    transport.send(peerConnection, ping)
    transport.expectMsg(TransportHandler.ReadAck(ping))
    assert(transport.expectMsgType[Pong].data.size == ping.pongLength)
  }

  test("send a ping if no message after init") { f =>
    import f._
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    // ~30s without an incoming message: peer should send a ping
    transport.expectMsgType[Ping](35 / transport.testKitSettings.TestTimeFactor seconds) // we don't want dilated time here
  }

  test("send a ping if no message received for 30s") { f =>
    import f._
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    // we make the transport send a message, this will delay the sending of a ping
    val dummy = updates.head
    for (_ <- 1 to 5) { // the goal of this loop is to make sure that we don't send pings when we receive messages
      // we make the transport send a message, this will delay the sending of a ping --again
      transport.expectNoMessage(10 / transport.testKitSettings.TestTimeFactor seconds) // we don't want dilated time here
      transport.send(peerConnection, dummy)
    }
    // ~30s without an incoming message: peer should send a ping
    transport.expectMsgType[Ping](35 / transport.testKitSettings.TestTimeFactor seconds) // we don't want dilated time here
  }

  test("ignore malicious ping") { f =>
    import f._
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    // huge requested pong length
    val ping = Ping(Int.MaxValue, randomBytes(127))
    transport.send(peerConnection, ping)
    transport.expectMsg(TransportHandler.ReadAck(ping))
    assert(transport.expectMsgType[Warning].channelId == Peer.CHANNELID_ZERO)
    transport.expectNoMessage()
  }

  test("disconnect if no reply to ping") { f =>
    import f._
    val sender = TestProbe()
    val deathWatcher = TestProbe()
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    // we manually trigger a ping because we don't want to wait too long in tests
    sender.send(peerConnection, PeerConnection.SendPing)
    transport.expectMsgType[Ping]
    deathWatcher.watch(transport.ref)
    deathWatcher.expectTerminated(transport.ref, max = 11 seconds)
  }

  test("filter gossip message (no filtering)") { f =>
    import f._
    val probe = TestProbe()
    val gossipOrigin = Set[GossipOrigin](RemoteGossip(TestProbe().ref, randomKey().publicKey))
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    val rebroadcast = Rebroadcast(channels.map(_ -> gossipOrigin).toMap, updates.map(_ -> gossipOrigin).toMap, nodes.map(_ -> gossipOrigin).toMap)
    probe.send(peerConnection, rebroadcast)
    transport.expectNoMessage(10 / transport.testKitSettings.TestTimeFactor seconds) // we don't want dilated time here
  }

  test("filter gossip message (filtered by origin)") { f =>
    import f._
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    val gossipOrigin = Set[GossipOrigin](RemoteGossip(TestProbe().ref, randomKey().publicKey))
    val bobOrigin = RemoteGossip(peerConnection, remoteNodeId)
    val rebroadcast = Rebroadcast(
      channels.map(_ -> gossipOrigin).toMap + (channels(5) -> Set(bobOrigin)),
      updates.map(_ -> gossipOrigin).toMap + (updates(6) -> (gossipOrigin + bobOrigin)) + (updates(10) -> Set(bobOrigin)),
      nodes.map(_ -> gossipOrigin).toMap + (nodes(4) -> Set(bobOrigin)))
    val filter = protocol.GossipTimestampFilter(Alice.nodeParams.chainHash, 0 unixsec, Int.MaxValue) // no filtering on timestamps
    transport.send(peerConnection, filter)
    transport.expectMsg(TransportHandler.ReadAck(filter))
    transport.send(peerConnection, rebroadcast)
    // peer won't send out announcements that came from itself
    transport.expectMsgAllOf(channels diff List(channels(5)): _*)
    transport.expectMsgAllOf(updates diff List(updates(6), updates(10)): _*)
    transport.expectMsgAllOf(nodes diff List(nodes(4)): _*)
  }

  test("filter gossip message (filtered by timestamp)") { f =>
    import f._
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    val gossipOrigin = Set[GossipOrigin](RemoteGossip(TestProbe().ref, randomKey().publicKey))
    val rebroadcast = Rebroadcast(channels.map(_ -> gossipOrigin).toMap, updates.map(_ -> gossipOrigin).toMap, nodes.map(_ -> gossipOrigin).toMap)
    val timestamps = updates.map(_.timestamp).sorted.slice(10, 30)
    val filter = protocol.GossipTimestampFilter(Alice.nodeParams.chainHash, timestamps.head, (timestamps.last - timestamps.head).toSeconds)
    transport.send(peerConnection, filter)
    transport.expectMsg(TransportHandler.ReadAck(filter))
    transport.send(peerConnection, rebroadcast)
    // peer doesn't filter channel announcements
    channels.foreach(transport.expectMsg(10 seconds, _))
    // but it will only send updates and node announcements matching the filter
    transport.expectMsgAllOf(updates.filter(u => timestamps.contains(u.timestamp)): _*)
    transport.expectMsgAllOf(nodes.filter(u => timestamps.contains(u.timestamp)): _*)
  }

  test("does not filter our own gossip message") { f =>
    import f._
    val probe = TestProbe()
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)
    val gossipOrigin = Set[GossipOrigin](RemoteGossip(TestProbe().ref, randomKey().publicKey))
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
    transport.expectNoMessage(10 / transport.testKitSettings.TestTimeFactor seconds) // we don't want dilated time here
  }

  test("react to peer's bad behavior") { f =>
    import f._
    val probe = TestProbe()
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer)

    val query = QueryShortChannelIds(
      Alice.nodeParams.chainHash,
      EncodedShortChannelIds(EncodingType.UNCOMPRESSED, List(RealShortChannelId(42000))),
      TlvStream.empty)

    // make sure that routing messages go through
    for (ann <- channels ++ updates) {
      transport.send(peerConnection, ann)
      router.expectMsg(Peer.PeerRoutingMessage(peerConnection, remoteNodeId, ann))
    }
    transport.expectNoMessage(1 second) // peer hasn't acknowledged the messages

    // let's assume that the router isn't happy with those channels because the funding tx is already spent
    for (c <- channels) {
      router.send(peerConnection, GossipDecision.ChannelClosed(c))
    }
    // peer will temporary ignore announcements coming from bob
    var warningSent = false
    for (ann <- channels ++ updates) {
      transport.send(peerConnection, ann)
      if (!warningSent) {
        transport.expectMsgType[Warning]
        warningSent = true
      }
      transport.expectMsg(TransportHandler.ReadAck(ann))
    }
    router.expectNoMessage(1 second)
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
    transport.expectNoMessage(1 second) // peer hasn't acknowledged the messages

    // now let's assume that the router isn't happy with those channels because the announcement is invalid
    router.send(peerConnection, GossipDecision.InvalidAnnouncement(channels(0)))
    // peer will return a connection-wide error, including the hex-encoded representation of the bad message
    val warn1 = transport.expectMsgType[Warning]
    assert(warn1.channelId == Peer.CHANNELID_ZERO)
    assert(new String(warn1.data.toArray).startsWith("invalid announcement, couldn't verify channel"))

    // let's assume that one of the sigs were invalid
    router.send(peerConnection, GossipDecision.InvalidSignature(channels(0)))
    // peer will return a connection-wide error, including the hex-encoded representation of the bad message
    val warn2 = transport.expectMsgType[Warning]
    assert(warn2.channelId == Peer.CHANNELID_ZERO)
    assert(new String(warn2.data.toArray).startsWith("invalid announcement sig"))
  }

  test("establish transient connection") { f =>
    import f._
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer, isPersistent = false)
    val probe = TestProbe()
    val Right((_, message)) = buildMessage(nodeParams.privateKey, randomKey(), randomKey(), Nil, Recipient(remoteNodeId, None), TlvStream.empty)
    probe.send(peerConnection, message)
    probe watch peerConnection
    probe.expectTerminated(peerConnection, max = 1500 millis)
  }

  def sleep(duration: FiniteDuration): Unit = {
    val probe = TestProbe()
    system.scheduler.scheduleOnce(duration, probe.ref, ())(system.dispatcher)
    probe.expectMsg(())
  }

  test("keep transient connection open for a short while") { f =>
    import f._
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer, isPersistent = false)
    val probe = TestProbe()
    val Right((_, message)) = buildMessage(nodeParams.privateKey, randomKey(), randomKey(), Nil, Recipient(remoteNodeId, None), TlvStream.empty)
    probe watch peerConnection
    probe.send(peerConnection, message)
    // The connection is still open for a short while.
    assert(peerConnection.stateName == PeerConnection.CONNECTED)
    sleep(1 second)
    probe.expectTerminated(peerConnection, max = Duration.Zero)
  }

  test("convert transient connection to persistent") { f =>
    import f._
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer, isPersistent = false)
    val probe = TestProbe()
    val Right((_, message)) = buildMessage(nodeParams.privateKey, randomKey(), randomKey(), Nil, Recipient(remoteNodeId, None), TlvStream.empty)
    probe.send(peerConnection, message)
    assert(peerConnection.stateName == PeerConnection.CONNECTED)
    probe.send(peerConnection, ChannelReady(ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"), randomKey().publicKey))
    peerConnection.stateData match {
      case d: PeerConnection.ConnectedData => assert(d.isPersistent)
      case _ => fail()
    }
  }

  test("incoming rate limiting") { f =>
    import f._
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer, isPersistent = true)
    val Right((_, message)) = buildMessage(nodeParams.privateKey, randomKey(), randomKey(), Nil, Recipient(nodeParams.nodeId, None), TlvStream.empty)
    for (_ <- 1 to 30) {
      transport.send(peerConnection, message)
    }
    var messagesReceived = 0
    peer.receiveWhile(100 millis) {
      case _: OnionMessage =>
        messagesReceived = messagesReceived + 1
    }
    assert(messagesReceived >= 10)
    assert(messagesReceived < 15)
    sleep(1000 millis)
    transport.send(peerConnection, message)
    peer.expectMsg(message)
  }

  test("outgoing rate limiting") { f =>
    import f._
    connect(nodeParams, remoteNodeId, switchboard, router, connection, transport, peerConnection, peer, isPersistent = true)
    val Right((_, message)) = buildMessage(nodeParams.privateKey, randomKey(), randomKey(), Nil, Recipient(remoteNodeId, None), TlvStream.empty)
    for (_ <- 1 to 30) {
      peer.send(peerConnection, message)
    }
    var messagesSent = 0
    transport.receiveWhile(100 millis) {
      case _: OnionMessage =>
        messagesSent = messagesSent + 1
    }
    assert(messagesSent >= 10)
    assert(messagesSent < 15)
    sleep(1000 millis)
    peer.send(peerConnection, message)
    transport.expectMsg(message)
  }

  test("filter private IP addresses") { () =>
    val testCases = Seq(
      NodeAddress.fromParts("127.0.0.1", 9735).get -> false,
      NodeAddress.fromParts("0.0.0.0", 9735).get -> false,
      NodeAddress.fromParts("192.168.0.1", 9735).get -> false,
      NodeAddress.fromParts("140.82.121.3", 9735).get -> true,
      NodeAddress.fromParts("0000:0000:0000:0000:0000:0000:0000:0001", 9735).get -> false,
      NodeAddress.fromParts("b643:8bb1:c1f9:0556:487c:0acb:2ba3:3cc2", 9735).get -> true,
      NodeAddress.fromParts("hsmithsxurybd7uh.onion", 9735).get -> false,
      NodeAddress.fromParts("iq7zhmhck54vcax2vlrdcavq2m32wao7ekh6jyeglmnuuvv3js57r4id.onion", 9735).get -> false,
    )
    for ((address, expected) <- testCases) {
      val isPublicIP = NodeAddress.isPublicIPAddress(address)
      assert(isPublicIP == expected)
    }
  }

}

