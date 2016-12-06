package fr.acinq.eclair.router

import akka.actor.{Actor, ActorLogging}
import akka.pattern.pipe
import fr.acinq.bitcoin.BinaryData
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 24/05/2016.
  */

class Router extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[NetworkEvent])

  import Router._

  import ExecutionContext.Implicits.global

  def receive: Receive = main(Map())

  def main(channels: Map[BinaryData, ChannelDesc]): Receive = {
    case ChannelDiscovered(c) =>
      log.info(s"added channel ${c.id} to available routes")
      context become main(channels + (c.id -> c))
    case ChannelLost(c) =>
      log.info(s"removed channel ${c.id} from available routes")
      context become main(channels - c.id)
    case 'network => sender ! channels.values
    case RouteRequest(start, end) => findRoute(start, end, channels) map(RouteResponse(_)) pipeTo sender
  }
}

object Router {

  def findRouteDijkstra(myNodeId: BinaryData, targetNodeId: BinaryData, channels: Map[BinaryData, ChannelDesc]): Seq[BinaryData] = {
    class NamedEdge(val id: BinaryData) extends DefaultEdge
    val g = new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge])
    channels.values.foreach(x => {
      g.addVertex(x.a)
      g.addVertex(x.b)
      g.addEdge(x.a, x.b, new NamedEdge(x.id))
    })
    Option(new DijkstraShortestPath(g, myNodeId, targetNodeId).getPath) match {
      case Some(path) => {
        val vertices = path.getEdgeList.foldLeft(List(path.getStartVertex)) {
          case (rest :+ v, edge) if g.getEdgeSource(edge) == v => rest :+ v :+ g.getEdgeTarget(edge)
          case (rest :+ v, edge) if g.getEdgeTarget(edge) == v => rest :+ v :+ g.getEdgeSource(edge)
        }
        vertices
      }
      case None => throw new RuntimeException("route not found")
    }
  }

  def findRoute(myNodeId: BinaryData, targetNodeId: BinaryData, channels: Map[BinaryData, ChannelDesc])(implicit ec: ExecutionContext): Future[Seq[BinaryData]] = Future {
    findRouteDijkstra(myNodeId, targetNodeId, channels)
  }
}

case class ChannelDesc(id: BinaryData, a: BinaryData, b: BinaryData)

case class RouteRequest(source: BinaryData, target: BinaryData)

case class RouteResponse(route: Seq[BinaryData])