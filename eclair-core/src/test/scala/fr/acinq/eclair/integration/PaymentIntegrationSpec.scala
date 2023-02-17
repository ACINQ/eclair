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

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, actorRefAdapter}
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, SatoshiLong}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{Watch, WatchFundingConfirmed}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel.{BroadcastChannelUpdate, PeriodicRefresh}
import fr.acinq.eclair.crypto.Sphinx.DecryptedFailurePacket
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartHandler.{DummyBlindedHop, ReceiveOfferPayment, ReceiveStandardPayment, ReceivingRoute}
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPaymentToNode, SendTrampolinePayment}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.router.Router.{GossipDecision, PublicChannel}
import fr.acinq.eclair.router.{Announcements, AnnouncementsBatchValidationSpec, Router}
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, IncorrectOrUnknownPaymentDetails}
import fr.acinq.eclair.{CltvExpiryDelta, Features, Kit, MilliSatoshiLong, ShortChannelId, TimestampMilli, randomBytes32, randomKey}
import org.json4s.JsonAST.{JString, JValue}
import scodec.bits.ByteVector

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Created by PM on 15/03/2017.
 */

class PaymentIntegrationSpec extends IntegrationSpec {

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.channel.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29730, "eclair.api.port" -> 28080, "eclair.channel.channel-flags.announce-channel" -> false).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig)) // A's channels are private
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.channel.expiry-delta-blocks" -> 131, "eclair.server.port" -> 29731, "eclair.api.port" -> 28081, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.channel.expiry-delta-blocks" -> 132, "eclair.server.port" -> 29732, "eclair.api.port" -> 28082, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(withDualFunding).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.channel.expiry-delta-blocks" -> 133, "eclair.server.port" -> 29733, "eclair.api.port" -> 28083, "eclair.trampoline-payments-enable" -> true).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
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
    generateBlocks(2)

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
    // generating more blocks so that all funding txes are buried under at least 6 blocks
    generateBlocks(4)
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
    val (sender, eventListener) = (TestProbe(), TestProbe())
    nodes("D").system.eventStream.subscribe(eventListener.ref, classOf[PaymentMetadataReceived])

    // first we retrieve a payment hash from D
    val amountMsat = 4200000.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.paymentMetadata.nonEmpty)

    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(sender.ref, amountMsat, invoice, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
    assert(Crypto.sha256(ps.paymentPreimage) == invoice.paymentHash)
    eventListener.expectMsg(PaymentMetadataReceived(invoice.paymentHash, invoice.paymentMetadata.get))
  }

  test("send an HTLC A->D with an invalid expiry delta for B") {
    val sender = TestProbe()
    // to simulate this, we will update B's relay params
    // first we find out the short channel id for channel B-C
    sender.send(nodes("B").router, Router.GetChannels)
    val shortIdBC = sender.expectMsgType[Iterable[ChannelAnnouncement]].find(c => Set(c.nodeId1, c.nodeId2) == Set(nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId)).get.shortChannelId
    // we also need the full commitment
    nodes("B").register ! Register.ForwardShortId(sender.ref.toTyped[Any], shortIdBC, CMD_GET_CHANNEL_INFO(ActorRef.noSender))
    val normalBC = sender.expectMsgType[RES_GET_CHANNEL_INFO].data.asInstanceOf[DATA_NORMAL]
    // we then forge a new channel_update for B-C...
    val channelUpdateBC = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, nodes("B").nodeParams.privateKey, nodes("C").nodeParams.nodeId, shortIdBC, nodes("B").nodeParams.channelConf.expiryDelta + 1, nodes("C").nodeParams.channelConf.htlcMinimum, nodes("B").nodeParams.relayParams.publicChannelFees.feeBase, nodes("B").nodeParams.relayParams.publicChannelFees.feeProportionalMillionths, 500000000 msat)
    // ...and notify B's relayer
    nodes("B").system.eventStream.publish(LocalChannelUpdate(system.deadLetters, normalBC.channelId, normalBC.shortIds, normalBC.commitments.remoteNodeId, normalBC.channelAnnouncement, channelUpdateBC, normalBC.commitments))
    // we retrieve a payment hash from D
    val amountMsat = 4200000.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    // then we make the actual payment, do not randomize the route to make sure we route through node B
    val sendReq = SendPaymentToNode(sender.ref, amountMsat, invoice, routeParams = integrationTestRouteParams, maxAttempts = 5)
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
    nodes("B").register ! Register.ForwardShortId(sender.ref.toTyped[Any], shortIdBC, CMD_GET_CHANNEL_INFO(ActorRef.noSender))
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
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    // then we make the payment (B-C has a smaller capacity than A-B and C-D)
    val sendReq = SendPaymentToNode(sender.ref, amountMsat, invoice, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will first receive an error from C, then retry and route around C: A->B->E->C->D
    sender.expectMsgType[UUID]
    sender.expectMsgType[PaymentSent] // the payment FSM will also reply to the sender after the payment is completed
  }

  test("send an HTLC A->D with an unknown payment hash") {
    val sender = TestProbe()
    val amount = 100000000 msat
    val unknownInvoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(amount), randomBytes32(), nodes("D").nodeParams.privateKey, Left("test"), finalCltvExpiryDelta)
    val invoice = SendPaymentToNode(sender.ref, amount, unknownInvoice, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, invoice)

    // A will receive an error from D and won't retry
    val paymentId = sender.expectMsgType[UUID]
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.id == paymentId)
    assert(failed.paymentHash == invoice.paymentHash)
    assert(failed.failures.size == 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e == DecryptedFailurePacket(nodes("D").nodeParams.nodeId, IncorrectOrUnknownPaymentDetails(amount, getBlockHeight())))
  }

  test("send an HTLC A->D with a lower amount than requested") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = 200000000.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]

    // A send payment of only 1 mBTC
    val sendReq = SendPaymentToNode(sender.ref, 100000000 msat, invoice, routeParams = integrationTestRouteParams, maxAttempts = 5)
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
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]

    // A send payment of 6 mBTC
    val sendReq = SendPaymentToNode(sender.ref, 600000000 msat, invoice, routeParams = integrationTestRouteParams, maxAttempts = 5)
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
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(amountMsat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]

    // A send payment of 3 mBTC, more than asked but it should still be accepted
    val sendReq = SendPaymentToNode(sender.ref, 300000000 msat, invoice, routeParams = integrationTestRouteParams, maxAttempts = 5)
    sender.send(nodes("A").paymentInitiator, sendReq)
    sender.expectMsgType[UUID]
  }

  test("send multiple HTLCs A->D with a failover when a channel gets exhausted") {
    val sender = TestProbe()
    // there are two C-D channels with 5000000 sat, so we should be able to make 7 payments worth 1000000 sat each
    for (_ <- 0 until 7) {
      val amountMsat = 1000000000.msat
      sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(amountMsat), Left("1 payment")))
      val invoice = sender.expectMsgType[Bolt11Invoice]

      val sendReq = SendPaymentToNode(sender.ref, amountMsat, invoice, routeParams = integrationTestRouteParams, maxAttempts = 5)
      sender.send(nodes("A").paymentInitiator, sendReq)
      sender.expectMsgType[UUID]
      sender.expectMsgType[PaymentSent] // the payment FSM will also reply to the sender after the payment is completed
    }
  }

  test("send an HTLC A->B->G->C using heuristics to select the route") {
    val sender = TestProbe()
    // first we retrieve a payment hash from C
    val amountMsat = 2000.msat
    sender.send(nodes("C").paymentHandler, ReceiveStandardPayment(Some(amountMsat), Left("Change from coffee")))
    val invoice = sender.expectMsgType[Bolt11Invoice]

    // the payment is requesting to use a capacity-optimized route which will select node G even though it's a bit more expensive
    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(sender.ref, amountMsat, invoice, maxAttempts = 1, routeParams = integrationTestRouteParams.copy(heuristics = Left(WeightRatios(0, 0, 0, 1, RelayFees(0 msat, 0))))))
    sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    ps.parts.foreach(part => assert(part.route.getOrElse(Nil).exists(_.nodeId == nodes("G").nodeParams.nodeId)))
  }

  test("send a multi-part payment B->D") {
    val start = TimestampMilli.now()
    val sender = TestProbe()
    val amount = 1000000000L.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(amount), Left("split the restaurant bill")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    sender.send(nodes("B").paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, maxAttempts = 5, routeParams = integrationTestRouteParams))
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
    assert(sent.head.copy(parts = sent.head.parts.sortBy(_.timestamp)) == paymentSent.copy(parts = paymentSent.parts.map(_.copy(route = None)).sortBy(_.timestamp)), sent)

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
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(amount), Left("well that's an expensive restaurant bill")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    val canSend = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    assert(canSend > amount)

    sender.send(nodes("B").paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, maxAttempts = 1, routeParams = integrationTestRouteParams))
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
    sender.send(nodes("C").paymentHandler, ReceiveStandardPayment(Some(amount), Left("lemme borrow some money")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    sender.send(nodes("D").paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, maxAttempts = 3, routeParams = integrationTestRouteParams))
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
    sender.send(nodes("C").paymentHandler, ReceiveStandardPayment(Some(amount), Left("lemme borrow more money")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    sender.send(nodes("D").relayer, Relayer.GetOutgoingChannels())
    val canSend = sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.commitments.availableBalanceForSend).sum
    assert(canSend < amount)

    sender.send(nodes("D").paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, maxAttempts = 1, routeParams = integrationTestRouteParams))
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

  test("send a trampoline payment B->F1 with retry (via trampoline G)") {
    val start = TimestampMilli.now()
    val sender = TestProbe()
    val amount = 4000000000L.msat
    sender.send(nodes("F").paymentHandler, ReceiveStandardPayment(Some(amount), Left("like trampoline much?")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(invoice.features.hasFeature(Features.TrampolinePaymentPrototype))

    // The best route from G is G -> C -> F which has a fee of 1210091 msat

    // The first attempt should fail, but the second one should succeed.
    val attempts = (1210000 msat, CltvExpiryDelta(42)) :: (1210100 msat, CltvExpiryDelta(288)) :: Nil
    val payment = SendTrampolinePayment(amount, invoice, nodes("G").nodeParams.nodeId, attempts, routeParams = integrationTestRouteParams)
    sender.send(nodes("B").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id == paymentId, paymentSent)
    assert(paymentSent.paymentHash == invoice.paymentHash, paymentSent)
    assert(paymentSent.recipientNodeId == nodes("F").nodeParams.nodeId, paymentSent)
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.trampolineFees == 1210100.msat, paymentSent)
    assert(paymentSent.nonTrampolineFees == 0.msat, paymentSent)
    assert(paymentSent.feesPaid == 1210100.msat, paymentSent)

    awaitCond(nodes("F").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingStandardPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("F").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(receivedAmount == amount)

    awaitCond({
      val relayed = nodes("G").nodeParams.db.audit.listRelayed(start, TimestampMilli.now()).filter(_.paymentHash == invoice.paymentHash)
      relayed.nonEmpty && relayed.head.amountOut >= amount
    })
    val relayed = nodes("G").nodeParams.db.audit.listRelayed(start, TimestampMilli.now()).filter(_.paymentHash == invoice.paymentHash).head
    assert(relayed.amountIn - relayed.amountOut > 0.msat, relayed)
    assert(relayed.amountIn - relayed.amountOut < 1210100.msat, relayed)

    val outgoingSuccess = nodes("B").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]).head
    assert(outgoingSuccess.recipientNodeId == nodes("F").nodeParams.nodeId, outgoingSuccess)
    assert(outgoingSuccess.recipientAmount == amount, outgoingSuccess)
    assert(outgoingSuccess.amount == amount + 1210100.msat, outgoingSuccess)
    val status = outgoingSuccess.status.asInstanceOf[OutgoingPaymentStatus.Succeeded]
    assert(status.route.lastOption.contains(HopSummary(nodes("G").nodeParams.nodeId, nodes("F").nodeParams.nodeId)), status)
  }

  test("send a trampoline payment D->B (via trampoline C)") {
    val start = TimestampMilli.now()
    val (sender, eventListener) = (TestProbe(), TestProbe())
    nodes("B").system.eventStream.subscribe(eventListener.ref, classOf[PaymentMetadataReceived])
    val amount = 2500000000L.msat
    sender.send(nodes("B").paymentHandler, ReceiveStandardPayment(Some(amount), Left("trampoline-MPP is so #reckless")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(invoice.features.hasFeature(Features.TrampolinePaymentPrototype))
    assert(invoice.paymentMetadata.nonEmpty)

    // The direct route C -> B does not have enough capacity, the payment will be split between
    // C -> B which would have a fee of 501000 if it could route the whole payment
    // C -> G -> B which would have a fee of 757061 if it was used to route the whole payment
    // The actual fee needed will be between these two values.
    val payment = SendTrampolinePayment(amount, invoice, nodes("C").nodeParams.nodeId, Seq((750000 msat, CltvExpiryDelta(288))), routeParams = integrationTestRouteParams)
    sender.send(nodes("D").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id == paymentId, paymentSent)
    assert(paymentSent.paymentHash == invoice.paymentHash, paymentSent)
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.trampolineFees == 750000.msat, paymentSent)
    assert(paymentSent.nonTrampolineFees == 0.msat, paymentSent)
    assert(paymentSent.feesPaid == 750000.msat, paymentSent)

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
    assert(relayed.amountIn - relayed.amountOut < 750000.msat, relayed)

    val outgoingSuccess = nodes("D").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]).head
    assert(outgoingSuccess.recipientNodeId == nodes("B").nodeParams.nodeId, outgoingSuccess)
    assert(outgoingSuccess.recipientAmount == amount, outgoingSuccess)
    assert(outgoingSuccess.amount == amount + 750000.msat, outgoingSuccess)
    val status = outgoingSuccess.status.asInstanceOf[OutgoingPaymentStatus.Succeeded]
    assert(status.route.lastOption.contains(HopSummary(nodes("C").nodeParams.nodeId, nodes("B").nodeParams.nodeId)), status)

    awaitCond(nodes("D").nodeParams.db.audit.listSent(start, TimestampMilli.now()).nonEmpty)
    val sent = nodes("D").nodeParams.db.audit.listSent(start, TimestampMilli.now())
    assert(sent.length == 1, sent)
    assert(sent.head.copy(parts = sent.head.parts.sortBy(_.timestamp)) == paymentSent.copy(parts = paymentSent.parts.map(_.copy(route = None)).sortBy(_.timestamp)), sent)
  }

  test("send a trampoline payment F1->A (via trampoline C, non-trampoline recipient)") {
    // The A -> B channel is not announced.
    val start = TimestampMilli.now()
    val (sender, eventListener) = (TestProbe(), TestProbe())
    nodes("A").system.eventStream.subscribe(eventListener.ref, classOf[PaymentMetadataReceived])

    sender.send(nodes("A").router, Router.GetRouterData)
    val routingHints = List(sender.expectMsgType[Router.Data].privateChannels.head._2.toIncomingExtraHop.toList)

    val amount = 3000000000L.msat
    sender.send(nodes("A").paymentHandler, ReceiveStandardPayment(Some(amount), Left("trampoline to non-trampoline is so #vintage"), extraHops = routingHints))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(!invoice.features.hasFeature(Features.TrampolinePaymentPrototype))
    assert(invoice.paymentMetadata.nonEmpty)

    val payment = SendTrampolinePayment(amount, invoice, nodes("C").nodeParams.nodeId, Seq((1500000 msat, CltvExpiryDelta(432))), routeParams = integrationTestRouteParams)
    sender.send(nodes("F").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id == paymentId, paymentSent)
    assert(paymentSent.paymentHash == invoice.paymentHash, paymentSent)
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.trampolineFees == 1500000.msat, paymentSent)
    assert(paymentSent.nonTrampolineFees == 0.msat, paymentSent)

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
    assert(relayed.amountIn - relayed.amountOut < 1500000.msat, relayed)

    val outgoingSuccess = nodes("F").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]).head
    assert(outgoingSuccess.recipientNodeId == nodes("A").nodeParams.nodeId, outgoingSuccess)
    assert(outgoingSuccess.recipientAmount == amount, outgoingSuccess)
    assert(outgoingSuccess.amount == amount + 1500000.msat, outgoingSuccess)
    val status = outgoingSuccess.status.asInstanceOf[OutgoingPaymentStatus.Succeeded]
    assert(status.route.lastOption.contains(HopSummary(nodes("C").nodeParams.nodeId, nodes("A").nodeParams.nodeId)), status)
  }

  test("send a trampoline payment B->D (temporary local failure at trampoline)") {
    val sender = TestProbe()

    // We put most of the capacity C <-> D on D's side.
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(8000000000L msat), Left("plz send everything")))
    val pr1 = sender.expectMsgType[Bolt11Invoice]
    sender.send(nodes("C").paymentInitiator, SendPaymentToNode(sender.ref, 8000000000L msat, pr1, maxAttempts = 3, routeParams = integrationTestRouteParams))
    sender.expectMsgType[UUID]
    sender.expectMsgType[PaymentSent](max = 30 seconds)

    // Now we try to send more than C's outgoing capacity to D.
    val amount = 2000000000L.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(amount), Left("I iz Satoshi")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(invoice.features.hasFeature(Features.TrampolinePaymentPrototype))

    val payment = SendTrampolinePayment(amount, invoice, nodes("C").nodeParams.nodeId, Seq((250000 msat, CltvExpiryDelta(288))), routeParams = integrationTestRouteParams)
    sender.send(nodes("B").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentFailed = sender.expectMsgType[PaymentFailed](max = 30 seconds)
    assert(paymentFailed.id == paymentId, paymentFailed)
    assert(paymentFailed.paymentHash == invoice.paymentHash, paymentFailed)

    assert(nodes("D").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)
    val outgoingPayments = nodes("B").nodeParams.db.payments.listOutgoingPayments(paymentId)
    assert(outgoingPayments.nonEmpty, outgoingPayments)
    assert(outgoingPayments.forall(p => p.status.isInstanceOf[OutgoingPaymentStatus.Failed]), outgoingPayments)
  }

  test("send a trampoline payment A->D (temporary remote failure at trampoline)") {
    val sender = TestProbe()
    val amount = 2000000000L.msat // B can forward to C, but C doesn't have that much outgoing capacity to D
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(amount), Left("I iz not Satoshi")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(invoice.features.hasFeature(Features.TrampolinePaymentPrototype))

    val payment = SendTrampolinePayment(amount, invoice, nodes("B").nodeParams.nodeId, Seq((450000 msat, CltvExpiryDelta(288))), routeParams = integrationTestRouteParams)
    sender.send(nodes("A").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentFailed = sender.expectMsgType[PaymentFailed](max = 30 seconds)
    assert(paymentFailed.id == paymentId, paymentFailed)
    assert(paymentFailed.paymentHash == invoice.paymentHash, paymentFailed)

    assert(nodes("D").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)
    val outgoingPayments = nodes("A").nodeParams.db.payments.listOutgoingPayments(paymentId)
    assert(outgoingPayments.nonEmpty, outgoingPayments)
    assert(outgoingPayments.forall(p => p.status.isInstanceOf[OutgoingPaymentStatus.Failed]), outgoingPayments)
  }

  test("send a trampoline payment A->D (via remote trampoline C)") {
    val sender = TestProbe()
    val amount = 500000000L.msat
    sender.send(nodes("D").paymentHandler, ReceiveStandardPayment(Some(amount), Left("remote trampoline is so #reckless")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(invoice.features.hasFeature(Features.TrampolinePaymentPrototype))

    val payment = SendTrampolinePayment(amount, invoice, nodes("C").nodeParams.nodeId, Seq((500000 msat, CltvExpiryDelta(288))), routeParams = integrationTestRouteParams)
    sender.send(nodes("A").paymentInitiator, payment)
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id == paymentId, paymentSent)
    assert(paymentSent.paymentHash == invoice.paymentHash, paymentSent)
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.trampolineFees == 500000.msat, paymentSent)
    assert(paymentSent.nonTrampolineFees > 0.msat, paymentSent)
    assert(paymentSent.feesPaid > 500000.msat, paymentSent)

    awaitCond(nodes("D").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingStandardPayment(_, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("D").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(receivedAmount == amount)

    val outgoingSuccess = nodes("A").nodeParams.db.payments.listOutgoingPayments(paymentId).filter(p => p.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]).head
    assert(outgoingSuccess.recipientNodeId == nodes("D").nodeParams.nodeId, outgoingSuccess)
    assert(outgoingSuccess.recipientAmount == amount, outgoingSuccess)
    assert(outgoingSuccess.amount == amount + 500000.msat, outgoingSuccess)
    val status = outgoingSuccess.status.asInstanceOf[OutgoingPaymentStatus.Succeeded]
    assert(status.route.lastOption.contains(HopSummary(nodes("C").nodeParams.nodeId, nodes("D").nodeParams.nodeId)), status)
  }

  test("send a blinded payment A->D with many blinded routes") {
    val sender = TestProbe()
    val recipientKey = randomKey()
    val amount = 50_000_000 msat
    val chain = nodes("D").nodeParams.chainHash
    val offer = Offer(Some(amount), "test offer", recipientKey.publicKey, nodes("D").nodeParams.features.bolt12Features(), chain)
    val invoiceRequest = InvoiceRequest(offer, amount, 1, nodes("A").nodeParams.features.bolt12Features(), randomKey(), chain)
    val receivingRoutes = Seq(
      ReceivingRoute(Seq(nodes("G").nodeParams.nodeId, nodes("C").nodeParams.nodeId, nodes("D").nodeParams.nodeId), CltvExpiryDelta(1000)),
      ReceivingRoute(Seq(nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId, nodes("D").nodeParams.nodeId), CltvExpiryDelta(1000)),
      ReceivingRoute(Seq(nodes("E").nodeParams.nodeId, nodes("C").nodeParams.nodeId, nodes("D").nodeParams.nodeId), CltvExpiryDelta(1000)),
    )
    sender.send(nodes("D").paymentHandler, ReceiveOfferPayment(recipientKey, invoiceRequest, receivingRoutes, nodes("D").router))
    val invoice = sender.expectMsgType[Bolt12Invoice]
    assert(invoice.blindedPaths.length == 3)
    assert(invoice.nodeId == recipientKey.publicKey)

    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, maxAttempts = 3, routeParams = integrationTestRouteParams))
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id == paymentId, paymentSent)
    assert(paymentSent.paymentHash == invoice.paymentHash, paymentSent)
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.feesPaid > 0.msat, paymentSent)

    awaitCond(nodes("D").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingBlindedPayment(_, _, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("D").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(receivedAmount >= amount)
  }

  test("send a blinded payment D->C with empty blinded routes") {
    val sender = TestProbe()
    val amount = 25_000_000 msat
    val chain = nodes("C").nodeParams.chainHash
    val offer = Offer(Some(amount), "test offer", nodes("C").nodeParams.nodeId, nodes("C").nodeParams.features.bolt12Features(), chain)
    val invoiceRequest = InvoiceRequest(offer, amount, 1, nodes("D").nodeParams.features.bolt12Features(), randomKey(), chain)
    // C uses a 0-hop blinded route and signs the invoice with its public nodeId.
    val receivingRoutes = Seq(
      ReceivingRoute(Seq(nodes("C").nodeParams.nodeId), CltvExpiryDelta(1000)),
      ReceivingRoute(Seq(nodes("C").nodeParams.nodeId), CltvExpiryDelta(1000)),
    )
    sender.send(nodes("C").paymentHandler, ReceiveOfferPayment(nodes("C").nodeParams.privateKey, invoiceRequest, receivingRoutes, nodes("C").router))
    val invoice = sender.expectMsgType[Bolt12Invoice]
    assert(invoice.blindedPaths.length == 2)
    assert(invoice.blindedPaths.forall(_.route.length == 0))
    assert(invoice.nodeId == nodes("C").nodeParams.nodeId)

    sender.send(nodes("D").paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, maxAttempts = 3, routeParams = integrationTestRouteParams))
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id == paymentId)
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.feesPaid == 0.msat, paymentSent)

    awaitCond(nodes("C").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingBlindedPayment(_, _, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("C").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(receivedAmount == amount)
  }

  test("send a blinded payment B->A with dummy hops") {
    val sender = TestProbe()
    val recipientKey = randomKey()
    val amount = 50_000_000 msat
    val chain = nodes("A").nodeParams.chainHash
    val offer = Offer(Some(amount), "test offer", recipientKey.publicKey, nodes("A").nodeParams.features.bolt12Features(), chain)
    val invoiceRequest = InvoiceRequest(offer, amount, 1, nodes("B").nodeParams.features.bolt12Features(), randomKey(), chain)
    val receivingRoutes = Seq(
      ReceivingRoute(Seq(nodes("A").nodeParams.nodeId), CltvExpiryDelta(1000), Seq(DummyBlindedHop(100 msat, 100, CltvExpiryDelta(48)), DummyBlindedHop(150 msat, 50, CltvExpiryDelta(36))))
    )
    sender.send(nodes("A").paymentHandler, ReceiveOfferPayment(recipientKey, invoiceRequest, receivingRoutes, nodes("A").router))
    val invoice = sender.expectMsgType[Bolt12Invoice]
    assert(invoice.blindedPaths.length == 1)
    assert(invoice.nodeId == recipientKey.publicKey)

    sender.send(nodes("B").paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, maxAttempts = 3, routeParams = integrationTestRouteParams))
    val paymentId = sender.expectMsgType[UUID]
    val paymentSent = sender.expectMsgType[PaymentSent](max = 30 seconds)
    assert(paymentSent.id == paymentId)
    assert(paymentSent.recipientAmount == amount, paymentSent)
    assert(paymentSent.feesPaid >= 0.msat, paymentSent)

    awaitCond(nodes("A").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))
    val Some(IncomingBlindedPayment(_, _, _, _, _, IncomingPaymentStatus.Received(receivedAmount, _))) = nodes("A").nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
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
