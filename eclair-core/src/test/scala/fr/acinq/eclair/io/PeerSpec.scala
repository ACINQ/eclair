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

import akka.actor.Status.Failure
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.actor.{ActorContext, ActorRef, FSM, PoisonPill, Status}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, Btc, SatoshiLong, Script}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel.ChannelTypes.UnsupportedChannelType
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.states.ChannelStateTestsTags
import fr.acinq.eclair.io.Peer._
import fr.acinq.eclair.message.OnionMessages.{Recipient, buildMessage}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol._
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, ParallelTestExecution, Tag}
import scodec.bits.ByteVector

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import scala.concurrent.duration._

class PeerSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ParallelTestExecution {

  import PeerSpec._

  val fakeIPAddress: NodeAddress = NodeAddress.fromParts("1.2.3.4", 42000).get

  case class FixtureParam(nodeParams: NodeParams, remoteNodeId: PublicKey, peer: TestFSMRef[Peer.State, Peer.Data, Peer], peerConnection: TestProbe, channel: TestProbe, switchboard: TestProbe)

  case class FakeChannelFactory(channel: TestProbe) extends ChannelFactory {
    override def spawn(context: ActorContext, remoteNodeId: PublicKey, origin_opt: Option[ActorRef]): ActorRef = {
      assert(remoteNodeId == Bob.nodeParams.nodeId)
      channel.ref
    }
  }

  override protected def withFixture(test: OneArgTest): Outcome = {
    val wallet = new DummyOnChainWallet()
    val remoteNodeId = Bob.nodeParams.nodeId
    val peerConnection = TestProbe()
    val channel = TestProbe()
    val switchboard = TestProbe()

    import com.softwaremill.quicklens._
    val aliceParams = TestConstants.Alice.nodeParams
      .modify(_.features).setToIf(test.tags.contains(ChannelStateTestsTags.ChannelType))(Features(ChannelType -> Optional))
      .modify(_.features).setToIf(test.tags.contains(ChannelStateTestsTags.StaticRemoteKey))(Features(StaticRemoteKey -> Optional))
      .modify(_.features).setToIf(test.tags.contains(ChannelStateTestsTags.Wumbo))(Features(Wumbo -> Optional))
      .modify(_.features).setToIf(test.tags.contains(ChannelStateTestsTags.AnchorOutputs))(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional))
      .modify(_.features).setToIf(test.tags.contains(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs))(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional))
      .modify(_.features).setToIf(test.tags.contains(ChannelStateTestsTags.DualFunding))(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, DualFunding -> Optional))
      .modify(_.channelConf.maxFundingSatoshis).setToIf(test.tags.contains("high-max-funding-satoshis"))(Btc(0.9))
      .modify(_.autoReconnect).setToIf(test.tags.contains("auto_reconnect"))(true)

    if (test.tags.contains("with_node_announcement")) {
      val bobAnnouncement = NodeAnnouncement(randomBytes64(), Features.empty, 1 unixsec, Bob.nodeParams.nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", fakeIPAddress :: Nil)
      aliceParams.db.network.addNode(bobAnnouncement)
    }

    val peer: TestFSMRef[Peer.State, Peer.Data, Peer] = TestFSMRef(new Peer(aliceParams, remoteNodeId, wallet, FakeChannelFactory(channel), switchboard.ref))
    withFixture(test.toNoArgTest(FixtureParam(aliceParams, remoteNodeId, peer, peerConnection, channel, switchboard)))
  }

  def connect(remoteNodeId: PublicKey, peer: TestFSMRef[Peer.State, Peer.Data, Peer], peerConnection: TestProbe, switchboard: TestProbe, channels: Set[PersistentChannelData] = Set.empty, remoteInit: protocol.Init = protocol.Init(Bob.nodeParams.features.initFeatures())): Unit = {
    // let's simulate a connection
    switchboard.send(peer, Peer.Init(channels))
    val localInit = protocol.Init(peer.underlyingActor.nodeParams.features.initFeatures())
    switchboard.send(peer, PeerConnection.ConnectionReady(peerConnection.ref, remoteNodeId, fakeIPAddress, outgoing = true, localInit, remoteInit))
    val probe = TestProbe()
    probe.send(peer, Peer.GetPeerInfo(Some(probe.ref.toTyped)))
    val peerInfo = probe.expectMsgType[Peer.PeerInfo]
    assert(peerInfo.peer == peer)
    assert(peerInfo.nodeId == remoteNodeId)
    assert(peerInfo.state == Peer.CONNECTED)
  }

  test("restore existing channels") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))
    probe.send(peer, Peer.GetPeerInfo(None))
    probe.expectMsg(PeerInfo(peer, remoteNodeId, Peer.CONNECTED, Some(fakeIPAddress), 1))
  }

  test("fail to connect if no address provided or found") { f =>
    import f._

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set.empty))
    probe.send(peer, Peer.Connect(remoteNodeId, address_opt = None, probe.ref, isPersistent = true))
    probe.expectMsg(PeerConnection.ConnectionResult.NoAddressFound)
  }

  test("successfully connect to peer at user request") { f =>
    import f._

    // this actor listens to connection requests and creates connections
    system.actorOf(ClientSpawner.props(nodeParams.keyPair, nodeParams.socksProxy_opt, nodeParams.peerConnectionConf, TestProbe().ref, TestProbe().ref))

    // we create a dummy tcp server and update bob's announcement to point to it
    val (mockServer, serverAddress) = createMockServer()
    val mockAddress_opt = NodeAddress.fromParts(serverAddress.getHostName, serverAddress.getPort).toOption

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set.empty))
    // we have auto-reconnect=false so we need to manually tell the peer to reconnect
    probe.send(peer, Peer.Connect(remoteNodeId, mockAddress_opt, probe.ref, isPersistent = true))

    // assert our mock server got an incoming connection (the client was spawned with the address from node_announcement)
    awaitCond(mockServer.accept() != null, max = 30 seconds, interval = 1 second)
    mockServer.close()
  }

  test("successfully reconnect to peer at startup when there are existing channels", Tag("auto_reconnect")) { f =>
    import f._

    // this actor listens to connection requests and creates connections
    system.actorOf(ClientSpawner.props(nodeParams.keyPair, nodeParams.socksProxy_opt, nodeParams.peerConnectionConf, TestProbe().ref, TestProbe().ref))

    // we create a dummy tcp server and update bob's announcement to point to it
    val (mockServer, serverAddress) = createMockServer()
    val mockAddress = NodeAddress.fromParts(serverAddress.getHostName, serverAddress.getPort).get

    // we put the server address in the node db
    val ann = NodeAnnouncement(randomBytes64(), Features.empty, 1 unixsec, Bob.nodeParams.nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", mockAddress :: Nil)
    nodeParams.db.network.addNode(ann)

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set(ChannelCodecsSpec.normal)))

    // assert our mock server got an incoming connection (the client was spawned with the address from node_announcement)
    awaitCond(mockServer.accept() != null, max = 30 seconds, interval = 1 second)
    mockServer.close()
  }

  test("reject connection attempts in state CONNECTED") { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))

    probe.send(peer, Peer.Connect(remoteNodeId, None, probe.ref, isPersistent = true))
    probe.expectMsgType[PeerConnection.ConnectionResult.AlreadyConnected]
  }

  test("handle unknown messages") { f =>
    import f._

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[UnknownMessageReceived])
    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))

    peerConnection.send(peer, UnknownMessage(tag = TestConstants.pluginParams.messageTags.head, data = ByteVector.empty))
    listener.expectMsgType[UnknownMessageReceived]
    peerConnection.send(peer, UnknownMessage(tag = 60005, data = ByteVector.empty)) // No plugin is subscribed to this tag
    listener.expectNoMessage()
  }

  test("handle disconnect in state CONNECTED") { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))

    probe.send(peer, Peer.GetPeerInfo(Some(probe.ref.toTyped)))
    assert(probe.expectMsgType[Peer.PeerInfo].state == Peer.CONNECTED)

    probe.send(peer, Peer.Disconnect(f.remoteNodeId))
    probe.expectMsg("disconnecting")
  }

  test("handle disconnect in state DISCONNECTED") { f =>
    import f._

    val probe = TestProbe()
    switchboard.send(peer, Peer.Init(Set.empty))

    awaitCond {
      probe.send(peer, Peer.GetPeerInfo(None))
      probe.expectMsgType[Peer.PeerInfo].state == Peer.DISCONNECTED
    }

    probe.send(peer, Peer.Disconnect(f.remoteNodeId))
    assert(probe.expectMsgType[Status.Failure].cause.getMessage == "not connected")
  }

  test("handle new connection in state CONNECTED") { f =>
    import f._

    val peerConnection1 = peerConnection
    val peerConnection2 = TestProbe()
    val peerConnection3 = TestProbe()

    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))
    channel.expectMsg(INPUT_RESTORED(ChannelCodecsSpec.normal))
    val (localInit, remoteInit) = {
      val inputReconnected = channel.expectMsgType[INPUT_RECONNECTED]
      assert(inputReconnected.remote == peerConnection1.ref)
      (inputReconnected.localInit, inputReconnected.remoteInit)
    }

    peerConnection2.send(peer, PeerConnection.ConnectionReady(peerConnection2.ref, remoteNodeId, fakeIPAddress, outgoing = false, localInit, remoteInit))
    // peer should kill previous connection
    peerConnection1.expectMsg(PeerConnection.Kill(PeerConnection.KillReason.ConnectionReplaced))
    channel.expectMsg(INPUT_DISCONNECTED)
    channel.expectMsg(INPUT_RECONNECTED(peerConnection2.ref, localInit, remoteInit))
    awaitCond(peer.stateData.asInstanceOf[Peer.ConnectedData].peerConnection == peerConnection2.ref)

    peerConnection3.send(peer, PeerConnection.ConnectionReady(peerConnection3.ref, remoteNodeId, fakeIPAddress, outgoing = false, localInit, remoteInit))
    // peer should kill previous connection
    peerConnection2.expectMsg(PeerConnection.Kill(PeerConnection.KillReason.ConnectionReplaced))
    channel.expectMsg(INPUT_DISCONNECTED)
    channel.expectMsg(INPUT_RECONNECTED(peerConnection3.ref, localInit, remoteInit))
    awaitCond(peer.stateData.asInstanceOf[Peer.ConnectedData].peerConnection == peerConnection3.ref)
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
    val dummyInit = protocol.Init(peer.underlyingActor.nodeParams.features.initFeatures())
    probe.send(peer, PeerConnection.ConnectionReady(peerConnection.ref, remoteNodeId, fakeIPAddress, outgoing = true, dummyInit, dummyInit))

    // we make sure that the reconnection task has done a full circle
    monitor.expectMsg(FSM.Transition(reconnectionTask, ReconnectionTask.CONNECTING, ReconnectionTask.IDLE))
  }

  test("don't spawn a channel with duplicate temporary channel id") { f =>
    import f._

    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection, switchboard)
    assert(peer.stateData.channels.isEmpty)

    val open = createOpenChannelMessage()
    peerConnection.send(peer, open)
    awaitCond(peer.stateData.channels.nonEmpty)
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_NON_INITIATOR].temporaryChannelId == open.temporaryChannelId)
    channel.expectMsg(open)

    // open_channel messages with the same temporary channel id should simply be ignored
    peerConnection.send(peer, open.copy(fundingSatoshis = 100000 sat, fundingPubkey = randomKey().publicKey))
    channel.expectNoMessage(100 millis)
    peerConnection.expectNoMessage(100 millis)
    assert(peer.stateData.channels.size == 1)
  }

  test("don't spawn a wumbo channel if wumbo feature isn't enabled") { f =>
    import f._

    val probe = TestProbe()
    val fundingAmountBig = Channel.MAX_FUNDING + 10000.sat
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection, switchboard)

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, fundingAmountBig, None, None, None, None, None))

    assert(probe.expectMsgType[Failure].cause.getMessage == s"fundingAmount=$fundingAmountBig is too big, you must enable large channels support in 'eclair.features' to use funding above ${Channel.MAX_FUNDING} (see eclair.conf)")
  }

  test("don't spawn a wumbo channel if remote doesn't support wumbo", Tag(ChannelStateTestsTags.Wumbo)) { f =>
    import f._

    val probe = TestProbe()
    val fundingAmountBig = Channel.MAX_FUNDING + 10000.sat
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection, switchboard) // Bob doesn't support wumbo, Alice does

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, fundingAmountBig, None, None, None, None, None))

    assert(probe.expectMsgType[Failure].cause.getMessage == s"fundingAmount=$fundingAmountBig is too big, the remote peer doesn't support wumbo")
  }

  test("don't spawn a channel if fundingSatoshis is greater than maxFundingSatoshis", Tag("high-max-funding-satoshis"), Tag(ChannelStateTestsTags.Wumbo)) { f =>
    import f._

    val probe = TestProbe()
    val fundingAmountBig = Btc(1).toSatoshi
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(Wumbo -> Optional))) // Bob supports wumbo

    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, fundingAmountBig, None, None, None, None, None))

    assert(probe.expectMsgType[Failure].cause.getMessage == s"fundingAmount=$fundingAmountBig is too big for the current settings, increase 'eclair.max-funding-satoshis' (see eclair.conf)")
  }

  test("don't spawn a channel if we don't support their channel type") { f =>
    import f._

    connect(remoteNodeId, peer, peerConnection, switchboard)
    assert(peer.stateData.channels.isEmpty)

    // They only support anchor outputs and we don't.
    {
      val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputs)))
      peerConnection.send(peer, open)
      peerConnection.expectMsg(Error(open.temporaryChannelId, "invalid channel_type=anchor_outputs, expected channel_type=standard"))
    }
    // They only support anchor outputs with zero fee htlc txs and we don't.
    {
      val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = false))))
      peerConnection.send(peer, open)
      peerConnection.expectMsg(Error(open.temporaryChannelId, "invalid channel_type=anchor_outputs_zero_fee_htlc_tx, expected channel_type=standard"))
    }
    // They want to use a channel type that doesn't exist in the spec.
    {
      val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(UnsupportedChannelType(Features(AnchorOutputs -> Optional)))))
      peerConnection.send(peer, open)
      peerConnection.expectMsg(Error(open.temporaryChannelId, "invalid channel_type=0x200000, expected channel_type=standard"))
    }
    // They want to use a channel type we don't support yet.
    {
      val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(UnsupportedChannelType(Features(Map(StaticRemoteKey -> Mandatory), unknown = Set(UnknownFeature(22)))))))
      peerConnection.send(peer, open)
      peerConnection.expectMsg(Error(open.temporaryChannelId, "invalid channel_type=0x401000, expected channel_type=standard"))
    }
  }

  test("don't spawn a channel is channel type is missing with the feature bit set", Tag(ChannelStateTestsTags.ChannelType)) { f =>
    import f._

    val remoteInit = protocol.Init(Features(ChannelType -> Optional))
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = remoteInit)
    assert(peer.stateData.channels.isEmpty)
    val open = createOpenChannelMessage()
    peerConnection.send(peer, open)
    peerConnection.expectMsg(Error(open.temporaryChannelId, "option_channel_type was negotiated but channel_type is missing"))
  }

  test("don't spawn a dual funded channel if not supported") { f =>
    import f._

    connect(remoteNodeId, peer, peerConnection, switchboard)
    val open = createOpenDualFundedChannelMessage()
    peerConnection.send(peer, open)
    peerConnection.expectMsg(Error(open.temporaryChannelId, "dual funding is not supported"))
  }

  test("use dual-funding when available", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val probe = TestProbe()
    // Both peers support option_dual_fund, so it is automatically used.
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, DualFunding -> Optional)))
    assert(peer.stateData.channels.isEmpty)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 25000 sat, None, None, None, None, None))
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR].dualFunded)
  }

  test("accept dual-funded channels when available", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    // Both peers support option_dual_fund, so it is automatically used.
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, DualFunding -> Optional)))
    assert(peer.stateData.channels.isEmpty)
    val open = createOpenDualFundedChannelMessage()
    peerConnection.send(peer, open)
    awaitCond(peer.stateData.channels.nonEmpty)
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_NON_INITIATOR].dualFunded)
    channel.expectMsg(open)
  }

  test("use their channel type when spawning a channel", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._

    // We both support option_static_remotekey but they want to open a standard channel.
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Optional)))
    assert(peer.stateData.channels.isEmpty)
    val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(ChannelTypes.Standard)))
    peerConnection.send(peer, open)
    awaitCond(peer.stateData.channels.nonEmpty)
    val init = channel.expectMsgType[INPUT_INIT_CHANNEL_NON_INITIATOR]
    assert(init.channelType == ChannelTypes.Standard)
    assert(!init.dualFunded)
    channel.expectMsg(open)
  }

  test("use requested channel type when spawning a channel", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Mandatory)))
    assert(peer.stateData.channels.isEmpty)

    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, None, None, None, None, None))
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR].channelType == ChannelTypes.StaticRemoteKey)

    // We can create channels that don't use the features we have enabled.
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, Some(ChannelTypes.Standard), None, None, None, None))
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR].channelType == ChannelTypes.Standard)

    // We can create channels that use features that we haven't enabled.
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, Some(ChannelTypes.AnchorOutputs), None, None, None, None))
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR].channelType == ChannelTypes.AnchorOutputs)
  }

  test("use correct on-chain fee rates when spawning a channel (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional)))
    assert(peer.stateData.channels.isEmpty)

    // We ensure the current network feerate is higher than the default anchor output feerate.
    val feeEstimator = nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator]
    feeEstimator.setFeerate(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw * 2).copy(mempoolMinFee = FeeratePerKw(250 sat)))
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, None, None, None, None, None))
    val init = channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR]
    assert(init.channelType == ChannelTypes.AnchorOutputs)
    assert(!init.dualFunded)
    assert(init.fundingAmount == 15000.sat)
    assert(init.commitTxFeerate == TestConstants.anchorOutputsFeeratePerKw)
    assert(init.fundingTxFeerate == feeEstimator.getFeeratePerKw(nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget))
  }

  test("use correct on-chain fee rates when spawning a channel (anchor outputs zero fee htlc)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional)))
    assert(peer.stateData.channels.isEmpty)

    // We ensure the current network feerate is higher than the default anchor output feerate.
    val feeEstimator = nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator]
    feeEstimator.setFeerate(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw * 2).copy(mempoolMinFee = FeeratePerKw(250 sat)))
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, None, None, None, None, None))
    val init = channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR]
    assert(init.channelType == ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = false))
    assert(!init.dualFunded)
    assert(init.fundingAmount == 15000.sat)
    assert(init.commitTxFeerate == TestConstants.anchorOutputsFeeratePerKw)
    assert(init.fundingTxFeerate == feeEstimator.getFeeratePerKw(nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget))
  }

  test("use correct final script if option_static_remotekey is negotiated", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Mandatory)))
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 24000 sat, None, None, None, None, None))
    val init = channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR]
    assert(init.channelType == ChannelTypes.StaticRemoteKey)
    assert(!init.dualFunded)
    assert(init.localParams.walletStaticPaymentBasepoint.isDefined)
    assert(init.localParams.defaultFinalScriptPubKey == Script.write(Script.pay2wpkh(init.localParams.walletStaticPaymentBasepoint.get)))
  }

  test("do not allow option_scid_alias with public channel") { f =>
    import f._

    intercept[IllegalArgumentException] {
      Peer.OpenChannel(remoteNodeId, 24000 sat, Some(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true, zeroConf = true)), None, None, Some(ChannelFlags(announceChannel = true)), None)
    }
  }

  test("set origin_opt when spawning a channel") { f =>
    import f._

    val probe = TestProbe()
    val channelFactory = new ChannelFactory {
      override def spawn(context: ActorContext, remoteNodeId: PublicKey, origin_opt: Option[ActorRef]): ActorRef = {
        assert(origin_opt.contains(probe.ref))
        channel.ref
      }
    }
    val peer = TestFSMRef(new Peer(TestConstants.Alice.nodeParams, remoteNodeId, new DummyOnChainWallet(), channelFactory, switchboard.ref))
    connect(remoteNodeId, peer, peerConnection, switchboard)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, None, Some(100 msat), None, None, None))
    val init = channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR]
    assert(init.fundingAmount == 15000.sat)
    assert(init.pushAmount_opt.contains(100.msat))
  }

  test("handle final channelId assigned in state DISCONNECTED") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))
    peer ! ConnectionDown(peerConnection.ref)
    probe.send(peer, Peer.GetPeerInfo(Some(probe.ref.toTyped)))
    val peerInfo1 = probe.expectMsgType[Peer.PeerInfo]
    assert(peerInfo1.state == Peer.DISCONNECTED)
    assert(peerInfo1.channels == 1)
    peer ! ChannelIdAssigned(probe.ref, remoteNodeId, randomBytes32(), randomBytes32())
    probe.send(peer, Peer.GetPeerInfo(Some(probe.ref.toTyped)))
    val peerInfo2 = probe.expectMsgType[Peer.PeerInfo]
    assert(peerInfo2.state == Peer.DISCONNECTED)
    assert(peerInfo2.channels == 2)
  }

  test("notify when last channel is closed") { f =>
    import f._
    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[LastChannelClosed])
    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))
    probe.send(channel.ref, PoisonPill)
    probe.expectMsg(LastChannelClosed(peer, remoteNodeId))
  }

  test("notify when last channel is closed in state DISCONNECTED") { f =>
    import f._
    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[LastChannelClosed])
    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))
    peer ! ConnectionDown(peerConnection.ref)
    probe.send(channel.ref, PoisonPill)
    probe.expectMsg(LastChannelClosed(peer, remoteNodeId))
  }

  test("reply to relay request") { f =>
    import f._
    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))
    val (_, msg) = buildMessage(randomKey(), randomKey(), Nil, Recipient(remoteNodeId, None), Nil)
    val messageId = randomBytes32()
    val probe = TestProbe()
    peer ! RelayOnionMessage(messageId, msg, Some(probe.ref.toTyped))
    probe.expectMsg(MessageRelay.Sent(messageId))
  }

  test("reply to relay request disconnected") { f =>
    import f._
    val (_, msg) = buildMessage(randomKey(), randomKey(), Nil, Recipient(remoteNodeId, None), Nil)
    val messageId = randomBytes32()
    val probe = TestProbe()
    peer ! RelayOnionMessage(messageId, msg, Some(probe.ref.toTyped))
    probe.expectMsg(MessageRelay.Disconnected(messageId))
  }
}

object PeerSpec {

  def createMockServer(): (ServerSocketChannel, InetSocketAddress) = {
    val mockServer = ServerSocketChannel.open()
    // NB: we force 127.0.0.1 (IPv4) because there are issues on ubuntu build machines with IPv6 loopback
    mockServer.bind(new InetSocketAddress("127.0.0.1", 0))
    mockServer.configureBlocking(false)
    (mockServer, mockServer.getLocalAddress.asInstanceOf[InetSocketAddress])
  }

  def createOpenChannelMessage(openTlv: TlvStream[OpenChannelTlv] = TlvStream.empty): protocol.OpenChannel = {
    protocol.OpenChannel(Block.RegtestGenesisBlock.hash, randomBytes32(), 25000 sat, 0 msat, 483 sat, UInt64(100), 1000 sat, 1 msat, TestConstants.feeratePerKw, CltvExpiryDelta(144), 10, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, ChannelFlags.Private, openTlv)
  }

  def createOpenDualFundedChannelMessage(): protocol.OpenDualFundedChannel = {
    protocol.OpenDualFundedChannel(Block.RegtestGenesisBlock.hash, randomBytes32(), TestConstants.feeratePerKw, TestConstants.anchorOutputsFeeratePerKw, 25000 sat, 483 sat, UInt64(100), 1 msat, CltvExpiryDelta(144), 10, 0, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, ChannelFlags.Private)
  }

}