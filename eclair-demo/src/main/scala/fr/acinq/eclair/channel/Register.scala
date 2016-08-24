package fr.acinq.eclair.channel

import akka.actor._
import akka.util.Timeout
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet, Satoshi}
import fr.acinq.eclair.io.AuthHandler
import fr.acinq.eclair.Globals

import scala.concurrent.duration._

/**
  * Created by PM on 26/01/2016.
  */

/**
  * Actor hierarchy:
  * system
  * ├── blockchain
  * ├── register
  * │       ├── auth-handler-0
  * │       │         └── channel
  * │       │                 └── remote_node_id-anchor_id (alias to parent)
  * │      ...
  * │       └── auth-handler-n
  * │                 └── channel
  * │                         └── remote_node_id-anchor_id (alias to parent)
  * ├── server
  * ├── client (0..m, transient)
  * └── api
  */
class Register(blockchain: ActorRef, paymentHandler: ActorRef) extends Actor with ActorLogging {

  import Register._

  def receive: Receive = main(0L)

  def main(counter: Long): Receive = {
    case CreateChannel(connection, amount) =>
      val commit_priv = DeterministicWallet.derivePrivateKey(Globals.Node.extendedPrivateKey, 0L :: counter :: Nil)
      val final_priv = DeterministicWallet.derivePrivateKey(Globals.Node.extendedPrivateKey, 1L :: counter :: Nil)
      val params = OurChannelParams(Globals.default_locktime, commit_priv.secretkey :+ 1.toByte, final_priv.secretkey :+ 1.toByte, Globals.default_mindepth, Globals.commit_fee, Globals.Node.seed, amount, Some(Globals.autosign_interval))
      val channel = context.actorOf(AuthHandler.props(connection, blockchain, paymentHandler, params), name = s"auth-handler-${counter}")
      context.become(main(counter + 1))
    case ListChannels => sender ! context.children
    case SendCommand(channelId, cmd) =>
      val s = sender
      implicit val timeout = Timeout(30 seconds)
      import scala.concurrent.ExecutionContext.Implicits.global
      context.system.actorSelection(actorPathToChannelId(channelId)).resolveOne().map(actor => {
        actor ! cmd
        actor
      })
  }
}

object Register {

  def props(blockchain: ActorRef, paymentHandler: ActorRef) = Props(classOf[Register], blockchain, paymentHandler)

  // @formatter:off
  case class CreateChannel(connection: ActorRef, anchorAmount: Option[Satoshi])

  case class ListChannels()

  case class SendCommand(channelId: String, cmd: Command)

  // @formatter:on

  /**
    * Once it reaches NORMAL state, channel creates a [[fr.acinq.eclair.channel.AliasActor]]
    * which name is counterparty_id-anchor_id
    */
  def create_alias(node_id: BinaryData, anchor_id: BinaryData)(implicit context: ActorContext) =
  context.actorOf(Props(new AliasActor(context.self)), name = s"$node_id-$anchor_id")

  def actorPathToNodeId(system: ActorSystem, nodeId: BinaryData): ActorPath =
    system / "register" / "auth-handler-*" / "channel" / s"${nodeId}-*"

  def actorPathToNodeId(nodeId: BinaryData)(implicit context: ActorContext): ActorPath = actorPathToNodeId(context.system, nodeId)

  def actorPathToChannelId(system: ActorSystem, channelId: BinaryData): ActorPath =
    system / "register" / "auth-handler-*" / "channel" / s"*-${channelId}"

  def actorPathToChannelId(channelId: BinaryData)(implicit context: ActorContext): ActorPath = actorPathToChannelId(context.system, channelId)

  def actorPathToChannels()(implicit context: ActorContext): ActorPath =
    context.system / "register" / "auth-handler-*" / "channel"

  def actorPathToHandlers()(implicit context: ActorContext): ActorPath =
    context.system / "register" / "auth-handler-*"
}