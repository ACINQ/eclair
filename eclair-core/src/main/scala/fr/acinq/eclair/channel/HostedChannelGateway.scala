package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import com.google.common.collect.HashBiMap
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.HostedChannelGateway.HotChannels

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class HostedChannelGateway(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends Actor with ActorLogging {

  context.system.scheduler.schedule(1.hour, 1.hour)(context.system.eventStream.publish(CMD_HOSTED_REMOVE_IDLE_CHANNELS))

  val inMemoryHostedChannels: HashBiMap[ByteVector32, ActorRef] = HashBiMap.create[ByteVector32, ActorRef]

  override def receive: Receive = {
    case cmd: CMD_HOSTED_INPUT_RECONNECTED =>
      Option(inMemoryHostedChannels.get(cmd.channelId)) match {
        case Some(channel) =>
          channel ! cmd
        case None =>
          val channel = context.actorOf(HostedChannel.props(nodeParams, cmd.remoteNodeId, router, relayer))
          nodeParams.db.hostedChannels.getChannel(cmd.channelId).foreach(channel ! _)
          inMemoryHostedChannels.put(cmd.channelId, channel)
          context.watch(channel)
          channel ! cmd
      }

    case cmd: HasHostedChanIdCommand => Option(inMemoryHostedChannels.get(cmd.channelId)).foreach(_ ! cmd)

    case Terminated(channelRef) => inMemoryHostedChannels.inverse.remove(channelRef)

    case HotChannels(channels) => channels.foreach(spawnChannel)
  }

  def spawnChannel(commits: HOSTED_DATA_COMMITMENTS): Unit = {
    val chan = context.actorOf(HostedChannel.props(nodeParams, commits.remoteNodeId, router, relayer))
    chan ! commits
    inMemoryHostedChannels.put(commits.channelId, chan)
    context.watch(chan)
  }
}

object HostedChannelGateway {
  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef) = Props(new HostedChannelGateway(nodeParams, router, relayer))

  case class HotChannels(channels: Set[HOSTED_DATA_COMMITMENTS])
}