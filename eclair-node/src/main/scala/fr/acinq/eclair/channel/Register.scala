package fr.acinq.eclair.channel

import akka.actor.{ActorContext, ActorPath, ActorSystem, Props}
import fr.acinq.bitcoin.BinaryData

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
/*class Register(watcher: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: Seq[ScriptElt]) extends Actor with ActorLogging {

  import Register._

  def receive: Receive = main(0L)

  def main(counter: Long): Receive = {

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
}*/

object Register {

  /*def props(blockchain: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: Seq[ScriptElt]) = Props(classOf[Register], blockchain, router, relayer, defaultFinalScriptPubKey)

  // @formatter:off
  case class CreateChannel(remoteNodeId: PublicKey, fundingSatoshis: Satoshi, pushMsat: MilliSatoshi)
  case class ConnectionEstablished(connection: ActorRef, createChannel_opt: Option[CreateChannel])
  case class HandshakeCompleted(transport: ActorRef, remoteNodeId: PublicKey)
  case class ListChannels()
  case class SendCommand(channelId: Long, cmd: Command)
  // @formatter:on
*/
  /**
    * Once it reaches NORMAL state, channel creates a [[fr.acinq.eclair.channel.AliasActor]]
    * which name is counterparty_id-anchor_id
    */
  def createAlias(nodeAddress: BinaryData, channelId: Long)(implicit context: ActorContext) =
    context.actorOf(Props(new AliasActor(context.self)), name = s"$nodeAddress-${java.lang.Long.toUnsignedString(channelId)}")

  /*def actorPathToNodeAddress(system: ActorSystem, nodeAddress: BinaryData): ActorPath =
    system / "register" / "transport-handler-*" / "channel" / s"$nodeAddress-*"

  def actorPathToNodeAddress(nodeAddress: BinaryData)(implicit context: ActorContext): ActorPath = actorPathToNodeAddress(context.system, nodeAddress)
*/
  def actorPathToChannelId(system: ActorSystem, channelId: Long): ActorPath =
    system / "switchboard" / "peer-*" / "*" / s"*-${channelId}"

  def actorPathToChannelId(channelId: Long)(implicit context: ActorContext): ActorPath = actorPathToChannelId(context.system, channelId)

  /*def actorPathToChannels()(implicit context: ActorContext): ActorPath =
    context.system / "register" / "transport-handler-*" / "channel"*/

  def actorPathToPeers()(implicit context: ActorContext): ActorPath =
    context.system / "switchboard" / "peer-*"


}