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

import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceiveStandardPayment
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.PreimageReceived
import fr.acinq.eclair.payment.send.PaymentInitiator
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.{MilliSatoshiLong, TimestampMilli}
import org.scalatest.Ignore

import java.util.UUID
import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/**
 * Created by PM on 12/07/2021.
 */

@Ignore
class PerformanceIntegrationSpec extends IntegrationSpec {

  test("start eclair nodes") {
    val commonPerfTestConfig = ConfigFactory.parseMap(Map(
      "eclair.channel.max-funding-satoshis" -> 100_000_000,
      "eclair.channel.max-accepted-htlcs" -> Channel.MAX_ACCEPTED_HTLCS,
      "eclair.file-backup.enabled" -> false,
    ).asJava)

    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.server.port" -> 29730).asJava).withFallback(commonPerfTestConfig).withFallback(commonConfig)) // A's channels are private
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.server.port" -> 29731).asJava).withFallback(commonPerfTestConfig).withFallback(commonConfig))
  }

  test("connect nodes") {
    // A---B

    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("A"), nodes("B"), 100_000_000 sat, 0 msat)

    // confirming the funding tx
    generateBlocks(6)

    within(60 seconds) {
      eventListener.expectMsgType[ChannelStateChanged](60 seconds).currentState == NORMAL
    }
  }

  test("wait for channels") {
    // Channels should now be available in the router
    val sender = TestProbe()
    awaitCond({
      sender.send(nodes("A").router, Router.GetRoutingState)
      val routingState = sender.expectMsgType[Router.RoutingState]
      routingState.channels.nonEmpty
    }, 60 seconds)
  }

  def sendPayment()(implicit ec: ExecutionContext): Future[PaymentSent] = Future {
    val sender = TestProbe()
    val amountMsat = 100_000.msat
    // first we retrieve a payment hash from B
    sender.send(nodes("B").paymentHandler, ReceiveStandardPayment(Some(amountMsat), Left("1 coffee")))
    val pr = sender.expectMsgType[Invoice]
    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator, PaymentInitiator.SendPaymentToNode(sender.ref, amountMsat, pr, routeParams = integrationTestRouteParams, maxAttempts = 1))
    val paymentId = sender.expectMsgType[UUID]
    sender.expectMsgType[PreimageReceived]
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == paymentId)
    ps
  }

  test("send a large number of htlcs A->B") {
    val SENDERS_COUNT = 16
    val PAYMENTS_COUNT = 3_000
    val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(SENDERS_COUNT))
    val start = TimestampMilli.now()
    val futures = (0 until PAYMENTS_COUNT).map(_ => sendPayment()(ec))
    implicit val dummyEc: ExecutionContext = ExecutionContext.Implicits.global
    val f = Future.sequence(futures)
    Await.result(f, 1 hour)
    val end = TimestampMilli.now()
    val duration = end - start
    println(s"$PAYMENTS_COUNT payments in ${duration.toMillis}ms ${PAYMENTS_COUNT * 1000 / duration.toMillis}htlc/s (senders=$SENDERS_COUNT)")
  }

}
