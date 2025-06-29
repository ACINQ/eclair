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

import akka.actor.{ActorContext, ActorRef, ActorSystem, FSM, PoisonPill}
import akka.testkit.TestActor.KeepRunning
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.blockchain.{CurrentFeerates, DummyOnChainWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.states.ChannelStateTestsTags
import fr.acinq.eclair.crypto.keymanager.ChannelKeys
import fr.acinq.eclair.io.Peer._
import fr.acinq.eclair.message.OnionMessages.{Recipient, buildMessage}
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol._
import org.scalatest.Inside.inside
import org.scalatest.{Tag, TestData}
import scodec.bits.{ByteVector, HexStringSyntax}

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel
import scala.concurrent.duration._

class PeerSpec extends FixtureSpec {

  import PeerSpec._
  import akka.actor.typed.scaladsl.adapter._

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = 30 seconds, interval = 1 second)

  case class FixtureParam(nodeParams: NodeParams, remoteNodeId: PublicKey, system: ActorSystem, peer: TestFSMRef[Peer.State, Peer.Data, Peer], peerConnection: TestProbe, channel: TestProbe, switchboard: TestProbe, register: TestProbe, mockLimiter: ActorRef) {
    implicit val implicitSystem: ActorSystem = system

    def cleanup(): Unit = TestKit.shutdownActorSystem(system)
  }

  def createFixture(testData: TestData): FixtureParam = {
    implicit val system: ActorSystem = ActorSystem()
    val wallet = new DummyOnChainWallet()
    val remoteNodeId = Bob.nodeParams.nodeId
    val peerConnection = TestProbe()
    val channel = TestProbe()
    val switchboard = TestProbe()
    val register = TestProbe()
    val router = TestProbe()

    import com.softwaremill.quicklens._
    val aliceParams = TestConstants.Alice.nodeParams
      .modify(_.features).setToIf(testData.tags.contains(ChannelStateTestsTags.StaticRemoteKey))(Features(StaticRemoteKey -> Optional))
      .modify(_.features).setToIf(testData.tags.contains(ChannelStateTestsTags.AnchorOutputs))(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional))
      .modify(_.features).setToIf(testData.tags.contains(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs))(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional))
      .modify(_.features).setToIf(testData.tags.contains(ChannelStateTestsTags.DualFunding))(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, DualFunding -> Optional))
      .modify(_.channelConf.maxHtlcValueInFlightMsat).setToIf(testData.tags.contains("max-htlc-value-in-flight-percent"))(100_000_000 msat)
      .modify(_.channelConf.maxHtlcValueInFlightPercent).setToIf(testData.tags.contains("max-htlc-value-in-flight-percent"))(25)
      .modify(_.channelConf.channelFundingTimeout).setToIf(testData.tags.contains("channel_funding_timeout"))(100 millis)
      .modify(_.autoReconnect).setToIf(testData.tags.contains("auto_reconnect"))(true)

    if (testData.tags.contains("with_node_announcement")) {
      val bobAnnouncement = NodeAnnouncement(randomBytes64(), Features.empty, 1 unixsec, Bob.nodeParams.nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", fakeIPAddress :: Nil)
      aliceParams.db.network.addNode(bobAnnouncement)
    }

    case class FakeChannelFactory(channel: TestProbe) extends ChannelFactory {
      override def spawn(context: ActorContext, remoteNodeId: PublicKey, channelKeys: ChannelKeys): ActorRef = {
        assert(remoteNodeId == Bob.nodeParams.nodeId)
        channel.ref
      }
    }

    val mockLimiter = TestProbe()
    mockLimiter.setAutoPilot((_: ActorRef, msg: Any) => msg match {
      case msg: PendingChannelsRateLimiter.AddOrRejectChannel if testData.tags.contains("rate_limited") =>
        msg.replyTo ! PendingChannelsRateLimiter.ChannelRateLimited
        KeepRunning
      case msg: PendingChannelsRateLimiter.AddOrRejectChannel =>
        msg.replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
        KeepRunning
      case _ => KeepRunning
    })

    val peer: TestFSMRef[Peer.State, Peer.Data, Peer] = TestFSMRef(new Peer(aliceParams, remoteNodeId, wallet, FakeChannelFactory(channel), switchboard.ref, register.ref, router.ref, mockLimiter.ref))

    FixtureParam(aliceParams, remoteNodeId, system, peer, peerConnection, channel, switchboard, register, mockLimiter.ref)
  }

  def cleanupFixture(fixture: FixtureParam): Unit = fixture.cleanup()

  def connect(remoteNodeId: PublicKey, peer: TestFSMRef[Peer.State, Peer.Data, Peer], peerConnection: TestProbe, switchboard: TestProbe, channels: Set[PersistentChannelData] = Set.empty, remoteInit: protocol.Init = protocol.Init(Bob.nodeParams.features.initFeatures()), initializePeer: Boolean = true, peerStorage: Option[ByteVector] = None)(implicit system: ActorSystem): Unit = {
    // let's simulate a connection
    if (initializePeer) {
      switchboard.send(peer, Peer.Init(channels, Map.empty))
    }
    val localInit = protocol.Init(peer.underlyingActor.nodeParams.features.initFeatures())
    switchboard.send(peer, PeerConnection.ConnectionReady(peerConnection.ref, remoteNodeId, fakeIPAddress, outgoing = true, localInit, remoteInit))
    peerStorage.foreach(data => peerConnection.expectMsg(PeerStorageRetrieval(data)))
    peerConnection.expectMsgType[RecommendedFeerates]
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
    system.eventStream.subscribe(probe.ref, classOf[PeerCreated])
    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))
    probe.expectMsg(PeerCreated(peer.ref, remoteNodeId))
    probe.send(peer, Peer.GetPeerInfo(None))
    val peerInfo = probe.expectMsgType[PeerInfo]
    assert(peerInfo.peer == peer)
    assert(peerInfo.nodeId == remoteNodeId)
    assert(peerInfo.state == Peer.CONNECTED)
    assert(peerInfo.address.contains(fakeIPAddress))
    assert(peerInfo.channels.size == 1)
  }

  test("fail to connect if no address provided or found") { f =>
    import f._

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set.empty, Map.empty))
    probe.send(peer, Peer.Connect(remoteNodeId, address_opt = None, probe.ref, isPersistent = true))
    probe.expectMsg(PeerConnection.ConnectionResult.NoAddressFound)
  }

  /** We need to be careful to avoid race conditions due to event stream asynchronous nature */
  def spawnClientSpawner(f: FixtureParam): Unit = {
    import f._
    val readyListener = TestProbe("ready-listener")
    system.eventStream.subscribe(readyListener.ref, classOf[SubscriptionsComplete])
    TestUtils.waitEventStreamSynced(system.eventStream)
    // this actor listens to connection requests and creates connections
    system.actorOf(ClientSpawner.props(nodeParams.keyPair, nodeParams.socksProxy_opt, nodeParams.peerConnectionConf, TestProbe().ref, TestProbe().ref))
    // make sure that the actor has registered to system.eventStream to prevent race conditions
    readyListener.expectMsg(SubscriptionsComplete(classOf[ClientSpawner]))
  }

  test("successfully connect to peer at user request") { f =>
    import f._

    spawnClientSpawner(f)

    // we create a dummy tcp server and update bob's announcement to point to it
    val (mockServer, serverAddress) = createMockServer()
    val mockAddress_opt = NodeAddress.fromParts(serverAddress.getHostName, serverAddress.getPort).toOption

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set.empty, Map.empty))
    // we have auto-reconnect=false so we need to manually tell the peer to reconnect
    probe.send(peer, Peer.Connect(remoteNodeId, mockAddress_opt, probe.ref, isPersistent = true))

    // assert our mock server got an incoming connection (the client was spawned with the address from node_announcement)
    eventually {
      assert(mockServer.accept() != null)
    }
    mockServer.close()
  }

  test("return connection failure for a peer with an invalid dns host name") { f =>
    import f._

    spawnClientSpawner(f)

    val invalidDnsHostname_opt = NodeAddress.fromParts("eclair.invalid", 9735).toOption
    assert(invalidDnsHostname_opt.nonEmpty)
    assert(invalidDnsHostname_opt.get == DnsHostname("eclair.invalid", 9735))

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set.empty, Map.empty))
    probe.send(peer, Peer.Connect(remoteNodeId, invalidDnsHostname_opt, probe.ref, isPersistent = true))
    probe.expectMsgType[PeerConnection.ConnectionResult.ConnectionFailed]
  }

  test("successfully reconnect to peer at startup when there are existing channels", Tag("auto_reconnect")) { f =>
    import f._

    spawnClientSpawner(f)

    // we create a dummy tcp server and update bob's announcement to point to it
    val (mockServer, serverAddress) = createMockServer()
    val mockAddress = NodeAddress.fromParts(serverAddress.getHostName, serverAddress.getPort).get

    // we put the server address in the node db
    val ann = NodeAnnouncement(randomBytes64(), Features.empty, 1 unixsec, Bob.nodeParams.nodeId, Color(100.toByte, 200.toByte, 300.toByte), "node-alias", mockAddress :: Nil)
    nodeParams.db.network.addNode(ann)

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set(ChannelCodecsSpec.normal), Map.empty))

    // assert our mock server got an incoming connection (the client was spawned with the address from node_announcement)
    eventually {
      assert(mockServer.accept() != null)
    }
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
    probe.expectMsgType[Peer.Disconnecting]
  }

  test("handle disconnect in state DISCONNECTED") { f =>
    import f._

    val probe = TestProbe()
    switchboard.send(peer, Peer.Init(Set.empty, Map.empty))

    eventually {
      probe.send(peer, Peer.GetPeerInfo(None))
      assert(probe.expectMsgType[Peer.PeerInfo].state == Peer.DISCONNECTED)
    }

    probe.send(peer, Peer.Disconnect(f.remoteNodeId))
    probe.expectMsgType[Peer.NotConnected]
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
    peerConnection2.expectMsgType[RecommendedFeerates]
    // peer should kill previous connection
    peerConnection1.expectMsg(PeerConnection.Kill(PeerConnection.KillReason.ConnectionReplaced))
    channel.expectMsg(INPUT_DISCONNECTED)
    channel.expectMsg(INPUT_RECONNECTED(peerConnection2.ref, localInit, remoteInit))
    eventually {
      assert(peer.stateData.asInstanceOf[Peer.ConnectedData].peerConnection == peerConnection2.ref)
    }

    peerConnection3.send(peer, PeerConnection.ConnectionReady(peerConnection3.ref, remoteNodeId, fakeIPAddress, outgoing = false, localInit, remoteInit))
    peerConnection3.expectMsgType[RecommendedFeerates]
    // peer should kill previous connection
    peerConnection2.expectMsg(PeerConnection.Kill(PeerConnection.KillReason.ConnectionReplaced))
    channel.expectMsg(INPUT_DISCONNECTED)
    channel.expectMsg(INPUT_RECONNECTED(peerConnection3.ref, localInit, remoteInit))
    eventually {
      assert(peer.stateData.asInstanceOf[Peer.ConnectedData].peerConnection == peerConnection3.ref)
    }
  }

  test("send state transitions to child reconnection actor", Tag("auto_reconnect"), Tag("with_node_announcement")) { f =>
    import f._

    // monitor state changes of child reconnection task
    val monitor = TestProbe()
    val reconnectionTask = peer.underlyingActor.context.child("reconnection-task").get
    monitor.send(reconnectionTask, FSM.SubscribeTransitionCallBack(monitor.ref))
    monitor.expectMsg(FSM.CurrentState(reconnectionTask, ReconnectionTask.IDLE))

    val probe = TestProbe()
    probe.send(peer, Peer.Init(Set(ChannelCodecsSpec.normal), Map.empty))

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

  test("send recommended feerates when feerate changes") { f =>
    import f._

    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))

    // We regularly update our internal feerates.
    val bitcoinCoreFeerates = FeeratesPerKw(FeeratePerKw(253 sat), FeeratePerKw(1000 sat), FeeratePerKw(2500 sat), FeeratePerKw(5000 sat), FeeratePerKw(10_000 sat))
    nodeParams.setBitcoinCoreFeerates(bitcoinCoreFeerates)
    peer ! CurrentFeerates.BitcoinCore(bitcoinCoreFeerates)
    peerConnection.expectMsg(RecommendedFeerates(
      chainHash = Block.RegtestGenesisBlock.hash,
      fundingFeerate = FeeratePerKw(2_500 sat),
      commitmentFeerate = FeeratePerKw(5000 sat),
      tlvStream = TlvStream[RecommendedFeeratesTlv](
        RecommendedFeeratesTlv.FundingFeerateRange(FeeratePerKw(1250 sat), FeeratePerKw(20_000 sat)),
        RecommendedFeeratesTlv.CommitmentFeerateRange(FeeratePerKw(2500 sat), FeeratePerKw(40_000 sat))
      )
    ))
  }

  test("reject funding requests if funding feerate is too low for on-the-fly funding", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, DualFunding -> Optional)))
    val requestFunds = LiquidityAds.RequestFunding(50_000 sat, LiquidityAds.FundingRate(10_000 sat, 100_000 sat, 0, 0, 0 sat, 0 sat), LiquidityAds.PaymentDetails.FromFutureHtlc(randomBytes32() :: Nil))
    val open = {
      val open = createOpenDualFundedChannelMessage()
      open.copy(fundingFeerate = FeeratePerKw(5000 sat), tlvStream = TlvStream(ChannelTlv.RequestFundingTlv(requestFunds)))
    }

    // Our current and previous feerates are higher than what will be proposed.
    Seq(FeeratePerKw(7500 sat), FeeratePerKw(6000 sat)).foreach(feerate => {
      val feerates = FeeratesPerKw.single(feerate)
      nodeParams.setBitcoinCoreFeerates(feerates)
      peer ! CurrentFeerates.BitcoinCore(feerates)
      assert(peerConnection.expectMsgType[RecommendedFeerates].fundingFeerate == feerate)
    })

    // The channel request is rejected.
    peerConnection.send(peer, open)
    peerConnection.expectMsg(Error(open.temporaryChannelId, FundingFeerateTooLow(open.temporaryChannelId, FeeratePerKw(5000 sat), FeeratePerKw(6000 sat)).getMessage))

    // Our latest feerate matches the channel request.
    val feerates3 = FeeratesPerKw.single(FeeratePerKw(5000 sat))
    nodeParams.setBitcoinCoreFeerates(feerates3)
    peer ! CurrentFeerates.BitcoinCore(feerates3)
    assert(peerConnection.expectMsgType[RecommendedFeerates].fundingFeerate == FeeratePerKw(5000 sat))

    // The channel request is accepted.
    peerConnection.send(peer, open)
    channel.expectMsgType[INPUT_INIT_CHANNEL_NON_INITIATOR]
    channel.expectMsg(open)

    val channelId = randomBytes32()
    peer ! ChannelIdAssigned(channel.ref, remoteNodeId, open.temporaryChannelId, channelId)
    peerConnection.expectMsgType[PeerConnection.DoSync]

    // The feerate of the splice request is lower than our last two feerates.
    val splice = SpliceInit(channelId, 100_000 sat, FeeratePerKw(4500 sat), 0, randomKey().publicKey, TlvStream(ChannelTlv.RequestFundingTlv(requestFunds)))
    peerConnection.send(peer, splice)
    peerConnection.expectMsg(TxAbort(channelId, FundingFeerateTooLow(channelId, FeeratePerKw(4500 sat), FeeratePerKw(5000 sat)).getMessage))

    // Our latest feerate matches the splice request.
    val feerates4 = FeeratesPerKw.single(FeeratePerKw(4000 sat))
    nodeParams.setBitcoinCoreFeerates(feerates4)
    peer ! CurrentFeerates.BitcoinCore(feerates4)
    assert(peerConnection.expectMsgType[RecommendedFeerates].fundingFeerate == FeeratePerKw(4000 sat))

    // The splice is accepted.
    peerConnection.send(peer, splice)
    channel.expectMsg(splice)
  }

  test("don't spawn a channel with duplicate temporary channel id", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[ChannelCreated])
    connect(remoteNodeId, peer, peerConnection, switchboard)
    assert(peer.stateData.channels.isEmpty)

    val open = createOpenChannelMessage()
    peerConnection.send(peer, open)
    eventually {
      assert(peer.stateData.channels.nonEmpty)
    }
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_NON_INITIATOR].temporaryChannelId == open.temporaryChannelId)
    channel.expectMsg(open)

    // open_channel messages with the same temporary channel id should simply be ignored
    peerConnection.send(peer, open.copy(fundingSatoshis = 100000 sat, fundingPubkey = randomKey().publicKey))
    channel.expectNoMessage(100 millis)
    peerConnection.expectNoMessage(100 millis)
    assert(peer.stateData.channels.size == 1)
  }

  test("send error when receiving message for unknown channel") { f =>
    import f._

    connect(remoteNodeId, peer, peerConnection, switchboard)
    val channelId = randomBytes32()
    peerConnection.send(peer, ChannelReestablish(channelId, 1, 0, randomKey(), randomKey().publicKey))
    peerConnection.expectMsg(Error(channelId, "unknown channel"))
  }

  test("handle OpenChannelInterceptor spawning a user initiated open channel request ") { f =>
    import f._

    connect(remoteNodeId, peer, peerConnection, switchboard)
    assert(peer.stateData.channels.isEmpty)

    val requestFunds = LiquidityAds.RequestFunding(50_000 sat, LiquidityAds.FundingRate(10_000 sat, 100_000 sat, 0, 0, 0 sat, 0 sat), LiquidityAds.PaymentDetails.FromChannelBalance)
    val open = Peer.OpenChannel(remoteNodeId, 10000 sat, None, None, None, None, Some(requestFunds), None, None)
    peerConnection.send(peer, open)
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR].requestFunding_opt.contains(requestFunds))
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
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 25000 sat, None, None, None, None, None, None, None))
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR].dualFunded)
  }

  test("accept dual-funded channels when available", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    // Both peers support option_dual_fund, so it is automatically used.
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, DualFunding -> Optional)))
    assert(peer.stateData.channels.isEmpty)
    val open = createOpenDualFundedChannelMessage()
    peerConnection.send(peer, open)
    eventually {
      assert(peer.stateData.channels.nonEmpty)
    }
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_NON_INITIATOR].dualFunded)
    channel.expectMsg(open)
  }

  test("use their channel type when spawning a channel", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    import f._

    // We both support option_anchors_zero_fee_htlc_tx they want to open an  anchor_outputs channel.
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(AnchorOutputsZeroFeeHtlcTx -> Optional)))
    assert(peer.stateData.channels.isEmpty)
    val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputs())))
    peerConnection.send(peer, open)
    eventually {
      assert(peer.stateData.channels.nonEmpty)
    }
    val init = channel.expectMsgType[INPUT_INIT_CHANNEL_NON_INITIATOR]
    assert(init.channelType == ChannelTypes.AnchorOutputs())
    assert(!init.dualFunded)
    channel.expectMsg(open)
  }

  test("use requested channel type when spawning a channel", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Mandatory)))
    assert(peer.stateData.channels.isEmpty)

    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, None, None, None, None, None, None, None))
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR].channelType == ChannelTypes.StaticRemoteKey())

    // We can create channels that don't use the features we have enabled.
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, Some(ChannelTypes.Standard()), None, None, None, None, None, None))
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR].channelType == ChannelTypes.Standard())

    // We can create channels that use features that we haven't enabled.
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, Some(ChannelTypes.AnchorOutputs()), None, None, None, None, None, None))
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR].channelType == ChannelTypes.AnchorOutputs())
  }

  test("handle OpenChannelInterceptor accepting an open channel message", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    connect(remoteNodeId, peer, peerConnection, switchboard)

    val open = createOpenChannelMessage()
    peerConnection.send(peer, open)
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_NON_INITIATOR].temporaryChannelId == open.temporaryChannelId)
    channel.expectMsg(open)
  }

  test("handle OpenChannelInterceptor rejecting an open channel message", Tag("rate_limited"), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    connect(remoteNodeId, peer, peerConnection, switchboard)

    val open = createOpenChannelMessage()
    peerConnection.send(peer, open)
    peerConnection.expectMsg(Error(open.temporaryChannelId, "rate limit reached"))
    assert(peer.stateData.channels.isEmpty)
  }

  test("use correct on-chain fee rates when spawning a channel (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional)))
    assert(peer.stateData.channels.isEmpty)

    // We ensure the current network feerate is higher than the default anchor output feerate.
    nodeParams.setBitcoinCoreFeerates(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw * 2).copy(minimum = FeeratePerKw(250 sat)))
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, None, None, None, None, None, None, None))
    val init = channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR]
    assert(init.channelType == ChannelTypes.AnchorOutputs())
    assert(!init.dualFunded)
    assert(init.fundingAmount == 15000.sat)
    assert(init.commitTxFeerate == TestConstants.anchorOutputsFeeratePerKw)
    assert(init.fundingTxFeerate == nodeParams.onChainFeeConf.getFundingFeerate(nodeParams.currentFeeratesForFundingClosing))
  }

  test("use correct on-chain fee rates when spawning a channel (anchor outputs zero fee htlc)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Optional, AnchorOutputs -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional)))
    assert(peer.stateData.channels.isEmpty)

    // We ensure the current network feerate is higher than the default anchor output feerate.
    nodeParams.setBitcoinCoreFeerates(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw * 2).copy(minimum = FeeratePerKw(250 sat)))
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, None, None, None, None, None, None, None))
    val init = channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR]
    assert(init.channelType == ChannelTypes.AnchorOutputsZeroFeeHtlcTx())
    assert(!init.dualFunded)
    assert(init.fundingAmount == 15000.sat)
    assert(init.commitTxFeerate == TestConstants.anchorOutputsFeeratePerKw)
    assert(init.fundingTxFeerate == nodeParams.onChainFeeConf.getFundingFeerate(nodeParams.currentFeeratesForFundingClosing))
  }

  test("use correct final script if option_static_remotekey is negotiated", Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Mandatory)))
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 24000 sat, None, None, None, None, None, None, None))
    val init = channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR]
    assert(init.channelType == ChannelTypes.StaticRemoteKey())
    assert(!init.dualFunded)
    assert(init.localChannelParams.walletStaticPaymentBasepoint.isDefined)
    assert(init.localChannelParams.upfrontShutdownScript_opt.isEmpty)
  }

  test("compute max-htlc-value-in-flight based on funding amount", Tag("max-htlc-value-in-flight-percent"), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard)
    assert(peer.underlyingActor.nodeParams.channelConf.maxHtlcValueInFlightPercent == 25)
    assert(peer.underlyingActor.nodeParams.channelConf.maxHtlcValueInFlightMsat == 100_000_000.msat)

    {
      probe.send(peer, Peer.OpenChannel(remoteNodeId, 200_000 sat, None, None, None, None, None, None, None))
      val init = channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR]
      assert(init.proposedCommitParams.localMaxHtlcValueInFlight == UInt64(50_000_000)) // max-htlc-value-in-flight-percent
    }
    {
      probe.send(peer, Peer.OpenChannel(remoteNodeId, 500_000 sat, None, None, None, None, None, None, None))
      val init = channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR]
      assert(init.proposedCommitParams.localMaxHtlcValueInFlight == UInt64(100_000_000)) // max-htlc-value-in-flight-msat
    }
    {
      val open = createOpenChannelMessage().copy(fundingSatoshis = 200_000 sat)
      peerConnection.send(peer, open)
      val init = channel.expectMsgType[INPUT_INIT_CHANNEL_NON_INITIATOR]
      assert(init.proposedCommitParams.localMaxHtlcValueInFlight == UInt64(50_000_000)) // max-htlc-value-in-flight-percent
      channel.expectMsg(open)
    }
    {
      val open = createOpenChannelMessage().copy(fundingSatoshis = 500_000 sat)
      peerConnection.send(peer, open)
      val init = channel.expectMsgType[INPUT_INIT_CHANNEL_NON_INITIATOR]
      assert(init.proposedCommitParams.localMaxHtlcValueInFlight == UInt64(100_000_000)) // max-htlc-value-in-flight-msat
      channel.expectMsg(open)
    }
  }

  test("do not allow option_scid_alias with public channel") { f =>
    import f._

    intercept[IllegalArgumentException] {
      Peer.OpenChannel(remoteNodeId, 24000 sat, Some(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true, zeroConf = true)), None, None, None, None, Some(ChannelFlags(announceChannel = true)), None)
    }
  }

  test("set origin_opt when spawning a channel") { f =>
    import f._

    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard)
    probe.send(peer, Peer.OpenChannel(remoteNodeId, 15000 sat, None, Some(100 msat), None, None, None, None, None))
    val init = channel.expectMsgType[INPUT_INIT_CHANNEL_INITIATOR]
    assert(init.replyTo == probe.ref.toTyped[OpenChannelResponse])
  }

  test("handle final channelId assigned in state DISCONNECTED") { f =>
    import f._
    val probe = TestProbe()
    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))
    peer ! ConnectionDown(peerConnection.ref)
    probe.send(peer, Peer.GetPeerInfo(Some(probe.ref.toTyped)))
    val peerInfo1 = probe.expectMsgType[Peer.PeerInfo]
    assert(peerInfo1.state == Peer.DISCONNECTED)
    assert(peerInfo1.channels.size == 1)
    peer ! ChannelIdAssigned(probe.ref, remoteNodeId, randomBytes32(), randomBytes32())
    probe.send(peer, Peer.GetPeerInfo(Some(probe.ref.toTyped)))
    val peerInfo2 = probe.expectMsgType[Peer.PeerInfo]
    assert(peerInfo2.state == Peer.DISCONNECTED)
    assert(peerInfo2.channels.size == 2)
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
    val Right(msg) = buildMessage(randomKey(), randomKey(), Nil, Recipient(remoteNodeId, None), TlvStream.empty)
    val messageId = randomBytes32()
    val probe = TestProbe()
    peer ! RelayOnionMessage(messageId, msg, Some(probe.ref.toTyped))
    probe.expectMsg(MessageRelay.Sent(messageId))
  }

  test("reply to relay request disconnected") { f =>
    import f._
    val Right(msg) = buildMessage(randomKey(), randomKey(), Nil, Recipient(remoteNodeId, None), TlvStream.empty)
    val messageId = randomBytes32()
    val probe = TestProbe()
    peer ! RelayOnionMessage(messageId, msg, Some(probe.ref.toTyped))
    probe.expectMsg(MessageRelay.Disconnected(messageId))
  }

  test("send UnknownMessage to peer if tag registered by a plugin") { f =>
    import f._
    val probe = TestProbe()
    val unknownMessage = UnknownMessage(60003, ByteVector32.One)
    connect(remoteNodeId, peer, peerConnection, switchboard, channels = Set(ChannelCodecsSpec.normal))
    probe.send(peer, Peer.RelayUnknownMessage(unknownMessage))
    peerConnection.expectMsgType[UnknownMessage]
  }

  test("abort channel open request if peer reconnects before channel is accepted") { f =>
    import f._
    val probe = TestProbe()
    val open = createOpenChannelMessage()
    system.eventStream.subscribe(probe.ref, classOf[ChannelAborted])
    connect(remoteNodeId, peer, peerConnection, switchboard)
    peer ! SpawnChannelNonInitiator(Left(open), ChannelConfig.standard, ChannelTypes.Standard(), None, ChannelCodecsSpec.localChannelParams, ActorRef.noSender)
    val channelAborted = probe.expectMsgType[ChannelAborted]
    assert(channelAborted.remoteNodeId == remoteNodeId)
    assert(channelAborted.channelId == open.temporaryChannelId)
  }

  test("abort incoming channel after funding timeout", Tag(ChannelStateTestsTags.DualFunding), Tag("channel_funding_timeout")) { f =>
    import f._

    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, DualFunding -> Optional)))
    val open = createOpenDualFundedChannelMessage()
    peerConnection.send(peer, open)
    assert(channel.expectMsgType[INPUT_INIT_CHANNEL_NON_INITIATOR].dualFunded)
    channel.expectMsg(open)
    channel.expectMsg(Channel.TickChannelOpenTimeout)
  }

  test("kill peer with no channels if connection dies before receiving `ConnectionReady`") { f =>
    import f._
    val probe = TestProbe()
    probe.watch(peer)
    switchboard.send(peer, Peer.Init(Set.empty, Map.empty))
    eventually {
      probe.send(peer, Peer.GetPeerInfo(None))
      assert(probe.expectMsgType[Peer.PeerInfo].state == Peer.DISCONNECTED)
    }
    // this will be sent if PeerConnection dies for any reason during the handshake
    peer ! ConnectionDown(peerConnection.ref)
    probe.expectTerminated(peer)
  }

  test("reject on-the-fly funding requests when from_future_htlc is disabled", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    // We make sure that from_future_htlc is disabled.
    nodeParams.onTheFlyFundingConfig.fromFutureHtlcFailed(randomBytes32(), randomKey().publicKey)
    assert(!nodeParams.onTheFlyFundingConfig.isFromFutureHtlcAllowed)

    // We reject requests using from_future_htlc.
    val paymentHash = randomBytes32()
    connect(remoteNodeId, peer, peerConnection, switchboard, remoteInit = protocol.Init(Features(StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, DualFunding -> Optional)))
    val requestFunds = LiquidityAds.RequestFunding(50_000 sat, LiquidityAds.FundingRate(10_000 sat, 100_000 sat, 0, 0, 0 sat, 0 sat), LiquidityAds.PaymentDetails.FromFutureHtlc(paymentHash :: Nil))
    val open = inside(createOpenDualFundedChannelMessage()) { msg => msg.copy(tlvStream = TlvStream(ChannelTlv.RequestFundingTlv(requestFunds))) }
    peerConnection.send(peer, open)
    peerConnection.expectMsg(CancelOnTheFlyFunding(open.temporaryChannelId, paymentHash :: Nil, "payments paid with future HTLCs are currently disabled"))
    channel.expectNoMessage(100 millis)

    // Once enabled, we accept requests using from_future_htlc.
    nodeParams.onTheFlyFundingConfig.enableFromFutureHtlc()
    peerConnection.send(peer, open)
    channel.expectMsgType[INPUT_INIT_CHANNEL_NON_INITIATOR]
    channel.expectMsg(open)
  }

  test("store remote peer storage once we have channels") { f =>
    import f._

    // We connect with a previous backup.
    val channel = ChannelCodecsSpec.normal
    val peerConnection1 = peerConnection
    nodeParams.db.peers.updateStorage(remoteNodeId, hex"abcdef")
    connect(remoteNodeId, peer, peerConnection1, switchboard, channels = Set(channel), peerStorage = Some(hex"abcdef"))
    peer ! ChannelReadyForPayments(ActorRef.noSender, channel.remoteNodeId, channel.channelId, channel.commitments.latest.fundingTxIndex)
    peerConnection1.send(peer, PeerStorageStore(hex"deadbeef"))
    peerConnection1.send(peer, PeerStorageStore(hex"0123456789"))

    // We disconnect and reconnect, sending the last backup we received.
    val peerConnection2 = TestProbe()
    connect(remoteNodeId, peer, peerConnection2, switchboard, channels = Set(ChannelCodecsSpec.normal), initializePeer = false, peerStorage = Some(hex"0123456789"))
    peerConnection2.send(peer, PeerStorageStore(hex"1111"))

    // We reconnect again.
    val peerConnection3 = TestProbe()
    connect(remoteNodeId, peer, peerConnection3, switchboard, channels = Set(ChannelCodecsSpec.normal), initializePeer = false, peerStorage = Some(hex"1111"))
    // Because of the delayed writes, we may not have stored the latest value immediately, but we will eventually store it.
    eventually {
      assert(nodeParams.db.peers.getStorage(remoteNodeId).contains(hex"1111"))
    }

    // Our channel closes, so we stop storing backups for that peer.
    peer ! LocalChannelDown(ActorRef.noSender, channel.channelId, channel.lastAnnouncement_opt.map(_.shortChannelId).toSeq, channel.aliases, channel.remoteNodeId)
    peerConnection3.send(peer, PeerStorageStore(hex"2222"))
    assert(!peer.isTimerActive("peer-storage-write"))
    assert(nodeParams.db.peers.getStorage(remoteNodeId).contains(hex"1111"))
  }

  test("store remote features when channel confirms") { f =>
    import f._

    // When we make an outgoing connection, we store the peer details in our DB.
    assert(nodeParams.db.peers.getPeer(remoteNodeId).isEmpty)
    connect(remoteNodeId, peer, peerConnection, switchboard)
    val Some(nodeInfo1) = nodeParams.db.peers.getPeer(remoteNodeId)
    assert(nodeInfo1.features == TestConstants.Bob.nodeParams.features.initFeatures())
    assert(nodeInfo1.address_opt.contains(fakeIPAddress))

    // We disconnect and our peer connects to us: we don't have any channel, so we don't update the DB entry.
    val peerConnection2 = TestProbe()
    val address2 = Tor3("of7husrflx7sforh3fw6yqlpwstee3wg5imvvmkp4bz6rbjxtg5nljad", 9735)
    val remoteFeatures2 = Features(Features.ChannelType -> FeatureSupport.Mandatory).initFeatures()
    switchboard.send(peer, PeerConnection.ConnectionReady(peerConnection2.ref, remoteNodeId, address2, outgoing = false, protocol.Init(Features.empty), protocol.Init(remoteFeatures2)))
    val probe = TestProbe()
    probe.send(peer, Peer.GetPeerInfo(Some(probe.ref.toTyped)))
    assert(probe.expectMsgType[Peer.PeerInfo].address.contains(address2))
    assert(nodeParams.db.peers.getPeer(remoteNodeId).contains(nodeInfo1))

    // A channel is created, so we update the remote features in our DB.
    // We don't update the address because this was an incoming connection.
    peer ! ChannelReadyForPayments(ActorRef.noSender, remoteNodeId, randomBytes32(), 0)
    probe.send(peer, Peer.GetPeerInfo(Some(probe.ref.toTyped)))
    assert(probe.expectMsgType[Peer.PeerInfo].features.contains(remoteFeatures2))
    assert(nodeParams.db.peers.getPeer(remoteNodeId).contains(nodeInfo1.copy(features = remoteFeatures2)))
  }

  test("store remote features when channel confirms while disconnected") { f =>
    import f._

    // When we receive an incoming connection, we don't store the peer details in our DB.
    assert(nodeParams.db.peers.getPeer(remoteNodeId).isEmpty)
    switchboard.send(peer, Peer.Init(Set.empty, Map.empty))
    val localInit = protocol.Init(peer.underlyingActor.nodeParams.features.initFeatures())
    val remoteInit = protocol.Init(TestConstants.Bob.nodeParams.features.initFeatures())
    switchboard.send(peer, PeerConnection.ConnectionReady(peerConnection.ref, remoteNodeId, fakeIPAddress, outgoing = false, localInit, remoteInit))
    val probe = TestProbe()
    probe.send(peer, Peer.GetPeerInfo(Some(probe.ref.toTyped)))
    assert(probe.expectMsgType[Peer.PeerInfo].state == Peer.CONNECTED)
    assert(nodeParams.db.peers.getPeer(remoteNodeId).isEmpty)

    // Our peer wants to open a channel to us, but we disconnect before we have a confirmed channel.
    peer ! SpawnChannelNonInitiator(Left(createOpenChannelMessage()), ChannelConfig.standard, ChannelTypes.Standard(), None, ChannelCodecsSpec.localChannelParams, peerConnection.ref)
    peer ! Peer.ConnectionDown(peerConnection.ref)
    probe.send(peer, Peer.GetPeerInfo(Some(probe.ref.toTyped)))
    assert(probe.expectMsgType[Peer.PeerInfo].state == Peer.DISCONNECTED)
    assert(nodeParams.db.peers.getPeer(remoteNodeId).isEmpty)

    // The channel confirms, so we store the remote features in our DB.
    // We don't store the remote address because this was an incoming connection.
    peer ! ChannelReadyForPayments(ActorRef.noSender, remoteNodeId, randomBytes32(), 0)
    probe.send(peer, Peer.GetPeerInfo(Some(probe.ref.toTyped)))
    assert(probe.expectMsgType[Peer.PeerInfo].state == Peer.DISCONNECTED)
    assert(nodeParams.db.peers.getPeer(remoteNodeId).contains(NodeInfo(remoteInit.features, None)))
  }

  test("get remote features from DB") { f =>
    import f._

    // We have information about one of our peers in our DB.
    val nodeInfo = NodeInfo(TestConstants.Bob.nodeParams.features.initFeatures(), None)
    nodeParams.db.peers.addOrUpdatePeerFeatures(remoteNodeId, nodeInfo.features)

    // We initialize ourselves after a restart, but our peer doesn't reconnect immediately to us.
    switchboard.send(peer, Peer.Init(Set(ChannelCodecsSpec.normal), Map.empty))
    // When we request information about the peer, we will fetch it from the DB.
    val probe = TestProbe()
    probe.send(peer, Peer.GetPeerInfo(Some(probe.ref.toTyped)))
    val peerInfo = probe.expectMsgType[Peer.PeerInfo]
    assert(peerInfo.state == Peer.DISCONNECTED)
    assert(peerInfo.address.isEmpty)
    assert(peerInfo.features.contains(nodeInfo.features))
    assert(nodeParams.db.peers.getPeer(remoteNodeId).contains(nodeInfo))
  }

}

object PeerSpec {

  val fakeIPAddress: NodeAddress = NodeAddress.fromParts("1.2.3.4", 42000).get

  def createMockServer(): (ServerSocketChannel, InetSocketAddress) = {
    val mockServer = ServerSocketChannel.open()
    // NB: we force 127.0.0.1 (IPv4) because there are issues on ubuntu build machines with IPv6 loopback
    mockServer.bind(new InetSocketAddress("127.0.0.1", 0))
    mockServer.configureBlocking(false)
    (mockServer, mockServer.getLocalAddress.asInstanceOf[InetSocketAddress])
  }

  def createOpenChannelMessage(openTlv: TlvStream[OpenChannelTlv] = TlvStream.empty): protocol.OpenChannel = {
    protocol.OpenChannel(Block.RegtestGenesisBlock.hash, randomBytes32(), 250_000 sat, 0 msat, 483 sat, UInt64(100), 1000 sat, 1 msat, TestConstants.feeratePerKw, CltvExpiryDelta(144), 10, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, ChannelFlags(announceChannel = false), openTlv)
  }

  def createOpenDualFundedChannelMessage(): protocol.OpenDualFundedChannel = {
    protocol.OpenDualFundedChannel(Block.RegtestGenesisBlock.hash, randomBytes32(), TestConstants.feeratePerKw, TestConstants.anchorOutputsFeeratePerKw, 250_000 sat, 483 sat, UInt64(100), 1 msat, CltvExpiryDelta(144), 10, 0, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, ChannelFlags(announceChannel = false))
  }

}