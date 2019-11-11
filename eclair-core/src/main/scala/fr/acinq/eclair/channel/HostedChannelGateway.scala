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
    case cmd: CMD_HOSTED_INPUT_RECONNECTED =>
      Option(inMemoryHostedChannels get cmd.channelId) match {
        case None => restoreOrSpawnNew(cmd.channelId, cmd.remoteNodeId) ! cmd
        case Some(channel) => channel ! cmd
      }

    case cmd: CMD_HOSTED_EXTERNAL_FULFILL =>
      Option(inMemoryHostedChannels get cmd.channelId) match {
        case None => restoreOrNotFound(cmd.channelId)(externalFulfill(_, cmd))
        case Some(channel) => externalFulfill(channel, cmd)
      }

    case cmd: CMD_HOSTED_OVERRIDE =>
      Option(inMemoryHostedChannels get cmd.channelId) match {
        case None => restoreOrNotFound(cmd.channelId)(_ forward cmd)
        case Some(channel) => channel forward cmd
      }

    case cmd: HasHostedChanIdCommand => Option(inMemoryHostedChannels get cmd.channelId).foreach(_ ! cmd)

    case Terminated(channelRef) => inMemoryHostedChannels.inverse.remove(channelRef)

    case HotChannels(channels) => channels.foreach(restoreChannel)
  }

  def spawnNewChannel(channelId: ByteVector32, remoteNodeId: PublicKey): ActorRef = {
    val channel = context.actorOf(HostedChannel.props(nodeParams, remoteNodeId, router, relayer))
    inMemoryHostedChannels.put(channelId, channel)
    context.watch(channel)
    channel
  }

  def restoreChannel(commits: HOSTED_DATA_COMMITMENTS): ActorRef = {
    val channel = spawnNewChannel(commits.channelId, commits.remoteNodeId)
    channel ! commits
    channel
  }

  def restoreOrSpawnNew(channelId: ByteVector32, remoteNodeId: PublicKey): ActorRef =
    nodeParams.db.hostedChannels.getChannel(channelId).map(restoreChannel) match {
      case None => spawnNewChannel(channelId, remoteNodeId)
      case Some(channel) => channel
    }

  def restoreOrNotFound(channelId: ByteVector32)(whenRestored: ActorRef => Unit): Unit =
    nodeParams.db.hostedChannels.getChannel(channelId).map(restoreChannel) match {
      case None => sender ! s"Hosted channel id=$channelId not found"
      case Some(channel) => whenRestored(channel)
    }

  def externalFulfill(channel: ActorRef, cmd: CMD_HOSTED_EXTERNAL_FULFILL): Unit = {
    val haltNormalOpsError = wire.Error(cmd.channelId, "External fulfill attempt")
    channel ! CMD_HOSTED_MESSAGE(cmd.channelId, haltNormalOpsError)
    channel forward cmd.fulfillCmd
  }
}

object HostedChannelGateway {

  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef) = Props(new HostedChannelGateway(nodeParams, router, relayer))

  case class HotChannels(channels: Seq[HOSTED_DATA_COMMITMENTS])
}