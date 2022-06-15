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

package fr.acinq.eclair.router

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.Script.{pay2wsh, write}
import fr.acinq.bitcoin.scalacompat.{Block, SatoshiLong, Transaction, TxOut}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{UtxoStatus, ValidateRequest, ValidateResult}
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.router.Announcements.{makeChannelAnnouncement, makeChannelUpdate, makeNodeAnnouncement}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol.Color
import org.scalatest.funsuite.AnyFunSuiteLike

class FrontRouterSpec extends TestKit(ActorSystem("test")) with AnyFunSuiteLike {

  import FrontRouterSpec._

  test("correctly dispatch valid gossip") {
    val nodeParams = Alice.nodeParams

    val watcher = TestProbe()
    val router = system.actorOf(Router.props(nodeParams, watcher.ref))

    val system1 = ActorSystem("front-system-1")
    val system2 = ActorSystem("front-system-2")
    val system3 = ActorSystem("front-system-3")

    // we use those to control messages exchanged between front and back routers
    val pipe1 = TestProbe()
    val pipe2 = TestProbe()
    val pipe3 = TestProbe()

    val front1 = system1.actorOf(FrontRouter.props(nodeParams.routerConf, pipe1.ref))
    val front2 = system2.actorOf(FrontRouter.props(nodeParams.routerConf, pipe2.ref))
    val front3 = system3.actorOf(FrontRouter.props(nodeParams.routerConf, pipe3.ref))

    pipe1.expectMsg(GetRoutingStateStreaming)
    pipe1.send(router, GetRoutingStateStreaming)
    pipe1.expectMsg(RoutingStateStreamingUpToDate)
    pipe1.forward(front1)

    pipe2.expectMsg(GetRoutingStateStreaming)
    pipe2.send(router, GetRoutingStateStreaming)
    pipe2.expectMsg(RoutingStateStreamingUpToDate)
    pipe2.forward(front2)

    pipe3.expectMsg(GetRoutingStateStreaming)
    pipe3.send(router, GetRoutingStateStreaming)
    pipe3.expectMsg(RoutingStateStreamingUpToDate)
    pipe3.forward(front3)

    val peerConnection1a = TestProbe()
    val peerConnection1b = TestProbe()
    val peerConnection2a = TestProbe()
    val peerConnection3a = TestProbe()

    system1.eventStream.subscribe(peerConnection1a.ref, classOf[Rebroadcast])
    system1.eventStream.subscribe(peerConnection1b.ref, classOf[Rebroadcast])
    system2.eventStream.subscribe(peerConnection2a.ref, classOf[Rebroadcast])
    system3.eventStream.subscribe(peerConnection3a.ref, classOf[Rebroadcast])

    val origin1a = RemoteGossip(peerConnection1a.ref, randomKey().publicKey)
    val origin1b = RemoteGossip(peerConnection1b.ref, randomKey().publicKey)
    val origin2a = RemoteGossip(peerConnection2a.ref, randomKey().publicKey)

    peerConnection1a.send(front1, PeerRoutingMessage(peerConnection1a.ref, origin1a.nodeId, chan_ab))
    pipe1.expectMsg(PeerRoutingMessage(front1, origin1a.nodeId, chan_ab))
    pipe1.send(router, PeerRoutingMessage(pipe1.ref, origin1a.nodeId, chan_ab))

    assert(watcher.expectMsgType[ValidateRequest].ann == chan_ab)

    peerConnection1b.send(front1, PeerRoutingMessage(peerConnection1b.ref, origin1b.nodeId, chan_ab))
    pipe1.expectNoMessage()

    peerConnection2a.send(front2, PeerRoutingMessage(peerConnection2a.ref, origin2a.nodeId, chan_ab))
    pipe2.expectMsg(PeerRoutingMessage(front2, origin2a.nodeId, chan_ab))
    pipe2.send(router, PeerRoutingMessage(pipe2.ref, origin2a.nodeId, chan_ab))
    pipe2.expectMsg(TransportHandler.ReadAck(chan_ab))

    pipe1.expectNoMessage()
    pipe2.expectNoMessage()

    watcher.send(router, ValidateResult(chan_ab, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_b)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
    pipe1.expectMsg(TransportHandler.ReadAck(chan_ab))

    pipe1.expectMsg(GossipDecision.Accepted(chan_ab))
    pipe1.forward(front1)
    pipe1.expectMsg(ChannelsDiscovered(Seq(SingleChannelDiscovered(chan_ab, 1000000 sat, None, None))))
    pipe1.forward(front1)
    pipe2.expectMsg(GossipDecision.Accepted(chan_ab))
    pipe2.forward(front2)
    pipe2.expectMsg(ChannelsDiscovered(Seq(SingleChannelDiscovered(chan_ab, 1000000 sat, None, None))))
    pipe2.forward(front2)
    pipe3.expectMsg(ChannelsDiscovered(Seq(SingleChannelDiscovered(chan_ab, 1000000 sat, None, None))))
    pipe3.forward(front3)

    pipe1.expectNoMessage()
    pipe2.expectNoMessage()
    pipe3.expectNoMessage()

    peerConnection1a.expectMsg(TransportHandler.ReadAck(chan_ab))
    peerConnection1b.expectMsg(TransportHandler.ReadAck(chan_ab))
    peerConnection2a.expectMsg(TransportHandler.ReadAck(chan_ab))

    peerConnection1a.expectMsg(GossipDecision.Accepted(chan_ab))
    peerConnection1b.expectMsg(GossipDecision.Accepted(chan_ab))
    peerConnection2a.expectMsg(GossipDecision.Accepted(chan_ab))

    // manual rebroadcast
    front1 ! Router.TickBroadcast
    peerConnection1a.expectMsg(Rebroadcast(channels = Map(chan_ab -> Set(origin1a, origin1b)), updates = Map.empty, nodes = Map.empty))
    peerConnection1b.expectMsg(Rebroadcast(channels = Map(chan_ab -> Set(origin1a, origin1b)), updates = Map.empty, nodes = Map.empty))
    front2 ! Router.TickBroadcast
    peerConnection2a.expectMsg(Rebroadcast(channels = Map(chan_ab -> Set(origin2a)), updates = Map.empty, nodes = Map.empty))
    front3 ! Router.TickBroadcast
    peerConnection3a.expectMsg(Rebroadcast(channels = Map(chan_ab -> Set.empty), updates = Map.empty, nodes = Map.empty))
  }

  test("aggregate gossip") {
    val nodeParams = Alice.nodeParams

    val watcher = TestProbe()
    val router = system.actorOf(Router.props(nodeParams, watcher.ref))

    val system1 = ActorSystem("front-system-1")
    val system2 = ActorSystem("front-system-2")
    val system3 = ActorSystem("front-system-3")

    val front1 = {
      implicit val system: ActorSystem = system1
      TestFSMRef[FrontRouter.State, FrontRouter.Data, FrontRouter](new FrontRouter(nodeParams.routerConf, router))
    }
    val front2 = {
      implicit val system: ActorSystem = system2
      TestFSMRef[FrontRouter.State, FrontRouter.Data, FrontRouter](new FrontRouter(nodeParams.routerConf, router))
    }
    val front3 = {
      implicit val system: ActorSystem = system3
      TestFSMRef[FrontRouter.State, FrontRouter.Data, FrontRouter](new FrontRouter(nodeParams.routerConf, router))
    }

    val peerConnection1a = TestProbe("peerconn-1a")
    val peerConnection1b = TestProbe("peerconn-1b")
    val peerConnection2a = TestProbe("peerconn-2a")
    val peerConnection3a = TestProbe("peerconn-3a")

    system1.eventStream.subscribe(peerConnection1a.ref, classOf[Rebroadcast])
    system1.eventStream.subscribe(peerConnection1b.ref, classOf[Rebroadcast])
    system2.eventStream.subscribe(peerConnection2a.ref, classOf[Rebroadcast])
    system3.eventStream.subscribe(peerConnection3a.ref, classOf[Rebroadcast])

    val origin1a = RemoteGossip(peerConnection1a.ref, randomKey().publicKey)
    val origin1b = RemoteGossip(peerConnection1b.ref, randomKey().publicKey)
    val origin2a = RemoteGossip(peerConnection2a.ref, randomKey().publicKey)
    val origin3a = RemoteGossip(peerConnection3a.ref, randomKey().publicKey)

    peerConnection1a.send(front1, PeerRoutingMessage(peerConnection1a.ref, origin1a.nodeId, chan_ab))
    assert(watcher.expectMsgType[ValidateRequest].ann == chan_ab)
    peerConnection1b.send(front1, PeerRoutingMessage(peerConnection1b.ref, origin1b.nodeId, chan_ab))
    peerConnection2a.send(front2, PeerRoutingMessage(peerConnection2a.ref, origin2a.nodeId, chan_ab))

    peerConnection1a.send(front1, PeerRoutingMessage(peerConnection1a.ref, origin1a.nodeId, ann_c))
    peerConnection1a.expectMsg(TransportHandler.ReadAck(ann_c))
    peerConnection1a.expectMsg(GossipDecision.NoKnownChannel(ann_c))
    peerConnection3a.send(front3, PeerRoutingMessage(peerConnection3a.ref, origin3a.nodeId, ann_a))
    peerConnection3a.send(front3, PeerRoutingMessage(peerConnection3a.ref, origin3a.nodeId, channelUpdate_ba))
    peerConnection3a.send(front3, PeerRoutingMessage(peerConnection3a.ref, origin3a.nodeId, channelUpdate_bc))
    peerConnection3a.expectMsg(TransportHandler.ReadAck(channelUpdate_bc))
    peerConnection3a.expectMsg(GossipDecision.NoRelatedChannel(channelUpdate_bc))

    watcher.send(router, ValidateResult(chan_ab, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_b)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))

    peerConnection1a.expectMsg(TransportHandler.ReadAck(chan_ab))
    peerConnection1b.expectMsg(TransportHandler.ReadAck(chan_ab))
    peerConnection2a.expectMsg(TransportHandler.ReadAck(chan_ab))

    peerConnection1a.expectMsg(GossipDecision.Accepted(chan_ab))
    peerConnection1b.expectMsg(GossipDecision.Accepted(chan_ab))
    peerConnection2a.expectMsg(GossipDecision.Accepted(chan_ab))

    peerConnection3a.expectMsg(TransportHandler.ReadAck(channelUpdate_ba))
    peerConnection3a.expectMsg(GossipDecision.Accepted(channelUpdate_ba))

    peerConnection3a.expectMsg(TransportHandler.ReadAck(ann_a))
    peerConnection3a.expectMsg(GossipDecision.Accepted(ann_a))

    peerConnection1b.send(front1, PeerRoutingMessage(peerConnection1b.ref, origin1b.nodeId, channelUpdate_ab))
    peerConnection1b.expectMsg(TransportHandler.ReadAck(channelUpdate_ab))
    peerConnection1b.expectMsg(GossipDecision.Accepted(channelUpdate_ab))

    peerConnection3a.send(front3, PeerRoutingMessage(peerConnection3a.ref, origin3a.nodeId, ann_b))
    peerConnection3a.expectMsg(TransportHandler.ReadAck(ann_b))
    peerConnection3a.expectMsg(GossipDecision.Accepted(ann_b))

    awaitCond(front1.stateData.nodes.size == 2)
    awaitCond(front2.stateData.nodes.size == 2)
    awaitCond(front3.stateData.nodes.size == 2)

    // manual rebroadcast
    front1 ! Router.TickBroadcast
    peerConnection1a.expectMsg(Rebroadcast(channels = Map(chan_ab -> Set(origin1a, origin1b)), updates = Map(channelUpdate_ab -> Set(origin1b), channelUpdate_ba -> Set.empty), nodes = Map(ann_a -> Set.empty, ann_b -> Set.empty)))
    peerConnection1b.expectMsg(Rebroadcast(channels = Map(chan_ab -> Set(origin1a, origin1b)), updates = Map(channelUpdate_ab -> Set(origin1b), channelUpdate_ba -> Set.empty), nodes = Map(ann_a -> Set.empty, ann_b -> Set.empty)))
    front2 ! Router.TickBroadcast
    peerConnection2a.expectMsg(Rebroadcast(channels = Map(chan_ab -> Set(origin2a)), updates = Map(channelUpdate_ab -> Set.empty, channelUpdate_ba -> Set.empty), nodes = Map(ann_a -> Set.empty, ann_b -> Set.empty)))
    front3 ! Router.TickBroadcast
    peerConnection3a.expectMsg(Rebroadcast(channels = Map(chan_ab -> Set.empty), updates = Map(channelUpdate_ab -> Set.empty, channelUpdate_ba -> Set(origin3a)), nodes = Map(ann_a -> Set(origin3a), ann_b -> Set(origin3a))))
  }

  test("do not forward duplicate gossip") {
    val nodeParams = Alice.nodeParams
    val router = TestProbe()
    val system1 = ActorSystem("front-system-1")
    val front1 = system1.actorOf(FrontRouter.props(nodeParams.routerConf, router.ref))
    router.expectMsg(GetRoutingStateStreaming)
    router.send(front1, RoutingStateStreamingUpToDate)

    val peerConnection1 = TestProbe()
    system1.eventStream.subscribe(peerConnection1.ref, classOf[Rebroadcast])

    val origin1 = RemoteGossip(peerConnection1.ref, randomKey().publicKey)

    peerConnection1.send(front1, PeerRoutingMessage(peerConnection1.ref, origin1.nodeId, chan_ab))
    router.expectMsg(PeerRoutingMessage(front1, origin1.nodeId, chan_ab))
    router.send(front1, TransportHandler.ReadAck(chan_ab))
    peerConnection1.expectNoMessage()
    router.send(front1, GossipDecision.Accepted(chan_ab))
    peerConnection1.expectMsg(TransportHandler.ReadAck(chan_ab))
    peerConnection1.expectMsg(GossipDecision.Accepted(chan_ab))
    router.send(front1, ChannelsDiscovered(SingleChannelDiscovered(chan_ab, 0.sat, None, None) :: Nil))

    peerConnection1.send(front1, PeerRoutingMessage(peerConnection1.ref, origin1.nodeId, chan_ab))
    router.expectNoMessage() // announcement is pending rebroadcast
    peerConnection1.expectMsg(TransportHandler.ReadAck(chan_ab))

    router.send(front1, TickBroadcast)
    peerConnection1.expectMsg(Rebroadcast(channels = Map(chan_ab -> Set(origin1)), updates = Map.empty, nodes = Map.empty))

    peerConnection1.send(front1, PeerRoutingMessage(peerConnection1.ref, origin1.nodeId, chan_ab))
    router.expectNoMessage() // announcement is already known
    peerConnection1.expectMsg(TransportHandler.ReadAck(chan_ab))
  }

  test("acknowledge duplicate gossip") {
    val nodeParams = Alice.nodeParams
    val router = TestProbe()
    val system1 = ActorSystem("front-system-1")
    val front1 = system1.actorOf(FrontRouter.props(nodeParams.routerConf, router.ref))
    router.expectMsg(GetRoutingStateStreaming)
    router.send(front1, RoutingStateStreamingUpToDate)

    val peerConnection1 = TestProbe()
    system1.eventStream.subscribe(peerConnection1.ref, classOf[Rebroadcast])

    val origin1 = RemoteGossip(peerConnection1.ref, randomKey().publicKey)

    // first message arrives and is forwarded to router
    peerConnection1.send(front1, PeerRoutingMessage(peerConnection1.ref, origin1.nodeId, chan_ab))
    router.expectMsg(PeerRoutingMessage(front1, origin1.nodeId, chan_ab))
    peerConnection1.expectNoMessage()
    // duplicate message is immediately acknowledged
    peerConnection1.send(front1, PeerRoutingMessage(peerConnection1.ref, origin1.nodeId, chan_ab))
    peerConnection1.expectMsg(TransportHandler.ReadAck(chan_ab))
    // router acknowledges the first message
    router.send(front1, TransportHandler.ReadAck(chan_ab))
    // but we still wait for the decision before acking the original message
    peerConnection1.expectNoMessage()
    // decision arrives, message is acknowledged
    router.send(front1, GossipDecision.Accepted(chan_ab))
    peerConnection1.expectMsg(TransportHandler.ReadAck(chan_ab))
    peerConnection1.expectMsg(GossipDecision.Accepted(chan_ab))
  }

  test("do not rebroadcast channel_update for private channels") {
    val nodeParams = Alice.nodeParams
    val router = TestProbe()
    val system1 = ActorSystem("front-system-1")
    val front1 = system1.actorOf(FrontRouter.props(nodeParams.routerConf, router.ref))
    router.expectMsg(GetRoutingStateStreaming)
    router.send(front1, RoutingStateStreamingUpToDate)

    val peerConnection1 = TestProbe()
    system1.eventStream.subscribe(peerConnection1.ref, classOf[Rebroadcast])

    val origin1 = RemoteGossip(peerConnection1.ref, randomKey().publicKey)

    // channel_update arrives and is forwarded to router (there is no associated channel, because it is private)
    peerConnection1.send(front1, PeerRoutingMessage(peerConnection1.ref, origin1.nodeId, channelUpdate_ab))
    router.expectMsg(PeerRoutingMessage(front1, origin1.nodeId, channelUpdate_ab))
    peerConnection1.expectNoMessage()
    // router acknowledges the message
    router.send(front1, TransportHandler.ReadAck(channelUpdate_ab))
    // but we still wait for the decision before acking the original message
    peerConnection1.expectNoMessage()
    // decision arrives, message is acknowledged
    router.send(front1, GossipDecision.Accepted(channelUpdate_ab))
    peerConnection1.expectMsg(TransportHandler.ReadAck(channelUpdate_ab))
    peerConnection1.expectMsg(GossipDecision.Accepted(channelUpdate_ab))
    // then the event arrives
    front1 ! ChannelUpdatesReceived(channelUpdate_ab :: Nil)
    // rebroadcast
    front1 ! TickBroadcast
    peerConnection1.expectNoMessage()
  }

}

object FrontRouterSpec {
  val (priv_a, priv_b, priv_c, priv_d, priv_e, priv_f) = (randomKey(), randomKey(), randomKey(), randomKey(), randomKey(), randomKey())
  val (a, b, c, d, e, f) = (priv_a.publicKey, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey, priv_f.publicKey)

  val (priv_funding_a, priv_funding_b, priv_funding_c, priv_funding_d, priv_funding_e, priv_funding_f) = (randomKey(), randomKey(), randomKey(), randomKey(), randomKey(), randomKey())
  val (funding_a, funding_b, funding_c, funding_d, funding_e, funding_f) = (priv_funding_a.publicKey, priv_funding_b.publicKey, priv_funding_c.publicKey, priv_funding_d.publicKey, priv_funding_e.publicKey, priv_funding_f.publicKey)

  val ann_a = makeNodeAnnouncement(priv_a, "node-A", Color(15, 10, -70), Nil, Features(Features.VariableLengthOnion -> FeatureSupport.Optional))
  val ann_b = makeNodeAnnouncement(priv_b, "node-B", Color(50, 99, -80), Nil, Features.empty)
  val ann_c = makeNodeAnnouncement(priv_c, "node-C", Color(123, 100, -40), Nil, Features(Features.VariableLengthOnion -> FeatureSupport.Optional))
  val ann_d = makeNodeAnnouncement(priv_d, "node-D", Color(-120, -20, 60), Nil, Features.empty)
  val ann_e = makeNodeAnnouncement(priv_e, "node-E", Color(-50, 0, 10), Nil, Features.empty)
  val ann_f = makeNodeAnnouncement(priv_f, "node-F", Color(30, 10, -50), Nil, Features.empty)

  val channelId_ab = RealShortChannelId(BlockHeight(420000), 1, 0)
  val channelId_bc = RealShortChannelId(BlockHeight(420000), 2, 0)
  val channelId_cd = RealShortChannelId(BlockHeight(420000), 3, 0)
  val channelId_ef = RealShortChannelId(BlockHeight(420000), 4, 0)

  def channelAnnouncement(shortChannelId: RealShortChannelId, node1_priv: PrivateKey, node2_priv: PrivateKey, funding1_priv: PrivateKey, funding2_priv: PrivateKey) = {
    val witness = Announcements.generateChannelAnnouncementWitness(Block.RegtestGenesisBlock.hash, shortChannelId, node1_priv.publicKey, node2_priv.publicKey, funding1_priv.publicKey, funding2_priv.publicKey, Features.empty)
    val node1_sig = Announcements.signChannelAnnouncement(witness, node1_priv)
    val funding1_sig = Announcements.signChannelAnnouncement(witness, funding1_priv)
    val node2_sig = Announcements.signChannelAnnouncement(witness, node2_priv)
    val funding2_sig = Announcements.signChannelAnnouncement(witness, funding2_priv)
    makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, shortChannelId, node1_priv.publicKey, node2_priv.publicKey, funding1_priv.publicKey, funding2_priv.publicKey, node1_sig, node2_sig, funding1_sig, funding2_sig)
  }

  val chan_ab = channelAnnouncement(channelId_ab, priv_a, priv_b, priv_funding_a, priv_funding_b)
  val chan_bc = channelAnnouncement(channelId_bc, priv_b, priv_c, priv_funding_b, priv_funding_c)
  val chan_cd = channelAnnouncement(channelId_cd, priv_c, priv_d, priv_funding_c, priv_funding_d)
  val chan_ef = channelAnnouncement(channelId_ef, priv_e, priv_f, priv_funding_e, priv_funding_f)

  val channelUpdate_ab = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, b, channelId_ab, CltvExpiryDelta(7), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 10, htlcMaximumMsat = 500000000 msat)
  val channelUpdate_ba = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, a, channelId_ab, CltvExpiryDelta(7), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 10, htlcMaximumMsat = 500000000 msat)
  val channelUpdate_bc = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, CltvExpiryDelta(5), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 1, htlcMaximumMsat = 500000000 msat)
  val channelUpdate_cb = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, b, channelId_bc, CltvExpiryDelta(5), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 1, htlcMaximumMsat = 500000000 msat)
  val channelUpdate_cd = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, d, channelId_cd, CltvExpiryDelta(3), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 4, htlcMaximumMsat = 500000000 msat)
  val channelUpdate_dc = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_d, c, channelId_cd, CltvExpiryDelta(3), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 4, htlcMaximumMsat = 500000000 msat)
  val channelUpdate_ef = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_e, f, channelId_ef, CltvExpiryDelta(9), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 8, htlcMaximumMsat = 500000000 msat)
  val channelUpdate_fe = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_f, e, channelId_ef, CltvExpiryDelta(9), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 8, htlcMaximumMsat = 500000000 msat)
}
