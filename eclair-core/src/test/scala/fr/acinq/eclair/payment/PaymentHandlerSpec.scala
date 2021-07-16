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

import akka.actor.Actor.Receive
import akka.actor.{ActorContext, ActorSystem}
import akka.event.DiagnosticLoggingAdapter
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.TestKitBaseClass
import fr.acinq.eclair.payment.receive.{PaymentHandler, ReceiveHandler}
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.duration._

class PaymentHandlerSpec extends TestKitBaseClass with AnyFunSuiteLike {

  test("compose payment handlers") {
    val handler = system.actorOf(PaymentHandler.props(Alice.nodeParams, TestProbe().ref))

    val intHandler = new ReceiveHandler {
      override def handle(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Receive = {
        case i: Int => ctx.sender() ! -i
      }
    }

    val stringHandler = new ReceiveHandler {
      override def handle(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Receive = {
        case s: String => ctx.sender() ! s.reverse
      }
    }

    val probe = TestProbe()

    probe.send(handler, 42)
    probe.expectNoMessage(300 millis)

    probe.send(handler, intHandler)

    probe.send(handler, 42)
    probe.expectMsg(-42)

    probe.send(handler, "abcdef")
    probe.expectNoMessage(300 millis)

    probe.send(handler, stringHandler)

    probe.send(handler, 123)
    probe.expectMsg(-123)

    probe.send(handler, "abcdef")
    probe.expectMsg("fedcba")
  }

}
