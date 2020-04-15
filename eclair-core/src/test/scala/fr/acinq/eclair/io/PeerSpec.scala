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

import java.net.{InetAddress, ServerSocket}

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.Status.Failure
import akka.testkit.{TestFSMRef, TestProbe}
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Btc
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{EclairWallet, TestWallet}
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{Channel, ChannelCreated, HasCommitments}
import fr.acinq.eclair.io.Peer._
import fr.acinq.eclair.wire.{ChannelCodecsSpec, Color, NodeAddress, NodeAnnouncement}
import org.scalatest.{Outcome, Tag}
import scodec.bits.{ByteVector, _}

import scala.concurrent.duration._

class PeerSpec extends TestkitBaseClass with StateTestsHelperMethods {

  val fakeIPAddress = NodeAddress.fromParts("1.2.3.4", 42000).get

  case class FixtureParam(nodeParams: NodeParams, remoteNodeId: PublicKey, switchboard: TestProbe, router: TestProbe, watcher: TestProbe, relayer: TestProbe, peer: TestFSMRef[Peer.State, Peer.Data, Peer], peerConnection: TestProbe)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val switchboard = TestProbe()
    val router = TestProbe()
    val watcher = TestProbe()
    val relayer = TestProbe()
    val paymentHandler = TestProbe()
    val wallet: EclairWallet = new TestWallet()
    val remoteNodeId = Bob.nodeParams.nodeId
    val peerConnection = TestProbe()

    import com.softwaremill.quicklens._
    val aliceParams = TestConstants.Alice.nodeParams
      .modify(_.features).setToIf(test.tags.contains("wumbo"))(hex"80000")
      .modify(_.maxFundingSatoshis).setToIf(test.tags.contains("high-max-funding-satoshis"))(Btc(0.9))
      .modify(_.autoReconnect).setToIf(test.tags.contains("auto_reconnect"))(true)

    if (test.tags.contains("with_node_announcements")) {
      val bobAnnouncement = NodeAnnouncement(randomBytes64, ByteVector.empty, 1, Bob.nodeParams.nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", fakeIPAddress :: Nil)
      aliceParams.db.network.addNode(bobAnnouncement)
    }

    val peer: TestFSMRef[Peer.State, Peer.Data, Peer] = TestFSMRef(new Peer(aliceParams, remoteNodeId, switchboard.ref, router.ref, watcher.ref, relayer.ref, paymentHandler.ref, wallet))
    withFixture(test.toNoArgTest(FixtureParam(aliceParams, remoteNodeId, switchboard, router, watcher, relayer, peer, peerConnection)))
  }

  def connect(remoteNodeId: PublicKey, switchboard: TestProbe, peer: TestFSMRef[Peer.State, Peer.Data, Peer], peerConnection: TestProbe, channels: Set[HasCommitments] = Set.empty, remoteInit: wire.Init = wire.Init(Bob.nodeParams.features)): Unit = {
    // let's simulate a connection
    switchboard.send(peer, Peer.Init(channels))
    val localInit = wire.Init(peer.underlyingActor.nodeParams.features)
    peerConnection.send(peer, PeerConnection.ConnectionReady(remoteNodeId, fakeIPAddress.socketAddress, outgoing = true, localInit, remoteInit))
    val probe = TestProbe()
    probe.send(peer, Peer.GetPeerInfo)
    assert(probe.expectMsgType[Peer.PeerInfo].state == "CONNECTED")
  }

  test("restore existing channels") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, switchboard, peer, peerConnection, channels = Set(ChannelCodecsSpec.normal))
    probe.send(peer, Peer.GetPeerInfo)
    probe.expectMsg(PeerInfo(remoteNodeId, "CONNECTED", Some(fakeIPAddress.socketAddress), 1))
  }

  test("fail to connect if no address provided or found") { f =>
    import f._

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set.empty))
    probe.send(peer, Peer.Connect(remoteNodeId, address_opt = None))
    probe.expectMsg(s"no address found")
  }

  test("successfully connect to peer at user request and reconnect automatically", Tag("auto_reconnect")) { f =>
    import f._

    // we create a dummy tcp server and update bob's announcement to point to it
    val mockServer = new ServerSocket(0, 1, InetAddress.getLocalHost) // port will be assigned automatically
    val mockAddress = HostAndPort.fromParts(mockServer.getInetAddress.getHostAddress, mockServer.getLocalPort)

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set.empty))
    // we have auto-reconnect=false so we need to manually tell the peer to reconnect
    probe.send(peer, Peer.Connect(remoteNodeId, Some(mockAddress)))

    // assert our mock server got an incoming connection (the client was spawned with the address from node_announcement)
    within(30 seconds) {
      mockServer.accept()
    }

    mockServer.close()
  }

  test("reject connection attempts in state CONNECTED") { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, switchboard, peer, peerConnection, channels = Set(ChannelCodecsSpec.normal))

    probe.send(peer, Peer.Connect(remoteNodeId, None))
    probe.expectMsg("already connected")
  }

  test("handle disconnect in state CONNECTED") { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, switchboard, peer, peerConnection, channels = Set(ChannelCodecsSpec.normal))

    probe.send(peer, Peer.GetPeerInfo)
    assert(probe.expectMsgType[Peer.PeerInfo].state == "CONNECTED")

    probe.send(peer, Peer.Disconnect(f.remoteNodeId))
    probe.expectMsg("disconnecting")
  }

  test("don't spawn a wumbo channel if wumbo feature isn't enabled") { f =>
    import f._

    val probe = TestProbe()
    val fundingAmountBig = Channel.MAX_FUNDING + 10000.sat
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, switchboard, peer, peerConnection)

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, fundingAmountBig, 0 msat, None, None, None))

    assert(probe.expectMsgType[Failure].cause.getMessage == s"fundingSatoshis=$fundingAmountBig is too big, you must enable large channels support in 'eclair.features' to use funding above ${Channel.MAX_FUNDING} (see eclair.conf)")
  }

  test("don't spawn a wumbo channel if remote doesn't support wumbo", Tag("wumbo")) { f =>
    import f._

    val probe = TestProbe()
    val fundingAmountBig = Channel.MAX_FUNDING + 10000.sat
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, switchboard, peer, peerConnection) // Bob doesn't support wumbo, Alice does

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, fundingAmountBig, 0 msat, None, None, None))

    assert(probe.expectMsgType[Failure].cause.getMessage == s"fundingSatoshis=$fundingAmountBig is too big, the remote peer doesn't support wumbo")
  }

  test("don't spawn a channel if fundingSatoshis is greater than maxFundingSatoshis", Tag("high-max-funding-satoshis"), Tag("wumbo")) { f =>
    import f._

    val probe = TestProbe()
    val fundingAmountBig = Btc(1).toSatoshi
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, switchboard, peer, peerConnection, remoteInit = wire.Init(hex"80000")) // Bob supports wumbo

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, fundingAmountBig, 0 msat, None, None, None))

    assert(probe.expectMsgType[Failure].cause.getMessage == s"fundingSatoshis=$fundingAmountBig is too big for the current settings, increase 'eclair.max-funding-satoshis' (see eclair.conf)")
  }

  test("use correct fee rates when spawning a channel") { f =>
    import f._

    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, switchboard, peer, peerConnection)

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 12300 sat, 0 msat, None, None, None))
    awaitCond(peer.stateData.channels.nonEmpty)

    val channelCreated = probe.expectMsgType[ChannelCreated]
    assert(channelCreated.initialFeeratePerKw == peer.feeEstimator.getFeeratePerKw(peer.feeTargets.commitmentBlockTarget))
    assert(channelCreated.fundingTxFeeratePerKw.get == peer.feeEstimator.getFeeratePerKw(peer.feeTargets.fundingBlockTarget))
  }
}
