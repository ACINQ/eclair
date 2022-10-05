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
import fr.acinq.eclair.router.{CannotRouteToSelf, Router}
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}
import fr.acinq.eclair.{Kit, MilliSatoshiLong, randomKey}

import java.util.UUID
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class BlindPaymentIntegrationSpec extends IntegrationSpec {

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.channel.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29730, "eclair.api.port" -> 28080, "eclair.features.option_route_blinding" -> "optional", "eclair.channel.channel-flags.announce-channel" -> false).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig)) // A's channels are private
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.channel.expiry-delta-blocks" -> 131, "eclair.server.port" -> 29731, "eclair.api.port" -> 28081, "eclair.features.option_route_blinding" -> "optional").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.channel.expiry-delta-blocks" -> 132, "eclair.server.port" -> 29732, "eclair.api.port" -> 28082, "eclair.features.option_route_blinding" -> "optional").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.channel.expiry-delta-blocks" -> 133, "eclair.server.port" -> 29733, "eclair.api.port" -> 28083, "eclair.features.option_route_blinding" -> "optional").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.channel.expiry-delta-blocks" -> 134, "eclair.server.port" -> 29734, "eclair.api.port" -> 28084).asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("F", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F", "eclair.channel.expiry-delta-blocks" -> 135, "eclair.server.port" -> 29735, "eclair.api.port" -> 28085, "eclair.features.option_route_blinding" -> "optional").asJava).withFallback(commonConfig))
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

    val recipientKey = randomKey()
    val payerKey = randomKey()

    // first we retrieve an invoice from D
    val amount = 4200000 msat
    val chain = nodes("D").nodeParams.chainHash
    val offer = Offer(Some(amount), "test offer", recipientKey.publicKey, nodes("D").nodeParams.features.invoiceFeatures(), chain)
    val invoiceRequest = InvoiceRequest(offer, amount, 1, nodes("A").nodeParams.features.invoiceFeatures(), payerKey, chain)

    sender.send(nodes("D").paymentHandler, ReceiveOfferPayment(recipientKey, offer, invoiceRequest))
    val invoice = sender.expectMsgType[Bolt12Invoice]

    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentToNode(amount, invoice, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
    assert(Crypto.sha256(ps.paymentPreimage) == invoice.paymentHash)
  }

  // TODO: Add more tests with more cases of blinded routes and with MPP.

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
