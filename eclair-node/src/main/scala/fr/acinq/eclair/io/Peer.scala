package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{ActorRef, LoggingFSM, PoisonPill, Props, Terminated}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{DeterministicWallet, ScriptElt}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler.{HandshakeCompleted, Listener}
import fr.acinq.eclair.io.Switchboard.{NewChannel, NewConnection}
import fr.acinq.eclair.router.SendRoutingState
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Features, Globals}

import scala.compat.Platform

// @formatter:off

case object Reconnect
//case class ChannelIdSwitch(previousId: Long, nextId: Long)

sealed trait OfflineChannel
case class BrandNewChannel(c: NewChannel) extends OfflineChannel
//case class ColdChannel(f: File) extends OfflineChannel
case class HotChannel(channelId: Long, a: ActorRef) extends OfflineChannel

sealed trait Data
case class DisconnectedData(offlineChannels: Seq[OfflineChannel]) extends Data
case class InitializingData(transport: ActorRef, offlineChannels: Seq[OfflineChannel]) extends Data
case class ConnectedData(transport: ActorRef, remoteInit: Init, channels: Map[Long, ActorRef]) extends Data

sealed trait State
case object DISCONNECTED extends State
case object INITIALIZING extends State
case object CONNECTED extends State

// @formatter:on

/**
  * Created by PM on 26/08/2016.
  */
class Peer(remoteNodeId: PublicKey, address_opt: Option[InetSocketAddress], watcher: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: Seq[ScriptElt]) extends LoggingFSM[State, Data] {

  import Peer._

  startWith(DISCONNECTED, DisconnectedData(Nil))

  when(DISCONNECTED) {
    case Event(c: NewChannel, d@DisconnectedData(offlineChannels)) =>
      stay using d.copy(offlineChannels = offlineChannels :+ BrandNewChannel(c))

    case Event(Reconnect, _) if address_opt.isDefined =>
      context.parent ! NewConnection(remoteNodeId, address_opt.get, None)
      stay

    case Event(HandshakeCompleted(transport, _), DisconnectedData(channels)) =>
      log.info(s"registering as a listener to $transport")
      transport ! Listener(self)
      context watch transport
      transport ! Init(globalFeatures = Globals.global_features, localFeatures = Globals.local_features)
      goto(INITIALIZING) using InitializingData(transport, channels)
  }

  when(INITIALIZING) {
    case Event(c: NewChannel, d@InitializingData(_, offlineChannels)) =>
      stay using d.copy(offlineChannels = offlineChannels :+ BrandNewChannel(c))

    case Event(remoteInit: Init, InitializingData(transport, offlineChannels)) =>
      import fr.acinq.eclair.Features._
      log.info(s"$remoteNodeId has features: channelPublic=${isChannelPublic(remoteInit.localFeatures)} initialRoutingSync=${requiresInitialRoutingSync(remoteInit.localFeatures)}")
      if (Features.requiresInitialRoutingSync(remoteInit.localFeatures)) {
        router ! SendRoutingState(transport)
      }
      // let's bring existing/requested channels online
      val channels = offlineChannels.map {
        case BrandNewChannel(c) =>
          self ! c
          None
        case HotChannel(channelId, channel) =>
          channel ! INPUT_RECONNECTED(transport)
          Some((channelId -> channel))
      }.flatten.toMap
      goto(CONNECTED) using ConnectedData(transport, remoteInit, channels)

    case Event(Terminated(actor), InitializingData(transport, channels)) if actor == transport =>
      log.warning(s"lost connection to $remoteNodeId")
      goto(DISCONNECTED) using DisconnectedData(channels)
  }

  when(CONNECTED) {
    case Event(c: NewChannel, d@ConnectedData(transport, remoteInit, channels)) =>
      log.info(s"requesting a new channel to $remoteNodeId with fundingSatoshis=${c.fundingSatoshis} and pushMsat=${c.pushMsat}")
      val temporaryChannelId = Platform.currentTime
      val (channel, localParams) = createChannel(transport, temporaryChannelId, funder = true)
      channel ! INPUT_INIT_FUNDER(remoteNodeId, temporaryChannelId, c.fundingSatoshis.amount, c.pushMsat.amount, localParams, remoteInit)
      stay using d.copy(channels = channels + (temporaryChannelId -> channel))

    case Event(msg@FundingLocked(previousId, nextId, _), d@ConnectedData(_, _, channels)) if channels.contains(previousId) =>
      log.info(s"channel id switch: previousId=$previousId nextId=$nextId")
      val channel = channels(previousId)
      channel forward msg
      //TODO: what if nextIds are different
      stay using d.copy(channels = channels - previousId + (nextId -> channel))

    case Event(msg: HasTemporaryChannelId, ConnectedData(_, _, channels)) if channels.contains(msg.temporaryChannelId) =>
      val channel = channels(msg.temporaryChannelId)
      channel forward msg
      stay

    case Event(msg: HasChannelId, ConnectedData(_, _, channels)) if channels.contains(msg.channelId) =>
      val channel = channels(msg.channelId)
      channel forward msg
      stay

    case Event(msg: OpenChannel, d@ConnectedData(transport, remoteInit, channels)) =>
      log.info(s"accepting a new channel to $remoteNodeId")
      val temporaryChannelId = msg.temporaryChannelId
      val (channel, localParams) = createChannel(transport, temporaryChannelId, funder = false)
      channel ! INPUT_INIT_FUNDEE(remoteNodeId, temporaryChannelId, localParams, remoteInit)
      channel ! msg
      stay using d.copy(channels = channels + (temporaryChannelId -> channel))

    case Event(msg: RoutingMessage, ConnectedData(transport, _, _)) if sender == router=>
      transport forward msg
      stay

    case Event(msg: RoutingMessage, _) =>
      router forward msg
      stay

    case Event(Terminated(actor), ConnectedData(transport, _, channels)) if actor == transport =>
      log.warning(s"lost connection to $remoteNodeId")
      channels.values.foreach(_ ! INPUT_DISCONNECTED)
      goto(DISCONNECTED) using DisconnectedData(channels.toSeq.map(c => HotChannel(c._1, c._2)))

    case Event(Terminated(actor), d@ConnectedData(transport, _, channels)) if channels.values.toSet.contains(actor) =>
      val channelId = channels.find(_._2 == actor).get._1
      log.info(s"channel closed: channelId=$channelId")
      if (channels.size == 1) {
        log.info(s"that was the last channel open, closing the connection")
        transport ! PoisonPill
      }
      stay using d.copy(channels = channels - channelId)
  }

  def createChannel(transport: ActorRef, temporaryChannelId: Long, funder: Boolean): (ActorRef, LocalParams) = {
    val localParams = makeChannelParams(temporaryChannelId, defaultFinalScriptPubKey, funder)
    val channel = context.actorOf(Channel.props(transport, watcher, router, relayer, Some(Globals.autosign_interval)), s"channel-$temporaryChannelId")
    context watch channel
    (channel, localParams)
  }

}

object Peer {

  def props(remoteNodeId: PublicKey, address_opt: Option[InetSocketAddress], watcher: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: Seq[ScriptElt]) = Props(classOf[Peer], remoteNodeId, address_opt, watcher, router, relayer, defaultFinalScriptPubKey)

  def generateKey(keyPath: Seq[Long]): PrivateKey = DeterministicWallet.derivePrivateKey(Globals.Node.extendedPrivateKey, keyPath).privateKey

  def makeChannelParams(keyIndex: Long, defaultFinalScriptPubKey: Seq[ScriptElt], isFunder: Boolean): LocalParams =
    LocalParams(
      dustLimitSatoshis = 542,
      maxHtlcValueInFlightMsat = Long.MaxValue,
      channelReserveSatoshis = 0,
      htlcMinimumMsat = 0,
      feeratePerKw = Globals.feeratePerKw,
      toSelfDelay = Globals.delay_blocks,
      maxAcceptedHtlcs = 100,
      fundingPrivKey = generateKey(keyIndex :: 0L :: Nil),
      revocationSecret = generateKey(keyIndex :: 1L :: Nil),
      paymentKey = generateKey(keyIndex :: 2L :: Nil),
      delayedPaymentKey = generateKey(keyIndex :: 3L :: Nil),
      defaultFinalScriptPubKey = defaultFinalScriptPubKey,
      shaSeed = Globals.Node.seed,
      isFunder = isFunder,
      globalFeatures = Globals.global_features,
      localFeatures = Globals.local_features
    )

}
