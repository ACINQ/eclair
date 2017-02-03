package fr.acinq.eclair.router

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.pipe
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire._
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

// @formatter:off

case class ChannelDesc(id: Long, a: BinaryData, b: BinaryData)
case class Hop(nodeId: BinaryData, nextNodeId: BinaryData, lastUpdate: ChannelUpdate)
case class RouteRequest(source: BinaryData, target: BinaryData)
case class RouteResponse(hops: Seq[Hop]) { require(hops.size > 0, "route cannot be empty") }

// @formatter:on

/**
  * Created by PM on 24/05/2016.
  */

class Router(watcher: ActorRef) extends Actor with ActorLogging {

  import Router._

  import ExecutionContext.Implicits.global

  context.system.eventStream.subscribe(self, classOf[ChannelChangedState])
  context.system.scheduler.schedule(10 seconds, 60 seconds, self, 'tick_broadcast)

  def receive: Receive = main(nodes = Map(), channels = Map(), updates = Map(), rebroadcast = Nil)

  def main(
            nodes: Map[BinaryData, NodeAnnouncement],
            channels: Map[Long, ChannelAnnouncement],
            updates: Map[ChannelDesc, ChannelUpdate],
            rebroadcast: Seq[RoutingMessage]): Receive = {

    case ChannelChangedState(_, transport, _, WAIT_FOR_INIT_INTERNAL, _, _) =>
      // we send all known announcements to the new peer as soon as the connection is opened
      channels.values.foreach(transport ! _)
      nodes.values.foreach(transport ! _)
      updates.values.foreach(transport ! _)

    case s: ChannelChangedState =>
    // other channel changed state messages are ignored

    case c: ChannelAnnouncement if channels.containsKey(c.channelId) =>
      log.debug(s"ignoring $c (duplicate)")

    case c: ChannelAnnouncement =>
      // TODO: check channel output = P2WSH(nodeid1, nodeid2)
      // TODO: check sigs
      // TODO: blacklist if already received same channel id and different node ids
      // TODO: check feature bit set
      // TODO: forget channel once funding tx spent (add watch)
      //watcher ! WatchSpent(self, txId: BinaryData, outputIndex: Int, minDepth: Int, event: BitcoinEvent)
      log.info(s"added channel channelId=${c.channelId} (nodes=${nodes.size} channels=${channels.size + 1})")
      context.system.eventStream.publish(ChannelDiscovered(c))
      context become main(nodes, channels + (c.channelId -> c), updates, rebroadcast :+ c)

    //case n: NodeAnnouncement if !checkSig(n) =>
    // TODO: fail connection (should probably be done in the auth handler or channel)

    case n: NodeAnnouncement if !channels.values.exists(c => c.nodeId1 == n.nodeId || c.nodeId2 == n.nodeId) =>
      log.debug(s"ignoring $n (no related channel found)")

    case n: NodeAnnouncement if nodes.containsKey(n.nodeId) && nodes(n.nodeId).timestamp >= n.timestamp =>
      log.debug(s"ignoring announcement $n (old timestamp or duplicate)")

    case n: NodeAnnouncement =>
      log.info(s"added/replaced node nodeId=${n.nodeId} (nodes=${nodes.size + 1} channels=${channels.size})")
      context.system.eventStream.publish(NodeDiscovered(n))
      context become main(nodes + (n.nodeId -> n), channels, updates, rebroadcast :+ n)

    case u: ChannelUpdate if !channels.contains(u.channelId) =>
      log.debug(s"ignoring $u (no related channel found)")

    //case u: ChannelUpdate if !checkSig(u, getDesc(u, channels(u.channelId)).a) =>
    // TODO: fail connection (should probably be done in the auth handler or channel)

    case u: ChannelUpdate =>
      val channel = channels(u.channelId)
      val desc = getDesc(u, channel)
      if (updates.contains(desc) && updates(desc).timestamp >= u.timestamp) {
        log.debug(s"ignoring $u (old timestamp or duplicate)")
      } else {
        context become main(nodes, channels, updates + (desc -> u), rebroadcast :+ u)
      }

    case 'tick_broadcast if rebroadcast.size == 0 =>
    // no-op

    case 'tick_broadcast =>
      log.info(s"broadcasting ${rebroadcast.size} routing messages")
      rebroadcast.foreach(context.actorSelection(Register.actorPathToTransportHandlers) ! _)
      context become main(nodes, channels, updates, Nil)

    case 'nodes => sender ! nodes.values

    case 'channels => sender ! channels.values

    case 'updates => sender ! updates.values

    case RouteRequest(start, end) => findRoute(start, end, updates).map(RouteResponse(_)) pipeTo sender

    case other => log.warning(s"unhandled message $other")
  }
}

object Router {

  def props(watcher: ActorRef) = Props(classOf[Router], watcher)

  def getDesc(u: ChannelUpdate, channel: ChannelAnnouncement): ChannelDesc = {
    require(u.flags.data.size == 2, s"invalid flags length ${u.flags.data.size} != 2")
    // the least significant bit tells us if it is node1 or node2
    if (u.flags.data(1) % 2 == 0) ChannelDesc(u.channelId, channel.nodeId1, channel.nodeId2) else ChannelDesc(u.channelId, channel.nodeId2, channel.nodeId1)
  }

  def findRouteDijkstra(localNodeId: BinaryData, targetNodeId: BinaryData, channels: Iterable[ChannelDesc]): Seq[ChannelDesc] = {
    require(localNodeId != targetNodeId, "cannot route to self")
    case class DescEdge(desc: ChannelDesc) extends DefaultEdge
    val g = new DefaultDirectedGraph[BinaryData, DescEdge](classOf[DescEdge])
    channels.foreach(d => {
      g.addVertex(d.a)
      g.addVertex(d.b)
      g.addEdge(d.a, d.b, new DescEdge(d))
    })
    Option(DijkstraShortestPath.findPathBetween(g, localNodeId, targetNodeId)) match {
      case Some(path) => path.getEdgeList.map(_.desc)
      case None => throw new RuntimeException("route not found")
    }
  }

  def findRoute(localNodeId: BinaryData, targetNodeId: BinaryData, updates: Map[ChannelDesc, ChannelUpdate])(implicit ec: ExecutionContext): Future[Seq[Hop]] = Future {
    findRouteDijkstra(localNodeId, targetNodeId, updates.keys)
      .map(desc => Hop(desc.a, desc.b, updates(desc)))
  }
}
