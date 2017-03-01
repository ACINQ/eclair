package fr.acinq.eclair.router

import java.io.{BufferedWriter, StringWriter}

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.pipe
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{GetTx, GetTxResponse, WatchEventSpent, WatchSpent}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler.Serializer
import fr.acinq.eclair.db.{JavaSerializer, SimpleDb, SimpleTypedDb}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.ext._
import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge, SimpleGraph}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

// @formatter:off

case class ChannelDesc(id: Long, a: BinaryData, b: BinaryData)
case class Hop(nodeId: BinaryData, nextNodeId: BinaryData, lastUpdate: ChannelUpdate)
case class RouteRequest(source: BinaryData, target: BinaryData)
case class RouteResponse(hops: Seq[Hop]) { require(hops.size > 0, "route cannot be empty") }
case class SendRoutingState(to: ActorRef)

// @formatter:on

/**
  * Created by PM on 24/05/2016.
  */

class Router(nodeParams: NodeParams, watcher: ActorRef) extends Actor with ActorLogging {

  import Router._

  import ExecutionContext.Implicits.global

  context.system.scheduler.schedule(10 seconds, 60 seconds, self, 'tick_broadcast)

  def receive: Receive = main(Map(), Map(), Map(), Nil, Set(), Nil)

  def mainWithLog(nodes: Map[BinaryData, NodeAnnouncement],
                  channels: Map[Long, ChannelAnnouncement],
                  updates: Map[ChannelDesc, ChannelUpdate],
                  rebroadcast: Seq[RoutingMessage],
                  awaiting: Set[ChannelAnnouncement],
                  stash: Seq[RoutingMessage]) = {
    log.info(s"current status channels=${channels.size} nodes=${nodes.size} updates=${updates.size}")
    main(nodes, channels, updates, rebroadcast, awaiting, stash)
  }

  def main(nodes: Map[BinaryData, NodeAnnouncement],
           channels: Map[Long, ChannelAnnouncement],
           updates: Map[ChannelDesc, ChannelUpdate],
           rebroadcast: Seq[RoutingMessage],
           awaiting: Set[ChannelAnnouncement],
           stash: Seq[RoutingMessage]): Receive = {

    case SendRoutingState(remote) =>
      log.info(s"info sending all announcements to $remote: channels=${channels.size} nodes=${nodes.size} updates=${updates.size}")
      channels.values.foreach(remote ! _)
      updates.values.foreach(remote ! _)
      nodes.values.foreach(remote ! _)

    case c: ChannelAnnouncement if !Announcements.checkSigs(c) =>
      // TODO: (dirty) this will make the origin channel close the connection
      log.error(s"bad signature for announcement $c")
      sender ! Error(0, "bad announcement sig!!!".getBytes())

    case c: ChannelAnnouncement if channels.containsKey(c.channelId) =>
      log.debug(s"ignoring $c (duplicate)")

    case c: ChannelAnnouncement =>
      val (blockHeight, txIndex, outputIndex) = fromShortId(c.channelId)
      log.info(s"retrieving raw tx with blockHeight=$blockHeight and txIndex=$txIndex")
      watcher ! GetTx(blockHeight, txIndex, outputIndex, c)
      context become main(nodes, channels, updates, rebroadcast, awaiting + c, stash)

    case GetTxResponse(tx, isSpendable, c: ChannelAnnouncement) if !isSpendable =>
      log.debug(s"ignoring $c (funding tx spent)")

    case GetTxResponse(tx, _, c: ChannelAnnouncement) =>
      // TODO: check sigs
      // TODO: blacklist if already received same channel id and different node ids
      val (_, _, outputIndex) = fromShortId(c.channelId)
      // let's check that the output is indeed a P2WSH multisig 2-of-2 of nodeid1 and nodeid2
      require(tx.txOut.size >= outputIndex + 1, s"tx $tx does not have outputIndex=$outputIndex")
      val output = tx.txOut(outputIndex)
      val fundingOutputScript = write(pay2wsh(Scripts.multiSig2of2(PublicKey(c.bitcoinKey1), PublicKey(c.bitcoinKey2))))
      require(fundingOutputScript == output.publicKeyScript, s"funding script mismatch: actual=${output.publicKeyScript} expected=${fundingOutputScript}")
      watcher ! WatchSpent(self, tx.txid, outputIndex, BITCOIN_FUNDING_OTHER_CHANNEL_SPENT(c.channelId))
      // TODO: check feature bit set
      log.info(s"added channel channelId=${c.channelId}")
      context.system.eventStream.publish(ChannelDiscovered(c, output.amount))
      val stash1 = if (awaiting == Set(c)) {
        stash.foreach(self ! _)
        Nil
      } else stash
      nodeParams.announcementsDb.put(s"ann-channel-${c.channelId}", c)
      context become mainWithLog(nodes, channels + (c.channelId -> c), updates, rebroadcast :+ c, awaiting - c, stash1)

    case n: NodeAnnouncement if !Announcements.checkSig(n) =>
      // TODO: (dirty) this will make the origin channel close the connection
      log.error(s"bad signature for announcement $n")
      sender ! Error(0, "bad announcement sig!!!".getBytes())

    case WatchEventSpent(BITCOIN_FUNDING_OTHER_CHANNEL_SPENT(channelId), tx) if channels.containsKey(channelId) =>
      val lostChannel = channels(channelId)
      log.info(s"funding tx of channelId=$channelId has been spent by txid=${tx.txid}")
      log.info(s"removed channel channelId=$channelId")
      context.system.eventStream.publish(ChannelLost(channelId))

      def isNodeLost(nodeId: BinaryData): Option[BinaryData] = {
        // has nodeId still open channels?
        if ((channels - channelId).values.filter(c => c.nodeId1 == nodeId || c.nodeId2 == nodeId).isEmpty) {
          context.system.eventStream.publish(NodeLost(nodeId))
          log.info(s"removed node nodeId=$nodeId")
          Some(nodeId)
        } else None
      }

      val lostNodes = isNodeLost(lostChannel.nodeId1).toSeq ++ isNodeLost(lostChannel.nodeId2).toSeq
      nodeParams.announcementsDb.delete(s"ann-channel-$channelId")
      lostNodes.foreach(id => nodeParams.announcementsDb.delete(s"ann-node-$id"))
      context become mainWithLog(nodes -- lostNodes, channels - channelId, updates.filterKeys(_.id != channelId), rebroadcast, awaiting, stash)

    case n: NodeAnnouncement if awaiting.size > 0 =>
      context become main(nodes, channels, updates, rebroadcast, awaiting, stash :+ n)

    case n: NodeAnnouncement if !channels.values.exists(c => c.nodeId1 == n.nodeId || c.nodeId2 == n.nodeId) =>
      log.debug(s"ignoring $n (no related channel found)")

    case n: NodeAnnouncement if nodes.containsKey(n.nodeId) && nodes(n.nodeId).timestamp >= n.timestamp =>
      log.debug(s"ignoring announcement $n (old timestamp or duplicate)")

    case n: NodeAnnouncement if nodes.containsKey(n.nodeId) =>
      log.info(s"updated node nodeId=${n.nodeId}")
      context.system.eventStream.publish(NodeUpdated(n))
      nodeParams.announcementsDb.put(s"ann-node-${n.nodeId}", n)
      context become mainWithLog(nodes + (n.nodeId -> n), channels, updates, rebroadcast :+ n, awaiting, stash)

    case n: NodeAnnouncement =>
      log.info(s"added node nodeId=${n.nodeId}")
      context.system.eventStream.publish(NodeDiscovered(n))
      nodeParams.announcementsDb.put(s"ann-node-${n.nodeId}", n)
      context become mainWithLog(nodes + (n.nodeId -> n), channels, updates, rebroadcast :+ n, awaiting, stash)

    case u: ChannelUpdate if awaiting.size > 0 =>
      context become main(nodes, channels, updates, rebroadcast, awaiting, stash :+ u)

    case u: ChannelUpdate if !channels.contains(u.channelId) =>
      log.debug(s"ignoring $u (no related channel found)")

    case u: ChannelUpdate if !Announcements.checkSig(u, getDesc(u, channels(u.channelId)).a) =>
      // TODO: (dirty) this will make the origin channel close the connection
      log.error(s"bad signature for announcement $u")
      sender ! Error(0, "bad announcement sig!!!".getBytes())

    case u: ChannelUpdate =>
      val channel = channels(u.channelId)
      val desc = getDesc(u, channel)
      if (updates.contains(desc) && updates(desc).timestamp >= u.timestamp) {
        log.debug(s"ignoring $u (old timestamp or duplicate)")
      } else {
        nodeParams.announcementsDb.put(s"ann-update-${u.channelId}-${u.flags}", u)
        context become mainWithLog(nodes, channels, updates + (desc -> u), rebroadcast :+ u, awaiting, stash)
      }

    case 'tick_broadcast if rebroadcast.size == 0 =>
    // no-op

    case 'tick_broadcast =>
      log.info(s"broadcasting ${rebroadcast.size} routing messages")
      rebroadcast.foreach(context.actorSelection(Register.actorPathToPeers) ! _)
      context become main(nodes, channels, updates, Nil, awaiting, stash)

    case 'nodes => sender ! nodes.values

    case 'channels => sender ! channels.values

    case 'updates => sender ! updates.values

    case 'dot => graph2dot(nodes, channels) pipeTo sender

    case RouteRequest(start, end) => findRoute(start, end, updates).map(RouteResponse(_)) pipeTo sender

    case other => log.warning(s"unhandled message $other")
  }

}

object Router {

  def props(nodeParams: NodeParams, watcher: ActorRef) = Props(new Router(nodeParams, watcher))

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

  def graph2dot(nodes: Map[BinaryData, NodeAnnouncement], channels: Map[Long, ChannelAnnouncement])(implicit ec: ExecutionContext): Future[String] = Future {
    case class DescEdge(channelId: Long) extends DefaultEdge
    val g = new SimpleGraph[BinaryData, DescEdge](classOf[DescEdge])
    channels.foreach(d => {
      g.addVertex(d._2.nodeId1)
      g.addVertex(d._2.nodeId2)
      g.addEdge(d._2.nodeId1, d._2.nodeId2, new DescEdge(d._1))
    })
    val vertexIDProvider = new ComponentNameProvider[BinaryData]() {
      override def getName(nodeId: BinaryData): String = "\"" + nodeId.toString() + "\""
    }
    val edgeLabelProvider = new ComponentNameProvider[DescEdge]() {
      override def getName(e: DescEdge): String = e.channelId.toString
    }
    val vertexAttributeProvider = new ComponentAttributeProvider[BinaryData]() {

      override def getComponentAttributes(nodeId: BinaryData): java.util.Map[String, String] =

        nodes.get(nodeId) match {
          case Some(ann) => Map("label" -> ann.alias, "color" -> f"#${ann.rgbColor._1}%02x${ann.rgbColor._2}%02x${ann.rgbColor._3}%02x")
          case None => Map.empty[String, String]
        }
    }
    val exporter = new DOTExporter[BinaryData, DescEdge](vertexIDProvider, null, edgeLabelProvider, vertexAttributeProvider, null)
    val writer = new StringWriter()
    try {
      exporter.exportGraph(g, writer)
      writer.toString
    } finally {
      writer.close()
    }

  }

  case class RouterState(nodes: Iterable[NodeAnnouncement],
                         channels: Iterable[ChannelAnnouncement],
                         updates: Iterable[ChannelUpdate])

}
