package fr.acinq.eclair.router

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.blockchain.ExtendedBitcoinClient
import fr.acinq.eclair.channel.{AliasActor, CMD_ADD_HTLC, Register}
import fr.acinq.eclair.{Boot, Globals, _}
import lightning.locktime.Locktime.Blocks
import lightning.route_step.Next
import lightning.{route_step, _}
import org.jgrapht.alg.DijkstraShortestPath
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent
import org.kitteh.irc.client.library.event.client.ClientConnectedEvent
import org.kitteh.irc.lib.net.engio.mbassy.listener.Handler

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConversions._

/**
  * Created by PM on 24/05/2016.
  */
class IRCRouter(bitcoinClient: ExtendedBitcoinClient) extends Actor with ActorLogging {

  import IRCRouter._

  import ExecutionContext.Implicits.global

  def receive: Receive = main(Map())

  context.system.scheduler.schedule(5 seconds, 10 seconds, self, 'tick)

   class IRCListener {
    @Handler
    def onClientConnected(event: ClientConnectedEvent) {
      log.info(s"connected to IRC: ${event.getServerInfo}")
    }

    val r = """([0-9a-f]{64}): ([0-9a-f]{66})-([0-9a-f]{66})""".r

    @Handler
    def onChannelMessage(event: ChannelMessageEvent): Unit = {
      event.getMessage match {
        case r(id, a, b) =>
          log.info(s"discovered new channel $id between $a and $b")
          self ! ChannelRegister(ChannelDesc(id, a, b))
        case msg => log.warning(s"ignored message $msg")
      }
    }
  }

  val channel = "#eclair-gossip"
  val ircClient = {
    val client = Client.builder().nick("node-" + Globals.Node.id.take(16)).serverHost("irc.freenode.net").build()
    client.getEventManager().registerEventListener(new IRCListener())
    client.addChannel(channel)
    client
  }

  def main(channels: Map[BinaryData, ChannelDesc]): Receive = {
    case ChannelDesc(id, a, b) => ircClient.sendMessage(channel, s"$id: $a-$b")
    case ChannelRegister(c) => context become main(channels + (c.id -> c))
    case ChannelUnregister(c) => context become main(channels - c.id)
    case 'network => sender ! channels.values
    case c: CreatePayment =>
      val s = sender
      (for {
        route <- findRoute(Globals.Node.publicKey, c.targetNodeId, channels)
        blockCount <- bitcoinClient.getBlockCount
      } yield route match {
        case us :: next :: others =>
        Boot.system.actorSelection(Register.actorPathToNodeId(next))
          .resolveOne(2 seconds)
          .map { channel =>
            // TODO : no fees!
            val r = lightning.route(others.map(n => route_step(c.amountMsat, next = Next.Bitcoin(n))) :+ route_step(0, next = route_step.Next.End(true)))
            // TODO : expiry is not correctly calculated
            channel ! CMD_ADD_HTLC(c.amountMsat, c.h, locktime(Blocks(blockCount.toInt + 100 + r.steps.size - 1)), r, commit = true)
            s ! channel
          }
      }) onFailure {
        case t: Throwable => s ! Failure(t)
      }
  }

}

object IRCRouter {

  def props(bitcoinClient: ExtendedBitcoinClient) = Props(classOf[IRCRouter], bitcoinClient)

  def register(node_id: BinaryData, anchor_id: BinaryData)(implicit context: ActorContext) =
    context.actorSelection(Boot.system / "router") ! ChannelDesc(anchor_id, Globals.Node.publicKey, node_id)

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