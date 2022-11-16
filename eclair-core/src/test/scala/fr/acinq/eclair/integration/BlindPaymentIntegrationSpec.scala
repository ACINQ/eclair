/*
 * Copyright 2022 ACINQ SAS
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

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.{Crypto, SatoshiLong}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{Watch, WatchFundingConfirmed}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceiveOfferPayment
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}
import fr.acinq.eclair.{Kit, MilliSatoshiLong, randomKey}

import java.util.UUID
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class BlindPaymentIntegrationSpec extends IntegrationSpec {

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.channel.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29730, "eclair.api.port" -> 28080, "eclair.features.option_route_blinding" -> "optional").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.channel.expiry-delta-blocks" -> 131, "eclair.server.port" -> 29731, "eclair.api.port" -> 28081, "eclair.features.option_route_blinding" -> "optional").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.channel.expiry-delta-blocks" -> 132, "eclair.server.port" -> 29732, "eclair.api.port" -> 28082, "eclair.features.option_route_blinding" -> "optional").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.channel.expiry-delta-blocks" -> 133, "eclair.server.port" -> 29733, "eclair.api.port" -> 28083, "eclair.features.option_route_blinding" -> "optional").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.channel.expiry-delta-blocks" -> 134, "eclair.server.port" -> 29734, "eclair.api.port" -> 28084, "eclair.features.option_route_blinding" -> "optional").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("F", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F", "eclair.channel.expiry-delta-blocks" -> 135, "eclair.server.port" -> 29735, "eclair.api.port" -> 28085, "eclair.features.option_route_blinding" -> "optional").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("G", ConfigFactory.parseMap(Map("eclair.node-alias" -> "G", "eclair.channel.expiry-delta-blocks" -> 136, "eclair.server.port" -> 29736, "eclair.api.port" -> 28086, "eclair.features.option_route_blinding" -> "disabled").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig)) // G does not support blinded routes.
  }

  test("connect nodes") {
    //       ,--G--,
    //      /       \
    // A---B ------- C ==== D
    //      \       / \
    //       '--E--'   \
    //           \_____ F
    //
    // All channels have fees 1 sat + 200 millionths

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("A"), nodes("B"), 11000000 sat, 0 msat)
    connect(nodes("B"), nodes("C"), 2000000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 5000000 sat, 0 msat)
    connect(nodes("C"), nodes("D"), 5000000 sat, 0 msat)
    connect(nodes("B"), nodes("E"), 10000000 sat, 0 msat)
    connect(nodes("E"), nodes("C"), 10000000 sat, 0 msat)
    connect(nodes("B"), nodes("G"), 16000000 sat, 0 msat)
    connect(nodes("G"), nodes("C"), 16000000 sat, 0 msat)
    connect(nodes("C"), nodes("F"), 2000000 sat, 0 msat)
    connect(nodes("E"), nodes("F"), 2000000 sat, 0 msat)

    val numberOfChannels = 10
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
    awaitAnnouncements(nodes.view.filterKeys(key => List("A", "B", "C", "D", "E", "G").contains(key)).toMap, nodes = 7, privateChannels = 0, publicChannels = 10, privateUpdates = 0, publicUpdates = 20)
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

  ignore("send an HTLC A->(D), minimal blinded route") {
    val (sender, eventListener) = (TestProbe(), TestProbe())
    nodes("D").system.eventStream.subscribe(eventListener.ref, classOf[PaymentMetadataReceived])

    val recipientKey = randomKey()
    val payerKey = randomKey()

    // first we retrieve an invoice from D
    val amount = 42000000 msat
    val chain = nodes("D").nodeParams.chainHash
    val offer = Offer(Some(amount), "test offer", recipientKey.publicKey, nodes("D").nodeParams.features.invoiceFeatures(), chain)
    val invoiceRequest = InvoiceRequest(offer, amount, 1, nodes("A").nodeParams.features.invoiceFeatures(), payerKey, chain)

    sender.send(nodes("D").paymentHandler, ReceiveOfferPayment(recipientKey, offer, invoiceRequest, Seq(Seq(nodes("D").nodeParams.nodeId)), nodes("D").router))
    val invoice = sender.expectMsgType[Bolt12Invoice]

    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(amount, invoice, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
    assert(Crypto.sha256(ps.paymentPreimage) == invoice.paymentHash)
  }

  ignore("send an HTLC A->(D->D->D), blinded route with dummy hops") {
    val (sender, eventListener) = (TestProbe(), TestProbe())
    nodes("D").system.eventStream.subscribe(eventListener.ref, classOf[PaymentMetadataReceived])

    val recipientKey = randomKey()
    val payerKey = randomKey()

    // first we retrieve an invoice from D
    val amount = 5600000 msat
    val chain = nodes("D").nodeParams.chainHash
    val offer = Offer(Some(amount), "test offer", recipientKey.publicKey, nodes("D").nodeParams.features.invoiceFeatures(), chain)
    val invoiceRequest = InvoiceRequest(offer, amount, 1, nodes("A").nodeParams.features.invoiceFeatures(), payerKey, chain)

    sender.send(nodes("D").paymentHandler, ReceiveOfferPayment(recipientKey, offer, invoiceRequest, Seq(Seq(nodes("D").nodeParams.nodeId, nodes("D").nodeParams.nodeId, nodes("D").nodeParams.nodeId)), nodes("D").router))
    val invoice = sender.expectMsgType[Bolt12Invoice]

    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(amount, invoice, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
    assert(Crypto.sha256(ps.paymentPreimage) == invoice.paymentHash)
  }

  test("send an HTLC A->(E->C->D), blinded route with intermediate hops") {
    val (sender, eventListener) = (TestProbe(), TestProbe())
    nodes("D").system.eventStream.subscribe(eventListener.ref, classOf[PaymentMetadataReceived])

    val recipientKey = randomKey()
    val payerKey = randomKey()

    // first we retrieve an invoice from D
    val amount = 7500000 msat
    val chain = nodes("D").nodeParams.chainHash
    val offer = Offer(Some(amount), "test offer", recipientKey.publicKey, nodes("D").nodeParams.features.invoiceFeatures(), chain)
    val invoiceRequest = InvoiceRequest(offer, amount, 1, nodes("A").nodeParams.features.invoiceFeatures(), payerKey, chain)

    sender.send(nodes("D").paymentHandler, ReceiveOfferPayment(recipientKey, offer, invoiceRequest, Seq(Seq(nodes("E").nodeParams.nodeId, nodes("C").nodeParams.nodeId, nodes("D").nodeParams.nodeId)), nodes("D").router))
    val invoice = sender.expectMsgType[Bolt12Invoice]

    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(amount, invoice, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
    assert(Crypto.sha256(ps.paymentPreimage) == invoice.paymentHash)
  }

  ignore("send an HTLC A->(G->C->D), blinded route with node not supporting blinded routes") {
    val (sender, eventListener) = (TestProbe(), TestProbe())
    nodes("D").system.eventStream.subscribe(eventListener.ref, classOf[PaymentMetadataReceived])

    val recipientKey = randomKey()
    val payerKey = randomKey()

    // first we retrieve an invoice from D
    val amount = 7500000 msat
    val chain = nodes("D").nodeParams.chainHash
    val offer = Offer(Some(amount), "test offer", recipientKey.publicKey, nodes("D").nodeParams.features.invoiceFeatures(), chain)
    val invoiceRequest = InvoiceRequest(offer, amount, 1, nodes("A").nodeParams.features.invoiceFeatures(), payerKey, chain)

    sender.send(nodes("D").paymentHandler, ReceiveOfferPayment(recipientKey, offer, invoiceRequest, Seq(Seq(nodes("G").nodeParams.nodeId, nodes("C").nodeParams.nodeId, nodes("D").nodeParams.nodeId)), nodes("D").router))
    val invoice = sender.expectMsgType[Bolt12Invoice]

    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(amount, invoice, routeParams = integrationTestRouteParams, maxAttempts = 1))
    sender.expectMsgType[UUID]
    sender.expectMsgType[PaymentFailed]
  }

  ignore("send an HTLC A->(A->B->C->D), blinded route with introduction node being the sender") {
    val (sender, eventListener) = (TestProbe(), TestProbe())
    nodes("D").system.eventStream.subscribe(eventListener.ref, classOf[PaymentMetadataReceived])

    val recipientKey = randomKey()
    val payerKey = randomKey()

    // first we retrieve an invoice from D
    val amount = 3200000 msat
    val chain = nodes("D").nodeParams.chainHash
    val offer = Offer(Some(amount), "test offer", recipientKey.publicKey, nodes("D").nodeParams.features.invoiceFeatures(), chain)
    val invoiceRequest = InvoiceRequest(offer, amount, 1, nodes("A").nodeParams.features.invoiceFeatures(), payerKey, chain)

    sender.send(nodes("D").paymentHandler, ReceiveOfferPayment(recipientKey, offer, invoiceRequest, Seq(Seq(nodes("A").nodeParams.nodeId, nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId, nodes("D").nodeParams.nodeId)), nodes("D").router))
    val invoice = sender.expectMsgType[Bolt12Invoice]

    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(amount, invoice, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
    assert(Crypto.sha256(ps.paymentPreimage) == invoice.paymentHash)
  }

  ignore("send to multiple blinded routes: "){
    val (sender, eventListener) = (TestProbe(), TestProbe())
    nodes("F").system.eventStream.subscribe(eventListener.ref, classOf[PaymentMetadataReceived])

    val recipientKey = randomKey()
    val payerKey = randomKey()

    // first we retrieve an invoice from D
    val amount = 3_000_000_000L msat
    val chain = nodes("F").nodeParams.chainHash
    val offer = Offer(Some(amount), "test offer", recipientKey.publicKey, nodes("F").nodeParams.features.invoiceFeatures(), chain)
    val invoiceRequest = InvoiceRequest(offer, amount, 1, nodes("A").nodeParams.features.invoiceFeatures(), payerKey, chain)

    sender.send(nodes("F").paymentHandler, ReceiveOfferPayment(recipientKey, offer, invoiceRequest, Seq(Seq(nodes("E").nodeParams.nodeId, nodes("F").nodeParams.nodeId), Seq(nodes("C").nodeParams.nodeId, nodes("F").nodeParams.nodeId)), nodes("F").router))
    val invoice = sender.expectMsgType[Bolt12Invoice]

    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(amount, invoice, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
    assert(Crypto.sha256(ps.paymentPreimage) == invoice.paymentHash)
  }

  // To show channel balances before adding new tests
  def debugChannelBalances(): Unit = {
    val names = nodes.map { case (name, kit) => (kit.nodeParams.nodeId, name) }
    val sender = TestProbe()
    sender.send(nodes("A").relayer, Relayer.GetOutgoingChannels())
    sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.toChannelBalance).foreach { balance =>
      println(s"A ${balance.canSend} ===== ${balance.canReceive} ${names(balance.remoteNodeId)}")
    }
    sender.send(nodes("B").relayer, Relayer.GetOutgoingChannels())
    sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.toChannelBalance).foreach { balance =>
      println(s"B ${balance.canSend} ===== ${balance.canReceive} ${names(balance.remoteNodeId)}")
    }
    sender.send(nodes("C").relayer, Relayer.GetOutgoingChannels())
    sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.toChannelBalance).foreach{ balance =>
      println(s"C ${balance.canSend} ===== ${balance.canReceive} ${names(balance.remoteNodeId)}")
    }
    sender.send(nodes("E").relayer, Relayer.GetOutgoingChannels())
    sender.expectMsgType[Relayer.OutgoingChannels].channels.map(_.toChannelBalance).foreach{ balance =>
      println(s"E ${balance.canSend} ===== ${balance.canReceive} ${names(balance.remoteNodeId)}")
    }
    names.foreach(println)
  }
}
