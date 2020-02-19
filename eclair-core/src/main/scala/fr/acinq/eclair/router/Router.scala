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
import akka.actor.{ActorRef, Props, Status}
import akka.event.Logging.MDC
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Satoshi}
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.{ChannelClosed, InvalidAnnouncement, InvalidSignature, PeerRoutingMessage}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{RichWeight, RoutingHeuristics, WeightRatios}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import kamon.Kamon
import kamon.context.Context
import scodec.bits.ByteVector
import shapeless.HNil

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.collection.{SortedSet, mutable}
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Random, Try}

/**
 * Created by PM on 24/05/2016.
 */

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
                      searchHeuristicsEnabled: Boolean,
                      searchRatioCltv: Double,
                      searchRatioChannelAge: Double,
                      searchRatioChannelCapacity: Double)

// @formatter:off
case class ChannelDesc(shortChannelId: ShortChannelId, a: PublicKey, b: PublicKey)
case class PublicChannel(ann: ChannelAnnouncement, fundingTxid: ByteVector32, capacity: Satoshi, update_1_opt: Option[ChannelUpdate], update_2_opt: Option[ChannelUpdate]) {
  update_1_opt.foreach(u => assert(Announcements.isNode1(u.channelFlags)))
  update_2_opt.foreach(u => assert(!Announcements.isNode1(u.channelFlags)))

  def getNodeIdSameSideAs(u: ChannelUpdate): PublicKey = if (Announcements.isNode1(u.channelFlags)) ann.nodeId1 else ann.nodeId2

  def getChannelUpdateSameSideAs(u: ChannelUpdate): Option[ChannelUpdate] = if (Announcements.isNode1(u.channelFlags)) update_1_opt else update_2_opt

  def updateChannelUpdateSameSideAs(u: ChannelUpdate): PublicChannel = if (Announcements.isNode1(u.channelFlags)) copy(update_1_opt = Some(u)) else copy(update_2_opt = Some(u))
}
case class PrivateChannel(localNodeId: PublicKey, remoteNodeId: PublicKey, update_1_opt: Option[ChannelUpdate], update_2_opt: Option[ChannelUpdate]) {
  val (nodeId1, nodeId2) = if (Announcements.isNode1(localNodeId, remoteNodeId)) (localNodeId, remoteNodeId) else (remoteNodeId, localNodeId)

  def getNodeIdSameSideAs(u: ChannelUpdate): PublicKey = if (Announcements.isNode1(u.channelFlags)) nodeId1 else nodeId2

  def getChannelUpdateSameSideAs(u: ChannelUpdate): Option[ChannelUpdate] = if (Announcements.isNode1(u.channelFlags)) update_1_opt else update_2_opt

  def updateChannelUpdateSameSideAs(u: ChannelUpdate): PrivateChannel = if (Announcements.isNode1(u.channelFlags)) copy(update_1_opt = Some(u)) else copy(update_2_opt = Some(u))
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
  override lazy val cltvExpiryDelta = lastUpdate.cltvExpiryDelta

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

case class RouteParams(randomize: Boolean, maxFeeBase: MilliSatoshi, maxFeePct: Double, routeMaxLength: Int, routeMaxCltv: CltvExpiryDelta, ratios: Option[WeightRatios])

case class RouteRequest(source: PublicKey,
                        target: PublicKey,
                        amount: MilliSatoshi,
                        assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                        ignoreNodes: Set[PublicKey] = Set.empty,
                        ignoreChannels: Set[ChannelDesc] = Set.empty,
                        routeParams: Option[RouteParams] = None)

case class FinalizeRoute(hops: Seq[PublicKey])

case class RouteResponse(hops: Seq[ChannelHop], ignoreNodes: Set[PublicKey], ignoreChannels: Set[ChannelDesc], allowEmpty: Boolean = false) {
  require(allowEmpty || hops.nonEmpty, "route cannot be empty")
}

// @formatter:off
/** This is used when we get a TemporaryChannelFailure, to give time for the channel to recover (note that exclusions are directed) */
case class ExcludeChannel(desc: ChannelDesc)
case class LiftChannelExclusion(desc: ChannelDesc)
// @formatter:on

case class SendChannelQuery(remoteNodeId: PublicKey, to: ActorRef, flags_opt: Option[QueryChannelRangeTlv])

case object GetNetworkStats

case class GetNetworkStatsResponse(stats: Option[NetworkStats])

case object GetRoutingState

case class RoutingState(channels: Iterable[PublicChannel], nodes: Iterable[NodeAnnouncement])

// @formatter:off
sealed trait GossipOrigin
/** Gossip that we received from a remote peer. */
case class RemoteGossip(peer: ActorRef) extends GossipOrigin
/** Gossip that was generated by our node. */
case object LocalGossip extends GossipOrigin

case class Stash(updates: Map[ChannelUpdate, Set[GossipOrigin]], nodes: Map[NodeAnnouncement, Set[GossipOrigin]])
case class Rebroadcast(channels: Map[ChannelAnnouncement, Set[GossipOrigin]], updates: Map[ChannelUpdate, Set[GossipOrigin]], nodes: Map[NodeAnnouncement, Set[GossipOrigin]])
// @formatter:on

case class ShortChannelIdAndFlag(shortChannelId: ShortChannelId, flag: Long)

case class Sync(pending: List[RoutingMessage], total: Int)

case class Data(nodes: Map[PublicKey, NodeAnnouncement],
                channels: SortedMap[ShortChannelId, PublicChannel],
                stats: Option[NetworkStats],
                stash: Stash,
                rebroadcast: Rebroadcast,
                awaiting: Map[ChannelAnnouncement, Seq[RemoteGossip]], // note: this is a seq because we want to preserve order: first actor is the one who we need to send a tcp-ack when validation is done
                privateChannels: Map[ShortChannelId, PrivateChannel], // short_channel_id -> node_id
                excludedChannels: Set[ChannelDesc], // those channels are temporarily excluded from route calculation, because their node returned a TemporaryChannelFailure
                graph: DirectedGraph,
                sync: Map[PublicKey, Sync] // keep tracks of channel range queries sent to each peer. If there is an entry in the map, it means that there is an ongoing query
                // for which we have not yet received an 'end' message
               )

// @formatter:off
sealed trait State
case object NORMAL extends State

case object TickBroadcast
case object TickPruneStaleChannels
case object TickComputeNetworkStats
// @formatter:on

class Router(val nodeParams: NodeParams, watcher: ActorRef, initialized: Option[Promise[Done]] = None) extends FSMDiagnosticActorLogging[State, Data] {

  import Router._

  import ExecutionContext.Implicits.global

  // we pass these to helpers classes so that they have the logging context
  implicit def implicitLog: LoggingAdapter = log

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])

  setTimer(TickBroadcast.toString, TickBroadcast, nodeParams.routerConf.routerBroadcastInterval, repeat = true)
  setTimer(TickPruneStaleChannels.toString, TickPruneStaleChannels, 1 hour, repeat = true)
  setTimer(TickComputeNetworkStats.toString, TickComputeNetworkStats, nodeParams.routerConf.networkStatsRefreshInterval, repeat = true)

  val defaultRouteParams = getDefaultRouteParams(nodeParams.routerConf)

  val db = nodeParams.db.network

  {
    log.info("loading network announcements from db...")
    val channels = db.listChannels()
    val nodes = db.listNodes()
    log.info("loaded from db: channels={} nodes={}", channels.size, nodes.size)
    val initChannels = channels
    // this will be used to calculate routes
    val graph = DirectedGraph.makeGraph(initChannels)
    val initNodes = nodes.map(n => n.nodeId -> n).toMap
    // send events for remaining channels/nodes
    context.system.eventStream.publish(ChannelsDiscovered(initChannels.values.map(pc => SingleChannelDiscovered(pc.ann, pc.capacity))))
    context.system.eventStream.publish(ChannelUpdatesReceived(initChannels.values.flatMap(pc => pc.update_1_opt ++ pc.update_2_opt ++ Nil)))
    context.system.eventStream.publish(NodesDiscovered(initNodes.values))

    // watch the funding tx of all these channels
    // note: some of them may already have been spent, in that case we will receive the watch event immediately
    initChannels.values.foreach { pc =>
      val txid = pc.fundingTxid
      val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(pc.ann.shortChannelId)
      val fundingOutputScript = write(pay2wsh(Scripts.multiSig2of2(pc.ann.bitcoinKey1, pc.ann.bitcoinKey2)))
      watcher ! WatchSpentBasic(self, txid, outputIndex, fundingOutputScript, BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(pc.ann.shortChannelId))
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
    case Event(LocalChannelUpdate(_, _, shortChannelId, remoteNodeId, channelAnnouncement_opt, u, _), d: Data) =>
      d.channels.get(shortChannelId) match {
        case Some(_) =>
          // channel has already been announced and router knows about it, we can process the channel_update
          stay using handle(u, LocalGossip, d)
        case None =>
          channelAnnouncement_opt match {
            case Some(c) if d.awaiting.contains(c) =>
              // channel is currently being verified, we can process the channel_update right away (it will be stashed)
              stay using handle(u, LocalGossip, d)
            case Some(c) =>
              // channel wasn't announced but here is the announcement, we will process it *before* the channel_update
              watcher ! ValidateRequest(c)
              val d1 = d.copy(awaiting = d.awaiting + (c -> Nil)) // no origin
              // maybe the local channel was pruned (can happen if we were disconnected for more than 2 weeks)
              db.removeFromPruned(c.shortChannelId)
              stay using handle(u, LocalGossip, d1)
            case None if d.privateChannels.contains(shortChannelId) =>
              // channel isn't announced but we already know about it, we can process the channel_update
              stay using handle(u, LocalGossip, d)
            case None =>
              // channel isn't announced and we never heard of it (maybe it is a private channel or maybe it is a public channel that doesn't yet have 6 confirmations)
              // let's create a corresponding private channel and process the channel_update
              log.info("adding unannounced local channel to remote={} shortChannelId={}", remoteNodeId, shortChannelId)
              stay using handle(u, LocalGossip, d.copy(privateChannels = d.privateChannels + (shortChannelId -> PrivateChannel(nodeParams.nodeId, remoteNodeId, None, None))))
          }
      }

    case Event(LocalChannelDown(_, channelId, shortChannelId, remoteNodeId), d: Data) =>
      // a local channel has permanently gone down
      if (d.channels.contains(shortChannelId)) {
        // the channel was public, we will receive (or have already received) a WatchEventSpentBasic event, that will trigger a clean up of the channel
        // so let's not do anything here
        stay
      } else if (d.privateChannels.contains(shortChannelId)) {
        // the channel was private or public-but-not-yet-announced, let's do the clean up
        log.debug("removing private local channel and channel_update for channelId={} shortChannelId={}", channelId, shortChannelId)
        val desc1 = ChannelDesc(shortChannelId, nodeParams.nodeId, remoteNodeId)
        val desc2 = ChannelDesc(shortChannelId, remoteNodeId, nodeParams.nodeId)
        // we remove the corresponding updates from the graph
        val graph1 = d.graph
          .removeEdge(desc1)
          .removeEdge(desc2)
        // and we remove the channel and channel_update from our state
        stay using d.copy(privateChannels = d.privateChannels - shortChannelId, graph = graph1)
      } else {
        stay
      }

    case Event(SyncProgress(progress), d: Data) =>
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

    case Event(v@ValidateResult(c, _), d0) =>
      Kamon.runWithContextEntry(shortChannelIdKey, c.shortChannelId) {
        Kamon.runWithSpan(Kamon.currentSpan(), finishSpan = true) {
          Kamon.runWithSpan(Kamon.spanBuilder("process-validate-result").start(), finishSpan = true) {
            d0.awaiting.get(c) match {
              case Some(origin +: _) => origin.peer ! TransportHandler.ReadAck(c) // now we can acknowledge the message, we only need to do it for the first peer that sent us the announcement
              case _ => ()
            }
            log.info("got validation result for shortChannelId={} (awaiting={} stash.nodes={} stash.updates={})", c.shortChannelId, d0.awaiting.size, d0.stash.nodes.size, d0.stash.updates.size)
            val publicChannel_opt = v match {
              case ValidateResult(c, Left(t)) =>
                log.warning("validation failure for shortChannelId={} reason={}", c.shortChannelId, t.getMessage)
                None
              case ValidateResult(c, Right((tx, UtxoStatus.Unspent))) =>
                val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(c.shortChannelId)
                val (fundingOutputScript, ok) = Kamon.runWithSpan(Kamon.spanBuilder("checked-pubkeyscript").start(), finishSpan = true) {
                  // let's check that the output is indeed a P2WSH multisig 2-of-2 of nodeid1 and nodeid2)
                  val fundingOutputScript = write(pay2wsh(Scripts.multiSig2of2(c.bitcoinKey1, c.bitcoinKey2)))
                  val ok = tx.txOut.size < outputIndex + 1 || fundingOutputScript != tx.txOut(outputIndex).publicKeyScript
                  (fundingOutputScript, ok)
                }
                if (ok) {
                  log.error(s"invalid script for shortChannelId={}: txid={} does not have script=$fundingOutputScript at outputIndex=$outputIndex ann={}", c.shortChannelId, tx.txid, c)
                  d0.awaiting.get(c) match {
                    case Some(origins) => origins.foreach(_.peer ! InvalidAnnouncement(c))
                    case _ => ()
                  }
                  None
                } else {
                  watcher ! WatchSpentBasic(self, tx, outputIndex, BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(c.shortChannelId))
                  // TODO: check feature bit set
                  log.debug("added channel channelId={}", c.shortChannelId)
                  val capacity = tx.txOut(outputIndex).amount
                  context.system.eventStream.publish(ChannelsDiscovered(SingleChannelDiscovered(c, capacity) :: Nil))
                  Kamon.runWithSpan(Kamon.spanBuilder("add-to-db").start(), finishSpan = true) {
                    db.addChannel(c, tx.txid, capacity)
                  }
                  // in case we just validated our first local channel, we announce the local node
                  if (!d0.nodes.contains(nodeParams.nodeId) && isRelatedTo(c, nodeParams.nodeId)) {
                    log.info("first local channel validated, announcing local node")
                    val nodeAnn = Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.publicAddresses, nodeParams.features)
                    self ! nodeAnn
                  }
                  Some(PublicChannel(c, tx.txid, capacity, None, None))
                }
              case ValidateResult(c, Right((tx, fundingTxStatus: UtxoStatus.Spent))) =>
                if (fundingTxStatus.spendingTxConfirmed) {
                  log.warning("ignoring shortChannelId={} tx={} (funding tx already spent and spending tx is confirmed)", c.shortChannelId, tx.txid)
                  // the funding tx has been spent by a transaction that is now confirmed: peer shouldn't send us those
                  d0.awaiting.get(c) match {
                    case Some(origins) => origins.foreach(_.peer ! ChannelClosed(c))
                    case _ => ()
                  }
                } else {
                  log.debug("ignoring shortChannelId={} tx={} (funding tx already spent but spending tx isn't confirmed)", c.shortChannelId, tx.txid)
                }
                // there may be a record if we have just restarted
                db.removeChannel(c.shortChannelId)
                None
            }
            val span1 = Kamon.spanBuilder("reprocess-stash").start
            // we also reprocess node and channel_update announcements related to channels that were just analyzed
            val reprocessUpdates = d0.stash.updates.filterKeys(u => u.shortChannelId == c.shortChannelId)
            val reprocessNodes = d0.stash.nodes.filterKeys(n => isRelatedTo(c, n.nodeId))
            // and we remove the reprocessed messages from the stash
            val stash1 = d0.stash.copy(updates = d0.stash.updates -- reprocessUpdates.keys, nodes = d0.stash.nodes -- reprocessNodes.keys)
            // we remove channel from awaiting map
            val awaiting1 = d0.awaiting - c
            span1.finish()

            publicChannel_opt match {
              case Some(pc) =>
                Kamon.runWithSpan(Kamon.spanBuilder("build-new-state").start, finishSpan = true) {
                  // note: if the channel is graduating from private to public, the implementation (in the LocalChannelUpdate handler) guarantees that we will process a new channel_update
                  // right after the channel_announcement, channel_updates will be moved from private to public at that time
                  val d1 = d0.copy(
                    channels = d0.channels + (c.shortChannelId -> pc),
                    privateChannels = d0.privateChannels - c.shortChannelId, // we remove fake announcements that we may have made before
                    rebroadcast = d0.rebroadcast.copy(channels = d0.rebroadcast.channels + (c -> d0.awaiting.getOrElse(c, Nil).toSet)), // we also add the newly validated channels to the rebroadcast queue
                    stash = stash1,
                    awaiting = awaiting1)
                  // we only reprocess updates and nodes if validation succeeded
                  val d2 = reprocessUpdates.foldLeft(d1) {
                    case (d, (u, origins)) => origins.foldLeft(d) { case (d, origin) => handle(u, origin, d) } // we reprocess the same channel_update for every origin (to preserve origin information)
                  }
                  val d3 = reprocessNodes.foldLeft(d2) {
                    case (d, (n, origins)) => origins.foldLeft(d) { case (d, origin) => handle(n, origin, d) } // we reprocess the same node_announcement for every origins (to preserve origin information)
                  }
                  stay using d3
                }
              case None =>
                stay using d0.copy(stash = stash1, awaiting = awaiting1)
            }
          }
        }
      }

    case Event(WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(shortChannelId)), d) if d.channels.contains(shortChannelId) =>
      val lostChannel = d.channels(shortChannelId).ann
      log.info("funding tx of channelId={} has been spent", shortChannelId)
      // we need to remove nodes that aren't tied to any channels anymore
      val channels1 = d.channels - lostChannel.shortChannelId
      val lostNodes = Seq(lostChannel.nodeId1, lostChannel.nodeId2).filterNot(nodeId => hasChannels(nodeId, channels1.values))
      // let's clean the db and send the events
      log.info("pruning shortChannelId={} (spent)", shortChannelId)
      db.removeChannel(shortChannelId) // NB: this also removes channel updates
      // we also need to remove updates from the graph
      val graph1 = d.graph
        .removeEdge(ChannelDesc(lostChannel.shortChannelId, lostChannel.nodeId1, lostChannel.nodeId2))
        .removeEdge(ChannelDesc(lostChannel.shortChannelId, lostChannel.nodeId2, lostChannel.nodeId1))

      context.system.eventStream.publish(ChannelLost(shortChannelId))
      lostNodes.foreach {
        nodeId =>
          log.info("pruning nodeId={} (spent)", nodeId)
          db.removeNode(nodeId)
          context.system.eventStream.publish(NodeLost(nodeId))
      }
      stay using d.copy(nodes = d.nodes -- lostNodes, channels = d.channels - shortChannelId, graph = graph1)

    case Event(TickBroadcast, d) =>
      if (d.rebroadcast.channels.isEmpty && d.rebroadcast.updates.isEmpty && d.rebroadcast.nodes.isEmpty) {
        stay
      } else {
        log.debug("broadcasting routing messages")
        log.debug("staggered broadcast details: channels={} updates={} nodes={}", d.rebroadcast.channels.size, d.rebroadcast.updates.size, d.rebroadcast.nodes.size)
        context.actorSelection(context.system / "*" / "switchboard") ! d.rebroadcast
        stay using d.copy(rebroadcast = Rebroadcast(channels = Map.empty, updates = Map.empty, nodes = Map.empty))
      }

    case Event(TickComputeNetworkStats, d) =>
      if (d.channels.nonEmpty) {
        log.info("re-computing network statistics")
        stay using d.copy(stats = NetworkStats.computeStats(d.channels.values))
      } else {
        log.debug("cannot compute network statistics: no public channels available")
        stay
      }

    case Event(TickPruneStaleChannels, d) =>
      // first we select channels that we will prune
      val staleChannels = getStaleChannels(d.channels.values, nodeParams.currentBlockHeight)
      val staleChannelIds = staleChannels.map(_.ann.shortChannelId)
      // then we remove nodes that aren't tied to any channels anymore (and deduplicate them)
      val potentialStaleNodes = staleChannels.flatMap(c => Set(c.ann.nodeId1, c.ann.nodeId2)).toSet
      val channels1 = d.channels -- staleChannelIds
      // no need to iterate on all nodes, just on those that are affected by current pruning
      val staleNodes = potentialStaleNodes.filterNot(nodeId => hasChannels(nodeId, channels1.values))

      // let's clean the db and send the events
      db.removeChannels(staleChannelIds) // NB: this also removes channel updates
      // we keep track of recently pruned channels so we don't revalidate them (zombie churn)
      db.addToPruned(staleChannelIds)
      staleChannelIds.foreach { shortChannelId =>
        log.info("pruning shortChannelId={} (stale)", shortChannelId)
        context.system.eventStream.publish(ChannelLost(shortChannelId))
      }

      val staleChannelsToRemove = new mutable.MutableList[ChannelDesc]
      staleChannels.foreach(ca => {
        staleChannelsToRemove += ChannelDesc(ca.ann.shortChannelId, ca.ann.nodeId1, ca.ann.nodeId2)
        staleChannelsToRemove += ChannelDesc(ca.ann.shortChannelId, ca.ann.nodeId2, ca.ann.nodeId1)
      })

      val graph1 = d.graph.removeEdges(staleChannelsToRemove)
      staleNodes.foreach {
        nodeId =>
          log.info("pruning nodeId={} (stale)", nodeId)
          db.removeNode(nodeId)
          context.system.eventStream.publish(NodeLost(nodeId))
      }
      stay using d.copy(nodes = d.nodes -- staleNodes, channels = channels1, graph = graph1)

    case Event(ExcludeChannel(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      val banDuration = nodeParams.routerConf.channelExcludeDuration
      log.info("excluding shortChannelId={} from nodeId={} for duration={}", shortChannelId, nodeId, banDuration)
      context.system.scheduler.scheduleOnce(banDuration, self, LiftChannelExclusion(desc))
      stay using d.copy(excludedChannels = d.excludedChannels + desc)

    case Event(LiftChannelExclusion(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      log.info("reinstating shortChannelId={} from nodeId={}", shortChannelId, nodeId)
      stay using d.copy(excludedChannels = d.excludedChannels - desc)

    case Event('nodes, d) =>
      sender ! d.nodes.values
      stay

    case Event('channels, d) =>
      sender ! d.channels.values.map(_.ann)
      stay

    case Event('channelsMap, d) =>
      sender ! d.channels
      stay

    case Event('updates, d) =>
      val updates: Iterable[ChannelUpdate] = d.channels.values.flatMap(d => d.update_1_opt ++ d.update_2_opt) ++ d.privateChannels.values.flatMap(d => d.update_1_opt ++ d.update_2_opt)
      sender ! updates
      stay

    case Event('data, d) =>
      sender ! d
      stay

    case Event(FinalizeRoute(partialHops), d) =>
      // split into sublists [(a,b),(b,c), ...] then get the edges between each of those pairs
      partialHops.sliding(2).map { case List(v1, v2) => d.graph.getEdgesBetween(v1, v2) }.toList match {
        case edges if edges.nonEmpty && edges.forall(_.nonEmpty) =>
          val selectedEdges = edges.map(_.maxBy(_.update.htlcMaximumMsat.getOrElse(0 msat))) // select the largest edge
          val hops = selectedEdges.map(d => ChannelHop(d.desc.a, d.desc.b, d.update))
          sender ! RouteResponse(hops, Set.empty, Set.empty)
        case _ => // some nodes in the supplied route aren't connected in our graph
          sender ! Status.Failure(new IllegalArgumentException("Not all the nodes in the supplied route are connected with public channels"))
      }
      stay

    case Event(RouteRequest(start, end, amount, assistedRoutes, ignoreNodes, ignoreChannels, params_opt), d) =>
      // we convert extra routing info provided in the payment request to fake channel_update
      // it takes precedence over all other channel_updates we know
      val assistedChannels: Map[ShortChannelId, AssistedChannel] = assistedRoutes.flatMap(toAssistedChannels(_, end, amount)).toMap
      val extraEdges = assistedChannels.values.map(ac => GraphEdge(ChannelDesc(ac.extraHop.shortChannelId, ac.extraHop.nodeId, ac.nextNodeId), toFakeUpdate(ac.extraHop, ac.htlcMaximum))).toSet
      val ignoredEdges = ignoreChannels ++ d.excludedChannels
      val params = params_opt.getOrElse(defaultRouteParams)
      val routesToFind = if (params.randomize) DEFAULT_ROUTES_COUNT else 1

      log.info(s"finding a route $start->$end with assistedChannels={} ignoreNodes={} ignoreChannels={} excludedChannels={}", assistedChannels.keys.mkString(","), ignoreNodes.map(_.value).mkString(","), ignoreChannels.mkString(","), d.excludedChannels.mkString(","))
      log.info(s"finding a route with randomize={} params={}", routesToFind > 1, params)
      findRoute(d.graph, start, end, amount, numRoutes = routesToFind, extraEdges = extraEdges, ignoredEdges = ignoredEdges, ignoredVertices = ignoreNodes, routeParams = params, nodeParams.currentBlockHeight)
        .map(r => sender ! RouteResponse(r, ignoreNodes, ignoreChannels))
        .recover { case t => sender ! Status.Failure(t) }
      stay

    case Event(SendChannelQuery(remoteNodeId, remote, flags_opt), d) =>
      // ask for everything
      // we currently send only one query_channel_range message per peer, when we just (re)connected to it, so we don't
      // have to worry about sending a new query_channel_range when another query is still in progress
      val query = QueryChannelRange(nodeParams.chainHash, firstBlockNum = 0L, numberOfBlocks = Int.MaxValue.toLong, TlvStream(flags_opt.toList))
      log.info("sending query_channel_range={}", query)
      remote ! query

      // we also set a pass-all filter for now (we can update it later) for the future gossip messages, by setting
      // the first_timestamp field to the current date/time and timestamp_range to the maximum value
      // NB: we can't just set firstTimestamp to 0, because in that case peer would send us all past messages matching
      // that (i.e. the whole routing table)
      val filter = GossipTimestampFilter(nodeParams.chainHash, firstTimestamp = Platform.currentTime.milliseconds.toSeconds, timestampRange = Int.MaxValue)
      remote ! filter

      // clean our sync state for this peer: we receive a SendChannelQuery just when we connect/reconnect to a peer and
      // will start a new complete sync process
      stay using d.copy(sync = d.sync - remoteNodeId)

    // Warning: order matters here, this must be the first match for HasChainHash messages !
    case Event(PeerRoutingMessage(_, _, routingMessage: HasChainHash), _) if routingMessage.chainHash != nodeParams.chainHash =>
      sender ! TransportHandler.ReadAck(routingMessage)
      log.warning("message {} for wrong chain {}, we're on {}", routingMessage, routingMessage.chainHash, nodeParams.chainHash)
      stay

    case Event(u: ChannelUpdate, d: Data) =>
      // it was sent by us (e.g. the payment lifecycle); routing messages that are sent by our peers are wrapped in a PeerRoutingMessage
      log.debug("received channel update from {}", sender)
      stay using handle(u, LocalGossip, d)

    case Event(PeerRoutingMessage(transport, remoteNodeId, u: ChannelUpdate), d) =>
      sender ! TransportHandler.ReadAck(u)
      log.debug("received channel update for shortChannelId={}", u.shortChannelId)
      stay using handle(u, RemoteGossip(sender), d, remoteNodeId_opt = Some(remoteNodeId), transport_opt = Some(transport))

    case Event(PeerRoutingMessage(_, _, c: ChannelAnnouncement), d) =>
      log.debug("received channel announcement for shortChannelId={} nodeId1={} nodeId2={}", c.shortChannelId, c.nodeId1, c.nodeId2)
      if (d.channels.contains(c.shortChannelId)) {
        sender ! TransportHandler.ReadAck(c)
        log.debug("ignoring {} (duplicate)", c)
        stay
      } else if (d.awaiting.contains(c)) {
        sender ! TransportHandler.ReadAck(c)
        log.debug("ignoring {} (being verified)", c)
        // adding the sender to the list of origins so that we don't send back the same announcement to this peer later
        val origins = d.awaiting(c) :+ RemoteGossip(sender)
        stay using d.copy(awaiting = d.awaiting + (c -> origins))
      } else if (db.isPruned(c.shortChannelId)) {
        sender ! TransportHandler.ReadAck(c)
        // channel was pruned and we haven't received a recent channel_update, so we have no reason to revalidate it
        log.debug("ignoring {} (was pruned)", c)
        stay
      } else if (!Announcements.checkSigs(c)) {
        sender ! TransportHandler.ReadAck(c)
        log.warning("bad signature for announcement {}", c)
        sender ! InvalidSignature(c)
        stay
      } else {
        log.info("validating shortChannelId={}", c.shortChannelId)
        Kamon.runWithContextEntry(shortChannelIdKey, c.shortChannelId) {
          Kamon.runWithSpan(Kamon.spanBuilder("validate-channel").tag("shortChannelId", c.shortChannelId.toString).start(), finishSpan = false) {
            watcher ! ValidateRequest(c)
          }
        }
        // we don't acknowledge the message just yet
        stay using d.copy(awaiting = d.awaiting + (c -> Seq(RemoteGossip(sender))))
      }

    case Event(n: NodeAnnouncement, d: Data) =>
      // it was sent by us, routing messages that are sent by our peers are wrapped in a PeerRoutingMessage
      log.debug("received node announcement from {}", sender)
      stay using handle(n, LocalGossip, d)

    case Event(PeerRoutingMessage(_, _, n: NodeAnnouncement), d: Data) =>
      sender ! TransportHandler.ReadAck(n)
      log.debug("received node announcement for nodeId={}", n.nodeId)
      stay using handle(n, RemoteGossip(sender), d)

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage@QueryChannelRange(chainHash, firstBlockNum, numberOfBlocks, extendedQueryFlags_opt)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      Kamon.runWithContextEntry(remoteNodeIdKey, remoteNodeId.toString) {
        Kamon.runWithSpan(Kamon.spanBuilder("query-channel-range").start(), finishSpan = true) {
          log.info("received query_channel_range with firstBlockNum={} numberOfBlocks={} extendedQueryFlags_opt={}", firstBlockNum, numberOfBlocks, extendedQueryFlags_opt)
          // keep channel ids that are in [firstBlockNum, firstBlockNum + numberOfBlocks]
          val shortChannelIds: SortedSet[ShortChannelId] = d.channels.keySet.filter(keep(firstBlockNum, numberOfBlocks, _))
          log.info("replying with {} items for range=({}, {})", shortChannelIds.size, firstBlockNum, numberOfBlocks)
          val chunks = Kamon.runWithSpan(Kamon.spanBuilder("split-channel-ids").start(), finishSpan = true) {
            split(shortChannelIds, firstBlockNum, numberOfBlocks, nodeParams.routerConf.channelRangeChunkSize)
          }

          Kamon.runWithSpan(Kamon.spanBuilder("compute-timestamps-checksums").start(), finishSpan = true) {
            chunks.foreach { chunk =>
              val reply = Router.buildReplyChannelRange(chunk, chainHash, nodeParams.routerConf.encodingType, routingMessage.queryFlags_opt, d.channels)
              transport ! reply
            }
          }
          stay
        }
      }

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage@ReplyChannelRange(chainHash, _, _, _, shortChannelIds, _)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)

      Kamon.runWithContextEntry(remoteNodeIdKey, remoteNodeId.toString) {
        Kamon.runWithSpan(Kamon.spanBuilder("reply-channel-range").start(), finishSpan = true) {

          @tailrec
          def loop(ids: List[ShortChannelId], timestamps: List[ReplyChannelRangeTlv.Timestamps], checksums: List[ReplyChannelRangeTlv.Checksums], acc: List[ShortChannelIdAndFlag] = List.empty[ShortChannelIdAndFlag]): List[ShortChannelIdAndFlag] = {
            ids match {
              case Nil => acc.reverse
              case head :: tail =>
                val flag = computeFlag(d.channels)(head, timestamps.headOption, checksums.headOption, nodeParams.routerConf.requestNodeAnnouncements)
                // 0 means nothing to query, just don't include it
                val acc1 = if (flag != 0) ShortChannelIdAndFlag(head, flag) :: acc else acc
                loop(tail, timestamps.drop(1), checksums.drop(1), acc1)
            }
          }

          val timestamps_opt = routingMessage.timestamps_opt.map(_.timestamps).getOrElse(List.empty[ReplyChannelRangeTlv.Timestamps])
          val checksums_opt = routingMessage.checksums_opt.map(_.checksums).getOrElse(List.empty[ReplyChannelRangeTlv.Checksums])

          val shortChannelIdAndFlags = Kamon.runWithSpan(Kamon.spanBuilder("compute-flags").start(), finishSpan = true) {
            loop(shortChannelIds.array, timestamps_opt, checksums_opt)
          }

          val (channelCount, updatesCount) = shortChannelIdAndFlags.foldLeft((0, 0)) {
            case ((c, u), ShortChannelIdAndFlag(_, flag)) =>
              val c1 = c + (if (QueryShortChannelIdsTlv.QueryFlagType.includeChannelAnnouncement(flag)) 1 else 0)
              val u1 = u + (if (QueryShortChannelIdsTlv.QueryFlagType.includeUpdate1(flag)) 1 else 0) + (if (QueryShortChannelIdsTlv.QueryFlagType.includeUpdate2(flag)) 1 else 0)
              (c1, u1)
          }
          log.info(s"received reply_channel_range with {} channels, we're missing {} channel announcements and {} updates, format={}", shortChannelIds.array.size, channelCount, updatesCount, shortChannelIds.encoding)

          def buildQuery(chunk: List[ShortChannelIdAndFlag]): QueryShortChannelIds = {
            // always encode empty lists as UNCOMPRESSED
            val encoding = if (chunk.isEmpty) EncodingType.UNCOMPRESSED else shortChannelIds.encoding
            QueryShortChannelIds(chainHash,
              shortChannelIds = EncodedShortChannelIds(encoding, chunk.map(_.shortChannelId)),
              if (routingMessage.timestamps_opt.isDefined || routingMessage.checksums_opt.isDefined)
                TlvStream(QueryShortChannelIdsTlv.EncodedQueryFlags(encoding, chunk.map(_.flag)))
              else
                TlvStream.empty
            )
          }

          // we update our sync data to this node (there may be multiple channel range responses and we can only query one set of ids at a time)
          val replies = shortChannelIdAndFlags
            .grouped(nodeParams.routerConf.channelQueryChunkSize)
            .map(buildQuery)
            .toList

          val (sync1, replynow_opt) = addToSync(d.sync, remoteNodeId, replies)
          // we only send a reply right away if there were no pending requests
          replynow_opt.foreach(transport ! _)
          val progress = syncProgress(sync1)
          context.system.eventStream.publish(progress)
          self ! progress
          stay using d.copy(sync = sync1)
        }
      }

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage@QueryShortChannelIds(chainHash, shortChannelIds, _)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)

      Kamon.runWithContextEntry(remoteNodeIdKey, remoteNodeId.toString) {
        Kamon.runWithSpan(Kamon.spanBuilder("query-short-channel-ids").start(), finishSpan = true) {

          val flags = routingMessage.queryFlags_opt.map(_.array).getOrElse(List.empty[Long])

          var channelCount = 0
          var updateCount = 0
          var nodeCount = 0

          Router.processChannelQuery(d.nodes, d.channels)(
            shortChannelIds.array,
            flags,
            ca => {
              channelCount = channelCount + 1
              transport ! ca
            },
            cu => {
              updateCount = updateCount + 1
              transport ! cu
            },
            na => {
              nodeCount = nodeCount + 1
              transport ! na
            }
          )
          log.info("received query_short_channel_ids with {} items, sent back {} channels and {} updates and {} nodes", shortChannelIds.array.size, channelCount, updateCount, nodeCount)
          transport ! ReplyShortChannelIdsEnd(chainHash, 1)
          stay
        }
      }

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage: ReplyShortChannelIdsEnd), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      // have we more channels to ask this peer?
      val sync1 = d.sync.get(remoteNodeId) match {
        case Some(sync) =>
          sync.pending match {
            case nextRequest +: rest =>
              log.info(s"asking for the next slice of short_channel_ids (remaining=${sync.pending.size}/${sync.total})")
              transport ! nextRequest
              d.sync + (remoteNodeId -> sync.copy(pending = rest))
            case Nil =>
              // we received reply_short_channel_ids_end for our last query and have not sent another one, we can now remove
              // the remote peer from our map
              log.info(s"sync complete (total=${sync.total})")
              d.sync - remoteNodeId
          }
        case _ => d.sync
      }
      val progress = syncProgress(sync1)
      context.system.eventStream.publish(progress)
      self ! progress
      stay using d.copy(sync = sync1)

  }

  initialize()

  def handle(n: NodeAnnouncement, origin: GossipOrigin, d: Data): Data =
    if (d.stash.nodes.contains(n)) {
      log.debug("ignoring {} (already stashed)", n)
      val origins = d.stash.nodes(n) + origin
      d.copy(stash = d.stash.copy(nodes = d.stash.nodes + (n -> origins)))
    } else if (d.rebroadcast.nodes.contains(n)) {
      log.debug("ignoring {} (pending rebroadcast)", n)
      val origins = d.rebroadcast.nodes(n) + origin
      d.copy(rebroadcast = d.rebroadcast.copy(nodes = d.rebroadcast.nodes + (n -> origins)))
    } else if (d.nodes.contains(n.nodeId) && d.nodes(n.nodeId).timestamp >= n.timestamp) {
      log.debug("ignoring {} (duplicate)", n)
      d
    } else if (!Announcements.checkSig(n)) {
      log.warning("bad signature for {}", n)
      origin match {
        case RemoteGossip(peer) => peer ! InvalidSignature(n)
        case LocalGossip =>
      }
      d
    } else if (d.nodes.contains(n.nodeId)) {
      log.debug("updated node nodeId={}", n.nodeId)
      context.system.eventStream.publish(NodeUpdated(n))
      db.updateNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n), rebroadcast = d.rebroadcast.copy(nodes = d.rebroadcast.nodes + (n -> Set(origin))))
    } else if (d.channels.values.exists(c => isRelatedTo(c.ann, n.nodeId))) {
      log.debug("added node nodeId={}", n.nodeId)
      context.system.eventStream.publish(NodesDiscovered(n :: Nil))
      db.addNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n), rebroadcast = d.rebroadcast.copy(nodes = d.rebroadcast.nodes + (n -> Set(origin))))
    } else if (d.awaiting.keys.exists(c => isRelatedTo(c, n.nodeId))) {
      log.debug("stashing {}", n)
      d.copy(stash = d.stash.copy(nodes = d.stash.nodes + (n -> Set(origin))))
    } else {
      log.debug("ignoring {} (no related channel found)", n)
      // there may be a record if we have just restarted
      db.removeNode(n.nodeId)
      d
    }

  def handle(u: ChannelUpdate, origin: GossipOrigin, d: Data, remoteNodeId_opt: Option[PublicKey] = None, transport_opt: Option[ActorRef] = None): Data =
    if (d.channels.contains(u.shortChannelId)) {
      // related channel is already known (note: this means no related channel_update is in the stash)
      val publicChannel = true
      val pc = d.channels(u.shortChannelId)
      val desc = getDesc(u, pc.ann)
      if (d.rebroadcast.updates.contains(u)) {
        log.debug("ignoring {} (pending rebroadcast)", u)
        val origins = d.rebroadcast.updates(u) + origin
        d.copy(rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> origins)))
      } else if (isStale(u)) {
        log.debug("ignoring {} (stale)", u)
        d
      } else if (pc.getChannelUpdateSameSideAs(u).exists(_.timestamp >= u.timestamp)) {
        log.debug("ignoring {} (duplicate)", u)
        d
      } else if (!Announcements.checkSig(u, pc.getNodeIdSameSideAs(u))) {
        log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId, u)
        origin match {
          case RemoteGossip(peer) => peer ! InvalidSignature(u)
          case LocalGossip =>
        }
        d
      } else if (pc.getChannelUpdateSameSideAs(u).isDefined) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        context.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        db.updateChannel(u)
        // update the graph
        val graph1 = Announcements.isEnabled(u.channelFlags) match {
          case true => d.graph.removeEdge(desc).addEdge(desc, u)
          case false => d.graph.removeEdge(desc) // if the channel is now disabled, we remove it from the graph
        }
        d.copy(channels = d.channels + (u.shortChannelId -> pc.updateChannelUpdateSameSideAs(u)), rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> Set(origin))), graph = graph1)
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        context.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        db.updateChannel(u)
        // we also need to update the graph
        val graph1 = d.graph.addEdge(desc, u)
        d.copy(channels = d.channels + (u.shortChannelId -> pc.updateChannelUpdateSameSideAs(u)), privateChannels = d.privateChannels - u.shortChannelId, rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> Set(origin))), graph = graph1)
      }
    } else if (d.awaiting.keys.exists(c => c.shortChannelId == u.shortChannelId)) {
      // channel is currently being validated
      if (d.stash.updates.contains(u)) {
        log.debug("ignoring {} (already stashed)", u)
        val origins = d.stash.updates(u) + origin
        d.copy(stash = d.stash.copy(updates = d.stash.updates + (u -> origins)))
      } else {
        log.debug("stashing {}", u)
        d.copy(stash = d.stash.copy(updates = d.stash.updates + (u -> Set(origin))))
      }
    } else if (d.privateChannels.contains(u.shortChannelId)) {
      val publicChannel = false
      val pc = d.privateChannels(u.shortChannelId)
      val desc = if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, pc.nodeId1, pc.nodeId2) else ChannelDesc(u.shortChannelId, pc.nodeId2, pc.nodeId1)
      if (isStale(u)) {
        log.debug("ignoring {} (stale)", u)
        d
      } else if (pc.getChannelUpdateSameSideAs(u).exists(_.timestamp >= u.timestamp)) {
        log.debug("ignoring {} (already know same or newer)", u)
        d
      } else if (!Announcements.checkSig(u, desc.a)) {
        log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId, u)
        origin match {
          case RemoteGossip(peer) => peer ! InvalidSignature(u)
          case LocalGossip =>
        }
        d
      } else if (pc.getChannelUpdateSameSideAs(u).isDefined) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        context.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        // we also need to update the graph
        val graph1 = d.graph.removeEdge(desc).addEdge(desc, u)
        d.copy(privateChannels = d.privateChannels + (u.shortChannelId -> pc.updateChannelUpdateSameSideAs(u)), graph = graph1)
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        context.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        // we also need to update the graph
        val graph1 = d.graph.addEdge(desc, u)
        d.copy(privateChannels = d.privateChannels + (u.shortChannelId -> pc.updateChannelUpdateSameSideAs(u)), graph = graph1)
      }
    } else if (db.isPruned(u.shortChannelId) && !isStale(u)) {
      // the channel was recently pruned, but if we are here, it means that the update is not stale so this is the case
      // of a zombie channel coming back from the dead. they probably sent us a channel_announcement right before this update,
      // but we ignored it because the channel was in the 'pruned' list. Now that we know that the channel is alive again,
      // let's remove the channel from the zombie list and ask the sender to re-send announcements (channel_announcement + updates)
      // about that channel. We can ignore this update since we will receive it again
      log.info(s"channel shortChannelId=${u.shortChannelId} is back from the dead! requesting announcements about this channel")
      db.removeFromPruned(u.shortChannelId)

      // transport_opt will contain a valid transport only when we're handling an update that we received from a peer, not
      // when we're sending updates to ourselves
      (transport_opt, remoteNodeId_opt) match {
        case (Some(transport), Some(remoteNodeId)) =>
          val query = QueryShortChannelIds(u.chainHash, EncodedShortChannelIds(nodeParams.routerConf.encodingType, List(u.shortChannelId)), TlvStream.empty)
          d.sync.get(remoteNodeId) match {
            case Some(sync) =>
              // we already have a pending request to that node, let's add this channel to the list and we'll get it later
              // TODO: we only request channels with old style channel_query
              d.copy(sync = d.sync + (remoteNodeId -> sync.copy(pending = sync.pending :+ query, total = sync.total + 1)))
            case None =>
              // we send the query right away
              transport ! query
              d.copy(sync = d.sync + (remoteNodeId -> Sync(pending = Nil, total = 1)))
          }
        case _ =>
          // we don't know which node this update came from (maybe it was stashed and the channel got pruned in the meantime or some other corner case).
          // or we don't have a transport to send our query to.
          // anyway, that's not really a big deal because we have removed the channel from the pruned db so next time it shows up we will revalidate it
          d
      }
    } else {
      log.debug("ignoring announcement {} (unknown channel)", u)
      d
    }

  override def mdc(currentMessage: Any): MDC = {
    val category_opt = LogCategory(currentMessage)
    currentMessage match {
      case SendChannelQuery(remoteNodeId, _, _) => Logs.mdc(category_opt, remoteNodeId_opt = Some(remoteNodeId))
      case PeerRoutingMessage(_, remoteNodeId, _) => Logs.mdc(category_opt, remoteNodeId_opt = Some(remoteNodeId))
      case LocalChannelUpdate(_, _, _, remoteNodeId, _, _, _) => Logs.mdc(category_opt, remoteNodeId_opt = Some(remoteNodeId))
      case _ => Logs.mdc(category_opt)
    }
  }
}

object Router {

  val shortChannelIdKey = Context.key[ShortChannelId]("shortChannelId", ShortChannelId(0))
  val remoteNodeIdKey = Context.key[String]("remoteNodeId", "unknown")

  // maximum number of ids we can keep in a single chunk and still have an encoded reply that is smaller than 65Kb
  // please note that:
  // - this is based on the worst case scenario where peer want timestamps and checksums and the reply is not compressed
  // - the maximum number of public channels in a single block so far is less than 300, and the maximum number of tx per block
  // almost never exceeds 2800 so this is not a real limitation yet
  val MAXIMUM_CHUNK_SIZE = 3200

  def props(nodeParams: NodeParams, watcher: ActorRef, initialized: Option[Promise[Done]] = None) = Props(new Router(nodeParams, watcher, initialized))

  def toFakeUpdate(extraHop: ExtraHop, htlcMaximum: MilliSatoshi): ChannelUpdate = {
    // the `direction` bit in flags will not be accurate but it doesn't matter because it is not used
    // what matters is that the `disable` bit is 0 so that this update doesn't get filtered out
    ChannelUpdate(signature = ByteVector64.Zeroes, chainHash = ByteVector32.Zeroes, extraHop.shortChannelId, Platform.currentTime.milliseconds.toSeconds, messageFlags = 1, channelFlags = 0, extraHop.cltvExpiryDelta, htlcMinimumMsat = 0 msat, extraHop.feeBase, extraHop.feeProportionalMillionths, Some(htlcMaximum))
  }

  def toAssistedChannels(extraRoute: Seq[ExtraHop], targetNodeId: PublicKey, amount: MilliSatoshi): Map[ShortChannelId, AssistedChannel] = {
    // BOLT 11: "For each entry, the pubkey is the node ID of the start of the channel", and the last node is the destination
    // The invoice doesn't explicitly specify the channel's htlcMaximumMsat, but we can safely assume that the channel
    // should be able to route the payment, so we'll compute an htlcMaximumMsat accordingly.
    // We could also get the channel capacity from the blockchain (since we have the shortChannelId) but that's more expensive.
    // We also need to make sure the channel isn't excluded by our heuristics.
    val lastChannelCapacity = amount.max(RoutingHeuristics.CAPACITY_CHANNEL_LOW)
    val nextNodeIds = extraRoute.map(_.nodeId).drop(1) :+ targetNodeId
    extraRoute.zip(nextNodeIds).reverse.foldLeft((lastChannelCapacity, Map.empty[ShortChannelId, AssistedChannel])) {
      case ((amount, acs), (extraHop: ExtraHop, nextNodeId)) =>
        val nextAmount = amount + nodeFee(extraHop.feeBase, extraHop.feeProportionalMillionths, amount)
        (nextAmount, acs + (extraHop.shortChannelId -> AssistedChannel(extraHop, nextNodeId, nextAmount)))
    }._2
  }

  def getDesc(u: ChannelUpdate, channel: ChannelAnnouncement): ChannelDesc = {
    // the least significant bit tells us if it is node1 or node2
    if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, channel.nodeId1, channel.nodeId2) else ChannelDesc(u.shortChannelId, channel.nodeId2, channel.nodeId1)
  }

  def isRelatedTo(c: ChannelAnnouncement, nodeId: PublicKey) = nodeId == c.nodeId1 || nodeId == c.nodeId2

  def hasChannels(nodeId: PublicKey, channels: Iterable[PublicChannel]): Boolean = channels.exists(c => isRelatedTo(c.ann, nodeId))

  def isStale(u: ChannelUpdate): Boolean = isStale(u.timestamp)

  def isStale(timestamp: Long): Boolean = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update
    val staleThresholdSeconds = (Platform.currentTime.milliseconds - 14.days).toSeconds
    timestamp < staleThresholdSeconds
  }

  def isAlmostStale(timestamp: Long): Boolean = {
    // we define almost stale as 2 weeks minus 4 days
    val staleThresholdSeconds = (Platform.currentTime.milliseconds - 10.days).toSeconds
    timestamp < staleThresholdSeconds
  }

  /**
   * Is stale a channel that:
   * (1) is older than 2 weeks (2*7*144 = 2016 blocks)
   * AND
   * (2) has no channel_update younger than 2 weeks
   *
   * @param update1_opt update corresponding to one side of the channel, if we have it
   * @param update2_opt update corresponding to the other side of the channel, if we have it
   * @return
   */
  def isStale(channel: ChannelAnnouncement, update1_opt: Option[ChannelUpdate], update2_opt: Option[ChannelUpdate], currentBlockHeight: Long): Boolean = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks (1209600 seconds)"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update, so we keep them as long as they are less than 2 weeks (2016 blocks) old
    val staleThresholdBlocks = currentBlockHeight - 2016
    val TxCoordinates(blockHeight, _, _) = ShortChannelId.coordinates(channel.shortChannelId)
    blockHeight < staleThresholdBlocks && update1_opt.forall(isStale) && update2_opt.forall(isStale)
  }

  def getStaleChannels(channels: Iterable[PublicChannel], currentBlockHeight: Long): Iterable[PublicChannel] = channels.filter(data => isStale(data.ann, data.update_1_opt, data.update_2_opt, currentBlockHeight))

  /**
   * Filters channels that we want to send to nodes asking for a channel range
   */
  def keep(firstBlockNum: Long, numberOfBlocks: Long, id: ShortChannelId): Boolean = {
    val height = id.blockHeight
    height >= firstBlockNum && height < (firstBlockNum + numberOfBlocks)
  }

  def shouldRequestUpdate(ourTimestamp: Long, ourChecksum: Long, theirTimestamp_opt: Option[Long], theirChecksum_opt: Option[Long]): Boolean = {
    (theirTimestamp_opt, theirChecksum_opt) match {
      case (Some(theirTimestamp), Some(theirChecksum)) =>
        // we request their channel_update if all those conditions are met:
        // - it is more recent than ours
        // - it is different from ours, or it is the same but ours is about to be stale
        // - it is not stale
        val theirsIsMoreRecent = ourTimestamp < theirTimestamp
        val areDifferent = ourChecksum != theirChecksum
        val oursIsAlmostStale = isAlmostStale(ourTimestamp)
        val theirsIsStale = isStale(theirTimestamp)
        theirsIsMoreRecent && (areDifferent || oursIsAlmostStale) && !theirsIsStale
      case (Some(theirTimestamp), None) =>
        // if we only have their timestamp, we request their channel_update if theirs is more recent than ours
        val theirsIsMoreRecent = ourTimestamp < theirTimestamp
        val theirsIsStale = isStale(theirTimestamp)
        theirsIsMoreRecent && !theirsIsStale
      case (None, Some(theirChecksum)) =>
        // if we only have their checksum, we request their channel_update if it is different from ours
        // NB: a zero checksum means that they don't have the data
        val areDifferent = theirChecksum != 0 && ourChecksum != theirChecksum
        areDifferent
      case (None, None) =>
        // if we have neither their timestamp nor their checksum we request their channel_update
        true
    }
  }

  def computeFlag(channels: SortedMap[ShortChannelId, PublicChannel])(
    shortChannelId: ShortChannelId,
    theirTimestamps_opt: Option[ReplyChannelRangeTlv.Timestamps],
    theirChecksums_opt: Option[ReplyChannelRangeTlv.Checksums],
    includeNodeAnnouncements: Boolean): Long = {
    import QueryShortChannelIdsTlv.QueryFlagType._

    val flagsNodes = if (includeNodeAnnouncements) INCLUDE_NODE_ANNOUNCEMENT_1 | INCLUDE_NODE_ANNOUNCEMENT_2 else 0

    val flags = if (!channels.contains(shortChannelId)) {
      INCLUDE_CHANNEL_ANNOUNCEMENT | INCLUDE_CHANNEL_UPDATE_1 | INCLUDE_CHANNEL_UPDATE_2
    } else {
      // we already know this channel
      val (ourTimestamps, ourChecksums) = Router.getChannelDigestInfo(channels)(shortChannelId)
      // if they don't provide timestamps or checksums, we set appropriate default values:
      // - we assume their timestamp is more recent than ours by setting timestamp = Long.MaxValue
      // - we assume their update is different from ours by setting checkum = Long.MaxValue (NB: our default value for checksum is 0)
      val shouldRequestUpdate1 = shouldRequestUpdate(ourTimestamps.timestamp1, ourChecksums.checksum1, theirTimestamps_opt.map(_.timestamp1), theirChecksums_opt.map(_.checksum1))
      val shouldRequestUpdate2 = shouldRequestUpdate(ourTimestamps.timestamp2, ourChecksums.checksum2, theirTimestamps_opt.map(_.timestamp2), theirChecksums_opt.map(_.checksum2))
      val flagUpdate1 = if (shouldRequestUpdate1) INCLUDE_CHANNEL_UPDATE_1 else 0
      val flagUpdate2 = if (shouldRequestUpdate2) INCLUDE_CHANNEL_UPDATE_2 else 0
      flagUpdate1 | flagUpdate2
    }

    if (flags == 0) 0 else flags | flagsNodes
  }

  /**
   * Handle a query message, which includes a list of channel ids and flags.
   *
   * @param nodes     node id -> node announcement
   * @param channels  channel id -> channel announcement + updates
   * @param ids       list of channel ids
   * @param flags     list of query flags, either empty one flag per channel id
   * @param onChannel called when a channel announcement matches (i.e. its bit is set in the query flag and we have it)
   * @param onUpdate  called when a channel update matches
   * @param onNode    called when a node announcement matches
   *
   */
  def processChannelQuery(nodes: Map[PublicKey, NodeAnnouncement],
                          channels: SortedMap[ShortChannelId, PublicChannel])(
                           ids: List[ShortChannelId],
                           flags: List[Long],
                           onChannel: ChannelAnnouncement => Unit,
                           onUpdate: ChannelUpdate => Unit,
                           onNode: NodeAnnouncement => Unit)(implicit log: LoggingAdapter): Unit = {
    import QueryShortChannelIdsTlv.QueryFlagType

    // we loop over channel ids and query flag. We track node Ids for node announcement
    // we've already sent to avoid sending them multiple times, as requested by the BOLTs
    @tailrec
    def loop(ids: List[ShortChannelId], flags: List[Long], numca: Int = 0, numcu: Int = 0, nodesSent: Set[PublicKey] = Set.empty[PublicKey]): (Int, Int, Int) = ids match {
      case Nil => (numca, numcu, nodesSent.size)
      case head :: tail if !channels.contains(head) =>
        log.warning("received query for shortChannelId={} that we don't have", head)
        loop(tail, flags.drop(1), numca, numcu, nodesSent)
      case head :: tail =>
        val numca1 = numca
        val numcu1 = numcu
        var sent1 = nodesSent
        val pc = channels(head)
        val flag_opt = flags.headOption
        // no flag means send everything

        val includeChannel = flag_opt.forall(QueryFlagType.includeChannelAnnouncement)
        val includeUpdate1 = flag_opt.forall(QueryFlagType.includeUpdate1)
        val includeUpdate2 = flag_opt.forall(QueryFlagType.includeUpdate2)
        val includeNode1 = flag_opt.forall(QueryFlagType.includeNodeAnnouncement1)
        val includeNode2 = flag_opt.forall(QueryFlagType.includeNodeAnnouncement2)

        if (includeChannel) {
          onChannel(pc.ann)
        }
        if (includeUpdate1) {
          pc.update_1_opt.foreach { u =>
            onUpdate(u)
          }
        }
        if (includeUpdate2) {
          pc.update_2_opt.foreach { u =>
            onUpdate(u)
          }
        }
        if (includeNode1 && !sent1.contains(pc.ann.nodeId1)) {
          nodes.get(pc.ann.nodeId1).foreach { n =>
            onNode(n)
            sent1 = sent1 + pc.ann.nodeId1
          }
        }
        if (includeNode2 && !sent1.contains(pc.ann.nodeId2)) {
          nodes.get(pc.ann.nodeId2).foreach { n =>
            onNode(n)
            sent1 = sent1 + pc.ann.nodeId2
          }
        }
        loop(tail, flags.drop(1), numca1, numcu1, sent1)
    }

    loop(ids, flags)
  }

  /**
   * Returns overall progress on synchronization
   *
   * @return a sync progress indicator (1 means fully synced)
   */
  def syncProgress(sync: Map[PublicKey, Sync]): SyncProgress = {
    // NB: progress is in terms of requests, not individual channels
    val (pending, total) = sync.foldLeft((0, 0)) {
      case ((p, t), (_, sync)) => (p + sync.pending.size, t + sync.total)
    }
    if (total == 0) {
      SyncProgress(1)
    } else {
      SyncProgress((total - pending) / (1.0 * total))
    }
  }

  /**
   * This method is used after a payment failed, and we want to exclude some nodes that we know are failing
   */
  def getIgnoredChannelDesc(channels: Map[ShortChannelId, PublicChannel], ignoreNodes: Set[PublicKey]): Iterable[ChannelDesc] = {
    val desc = if (ignoreNodes.isEmpty) {
      Iterable.empty[ChannelDesc]
    } else {
      // expensive, but node blacklisting shouldn't happen often
      channels.values
        .filter(channelData => ignoreNodes.contains(channelData.ann.nodeId1) || ignoreNodes.contains(channelData.ann.nodeId2))
        .flatMap(channelData => Vector(ChannelDesc(channelData.ann.shortChannelId, channelData.ann.nodeId1, channelData.ann.nodeId2), ChannelDesc(channelData.ann.shortChannelId, channelData.ann.nodeId2, channelData.ann.nodeId1)))
    }
    desc
  }

  def getChannelDigestInfo(channels: SortedMap[ShortChannelId, PublicChannel])(shortChannelId: ShortChannelId): (ReplyChannelRangeTlv.Timestamps, ReplyChannelRangeTlv.Checksums) = {
    val c = channels(shortChannelId)
    val timestamp1 = c.update_1_opt.map(_.timestamp).getOrElse(0L)
    val timestamp2 = c.update_2_opt.map(_.timestamp).getOrElse(0L)
    val checksum1 = c.update_1_opt.map(getChecksum).getOrElse(0L)
    val checksum2 = c.update_2_opt.map(getChecksum).getOrElse(0L)
    (ReplyChannelRangeTlv.Timestamps(timestamp1 = timestamp1, timestamp2 = timestamp2), ReplyChannelRangeTlv.Checksums(checksum1 = checksum1, checksum2 = checksum2))
  }

  def crc32c(data: ByteVector): Long = {
    import com.google.common.hash.Hashing
    Hashing.crc32c().hashBytes(data.toArray).asInt() & 0xFFFFFFFFL
  }

  def getChecksum(u: ChannelUpdate): Long = {
    import u._

    val data = serializationResult(LightningMessageCodecs.channelUpdateChecksumCodec.encode(chainHash :: shortChannelId :: messageFlags :: channelFlags :: cltvExpiryDelta :: htlcMinimumMsat :: feeBaseMsat :: feeProportionalMillionths :: htlcMaximumMsat :: HNil))
    crc32c(data)
  }

  case class ShortChannelIdsChunk(firstBlock: Long, numBlocks: Long, shortChannelIds: List[ShortChannelId]) {
    /**
     *
     * @param maximumSize maximum size of the short channel ids list
     * @return a chunk with at most `maximumSize` ids
     */
    def enforceMaximumSize(maximumSize: Int) = {
      if (shortChannelIds.size <= maximumSize) this else {
        // we use a random offset here, so even if shortChannelIds.size is much bigger than maximumSize (which should
        // not happen) peers will eventually receive info about all channels in this chunk
        val offset = Random.nextInt(shortChannelIds.size - maximumSize + 1)
        this.copy(shortChannelIds = this.shortChannelIds.slice(offset, offset + maximumSize))
      }
    }
  }

  /**
   * Split short channel ids into chunks, because otherwise message could be too big
   * there could be several reply_channel_range messages for a single query, but we make sure that the returned
   * chunks fully covers the [firstBlockNum, numberOfBlocks] range that was requested
   *
   * @param shortChannelIds       list of short channel ids to split
   * @param firstBlockNum         first block height requested by our peers
   * @param numberOfBlocks        number of blocks requested by our peer
   * @param channelRangeChunkSize target chunk size. All ids that have the same block height will be grouped together, so
   *                              returned chunks may still contain more than `channelRangeChunkSize` elements
   * @return a list of short channel id chunks
   */
  def split(shortChannelIds: SortedSet[ShortChannelId], firstBlockNum: Long, numberOfBlocks: Long, channelRangeChunkSize: Int): List[ShortChannelIdsChunk] = {
    // see BOLT7: MUST encode a short_channel_id for every open channel it knows in blocks first_blocknum to first_blocknum plus number_of_blocks minus one
    val it = shortChannelIds.iterator.dropWhile(_.blockHeight < firstBlockNum).takeWhile(_.blockHeight < firstBlockNum + numberOfBlocks)
    if (it.isEmpty) {
      List(ShortChannelIdsChunk(firstBlockNum, numberOfBlocks, List.empty))
    } else {
      // we want to split ids in different chunks, with the following rules by order of priority
      // ids that have the same block height must be grouped in the same chunk
      // chunk should contain `channelRangeChunkSize` ids
      @tailrec
      def loop(currentChunk: List[ShortChannelId], acc: List[ShortChannelIdsChunk]): List[ShortChannelIdsChunk] = {
        if (it.hasNext) {
          val id = it.next()
          val currentHeight = currentChunk.head.blockHeight
          if (id.blockHeight == currentHeight)
            loop(id :: currentChunk, acc) // same height => always add to the current chunk
          else if (currentChunk.size < channelRangeChunkSize) // different height but we're under the size target => add to the current chunk
            loop(id :: currentChunk, acc) // different height and over the size target => start a new chunk
          else {
            // we always prepend because it's more efficient so we have to reverse the current chunk
            // for the first chunk, we make sure that we start at the request first block
            // for the next chunks we start at the end of the range covered by the last chunk
            val first = if (acc.isEmpty) firstBlockNum else acc.head.firstBlock + acc.head.numBlocks
            val count = currentChunk.head.blockHeight - first + 1
            loop(id :: Nil, ShortChannelIdsChunk(first, count, currentChunk.reverse) :: acc)
          }
        }
        else {
          // for the last chunk, we make sure that we cover the requested block range
          val first = if (acc.isEmpty) firstBlockNum else acc.head.firstBlock + acc.head.numBlocks
          val count = numberOfBlocks - first + firstBlockNum
          (ShortChannelIdsChunk(first, count, currentChunk.reverse) :: acc).reverse
        }
      }

      val first = it.next()
      val chunks = loop(first :: Nil, Nil)

      // make sure that all our chunks match our max size policy
      enforceMaximumSize(chunks)
    }
  }

  /**
   * Enforce max-size constraints for each chunk
   *
   * @param chunks list of short channel id chunks
   * @return a processed list of chunks
   */
  def enforceMaximumSize(chunks: List[ShortChannelIdsChunk]): List[ShortChannelIdsChunk] = chunks.map(_.enforceMaximumSize(MAXIMUM_CHUNK_SIZE))

  /**
   * Build a `reply_channel_range` message
   * @param chunk chunk of scids
   * @param chainHash chain hash
   * @param defaultEncoding default encoding
   * @param queryFlags_opt query flag set by the requester
   * @param channels channels map
   * @return a ReplyChannelRange object
   */
  def buildReplyChannelRange(chunk: ShortChannelIdsChunk, chainHash: ByteVector32, defaultEncoding: EncodingType, queryFlags_opt: Option[QueryChannelRangeTlv.QueryFlags], channels: SortedMap[ShortChannelId, PublicChannel]): ReplyChannelRange = {
    val encoding = if (chunk.shortChannelIds.isEmpty) EncodingType.UNCOMPRESSED else defaultEncoding
    val (timestamps, checksums) = queryFlags_opt match {
      case Some(extension) if extension.wantChecksums | extension.wantTimestamps =>
        // we always compute timestamps and checksums even if we don't need both, overhead is negligible
        val (timestamps, checksums) = chunk.shortChannelIds.map(getChannelDigestInfo(channels)).unzip
        val encodedTimestamps = if (extension.wantTimestamps) Some(ReplyChannelRangeTlv.EncodedTimestamps(encoding, timestamps)) else None
        val encodedChecksums = if (extension.wantChecksums) Some(ReplyChannelRangeTlv.EncodedChecksums(checksums)) else None
        (encodedTimestamps, encodedChecksums)
      case _ => (None, None)
    }
    ReplyChannelRange(chainHash, chunk.firstBlock, chunk.numBlocks,
      complete = 1,
      shortChannelIds = EncodedShortChannelIds(encoding, chunk.shortChannelIds),
      timestamps = timestamps,
      checksums = checksums)
  }

  def addToSync(syncMap: Map[PublicKey, Sync], remoteNodeId: PublicKey, pending: List[RoutingMessage]): (Map[PublicKey, Sync], Option[RoutingMessage]) = {
    pending match {
      case head +: rest =>
        // they may send back several reply_channel_range messages for a single query_channel_range query, and we must not
        // send another query_short_channel_ids query if they're still processing one
        syncMap.get(remoteNodeId) match {
          case None =>
            // we don't have a pending query with this peer, let's send it
            (syncMap + (remoteNodeId -> Sync(rest, pending.size)), Some(head))
          case Some(sync) =>
            // we already have a pending query with this peer, add missing ids to our "sync" state
            (syncMap + (remoteNodeId -> Sync(sync.pending ++ pending, sync.total + pending.size)), None)
        }
      case Nil =>
        // there is nothing to send
        (syncMap, None)
    }
  }

  /**
   * https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#clarifications
   */
  val ROUTE_MAX_LENGTH = 20

  // Max allowed CLTV for a route
  val DEFAULT_ROUTE_MAX_CLTV = CltvExpiryDelta(1008)

  // The default number of routes we'll search for when findRoute is called with randomize = true
  val DEFAULT_ROUTES_COUNT = 3

  def getDefaultRouteParams(routerConf: RouterConf) = RouteParams(
    randomize = routerConf.randomizeRouteSelection,
    maxFeeBase = routerConf.searchMaxFeeBase.toMilliSatoshi,
    maxFeePct = routerConf.searchMaxFeePct,
    routeMaxLength = routerConf.searchMaxRouteLength,
    routeMaxCltv = routerConf.searchMaxCltv,
    ratios = routerConf.searchHeuristicsEnabled match {
      case false => None
      case true => Some(WeightRatios(
        cltvDeltaFactor = routerConf.searchRatioCltv,
        ageFactor = routerConf.searchRatioChannelAge,
        capacityFactor = routerConf.searchRatioChannelCapacity
      ))
    }
  )

  /**
   * Find a route in the graph between localNodeId and targetNodeId, returns the route.
   * Will perform a k-shortest path selection given the @param numRoutes and randomly select one of the result.
   *
   * @param g            graph of the whole network
   * @param localNodeId  sender node (payer)
   * @param targetNodeId target node (final recipient)
   * @param amount       the amount that will be sent along this route
   * @param numRoutes    the number of shortest-paths to find
   * @param extraEdges   a set of extra edges we want to CONSIDER during the search
   * @param ignoredEdges a set of extra edges we want to IGNORE during the search
   * @param routeParams  a set of parameters that can restrict the route search
   * @return the computed route to the destination @targetNodeId
   */
  def findRoute(g: DirectedGraph,
                localNodeId: PublicKey,
                targetNodeId: PublicKey,
                amount: MilliSatoshi,
                numRoutes: Int,
                extraEdges: Set[GraphEdge] = Set.empty,
                ignoredEdges: Set[ChannelDesc] = Set.empty,
                ignoredVertices: Set[PublicKey] = Set.empty,
                routeParams: RouteParams,
                currentBlockHeight: Long): Try[Seq[ChannelHop]] = Try {

    if (localNodeId == targetNodeId) throw CannotRouteToSelf

    def feeBaseOk(fee: MilliSatoshi): Boolean = fee <= routeParams.maxFeeBase

    def feePctOk(fee: MilliSatoshi, amount: MilliSatoshi): Boolean = {
      val maxFee = amount * routeParams.maxFeePct
      fee <= maxFee
    }

    def feeOk(fee: MilliSatoshi, amount: MilliSatoshi): Boolean = feeBaseOk(fee) || feePctOk(fee, amount)

    def lengthOk(length: Int): Boolean = length <= routeParams.routeMaxLength && length <= ROUTE_MAX_LENGTH

    def cltvOk(cltv: CltvExpiryDelta): Boolean = cltv <= routeParams.routeMaxCltv

    val boundaries: RichWeight => Boolean = { weight =>
      feeOk(weight.cost - amount, amount) && lengthOk(weight.length) && cltvOk(weight.cltv)
    }

    val foundRoutes = Graph.yenKshortestPaths(g, localNodeId, targetNodeId, amount, ignoredEdges, ignoredVertices, extraEdges, numRoutes, routeParams.ratios, currentBlockHeight, boundaries).toList match {
      case Nil if routeParams.routeMaxLength < ROUTE_MAX_LENGTH => // if not found within the constraints we relax and repeat the search
        return findRoute(g, localNodeId, targetNodeId, amount, numRoutes, extraEdges, ignoredEdges, ignoredVertices, routeParams.copy(routeMaxLength = ROUTE_MAX_LENGTH, routeMaxCltv = DEFAULT_ROUTE_MAX_CLTV), currentBlockHeight)
      case Nil => throw RouteNotFound
      case routes => routes.find(_.path.size == 1) match {
        case Some(directRoute) => directRoute :: Nil
        case _ => routes
      }
    }

    // At this point 'foundRoutes' cannot be empty
    val randomizedRoutes = if (routeParams.randomize) Random.shuffle(foundRoutes) else foundRoutes
    randomizedRoutes.head.path.map(graphEdgeToHop)
  }
}
