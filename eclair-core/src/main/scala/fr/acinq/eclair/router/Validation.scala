/*
 * Copyright 2020 ACINQ SAS
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

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.actor.{ActorContext, ActorRef, typed}
import akka.event.{DiagnosticLoggingAdapter, LoggingAdapter}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.Script.{pay2wsh, write}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{UtxoStatus, ValidateRequest, ValidateResult, WatchExternalChannelSpent}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.Graph.GraphStructure.GraphEdge
import fr.acinq.eclair.router.Monitoring.Metrics
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Logs, MilliSatoshiLong, NodeParams, RealShortChannelId, ShortChannelId, TxCoordinates}

object Validation {

  private def sendDecision(origins: Set[GossipOrigin], decision: GossipDecision)(implicit sender: ActorRef): Unit = {
    origins.collect { case RemoteGossip(peerConnection, _) => sendDecision(peerConnection, decision) }
  }

  private def sendDecision(peerConnection: ActorRef, decision: GossipDecision)(implicit sender: ActorRef): Unit = {
    peerConnection ! decision
    Metrics.gossipResult(decision).increment()
  }

  def handleChannelAnnouncement(d: Data, db: NetworkDb, watcher: typed.ActorRef[ZmqWatcher.Command], origin: RemoteGossip, c: ChannelAnnouncement)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    log.debug("received channel announcement for shortChannelId={} nodeId1={} nodeId2={}", c.shortChannelId, c.nodeId1, c.nodeId2)
    if (d.channels.contains(c.shortChannelId)) {
      origin.peerConnection ! TransportHandler.ReadAck(c)
      log.debug("ignoring {} (duplicate)", c)
      sendDecision(origin.peerConnection, GossipDecision.Duplicate(c))
      d
    } else if (d.awaiting.contains(c)) {
      origin.peerConnection ! TransportHandler.ReadAck(c)
      log.debug("ignoring {} (being verified)", c)
      // adding the sender to the list of origins so that we don't send back the same announcement to this peer later
      val origins = d.awaiting(c) :+ origin
      d.copy(awaiting = d.awaiting + (c -> origins))
    } else if (db.isPruned(c.shortChannelId)) {
      origin.peerConnection ! TransportHandler.ReadAck(c)
      // channel was pruned and we haven't received a recent channel_update, so we have no reason to revalidate it
      log.debug("ignoring {} (was pruned)", c)
      sendDecision(origin.peerConnection, GossipDecision.ChannelPruned(c))
      d
    } else if (!Announcements.checkSigs(c)) {
      origin.peerConnection ! TransportHandler.ReadAck(c)
      log.warning("bad signature for announcement {}", c)
      sendDecision(origin.peerConnection, GossipDecision.InvalidSignature(c))
      d
    } else {
      log.info("validating shortChannelId={}", c.shortChannelId)
      watcher ! ValidateRequest(ctx.self, c)
      // we don't acknowledge the message just yet
      d.copy(awaiting = d.awaiting + (c -> Seq(origin)))
    }
  }

  def handleChannelValidationResponse(d0: Data, nodeParams: NodeParams, watcher: typed.ActorRef[ZmqWatcher.Command], r: ValidateResult)(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    import nodeParams.db.{network => db}
    import r.c
    // now we can acknowledge the message, we only need to do it for the first peer that sent us the announcement
    // (the other ones have already been acknowledged as duplicates)
    d0.awaiting.getOrElse(c, Seq.empty).headOption match {
      case Some(origin: RemoteGossip) => origin.peerConnection ! TransportHandler.ReadAck(c)
      case Some(LocalGossip) => () // there is nothing to ack if it was a local gossip
      case _ => ()
    }
    val remoteOrigins = d0.awaiting.getOrElse(c, Set.empty).collect { case rg: RemoteGossip => rg }
    Logs.withMdc(log)(Logs.mdc(remoteNodeId_opt = remoteOrigins.headOption.map(_.nodeId))) { // in the MDC we use the node id that sent us the announcement first
      log.debug("got validation result for shortChannelId={} (awaiting={} stash.nodes={} stash.updates={})", c.shortChannelId, d0.awaiting.size, d0.stash.nodes.size, d0.stash.updates.size)
      val publicChannel_opt = r match {
        case ValidateResult(c, Left(t)) =>
          log.warning("validation failure for shortChannelId={} reason={}", c.shortChannelId, t.getMessage)
          remoteOrigins.foreach(o => sendDecision(o.peerConnection, GossipDecision.ValidationFailure(c)))
          None
        case ValidateResult(c, Right((tx, UtxoStatus.Unspent))) =>
          val TxCoordinates(_, _, outputIndex) = ShortChannelId.coordinates(c.shortChannelId)
          val (fundingOutputScript, fundingOutputIsInvalid) = {
            // let's check that the output is indeed a P2WSH multisig 2-of-2 of nodeid1 and nodeid2)
            val fundingOutputScript = write(pay2wsh(Scripts.multiSig2of2(c.bitcoinKey1, c.bitcoinKey2)))
            val fundingOutputIsInvalid = tx.txOut.size < outputIndex + 1 || fundingOutputScript != tx.txOut(outputIndex).publicKeyScript
            (fundingOutputScript, fundingOutputIsInvalid)
          }
          if (fundingOutputIsInvalid) {
            log.error(s"invalid script for shortChannelId={}: txid={} does not have script=$fundingOutputScript at outputIndex=$outputIndex ann={}", c.shortChannelId, tx.txid, c)
            remoteOrigins.foreach(o => sendDecision(o.peerConnection, GossipDecision.InvalidAnnouncement(c)))
            None
          } else {
            watcher ! WatchExternalChannelSpent(ctx.self, tx.txid, outputIndex, c.shortChannelId)
            log.debug("added channel channelId={}", c.shortChannelId)
            remoteOrigins.foreach(o => sendDecision(o.peerConnection, GossipDecision.Accepted(c)))
            val capacity = tx.txOut(outputIndex).amount
            ctx.system.eventStream.publish(ChannelsDiscovered(SingleChannelDiscovered(c, capacity, None, None) :: Nil))
            db.addChannel(c, tx.txid, capacity)
            Some(PublicChannel(c,
              tx.txid,
              capacity,
              update_1_opt = None,
              update_2_opt = None,
              meta_opt = None))
          }
        case ValidateResult(c, Right((tx, fundingTxStatus: UtxoStatus.Spent))) =>
          if (fundingTxStatus.spendingTxConfirmed) {
            log.debug("ignoring shortChannelId={} tx={} (funding tx already spent and spending tx is confirmed)", c.shortChannelId, tx.txid)
            // the funding tx has been spent by a transaction that is now confirmed: peer shouldn't send us those
            remoteOrigins.foreach(o => sendDecision(o.peerConnection, GossipDecision.ChannelClosed(c)))
          } else {
            log.debug("ignoring shortChannelId={} tx={} (funding tx already spent but spending tx isn't confirmed)", c.shortChannelId, tx.txid)
            remoteOrigins.foreach(o => sendDecision(o.peerConnection, GossipDecision.ChannelClosing(c)))
          }
          // there may be a record if we have just restarted
          db.removeChannel(c.shortChannelId)
          None
      }
      // we also reprocess node and channel_update announcements related to the channel that was just analyzed
      val reprocessUpdates = d0.stash.updates.view.filterKeys(u => u.shortChannelId == c.shortChannelId)
      val reprocessNodes = d0.stash.nodes.view.filterKeys(n => isRelatedTo(c, n.nodeId))
      // and we remove the reprocessed messages from the stash
      val stash1 = d0.stash.copy(updates = d0.stash.updates -- reprocessUpdates.keys, nodes = d0.stash.nodes -- reprocessNodes.keys)
      // we remove channel from awaiting map
      val awaiting1 = d0.awaiting - c

      publicChannel_opt match {
        case Some(publicChannel) =>
          val d1 = addPublicChannel(d0, nodeParams, publicChannel).copy(stash = stash1, awaiting = awaiting1)
          // we process channel updates and node announcements if validation succeeded
          val d2 = reprocessUpdates.foldLeft(d1) {
            case (d, (u, origins)) => Validation.handleChannelUpdate(d, nodeParams.db.network, nodeParams.routerConf, Right(RemoteChannelUpdate(u, origins)), wasStashed = true)
          }
          val d3 = reprocessNodes.foldLeft(d2) {
            case (d, (n, origins)) => Validation.handleNodeAnnouncement(d, nodeParams.db.network, origins, n, wasStashed = true)
          }
          d3
        case None =>
          // if validation failed we can fast-discard related announcements
          reprocessUpdates.foreach { case (u, origins) => origins.collect { case o: RemoteGossip => sendDecision(o.peerConnection, GossipDecision.NoRelatedChannel(u)) } }
          reprocessNodes.foreach { case (n, origins) => origins.collect { case o: RemoteGossip => sendDecision(o.peerConnection, GossipDecision.NoKnownChannel(n)) } }
          d0.copy(stash = stash1, awaiting = awaiting1)
      }
    }
  }

  private def addPublicChannel(d: Data, nodeParams: NodeParams, pc: PublicChannel)(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    log.debug("adding public channel channelId={} realScid={}", pc.channelId, pc.shortChannelId)
    // in case this was our first local channel, we make a node announcement
    if (!d.nodes.contains(nodeParams.nodeId)) {
      log.info("first local channel validated, announcing local node")
      val nodeAnn = Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.publicAddresses, nodeParams.features.nodeAnnouncementFeatures())
      ctx.self ! nodeAnn
    }
    // those updates are only defined if this was a previously an unannounced local channel, we broadcast them if they use the real scid
    val updates1 = (pc.update_1_opt.toSet ++ pc.update_2_opt.toSet)
      .filter(_.shortChannelId == pc.shortChannelId)
      .map(u => u -> (if (pc.getNodeIdSameSideAs(u) == nodeParams.nodeId) Set[GossipOrigin](LocalGossip) else Set.empty[GossipOrigin]))
      .toMap
    d.copy(
      channels = d.channels + (pc.shortChannelId -> pc),
      // we remove the corresponding unannounced channel that we may have until now
      privateChannels = d.privateChannels - pc.channelId,
      // we also add the newly validated channels to the rebroadcast queue
      rebroadcast = d.rebroadcast.copy(
        // we rebroadcast the channel to our peers
        channels = d.rebroadcast.channels + (pc.ann -> d.awaiting.getOrElse(pc.ann, if (pc.nodeId1 == nodeParams.nodeId || pc.nodeId2 == nodeParams.nodeId) Seq(LocalGossip) else Nil).toSet),
        // those updates are only defined if the channel is was previously an unannounced local channel, we broadcast them
        updates = d.rebroadcast.updates ++ updates1
      )
    )
  }

  def handleChannelSpent(d: Data, db: NetworkDb, shortChannelId: RealShortChannelId)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    val lostChannel = d.channels(shortChannelId).ann
    log.info("funding tx of channelId={} has been spent", shortChannelId)
    // we need to remove nodes that aren't tied to any channels anymore
    val channels1 = d.channels - lostChannel.shortChannelId
    val lostNodes = Seq(lostChannel.nodeId1, lostChannel.nodeId2).filterNot(nodeId => hasChannels(nodeId, channels1.values))
    // let's clean the db and send the events
    log.info("pruning shortChannelId={} (spent)", shortChannelId)
    db.removeChannel(shortChannelId) // NB: this also removes channel updates
    // we also need to remove updates from the graph
    val graphWithBalances1 = d.graphWithBalances
      .removeEdge(ChannelDesc(lostChannel.shortChannelId, lostChannel.nodeId1, lostChannel.nodeId2))
      .removeEdge(ChannelDesc(lostChannel.shortChannelId, lostChannel.nodeId2, lostChannel.nodeId1))

    ctx.system.eventStream.publish(ChannelLost(shortChannelId))
    lostNodes.foreach {
      nodeId =>
        log.info("pruning nodeId={} (spent)", nodeId)
        db.removeNode(nodeId)
        ctx.system.eventStream.publish(NodeLost(nodeId))
    }
    d.copy(nodes = d.nodes -- lostNodes, channels = d.channels - shortChannelId, graphWithBalances = graphWithBalances1)
  }

  def handleNodeAnnouncement(d: Data, db: NetworkDb, origins: Set[GossipOrigin], n: NodeAnnouncement, wasStashed: Boolean = false)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    val remoteOrigins = origins flatMap {
      case r: RemoteGossip if wasStashed =>
        Some(r.peerConnection)
      case RemoteGossip(peerConnection, _) =>
        peerConnection ! TransportHandler.ReadAck(n)
        log.debug("received node announcement for nodeId={}", n.nodeId)
        Some(peerConnection)
      case LocalGossip =>
        log.debug("received node announcement from {}", ctx.sender())
        None
    }
    if (d.stash.nodes.contains(n)) {
      log.debug("ignoring {} (already stashed)", n)
      val origins1 = d.stash.nodes(n) ++ origins
      d.copy(stash = d.stash.copy(nodes = d.stash.nodes + (n -> origins1)))
    } else if (d.rebroadcast.nodes.contains(n)) {
      log.debug("ignoring {} (pending rebroadcast)", n)
      remoteOrigins.foreach(sendDecision(_, GossipDecision.Accepted(n)))
      val origins1 = d.rebroadcast.nodes(n) ++ origins
      d.copy(rebroadcast = d.rebroadcast.copy(nodes = d.rebroadcast.nodes + (n -> origins1)))
    } else if (d.nodes.contains(n.nodeId) && d.nodes(n.nodeId).timestamp >= n.timestamp) {
      log.debug("ignoring {} (duplicate)", n)
      remoteOrigins.foreach(sendDecision(_, GossipDecision.Duplicate(n)))
      d
    } else if (!Announcements.checkSig(n)) {
      log.warning("bad signature for {}", n)
      remoteOrigins.foreach(sendDecision(_, GossipDecision.InvalidSignature(n)))
      d
    } else if (d.nodes.contains(n.nodeId)) {
      log.debug("updated node nodeId={}", n.nodeId)
      remoteOrigins.foreach(sendDecision(_, GossipDecision.Accepted(n)))
      ctx.system.eventStream.publish(NodeUpdated(n))
      db.updateNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n), rebroadcast = d.rebroadcast.copy(nodes = d.rebroadcast.nodes + (n -> origins)))
    } else if (d.channels.values.exists(c => isRelatedTo(c.ann, n.nodeId))) {
      log.debug("added node nodeId={}", n.nodeId)
      remoteOrigins.foreach(sendDecision(_, GossipDecision.Accepted(n)))
      ctx.system.eventStream.publish(NodesDiscovered(n :: Nil))
      db.addNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n), rebroadcast = d.rebroadcast.copy(nodes = d.rebroadcast.nodes + (n -> origins)))
    } else if (d.awaiting.keys.exists(c => isRelatedTo(c, n.nodeId))) {
      log.debug("stashing {}", n)
      d.copy(stash = d.stash.copy(nodes = d.stash.nodes + (n -> origins)))
    } else {
      log.debug("ignoring {} (no related channel found)", n)
      remoteOrigins.foreach(sendDecision(_, GossipDecision.NoKnownChannel(n)))
      // there may be a record if we have just restarted
      db.removeNode(n.nodeId)
      d
    }
  }

  def handleChannelUpdate(d: Data, db: NetworkDb, routerConf: RouterConf, update: Either[LocalChannelUpdate, RemoteChannelUpdate], wasStashed: Boolean = false)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    val (pc_opt: Option[KnownChannel], u: ChannelUpdate, origins: Set[GossipOrigin]) = update match {
      case Left(lcu) => (d.resolve(lcu.channelId, lcu.realShortChannelId_opt), lcu.channelUpdate, Set(LocalGossip))
      case Right(rcu) =>
        rcu.origins.collect {
          case RemoteGossip(peerConnection, _) if !wasStashed => // stashed changes have already been acknowledged
            log.debug("received channel update for shortChannelId={}", rcu.channelUpdate.shortChannelId)
            peerConnection ! TransportHandler.ReadAck(rcu.channelUpdate)
        }
        (d.resolve(rcu.channelUpdate.shortChannelId), rcu.channelUpdate, rcu.origins)
    }
    pc_opt match {
      case Some(pc: PublicChannel) =>
        // related channel is already known (note: this means no related channel_update is in the stash)
        val publicChannel = true
        if (d.rebroadcast.updates.contains(u)) {
          log.debug("ignoring {} (pending rebroadcast)", u)
          sendDecision(origins, GossipDecision.Accepted(u))
          val origins1 = d.rebroadcast.updates(u) ++ origins
          // NB: we update the channels because the balances may have changed even if the channel_update is the same.
          val pc1 = pc.applyChannelUpdate(update)
          val graphWithBalances1 = d.graphWithBalances.addEdge(GraphEdge(u, pc1))
          d.copy(rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> origins1)), channels = d.channels + (pc.shortChannelId -> pc1), graphWithBalances = graphWithBalances1)
        } else if (StaleChannels.isStale(u)) {
          log.debug("ignoring {} (stale)", u)
          sendDecision(origins, GossipDecision.Stale(u))
          d
        } else if (pc.getChannelUpdateSameSideAs(u).exists(previous => previous.timestamp >= u.timestamp && previous.shortChannelId == u.shortChannelId)) { // NB: we also check the id because there could be a switch alias->real scid
          log.debug("ignoring {} (duplicate)", u)
          sendDecision(origins, GossipDecision.Duplicate(u))
          update match {
            case Left(_) =>
              // NB: we update the graph because the balances may have changed even if the channel_update is the same.
              val pc1 = pc.applyChannelUpdate(update)
              val graphWithBalances1 = d.graphWithBalances.addEdge(GraphEdge(u, pc1))
              d.copy(channels = d.channels + (pc.shortChannelId -> pc1), graphWithBalances = graphWithBalances1)
            case Right(_) => d
          }
        } else if (!Announcements.checkSig(u, pc.getNodeIdSameSideAs(u))) {
          log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId, u)
          sendDecision(origins, GossipDecision.InvalidSignature(u))
          d
        } else if (pc.getChannelUpdateSameSideAs(u).isDefined) {
          log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
          Metrics.channelUpdateRefreshed(u, pc.getChannelUpdateSameSideAs(u).get, publicChannel)
          sendDecision(origins, GossipDecision.Accepted(u))
          ctx.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
          db.updateChannel(u)
          // update the graph
          val pc1 = pc.applyChannelUpdate(update)
          val graphWithBalances1 = if (u.channelFlags.isEnabled) {
            update.left.foreach(_ => log.info("added local shortChannelId={} public={} to the network graph", u.shortChannelId, publicChannel))
            d.graphWithBalances.addEdge(GraphEdge(u, pc1))
          } else {
            update.left.foreach(_ => log.info("removed local shortChannelId={} public={} from the network graph", u.shortChannelId, publicChannel))
            d.graphWithBalances.removeEdge(ChannelDesc(u, pc1.ann))
          }
          d.copy(channels = d.channels + (pc.shortChannelId -> pc1), rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> origins)), graphWithBalances = graphWithBalances1)
        } else {
          log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
          sendDecision(origins, GossipDecision.Accepted(u))
          ctx.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
          db.updateChannel(u)
          // we also need to update the graph
          val pc1 = pc.applyChannelUpdate(update)
          val graphWithBalances1 = d.graphWithBalances.addEdge(GraphEdge(u, pc1))
          update.left.foreach(_ => log.info("added local shortChannelId={} public={} to the network graph", u.shortChannelId, publicChannel))
          d.copy(channels = d.channels + (pc.shortChannelId -> pc1), privateChannels = d.privateChannels - pc1.channelId, rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> origins)), graphWithBalances = graphWithBalances1)
        }
      case Some(pc: PrivateChannel) =>
        val publicChannel = false
        if (StaleChannels.isStale(u)) {
          log.debug("ignoring {} (stale)", u)
          sendDecision(origins, GossipDecision.Stale(u))
          d
        } else if (pc.getChannelUpdateSameSideAs(u).exists(_.timestamp >= u.timestamp)) {
          log.debug("ignoring {} (already know same or newer)", u)
          sendDecision(origins, GossipDecision.Duplicate(u))
          d
        } else if (!Announcements.checkSig(u, pc.getNodeIdSameSideAs(u))) {
          log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId, u)
          sendDecision(origins, GossipDecision.InvalidSignature(u))
          d
        } else if (pc.getChannelUpdateSameSideAs(u).isDefined) {
          log.debug("updated channel_update for channelId={} public={} flags={} {}", pc.channelId, publicChannel, u.channelFlags, u)
          Metrics.channelUpdateRefreshed(u, pc.getChannelUpdateSameSideAs(u).get, publicChannel)
          sendDecision(origins, GossipDecision.Accepted(u))
          ctx.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
          // we also need to update the graph
          val pc1 = pc.applyChannelUpdate(update)
          val graphWithBalances1 = if (u.channelFlags.isEnabled) {
            update.left.foreach(_ => log.info("added local channelId={} public={} to the network graph", pc.channelId, publicChannel))
            d.graphWithBalances.addEdge(GraphEdge(u, pc1))
          } else {
            update.left.foreach(_ => log.info("removed local channelId={} public={} from the network graph", pc.channelId, publicChannel))
            d.graphWithBalances.removeEdge(ChannelDesc(u, pc1))
          }
          d.copy(privateChannels = d.privateChannels + (pc.channelId -> pc1), graphWithBalances = graphWithBalances1)
        } else {
          log.debug("added channel_update for channelId={} public={} flags={} {}", pc.channelId, publicChannel, u.channelFlags, u)
          sendDecision(origins, GossipDecision.Accepted(u))
          ctx.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
          // we also need to update the graph
          val pc1 = pc.applyChannelUpdate(update)
          val graphWithBalances1 = d.graphWithBalances.addEdge(GraphEdge(u, pc1))
          update.left.foreach(_ => log.info("added local channelId={} public={} to the network graph", pc.channelId, publicChannel))
          d.copy(privateChannels = d.privateChannels + (pc.channelId -> pc1), graphWithBalances = graphWithBalances1)
        }
      case None if d.awaiting.keys.exists(c => c.shortChannelId == u.shortChannelId) =>
        // channel is currently being validated
        if (d.stash.updates.contains(u)) {
          log.debug("ignoring {} (already stashed)", u)
          val origins1 = d.stash.updates(u) ++ origins
          d.copy(stash = d.stash.copy(updates = d.stash.updates + (u -> origins1)))
        } else {
          log.debug("stashing {}", u)
          d.copy(stash = d.stash.copy(updates = d.stash.updates + (u -> origins)))
        }
      case None if db.isPruned(u.shortChannelId) && !StaleChannels.isStale(u) =>
        // only public channels are pruned
        val realShortChannelId = u.shortChannelId.toReal
        // the channel was recently pruned, but if we are here, it means that the update is not stale so this is the case
        // of a zombie channel coming back from the dead. they probably sent us a channel_announcement right before this update,
        // but we ignored it because the channel was in the 'pruned' list. Now that we know that the channel is alive again,
        // let's remove the channel from the zombie list and ask the sender to re-send announcements (channel_announcement + updates)
        // about that channel. We can ignore this update since we will receive it again
        log.info(s"channel shortChannelId=${realShortChannelId} is back from the dead! requesting announcements about this channel")
        sendDecision(origins, GossipDecision.RelatedChannelPruned(u))
        db.removeFromPruned(realShortChannelId)
        // peerConnection_opt will contain a valid peerConnection only when we're handling an update that we received from a peer, not
        // when we're sending updates to ourselves
        origins head match {
          case RemoteGossip(peerConnection, remoteNodeId) =>
            val query = QueryShortChannelIds(u.chainHash, EncodedShortChannelIds(routerConf.encodingType, List(realShortChannelId)), TlvStream.empty)
            d.sync.get(remoteNodeId) match {
              case Some(sync) if sync.started =>
                // we already have a pending request to that node, let's add this channel to the list and we'll get it later
                // TODO: we only request channels with old style channel_query
                d.copy(sync = d.sync + (remoteNodeId -> sync.copy(remainingQueries = sync.remainingQueries :+ query, totalQueries = sync.totalQueries + 1)))
              case _ =>
                // otherwise we send the query right away
                peerConnection ! query
                d.copy(sync = d.sync + (remoteNodeId -> Syncing(remainingQueries = Nil, totalQueries = 1)))
            }
          case _ =>
            // we don't know which node this update came from (maybe it was stashed and the channel got pruned in the meantime or some other corner case).
            // or we don't have a peerConnection to send our query to.
            // anyway, that's not really a big deal because we have removed the channel from the pruned db so next time it shows up we will revalidate it
            d
        }
      case None =>
        log.debug("ignoring announcement {} (unknown channel)", u)
        sendDecision(origins, GossipDecision.NoRelatedChannel(u))
        d
    }
  }

  /**
   * We will receive this event before [[LocalChannelUpdate]] or [[ChannelUpdate]]
   */
  def handleShortChannelIdAssigned(d: Data, localNodeId: PublicKey, scia: ShortChannelIdAssigned)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    // NB: we don't map remote aliases because they are decided our peer and could overlap with ours
    val mappings = scia.realShortChannelId_opt match {
      case Some(realScid) => Map(realScid -> scia.channelId, scia.localAlias -> scia.channelId)
      case None => Map(scia.localAlias -> scia.channelId)
    }
    val d1 = d.copy(scid2PrivateChannels = d.scid2PrivateChannels ++ mappings)
    d1.resolve(scia.channelId, scia.realShortChannelId_opt) match {
      case Some(_) =>
        // channel is known, nothing more to do
        d1
      case None =>
        // this is a local channel that hasn't yet been announced (maybe it is a private channel or maybe it is a public
        // channel that doesn't yet have 6 confirmations), we create a corresponding private channel
        val pc = PrivateChannel(scia.localAlias, scia.channelId, localNodeId, scia.remoteNodeId, None, None, ChannelMeta(0 msat, 0 msat))
        log.debug("adding unannounced local channel to remote={} channelId={} localAlias={}", scia.remoteNodeId, scia.channelId, scia.localAlias)
        d1.copy(privateChannels = d1.privateChannels + (scia.channelId -> pc))
    }
  }

  def handleLocalChannelUpdate(d: Data, nodeParams: NodeParams, lcu: LocalChannelUpdate)(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    import nodeParams.db.{network => db}
    d.resolve(lcu.channelId, lcu.realShortChannelId_opt) match {
      case Some(_: PublicChannel) =>
        // this a known public channel, we can process the channel_update
        handleChannelUpdate(d, db, nodeParams.routerConf, Left(lcu))
      case Some(privateChannel: PrivateChannel) =>
        lcu.channelAnnouncement_opt match {
          case Some(ann) =>
            // channel is graduating from private to public
            // since this is a local channel, we can trust the announcement, no need to go through the full
            // verification process and make calls to bitcoin core
            val commitments = lcu.commitments.asInstanceOf[Commitments] // TODO: ugly! a public channel has to have a real commitment
            val publicChannel = PublicChannel(
              ann = ann,
              fundingTxid = commitments.commitInput.outPoint.txid,
              capacity = commitments.capacity,
              update_1_opt = privateChannel.update_1_opt,
              update_2_opt = privateChannel.update_2_opt,
              meta_opt = Some(privateChannel.meta)
            )
            val d1 = addPublicChannel(d, nodeParams, publicChannel)
            // maybe the local channel was pruned (can happen if we were disconnected for more than 2 weeks)
            db.removeFromPruned(ann.shortChannelId)
            val d2 = handleChannelUpdate(d1, db, nodeParams.routerConf, Left(lcu))
            d2
          case None =>
            // this is a known unannounced channel, we can process the channel_update
            handleChannelUpdate(d, db, nodeParams.routerConf, Left(lcu))
        }
      case None =>
        // should never happen, we log a warning and handle the update, it will be rejected since there is no related channel
        log.warning("unrecognized local chanel update for channelId={} localAlias={}", lcu.channelId, lcu.localAlias)
        handleChannelUpdate(d, db, nodeParams.routerConf, Left(lcu))
    }
  }

  def handleLocalChannelDown(d: Data, localNodeId: PublicKey, lcd: LocalChannelDown)(implicit log: LoggingAdapter): Data = {
    import lcd.{channelId, remoteNodeId}
    // a local channel has permanently gone down
    if (lcd.realShortChannelId_opt.exists(d.channels.contains)) {
      // the channel was public, we will receive (or have already received) a WatchEventSpentBasic event, that will trigger a clean up of the channel
      // so let's not do anything here
      d
    } else if (d.privateChannels.contains(lcd.channelId)) {
      // the channel was private or public-but-not-yet-announced, let's do the clean up
      val localAlias = d.privateChannels(channelId).localAlias
      log.info("removing private local channel and channel_update for channelId={} localAlias={}", channelId, localAlias)
      val desc1 = ChannelDesc(localAlias, localNodeId, remoteNodeId)
      val desc2 = ChannelDesc(localAlias, remoteNodeId, localNodeId)
      // we remove the corresponding updates from the graph
      val graphWithBalances1 = d.graphWithBalances
        .removeEdge(desc1)
        .removeEdge(desc2)
      // and we remove the channel and channel_update from our state
      d.copy(privateChannels = d.privateChannels - channelId, graphWithBalances = graphWithBalances1)
    } else {
      d
    }
  }

  def handleAvailableBalanceChanged(d: Data, e: AvailableBalanceChanged)(implicit log: LoggingAdapter): Data = {
    val (publicChannels1, graphWithBalances1) = e.realShortChannelId_opt.flatMap(d.channels.get) match {
      case Some(pc) =>
        val pc1 = pc.updateBalances(e.commitments)
        log.debug("public channel balance updated: {}", pc1)
        val update_opt = if (e.commitments.localNodeId == pc1.ann.nodeId1) pc1.update_1_opt else pc1.update_2_opt
        val graphWithBalances1 = update_opt.map(u => d.graphWithBalances.addEdge(GraphEdge(u, pc1))).getOrElse(d.graphWithBalances)
        (d.channels + (pc.ann.shortChannelId -> pc1), graphWithBalances1)
      case None =>
        (d.channels, d.graphWithBalances)
    }
    val (privateChannels1, graphWithBalances2) = d.privateChannels.get(e.channelId) match {
      case Some(pc) =>
        val pc1 = pc.updateBalances(e.commitments)
        log.debug("private channel balance updated: {}", pc1)
        val update_opt = if (e.commitments.localNodeId == pc1.nodeId1) pc1.update_1_opt else pc1.update_2_opt
        val graphWithBalances2 = update_opt.map(u => graphWithBalances1.addEdge(GraphEdge(u, pc1))).getOrElse(graphWithBalances1)
        (d.privateChannels + (e.channelId -> pc1), graphWithBalances2)
      case None =>
        (d.privateChannels, graphWithBalances1)
    }
    d.copy(channels = publicChannels1, privateChannels = privateChannels1, graphWithBalances = graphWithBalances2)
  }

}
