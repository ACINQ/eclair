package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import com.google.common.collect.HashBiMap
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.payment.Origin
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.wire.{ChannelUpdate, Error, LastCrossSignedState, UpdateMessage}
import fr.acinq.eclair._
import scala.concurrent.ExecutionContext
import fr.acinq.eclair.{NodeParams, ShortChannelId}

import scala.collection.JavaConverters._
import scala.concurrent.duration._

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

      val channelUpdate = Announcements.makeChannelUpdate(nodeParams.chainHash, nodeParams.privateKey, cmd.remoteNodeId, newShortChannelId, minHostedCltvDelta,
        cmd.localLCSS.initHostedChannel.htlcMinimumMsat, nodeParams.feeBase, nodeParams.feeProportionalMillionth, cmd.localLCSS.initHostedChannel.channelCapacityMsat)

      val hc = HOSTED_DATA_COMMITMENTS(ChannelVersion.STANDARD, lastCrossSignedState = cmd.localLCSS, allLocalUpdates = 0L, allRemoteUpdates = 0L,
        localChanges = LocalChanges(Nil, Nil, Nil), remoteUpdates = List.empty, localSpec = CommitmentSpec(Set.empty, feeratePerKw = 0L, toLocal = cmd.localLCSS.localBalanceMsat, toRemote = cmd.localLCSS.remoteBalanceMsat),
        originChannels = Map.empty[Long, Origin], channelId = cmd.channelId, isHost = true, channelUpdateOpt = Some(channelUpdate), localError = None, remoteError = None)

      nodeParams.db.hostedChannels.addUsedShortChannelId(newShortChannelId)
      nodeParams.db.hostedChannels.addOrUpdateChannel(hc)
      sender ! hc

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