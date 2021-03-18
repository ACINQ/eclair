package fr.acinq.eclair.io

import akka.actor.ActorRef
import akka.testkit.{TestActorRef, TestProbe}
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.channel.ChannelIdAssigned
import fr.acinq.eclair.wire._
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.{Features, NodeParams, TestKitBaseClass, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits._

class SwitchboardSpec extends TestKitBaseClass with AnyFunSuiteLike {

  class TestSwitchboard(nodeParams: NodeParams, remoteNodeId: PublicKey, remotePeer: TestProbe) extends Switchboard(nodeParams, TestProbe().ref, TestProbe().ref, new TestWallet()) {
    override def createPeer(remoteNodeId2: PublicKey): ActorRef = {
      assert(remoteNodeId === remoteNodeId2)
      remotePeer.ref
    }
  }

  test("on initialization create peers") {
    val nodeParams = Alice.nodeParams
    val peer = TestProbe()
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteParams.nodeId
    // If we have a channel with that remote peer, we will automatically reconnect.
    nodeParams.db.channels.addOrUpdateChannel(ChannelCodecsSpec.normal)

    val _ = TestActorRef(new TestSwitchboard(nodeParams, remoteNodeId, peer))
    peer.expectMsg(Peer.Init(Set(ChannelCodecsSpec.normal)))
  }

  test("when connecting to a new peer forward Peer.Connect to it") {
    val nodeParams = Alice.nodeParams
    val (probe, peer) = (TestProbe(), TestProbe())
    val remoteNodeId = PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")
    val remoteNodeAddress = NodeAddress.fromParts("127.0.0.1", 9735).get
    nodeParams.db.network.addNode(NodeAnnouncement(ByteVector64.Zeroes, Features.empty, 0, remoteNodeId, Color(0, 0, 0), "alias", remoteNodeAddress :: Nil))

    val switchboard = TestActorRef(new TestSwitchboard(nodeParams, remoteNodeId, peer))
    probe.send(switchboard, Peer.Connect(remoteNodeId, None))
    peer.expectMsg(Peer.Init(Set.empty))
    peer.expectMsg(Peer.Connect(remoteNodeId, None))
  }

  def sendFeatures(nodeParams: NodeParams, remoteNodeId: PublicKey, expectedFeatures: Features, expectedSync: Boolean) = {
    val peer = TestProbe()
    val peerConnection = TestProbe()
    val switchboard = TestActorRef(new TestSwitchboard(nodeParams, remoteNodeId, peer))
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, remoteNodeId)
    peerConnection.expectMsg(PeerConnection.InitializeConnection(peer.ref, nodeParams.chainHash, expectedFeatures, doSync = expectedSync))
  }

  test("sync if no whitelist is defined and peer has channels") {
    val nodeParams = Alice.nodeParams.copy(syncWhitelist = Set.empty)
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteParams.nodeId
    nodeParams.db.channels.addOrUpdateChannel(ChannelCodecsSpec.normal)
    sendFeatures(nodeParams, remoteNodeId, nodeParams.features, expectedSync = true)
  }

  test("sync if no whitelist is defined and peer creates a channel") {
    val peer = TestProbe()
    val peerConnection = TestProbe()
    val nodeParams = Alice.nodeParams.copy(syncWhitelist = Set.empty)
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteParams.nodeId
    val switchboard = TestActorRef(new TestSwitchboard(nodeParams, remoteNodeId, peer))

    // We have a channel with our peer, so we trigger a sync when connecting.
    switchboard ! ChannelIdAssigned(TestProbe().ref, remoteNodeId, randomBytes32, randomBytes32)
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, remoteNodeId)
    peerConnection.expectMsg(PeerConnection.InitializeConnection(peer.ref, nodeParams.chainHash, nodeParams.features, doSync = true))

    // We don't have channels with our peer, so we won't trigger a sync when connecting.
    switchboard ! LastChannelClosed(peer.ref, remoteNodeId)
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, remoteNodeId)
    peerConnection.expectMsg(PeerConnection.InitializeConnection(peer.ref, nodeParams.chainHash, nodeParams.features, doSync = false))
  }

  test("don't sync if no whitelist is defined and peer does not have channels") {
    val nodeParams = Alice.nodeParams.copy(syncWhitelist = Set.empty)
    sendFeatures(nodeParams, randomKey.publicKey, nodeParams.features, expectedSync = false)
  }

  test("sync if whitelist contains peer") {
    val remoteNodeId = randomKey.publicKey
    val nodeParams = Alice.nodeParams.copy(syncWhitelist = Set(remoteNodeId, randomKey.publicKey, randomKey.publicKey))
    sendFeatures(nodeParams, remoteNodeId, nodeParams.features, expectedSync = true)
  }

  test("don't sync if whitelist doesn't contain peer") {
    val nodeParams = Alice.nodeParams.copy(syncWhitelist = Set(randomKey.publicKey, randomKey.publicKey, randomKey.publicKey))
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteParams.nodeId
    nodeParams.db.channels.addOrUpdateChannel(ChannelCodecsSpec.normal)
    sendFeatures(nodeParams, remoteNodeId, nodeParams.features, expectedSync = false)
  }

}
