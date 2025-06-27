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

package fr.acinq.eclair.integration

import akka.actor.testkit.typed.scaladsl.{TestProbe => TypedProbe}
import akka.actor.typed.scaladsl.adapter._
import akka.pattern.pipe
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, SatoshiLong}
import fr.acinq.eclair.EncodedNodeId.ShortChannelIdDir
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{Watch, WatchFundingConfirmed}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.{BroadcastChannelUpdate, PeriodicRefresh}
import fr.acinq.eclair.crypto.Sphinx.DecryptedFailurePacket
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient, buildRoute}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.offer.OfferManager
import fr.acinq.eclair.payment.offer.OfferManager._
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceiveStandardPayment
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPaymentToNode, SendTrampolinePayment}
import fr.acinq.eclair.router.Graph.PaymentWeightRatios
import fr.acinq.eclair.router.Router.{ChannelHop, GossipDecision, PublicChannel}
import fr.acinq.eclair.router.{Announcements, AnnouncementsBatchValidationSpec, Router}
import fr.acinq.eclair.wire.protocol.OfferTypes.{Offer, OfferPaths}
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, IncorrectOrUnknownPaymentDetails}
import fr.acinq.eclair.{CltvExpiryDelta, EclairImpl, EncodedNodeId, Features, Kit, MilliSatoshiLong, ShortChannelId, TimestampMilli, randomBytes32, randomKey}
import org.json4s.JsonAST.{JString, JValue}
import scodec.bits.{ByteVector, HexStringSyntax}

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Created by PM on 15/03/2017.
 */

class PaymentIntegrationSpec extends IntegrationSpec {

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.channel.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29730, "eclair.api.port" -> 28080, "eclair.channel.channel-flags.announce-channel" -> false).asJava).withFallback(withStaticRemoteKey).withFallback(commonConfig)) // A's channels are private
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.channel.expiry-delta-blocks" -> 131, "eclair.server.port" -> 29731, "eclair.api.port" -> 28081, "eclair.trampoline-payments-enable" -> true, "eclair.onion-messages.relay-policy" -> "relay-all").asJava).withFallback(withStaticRemoteKey).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.channel.expiry-delta-blocks" -> 132, "eclair.server.port" -> 29732, "eclair.api.port" -> 28082, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(withDualFunding).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.channel.expiry-delta-blocks" -> 133, "eclair.server.port" -> 29733, "eclair.api.port" -> 28083, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(withStaticRemoteKey).withFallback(commonConfig))
    instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.channel.expiry-delta-blocks" -> 134, "eclair.server.port" -> 29734, "eclair.api.port" -> 28084).asJava).withFallback(withDualFunding).withFallback(commonConfig))
    instantiateEclairNode("F", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F", "eclair.channel.expiry-delta-blocks" -> 135, "eclair.server.port" -> 29735, "eclair.api.port" -> 28085, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(commonConfig))
    instantiateEclairNode("G", ConfigFactory.parseMap(Map("eclair.node-alias" -> "G", "eclair.channel.expiry-delta-blocks" -> 136, "eclair.server.port" -> 29736, "eclair.api.port" -> 28086, "eclair.relay.fees.public-channels.fee-base-msat" -> 1010, "eclair.relay.fees.public-channels.fee-proportional-millionths" -> 102, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(commonConfig))
  }

  test("connect nodes") {
    //       ,--G--,
    //      /       \
    // A---B ------- C ==== D
    //      \       / \
    //       '--E--'   '--F
    //
    // All channels have fees 1 sat + 200 millionths, except for G that have fees 1010 msat + 102 millionths

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("A"), nodes("B"), 11000000 sat, 0 msat)
    connect(nodes("B"), nodes("C"), 2000000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 5000000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 5000000 sat, 0 msat)
    connect(nodes("C"), nodes("F"), 16000000 sat, 0 msat)
    connect(nodes("B"), nodes("E"), 10000000 sat, 0 msat)
    connect(nodes("E"), nodes("C"), 10000000 sat, 0 msat)
    connect(nodes("B"), nodes("G"), 16000000 sat, 0 msat)
    connect(nodes("G"), nodes("C"), 16000000 sat, 0 msat)

    val numberOfChannels = 9
    val channelEndpointsCount = 2 * numberOfChannels

    // we make sure all channels have set up their WatchConfirmed for the funding tx
    awaitCond({
      val watches = nodes.values.foldLeft(Set.empty[Watch[_]]) {
        case (watches, setup) =>
          setup.watcher ! ZmqWatcher.ListWatches(sender.ref)
          watches ++ sender.expectMsgType[Set[Watch[_]]]
      }
      watches.count(_.isInstanceOf[WatchFundingConfirmed]) == channelEndpointsCount
    }, max = 20 seconds, interval = 1 second)

    // confirming the funding tx
    generateBlocks(8)

    within(60 seconds) {
      var count = 0
      while (count < channelEndpointsCount) {
        if (eventListener.expectMsgType[ChannelStateChanged](60 seconds).currentState == NORMAL) count = count + 1
      }
    }
  }

  def awaitAnnouncements(subset: Map[String, Kit], nodes: Int, privateChannels: Int, publicChannels: Int, privateUpdates: Int, publicUpdates: Int): Unit = {
    val sender = TestProbe()
    subset.foreach {
      case (node, setup) =>
        withClue(node) {
          awaitAssert({
            sender.send(setup.router, Router.GetRouterData)
            val data = sender.expectMsgType[Router.Data]
            assert(data.nodes.size == nodes)
            assert(data.privateChannels.size == privateChannels)
            assert(data.channels.size == publicChannels)
            assert(data.privateChannels.values.flatMap(pc => pc.update_1_opt.toSeq ++ pc.update_2_opt.toSeq).size == privateUpdates)
            assert(data.channels.values.flatMap(pc => pc.update_1_opt.toSeq ++ pc.update_2_opt.toSeq).size == publicUpdates)
          }, max = 10 seconds, interval = 1 second)
        }
    }
  }

  test("wait for network announcements") {
    // A requires private channels, as a consequence:
    // - only A and B know about channel A-B (and there is no channel_announcement)
    // - A is not announced (no node_announcement)
    awaitAnnouncements(nodes.view.filterKeys(key => List("A", "B").contains(key)).toMap, nodes = 6, privateChannels = 1, publicChannels = 8, privateUpdates = 2, publicUpdates = 16)
    awaitAnnouncements(nodes.view.filterKeys(key => List("C", "D", "E", "G").contains(key)).toMap, nodes = 6, privateChannels = 0, publicChannels = 8, privateUpdates = 0, publicUpdates = 16)
  }

  test("wait for channels balance") {
    // Channels balance should now be available in the router
    val sender = TestProbe()
    val nodeId = nodes("C").nodeParams.nodeId
    sender.send(nodes("C").router, Router.GetRoutingState)
    val routingState = sender.expectMsgType[Router.RoutingState]
    val publicChannels = routingState.channels.filter(pc => Set(pc.ann.nodeId1, pc.ann.nodeId2).contains(nodeId))
    assert(publicChannels.nonEmpty)
    publicChannels.foreach(pc => assert(pc.meta_opt.exists(m => m.balance1 > 0.msat || m.balance2 > 0.msat), pc))
  }

  test("send an HTLC A->D") {
    val (sender, eventListener, holdTimesRecorder) = (TestProbe(), TestProbe(), TestProbe())
    nodes("D").system.eventStream.subscribe(eventListener.ref, classOf[PaymentMetadataReceived])
    nodes("A").system.eventStream.subscribe(holdTimesRecorder.ref, classOf[Router.ReportedHoldTimes])

    // first we retrieve a payment hash from D
    val amountMsat = 4200000.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.paymentMetadata.nonEmpty)

    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(sender.ref, amountMsat, invoice, Nil, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
    assert(Crypto.sha256(ps.paymentPreimage) == invoice.paymentHash)
    eventListener.expectMsg(PaymentMetadataReceived(invoice.paymentHash, invoice.paymentMetadata.get))

    assert(holdTimesRecorder.expectMsgType[Router.ReportedHoldTimes].holdTimes.map(_.remoteNodeId) == Seq("B", "C", "D").map(nodes(_).nodeParams.nodeId))
  }

  test("send an HTLC A->D with an invalid expiry delta for B") {
    val sender = TestProbe()
    // to simulate this, we will update B's relay params
    // first we find out the short channel id for channel B-C
    sender.send(nodes("B").router, Router.GetChannels)
    val shortIdBC = sender.expectMsgType[Iterable[ChannelAnnouncement]].find(c => Set(c.nodeId1, c.nodeId2) == Set(nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId)).get.shortChannelId
    // we also need the full commitment
    nodes("B").register ! Register.ForwardShortId(sender.ref.toTyped[Any], shortIdBC, CMD_GET_CHANNEL_INFO(sender.ref.toTyped[Any]))
    val normalBC = sender.expectMsgType[RES_GET_CHANNEL_INFO].data.asInstanceOf[DATA_NORMAL]
    // we then forge a new channel_update for B-C...
    val channelUpdateBC = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, nodes("B").nodeParams.privateKey, nodes("C").nodeParams.nodeId, shortIdBC, nodes("B").nodeParams.channelConf.expiryDelta + 1, nodes("C").nodeParams.channelConf.htlcMinimum, nodes("B").nodeParams.relayParams.publicChannelFees.feeBase, nodes("B").nodeParams.relayParams.publicChannelFees.feeProportionalMillionths, 500000000 msat)
    // ...and notify B's relayer
    nodes("B").system.eventStream.publish(LocalChannelUpdate(system.deadLetters, normalBC.channelId, normalBC.aliases, normalBC.commitments.remoteNodeId, normalBC.lastAnnouncedCommitment_opt, channelUpdateBC, normalBC.commitments))
    // we retrieve a payment hash from D
    val amountMsat = 4200000.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    // then we make the actual payment, do not randomize the route to make sure we route through node B
    val sendReq = SendPaymentToNode(sender.ref, amountMsat, invoice, Nil, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will receive an error from B that include the updated channel update, then will retry the payment
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
    assert(Crypto.sha256(ps.paymentPreimage) == invoice.paymentHash)

    def updateFor(n: PublicKey, pc: PublicChannel): Option[ChannelUpdate] = if (n == pc.ann.nodeId1) pc.update_1_opt else if (n == pc.ann.nodeId2) pc.update_2_opt else throw new IllegalArgumentException("this node is unrelated to this channel")

    awaitCond({
      // in the meantime, the router will have updated its state
      sender.send(nodes("A").router, Router.GetChannelsMap)
      // we then put everything back like before by asking B to refresh its channel update (this will override the one we created)
      val u_opt = updateFor(nodes("B").nodeParams.nodeId, sender.expectMsgType[Map[ShortChannelId, PublicChannel]](10 seconds).apply(channelUpdateBC.shortChannelId))
      u_opt.contains(channelUpdateBC)
    }, max = 30 seconds, interval = 1 seconds)

    // first let's wait 3 seconds to make sure the timestamp of the new channel_update will be strictly greater than the former
    sender.expectNoMessage(3 seconds)
    nodes("B").register ! Register.ForwardShortId(sender.ref.toTyped[Any], shortIdBC, BroadcastChannelUpdate(PeriodicRefresh))
    nodes("B").register ! Register.ForwardShortId(sender.ref.toTyped[Any], shortIdBC, CMD_GET_CHANNEL_INFO(sender.ref.toTyped[Any]))
    val channelUpdateBC_new = sender.expectMsgType[RES_GET_CHANNEL_INFO].data.asInstanceOf[DATA_NORMAL].channelUpdate
    logger.info(s"channelUpdateBC=$channelUpdateBC")
    logger.info(s"channelUpdateBC_new=$channelUpdateBC_new")
    assert(channelUpdateBC_new.timestamp > channelUpdateBC.timestamp)
    assert(channelUpdateBC_new.cltvExpiryDelta == nodes("B").nodeParams.channelConf.expiryDelta)
    awaitCond({
      sender.send(nodes("A").router, Router.GetChannelsMap)
      val u = updateFor(nodes("B").nodeParams.nodeId, sender.expectMsgType[Map[ShortChannelId, PublicChannel]].apply(channelUpdateBC.shortChannelId)).get
      u.cltvExpiryDelta == nodes("B").nodeParams.channelConf.expiryDelta
    }, max = 30 seconds, interval = 1 second)
  }

  test("send an HTLC A->D with an amount greater than capacity of B-C") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D
    val amountMsat = 300000000.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    // then we make the payment (B-C has a smaller capacity than A-B and C-D)
    val sendReq = SendPaymentToNode(sender.ref, amountMsat, invoice, Nil, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will first receive an error from C, then retry and route around C: A->B->E->C->D
    sender.expectMsgType[UUID]
    sender.expectMsgType[PaymentSent] // the payment FSM will also reply to the sender after the payment is completed
  }

  test("send an HTLC A->D with an unknown payment hash") {
    val (sender, holdTimesRecorder) = (TestProbe(), TestProbe())
    nodes("A").system.eventStream.subscribe(holdTimesRecorder.ref, classOf[Router.ReportedHoldTimes])

    val amount = 100000000 msat
    val unknownInvoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(amount), randomBytes32(), nodes("D").nodeParams.privateKey, Left("test"), finalCltvExpiryDelta)
    val invoice = SendPaymentToNode(sender.ref, amount, unknownInvoice, Nil, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, invoice)

    // A will receive an error from D and won't retry
    val paymentId = sender.expectMsgType[UUID]
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.id == paymentId)
    assert(failed.paymentHash == invoice.paymentHash)
    assert(failed.failures.size == 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e == DecryptedFailurePacket(nodes("D").nodeParams.nodeId, IncorrectOrUnknownPaymentDetails(amount, getBlockHeight())))

    assert(holdTimesRecorder.expectMsgType[Router.ReportedHoldTimes].holdTimes.map(_.remoteNodeId) == Seq("B", "C", "D").map(nodes(_).nodeParams.nodeId))
  }

  test("send an HTLC A->D with a lower amount than requested") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = 200000000.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]

    // A send payment of only 1 mBTC
    val sendReq = SendPaymentToNode(sender.ref, 100000000 msat, invoice, Nil, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)

    // A will first receive an IncorrectPaymentAmount error from D
    val paymentId = sender.expectMsgType[UUID]
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.id == paymentId)
    assert(failed.paymentHash == invoice.paymentHash)
    assert(failed.failures.size == 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e == DecryptedFailurePacket(nodes("D").nodeParams.nodeId, IncorrectOrUnknownPaymentDetails(100000000 msat, getBlockHeight())))
  }

  test("send an HTLC A->D with too much overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = 200000000.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]

    // A send payment of 6 mBTC
    val sendReq = SendPaymentToNode(sender.ref, 600000000 msat, invoice, Nil, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)

    // A will first receive an IncorrectPaymentAmount error from D
    val paymentId = sender.expectMsgType[UUID]
    val failed = sender.expectMsgType[PaymentFailed]
    assert(paymentId == failed.id)
    assert(failed.paymentHash == invoice.paymentHash)
    assert(failed.failures.size == 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e == DecryptedFailurePacket(nodes("D").nodeParams.nodeId, IncorrectOrUnknownPaymentDetails(600000000 msat, getBlockHeight())))
  }

  test("send an HTLC A->D with a reasonable overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = 200000000.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]

    // A send payment of 3 mBTC, more than asked but it should still be accepted
    val sendReq = SendPaymentToNode(sender.ref, 300000000 msat, invoice, Nil, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)
    sender.expectMsgType[UUID]
  }

  test("send multiple HTLCs A->D with a failover when a channel gets exhausted") {
    val sender = TestProbe()
    // there are two C-D channels with 5000000 sat, so we should be able to make 7 payments worth 1000000 sat each
    for (_ <- 0 until 7) {
      val amountMsat = 1000000000.msat
      sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amountMsat), Left("1 payment")))
      val invoice = sender.expectMsgType[Bolt11Invoice]

      val sendReq = SendPaymentToNode(sender.ref, amountMsat, invoice, Nil, routeParams = integrationTestRouteParams, maxAttempts = 5)
      sender.send(nodes("A").paymentInitiator, sendReq)
      sender.expectMsgType[UUID]
      sender.expectMsgType[PaymentSent] // the payment FSM will also reply to the sender after the payment is completed
    }
  }

  test("send an HTLC A->B->G->C using heuristics to select the route") {
    val (sender, holdTimesRecorder) = (TestProbe(), TestProbe())
    nodes("A").system.eventStream.subscribe(holdTimesRecorder.ref, classOf[Router.ReportedHoldTimes])
    // first we retrieve a payment hash from C
    val amountMsat = 2000.msat
    sender.send(nodes("C").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amountMsat), Left("Change from coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]

    // the payment is requesting to use a capacity-optimized route which will select node G even though it's a bit more expensive
    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(sender.ref, amountMsat, invoice, Nil, maxAttempts = 1, routeParams = integrationTestRouteParams.copy(heuristics = PaymentWeightRatios(0, 0, 0, 1, RelayFees(0 msat, 0)))))
    sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    ps.parts.foreach(part => assert(part.route.getOrElse(Nil).exists(_.nodeId == nodes("G").nodeParams.nodeId)))

    assert(holdTimesRecorder.expectMsgType[Router.ReportedHoldTimes].holdTimes.map(_.remoteNodeId) == Seq("B", "G", "C").map(nodes(_).nodeParams.nodeId))
  }

  test("send a multi-part payment B->D") {
    val start = TimestampMilli.now()
    val sender = TestProbe()
    val amount = 1000000000L.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amount), Left("split the restaurant bill")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    sender.send(nodes("B").paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, Nil, maxAttempts = 5, routeParams = integrationTestRouteParams))
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id == paymentId, paymentSent)
    assert(paymentSent.paymentHash == invoice.paymentHash, paymentSent)
    assert(paymentSent.parts.length > 1, paymentSent)
    assert(paymentSent.recipientNodeId == nodes("D").nodeParams.nodeId, paymentSent)
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.feesPaid > 0.msat, paymentSent)
    assert(paymentSent.parts.forall(p => p.id != paymentSent.id), paymentSent)
    assert(paymentSent.parts.forall(p => p.route.isDefined), paymentSent)

    val paymentParts = nodes("B").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    assert(paymentParts.length == paymentSent.parts.length, paymentParts)
    assert(paymentParts.map(_.amount).sum == amount, paymentParts)
    assert(paymentParts.forall(p => p.parentId == paymentId), paymentParts)
    assert(paymentParts.forall(p => p.parentId != p.id), paymentParts)
    assert(paymentParts.forall(p => p.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].feesPaid > 0.msat), paymentParts)

    awaitCond(nodes("B").nodeParams.db.audit.listSent(start, TimestampMilli.now()).nonEmpty)
    val sent = nodes("B").nodeParams.db.audit.listSent(start, TimestampMilli.now())
    assert(sent.length == 1, sent)
    assert(sent.head.copy(parts = sent.head.parts.sortBy(_.timestamp)) == paymentSent.copy(parts = paymentSent.parts.map(_.copy(route = None)).sortBy(_.timestamp), remainingAttribution_opt = None), sent)

    awaitCond(nodes("D").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingStandardPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("D").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(receivedAmount == amount)
  }

  // NB: this test may take 20 seconds to complete (multi-part payment timeout).
  test("send a multi-part payment B->D greater than balance C->D (temporary remote failure)") {
    val sender = TestProbe()
    // There is enough channel capacity to route this payment, but D doesn't have enough incoming capacity to receive it
    // (the link C->D has too much capacity on D's side).
    val amount = 2000000000L.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amount), Left("well that's an expensive restaurant bill")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    val canSend = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    assert(canSend > amount)

    sender.send(nodes("B").paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, Nil, maxAttempts = 1, routeParams = integrationTestRouteParams))
    val paymentId = sender.expectMsgType[UUID]
    val paymentFailed = sender.expectMsgType[PaymentFailed](max = 30 seconds)
    assert(paymentFailed.id == paymentId, paymentFailed)
    assert(paymentFailed.paymentHash == invoice.paymentHash, paymentFailed)
    assert(paymentFailed.failures.length > 1, paymentFailed)

    assert(nodes("D").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)

    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    val canSend2 = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    // Fee updates may impact balances, but it shouldn't have changed much.
    assert(math.abs((canSend - canSend2).toLong) < 50000000)
  }

  test("send a multi-part payment D->C (local channels only)") {
    val sender = TestProbe()
    // This amount is greater than any channel capacity between D and C, so it should be split.
    val amount = 5100000000L.msat
    sender.send(nodes("C").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amount), Left("lemme borrow some money")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    sender.send(nodes("D").paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, Nil, maxAttempts = 3, routeParams = integrationTestRouteParams))
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id == paymentId, paymentSent)
    assert(paymentSent.paymentHash == invoice.paymentHash, paymentSent)
    assert(paymentSent.parts.length > 1, paymentSent)
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.feesPaid == 0.msat, paymentSent) // no fees when using direct channels

    val paymentParts = nodes("D").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    assert(paymentParts.map(_.amount).sum == amount, paymentParts)
    assert(paymentParts.forall(p => p.parentId == paymentId), paymentParts)
    assert(paymentParts.forall(p => p.parentId != p.id), paymentParts)
    assert(paymentParts.forall(p => p.status.asInstanceOf[OutgoingPaymentStatus.Succeeded].feesPaid == 0.msat), paymentParts)

    awaitCond(nodes("C").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingStandardPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("C").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(receivedAmount == amount)
  }

  test("send a multi-part payment D->C greater than balance D->C (temporary local failure)") {
    val sender = TestProbe()
    // This amount is greater than the current capacity between D and C.
    val amount = 10000000000L.msat
    sender.send(nodes("C").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amount), Left("lemme borrow more money")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    sender.send(nodes("D").relayer, Relayer.GetOutgoingChannels())
    val canSend = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    assert(canSend < amount)

    sender.send(nodes("D").paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, Nil, maxAttempts = 1, routeParams = integrationTestRouteParams))
    val paymentId = sender.expectMsgType[UUID]
    val paymentFailed = sender.expectMsgType[PaymentFailed](max = 30 seconds)
    assert(paymentFailed.id == paymentId, paymentFailed)
    assert(paymentFailed.paymentHash == invoice.paymentHash, paymentFailed)

    val incoming = nodes("C").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(incoming.get.status == IncomingPaymentStatus.Pending, incoming)

    sender.send(nodes("D").relayer, Relayer.GetOutgoingChannels())
    val canSend2 = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    // Fee updates may impact balances, but it shouldn't have changed much.
    assert(math.abs((canSend - canSend2).toLong) < 50000000)
  }

  test("send a trampoline payment B->F1 (via trampoline G)") {
    val start = TimestampMilli.now()
    val (sender, holdTimesRecorderB, holdTimesRecorderG) = (TestProbe(), TestProbe(), TestProbe())
    nodes("B").system.eventStream.subscribe(holdTimesRecorderB.ref, classOf[Router.ReportedHoldTimes])
    nodes("G").system.eventStream.subscribe(holdTimesRecorderG.ref, classOf[Router.ReportedHoldTimes])
    val amount = 4_000_000_000L.msat
    sender.send(nodes("F").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amount), Left("like trampoline much?")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(invoice.features.hasFeature(Features.TrampolinePaymentPrototype))

    // The best route from G is G -> C -> F.
    val payment = SendTrampolinePayment(sender.ref, invoice, nodes("G").nodeParams.nodeId, routeParams = integrationTestRouteParams)
    sender.send(nodes("B").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id == paymentId, paymentSent)
    assert(paymentSent.paymentHash == invoice.paymentHash, paymentSent)
    assert(paymentSent.recipientNodeId == nodes("F").nodeParams.nodeId, paymentSent)
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.feesPaid == amount * 0.002) // 0.2%

    awaitCond(nodes("F").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingStandardPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("F").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(receivedAmount == amount)

    awaitCond({
      val relayed = nodes("G").nodeParams.db.audit.listRelayed(start, TimestampMilli.now()).filter(_.paymentHash == invoice.paymentHash)
      relayed.nonEmpty && relayed.head.amountOut >= amount
    })
    val relayed = nodes("G").nodeParams.db.audit.listRelayed(start, TimestampMilli.now()).filter(_.paymentHash == invoice.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < paymentSent.feesPaid, relayed)

    assert(holdTimesRecorderG.expectMsgType[Router.ReportedHoldTimes].holdTimes.map(_.remoteNodeId) == Seq("C", "F").map(nodes(_).nodeParams.nodeId))
    assert(holdTimesRecorderB.expectMsgType[Router.ReportedHoldTimes].holdTimes.map(_.remoteNodeId) == Seq("G").map(nodes(_).nodeParams.nodeId))
  }

  test("send a trampoline payment D->B (via trampoline C)") {
    val start = TimestampMilli.now()
    val (sender, eventListener) = (TestProbe(), TestProbe())
    nodes("B").system.eventStream.subscribe(eventListener.ref, classOf[PaymentMetadataReceived])
    val amount = 2_500_000_000L.msat
    sender.send(nodes("B").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amount), Left("trampoline-MPP is so #reckless")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(invoice.features.hasFeature(Features.TrampolinePaymentPrototype))
    assert(invoice.paymentMetadata.nonEmpty)

    // The direct route C -> B does not have enough capacity, the payment will be split between C -> B and C -> G -> B
    val payment = SendTrampolinePayment(sender.ref, invoice, nodes("C").nodeParams.nodeId, routeParams = integrationTestRouteParams)
    sender.send(nodes("D").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id == paymentId, paymentSent)
    assert(paymentSent.paymentHash == invoice.paymentHash, paymentSent)
    assert(paymentSent.recipientAmount == amount, paymentSent)

    awaitCond(nodes("B").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingStandardPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("B").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(receivedAmount == amount)
    eventListener.expectMsg(PaymentMetadataReceived(invoice.paymentHash, invoice.paymentMetadata.get))

    awaitCond({
      val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, TimestampMilli.now()).filter(_.paymentHash == invoice.paymentHash)
      relayed.nonEmpty && relayed.head.amountOut >= amount
    })
    val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, TimestampMilli.now()).filter(_.paymentHash == invoice.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < paymentSent.feesPaid, relayed)
  }

  test("send a trampoline payment F1->A (via trampoline C, non-trampoline recipient)") {
    // The A -> B channel is not announced.
    val start = TimestampMilli.now()
    val (sender, eventListener) = (TestProbe(), TestProbe())
    nodes("A").system.eventStream.subscribe(eventListener.ref, classOf[PaymentMetadataReceived])

    sender.send(nodes("A").router, Router.GetRouterData)
    val routingHints = List(sender.expectMsgType[Router.Data].privateChannels.head._2.toIncomingExtraHop.toList)

    val amount = 3_000_000_000L.msat
    sender.send(nodes("A").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amount), Left("trampoline to non-trampoline is so #vintage"), extraHops = routingHints))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(!invoice.features.hasFeature(Features.TrampolinePaymentPrototype))
    assert(invoice.paymentMetadata.nonEmpty)

    val payment = SendTrampolinePayment(sender.ref, invoice, nodes("C").nodeParams.nodeId, routeParams = integrationTestRouteParams)
    sender.send(nodes("F").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id == paymentId, paymentSent)
    assert(paymentSent.paymentHash == invoice.paymentHash, paymentSent)
    assert(paymentSent.recipientAmount == amount, paymentSent)

    awaitCond(nodes("A").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingStandardPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("A").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(receivedAmount == amount)
    eventListener.expectMsg(PaymentMetadataReceived(invoice.paymentHash, invoice.paymentMetadata.get))

    awaitCond({
      val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, TimestampMilli.now()).filter(_.paymentHash == invoice.paymentHash)
      relayed.nonEmpty && relayed.head.amountOut >= amount
    })
    val relayed = nodes("C").nodeParams.db.audit.listRelayed(start, TimestampMilli.now()).filter(_.paymentHash == invoice.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < paymentSent.feesPaid, relayed)
  }

  test("send a trampoline payment B->D (temporary local failure at trampoline)") {
    val (sender, holdTimesRecorder) = (TestProbe(), TestProbe())

    // We put most of the capacity C <-> D on D's side.
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(sender.ref, Some(8_000_000_000L msat), Left("plz send everything")))
    val pr1 = sender.expectMsgType[Bolt11Invoice]
    sender.send(nodes("C").paymentInitiator, SendPaymentToNode(sender.ref, 8_000_000_000L msat, pr1, Nil, maxAttempts = 3, routeParams = integrationTestRouteParams))
    sender.expectMsgType[UUID]
    sender.expectMsgType[PaymentSent](max = 30 seconds)

    // Now we try to send more than C's outgoing capacity to D.
    val amount = 1_800_000_000L.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amount), Left("I iz Satoshi")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(invoice.features.hasFeature(Features.TrampolinePaymentPrototype))

    nodes("B").system.eventStream.subscribe(holdTimesRecorder.ref, classOf[Router.ReportedHoldTimes])
    val payment = SendTrampolinePayment(sender.ref, invoice, nodes("C").nodeParams.nodeId, routeParams = integrationTestRouteParams)
    sender.send(nodes("B").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentFailed = sender.expectMsgType[PaymentFailed](max = 30 seconds)
    assert(paymentFailed.id == paymentId, paymentFailed)
    assert(paymentFailed.paymentHash == invoice.paymentHash, paymentFailed)

    assert(holdTimesRecorder.expectMsgType[Router.ReportedHoldTimes].holdTimes.map(_.remoteNodeId) == Seq("C").map(nodes(_).nodeParams.nodeId))
  }

  test("send a trampoline payment A->D (temporary remote failure at trampoline)") {
    val (sender, holdTimesRecorderA, holdTimesRecorderB) = (TestProbe(), TestProbe(), TestProbe())
    nodes("A").system.eventStream.subscribe(holdTimesRecorderA.ref, classOf[Router.ReportedHoldTimes])
    nodes("B").system.eventStream.subscribe(holdTimesRecorderB.ref, classOf[Router.ReportedHoldTimes])

    val amount = 1_800_000_000L.msat // B can forward to C, but C doesn't have that much outgoing capacity to D
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(sender.ref, Some(amount), Left("I iz not Satoshi")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(invoice.features.hasFeature(Features.TrampolinePaymentPrototype))

    val payment = SendTrampolinePayment(sender.ref, invoice, nodes("B").nodeParams.nodeId, routeParams = integrationTestRouteParams)
    sender.send(nodes("A").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentFailed = sender.expectMsgType[PaymentFailed](max = 30 seconds)
    assert(paymentFailed.id == paymentId, paymentFailed)
    assert(paymentFailed.paymentHash == invoice.paymentHash, paymentFailed)

    assert(holdTimesRecorderB.expectMsgType[Router.ReportedHoldTimes].holdTimes.map(_.remoteNodeId) == Seq("C").map(nodes(_).nodeParams.nodeId))
    assert(holdTimesRecorderA.expectMsgType[Router.ReportedHoldTimes].holdTimes.map(_.remoteNodeId) == Seq("B").map(nodes(_).nodeParams.nodeId))
  }

  test("send a blinded payment B->D with many blinded routes") {
    val recipientKey = randomKey()
    val amount = 50_000_000 msat
    val chain = nodes("D").nodeParams.chainHash
    val pathId = randomBytes32()
    val offerPaths = Seq(
      buildRoute(randomKey(), Seq(IntermediateNode(nodes("G").nodeParams.nodeId), IntermediateNode(nodes("C").nodeParams.nodeId)), Recipient(nodes("D").nodeParams.nodeId, Some(pathId))).route,
      buildRoute(randomKey(), Seq(IntermediateNode(nodes("B").nodeParams.nodeId), IntermediateNode(nodes("C").nodeParams.nodeId)), Recipient(nodes("D").nodeParams.nodeId, Some(pathId))).route,
      buildRoute(randomKey(), Seq(IntermediateNode(nodes("E").nodeParams.nodeId), IntermediateNode(nodes("C").nodeParams.nodeId)), Recipient(nodes("D").nodeParams.nodeId, Some(pathId))).route
    )
    val offer = Offer(Some(amount), Some("test offer"), recipientKey.publicKey, nodes("D").nodeParams.features.bolt12Features(), chain, additionalTlvs = Set(OfferPaths(offerPaths)))
    val offerHandler = TypedProbe[HandlerCommand]()(nodes("D").system.toTyped)
    nodes("D").offerManager ! RegisterOffer(offer, Some(recipientKey), Some(pathId), offerHandler.ref)

    val sender = TestProbe()
    val bob = new EclairImpl(nodes("B"))
    bob.payOfferBlocking(offer, amount, 1, maxAttempts_opt = Some(3))(30 seconds).pipeTo(sender.ref)

    nodes("D").router ! Router.FinalizeRoute(sender.ref, Router.PredefinedNodeRoute(amount, Seq(nodes("G").nodeParams.nodeId, nodes("C").nodeParams.nodeId, nodes("D").nodeParams.nodeId)))
    val route1 = sender.expectMsgType[Router.RouteResponse].routes.head
    nodes("D").router ! Router.FinalizeRoute(sender.ref, Router.PredefinedNodeRoute(amount, Seq(nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId, nodes("D").nodeParams.nodeId)))
    val route2 = sender.expectMsgType[Router.RouteResponse].routes.head
    nodes("D").router ! Router.FinalizeRoute(sender.ref, Router.PredefinedNodeRoute(amount, Seq(nodes("E").nodeParams.nodeId, nodes("C").nodeParams.nodeId, nodes("D").nodeParams.nodeId)))
    val route3 = sender.expectMsgType[Router.RouteResponse].routes.head

    val handleInvoiceRequest = offerHandler.expectMessageType[HandleInvoiceRequest]
    val receivingRoutes = Seq(
      OfferManager.InvoiceRequestActor.Route(route1.hops, CltvExpiryDelta(1000)),
      OfferManager.InvoiceRequestActor.Route(route2.hops, CltvExpiryDelta(1000)),
      OfferManager.InvoiceRequestActor.Route(route3.hops, CltvExpiryDelta(1000)),
    )
    handleInvoiceRequest.replyTo ! InvoiceRequestActor.ApproveRequest(amount, receivingRoutes, pluginData_opt = Some(hex"abcd"))

    val handlePayment = offerHandler.expectMessageType[HandlePayment]
    assert(handlePayment.offer == offer)
    assert(handlePayment.invoiceData.pluginData_opt.contains(hex"abcd"))
    handlePayment.replyTo ! PaymentActor.AcceptPayment()

    val paymentSent = sender.expectMsgType[PaymentSent]
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.feesPaid > 0.msat, paymentSent)

    awaitCond(nodes("D").nodeParams.db.payments.getIncomingPayment(paymentSent.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingBlindedPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("D").nodeParams.db.payments.getIncomingPayment(paymentSent.paymentHash)
    assert(receivedAmount >= amount)
  }

  test("send a blinded payment D->C with empty blinded routes") {
    val amount = 25_000_000 msat
    val chain = nodes("C").nodeParams.chainHash
    val offer = Offer(Some(amount), Some("test offer"), nodes("C").nodeParams.nodeId, nodes("C").nodeParams.features.bolt12Features(), chain)
    val offerHandler = TypedProbe[HandlerCommand]()(nodes("C").system.toTyped)
    nodes("C").offerManager ! RegisterOffer(offer, Some(nodes("C").nodeParams.privateKey), None, offerHandler.ref)

    val sender = TestProbe()
    val dave = new EclairImpl(nodes("D"))
    dave.payOfferBlocking(offer, amount, 1, maxAttempts_opt = Some(3))(30 seconds).pipeTo(sender.ref)

    val handleInvoiceRequest = offerHandler.expectMessageType[HandleInvoiceRequest]
    // C uses a 0-hop blinded route and signs the invoice with its public nodeId.
    val receivingRoutes = Seq(
      OfferManager.InvoiceRequestActor.Route(Nil, CltvExpiryDelta(1000)),
      OfferManager.InvoiceRequestActor.Route(Nil, CltvExpiryDelta(1000)),
    )
    handleInvoiceRequest.replyTo ! InvoiceRequestActor.ApproveRequest(amount, receivingRoutes, pluginData_opt = Some(hex"0123"))

    val handlePayment = offerHandler.expectMessageType[HandlePayment]
    assert(handlePayment.offer == offer)
    assert(handlePayment.invoiceData.pluginData_opt.contains(hex"0123"))
    handlePayment.replyTo ! PaymentActor.AcceptPayment()

    val paymentSent = sender.expectMsgType[PaymentSent]
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.feesPaid == 0.msat, paymentSent)

    awaitCond(nodes("C").nodeParams.db.payments.getIncomingPayment(paymentSent.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingBlindedPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("C").nodeParams.db.payments.getIncomingPayment(paymentSent.paymentHash)
    assert(receivedAmount == amount)
  }

  test("send a blinded payment B->A with dummy hops") {
    val recipientKey = randomKey()
    val amount = 50_000_000 msat
    val chain = nodes("A").nodeParams.chainHash
    val pathId = randomBytes32()
    val offerPath = buildRoute(randomKey(), Seq(IntermediateNode(nodes("A").nodeParams.nodeId), IntermediateNode(nodes("A").nodeParams.nodeId)), Recipient(nodes("A").nodeParams.nodeId, Some(pathId))).route
    val offer = Offer(Some(amount), Some("test offer"), recipientKey.publicKey, nodes("A").nodeParams.features.bolt12Features(), chain, additionalTlvs = Set(OfferPaths(Seq(offerPath))))
    val offerHandler = TypedProbe[HandlerCommand]()(nodes("A").system.toTyped)
    nodes("A").offerManager ! RegisterOffer(offer, Some(recipientKey), Some(pathId), offerHandler.ref)

    val sender = TestProbe()
    val bob = new EclairImpl(nodes("B"))
    bob.payOfferBlocking(offer, amount, 1, maxAttempts_opt = Some(3))(30 seconds).pipeTo(sender.ref)

    val handleInvoiceRequest = offerHandler.expectMessageType[HandleInvoiceRequest]
    val receivingRoutes = Seq(
      OfferManager.InvoiceRequestActor.Route(Seq(ChannelHop.dummy(nodes("A").nodeParams.nodeId, 100 msat, 100, CltvExpiryDelta(48)), ChannelHop.dummy(nodes("A").nodeParams.nodeId, 150 msat, 50, CltvExpiryDelta(36))), CltvExpiryDelta(1000))
    )
    handleInvoiceRequest.replyTo ! InvoiceRequestActor.ApproveRequest(amount, receivingRoutes)

    val handlePayment = offerHandler.expectMessageType[HandlePayment]
    assert(handlePayment.offer == offer)
    assert(handlePayment.invoiceData.pluginData_opt.isEmpty)
    handlePayment.replyTo ! PaymentActor.AcceptPayment()

    val paymentSent = sender.expectMsgType[PaymentSent]
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.feesPaid >= 0.msat, paymentSent)

    awaitCond(nodes("A").nodeParams.db.payments.getIncomingPayment(paymentSent.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingBlindedPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("A").nodeParams.db.payments.getIncomingPayment(paymentSent.paymentHash)
    assert(receivedAmount >= amount)
  }

  test("send a blinded payment B->C with sender as introduction point of blinded route") {
    val recipientKey = randomKey()
    val amount = 10_000_000 msat
    val chain = nodes("C").nodeParams.chainHash
    val pathId = randomBytes32()
    val offerPath = buildRoute(randomKey(), Seq(IntermediateNode(nodes("B").nodeParams.nodeId), IntermediateNode(nodes("C").nodeParams.nodeId)), Recipient(nodes("C").nodeParams.nodeId, Some(pathId))).route
    val offer = Offer(Some(amount), Some("tricky test offer"), recipientKey.publicKey, nodes("C").nodeParams.features.bolt12Features(), chain, additionalTlvs = Set(OfferPaths(Seq(offerPath))))
    val offerHandler = TypedProbe[HandlerCommand]()(nodes("C").system.toTyped)
    nodes("C").offerManager ! RegisterOffer(offer, Some(recipientKey), Some(pathId), offerHandler.ref)

    val sender = TestProbe()
    val bob = new EclairImpl(nodes("B"))
    bob.payOfferBlocking(offer, amount, 1, maxAttempts_opt = Some(3))(30 seconds).pipeTo(sender.ref)

    nodes("C").router ! Router.FinalizeRoute(sender.ref, Router.PredefinedNodeRoute(amount, Seq(nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val handleInvoiceRequest = offerHandler.expectMessageType[HandleInvoiceRequest]
    val receivingRoutes = Seq(
      OfferManager.InvoiceRequestActor.Route(route.hops :+ ChannelHop.dummy(nodes("C").nodeParams.nodeId, 55 msat, 55, CltvExpiryDelta(55)), CltvExpiryDelta(555))
    )
    handleInvoiceRequest.replyTo ! InvoiceRequestActor.ApproveRequest(amount, receivingRoutes, pluginData_opt = Some(hex"eff0"))

    val handlePayment = offerHandler.expectMessageType[HandlePayment]
    assert(handlePayment.offer == offer)
    assert(handlePayment.invoiceData.pluginData_opt.contains(hex"eff0"))
    handlePayment.replyTo ! PaymentActor.AcceptPayment()

    val paymentSent = sender.expectMsgType[PaymentSent]
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.feesPaid >= 0.msat, paymentSent)

    awaitCond(nodes("C").nodeParams.db.payments.getIncomingPayment(paymentSent.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingBlindedPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("C").nodeParams.db.payments.getIncomingPayment(paymentSent.paymentHash)
    assert(receivedAmount >= amount)
  }

  test("send a blinded payment A->D with trampoline") {
    val amount = 10_000_000 msat
    val chain = nodes("D").nodeParams.chainHash
    val offer = Offer(Some(amount), Some("test offer"), nodes("D").nodeParams.nodeId, nodes("D").nodeParams.features.bolt12Features(), chain)
    val offerHandler = TypedProbe[HandlerCommand]()(nodes("D").system.toTyped)
    nodes("D").offerManager ! RegisterOffer(offer, Some(nodes("D").nodeParams.privateKey), None, offerHandler.ref)

    val (sender, holdTimesRecorderA, holdTimesRecorderB) = (TestProbe(), TestProbe(), TestProbe())
    nodes("A").system.eventStream.subscribe(holdTimesRecorderA.ref, classOf[Router.ReportedHoldTimes])
    nodes("B").system.eventStream.subscribe(holdTimesRecorderB.ref, classOf[Router.ReportedHoldTimes])
    val alice = new EclairImpl(nodes("A"))
    alice.payOfferTrampoline(offer, amount, 1, nodes("B").nodeParams.nodeId, maxAttempts_opt = Some(1))(30 seconds).pipeTo(sender.ref)

    nodes("D").router ! Router.FinalizeRoute(sender.ref, Router.PredefinedNodeRoute(amount, Seq(nodes("C").nodeParams.nodeId, nodes("D").nodeParams.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val handleInvoiceRequest = offerHandler.expectMessageType[HandleInvoiceRequest]
    val receivingRoutes = Seq(OfferManager.InvoiceRequestActor.Route(route.hops, CltvExpiryDelta(500)))
    handleInvoiceRequest.replyTo ! InvoiceRequestActor.ApproveRequest(amount, receivingRoutes, pluginData_opt = Some(hex"0123"))

    val handlePayment = offerHandler.expectMessageType[HandlePayment]
    assert(handlePayment.offer == offer)
    assert(handlePayment.invoiceData.pluginData_opt.contains(hex"0123"))
    handlePayment.replyTo ! PaymentActor.AcceptPayment()

    val paymentSent = sender.expectMsgType[PaymentSent]
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.feesPaid >= 0.msat, paymentSent)

    awaitCond(nodes("D").nodeParams.db.payments.getIncomingPayment(paymentSent.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingBlindedPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("D").nodeParams.db.payments.getIncomingPayment(paymentSent.paymentHash)
    assert(receivedAmount >= amount)

    assert(holdTimesRecorderB.expectMsgType[Router.ReportedHoldTimes].holdTimes.map(_.remoteNodeId) == Seq("C").map(nodes(_).nodeParams.nodeId))
    assert(holdTimesRecorderA.expectMsgType[Router.ReportedHoldTimes].holdTimes.map(_.remoteNodeId) == Seq("B").map(nodes(_).nodeParams.nodeId))
  }

  test("send to compact route") {
    val probe = TestProbe()
    val recipientKey = randomKey()
    val amount = 10_000_000 msat
    val chain = nodes("C").nodeParams.chainHash
    val pathId = randomBytes32()
    val scidDirEB = {
      probe.send(nodes("B").router, Router.GetChannels)
      val Some(channelBE) = probe.expectMsgType[Iterable[ChannelAnnouncement]].find(ann => Set(ann.nodeId1, ann.nodeId2) == Set(nodes("B").nodeParams.nodeId, nodes("E").nodeParams.nodeId))
      ShortChannelIdDir(channelBE.nodeId1 == nodes("B").nodeParams.nodeId, channelBE.shortChannelId)
    }
    val offerBlindedRoute = buildRoute(randomKey(), Seq(IntermediateNode(nodes("B").nodeParams.nodeId), IntermediateNode(nodes("C").nodeParams.nodeId)), Recipient(nodes("C").nodeParams.nodeId, Some(pathId))).route
    val offerPath = BlindedRoute(scidDirEB, offerBlindedRoute.firstPathKey, offerBlindedRoute.blindedHops)
    val offer = Offer(Some(amount), Some("test offer"), recipientKey.publicKey, nodes("C").nodeParams.features.bolt12Features(), chain, additionalTlvs = Set(OfferPaths(Seq(offerPath))))
    val offerHandler = TypedProbe[HandlerCommand]()(nodes("C").system.toTyped)
    nodes("C").offerManager ! RegisterOffer(offer, Some(recipientKey), Some(pathId), offerHandler.ref)

    val sender = TestProbe()
    val alice = new EclairImpl(nodes("A"))
    alice.payOfferBlocking(offer, amount, 1, maxAttempts_opt = Some(3))(30 seconds).pipeTo(sender.ref)

    nodes("C").router ! Router.FinalizeRoute(sender.ref, Router.PredefinedNodeRoute(amount, Seq(nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val handleInvoiceRequest = offerHandler.expectMessageType[HandleInvoiceRequest]
    val scidDirCB = {
      probe.send(nodes("B").router, Router.GetChannels)
      val Some(channelBC) = probe.expectMsgType[Iterable[ChannelAnnouncement]].find(ann => Set(ann.nodeId1, ann.nodeId2) == Set(nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId))
      ShortChannelIdDir(channelBC.nodeId1 == nodes("B").nodeParams.nodeId, channelBC.shortChannelId)
    }
    val receivingRoutes = Seq(
      OfferManager.InvoiceRequestActor.Route(route.hops :+ ChannelHop.dummy(nodes("C").nodeParams.nodeId, 55 msat, 55, CltvExpiryDelta(55)), CltvExpiryDelta(555), shortChannelIdDir_opt = Some(scidDirCB))
    )
    handleInvoiceRequest.replyTo ! InvoiceRequestActor.ApproveRequest(amount, receivingRoutes)

    val handlePayment = offerHandler.expectMessageType[HandlePayment]
    assert(handlePayment.offer == offer)
    handlePayment.replyTo ! PaymentActor.AcceptPayment()

    val paymentSent = sender.expectMsgType[PaymentSent]
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.feesPaid >= 0.msat, paymentSent)
    val Some(invoice: Bolt12Invoice) = nodes("A").nodeParams.db.payments.listOutgoingPaymentsToOffer(offer.offerId).head.invoice
    assert(invoice.blindedPaths.forall(_.route.firstNodeId.isInstanceOf[EncodedNodeId.ShortChannelIdDir]))

    awaitCond(nodes("C").nodeParams.db.payments.getIncomingPayment(paymentSent.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingBlindedPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("C").nodeParams.db.payments.getIncomingPayment(paymentSent.paymentHash)
    assert(receivedAmount >= amount)
  }

  test("generate and validate lots of channels") {
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    // we simulate fake channels by publishing a funding tx and sending announcement messages to a node at random
    logger.info(s"generating fake channels")
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(address) = sender.expectMsgType[JValue]
    val channels = for (i <- 0 until 242) yield {
      // let's generate a block every 10 txs so that we can compute short ids
      if (i % 10 == 0) {
        generateBlocks(1, Some(address))
      }
      AnnouncementsBatchValidationSpec.simulateChannel(bitcoinClient)
    }
    generateBlocks(1, Some(address))
    logger.info(s"simulated ${channels.size} channels")

    val remoteNodeId = PrivateKey(ByteVector32(ByteVector.fill(32)(1))).publicKey

    // then we make the announcements
    val announcements = channels.map(c => AnnouncementsBatchValidationSpec.makeChannelAnnouncement(c, bitcoinClient))
    announcements.foreach { ann =>
      nodes("A").router ! PeerRoutingMessage(sender.ref, remoteNodeId, ann)
      sender.expectMsg(TransportHandler.ReadAck(ann))
      sender.expectMsg(GossipDecision.Accepted(ann))
    }
    awaitCond({
      sender.send(nodes("D").router, Router.GetChannels)
      sender.expectMsgType[Iterable[ChannelAnnouncement]].size == channels.size + 8 // 8 original channels (A -> B is private)
    }, max = 120 seconds, interval = 1 second)
  }

  /** Handy way to check what the channel balances are before adding new tests. */
  def debugChannelBalances(): Unit = {
    val sender = TestProbe()
    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    sender.send(nodes("C").relayer, Relayer.GetOutgoingChannels())

    logger.info(s"A -> ${nodes("A").nodeParams.nodeId}")
    logger.info(s"B -> ${nodes("B").nodeParams.nodeId}")
    logger.info(s"C -> ${nodes("C").nodeParams.nodeId}")
    logger.info(s"D -> ${nodes("D").nodeParams.nodeId}")
    logger.info(s"E -> ${nodes("E").nodeParams.nodeId}")
    logger.info(s"F -> ${nodes("F").nodeParams.nodeId}")
    logger.info(s"G -> ${nodes("G").nodeParams.nodeId}")

    val channels1 = sender.expectMsgType[Relayer.OutgoingChannels]
    val channels2 = sender.expectMsgType[Relayer.OutgoingChannels]

    logger.info(channels1.channels.map(_.toChannelBalance))
    logger.info(channels2.channels.map(_.toChannelBalance))
  }

}
