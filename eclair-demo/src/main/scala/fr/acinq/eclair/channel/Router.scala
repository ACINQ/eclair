package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging}
import fr.acinq.bitcoin.BinaryData
import lightning._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Created by PM on 24/05/2016.
  */
class Router extends Actor with ActorLogging {

  import ExecutionContext.Implicits.global
  context.system.scheduler.schedule(5 seconds, 10 seconds, self, 'tick)

  def receive: Receive = main(Map())

  def main(channels: Map[BinaryData, channel_desc]): Receive = {
    case r@register_channel(c) =>
      log.debug(s"received $r")
      context become main(channels + (BinaryData(c.id.toByteArray) -> c))
    case u@unregister_channel(c) =>
      log.debug(s"received $u")
      context become main(channels - BinaryData(c.id.toByteArray))
    case 'tick =>
      val sel = context.actorSelection(Register.actorPathToHandlers())
      channels.values.foreach(sel ! register_channel(_))
    case 'network =>
      sender ! channels.values
  }

}
