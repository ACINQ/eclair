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

package fr.acinq.eclair.payment

import java.util.UUID

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.eclair.payment.HtlcGenerationSpec._
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.{FinalizeRoute, RouteParams, RouteRequest}
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, TestConstants}
import org.scalatest.FunSuiteLike

/**
 * Created by t-bast on 25/07/2019.
 */

class PaymentInitiatorSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  test("forward payment with pre-defined route") {
    val sender = TestProbe()
    val router = TestProbe()
    val paymentInitiator = system.actorOf(PaymentInitiator.props(TestConstants.Alice.nodeParams, router.ref, TestProbe().ref))

    sender.send(paymentInitiator, PaymentInitiator.SendPaymentRequest(finalAmountMsat, paymentHash, c, 1, predefinedRoute = Seq(a, b, c)))
    sender.expectMsgType[UUID]
    router.expectMsg(FinalizeRoute(Seq(a, b, c)))
  }

  test("forward legacy payment") {
    val sender = TestProbe()
    val router = TestProbe()
    val paymentInitiator = system.actorOf(PaymentInitiator.props(TestConstants.Alice.nodeParams, router.ref, TestProbe().ref))

    val hints = Seq(Seq(ExtraHop(b, channelUpdate_bc.shortChannelId, feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12))))
    val routeParams = RouteParams(randomize = true, 15 msat, 1.5, 5, CltvExpiryDelta(561), None)
    sender.send(paymentInitiator, PaymentInitiator.SendPaymentRequest(finalAmountMsat, paymentHash, c, 1, CltvExpiryDelta(42), assistedRoutes = hints, routeParams = Some(routeParams)))
    sender.expectMsgType[UUID]
    router.expectMsg(RouteRequest(TestConstants.Alice.nodeParams.nodeId, c, finalAmountMsat, assistedRoutes = hints, routeParams = Some(routeParams)))

    sender.send(paymentInitiator, PaymentInitiator.SendPaymentRequest(finalAmountMsat, paymentHash, e, 3))
    sender.expectMsgType[UUID]
    router.expectMsg(RouteRequest(TestConstants.Alice.nodeParams.nodeId, e, finalAmountMsat))
  }

}
