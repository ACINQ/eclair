package fr.acinq.eclair.io

import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.actor.{Actor, ActorContext, ActorRef, Props}
import akka.testkit.{TestActorRef, TestProbe}
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, OutPoint, Satoshi, TxHash, TxOut}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.TestConstants._
import fr.acinq.eclair.channel.SpliceStatus.NoSplice
import fr.acinq.eclair.channel.{ChannelFeatures, ChannelFlags, ChannelIdAssigned, ChannelParams, CommitTxAndRemoteSig, Commitment, Commitments, DATA_NORMAL, LocalCommit, LocalParams, PersistentChannelData, RemoteCommit, RemoteParams, Upstream}
import fr.acinq.eclair.io.Peer.PeerNotFound
import fr.acinq.eclair.io.Switchboard._
import fr.acinq.eclair.payment.relay.{OnTheFlyFunding, OnTheFlyFundingSpec}
import fr.acinq.eclair.transactions.Transactions.{CommitTx, InputInfo}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, Features, InitFeature, MilliSatoshiLong, NodeParams, TestKitBaseClass, TimestampSecondLong, UInt64, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits._

import java.util.UUID
import scala.concurrent.duration.DurationInt

class SwitchboardSpec extends TestKitBaseClass with AnyFunSuiteLike {

  import SwitchboardSpec._

  test("on initialization create peers") {
    val nodeParams = Alice.nodeParams
    val (probe, peer) = (TestProbe(), TestProbe())
    val remoteNodeId = ChannelCodecsSpec.normal.remoteNodeId
    // If we have a channel with that remote peer, we will automatically reconnect.

    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(List(ChannelCodecsSpec.normal))
    probe.expectMsg(remoteNodeId)
    peer.expectMsg(Peer.Init(Set(ChannelCodecsSpec.normal), Map.empty))
  }

  test("on initialization create peers with pending on-the-fly funding proposals") {
    val nodeParams = Alice.nodeParams

    // We have a channel with one of our peer, and a pending on-the-fly funding with them as well.
    val channel = ChannelCodecsSpec.normal
    val remoteNodeId1 = channel.remoteNodeId
    val paymentHash1 = randomBytes32()
    val pendingOnTheFly1 = OnTheFlyFunding.Pending(
      proposed = Seq(OnTheFlyFunding.Proposal(OnTheFlyFundingSpec.createWillAdd(10_000_000 msat, paymentHash1, CltvExpiry(600)), Upstream.Local(UUID.randomUUID()), Nil)),
      status = OnTheFlyFundingSpec.createStatus()
    )
    nodeParams.db.liquidity.addPendingOnTheFlyFunding(remoteNodeId1, pendingOnTheFly1)

    // We don't have channels yet with another of our peers, but we have a pending on-the-fly funding proposal.
    val remoteNodeId2 = randomKey().publicKey
    val paymentHash2 = randomBytes32()
    val pendingOnTheFly2 = OnTheFlyFunding.Pending(
      proposed = Seq(OnTheFlyFunding.Proposal(OnTheFlyFundingSpec.createWillAdd(5_000_000 msat, paymentHash2, CltvExpiry(600)), Upstream.Local(UUID.randomUUID()), Nil)),
      status = OnTheFlyFundingSpec.createStatus()
    )
    nodeParams.db.liquidity.addPendingOnTheFlyFunding(remoteNodeId2, pendingOnTheFly2)

    val (probe, peer) = (TestProbe(), TestProbe())
    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(List(channel))
    probe.expectMsgAllOf(remoteNodeId1, remoteNodeId2)
    probe.expectNoMessage(100 millis)
    peer.expectMsgAllOf(
      Peer.Init(Set(channel), Map(paymentHash1 -> pendingOnTheFly1)),
      Peer.Init(Set.empty, Map(paymentHash2 -> pendingOnTheFly2)),
    )
    peer.expectNoMessage(100 millis)
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
    peer.expectMsg(Peer.Init(Set.empty, Map.empty))
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
    peer.expectMsg(Peer.Init(Set.empty, Map.empty))
    peer.expectMsgType[Peer.Connect]

    val unknownNodeId = randomKey().publicKey
    probe.send(switchboard, Peer.Disconnect(unknownNodeId))
    probe.expectMsgType[PeerNotFound]
    probe.send(switchboard, Peer.Disconnect(remoteNodeId))
    peer.expectMsg(Peer.Disconnect(remoteNodeId))
  }

  def sendFeatures(nodeParams: NodeParams, channels: Seq[PersistentChannelData], remoteNodeId: PublicKey, expectedFeatures: Features[InitFeature], expectedSync: Boolean): Unit = {
    val (probe, peer, peerConnection) = (TestProbe(), TestProbe(), TestProbe())
    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(channels)
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, remoteNodeId, outgoing = true)
    val initConnection = peerConnection.expectMsgType[PeerConnection.InitializeConnection]
    assert(initConnection.chainHash == nodeParams.chainHash)
    assert(initConnection.features == expectedFeatures)
    assert(initConnection.doSync == expectedSync)
  }

  test("sync if no whitelist is defined and peer has channels") {
    val nodeParams = Alice.nodeParams.modify(_.routerConf.syncConf.whitelist).setTo(Set.empty)
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteNodeId
    sendFeatures(nodeParams, List(ChannelCodecsSpec.normal), remoteNodeId, nodeParams.features.initFeatures(), expectedSync = true)
  }

  test("sync if no whitelist is defined and peer creates a channel") {
    val nodeParams = Alice.nodeParams.modify(_.routerConf.syncConf.whitelist).setTo(Set.empty)
    val (probe, peer, peerConnection) = (TestProbe(), TestProbe(), TestProbe())
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteNodeId
    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(Nil)

    // We have a channel with our peer, so we trigger a sync when connecting.
    switchboard ! ChannelIdAssigned(TestProbe().ref, remoteNodeId, randomBytes32(), randomBytes32())
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, remoteNodeId, outgoing = true)
    val initConnection1 = peerConnection.expectMsgType[PeerConnection.InitializeConnection]
    assert(initConnection1.chainHash == nodeParams.chainHash)
    assert(initConnection1.features == nodeParams.features.initFeatures())
    assert(initConnection1.doSync)

    // We don't have channels with our peer, so we won't trigger a sync when connecting.
    switchboard ! LastChannelClosed(peer.ref, remoteNodeId)
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, remoteNodeId, outgoing = true)
    val initConnection2 = peerConnection.expectMsgType[PeerConnection.InitializeConnection]
    assert(initConnection2.chainHash == nodeParams.chainHash)
    assert(initConnection2.features == nodeParams.features.initFeatures())
    assert(!initConnection2.doSync)
  }

  test("don't sync if no whitelist is defined and peer does not have channels") {
    val nodeParams = Alice.nodeParams.modify(_.routerConf.syncConf.whitelist).setTo(Set.empty)
    sendFeatures(nodeParams, Nil, randomKey().publicKey, nodeParams.features.initFeatures(), expectedSync = false)
  }

  test("sync if whitelist contains peer") {
    val remoteNodeId = randomKey().publicKey
    val nodeParams = Alice.nodeParams.modify(_.routerConf.syncConf.whitelist).setTo(Set(remoteNodeId, randomKey().publicKey, randomKey().publicKey))
    sendFeatures(nodeParams, Nil, remoteNodeId, nodeParams.features.initFeatures(), expectedSync = true)
  }

  test("don't sync if whitelist doesn't contain peer") {
    val nodeParams = Alice.nodeParams.modify(_.routerConf.syncConf.whitelist).setTo(Set(randomKey().publicKey, randomKey().publicKey, randomKey().publicKey)).modify(_.routerConf.syncConf.peerLimit).setTo(0)
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteNodeId
    sendFeatures(nodeParams, List(ChannelCodecsSpec.normal), remoteNodeId, nodeParams.features.initFeatures(), expectedSync = false)
  }

  def dummyDataNormal(capacity: Satoshi, remoteNodeId: PublicKey): DATA_NORMAL = {
    val channelFeatures = ChannelFeatures(Features.DualFunding)
    val localParams = LocalParams(nodeId = null, fundingKeyPath = null, dustLimit = null, maxHtlcValueInFlightMsat = 1000 msat, initialRequestedChannelReserve_opt = None, htlcMinimum = null, toSelfDelay = null, maxAcceptedHtlcs = 1, isChannelOpener = true, paysCommitTxFees = true, upfrontShutdownScript_opt = null, walletStaticPaymentBasepoint = None, initFeatures = null)
    val remoteParams = RemoteParams(nodeId = remoteNodeId, dustLimit = null, maxHtlcValueInFlightMsat = UInt64(1000), initialRequestedChannelReserve_opt = None, htlcMinimum = null, toSelfDelay = null, maxAcceptedHtlcs = 1, revocationBasepoint = null, paymentBasepoint = null, delayedPaymentBasepoint = null, htlcBasepoint = null, initFeatures = null, upfrontShutdownScript_opt = null)
    val channelFlags = ChannelFlags(announceChannel = true)
    val params = ChannelParams(channelId = null, channelConfig = null, channelFeatures = channelFeatures, localParams = localParams, remoteParams = remoteParams, channelFlags = channelFlags)
    val commitTx = CommitTx(input = InputInfo(outPoint = OutPoint(hash= TxHash(ByteVector32.Zeroes), index = 0), txOut = TxOut(amount = capacity, publicKeyScript = ByteVector.empty), redeemScript = ByteVector.empty), tx = null)
    val commitTxAndRemoteSig = CommitTxAndRemoteSig(commitTx, ByteVector64.Zeroes)
    val localCommit = LocalCommit(index = 0, spec = null, commitTxAndRemoteSig = commitTxAndRemoteSig, htlcTxsAndRemoteSigs = null)
    val remoteCommit = RemoteCommit(index = 0, spec = null, txid = null, remotePerCommitmentPoint = null)
    val active = Commitment(fundingTxIndex = 0, firstRemoteCommitIndex = 0, remoteFundingPubKey = null, localFundingStatus = null, remoteFundingStatus = null, localCommit = localCommit, remoteCommit = remoteCommit, nextRemoteCommit_opt = null)
    val commitments = Commitments(params = params, changes = null, active = List(active), inactive = Nil, remoteNextCommitInfo = null, remotePerCommitmentSecrets = null, originChannels = null, remoteChannelData_opt = null)
    DATA_NORMAL(commitments = commitments, aliases = null, lastAnnouncement_opt = None, channelUpdate = null, localShutdown = null, remoteShutdown = null, closingFeerates = null, spliceStatus = NoSplice)
  }

  test("only sync with top peers if no whitelist") {
    val (alice, bob, carol, dave) = (randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey)
    val nodeParams = Alice.nodeParams.modify(_.routerConf.syncConf.whitelist).setTo(Set.empty).modify(_.routerConf.syncConf.peerLimit).setTo(2)
    val (probe, peer, peerConnection) = (TestProbe(), TestProbe(), TestProbe())
    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(List(
      dummyDataNormal(Satoshi(500), alice),
      dummyDataNormal(Satoshi(600), alice),
      dummyDataNormal(Satoshi(1000), bob),
      dummyDataNormal(Satoshi(2000), carol),
    ))

    switchboard ! PeerConnection.Authenticated(peerConnection.ref, alice, outgoing = true)
    assert(peerConnection.expectMsgType[PeerConnection.InitializeConnection].doSync)

    switchboard ! PeerConnection.Authenticated(peerConnection.ref, bob, outgoing = true)
    assert(!peerConnection.expectMsgType[PeerConnection.InitializeConnection].doSync)

    switchboard ! PeerConnection.Authenticated(peerConnection.ref, carol, outgoing = true)
    assert(peerConnection.expectMsgType[PeerConnection.InitializeConnection].doSync)

    switchboard ! PeerConnection.Authenticated(peerConnection.ref, dave, outgoing = true)
    assert(!peerConnection.expectMsgType[PeerConnection.InitializeConnection].doSync)
  }

  test("sync with top peers and whitelisted peers") {
    val (alice, bob, carol, dave) = (randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey)
    val nodeParams = Alice.nodeParams.modify(_.routerConf.syncConf.whitelist).setTo(Set(dave)).modify(_.routerConf.syncConf.peerLimit).setTo(1)
    val (probe, peer, peerConnection) = (TestProbe(), TestProbe(), TestProbe())
    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(List(
      dummyDataNormal(Satoshi(500), alice),
      dummyDataNormal(Satoshi(600), alice),
      dummyDataNormal(Satoshi(1000), bob),
      dummyDataNormal(Satoshi(2000), carol),
    ))

    switchboard ! PeerConnection.Authenticated(peerConnection.ref, alice, outgoing = true)
    assert(!peerConnection.expectMsgType[PeerConnection.InitializeConnection].doSync)

    switchboard ! PeerConnection.Authenticated(peerConnection.ref, bob, outgoing = true)
    assert(!peerConnection.expectMsgType[PeerConnection.InitializeConnection].doSync)

    switchboard ! PeerConnection.Authenticated(peerConnection.ref, carol, outgoing = true)
    assert(peerConnection.expectMsgType[PeerConnection.InitializeConnection].doSync)

    switchboard ! PeerConnection.Authenticated(peerConnection.ref, dave, outgoing = true)
    assert(peerConnection.expectMsgType[PeerConnection.InitializeConnection].doSync)
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

  test("track nodes with incoming connections that do not have a channel") {
    val nodeParams = Alice.nodeParams.copy(peerConnectionConf = Alice.nodeParams.peerConnectionConf.copy(maxNoChannels = 2))
    val (probe, peer, peerConnection, channel) = (TestProbe(), TestProbe(), TestProbe(), TestProbe())
    val hasChannelsNodeId1 = randomKey().publicKey
    val hasChannelsNodeId2 = randomKey().publicKey
    val unknownNodeId1 = randomKey().publicKey
    val unknownNodeId2 = randomKey().publicKey
    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(probe, peer)))
    switchboard ! Switchboard.Init(Nil)

    // Do not track nodes we connect to.
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, randomKey().publicKey, outgoing = true)
    peer.expectMsgType[Peer.Init]

    // Do not track an incoming connection from a peer we have a channel with.
    switchboard ! ChannelIdAssigned(channel.ref, hasChannelsNodeId1, randomBytes32(), randomBytes32())
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, hasChannelsNodeId1, outgoing = false)
    peer.expectMsgType[Peer.Init]

    // We do not yet have channels with these peers, so we track their incoming connections.
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, unknownNodeId1, outgoing = false)
    peer.expectMsgType[Peer.Init]
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, unknownNodeId2, outgoing = false)
    peer.expectMsgType[Peer.Init]

    // Do not disconnect an old peer when a new peer with channels connects.
    switchboard ! ChannelIdAssigned(channel.ref, hasChannelsNodeId2, randomBytes32(), randomBytes32())
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, hasChannelsNodeId2, outgoing = false)
    peer.expectMsgType[Peer.Init]
    peer.expectNoMessage(100 millis)

    // Disconnect the oldest tracked peer when an incoming connection from a peer without channels connects.
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, randomKey().publicKey, outgoing = false)
    peer.fishForMessage() {
      case d: Peer.Disconnect => d.nodeId == unknownNodeId1
      case _: Peer.Init => false
    }

    // Disconnect the next oldest tracked peer when an incoming connection from a peer without channels connects.
    switchboard ! PeerConnection.Authenticated(peerConnection.ref, randomKey().publicKey, outgoing = false)
    peer.fishForMessage() {
      case d: Peer.Disconnect => d.nodeId == unknownNodeId2
      case _: Peer.Init => false
    }
  }

  test("GetPeers should only return child nodes of type `Peer`") {
    val nodeParams = Alice.nodeParams.copy(peerConnectionConf = Alice.nodeParams.peerConnectionConf.copy(maxNoChannels = 2))
    val (peer, probe) = (TestProbe(), TestProbe())
    val remoteNodeId = ChannelCodecsSpec.normal.commitments.remoteNodeId
    val switchboard = TestActorRef(new Switchboard(nodeParams, FakePeerFactory(TestProbe(), peer)))
    switchboard ! Switchboard.Init(Nil)
    switchboard ! Peer.Connect(remoteNodeId, None, TestProbe().ref, isPersistent = true)
    peer.expectMsgType[Peer.Init]
    probe.send(switchboard, GetPeers)
    val peers = probe.expectMsgType[Iterable[ActorRef]]
    assert(peers.size == 1)
    assert(peers.head.path.name == peerActorName(remoteNodeId))
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
    def props(fwd: ActorRef): Props = Props(new DummyPeer(fwd))
  }

  case class FakePeerFactory(probe: TestProbe, peer: TestProbe) extends PeerFactory {
    override def spawn(context: ActorContext, remoteNodeId: PublicKey): ActorRef = {
      peer.send(probe.ref, remoteNodeId)
      context.actorOf(DummyPeer.props(peer.ref), peerActorName(remoteNodeId))
    }
  }

}