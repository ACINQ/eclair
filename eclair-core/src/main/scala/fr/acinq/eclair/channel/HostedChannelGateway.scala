package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import com.google.common.collect.HashBiMap
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.router.Announcements
import scala.concurrent.ExecutionContext
import fr.acinq.eclair.{NodeParams, ShortChannelId}
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import fr.acinq.eclair._

class HostedChannelGateway(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.Implicits.global) extends Actor with ActorLogging {

  context.system.scheduler.schedule(initialDelay = 1.hour, interval = 1.hour, receiver = self, message = CMD_KILL_IDLE_HOSTED_CHANNELS)

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

    case cmd: CMD_REGISTER_HOSTED_SHORT_CHANNEL_ID =>
      val newShortChannelId = ShortChannelId(nodeParams.db.hostedChannels.getNewShortChannelId)

      val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, cmd.remoteNodeId, newShortChannelId,
        minHostedCltvDelta, cmd.hostedCommits.lastCrossSignedState.initHostedChannel.htlcMinimumMsat, nodeParams.feeBase,
        nodeParams.feeProportionalMillionth, cmd.hostedCommits.lastCrossSignedState.initHostedChannel.channelCapacityMsat)

      val hostedCommits1 = cmd.hostedCommits.copy(channelUpdateOpt = Some(channelUpdate))
      nodeParams.db.hostedChannels.addUsedShortChannelId(newShortChannelId)
      nodeParams.db.hostedChannels.addOrUpdateChannel(hostedCommits1)
      sender ! hostedCommits1

    case CMD_KILL_IDLE_HOSTED_CHANNELS =>
      inMemoryHostedChannels.values().asScala.foreach(_ ! CMD_KILL_IDLE_HOSTED_CHANNELS)

    case cmd: HasHostedChanIdCommand =>
      Option(inMemoryHostedChannels.get(cmd.channelId)).foreach(_ ! cmd)

    case Terminated(channelRef) =>
      inMemoryHostedChannels.inverse.remove(channelRef)
  }
}

object HostedChannelGateway {
  def props(nodeParams: NodeParams, router: ActorRef, relayer: ActorRef) = Props(new HostedChannelGateway(nodeParams, router, relayer))
}