package fr.acinq.eclair.io

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
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
  private case class ReplaceChannelId(remoteNodeId: PublicKey, temporaryChannelId: ByteVector32, channelId: ByteVector32) extends Command
  private case class RemoveChannelId(remoteNodeId: PublicKey, channelId: ByteVector32) extends Command
  private[io] case class CountOpenChannelRequests(replyTo: ActorRef[Int], publicPeers: Boolean) extends Command

  sealed trait Response
  case object AcceptOpenChannel extends Response
  case object ChannelRateLimited extends Response
  // @formatter:on

  def apply(nodeParams: NodeParams, router: ActorRef[Router.GetNode], channels: Seq[PersistentChannelData]): Behavior[Command] = {
    Behaviors.setup { context =>
      new PendingChannelsRateLimiter(nodeParams, router, context).restoring(filterPendingChannels(channels), Map(), Map())
    }
  }

  private[io] def filterPendingChannels(channels: Seq[PersistentChannelData]): Map[PublicKey, Seq[PersistentChannelData]] = {
    channels.filter {
      case _: DATA_WAIT_FOR_FUNDING_CONFIRMED => true
      case _: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => true
      case _: DATA_WAIT_FOR_CHANNEL_READY => true
      case _: DATA_WAIT_FOR_DUAL_FUNDING_READY => true
      case _ => false
    }.groupBy(_.commitments.remoteNodeId)
  }
}

private class PendingChannelsRateLimiter(nodeParams: NodeParams, router: ActorRef[Router.GetNode], context: ActorContext[Command]) {

  import PendingChannelsRateLimiter._

  private def restoring(channels: Map[PublicKey, Seq[PersistentChannelData]], pendingPublicNodeChannels: Map[PublicKey, Seq[ByteVector32]], pendingPrivateNodeChannels: Map[PublicKey, Seq[ByteVector32]]): Behavior[Command] = {
    channels.headOption match {
      case Some((remoteNodeId, pendingChannels)) =>
        val adapter = context.messageAdapter[Router.GetNodeResponse](r => WrappedGetNodeResponse(pendingChannels.head.channelId, r, None))
        router ! GetNode(adapter, remoteNodeId)
        Behaviors.receiveMessagePartial[Command] {
          case AddOrRejectChannel(replyTo, _, _) =>
            replyTo ! ChannelRateLimited
            Behaviors.same
          case WrappedGetNodeResponse(_, PublicNode(announcement, _, _), _) =>
            restoring(channels.tail, pendingPublicNodeChannels + (announcement.nodeId -> pendingChannels.map(_.channelId)), pendingPrivateNodeChannels)
          case WrappedGetNodeResponse(_, UnknownNode(nodeId), _) =>
            restoring(channels.tail, pendingPublicNodeChannels, pendingPrivateNodeChannels + (nodeId -> pendingChannels.map(_.channelId)))
          case CountOpenChannelRequests(replyTo, publicPeers) =>
            val pendingChannels = if (publicPeers) pendingPublicNodeChannels else pendingPrivateNodeChannels
            replyTo ! pendingChannels.map(_._2.length).sum
            Behaviors.same
        }
      case None =>
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelIdAssigned](c => ReplaceChannelId(c.remoteNodeId, c.temporaryChannelId, c.channelId)))
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelOpened](c => RemoveChannelId(c.remoteNodeId, c.channelId)))
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelClosed](c => RemoveChannelId(c.commitments.remoteNodeId, c.channelId)))
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelAborted](c => RemoveChannelId(c.remoteNodeId, c.channelId)))
        registering(pendingPublicNodeChannels, pendingPrivateNodeChannels)
    }
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
            replyTo ! AcceptOpenChannel
            registering(pendingPublicNodeChannels + (announcement.nodeId -> (temporaryChannelId +: peerChannels)), pendingPrivateNodeChannels)
          case None =>
            replyTo ! AcceptOpenChannel
            registering(pendingPublicNodeChannels + (announcement.nodeId -> Seq(temporaryChannelId)), pendingPrivateNodeChannels)
        }
      case WrappedGetNodeResponse(temporaryChannelId, UnknownNode(nodeId), Some(replyTo)) =>
        if (pendingPrivateNodeChannels.map(_._2.size).sum >= nodeParams.channelConf.maxTotalPendingChannelsPrivateNodes) {
          replyTo ! ChannelRateLimited
          Behaviors.same
        } else {
          replyTo ! AcceptOpenChannel
          pendingPrivateNodeChannels.get(nodeId) match {
            case Some(peerChannels) =>
              registering(pendingPublicNodeChannels, pendingPrivateNodeChannels + (nodeId -> (temporaryChannelId +: peerChannels)))
            case None =>
              registering(pendingPublicNodeChannels, pendingPrivateNodeChannels + (nodeId -> Seq(temporaryChannelId)))
          }
        }
      case ReplaceChannelId(remoteNodeId, temporaryChannelId, channelId) =>
        pendingPublicNodeChannels.get(remoteNodeId) match {
          case Some(channels) if channels.contains(temporaryChannelId) =>
            registering(pendingPublicNodeChannels + (remoteNodeId -> (channels.filterNot(_ == temporaryChannelId) :+ channelId)), pendingPrivateNodeChannels)
          case Some(_) => Behaviors.same
          case None =>
            pendingPrivateNodeChannels.get(remoteNodeId) match {
              case Some(channels) if channels.contains(temporaryChannelId) =>
                registering(pendingPublicNodeChannels, pendingPrivateNodeChannels + (remoteNodeId -> (channels.filterNot(_ == temporaryChannelId) :+ channelId)))
              case Some(_) => Behaviors.same
              case None => Behaviors.same
            }
        }
      case RemoveChannelId(remoteNodeId, channelId) =>
        pendingPublicNodeChannels.get(remoteNodeId) match {
          case Some(pendingChannels) =>
            val pendingChannels1 = pendingChannels.filterNot(_ == channelId)
            if (pendingChannels1.isEmpty) {
              registering(pendingPublicNodeChannels - remoteNodeId, pendingPrivateNodeChannels)
            } else {
              registering(pendingPublicNodeChannels + (remoteNodeId -> pendingChannels1), pendingPrivateNodeChannels)
            }
          case None =>
            pendingPrivateNodeChannels.get(remoteNodeId) match {
              case Some(pendingChannels) =>
                val pendingChannels1 = pendingChannels.filterNot(_ == channelId)
                if (pendingChannels1.isEmpty) {
                  registering(pendingPublicNodeChannels, pendingPrivateNodeChannels - remoteNodeId)
                } else {
                  registering(pendingPublicNodeChannels, pendingPrivateNodeChannels + (remoteNodeId -> pendingChannels1))
                }
              case None => Behaviors.same
            }
        }
      case CountOpenChannelRequests(replyTo, publicPeers) =>
        val pendingChannels = if (publicPeers) pendingPublicNodeChannels else pendingPrivateNodeChannels
        replyTo ! pendingChannels.map(_._2.length).sum
        Behaviors.same
    }
  }
}