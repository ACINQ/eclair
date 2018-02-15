package fr.acinq.eclair.router

import java.io.StringWriter

import akka.actor.{ActorRef, FSM, Props, Terminated}
import akka.pattern.pipe
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.ext._
import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge, SimpleGraph}

import scala.collection.JavaConversions._
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Success, Try}

// @formatter:off

case class ChannelDesc(id: Long, a: PublicKey, b: PublicKey)
case class Hop(nodeId: PublicKey, nextNodeId: PublicKey, lastUpdate: ChannelUpdate)
case class RouteRequest(source: PublicKey, target: PublicKey, assistedRoutes: Seq[Seq[ExtraHop]] = Nil, ignoreNodes: Set[PublicKey] = Set.empty, ignoreChannels: Set[Long] = Set.empty)
case class RouteResponse(hops: Seq[Hop], ignoreNodes: Set[PublicKey], ignoreChannels: Set[Long]) { require(hops.size > 0, "route cannot be empty") }
case class ExcludeChannel(desc: ChannelDesc) // this is used when we get a TemporaryChannelFailure, to give time for the channel to recover (note that exclusions are directed)
case class LiftChannelExclusion(desc: ChannelDesc)
case class SendRoutingState(to: ActorRef)
case class Stash(updates: Map[ChannelUpdate, Set[ActorRef]], nodes: Map[NodeAnnouncement, Set[ActorRef]])
case class Rebroadcast(channels: Map[ChannelAnnouncement, Set[ActorRef]], updates: Map[ChannelUpdate, Set[ActorRef]], nodes: Map[NodeAnnouncement, Set[ActorRef]])

case class Data(nodes: Map[PublicKey, NodeAnnouncement],
                  channels: Map[Long, ChannelAnnouncement],
                  updates: Map[ChannelDesc, ChannelUpdate],
                  stash: Stash,
                  rebroadcast: Rebroadcast,
                  awaiting: Map[ChannelAnnouncement, Seq[ActorRef]], // note: this is a seq because we want to preserve order: first actor is the one who we need to send a tcp-ack when validation is done
                  privateChannels: Map[Long, PublicKey], // short_channel_id -> node_id
                  privateUpdates: Map[ChannelDesc, ChannelUpdate],
                  excludedChannels: Set[ChannelDesc], // those channels are temporarily excluded from route calculation, because their node returned a TemporaryChannelFailure
                  sendingState: Set[ActorRef])

sealed trait State
case object NORMAL extends State
case object WAITING_FOR_VALIDATION extends State

case object TickBroadcast
case object TickPruneStaleChannels

// @formatter:on

/**
  * Created by PM on 24/05/2016.
  */

class Router(nodeParams: NodeParams, watcher: ActorRef) extends FSM[State, Data] {

  import Router._

  import ExecutionContext.Implicits.global

  context.system.eventStream.subscribe(self, classOf[LocalChannelUpdate])
  context.system.eventStream.subscribe(self, classOf[LocalChannelDown])

  setTimer(TickBroadcast.toString, TickBroadcast, nodeParams.routerBroadcastInterval, repeat = true)
  setTimer(TickPruneStaleChannels.toString, TickPruneStaleChannels, 1 hour, repeat = true)

  val db = nodeParams.networkDb

  // Note: It is possible that some channels have been closed while we were shutdown. Since we are directly loading channels
  // in memory without checking they are still alive, we may advertise closed channels if someone connects to us right after
  // startup. That being said, if we stay down long enough that a significant numbers of channels are closed, there is a chance
  // other peers forgot about us in the meantime.
  {
    log.info("loading network announcements from db...")
    val channels = db.listChannels()
    val nodes = db.listNodes()
    val updates = db.listChannelUpdates()
    // let's prune the db (maybe eclair was stopped for a long time)
    val staleChannels = getStaleChannels(channels.keys, updates)
    if (staleChannels.nonEmpty) {
      log.info("dropping {} stale channels pre-validation", staleChannels.size)
      staleChannels.foreach(shortChannelId => db.removeChannel(shortChannelId)) // this also removes updates
    }
    val remainingChannels = channels.keys.filterNot(c => staleChannels.contains(c.shortChannelId))
    val remainingUpdates = updates.filterNot(c => staleChannels.contains(c.shortChannelId))
    val remainingNodes = nodes.filter(n => remainingChannels.exists(c => isRelatedTo(c, n.nodeId)))

    val initChannels = remainingChannels.map(c => (c.shortChannelId -> c)).toMap
    val initChannelUpdates = remainingUpdates.map(u => (getDesc(u, initChannels(u.shortChannelId)) -> u)).toMap
    val initNodes = remainingNodes.map(n => (n.nodeId -> n)).toMap

    // send events for these channels/nodes
    remainingChannels.foreach(c => context.system.eventStream.publish(ChannelDiscovered(c, channels(c)._2)))
    remainingNodes.foreach(n => context.system.eventStream.publish(NodeDiscovered(n)))

    // watch the funding tx of all these channels
    // note: some of them may already have been spent, in that case we will receive the watch event immediately
    remainingChannels.foreach { c =>
      val txid = channels(c)._1
      val (_, _, outputIndex) = fromShortId(c.shortChannelId)
      val fundingOutputScript = write(pay2wsh(Scripts.multiSig2of2(PublicKey(c.bitcoinKey1), PublicKey(c.bitcoinKey2))))
      watcher ! WatchSpentBasic(self, txid, outputIndex, fundingOutputScript, BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(c.shortChannelId))
    }

    log.info("loaded from db: channels={} nodes={} updates={}", remainingChannels.size, nodes.size, remainingUpdates.size)
    startWith(NORMAL, Data(initNodes, initChannels, initChannelUpdates, Stash(Map.empty, Map.empty), rebroadcast = Rebroadcast(channels = Map.empty, updates = Map.empty, nodes = Map.empty), awaiting = Map.empty, privateChannels = Map.empty, privateUpdates = Map.empty, excludedChannels = Set.empty, sendingState = Set.empty))
  }

  when(NORMAL) {
    case Event(LocalChannelUpdate(_, _, shortChannelId, remoteNodeId, channelAnnouncement_opt, u), d: Data) =>
      d.channels.get(shortChannelId) match {
        case Some(_) =>
          // channel has already been announced and router knows about it, we can process the channel_update
          stay using handle(u, self, d)
        case None =>
          channelAnnouncement_opt match {
            case Some(c) if d.awaiting.contains(c) =>
              // channel is currently beeing verified, we can process the channel_update right away (it will be stashed)
              stay using handle(u, self, d)
            case Some(c) =>
              // channel wasn't announced but here is the announcement, we will process it *before* the channel_update
              watcher ! ValidateRequest(c)
              val d1 = d.copy(awaiting = d.awaiting + (c -> Nil)) // no origin
              stay using handle(u, self, d1)
            case None if d.privateChannels.contains(shortChannelId) =>
              // channel isn't announced but we already know about it, we can process the channel_update
              stay using handle(u, self, d)
            case None =>
              // channel isn't announced and we never heard of it (maybe it is a private channel or maybe it is a public channel that doesn't yet have 6 confirmations)
              // let's create a corresponding private channel and process the channel_update
              log.info("adding unannounced local channel to remote={} shortChannelId={}", remoteNodeId, shortChannelId.toHexString)
              stay using handle(u, self, d.copy(privateChannels = d.privateChannels + (shortChannelId -> remoteNodeId)))
          }
      }

    case Event(LocalChannelDown(_, channelId, shortChannelId, _), d: Data) =>
      log.debug("removed local channel_update for channelId={} shortChannelId={}", channelId, shortChannelId.toHexString)
      stay using d.copy(privateChannels = d.privateChannels - shortChannelId, privateUpdates = d.privateUpdates.filterKeys(_.id != shortChannelId))

    case Event(s@SendRoutingState(remote), d: Data) =>
      if (d.sendingState.size > 3) {
        log.info("received request to send announcements to {}, already sending state to peers, delaying...", remote, d.sendingState.size)
        context.system.scheduler.scheduleOnce(3 seconds, self, s)
        stay
      } else {
        log.info("info sending all announcements to {}: channels={} nodes={} updates={}", remote, d.channels.size, d.nodes.size, d.updates.size)
        val batch = d.channels.values ++ d.nodes.values ++ d.updates.values
        // we group and add delays to leave room for channel messages
        val actor = context.actorOf(ThrottleForwarder.props(remote, batch, 100, 100 millis))
        context watch actor
        stay using d.copy(sendingState = d.sendingState + actor)
      }

    case Event(Terminated(actor), d: Data) if d.sendingState.contains(actor) =>
      log.info("done sending announcements to a peer, freeing slot")
      stay using d.copy(sendingState = d.sendingState - actor)

    case Event(c: ChannelAnnouncement, d) =>
      log.debug("received channel announcement for shortChannelId={} nodeId1={} nodeId2={} from {}", c.shortChannelId.toHexString, c.nodeId1, c.nodeId2, sender)
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
      } else if (!Announcements.checkSigs(c)) {
        sender ! TransportHandler.ReadAck(c)
        log.warning("bad signature for announcement {}", c)
        sender ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
        stay
      } else {
        log.info("validating shortChannelId={}", c.shortChannelId.toHexString)
        watcher ! ValidateRequest(c)
        // we don't acknowledge the message just yet
        stay using d.copy(awaiting = d.awaiting + (c -> Seq(sender)))
      }

    case Event(v@ValidateResult(c, _, _, _), d0) =>
      d0.awaiting.get(c) match {
        case Some(origin +: others) => origin ! TransportHandler.ReadAck(c) // now we can acknowledge the message, we only need to do it for the first peer that sent us the announcement
        case _ => ()
      }
      log.info("got validation result for shortChannelId={} (awaiting={} stash.nodes={} stash.updates={})", c.shortChannelId.toHexString, d0.awaiting.size, d0.stash.nodes.size, d0.stash.updates.size)
      val success = v match {
        case ValidateResult(c, _, _, Some(t)) =>
          log.warning("validation failure for shortChannelId={} reason={}", c.shortChannelId.toHexString, t.getMessage)
          false
        case ValidateResult(c, Some(tx), true, None) =>
          // TODO: blacklisting
          val (_, _, outputIndex) = fromShortId(c.shortChannelId)
          // let's check that the output is indeed a P2WSH multisig 2-of-2 of nodeid1 and nodeid2)
          val fundingOutputScript = write(pay2wsh(Scripts.multiSig2of2(PublicKey(c.bitcoinKey1), PublicKey(c.bitcoinKey2))))
          if (tx.txOut.size < outputIndex + 1) {
            log.error("invalid script for shortChannelId={}: txid={} does not have outputIndex={} ann={}", c.shortChannelId.toHexString, tx.txid, outputIndex, c)
            false
          } else if (fundingOutputScript != tx.txOut(outputIndex).publicKeyScript) {
            log.error("invalid script for shortChannelId={} txid={} ann={}", c.shortChannelId.toHexString, tx.txid, c)
            false
          } else {
            watcher ! WatchSpentBasic(self, tx, outputIndex, BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(c.shortChannelId))
            // TODO: check feature bit set
            log.debug("added channel channelId={}", c.shortChannelId.toHexString)
            val capacity = tx.txOut(outputIndex).amount
            context.system.eventStream.publish(ChannelDiscovered(c, capacity))
            db.addChannel(c, tx.txid, capacity)

            // in case we just validated our first local channel, we announce the local node
            // note that this will also make sure we always update our node announcement on restart (eg: alias, color), because
            // even if we had stored a previous announcement, it would be overridden by this more recent one
            if (!d0.nodes.contains(nodeParams.nodeId) && isRelatedTo(c, nodeParams.nodeId)) {
              log.info("first local channel validated, announcing local node")
              val nodeAnn = Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.publicAddresses)
              self ! nodeAnn
            }
            true
          }
        case ValidateResult(c, Some(tx), false, None) =>
          // TODO: vulnerability if they flood us with spent funding tx?
          log.warning("ignoring shortChannelId={} tx={} (funding tx not found in utxo)", c.shortChannelId.toHexString, tx.txid)
          // there may be a record if we have just restarted
          db.removeChannel(c.shortChannelId)
          false
        case ValidateResult(c, None, _, None) =>
          // TODO: blacklist?
          log.warning("could not retrieve tx for shortChannelId={}", c.shortChannelId.toHexString)
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

    case Event(n: NodeAnnouncement, d: Data) =>
      if (sender != self) sender ! TransportHandler.ReadAck(n)
      log.debug("received node announcement for nodeId={} from {}", n.nodeId, sender)
      stay using handle(n, sender, d)

    case Event(u: ChannelUpdate, d: Data) =>
      sender ! TransportHandler.ReadAck(u)
      log.debug("received channel update for shortChannelId={} from {}", u.shortChannelId.toHexString, sender)
      stay using handle(u, sender, d)

    case Event(WatchEventSpentBasic(BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(shortChannelId)), d)
      if d.channels.contains(shortChannelId) =>
      val lostChannel = d.channels(shortChannelId)
      log.info("funding tx of channelId={} has been spent", shortChannelId.toHexString)
      // we need to remove nodes that aren't tied to any channels anymore
      val channels1 = d.channels - lostChannel.shortChannelId
      val lostNodes = Seq(lostChannel.nodeId1, lostChannel.nodeId2).filterNot(nodeId => hasChannels(nodeId, channels1.values))
      // let's clean the db and send the events
      log.info("pruning shortChannelId={} (spent)", shortChannelId.toHexString)
      db.removeChannel(shortChannelId) // NB: this also removes channel updates
      context.system.eventStream.publish(ChannelLost(shortChannelId))
      lostNodes.foreach {
        case nodeId =>
          log.info("pruning nodeId={} (spent)", nodeId)
          db.removeNode(nodeId)
          context.system.eventStream.publish(NodeLost(nodeId))
      }
      stay using d.copy(nodes = d.nodes -- lostNodes, channels = d.channels - shortChannelId, updates = d.updates.filterKeys(_.id != shortChannelId))

    case Event(TickBroadcast, d) =>
      if (d.rebroadcast.channels.isEmpty && d.rebroadcast.updates.isEmpty && d.rebroadcast.nodes.isEmpty) {
        stay
      } else {
        log.info("broadcasting routing messages: channels={} updates={} nodes={}", d.rebroadcast.channels.size, d.rebroadcast.updates.size, d.rebroadcast.nodes.size)
        context.actorSelection(context.system / "*" / "switchboard") ! d.rebroadcast
        stay using d.copy(rebroadcast = Rebroadcast(channels = Map.empty, updates = Map.empty, nodes = Map.empty))
      }

    case Event(TickPruneStaleChannels, d) =>
      // first we select channels that we will prune
      val staleChannels = getStaleChannels(d.channels.values, d.updates.values)
      // then we clean up the related channel updates
      val staleUpdates = d.updates.keys.filter(desc => staleChannels.contains(desc.id))
      // finally we remove nodes that aren't tied to any channels anymore
      val channels1 = d.channels -- staleChannels
      val staleNodes = d.nodes.keys.filterNot(nodeId => hasChannels(nodeId, channels1.values))
      // let's clean the db and send the events
      staleChannels.foreach {
        case shortChannelId =>
          log.info("pruning shortChannelId={} (stale)", shortChannelId.toHexString)
          db.removeChannel(shortChannelId) // NB: this also removes channel updates
          context.system.eventStream.publish(ChannelLost(shortChannelId))
      }
      staleNodes.foreach {
        case nodeId =>
          log.info("pruning nodeId={} (stale)", nodeId)
          db.removeNode(nodeId)
          context.system.eventStream.publish(NodeLost(nodeId))
      }
      stay using d.copy(nodes = d.nodes -- staleNodes, channels = channels1, updates = d.updates -- staleUpdates)

    case Event(ExcludeChannel(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      val banDuration = nodeParams.channelExcludeDuration
      log.info("excluding shortChannelId={} from nodeId={} for duration={}", shortChannelId.toHexString, nodeId, banDuration)
      context.system.scheduler.scheduleOnce(banDuration, self, LiftChannelExclusion(desc))
      stay using d.copy(excludedChannels = d.excludedChannels + desc)

    case Event(LiftChannelExclusion(desc@ChannelDesc(shortChannelId, nodeId, _)), d) =>
      log.info("reinstating shortChannelId={} from nodeId={}", shortChannelId.toHexString, nodeId)
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

    case Event('dot, d) =>
      graph2dot(d.nodes, d.channels) pipeTo sender
      stay

    case Event(RouteRequest(start, end, assistedRoutes, ignoreNodes, ignoreChannels), d) =>
      // we convert extra routing info provided in the payment request to fake channel_update
      // it has precedence over all other channel_updates we know
      val assistedUpdates = assistedRoutes.flatMap(toFakeUpdates(_, end))
      // we add them to the private channel_updates
      val updates0 = d.privateUpdates ++ assistedUpdates
      // we add them to the publicly-announced updates (order matters!! local/assisted channel_updates will override channel_updates received by the network)
      val updates1 = d.updates ++ updates0
      // we then filter out the currently excluded channels
      val updates2 = updates1.filterKeys(!d.excludedChannels.contains(_))
      // we also filter out disabled channels, and channels/nodes that are blacklisted for this particular request
      val updates3 = filterUpdates(updates2, ignoreNodes, ignoreChannels)
      log.info("finding a route {}->{} with ignoreNodes={} ignoreChannels={}", start, end, ignoreNodes.map(_.toBin).mkString(","), ignoreChannels.map(_.toHexString).mkString(","))
      findRoute(start, end, updates3).map(r => RouteResponse(r, ignoreNodes, ignoreChannels)) pipeTo sender
      stay
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
      log.debug("ignoring {} (old timestamp or duplicate)", n)
      d
    } else if (!Announcements.checkSig(n)) {
      log.warning("bad signature for {}", n)
      origin ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
      d
    } else if (d.nodes.contains(n.nodeId)) {
      log.debug("updated node nodeId={}", n.nodeId)
      context.system.eventStream.publish(NodeUpdated(n))
      db.updateNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n), rebroadcast = d.rebroadcast.copy(nodes = d.rebroadcast.nodes + (n -> Set(origin))))
    } else if (d.channels.values.exists(c => isRelatedTo(c, n.nodeId))) {
      log.debug("added node nodeId={}", n.nodeId)
      context.system.eventStream.publish(NodeDiscovered(n))
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

  def handle(u: ChannelUpdate, origin: ActorRef, d: Data): Data =
    if (d.channels.contains(u.shortChannelId)) {
      // related channel is already known (note: this means no related channel_update is in the stash)
      val publicChannel = true
      val c = d.channels(u.shortChannelId)
      val desc = getDesc(u, c)
      if (d.rebroadcast.updates.contains(u)) {
        log.debug("ignoring {} (pending rebroadcast)", u)
        val origins = d.rebroadcast.updates(u) + origin
        d.copy(rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> origins)))
      } else if (d.updates.contains(desc) && d.updates(desc).timestamp >= u.timestamp) {
        log.debug("ignoring {} (old timestamp or duplicate)", u)
        d
      } else if (!Announcements.checkSig(u, desc.a)) {
        log.warning("bad signature for announcement shortChannelId={} {}", u, u.shortChannelId.toHexString)
        origin ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
        d
      } else if (d.updates.contains(desc)) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId.toHexString, publicChannel, u.flags, u)
        context.system.eventStream.publish(ChannelUpdateReceived(u))
        db.updateChannelUpdate(u)
        d.copy(updates = d.updates + (desc -> u), rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> Set(origin))))
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId.toHexString, publicChannel, u.flags, u)
        context.system.eventStream.publish(ChannelUpdateReceived(u))
        db.addChannelUpdate(u)
        d.copy(updates = d.updates + (desc -> u), privateUpdates = d.privateUpdates - desc, rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> Set(origin))))
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
      val desc = if (Announcements.isNode1(u.flags)) ChannelDesc(u.shortChannelId, a, b) else ChannelDesc(u.shortChannelId, b, a)
      if (d.updates.contains(desc) && d.updates(desc).timestamp >= u.timestamp) {
        log.debug("ignoring {} (old timestamp or duplicate)", u)
        d
      } else if (!Announcements.checkSig(u, desc.a)) {
        log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId.toHexString, u)
        origin ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
        d
      } else if (d.privateUpdates.contains(desc)) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId.toHexString, publicChannel, u.flags, u)
        context.system.eventStream.publish(ChannelUpdateReceived(u))
        d.copy(privateUpdates = d.privateUpdates + (desc -> u))
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId.toHexString, publicChannel, u.flags, u)
        context.system.eventStream.publish(ChannelUpdateReceived(u))
        d.copy(privateUpdates = d.privateUpdates + (desc -> u))
      }
    } else {
      log.debug("ignoring announcement {} (unknown channel)", u)
      d
    }


}

object Router {

  def props(nodeParams: NodeParams, watcher: ActorRef) = Props(new Router(nodeParams, watcher))

  def toFakeUpdate(extraHop: ExtraHop): ChannelUpdate =
  // the `direction` bit in flags will not be accurate but it doesn't matter because it is not used
  // what matters is that the `disable` bit is 0 so that this update doesn't get filtered out
    ChannelUpdate(signature = "", chainHash = "", extraHop.shortChannelId, Platform.currentTime / 1000, flags = BinaryData("0000"), extraHop.cltvExpiryDelta, htlcMinimumMsat = 0L, extraHop.feeBaseMsat, extraHop.feeProportionalMillionths)

  def toFakeUpdates(extraRoute: Seq[ExtraHop], targetNodeId: PublicKey): Map[ChannelDesc, ChannelUpdate] = {
    // BOLT 11: "For each entry, the pubkey is the node ID of the start of the channel", and the last node is the destination
    val nextNodeIds = extraRoute.map(_.nodeId).drop(1) :+ targetNodeId
    extraRoute.zip(nextNodeIds).map {
      case (extraHop: ExtraHop, nextNodeId) => (ChannelDesc(extraHop.shortChannelId, extraHop.nodeId, nextNodeId) -> toFakeUpdate(extraHop))
    }.toMap
  }

  def getDesc(u: ChannelUpdate, channel: ChannelAnnouncement): ChannelDesc = {
    require(u.flags.data.size == 2, s"invalid flags length ${u.flags.data.size} != 2")
    // the least significant bit tells us if it is node1 or node2
    if (Announcements.isNode1(u.flags)) ChannelDesc(u.shortChannelId, channel.nodeId1, channel.nodeId2) else ChannelDesc(u.shortChannelId, channel.nodeId2, channel.nodeId1)
  }

  def isRelatedTo(c: ChannelAnnouncement, nodeId: PublicKey) = nodeId == c.nodeId1 || nodeId == c.nodeId2

  def hasChannels(nodeId: PublicKey, channels: Iterable[ChannelAnnouncement]): Boolean = channels.exists(c => isRelatedTo(c, nodeId))

  /**
    * Is stale a channel that:
    * (1) is older than 2 weeks (2*7*144 = 2016 blocks)
    * AND
    * (2) has 1 or 2 channel_update and they are older than 2 weeks
    *
    * @param channels
    * @param updates
    * @return
    */
  def getStaleChannels(channels: Iterable[ChannelAnnouncement], updates: Iterable[ChannelUpdate]): Iterable[Long] = {
    // BOLT 7: "nodes MAY prune channels should the timestamp of the latest channel_update be older than 2 weeks (1209600 seconds)"
    // but we don't want to prune brand new channels for which we didn't yet receive a channel update
    val staleThresholdSeconds = Platform.currentTime / 1000 - 1209600
    val staleThresholdBlocks = Globals.blockCount.get() - 2016
    val staleChannels = channels
      .filter(c => fromShortId(c.shortChannelId)._1 < staleThresholdBlocks) // consider only channels older than 2 weeks
      .filter(c => updates.exists(_.shortChannelId == c.shortChannelId)) // channel must have updates
      .filter(c => updates.filter(_.shortChannelId == c.shortChannelId).map(_.timestamp).max < staleThresholdSeconds) // updates are all older than 2 weeks (can have 1 or 2)
    staleChannels.map(_.shortChannelId)
  }

  /**
    * This method is used after a payment failed, and we want to exclude some nodes/channels that we know are failing
    */
  def filterUpdates(updates: Map[ChannelDesc, ChannelUpdate], ignoreNodes: Set[PublicKey], ignoreChannels: Set[Long]) =
    updates
      .filterNot(u => ignoreNodes.map(_.toBin).contains(u._1.a) || ignoreNodes.map(_.toBin).contains(u._1.b))
      .filterNot(u => ignoreChannels.contains(u._1.id))
      .filter(u => Announcements.isEnabled(u._2.flags))

  def findRouteDijkstra(localNodeId: PublicKey, targetNodeId: PublicKey, channels: Iterable[ChannelDesc]): Seq[ChannelDesc] = {
    if (localNodeId == targetNodeId) throw CannotRouteToSelf
    case class DescEdge(desc: ChannelDesc) extends DefaultEdge
    val g = new DefaultDirectedGraph[PublicKey, DescEdge](classOf[DescEdge])
    Random.shuffle(channels).foreach(d => {
      g.addVertex(d.a)
      g.addVertex(d.b)
      g.addEdge(d.a, d.b, new DescEdge(d))
    })
    Try(Option(DijkstraShortestPath.findPathBetween(g, localNodeId, targetNodeId))) match {
      case Success(Some(path)) => path.getEdgeList.map(_.desc)
      case _ => throw RouteNotFound
    }
  }

  def findRoute(localNodeId: PublicKey, targetNodeId: PublicKey, updates: Map[ChannelDesc, ChannelUpdate])(implicit ec: ExecutionContext): Future[Seq[Hop]] = Future {
    findRouteDijkstra(localNodeId, targetNodeId, updates.keys)
      .map(desc => Hop(desc.a, desc.b, updates(desc)))
  }

  def graph2dot(nodes: Map[PublicKey, NodeAnnouncement], channels: Map[Long, ChannelAnnouncement])(implicit ec: ExecutionContext): Future[String] = Future {
    case class DescEdge(channelId: Long) extends DefaultEdge
    val g = new SimpleGraph[PublicKey, DescEdge](classOf[DescEdge])
    channels.foreach(d => {
      g.addVertex(d._2.nodeId1)
      g.addVertex(d._2.nodeId2)
      g.addEdge(d._2.nodeId1, d._2.nodeId2, new DescEdge(d._1))
    })
    val vertexIDProvider = new ComponentNameProvider[PublicKey]() {
      override def getName(nodeId: PublicKey): String = "\"" + nodeId.toString() + "\""
    }
    val edgeLabelProvider = new ComponentNameProvider[DescEdge]() {
      override def getName(e: DescEdge): String = e.channelId.toString
    }
    val vertexAttributeProvider = new ComponentAttributeProvider[PublicKey]() {

      override def getComponentAttributes(nodeId: PublicKey): java.util.Map[String, String] =

        nodes.get(nodeId) match {
          case Some(ann) => Map("label" -> ann.alias, "color" -> ann.rgbColor.toString)
          case None => Map.empty[String, String]
        }
    }
    val exporter = new DOTExporter[PublicKey, DescEdge](vertexIDProvider, null, edgeLabelProvider, vertexAttributeProvider, null)
    val writer = new StringWriter()
    try {
      exporter.exportGraph(g, writer)
      writer.toString
    } finally {
      writer.close()
    }

  }


}
