/*
 * Copyright 2018 ACINQ SAS
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

import akka.actor.Status.Failure
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{Block, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.{InvalidSignature, PeerRoutingMessage}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Announcements.makeChannelUpdate
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.QueryShortChannelIds
import fr.acinq.eclair.{Globals, ShortChannelId, randomKey}
import RouteCalculationSpec.DEFAULT_AMOUNT_MSAT
import scala.collection.SortedSet
import scala.compat.Platform
import scala.concurrent.duration._

/**
  * Created by PM on 29/08/2016.
  */

class RouterSpec extends BaseRouterSpec {

  val relaxedRouteParams = Some(RouteCalculationSpec.DEFAULT_ROUTE_PARAMS.copy(maxFeePct = 0.3))

  test("properly announce valid new channels and ignore invalid ones") { fixture =>
    import fixture._
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[NetworkEvent])

    val channelId_ac = ShortChannelId(420000, 5, 0)
    val chan_ac = channelAnnouncement(channelId_ac, priv_a, priv_c, priv_funding_a, priv_funding_c)
    val update_ac = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, c, channelId_ac, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, 500000000L)
    // a-x will not be found
    val priv_x = randomKey
    val chan_ax = channelAnnouncement(ShortChannelId(42001), priv_a, priv_x, priv_funding_a, randomKey)
    val update_ax = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_x.publicKey, chan_ax.shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, 500000000L)
    // a-y will have an invalid script
    val priv_y = randomKey
    val priv_funding_y = randomKey
    val chan_ay = channelAnnouncement(ShortChannelId(42002), priv_a, priv_y, priv_funding_a, priv_funding_y)
    val update_ay = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_y.publicKey, chan_ay.shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, 500000000L)
    // a-z will be spent
    val priv_z = randomKey
    val priv_funding_z = randomKey
    val chan_az = channelAnnouncement(ShortChannelId(42003), priv_a, priv_z, priv_funding_a, priv_funding_z)
    val update_az = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, priv_z.publicKey, chan_az.shortChannelId, cltvExpiryDelta = 7, 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, 500000000L)

    router ! PeerRoutingMessage(null, remoteNodeId, chan_ac)
    router ! PeerRoutingMessage(null, remoteNodeId, chan_ax)
    router ! PeerRoutingMessage(null, remoteNodeId, chan_ay)
    router ! PeerRoutingMessage(null, remoteNodeId, chan_az)
    // router won't validate channels before it has a recent enough channel update
    router ! PeerRoutingMessage(null, remoteNodeId, update_ac)
    router ! PeerRoutingMessage(null, remoteNodeId, update_ax)
    router ! PeerRoutingMessage(null, remoteNodeId, update_ay)
    router ! PeerRoutingMessage(null, remoteNodeId, update_az)
    watcher.expectMsg(ValidateRequest(chan_ac))
    watcher.expectMsg(ValidateRequest(chan_ax))
    watcher.expectMsg(ValidateRequest(chan_ay))
    watcher.expectMsg(ValidateRequest(chan_az))
    watcher.send(router, ValidateResult(chan_ac, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_c)))) :: Nil, lockTime = 0), UtxoStatus.Unspent)))
    watcher.send(router, ValidateResult(chan_ax, Left(new RuntimeException(s"funding tx not found"))))
    watcher.send(router, ValidateResult(chan_ay, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, randomKey.publicKey)))) :: Nil, lockTime = 0), UtxoStatus.Unspent)))
    watcher.send(router, ValidateResult(chan_az, Right(Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, priv_funding_z.publicKey)))) :: Nil, lockTime = 0), UtxoStatus.Spent(spendingTxConfirmed = true))))
    watcher.expectMsgType[WatchSpentBasic]
    watcher.expectNoMsg(1 second)

    eventListener.expectMsg(ChannelsDiscovered(SingleChannelDiscovered(chan_ac, Satoshi(1000000)) :: Nil))
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
    val sender = TestProbe()
    val channelId_ac = ShortChannelId(420000, 5, 0)
    val chan_ac = channelAnnouncement(channelId_ac, priv_a, priv_c, priv_funding_a, priv_funding_c)
    val buggy_chan_ac = chan_ac.copy(nodeSignature1 = chan_ac.nodeSignature2)
    sender.send(router, PeerRoutingMessage(null, remoteNodeId, buggy_chan_ac))
    sender.expectMsg(TransportHandler.ReadAck(buggy_chan_ac))
    sender.expectMsg(InvalidSignature(buggy_chan_ac))
  }

  test("handle bad signature for NodeAnnouncement") { fixture =>
    import fixture._
    val sender = TestProbe()
    val buggy_ann_a = ann_a.copy(signature = ann_b.signature, timestamp = ann_a.timestamp + 1)
    sender.send(router, PeerRoutingMessage(null, remoteNodeId, buggy_ann_a))
    sender.expectMsg(TransportHandler.ReadAck(buggy_ann_a))
    sender.expectMsg(InvalidSignature(buggy_ann_a))
  }

  test("handle bad signature for ChannelUpdate") { fixture =>
    import fixture._
    val sender = TestProbe()
    val buggy_channelUpdate_ab = channelUpdate_ab.copy(signature = ann_b.signature, timestamp = channelUpdate_ab.timestamp + 1)
    sender.send(router, PeerRoutingMessage(null, remoteNodeId, buggy_channelUpdate_ab))
    sender.expectMsg(TransportHandler.ReadAck(buggy_channelUpdate_ab))
    sender.expectMsg(InvalidSignature(buggy_channelUpdate_ab))
  }

  test("route not found (unreachable target)") { fixture =>
    import fixture._
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(a, f, DEFAULT_AMOUNT_MSAT))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route not found (non-existing source)") { fixture =>
    import fixture._
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(randomKey.publicKey, f, DEFAULT_AMOUNT_MSAT))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route not found (non-existing target)") { fixture =>
    import fixture._
    val sender = TestProbe()
    // no route a->f
    sender.send(router, RouteRequest(a, randomKey.publicKey, DEFAULT_AMOUNT_MSAT))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("route found") { fixture =>
    import fixture._
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, d, DEFAULT_AMOUNT_MSAT, routeParams = relaxedRouteParams))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.hops.map(_.nodeId).toList === a :: b :: c :: Nil)
    assert(res.hops.last.nextNodeId === d)
  }

  test("route found (with extra routing info)") { fixture =>
    import fixture._
    val sender = TestProbe()
    val x = PublicKey("02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73")
    val y = PublicKey("03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a")
    val z = PublicKey("0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484")
    val extraHop_cx = ExtraHop(c, ShortChannelId(1), 10, 11, 12)
    val extraHop_xy = ExtraHop(x, ShortChannelId(2), 10, 11, 12)
    val extraHop_yz = ExtraHop(y, ShortChannelId(3), 20, 21, 22)
    sender.send(router, RouteRequest(a, z, DEFAULT_AMOUNT_MSAT, assistedRoutes = Seq(extraHop_cx :: extraHop_xy :: extraHop_yz :: Nil)))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.hops.map(_.nodeId).toList === a :: b :: c :: x :: y :: Nil)
    assert(res.hops.last.nextNodeId === z)
  }

  test("route not found (channel disabled)") { fixture =>
    import fixture._
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, d, DEFAULT_AMOUNT_MSAT, routeParams = relaxedRouteParams))
    val res = sender.expectMsgType[RouteResponse]
    assert(res.hops.map(_.nodeId).toList === a :: b :: c :: Nil)
    assert(res.hops.last.nextNodeId === d)

    val channelUpdate_cd1 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, d, channelId_cd, cltvExpiryDelta = 3, 0, feeBaseMsat = 153000, feeProportionalMillionths = 4, htlcMaximumMsat = 500000000L, enable = false)
    sender.send(router, PeerRoutingMessage(null, remoteNodeId, channelUpdate_cd1))
    sender.expectMsg(TransportHandler.ReadAck(channelUpdate_cd1))
    sender.send(router, RouteRequest(a, d, DEFAULT_AMOUNT_MSAT, routeParams = relaxedRouteParams))
    sender.expectMsg(Failure(RouteNotFound))
  }

  test("temporary channel exclusion") { fixture =>
    import fixture._
    val sender = TestProbe()
    sender.send(router, RouteRequest(a, d, DEFAULT_AMOUNT_MSAT, routeParams = relaxedRouteParams))
    sender.expectMsgType[RouteResponse]
    val bc = ChannelDesc(channelId_bc, b, c)
    // let's exclude channel b->c
    sender.send(router, ExcludeChannel(bc))
    sender.send(router, RouteRequest(a, d, DEFAULT_AMOUNT_MSAT, routeParams = relaxedRouteParams))
    sender.expectMsg(Failure(RouteNotFound))
    // note that cb is still available!
    sender.send(router, RouteRequest(d, a, DEFAULT_AMOUNT_MSAT, routeParams = relaxedRouteParams))
    sender.expectMsgType[RouteResponse]
    // let's remove the exclusion
    sender.send(router, LiftChannelExclusion(bc))
    sender.send(router, RouteRequest(a, d, DEFAULT_AMOUNT_MSAT, routeParams = relaxedRouteParams))
    sender.expectMsgType[RouteResponse]
  }

  test("send routing state") { fixture =>
    import fixture._
    val sender = TestProbe()
    sender.send(router, GetRoutingState)
    val state = sender.expectMsgType[RoutingState]
    assert(state.channels.size == 4)
    assert(state.nodes.size == 6)
    assert(state.updates.size == 8)
  }

  test("ask for channels that we marked as stale for which we receive a new update") { fixture =>
    import fixture._
    val blockHeight = Globals.blockCount.get().toInt - 2020
    val channelId = ShortChannelId(blockHeight, 5, 0)
    val announcement = channelAnnouncement(channelId, priv_a, priv_c, priv_funding_a, priv_funding_c)
    val timestamp = Platform.currentTime / 1000 - 1209600 - 1
    val update = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, c, channelId, cltvExpiryDelta = 7, htlcMinimumMsat = 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, htlcMaximumMsat = 5, timestamp = timestamp)
    val probe = TestProbe()
    probe.ignoreMsg { case _: TransportHandler.ReadAck => true }
    probe.send(router, PeerRoutingMessage(null, remoteNodeId, announcement))
    watcher.expectMsgType[ValidateRequest]
    probe.send(router, PeerRoutingMessage(null, remoteNodeId, update))
    watcher.send(router, ValidateResult(announcement, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_c)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))

    probe.send(router, TickPruneStaleChannels)
    val sender = TestProbe()
    sender.send(router, GetRoutingState)
    val state = sender.expectMsgType[RoutingState]


    val update1 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, c, channelId, cltvExpiryDelta = 7, htlcMinimumMsat = 0, feeBaseMsat = 766000, feeProportionalMillionths = 10, htlcMaximumMsat = 500000000L, timestamp = Platform.currentTime / 1000)

    // we want to make sure that transport receives the query
    val transport = TestProbe()
    probe.send(router, PeerRoutingMessage(transport.ref, remoteNodeId, update1))
    val query = transport.expectMsgType[QueryShortChannelIds]
    assert(ChannelRangeQueries.decodeShortChannelIds(query.data)._2 == SortedSet(channelId))
  }
}
