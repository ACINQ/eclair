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

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.Script.{pay2wsh, write}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, SatoshiLong, Transaction, TxOut}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{UtxoStatus, ValidateRequest, ValidateResult, WatchExternalChannelSpent}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager}
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.payment.send.BlindedRecipient
import fr.acinq.eclair.payment.{Bolt12Invoice, PaymentBlindedRoute}
import fr.acinq.eclair.router.Announcements._
import fr.acinq.eclair.router.BaseRouterSpec.channelAnnouncement
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}
import fr.acinq.eclair.wire.protocol._
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Base class for router testing.
 * It is re-used in payment FSM tests
 * Created by PM on 29/08/2016.
 */

abstract class BaseRouterSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, router: ActorRef, watcher: TestProbe)

  val remoteNodeId = PrivateKey(ByteVector32(ByteVector.fill(32)(1))).publicKey
  val publicChannelCapacity = 1000000 sat
  val htlcMaximum = 500000000 msat

  val seed = ByteVector32(ByteVector.fill(32)(2))
  val testNodeKeyManager = new LocalNodeKeyManager(seed, Block.RegtestGenesisBlock.hash)
  val testChannelKeyManager = new LocalChannelKeyManager(seed, Block.RegtestGenesisBlock.hash)

  val (priv_a, priv_b, priv_c, priv_d, priv_e, priv_f, priv_g, priv_h) = (testNodeKeyManager.nodeKey.privateKey, randomKey(), randomKey(), randomKey(), randomKey(), randomKey(), randomKey(), randomKey())
  val (a, b, c, d, e, f, g, h) = (priv_a.publicKey, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey, priv_f.publicKey, priv_g.publicKey, priv_h.publicKey)

  val (priv_funding_a, priv_funding_b, priv_funding_c, priv_funding_d, priv_funding_e, priv_funding_f, priv_funding_g, priv_funding_h) = (randomKey(), randomKey(), randomKey(), randomKey(), randomKey(), randomKey(), randomKey(), randomKey())
  val (funding_a, funding_b, funding_c, funding_d, funding_e, funding_f, funding_g, funding_h) = (priv_funding_a.publicKey, priv_funding_b.publicKey, priv_funding_c.publicKey, priv_funding_d.publicKey, priv_funding_e.publicKey, priv_funding_f.publicKey, priv_funding_g.publicKey, priv_funding_h.publicKey)

  // in the tests we are 'a', we don't define a node_a, it will be generated automatically when the router validates the first channel
  val node_b = makeNodeAnnouncement(priv_b, "node-B", Color(50, 99, -80), Nil, Features.empty)
  val node_c = makeNodeAnnouncement(priv_c, "node-C", Color(123, 100, -40), Nil, TestConstants.Bob.nodeParams.features.nodeAnnouncementFeatures())
  val node_d = makeNodeAnnouncement(priv_d, "node-D", Color(-120, -20, 60), Nil, Features.empty)
  val node_e = makeNodeAnnouncement(priv_e, "node-E", Color(-50, 0, 10), Nil, Features.empty)
  val node_f = makeNodeAnnouncement(priv_f, "node-F", Color(30, 10, -50), Nil, Features.empty)
  val node_g = makeNodeAnnouncement(priv_g, "node-G", Color(30, 10, -50), Nil, Features.empty)
  val node_h = makeNodeAnnouncement(priv_h, "node-H", Color(30, 10, -50), Nil, Features.empty)

  val scid_ab = RealShortChannelId(BlockHeight(420000), 1, 0)
  val scid_bc = RealShortChannelId(BlockHeight(420000), 2, 0)
  val scid_cd = RealShortChannelId(BlockHeight(420000), 3, 0)
  val scid_ef = RealShortChannelId(BlockHeight(420000), 4, 0)
  val scid_ag_private = RealShortChannelId(BlockHeight(420000), 5, 0)
  val scid_gh = RealShortChannelId(BlockHeight(420000), 6, 0)

  val channelId_ag_private = randomBytes32()

  val alias_ab = ShortChannelId.generateLocalAlias()
  val alias_ba = ShortChannelId.generateLocalAlias()
  val alias_ag_private = ShortChannelId.generateLocalAlias()
  val alias_ga_private = ShortChannelId.generateLocalAlias()

  val scids_ab = ShortIds(RealScidStatus.Final(scid_ab), alias_ab, Some(alias_ba))
  val scids_ag_private = ShortIds(RealScidStatus.Final(scid_ag_private), alias_ag_private, Some(alias_ga_private))

  val chan_ab = channelAnnouncement(scid_ab, priv_a, priv_b, priv_funding_a, priv_funding_b)
  val chan_bc = channelAnnouncement(scid_bc, priv_b, priv_c, priv_funding_b, priv_funding_c)
  val chan_cd = channelAnnouncement(scid_cd, priv_c, priv_d, priv_funding_c, priv_funding_d)
  val chan_ef = channelAnnouncement(scid_ef, priv_e, priv_f, priv_funding_e, priv_funding_f)
  val chan_gh = channelAnnouncement(scid_gh, priv_g, priv_h, priv_funding_g, priv_funding_h)

  val update_ab = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, b, scid_ab, CltvExpiryDelta(7), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 10, htlcMaximumMsat = htlcMaximum)
  val update_ba = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, a, scid_ab, CltvExpiryDelta(7), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 10, htlcMaximumMsat = htlcMaximum)
  val update_bc = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, scid_bc, CltvExpiryDelta(5), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 1, htlcMaximumMsat = htlcMaximum)
  val update_cb = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, b, scid_bc, CltvExpiryDelta(5), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 1, htlcMaximumMsat = htlcMaximum)
  val update_cd = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, d, scid_cd, CltvExpiryDelta(3), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 4, htlcMaximumMsat = htlcMaximum)
  val update_dc = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_d, c, scid_cd, CltvExpiryDelta(3), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 4, htlcMaximumMsat = htlcMaximum)
  val update_ef = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_e, f, scid_ef, CltvExpiryDelta(9), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 8, htlcMaximumMsat = htlcMaximum)
  val update_fe = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_f, e, scid_ef, CltvExpiryDelta(9), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 8, htlcMaximumMsat = htlcMaximum)
  // We use our local alias for private channel updates that we store internally.
  val update_ag_private = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_a, g, alias_ag_private, CltvExpiryDelta(7), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 10, htlcMaximumMsat = htlcMaximum)
  // Our peer uses our local alias to send us a private channel update.
  val update_ga_private = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_g, a, alias_ag_private, CltvExpiryDelta(7), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 10, htlcMaximumMsat = htlcMaximum)
  val update_gh = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_g, h, scid_gh, CltvExpiryDelta(7), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 10, htlcMaximumMsat = htlcMaximum)
  val update_hg = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_h, g, scid_gh, CltvExpiryDelta(7), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 10, htlcMaximumMsat = htlcMaximum)

  override def withFixture(test: OneArgTest): Outcome = {
    // the network will be a --(1)--> b ---(2)--> c --(3)--> d
    //                     |
    //                     +---(5)--> g ---(6)--> h
    // and e --(4)--> f (we are a)
    within(30 seconds) {
      // first we make sure that we correctly resolve channelId+direction to nodeId
      assert(ChannelDesc(update_ab, chan_ab) == ChannelDesc(chan_ab.shortChannelId, a, b))
      assert(ChannelDesc(update_bc, chan_bc) == ChannelDesc(chan_bc.shortChannelId, b, c))
      assert(ChannelDesc(update_cd, chan_cd) == ChannelDesc(chan_cd.shortChannelId, c, d))
      assert(ChannelDesc(update_ef, chan_ef) == ChannelDesc(chan_ef.shortChannelId, e, f))
      val privateChannel_ag = PrivateChannel(channelId_ag_private, scids_ag_private, a, g, None, None, ChannelMeta(1000 msat, 1000 msat))
      assert(ChannelDesc(update_ag_private, privateChannel_ag) == ChannelDesc(scids_ag_private.localAlias, a, g))
      assert(ChannelDesc(update_ga_private, privateChannel_ag) == ChannelDesc(scids_ag_private.localAlias, g, a))
      assert(ChannelDesc(update_gh, chan_gh) == ChannelDesc(chan_gh.shortChannelId, g, h))

      // let's set up the router
      val sender = TestProbe()
      val peerConnection = TestProbe()
      peerConnection.ignoreMsg { case _: TransportHandler.ReadAck => true }
      val watcher = TestProbe()
      import com.softwaremill.quicklens._
      val nodeParams = Alice.nodeParams
        .modify(_.nodeKeyManager).setTo(testNodeKeyManager)
      val router = system.actorOf(Router.props(nodeParams, watcher.ref))
      // we announce channels
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_ab))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_bc))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_cd))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_ef))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_gh))
      // then nodes
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_b))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_c))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_d))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_e))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_f))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_g))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, node_h))
      // then channel updates
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ab))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ba))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_bc))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_cb))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_cd))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_dc))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ef))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_fe))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_gh))
      peerConnection.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_hg))
      // then private channels
      sender.send(router, ShortChannelIdAssigned(sender.ref, channelId_ag_private, scids_ag_private, remoteNodeId = g))
      sender.send(router, LocalChannelUpdate(sender.ref, channelId_ag_private, scids_ag_private, g, None, update_ag_private, CommitmentsSpec.makeCommitments(30000000 msat, 8000000 msat, a, g, announceChannel = false)))
      sender.send(router, PeerRoutingMessage(peerConnection.ref, remoteNodeId, update_ga_private))
      // watcher receives the get tx requests
      assert(watcher.expectMsgType[ValidateRequest].ann == chan_ab)
      assert(watcher.expectMsgType[ValidateRequest].ann == chan_bc)
      assert(watcher.expectMsgType[ValidateRequest].ann == chan_cd)
      assert(watcher.expectMsgType[ValidateRequest].ann == chan_ef)
      assert(watcher.expectMsgType[ValidateRequest].ann == chan_gh)
      // and answers with valid scripts
      watcher.send(router, ValidateResult(chan_ab, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(publicChannelCapacity, write(pay2wsh(Scripts.multiSig2of2(funding_a, funding_b)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
      watcher.send(router, ValidateResult(chan_bc, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(publicChannelCapacity, write(pay2wsh(Scripts.multiSig2of2(funding_b, funding_c)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
      watcher.send(router, ValidateResult(chan_cd, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(publicChannelCapacity, write(pay2wsh(Scripts.multiSig2of2(funding_c, funding_d)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
      watcher.send(router, ValidateResult(chan_ef, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(publicChannelCapacity, write(pay2wsh(Scripts.multiSig2of2(funding_e, funding_f)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
      watcher.send(router, ValidateResult(chan_gh, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(publicChannelCapacity, write(pay2wsh(Scripts.multiSig2of2(funding_g, funding_h)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
      // watcher receives watch-spent request
      val watchedShortChannelIds = Set(
        watcher.expectMsgType[WatchExternalChannelSpent].shortChannelId,
        watcher.expectMsgType[WatchExternalChannelSpent].shortChannelId,
        watcher.expectMsgType[WatchExternalChannelSpent].shortChannelId,
        watcher.expectMsgType[WatchExternalChannelSpent].shortChannelId,
        watcher.expectMsgType[WatchExternalChannelSpent].shortChannelId,
      )
      assert(watchedShortChannelIds == Set(scid_ab, scid_bc, scid_cd, scid_ef, scid_gh))
      // all messages are acked
      peerConnection.expectMsgAllOf(
        GossipDecision.Accepted(chan_ab),
        GossipDecision.Accepted(chan_bc),
        GossipDecision.Accepted(chan_cd),
        GossipDecision.Accepted(chan_ef),
        GossipDecision.Accepted(chan_gh),
        GossipDecision.Accepted(update_ab),
        GossipDecision.Accepted(update_ba),
        GossipDecision.Accepted(update_bc),
        GossipDecision.Accepted(update_cb),
        GossipDecision.Accepted(update_cd),
        GossipDecision.Accepted(update_dc),
        GossipDecision.Accepted(update_ef),
        GossipDecision.Accepted(update_fe),
        GossipDecision.Accepted(update_gh),
        GossipDecision.Accepted(update_hg),
        GossipDecision.Accepted(update_ga_private),
        GossipDecision.Accepted(node_b),
        GossipDecision.Accepted(node_c),
        GossipDecision.Accepted(node_d),
        GossipDecision.Accepted(node_e),
        GossipDecision.Accepted(node_f),
        GossipDecision.Accepted(node_g),
        GossipDecision.Accepted(node_h))
      peerConnection.expectNoMessage()
      awaitCond({
        sender.send(router, GetNodes)
        val nodes = sender.expectMsgType[Iterable[NodeAnnouncement]]
        sender.send(router, GetChannels)
        val channels = sender.expectMsgType[Iterable[ChannelAnnouncement]]
        sender.send(router, GetChannelUpdates)
        val updates = sender.expectMsgType[Iterable[ChannelUpdate]]
        nodes.size == 8 && channels.size == 5 && updates.size == 12
      }, max = 10 seconds, interval = 1 second)

      withFixture(test.toNoArgTest(FixtureParam(nodeParams, router, watcher)))
    }
  }

}

object BaseRouterSpec {

  def channelAnnouncement(shortChannelId: RealShortChannelId, node1_priv: PrivateKey, node2_priv: PrivateKey, funding1_priv: PrivateKey, funding2_priv: PrivateKey) = {
    val witness = Announcements.generateChannelAnnouncementWitness(Block.RegtestGenesisBlock.hash, shortChannelId, node1_priv.publicKey, node2_priv.publicKey, funding1_priv.publicKey, funding2_priv.publicKey, Features.empty)
    val node1_sig = Announcements.signChannelAnnouncement(witness, node1_priv)
    val funding1_sig = Announcements.signChannelAnnouncement(witness, funding1_priv)
    val node2_sig = Announcements.signChannelAnnouncement(witness, node2_priv)
    val funding2_sig = Announcements.signChannelAnnouncement(witness, funding2_priv)
    makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, shortChannelId, node1_priv.publicKey, node2_priv.publicKey, funding1_priv.publicKey, funding2_priv.publicKey, node1_sig, node2_sig, funding1_sig, funding2_sig)
  }

  def channelHopFromUpdate(nodeId: PublicKey, nextNodeId: PublicKey, channelUpdate: ChannelUpdate): ChannelHop = {
    ChannelHop(channelUpdate.shortChannelId, nodeId, nextNodeId, HopRelayParams.FromAnnouncement(channelUpdate))
  }

  def blindedRouteFromHops(amount: MilliSatoshi,
                           expiry: CltvExpiry,
                           hops: Seq[ChannelHop],
                           routeExpiry: CltvExpiry,
                           preimage: ByteVector32 = randomBytes32(),
                           pathId: ByteVector = randomBytes(32)): (Bolt12Invoice, BlindedHop, BlindedRecipient) = {
    val (invoice, recipient) = blindedRoutesFromPaths(amount, expiry, Seq(hops), routeExpiry, preimage, pathId)
    (invoice, recipient.blindedHops.head, recipient)
  }

  def blindedRoutesFromPaths(amount: MilliSatoshi,
                             expiry: CltvExpiry,
                             paths: Seq[Seq[ChannelHop]],
                             routeExpiry: CltvExpiry,
                             preimage: ByteVector32 = randomBytes32(),
                             pathId: ByteVector = randomBytes(32)): (Bolt12Invoice, BlindedRecipient) = {
    val recipientKey = randomKey()
    val features = Features[Bolt12Feature](
      Features.BasicMultiPartPayment -> FeatureSupport.Optional,
    )
    val offer = Offer(None, "Bolt12 r0cks", recipientKey.publicKey, features, Block.RegtestGenesisBlock.hash)
    val invoiceRequest = InvoiceRequest(offer, amount, 1, features, randomKey(), Block.RegtestGenesisBlock.hash)
    val blindedRoutes = paths.map(hops => {
      val blindedRoute = BlindedRouteCreation.createBlindedRouteFromHops(hops, pathId, 1 msat, routeExpiry).route
      val paymentInfo = BlindedRouteCreation.aggregatePaymentInfo(amount, hops, Channel.MIN_CLTV_EXPIRY_DELTA)
      PaymentBlindedRoute(blindedRoute, paymentInfo)
    })
    val invoice = Bolt12Invoice(invoiceRequest, preimage, recipientKey, 300 seconds, features, blindedRoutes)
    val recipient = BlindedRecipient(invoice, amount, expiry, Set.empty)
    (invoice, recipient)
  }

}
