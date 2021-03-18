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

import akka.actor.FSM
import akka.actor.Status.Failure
import akka.testkit.{TestFSMRef, TestProbe}
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, Btc, SatoshiLong, Script}
import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.Features.{AnchorOutputs, StaticRemoteKey, Wumbo}
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.blockchain.{EclairWallet, TestWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Peer._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, ParallelTestExecution, Tag}
import scodec.bits.ByteVector

import java.net.{InetAddress, ServerSocket, Socket}
import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class PeerSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ParallelTestExecution {

  val fakeIPAddress: NodeAddress = NodeAddress.fromParts("1.2.3.4", 42000).get

  case class FixtureParam(nodeParams: NodeParams, remoteNodeId: PublicKey, watcher: TestProbe, relayer: TestProbe, peer: TestFSMRef[Peer.State, Peer.Data, Peer], peerConnection: TestProbe)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val watcher = TestProbe()
    val relayer = TestProbe()
    val wallet: EclairWallet = new TestWallet()
    val remoteNodeId = Bob.nodeParams.nodeId
    val peerConnection = TestProbe()

    import com.softwaremill.quicklens._
    val aliceParams = TestConstants.Alice.nodeParams
      .modify(_.features).setToIf(test.tags.contains("static_remotekey"))(Features(StaticRemoteKey -> Optional))
      .modify(_.features).setToIf(test.tags.contains("wumbo"))(Features(Wumbo -> Optional))
      .modify(_.features).setToIf(test.tags.contains("anchor_outputs"))(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional))
      .modify(_.maxFundingSatoshis).setToIf(test.tags.contains("high-max-funding-satoshis"))(Btc(0.9))
      .modify(_.autoReconnect).setToIf(test.tags.contains("auto_reconnect"))(true)

    if (test.tags.contains("with_node_announcement")) {
      val bobAnnouncement = NodeAnnouncement(randomBytes64, Features.empty, 1, Bob.nodeParams.nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", fakeIPAddress :: Nil)
      aliceParams.db.network.addNode(bobAnnouncement)
    }

    val peer: TestFSMRef[Peer.State, Peer.Data, Peer] = TestFSMRef(new Peer(aliceParams, remoteNodeId, watcher.ref, relayer.ref, wallet))
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

  test("fail to connect if no address provided or found") { f =>
    import f._

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set.empty))
    probe.send(peer, Peer.Connect(remoteNodeId, address_opt = None))
    probe.expectMsg(PeerConnection.ConnectionResult.NoAddressFound)
  }

  test("successfully connect to peer at user request") { f =>
    import f._

    // this actor listens to connection requests and creates connections
    system.actorOf(ClientSpawner.props(nodeParams.keyPair, nodeParams.socksProxy_opt, nodeParams.peerConnectionConf, TestProbe().ref, TestProbe().ref))

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
    system.actorOf(ClientSpawner.props(nodeParams.keyPair, nodeParams.socksProxy_opt, nodeParams.peerConnectionConf, TestProbe().ref, TestProbe().ref))

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

  test("handle unknown messages") { f =>
    import f._

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[UnknownMessageReceived])
    connect(remoteNodeId, peer, peerConnection, channels = Set(ChannelCodecsSpec.normal))

    peerConnection.send(peer, UnknownMessage(tag = TestConstants.pluginParams.messageTags.head, data = ByteVector.empty))
    listener.expectMsgType[UnknownMessageReceived]
    peerConnection.send(peer, UnknownMessage(tag = 60005, data = ByteVector.empty)) // No plugin is subscribed to this tag
    listener.expectNoMessage()
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

    val peerConnection1 = peerConnection
    val peerConnection2 = TestProbe()
    val peerConnection3 = TestProbe()

    connect(remoteNodeId, peer, peerConnection, channels = Set(ChannelCodecsSpec.normal))
    peerConnection1.expectMsgType[ChannelReestablish]
    // this is just to extract inits
    val Peer.ConnectedData(_, _, localInit, remoteInit, _) = peer.stateData

    peerConnection2.send(peer, PeerConnection.ConnectionReady(peerConnection2.ref, remoteNodeId, fakeIPAddress.socketAddress, outgoing = false, localInit, remoteInit))
    // peer should kill previous connection
    peerConnection1.expectMsg(PeerConnection.Kill(PeerConnection.KillReason.ConnectionReplaced))
    awaitCond(peer.stateData.asInstanceOf[Peer.ConnectedData].peerConnection === peerConnection2.ref)
    peerConnection2.expectMsgType[ChannelReestablish]

    peerConnection3.send(peer, PeerConnection.ConnectionReady(peerConnection3.ref, remoteNodeId, fakeIPAddress.socketAddress, outgoing = false, localInit, remoteInit))
    // peer should kill previous connection
    peerConnection2.expectMsg(PeerConnection.Kill(PeerConnection.KillReason.ConnectionReplaced))
    awaitCond(peer.stateData.asInstanceOf[Peer.ConnectedData].peerConnection === peerConnection3.ref)
    peerConnection3.expectMsgType[ChannelReestablish]
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

  test("don't spawn a channel with duplicate temporary channel id") { f =>
    import f._

    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection)
    assert(peer.stateData.channels.isEmpty)

    val open = wire.OpenChannel(Block.RegtestGenesisBlock.hash, randomBytes32, 25000 sat, 0 msat, 483 sat, UInt64(100), 1000 sat, 1 msat, TestConstants.feeratePerKw, CltvExpiryDelta(144), 10, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, 0)
    peerConnection.send(peer, open)
    awaitCond(peer.stateData.channels.nonEmpty)
    assert(probe.expectMsgType[ChannelCreated].temporaryChannelId === open.temporaryChannelId)
    peerConnection.expectMsgType[AcceptChannel]

    // open_channel messages with the same temporary channel id should simply be ignored
    peerConnection.send(peer, open.copy(fundingSatoshis = 100000 sat, fundingPubkey = randomKey.publicKey))
    probe.expectNoMsg(100 millis)
    peerConnection.expectNoMsg(100 millis)
    assert(peer.stateData.channels.size === 1)
  }

  test("don't spawn a wumbo channel if wumbo feature isn't enabled") { f =>
    import f._

    val probe = TestProbe()
    val fundingAmountBig = Channel.MAX_FUNDING + 10000.sat
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection)

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, fundingAmountBig, 0 msat, None, None, None, None))

    assert(probe.expectMsgType[Failure].cause.getMessage == s"fundingSatoshis=$fundingAmountBig is too big, you must enable large channels support in 'eclair.features' to use funding above ${Channel.MAX_FUNDING} (see eclair.conf)")
  }

  test("don't spawn a wumbo channel if remote doesn't support wumbo", Tag("wumbo")) { f =>
    import f._

    val probe = TestProbe()
    val fundingAmountBig = Channel.MAX_FUNDING + 10000.sat
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection) // Bob doesn't support wumbo, Alice does

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, fundingAmountBig, 0 msat, None, None, None, None))

    assert(probe.expectMsgType[Failure].cause.getMessage == s"fundingSatoshis=$fundingAmountBig is too big, the remote peer doesn't support wumbo")
  }

  test("don't spawn a channel if fundingSatoshis is greater than maxFundingSatoshis", Tag("high-max-funding-satoshis"), Tag("wumbo")) { f =>
    import f._

    val probe = TestProbe()
    val fundingAmountBig = Btc(1).toSatoshi
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection, remoteInit = wire.Init(Features(Wumbo -> Optional))) // Bob supports wumbo

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, fundingAmountBig, 0 msat, None, None, None, None))

    assert(probe.expectMsgType[Failure].cause.getMessage == s"fundingSatoshis=$fundingAmountBig is too big for the current settings, increase 'eclair.max-funding-satoshis' (see eclair.conf)")
  }

  test("use correct fee rates when spawning a channel") { f =>
    import f._

    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection)
    assert(peer.stateData.channels.isEmpty)

    val relayFees = Some(100 msat, 1000)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 12300 sat, 0 msat, None, relayFees, None, None))
    awaitCond(peer.stateData.channels.nonEmpty)

    val channelCreated = probe.expectMsgType[ChannelCreated]
    assert(channelCreated.initialFeeratePerKw == nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(nodeParams.onChainFeeConf.feeTargets.commitmentBlockTarget))
    assert(channelCreated.fundingTxFeeratePerKw.get == nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget))

    peer.stateData.channels.foreach { case (_, channelRef) =>
      probe.send(channelRef, CMD_GETINFO(probe.ref))
      val info = probe.expectMsgType[RES_GETINFO]
      assert(info.state == WAIT_FOR_ACCEPT_CHANNEL)
      val inputInit = info.data.asInstanceOf[DATA_WAIT_FOR_ACCEPT_CHANNEL].initFunder
      assert(inputInit.initialRelayFees_opt === relayFees)
    }
  }

  test("use correct on-chain fee rates when spawning a channel (anchor outputs)", Tag("anchor_outputs")) { f =>
    import f._

    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection, remoteInit = wire.Init(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional)))

    // We ensure the current network feerate is higher than the default anchor output feerate.
    val feeEstimator = nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator]
    feeEstimator.setFeerate(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw * 2))
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, 0 msat, None, None, None, None))

    val channelCreated = probe.expectMsgType[ChannelCreated]
    assert(channelCreated.initialFeeratePerKw == TestConstants.anchorOutputsFeeratePerKw)
    assert(channelCreated.fundingTxFeeratePerKw.get == feeEstimator.getFeeratePerKw(nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget))
  }

  test("use correct final script if option_static_remotekey is negotiated", Tag("static_remotekey")) { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, remoteInit = wire.Init(Features(StaticRemoteKey -> Optional))) // Bob supports option_static_remotekey
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 24000 sat, 0 msat, None, None, None, None))
    awaitCond(peer.stateData.channels.nonEmpty)
    peer.stateData.channels.foreach { case (_, channelRef) =>
      probe.send(channelRef, CMD_GETINFO(probe.ref))
      val info = probe.expectMsgType[RES_GETINFO]
      assert(info.state == WAIT_FOR_ACCEPT_CHANNEL)
      val inputInit = info.data.asInstanceOf[DATA_WAIT_FOR_ACCEPT_CHANNEL].initFunder
      assert(inputInit.channelVersion.hasStaticRemotekey)
      assert(inputInit.localParams.walletStaticPaymentBasepoint.isDefined)
      assert(inputInit.localParams.defaultFinalScriptPubKey === Script.write(Script.pay2wpkh(inputInit.localParams.walletStaticPaymentBasepoint.get)))
    }
  }
}
