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
import akka.pattern.ask
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.Timeout
import com.codahale.metrics.{CachedGauge, Gauge, Meter}
import fr.acinq.eclair.NodeParams

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.collection.mutable

case class UpdateGauges()

class MetricsActor(nodeParams: NodeParams, router:ActorRef, register: ActorRef) extends Actor with ActorLogging {
  implicit val timeout = Timeout(60 seconds)
  val metrics = nodeParams.metrics

  val meters = mutable.Map[String,Meter]()
  log.debug("Starting")
  context.system.eventStream.subscribe(self, classOf[AnyRef])

  var channels=0
  var nodes=0
  var localchannels=0

  metrics.register("stats.channels",
    new Gauge[Int]() {
      override def getValue() = channels
    })

  metrics.register("stats.nodes",
    new Gauge[Int]() {
      override def getValue() = nodes
    })

  metrics.register("stats.localchannels",
    new Gauge[Int]() {
      override def getValue() = localchannels
    })

  context.system.scheduler.schedule(1 minute,10 minutes,self,UpdateGauges)

  override def receive: Receive = {
    case UpdateGauges => {
      log.debug("Updating gauges")
      (router ? 'channelcount).mapTo[Int] map {channels=_ }
      (router ? 'nodecount).mapTo[Int] map {nodes=_ }
      (register ? 'channelcount).mapTo[Int] map {localchannels=_ }
    }

    case e => {
      val name=e.getClass.toString.split("\\.").last
      meters.get(name) match {
        case Some(m: Meter) => m.mark()
        case None => {
          val m=metrics.meter("eventstream."+name)
          log.debug("Adding metric for : "+name)
          m.mark()
          meters += (name -> m)
        }
      }
    }
  }

}

object MetricsActor {

  def props(nodeParams: NodeParams, router: ActorRef, register: ActorRef) = Props(classOf[MetricsActor], nodeParams, router, register)

}
