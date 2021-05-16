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

import akka.Done
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.SatoshiLong
import fr.acinq.eclair.MilliSatoshiLong
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivePayment
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentRequest
import fr.acinq.eclair.router.Router

import java.util.UUID
import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/**
 * Created by PM on 15/03/2017.
 */

class PerformanceIntegrationSpec extends IntegrationSpec {

  test("start eclair nodes") {
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.max-funding-satoshis" -> 100_000_000, "eclair.server.port" -> 29730).asJava).withFallback(commonFeatures).withFallback(commonConfig)) // A's channels are private
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.max-funding-satoshis" -> 100_000_000, "eclair.server.port" -> 29731).asJava).withFallback(commonFeatures).withFallback(commonConfig))
  }

  test("connect nodes") {
    // A---B

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("A"), nodes("B"), 100_000_000 sat, 0 msat)

    // confirming the funding tx
    generateBlocks(6)

    within(60 seconds) {
      eventListener.expectMsgType[ChannelStateChanged](60 seconds).currentState == NORMAL
    }
  }

  test("wait for channels balance") {
    // Channels balance should now be available in the router
    val sender = TestProbe()
    awaitCond({
      sender.send(nodes("A").router, Router.GetRoutingState)
      val routingState = sender.expectMsgType[Router.RoutingState]
      routingState.channels.nonEmpty
    }, 60 seconds)
  }

  def sendPayment(): Unit = {
    val sender = TestProbe()
    val amountMsat = 100_000.msat
    // first we retrieve a payment hash from D
    sender.send(nodes("B").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, SendPaymentRequest(amountMsat, pr.paymentHash, nodes("B").nodeParams.nodeId, fallbackFinalExpiryDelta = finalCltvExpiryDelta, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
  }

  test("send a large number of htlcs A->B") {
    val SENDERS_COUNT = 8
    val PAYMENTS_COUNT = 1_000
    val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(SENDERS_COUNT))
    val start = System.currentTimeMillis()
    (0 until PAYMENTS_COUNT).foreach(_ => Future(sendPayment())(ec))
    val f = Future(Done)(ec)
    awaitCond(f.isCompleted, 1 hour)
    val end = System.currentTimeMillis()
    val duration = end - start
    println(s"$PAYMENTS_COUNT payments in ${duration}ms ${PAYMENTS_COUNT * 1000 / duration}htlc/s (senders=$SENDERS_COUNT)")
  }

}
