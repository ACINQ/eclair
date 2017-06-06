package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef}

/**
  * Created by PM on 16/06/2016.
  */
class NoopPaymentHandler extends Actor with ActorLogging {

  override def receive: Receive = forward(context.system.deadLetters)

  def forward(handler: ActorRef): Receive = {
    case newHandler: ActorRef =>
      log.info(s"registering actor $handler as payment handler")
      context become forward(newHandler)
    case msg => handler forward msg
  }

}
