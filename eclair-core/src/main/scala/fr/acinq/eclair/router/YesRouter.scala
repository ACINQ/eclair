package fr.acinq.eclair.router

import akka.actor.{ActorRef, FSM, Props, Status}
import akka.pattern.pipe
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.wire._

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext


/**
  * Created by PM on 24/05/2016.
  */

class YesRouter(nodeParams: NodeParams, watcher: ActorRef) extends FSM[State, Data] {

  import Router._

  import ExecutionContext.Implicits.global

  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])

  setTimer("broadcast", 'tick_broadcast, nodeParams.routerBroadcastInterval, repeat = true)
  setTimer("validate", 'tick_validate, nodeParams.routerValidateInterval, repeat = true)

  startWith(NORMAL, Data(Map.empty, Map.empty, Map.empty, Nil, Nil, Nil, Map.empty, Map.empty))

  when(NORMAL) {

    case Event(ChannelStateChanged(_, _, _, _, channel.NORMAL, d: DATA_NORMAL), d1) =>
      stay using d1.copy(localChannels = d1.localChannels + (d.commitments.channelId -> d.commitments.remoteParams.nodeId))

    case Event(ChannelStateChanged(_, _, _, channel.NORMAL, _, d: DATA_NEGOTIATING), d1) =>
      stay using d1.copy(localChannels = d1.localChannels - d.commitments.channelId)

    case Event(c: ChannelStateChanged, _) => stay

    case Event(SendRoutingState(remote), Data(nodes, channels, updates, _, _, _, _, _)) =>
      log.debug(s"info sending all announcements to $remote: channels=${channels.size} nodes=${nodes.size} updates=${updates.size}")
      channels.values.foreach(remote ! _)
      nodes.values.foreach(remote ! _)
      updates.values.foreach(remote ! _)
      stay

    case Event(c: ChannelAnnouncement, d) =>
      log.debug(s"received channel announcement for shortChannelId=${c.shortChannelId} nodeId1=${c.nodeId1} nodeId2=${c.nodeId2}")
      if (!Announcements.checkSigs(c)) {
        log.error(s"bad signature for announcement $c")
        sender ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
        stay
      } else if (d.channels.containsKey(c.shortChannelId)) {
        log.debug(s"ignoring $c (duplicate)")
        stay
      } else {
        log.debug(s"added channel channelId=${c.shortChannelId}")
        context.system.eventStream.publish(ChannelDiscovered(c, Satoshi(0)))
        nodeParams.announcementsDb.put(channelKey(c.shortChannelId), c)
        stay using d.copy(channels = d.channels + (c.shortChannelId -> c), origins = d.origins + (c -> sender))
      }

    case Event(n: NodeAnnouncement, d: Data) =>
      if (!Announcements.checkSig(n)) {
        log.error(s"bad signature for announcement $n")
        sender ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
        stay
      } else if (d.nodes.containsKey(n.nodeId) && d.nodes(n.nodeId).timestamp >= n.timestamp) {
        log.debug(s"ignoring announcement $n (old timestamp or duplicate)")
        stay
      } else if (d.nodes.containsKey(n.nodeId)) {
        log.debug(s"updated node nodeId=${n.nodeId}")
        context.system.eventStream.publish(NodeUpdated(n))
        nodeParams.announcementsDb.put(nodeKey(n.nodeId), n)
        stay using d.copy(nodes = d.nodes + (n.nodeId -> n), rebroadcast = d.rebroadcast :+ n, origins = d.origins + (n -> sender))
      } else if (d.channels.values.exists(c => isRelatedTo(c, n))) {
        log.debug(s"added node nodeId=${n.nodeId}")
        context.system.eventStream.publish(NodeDiscovered(n))
        nodeParams.announcementsDb.put(nodeKey(n.nodeId), n)
        stay using d.copy(nodes = d.nodes + (n.nodeId -> n), rebroadcast = d.rebroadcast :+ n, origins = d.origins + (n -> sender))
      } else {
        log.warning(s"ignoring $n (no related channel found)")
        stay
      }

    case Event(u: ChannelUpdate, d: Data) =>
      if (d.channels.contains(u.shortChannelId)) {
        val c = d.channels(u.shortChannelId)
        val desc = getDesc(u, c)
        if (!Announcements.checkSig(u, getDesc(u, d.channels(u.shortChannelId)).a)) {
          // TODO: (dirty) this will make the origin channel close the connection
          log.error(s"bad signature for announcement $u")
          sender ! Error(Peer.CHANNELID_ZERO, "bad announcement sig!!!".getBytes())
          stay
        } else if (d.updates.contains(desc) && d.updates(desc).timestamp >= u.timestamp) {
          log.debug(s"ignoring $u (old timestamp or duplicate)")
          stay
        } else {
          log.debug(s"added/updated $u")
          context.system.eventStream.publish(ChannelUpdateReceived(u))
          nodeParams.announcementsDb.put(channelUpdateKey(u.shortChannelId, u.flags), u)
          stay using d.copy(updates = d.updates + (desc -> u), rebroadcast = d.rebroadcast :+ u, origins = d.origins + (u -> sender))
        }
      } else {
        log.warning(s"ignoring announcement $u (unknown channel)")
        stay
      }

    case Event(WatchEventSpentBasic(BITCOIN_FUNDING_OTHER_CHANNEL_SPENT(shortChannelId)), d)
      if d.channels.containsKey(shortChannelId) =>
      val lostChannel = d.channels(shortChannelId)
      log.debug(s"funding tx of channelId=$shortChannelId has been spent")
      log.debug(s"removed channel channelId=$shortChannelId")
      context.system.eventStream.publish(ChannelLost(shortChannelId))

      def isNodeLost(nodeId: PublicKey): Option[PublicKey] = {
        // has nodeId still open channels?
        if ((d.channels - shortChannelId).values.filter(c => c.nodeId1 == nodeId || c.nodeId2 == nodeId).isEmpty) {
          context.system.eventStream.publish(NodeLost(nodeId))
          log.debug(s"removed node nodeId=$nodeId")
          Some(nodeId)
        } else None
      }

      val lostNodes = isNodeLost(lostChannel.nodeId1).toSeq ++ isNodeLost(lostChannel.nodeId2).toSeq
      nodeParams.announcementsDb.delete(channelKey(shortChannelId))
      d.updates.values.filter(_.shortChannelId == shortChannelId).foreach(u => nodeParams.announcementsDb.delete(channelUpdateKey(u.shortChannelId, u.flags)))
      lostNodes.foreach(id => nodeParams.announcementsDb.delete(s"ann-node-$id"))
      stay using d.copy(nodes = d.nodes -- lostNodes, channels = d.channels - shortChannelId, updates = d.updates.filterKeys(_.id != shortChannelId))

    case Event('tick_validate, d) => stay // ignored

    case Event('tick_broadcast, d) =>
      d.rebroadcast match {
        case Nil => stay using d.copy(origins = Map.empty)
        case _ =>
          log.info(s"broadcasting ${d.rebroadcast.size} routing messages")
          context.actorSelection(context.system / "*" / "switchboard") ! Rebroadcast(d.rebroadcast, d.origins)
          stay using d.copy(rebroadcast = Nil, origins = Map.empty)
      }

    case Event('nodes, d) =>
      sender ! d.nodes.values
      stay

    case Event('channels, d) =>
      sender ! d.channels.values
      stay

    case Event('updates, d) =>
      sender ! d.updates.values
      stay

    case Event('dot, d) =>
      sender ! Status.Failure(???)
      stay

    case Event(RouteRequest(start, end, ignoreNodes, ignoreChannels), d) =>
      val localNodeId = nodeParams.privateKey.publicKey
      // TODO: HACK!!!!! the following is a workaround to make our routing work with private/not-yet-announced channels, that do not have a channelUpdate
      val fakeUpdates = d.localChannels.map { case (channelId, remoteNodeId) =>
        // note that this id is deterministic, so that filterUpdates method still works
        val fakeShortId = BigInt(channelId.take(7).toArray).toLong
        val channelDesc = ChannelDesc(fakeShortId, localNodeId, remoteNodeId)
        // note that we store the channelId in the sig, other values are not used because this will be the first channel in the route
        val channelUpdate = ChannelUpdate(signature = channelId, chainHash = nodeParams.chainHash, fakeShortId, 0, "0000", 0, 0, 0, 0)
        (channelDesc -> channelUpdate)
      }
      // we replace local channelUpdates (we have them for regular public alread-announced channels) by the ones we just generated
      val updates1 = d.updates.filterKeys(_.a != localNodeId) ++ fakeUpdates
      val updates2 = filterUpdates(updates1, ignoreNodes, ignoreChannels)
      log.info(s"finding a route $start->$end with ignoreNodes=${ignoreNodes.map(_.toBin).mkString(",")} ignoreChannels=${ignoreChannels.mkString(",")}")
      findRoute(start, end, updates2).map(r => RouteResponse(r, ignoreNodes, ignoreChannels)) pipeTo sender
      stay
  }

  onTransition {
    case _ -> NORMAL => log.info(s"current status channels=${nextStateData.channels.size} nodes=${nextStateData.nodes.size} updates=${nextStateData.updates.size}")
  }

  initialize()

}

object YesRouter {

  def props(nodeParams: NodeParams, watcher: ActorRef) = Props(new YesRouter(nodeParams, watcher))

}