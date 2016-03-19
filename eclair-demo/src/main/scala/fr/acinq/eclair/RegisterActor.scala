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
  // @formatter:on
}

/**
  * Created by PM on 26/01/2016.
  */

/**
  * Actor hierarchy:
  *   system
  *     ├── blockchain
  *     ├── register
  *     │       ├── handler-0
  *     │       │         └── channel
  *     │       │                 └── remote_node_id-anchor_id (alias to parent)
  *     │      ...
  *     │       └── handler-n
  *     │                 └── channel
  *     │                         └── remote_node_id-anchor_id (alias to parent)
  *     ├── server
  *     ├── client (0..m, transient)
  *     └── api
  */
class RegisterActor extends Actor with ActorLogging {

  import RegisterActor._

  implicit val timeout = Timeout(30 seconds)

  import ExecutionContext.Implicits.global

  def receive: Receive = main(0L)

  def main(counter: Long): Receive = {
    case CreateChannel(connection, amount) =>
      val commit_priv = DeterministicWallet.derivePrivateKey(Globals.Node.extendedPrivateKey, 0L :: counter :: Nil)
      val final_priv = DeterministicWallet.derivePrivateKey(Globals.Node.extendedPrivateKey, 1L :: counter :: Nil)
      val params = OurChannelParams(Globals.default_locktime, commit_priv.secretkey :+ 1.toByte, final_priv.secretkey :+ 1.toByte, Globals.default_mindepth, Globals.commit_fee, "sha-seed".getBytes(), amount)
      context.actorOf(Props(classOf[AuthHandler], connection, Boot.blockchain, params), name = s"handler-${counter}")
      context.become(main(counter + 1))
    case GetChannels =>
      val s = sender()
      Future.sequence(context.children.map(c => c ? CMD_GETINFO)).map(s ! _)
  }

  override def unhandled(message: Any): Unit = {
    log.warning(s"unhandled message $message")
    super.unhandled(message)
  }
}
