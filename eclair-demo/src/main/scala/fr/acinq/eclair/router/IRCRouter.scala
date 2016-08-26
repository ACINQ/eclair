package fr.acinq.eclair.router

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorContext, ActorLogging, Props}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.blockchain.peer.CurrentBlockCount
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, Register}
import fr.acinq.eclair.{Globals, _}
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
class IRCRouter(initialBlockCount: Long) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[CurrentBlockCount])

  import IRCRouter._

  import ExecutionContext.Implicits.global

  def receive: Receive = main(Map(), initialBlockCount)

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

  def main(channels: Map[BinaryData, ChannelDesc], currentBlockCount: Long): Receive = {
    case c@ChannelDesc(id, a, b) =>
      self ! ChannelRegister(c)
      ircClient.sendMessage(channel, s"$id: $a-$b")
    case ChannelRegister(c) =>
      context.system.eventStream.publish(ChannelDiscovered(c.id, c.a, c.b))
      context become main(channels + (c.id -> c), currentBlockCount)
    case ChannelUnregister(c) => context become main(channels - c.id, currentBlockCount)
    case 'network => sender ! channels.values
    case 'tick => channels.values.map(c => ircClient.sendMessage(channel, s"${c.id}: ${c.a}-${c.b}"))
    case CurrentBlockCount(count) => context become main(channels, count)
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

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = ircClient.shutdown()
}

object IRCRouter {

  def props(initialBlockCount: Long) = Props(classOf[IRCRouter], initialBlockCount)

  def register(node_id: BinaryData, anchor_id: BinaryData)(implicit context: ActorContext) =
    context.actorSelection(context.system / "router") ! ChannelDesc(anchor_id, Globals.Node.publicKey, node_id)

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