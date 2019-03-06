/*
 * Copyright 2018 ACINQ SAS
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
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.{ChannelClosed, InvalidAnnouncement, InvalidSignature, PeerRoutingMessage}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{RichWeight, WeightRatios}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import scala.collection.{SortedSet, mutable}
import scala.collection.immutable.{SortedMap, TreeMap}
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Random, Try}

// @formatter:off

case class RouterConf(randomizeRouteSelection: Boolean,
                      channelExcludeDuration: FiniteDuration,
                      routerBroadcastInterval: FiniteDuration,
                      searchMaxFeeBaseSat: Long,
                      searchMaxFeePct: Double,
                      searchMaxRouteLength: Int,
                      searchMaxCltv: Int,
                      searchHeuristicsEnabled: Boolean,
                      searchRatioCltv: Double,
                      searchRatioChannelAge: Double,
                      searchRatioChannelCapacity: Double)

case class ChannelDesc(shortChannelId: ShortChannelId, a: PublicKey, b: PublicKey)
case class Hop(nodeId: PublicKey, nextNodeId: PublicKey, lastUpdate: ChannelUpdate)
case class RouteParams(maxFeeBaseMsat: Long, maxFeePct: Double, routeMaxLength: Int, routeMaxCltv: Int, ratios: Option[WeightRatios])
case class RouteRequest(source: PublicKey,
                        target: PublicKey,
                        amountMsat: Long,
                        assistedRoutes: Seq[Seq[ExtraHop]] = Nil,
                        ignoreNodes: Set[PublicKey] = Set.empty,
                        ignoreChannels: Set[ChannelDesc] = Set.empty,
                        randomize: Option[Boolean] = None,
                        routeParams: Option[RouteParams] = None)

case class RouteResponse(hops: Seq[Hop], ignoreNodes: Set[PublicKey], ignoreChannels: Set[ChannelDesc]) {
  require(hops.size > 0, "route cannot be empty")
}
case class ExcludeChannel(desc: ChannelDesc) // this is used when we get a TemporaryChannelFailure, to give time for the channel to recover (note that exclusions are directed)
case class LiftChannelExclusion(desc: ChannelDesc)
case class SendChannelQuery(remoteNodeId: PublicKey, to: ActorRef)
case object GetRoutingState
case class RoutingState(channels: Iterable[ChannelAnnouncement], updates: Iterable[ChannelUpdate], nodes: Iterable[NodeAnnouncement])
case class Stash(updates: Map[ChannelUpdate, Set[ActorRef]], nodes: Map[NodeAnnouncement, Set[ActorRef]])
case class Rebroadcast(channels: Map[ChannelAnnouncement, Set[ActorRef]], updates: Map[ChannelUpdate, Set[ActorRef]], nodes: Map[NodeAnnouncement, Set[ActorRef]])

case class Sync(missing: SortedSet[ShortChannelId], totalMissingCount: Int)

case class Data(nodes: Map[PublicKey, NodeAnnouncement],
                channels: SortedMap[ShortChannelId, ChannelAnnouncement],
                updates: Map[ChannelDesc, ChannelUpdate],
                stash: Stash,
                rebroadcast: Rebroadcast,
                awaiting: Map[ChannelAnnouncement, Seq[ActorRef]], // note: this is a seq because we want to preserve order: first actor is the one who we need to send a tcp-ack when validation is done
                privateChannels: Map[ShortChannelId, PublicKey], // short_channel_id -> node_id
                privateUpdates: Map[ChannelDesc, ChannelUpdate],
                excludedChannels: Set[ChannelDesc], // those channels are temporarily excluded from route calculation, because their node returned a TemporaryChannelFailure
                graph: DirectedGraph,
                sync: Map[PublicKey, Sync] // keep tracks of channel range queries sent to each peer. If there is an entry in the map, it means that there is an ongoing query
                // for which we have not yet received an 'end' message
               )

sealed trait State
case object NORMAL extends State

case object TickBroadcast
case object TickPruneStaleChannels

// @formatter:on

/**
  * Created by PM on 24/05/2016.
  */

class Router(nodeParams: NodeParams, watcher: ActorRef, initialized: Option[Promise[Done]] = None) extends FSMDiagnosticActorLogging[State, Data] {

  import Router._

  import ExecutionContext.Implicits.global

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])

  setTimer(TickBroadcast.toString, TickBroadcast, nodeParams.routerConf.routerBroadcastInterval, repeat = true)
  setTimer(TickPruneStaleChannels.toString, TickPruneStaleChannels, 1 hour, repeat = true)

  val SHORTID_WINDOW = 100

  val defaultRouteParams = RouteParams(
    maxFeeBaseMsat = nodeParams.routerConf.searchMaxFeeBaseSat * 1000, // converting sat -> msat
    maxFeePct = nodeParams.routerConf.searchMaxFeePct,
    routeMaxLength = nodeParams.routerConf.searchMaxRouteLength,
    routeMaxCltv = nodeParams.routerConf.searchMaxCltv,
    ratios = nodeParams.routerConf.searchHeuristicsEnabled match {
      case false => None
      case true => Some(WeightRatios(
        cltvDeltaFactor = nodeParams.routerConf.searchRatioCltv,
        ageFactor = nodeParams.routerConf.searchRatioChannelAge,
        capacityFactor = nodeParams.routerConf.searchRatioChannelCapacity
      ))
    }
  )

  val db = nodeParams.networkDb

  {
    log.info("loading network announcements from db...")
    val channels = db.listChannels()
    val nodes = db.listNodes()
    val updates = db.listChannelUpdates()
    log.info("loaded from db: channels={} nodes={} updates={}", channels.size, nodes.size, updates.size)
    val initChannels = channels.keys.foldLeft(TreeMap.empty[ShortChannelId, ChannelAnnouncement]) { case (m, c) => m + (c.shortChannelId -> c) }
    val initChannelUpdates = updates.map { u =>
      val desc = getDesc(u, initChannels(u.shortChannelId))
      desc -> u
    }.toMap
    // this will be used to calculate routes
    val graph = DirectedGraph.makeGraph(initChannelUpdates)
    val initNodes = nodes.map(n => (n.nodeId -> n)).toMap
    // send events for remaining channels/nodes
    context.system.eventStream.publish(ChannelsDiscovered(initChannels.values.map(c => SingleChannelDiscovered(c, channels(c)._2))))
    context.system.eventStream.publish(ChannelUpdatesReceived(initChannelUpdates.values))
    context.system.eventStream.publish(NodesDiscovered(initNodes.values))

    // watch the funding tx of all these channels
    // note: some of them may already have been spent, in that case we will receive the watch event immediately
    initChannels.values.foreach { c =>
      val txid = channels(c)._1
      val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(c.shortChannelId)
      val fundingOutputScript = write(pay2wsh(Scripts.multiSig2of2(PublicKey(c.bitcoinKey1), PublicKey(c.bitcoinKey2))))
      watcher ! WatchSpentBasic(self, txid, outputIndex, fundingOutputScript, BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(c.shortChannelId))
    }

    // on restart we update our node announcement
    // note that if we don't currently have public channels, this will be ignored
    val nodeAnn = Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.publicAddresses)
    self ! nodeAnn

    log.info(s"initialization completed, ready to process messages")
    Try(initialized.map(_.success(Done)))
    startWith(NORMAL, Data(initNodes, initChannels, initChannelUpdates, Stash(Map.empty, Map.empty), rebroadcast = Rebroadcast(channels = Map.empty, updates = Map.empty, nodes = Map.empty), awaiting = Map.empty, privateChannels = Map.empty, privateUpdates = Map.empty, excludedChannels = Set.empty, graph, sync = Map.empty))
  }

  when(NORMAL) {
    case Event(LocalChannelUpdate(_, _, shortChannelId, remoteNodeId, channelAnnouncement_opt, u, _), d: Data) =>
      d.channels.get(shortChannelId) match {
        case Some(_) =>
          // channel has already been announced and router knows about it, we can process the channel_update
          stay using handle(u, self, d)
        case None =>
          channelAnnouncement_opt match {
            case Some(c) if d.awaiting.contains(c) =>
              // channel is currently being verified, we can process the channel_update right away (it will be stashed)
              stay using handle(u, self, d)
            case Some(c) =>
              // channel wasn't announced but here is the announcement, we will process it *before* the channel_update
              watcher ! ValidateRequest(c)
              val d1 = d.copy(awaiting = d.awaiting + (c -> Nil)) // no origin
              // maybe the local channel was pruned (can happen if we were disconnected for more than 2 weeks)
              db.removeFromPruned(c.shortChannelId)
              stay using handle(u, self, d1)
            case None if d.privateChannels.contains(shortChannelId) =>
              // channel isn't announced but we already know about it, we can process the channel_update
              stay using handle(u, self, d)
            case None =>
              // channel isn't announced and we never heard of it (maybe it is a private channel or maybe it is a public channel that doesn't yet have 6 confirmations)
              // let's create a corresponding private channel and process the channel_update
              log.info("adding unannounced local channel to remote={} shortChannelId={}", remoteNodeId, shortChannelId)
              stay using handle(u, self, d.copy(privateChannels = d.privateChannels + (shortChannelId -> remoteNodeId)))
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
        stay using d.copy(privateChannels = d.privateChannels - shortChannelId, privateUpdates = d.privateUpdates - desc1 - desc2, graph = graph1)
      } else {
        stay
      }

    case Event(GetRoutingState, d: Data) =>
      log.info(s"getting valid announcements for $sender")
      sender ! RoutingState(d.channels.values, d.updates.values, d.nodes.values)
      stay

    case Event(v@ValidateResult(c, _), d0) =>
      d0.awaiting.get(c) match {
        case Some(origin +: others) => origin ! TransportHandler.ReadAck(c) // now we can acknowledge the message, we only need to do it for the first peer that sent us the announcement
        case _ => ()
      }
      log.info("got validation result for shortChannelId={} (awaiting={} stash.nodes={} stash.updates={})", c.shortChannelId, d0.awaiting.size, d0.stash.nodes.size, d0.stash.updates.size)
      val success = v match {
        case ValidateResult(c, Left(t)) =>
          log.warning("validation failure for shortChannelId={} reason={}", c.shortChannelId, t.getMessage)
          false
        case ValidateResult(c, Right((tx, UtxoStatus.Unspent))) =>
          val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(c.shortChannelId)
          // let's check that the output is indeed a P2WSH multisig 2-of-2 of nodeid1 and nodeid2)
          val fundingOutputScript = write(pay2wsh(Scripts.multiSig2of2(PublicKey(c.bitcoinKey1), PublicKey(c.bitcoinKey2))))
          if (tx.txOut.size < outputIndex + 1 || fundingOutputScript != tx.txOut(outputIndex).publicKeyScript) {
            log.error(s"invalid script for shortChannelId={}: txid={} does not have script=$fundingOutputScript at outputIndex=$outputIndex ann={}", c.shortChannelId, tx.txid, c)
            d0.awaiting.get(c) match {
              case Some(origins) => origins.foreach(_ ! InvalidAnnouncement(c))
              case _ => ()
            }
            false
          } else {
            watcher ! WatchSpentBasic(self, tx, outputIndex, BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(c.shortChannelId))
            // TODO: check feature bit set
            log.debug("added channel channelId={}", c.shortChannelId)
            val capacity = tx.txOut(outputIndex).amount
            context.system.eventStream.publish(ChannelsDiscovered(SingleChannelDiscovered(c, capacity) :: Nil))
            db.addChannel(c, tx.txid, capacity)

            // in case we just validated our first local channel, we announce the local node
            if (!d0.nodes.contains(nodeParams.nodeId) && isRelatedTo(c, nodeParams.nodeId)) {
              log.info("first local channel validated, announcing local node")
              val nodeAnn = Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.publicAddresses)
              self ! nodeAnn
            }
            true
          }
        case ValidateResult(c, Right((tx, fundingTxStatus: UtxoStatus.Spent))) =>
          if (fundingTxStatus.spendingTxConfirmed) {
            log.warning("ignoring shortChannelId={} tx={} (funding tx already spent and spending tx is confirmed)", c.shortChannelId, tx.txid)
            // the funding tx has been spent by a transaction that is now confirmed: peer shouldn't send us those
            d0.awaiting.get(c) match {
              case Some(origins) => origins.foreach(_ ! ChannelClosed(c))
              case _ => ()
            }
          } else {
            log.debug("ignoring shortChannelId={} tx={} (funding tx already spent but spending tx isn't confirmed)", c.shortChannelId, tx.txid)
          }
          // there may be a record if we have just restarted
          db.removeChannel(c.shortChannelId)
          false
      }

      // we also reprocess node and channel_update announcements related to channels that were just analyzed
      val reprocessUpdates = d0.stash.updates.filterKeys(u => u.shortChannelId == c.shortChannelId)
      val reprocessNodes = d0.stash.nodes.filterKeys(n => isRelatedTo(c, n.nodeId))
      // and we remove the reprocessed messages from the stash
      val stash1 = d0.stash.copy(updates = d0.stash.updates -- reprocessUpdates.keys, nodes = d0.stash.nodes -- reprocessNodes.keys)
      // we remove channel from awaiting map
      val awaiting1 = d0.awaiting - c
      if (success) {
        // note: if the channel is graduating from private to public, the implementation (in the LocalChannelUpdate handler) guarantees that we will process a new channel_update
        // right after the channel_announcement, channel_updates will be moved from private to public at that time
        val d1 = d0.copy(
          channels = d0.channels + (c.shortChannelId -> c),
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
      } else {
        stay using d0.copy(stash = stash1, awaiting = awaiting1)
      }

    case Event(WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(shortChannelId)), d) if d.channels.contains(shortChannelId) =>
      val lostChannel = d.channels(shortChannelId)
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
        case nodeId =>
          log.info("pruning nodeId={} (spent)", nodeId)
          db.removeNode(nodeId)
          context.system.eventStream.publish(NodeLost(nodeId))
      }
      stay using d.copy(nodes = d.nodes -- lostNodes, channels = d.channels - shortChannelId, updates = d.updates.filterKeys(_.shortChannelId != shortChannelId), graph = graph1)

    case Event(TickBroadcast, d) =>
      if (d.rebroadcast.channels.isEmpty && d.rebroadcast.updates.isEmpty && d.rebroadcast.nodes.isEmpty) {
        stay
      } else {
        log.debug("broadcasting routing messages")
        log.debug("staggered broadcast details: channels={} updates={} nodes={}", d.rebroadcast.channels.size, d.rebroadcast.updates.size, d.rebroadcast.nodes.size)
        context.actorSelection(context.system / "*" / "switchboard") ! d.rebroadcast
        stay using d.copy(rebroadcast = Rebroadcast(channels = Map.empty, updates = Map.empty, nodes = Map.empty))
      }

    case Event(TickPruneStaleChannels, d) =>
      // first we select channels that we will prune
      val staleChannels = getStaleChannels(d.channels.values, d.updates)
      // then we clean up the related channel updates
      val staleUpdates = staleChannels.map(d.channels).flatMap(c => Seq(ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2), ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1)))
      // finally we remove nodes that aren't tied to any channels anymore (and deduplicate them)
      val potentialStaleNodes = staleChannels.map(d.channels).flatMap(c => Set(c.nodeId1, c.nodeId2)).toSet
      val channels1 = d.channels -- staleChannels
      // no need to iterate on all nodes, just on those that are affected by current pruning
      val staleNodes = potentialStaleNodes.filterNot(nodeId => hasChannels(nodeId, channels1.values))

      // let's clean the db and send the events
      staleChannels.foreach { shortChannelId =>
        log.info("pruning shortChannelId={} (stale)", shortChannelId)
        db.removeChannel(shortChannelId) // NB: this also removes channel updates
        // we keep track of recently pruned channels so we don't revalidate them (zombie churn)
        db.addToPruned(shortChannelId)
        context.system.eventStream.publish(ChannelLost(shortChannelId))
      }

      val staleChannelsToRemove = new mutable.MutableList[ChannelDesc]
      staleChannels.map(d.channels).foreach(ca => {
        staleChannelsToRemove += ChannelDesc(ca.shortChannelId, ca.nodeId1, ca.nodeId2)
        staleChannelsToRemove += ChannelDesc(ca.shortChannelId, ca.nodeId2, ca.nodeId1)
      })

      val graph1 = d.graph.removeEdges(staleChannelsToRemove)
      staleNodes.foreach {
        case nodeId =>
          log.info("pruning nodeId={} (stale)", nodeId)
          db.removeNode(nodeId)
          context.system.eventStream.publish(NodeLost(nodeId))
      }
      stay using d.copy(nodes = d.nodes -- staleNodes, channels = channels1, updates = d.updates -- staleUpdates, graph = graph1)

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
      sender ! d.channels.values
      stay

    case Event('updates, d) =>
      sender ! (d.updates ++ d.privateUpdates).values
      stay

    case Event('updatesMap, d) =>
      sender ! (d.updates ++ d.privateUpdates)
      stay

    case Event('data, d) =>
      sender ! d
      stay

    case Event(RouteRequest(start, end, amount, assistedRoutes, ignoreNodes, ignoreChannels, randomize_opt, params_opt), d) =>
      // we convert extra routing info provided in the payment request to fake channel_update
      // it takes precedence over all other channel_updates we know
      val assistedUpdates = assistedRoutes.flatMap(toFakeUpdates(_, end)).toMap
      // we also filter out updates corresponding to channels/nodes that are blacklisted for this particular request
      // TODO: in case of duplicates, d.updates will be overridden by assistedUpdates even if they are more recent!
      val ignoredUpdates = getIgnoredChannelDesc(d.updates ++ d.privateUpdates ++ assistedUpdates, ignoreNodes) ++ ignoreChannels ++ d.excludedChannels
      val extraEdges = assistedUpdates.map { case (c, u) => GraphEdge(c, u) }.toSet
      val routesToFind = if (randomize_opt.getOrElse(nodeParams.routerConf.randomizeRouteSelection)) DEFAULT_ROUTES_COUNT else 1
      val params = params_opt.getOrElse(defaultRouteParams)

      log.info(s"finding a route $start->$end with assistedChannels={} ignoreNodes={} ignoreChannels={} excludedChannels={}", assistedUpdates.keys.mkString(","), ignoreNodes.map(_.toBin).mkString(","), ignoreChannels.mkString(","), d.excludedChannels.mkString(","))
      log.info(s"finding a route with randomize={} params={}", routesToFind > 1, params)
      findRoute(d.graph, start, end, amount, numRoutes = routesToFind, extraEdges = extraEdges, ignoredEdges = ignoredUpdates.toSet, routeParams = params)
        .map(r => sender ! RouteResponse(r, ignoreNodes, ignoreChannels))
        .recover { case t => sender ! Status.Failure(t) }
      stay

    case Event(SendChannelQuery(remoteNodeId, remote), d) =>
      // ask for everything
      // we currently send only one query_channel_range message per peer, when we just (re)connected to it, so we don't
      // have to worry about sending a new query_channel_range when another query is still in progress
      val query = QueryChannelRange(nodeParams.chainHash, firstBlockNum = 0, numberOfBlocks = Int.MaxValue)
      log.info("sending query_channel_range={}", query)
      remote ! query

      // we also set a pass-all filter for now (we can update it later) for the future gossip messages, by setting
      // the first_timestamp field to the current date/time and timestamp_range to the maximum value
      // NB: we can't just set firstTimestamp to 0, because in that case peer would send us all past messages matching
      // that (i.e. the whole routing table)
      val filter = GossipTimestampFilter(nodeParams.chainHash, firstTimestamp = Platform.currentTime / 1000, timestampRange = Int.MaxValue)
      remote ! filter

      // clean our sync state for this peer: we receive a SendChannelQuery just when we connect/reconnect to a peer and
      // will start a new complete sync process
      stay using d.copy(sync = d.sync - remoteNodeId)

    // Warning: order matters here, this must be the first match for HasChainHash messages !
    case Event(PeerRoutingMessage(_, _, routingMessage: HasChainHash), d) if routingMessage.chainHash != nodeParams.chainHash =>
      sender ! TransportHandler.ReadAck(routingMessage)
      log.warning("message {} for wrong chain {}, we're on {}", routingMessage, routingMessage.chainHash, nodeParams.chainHash)
      stay

    case Event(u: ChannelUpdate, d: Data) =>
      // it was sent by us, routing messages that are sent by  our peers are now wrapped in a PeerRoutingMessage
      log.debug("received channel update from {}", sender)
      stay using handle(u, sender, d)

    case Event(PeerRoutingMessage(transport, remoteNodeId, u: ChannelUpdate), d) =>
      sender ! TransportHandler.ReadAck(u)
      log.debug("received channel update for shortChannelId={}", u.shortChannelId)
      stay using handle(u, sender, d, remoteNodeId_opt = Some(remoteNodeId), transport_opt = Some(transport))

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
        val origins = d.awaiting(c) :+ sender
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
        watcher ! ValidateRequest(c)
        // we don't acknowledge the message just yet
        stay using d.copy(awaiting = d.awaiting + (c -> Seq(sender)))
      }

    case Event(n: NodeAnnouncement, d: Data) =>
      // it was sent by us, routing messages that are sent by  our peers are now wrapped in a PeerRoutingMessage
      log.debug("received node announcement from {}", sender)
      stay using handle(n, sender, d)

    case Event(PeerRoutingMessage(_, _, n: NodeAnnouncement), d: Data) =>
      sender ! TransportHandler.ReadAck(n)
      log.debug("received node announcement for nodeId={}", n.nodeId)
      stay using handle(n, sender, d)

    case Event(PeerRoutingMessage(transport, _, routingMessage@QueryChannelRange(chainHash, firstBlockNum, numberOfBlocks)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      log.info("received query_channel_range={}", routingMessage)
      // sort channel ids and keep the ones which are in [firstBlockNum, firstBlockNum + numberOfBlocks]
      val shortChannelIds: SortedSet[ShortChannelId] = d.channels.keySet.filter(keep(firstBlockNum, numberOfBlocks, _, d.channels, d.updates))
      // TODO: we don't compress to be compatible with old mobile apps, switch to ZLIB ASAP
      // Careful: when we remove GZIP support, eclair-wallet 0.3.0 will stop working i.e. channels to ACINQ nodes will not
      // work anymore
      val blocks = ChannelRangeQueries.encodeShortChannelIds(firstBlockNum, numberOfBlocks, shortChannelIds, ChannelRangeQueries.UNCOMPRESSED_FORMAT)
      log.info("sending back reply_channel_range with {} items for range=({}, {})", shortChannelIds.size, firstBlockNum, numberOfBlocks)
      // there could be several reply_channel_range messages for a single query
      val replies = blocks.map(block => ReplyChannelRange(chainHash, block.firstBlock, block.numBlocks, 1, block.shortChannelIds))
      replies.foreach(reply => transport ! reply)
      stay

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage@ReplyChannelRange(chainHash, firstBlockNum, numberOfBlocks, _, data)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      val (format, theirShortChannelIds, useGzip) = ChannelRangeQueries.decodeShortChannelIds(data)
      val ourShortChannelIds: SortedSet[ShortChannelId] = d.channels.keySet.filter(keep(firstBlockNum, numberOfBlocks, _, d.channels, d.updates))
      val missing: SortedSet[ShortChannelId] = theirShortChannelIds -- ourShortChannelIds
      log.info("received reply_channel_range, we're missing {} channel announcements/updates, format={} useGzip={}", missing.size, format, useGzip)
      val d1 = if (missing.nonEmpty) {
        // they may send back several reply_channel_range messages for a single query_channel_range query, and we must not
        // send another query_short_channel_ids query if they're still processing one
        d.sync.get(remoteNodeId) match {
          case None =>
            // we don't have a pending query with this peer
            val (slice, rest) = missing.splitAt(SHORTID_WINDOW)
            transport ! QueryShortChannelIds(chainHash, ChannelRangeQueries.encodeShortChannelIdsSingle(slice, format, useGzip))
            d.copy(sync = d.sync + (remoteNodeId -> Sync(rest, missing.size)))
          case Some(sync) =>
            // we already have a pending query with this peer, add missing ids to our "sync" state
            d.copy(sync = d.sync + (remoteNodeId -> Sync(sync.missing ++ missing, sync.totalMissingCount + missing.size)))
        }
      } else d
      context.system.eventStream.publish(syncProgress(d1))
      stay using d1

    case Event(PeerRoutingMessage(transport, _, routingMessage@QueryShortChannelIds(chainHash, data)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      val (_, shortChannelIds, useGzip) = ChannelRangeQueries.decodeShortChannelIds(data)
      log.info("received query_short_channel_ids for {} channel announcements, useGzip={}", shortChannelIds.size, useGzip)
      shortChannelIds.foreach(shortChannelId => {
        d.channels.get(shortChannelId) match {
          case None => log.warning("received query for shortChannelId={} that we don't have", shortChannelId)
          case Some(ca) =>
            transport ! ca
            d.updates.get(ChannelDesc(ca.shortChannelId, ca.nodeId1, ca.nodeId2)).map(u => transport ! u)
            d.updates.get(ChannelDesc(ca.shortChannelId, ca.nodeId2, ca.nodeId1)).map(u => transport ! u)
        }
      })
      transport ! ReplyShortChannelIdsEnd(chainHash, 1)
      stay

    case Event(PeerRoutingMessage(transport, remoteNodeId, routingMessage@ReplyShortChannelIdsEnd(chainHash, complete)), d) =>
      sender ! TransportHandler.ReadAck(routingMessage)
      log.info("received reply_short_channel_ids_end={}", routingMessage)
      // have we more channels to ask this peer?
      val d1 = d.sync.get(remoteNodeId) match {
        case Some(sync) if sync.missing.nonEmpty =>
          log.info(s"asking {} for the next slice of short_channel_ids", remoteNodeId)
          val (slice, rest) = sync.missing.splitAt(SHORTID_WINDOW)
          transport ! QueryShortChannelIds(chainHash, ChannelRangeQueries.encodeShortChannelIdsSingle(slice, ChannelRangeQueries.UNCOMPRESSED_FORMAT, useGzip = false))
          d.copy(sync = d.sync + (remoteNodeId -> sync.copy(missing = rest)))
        case Some(sync) if sync.missing.isEmpty =>
          // we received reply_short_channel_ids_end for our last query aand have not sent another one, we can now remove
          // the remote peer from our map
          d.copy(sync = d.sync - remoteNodeId)
        case _ =>
          d
      }
      context.system.eventStream.publish(syncProgress(d1))
      stay using d1
  }

  initialize()

  def handle(n: NodeAnnouncement, origin: ActorRef, d: Data): Data =
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
      origin ! InvalidSignature(n)
      d
    } else if (d.nodes.contains(n.nodeId)) {
      log.debug("updated node nodeId={}", n.nodeId)
      context.system.eventStream.publish(NodeUpdated(n))
      db.updateNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n), rebroadcast = d.rebroadcast.copy(nodes = d.rebroadcast.nodes + (n -> Set(origin))))
    } else if (d.channels.values.exists(c => isRelatedTo(c, n.nodeId))) {
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

  def handle(u: ChannelUpdate, origin: ActorRef, d: Data, remoteNodeId_opt: Option[PublicKey] = None, transport_opt: Option[ActorRef] = None): Data =
    if (d.channels.contains(u.shortChannelId)) {
      // related channel is already known (note: this means no related channel_update is in the stash)
      val publicChannel = true
      val c = d.channels(u.shortChannelId)
      val desc = getDesc(u, c)
      if (d.rebroadcast.updates.contains(u)) {
        log.debug("ignoring {} (pending rebroadcast)", u)
        val origins = d.rebroadcast.updates(u) + origin
        d.copy(rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> origins)))
      } else if (isStale(u)) {
        log.debug("ignoring {} (stale)", u)
        d
      } else if (d.updates.contains(desc) && d.updates(desc).timestamp >= u.timestamp) {
        log.debug("ignoring {} (duplicate)", u)
        d
      } else if (!Announcements.checkSig(u, desc.a)) {
        log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId, u)
        origin ! InvalidSignature(u)
        d
      } else if (d.updates.contains(desc)) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        context.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        db.updateChannelUpdate(u)
        // update the graph
        val graph1 = Announcements.isEnabled(u.channelFlags) match {
          case true => d.graph.removeEdge(desc).addEdge(desc, u)
          case false => d.graph.removeEdge(desc) // if the channel is now disabled, we remove it from the graph
        }
        d.copy(updates = d.updates + (desc -> u), rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> Set(origin))), graph = graph1)
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        context.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        db.addChannelUpdate(u)
        // we also need to update the graph
        val graph1 = d.graph.addEdge(desc, u)
        d.copy(updates = d.updates + (desc -> u), privateUpdates = d.privateUpdates - desc, rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> Set(origin))), graph = graph1)
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
      val remoteNodeId = d.privateChannels(u.shortChannelId)
      val (a, b) = if (Announcements.isNode1(nodeParams.nodeId, remoteNodeId)) (nodeParams.nodeId, remoteNodeId) else (remoteNodeId, nodeParams.nodeId)
      val desc = if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, a, b) else ChannelDesc(u.shortChannelId, b, a)
      if (isStale(u)) {
        log.debug("ignoring {} (stale)", u)
        d
      } else if (d.updates.contains(desc) && d.updates(desc).timestamp >= u.timestamp) {
        log.debug("ignoring {} (already know same or newer)", u)
        d
      } else if (!Announcements.checkSig(u, desc.a)) {
        log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId, u)
        origin ! InvalidSignature(u)
        d
      } else if (d.privateUpdates.contains(desc)) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        context.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        // we also need to update the graph
        val graph1 = d.graph.removeEdge(desc).addEdge(desc, u)
        d.copy(privateUpdates = d.privateUpdates + (desc -> u), graph = graph1)
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        context.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        // we also need to update the graph
        val graph1 = d.graph.addEdge(desc, u)
        d.copy(privateUpdates = d.privateUpdates + (desc -> u), graph = graph1)
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
          d.sync.get(remoteNodeId) match {
            case Some(sync) =>
              // we already have a pending request to that node, let's add this channel to the list and we'll get it later
              d.copy(sync = d.sync + (remoteNodeId -> sync.copy(missing = sync.missing + u.shortChannelId, totalMissingCount = sync.totalMissingCount + 1)))
            case None =>
              // we send the query right away
              transport ! QueryShortChannelIds(u.chainHash, ChannelRangeQueries.encodeShortChannelIdsSingle(Seq(u.shortChannelId), ChannelRangeQueries.UNCOMPRESSED_FORMAT, useGzip = false))
              d.copy(sync = d.sync + (remoteNodeId -> Sync(missing = SortedSet(u.shortChannelId), totalMissingCount = 1)))
          }
        case _ =>
          // we don't know which node this update came from (maybe it was stashed and the channel got pruned in the meantime or some other corner case).
          // or we don't have a transport to send our query with.
          // anyway, that's not really a big deal because we have removed the channel from the pruned db so next time it shows up we will revalidate it
          d
      }
    } else {
      log.debug("ignoring announcement {} (unknown channel)", u)
      d
    }

  override def mdc(currentMessage: Any): MDC = currentMessage match {
    case SendChannelQuery(remoteNodeId, _) => Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))
    case PeerRoutingMessage(_, remoteNodeId, _) => Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))
    case _ => akka.event.Logging.emptyMDC
  }
}

object Router {

  def props(nodeParams: NodeParams, watcher: ActorRef, initialized: Option[Promise[Done]] = None) = Props(new Router(nodeParams, watcher, initialized))

  def toFakeUpdate(extraHop: ExtraHop): ChannelUpdate =
  // the `direction` bit in flags will not be accurate but it doesn't matter because it is not used
  // what matters is that the `disable` bit is 0 so that this update doesn't get filtered out
    ChannelUpdate(signature = "", chainHash = "", extraHop.shortChannelId, Platform.currentTime / 1000, messageFlags = 0, channelFlags = 0, extraHop.cltvExpiryDelta, htlcMinimumMsat = 0L, extraHop.feeBaseMsat, extraHop.feeProportionalMillionths, None)

  def toFakeUpdates(extraRoute: Seq[ExtraHop], targetNodeId: PublicKey): Map[ChannelDesc, ChannelUpdate] = {
    // BOLT 11: "For each entry, the pubkey is the node ID of the start of the channel", and the last node is the destination
    val nextNodeIds = extraRoute.map(_.nodeId).drop(1) :+ targetNodeId
    extraRoute.zip(nextNodeIds).map {
      case (extraHop: ExtraHop, nextNodeId) => (ChannelDesc(extraHop.shortChannelId, extraHop.nodeId, nextNodeId) -> toFakeUpdate(extraHop))
    }.toMap
  }

  def getDesc(u: ChannelUpdate, channel: ChannelAnnouncement): ChannelDesc = {
    // the least significant bit tells us if it is node1 or node2
    if (Announcements.isNode1(u.channelFlags)) ChannelDesc(u.shortChannelId, channel.nodeId1, channel.nodeId2) else ChannelDesc(u.shortChannelId, channel.nodeId2, channel.nodeId1)
  }

  def isRelatedTo(c: ChannelAnnouncement, nodeId: PublicKey) = nodeId == c.nodeId1 || nodeId == c.nodeId2

  def hasChannels(nodeId: PublicKey, channels: Iterable[ChannelAnnouncement]): Boolean = channels.exists(c => isRelatedTo(c, nodeId))

  def isStale(u: ChannelUpdate): Boolean = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks (1209600 seconds)"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update
    val staleThresholdSeconds = Platform.currentTime / 1000 - 1209600
    u.timestamp < staleThresholdSeconds
  }

  /**
    * Is stale a channel that:
    * (1) is older than 2 weeks (2*7*144 = 2016 blocks)
    * AND
    * (2) has no channel_update younger than 2 weeks
    *
    * @param channel
    * @param update1_opt update corresponding to one side of the channel, if we have it
    * @param update2_opt update corresponding to the other side of the channel, if we have it
    * @return
    */
  def isStale(channel: ChannelAnnouncement, update1_opt: Option[ChannelUpdate], update2_opt: Option[ChannelUpdate]): Boolean = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks (1209600 seconds)"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update, so we keep them as long as they are less than 2 weeks (2016 blocks) old
    val staleThresholdBlocks = Globals.blockCount.get() - 2016
    val TxCoordinates(blockHeight, _, _) = ShortChannelId.coordinates(channel.shortChannelId)
    blockHeight < staleThresholdBlocks && update1_opt.map(isStale).getOrElse(true) && update2_opt.map(isStale).getOrElse(true)
  }

  def getStaleChannels(channels: Iterable[ChannelAnnouncement], updates: Map[ChannelDesc, ChannelUpdate]): Iterable[ShortChannelId] = {
    val staleChannels = channels.filter { c =>
      val update1 = updates.get(ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2))
      val update2 = updates.get(ChannelDesc(c.shortChannelId, c.nodeId2, c.nodeId1))
      isStale(c, update1, update2)
    }
    staleChannels.map(_.shortChannelId)
  }

  /**
    * Filters channels that we want to send to nodes asking for a channel range
    */
  def keep(firstBlockNum: Long, numberOfBlocks: Long, id: ShortChannelId, channels: Map[ShortChannelId, ChannelAnnouncement], updates: Map[ChannelDesc, ChannelUpdate]): Boolean = {
    val TxCoordinates(height, _, _) = ShortChannelId.coordinates(id)
    height >= firstBlockNum && height <= (firstBlockNum + numberOfBlocks)
  }

  def syncProgress(d: Data): SyncProgress =
    if (d.sync.isEmpty) {
      SyncProgress(1)
    } else {
      SyncProgress(1 - d.sync.values.map(_.missing.size).sum * 1.0 / d.sync.values.map(_.totalMissingCount).sum)
    }

  /**
    * This method is used after a payment failed, and we want to exclude some nodes that we know are failing
    */
  def getIgnoredChannelDesc(updates: Map[ChannelDesc, ChannelUpdate], ignoreNodes: Set[PublicKey]): Iterable[ChannelDesc] = {
    val desc = if (ignoreNodes.isEmpty) {
      Iterable.empty[ChannelDesc]
    } else {
      // expensive, but node blacklisting shouldn't happen often
      updates.keys.filter(desc => ignoreNodes.contains(desc.a) || ignoreNodes.contains(desc.b))
    }
    desc
  }

  /**
    * https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#clarifications
    */
  val ROUTE_MAX_LENGTH = 20

  // Max allowed CLTV for a route
  val DEFAULT_ROUTE_MAX_CLTV = 1008

  // The default amount of routes we'll search for when findRoute is called
  val DEFAULT_ROUTES_COUNT = 3

  /**
    * Find a route in the graph between localNodeId and targetNodeId, returns the route.
    * Will perform a k-shortest path selection given the @param numRoutes and randomly select one of the result.
    *
    * @param g
    * @param localNodeId
    * @param targetNodeId
    * @param amountMsat   the amount that will be sent along this route
    * @param numRoutes    the number of shortest-paths to find
    * @param extraEdges   a set of extra edges we want to CONSIDER during the search
    * @param ignoredEdges a set of extra edges we want to IGNORE during the search
    * @param routeParams  a set of parameters that can restrict the route search
    * @param wr           an object containing the ratios used to 'weight' edges when searching for the shortest path
    * @return the computed route to the destination @targetNodeId
    */
  def findRoute(g: DirectedGraph,
                localNodeId: PublicKey,
                targetNodeId: PublicKey,
                amountMsat: Long,
                numRoutes: Int,
                extraEdges: Set[GraphEdge] = Set.empty,
                ignoredEdges: Set[ChannelDesc] = Set.empty,
                routeParams: RouteParams): Try[Seq[Hop]] = Try {

    if (localNodeId == targetNodeId) throw CannotRouteToSelf

    val currentBlockHeight = Globals.blockCount.get()

    val boundaries: RichWeight => Boolean = { weight =>
      ((weight.cost - amountMsat) < routeParams.maxFeeBaseMsat || (weight.cost - amountMsat) < (routeParams.maxFeePct * amountMsat)) &&
        weight.length <= routeParams.routeMaxLength && weight.length <= ROUTE_MAX_LENGTH &&
        weight.cltv <= routeParams.routeMaxCltv
    }

    val foundRoutes = Graph.yenKshortestPaths(g, localNodeId, targetNodeId, amountMsat, ignoredEdges, extraEdges, numRoutes, routeParams.ratios, currentBlockHeight, boundaries).toList match {
      case Nil if routeParams.routeMaxLength < ROUTE_MAX_LENGTH => // if not found within the constraints we relax and repeat the search
        return findRoute(g, localNodeId, targetNodeId, amountMsat, numRoutes, extraEdges, ignoredEdges, routeParams.copy(routeMaxLength = ROUTE_MAX_LENGTH, routeMaxCltv = DEFAULT_ROUTE_MAX_CLTV))
      case Nil => throw RouteNotFound
      case routes => routes.find(_.path.size == 1) match {
        case Some(directRoute) => directRoute :: Nil
        case _ => routes
      }
    }

    // At this point 'foundRoutes' cannot be empty
    Random.shuffle(foundRoutes).head.path.map(graphEdgeToHop)
  }
}
