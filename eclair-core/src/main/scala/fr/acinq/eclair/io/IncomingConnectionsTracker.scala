package fr.acinq.eclair.io

import akka.actor.typed.delivery.DurableProducerQueue.TimestampMillis
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.{Logs, NodeParams}
import fr.acinq.eclair.channel.ChannelOpened
import fr.acinq.eclair.io.IncomingConnectionsTracker.Command
import fr.acinq.eclair.io.Monitoring.Metrics
import fr.acinq.eclair.io.Peer.{Disconnect, DisconnectResponse}

/**
 * A singleton actor that limits the total number of incoming connections from peers that do not have channels with us.
 *
 * When a new incoming connection request is received, the Switchboard should send an
 * [[IncomingConnectionsTracker.TrackIncomingConnection]] message.
 *
 * When the number of tracked peers exceeds `eclair.peer-connection.max-no-channels`, send [[Peer.Disconnect]] to
 * the tracked peer with the oldest incoming connection.
 *
 * When a tracked peer disconnects or confirms a channel, we will stop tracking that peer.
 *
 * We do not need to track peers that disconnect because they will terminate if they have no channels.
 * Likewise, peers with channels will disconnect and terminate when their last channel closes.
 *
 * Note: Peers on the sync whitelist are not tracked.
 *
 * This rate-limiting can be abused by attackers to prevent us from accepting channels from unknown peers: attackers can
 * create a continuous stream of incoming connections with random nodeIds, which forces us to constantly disconnect old
 * connections before they have the opportunity to open a channel. This can be fixed by adding a TCP rate-limiter that
 * rejects connections based on IP addresses, which forces the attacker to own a lot of IP addresses.
*/
object IncomingConnectionsTracker {
  // @formatter:off
  sealed trait Command

  case class TrackIncomingConnection(remoteNodeId: PublicKey) extends Command
  private[io] case class ForgetIncomingConnection(remoteNodeId: PublicKey) extends Command
  private[io] case class CountIncomingConnections(replyTo: ActorRef[Int]) extends Command
  // @formatter:on

  def apply(nodeParams: NodeParams, switchboard: ActorRef[Disconnect]): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(category_opt = Some(LogCategory.CONNECTION))) {
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[PeerDisconnected](c => ForgetIncomingConnection(c.nodeId)))
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelOpened](c => ForgetIncomingConnection(c.remoteNodeId)))
        new IncomingConnectionsTracker(nodeParams, switchboard, context).tracking(Map.empty)
      }
    }
  }
}

private class IncomingConnectionsTracker(nodeParams: NodeParams, switchboard: ActorRef[Disconnect], context: ActorContext[Command]) {
  import IncomingConnectionsTracker._

  private def tracking(incomingConnections: Map[PublicKey, TimestampMillis]): Behavior[Command] = {
    Metrics.IncomingConnectionsNoChannels.withoutTags().update(incomingConnections.size)
    Behaviors.receiveMessage {
      case TrackIncomingConnection(remoteNodeId) =>
        if (nodeParams.syncWhitelist.contains(remoteNodeId)) {
          Behaviors.same
        } else {
          if (incomingConnections.size >= nodeParams.peerConnectionConf.maxNoChannels) {
            Metrics.IncomingConnectionsDisconnected.withoutTags().increment()
            val oldest = incomingConnections.minBy(_._2)._1
            context.log.warn(s"disconnecting peer=$oldest, too many incoming connections from peers without channels.")
            switchboard ! Disconnect(oldest, Some(context.system.ignoreRef[DisconnectResponse]))
            tracking(incomingConnections + (remoteNodeId -> System.currentTimeMillis()) - oldest)
          }
          else {
            tracking(incomingConnections + (remoteNodeId -> System.currentTimeMillis()))
          }
        }
      case ForgetIncomingConnection(remoteNodeId) => tracking(incomingConnections - remoteNodeId)
      case CountIncomingConnections(replyTo) =>
        replyTo ! incomingConnections.size
        Behaviors.same
    }
  }

}