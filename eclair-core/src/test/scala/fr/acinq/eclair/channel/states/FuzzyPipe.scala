package fr.acinq.eclair.channel.states

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import fr.acinq.eclair.channel.Commitments.msg2String
import fr.acinq.eclair.channel.{INPUT_DISCONNECTED, INPUT_RECONNECTED}
import fr.acinq.eclair.wire.LightningMessage

import scala.concurrent.duration._
import scala.util.Random

/**
  * A Fuzzy [[fr.acinq.eclair.Pipe]] which randomly disconnects/reconnects peers.
  */
class FuzzyPipe extends Actor with Stash with ActorLogging {

  import scala.concurrent.ExecutionContext.Implicits.global

  def receive = {
    case (a: ActorRef, b: ActorRef) =>
      unstashAll()
      context become connected(a, b, 10)

    case _ => stash()
  }

  def stayOrDisconnect(a: ActorRef, b: ActorRef, countdown: Int) = {
    if (countdown > 1) context become connected(a, b, countdown - 1)
    else {
      log.debug("DISCONNECTED")
      a ! INPUT_DISCONNECTED
      b ! INPUT_DISCONNECTED
      context.system.scheduler.scheduleOnce(100 millis, self, 'reconnect)
      context become disconnected(a, b)
    }
  }

  def connected(a: ActorRef, b: ActorRef, countdown: Int): Receive = {
    case msg: LightningMessage if sender() == a =>
      log.debug(f"A ---${msg2String(msg)}%-6s--> B")
      b forward msg
      stayOrDisconnect(a, b, countdown)
    case msg: LightningMessage if sender() == b =>
      log.debug(f"A <--${msg2String(msg)}%-6s--- B")
      a forward msg
      stayOrDisconnect(a, b, countdown)
  }

  def disconnected(a: ActorRef, b: ActorRef): Receive = {
    case msg: LightningMessage if sender() == a =>
      // dropped
      log.info(f"A ---${msg2String(msg)}%-6s-X")
    case msg: LightningMessage if sender() == b =>
      // dropped
      log.debug(f"  X-${msg2String(msg)}%-6s--- B")
    case 'reconnect =>
      log.debug("RECONNECTED")
      a ! INPUT_RECONNECTED(self)
      b ! INPUT_RECONNECTED(self)
      context become connected(a, b, Random.nextInt(20))
  }
}
