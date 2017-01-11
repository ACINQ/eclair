package fr.acinq.eclair.channel

import akka.actor._
import akka.util.Timeout
import fr.acinq.bitcoin.{BinaryData, Crypto, DeterministicWallet, Satoshi, Script}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.io.AuthHandler
import fr.acinq.eclair.transactions.Scripts

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
    case CreateChannel(connection, amount_opt) =>
      def generateKey(index: Long): BinaryData = DeterministicWallet.derivePrivateKey(Globals.Node.extendedPrivateKey, index :: counter :: Nil).privateKey
      val localParams = LocalParams(
        dustLimitSatoshis = 542,
        maxHtlcValueInFlightMsat = Long.MaxValue,
        channelReserveSatoshis = 0,
        htlcMinimumMsat = 0,
        feeratePerKw = 10000,
        toSelfDelay = 144,
        maxAcceptedHtlcs = 100,
        fundingPrivKey = generateKey(0),
        revocationSecret = generateKey(1),
        paymentKey = generateKey(2),
        delayedPaymentKey = generateKey(3),
        finalPrivKey = generateKey(4),
        shaSeed = Globals.Node.seed,
        isFunder = amount_opt.isDefined
      )
      val init = amount_opt.map(amount => Left(INPUT_INIT_FUNDER(amount.amount, 0))).getOrElse(Right(INPUT_INIT_FUNDEE()))
      context.actorOf(AuthHandler.props(connection, blockchain, paymentHandler, localParams, init), name = s"auth-handler-${counter}")
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