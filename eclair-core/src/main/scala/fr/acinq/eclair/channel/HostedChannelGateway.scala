package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import com.google.common.collect.HashBiMap
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.NodeParams

import scala.concurrent.ExecutionContext
import scala.collection.JavaConverters._
import scala.concurrent.duration._

class HostedChannelGateway(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends Actor with ActorLogging {

  context.system.scheduler.schedule(initialDelay = 1.hour, interval = 1.hour, receiver = self, message = CMD_HOSTED_REMOVE_IDLE_CHANNELS)

  val inMemoryHostedChannels: HashBiMap[ByteVector32, ActorRef] = HashBiMap.create[ByteVector32, ActorRef]

  override def receive: Receive = {
    case cmd: CMD_HOSTED_INPUT_RECONNECTED =>
      Option(inMemoryHostedChannels.get(cmd.channelId)) getOrElse {
        val freshChannel = context.actorOf(HostedChannel.props(nodeParams, cmd.remoteNodeId, router, relayer))
        nodeParams.db.hostedChannels.getChannel(cmd.channelId).foreach(freshChannel ! _)
        inMemoryHostedChannels.put(cmd.channelId, freshChannel)
        context.watch(freshChannel)
        freshChannel ! cmd
      }

    case CMD_HOSTED_REMOVE_IDLE_CHANNELS => inMemoryHostedChannels.values().asScala.foreach(_ ! CMD_HOSTED_REMOVE_IDLE_CHANNELS)

    case cmd: HasHostedChanIdCommand => Option(inMemoryHostedChannels.get(cmd.channelId)).foreach(_ ! cmd)

    case Terminated(channelRef) => inMemoryHostedChannels.inverse.remove(channelRef)
  }
}

object HostedChannelGateway {
  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef) = Props(new HostedChannelGateway(nodeParams, router, relayer))
}