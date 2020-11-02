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

import akka.actor.Status
import akka.actor.Status.Failure
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{Block, Transaction, TxOut}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.{AvailableBalanceChanged, BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT, CommitmentsSpec, LocalChannelUpdate}
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Announcements.{makeChannelUpdate, makeNodeAnnouncement}
import fr.acinq.eclair.router.RouteCalculationSpec.{DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.{Color, QueryShortChannelIds}
import fr.acinq.eclair.{CltvExpiryDelta, Features, LongToBtcAmount, MilliSatoshi, ShortChannelId, TestConstants, randomKey}
import scodec.bits._

import scala.concurrent.duration._

/**
 * Created by PM on 29/08/2016.
 */

class RouterSpec extends BaseRouterSpec {

  test("properly announce valid new channels and ignore invalid ones") { fixture =>
    import fixture._
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])
    system.eventStream.subscribe(eventListener.ref, classOf[Rebroadcast])
    val peerConnection = TestProbe()

    {
      // valid channel announcement, no stashing
      val chan_ac = channelAnnouncement(ShortChannelId(420000, 5, 0), priv_a, priv_c, priv_funding_a, priv_funding_c)
      val update_ac = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, c, chan_ac.shortChannelId, CltvExpiryDelta(7), 0 msat, 766000 msat, 10, htlcMaximum)
      val node_c = makeNodeAnnouncement(priv_c, "node-C", Color(123, 100, -40), Nil, TestConstants.Bob.nodeParams.features, timestamp = System.currentTimeMillis.milliseconds.toSeconds + 1)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_ac))
      peerConnection.expectNoMsg(100 millis) // we don't immediately acknowledge the announcement (back pressure)
      watcher.expectMsg(ValidateRequest(chan_ac))
      watcher.send(router, ValidateResult(chan_ac, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_c)))) :: Nil, lockTime = 0), UtxoStatus.Unspent)))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_ac))
      peerConnection.expectMsg(GossipDecision.Accepted(chan_ac))
      assert(peerConnection.sender() == router)
      watcher.expectMsgType[WatchSpentBasic]
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ac))
      peerConnection.expectMsg(TransportHandler.ReadAck(update_ac))
      peerConnection.expectMsg(GossipDecision.Accepted(update_ac))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_c))
      peerConnection.expectMsg(TransportHandler.ReadAck(node_c))
      peerConnection.expectMsg(GossipDecision.Accepted(node_c))
      eventListener.expectMsg(ChannelsDiscovered(SingleChannelDiscovered(chan_ac, 1000000 sat, None, None) :: Nil))
      eventListener.expectMsg(ChannelUpdatesReceived(update_ac :: Nil))
      eventListener.expectMsg(NodeUpdated(node_c))
      peerConnection.expectNoMsg(100 millis)
      eventListener.expectNoMsg(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectMsgType[Rebroadcast]
    }

    {
      // valid channel announcement, stashing while validating channel announcement
      val priv_u = randomKey
      val priv_funding_u = randomKey
      val chan_uc = channelAnnouncement(ShortChannelId(420000, 100, 0), priv_u, priv_c, priv_funding_u, priv_funding_c)
      val update_uc = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_u, c, chan_uc.shortChannelId, CltvExpiryDelta(7), 0 msat, 766000 msat, 10, htlcMaximum)
      val node_u = makeNodeAnnouncement(priv_u, "node-U", Color(-120, -20, 60), Nil, Features.empty)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_uc))
      peerConnection.expectNoMsg(200 millis) // we don't immediately acknowledge the announcement (back pressure)
      watcher.expectMsg(ValidateRequest(chan_uc))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_uc))
      peerConnection.expectMsg(TransportHandler.ReadAck(update_uc))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_u))
      peerConnection.expectMsg(TransportHandler.ReadAck(node_u))
      watcher.send(router, ValidateResult(chan_uc, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(2000000 sat, write(pay2wsh(Scripts.multiSig2of2(priv_funding_u.publicKey, funding_c)))) :: Nil, lockTime = 0), UtxoStatus.Unspent)))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_uc))
      peerConnection.expectMsg(GossipDecision.Accepted(chan_uc))
      assert(peerConnection.sender() == router)
      watcher.expectMsgType[WatchSpentBasic]
      peerConnection.expectMsg(GossipDecision.Accepted(update_uc))
      peerConnection.expectMsg(GossipDecision.Accepted(node_u))
      eventListener.expectMsg(ChannelsDiscovered(SingleChannelDiscovered(chan_uc, 2000000 sat, None, None) :: Nil))
      eventListener.expectMsg(ChannelUpdatesReceived(update_uc :: Nil))
      eventListener.expectMsg(NodesDiscovered(node_u :: Nil))
      peerConnection.expectNoMsg(100 millis)
      eventListener.expectNoMsg(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectMsgType[Rebroadcast]
    }

    {
      // duplicates
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_b))
      peerConnection.expectMsg(TransportHandler.ReadAck(node_b))
      peerConnection.expectMsg(GossipDecision.Duplicate(node_b))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_ab))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_ab))
      peerConnection.expectMsg(GossipDecision.Duplicate(chan_ab))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ab))
      peerConnection.expectMsg(TransportHandler.ReadAck(update_ab))
      peerConnection.expectMsg(GossipDecision.Duplicate(update_ab))
      peerConnection.expectNoMsg(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMsg(100 millis)
    }

    {
      // invalid signatures
      val invalid_node_b = node_b.copy(timestamp = node_b.timestamp + 10)
      val invalid_chan_ac = channelAnnouncement(ShortChannelId(420000, 101, 1), priv_a, priv_c, priv_funding_a, priv_funding_c).copy(nodeId1 = randomKey.publicKey)
      val invalid_update_ab = update_ab.copy(cltvExpiryDelta = CltvExpiryDelta(21), timestamp = update_ab.timestamp + 1)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, invalid_node_b))
      peerConnection.expectMsg(TransportHandler.ReadAck(invalid_node_b))
      peerConnection.expectMsg(GossipDecision.InvalidSignature(invalid_node_b))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, invalid_chan_ac))
      peerConnection.expectMsg(TransportHandler.ReadAck(invalid_chan_ac))
      peerConnection.expectMsg(GossipDecision.InvalidSignature(invalid_chan_ac))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, invalid_update_ab))
      peerConnection.expectMsg(TransportHandler.ReadAck(invalid_update_ab))
      peerConnection.expectMsg(GossipDecision.InvalidSignature(invalid_update_ab))
      peerConnection.expectNoMsg(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMsg(100 millis)
    }

    {
      // pruned channel
      val priv_v = randomKey
      val priv_funding_v = randomKey
      val chan_vc = channelAnnouncement(ShortChannelId(420000, 102, 0), priv_v, priv_c, priv_funding_v, priv_funding_c)
      nodeParams.db.network.addToPruned(chan_vc.shortChannelId :: Nil)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_vc))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_vc))
      peerConnection.expectMsg(GossipDecision.ChannelPruned(chan_vc))
      peerConnection.expectNoMsg(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMsg(100 millis)
    }

    {
      // stale channel update
      val update_ab = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_b.publicKey, chan_ab.shortChannelId, CltvExpiryDelta(7), 0 msat, 766000 msat, 10, htlcMaximum, timestamp = (System.currentTimeMillis.milliseconds - 15.days).toSeconds)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ab))
      peerConnection.expectMsg(TransportHandler.ReadAck(update_ab))
      peerConnection.expectMsg(GossipDecision.Stale(update_ab))
      peerConnection.expectNoMsg(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMsg(100 millis)
    }

    {
      // unknown channel
      val priv_y = randomKey
      val update_ay = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_y.publicKey, ShortChannelId(4646464), CltvExpiryDelta(7), 0 msat, 766000 msat, 10, htlcMaximum)
      val node_y = makeNodeAnnouncement(priv_y, "node-Y", Color(123, 100, -40), Nil, TestConstants.Bob.nodeParams.features)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ay))
      peerConnection.expectMsg(TransportHandler.ReadAck(update_ay))
      peerConnection.expectMsg(GossipDecision.NoRelatedChannel(update_ay))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_y))
      peerConnection.expectMsg(TransportHandler.ReadAck(node_y))
      peerConnection.expectMsg(GossipDecision.NoKnownChannel(node_y))
      peerConnection.expectNoMsg(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMsg(100 millis)
    }

    {
      // invalid announcement + reject stashed
      val priv_y = randomKey
      val priv_funding_y = randomKey // a-y will have an invalid script
      val chan_ay = channelAnnouncement(ShortChannelId(42002), priv_a, priv_y, priv_funding_a, priv_funding_y)
      val update_ay = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_y.publicKey, chan_ay.shortChannelId, CltvExpiryDelta(7), 0 msat, 766000 msat, 10, htlcMaximum)
      val node_y = makeNodeAnnouncement(priv_y, "node-Y", Color(123, 100, -40), Nil, TestConstants.Bob.nodeParams.features)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_ay))
      watcher.expectMsg(ValidateRequest(chan_ay))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ay))
      peerConnection.expectMsg(TransportHandler.ReadAck(update_ay))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_y))
      peerConnection.expectMsg(TransportHandler.ReadAck(node_y))
      watcher.send(router, ValidateResult(chan_ay, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_a, randomKey.publicKey)))) :: Nil, lockTime = 0), UtxoStatus.Unspent)))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_ay))
      peerConnection.expectMsg(GossipDecision.InvalidAnnouncement(chan_ay))
      peerConnection.expectMsg(GossipDecision.NoRelatedChannel(update_ay))
      peerConnection.expectMsg(GossipDecision.NoKnownChannel(node_y))
      peerConnection.expectNoMsg(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMsg(100 millis)
    }

    {
      // validation failure
      val priv_x = randomKey
      val chan_ax = channelAnnouncement(ShortChannelId(42001), priv_a, priv_x, priv_funding_a, randomKey)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_ax))
      watcher.expectMsg(ValidateRequest(chan_ax))
      watcher.send(router, ValidateResult(chan_ax, Left(new RuntimeException("funding tx not found"))))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_ax))
      peerConnection.expectMsg(GossipDecision.ValidationFailure(chan_ax))
      peerConnection.expectNoMsg(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMsg(100 millis)
    }

    {
      // funding tx spent (funding tx not confirmed)
      val priv_z = randomKey
      val priv_funding_z = randomKey
      val chan_az = channelAnnouncement(ShortChannelId(42003), priv_a, priv_z, priv_funding_a, priv_funding_z)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_az))
      watcher.expectMsg(ValidateRequest(chan_az))
      watcher.send(router, ValidateResult(chan_az, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_a, priv_funding_z.publicKey)))) :: Nil, lockTime = 0), UtxoStatus.Spent(spendingTxConfirmed = false))))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_az))
      peerConnection.expectMsg(GossipDecision.ChannelClosing(chan_az))
      peerConnection.expectNoMsg(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMsg(100 millis)
    }

    {
      // funding tx spent (funding tx confirmed)
      val priv_z = randomKey
      val priv_funding_z = randomKey
      val chan_az = channelAnnouncement(ShortChannelId(42003), priv_a, priv_z, priv_funding_a, priv_funding_z)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_az))
      watcher.expectMsg(ValidateRequest(chan_az))
      watcher.send(router, ValidateResult(chan_az, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_a, priv_funding_z.publicKey)))) :: Nil, lockTime = 0), UtxoStatus.Spent(spendingTxConfirmed = true))))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_az))
      peerConnection.expectMsg(GossipDecision.ChannelClosed(chan_az))
      peerConnection.expectNoMsg(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMsg(100 millis)
    }

    watcher.expectNoMsg(100 millis)

  }

  test("properly announce lost channels and nodes") { fixture =>
    import fixture._
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])

    router ! WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(channelId_ab))
    eventListener.expectMsg(ChannelLost(channelId_ab))
    // a doesn't have any channels, b still has one with c
    eventListener.expectMsg(NodeLost(a))
    eventListener.expectNoMsg(200 milliseconds)

    router ! WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(channelId_cd))
    eventListener.expectMsg(ChannelLost(channelId_cd))
    // d doesn't have any channels, c still has one with b
    eventListener.expectMsg(NodeLost(d))
    eventListener.expectNoMsg(200 milliseconds)

    router ! WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(channelId_bc))
    eventListener.expectMsg(ChannelLost(channelId_bc))
    // now b and c do not have any channels
    eventListener.expectMsgAllOf(NodeLost(b), NodeLost(c))
    eventListener.expectNoMsg(200 milliseconds)

  }

  test("handle bad signature for ChannelAnnouncement") { fixture =>
    import fixture._
    val peerConnection = TestProbe()
    val channelId_ac = ShortChannelId(420000, 105, 0)
    val chan_ac = channelAnnouncement(channelId_ac, priv_a, priv_c, priv_funding_a, priv_funding_c)
    val buggy_chan_ac = chan_ac.copy(nodeSignature1 = chan_ac.nodeSignature2)
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, buggy_chan_ac))
    peerConnection.expectMsg(TransportHandler.ReadAck(buggy_chan_ac))
    peerConnection.expectMsg(GossipDecision.InvalidSignature(buggy_chan_ac))
  }

  test("handle bad signature for NodeAnnouncement") { fixture =>
    import fixture._
    val peerConnection = TestProbe()
    val buggy_ann_b = node_b.copy(signature = node_c.signature, timestamp = node_b.timestamp + 1)
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, buggy_ann_b))
    peerConnection.expectMsg(TransportHandler.ReadAck(buggy_ann_b))
    peerConnection.expectMsg(GossipDecision.InvalidSignature(buggy_ann_b))
  }

  test("handle bad signature for ChannelUpdate") { fixture =>
    import fixture._
    val peerConnection = TestProbe()
    val buggy_channelUpdate_ab = update_ab.copy(signature = node_b.signature, timestamp = update_ab.timestamp + 1)
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, buggy_channelUpdate_ab))
    peerConnection.expectMsg(TransportHandler.ReadAck(buggy_channelUpdate_ab))
    peerConnection.expectMsg(GossipDecision.InvalidSignature(buggy_channelUpdate_ab))
  }

  test("route not found (unreachable target)") { fixture =>
    import fixture._
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(a, f, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route not found (non-existing source)") { fixture =>
    import fixture._
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(randomKey.publicKey, f, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route not found (non-existing target)") { fixture =>
    import fixture._
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(a, randomKey.publicKey, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route found") { fixture =>
    import fixture._
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.routes.head.hops.map(_.nodeId).toList === a :: b :: c :: Nil)
    assert(res.routes.head.hops.last.nextNodeId === d)

    sender.send(router, RouteRequest(a, h, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    val res1 = sender.expectMsgType[RouteResponse]
    assert(res1.routes.head.hops.map(_.nodeId).toList === a :: g :: Nil)
    assert(res1.routes.head.hops.last.nextNodeId === h)
  }

  test("route found (with extra routing info)") { fixture =>
    import fixture._
    val sender = TestProbe()
    val x = PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73")
    val y = PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a")
    val z = PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484")
    val extraHop_cx = ExtraHop(c, ShortChannelId(1), 10 msat, 11, CltvExpiryDelta(12))
    val extraHop_xy = ExtraHop(x, ShortChannelId(2), 10 msat, 11, CltvExpiryDelta(12))
    val extraHop_yz = ExtraHop(y, ShortChannelId(3), 20 msat, 21, CltvExpiryDelta(22))
    sender.send(router, RouteRequest(a, z, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, assistedRoutes = Seq(extraHop_cx :: extraHop_xy :: extraHop_yz :: Nil)))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.routes.head.hops.map(_.nodeId).toList === a :: b :: c :: x :: y :: Nil)
    assert(res.routes.head.hops.last.nextNodeId === z)
  }

  test("route not found (channel disabled)") { fixture =>
    import fixture._
    val sender = TestProbe()
    val peerConnection = TestProbe()
    sender.send(router, RouteRequest(a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.routes.head.hops.map(_.nodeId).toList === a :: b :: c :: Nil)
    assert(res.routes.head.hops.last.nextNodeId === d)

    val channelUpdate_cd1 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, d, channelId_cd, CltvExpiryDelta(3), 0 msat, 153000 msat, 4, htlcMaximum, enable = false)
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, channelUpdate_cd1))
    peerConnection.expectMsg(TransportHandler.ReadAck(channelUpdate_cd1))
    sender.send(router, RouteRequest(a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route not found (private channel disabled)") { fixture =>
    import fixture._
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, h, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.routes.head.hops.map(_.nodeId).toList === a :: g :: Nil)
    assert(res.routes.head.hops.last.nextNodeId === h)

    val channelUpdate_ag1 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, g, channelId_ag, CltvExpiryDelta(7), 0 msat, 10 msat, 10, htlcMaximum, enable = false)
    sender.send(router, LocalChannelUpdate(sender.ref, null, channelId_ag, g, None, channelUpdate_ag1, CommitmentsSpec.makeCommitments(10000 msat, 15000 msat, a, g, announceChannel = false)))
    sender.send(router, RouteRequest(a, h, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route not found (balance too low)") { fixture =>
    import fixture._
    val sender = TestProbe()

    // Via private channels.
    sender.send(router, RouteRequest(a, g, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    sender.expectMsgType[RouteResponse]
    sender.send(router, RouteRequest(a, g, 50000000 msat, Long.MaxValue.msat))
    sender.expectMsg(Failure(BalanceTooLow))
    sender.send(router, RouteRequest(a, g, 50000000 msat, Long.MaxValue.msat, allowMultiPart = true))
    sender.expectMsg(Failure(BalanceTooLow))

    // Via public channels.
    sender.send(router, RouteRequest(a, b, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    sender.expectMsgType[RouteResponse]
    val commitments1 = CommitmentsSpec.makeCommitments(10000000 msat, 20000000 msat, a, b, announceChannel = true)
    sender.send(router, LocalChannelUpdate(sender.ref, null, channelId_ab, b, Some(chan_ab), update_ab, commitments1))
    sender.send(router, RouteRequest(a, b, 12000000 msat, Long.MaxValue.msat))
    sender.expectMsg(Failure(BalanceTooLow))
    sender.send(router, RouteRequest(a, b, 12000000 msat, Long.MaxValue.msat, allowMultiPart = true))
    sender.expectMsg(Failure(BalanceTooLow))
    sender.send(router, RouteRequest(a, b, 5000000 msat, Long.MaxValue.msat))
    sender.expectMsgType[RouteResponse]
    sender.send(router, RouteRequest(a, b, 5000000 msat, Long.MaxValue.msat, allowMultiPart = true))
    sender.expectMsgType[RouteResponse]
  }

  test("temporary channel exclusion") { fixture =>
    import fixture._
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    sender.expectMsgType[RouteResponse]
    val bc = ChannelDesc(channelId_bc, b, c)
    // let's exclude channel b->c
    sender.send(router, ExcludeChannel(bc))
    sender.send(router, RouteRequest(a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    sender.expectMsg(Failure(RouteNotFound))
    // note that cb is still available!
    sender.send(router, RouteRequest(d, a, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    sender.expectMsgType[RouteResponse]
    // let's remove the exclusion
    sender.send(router, LiftChannelExclusion(bc))
    sender.send(router, RouteRequest(a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE))
    sender.expectMsgType[RouteResponse]
  }

  test("send routing state") { fixture =>
    import fixture._
    val sender = TestProbe()
    sender.send(router, GetRoutingState)
    val state = sender.expectMsgType[RoutingState]
    assert(state.channels.size == 5)
    assert(state.nodes.size == 8)
    assert(state.channels.flatMap(c => c.update_1_opt.toSeq ++ c.update_2_opt.toSeq).size == 10)
    state.channels.foreach(c => assert(c.capacity === publicChannelCapacity))
  }

  test("send network statistics") { fixture =>
    import fixture._
    val sender = TestProbe()
    sender.send(router, GetNetworkStats)
    sender.expectMsg(GetNetworkStatsResponse(None))

    // Network statistics should be computed after initial sync
    router ! SyncProgress(1.0)
    awaitCond({
      sender.send(router, GetNetworkStats)
      sender.expectMsgType[GetNetworkStatsResponse].stats.isDefined
    })

    sender.send(router, GetNetworkStats)
    val GetNetworkStatsResponse(Some(stats)) = sender.expectMsgType[GetNetworkStatsResponse]
    // if you change this test update test "router returns Network Stats" in EclairImpSpec that mocks this call.
    // else will break the networkstats API call
    assert(stats.channels === 5)
    assert(stats.nodes === 8)
    assert(stats.capacity.median === 1000000.sat)
    assert(stats.cltvExpiryDelta.median === CltvExpiryDelta(7))
  }

  test("given a pre-defined nodes route add the proper channel updates") { fixture =>
    import fixture._

    val sender = TestProbe()
    val preComputedRoute = PredefinedNodeRoute(Seq(a, b, c, d))
    sender.send(router, FinalizeRoute(10000 msat, preComputedRoute))

    val response = sender.expectMsgType[RouteResponse]
    // the route hasn't changed (nodes are the same)
    assert(response.routes.head.hops.map(_.nodeId).toList == preComputedRoute.nodes.dropRight(1).toList)
    assert(response.routes.head.hops.last.nextNodeId == preComputedRoute.targetNodeId)
    assert(response.routes.head.hops.map(_.lastUpdate).toList == List(update_ab, update_bc, update_cd))
  }

  test("given a pre-defined channels route add the proper channel updates") { fixture =>
    import fixture._

    val sender = TestProbe()
    val preComputedRoute = PredefinedChannelRoute(d, Seq(channelId_ab, channelId_bc, channelId_cd))
    sender.send(router, FinalizeRoute(10000 msat, preComputedRoute))

    val response = sender.expectMsgType[RouteResponse]
    // the route hasn't changed (nodes are the same)
    assert(response.routes.head.hops.map(_.nodeId).toList == Seq(a, b, c))
    assert(response.routes.head.hops.last.nextNodeId == preComputedRoute.targetNodeId)
    assert(response.routes.head.hops.map(_.lastUpdate).toList == List(update_ab, update_bc, update_cd))
  }

  test("given an invalid pre-defined channels route return an error") { fixture =>
    import fixture._
    val sender = TestProbe()

    {
      val preComputedRoute = PredefinedChannelRoute(d, Seq(channelId_ab, channelId_cd))
      sender.send(router, FinalizeRoute(10000 msat, preComputedRoute))
      sender.expectMsgType[Status.Failure]
    }
    {
      val preComputedRoute = PredefinedChannelRoute(d, Seq(channelId_ab, channelId_bc))
      sender.send(router, FinalizeRoute(10000 msat, preComputedRoute))
      sender.expectMsgType[Status.Failure]
    }
    {
      val preComputedRoute = PredefinedChannelRoute(d, Seq(channelId_bc, channelId_cd))
      sender.send(router, FinalizeRoute(10000 msat, preComputedRoute))
      sender.expectMsgType[Status.Failure]
    }
    {
      val preComputedRoute = PredefinedChannelRoute(d, Seq(channelId_ab, ShortChannelId(1105), channelId_cd))
      sender.send(router, FinalizeRoute(10000 msat, preComputedRoute))
      sender.expectMsgType[Status.Failure]
    }
  }

  test("ask for channels that we marked as stale for which we receive a new update") { fixture =>
    import fixture._
    val blockHeight = 400000 - 2020
    val channelId = ShortChannelId(blockHeight, 5, 0)
    val announcement = channelAnnouncement(channelId, priv_a, priv_c, priv_funding_a, priv_funding_c)
    val oldTimestamp = (System.currentTimeMillis.milliseconds - 14.days - 1.day).toSeconds
    val staleUpdate = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, c, channelId, CltvExpiryDelta(7), 0 msat, 766000 msat, 10, 5 msat, timestamp = oldTimestamp)
    val peerConnection = TestProbe()
    peerConnection.ignoreMsg { case _: TransportHandler.ReadAck => true }
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, announcement))
    watcher.expectMsgType[ValidateRequest]
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, staleUpdate))
    watcher.send(router, ValidateResult(announcement, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_c)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
    peerConnection.expectMsg(GossipDecision.Accepted(announcement))
    peerConnection.expectMsg(GossipDecision.Stale(staleUpdate))

    val probe = TestProbe()
    probe.send(router, TickPruneStaleChannels)
    val sender = TestProbe()
    sender.send(router, GetRoutingState)
    sender.expectMsgType[RoutingState]

    val recentUpdate = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, c, channelId, CltvExpiryDelta(7), 0 msat, 766000 msat, 10, htlcMaximum, timestamp = System.currentTimeMillis.millisecond.toSeconds)

    // we want to make sure that transport receives the query
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, recentUpdate))
    peerConnection.expectMsg(GossipDecision.RelatedChannelPruned(recentUpdate))
    val query = peerConnection.expectMsgType[QueryShortChannelIds]
    assert(query.shortChannelIds.array == List(channelId))
  }

  test("update local channels balances") { fixture =>
    import fixture._

    val sender = TestProbe()
    sender.send(router, GetRoutingState)
    val channel_ab = sender.expectMsgType[RoutingState].channels.find(_.ann == chan_ab).get
    assert(channel_ab.meta_opt === None)

    {
      // When the local channel comes back online, it will send a LocalChannelUpdate to the router.
      val balances = Set[Option[MilliSatoshi]](Some(10000 msat), Some(15000 msat))
      val commitments = CommitmentsSpec.makeCommitments(10000 msat, 15000 msat, a, b, announceChannel = true)
      sender.send(router, LocalChannelUpdate(sender.ref, null, channelId_ab, b, Some(chan_ab), update_ab, commitments))
      sender.send(router, GetRoutingState)
      val channel_ab = sender.expectMsgType[RoutingState].channels.find(_.ann == chan_ab).get
      assert(Set(channel_ab.meta_opt.map(_.balance1), channel_ab.meta_opt.map(_.balance2)) === balances)
      // And the graph should be updated too.
      sender.send(router, Router.GetRouterData)
      val g = sender.expectMsgType[Data].graph
      val edge_ab = g.getEdge(ChannelDesc(channelId_ab, a, b)).get
      val edge_ba = g.getEdge(ChannelDesc(channelId_ab, b, a)).get
      assert(edge_ab.capacity == channel_ab.capacity && edge_ba.capacity == channel_ab.capacity)
      assert(balances.contains(edge_ab.balance_opt))
      assert(edge_ba.balance_opt === None)
    }

    {
      // First we make sure we aren't in the "pending rebroadcast" state for this channel update.
      sender.send(router, TickBroadcast)
      sender.send(router, Router.GetRouterData)
      assert(sender.expectMsgType[Data].rebroadcast.updates.isEmpty)

      // Then we update the balance without changing the contents of the channel update; the graph should still be updated.
      val balances = Set[Option[MilliSatoshi]](Some(11000 msat), Some(14000 msat))
      val commitments = CommitmentsSpec.makeCommitments(11000 msat, 14000 msat, a, b, announceChannel = true)
      sender.send(router, LocalChannelUpdate(sender.ref, null, channelId_ab, b, Some(chan_ab), update_ab, commitments))
      sender.send(router, GetRoutingState)
      val channel_ab = sender.expectMsgType[RoutingState].channels.find(_.ann == chan_ab).get
      assert(Set(channel_ab.meta_opt.map(_.balance1), channel_ab.meta_opt.map(_.balance2)) === balances)
      // And the graph should be updated too.
      sender.send(router, Router.GetRouterData)
      val g = sender.expectMsgType[Data].graph
      val edge_ab = g.getEdge(ChannelDesc(channelId_ab, a, b)).get
      val edge_ba = g.getEdge(ChannelDesc(channelId_ab, b, a)).get
      assert(edge_ab.capacity == channel_ab.capacity && edge_ba.capacity == channel_ab.capacity)
      assert(balances.contains(edge_ab.balance_opt))
      assert(edge_ba.balance_opt === None)
    }

    {
      // When HTLCs are relayed through the channel, balance changes are sent to the router.
      val balances = Set[Option[MilliSatoshi]](Some(12000 msat), Some(13000 msat))
      val commitments = CommitmentsSpec.makeCommitments(12000 msat, 13000 msat, a, b, announceChannel = true)
      sender.send(router, AvailableBalanceChanged(sender.ref, null, channelId_ab, commitments))
      sender.send(router, GetRoutingState)
      val channel_ab = sender.expectMsgType[RoutingState].channels.find(_.ann == chan_ab).get
      assert(Set(channel_ab.meta_opt.map(_.balance1), channel_ab.meta_opt.map(_.balance2)) === balances)
      // And the graph should be updated too.
      sender.send(router, Router.GetRouterData)
      val g = sender.expectMsgType[Data].graph
      val edge_ab = g.getEdge(ChannelDesc(channelId_ab, a, b)).get
      val edge_ba = g.getEdge(ChannelDesc(channelId_ab, b, a)).get
      assert(edge_ab.capacity == channel_ab.capacity && edge_ba.capacity == channel_ab.capacity)
      assert(balances.contains(edge_ab.balance_opt))
      assert(edge_ba.balance_opt === None)
    }

    {
      // Private channels should also update the graph when HTLCs are relayed through them.
      val balances = Set(33000000 msat, 5000000 msat)
      val commitments = CommitmentsSpec.makeCommitments(33000000 msat, 5000000 msat, a, g, announceChannel = false)
      sender.send(router, AvailableBalanceChanged(sender.ref, null, channelId_ag, commitments))
      sender.send(router, Router.GetRouterData)
      val data = sender.expectMsgType[Data]
      val channel_ag = data.privateChannels(channelId_ag)
      assert(Set(channel_ag.meta.balance1, channel_ag.meta.balance2) === balances)
      // And the graph should be updated too.
      val edge_ag = data.graph.getEdge(ChannelDesc(channelId_ag, a, g)).get
      assert(edge_ag.capacity == channel_ag.capacity)
      assert(edge_ag.balance_opt === Some(33000000 msat))
    }
  }

}
