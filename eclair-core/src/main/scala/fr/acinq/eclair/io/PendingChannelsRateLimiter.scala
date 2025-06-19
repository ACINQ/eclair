package fr.acinq.eclair.io

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.io.PendingChannelsRateLimiter.Command
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.{GetNode, PublicNode, UnknownNode}

/**
 * A singleton actor that tracks pending channels and rate limits their creation.
 *
 * This actor should be initialized with the list of current persistent channels. It will track the pending channels
 * in real-time and apply the configured rate limits for new requests.
 *
 * It accepts the command AddOrRejectChannel and will respond with AcceptChannel or ChannelRateLimited. It also tracks
 * when channels are assigned a channel id, confirmed on-chain, closed or aborted and will update its internal state
 * accordingly.
 *
 */
object PendingChannelsRateLimiter {
  // @formatter:off
  sealed trait Command
  case class AddOrRejectChannel(replyTo: ActorRef[Response], remoteNodeId: PublicKey, temporaryChannelId: ByteVector32) extends Command
  private case class WrappedGetNodeResponse(temporaryChannelId: ByteVector32, response: Router.GetNodeResponse, replyTo: Option[ActorRef[Response]]) extends Command
  private[io] case class ReplaceChannelId(remoteNodeId: PublicKey, temporaryChannelId: ByteVector32, channelId: ByteVector32) extends Command
  private[io] case class RemoveChannelId(remoteNodeId: PublicKey, channelId: ByteVector32) extends Command
  private[io] case class CountOpenChannelRequests(replyTo: ActorRef[Int], publicPeers: Boolean) extends Command

  sealed trait Response
  case object AcceptOpenChannel extends Response
  case object ChannelRateLimited extends Response
  // @formatter:on

  def apply(nodeParams: NodeParams, router: ActorRef[Router.GetNode], channels: Seq[PersistentChannelData]): Behavior[Command] = {
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelIdAssigned](c => ReplaceChannelId(c.remoteNodeId, c.temporaryChannelId, c.channelId)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelOpened](c => RemoveChannelId(c.remoteNodeId, c.channelId)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelClosed](c => RemoveChannelId(c.commitments.remoteNodeId, c.channelId)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelAborted](c => RemoveChannelId(c.remoteNodeId, c.channelId)))
      val pendingChannels = filterPendingChannels(nodeParams, channels)
      // stash up to 3 commands for each pending channel; the worst case scenario is ChannelIdAssigned followed by ChannelOpened and ChannelClosed.
      Behaviors.withStash(3 * pendingChannels.size) { stash =>
        new PendingChannelsRateLimiter(nodeParams, router, context).restoring(pendingChannels, Map(), Map(), stash)
      }
    }
  }

  private[io] def filterPendingChannels(nodeParams: NodeParams, channels: Seq[PersistentChannelData]): Map[PublicKey, Seq[PersistentChannelData]] = {
    channels.filter {
      case p: PersistentChannelData if nodeParams.channelConf.channelOpenerWhitelist.contains(p.remoteNodeId) => false
      case d: DATA_WAIT_FOR_FUNDING_CONFIRMED if !d.commitments.localChannelParams.isChannelOpener => true
      case d: DATA_WAIT_FOR_DUAL_FUNDING_SIGNED if !d.channelParams.localParams.isChannelOpener => true
      case d: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED if !d.commitments.localChannelParams.isChannelOpener => true
      case d: DATA_WAIT_FOR_CHANNEL_READY if !d.commitments.localChannelParams.isChannelOpener => true
      case d: DATA_WAIT_FOR_DUAL_FUNDING_READY if !d.commitments.localChannelParams.isChannelOpener => true
      case _ => false
    }.groupBy(_.remoteNodeId)
  }
}

private class PendingChannelsRateLimiter(nodeParams: NodeParams, router: ActorRef[Router.GetNode], context: ActorContext[Command]) {

  import PendingChannelsRateLimiter._

  private def restoring(channels: Map[PublicKey, Seq[PersistentChannelData]], pendingPublicNodeChannels: Map[PublicKey, Seq[ByteVector32]], pendingPrivateNodeChannels: Map[PublicKey, Seq[ByteVector32]], stash: StashBuffer[Command]): Behavior[Command] = {
    def stashEvent(remoteNodeId: PublicKey): Boolean = {
      val hasPendingChannels = channels.contains(remoteNodeId) || pendingPublicNodeChannels.contains(remoteNodeId) || pendingPrivateNodeChannels.contains(remoteNodeId)
      hasPendingChannels && !stash.isFull
    }
    channels.headOption match {
      case Some((remoteNodeId, pendingChannels)) =>
        val adapter = context.messageAdapter[Router.GetNodeResponse](r => WrappedGetNodeResponse(pendingChannels.head.channelId, r, None))
        router ! GetNode(adapter, remoteNodeId)
        Behaviors.receiveMessagePartial[Command] {
          case AddOrRejectChannel(replyTo, _, _) =>
            replyTo ! ChannelRateLimited
            Behaviors.same
          case WrappedGetNodeResponse(_, PublicNode(announcement, _, _), _) =>
            restoring(channels.tail, pendingPublicNodeChannels + (announcement.nodeId -> pendingChannels.map(_.channelId)), pendingPrivateNodeChannels, stash)
          case WrappedGetNodeResponse(_, UnknownNode(nodeId), _) =>
            restoring(channels.tail, pendingPublicNodeChannels, pendingPrivateNodeChannels + (nodeId -> pendingChannels.map(_.channelId)), stash)
          case r: ReplaceChannelId =>
            if (stashEvent(r.remoteNodeId)) stash.stash(r)
            Behaviors.same
          case r: RemoveChannelId =>
            if (stashEvent(r.remoteNodeId)) stash.stash(r)
            Behaviors.same
          case CountOpenChannelRequests(replyTo, publicPeers) =>
            val pendingChannels = if (publicPeers) pendingPublicNodeChannels else pendingPrivateNodeChannels
            replyTo ! pendingChannels.map(_._2.length).sum
            Behaviors.same
        }
      case None =>
        context.log.info("restored {} public peers with pending channel opens", pendingPublicNodeChannels.size)
        context.log.info("restored {} private peers with pending channel opens", pendingPrivateNodeChannels.size)
        context.log.debug("stashed commands: {}", stash.size)
        stash.unstashAll(registering(pendingPublicNodeChannels, pendingPrivateNodeChannels))
    }
  }

  private def replaceChannel(pendingNodeChannels: Map[PublicKey, Seq[ByteVector32]], remoteNodeId: PublicKey, temporaryChannelId: ByteVector32, channelId: ByteVector32): Map[PublicKey, Seq[ByteVector32]] =
    pendingNodeChannels.get(remoteNodeId) match {
      case Some(channels) if channels.contains(temporaryChannelId) =>
        context.log.debug("replaced pending channel {} with {} for node {}", temporaryChannelId, channelId, remoteNodeId)
        pendingNodeChannels + (remoteNodeId -> (channels.filterNot(_ == temporaryChannelId) :+ channelId))
      case _ => pendingNodeChannels
    }

  private def removeChannel(pendingNodeChannels: Map[PublicKey, Seq[ByteVector32]], remoteNodeId: PublicKey, channelId: ByteVector32): Map[PublicKey, Seq[ByteVector32]] =
    pendingNodeChannels.get(remoteNodeId) match {
      case Some(pendingChannels) =>
        context.log.debug("removed pending channel {} for node {}", channelId, remoteNodeId)
        val pendingChannels1 = pendingChannels.filterNot(_ == channelId)
        if (pendingChannels1.isEmpty) {
          pendingNodeChannels - remoteNodeId
        } else {
          pendingNodeChannels + (remoteNodeId -> pendingChannels1)
        }
      case _ => pendingNodeChannels
    }

  private def registering(pendingPublicNodeChannels: Map[PublicKey, Seq[ByteVector32]], pendingPrivateNodeChannels: Map[PublicKey, Seq[ByteVector32]]): Behavior[Command] = {
    Metrics.OpenChannelRequestsPending.withTag(Tags.PublicPeers, value = true).update(pendingPublicNodeChannels.map(_._2.length).sum)
    Metrics.OpenChannelRequestsPending.withTag(Tags.PublicPeers, value = false).update(pendingPrivateNodeChannels.map(_._2.length).sum)
    Behaviors.receiveMessagePartial {
      case AddOrRejectChannel(replyTo, remoteNodeId, _) if nodeParams.channelConf.channelOpenerWhitelist.contains(remoteNodeId) =>
        replyTo ! AcceptOpenChannel
        Behaviors.same
      case AddOrRejectChannel(replyTo, remoteNodeId, temporaryChannelId) =>
        val adapter = context.messageAdapter[Router.GetNodeResponse](r => WrappedGetNodeResponse(temporaryChannelId, r, Some(replyTo)))
        router ! GetNode(adapter, remoteNodeId)
        Behaviors.same
      case WrappedGetNodeResponse(temporaryChannelId, PublicNode(announcement, _, _), Some(replyTo)) =>
        pendingPublicNodeChannels.get(announcement.nodeId) match {
          case Some(pendingChannels) if pendingChannels.size >= nodeParams.channelConf.maxPendingChannelsPerPeer =>
            replyTo ! ChannelRateLimited
            Behaviors.same
          case Some(peerChannels) =>
            context.log.debug("tracking public channel {} of node: {}", temporaryChannelId, announcement.nodeId)
            replyTo ! AcceptOpenChannel
            registering(pendingPublicNodeChannels + (announcement.nodeId -> (temporaryChannelId +: peerChannels)), pendingPrivateNodeChannels)
          case None =>
            context.log.debug("tracking public channel {} of node: {}", temporaryChannelId, announcement.nodeId)
            replyTo ! AcceptOpenChannel
            registering(pendingPublicNodeChannels + (announcement.nodeId -> Seq(temporaryChannelId)), pendingPrivateNodeChannels)
        }
      case WrappedGetNodeResponse(temporaryChannelId, UnknownNode(nodeId), Some(replyTo)) =>
        if (pendingPrivateNodeChannels.map(_._2.size).sum >= nodeParams.channelConf.maxTotalPendingChannelsPrivateNodes) {
          replyTo ! ChannelRateLimited
          Behaviors.same
        } else {
          context.log.debug("tracking private channel {} of node: {}", temporaryChannelId, nodeId)
          replyTo ! AcceptOpenChannel
          pendingPrivateNodeChannels.get(nodeId) match {
            case Some(peerChannels) =>
              registering(pendingPublicNodeChannels, pendingPrivateNodeChannels + (nodeId -> (temporaryChannelId +: peerChannels)))
            case None =>
              registering(pendingPublicNodeChannels, pendingPrivateNodeChannels + (nodeId -> Seq(temporaryChannelId)))
          }
        }
      case ReplaceChannelId(remoteNodeId, temporaryChannelId, channelId) =>
        val pendingPublicNodeChannels1 = replaceChannel(pendingPublicNodeChannels, remoteNodeId, temporaryChannelId, channelId)
        val pendingPrivateNodeChannels1 = replaceChannel(pendingPrivateNodeChannels, remoteNodeId, temporaryChannelId, channelId)
        registering(pendingPublicNodeChannels1, pendingPrivateNodeChannels1)
      case RemoveChannelId(remoteNodeId, channelId) =>
        val pendingPublicNodeChannels1 = removeChannel(pendingPublicNodeChannels, remoteNodeId, channelId)
        val pendingPrivateNodeChannels1 = removeChannel(pendingPrivateNodeChannels, remoteNodeId, channelId)
        registering(pendingPublicNodeChannels1, pendingPrivateNodeChannels1)
      case CountOpenChannelRequests(replyTo, publicPeers) =>
        val pendingChannels = if (publicPeers) pendingPublicNodeChannels else pendingPrivateNodeChannels
        replyTo ! pendingChannels.map(_._2.length).sum
        Behaviors.same
    }
  }
}