package fr.acinq.eclair

import java.net.InetSocketAddress

import akka.actor.{Props, Actor, ActorLogging}
import fr.acinq.eclair.channel.ChannelState
import fr.acinq.eclair.io.Client

// @formatter:off
case class CreateChannel(addr: InetSocketAddress)
case class GetChannels()
case class RegisterChannel(nodeId: String, state: ChannelState)
// @formatter:on


/**
  * Created by PM on 26/01/2016.
  */
class RegisterActor extends Actor with ActorLogging {

  context.become(main(Map()))

  override def receive: Receive = ???

  def main(channels: Map[String, ChannelState]): Receive = {
    case CreateChannel(addr) => context.actorOf(Props(classOf[Client], addr))
    case GetChannels => sender() ! context.children
    case RegisterChannel(nodeId, state) => context.become(main(channels + (nodeId -> state)))
  }

}
