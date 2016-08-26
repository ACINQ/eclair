package fr.acinq.eclair.router

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, Props}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.blockchain.peer.CurrentBlockCount
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, Register}
import fr.acinq.eclair.{Globals, _}
import lightning.locktime.Locktime.Blocks
import lightning.route_step.Next
import lightning.{route_step, _}
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConversions._

/**
  * Created by PM on 24/05/2016.
  */

class Router(initialBlockCount: Long) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])
  context.system.eventStream.subscribe(self, classOf[NetworkEvent])

  import Router._

  import ExecutionContext.Implicits.global

  def receive: Receive = main(Map(), initialBlockCount)

  def main(channels: Map[BinaryData, ChannelDesc], currentBlockCount: Long): Receive = {
    case ChannelDiscovered(c) =>
      log.info(s"added channel ${c.id} to available routes")
      context become main(channels + (c.id -> c), currentBlockCount)
    case ChannelLost(c) =>
      log.info(s"removed channel ${c.id} from available routes")
      context become main(channels - c.id, currentBlockCount)
    case CurrentBlockCount(count) => context become main(channels, count)
    case 'network => sender ! channels.values
    case c: CreatePayment =>
      val s = sender
      findRoute(Globals.Node.publicKey, c.targetNodeId, channels).map(_ match {
        case us :: next :: others =>
          context.system.actorSelection(Register.actorPathToNodeId(next))
            .resolveOne(2 seconds)
            .map { channel =>
              // build a route
              val r = buildRoute(c.amountMsat, next +: others)

              // apply fee
              val amountMsat = r.steps(0).amount
              channel ! CMD_ADD_HTLC(amountMsat, c.h, locktime(Blocks(currentBlockCount.toInt + 100 + r.steps.size - 2)), r.copy(steps = r.steps.tail), commit = true)
              s ! channel
            }
      }) onFailure {
        case t: Throwable => s ! Failure(t)
      }
  }
}

object Router {

  def props(initialBlockCount: Long) = Props(classOf[Router], initialBlockCount)


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

  def buildRoute(finalAmountMsat: Int, nodeIds: Seq[BinaryData]): lightning.route = {

    // FIXME: use actual fee parameters that are specific to each node
    def fee(amountMsat: Int) = nodeFee(Globals.base_fee, Globals.proportional_fee, amountMsat).toInt

    var amountMsat = finalAmountMsat
    val steps = nodeIds.reverse.map(nodeId => {
      val step = route_step(amountMsat, next = Next.Bitcoin(nodeId))
      amountMsat = amountMsat + fee(amountMsat)
      step
    })
    lightning.route(steps.reverse :+ route_step(0, next = route_step.Next.End(true)))
  }
}