package fr.acinq.eclair

import akka.actor._
import akka.util.Timeout
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.AuthHandler
import akka.pattern.ask

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

object RegisterActor {

  // @formatter:off
  case class CreateChannel(connection: ActorRef, anchorAmount: Option[Long])
  case class GetChannels()
  case class Entry(nodeId: String, channelId: String, channel: ActorRef, state: ChannelState)
  case class UpdateState(state: ChannelState)
  case class RegisterChannel(nodeId: String, channelId: String, state: ChannelState)
  // @formatter:on
}

/**
  * Created by PM on 26/01/2016.
  */
class RegisterActor extends Actor with ActorLogging {
  import RegisterActor._
  implicit val timeout = Timeout(30 seconds)
  import ExecutionContext.Implicits.global

  override def unhandled(message: Any): Unit = {
    log.warning(s"unhandled message $message")
    super.unhandled(message)
  }

  def receive: Receive = main(Seq.empty[Entry], 0L)

  def main(entries: Seq[Entry], counter: Long): Receive = {
    case CreateChannel(connection, amount) =>
      val commit_priv = DeterministicWallet.derivePrivateKey(Globals.Node.extendedPrivateKey, 0L :: counter :: Nil)
      val final_priv = DeterministicWallet.derivePrivateKey(Globals.Node.extendedPrivateKey, 1L :: counter :: Nil)
      val params = OurChannelParams(Globals.default_locktime, commit_priv.secretkey :+ 1.toByte, final_priv.secretkey :+ 1.toByte, Globals.default_mindepth, Globals.commit_fee, "sha-seed".getBytes(), amount)
      context.actorOf(Props(classOf[AuthHandler], connection, Boot.blockchain, params), name = s"handler-${counter}")
      context.become(main(entries,counter + 1))
    case GetChannels =>
      val s = sender()
      Future.sequence(context.children.map(c => c ? CMD_GETINFO)).map(s ! _)
    case RegisterChannel(nodeId, channelId, state) =>
      log.info(s"${Globals.Node.id} has a new channel to nodeId:$nodeId channelId:$channelId")
      context.watch(sender)
      context.become(main(Entry(nodeId, channelId, sender, state) +: entries, counter))
    case UpdateState(newState) =>
      entries.zipWithIndex.find(_._1.channel == sender).map {
        case (entry, index) =>
          log.debug(s"${Globals.Node.id} -> ${entry.nodeId} updated state $newState")
          context.become(main(entries.updated(index, entry.copy(state = newState)), counter))
      }
    case msg@CMD_SEND_HTLC_UPDATE(amount, rhash, expiry, nodeIds) if !nodeIds.contains(Globals.Node.id) =>
      log.error(s"cannot send $msg because I am not on the route")
      sender ! akka.actor.Status.Failure(new RuntimeException("not on the route"))
    case msg@CMD_SEND_HTLC_UPDATE(amount, rhash, expiry, nodeIds) if nodeIds.last == Globals.Node.id =>
      log.debug(s"not forwarding $msg because I am the last node the route")
    case msg@CMD_SEND_HTLC_UPDATE(amount, rhash, expiry, nodeIds) =>
      val nextNodeId = nodeIds.dropWhile(_ != Globals.Node.id).tail.head
      entries.find(_.nodeId == nextNodeId) match {
        case None =>
          log.error(s"cannot send htlc: no channels to $nextNodeId")
          sender ! akka.actor.Status.Failure(new RuntimeException(s"no channels to $nextNodeId"))
        case Some(entry) =>
          log.debug(s"forwarding $msg to ${entry.nodeId}:${entry.channelId}")
          entry.channel forward msg
      }
    case msg@CMD_SEND_HTLC_FULFILL(r, nodeIds) if !nodeIds.contains(Globals.Node.id) =>
      log.error(s"cannot send $msg because I am not on the route")
      sender ! akka.actor.Status.Failure(new RuntimeException("not on the route"))
    case msg@CMD_SEND_HTLC_FULFILL(r, nodeIds) if nodeIds.head == Globals.Node.id =>
      log.debug(s"not forwarding $msg because I am the first node the route")
    case msg@CMD_SEND_HTLC_FULFILL(r, nodeIds) =>
      val previousNodeId = nodeIds.reverse.dropWhile(_ != Globals.Node.id).tail.head
      entries.find(_.nodeId == previousNodeId) match {
        case None =>
          log.error(s"cannot send fulfill: no channels to $previousNodeId")
          sender ! akka.actor.Status.Failure(new RuntimeException(s"no channels to $previousNodeId"))
        case Some(entry) =>
          log.debug(s"forwarding $msg to ${entry.nodeId}:${entry.channelId}")
          entry.channel forward msg
      }
    case Terminated(actor) =>
      context.unwatch(actor)
      entries.find(_.channel == actor).map(e => log.info(s"${Globals.Node.publicKey} has lost its channel nodeId:${e.nodeId} channelId:${e.channelId}"))
      context.become(main(entries.filterNot(_.channel == actor), counter))
    case msg => {
      log.warning(s"unhandled message $msg")
    }
  }
}
