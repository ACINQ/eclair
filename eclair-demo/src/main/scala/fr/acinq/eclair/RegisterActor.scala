package fr.acinq.eclair

import akka.actor.{Actor, ActorLogging}
import fr.acinq.eclair.channel.ChannelState

// @formatter:off
case class RegisterChannel(nodeId: String, state: ChannelState)
// @formatter:on


/**
  * Created by PM on 26/01/2016.
  */
class RegisterActor extends Actor with ActorLogging {

  context.become(main(Map()))

  override def receive: Receive = ???

  def main(channels: Map[String, ChannelState]): Receive = {
    case RegisterChannel(nodeId, state) => context.become(main(channels + (nodeId -> state)))
  }

}
