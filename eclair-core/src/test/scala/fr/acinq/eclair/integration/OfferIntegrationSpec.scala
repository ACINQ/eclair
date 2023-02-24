/*
 * Copyright 2023 ACINQ SAS
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
import akka.testkit.TestProbe
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.{Crypto, SatoshiLong}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{Watch, WatchFundingConfirmed}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient, buildRoute}
import fr.acinq.eclair.payment.offer.OfferManager._
import fr.acinq.eclair.payment.receive.MultiPartHandler.{DummyBlindedHop, ReceivingRoute}
import fr.acinq.eclair.payment.{PaymentReceived, PaymentSent}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.{CltvExpiryDelta, EclairImpl, Features, Kit, MilliSatoshiLong, randomBytes32, randomKey}
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class OfferIntegrationSpec extends IntegrationSpec {
  implicit val timeout: Timeout = FiniteDuration(30, SECONDS)

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.channel.expiry-delta-blocks" -> 130, "eclair.server.port" -> 29730, "eclair.api.port" -> 28080, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional", "eclair.onion-messages.relay-policy" -> "channels-only").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.channel.expiry-delta-blocks" -> 131, "eclair.server.port" -> 29731, "eclair.api.port" -> 28081, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional", "eclair.onion-messages.relay-policy" -> "channels-only").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.channel.expiry-delta-blocks" -> 132, "eclair.server.port" -> 29732, "eclair.api.port" -> 28082, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional", "eclair.onion-messages.relay-policy" -> "channels-only").asJava).withFallback(withDefaultCommitment).withFallback(commonConfig))
  }

  test("connect nodes") {
    // A -- B -- C

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("A"), nodes("B"), 100000000 sat, 0 msat)
    connect(nodes("B"), nodes("C"), 100000000 sat, 0 msat)

    val numberOfChannels = 2
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
    generateBlocks(6)

    within(60 seconds) {
      var count = 0
      while (count < channelEndpointsCount) {
        if (eventListener.expectMsgType[ChannelStateChanged](60 seconds).currentState == NORMAL) count = count + 1
      }
    }
  }

  def awaitAnnouncements(subset: Map[String, Kit], nodes: Int, publicChannels: Int, publicUpdates: Int): Unit = {
    val sender = TestProbe()
    subset.foreach {
      case (node, setup) =>
        withClue(node) {
          awaitAssert({
            sender.send(setup.router, Router.GetRouterData)
            val data = sender.expectMsgType[Router.Data]
            assert(data.nodes.size == nodes)
            assert(data.channels.size == publicChannels)
            assert(data.channels.values.flatMap(pc => pc.update_1_opt.toSeq ++ pc.update_2_opt.toSeq).size == publicUpdates)
          }, max = 10 seconds, interval = 1 second)
        }
    }
  }

  test("wait for network announcements") {
    awaitAnnouncements(nodes.view.filterKeys(key => List("A", "B", "C").contains(key)).toMap, nodes = 3, publicChannels = 2, publicUpdates = 4)
  }

  test("simple offer") {
    val aliceEventListener = TestProbe()
    nodes("A").system.eventStream.subscribe(aliceEventListener.ref, classOf[PaymentSent])
    val carolEventListener = TestProbe()
    nodes("C").system.eventStream.subscribe(carolEventListener.ref, classOf[PaymentReceived])


    val offerHandler = TypedProbe[HandlerCommand]()(nodes("C").system.toTyped)
    val offer = Offer(Some(10_000_000 msat), "simple offer", nodes("C").nodeParams.nodeId, Features.empty, nodes("C").nodeParams.chainHash)
    nodes("C").offerManager ! RegisterOffer(offer, nodes("C").nodeParams.privateKey, None, offerHandler.ref)

    val alice = new EclairImpl(nodes("A"))
    alice.payOffer(offer, 10_000_000 msat, 1)

    val handleInvoiceRequest = offerHandler.expectMessageType[HandleInvoiceRequest]
    handleInvoiceRequest.replyTo ! InvoiceRequestActor.ApproveRequest(10_000_000 msat, Seq(ReceivingRoute(Seq(nodes("C").nodeParams.nodeId), CltvExpiryDelta(1000))), Features.empty, ByteVector.empty)

    val handlePayment = offerHandler.expectMessageType[HandlePayment]
    assert(handlePayment.offerId == offer.offerId)
    handlePayment.replyTo ! PaymentActor.AcceptPayment()

    val paymentReceived = carolEventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.amount == 10_000_000.msat)

    val paymentSent = aliceEventListener.expectMsgType[PaymentSent]
    assert(paymentSent.recipientAmount == 10_000_000.msat)
    assert(Crypto.sha256(paymentSent.paymentPreimage) == paymentReceived.paymentHash)
  }

  test("offer with tricky blinded route") {
    val aliceEventListener = TestProbe()
    nodes("A").system.eventStream.subscribe(aliceEventListener.ref, classOf[PaymentSent])
    val carolEventListener = TestProbe()
    nodes("C").system.eventStream.subscribe(carolEventListener.ref, classOf[PaymentReceived])


    val offerHandler = TypedProbe[HandlerCommand]()(nodes("C").system.toTyped)
    val key = randomKey()
    val pathId = randomBytes32()
    val offerPath = buildRoute(randomKey(), Seq(IntermediateNode(nodes("A").nodeParams.nodeId), IntermediateNode(nodes("B").nodeParams.nodeId), IntermediateNode(nodes("C").nodeParams.nodeId), IntermediateNode(nodes("C").nodeParams.nodeId), IntermediateNode(nodes("C").nodeParams.nodeId)), Recipient(nodes("C").nodeParams.nodeId, Some(pathId)))
    val offer = Offer(Some(10_000_000 msat), "simple offer", key.publicKey, Features.empty, nodes("C").nodeParams.chainHash, Set(OfferPaths(Seq(offerPath)), OfferQuantityMax(0)))
    nodes("C").offerManager ! RegisterOffer(offer, key, Some(pathId), offerHandler.ref)

    val alice = new EclairImpl(nodes("A"))
    alice.payOffer(offer, 1_230_000_000 msat, 123)

    val handleInvoiceRequest = offerHandler.expectMessageType[HandleInvoiceRequest]
    val paymentPath = ReceivingRoute(Seq(nodes("A").nodeParams.nodeId, nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId), CltvExpiryDelta(1000), Seq(DummyBlindedHop(1000 msat, 300, CltvExpiryDelta(33)), DummyBlindedHop(500 msat, 800, CltvExpiryDelta(44))))
    handleInvoiceRequest.replyTo ! InvoiceRequestActor.ApproveRequest(1_230_000_000 msat, Seq(paymentPath), Features.empty, ByteVector.empty)

    val handlePayment = offerHandler.expectMessageType[HandlePayment]
    assert(handlePayment.offerId == offer.offerId)
    handlePayment.replyTo ! PaymentActor.AcceptPayment()

    val paymentReceived = carolEventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.amount >= 1_230_000_000.msat)

    val paymentSent = aliceEventListener.expectMsgType[PaymentSent]
    assert(paymentSent.recipientAmount == 1_230_000_000.msat)
    assert(Crypto.sha256(paymentSent.paymentPreimage) == paymentReceived.paymentHash)
  }
}
