package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import com.google.common.collect.HashBiMap
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey

import scala.concurrent.ExecutionContext
import fr.acinq.eclair.{NodeParams, ShortChannelId}
import scala.collection.JavaConverters._
import scala.concurrent.duration._

class HostedChannelGateway(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef)
                          (implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends Actor with ActorLogging {

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
    case cmd: CMD_HOSTED_INPUT_DISCONNECTED =>
      Option(inMemoryHostedChannels.get(cmd.channelId)).foreach(_ ! cmd)

    case cmd: CMD_INVOKE_HOSTED_CHANNEL =>
      getChannel(cmd.channelId, cmd.remoteNodeId) ! cmd

    case cmd: CMD_HOSTED_MESSAGE =>
      getChannel(cmd.channelId, cmd.remoteNodeId) ! cmd.message

    case cmd: CMD_REGISTER_HOSTED_SHORT_CHANNEL_ID =>
      val newShortChannelId = ShortChannelId(nodeParams.db.hostedChannels.getNewShortChannelId)
      val hc1 = cmd.hc.copy(shortChannelId = newShortChannelId)
      nodeParams.db.hostedChannels.addOrUpdateChannel(hc1)
      sender ! CMD_REGISTER_HOSTED_SHORT_CHANNEL_ID(hc1)

    case CMD_KILL_IDLE_HOSTED_CHANNELS =>
      inMemoryHostedChannels.values().asScala.foreach(_ ! CMD_KILL_IDLE_HOSTED_CHANNELS)

    case Terminated(channelRef) =>
      inMemoryHostedChannels.inverse.remove(channelRef)
  }
}

object HostedChannelGateway {
  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef) = Props(new HostedChannelGateway(nodeParams, router, relayer))
}