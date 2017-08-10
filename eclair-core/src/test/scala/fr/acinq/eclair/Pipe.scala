package fr.acinq.eclair

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import fr.acinq.eclair.channel.Commitments.msg2String
import fr.acinq.eclair.channel.{INPUT_DISCONNECTED, INPUT_RECONNECTED}
import fr.acinq.eclair.wire.LightningMessage

/**
  * Handles a bi-directional path between 2 actors
  * used to avoid the chicken-and-egg problem of:
  * a = new Channel(b)
  * b = new Channel(a)
  */
class Pipe extends Actor with Stash with ActorLogging {

  def receive = {
    case (a: ActorRef, b: ActorRef) =>
      unstashAll()
      context become connected(a, b)

    case _ => stash()
  }

  def connected(a: ActorRef, b: ActorRef): Receive = {
    case msg: LightningMessage if sender() == a =>
      log.debug(f"A ---${msg2String(msg)}%-6s--> B")
      b forward msg
    case msg: LightningMessage if sender() == b =>
      log.debug(f"A <--${msg2String(msg)}%-6s--- B")
      a forward msg
  }
}
