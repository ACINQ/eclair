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

import akka.actor.testkit.typed.scaladsl.{TestProbe => TypedProbe}
import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, ClassicActorSystemOps}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.Script.{pay2wsh, write}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, OutPoint, Satoshi, SatoshiLong, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment.Invoice.ExtraEdge
import fr.acinq.eclair.payment.send.{ClearRecipient, SpontaneousRecipient}
import fr.acinq.eclair.payment.{Bolt11Invoice, Invoice}
import fr.acinq.eclair.router.Announcements.{makeChannelUpdate, makeNodeAnnouncement}
import fr.acinq.eclair.router.BaseRouterSpec.{blindedRoutesFromPaths, channelAnnouncement}
import fr.acinq.eclair.router.Graph.RoutingHeuristics
import fr.acinq.eclair.router.RouteCalculationSpec.{DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, DEFAULT_ROUTE_PARAMS, route2NodeIds}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Alias, BlockHeight, CltvExpiryDelta, Features, MilliSatoshiLong, RealShortChannelId, ShortChannelId, TestConstants, TimestampSecond, randomBytes32, randomKey}
import org.scalatest.Inside.inside
import scodec.bits._

import scala.concurrent.duration._

/**
 * Created by PM on 29/08/2016.
 */

class RouterSpec extends BaseRouterSpec {

  test("properly announce valid new nodes announcements and ignore invalid ones") { fixture =>
    import fixture._
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])
    system.eventStream.subscribe(eventListener.ref, classOf[Rebroadcast])
    val peerConnection = TestProbe()

    {
      // continue to rebroadcast node updates with deprecated Torv2 addresses
      val torv2Address = List(NodeAddress.fromParts("hsmithsxurybd7uh.onion", 9735).get)
      val node_c_torv2 = makeNodeAnnouncement(priv_c, "node-C", Color(123, 100, -40), torv2Address, TestConstants.Bob.nodeParams.features.nodeAnnouncementFeatures(), timestamp = TimestampSecond.now() + 1)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_c_torv2))
      peerConnection.expectMsg(TransportHandler.ReadAck(node_c_torv2))
      peerConnection.expectMsg(GossipDecision.Accepted(node_c_torv2))
      eventListener.expectMsg(NodeUpdated(node_c_torv2))
      router ! Router.TickBroadcast
      val rebroadcast = eventListener.expectMsgType[Rebroadcast]
      assert(rebroadcast.nodes.contains(node_c_torv2))
    }
    {
      // rebroadcast node updates with a single DNS hostname addresses
      val hostname = List(NodeAddress.fromParts("acinq.co", 9735).get)
      val node_c_hostname = makeNodeAnnouncement(priv_c, "node-C", Color(123, 100, -40), hostname, TestConstants.Bob.nodeParams.features.nodeAnnouncementFeatures(), timestamp = TimestampSecond.now() + 10)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_c_hostname))
      peerConnection.expectMsg(TransportHandler.ReadAck(node_c_hostname))
      peerConnection.expectMsg(GossipDecision.Accepted(node_c_hostname))
      eventListener.expectMsg(NodeUpdated(node_c_hostname))
      router ! Router.TickBroadcast
      val rebroadcast = eventListener.expectMsgType[Rebroadcast]
      assert(rebroadcast.nodes.contains(node_c_hostname))
    }
    {
      // do NOT rebroadcast node updates with more than one DNS hostname addresses
      val multiHostnames = List(NodeAddress.fromParts("acinq.co", 9735).get, NodeAddress.fromParts("acinq.fr", 9735).get)
      val node_c_noForward = makeNodeAnnouncement(priv_c, "node-C", Color(123, 100, -40), multiHostnames, TestConstants.Bob.nodeParams.features.nodeAnnouncementFeatures(), timestamp = TimestampSecond.now() + 20)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_c_noForward))
      peerConnection.expectMsg(TransportHandler.ReadAck(node_c_noForward))
      peerConnection.expectMsg(GossipDecision.Accepted(node_c_noForward))
      eventListener.expectMsg(NodeUpdated(node_c_noForward))
      router ! Router.TickBroadcast
      eventListener.expectNoMessage(100 millis)
    }
  }

  test("properly announce valid new channels and ignore invalid ones") { fixture =>
    import fixture._
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])
    system.eventStream.subscribe(eventListener.ref, classOf[Rebroadcast])
    val peerConnection = TestProbe()

    {
      // valid channel announcement, no stashing
      val chan_ac = channelAnnouncement(RealShortChannelId(BlockHeight(420000), 5, 0), priv_a, priv_c, priv_funding_a, priv_funding_c)
      val update_ac = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, c, chan_ac.shortChannelId, CltvExpiryDelta(7), 0 msat, 766000 msat, 10, htlcMaximum)
      val node_c = makeNodeAnnouncement(priv_c, "node-C", Color(123, 100, -40), Nil, TestConstants.Bob.nodeParams.features.nodeAnnouncementFeatures(), timestamp = TimestampSecond.now() + 1)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_ac))
      peerConnection.expectNoMessage(100 millis) // we don't immediately acknowledge the announcement (back pressure)
      assert(watcher.expectMsgType[ValidateRequest].ann == chan_ac)
      watcher.send(router, ValidateResult(chan_ac, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_c)))) :: Nil, lockTime = 0), UtxoStatus.Unspent)))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_ac))
      peerConnection.expectMsg(GossipDecision.Accepted(chan_ac))
      assert(peerConnection.sender() == router)
      assert(watcher.expectMsgType[WatchExternalChannelSpent].shortChannelId == chan_ac.shortChannelId)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ac))
      peerConnection.expectMsg(TransportHandler.ReadAck(update_ac))
      peerConnection.expectMsg(GossipDecision.Accepted(update_ac))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_c))
      peerConnection.expectMsg(TransportHandler.ReadAck(node_c))
      peerConnection.expectMsg(GossipDecision.Accepted(node_c))
      eventListener.expectMsg(ChannelsDiscovered(SingleChannelDiscovered(chan_ac, 1000000 sat, None, None) :: Nil))
      eventListener.expectMsg(ChannelUpdatesReceived(update_ac :: Nil))
      eventListener.expectMsg(NodeUpdated(node_c))
      peerConnection.expectNoMessage(100 millis)
      eventListener.expectNoMessage(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectMsgType[Rebroadcast]
    }
    {
      // valid channel announcement, stashing while validating channel announcement
      val priv_u = randomKey()
      val priv_funding_u = randomKey()
      val chan_uc = channelAnnouncement(RealShortChannelId(BlockHeight(420000), 100, 0), priv_u, priv_c, priv_funding_u, priv_funding_c)
      val update_uc = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_u, c, chan_uc.shortChannelId, CltvExpiryDelta(7), 0 msat, 766000 msat, 10, htlcMaximum)
      val node_u = makeNodeAnnouncement(priv_u, "node-U", Color(-120, -20, 60), Nil, Features.empty)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_uc))
      peerConnection.expectNoMessage(200 millis) // we don't immediately acknowledge the announcement (back pressure)
      assert(watcher.expectMsgType[ValidateRequest].ann == chan_uc)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_uc))
      peerConnection.expectMsg(TransportHandler.ReadAck(update_uc))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_u))
      peerConnection.expectMsg(TransportHandler.ReadAck(node_u))
      watcher.send(router, ValidateResult(chan_uc, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(2000000 sat, write(pay2wsh(Scripts.multiSig2of2(priv_funding_u.publicKey, funding_c)))) :: Nil, lockTime = 0), UtxoStatus.Unspent)))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_uc))
      peerConnection.expectMsg(GossipDecision.Accepted(chan_uc))
      assert(peerConnection.sender() == router)
      assert(watcher.expectMsgType[WatchExternalChannelSpent].shortChannelId == chan_uc.shortChannelId)
      peerConnection.expectMsg(GossipDecision.Accepted(update_uc))
      peerConnection.expectMsg(GossipDecision.Accepted(node_u))
      eventListener.expectMsg(ChannelsDiscovered(SingleChannelDiscovered(chan_uc, 2000000 sat, None, None) :: Nil))
      eventListener.expectMsg(ChannelUpdatesReceived(update_uc :: Nil))
      eventListener.expectMsg(NodesDiscovered(node_u :: Nil))
      peerConnection.expectNoMessage(100 millis)
      eventListener.expectNoMessage(100 millis)
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
      peerConnection.expectNoMessage(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMessage(100 millis)
    }
    {
      // invalid signatures
      val invalid_node_b = node_b.copy(timestamp = node_b.timestamp + 10)
      val invalid_chan_ac = channelAnnouncement(RealShortChannelId(BlockHeight(420000), 101, 1), priv_a, priv_c, priv_funding_a, priv_funding_c).copy(nodeId1 = randomKey().publicKey)
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
      peerConnection.expectNoMessage(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMessage(100 millis)
    }
    {
      // pruned channel: we receive a channel announcement but no channel updates
      val priv_v = randomKey()
      val priv_funding_v = randomKey()
      val chan_vc = channelAnnouncement(RealShortChannelId(BlockHeight(100), 102, 0), priv_v, priv_c, priv_funding_v, priv_funding_c)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_vc))
      watcher.expectMsgType[ValidateRequest]
      watcher.send(router, ValidateResult(chan_vc, Right((Transaction(2, Nil, Seq(TxOut(100_000 sat, pay2wsh(Scripts.multiSig2of2(funding_c, priv_funding_v.publicKey)))), 0), UtxoStatus.Unspent))))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_vc))
      peerConnection.expectMsg(GossipDecision.Accepted(chan_vc))
      assert(watcher.expectMsgType[WatchExternalChannelSpent].shortChannelId == chan_vc.shortChannelId)
      eventListener.expectMsg(ChannelsDiscovered(SingleChannelDiscovered(chan_vc, 100_000 sat, None, None) :: Nil))
      awaitAssert(assert(nodeParams.db.network.getChannel(chan_vc.shortChannelId).nonEmpty))
      router ! TickPruneStaleChannels
      eventListener.expectMsg(ChannelLost(chan_vc.shortChannelId))
      eventListener.expectMsg(NodeLost(priv_v.publicKey))
      router ! Router.TickBroadcast
      eventListener.expectMsgType[Rebroadcast]
      // we receive this old channel announcement again, but it is now pruned
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_vc))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_vc))
      peerConnection.expectMsg(GossipDecision.ChannelPruned(chan_vc))
      peerConnection.expectNoMessage(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMessage(100 millis)
    }
    {
      // stale channel update
      val update_ab = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_b.publicKey, chan_ab.shortChannelId, CltvExpiryDelta(7), 0 msat, 766000 msat, 10, htlcMaximum, timestamp = TimestampSecond.now() - 15.days)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ab))
      peerConnection.expectMsg(TransportHandler.ReadAck(update_ab))
      peerConnection.expectMsg(GossipDecision.Stale(update_ab))
      peerConnection.expectNoMessage(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMessage(100 millis)
    }
    {
      // unknown channel
      val priv_y = randomKey()
      val update_ay = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_y.publicKey, ShortChannelId(4646464), CltvExpiryDelta(7), 0 msat, 766000 msat, 10, htlcMaximum)
      val node_y = makeNodeAnnouncement(priv_y, "node-Y", Color(123, 100, -40), Nil, TestConstants.Bob.nodeParams.features.nodeAnnouncementFeatures())
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ay))
      peerConnection.expectMsg(TransportHandler.ReadAck(update_ay))
      peerConnection.expectMsg(GossipDecision.NoRelatedChannel(update_ay))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_y))
      peerConnection.expectMsg(TransportHandler.ReadAck(node_y))
      peerConnection.expectMsg(GossipDecision.NoKnownChannel(node_y))
      peerConnection.expectNoMessage(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMessage(100 millis)
    }
    {
      // invalid announcement + reject stashed
      val priv_y = randomKey()
      val priv_funding_y = randomKey() // a-y will have an invalid script
      val chan_ay = channelAnnouncement(RealShortChannelId(42002), priv_a, priv_y, priv_funding_a, priv_funding_y)
      val update_ay = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_y.publicKey, chan_ay.shortChannelId, CltvExpiryDelta(7), 0 msat, 766000 msat, 10, htlcMaximum)
      val node_y = makeNodeAnnouncement(priv_y, "node-Y", Color(123, 100, -40), Nil, TestConstants.Bob.nodeParams.features.nodeAnnouncementFeatures())
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_ay))
      assert(watcher.expectMsgType[ValidateRequest].ann == chan_ay)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ay))
      peerConnection.expectMsg(TransportHandler.ReadAck(update_ay))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_y))
      peerConnection.expectMsg(TransportHandler.ReadAck(node_y))
      watcher.send(router, ValidateResult(chan_ay, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_a, randomKey().publicKey)))) :: Nil, lockTime = 0), UtxoStatus.Unspent)))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_ay))
      peerConnection.expectMsg(GossipDecision.InvalidAnnouncement(chan_ay))
      peerConnection.expectMsg(GossipDecision.NoRelatedChannel(update_ay))
      peerConnection.expectMsg(GossipDecision.NoKnownChannel(node_y))
      peerConnection.expectNoMessage(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMessage(100 millis)
    }
    {
      // validation failure
      val priv_x = randomKey()
      val chan_ax = channelAnnouncement(RealShortChannelId(42001), priv_a, priv_x, priv_funding_a, randomKey())
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_ax))
      assert(watcher.expectMsgType[ValidateRequest].ann == chan_ax)
      watcher.send(router, ValidateResult(chan_ax, Left(new RuntimeException("funding tx not found"))))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_ax))
      peerConnection.expectMsg(GossipDecision.ValidationFailure(chan_ax))
      peerConnection.expectNoMessage(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMessage(100 millis)
    }
    {
      // funding tx spent (funding tx not confirmed)
      val priv_z = randomKey()
      val priv_funding_z = randomKey()
      val chan_az = channelAnnouncement(RealShortChannelId(42003), priv_a, priv_z, priv_funding_a, priv_funding_z)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_az))
      assert(watcher.expectMsgType[ValidateRequest].ann == chan_az)
      watcher.send(router, ValidateResult(chan_az, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_a, priv_funding_z.publicKey)))) :: Nil, lockTime = 0), UtxoStatus.Spent(spendingTxConfirmed = false))))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_az))
      peerConnection.expectMsg(GossipDecision.ChannelClosing(chan_az))
      peerConnection.expectNoMessage(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMessage(100 millis)
    }
    {
      // funding tx spent (funding tx confirmed)
      val priv_z = randomKey()
      val priv_funding_z = randomKey()
      val chan_az = channelAnnouncement(RealShortChannelId(42003), priv_a, priv_z, priv_funding_a, priv_funding_z)
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_az))
      assert(watcher.expectMsgType[ValidateRequest].ann == chan_az)
      watcher.send(router, ValidateResult(chan_az, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_a, priv_funding_z.publicKey)))) :: Nil, lockTime = 0), UtxoStatus.Spent(spendingTxConfirmed = true))))
      peerConnection.expectMsg(TransportHandler.ReadAck(chan_az))
      peerConnection.expectMsg(GossipDecision.ChannelClosed(chan_az))
      peerConnection.expectNoMessage(100 millis)
      router ! Router.TickBroadcast
      eventListener.expectNoMessage(100 millis)
    }

    watcher.expectNoMessage(100 millis)
  }

  test("get nodes") { fixture =>
    import fixture._

    val probe = TestProbe()
    val unknownNodeId = randomKey().publicKey
    router ! GetNode(probe.ref.toTyped, unknownNodeId)
    probe.expectMsg(UnknownNode(unknownNodeId))
    router ! GetNode(probe.ref.toTyped, b)
    probe.expectMsg(PublicNode(node_b, 2, publicChannelCapacity * 2))
  }

  def fundingTx(node1: PublicKey, node2: PublicKey, capacity: Satoshi = publicChannelCapacity): Transaction = {
    val fundingScript = write(pay2wsh(Scripts.multiSig2of2(node1, node2)))
    val fundingTx = Transaction(version = 0, txIn = Nil, txOut = TxOut(capacity, fundingScript) :: Nil, lockTime = 0)
    fundingTx
  }

  def spendingTx(node1: PublicKey, node2: PublicKey, capacity: Satoshi = publicChannelCapacity): Transaction = {
    val fundingScript = write(pay2wsh(Scripts.multiSig2of2(node1, node2)))
    val nextFundingTx = Transaction(version = 2, txIn = TxIn(OutPoint(fundingTx(node1, node2, capacity), 0), fundingScript, 0) :: Nil, txOut = TxOut(capacity, fundingScript) :: Nil, lockTime = 0)
    nextFundingTx
  }

  def batchSpendingTx(spendingTxs: Seq[Transaction]): Transaction = Transaction(version = 2, txIn = spendingTxs.flatMap(_.txIn), txOut = spendingTxs.flatMap(_.txOut), lockTime = 0)

  test("properly announce lost channels and nodes") { fixture =>
    import fixture._
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])

    val probe = TestProbe()
    probe.send(router, GetRouterData)
    val channels = probe.expectMsgType[Data].channels

    router ! WatchExternalChannelSpentTriggered(scid_ab, spendingTx(funding_a, funding_b))
    watcher.expectMsgType[WatchTxConfirmed]
    router ! WatchTxConfirmedTriggered(BlockHeight(0), 0, spendingTx(funding_a, funding_b))
    watcher.expectMsg(UnwatchExternalChannelSpent(channels(scid_ab).fundingTxId, ShortChannelId.outputIndex(scid_ab)))
    eventListener.expectMsg(ChannelLost(scid_ab))
    assert(nodeParams.db.network.getChannel(scid_ab).isEmpty)
    // a doesn't have any channels, b still has one with c
    eventListener.expectMsg(NodeLost(a))
    assert(nodeParams.db.network.getNode(a).isEmpty)
    assert(nodeParams.db.network.getNode(b).nonEmpty)
    eventListener.expectNoMessage(200 milliseconds)

    router ! WatchExternalChannelSpentTriggered(scid_cd, spendingTx(funding_c, funding_d))
    watcher.expectMsgType[WatchTxConfirmed]
    router ! WatchTxConfirmedTriggered(BlockHeight(0), 0, spendingTx(funding_c, funding_d))
    watcher.expectMsg(UnwatchExternalChannelSpent(channels(scid_cd).fundingTxId, ShortChannelId.outputIndex(scid_cd)))
    eventListener.expectMsg(ChannelLost(scid_cd))
    assert(nodeParams.db.network.getChannel(scid_cd).isEmpty)
    // d doesn't have any channels, c still has one with b
    eventListener.expectMsg(NodeLost(d))
    assert(nodeParams.db.network.getNode(d).isEmpty)
    assert(nodeParams.db.network.getNode(c).nonEmpty)
    eventListener.expectNoMessage(200 milliseconds)

    router ! WatchExternalChannelSpentTriggered(scid_bc, spendingTx(funding_b, funding_c))
    watcher.expectMsgType[WatchTxConfirmed]
    router ! WatchTxConfirmedTriggered(BlockHeight(0), 0, spendingTx(funding_b, funding_c))
    watcher.expectMsg(UnwatchExternalChannelSpent(channels(scid_bc).fundingTxId, ShortChannelId.outputIndex(scid_bc)))
    eventListener.expectMsg(ChannelLost(scid_bc))
    assert(nodeParams.db.network.getChannel(scid_bc).isEmpty)
    // now b and c do not have any channels
    eventListener.expectMsgAllOf(NodeLost(b), NodeLost(c))
    assert(nodeParams.db.network.getNode(b).isEmpty)
    assert(nodeParams.db.network.getNode(c).isEmpty)
    eventListener.expectNoMessage(200 milliseconds)
  }

  test("properly announce lost pruned channels and nodes") { fixture =>
    import fixture._
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])

    val priv_u = randomKey()
    val priv_funding_u = randomKey()
    val scid_au = RealShortChannelId(fixture.nodeParams.currentBlockHeight - 5000, 5, 0)
    val ann = channelAnnouncement(scid_au, priv_a, priv_u, priv_funding_a, priv_funding_u)
    val fundingTx_au = fundingTx(funding_a, priv_funding_u.publicKey, 500_000 sat)
    router ! PeerRoutingMessage(TestProbe().ref, remoteNodeId, ann)
    watcher.expectMsgType[ValidateRequest]
    watcher.send(router, ValidateResult(ann, Right((fundingTx_au, UtxoStatus.Unspent))))
    watcher.expectMsgType[WatchExternalChannelSpent]
    eventListener.expectMsg(ChannelsDiscovered(SingleChannelDiscovered(ann, 500_000 sat, None, None) :: Nil))
    awaitAssert(assert(nodeParams.db.network.getChannel(scid_au).nonEmpty))

    val probe = TestProbe()
    probe.send(router, GetRouterData)
    val channels = probe.expectMsgType[Data].channels

    // The channel is pruned: we keep it in the DB until it is spent.
    router ! TickPruneStaleChannels
    eventListener.expectMsg(ChannelLost(scid_au))
    eventListener.expectMsg(NodeLost(priv_u.publicKey))
    awaitAssert(assert(nodeParams.db.network.getChannel(scid_au).nonEmpty))

    // The channel is closed, now we can remove it from the DB.
    router ! WatchExternalChannelSpentTriggered(scid_au, spendingTx(funding_a, priv_funding_u.publicKey))
    assert(watcher.expectMsgType[WatchTxConfirmed].txId == spendingTx(funding_a, priv_funding_u.publicKey).txid)
    router ! WatchTxConfirmedTriggered(BlockHeight(0), 0, spendingTx(funding_a, priv_funding_u.publicKey))
    watcher.expectMsg(UnwatchExternalChannelSpent(channels(scid_au).fundingTxId, ShortChannelId.outputIndex(scid_au)))
    eventListener.expectMsg(ChannelLost(scid_au))
    eventListener.expectMsg(NodeLost(priv_u.publicKey))
    awaitAssert(assert(nodeParams.db.network.getChannel(scid_au).isEmpty))
  }

  test("properly identify stale channels (new channel)") { () =>
    // we don't want to prune new channels for which we haven't received channel updates yet
    val currentBlockHeight = BlockHeight(102_000)
    val ann = chan_ab.copy(shortChannelId = RealShortChannelId(BlockHeight(100_000), 0, 0))
    assert(!StaleChannels.isStale(ann, None, None, currentBlockHeight))
    val update1 = update_ab.copy(timestamp = TimestampSecond.now() - 1.day)
    assert(!StaleChannels.isStale(ann, Some(update1), None, currentBlockHeight))
  }

  test("properly identify stale channels (old channel)") { () =>
    // we prune old channels for which we haven't received a channel update or which have an outdated channel update
    val currentBlockHeight = BlockHeight(105_000)
    val ann = chan_ab.copy(shortChannelId = RealShortChannelId(BlockHeight(100_000), 0, 0))
    val staleUpdate1 = update_ab.copy(timestamp = TimestampSecond.now() - 15.days)
    val update1 = update_ab.copy(timestamp = TimestampSecond.now() - 1.day)
    val staleUpdate2 = update_ba.copy(timestamp = TimestampSecond.now() - 15.days)
    val update2 = update_ba.copy(timestamp = TimestampSecond.now() - 7.days)
    assert(StaleChannels.isStale(ann, None, None, currentBlockHeight))
    assert(StaleChannels.isStale(ann, Some(update1), None, currentBlockHeight))
    assert(StaleChannels.isStale(ann, Some(update1), Some(staleUpdate2), currentBlockHeight))
    assert(StaleChannels.isStale(ann, Some(staleUpdate1), Some(staleUpdate2), currentBlockHeight))
    assert(!StaleChannels.isStale(ann, Some(update1), Some(update2), currentBlockHeight))
  }

  test("handle bad signature for ChannelAnnouncement") { fixture =>
    import fixture._
    val peerConnection = TestProbe()
    val channelId_ac = RealShortChannelId(BlockHeight(420000), 105, 0)
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
    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    // no route a->f
    router ! RouteRequest(sender.ref, a, SpontaneousRecipient(f, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    sender.expectMessage(PaymentRouteNotFound(RouteNotFound))
  }

  test("route not found (non-existing source)") { fixture =>
    import fixture._
    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    // no route a->f
    router ! RouteRequest(sender.ref, randomKey().publicKey, SpontaneousRecipient(f, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    sender.expectMessage(PaymentRouteNotFound(RouteNotFound))
  }

  test("route not found (non-existing target)") { fixture =>
    import fixture._
    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    // no route a->f
    router ! RouteRequest(sender.ref, a, SpontaneousRecipient(randomKey().publicKey, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    sender.expectMessage(PaymentRouteNotFound(RouteNotFound))
  }

  test("route found") { fixture =>
    import fixture._
    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    router ! RouteRequest(sender.ref, a, SpontaneousRecipient(d, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    val res = sender.expectMessageType[RouteResponse]
    assert(route2NodeIds(res.routes.head) == Seq(a, b, c, d))
    assert(res.routes.head.finalHop_opt.isEmpty)

    router ! RouteRequest(sender.ref, a, SpontaneousRecipient(h, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    val res1 = sender.expectMessageType[RouteResponse]
    assert(route2NodeIds(res1.routes.head) == Seq(a, g, h))
    assert(res1.routes.head.finalHop_opt.isEmpty)
  }

  test("route found (with extra routing info)") { fixture =>
    import fixture._
    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    val x = PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73")
    val y = PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a")
    val z = PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484")
    val extraHop_cx = ExtraHop(c, ShortChannelId(1), 10 msat, 11, CltvExpiryDelta(12))
    val extraHop_xy = ExtraHop(x, ShortChannelId(2), 10 msat, 11, CltvExpiryDelta(12))
    val extraHop_yz = ExtraHop(y, ShortChannelId(3), 20 msat, 21, CltvExpiryDelta(22))
    val recipient = ClearRecipient(z, Features.empty, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One, Bolt11Invoice.toExtraEdges(extraHop_cx :: extraHop_xy :: extraHop_yz :: Nil, z))
    router ! RouteRequest(sender.ref, a, recipient, DEFAULT_ROUTE_PARAMS)
    val res = sender.expectMessageType[RouteResponse]
    assert(route2NodeIds(res.routes.head) == Seq(a, b, c, x, y, z))
    assert(res.routes.head.finalHop_opt.isEmpty)
  }

  test("routes found (with pending payments)") { fixture =>
    import fixture._
    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    val routeParams = DEFAULT_ROUTE_PARAMS.copy(boundaries = SearchBoundaries(15 msat, 0.0, 6, CltvExpiryDelta(1008)))
    val recipient = ClearRecipient(c, Features.empty, 500_000 msat, DEFAULT_EXPIRY, randomBytes32())
    router ! RouteRequest(sender.ref, a, recipient, routeParams)
    val route1 = sender.expectMessageType[RouteResponse].routes.head
    assert(route1.amount == 500_000.msat)
    assert(route2NodeIds(route1) == Seq(a, b, c))
    assert(route1.channelFee(false) == 10.msat)
    // We can't find another route to complete the payment amount because it exceeds the fee budget.
    router ! RouteRequest(sender.ref, a, recipient, routeParams, pendingPayments = Seq(route1.copy(amount = 200_000 msat)))
    sender.expectMessage(PaymentRouteNotFound(RouteNotFound))
    // But if we increase the fee budget, we're able to find a second route.
    router ! RouteRequest(sender.ref, a, recipient, routeParams.copy(boundaries = routeParams.boundaries.copy(maxFeeFlat = 20 msat)), pendingPayments = Seq(route1.copy(amount = 200_000 msat)))
    val route2 = sender.expectMessageType[RouteResponse].routes.head
    assert(route2.amount == 300_000.msat)
    assert(route2NodeIds(route2) == Seq(a, b, c))
    assert(route2.channelFee(false) == 10.msat)
  }

  test("routes found (with blinded hops)") { fixture =>
    import fixture._
    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    val r = randomKey().publicKey
    val hopsToRecipient = Seq(
      ChannelHop(ShortChannelId(10000), b, r, HopRelayParams.FromHint(ExtraEdge(b, r, ShortChannelId(10000), 800 msat, 0, CltvExpiryDelta(36), 1 msat, Some(400_000 msat)))) :: Nil,
      ChannelHop(ShortChannelId(10001), c, r, HopRelayParams.FromHint(ExtraEdge(c, r, ShortChannelId(10001), 500 msat, 0, CltvExpiryDelta(36), 1 msat, Some(400_000 msat)))) :: Nil,
    )

    {
      // Amount split between both blinded routes:
      val (_, recipient) = blindedRoutesFromPaths(600_000 msat, DEFAULT_EXPIRY, hopsToRecipient, DEFAULT_EXPIRY)
      router ! RouteRequest(sender.ref, a, recipient, DEFAULT_ROUTE_PARAMS, allowMultiPart = true)
      val routes = sender.expectMessageType[RouteResponse].routes
      assert(routes.length == 2)
      assert(routes.flatMap(_.finalHop_opt) == recipient.blindedHops)
      assert(routes.map(route => route2NodeIds(route)).toSet == Set(Seq(a, b), Seq(a, b, c)))
      assert(routes.map(route => route.blindedFee + route.channelFee(false)).toSet == Set(510 msat, 800 msat))
    }
    {
      // One blinded route is ignored, we use the other one:
      val (_, recipient) = blindedRoutesFromPaths(300_000 msat, DEFAULT_EXPIRY, hopsToRecipient, DEFAULT_EXPIRY)
      val ignored = Ignore(Set.empty, Set(ChannelDesc(recipient.extraEdges.last.shortChannelId, recipient.extraEdges.last.sourceNodeId, recipient.extraEdges.last.targetNodeId)))
      router ! RouteRequest(sender.ref, a, recipient, DEFAULT_ROUTE_PARAMS, ignore = ignored)
      val routes = sender.expectMessageType[RouteResponse].routes
      assert(routes.length == 1)
      assert(routes.head.finalHop_opt.nonEmpty)
      assert(route2NodeIds(routes.head) == Seq(a, b))
      assert(routes.head.blindedFee == 800.msat)
    }
    {
      // One blinded route is ignored, the other one doesn't have enough capacity:
      val (_, recipient) = blindedRoutesFromPaths(500_000 msat, DEFAULT_EXPIRY, hopsToRecipient, DEFAULT_EXPIRY)
      val ignored = Ignore(Set.empty, Set(ChannelDesc(recipient.extraEdges.last.shortChannelId, recipient.extraEdges.last.sourceNodeId, recipient.extraEdges.last.targetNodeId)))
      router ! RouteRequest(sender.ref, a, recipient, DEFAULT_ROUTE_PARAMS, allowMultiPart = true, ignore = ignored)
      sender.expectMessage(PaymentRouteNotFound(RouteNotFound))
    }
    {
      // One blinded route is pending, we use the other one:
      val (_, recipient) = blindedRoutesFromPaths(600_000 msat, DEFAULT_EXPIRY, hopsToRecipient, DEFAULT_EXPIRY)
      router ! RouteRequest(sender.ref, a, recipient, DEFAULT_ROUTE_PARAMS, allowMultiPart = true)
      val routes1 = sender.expectMessageType[RouteResponse].routes
      assert(routes1.length == 2)
      router ! RouteRequest(sender.ref, a, recipient, DEFAULT_ROUTE_PARAMS, allowMultiPart = true, pendingPayments = Seq(routes1.head))
      val routes2 = sender.expectMessageType[RouteResponse].routes
      assert(routes2 == routes1.tail)
    }
    {
      // One blinded route is pending, we send two htlcs to the other one:
      val (_, recipient) = blindedRoutesFromPaths(600_000 msat, DEFAULT_EXPIRY, hopsToRecipient, DEFAULT_EXPIRY)
      router ! RouteRequest(sender.ref, a, recipient, DEFAULT_ROUTE_PARAMS, allowMultiPart = true)
      val routes1 = sender.expectMessageType[RouteResponse].routes
      assert(routes1.length == 2)
      router ! RouteRequest(sender.ref, a, recipient, DEFAULT_ROUTE_PARAMS, allowMultiPart = true, pendingPayments = Seq(routes1.head))
      val routes2 = sender.expectMessageType[RouteResponse].routes
      assert(routes2 == routes1.tail)
      router ! RouteRequest(sender.ref, a, recipient, DEFAULT_ROUTE_PARAMS, allowMultiPart = true, pendingPayments = Seq(routes1.head, routes2.head.copy(amount = routes2.head.amount - 25_000.msat)))
      val routes3 = sender.expectMessageType[RouteResponse].routes
      assert(routes3.length == 1)
      assert(routes3.head.amount == 25_000.msat)
    }
    {
      // One blinded route is pending, we cannot use the other one because of the fee budget:
      val (_, recipient) = blindedRoutesFromPaths(600_000 msat, DEFAULT_EXPIRY, hopsToRecipient, DEFAULT_EXPIRY)
      val routeParams1 = DEFAULT_ROUTE_PARAMS.copy(boundaries = SearchBoundaries(5000 msat, 0.0, 6, CltvExpiryDelta(1008)))
      router ! RouteRequest(sender.ref, a, recipient, routeParams1, allowMultiPart = true)
      val routes1 = sender.expectMessageType[RouteResponse].routes
      assert(routes1.length == 2)
      assert(routes1.head.blindedFee + routes1.head.channelFee(false) == 800.msat)
      val routeParams2 = DEFAULT_ROUTE_PARAMS.copy(boundaries = SearchBoundaries(1000 msat, 0.0, 6, CltvExpiryDelta(1008)))
      router ! RouteRequest(sender.ref, a, recipient, routeParams2, allowMultiPart = true, pendingPayments = Seq(routes1.head))
      sender.expectMessage(PaymentRouteNotFound(RouteNotFound))
      val routeParams3 = DEFAULT_ROUTE_PARAMS.copy(boundaries = SearchBoundaries(1500 msat, 0.0, 6, CltvExpiryDelta(1008)))
      router ! RouteRequest(sender.ref, a, recipient, routeParams3, allowMultiPart = true, pendingPayments = Seq(routes1.head))
      assert(sender.expectMessageType[RouteResponse].routes.length == 1)
    }
  }

  test("route not found (channel disabled)") { fixture =>
    import fixture._
    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    val peerConnection = TestProbe()
    router ! RouteRequest(sender.ref, a, SpontaneousRecipient(d, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    val res = sender.expectMessageType[RouteResponse]
    assert(res.routes.head.hops.map(_.nodeId).toList == a :: b :: c :: Nil)
    assert(res.routes.head.hops.last.nextNodeId == d)

    val channelUpdate_cd1 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, d, scid_cd, CltvExpiryDelta(3), 0 msat, 153000 msat, 4, htlcMaximum, enable = false)
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, channelUpdate_cd1))
    peerConnection.expectMsg(TransportHandler.ReadAck(channelUpdate_cd1))
    router ! RouteRequest(sender.ref, a, SpontaneousRecipient(d, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    sender.expectMessage(PaymentRouteNotFound(RouteNotFound))
  }

  test("route not found (private channel disabled)") { fixture =>
    import fixture._
    val requester = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    val sender = TestProbe()
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(h, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    val res = requester.expectMessageType[RouteResponse]
    assert(res.routes.head.hops.map(_.nodeId).toList == a :: g :: Nil)
    assert(res.routes.head.hops.last.nextNodeId == h)

    val channelUpdate_ag1 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, g, alias_ga_private, CltvExpiryDelta(7), 0 msat, 10 msat, 10, htlcMaximum, enable = false)
    sender.send(router, LocalChannelUpdate(sender.ref, channelId_ag_private, scids_ag_private, g, None, channelUpdate_ag1, CommitmentsSpec.makeCommitments(10000 msat, 15000 msat, a, g, announcement_opt = None)))
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(h, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessage(PaymentRouteNotFound(RouteNotFound))
  }

  test("route not found (balance too low)") { fixture =>
    import fixture._
    val requester = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    val sender = TestProbe()

    // Via private channels.
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(g, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessageType[RouteResponse]
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(g, 50000000 msat, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessage(PaymentRouteNotFound(BalanceTooLow))
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(g, 50000000 msat, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS, allowMultiPart = true)
    requester.expectMessage(PaymentRouteNotFound(BalanceTooLow))

    // Via public channels.
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(b, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessageType[RouteResponse]
    val commitments1 = CommitmentsSpec.makeCommitments(10000000 msat, 20000000 msat, a, b, announcement_opt = Some(chan_ab))
    sender.send(router, LocalChannelUpdate(sender.ref, commitments1.channelId, scids_ab, b, Some(AnnouncedCommitment(commitments1.latest.commitment, chan_ab)), update_ab, commitments1))
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(b, 12000000 msat, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessage(PaymentRouteNotFound(BalanceTooLow))
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(b, 12000000 msat, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS, allowMultiPart = true)
    requester.expectMessage(PaymentRouteNotFound(BalanceTooLow))
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(b, 5000000 msat, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessageType[RouteResponse]
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(b, 5000000 msat, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS, allowMultiPart = true)
    requester.expectMessageType[RouteResponse]
  }

  test("temporary channel exclusion") { fixture =>
    import fixture._
    val requester = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    val sender = TestProbe()
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(d, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessageType[RouteResponse]
    val bc = ChannelDesc(scid_bc, b, c)
    // let's exclude channel b->c
    sender.send(router, ExcludeChannel(bc, Some(1 hour)))
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(d, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessage(PaymentRouteNotFound(RouteNotFound))
    // note that cb is still available!
    router ! RouteRequest(requester.ref, d, SpontaneousRecipient(a, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessageType[RouteResponse]
    // let's remove the exclusion
    sender.send(router, LiftChannelExclusion(bc))
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(d, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessageType[RouteResponse]
  }

  test("concurrent channel exclusions") { fixture =>
    import fixture._
    val requester = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    val sender = TestProbe()
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(d, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessageType[RouteResponse]
    val bc = ChannelDesc(scid_bc, b, c)
    sender.send(router, ExcludeChannel(bc, Some(1 second)))
    sender.send(router, ExcludeChannel(bc, Some(10 minute)))
    sender.send(router, ExcludeChannel(bc, Some(1 second)))
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(d, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessage(PaymentRouteNotFound(RouteNotFound))
    sender.send(router, GetExcludedChannels)
    val excludedChannels1 = sender.expectMsgType[Map[ChannelDesc, ExcludedChannelStatus]]
    assert(excludedChannels1.size == 1)
    assert(excludedChannels1(bc).isInstanceOf[ExcludedUntil])
    assert(excludedChannels1(bc).asInstanceOf[ExcludedUntil].liftExclusionAt > TimestampSecond.now() + 9.minute)
    sender.send(router, LiftChannelExclusion(bc))
    router ! RouteRequest(requester.ref, a, SpontaneousRecipient(d, DEFAULT_AMOUNT_MSAT, DEFAULT_EXPIRY, ByteVector32.One), DEFAULT_ROUTE_PARAMS)
    requester.expectMessageType[RouteResponse]
    sender.send(router, ExcludeChannel(bc, None))
    sender.send(router, GetExcludedChannels)
    val excludedChannels2 = sender.expectMsgType[Map[ChannelDesc, ExcludedChannelStatus]]
    assert(excludedChannels2.size == 1)
    assert(excludedChannels2(bc) == ExcludedForever)
  }

  test("send routing state") { fixture =>
    import fixture._
    val sender = TestProbe()
    sender.send(router, GetRoutingState)
    val state = sender.expectMsgType[RoutingState]
    assert(state.channels.size == 5)
    assert(state.nodes.size == 8)
    assert(state.channels.flatMap(c => c.update_1_opt.toSeq ++ c.update_2_opt.toSeq).size == 10)
    state.channels.foreach(c => assert(c.capacity == publicChannelCapacity))
  }

  test("given a pre-defined nodes route add the proper channel updates") { fixture =>
    import fixture._

    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)

    {
      val preComputedRoute = PredefinedNodeRoute(10000 msat, Seq(a, b, c, d))
      router ! FinalizeRoute(sender.ref, preComputedRoute)
      val response = sender.expectMessageType[RouteResponse]
      assert(response.routes.head.hops.map(_.nodeId) == Seq(a, b, c))
      assert(response.routes.head.hops.map(_.nextNodeId) == Seq(b, c, d))
      assert(response.routes.head.hops.map(_.shortChannelId) == Seq(scid_ab, scid_bc, scid_cd))
      assert(response.routes.head.hops.map(_.params) == Seq(HopRelayParams.FromAnnouncement(update_ab), HopRelayParams.FromAnnouncement(update_bc), HopRelayParams.FromAnnouncement(update_cd)))
    }
    {
      val preComputedRoute = PredefinedNodeRoute(10000 msat, Seq(a, g, h))
      router ! FinalizeRoute(sender.ref, preComputedRoute)
      val response = sender.expectMessageType[RouteResponse]
      assert(response.routes.head.hops.map(_.nodeId) == Seq(a, g))
      assert(response.routes.head.hops.map(_.nextNodeId) == Seq(g, h))
      assert(response.routes.head.hops.map(_.shortChannelId) == Seq(alias_ag_private, scid_gh))
    }
    {
      val preComputedRoute = PredefinedNodeRoute(10000 msat, Seq(a, g, a))
      router ! FinalizeRoute(sender.ref, preComputedRoute)
      val response = sender.expectMessageType[RouteResponse]
      assert(response.routes.head.hops.map(_.nodeId) == Seq(a, g))
      assert(response.routes.head.hops.map(_.nextNodeId) == Seq(g, a))
      assert(response.routes.head.hops.map(_.shortChannelId) == Seq(alias_ag_private, alias_ga_private))
    }
  }

  test("given a pre-defined channels route add the proper channel updates") { fixture =>
    import fixture._

    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    val preComputedRoute = PredefinedChannelRoute(10000 msat, d, Seq(scid_ab, scid_bc, scid_cd))
    router ! FinalizeRoute(sender.ref, preComputedRoute)

    val response = sender.expectMessageType[RouteResponse]
    // the route hasn't changed (nodes are the same)
    assert(response.routes.head.hops.map(_.nodeId) == Seq(a, b, c))
    assert(response.routes.head.hops.map(_.nextNodeId) == Seq(b, c, d))
    assert(response.routes.head.hops.map(_.shortChannelId) == Seq(scid_ab, scid_bc, scid_cd))
    assert(response.routes.head.hops.map(_.params) == Seq(HopRelayParams.FromAnnouncement(update_ab), HopRelayParams.FromAnnouncement(update_bc), HopRelayParams.FromAnnouncement(update_cd)))
  }

  test("given a pre-defined private channels route add the proper channel updates") { fixture =>
    import fixture._
    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)

    {
      // using the channel alias
      val preComputedRoute = PredefinedChannelRoute(10000 msat, g, Seq(alias_ag_private))
      router ! FinalizeRoute(sender.ref, preComputedRoute)
      val response = sender.expectMessageType[RouteResponse]
      assert(response.routes.length == 1)
      val route = response.routes.head
      assert(route.hops.map(_.params) == Seq(HopRelayParams.FromAnnouncement(update_ag_private)))
      assert(route.hops.head.nodeId == a)
      assert(route.hops.head.nextNodeId == g)
      assert(route.hops.head.shortChannelId == alias_ag_private)
    }
    {
      // using the channel alias routing to ourselves: a -> g -> a
      val preComputedRoute = PredefinedChannelRoute(10000 msat, a, Seq(alias_ag_private, alias_ag_private))
      router ! FinalizeRoute(sender.ref, preComputedRoute)
      val response = sender.expectMessageType[RouteResponse]
      assert(response.routes.length == 1)
      val route = response.routes.head
      assert(route.hops.map(_.params) == Seq(HopRelayParams.FromAnnouncement(update_ag_private), HopRelayParams.FromAnnouncement(update_ga_private)))
      assert(route.hops.map(_.nodeId) == Seq(a, g))
      assert(route.hops.map(_.nextNodeId) == Seq(g, a))
      assert(route.hops.map(_.shortChannelId) == Seq(alias_ag_private, alias_ga_private))
    }
  }

  test("given a pre-defined channels route with routing hints add the proper channel updates") { fixture =>
    import fixture._
    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)
    val targetNodeId = randomKey().publicKey

    {
      val amount = 10_000.msat
      val invoiceRoutingHint = Invoice.ExtraEdge(b, targetNodeId, RealShortChannelId(BlockHeight(420000), 516, 1105), 10 msat, 150, CltvExpiryDelta(96), 1 msat, None)
      val preComputedRoute = PredefinedChannelRoute(amount, targetNodeId, Seq(scid_ab, invoiceRoutingHint.shortChannelId))
      // the amount affects the way we estimate the channel capacity of the hinted channel
      assert(amount < RoutingHeuristics.CAPACITY_CHANNEL_LOW)
      router ! FinalizeRoute(sender.ref, preComputedRoute, extraEdges = Seq(invoiceRoutingHint))
      val response = sender.expectMessageType[RouteResponse]
      assert(response.routes.length == 1)
      val route = response.routes.head
      assert(route.hops.map(_.nodeId) == Seq(a, b))
      assert(route.hops.map(_.nextNodeId) == Seq(b, targetNodeId))
      assert(route.hops.map(_.shortChannelId) == Seq(scid_ab, invoiceRoutingHint.shortChannelId))
      assert(route.hops.head.params == HopRelayParams.FromAnnouncement(update_ab))
      assert(route.hops.last.params == HopRelayParams.FromHint(invoiceRoutingHint))
    }
    {
      val amount = RoutingHeuristics.CAPACITY_CHANNEL_LOW * 2
      val invoiceRoutingHint = Invoice.ExtraEdge(h, targetNodeId, RealShortChannelId(BlockHeight(420000), 516, 1105), 10 msat, 150, CltvExpiryDelta(96), 1 msat, None)
      val preComputedRoute = PredefinedChannelRoute(amount, targetNodeId, Seq(alias_ag_private, scid_gh, invoiceRoutingHint.shortChannelId))
      // the amount affects the way we estimate the channel capacity of the hinted channel
      assert(amount > RoutingHeuristics.CAPACITY_CHANNEL_LOW)
      router ! FinalizeRoute(sender.ref, preComputedRoute, extraEdges = Seq(invoiceRoutingHint))
      val response = sender.expectMessageType[RouteResponse]
      assert(response.routes.length == 1)
      val route = response.routes.head
      assert(route.hops.map(_.nodeId) == Seq(a, g, h))
      assert(route.hops.map(_.nextNodeId) == Seq(g, h, targetNodeId))
      assert(route.hops.map(_.shortChannelId) == Seq(alias_ag_private, scid_gh, invoiceRoutingHint.shortChannelId))
      assert(route.hops.map(_.params).dropRight(1) == Seq(HopRelayParams.FromAnnouncement(update_ag_private), HopRelayParams.FromAnnouncement(update_gh)))
      assert(route.hops.last.params == HopRelayParams.FromHint(invoiceRoutingHint))
    }
  }

  test("given an invalid pre-defined channels route return an error") { fixture =>
    import fixture._
    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)

    {
      val preComputedRoute = PredefinedChannelRoute(10000 msat, d, Seq(scid_ab, scid_cd))
      router ! FinalizeRoute(sender.ref, preComputedRoute)
      sender.expectMessageType[PaymentRouteNotFound]
    }
    {
      val preComputedRoute = PredefinedChannelRoute(10000 msat, d, Seq(scid_ab, scid_bc))
      router ! FinalizeRoute(sender.ref, preComputedRoute)
      sender.expectMessageType[PaymentRouteNotFound]
    }
    {
      val preComputedRoute = PredefinedChannelRoute(10000 msat, d, Seq(scid_bc, scid_cd))
      router ! FinalizeRoute(sender.ref, preComputedRoute)
      sender.expectMessageType[PaymentRouteNotFound]
    }
    {
      val preComputedRoute = PredefinedChannelRoute(10000 msat, d, Seq(scid_ab, ShortChannelId(1105), scid_cd))
      router ! FinalizeRoute(sender.ref, preComputedRoute)
      sender.expectMessageType[PaymentRouteNotFound]
    }
  }

  test("given a pre-defined channels route properly handles provided max fee") { fixture =>
    import fixture._
    val sender = TypedProbe[PaymentRouteResponse]()(system.toTyped)

    {
      val preComputedRoute = PredefinedChannelRoute(10000 msat, d, Seq(scid_ab, scid_bc, scid_cd), maxFee_opt = Some(19.msat))
      router ! FinalizeRoute(sender.ref, preComputedRoute)
      sender.expectMessageType[PaymentRouteNotFound]
    }
    {
      val preComputedRoute = PredefinedChannelRoute(10000 msat, d, Seq(scid_ab, scid_bc, scid_cd), maxFee_opt = Some(20.msat))
      router ! FinalizeRoute(sender.ref, preComputedRoute)
      sender.expectMessageType[Router.RouteResponse]
    }
  }

  test("restore stale channel that comes back from the dead") { fixture =>
    import fixture._

    // A new channel is created and announced.
    val probe = TestProbe()
    val scid = RealShortChannelId(fixture.nodeParams.currentBlockHeight - 5000, 5, 0)
    val capacity = 1_000_000.sat
    val ann = channelAnnouncement(scid, priv_a, priv_c, priv_funding_a, priv_funding_c)
    val peerConnection = TestProbe()
    val fundingTx_ac = fundingTx(funding_a, funding_c, capacity)
    peerConnection.ignoreMsg { case _: TransportHandler.ReadAck => true }
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, ann))
    watcher.expectMsgType[ValidateRequest]
    watcher.send(router, ValidateResult(ann, Right((fundingTx_ac, UtxoStatus.Unspent))))
    peerConnection.expectMsg(GossipDecision.Accepted(ann))
    probe.send(router, GetChannels)
    assert(probe.expectMsgType[Iterable[ChannelAnnouncement]].exists(_.shortChannelId == scid))

    // We never received the channel updates, so we prune the channel.
    probe.send(router, TickPruneStaleChannels)
    awaitAssert({
      probe.send(router, GetRouterData)
      val routerData = probe.expectMsgType[Data]
      assert(routerData.prunedChannels.contains(scid) && !routerData.channels.contains(scid))
    })

    // We receive a stale channel update for one side of the channel.
    val staleTimestamp = TimestampSecond.now() - 15.days
    val staleUpdate = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, c, scid, CltvExpiryDelta(72), 1 msat, 10 msat, 100, htlcMaximum, timestamp = staleTimestamp)
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, staleUpdate))
    peerConnection.expectMsg(GossipDecision.Stale(staleUpdate))
    assert(nodeParams.db.network.getChannel(scid).contains(PublicChannel(ann, fundingTx_ac.txid, capacity, None, None, None)))

    // We receive a non-stale channel update for one side of the channel.
    val update_ac_1 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, c, scid, CltvExpiryDelta(72), 1 msat, 10 msat, 100, htlcMaximum, timestamp = TimestampSecond.now() - 3.days)
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ac_1))
    peerConnection.expectMsg(GossipDecision.RelatedChannelPruned(update_ac_1))
    peerConnection.expectNoMessage(100 millis)
    if (update_ac_1.channelFlags.isNode1) {
      assert(nodeParams.db.network.getChannel(scid).contains(PublicChannel(ann, fundingTx_ac.txid, capacity, Some(update_ac_1), None, None)))
    } else {
      assert(nodeParams.db.network.getChannel(scid).contains(PublicChannel(ann, fundingTx_ac.txid, capacity, None, Some(update_ac_1), None)))
    }
    probe.send(router, GetRouterData)
    val routerData1 = probe.expectMsgType[Data]
    assert(routerData1.prunedChannels.contains(scid))
    assert(!routerData1.channels.contains(scid))
    assert(!routerData1.graphWithBalances.graph.containsEdge(ChannelDesc(scid, a, c)))
    assert(!routerData1.graphWithBalances.graph.containsEdge(ChannelDesc(scid, c, a)))

    // We receive another non-stale channel update for the same side of the channel.
    val update_ac_2 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, c, scid, CltvExpiryDelta(48), 1 msat, 1 msat, 150, htlcMaximum, timestamp = TimestampSecond.now() - 1.days)
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ac_2))
    peerConnection.expectMsg(GossipDecision.RelatedChannelPruned(update_ac_2))
    peerConnection.expectNoMessage(100 millis)
    if (update_ac_2.channelFlags.isNode1) {
      assert(nodeParams.db.network.getChannel(scid).contains(PublicChannel(ann, fundingTx_ac.txid, capacity, Some(update_ac_2), None, None)))
    } else {
      assert(nodeParams.db.network.getChannel(scid).contains(PublicChannel(ann, fundingTx_ac.txid, capacity, None, Some(update_ac_2), None)))
    }
    probe.send(router, GetRouterData)
    val routerData2 = probe.expectMsgType[Data]
    assert(routerData2.prunedChannels.contains(scid))
    assert(!routerData2.channels.contains(scid))

    // We receive a non-stale channel update for the other side of the channel.
    val update_ca = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, a, scid, CltvExpiryDelta(144), 1000 msat, 15 msat, 0, htlcMaximum, timestamp = TimestampSecond.now() - 6.hours)
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ca))
    peerConnection.expectMsg(GossipDecision.Accepted(update_ca))
    peerConnection.expectNoMessage(100 millis)
    probe.send(router, GetRouterData)
    val routerData3 = probe.expectMsgType[Data]
    assert(routerData3.channels.contains(scid))
    assert(!routerData3.prunedChannels.contains(scid))
    if (update_ac_2.channelFlags.isNode1) {
      assert(routerData3.channels.get(scid).contains(PublicChannel(ann, fundingTx_ac.txid, capacity, Some(update_ac_2), Some(update_ca), None)))
    } else {
      assert(routerData3.channels.get(scid).contains(PublicChannel(ann, fundingTx_ac.txid, capacity, Some(update_ca), Some(update_ac_2), None)))
    }
    assert(routerData3.graphWithBalances.graph.containsEdge(ChannelDesc(update_ac_2, ann)))
    assert(routerData3.graphWithBalances.graph.containsEdge(ChannelDesc(update_ca, ann)))
  }

  test("update local channels balances") { fixture =>
    import fixture._

    val sender = TestProbe()
    sender.send(router, GetRoutingState)
    val channel_ab = sender.expectMsgType[RoutingState].channels.find(_.ann == chan_ab).get
    assert(channel_ab.meta_opt.isEmpty)

    {
      // When the local channel comes back online, it will send a LocalChannelUpdate to the router.
      val commitments = CommitmentsSpec.makeCommitments(channel_ab.capacity - 50_000_000.msat, 50_000_000 msat, a, b, announcement_opt = Some(chan_ab))
      val balances = Set(commitments.availableBalanceForSend, commitments.availableBalanceForReceive)
      sender.send(router, LocalChannelUpdate(sender.ref, commitments.channelId, scids_ab, b, Some(AnnouncedCommitment(commitments.latest.commitment, chan_ab)), update_ab, commitments))
      sender.send(router, GetRoutingState)
      val channel_ab1 = sender.expectMsgType[RoutingState].channels.find(_.ann == chan_ab).get
      assert(Set(channel_ab1.meta_opt.map(_.balance1), channel_ab1.meta_opt.map(_.balance2)).flatten == balances)
      // And the graph should be updated too.
      sender.send(router, Router.GetRouterData)
      val g = sender.expectMsgType[Data].graphWithBalances.graph
      val edge_ab = g.getEdge(ChannelDesc(scid_ab, a, b)).get
      val edge_ba = g.getEdge(ChannelDesc(scid_ab, b, a)).get
      assert(edge_ab.capacity == channel_ab1.capacity && edge_ba.capacity == channel_ab1.capacity)
      assert(edge_ab.balance_opt.contains(commitments.availableBalanceForSend))
      assert(edge_ba.balance_opt.isEmpty)
    }
    {
      // First we make sure we aren't in the "pending rebroadcast" state for this channel update.
      sender.send(router, TickBroadcast)
      sender.send(router, Router.GetRouterData)
      assert(sender.expectMsgType[Data].rebroadcast.updates.isEmpty)

      // Then we update the balance without changing the contents of the channel update; the graph should still be updated.
      val commitments = CommitmentsSpec.makeCommitments(channel_ab.capacity - 40_000_000.msat, 40_000_000 msat, a, b, announcement_opt = Some(chan_ab))
      val balances = Set(commitments.availableBalanceForSend, commitments.availableBalanceForReceive)
      sender.send(router, LocalChannelUpdate(sender.ref, commitments.channelId, scids_ab, b, Some(AnnouncedCommitment(commitments.latest.commitment, chan_ab)), update_ab, commitments))
      sender.send(router, GetRoutingState)
      val channel_ab1 = sender.expectMsgType[RoutingState].channels.find(_.ann == chan_ab).get
      assert(Set(channel_ab1.meta_opt.map(_.balance1), channel_ab1.meta_opt.map(_.balance2)).flatten == balances)
      // And the graph should be updated too.
      sender.send(router, Router.GetRouterData)
      val g = sender.expectMsgType[Data].graphWithBalances.graph
      val edge_ab = g.getEdge(ChannelDesc(scid_ab, a, b)).get
      val edge_ba = g.getEdge(ChannelDesc(scid_ab, b, a)).get
      assert(edge_ab.capacity == channel_ab1.capacity && edge_ba.capacity == channel_ab1.capacity)
      assert(edge_ab.balance_opt.contains(commitments.availableBalanceForSend))
      assert(edge_ba.balance_opt.isEmpty)
    }
    {
      // When HTLCs are relayed through the channel, balance changes are sent to the router.
      val commitments = CommitmentsSpec.makeCommitments(channel_ab.capacity - 55_000_000.msat, 55_000_000 msat, a, b, announcement_opt = Some(chan_ab))
      val balances = Set(commitments.availableBalanceForSend, commitments.availableBalanceForReceive)
      sender.send(router, AvailableBalanceChanged(sender.ref, null, scids_ab, commitments, lastAnnouncement_opt = Some(chan_ab)))
      sender.send(router, GetRoutingState)
      val channel_ab1 = sender.expectMsgType[RoutingState].channels.find(_.ann == chan_ab).get
      assert(Set(channel_ab1.meta_opt.map(_.balance1), channel_ab1.meta_opt.map(_.balance2)).flatten == balances)
      // And the graph should be updated too.
      sender.send(router, Router.GetRouterData)
      val g = sender.expectMsgType[Data].graphWithBalances.graph
      val edge_ab = g.getEdge(ChannelDesc(scid_ab, a, b)).get
      val edge_ba = g.getEdge(ChannelDesc(scid_ab, b, a)).get
      assert(edge_ab.capacity == channel_ab1.capacity && edge_ba.capacity == channel_ab1.capacity)
      assert(edge_ab.balance_opt.contains(commitments.availableBalanceForSend))
      assert(edge_ba.balance_opt.isEmpty)
    }
    {
      // Private channels should also update the graph when HTLCs are relayed through them.
      sender.send(router, GetRouterData)
      val channel_ag = sender.expectMsgType[Data].privateChannels(channelId_ag_private)
      val commitments = CommitmentsSpec.makeCommitments(channel_ag.meta.balance1 + 10_000_000.msat, channel_ag.meta.balance2 - 10_000_000.msat, a, g, announcement_opt = None)
      sender.send(router, AvailableBalanceChanged(sender.ref, channelId_ag_private, scids_ab, commitments, lastAnnouncement_opt = None))
      sender.send(router, Router.GetRouterData)
      val data = sender.expectMsgType[Data]
      val channel_ag1 = data.privateChannels(channelId_ag_private)
      assert(Set(channel_ag1.meta.balance1, channel_ag1.meta.balance2) == Set(commitments.availableBalanceForSend, commitments.availableBalanceForReceive))
      // And the graph should be updated too.
      val edge_ag = data.graphWithBalances.graph.getEdge(ChannelDesc(alias_ag_private, a, g)).get
      assert(edge_ag.capacity == channel_ag1.capacity)
      assert(edge_ag.balance_opt.contains(commitments.availableBalanceForSend))
    }
  }

  test("restore channels after restart") { fixture =>
    import fixture._

    val sender = TestProbe()
    // There are two channels between A and X, where the first channel has been announced but not the second one.
    val x = randomKey()
    val scid1 = RealShortChannelId(BlockHeight(817_031), 12, 8)
    val aliases1 = ShortIdAliases(Alias(37), None)
    val scid2 = RealShortChannelId(BlockHeight(819_506), 3, 1)
    val aliases2 = ShortIdAliases(Alias(42), None)
    val announcement1 = channelAnnouncement(scid1, priv_a, x, randomKey(), randomKey())
    val commitments1 = CommitmentsSpec.makeCommitments(250_000_000 msat, 150_000_000 msat, a, x.publicKey, announcement_opt = Some(announcement1))
    val update1 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, x.publicKey, scid1, CltvExpiryDelta(36), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 10, htlcMaximumMsat = htlcMaximum)
    val announcement2 = channelAnnouncement(scid2, priv_a, x, randomKey(), randomKey())
    val commitments2 = CommitmentsSpec.makeCommitments(100_000_000 msat, 0 msat, a, x.publicKey, announcement_opt = None)
    val update2 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, x.publicKey, scid2, CltvExpiryDelta(24), htlcMinimumMsat = 0 msat, feeBaseMsat = 50 msat, feeProportionalMillionths = 15, htlcMaximumMsat = htlcMaximum)

    // The first channel comes back online and is added to the public channels.
    sender.send(router, ShortChannelIdAssigned(sender.ref, commitments1.channelId, announcement_opt = Some(announcement1), aliases1, x.publicKey))
    sender.send(router, LocalChannelUpdate(sender.ref, commitments1.channelId, aliases1, x.publicKey, Some(AnnouncedCommitment(commitments1.latest.commitment, announcement1)), update1, commitments1))
    sender.send(router, GetRouterData)
    inside(sender.expectMsgType[Data]) { data =>
      assert(data.channels.contains(scid1))
      val chan1 = data.channels(scid1)
      assert(chan1.capacity == 400_000.sat)
      assert(chan1.ann == announcement1)
      assert((chan1.update_1_opt.toSet ++ chan1.update_2_opt.toSet) == Set(update1))
    }

    // The second channel comes back online and is added to the private channels.
    sender.send(router, ShortChannelIdAssigned(sender.ref, commitments2.channelId, announcement_opt = None, aliases2, x.publicKey))
    sender.send(router, LocalChannelUpdate(sender.ref, commitments2.channelId, aliases2, x.publicKey, None, update2, commitments2))
    sender.send(router, GetRouterData)
    inside(sender.expectMsgType[Data]) { data =>
      assert(!data.channels.contains(scid2))
      assert(data.scid2PrivateChannels.get(aliases2.localAlias.toLong).contains(commitments2.channelId))
      val chan2 = data.privateChannels(commitments2.channelId)
      assert(chan2.capacity == 99_000.sat) // for private channels, we use the balance to compute the channel's capacity
      assert((chan2.update_1_opt.toSet ++ chan2.update_2_opt.toSet) == Set(update2))
    }

    // The second channel is announced and moves from the private channels to the public channels.
    val fundingConfirmed = LocalFundingStatus.ConfirmedFundingTx(Nil, TxOut(100_000 sat, Nil), scid2, None, None)
    val commitments3 = commitments2.updateLocalFundingStatus(commitments2.latest.fundingTxId, fundingConfirmed, None)(akka.event.NoLogging).toOption.get._1
    assert(commitments3.channelId == commitments2.channelId)
    sender.send(router, LocalChannelUpdate(sender.ref, commitments3.channelId, aliases2, x.publicKey, Some(AnnouncedCommitment(commitments3.latest.commitment, announcement2)), update2, commitments3))
    sender.send(router, GetRouterData)
    inside(sender.expectMsgType[Data]) { data =>
      assert(data.channels.contains(scid2))
      assert(!data.privateChannels.contains(commitments3.channelId))
      val chan2 = data.channels(scid2)
      assert(chan2.capacity == 100_000.sat)
      assert(chan2.ann == announcement2)
      assert((chan2.update_1_opt.toSet ++ chan2.update_2_opt.toSet) == Set(update2))
    }
  }

  test("stream updates to front") { fixture =>
    import fixture._

    val sender = TestProbe()
    sender.send(router, GetRoutingStateStreaming)

    // initial sync
    var nodes = Set.empty[NodeAnnouncement]
    var channels = Set.empty[ChannelAnnouncement]
    var updates = Set.empty[ChannelUpdate]
    sender.fishForMessage() {
      case nd: NodesDiscovered =>
        nodes = nodes ++ nd.ann
        false
      case cd: ChannelsDiscovered =>
        channels = channels ++ cd.c.map(_.ann)
        updates = updates ++ cd.c.flatMap(sc => sc.u1_opt.toSeq ++ sc.u2_opt.toSeq)
        false
      case RoutingStateStreamingUpToDate => true
    }
    assert(nodes.size == 8 && channels.size == 5 && updates.size == 10) // public channels only

    // just making sure that we have been subscribed to network events, otherwise there is a possible race condition
    awaitCond({
      system.eventStream.publish(SyncProgress(42))
      sender.msgAvailable
    }, max = 30 seconds)

    // new announcements
    val update_ab_2 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, b, scid_ab, CltvExpiryDelta(7), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 10, htlcMaximumMsat = htlcMaximum, timestamp = update_ab.timestamp + 1)
    val peerConnection = TestProbe()
    router ! PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ab_2)
    sender.fishForMessage() {
      case cu: ChannelUpdatesReceived => cu == ChannelUpdatesReceived(List(update_ab_2))
      case _ => false
    }
  }

  def processSpliceChannelAnnouncement(fixture: FixtureParam, parentScid: RealShortChannelId, channelAnnouncement: ChannelAnnouncement, spliceTx: Transaction, newCapacity: Satoshi): Unit = {
    import fixture._
    // A splice of the channel is announced and validated.
    val peerConnection = TestProbe()
    peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, channelAnnouncement))
    peerConnection.expectNoMessage(100 millis)
    assert(watcher.expectMsgType[ValidateRequest].ann == channelAnnouncement)
    watcher.send(router, ValidateResult(channelAnnouncement, Right(spliceTx, UtxoStatus.Unspent)))
    assert(watcher.expectMsgType[WatchExternalChannelSpent].shortChannelId == channelAnnouncement.shortChannelId)
    watcher.expectMsgType[UnwatchExternalChannelSpent] // unwatch the parent channel
    peerConnection.expectMsg(TransportHandler.ReadAck(channelAnnouncement))
    peerConnection.expectMsg(GossipDecision.Accepted(channelAnnouncement))
    assert(peerConnection.sender() == router)

    // And the graph should be updated too.
    val sender = TestProbe()
    sender.send(router, Router.GetRouterData)
    inside(sender.expectMsgType[Data]) { routerData =>
      val g = routerData.graphWithBalances.graph
      val edge_ab = g.getEdge(ChannelDesc(channelAnnouncement.shortChannelId, channelAnnouncement.nodeId1, channelAnnouncement.nodeId2)).get
      val edge_ba = g.getEdge(ChannelDesc(channelAnnouncement.shortChannelId, channelAnnouncement.nodeId2, channelAnnouncement.nodeId1)).get
      assert(g.getEdge(ChannelDesc(parentScid, channelAnnouncement.nodeId1, channelAnnouncement.nodeId2)).isEmpty)
      assert(g.getEdge(ChannelDesc(parentScid, channelAnnouncement.nodeId2, channelAnnouncement.nodeId1)).isEmpty)
      assert(newCapacity == spliceTx.txOut(channelAnnouncement.shortChannelId.outputIndex).amount)
      assert(edge_ab.capacity == newCapacity && edge_ba.capacity == newCapacity)
    }
  }

  test("update an existing channel after a splice") { fixture =>
    import fixture._

    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])

    // Channel ab is spent by a splice tx.
    val capacity1 = publicChannelCapacity - 100_000.sat
    val spliceTx1 = spendingTx(funding_a, funding_b, capacity1)
    router ! WatchExternalChannelSpentTriggered(scid_ab, spliceTx1)
    inside(watcher.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == spliceTx1.txid)
      assert(w.minDepth == 12)
    }
    eventListener.expectNoMessage(100 millis)

    // Channel ab is spent and confirmed by an RBF of splice tx.
    val newCapacity = publicChannelCapacity - 100_000.sat - 1000.sat
    val spliceTx2 = spendingTx(funding_a, funding_b, newCapacity)
    router ! WatchExternalChannelSpentTriggered(scid_ab, spliceTx2)
    inside(watcher.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == spliceTx2.txid)
      assert(w.minDepth == 12)
    }
    eventListener.expectNoMessage(100 millis)

    // The splice of channel ab is announced.
    val spliceScid = RealShortChannelId(BlockHeight(450000), 1, 0)
    val spliceAnn = channelAnnouncement(spliceScid, priv_a, priv_b, priv_funding_a, priv_funding_b)
    processSpliceChannelAnnouncement(fixture, scid_ab, spliceAnn, spliceTx2, newCapacity)

    // The router no longer tracks the parent scids.
    val probe = TestProbe()
    awaitAssert({
      probe.send(router, GetRouterData)
      val routerData = probe.expectMsgType[Data]
      assert(routerData.spentChannels.isEmpty)
      assert(!routerData.channels.contains(scid_ab))
    })
  }

  test("update multiple existing channels with a batch splice") { fixture =>
    import fixture._

    // add second b-c channel
    val scid_bc2 = RealShortChannelId(BlockHeight(420000), 7, 0)
    addChannel(fixture.router, fixture.watcher, scid_bc2, priv_b, priv_c, priv_funding_b, priv_funding_c)

    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])

    val newCapacity_ab = publicChannelCapacity - 100_000.sat
    val newCapacity_bc = publicChannelCapacity + 50_000.sat
    val spliceTx_ab = spendingTx(funding_a, funding_b, newCapacity_ab)
    val spliceTx_bc = spendingTx(funding_b, funding_c, newCapacity_bc)
    val spliceTx_bc2 = spendingTx(funding_b, funding_c)
    val batchSpliceTx = batchSpendingTx(Seq(spliceTx_ab, spliceTx_bc, spliceTx_bc2))

    // Channel ab is spent by a splice tx.
    router ! WatchExternalChannelSpentTriggered(scid_ab, spliceTx_ab)
    inside(watcher.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == spliceTx_ab.txid)
      assert(w.minDepth == 12)
    }

    // Channel bc is spent by a splice tx.
    router ! WatchExternalChannelSpentTriggered(scid_bc, spliceTx_bc)
    inside(watcher.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == spliceTx_bc.txid)
      assert(w.minDepth == 12)
    }

    // Channel bc2 is spent by a splice tx.
    router ! WatchExternalChannelSpentTriggered(scid_bc2, spliceTx_bc2)
    inside(watcher.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == spliceTx_bc2.txid)
      assert(w.minDepth == 12)
    }

    // Channels ab, bc and bc2 are all spent by the same batch splice tx.
    router ! WatchExternalChannelSpentTriggered(scid_ab, batchSpliceTx)
    inside(watcher.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == batchSpliceTx.txid)
      assert(w.minDepth == 12)
    }
    router ! WatchExternalChannelSpentTriggered(scid_bc, batchSpliceTx)
    inside(watcher.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == batchSpliceTx.txid)
      assert(w.minDepth == 12)
    }
    router ! WatchExternalChannelSpentTriggered(scid_bc2, batchSpliceTx)
    inside(watcher.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == batchSpliceTx.txid)
      assert(w.minDepth == 12)
    }

    // Channels ab, bc and bc2 are also all spent by an RBF of the batch splice tx.
    val newCapacity_ab_RBF = newCapacity_ab - 1000.sat
    val batchSpliceTx_RBF = batchSpendingTx(Seq(spendingTx(funding_a, funding_b, newCapacity_ab_RBF), spliceTx_bc, spliceTx_bc2))
    router ! WatchExternalChannelSpentTriggered(scid_ab, batchSpliceTx_RBF)
    inside(watcher.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == batchSpliceTx_RBF.txid)
      assert(w.minDepth == 12)
    }
    router ! WatchExternalChannelSpentTriggered(scid_bc, batchSpliceTx_RBF)
    inside(watcher.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == batchSpliceTx_RBF.txid)
      assert(w.minDepth == 12)
    }
    router ! WatchExternalChannelSpentTriggered(scid_bc2, batchSpliceTx_RBF)
    inside(watcher.expectMsgType[WatchTxConfirmed]) { w =>
      assert(w.txId == batchSpliceTx_RBF.txid)
      assert(w.minDepth == 12)
    }

    // The router tracks the possible spending txs for channels ab, bc and bc2.
    val sender = TestProbe()
    awaitAssert({
      sender.send(router, GetRouterData)
      inside(sender.expectMsgType[Data]) { routerData =>
        assert(routerData.spentChannels(spliceTx_ab.txid) == Set(scid_ab))
        assert(routerData.spentChannels(spliceTx_bc.txid) == Set(scid_bc))
        assert(routerData.spentChannels(spliceTx_bc2.txid) == Set(scid_bc2))
        assert(routerData.spentChannels(batchSpliceTx.txid) == Set(scid_ab, scid_bc, scid_bc2))
        assert(routerData.spentChannels(batchSpliceTx_RBF.txid) == Set(scid_ab, scid_bc, scid_bc2))
      }
    })

    // The splice of channel ab is announced, verified and added to the graph; the parent channel is removed from the graph.
    val spliceScid_ab = RealShortChannelId(BlockHeight(450000), 1, 0)
    val spliceAnn_ab = channelAnnouncement(spliceScid_ab, priv_a, priv_b, priv_funding_a, priv_funding_b)
    processSpliceChannelAnnouncement(fixture, scid_ab, spliceAnn_ab, batchSpliceTx_RBF, newCapacity_ab_RBF)
    assert(watcher.expectMsgType[UnwatchTxConfirmed].txId == spliceTx_ab.txid)

    // The router still tracks the possible spending txs for channels bc and bc2.
    awaitAssert({
      sender.send(router, GetRouterData)
      inside(sender.expectMsgType[Data]) { routerData =>
        assert(!routerData.spentChannels.contains(spliceTx_ab.txid))
        assert(routerData.spentChannels.contains(spliceTx_bc.txid))
        assert(routerData.spentChannels.contains(spliceTx_bc2.txid))
        assert(routerData.spentChannels(spliceTx_bc.txid) == Set(scid_bc))
        assert(routerData.spentChannels(batchSpliceTx.txid) == Set(scid_bc, scid_bc2))
        assert(routerData.spentChannels(batchSpliceTx_RBF.txid) == Set(scid_bc, scid_bc2))
      }
    })

    // The splice of channel bc is announced, verified and added to the graph; the parent channel is removed from the graph.
    val spliceScid_bc = RealShortChannelId(BlockHeight(450000), 1, 1)
    val spliceAnn_bc = channelAnnouncement(spliceScid_bc, priv_b, priv_c, priv_funding_b, priv_funding_c)
    processSpliceChannelAnnouncement(fixture, scid_bc, spliceAnn_bc, batchSpliceTx_RBF, newCapacity_bc)
    assert(watcher.expectMsgType[UnwatchTxConfirmed].txId == spliceTx_bc.txid)

    // The router still tracks the possible spending txs for channel bc or bc2 - either could be considered the parent of scid_bc.
    awaitAssert({
      sender.send(router, GetRouterData)
      inside(sender.expectMsgType[Data]) { routerData =>
        if (routerData.spentChannels.contains(spliceTx_bc.txid)) {
          assert(!routerData.spentChannels.contains(spliceTx_bc2.txid))
          assert(routerData.spentChannels(spliceTx_bc.txid) == Set(scid_bc))
          assert(routerData.spentChannels(batchSpliceTx.txid) == Set(scid_bc))
          assert(routerData.spentChannels(batchSpliceTx_RBF.txid) == Set(scid_bc))
        } else {
          assert(routerData.spentChannels.contains(spliceTx_bc2.txid))
          assert(routerData.spentChannels(spliceTx_bc2.txid) == Set(scid_bc2))
          assert(routerData.spentChannels(batchSpliceTx.txid) == Set(scid_bc2))
          assert(routerData.spentChannels(batchSpliceTx_RBF.txid) == Set(scid_bc2))
        }
      }
    })

    // Splice channel updates received for ab and bc add new channels to and remove the parent channels from the graph.
    eventListener.expectMsg(ChannelsDiscovered(SingleChannelDiscovered(spliceAnn_ab, newCapacity_ab_RBF, None, None) :: Nil))
    eventListener.expectMsg(ChannelLost(scid_ab))
    eventListener.expectMsg(ChannelsDiscovered(SingleChannelDiscovered(spliceAnn_bc, newCapacity_bc, None, None) :: Nil))
    eventListener.expectMsg(ChannelLost(scid_bc))

    // No splice channel update for channel bc2 was received before the batch splice tx confirms so channel bc2 is removed.
    sender.send(router, GetRouterData)
    val fundingTxId_bc2 = sender.expectMsgType[Data].channels(scid_bc2).fundingTxId
    router ! WatchTxConfirmedTriggered(BlockHeight(0), 0, batchSpliceTx_RBF)
    assert(watcher.expectMsgType[UnwatchExternalChannelSpent].txId == fundingTxId_bc2)
    eventListener.expectMsg(ChannelLost(scid_bc2))
    eventListener.expectNoMessage(100 millis)

    // Alternative spending transactions in the mempool are now unspendable and need not be watched.
    val unwatchedTxs = Set(
      watcher.expectMsgType[UnwatchTxConfirmed].txId,
      watcher.expectMsgType[UnwatchTxConfirmed].txId,
    )
    assert(unwatchedTxs == Set(spliceTx_bc2.txid, batchSpliceTx.txid))
    watcher.expectNoMessage(100 millis)

    // The router no longer tracks the parent scids.
    awaitAssert({
      sender.send(router, GetRouterData)
      inside(sender.expectMsgType[Data]) { routerData =>
        assert(routerData.spentChannels.isEmpty)
        assert(!routerData.channels.contains(scid_ab))
        assert(!routerData.channels.contains(scid_bc))
        assert(!routerData.channels.contains(scid_bc2))
        assert(routerData.channels.contains(spliceScid_ab))
        assert(routerData.channels.contains(spliceScid_bc))
      }
    })
  }

}
