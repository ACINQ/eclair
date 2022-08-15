/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.router

import akka.Done
import akka.actor.{ActorRef, Props}
import akka.event.Logging.MDC
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.RealShortChannelId
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Logs, ShortChannelId, getSimpleClassName}
import kamon.Kamon
import kamon.metric.Counter

import scala.collection.immutable.SortedMap
import scala.concurrent.Promise

class FrontRouter(routerConf: RouterConf, remoteRouter: ActorRef, initialized: Option[Promise[Done]] = None) extends FSMDiagnosticActorLogging[FrontRouter.State, FrontRouter.Data] {

  import FrontRouter._

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog: LoggingAdapter = log

  remoteRouter ! GetRoutingStateStreaming

  startWith(SYNCING, Data(Map.empty, SortedMap.empty, Map.empty, Map.empty, rebroadcast = Rebroadcast(channels = Map.empty, updates = Map.empty, nodes = Map.empty)))

  when(SYNCING) {
    case Event(networkEvent: NetworkEvent, d) =>
      stay() using FrontRouter.updateTable(d, networkEvent, doRebroadcast = false)

    case Event(RoutingStateStreamingUpToDate, d) =>
      log.info("sync done nodes={} channels={}", d.nodes.size, d.channels.size)
      initialized.map(_.success(Done))
      startTimerWithFixedDelay(TickBroadcast.toString, TickBroadcast, routerConf.routerBroadcastInterval)
      goto(NORMAL) using d
  }

  when(NORMAL) {
    case Event(GetRoutingState, d) =>
      log.info(s"getting valid announcements for ${sender()}")
      sender() ! RoutingState(d.channels.values, d.nodes.values)
      stay()

    case Event(s: SendChannelQuery, _) =>
      remoteRouter forward s
      stay()

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, q: QueryChannelRange), d) =>
      Sync.handleQueryChannelRange(d.channels, routerConf, RemoteGossip(peerConnection, remoteNodeId), q)
      stay()

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, q: QueryShortChannelIds), d) =>
      Sync.handleQueryShortChannelIds(d.nodes, d.channels, RemoteGossip(peerConnection, remoteNodeId), q)
      stay()

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, ann: AnnouncementMessage), d) =>
      val origin = RemoteGossip(peerConnection, remoteNodeId)
      val d1 = d.processing.get(ann) match {
        case Some(origins) if origins.contains(origin) =>
          log.warning("acking duplicate msg={}", ann)
          origin.peerConnection ! TransportHandler.ReadAck(ann)
          d
        case Some(origins) =>
          log.debug("message is already in processing={} origins.size={}", ann, origins.size)
          Metrics.gossipStashed(ann).increment()
          // we have already forwarded that message to the router
          // we could just acknowledge the message now, but then we would lose the information that we did receive this
          // announcement from that peer, and would send it back to that same peer if our master router accepts it
          // in the general case, we should fairly often receive the same gossip from several peers almost at the same time
          val origins1 = origins + origin
          d.copy(processing = d.processing + (ann -> origins1))
        case None =>
          d.accepted.get(ann) match {
            case Some(origins) if origins.contains(origin) =>
              log.warning("acking duplicate msg={}", ann)
              origin.peerConnection ! TransportHandler.ReadAck(ann)
              d
            case Some(origins) =>
              log.debug("message is already in accepted={} origins.size={}", ann, origins.size)
              val origins1 = origins + origin
              d.copy(accepted = d.accepted + (ann -> origins1))
            case None =>
              ann match {
                case n: NodeAnnouncement if d.nodes.contains(n.nodeId) =>
                  origin.peerConnection ! TransportHandler.ReadAck(ann)
                  Metrics.gossipDropped(ann).increment()
                  d
                case c: ChannelAnnouncement if d.channels.contains(c.shortChannelId) =>
                  origin.peerConnection ! TransportHandler.ReadAck(ann)
                  Metrics.gossipDropped(ann).increment()
                  d
                case u: ChannelUpdate if d.channels.get(RealShortChannelId(u.shortChannelId.toLong)).exists(_.getChannelUpdateSameSideAs(u).contains(u)) =>
                  origin.peerConnection ! TransportHandler.ReadAck(ann)
                  Metrics.gossipDropped(ann).increment()
                  d
                case n: NodeAnnouncement if d.rebroadcast.nodes.contains(n) =>
                  origin.peerConnection ! TransportHandler.ReadAck(ann)
                  Metrics.gossipStashedRebroadcast(ann).increment()
                  d.copy(rebroadcast = d.rebroadcast.copy(nodes = d.rebroadcast.nodes + (n -> (d.rebroadcast.nodes(n) + origin))))
                case c: ChannelAnnouncement if d.rebroadcast.channels.contains(c) =>
                  origin.peerConnection ! TransportHandler.ReadAck(ann)
                  Metrics.gossipStashedRebroadcast(ann).increment()
                  d.copy(rebroadcast = d.rebroadcast.copy(channels = d.rebroadcast.channels + (c -> (d.rebroadcast.channels(c) + origin))))
                case u: ChannelUpdate if d.rebroadcast.updates.contains(u) =>
                  origin.peerConnection ! TransportHandler.ReadAck(ann)
                  Metrics.gossipStashedRebroadcast(ann).increment()
                  d.copy(rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> (d.rebroadcast.updates(u) + origin))))
                case _ =>
                  Metrics.gossipForwarded(ann).increment()
                  log.debug("sending announcement class={} to master router", ann.getClass.getSimpleName)
                  remoteRouter ! PeerRoutingMessage(self, remoteNodeId, ann) // nb: we set ourselves as the origin
                  d.copy(processing = d.processing + (ann -> Set(origin)))
              }
          }
      }
      stay() using d1

    case Event(accepted: GossipDecision.Accepted, d) =>
      log.debug("message has been accepted by router: {}", accepted)
      Metrics.gossipAccepted(accepted.ann).increment()
      d.processing.get(accepted.ann) match {
        case Some(origins) => origins.foreach { origin =>
          log.debug("acking msg={} for origin={}", accepted.ann, origin)
          origin.peerConnection ! TransportHandler.ReadAck(accepted.ann)
          origin.peerConnection ! accepted
        }
        case None => ()
      }
      // NB: we will also shortly receive a NetworkEvent from the router for this announcement, where we put it in the
      // rebroadcast map. We keep the origin peers in the accepted map, so we don't send back the same announcement to
      // the peers that sent us in the first place.
      // Why do we not just leave the announcement in the processing map? Because we would have a race between other
      // peers that send us that same announcement, and the NetworkEvent. If the other peers win the race, we will defer
      // acknowledging their message (because the announcement is still in the processing map) and we will
      // wait forever for the very gossip decision that we are processing now, resulting in a stuck connection
      val origins1 = d.processing.getOrElse(accepted.ann, Set.empty[RemoteGossip])
      stay() using d.copy(processing = d.processing - accepted.ann, accepted = d.accepted + (accepted.ann -> origins1))

    case Event(rejected: GossipDecision.Rejected, d) =>
      log.debug("message has been rejected by router: {}", rejected)
      Metrics.gossipRejected(rejected.ann, rejected).increment()
      d.processing.get(rejected.ann) match {
        case Some(origins) => origins.foreach { origin =>
          log.debug("acking msg={} for origin={}", rejected.ann, origin)
          origin.peerConnection ! TransportHandler.ReadAck(rejected.ann)
          origin.peerConnection ! rejected
        }
        case None => ()
      }
      stay() using d.copy(processing = d.processing - rejected.ann)

    case Event(networkEvent: NetworkEvent, d) =>
      log.debug("received event={}", networkEvent)
      Metrics.routerEvent(networkEvent).increment()
      stay() using FrontRouter.updateTable(d, networkEvent, doRebroadcast = true)

    case Event(TickBroadcast, d) =>
      if (d.rebroadcast.channels.isEmpty && d.rebroadcast.updates.isEmpty && d.rebroadcast.nodes.isEmpty) {
        stay()
      } else {
        log.debug("broadcasting routing messages")
        log.debug("staggered broadcast details: channels={} updates={} nodes={}", d.rebroadcast.channels.size, d.rebroadcast.updates.size, d.rebroadcast.nodes.size)
        context.system.eventStream.publish(d.rebroadcast)
        stay() using d.copy(rebroadcast = Rebroadcast(channels = Map.empty, updates = Map.empty, nodes = Map.empty))
      }

    case Event(msg: PeerRoutingMessage, _) =>
      log.debug("forwarding peer routing message class={}", msg.message.getClass.getSimpleName)
      remoteRouter forward msg
      stay()

    case Event(_: TransportHandler.ReadAck, _) => stay() // acks from remote router
  }

  override def mdc(currentMessage: Any): MDC = {
    val category_opt = LogCategory(currentMessage)
    currentMessage match {
      case PeerRoutingMessage(_, remoteNodeId, _) => Logs.mdc(category_opt, remoteNodeId_opt = Some(remoteNodeId))
      case _ => Logs.mdc(category_opt)
    }
  }
}

object FrontRouter {

  def props(routerConf: RouterConf, remoteRouter: ActorRef, initialized: Option[Promise[Done]] = None): Props = Props(new FrontRouter(routerConf: RouterConf, remoteRouter: ActorRef, initialized))

  // @formatter:off
  sealed trait State
  case object SYNCING extends State
  case object NORMAL extends State
  // @formatter:on

  case class Data(nodes: Map[PublicKey, NodeAnnouncement],
                  channels: SortedMap[RealShortChannelId, PublicChannel],
                  processing: Map[AnnouncementMessage, Set[RemoteGossip]],
                  accepted: Map[AnnouncementMessage, Set[RemoteGossip]],
                  rebroadcast: Rebroadcast)

  object Metrics {
    private val Gossip = Kamon.counter("front.router.gossip")
    private val GossipResult = Kamon.counter("front.router.gossip.result")
    private val RouterEvent = Kamon.counter("front.router.event")

    // @formatter:off
    def gossipDropped(ann: AnnouncementMessage): Counter = Gossip.withTag("status", "dropped").withTag("type", getSimpleClassName(ann))
    def gossipStashed(ann: AnnouncementMessage): Counter = Gossip.withTag("status", "stashed").withTag("type", getSimpleClassName(ann))
    def gossipStashedRebroadcast(ann: AnnouncementMessage): Counter = Gossip.withTag("status", "stashed-rebroadcast").withTag("type", getSimpleClassName(ann))
    def gossipForwarded(ann: AnnouncementMessage): Counter = Gossip.withTag("status", "forwarded").withTag("type", getSimpleClassName(ann))

    def gossipAccepted(ann: AnnouncementMessage): Counter = GossipResult.withTag("result", "accepted").withTag("type", getSimpleClassName(ann))
    def gossipRejected(ann: AnnouncementMessage, reason: GossipDecision.Rejected): Counter = GossipResult.withTag("result", "rejected").withTag("reason", getSimpleClassName(reason)).withTag("type", getSimpleClassName(ann))

    def routerEvent(event: NetworkEvent): Counter = RouterEvent.withTag("type", getSimpleClassName(event))
    // @formatter:on
  }

  def updateTable(d: Data, event: NetworkEvent, doRebroadcast: Boolean)(implicit log: LoggingAdapter): Data = {
    event match {
      case NodesDiscovered(nodes) =>
        log.debug("adding {} nodes", nodes.size)
        val nodes1 = nodes.map(n => n.nodeId -> n).toMap
        val d1 = d.copy(nodes = d.nodes ++ nodes1)
        if (doRebroadcast) {
          nodes.foldLeft(d1) { case (d, ann) => FrontRouter.rebroadcast(d, ann) }
        } else {
          d1
        }

      case NodeUpdated(n) =>
        log.debug("updating {} nodes", 1)
        val d1 = d.copy(nodes = d.nodes + (n.nodeId -> n))
        if (doRebroadcast) {
          FrontRouter.rebroadcast(d1, n)
        } else {
          d1
        }

      case NodeLost(nodeId) =>
        log.debug("removing {} nodes", 1)
        d.copy(nodes = d.nodes - nodeId)

      case ChannelsDiscovered(channels) =>
        log.debug("adding {} channels", channels.size)
        val channels1 = channels.foldLeft(SortedMap.empty[RealShortChannelId, PublicChannel]) {
          case (channels, sc) => channels + (sc.ann.shortChannelId -> PublicChannel(sc.ann, ByteVector32.Zeroes, sc.capacity, sc.u1_opt, sc.u2_opt, None))
        }
        val d1 = d.copy(channels = d.channels ++ channels1)
        if (doRebroadcast) {
          channels.foldLeft(d1) { case (d, sc) => FrontRouter.rebroadcast(d, sc.ann) }
        } else {
          d1
        }

      case ChannelLost(channelId) =>
        log.debug("removing {} channels", 1)
        d.copy(channels = d.channels - channelId)

      case ChannelUpdatesReceived(updates) =>
        log.debug("adding/updating {} channel_updates", updates.size)
        val channels1 = updates.foldLeft(d.channels) {
          case (channels, u) => channels.get(RealShortChannelId(u.shortChannelId.toLong)) match {
            case Some(c) => channels + (c.ann.shortChannelId -> c.updateChannelUpdateSameSideAs(u))
            case None => channels
          }
        }
        val d1 = d.copy(channels = channels1)
        if (doRebroadcast) {
          updates.foldLeft(d1) { case (d, ann) => FrontRouter.rebroadcast(d, ann) }
        } else {
          d1
        }

      case _: SyncProgress =>
        // we receive this as part of network events but it's useless
        d
    }
  }

  /**
   * Schedule accepted announcements for rebroadcasting to our peers.
   */
  def rebroadcast(d: Data, ann: AnnouncementMessage)(implicit log: LoggingAdapter): Data = {
    // We don't want to send back the announcement to the peer(s) that sent it to us in the first place. Announcements
    // that came from our peers are in the [[d.accepted]] map.
    val origins = d.accepted.getOrElse(ann, Set.empty[RemoteGossip]).map(o => o: GossipOrigin)
    val rebroadcast1 = ann match {
      case n: NodeAnnouncement => d.rebroadcast.copy(nodes = d.rebroadcast.nodes + (n -> origins))
      case c: ChannelAnnouncement => d.rebroadcast.copy(channels = d.rebroadcast.channels + (c -> origins))
      case u: ChannelUpdate =>
        if (d.channels.contains(RealShortChannelId(u.shortChannelId.toLong))) {
          d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> origins))
        } else {
          d.rebroadcast // private channel, we don't rebroadcast the channel_update
        }
    }
    d.copy(accepted = d.accepted - ann, rebroadcast = rebroadcast1)
  }
}