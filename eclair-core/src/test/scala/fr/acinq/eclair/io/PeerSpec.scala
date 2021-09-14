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

import java.net.{InetAddress, ServerSocket, Socket}
import java.util.concurrent.Executors

import akka.actor.FSM
import akka.actor.Status.Failure
import akka.testkit.{TestFSMRef, TestProbe}
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.scala.{Btc, Crypto, Script}
import fr.acinq.bitcoin.scala.Crypto.PublicKey
import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.Features.{Wumbo, StaticRemoteKey}
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{EclairWallet, TestWallet}
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{CMD_GETINFO, Channel, ChannelCreated, ChannelVersion, DATA_WAIT_FOR_ACCEPT_CHANNEL, HasCommitments, RES_GETINFO, WAIT_FOR_ACCEPT_CHANNEL}
import fr.acinq.eclair.io.Peer._
import fr.acinq.eclair.wire.{ChannelCodecsSpec, Color, NodeAddress, NodeAnnouncement}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.{ByteVector, _}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class PeerSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsHelperMethods {

  val fakeIPAddress: NodeAddress = NodeAddress.fromParts("1.2.3.4", 42000).get

  case class FixtureParam(nodeParams: NodeParams, remoteNodeId: PublicKey, watcher: TestProbe, relayer: TestProbe, peer: TestFSMRef[Peer.State, Peer.Data, Peer], peerConnection: TestProbe)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val watcher = TestProbe()
    val relayer = TestProbe()
    val wallet: EclairWallet = new TestWallet()
    val paymentHandler = TestProbe()
    val remoteNodeId = Bob.nodeParams.nodeId
    val peerConnection = TestProbe()

    import com.softwaremill.quicklens._
    val aliceParams = TestConstants.Alice.nodeParams
      .modify(_.features).setToIf(test.tags.contains("static_remotekey"))(Features(Set(ActivatedFeature(StaticRemoteKey, Optional))))
      .modify(_.features).setToIf(test.tags.contains("wumbo"))(Features(Set(ActivatedFeature(Wumbo, Optional))))
      .modify(_.maxFundingSatoshis).setToIf(test.tags.contains("high-max-funding-satoshis"))(Btc(0.9))
      .modify(_.autoReconnect).setToIf(test.tags.contains("auto_reconnect"))(true)

    if (test.tags.contains("with_node_announcement")) {
      val bobAnnouncement = NodeAnnouncement(randomBytes64, Features.empty, 1, Bob.nodeParams.nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", fakeIPAddress :: Nil)
      aliceParams.db.network.addNode(bobAnnouncement)
    }

    val peer: TestFSMRef[Peer.State, Peer.Data, Peer] = TestFSMRef(new Peer(aliceParams, remoteNodeId, watcher.ref, relayer.ref, paymentHandler.ref, wallet))
    withFixture(test.toNoArgTest(FixtureParam(aliceParams, remoteNodeId, watcher, relayer, peer, peerConnection)))
  }

  def connect(remoteNodeId: PublicKey, peer: TestFSMRef[Peer.State, Peer.Data, Peer], peerConnection: TestProbe, channels: Set[HasCommitments] = Set.empty, remoteInit: wire.Init = wire.Init(Bob.nodeParams.features)): Unit = {
    // let's simulate a connection
    val switchboard = TestProbe()
    switchboard.send(peer, Peer.Init(channels))
    val localInit = wire.Init(peer.underlyingActor.nodeParams.features)
    switchboard.send(peer, PeerConnection.ConnectionReady(peerConnection.ref, remoteNodeId, fakeIPAddress.socketAddress, outgoing = true, localInit, remoteInit))
    val probe = TestProbe()
    probe.send(peer, Peer.GetPeerInfo)
    assert(probe.expectMsgType[Peer.PeerInfo].state == "CONNECTED")
  }

  test("restore existing channels") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, channels = Set(ChannelCodecsSpec.normal))
    probe.send(peer, Peer.GetPeerInfo)
    probe.expectMsg(PeerInfo(remoteNodeId, "CONNECTED", Some(fakeIPAddress.socketAddress), 1))
  }

  ignore("fail to connect if no address provided or found") { f =>
    import f._

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set.empty))
    probe.send(peer, Peer.Connect(remoteNodeId, address_opt = None))
    probe.expectMsg(PeerConnection.ConnectionResult.NoAddressFound)
  }

  ignore("successfully connect to peer at user request") { f =>
    import f._

    // this actor listens to connection requests and creates connections
    system.actorOf(ClientSpawner.props(nodeParams, TestProbe().ref, TestProbe().ref))

    // we create a dummy tcp server and update bob's announcement to point to it
    val mockServer = new ServerSocket(0, 1, InetAddress.getLocalHost) // port will be assigned automatically
    val mockAddress = HostAndPort.fromParts(mockServer.getInetAddress.getHostAddress, mockServer.getLocalPort)

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set.empty))
    // we have auto-reconnect=false so we need to manually tell the peer to reconnect
    probe.send(peer, Peer.Connect(remoteNodeId, Some(mockAddress)))

    // assert our mock server got an incoming connection (the client was spawned with the address from node_announcement)
    val res = TestProbe()
    Future {
      val socket = mockServer.accept()
      res.ref ! socket
    }(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(1)))
    res.expectMsgType[Socket](10 seconds)

    mockServer.close()
  }

  test("successfully reconnect to peer at startup when there are existing channels", Tag("auto_reconnect")) { f =>
    import f._

    // this actor listens to connection requests and creates connections
    system.actorOf(ClientSpawner.props(nodeParams, TestProbe().ref, TestProbe().ref))

    // we create a dummy tcp server and update bob's announcement to point to it
    val mockServer = new ServerSocket(0, 1, InetAddress.getLocalHost) // port will be assigned automatically
    val mockAddress = NodeAddress.fromParts(mockServer.getInetAddress.getHostAddress, mockServer.getLocalPort).get

    // we put the server address in the node db
    val ann = NodeAnnouncement(randomBytes64, Features.empty, 1, Bob.nodeParams.nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", mockAddress :: Nil)
    nodeParams.db.network.addNode(ann)

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set(ChannelCodecsSpec.normal)))

    // assert our mock server got an incoming connection (the client was spawned with the address from node_announcement)
    val res = TestProbe()
    Future {
      val socket = mockServer.accept()
      res.ref ! socket
    }(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(1)))
    res.expectMsgType[Socket](10 seconds)

    mockServer.close()
  }

  test("reject connection attempts in state CONNECTED") { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, channels = Set(ChannelCodecsSpec.normal))

    probe.send(peer, Peer.Connect(remoteNodeId, None))
    probe.expectMsg(PeerConnection.ConnectionResult.AlreadyConnected)
  }

  test("handle disconnect in state CONNECTED") { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, channels = Set(ChannelCodecsSpec.normal))

    probe.send(peer, Peer.GetPeerInfo)
    assert(probe.expectMsgType[Peer.PeerInfo].state == "CONNECTED")

    probe.send(peer, Peer.Disconnect(f.remoteNodeId))
    probe.expectMsg("disconnecting")
  }

  test("handle new connection in state CONNECTED") { f =>
    import f._

    connect(remoteNodeId, peer, peerConnection, channels = Set(ChannelCodecsSpec.normal))
    // this is just to extract inits
    val Peer.ConnectedData(_, _, localInit, remoteInit, _, _) = peer.stateData

    val peerConnection1 = peerConnection
    val peerConnection2 = TestProbe()
    val peerConnection3 = TestProbe()

    val deathWatch = TestProbe()
    deathWatch.watch(peerConnection1.ref)
    deathWatch.watch(peerConnection2.ref)
    deathWatch.watch(peerConnection3.ref)

    peerConnection2.send(peer, PeerConnection.ConnectionReady(peerConnection2.ref, remoteNodeId, fakeIPAddress.socketAddress, outgoing = false, localInit, remoteInit))
    // peer should kill previous connection
    deathWatch.expectTerminated(peerConnection1.ref)
    awaitCond(peer.stateData.asInstanceOf[Peer.ConnectedData].peerConnection === peerConnection2.ref)

    peerConnection3.send(peer, PeerConnection.ConnectionReady(peerConnection3.ref, remoteNodeId, fakeIPAddress.socketAddress, outgoing = false, localInit, remoteInit))
    // peer should kill previous connection
    deathWatch.expectTerminated(peerConnection2.ref)
    awaitCond(peer.stateData.asInstanceOf[Peer.ConnectedData].peerConnection === peerConnection3.ref)
  }

  test("send state transitions to child reconnection actor", Tag("auto_reconnect"), Tag("with_node_announcement")) { f =>
    import f._

    // monitor state changes of child reconnection task
    val monitor = TestProbe()
    val reconnectionTask = peer.underlyingActor.context.child("reconnection-task").get
    monitor.send(reconnectionTask, FSM.SubscribeTransitionCallBack(monitor.ref))
    monitor.expectMsg(FSM.CurrentState(reconnectionTask, ReconnectionTask.IDLE))

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set(ChannelCodecsSpec.normal)))

    // the reconnection task will wait a little...
    monitor.expectMsg(FSM.Transition(reconnectionTask, ReconnectionTask.IDLE, ReconnectionTask.WAITING))
    // then it will trigger a reconnection request (which will be left unhandled because there is no listener)
    monitor.expectMsg(FSM.Transition(reconnectionTask, ReconnectionTask.WAITING, ReconnectionTask.CONNECTING))

    // we simulate a success
    val dummyInit = wire.Init(peer.underlyingActor.nodeParams.features)
    probe.send(peer, PeerConnection.ConnectionReady(peerConnection.ref, remoteNodeId, fakeIPAddress.socketAddress, outgoing = true, dummyInit, dummyInit))

    // we make sure that the reconnection task has done a full circle
    monitor.expectMsg(FSM.Transition(reconnectionTask, ReconnectionTask.CONNECTING, ReconnectionTask.IDLE))
  }

  test("don't spawn a wumbo channel if wumbo feature isn't enabled") { f =>
    import f._

    val probe = TestProbe()
    val fundingAmountBig = Channel.MAX_FUNDING + 10000.sat
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection)

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, fundingAmountBig, 0 msat, None, None, None))

    assert(probe.expectMsgType[Failure].cause.getMessage == s"fundingSatoshis=$fundingAmountBig is too big, you must enable large channels support in 'eclair.features' to use funding above ${Channel.MAX_FUNDING} (see eclair.conf)")
  }

  test("don't spawn a wumbo channel if remote doesn't support wumbo", Tag("wumbo")) { f =>
    import f._

    val probe = TestProbe()
    val fundingAmountBig = Channel.MAX_FUNDING + 10000.sat
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection) // Bob doesn't support wumbo, Alice does

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, fundingAmountBig, 0 msat, None, None, None))

    assert(probe.expectMsgType[Failure].cause.getMessage == s"fundingSatoshis=$fundingAmountBig is too big, the remote peer doesn't support wumbo")
  }

  test("don't spawn a channel if fundingSatoshis is greater than maxFundingSatoshis", Tag("high-max-funding-satoshis"), Tag("wumbo")) { f =>
    import f._

    val probe = TestProbe()
    val fundingAmountBig = Btc(1).toSatoshi
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection, remoteInit = wire.Init(Features(Set(ActivatedFeature(Wumbo, Optional))))) // Bob supports wumbo

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, fundingAmountBig, 0 msat, None, None, None))

    assert(probe.expectMsgType[Failure].cause.getMessage == s"fundingSatoshis=$fundingAmountBig is too big for the current settings, increase 'eclair.max-funding-satoshis' (see eclair.conf)")
  }

  test("use correct fee rates when spawning a channel") { f =>
    import f._

    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection)

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 12300 sat, 0 msat, None, None, None))
    awaitCond(peer.stateData.channels.nonEmpty)

    val channelCreated = probe.expectMsgType[ChannelCreated]
    assert(channelCreated.initialFeeratePerKw == peer.feeEstimator.getFeeratePerKw(peer.feeTargets.commitmentBlockTarget))
    assert(channelCreated.fundingTxFeeratePerKw.get == peer.feeEstimator.getFeeratePerKw(peer.feeTargets.fundingBlockTarget))
  }

  test("use correct final script if option_static_remotekey is negotiated", Tag("static_remotekey")) { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, remoteInit = wire.Init(Features(Set(ActivatedFeature(StaticRemoteKey, Optional))))) // Bob supports option_static_remotekey
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 24000 sat, 0 msat, None, None, None))
    awaitCond(peer.stateData.channels.nonEmpty)
    peer.stateData.channels.foreach { case (_, channelRef) =>
      probe.send(channelRef, CMD_GETINFO)
      val info = probe.expectMsgType[RES_GETINFO]
      assert(info.state == WAIT_FOR_ACCEPT_CHANNEL)
      val inputInit = info.data.asInstanceOf[DATA_WAIT_FOR_ACCEPT_CHANNEL].initFunder
      assert(inputInit.channelVersion.hasStaticRemotekey)
      assert(inputInit.localParams.staticPaymentBasepoint.isDefined)
      assert(inputInit.localParams.defaultFinalScriptPubKey === Script.write(Script.pay2wpkh(inputInit.localParams.staticPaymentBasepoint.get)))
    }
  }
}

object PeerSpec {

  val preimage = randomBytes32
  val paymentHash = Crypto.sha256(preimage)

}