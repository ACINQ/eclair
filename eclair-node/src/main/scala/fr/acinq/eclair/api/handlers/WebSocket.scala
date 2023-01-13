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

package fr.acinq.eclair.api.handlers

import akka.NotUsed
import akka.actor.{Actor, Props}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Source}
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.channel._
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.payment.PaymentEvent

trait WebSocket {
  this: Service =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, serialization}

  /**
   * Http server route exposing the websocket handler
   */
  val webSocket: Route = getRequest("ws") { implicit t =>
    handleWebSocketMessages(makeSocketHandler)
  }

  // Init the websocket message flow
  private lazy val makeSocketHandler: Flow[Message, TextMessage.Strict, NotUsed] = {

    // create a flow transforming a queue of string -> string
    val (flowInput, flowOutput) = Source.queue[String](10, OverflowStrategy.dropTail)
      .toMat(BroadcastHub.sink[String])(Keep.both).run()

    // register an actor that feeds the queue on payment related events
    actorSystem.actorOf(Props(new Actor {

      override def preStart: Unit = {
        context.system.eventStream.subscribe(self, classOf[PaymentEvent])
        context.system.eventStream.subscribe(self, classOf[ChannelCreated])
        context.system.eventStream.subscribe(self, classOf[ChannelOpened])
        context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])
        context.system.eventStream.subscribe(self, classOf[ChannelClosed])
        context.system.eventStream.subscribe(self, classOf[OnionMessages.ReceiveMessage])
      }

      def receive: Receive = {
        case message: PaymentEvent => flowInput.offer(serialization.write(message))
        case message: ChannelCreated => flowInput.offer(serialization.write(message))
        case message: ChannelOpened => flowInput.offer(serialization.write(message))
        case message: ChannelStateChanged =>
          if (message.previousState != WAIT_FOR_INIT_INTERNAL) {
            flowInput.offer(serialization.write(message))
          }
        case message: ChannelClosed => flowInput.offer(serialization.write(message))
        case message: OnionMessages.ReceiveMessage => flowInput.offer(serialization.write(message))
      }

    }))

    Flow[Message]
      .mapConcat(_ => Nil) // Ignore heartbeats and other data from the client
      .merge(flowOutput) // Stream the data we want to the client
      .map(TextMessage.apply)
  }

}
