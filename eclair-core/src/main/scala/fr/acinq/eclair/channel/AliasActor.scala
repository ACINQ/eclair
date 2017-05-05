package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef}

/**
  * Created by PM on 19/03/2016.
  */

/**
  * Purpose of this actor is to be an alias for its origin actor.
  * It allows to reference the using {{{system.actorSelection()}}} with a meaningful name
  *
  * @param origin aliased actor
  */
class AliasActor(origin: ActorRef) extends Actor with ActorLogging {

  log.info(s"forwarding messages from $self to $origin")

  override def receive: Actor.Receive = {
    case m => origin forward m
  }
}
