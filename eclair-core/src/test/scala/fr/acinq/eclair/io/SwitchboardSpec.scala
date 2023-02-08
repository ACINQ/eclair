package fr.acinq.eclair.io

import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.actor.{Actor, ActorContext, ActorRef, Props, Status}
import akka.testkit.{TestActorRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.ByteVector64
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair.channel.{ChannelIdAssigned, PersistentChannelData}
import fr.acinq.eclair.io.Switchboard._
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Features, InitFeature, NodeParams, TestKitBaseClass, TimestampSecondLong, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits._

class SwitchboardSpec extends TestKitBaseClass with AnyFunSuiteLike {

  import SwitchboardSpec._

  test("on initialization create peers") {
    val nodeParams = Alice.nodeParams
    val (probe, peer) = (TestProbe(), TestProbe())
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteNodeId
    // If we have a channel with that remote peer, we will automatically reconnect.

    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(List(ChannelCodecsSpec.normal))
    probe.expectMsg(remoteNodeId)
    peer.expectMsg(Peer.Init(Set(ChannelCodecsSpec.normal)))
  }

  test("when connecting to a new peer forward Peer.Connect to it") {
    val nodeParams = Alice.nodeParams
    val (probe, peer) = (TestProbe(), TestProbe())
    val remoteNodeId = PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")
    val remoteNodeAddress = NodeAddress.fromParts("127.0.0.1", 9735).get
    nodeParams.db.network.addNode(NodeAnnouncement(ByteVector64.Zeroes, Features.empty, 0 unixsec, remoteNodeId, Color(0, 0, 0), "alias", remoteNodeAddress :: Nil))

    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(Nil)
    probe.send(switchboard, Peer.Connect(remoteNodeId, None, probe.ref, isPersistent = true))
    probe.expectMsg(remoteNodeId)
    peer.expectMsg(Peer.Init(Set.empty))
    val connect = peer.expectMsgType[Peer.Connect]
    assert(connect.nodeId == remoteNodeId)
    assert(connect.address_opt.isEmpty)
  }

  test("disconnect from peers") {
    val nodeParams = Alice.nodeParams
    val (probe, peer) = (TestProbe(), TestProbe())
    val remoteNodeId = randomKey().publicKey
    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(Nil)
    probe.send(switchboard, Peer.Connect(remoteNodeId, None, probe.ref, isPersistent = true))
    probe.expectMsg(remoteNodeId)
    peer.expectMsg(Peer.Init(Set.empty))
    peer.expectMsgType[Peer.Connect]

    val unknownNodeId = randomKey().publicKey
    probe.send(switchboard, Peer.Disconnect(unknownNodeId))
    assert(probe.expectMsgType[Status.Failure].cause.getMessage == s"peer $unknownNodeId not found")
    probe.send(switchboard, Peer.Disconnect(remoteNodeId))
    peer.expectMsg(Peer.Disconnect(remoteNodeId))
  }

  def sendFeatures(nodeParams: NodeParams, channels: Seq[PersistentChannelData], remoteNodeId: PublicKey, expectedFeatures: Features[InitFeature], expectedSync: Boolean): Unit = {
    val (probe, peer, peerConnection) = (TestProbe(), TestProbe(), TestProbe())
    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(channels)
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, remoteNodeId)
    val initConnection = peerConnection.expectMsgType[PeerConnection.InitializeConnection]
    assert(initConnection.chainHash == nodeParams.chainHash)
    assert(initConnection.features == expectedFeatures)
    assert(initConnection.doSync == expectedSync)
  }

  test("sync if no whitelist is defined and peer has channels") {
    val nodeParams = Alice.nodeParams.copy(syncWhitelist = Set.empty)
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteNodeId
    sendFeatures(nodeParams, List(ChannelCodecsSpec.normal), remoteNodeId, nodeParams.features.initFeatures(), expectedSync = true)
  }

  test("sync if no whitelist is defined and peer creates a channel") {
    val nodeParams = Alice.nodeParams.copy(syncWhitelist = Set.empty)
    val (probe, peer, peerConnection) = (TestProbe(), TestProbe(), TestProbe())
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteNodeId
    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(Nil)

    // We have a channel with our peer, so we trigger a sync when connecting.
    switchboard ! ChannelIdAssigned(TestProbe().ref, remoteNodeId, randomBytes32(), randomBytes32())
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, remoteNodeId)
    val initConnection1 = peerConnection.expectMsgType[PeerConnection.InitializeConnection]
    assert(initConnection1.chainHash == nodeParams.chainHash)
    assert(initConnection1.features == nodeParams.features.initFeatures())
    assert(initConnection1.doSync)

    // We don't have channels with our peer, so we won't trigger a sync when connecting.
    switchboard ! LastChannelClosed(peer.ref, remoteNodeId)
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, remoteNodeId)
    val initConnection2 = peerConnection.expectMsgType[PeerConnection.InitializeConnection]
    assert(initConnection2.chainHash == nodeParams.chainHash)
    assert(initConnection2.features == nodeParams.features.initFeatures())
    assert(!initConnection2.doSync)
  }

  test("don't sync if no whitelist is defined and peer does not have channels") {
    val nodeParams = Alice.nodeParams.copy(syncWhitelist = Set.empty)
    sendFeatures(nodeParams, Nil, randomKey().publicKey, nodeParams.features.initFeatures(), expectedSync = false)
  }

  test("sync if whitelist contains peer") {
    val remoteNodeId = randomKey().publicKey
    val nodeParams = Alice.nodeParams.copy(syncWhitelist = Set(remoteNodeId, randomKey().publicKey, randomKey().publicKey))
    sendFeatures(nodeParams, Nil, remoteNodeId, nodeParams.features.initFeatures(), expectedSync = true)
  }

  test("don't sync if whitelist doesn't contain peer") {
    val nodeParams = Alice.nodeParams.copy(syncWhitelist = Set(randomKey().publicKey, randomKey().publicKey, randomKey().publicKey))
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteNodeId
    sendFeatures(nodeParams, List(ChannelCodecsSpec.normal), remoteNodeId, nodeParams.features.initFeatures(), expectedSync = false)
  }

  test("get peer info") {
    val (probe, peer) = (TestProbe(), TestProbe())
    val switchboard = TestActorRef(new Switchboard(Alice.nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(Nil)
    val knownPeerNodeId = randomKey().publicKey
    probe.send(switchboard, Peer.Connect(knownPeerNodeId, None, probe.ref, isPersistent = true))
    probe.expectMsg(knownPeerNodeId)
    peer.expectMsgType[Peer.Init]
    peer.expectMsgType[Peer.Connect]

    val unknownPeerNodeId = randomKey().publicKey
    probe.send(switchboard, GetPeerInfo(probe.ref.toTyped, unknownPeerNodeId))
    probe.expectMsg(Peer.PeerNotFound(unknownPeerNodeId))

    probe.send(switchboard, GetPeerInfo(probe.ref.toTyped, knownPeerNodeId))
    peer.expectMsg(Peer.GetPeerInfo(Some(probe.ref.toTyped)))
  }

}

object SwitchboardSpec {

  // We use a dummy actor that simply forwards messages to a test probe.
  // This lets us test the children lookup that the switchboard does internally.
  class DummyPeer(fwd: ActorRef) extends Actor {
    def receive: Receive = {
      case m => fwd forward m
    }
  }

  object DummyPeer {
    def props(fwd: ActorRef) = Props(new DummyPeer(fwd))
  }

  case class FakePeerFactory(probe: TestProbe, peer: TestProbe) extends PeerFactory {
    override def spawn(context: ActorContext, remoteNodeId: PublicKey): ActorRef = {
      peer.send(probe.ref, remoteNodeId)
      context.actorOf(DummyPeer.props(peer.ref), peerActorName(remoteNodeId))
    }
  }

}