package fr.acinq.eclair.io

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.wire._
import org.scalatest.FunSuiteLike
import scodec.bits._

class SwitchboardSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  class TestSwitchboard(nodeParams: NodeParams, remoteNodeId: PublicKey, remotePeer: TestProbe) extends Switchboard(nodeParams, TestProbe().ref, TestProbe().ref, TestProbe().ref, TestProbe().ref, TestProbe().ref, new TestWallet()) {
    override def createPeer(remoteNodeId2: PublicKey): ActorRef = {
      assert(remoteNodeId === remoteNodeId2)
      remotePeer.ref
    }
  }

  test("on initialization create peers and send Reconnect to them") {
    val nodeParams = Alice.nodeParams
    val peer = TestProbe()
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteParams.nodeId
    val remoteNodeAddress = NodeAddress.fromParts("127.0.0.1", 9735).get
    nodeParams.db.peers.addOrUpdatePeer(remoteNodeId, remoteNodeAddress)
    nodeParams.db.network.addNode(NodeAnnouncement(ByteVector64.Zeroes, ByteVector.empty, 0, remoteNodeId, Color(0, 0, 0), "alias", remoteNodeAddress :: Nil))
    // If we have a channel with that remote peer, we will automatically reconnect.
    nodeParams.db.channels.addOrUpdateChannel(ChannelCodecsSpec.normal)

    val _ = TestActorRef(new TestSwitchboard(nodeParams, remoteNodeId, peer))
    peer.expectMsg(Peer.Init(Some(remoteNodeAddress.socketAddress), Set(ChannelCodecsSpec.normal)))
    peer.expectMsg(Peer.Reconnect)
  }

  test("when connecting to a new peer forward Peer.Connect to it") {
    val nodeParams = Alice.nodeParams
    val (probe, peer) = (TestProbe(), TestProbe())
    val remoteNodeId = PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")
    val remoteNodeAddress = NodeAddress.fromParts("127.0.0.1", 9735).get
    nodeParams.db.network.addNode(NodeAnnouncement(ByteVector64.Zeroes, ByteVector.empty, 0, remoteNodeId, Color(0, 0, 0), "alias", remoteNodeAddress :: Nil))

    val switchboard = TestActorRef(new TestSwitchboard(nodeParams, remoteNodeId, peer))
    probe.send(switchboard, Peer.Connect(remoteNodeId, None))
    peer.expectMsg(Peer.Init(None, Set.empty))
    peer.expectMsg(Peer.Connect(remoteNodeId, None))
  }

}
