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
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable, Props, Terminated, typed}
import akka.event.DiagnosticLoggingAdapter
import akka.event.Logging.MDC
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{ValidateResult, WatchExternalChannelSpent, WatchExternalChannelSpentTriggered}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.payment.Invoice.ExtraEdge
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.payment.send.Recipient
import fr.acinq.eclair.payment.{Bolt11Invoice, Invoice}
import fr.acinq.eclair.remote.EclairInternalsSerializer.RemoteTypes
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph
import fr.acinq.eclair.router.Graph.{HeuristicsConstants, WeightRatios}
import fr.acinq.eclair.router.Monitoring.Metrics
import fr.acinq.eclair.wire.protocol._

import java.util.UUID
import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Random, Try}

/**
 * Created by PM on 24/05/2016.
 */
class Router(val nodeParams: NodeParams, watcher: typed.ActorRef[ZmqWatcher.Command], initialized: Option[Promise[Done]] = None) extends FSMDiagnosticActorLogging[Router.State, Router.Data] {

  import Router._

  import ExecutionContext.Implicits.global

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog: DiagnosticLoggingAdapter = diagLog

  context.system.eventStream.subscribe(self, classOf[ShortChannelIdAssigned])
  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])
  context.system.eventStream.subscribe(self, classOf[AvailableBalanceChanged])
  context.system.eventStream.publish(SubscriptionsComplete(this.getClass))

  startTimerWithFixedDelay(TickBroadcast.toString, TickBroadcast, nodeParams.routerConf.routerBroadcastInterval)
  startTimerWithFixedDelay(TickPruneStaleChannels.toString, TickPruneStaleChannels, 1 hour)

  val db: NetworkDb = nodeParams.db.network

  {
    log.info("loading network announcements from db...")
    val (pruned, channels) = db.listChannels().partition { case (_, pc) => pc.isStale(nodeParams.currentBlockHeight) }
    val nodes = db.listNodes().map(n => n.nodeId -> n).toMap
    Metrics.Nodes.withoutTags().update(nodes.size)
    Metrics.Channels.withoutTags().update(channels.size)
    log.info("loaded from db: channels={} nodes={}", channels.size, nodes.size)
    log.info("{} pruned channels at blockHeight={}", pruned.size, nodeParams.currentBlockHeight)
    // this will be used to calculate routes
    val graph = DirectedGraph.makeGraph(channels)
    // send events for remaining channels/nodes
    context.system.eventStream.publish(ChannelsDiscovered(channels.values.map(pc => SingleChannelDiscovered(pc.ann, pc.capacity, pc.update_1_opt, pc.update_2_opt))))
    context.system.eventStream.publish(ChannelUpdatesReceived(channels.values.flatMap(pc => pc.update_1_opt ++ pc.update_2_opt ++ Nil)))
    context.system.eventStream.publish(NodesDiscovered(nodes.values))

    // watch the funding tx of all these channels
    // note: some of them may already have been spent, in that case we will receive the watch event immediately
    (channels.values ++ pruned.values).foreach { pc =>
      val txid = pc.fundingTxid
      val outputIndex = ShortChannelId.coordinates(pc.ann.shortChannelId).outputIndex
      // avoid herd effect at startup because watch-spent are intensive in terms of rpc calls to bitcoind
      context.system.scheduler.scheduleOnce(Random.nextLong(nodeParams.routerConf.watchSpentWindow.toSeconds).seconds) {
        watcher ! WatchExternalChannelSpent(self, txid, outputIndex, pc.ann.shortChannelId)
      }
    }

    // on restart we update our node announcement
    // note that if we don't currently have public channels, this will be ignored
    val nodeAnn = Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.publicAddresses, nodeParams.features.nodeAnnouncementFeatures())
    self ! nodeAnn

    log.info(s"initialization completed, ready to process messages")
    Try(initialized.map(_.success(Done)))
    val data = Data(
      nodes, channels, pruned,
      Stash(Map.empty, Map.empty),
      rebroadcast = Rebroadcast(channels = Map.empty, updates = Map.empty, nodes = Map.empty),
      awaiting = Map.empty,
      privateChannels = Map.empty,
      scid2PrivateChannels = Map.empty,
      excludedChannels = Map.empty,
      graphWithBalances = GraphWithBalanceEstimates(graph, nodeParams.routerConf.balanceEstimateHalfLife),
      sync = Map.empty)
    startWith(NORMAL, data)
  }

  when(NORMAL) {

    case Event(SyncProgress(progress), d: Data) =>
      Metrics.SyncProgress.withoutTags().update(100 * progress)
      if (progress == 1.0 && d.channels.nonEmpty) {
        log.info("initial routing sync done")
      }
      stay()

    case Event(GetRoutingState, d: Data) =>
      log.info(s"getting valid announcements for ${sender()}")
      sender() ! RoutingState(d.channels.values, d.nodes.values)
      stay()

    case Event(GetRoutingStateStreaming, d) =>
      val listener = sender()
      d.nodes
        .values
        .sliding(100, 100)
        .map(NodesDiscovered)
        .foreach(listener ! _)
      d.channels
        .values
        .map(pc => SingleChannelDiscovered(pc.ann, pc.capacity, pc.update_1_opt, pc.update_2_opt))
        .sliding(100, 100)
        .map(ChannelsDiscovered)
        .foreach(listener ! _)
      listener ! RoutingStateStreamingUpToDate
      context.actorOf(Props(new Actor with ActorLogging {
        log.debug(s"subscribing listener=$listener to network events")
        context.system.eventStream.subscribe(listener, classOf[NetworkEvent])
        context.watch(listener)

        override def receive: Receive = {
          case Terminated(actor) if actor == listener =>
            log.warning(s"unsubscribing listener=$listener to network events")
            context.system.eventStream.unsubscribe(listener)
            context stop self
        }
      }))
      stay()

    case Event(TickBroadcast, d) =>
      if (d.rebroadcast.channels.isEmpty && d.rebroadcast.updates.isEmpty && d.rebroadcast.nodes.isEmpty) {
        stay()
      } else {
        log.debug("staggered broadcast details: channels={} updates={} nodes={}", d.rebroadcast.channels.size, d.rebroadcast.updates.size, d.rebroadcast.nodes.size)
        context.system.eventStream.publish(d.rebroadcast)
        stay() using d.copy(rebroadcast = Rebroadcast(channels = Map.empty, updates = Map.empty, nodes = Map.empty))
      }

    case Event(TickPruneStaleChannels, d) =>
      stay() using StaleChannels.handlePruneStaleChannels(d, nodeParams.db.network, nodeParams.currentBlockHeight)

    case Event(ExcludeChannel(desc, duration_opt), d) =>
      log.info("excluding shortChannelId={} from nodeId={} for duration={}", desc.shortChannelId, desc.a, duration_opt.getOrElse("n/a"))
      val (excludedUntil, oldTimer) = d.excludedChannels.get(desc) match {
        case Some(ExcludedForever) => (TimestampSecond.max, None)
        case Some(ExcludedUntil(liftExclusionAt, timer)) => (liftExclusionAt, Some(timer))
        case None => (TimestampSecond.min, None)
      }
      val newState = duration_opt match {
        case Some(banDuration) =>
          if (TimestampSecond.now() + banDuration > excludedUntil) {
            oldTimer.foreach(_.cancel())
            val newTimer = context.system.scheduler.scheduleOnce(banDuration, self, LiftChannelExclusion(desc))
            ExcludedUntil(TimestampSecond.now() + banDuration, newTimer)
          } else {
            d.excludedChannels(desc)
          }
        case None =>
          oldTimer.foreach(_.cancel())
          ExcludedForever
      }
      stay() using d.copy(excludedChannels = d.excludedChannels + (desc -> newState))

    case Event(LiftChannelExclusion(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      log.info("reinstating shortChannelId={} from nodeId={}", shortChannelId, nodeId)
      stay() using d.copy(excludedChannels = d.excludedChannels - desc)

    case Event(GetExcludedChannels, d) =>
      sender() ! d.excludedChannels
      stay()

    case Event(GetNode(replyTo, nodeId), d) =>
      d.nodes.get(nodeId) match {
        case Some(announcement) =>
          // This only provides a lower bound on the number of channels this peer has: disabled channels will be filtered out.
          val activeChannels = d.graphWithBalances.graph.getIncomingEdgesOf(nodeId)
          val totalCapacity = activeChannels.map(_.capacity).sum
          replyTo ! PublicNode(announcement, activeChannels.size, totalCapacity)
        case None =>
          replyTo ! UnknownNode(nodeId)
      }
      stay()

    case Event(GetNodes, d) =>
      sender() ! d.nodes.values
      stay()

    case Event(GetChannels, d) =>
      sender() ! d.channels.values.map(_.ann)
      stay()

    case Event(GetChannelsMap, d) =>
      sender() ! d.channels
      stay()

    case Event(GetChannelUpdates, d) =>
      val updates: Iterable[ChannelUpdate] = d.channels.values.flatMap(d => d.update_1_opt ++ d.update_2_opt) ++ d.privateChannels.values.flatMap(d => d.update_1_opt ++ d.update_2_opt)
      sender() ! updates
      stay()

    case Event(GetRouterData, d) =>
      sender() ! d
      stay()

    case Event(fr: FinalizeRoute, d) =>
      stay() using RouteCalculation.finalizeRoute(d, nodeParams.nodeId, fr)

    case Event(r: RouteRequest, d) =>
      stay() using RouteCalculation.handleRouteRequest(d, nodeParams.currentBlockHeight, r)

    // Warning: order matters here, this must be the first match for HasChainHash messages !
    case Event(PeerRoutingMessage(_, _, routingMessage: HasChainHash), _) if routingMessage.chainHash != nodeParams.chainHash =>
      sender() ! TransportHandler.ReadAck(routingMessage)
      log.warning("message {} for wrong chain {}, we're on {}", routingMessage, routingMessage.chainHash, nodeParams.chainHash)
      stay()

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, c: ChannelAnnouncement), d) =>
      stay() using Validation.handleChannelAnnouncement(d, watcher, RemoteGossip(peerConnection, remoteNodeId), c)

    case Event(r: ValidateResult, d) =>
      stay() using Validation.handleChannelValidationResponse(d, nodeParams, watcher, r)

    case Event(WatchExternalChannelSpentTriggered(shortChannelId), d) if d.channels.contains(shortChannelId) || d.prunedChannels.contains(shortChannelId) =>
      stay() using Validation.handleChannelSpent(d, nodeParams.db.network, shortChannelId)

    case Event(n: NodeAnnouncement, d: Data) =>
      stay() using Validation.handleNodeAnnouncement(d, nodeParams.db.network, Set(LocalGossip), n)

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, n: NodeAnnouncement), d: Data) =>
      stay() using Validation.handleNodeAnnouncement(d, nodeParams.db.network, Set(RemoteGossip(peerConnection, remoteNodeId)), n)

    case Event(scia: ShortChannelIdAssigned, d) =>
      stay() using Validation.handleShortChannelIdAssigned(d, nodeParams.nodeId, scia)

    case Event(u: ChannelUpdate, d: Data) => // from payment lifecycle
      stay() using Validation.handleChannelUpdate(d, nodeParams.db.network, nodeParams.currentBlockHeight, Right(RemoteChannelUpdate(u, Set(LocalGossip))))

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, u: ChannelUpdate), d) => // from network (gossip or peer)
      stay() using Validation.handleChannelUpdate(d, nodeParams.db.network, nodeParams.currentBlockHeight, Right(RemoteChannelUpdate(u, Set(RemoteGossip(peerConnection, remoteNodeId)))))

    case Event(lcu: LocalChannelUpdate, d: Data) => // from local channel
      stay() using Validation.handleLocalChannelUpdate(d, nodeParams, watcher, lcu)

    case Event(lcd: LocalChannelDown, d: Data) =>
      stay() using Validation.handleLocalChannelDown(d, nodeParams.nodeId, lcd)

    case Event(e: AvailableBalanceChanged, d: Data) =>
      stay() using Validation.handleAvailableBalanceChanged(d, e)

    case Event(s: SendChannelQuery, d) =>
      stay() using Sync.handleSendChannelQuery(d, s)

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, q: QueryChannelRange), d) =>
      Sync.handleQueryChannelRange(d.channels, nodeParams.routerConf, RemoteGossip(peerConnection, remoteNodeId), q)
      stay()

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, r: ReplyChannelRange), d) =>
      stay() using Sync.handleReplyChannelRange(d, nodeParams.routerConf, RemoteGossip(peerConnection, remoteNodeId), r)

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, q: QueryShortChannelIds), d) =>
      Sync.handleQueryShortChannelIds(d.nodes, d.channels, RemoteGossip(peerConnection, remoteNodeId), q)
      stay()

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, r: ReplyShortChannelIdsEnd), d) =>
      stay() using Sync.handleReplyShortChannelIdsEnd(d, RemoteGossip(peerConnection, remoteNodeId), r)

    case Event(RouteCouldRelay(route), d) =>
      stay() using d.copy(graphWithBalances = d.graphWithBalances.routeCouldRelay(route))

    case Event(RouteDidRelay(route), d) =>
      stay() using d.copy(graphWithBalances = d.graphWithBalances.routeDidRelay(route))

    case Event(ChannelCouldNotRelay(amount, hop), d) =>
      stay() using d.copy(graphWithBalances = d.graphWithBalances.channelCouldNotSend(hop, amount))
  }

  initialize()

  override def mdc(currentMessage: Any): MDC = {
    val category_opt = LogCategory(currentMessage)
    val (remoteNodeId_opt, channelId_opt) = currentMessage match {
      case s: SendChannelQuery => (Some(s.remoteNodeId), None)
      case prm: PeerRoutingMessage => (Some(prm.remoteNodeId), None)
      case sca: ShortChannelIdAssigned => (Some(sca.remoteNodeId), Some(sca.channelId))
      case lcu: LocalChannelUpdate => (Some(lcu.remoteNodeId), Some(lcu.channelId))
      case lcd: LocalChannelDown => (Some(lcd.remoteNodeId), Some(lcd.channelId))
      case abc: AvailableBalanceChanged => (Some(abc.commitments.remoteNodeId), Some(abc.channelId))
      case _ => (None, None)
    }
    Logs.mdc(category_opt, remoteNodeId_opt = remoteNodeId_opt, channelId_opt = channelId_opt, nodeAlias_opt = Some(nodeParams.alias))
  }
}

object Router {

  def props(nodeParams: NodeParams, watcher: typed.ActorRef[ZmqWatcher.Command], initialized: Option[Promise[Done]] = None) = Props(new Router(nodeParams, watcher, initialized))

  case class SearchBoundaries(maxFeeFlat: MilliSatoshi,
                              maxFeeProportional: Double,
                              maxRouteLength: Int,
                              maxCltv: CltvExpiryDelta)

  case class PathFindingConf(randomize: Boolean,
                             boundaries: SearchBoundaries,
                             heuristics: Either[WeightRatios, HeuristicsConstants],
                             mpp: MultiPartParams,
                             experimentName: String,
                             experimentPercentage: Int) {
    def getDefaultRouteParams: RouteParams = RouteParams(
      randomize = randomize,
      boundaries = boundaries,
      heuristics = heuristics,
      mpp = mpp,
      experimentName = experimentName,
      includeLocalChannelCost = false
    )
  }

  case class RouterConf(watchSpentWindow: FiniteDuration,
                        channelExcludeDuration: FiniteDuration,
                        routerBroadcastInterval: FiniteDuration,
                        requestNodeAnnouncements: Boolean,
                        encodingType: EncodingType,
                        channelRangeChunkSize: Int,
                        channelQueryChunkSize: Int,
                        pathFindingExperimentConf: PathFindingExperimentConf,
                        balanceEstimateHalfLife: FiniteDuration) {
    require(channelRangeChunkSize <= Sync.MAXIMUM_CHUNK_SIZE, "channel range chunk size exceeds the size of a lightning message")
    require(channelQueryChunkSize <= Sync.MAXIMUM_CHUNK_SIZE, "channel query chunk size exceeds the size of a lightning message")
  }

  // @formatter:off
  case class ChannelDesc private(shortChannelId: ShortChannelId, a: PublicKey, b: PublicKey)
  object ChannelDesc {
    def apply(u: ChannelUpdate, ann: ChannelAnnouncement): ChannelDesc = {
      // the least significant bit tells us if it is node1 or node2
      if (u.channelFlags.isNode1) ChannelDesc(ann.shortChannelId, ann.nodeId1, ann.nodeId2) else ChannelDesc(ann.shortChannelId, ann.nodeId2, ann.nodeId1)
    }
    def apply(u: ChannelUpdate, pc: PrivateChannel): ChannelDesc = {
      // the least significant bit tells us if it is node1 or node2
      if (u.channelFlags.isNode1) ChannelDesc(pc.shortIds.localAlias, pc.nodeId1, pc.nodeId2) else ChannelDesc(pc.shortIds.localAlias, pc.nodeId2, pc.nodeId1)
    }
  }
  case class ChannelMeta(balance1: MilliSatoshi, balance2: MilliSatoshi)
  sealed trait KnownChannel {
    val capacity: Satoshi
    val nodeId1: PublicKey
    val nodeId2: PublicKey
    def getNodeIdSameSideAs(u: ChannelUpdate): PublicKey
    def getChannelUpdateSameSideAs(u: ChannelUpdate): Option[ChannelUpdate]
    def getBalanceSameSideAs(u: ChannelUpdate): Option[MilliSatoshi]
    def updateChannelUpdateSameSideAs(u: ChannelUpdate): KnownChannel
    def updateBalances(commitments: Commitments): KnownChannel
    def applyChannelUpdate(update: Either[LocalChannelUpdate, RemoteChannelUpdate]): KnownChannel
  }
  case class PublicChannel(ann: ChannelAnnouncement, fundingTxid: ByteVector32, capacity: Satoshi, update_1_opt: Option[ChannelUpdate], update_2_opt: Option[ChannelUpdate], meta_opt: Option[ChannelMeta]) extends KnownChannel {
    update_1_opt.foreach(u => assert(u.channelFlags.isNode1))
    update_2_opt.foreach(u => assert(!u.channelFlags.isNode1))

    val nodeId1: PublicKey = ann.nodeId1
    val nodeId2: PublicKey = ann.nodeId2
    val shortChannelId: RealShortChannelId = ann.shortChannelId

    def isStale(currentBlockHeight: BlockHeight): Boolean = StaleChannels.isStale(ann, update_1_opt, update_2_opt, currentBlockHeight)
    def getNodeIdSameSideAs(u: ChannelUpdate): PublicKey = if (u.channelFlags.isNode1) ann.nodeId1 else ann.nodeId2
    def getChannelUpdateSameSideAs(u: ChannelUpdate): Option[ChannelUpdate] = if (u.channelFlags.isNode1) update_1_opt else update_2_opt
    def getBalanceSameSideAs(u: ChannelUpdate): Option[MilliSatoshi] = if (u.channelFlags.isNode1) meta_opt.map(_.balance1) else meta_opt.map(_.balance2)
    def updateChannelUpdateSameSideAs(u: ChannelUpdate): PublicChannel = if (u.channelFlags.isNode1) copy(update_1_opt = Some(u)) else copy(update_2_opt = Some(u))
    def updateBalances(commitments: Commitments): PublicChannel = if (commitments.localNodeId == ann.nodeId1) {
      copy(meta_opt = Some(ChannelMeta(commitments.availableBalanceForSend, commitments.availableBalanceForReceive)))
    } else {
      copy(meta_opt = Some(ChannelMeta(commitments.availableBalanceForReceive, commitments.availableBalanceForSend)))
    }
    def applyChannelUpdate(update: Either[LocalChannelUpdate, RemoteChannelUpdate]): PublicChannel = update match {
      case Left(lcu) => updateChannelUpdateSameSideAs(lcu.channelUpdate).updateBalances(lcu.commitments)
      case Right(rcu) => updateChannelUpdateSameSideAs(rcu.channelUpdate)
    }
  }
  case class PrivateChannel(channelId: ByteVector32, shortIds: ShortIds, localNodeId: PublicKey, remoteNodeId: PublicKey, update_1_opt: Option[ChannelUpdate], update_2_opt: Option[ChannelUpdate], meta: ChannelMeta) extends KnownChannel {
    val (nodeId1, nodeId2) = if (Announcements.isNode1(localNodeId, remoteNodeId)) (localNodeId, remoteNodeId) else (remoteNodeId, localNodeId)
    val capacity: Satoshi = (meta.balance1 + meta.balance2).truncateToSatoshi

    def getNodeIdSameSideAs(u: ChannelUpdate): PublicKey = if (u.channelFlags.isNode1) nodeId1 else nodeId2
    def getChannelUpdateSameSideAs(u: ChannelUpdate): Option[ChannelUpdate] = if (u.channelFlags.isNode1) update_1_opt else update_2_opt
    def getBalanceSameSideAs(u: ChannelUpdate): Option[MilliSatoshi] = if (u.channelFlags.isNode1) Some(meta.balance1) else Some(meta.balance2)
    def updateChannelUpdateSameSideAs(u: ChannelUpdate): PrivateChannel = if (u.channelFlags.isNode1) copy(update_1_opt = Some(u)) else copy(update_2_opt = Some(u))
    def updateBalances(commitments: Commitments): PrivateChannel = if (commitments.localNodeId == nodeId1) {
      copy(meta = ChannelMeta(commitments.availableBalanceForSend, commitments.availableBalanceForReceive))
    } else {
      copy(meta = ChannelMeta(commitments.availableBalanceForReceive, commitments.availableBalanceForSend))
    }
    def applyChannelUpdate(update: Either[LocalChannelUpdate, RemoteChannelUpdate]): PrivateChannel = update match {
      case Left(lcu) => updateChannelUpdateSameSideAs(lcu.channelUpdate).updateBalances(lcu.commitments)
      case Right(rcu) => updateChannelUpdateSameSideAs(rcu.channelUpdate)
    }
    /** Create an invoice routing hint from that channel. Note that if the channel is private, the invoice will leak its existence. */
    def toIncomingExtraHop: Option[Bolt11Invoice.ExtraHop] = {
      // we want the incoming channel_update
      val remoteUpdate_opt = if (localNodeId == nodeId1) update_2_opt else update_1_opt
      // for incoming payments we preferably use the *remote alias*, otherwise the real scid if we have it
      val scid_opt = shortIds.remoteAlias_opt.orElse(shortIds.real.toOption)
      // we override the remote update's scid, because it contains either the real scid or our local alias
      scid_opt.flatMap { scid =>
        remoteUpdate_opt.map { remoteUpdate =>
          Bolt11Invoice.ExtraHop(remoteNodeId, scid, remoteUpdate.feeBaseMsat, remoteUpdate.feeProportionalMillionths, remoteUpdate.cltvExpiryDelta)
        }
      }
    }
  }
  // @formatter:on

  sealed trait Hop {
    /** @return the id of the start node. */
    def nodeId: PublicKey

    /** @return the id of the end node. */
    def nextNodeId: PublicKey

    /**
     * @param amount amount to be forwarded.
     * @return total fee required by the current hop.
     */
    def fee(amount: MilliSatoshi): MilliSatoshi

    /** @return cltv delta required by the current hop. */
    def cltvExpiryDelta: CltvExpiryDelta
  }

  /** Routing parameters for relaying payments over a given hop. */
  sealed trait HopRelayParams {
    // @formatter:off
    def cltvExpiryDelta: CltvExpiryDelta
    def relayFees: Relayer.RelayFees
    final def fee(amount: MilliSatoshi): MilliSatoshi = nodeFee(relayFees, amount)
    def htlcMinimum: MilliSatoshi
    def htlcMaximum_opt: Option[MilliSatoshi]
    // @formatter:on
  }

  object HopRelayParams {
    /** We learnt about this channel from a channel_update. */
    case class FromAnnouncement(channelUpdate: ChannelUpdate) extends HopRelayParams {
      override val cltvExpiryDelta = channelUpdate.cltvExpiryDelta
      override val relayFees = channelUpdate.relayFees
      override val htlcMinimum = channelUpdate.htlcMinimumMsat
      override val htlcMaximum_opt = Some(channelUpdate.htlcMaximumMsat)
    }

    /** We learnt about this hop from hints in an invoice. */
    case class FromHint(extraHop: Invoice.ExtraEdge) extends HopRelayParams {
      override val cltvExpiryDelta = extraHop.cltvExpiryDelta
      override val relayFees = extraHop.relayFees
      override val htlcMinimum = extraHop.htlcMinimum
      override val htlcMaximum_opt = extraHop.htlcMaximum_opt
    }

    def areSame(a: HopRelayParams, b: HopRelayParams, ignoreHtlcSize: Boolean = false): Boolean =
      a.cltvExpiryDelta == b.cltvExpiryDelta &&
        a.relayFees == b.relayFees &&
        (ignoreHtlcSize || (a.htlcMinimum == b.htlcMinimum && a.htlcMaximum_opt == b.htlcMaximum_opt))
  }

  /**
   * A directed hop between two nodes connected by a channel.
   *
   * @param shortChannelId scid of the channel.
   * @param nodeId         id of the start node.
   * @param nextNodeId     id of the end node.
   * @param params         source for the channel parameters.
   */
  case class ChannelHop(shortChannelId: ShortChannelId, nodeId: PublicKey, nextNodeId: PublicKey, params: HopRelayParams) extends Hop {
    // @formatter:off
    override val cltvExpiryDelta = params.cltvExpiryDelta
    override def fee(amount: MilliSatoshi): MilliSatoshi = params.fee(amount)
    // @formatter:on
  }

  sealed trait FinalHop extends Hop

  /**
   * A directed hop over a blinded route composed of multiple (blinded) channels.
   * Since a blinded route has to be used from start to end, we model it as a single virtual hop.
   *
   * @param dummyId     dummy identifier to allow indexing in maps: unlike normal scid aliases, this one doesn't exist
   *                    in our routing tables and should be used carefully.
   * @param route       blinded route covered by that hop.
   * @param paymentInfo payment information about the blinded route.
   */
  case class BlindedHop(dummyId: Alias, route: BlindedRoute, paymentInfo: OfferTypes.PaymentInfo) extends FinalHop {
    // @formatter:off
    override val nodeId = route.introductionNodeId
    override val nextNodeId = route.blindedNodes.last.blindedPublicKey
    override val cltvExpiryDelta = paymentInfo.cltvExpiryDelta
    override def fee(amount: MilliSatoshi): MilliSatoshi = paymentInfo.fee(amount)
    // @formatter:on
  }

  /**
   * A directed hop between two trampoline nodes.
   * These nodes need not be connected and we don't need to know a route between them.
   * The start node will compute the route to the end node itself when it receives our payment.
   *
   * @param nodeId          id of the start node.
   * @param nextNodeId      id of the end node.
   * @param cltvExpiryDelta cltv expiry delta.
   * @param fee             total fee for that hop.
   */
  case class NodeHop(nodeId: PublicKey, nextNodeId: PublicKey, cltvExpiryDelta: CltvExpiryDelta, fee: MilliSatoshi) extends FinalHop {
    override def fee(amount: MilliSatoshi): MilliSatoshi = fee
  }

  case class MultiPartParams(minPartAmount: MilliSatoshi, maxParts: Int)

  case class RouteParams(randomize: Boolean,
                         boundaries: SearchBoundaries,
                         heuristics: Either[WeightRatios, HeuristicsConstants],
                         mpp: MultiPartParams,
                         experimentName: String,
                         includeLocalChannelCost: Boolean) {
    def getMaxFee(amount: MilliSatoshi): MilliSatoshi = {
      // The payment fee must satisfy either the flat fee or the proportional fee, not necessarily both.
      boundaries.maxFeeFlat.max(amount * boundaries.maxFeeProportional)
    }
  }

  case class Ignore(nodes: Set[PublicKey], channels: Set[ChannelDesc]) {
    // @formatter:off
    def +(ignoreNode: PublicKey): Ignore = copy(nodes = nodes + ignoreNode)
    def ++(ignoreNodes: Set[PublicKey]): Ignore = copy(nodes = nodes ++ ignoreNodes)
    def +(ignoreChannel: ChannelDesc): Ignore = copy(channels = channels + ignoreChannel)
    def emptyNodes(): Ignore = copy(nodes = Set.empty)
    def emptyChannels(): Ignore = copy(channels = Set.empty)
    // @formatter:on
  }

  object Ignore {
    def empty: Ignore = Ignore(Set.empty, Set.empty)
  }

  case class RouteRequest(source: PublicKey,
                          target: Recipient,
                          routeParams: RouteParams,
                          ignore: Ignore = Ignore.empty,
                          allowMultiPart: Boolean = false,
                          pendingPayments: Seq[Route] = Nil,
                          paymentContext: Option[PaymentContext] = None)

  case class FinalizeRoute(route: PredefinedRoute,
                           extraEdges: Seq[ExtraEdge] = Nil,
                           paymentContext: Option[PaymentContext] = None)

  /**
   * Useful for having appropriate logging context at hand when finding routes
   */
  case class PaymentContext(id: UUID, parentId: UUID, paymentHash: ByteVector32)

  case class Route(amount: MilliSatoshi, hops: Seq[ChannelHop], finalHop_opt: Option[FinalHop]) {
    require(hops.nonEmpty || finalHop_opt.nonEmpty, "route cannot be empty")

    /** Full route including the final hop, if any. */
    val fullRoute: Seq[Hop] = hops ++ finalHop_opt.toSeq

    /**
     * Fee paid for the trampoline hop, if any.
     * Note that when using MPP to reach the trampoline node, the trampoline fee must be counted only once.
     */
    val trampolineFee: MilliSatoshi = finalHop_opt.collect { case hop: NodeHop => hop.fee(amount) }.getOrElse(0 msat)

    /**
     * Fee paid for the blinded route, if any.
     * Note that when we are the introduction node for the blinded route, we cannot easily compute the fee without the
     * cost for the first local channel.
     */
    val blindedFee: MilliSatoshi = finalHop_opt.collect { case hop: BlindedHop => hop.fee(amount) }.getOrElse(0 msat)

    /** Fee paid for the channel hops towards the recipient or the source of the final hop, if any. */

    /**
     * Fee paid for the channel hops towards the recipient or the source of the final hop.
     * Note that this doesn't include the fees for the final hop, if one exits.
     */
    def channelFee(includeLocalChannelCost: Boolean): MilliSatoshi = {
      val hopsToPay = if (includeLocalChannelCost) hops else hops.drop(1)
      val amountToSend = hopsToPay.foldRight(amount) { case (hop, amount1) => amount1 + hop.fee(amount1) }
      amountToSend - amount
    }

    def printNodes(): String = hops.map(_.nextNodeId).mkString("->")

    def printChannels(): String = hops.map(_.shortChannelId).mkString("->")

    def stopAt(nodeId: PublicKey): Route = {
      val amountAtStop = hops.reverse.takeWhile(_.nextNodeId != nodeId).foldLeft(amount) { case (amount1, hop) => amount1 + hop.fee(amount1) }
      Route(amountAtStop, hops.takeWhile(_.nodeId != nodeId), None)
    }
  }

  case class RouteResponse(routes: Seq[Route]) {
    require(routes.nonEmpty, "routes cannot be empty")
  }

  // @formatter:off
  /** A pre-defined route chosen outside of eclair (e.g. manually by a user to do some re-balancing). */
  sealed trait PredefinedRoute {
    def isEmpty: Boolean
    def amount: MilliSatoshi
    def targetNodeId: PublicKey
  }
  case class PredefinedNodeRoute(amount: MilliSatoshi, nodes: Seq[PublicKey]) extends PredefinedRoute {
    override def isEmpty = nodes.isEmpty
    override def targetNodeId: PublicKey = nodes.last
  }
  case class PredefinedChannelRoute(amount: MilliSatoshi, targetNodeId: PublicKey, channels: Seq[ShortChannelId]) extends PredefinedRoute {
    override def isEmpty = channels.isEmpty
  }
  // @formatter:on

  // @formatter:off
  /** This is used when we get a TemporaryChannelFailure, to give time for the channel to recover (note that exclusions are directed) */
  case class ExcludeChannel(desc: ChannelDesc, duration_opt: Option[FiniteDuration])
  case class LiftChannelExclusion(desc: ChannelDesc)
  case object GetExcludedChannels

  sealed trait ExcludedChannelStatus
  case object ExcludedForever extends ExcludedChannelStatus
  case class ExcludedUntil(liftExclusionAt: TimestampSecond, timer: Cancellable) extends ExcludedChannelStatus
  // @formatter:on

  // @formatter:off
  case class SendChannelQuery(chainHash: ByteVector32, remoteNodeId: PublicKey, to: ActorRef, replacePrevious: Boolean, flags_opt: Option[QueryChannelRangeTlv]) extends RemoteTypes
  case object GetRoutingState
  case class RoutingState(channels: Iterable[PublicChannel], nodes: Iterable[NodeAnnouncement])
  case object GetRoutingStateStreaming extends RemoteTypes
  case object RoutingStateStreamingUpToDate extends RemoteTypes
  case object GetRouterData
  case object GetNodes
  case object GetChannels
  case object GetChannelsMap
  case object GetChannelUpdates

  case class GetNode(replyTo: typed.ActorRef[GetNodeResponse], nodeId: PublicKey)
  sealed trait GetNodeResponse
  case class PublicNode(announcement: NodeAnnouncement, activeChannels: Int, totalCapacity: Satoshi) extends GetNodeResponse
  case class UnknownNode(nodeId: PublicKey) extends GetNodeResponse
  // @formatter:on

  // @formatter:off
  sealed trait GossipOrigin
  /** Gossip that we received from a remote peer. */
  case class RemoteGossip(peerConnection: ActorRef, nodeId: PublicKey) extends GossipOrigin
  /** Gossip that was generated by our node. */
  case object LocalGossip extends GossipOrigin

  sealed trait GossipDecision extends RemoteTypes { def ann: AnnouncementMessage }
  object GossipDecision {
    case class Accepted(ann: AnnouncementMessage) extends GossipDecision

    sealed trait Rejected extends GossipDecision
    case class Duplicate(ann: AnnouncementMessage) extends Rejected
    case class InvalidSignature(ann: AnnouncementMessage) extends Rejected
    case class NoKnownChannel(ann: NodeAnnouncement) extends Rejected
    case class ValidationFailure(ann: ChannelAnnouncement) extends Rejected
    case class InvalidAnnouncement(ann: ChannelAnnouncement) extends Rejected
    case class ChannelPruned(ann: ChannelAnnouncement) extends Rejected
    case class ChannelClosing(ann: ChannelAnnouncement) extends Rejected
    case class ChannelClosed(ann: ChannelAnnouncement) extends Rejected
    case class Stale(ann: ChannelUpdate) extends Rejected
    case class NoRelatedChannel(ann: ChannelUpdate) extends Rejected
    case class RelatedChannelPruned(ann: ChannelUpdate) extends Rejected
  }

  case class RemoteChannelUpdate(channelUpdate: ChannelUpdate, origins: Set[GossipOrigin])
  case class Stash(updates: Map[ChannelUpdate, Set[GossipOrigin]], nodes: Map[NodeAnnouncement, Set[GossipOrigin]])
  case class Rebroadcast(channels: Map[ChannelAnnouncement, Set[GossipOrigin]], updates: Map[ChannelUpdate, Set[GossipOrigin]], nodes: Map[NodeAnnouncement, Set[GossipOrigin]])
  // @formatter:on

  case class ShortChannelIdAndFlag(shortChannelId: RealShortChannelId, flag: Long)

  /**
   * @param remainingQueries remaining queries to send, the next one will be popped after we receive a [[ReplyShortChannelIdsEnd]]
   * @param totalQueries     total number of *queries* (not channels) that will be sent during this syncing session
   */
  case class Syncing(remainingQueries: List[RoutingMessage], totalQueries: Int) {
    def started: Boolean = totalQueries > 0
  }

  case class Data(nodes: Map[PublicKey, NodeAnnouncement],
                  channels: SortedMap[RealShortChannelId, PublicChannel],
                  prunedChannels: SortedMap[RealShortChannelId, PublicChannel],
                  stash: Stash,
                  rebroadcast: Rebroadcast,
                  awaiting: Map[ChannelAnnouncement, Seq[GossipOrigin]], // note: this is a seq because we want to preserve order: first actor is the one who we need to send a tcp-ack when validation is done
                  privateChannels: Map[ByteVector32, PrivateChannel], // indexed by channel id
                  scid2PrivateChannels: Map[Long, ByteVector32], // real scid or alias to channel_id, only to be used for private channels
                  excludedChannels: Map[ChannelDesc, ExcludedChannelStatus], // those channels are temporarily excluded from route calculation, because their node returned a TemporaryChannelFailure
                  graphWithBalances: GraphWithBalanceEstimates,
                  sync: Map[PublicKey, Syncing] // keep tracks of channel range queries sent to each peer. If there is an entry in the map, it means that there is an ongoing query for which we have not yet received an 'end' message
                 ) {
    def resolve(scid: ShortChannelId): Option[KnownChannel] = {
      // let's assume this is a real scid
      channels.get(RealShortChannelId(scid.toLong)) match {
        case Some(publicChannel) => Some(publicChannel)
        case None =>
          // maybe it's an alias or a real scid
          scid2PrivateChannels.get(scid.toLong).flatMap(privateChannels.get) match {
            case Some(privateChannel) => Some(privateChannel)
            case None => None
          }
      }
    }

    def resolve(channelId: ByteVector32, realScid_opt: Option[RealShortChannelId]): Option[KnownChannel] = {
      privateChannels.get(channelId).orElse(realScid_opt.flatMap(channels.get))
    }
  }

  // @formatter:off
  sealed trait State
  case object NORMAL extends State

  case object TickBroadcast
  case object TickPruneStaleChannels
  // @formatter:on

  def isRelatedTo(c: ChannelAnnouncement, nodeId: PublicKey) = nodeId == c.nodeId1 || nodeId == c.nodeId2

  def hasChannels(nodeId: PublicKey, channels: Iterable[PublicChannel]): Boolean = channels.exists(c => isRelatedTo(c.ann, nodeId))

  /** We know that this route could relay because we have tried it but the payment was eventually cancelled */
  case class RouteCouldRelay(route: Route)

  /** We have relayed using this route. */
  case class RouteDidRelay(route: Route)

  /** We have tried to relay this amount from this channel and it failed. */
  case class ChannelCouldNotRelay(amount: MilliSatoshi, hop: ChannelHop)
}
