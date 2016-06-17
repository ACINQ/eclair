package fr.acinq.eclair.channel

import akka.actor._
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet}
import fr.acinq.eclair.io.AuthHandler
import fr.acinq.eclair.{Boot, Globals}

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
class Register extends Actor with ActorLogging {

  import Register._

  def receive: Receive = main(0L)

  def main(counter: Long): Receive = {
    case CreateChannel(connection, amount) =>
      val commit_priv = DeterministicWallet.derivePrivateKey(Globals.Node.extendedPrivateKey, 0L :: counter :: Nil)
      val final_priv = DeterministicWallet.derivePrivateKey(Globals.Node.extendedPrivateKey, 1L :: counter :: Nil)
      val params = OurChannelParams(Globals.default_locktime, commit_priv.secretkey :+ 1.toByte, final_priv.secretkey :+ 1.toByte, Globals.default_mindepth, Globals.commit_fee, "sha-seed".getBytes(), amount)
      context.actorOf(AuthHandler.props(connection, Boot.blockchain, Boot.paymentHandler, params), name = s"auth-handler-${counter}")
      context.become(main(counter + 1))
    case ListChannels => sender ! context.children
  }
}

object Register {

  // @formatter:off
  case class CreateChannel(connection: ActorRef, anchorAmount: Option[Long])
  case class ListChannels()
  // @formatter:on

  /**
    * Once it reaches NORMAL state, channel creates a [[fr.acinq.eclair.channel.AliasActor]]
    * which name is counterparty_id-anchor_id
    */
  def create_alias(node_id: BinaryData, anchor_id: BinaryData)(implicit context: ActorContext) =
    context.actorOf(Props(new AliasActor(context.self)), name = s"$node_id-$anchor_id")

  def actorPathToNodeId(nodeId: BinaryData): ActorPath =
    Boot.system / "register" / "auth-handler-*" / "channel" / s"${nodeId}-*"

  def actorPathToChannelId(channelId: BinaryData): ActorPath =
    Boot.system / "register" / "auth-handler-*" / "channel" / s"*-${channelId}"

  def actorPathToChannels(): ActorPath =
    Boot.system / "register" / "auth-handler-*" / "channel"

  def actorPathToHandlers(): ActorPath =
    Boot.system / "register" / "auth-handler-*"
}