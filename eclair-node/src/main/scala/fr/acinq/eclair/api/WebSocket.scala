package fr.acinq.eclair.api

import akka.NotUsed
import akka.actor.{Actor, Props}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Route
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Source}
import fr.acinq.eclair.channel.{ChannelClosed, ChannelCreated, ChannelStateChanged, WAIT_FOR_INIT_INTERNAL}
import fr.acinq.eclair.payment.PaymentEvent

trait WebSocket {
  this: Service =>

  import JsonSupport.{formats, serialization}

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
        context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])
        context.system.eventStream.subscribe(self, classOf[ChannelClosed])
      }

      def receive: Receive = {
        case message: PaymentEvent => flowInput.offer(serialization.write(message))
        case message: ChannelCreated => flowInput.offer(serialization.write(message))
        case message: ChannelStateChanged =>
          if (message.previousState != WAIT_FOR_INIT_INTERNAL) {
            flowInput.offer(serialization.write(message))
          }
        case message: ChannelClosed => flowInput.offer(serialization.write(message))
      }

    }))

    Flow[Message]
      .mapConcat(_ => Nil) // Ignore heartbeats and other data from the client
      .merge(flowOutput) // Stream the data we want to the client
      .map(TextMessage.apply)
  }



}
