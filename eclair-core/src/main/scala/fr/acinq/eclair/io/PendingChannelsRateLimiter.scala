package fr.acinq.eclair.io

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.PendingChannelsRateLimiter.Command
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.{GetNode, PublicNode, UnknownNode}

/**
 * A singleton actor that tracks pending channels and rate limits their creation.
 *
 * This actor should be initialized with the list of currently pending channels, keeps track of the number of pending
 * channels in real-time and applies the configured rate limits.
 *
 * It accepts the command AddOrRejectChannel and will respond with AcceptChannel or ChannelRateLimited. It also tracks
 * when channels are assigned a channel id or confirmed on-chain and will update its internal state accordingly.
 *
 */
object PendingChannelsRateLimiter {
  // @formatter:off
  sealed trait Command
  case class AddOrRejectChannel(replyTo: ActorRef[Response], remoteNodeId: PublicKey, temporaryChannelId: ByteVector32) extends Command
  private case class WrappedGetNodeResponse(temporaryChannelId: ByteVector32, response: Router.GetNodeResponse, replyTo: Option[ActorRef[Response]]) extends Command
  private case class ReplaceChannelId(remoteNodeId: PublicKey, temporaryChannelId: ByteVector32, channelId: ByteVector32) extends Command
  private case class RemoveChannelId(remoteNodeId: PublicKey, channelId: ByteVector32) extends Command

  sealed trait Response
  case object AcceptOpenChannel extends Response
  case object ChannelRateLimited extends Response
  // @formatter:on

  def apply(nodeParams: NodeParams, router: ActorRef[Any], channels: Seq[PersistentChannelData]): Behavior[Command] = {
    Behaviors.setup { context =>
      new PendingChannelsRateLimiter(nodeParams, router, context).restoring(filterPendingChannels(channels), Map(), Seq())
    }
  }

  private def filterPendingChannels(channels: Seq[PersistentChannelData]): Map[PublicKey, Seq[PersistentChannelData]] = {
    channels.filter {
      case _: DATA_WAIT_FOR_FUNDING_CONFIRMED => true
      case _: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED => true
      case _: DATA_WAIT_FOR_CHANNEL_READY => true
      case _: DATA_WAIT_FOR_DUAL_FUNDING_READY => true
      case _ => false
    }.groupBy(_.metaCommitments.remoteNodeId)
  }
}

private class PendingChannelsRateLimiter(nodeParams: NodeParams, router: ActorRef[Any], context: ActorContext[Command]) {
  import PendingChannelsRateLimiter._

  private def restoring(channels: Map[PublicKey, Seq[PersistentChannelData]], pendingPeerChannels: Map[PublicKey, Seq[ByteVector32]], pendingPrivateNodeChannels: Seq[ByteVector32]): Behavior[Command] = {
    channels.headOption match {
      case Some(d) =>
        val adapter = context.messageAdapter[Router.GetNodeResponse](r => WrappedGetNodeResponse(d._2.head.channelId, r, None))
        router ! GetNode(adapter, d._1)
        Behaviors.receiveMessagePartial[Command] {
          case WrappedGetNodeResponse(_, PublicNode(announcement, _, _), _) =>
            restoring(channels.tail, pendingPeerChannels + (announcement.nodeId -> d._2.map(_.channelId)), pendingPrivateNodeChannels)
          case WrappedGetNodeResponse(_, UnknownNode(_), _) =>
            restoring(channels.tail, pendingPeerChannels, pendingPrivateNodeChannels ++ d._2.map(_.channelId))
        }
      case None =>
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelIdAssigned](c => ReplaceChannelId(c.remoteNodeId, c.temporaryChannelId, c.channelId)))
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelOpened](c => RemoveChannelId(c.remoteNodeId, c.channelId)))
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelAborted](c => RemoveChannelId(c.remoteNodeId, c.channelId)))
        registering(pendingPeerChannels, pendingPrivateNodeChannels)
    }
  }

  private def registering(pendingPeerChannels: Map[PublicKey, Seq[ByteVector32]], pendingPrivateNodeChannels: Seq[ByteVector32]): Behavior[Command] = {
    Behaviors.receiveMessagePartial[Command] {
      case AddOrRejectChannel(replyTo, remoteNodeId, _) if nodeParams.channelConf.channelOpenerWhitelist.contains(remoteNodeId) =>
        replyTo ! AcceptOpenChannel
        Behaviors.same
      case AddOrRejectChannel(replyTo, remoteNodeId, temporaryChannelId) =>
        val adapter = context.messageAdapter[Router.GetNodeResponse](r => WrappedGetNodeResponse(temporaryChannelId, r, Some(replyTo)))
        router ! GetNode(adapter, remoteNodeId)
        Behaviors.same
      case WrappedGetNodeResponse(temporaryChannelId, PublicNode(announcement, _, _), Some(replyTo)) =>
        pendingPeerChannels.get(announcement.nodeId) match {
          case Some(pendingChannels) if pendingChannels.size >= nodeParams.channelConf.maxPendingChannelsPerPeer =>
            replyTo ! ChannelRateLimited
            Behaviors.same
          case Some(peerChannels) =>
            replyTo ! AcceptOpenChannel
            registering(pendingPeerChannels + (announcement.nodeId -> (temporaryChannelId +: peerChannels)), pendingPrivateNodeChannels)
          case None =>
            replyTo ! AcceptOpenChannel
            registering(Map(announcement.nodeId -> Seq(temporaryChannelId)), pendingPrivateNodeChannels)
        }
      case WrappedGetNodeResponse(temporaryChannelId, UnknownNode(_), Some(replyTo)) =>
        if (pendingPrivateNodeChannels.size >= nodeParams.channelConf.maxTotalPendingChannelsPrivateNodes) {
          replyTo ! ChannelRateLimited
          Behaviors.same
        } else {
          replyTo ! AcceptOpenChannel
          registering(pendingPeerChannels, pendingPrivateNodeChannels :+ temporaryChannelId)
        }
      case ReplaceChannelId(remoteNodeId, temporaryChannelId, channelId) =>
        pendingPeerChannels.get(remoteNodeId) match {
          case Some(channels) => registering(pendingPeerChannels + (remoteNodeId -> (channels.filterNot(_ == temporaryChannelId) :+ channelId)), pendingPrivateNodeChannels)
          case None => registering(pendingPeerChannels, pendingPrivateNodeChannels.filterNot(_ == temporaryChannelId) :+ channelId)
        }
      case RemoveChannelId(remoteNodeId, channelId) =>
        pendingPeerChannels.get(remoteNodeId) match {
          case Some(pendingChannels) => registering(pendingPeerChannels + (remoteNodeId -> pendingChannels.filterNot(_ == channelId)), pendingPrivateNodeChannels)
          case None => registering(pendingPeerChannels, pendingPrivateNodeChannels.filterNot(_ == channelId))
        }
    }
  }

}