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

import akka.actor.ActorContext
import akka.event.LoggingAdapter
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.io.PeerConnection
import fr.acinq.eclair.router.Router.{getDesc, isRelatedTo, isStale}
import fr.acinq.eclair.wire.{ChannelUpdate, EncodedShortChannelIds, NodeAnnouncement, QueryShortChannelIds, TlvStream}

object ValidationHandlers {

  def handleNodeAnnouncement(d: Data, db: NetworkDb, origin: GossipOrigin, n: NodeAnnouncement)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    origin match {
      case RemoteGossip(peerConnection, _) =>
        peerConnection ! TransportHandler.ReadAck(n)
        log.debug("received node announcement for nodeId={}", n.nodeId)
      case LocalGossip =>
        log.debug("received node announcement from {}", ctx.sender)
    }
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
        case RemoteGossip(peerConnection, _) => peerConnection ! PeerConnection.InvalidSignature(n)
        case LocalGossip =>
      }
      d
    } else if (d.nodes.contains(n.nodeId)) {
      log.debug("updated node nodeId={}", n.nodeId)
      ctx.system.eventStream.publish(NodeUpdated(n))
      db.updateNode(n)
      d.copy(nodes = d.nodes + (n.nodeId -> n), rebroadcast = d.rebroadcast.copy(nodes = d.rebroadcast.nodes + (n -> Set(origin))))
    } else if (d.channels.values.exists(c => isRelatedTo(c.ann, n.nodeId))) {
      log.debug("added node nodeId={}", n.nodeId)
      ctx.system.eventStream.publish(NodesDiscovered(n :: Nil))
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

  }

  def handleChannelUpdate(d: Data, db: NetworkDb, routerConf: RouterConf, origin: GossipOrigin, u: ChannelUpdate)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    origin match {
      case RemoteGossip(peerConnection, _) =>
        peerConnection ! TransportHandler.ReadAck(u)
        log.debug("received channel update for shortChannelId={}", u.shortChannelId)
      case LocalGossip =>
        log.debug("received channel update from {}", ctx.sender)
    }
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
          case RemoteGossip(peerConnection, _) => peerConnection ! PeerConnection.InvalidSignature(u)
          case LocalGossip =>
        }
        d
      } else if (pc.getChannelUpdateSameSideAs(u).isDefined) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        ctx.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        db.updateChannel(u)
        // update the graph
        val graph1 = if (Announcements.isEnabled(u.channelFlags)) {
          d.graph.removeEdge(desc).addEdge(desc, u)
        } else {
          d.graph.removeEdge(desc)
        }
        d.copy(channels = d.channels + (u.shortChannelId -> pc.updateChannelUpdateSameSideAs(u)), rebroadcast = d.rebroadcast.copy(updates = d.rebroadcast.updates + (u -> Set(origin))), graph = graph1)
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        ctx.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
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
          case RemoteGossip(peerConnection, _) => peerConnection ! PeerConnection.InvalidSignature(u)
          case LocalGossip =>
        }
        d
      } else if (pc.getChannelUpdateSameSideAs(u).isDefined) {
        log.debug("updated channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        ctx.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
        // we also need to update the graph
        val graph1 = d.graph.removeEdge(desc).addEdge(desc, u)
        d.copy(privateChannels = d.privateChannels + (u.shortChannelId -> pc.updateChannelUpdateSameSideAs(u)), graph = graph1)
      } else {
        log.debug("added channel_update for shortChannelId={} public={} flags={} {}", u.shortChannelId, publicChannel, u.channelFlags, u)
        ctx.system.eventStream.publish(ChannelUpdatesReceived(u :: Nil))
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

      // peerConnection_opt will contain a valid peerConnection only when we're handling an update that we received from a peer, not
      // when we're sending updates to ourselves
      origin match {
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
              d.copy(sync = d.sync + (remoteNodeId -> Sync(pending = Nil, total = 1)))
          }
        case _ =>
          // we don't know which node this update came from (maybe it was stashed and the channel got pruned in the meantime or some other corner case).
          // or we don't have a peerConnection to send our query to.
          // anyway, that's not really a big deal because we have removed the channel from the pruned db so next time it shows up we will revalidate it
          d
      }
    } else {
      log.debug("ignoring announcement {} (unknown channel)", u)
      d
    }
  }

}
