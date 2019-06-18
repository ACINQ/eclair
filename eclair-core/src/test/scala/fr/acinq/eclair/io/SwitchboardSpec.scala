package fr.acinq.eclair.io

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.db._
import fr.acinq.eclair.wire.{Color, NodeAddress, NodeAnnouncement}
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.FunSuiteLike
import scodec.bits.ByteVector

class SwitchboardSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with IdiomaticMockito {

  test("on initialization create peers and send Reconnect to them") {

    val mockNetworkDb = mock[NetworkDb]
    val nodeParams = Alice.nodeParams.copy(
      db = new Databases {
        override val network: NetworkDb = mockNetworkDb
        override val audit: AuditDb = Alice.nodeParams.db.audit
        override val channels: ChannelsDb = Alice.nodeParams.db.channels
        override val peers: PeersDb = Alice.nodeParams.db.peers
        override val payments: PaymentsDb = Alice.nodeParams.db.payments
        override val pendingRelay: PendingRelayDb = Alice.nodeParams.db.pendingRelay
        override def backup(file: File): Unit = ()
      }
    )

    val remoteNodeId = ChannelStateSpec.normal.commitments.remoteParams.nodeId
    val authenticator = TestProbe()
    val watcher = TestProbe()
    val router = TestProbe()
    val relayer = TestProbe()
    val wallet = new TestWallet()
    val probe = TestProbe()

    // mock the call that will be done by the peer once it receives Peer.Reconnect
    mockNetworkDb.getNode(remoteNodeId) returns Some(
      NodeAnnouncement(ByteVector64.Zeroes, ByteVector.empty, 0, remoteNodeId, Color(0,0,0), "alias", List(NodeAddress.fromParts("127.0.0.1", 9735).get))
    )

    // add a channel to the db
    nodeParams.db.channels.addOrUpdateChannel(ChannelStateSpec.normal)

    val switchboard = system.actorOf(Switchboard.props(nodeParams, authenticator.ref, watcher.ref, router.ref, relayer.ref, wallet))

    probe.send(switchboard, 'peers)
    val List(peer) = probe.expectMsgType[Iterable[ActorRef]].toList
    assert(peer.path.name == Switchboard.peerActorName(remoteNodeId))

    // assert that the peer called `networkDb.getNode` - because it received a Peer.Reconnect
    awaitAssert(mockNetworkDb.getNode(remoteNodeId).wasCalled(once))
  }

}
