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

import akka.actor.{ActorContext, ActorRef}
import akka.event.{DiagnosticLoggingAdapter, LoggingAdapter}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.eclair.blockchain.{UtxoStatus, ValidateRequest, ValidateResult, WatchSpentBasic}
import fr.acinq.eclair.channel.{AvailableBalanceChanged, BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT, LocalChannelDown, LocalChannelUpdate}
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.Monitoring.Metrics
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Logs, MilliSatoshiLong, NodeParams, ShortChannelId, TxCoordinates}

object Validation {

  private def sendDecision(origins: Set[GossipOrigin], decision: GossipDecision)(implicit sender: ActorRef): Unit = {
    origins.collect { case RemoteGossip(peerConnection, _) => sendDecision(peerConnection, decision) }
  }

  private def sendDecision(peerConnection: ActorRef, decision: GossipDecision)(implicit sender: ActorRef): Unit = {
    peerConnection ! decision
    Metrics.gossipResult(decision).increment()
  }

  def handleChannelAnnouncement(d: Data, db: NetworkDb, watcher: ActorRef, origin: RemoteGossip, c: ChannelAnnouncement)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
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
      watcher ! ValidateRequest(c)
      // we don't acknowledge the message just yet
      d.copy(awaiting = d.awaiting + (c -> Seq(origin)))
    }
  }

  def handleChannelValidationResponse(d0: Data, nodeParams: NodeParams, watcher: ActorRef, r: ValidateResult)(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    import nodeParams.db.{network => db}
    import r.c
    d0.awaiting.get(c) match {
      case Some(origin +: _) => origin.peerConnection ! TransportHandler.ReadAck(c) // now we can acknowledge the message, we only need to do it for the first peer that sent us the announcement
      case _ => ()
    }
    val remoteOrigins_opt = d0.awaiting.get(c)
    Logs.withMdc(log)(Logs.mdc(remoteNodeId_opt = remoteOrigins_opt.flatMap(_.headOption).map(_.nodeId))) { // in the MDC we use the node id that sent us the announcement first
      log.info("got validation result for shortChannelId={} (awaiting={} stash.nodes={} stash.updates={})", c.shortChannelId, d0.awaiting.size, d0.stash.nodes.size, d0.stash.updates.size)
      val publicChannel_opt = r match {
        case ValidateResult(c, Left(t)) =>
          log.warning("validation failure for shortChannelId={} reason={}", c.shortChannelId, t.getMessage)
          remoteOrigins_opt.foreach(_.foreach(o => sendDecision(o.peerConnection, GossipDecision.ValidationFailure(c))))
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
            remoteOrigins_opt.foreach(_.foreach(o => sendDecision(o.peerConnection, GossipDecision.InvalidAnnouncement(c))))
            None
          } else {
            watcher ! WatchSpentBasic(ctx.self, tx, outputIndex, BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT(c.shortChannelId))
            log.debug("added channel channelId={}", c.shortChannelId)
            remoteOrigins_opt.foreach(_.foreach(o => sendDecision(o.peerConnection, GossipDecision.Accepted(c))))
            val capacity = tx.txOut(outputIndex).amount
            ctx.system.eventStream.publish(ChannelsDiscovered(SingleChannelDiscovered(c, capacity, None, None) :: Nil))
            db.addChannel(c, tx.txid, capacity)
            // in case we just validated our first local channel, we announce the local node
            if (!d0.nodes.contains(nodeParams.nodeId) && isRelatedTo(c, nodeParams.nodeId)) {
              log.info("first local channel validated, announcing local node")
              val nodeAnn = Announcements.makeNodeAnnouncement(nodeParams.privateKey, nodeParams.alias, nodeParams.color, nodeParams.publicAddresses, nodeParams.features)
              ctx.self ! nodeAnn
            }
            // public channels that haven't yet been announced are considered as private channels
            val channelMeta_opt = d0.privateChannels.get(c.shortChannelId).map(_.meta)
            Some(PublicChannel(c, tx.txid, capacity, None, None, channelMeta_opt))
          }
        case ValidateResult(c, Right((tx, fundingTxStatus: UtxoStatus.Spent))) =>
          if (fundingTxStatus.spendingTxConfirmed) {
            log.warning("ignoring shortChannelId={} tx={} (funding tx already spent and spending tx is confirmed)", c.shortChannelId, tx.txid)
            // the funding tx has been spent by a transaction that is now confirmed: peer shouldn't send us those
            remoteOrigins_opt.foreach(_.foreach(o => sendDecision(o.peerConnection, GossipDecision.ChannelClosed(c))))
          } else {
            log.debug("ignoring shortChannelId={} tx={} (funding tx already spent but spending tx isn't confirmed)", c.shortChannelId, tx.txid)
            remoteOrigins_opt.foreach(_.foreach(o => sendDecision(o.peerConnection, GossipDecision.ChannelClosing(c))))
          }
          // there may be a record if we have just restarted
          db.removeChannel(c.shortChannelId)
          None
      }
      // we also reprocess node and channel_update announcements related to channels that were just analyzed
      val reprocessUpdates = d0.stash.updates.filterKeys(u => u.shortChannelId == c.shortChannelId)
      val reprocessNodes = d0.stash.nodes.filterKeys(n => isRelatedTo(c, n.nodeId))
      // and we remove the reprocessed messages from the stash
      val stash1 = d0.stash.copy(updates = d0.stash.updates -- reprocessUpdates.keys, nodes = d0.stash.nodes -- reprocessNodes.keys)
      // we remove channel from awaiting map
      val awaiting1 = d0.awaiting - c

      publicChannel_opt match {
        case Some(pc) =>
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
            case (d, (u, origins)) => Validation.handleChannelUpdate(d, nodeParams.db.network, nodeParams.routerConf, Right(RemoteChannelUpdate(u, origins)), wasStashed = true)
          }
          val d3 = reprocessNodes.foldLeft(d2) {
            case (d, (n, origins)) => Validation.handleNodeAnnouncement(d, nodeParams.db.network, origins, n, wasStashed = true)
          }
          d3
        case None =>
          reprocessUpdates.foreach { case (u, origins) => origins.collect { case o: RemoteGossip => sendDecision(o.peerConnection, GossipDecision.NoRelatedChannel(u)) } }
          reprocessNodes.foreach { case (n, origins) => origins.collect { case o: RemoteGossip => sendDecision(o.peerConnection, GossipDecision.NoKnownChannel(n)) } }
          d0.copy(stash = stash1, awaiting = awaiting1)
      }
    }
  }

  def handleChannelSpent(d: Data, db: NetworkDb, event: BITCOIN_FUNDING_EXTERNAL_CHANNEL_SPENT)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    import event.shortChannelId
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

    ctx.system.eventStream.publish(ChannelLost(shortChannelId))
    lostNodes.foreach {
      nodeId =>
        log.info("pruning nodeId={} (spent)", nodeId)
        db.removeNode(nodeId)
        ctx.system.eventStream.publish(NodeLost(nodeId))
    }
    d.copy(nodes = d.nodes -- lostNodes, channels = d.channels - shortChannelId, graph = graph1)
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
        log.debug("received node announcement from {}", ctx.sender)
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
    val (u: ChannelUpdate, origins: Set[GossipOrigin]) = update match {
      case Left(lcu) => (lcu.channelUpdate, Set(LocalGossip))
      case Right(rcu) =>
        rcu.origins.collect {
          case RemoteGossip(peerConnection, _) if !wasStashed => // stashed changes have already been acknowledged
            log.debug("received channel update for shortChannelId={}", rcu.channelUpdate.shortChannelId)
            peerConnection ! TransportHandler.ReadAck(rcu.channelUpdate)
        }
        (rcu.channelUpdate, rcu.origins)
    }
    if (d.channels.contains(u.shortChannelId)) {
      // related channel is already known (note: this means no related channel_update is in the stash)
      val publicChannel = true
      val pc = d.channels(u.shortChannelId)
      val desc = getDesc(u, pc.ann)
      if (d.rebroadcast.updates.contains(u)) {
        log.debug("ignoring {} (pending rebroadcast)", u)
        sendDecision(origins, GossipDecision.Accepted(u))
        val origins1 = d.rebroadcast.updates(u) ++ origins
        // NB: we update the channels because the balances may have changed even if the channel_update is the same.
        val pc1 = pc.applyChannelUpdate(update)
        val graph1 = d.graph.addEdge(desc, u, pc1.capacity, pc1.getBalanceSameSideAs(u))
        d.copy(rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> origins1)), channels = d.channels + (u.shortChannelId -> pc1), graph = graph1)
      } else if (StaleChannels.isStale(u)) {
        log.debug("ignoring {} (stale)", u)
        sendDecision(origins, GossipDecision.Stale(u))
        d
      } else if (pc.getChannelUpdateSameSideAs(u).exists(_.timestamp >= u.timestamp)) {
        log.debug("ignoring {} (duplicate)", u)
        sendDecision(origins, GossipDecision.Duplicate(u))
        update match {
          case Left(_) =>
            // NB: we update the graph because the balances may have changed even if the channel_update is the same.
            val pc1 = pc.applyChannelUpdate(update)
            val graph1 = d.graph.addEdge(desc, u, pc1.capacity, pc1.getBalanceSameSideAs(u))
            d.copy(channels = d.channels + (u.shortChannelId -> pc1), graph = graph1)
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
        val graph1 = if (Announcements.isEnabled(u.channelFlags)) {
          d.graph.addEdge(desc, u, pc1.capacity, pc1.getBalanceSameSideAs(u))
        } else {
          d.graph.removeEdge(desc)
        }
        d.copy(channels = d.channels + (u.shortChannelId -> pc1), rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> origins)), graph = graph1)
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        sendDecision(origins, GossipDecision.Accepted(u))
        ctx.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        db.updateChannel(u)
        // we also need to update the graph
        val pc1 = pc.applyChannelUpdate(update)
        val graph1 = d.graph.addEdge(desc, u, pc1.capacity, pc1.getBalanceSameSideAs(u))
        d.copy(channels = d.channels + (u.shortChannelId -> pc1), privateChannels = d.privateChannels - u.shortChannelId, rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> origins)), graph = graph1)
      }
    } else if (d.awaiting.keys.exists(c => c.shortChannelId == u.shortChannelId)) {
      // channel is currently being validated
      if (d.stash.updates.contains(u)) {
        log.debug("ignoring {} (already stashed)", u)
        val origins1 = d.stash.updates(u) ++ origins
        d.copy(stash = d.stash.copy(updates = d.stash.updates + (u -> origins1)))
      } else {
        log.debug("stashing {}", u)
        d.copy(stash = d.stash.copy(updates = d.stash.updates + (u -> origins)))
      }
    } else if (d.privateChannels.contains(u.shortChannelId)) {
      val publicChannel = false
      val pc = d.privateChannels(u.shortChannelId)
      val desc = getDesc(u, pc)
      if (StaleChannels.isStale(u)) {
        log.debug("ignoring {} (stale)", u)
        sendDecision(origins, GossipDecision.Stale(u))
        d
      } else if (pc.getChannelUpdateSameSideAs(u).exists(_.timestamp >= u.timestamp)) {
        log.debug("ignoring {} (already know same or newer)", u)
        sendDecision(origins, GossipDecision.Duplicate(u))
        d
      } else if (!Announcements.checkSig(u, desc.a)) {
        log.warning("bad signature for announcement shortChannelId={} {}", u.shortChannelId, u)
        sendDecision(origins, GossipDecision.InvalidSignature(u))
        d
      } else if (pc.getChannelUpdateSameSideAs(u).isDefined) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        Metrics.channelUpdateRefreshed(u, pc.getChannelUpdateSameSideAs(u).get, publicChannel)
        sendDecision(origins, GossipDecision.Accepted(u))
        ctx.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        // we also need to update the graph
        val pc1 = pc.applyChannelUpdate(update)
        val graph1 = if (Announcements.isEnabled(u.channelFlags)) {
          d.graph.addEdge(desc, u, pc1.capacity, pc1.getBalanceSameSideAs(u))
        } else {
          d.graph.removeEdge(desc)
        }
        d.copy(privateChannels = d.privateChannels + (u.shortChannelId -> pc1), graph = graph1)
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        sendDecision(origins, GossipDecision.Accepted(u))
        ctx.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        // we also need to update the graph
        val pc1 = pc.applyChannelUpdate(update)
        val graph1 = d.graph.addEdge(desc, u, pc1.capacity, pc1.getBalanceSameSideAs(u))
        d.copy(privateChannels = d.privateChannels + (u.shortChannelId -> pc1), graph = graph1)
      }
    } else if (db.isPruned(u.shortChannelId) && !StaleChannels.isStale(u)) {
      // the channel was recently pruned, but if we are here, it means that the update is not stale so this is the case
      // of a zombie channel coming back from the dead. they probably sent us a channel_announcement right before this update,
      // but we ignored it because the channel was in the 'pruned' list. Now that we know that the channel is alive again,
      // let's remove the channel from the zombie list and ask the sender to re-send announcements (channel_announcement + updates)
      // about that channel. We can ignore this update since we will receive it again
      log.info(s"channel shortChannelId=${u.shortChannelId} is back from the dead! requesting announcements about this channel")
      sendDecision(origins, GossipDecision.RelatedChannelPruned(u))
      db.removeFromPruned(u.shortChannelId)
      // peerConnection_opt will contain a valid peerConnection only when we're handling an update that we received from a peer, not
      // when we're sending updates to ourselves
      origins head match {
        case RemoteGossip(peerConnection, remoteNodeId) =>
          val query = QueryShortChannelIds(u.chainHash, EncodedShortChannelIds(routerConf.encodingType, List(u.shortChannelId)), TlvStream.empty)
          d.sync.get(remoteNodeId) match {
            case Some(sync) =>
              // we already have a pending request to that node, let's add this channel to the list and we'll get it later
              // TODO: we only request channels with old style channel_query
              d.copy(sync = d.sync + (remoteNodeId -> sync.copy(pending = sync.pending :+ query, total = sync.total + 1)))
            case None =>
              // we send the query right away
              peerConnection ! query
              d.copy(sync = d.sync + (remoteNodeId -> Syncing(pending = Nil, total = 1)))
          }
        case _ =>
          // we don't know which node this update came from (maybe it was stashed and the channel got pruned in the meantime or some other corner case).
          // or we don't have a peerConnection to send our query to.
          // anyway, that's not really a big deal because we have removed the channel from the pruned db so next time it shows up we will revalidate it
          d
      }
    } else {
      log.debug("ignoring announcement {} (unknown channel)", u)
      sendDecision(origins, GossipDecision.NoRelatedChannel(u))
      d
    }
  }

  def handleLocalChannelUpdate(d: Data, db: NetworkDb, routerConf: RouterConf, localNodeId: PublicKey, watcher: ActorRef, lcu: LocalChannelUpdate)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    d.channels.get(lcu.shortChannelId) match {
      case Some(_) =>
        // channel has already been announced and router knows about it, we can process the channel_update
        handleChannelUpdate(d, db, routerConf, Left(lcu))
      case None =>
        lcu.channelAnnouncement_opt match {
          case Some(c) if d.awaiting.contains(c) =>
            // channel is currently being verified, we can process the channel_update right away (it will be stashed)
            handleChannelUpdate(d, db, routerConf, Left(lcu))
          case Some(c) =>
            // channel wasn't announced but here is the announcement, we will process it *before* the channel_update
            watcher ! ValidateRequest(c)
            val d1 = d.copy(awaiting = d.awaiting + (c -> Nil)) // no origin
            // maybe the local channel was pruned (can happen if we were disconnected for more than 2 weeks)
            db.removeFromPruned(c.shortChannelId)
            handleChannelUpdate(d1, db, routerConf, Left(lcu))
          case None if d.privateChannels.contains(lcu.shortChannelId) =>
            // channel isn't announced but we already know about it, we can process the channel_update
            handleChannelUpdate(d, db, routerConf, Left(lcu))
          case None =>
            // channel isn't announced and we never heard of it (maybe it is a private channel or maybe it is a public channel that doesn't yet have 6 confirmations)
            // let's create a corresponding private channel and process the channel_update
            log.debug("adding unannounced local channel to remote={} shortChannelId={}", lcu.remoteNodeId, lcu.shortChannelId)
            val pc = PrivateChannel(localNodeId, lcu.remoteNodeId, None, None, ChannelMeta(0 msat, 0 msat)).updateBalances(lcu.commitments)
            val d1 = d.copy(privateChannels = d.privateChannels + (lcu.shortChannelId -> pc))
            handleChannelUpdate(d1, db, routerConf, Left(lcu))
        }
    }
  }

  def handleLocalChannelDown(d: Data, localNodeId: PublicKey, lcd: LocalChannelDown)(implicit log: LoggingAdapter): Data = {
    import lcd.{channelId, remoteNodeId, shortChannelId}
    // a local channel has permanently gone down
    if (d.channels.contains(shortChannelId)) {
      // the channel was public, we will receive (or have already received) a WatchEventSpentBasic event, that will trigger a clean up of the channel
      // so let's not do anything here
      d
    } else if (d.privateChannels.contains(shortChannelId)) {
      // the channel was private or public-but-not-yet-announced, let's do the clean up
      log.debug("removing private local channel and channel_update for channelId={} shortChannelId={}", channelId, shortChannelId)
      val desc1 = ChannelDesc(shortChannelId, localNodeId, remoteNodeId)
      val desc2 = ChannelDesc(shortChannelId, remoteNodeId, localNodeId)
      // we remove the corresponding updates from the graph
      val graph1 = d.graph
        .removeEdge(desc1)
        .removeEdge(desc2)
      // and we remove the channel and channel_update from our state
      d.copy(privateChannels = d.privateChannels - shortChannelId, graph = graph1)
    } else {
      d
    }
  }

  def handleAvailableBalanceChanged(d: Data, e: AvailableBalanceChanged)(implicit log: LoggingAdapter): Data = {
    val desc = ChannelDesc(e.shortChannelId, e.commitments.localNodeId, e.commitments.remoteNodeId)
    val (publicChannels1, graph1) = d.channels.get(e.shortChannelId) match {
      case Some(pc) =>
        val pc1 = pc.updateBalances(e.commitments)
        log.debug("public channel balance updated: {}", pc1)
        val update_opt = if (e.commitments.localNodeId == pc1.ann.nodeId1) pc1.update_1_opt else pc1.update_2_opt
        val graph1 = update_opt.map(u => d.graph.addEdge(desc, u, pc1.capacity, pc1.getBalanceSameSideAs(u))).getOrElse(d.graph)
        (d.channels + (e.shortChannelId -> pc1), graph1)
      case None =>
        (d.channels, d.graph)
    }
    val (privateChannels1, graph2) = d.privateChannels.get(e.shortChannelId) match {
      case Some(pc) =>
        val pc1 = pc.updateBalances(e.commitments)
        log.debug("private channel balance updated: {}", pc1)
        val update_opt = if (e.commitments.localNodeId == pc1.nodeId1) pc1.update_1_opt else pc1.update_2_opt
        val graph2 = update_opt.map(u => graph1.addEdge(desc, u, pc1.capacity, pc1.getBalanceSameSideAs(u))).getOrElse(graph1)
        (d.privateChannels + (e.shortChannelId -> pc1), graph2)
      case None =>
        (d.privateChannels, graph1)
    }
    d.copy(channels = publicChannels1, privateChannels = privateChannels1, graph = graph2)
  }

}
