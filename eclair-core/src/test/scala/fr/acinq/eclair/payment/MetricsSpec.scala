/*
 * Copyright 2018 ACINQ SAS
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

import akka.actor.{ActorRef, ActorSystem, Status}
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.blockchain.CurrentBlockCount
import org.scalatest.FunSuiteLike
import scala.concurrent.duration._
import com.codahale.metrics.Meter
/**
  * Created by RO on 8 Jan 2018.
  */

class MetricsSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {



  test("Check events logged") {
    val nodeParams=TestConstants.Bob.nodeParams
    val router = TestProbe()
    val register = TestProbe()
    val metrics=system.actorOf(MetricsActor.props(nodeParams,router.ref,register.ref))

    // Seem to need this dummy probe to force akka to send messages on event bus?
    val deliverOrder = TestProbe()
    system.eventStream.subscribe(deliverOrder.ref,classOf[String])
    system.eventStream.subscribe(deliverOrder.ref,classOf[CurrentBlockCount])
    system.eventStream.publish("TestMessage")
    deliverOrder.expectMsg("TestMessage")
    system.eventStream.publish("TestMessage")
    deliverOrder.expectMsg("TestMessage")
    system.eventStream.publish("TestMessage")
    deliverOrder.expectMsg("TestMessage")
    system.eventStream.publish(CurrentBlockCount(4))
    deliverOrder.expectMsg(CurrentBlockCount(4))

    metrics ! UpdateGauges
    router.expectMsg('channelcount)
    router.reply(42)

    router.expectMsg('nodecount)
    router.reply(43)

    register.expectMsg('channelcount)
    register.reply(44)


    awaitCond({
      Option(nodeParams.metrics.getMeters.get("eventstream.String")) match {
        case Some(m: Meter) => m.getCount == 3
        case _ => false
      }
    }, max = 3 seconds, interval = 500 millis)

    assert(nodeParams.metrics.getMeters.get("eventstream.CurrentBlockCount").getCount==1)

    assert(nodeParams.metrics.getGauges.get("stats.channels").getValue==42)
    assert(nodeParams.metrics.getGauges.get("stats.nodes").getValue==43)
    assert(nodeParams.metrics.getGauges.get("stats.localchannels").getValue==44)
  }

}
