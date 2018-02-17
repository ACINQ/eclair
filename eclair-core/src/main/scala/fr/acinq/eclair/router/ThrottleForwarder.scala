package fr.acinq.eclair.router

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}

import scala.concurrent.duration.{FiniteDuration, _}

/**
  * This actor forwards messages to another actor, but groups them and introduces
  * delays between each groups.
  *
  * If A wants to send a lot of lower importance messages to B, it is useful to let
  * higher importance messages go in the stream.
  */
class ThrottleForwarder(target: ActorRef, messages: Iterable[Any], chunkSize: Int, delay: FiniteDuration) extends Actor with ActorLogging {

  import ThrottleForwarder.Tick

  import scala.concurrent.ExecutionContext.Implicits.global

  context watch target

  val clock = context.system.scheduler.schedule(0 second, delay, self, Tick)

  log.debug(s"sending messages=${messages.size} with chunkSize=$chunkSize and delay=$delay")

  override def receive = group(messages)

  def group(remaining: Iterable[Any]): Receive = {
    case Tick =>
      remaining.splitAt(chunkSize) match {
        case (Nil, _) =>
          clock.cancel()
          log.debug(s"sent messages=${messages.size} with chunkSize=$chunkSize and delay=$delay")
          context stop self
        case (chunk, rest) =>
          chunk.foreach(target ! _)
          context become group(rest)
      }

    case Terminated(_) =>
      clock.cancel()
      log.debug(s"target died, aborting sending")
      context stop self
  }

}

object ThrottleForwarder {

  def props(target: ActorRef, messages: Iterable[Any], groupSize: Int, delay: FiniteDuration) = Props(new ThrottleForwarder(target, messages, groupSize, delay))

  case object Tick

}
