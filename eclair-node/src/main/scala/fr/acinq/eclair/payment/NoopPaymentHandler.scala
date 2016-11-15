package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef}

/**
  * Created by PM on 16/06/2016.
  */
class NoopPaymentHandler extends Actor with ActorLogging {

  override def receive: Receive = {
    case handler: ActorRef => {
      log.info(s"registering actor $handler as payment handler")
      context.become(forward(handler))
    }
    case _ => {} // no-op
  }

  def forward(handler: ActorRef): Receive = {
    case msg => handler forward msg
  }

}
