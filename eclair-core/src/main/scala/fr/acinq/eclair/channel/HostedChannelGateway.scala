package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import com.google.common.collect.HashBiMap
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey

import scala.concurrent.ExecutionContext
import fr.acinq.eclair.{NodeParams, ShortChannelId}

import scala.concurrent.duration._

class HostedChannelGateway(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends Actor with ActorLogging {

  context.system.scheduler.schedule(initialDelay = 1.hour, interval = 1.hour, receiver = self, message = CMD_KILL_IDLE_HOSTED_CHANNELS)

  val inMemoryHostedChannels: HashBiMap[ByteVector32, ActorRef] = HashBiMap.create[ByteVector32, ActorRef]

  def getChannel(channelId: ByteVector32, remoteNodeId: PublicKey): ActorRef =
    Option(inMemoryHostedChannels.get(channelId)) getOrElse {
      val freshChannel = context.actorOf(HostedChannel.props(nodeParams, remoteNodeId, router, relayer))
      nodeParams.db.hostedChannels.getChannelById(channelId).foreach(freshChannel ! _)
      inMemoryHostedChannels.put(channelId, freshChannel)
      context.watch(freshChannel)
    }

  override def receive: Receive = {
    case wrap: CMD_HOSTED_MESSAGE =>
      getChannel(wrap.channelId, wrap.remoteNodeId) ! wrap.message

    case cmd: CMD_REGISTER_HOSTED_SHORT_CHANNEL_ID =>
      val newShortChannelId = ShortChannelId(nodeParams.db.hostedChannels.getNewShortChannelId)
      val hc1 = cmd.hc.copy(shortChannelId = newShortChannelId)
      nodeParams.db.hostedChannels.addOrUpdateChannel(hc1)
      sender ! CMD_REGISTER_HOSTED_SHORT_CHANNEL_ID(hc1)

    case Terminated(channelRef) =>
      inMemoryHostedChannels.inverse.remove(channelRef)
  }
}

object HostedChannelGateway {
  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef) = Props(new HostedChannelGateway(nodeParams, router, relayer))
}