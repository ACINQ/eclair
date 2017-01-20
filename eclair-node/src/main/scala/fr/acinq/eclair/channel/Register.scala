package fr.acinq.eclair.channel

import akka.actor.{Props, _}
import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet, Satoshi, ScriptElt}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.LightningMessageSerializer
import fr.acinq.eclair.wire.LightningMessage

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
class Register(blockchain: ActorRef, paymentHandler: ActorRef, defaultFinalScriptPubKey: Seq[ScriptElt]) extends Actor with ActorLogging {

  import Register._

  def receive: Receive = main(0L)

  def main(counter: Long): Receive = {
    case CreateChannel(connection, pubkey, amount_opt) =>
      def generateKey(index: Long): PrivateKey = DeterministicWallet.derivePrivateKey(Globals.Node.extendedPrivateKey, index :: counter :: Nil).privateKey

      val localParams = LocalParams(
        dustLimitSatoshis = 542,
        maxHtlcValueInFlightMsat = Long.MaxValue,
        channelReserveSatoshis = 0,
        htlcMinimumMsat = 0,
        feeratePerKw = Globals.default_feeratePerKw,
        toSelfDelay = Globals.default_delay_blocks,
        maxAcceptedHtlcs = 100,
        fundingPrivKey = generateKey(0),
        revocationSecret = generateKey(1),
        paymentKey = generateKey(2),
        delayedPaymentKey = generateKey(3),
        defaultFinalScriptPubKey = defaultFinalScriptPubKey,
        shaSeed = Globals.Node.seed,
        isFunder = amount_opt.isDefined
      )

      def makeChannel(conn: ActorRef, publicKey: BinaryData): ActorRef = {
        val channel = context.actorOf(Channel.props(conn, blockchain, paymentHandler, localParams, publicKey.toString(), Some(Globals.autosign_interval)), s"channel-$counter")
        amount_opt match {
          case Some(amount) => channel ! INPUT_INIT_FUNDER(amount.amount, 0)
          case None => channel ! INPUT_INIT_FUNDEE()
        }
        channel
      }

      val transportHandler = context.actorOf(Props(
        new TransportHandler[LightningMessage](
          KeyPair(Globals.Node.publicKey.toBin, Globals.Node.privateKey.toBin),
          pubkey,
          isWriter = amount_opt.isDefined,
          them = connection,
          listenerFactory = makeChannel,
          serializer = LightningMessageSerializer)),
        name = s"transport-handler-${counter}")

      connection ! akka.io.Tcp.Register(transportHandler)
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

  def props(blockchain: ActorRef, paymentHandler: ActorRef, defaultFinalScriptPubKey: Seq[ScriptElt]) = Props(classOf[Register], blockchain, paymentHandler, defaultFinalScriptPubKey)

  // @formatter:off
  case class CreateChannel(connection: ActorRef, pubkey: Option[BinaryData], anchorAmount: Option[Satoshi])

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