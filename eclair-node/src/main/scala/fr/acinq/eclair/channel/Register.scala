package fr.acinq.eclair.channel

import akka.actor.{Props, _}
import akka.util.Timeout
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet, MilliSatoshi, Satoshi, ScriptElt}
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
class Register(watcher: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: Seq[ScriptElt]) extends Actor with ActorLogging {

  import Register._

  def receive: Receive = main(0L)

  def main(counter: Long): Receive = {
    case CreateChannel(connection, pubkey, funding_opt, pushmsat_opt) =>
      def generateKey(index: Long): PrivateKey = DeterministicWallet.derivePrivateKey(Globals.Node.extendedPrivateKey, index :: counter :: Nil).privateKey

      val localParams = LocalParams(
        dustLimitSatoshis = 542,
        maxHtlcValueInFlightMsat = Long.MaxValue,
        channelReserveSatoshis = 0,
        htlcMinimumMsat = 0,
        feeratePerKw = Globals.feeratePerKw,
        toSelfDelay = Globals.delay_blocks,
        maxAcceptedHtlcs = 100,
        fundingPrivKey = generateKey(0),
        revocationSecret = generateKey(1),
        paymentKey = generateKey(2),
        delayedPaymentKey = generateKey(3),
        defaultFinalScriptPubKey = defaultFinalScriptPubKey,
        shaSeed = Globals.Node.seed,
        isFunder = funding_opt.isDefined
      )

      def makeChannel(conn: ActorRef, publicKey: PublicKey, ctx: ActorContext): ActorRef = {
        // note that we use transport's context and not register's context
        val channel = ctx.actorOf(Channel.props(conn, watcher, router, relayer, localParams, publicKey, Some(Globals.autosign_interval)), "channel")
        funding_opt match {
          case Some(funding) => pushmsat_opt match {
            case Some(pushmsat) => channel ! INPUT_INIT_FUNDER(funding.amount, pushmsat.amount)
            case None => channel ! INPUT_INIT_FUNDER(funding.amount, 0)
          }
          case None => channel ! INPUT_INIT_FUNDEE()
        }
        channel
      }

      val transportHandler = context.actorOf(Props(
        new TransportHandler[LightningMessage](
          KeyPair(Globals.Node.publicKey.toBin, Globals.Node.privateKey.toBin),
          pubkey,
          isWriter = funding_opt.isDefined,
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

  def props(blockchain: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: Seq[ScriptElt]) = Props(classOf[Register], blockchain, router, relayer, defaultFinalScriptPubKey)

  // @formatter:off
  case class CreateChannel(connection: ActorRef, pubkey: Option[BinaryData], fundingSatoshis: Option[Satoshi], pushMsat: Option[MilliSatoshi])

  case class ListChannels()

  case class SendCommand(channelId: Long, cmd: Command)

  // @formatter:on

  /**
    * Once it reaches NORMAL state, channel creates a [[fr.acinq.eclair.channel.AliasActor]]
    * which name is counterparty_id-anchor_id
    */
  def createAlias(nodeAddress: BinaryData, channelId: Long)(implicit context: ActorContext) =
    context.actorOf(Props(new AliasActor(context.self)), name = s"$nodeAddress-${java.lang.Long.toUnsignedString(channelId)}")

  def actorPathToNodeAddress(system: ActorSystem, nodeAddress: BinaryData): ActorPath =
    system / "register" / "transport-handler-*" / "channel" / s"$nodeAddress-*"

  def actorPathToNodeAddress(nodeAddress: BinaryData)(implicit context: ActorContext): ActorPath = actorPathToNodeAddress(context.system, nodeAddress)

  def actorPathToChannelId(system: ActorSystem, channelId: Long): ActorPath =
    system / "register" / "transport-handler-*" / "channel" / s"*-${channelId}"

  def actorPathToChannelId(channelId: Long)(implicit context: ActorContext): ActorPath = actorPathToChannelId(context.system, channelId)

  def actorPathToChannels()(implicit context: ActorContext): ActorPath =
    context.system / "register" / "transport-handler-*" / "channel"

  def actorPathToTransportHandlers()(implicit context: ActorContext): ActorPath =
    context.system / "register" / "transport-handler-*"
}