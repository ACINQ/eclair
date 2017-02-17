package fr.acinq.eclair

import akka.actor.{Actor, ActorRef, Stash}
import fr.acinq.eclair.channel.{INPUT_DISCONNECTED, INPUT_RECONNECTED}

/**
  * Handles a bi-directional path between 2 actors
  * used to avoid the chicken-and-egg problem of:
  * a = new Channel(b)
  * b = new Channel(a)
  */
class Pipe extends Actor with Stash {

  def receive = {
    case (a: ActorRef, b: ActorRef) =>
      unstashAll()
      context become connected(a, b)

    case msg => stash()
  }

  def connected(a: ActorRef, b: ActorRef): Receive = {
    case msg if sender() == a => b forward msg
    case msg if sender() == b => a forward msg
    case msg@INPUT_DISCONNECTED =>
      // used for fuzzy testing (eg: send Disconnected messages)
      b forward msg
      a forward msg
      context become disconnected(a, b)
  }

  def disconnected(a: ActorRef, b: ActorRef): Receive = {
    case msg if sender() == a => {} // dropped
    case msg if sender() == b => {} // dropped
    case msg: INPUT_RECONNECTED =>
      // used for fuzzy testing (eg: send Disconnected messages)
      b forward msg
      a forward msg
      context become connected(a, b)

  }
}
