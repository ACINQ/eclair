package fr.acinq.eclair.io

import akka.actor.typed.delivery.DurableProducerQueue.TimestampMillis
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.ChannelCreated
import fr.acinq.eclair.io.Monitoring.Metrics
import fr.acinq.eclair.io.Peer.Disconnect

/**
 * A singleton actor that limits the total number of incoming connections from peers that do not have channels with us.
 *
 * When a new incoming connection request is received, the Switchboard should send an
 * [[IncomingConnectionsTracker.TrackIncomingConnection]] message.
 *
 * When the number of tracked peers exceeds `eclair.peer-connection.max-no-channels`, send [[Peer.Disconnect]] to
 * the tracked peer with the oldest incoming connection.
 *
 * When a tracked peer disconnects or adds a channel, we will stop tracking that peer.
 *
 * We do not need to track peers that disconnect because they will terminate if they have no channels.
 * Likewise, peers with channels will disconnect and terminate when their last channel closes.
 *
 * Note: Peers on the sync whitelist are not tracked.
*/
object IncomingConnectionsTracker {
  // @formatter:off
  sealed trait Command

  case class TrackIncomingConnection(remoteNodeId: PublicKey) extends Command
  private case class ForgetIncomingConnection(remoteNodeId: PublicKey) extends Command
  private[io] case class IncomingConnectionsCount(replyTo: ActorRef[Int]) extends Command
  // @formatter:on

  def apply(nodeParams: NodeParams, switchboard: ActorRef[Disconnect]): Behavior[Command] = {
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[PeerDisconnected](c => ForgetIncomingConnection(c.nodeId)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelCreated](c => ForgetIncomingConnection(c.remoteNodeId)))
      new IncomingConnectionsTracker(nodeParams, switchboard).tracking(Map())
    }
  }
}

private class IncomingConnectionsTracker(nodeParams: NodeParams, switchboard: ActorRef[Disconnect]) {
  import IncomingConnectionsTracker._

  private def tracking(incomingConnections: Map[PublicKey, TimestampMillis]): Behavior[Command] = {
    Metrics.IncomingConnectionsWithoutChannels.withoutTags().update(incomingConnections.size)
    Behaviors.receiveMessage {
      case TrackIncomingConnection(remoteNodeId) =>
        if (nodeParams.syncWhitelist.contains(remoteNodeId)) {
          Behaviors.same
        } else {
          if (incomingConnections.size >= nodeParams.peerConnectionConf.maxNoChannels) {
            Metrics.IncomingConnectionsDisconnected.withoutTags().increment()
            val oldest = incomingConnections.minBy(_._2)._1
            switchboard ! Disconnect(oldest)
            tracking(incomingConnections + (remoteNodeId -> System.currentTimeMillis()) - oldest)
          }
          else {
            tracking(incomingConnections + (remoteNodeId -> System.currentTimeMillis()))
          }
        }
      case ForgetIncomingConnection(remoteNodeId) => tracking(incomingConnections - remoteNodeId)
      case IncomingConnectionsCount(replyTo) =>
        replyTo ! incomingConnections.size
        Behaviors.same
    }
  }

}