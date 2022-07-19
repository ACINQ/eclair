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

import akka.pattern.pipe
import akka.testkit.TestProbe
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{Watch, WatchFundingConfirmed}
import fr.acinq.eclair.channel.{ChannelStateChanged, NORMAL}
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus}
import fr.acinq.eclair.db.OutgoingPaymentStatus.Succeeded
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.payment.{Bolt12Invoice, PaymentEvent}
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.PreimageReceived
import fr.acinq.eclair.wire.protocol.MessageOnionCodecs.perHopPayloadCodec
import fr.acinq.eclair.wire.protocol.OfferTypes.Offer
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.Invoice
import fr.acinq.eclair.wire.protocol.TlvStream
import fr.acinq.eclair.{EclairImpl, Features, MilliSatoshiLong, PayOfferResponse, randomBytes32}
import scodec.bits.ByteVector

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class OffersIntegrationSpec extends IntegrationSpec {
  implicit val timeout: Timeout = FiniteDuration(30, SECONDS)

  test("setup eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.server.port" -> 30700, "eclair.api.port" -> 30780, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional", "eclair.onion-messages.relay-policy" -> "relay-all").asJava).withFallback(commonConfig))
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.server.port" -> 30701, "eclair.api.port" -> 30781, s"eclair.features.${Features.OnionMessages.rfcName}" -> "optional", "eclair.onion-messages.relay-policy" -> "relay-all").asJava).withFallback(commonConfig))

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("B"), nodes("A"), 1000000 sat, 0 msat)

    val numberOfChannels = 1
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

  test("simple bolt11 payment") {
    val alice = new EclairImpl(nodes("A"))
    val bob = new EclairImpl(nodes("B"))

    val preimage = randomBytes32()
    val invoice = Await.result(alice.receive(Left("simple bolt11 invoice"), Some(100000 msat), None, None, Some(preimage)), 10 seconds)

    bob.send(None,100000 msat, invoice)

    Thread.sleep(2000)

    val probe = TestProbe()
    bob.sentInfo(Right(invoice.paymentHash)).pipeTo(probe.ref)

    val response = probe.expectMsgType[Seq[OutgoingPayment]](1 minute)
    val OutgoingPaymentStatus.Succeeded(preimageReceived, _, _, _) = response.head.status
    assert(preimageReceived === preimage)
  }

  test("simple offer") {
    val alice = new EclairImpl(nodes("A"))
    val bob = new EclairImpl(nodes("B"))

    val eventListener = TestProbe()
    nodes("A").system.eventStream.subscribe(eventListener.ref, classOf[OnionMessages.ReceiveMessage])

    val offer = Offer(Some(100000 msat), "test offer", nodes("A").nodeParams.nodeId, Features.empty, nodes("A").nodeParams.chainHash)

    val probe = TestProbe()
    bob.payOffer(offer, 100000 msat, 1).pipeTo(probe.ref)

    val preimage = randomBytes32()
    val dummyInvoice = Await.result(alice.receive(Left("dummy bolt11 invoice"), Some(100000 msat), None, None, Some(preimage)), 10 seconds)

    val invoiceRequestPayload = eventListener.expectMsgType[OnionMessages.ReceiveMessage].finalPayload
    val invoiceRequest = invoiceRequestPayload.invoiceRequest.get.request
    val invoiceRequestReplyPath = invoiceRequestPayload.replyPath.get.blindedRoute
    val invoice = Bolt12Invoice(offer, invoiceRequest, preimage, nodes("A").nodeParams.privateKey, dummyInvoice.minFinalCltvExpiryDelta, Features.empty, randomBytes32())
    val encodedInvoice = perHopPayloadCodec.encode(TlvStream(Invoice(invoice))).require.bytes
    alice.sendOnionMessage(Nil, Right(invoiceRequestReplyPath), None, encodedInvoice)

    val response = probe.expectMsgType[PayOfferResponse](1 minute)
    println(response)
    assert(response.preimage contains preimage.toHex)
  }
}
