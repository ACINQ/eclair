package fr.acinq.eclair.io

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{EventFilter, TestFSMRef, TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.db.ChannelStateSpec
import org.scalatest.{FunSuiteLike, Outcome, Tag}
import scala.concurrent.duration._
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.wire.LightningMessageCodecsSpec.randomSignature
import fr.acinq.eclair.wire.{Color, IPv4, NodeAddress, NodeAnnouncement}
import scodec.bits.ByteVector

class PeerSpecWithLogging extends TestKit(ActorSystem("test", ConfigFactory.parseString("""akka.loggers = ["akka.testkit.TestEventListener"]"""))) with FunSuiteLike {

  val fakeIPAddress = NodeAddress.fromParts("1.2.3.4", 42000).get

  test("reconnect using the address from node_announcement") {
    val aliceParams = Alice.nodeParams
    val aliceAnnouncement = NodeAnnouncement(randomSignature, ByteVector.empty, 1, Bob.nodeParams.nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", fakeIPAddress :: Nil)
    aliceParams.db.network.addNode(aliceAnnouncement)
    val authenticator = TestProbe()
    val watcher = TestProbe()
    val router = TestProbe()
    val relayer = TestProbe()
    val wallet: EclairWallet = null // unused
    val remoteNodeId = Bob.nodeParams.nodeId
    val peer: TestFSMRef[Peer.State, Peer.Data, Peer] = TestFSMRef(new Peer(aliceParams, remoteNodeId, authenticator.ref, watcher.ref, router.ref, relayer.ref, wallet))


    val probe = TestProbe()
    awaitCond({peer.stateName.toString == "INSTANTIATING"}, 10 seconds)
    probe.send(peer, Peer.Init(None, Set(ChannelStateSpec.normal)))
    awaitCond({peer.stateName.toString == "DISCONNECTED" && peer.stateData.address_opt.isEmpty}, 10 seconds)
    EventFilter.info(message = s"reconnecting to ${fakeIPAddress.socketAddress}", occurrences = 1) intercept {
      probe.send(peer, Peer.Reconnect)
    }
  }


}
