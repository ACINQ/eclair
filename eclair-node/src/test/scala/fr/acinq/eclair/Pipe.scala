package fr.acinq.eclair

import akka.actor.{Actor, ActorRef, Stash}

/**
  * Created by PM on 26/04/2016.
  */

// handle a bi-directional path between 2 actors
// used to avoid the chicken-and-egg problem of:
// a = new Channel(b)
// b = new Channel(a)
class Pipe extends Actor with Stash {

  def receive = {
    case (a: ActorRef, b: ActorRef) =>
      unstashAll()
      context become ready(a, b)

    case msg => stash()
  }

  def ready(a: ActorRef, b: ActorRef): Receive = {
    case msg if sender() == a => b forward msg
    case msg if sender() == b => a forward msg
  }
}
