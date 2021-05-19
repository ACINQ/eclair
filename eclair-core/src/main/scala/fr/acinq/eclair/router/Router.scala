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
import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated, typed}
import akka.event.DiagnosticLoggingAdapter
import akka.event.Logging.MDC
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{ValidateResult, WatchExternalChannelSpent, WatchExternalChannelSpentTriggered}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.remote.EclairInternalsSerializer.RemoteTypes
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.router.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.wire.protocol._
import kamon.context.Context

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

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])
  context.system.eventStream.subscribe(self, classOf[AvailableBalanceChanged])

  setTimer(TickBroadcast.toString, TickBroadcast, nodeParams.routerConf.routerBroadcastInterval, repeat = true)
  setTimer(TickPruneStaleChannels.toString, TickPruneStaleChannels, 1 hour, repeat = true)
  setTimer(TickComputeNetworkStats.toString, TickComputeNetworkStats, nodeParams.routerConf.networkStatsRefreshInterval, repeat = true)

  val db: NetworkDb = nodeParams.db.network

  {
    log.info("loading network announcements from db...")
    val channels = db.listChannels()
    val nodes = db.listNodes()
    Metrics.Nodes.withoutTags().update(nodes.size)
    Metrics.Channels.withoutTags().update(channels.size)
    log.info("loaded from db: channels={} nodes={}", channels.size, nodes.size)
    val initChannels = channels
    // this will be used to calculate routes
    val graph = DirectedGraph.makeGraph(initChannels)
    val initNodes = nodes.map(n => n.nodeId -> n).toMap
    // send events for remaining channels/nodes
    context.system.eventStream.publish(ChannelsDiscovered(initChannels.values.map(pc => SingleChannelDiscovered(pc.ann, pc.capacity, pc.update_1_opt, pc.update_2_opt))))
    context.system.eventStream.publish(ChannelUpdatesReceived(initChannels.values.flatMap(pc => pc.update_1_opt ++ pc.update_2_opt ++ Nil)))
    context.system.eventStream.publish(NodesDiscovered(initNodes.values))

    // watch the funding tx of all these channels
    // note: some of them may already have been spent, in that case we will receive the watch event immediately
    initChannels.values.foreach { pc =>
      val txid = pc.fundingTxid
      val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(pc.ann.shortChannelId)
      // avoid herd effect at startup because watch-spent are intensive in terms of rpc calls to bitcoind
      context.system.scheduler.scheduleOnce(Random.nextLong(nodeParams.watchSpentWindow.toSeconds).seconds) {
        watcher ! WatchExternalChannelSpent(self, txid, outputIndex, pc.ann.shortChannelId)
      }
    }

    // on restart we update our node announcement
    // note that if we don't currently have public channels, this will be ignored
    val nodeAnn = Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.publicAddresses, nodeParams.features)
    self ! nodeAnn

    log.info(s"computing network stats...")
    val stats = NetworkStats.computeStats(initChannels.values)

    log.info(s"initialization completed, ready to process messages")
    Try(initialized.map(_.success(Done)))
    startWith(NORMAL, Data(initNodes, initChannels, stats, Stash(Map.empty, Map.empty), rebroadcast = Rebroadcast(channels = Map.empty, updates = Map.empty, nodes = Map.empty), awaiting = Map.empty, privateChannels = Map.empty, excludedChannels = Set.empty, graph, sync = Map.empty))
  }

  when(NORMAL) {

    case Event(SyncProgress(progress), d: Data) =>
      Metrics.SyncProgress.withoutTags().update(100 * progress)
      if (d.stats.isEmpty && progress == 1.0 && d.channels.nonEmpty) {
        log.info("initial routing sync done: computing network statistics")
        self ! TickComputeNetworkStats
      }
      stay

    case Event(GetRoutingState, d: Data) =>
      log.info(s"getting valid announcements for $sender")
      sender ! RoutingState(d.channels.values, d.nodes.values)
      stay

    case Event(GetNetworkStats, d: Data) =>
      sender ! GetNetworkStatsResponse(d.stats)
      stay

    case Event(GetRoutingStateStreaming, d) =>
      val listener = sender
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
        log.info(s"subscribing listener=$listener to network events")
        context.system.eventStream.subscribe(listener, classOf[NetworkEvent])
        context.watch(listener)

        override def receive: Receive = {
          case Terminated(actor) if actor == listener =>
            log.warning(s"unsubscribing listener=$listener to network events")
            context.system.eventStream.unsubscribe(listener)
            context stop self
        }
      }))
      stay

    case Event(TickBroadcast, d) =>
      if (d.rebroadcast.channels.isEmpty && d.rebroadcast.updates.isEmpty && d.rebroadcast.nodes.isEmpty) {
        stay
      } else {
        log.debug("broadcasting routing messages")
        log.debug("staggered broadcast details: channels={} updates={} nodes={}", d.rebroadcast.channels.size, d.rebroadcast.updates.size, d.rebroadcast.nodes.size)
        context.system.eventStream.publish(d.rebroadcast)
        stay using d.copy(rebroadcast = Rebroadcast(channels = Map.empty, updates = Map.empty, nodes = Map.empty))
      }

    case Event(TickComputeNetworkStats, d) =>
      if (d.channels.nonEmpty) {
        Metrics.Nodes.withoutTags().update(d.nodes.size)
        Metrics.Channels.withTag(Tags.Announced, value = true).update(d.channels.size)
        Metrics.Channels.withTag(Tags.Announced, value = false).update(d.privateChannels.size)
        log.info("re-computing network statistics")
        stay using d.copy(stats = NetworkStats.computeStats(d.channels.values))
      } else {
        log.debug("cannot compute network statistics: no public channels available")
        stay
      }

    case Event(TickPruneStaleChannels, d) =>
      stay using StaleChannels.handlePruneStaleChannels(d, nodeParams.db.network, nodeParams.currentBlockHeight)

    case Event(ExcludeChannel(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      val banDuration = nodeParams.routerConf.channelExcludeDuration
      log.info("excluding shortChannelId={} from nodeId={} for duration={}", shortChannelId, nodeId, banDuration)
      context.system.scheduler.scheduleOnce(banDuration, self, LiftChannelExclusion(desc))
      stay using d.copy(excludedChannels = d.excludedChannels + desc)

    case Event(LiftChannelExclusion(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      log.info("reinstating shortChannelId={} from nodeId={}", shortChannelId, nodeId)
      stay using d.copy(excludedChannels = d.excludedChannels - desc)

    case Event(GetNodes, d) =>
      sender ! d.nodes.values
      stay

    case Event(GetLocalChannels, d) =>
      val scids = d.graph.getIncomingEdgesOf(nodeParams.nodeId).map(_.desc.shortChannelId)
      val localChannels = scids.flatMap(scid => d.channels.get(scid).orElse(d.privateChannels.get(scid))).map(c => LocalChannel(nodeParams.nodeId, c))
      sender ! localChannels
      stay

    case Event(GetChannels, d) =>
      sender ! d.channels.values.map(_.ann)
      stay

    case Event(GetChannelsMap, d) =>
      sender ! d.channels
      stay

    case Event(GetChannelUpdates, d) =>
      val updates: Iterable[ChannelUpdate] = d.channels.values.flatMap(d => d.update_1_opt ++ d.update_2_opt) ++ d.privateChannels.values.flatMap(d => d.update_1_opt ++ d.update_2_opt)
      sender ! updates
      stay

    case Event(GetRouterData, d) =>
      sender ! d
      stay

    case Event(fr: FinalizeRoute, d) =>
      stay using RouteCalculation.finalizeRoute(d, nodeParams.nodeId, fr)

    case Event(r: RouteRequest, d) =>
      stay using RouteCalculation.handleRouteRequest(d, nodeParams.routerConf, nodeParams.currentBlockHeight, r)

    // Warning: order matters here, this must be the first match for HasChainHash messages !
    case Event(PeerRoutingMessage(_, _, routingMessage: HasChainHash), _) if routingMessage.chainHash != nodeParams.chainHash =>
      sender ! TransportHandler.ReadAck(routingMessage)
      log.warning("message {} for wrong chain {}, we're on {}", routingMessage, routingMessage.chainHash, nodeParams.chainHash)
      stay

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, c: ChannelAnnouncement), d) =>
      stay using Validation.handleChannelAnnouncement(d, nodeParams.db.network, watcher, RemoteGossip(peerConnection, remoteNodeId), c)

    case Event(r: ValidateResult, d) =>
      stay using Validation.handleChannelValidationResponse(d, nodeParams, watcher, r)

    case Event(WatchExternalChannelSpentTriggered(shortChannelId), d) if d.channels.contains(shortChannelId) =>
      stay using Validation.handleChannelSpent(d, nodeParams.db.network, shortChannelId)

    case Event(n: NodeAnnouncement, d: Data) =>
      stay using Validation.handleNodeAnnouncement(d, nodeParams.db.network, Set(LocalGossip), n)

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, n: NodeAnnouncement), d: Data) =>
      stay using Validation.handleNodeAnnouncement(d, nodeParams.db.network, Set(RemoteGossip(peerConnection, remoteNodeId)), n)

    case Event(u: ChannelUpdate, d: Data) =>
      stay using Validation.handleChannelUpdate(d, nodeParams.db.network, nodeParams.routerConf, Right(RemoteChannelUpdate(u, Set(LocalGossip))))

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, u: ChannelUpdate), d) =>
      stay using Validation.handleChannelUpdate(d, nodeParams.db.network, nodeParams.routerConf, Right(RemoteChannelUpdate(u, Set(RemoteGossip(peerConnection, remoteNodeId)))))

    case Event(lcu: LocalChannelUpdate, d: Data) =>
      stay using Validation.handleLocalChannelUpdate(d, nodeParams.db.network, nodeParams.routerConf, nodeParams.nodeId, watcher, lcu)

    case Event(lcd: LocalChannelDown, d: Data) =>
      stay using Validation.handleLocalChannelDown(d, nodeParams.nodeId, lcd)

    case Event(e: AvailableBalanceChanged, d: Data) =>
      stay using Validation.handleAvailableBalanceChanged(d, e)

    case Event(s: SendChannelQuery, d) =>
      stay using Sync.handleSendChannelQuery(d, s)

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, q: QueryChannelRange), d) =>
      Sync.handleQueryChannelRange(d.channels, nodeParams.routerConf, RemoteGossip(peerConnection, remoteNodeId), q)
      stay

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, r: ReplyChannelRange), d) =>
      stay using Sync.handleReplyChannelRange(d, nodeParams.routerConf, RemoteGossip(peerConnection, remoteNodeId), r)

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, q: QueryShortChannelIds), d) =>
      Sync.handleQueryShortChannelIds(d.nodes, d.channels, RemoteGossip(peerConnection, remoteNodeId), q)
      stay

    case Event(PeerRoutingMessage(peerConnection, remoteNodeId, r: ReplyShortChannelIdsEnd), d) =>
      stay using Sync.handleReplyShortChannelIdsEnd(d, RemoteGossip(peerConnection, remoteNodeId), r)

  }

  initialize()

  override def mdc(currentMessage: Any): MDC = {
    val category_opt = LogCategory(currentMessage)
    currentMessage match {
      case s: SendChannelQuery => Logs.mdc(category_opt, remoteNodeId_opt = Some(s.remoteNodeId))
      case prm: PeerRoutingMessage => Logs.mdc(category_opt, remoteNodeId_opt = Some(prm.remoteNodeId))
      case lcu: LocalChannelUpdate => Logs.mdc(category_opt, remoteNodeId_opt = Some(lcu.remoteNodeId))
      case _ => Logs.mdc(category_opt)
    }
  }
}

object Router {

  val shortChannelIdKey = Context.key[ShortChannelId]("shortChannelId", ShortChannelId(0))
  val remoteNodeIdKey = Context.key[String]("remoteNodeId", "unknown")

  def props(nodeParams: NodeParams, watcher: typed.ActorRef[ZmqWatcher.Command], initialized: Option[Promise[Done]] = None) = Props(new Router(nodeParams, watcher, initialized))

  case class RouterConf(randomizeRouteSelection: Boolean,
                        channelExcludeDuration: FiniteDuration,
                        routerBroadcastInterval: FiniteDuration,
                        networkStatsRefreshInterval: FiniteDuration,
                        requestNodeAnnouncements: Boolean,
                        encodingType: EncodingType,
                        channelRangeChunkSize: Int,
                        channelQueryChunkSize: Int,
                        searchMaxFeeBase: Satoshi,
                        searchMaxFeePct: Double,
                        searchMaxRouteLength: Int,
                        searchMaxCltv: CltvExpiryDelta,
                        searchRatioBias: Double,
                        searchRatioCltv: Double,
                        searchRatioChannelAge: Double,
                        searchRatioChannelCapacity: Double,
                        searchHopCostBase: MilliSatoshi,
                        searchHopCostMillionths: Long,
                        mppMinPartAmount: MilliSatoshi,
                        mppMaxParts: Int)

  // @formatter:off
  case class ChannelDesc(shortChannelId: ShortChannelId, a: PublicKey, b: PublicKey)
  case class ChannelMeta(balance1: MilliSatoshi, balance2: MilliSatoshi)
  sealed trait ChannelDetails {
    val capacity: Satoshi
    def getNodeIdSameSideAs(u: ChannelUpdate): PublicKey
    def getChannelUpdateSameSideAs(u: ChannelUpdate): Option[ChannelUpdate]
    def getBalanceSameSideAs(u: ChannelUpdate): Option[MilliSatoshi]
    def updateChannelUpdateSameSideAs(u: ChannelUpdate): ChannelDetails
    def updateBalances(commitments: AbstractCommitments): ChannelDetails
    def applyChannelUpdate(update: Either[LocalChannelUpdate, RemoteChannelUpdate]): ChannelDetails
  }
  case class PublicChannel(ann: ChannelAnnouncement, fundingTxid: ByteVector32, capacity: Satoshi, update_1_opt: Option[ChannelUpdate], update_2_opt: Option[ChannelUpdate], meta_opt: Option[ChannelMeta]) extends ChannelDetails {
    update_1_opt.foreach(u => assert(Announcements.isNode1(u.channelFlags)))
    update_2_opt.foreach(u => assert(!Announcements.isNode1(u.channelFlags)))

    def getNodeIdSameSideAs(u: ChannelUpdate): PublicKey = if (Announcements.isNode1(u.channelFlags)) ann.nodeId1 else ann.nodeId2
    def getChannelUpdateSameSideAs(u: ChannelUpdate): Option[ChannelUpdate] = if (Announcements.isNode1(u.channelFlags)) update_1_opt else update_2_opt
    def getBalanceSameSideAs(u: ChannelUpdate): Option[MilliSatoshi] = if (Announcements.isNode1(u.channelFlags)) meta_opt.map(_.balance1) else meta_opt.map(_.balance2)
    def updateChannelUpdateSameSideAs(u: ChannelUpdate): PublicChannel = if (Announcements.isNode1(u.channelFlags)) copy(update_1_opt = Some(u)) else copy(update_2_opt = Some(u))
    def updateBalances(commitments: AbstractCommitments): PublicChannel = if (commitments.localNodeId == ann.nodeId1) {
      copy(meta_opt = Some(ChannelMeta(commitments.availableBalanceForSend, commitments.availableBalanceForReceive)))
    } else {
      copy(meta_opt = Some(ChannelMeta(commitments.availableBalanceForReceive, commitments.availableBalanceForSend)))
    }
    def applyChannelUpdate(update: Either[LocalChannelUpdate, RemoteChannelUpdate]): PublicChannel = update match {
      case Left(lcu) => updateChannelUpdateSameSideAs(lcu.channelUpdate).updateBalances(lcu.commitments)
      case Right(rcu) => updateChannelUpdateSameSideAs(rcu.channelUpdate)
    }
  }
  case class PrivateChannel(localNodeId: PublicKey, remoteNodeId: PublicKey, update_1_opt: Option[ChannelUpdate], update_2_opt: Option[ChannelUpdate], meta: ChannelMeta) extends ChannelDetails {
    val (nodeId1, nodeId2) = if (Announcements.isNode1(localNodeId, remoteNodeId)) (localNodeId, remoteNodeId) else (remoteNodeId, localNodeId)
    val capacity: Satoshi = (meta.balance1 + meta.balance2).truncateToSatoshi

    def getNodeIdSameSideAs(u: ChannelUpdate): PublicKey = if (Announcements.isNode1(u.channelFlags)) nodeId1 else nodeId2
    def getChannelUpdateSameSideAs(u: ChannelUpdate): Option[ChannelUpdate] = if (Announcements.isNode1(u.channelFlags)) update_1_opt else update_2_opt
    def getBalanceSameSideAs(u: ChannelUpdate): Option[MilliSatoshi] = if (Announcements.isNode1(u.channelFlags)) Some(meta.balance1) else Some(meta.balance2)
    def updateChannelUpdateSameSideAs(u: ChannelUpdate): PrivateChannel = if (Announcements.isNode1(u.channelFlags)) copy(update_1_opt = Some(u)) else copy(update_2_opt = Some(u))
    def updateBalances(commitments: AbstractCommitments): PrivateChannel = if (commitments.localNodeId == nodeId1) {
      copy(meta = ChannelMeta(commitments.availableBalanceForSend, commitments.availableBalanceForReceive))
    } else {
      copy(meta = ChannelMeta(commitments.availableBalanceForReceive, commitments.availableBalanceForSend))
    }
    def applyChannelUpdate(update: Either[LocalChannelUpdate, RemoteChannelUpdate]): PrivateChannel = update match {
      case Left(lcu) => updateChannelUpdateSameSideAs(lcu.channelUpdate).updateBalances(lcu.commitments)
      case Right(rcu) => updateChannelUpdateSameSideAs(rcu.channelUpdate)
    }
  }
  case class LocalChannel(localNodeId: PublicKey, channel: ChannelDetails) {
    val isPrivate: Boolean = channel match {
      case _: PrivateChannel => true
      case _ => false
    }
    val capacity: Satoshi = channel.capacity
    val remoteNodeId: PublicKey = channel match {
      case c: PrivateChannel => c.remoteNodeId
      case c: PublicChannel => if (c.ann.nodeId1 == localNodeId) c.ann.nodeId2 else c.ann.nodeId1
    }
    /** Our remote peer's channel_update: this is what must be used in invoice routing hints. */
    val remoteUpdate: Option[ChannelUpdate] = channel match {
      case c: PrivateChannel => if (remoteNodeId == c.nodeId1) c.update_1_opt else c.update_2_opt
      case c: PublicChannel => if (remoteNodeId == c.ann.nodeId1) c.update_1_opt else c.update_2_opt
    }
    /** Create an invoice routing hint from that channel. Note that if the channel is private, the invoice will leak its existence. */
    def toExtraHop: Option[ExtraHop] = remoteUpdate.map(u => ExtraHop(remoteNodeId, u.shortChannelId, u.feeBaseMsat, u.feeProportionalMillionths, u.cltvExpiryDelta))
  }
  // @formatter:on

  case class AssistedChannel(extraHop: ExtraHop, nextNodeId: PublicKey, htlcMaximum: MilliSatoshi)

  trait Hop {
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

  /**
   * A directed hop between two connected nodes using a specific channel.
   *
   * @param nodeId     id of the start node.
   * @param nextNodeId id of the end node.
   * @param lastUpdate last update of the channel used for the hop.
   */
  case class ChannelHop(nodeId: PublicKey, nextNodeId: PublicKey, lastUpdate: ChannelUpdate) extends Hop {
    override lazy val cltvExpiryDelta: CltvExpiryDelta = lastUpdate.cltvExpiryDelta

    override def fee(amount: MilliSatoshi): MilliSatoshi = nodeFee(lastUpdate.feeBaseMsat, lastUpdate.feeProportionalMillionths, amount)
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
  case class NodeHop(nodeId: PublicKey, nextNodeId: PublicKey, cltvExpiryDelta: CltvExpiryDelta, fee: MilliSatoshi) extends Hop {
    override def fee(amount: MilliSatoshi): MilliSatoshi = fee
  }

  case class MultiPartParams(minPartAmount: MilliSatoshi, maxParts: Int)

  case class RouteParams(randomize: Boolean, maxFeeBase: MilliSatoshi, maxFeePct: Double, routeMaxLength: Int, routeMaxCltv: CltvExpiryDelta, ratios: WeightRatios, mpp: MultiPartParams) {
    def getMaxFee(amount: MilliSatoshi): MilliSatoshi = {
      // The payment fee must satisfy either the flat fee or the percentage fee, not necessarily both.
      maxFeeBase.max(amount * maxFeePct)
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
                          target: PublicKey,
                          amount: MilliSatoshi,
                          maxFee: MilliSatoshi,
                          assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                          ignore: Ignore = Ignore.empty,
                          routeParams: Option[RouteParams] = None,
                          allowMultiPart: Boolean = false,
                          pendingPayments: Seq[Route] = Nil,
                          paymentContext: Option[PaymentContext] = None)

  case class FinalizeRoute(amount: MilliSatoshi,
                           route: PredefinedRoute,
                           assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                           paymentContext: Option[PaymentContext] = None)

  /**
   * Useful for having appropriate logging context at hand when finding routes
   */
  case class PaymentContext(id: UUID, parentId: UUID, paymentHash: ByteVector32)

  case class Route(amount: MilliSatoshi, hops: Seq[ChannelHop]) {
    require(hops.nonEmpty, "route cannot be empty")

    val length = hops.length
    lazy val fee: MilliSatoshi = {
      val amountToSend = hops.drop(1).reverse.foldLeft(amount) { case (amount1, hop) => amount1 + hop.fee(amount1) }
      amountToSend - amount
    }

    /** This method retrieves the channel update that we used when we built the route. */
    def getChannelUpdateForNode(nodeId: PublicKey): Option[ChannelUpdate] = hops.find(_.nodeId == nodeId).map(_.lastUpdate)

    def printNodes(): String = hops.map(_.nextNodeId).mkString("->")

    def printChannels(): String = hops.map(_.lastUpdate.shortChannelId).mkString("->")

  }

  case class RouteResponse(routes: Seq[Route]) {
    require(routes.nonEmpty, "routes cannot be empty")
  }

  // @formatter:off
  /** A pre-defined route chosen outside of eclair (e.g. manually by a user to do some re-balancing). */
  sealed trait PredefinedRoute {
    def isEmpty: Boolean
    def targetNodeId: PublicKey
  }
  case class PredefinedNodeRoute(nodes: Seq[PublicKey]) extends PredefinedRoute {
    override def isEmpty = nodes.isEmpty
    override def targetNodeId: PublicKey = nodes.last
  }
  case class PredefinedChannelRoute(targetNodeId: PublicKey, channels: Seq[ShortChannelId]) extends PredefinedRoute {
    override def isEmpty = channels.isEmpty
  }
  // @formatter:on

  // @formatter:off
  /** This is used when we get a TemporaryChannelFailure, to give time for the channel to recover (note that exclusions are directed) */
  case class ExcludeChannel(desc: ChannelDesc)
  case class LiftChannelExclusion(desc: ChannelDesc)
  // @formatter:on

  // @formatter:off
  case class SendChannelQuery(chainHash: ByteVector32, remoteNodeId: PublicKey, to: ActorRef, replacePrevious: Boolean, flags_opt: Option[QueryChannelRangeTlv]) extends RemoteTypes
  case object GetNetworkStats
  case class GetNetworkStatsResponse(stats: Option[NetworkStats])
  case object GetRoutingState
  case class RoutingState(channels: Iterable[PublicChannel], nodes: Iterable[NodeAnnouncement])
  case object GetRoutingStateStreaming extends RemoteTypes
  case object RoutingStateStreamingUpToDate extends RemoteTypes
  case object GetRouterData
  case object GetNodes
  case object GetLocalChannels
  case object GetChannels
  case object GetChannelsMap
  case object GetChannelUpdates
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

  case class ShortChannelIdAndFlag(shortChannelId: ShortChannelId, flag: Long)

  /**
   * @param remainingQueries remaining queries to send, the next one will be popped after we receive a [[ReplyShortChannelIdsEnd]]
   * @param totalQueries     total number of *queries* (not channels) that will be sent during this syncing session
   */
  case class Syncing(remainingQueries: List[RoutingMessage], totalQueries: Int) {
    def started: Boolean = totalQueries > 0
  }

  case class Data(nodes: Map[PublicKey, NodeAnnouncement],
                  channels: SortedMap[ShortChannelId, PublicChannel],
                  stats: Option[NetworkStats],
                  stash: Stash,
                  rebroadcast: Rebroadcast,
                  awaiting: Map[ChannelAnnouncement, Seq[RemoteGossip]], // note: this is a seq because we want to preserve order: first actor is the one who we need to send a tcp-ack when validation is done
                  privateChannels: Map[ShortChannelId, PrivateChannel],
                  excludedChannels: Set[ChannelDesc], // those channels are temporarily excluded from route calculation, because their node returned a TemporaryChannelFailure
                  graph: DirectedGraph,
                  sync: Map[PublicKey, Syncing] // keep tracks of channel range queries sent to each peer. If there is an entry in the map, it means that there is an ongoing query for which we have not yet received an 'end' message
                 )

  // @formatter:off
  sealed trait State
  case object NORMAL extends State

  case object TickBroadcast
  case object TickPruneStaleChannels
  case object TickComputeNetworkStats
  // @formatter:on

  def getDesc(u: ChannelUpdate, channel: ChannelAnnouncement): ChannelDesc = {
    // the least significant bit tells us if it is node1 or node2
    if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, channel.nodeId1, channel.nodeId2) else ChannelDesc(u.shortChannelId, channel.nodeId2, channel.nodeId1)
  }

  def getDesc(u: ChannelUpdate, pc: PrivateChannel): ChannelDesc = {
    // the least significant bit tells us if it is node1 or node2
    if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, pc.nodeId1, pc.nodeId2) else ChannelDesc(u.shortChannelId, pc.nodeId2, pc.nodeId1)
  }

  def isRelatedTo(c: ChannelAnnouncement, nodeId: PublicKey) = nodeId == c.nodeId1 || nodeId == c.nodeId2

  def hasChannels(nodeId: PublicKey, channels: Iterable[PublicChannel]): Boolean = channels.exists(c => isRelatedTo(c.ann, nodeId))
}
