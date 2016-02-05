package fr.acinq.eclair

import akka.actor._
import akka.util.Timeout
import fr.acinq.eclair.channel.{CMD_GETINFO, ChannelState}
import fr.acinq.eclair.io.AuthHandler
import akka.pattern.ask

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

// @formatter:off
case class CreateChannel(connection: ActorRef, our_anchor: Boolean, amount: Long = 0)
case class GetChannels()
case class RegisterChannel(nodeId: String, state: ChannelState)
// @formatter:on


/**
  * Created by PM on 26/01/2016.
  */
class RegisterActor extends Actor with ActorLogging {

  var i = 0
  implicit val timeout = Timeout(30 seconds)
  import ExecutionContext.Implicits.global

  context.become(main(Map()))

  override def receive: Receive = ???

  def main(channels: Map[String, ChannelState]): Receive = {
    case CreateChannel(connection, our_anchor, amount) => context.actorOf(Props(classOf[AuthHandler], connection, Boot.blockchain, our_anchor, amount), name = s"handler-${i = i + 1; i}")
    case GetChannels =>
      val s = sender()
      Future.sequence(context.children.map(c => c ? CMD_GETINFO)).map(s ! _)
    case RegisterChannel(nodeId, state) => context.become(main(channels + (nodeId -> state)))
  }

}
