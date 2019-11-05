package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import com.google.common.collect.HashBiMap
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.HostedChannelGateway.HotChannels
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import fr.acinq.eclair.wire

class HostedChannelGateway(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends Actor with ActorLogging {

  context.system.scheduler.schedule(1.day, 1.day)(context.system.eventStream.publish(CMD_HOSTED_REMOVE_IDLE_CHANNELS))

  val inMemoryHostedChannels: HashBiMap[ByteVector32, ActorRef] = HashBiMap.create[ByteVector32, ActorRef]

  override def receive: Receive = {
    case cmd: CMD_HOSTED_INPUT_RECONNECTED => prepareChannel(cmd, cmd.remoteNodeId) ! cmd

    case cmd: CMD_HOSTED_EXTERNAL_FULFILL =>
      val chan = prepareChannel(cmd, cmd.remoteNodeId)
      val error = wire.Error(cmd.channelId, "External fulfill attempt")
      chan ! CMD_HOSTED_MESSAGE(cmd.channelId, error)
      chan forward cmd.fulfillCmd

    case cmd: CMD_HOSTED_OVERRIDE => Option(inMemoryHostedChannels.get(cmd.channelId)).foreach(channel => channel forward cmd)

    case cmd: HasHostedChanIdCommand => Option(inMemoryHostedChannels.get(cmd.channelId)).foreach(channel => channel ! cmd)

    case HotChannels(channels) => channels.foreach(commits => spawnChannel(commits.channelId, commits.remoteNodeId) ! commits)

    case Terminated(channelRef) => inMemoryHostedChannels.inverse.remove(channelRef)
  }

  def spawnChannel(channelId: ByteVector32, remoteNodeId: PublicKey): ActorRef = {
    val chan = context.actorOf(HostedChannel.props(nodeParams, remoteNodeId, router, relayer))
    inMemoryHostedChannels.put(channelId, chan)
    context.watch(chan)
    chan
  }

  def prepareChannel(cmd: HasHostedChanIdCommand, remoteNodeId: PublicKey): ActorRef =
    Option(inMemoryHostedChannels.get(cmd.channelId)).getOrElse {
      val chan = spawnChannel(cmd.channelId, remoteNodeId)
      nodeParams.db.hostedChannels.getChannel(cmd.channelId).foreach(commits => chan ! commits)
      chan
    }
}

object HostedChannelGateway {

  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef) = Props(new HostedChannelGateway(nodeParams, router, relayer))

  case class HotChannels(channels: Set[HOSTED_DATA_COMMITMENTS])
}