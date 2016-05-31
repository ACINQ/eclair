package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.{Boot, Globals}
import fr.acinq.eclair._
import lightning._
import lightning.locktime.Locktime.{Blocks}

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Created by PM on 24/05/2016.
  */
class Router extends Actor with ActorLogging {

  import ExecutionContext.Implicits.global
  import Router._

  context.system.scheduler.schedule(5 seconds, 10 seconds, self, 'tick)

  def receive: Receive = main(Map())

  def main(channels: Map[BinaryData, channel_desc]): Receive = {
    case r@register_channel(c) => context become main(channels + (BinaryData(c.id.toByteArray) -> c))
    case u@unregister_channel(c) => context become main(channels - BinaryData(c.id.toByteArray))
    case 'tick =>
      val sel = context.actorSelection(Register.actorPathToHandlers())
      channels.values.foreach(sel ! register_channel(_))
    case 'network =>
      sender ! channels.values
    case c: CreatePayment =>
      val s = sender
      findRoute(Globals.Node.publicKey, c.targetNodeId, channels).map(route => {
        Boot.system.actorSelection(Register.actorPathToNodeId(route.head))
          .resolveOne(2 seconds)
          .map { channel =>
            // TODO : expiry is not correctly calculated
            channel ! CMD_ADD_HTLC(c.amount, c.h, locktime(Blocks(route.size - 1)), route.drop(1), commit = true)
            s ! channel
          }
      })
  }

}

object Router {

  // @formatter:off
  case class CreatePayment(amount: Int, h: sha256_hash, targetNodeId: BinaryData)
  // @formatter:on

  @tailrec
  def findRoute(myNodeId: BinaryData, targetNodeId: BinaryData, channels: Map[BinaryData, channel_desc], route: Seq[BinaryData]): Seq[BinaryData] = {
    channels.values.map(c => (c.nodeIdA: BinaryData, c.nodeIdB: BinaryData) ::(c.nodeIdB: BinaryData, c.nodeIdA: BinaryData) :: Nil).flatten.find(_._1 == targetNodeId) match {
      case Some((_, previous)) if previous == myNodeId => targetNodeId +: route
      case Some((_, previous)) => findRoute(myNodeId, previous, channels, targetNodeId +: route)
      case None => throw new RuntimeException(s"cannot find route to $targetNodeId")
    }
  }

  def findRoute(myNodeId: BinaryData, targetNodeId: BinaryData, channels: Map[BinaryData, channel_desc])(implicit ec: ExecutionContext): Future[Seq[BinaryData]] = Future {
    findRoute(myNodeId, targetNodeId, channels, Seq())
  }

}