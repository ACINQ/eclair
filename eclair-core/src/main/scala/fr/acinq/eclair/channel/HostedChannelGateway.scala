package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef, Props}

import scala.concurrent.ExecutionContext
import fr.acinq.eclair.{NodeParams, ShortChannelId}

import scala.concurrent.duration._

class HostedChannelGateway(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends Actor with ActorLogging {

  context.system.scheduler.schedule(initialDelay = 1.hour, interval = 1.hour, receiver = self, message = CMD_KILL_IDLE_HOSTED_CHANNELS)

  override def receive: Receive = {
    case cmd: CMD_REGISTER_HOSTED_SHORT_CHANNEL_ID =>
      val newShortChannelId = ShortChannelId(nodeParams.db.hostedChannels.getNewShortChannelId)
      val hc1 = cmd.hc.copy(shortChannelId = newShortChannelId)
      nodeParams.db.hostedChannels.addOrUpdateChannel(hc1)
      sender ! CMD_REGISTER_HOSTED_SHORT_CHANNEL_ID(hc1)
  }
}

object HostedChannelGateway {
  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef) = Props(new HostedChannelGateway(nodeParams, router, relayer))
}