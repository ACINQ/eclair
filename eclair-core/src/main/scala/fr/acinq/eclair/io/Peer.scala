package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{ActorRef, FSM, LoggingFSM, OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Crypto, DeterministicWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler.{HandshakeCompleted, Listener}
import fr.acinq.eclair.io.Switchboard.{NewChannel, NewConnection}
import fr.acinq.eclair.router.{Rebroadcast, SendRoutingState}
import fr.acinq.eclair.wire._
import fr.acinq.eclair._

import scala.concurrent.duration._
import scala.util.Random

// @formatter:off

case object Reconnect
case object Disconnect

sealed trait OfflineChannel
case class BrandNewChannel(c: NewChannel) extends OfflineChannel
case class HotChannel(channelId: ChannelId, a: ActorRef) extends OfflineChannel

sealed trait ChannelId
case class TemporaryChannelId(id: BinaryData) extends ChannelId
case class FinalChannelId(id: BinaryData) extends ChannelId

sealed trait Data
case class DisconnectedData(offlineChannels: Set[OfflineChannel], attempts: Int = 0) extends Data
case class InitializingData(transport: ActorRef, offlineChannels: Set[OfflineChannel]) extends Data
case class ConnectedData(transport: ActorRef, remoteInit: Init, channels: Map[ChannelId, ActorRef]) extends Data

sealed trait State
case object DISCONNECTED extends State
case object INITIALIZING extends State
case object CONNECTED extends State

case class PeerRecord(id: PublicKey, address: InetSocketAddress)

// @formatter:on

/**
  * Created by PM on 26/08/2016.
  */
class Peer(nodeParams: NodeParams, remoteNodeId: PublicKey, address_opt: Option[InetSocketAddress], watcher: ActorRef, router: ActorRef, relayer: ActorRef, storedChannels: Set[HasCommitments]) extends LoggingFSM[State, Data] {

  import Peer._

  val RECONNECT_TIMER = "reconnect"

  startWith(DISCONNECTED, DisconnectedData(offlineChannels = storedChannels.map { state =>
    val channel = spawnChannel(nodeParams, context.system.deadLetters)
    channel ! INPUT_RESTORED(state)
    HotChannel(FinalChannelId(state.channelId), channel)
  }, attempts = 0))

  when(DISCONNECTED) {
    case Event(c: NewChannel, d@DisconnectedData(offlineChannels, _)) =>
      stay using d.copy(offlineChannels = offlineChannels + BrandNewChannel(c))

    case Event(Reconnect, d@DisconnectedData(_, attempts)) =>
      address_opt match {
        case Some(address) => context.parent forward NewConnection(remoteNodeId, address, None)
        case None => {} // no-op (this peer didn't initiate the connection and doesn't have the ip of the counterparty)
      }
      // exponential backoff retry with a finite max
      setTimer(RECONNECT_TIMER, Reconnect, Math.min(Math.pow(2, attempts), 60) seconds, repeat = false)
      stay using d.copy(attempts = attempts + 1)

    case Event(HandshakeCompleted(transport, _), DisconnectedData(offlineChannels, _)) =>
      log.info(s"registering as a listener to $transport")
      transport ! Listener(self)
      context watch transport
      transport ! Init(globalFeatures = nodeParams.globalFeatures, localFeatures = nodeParams.localFeatures)
      // we store the ip upon successful connection
      address_opt.foreach(address => nodeParams.peersDb.put(remoteNodeId, PeerRecord(remoteNodeId, address)))
      goto(INITIALIZING) using InitializingData(transport, offlineChannels)

    case Event(Terminated(actor), d@DisconnectedData(offlineChannels, _)) if offlineChannels.collect { case h: HotChannel if h.a == actor => h }.size >= 0 =>
      val h = offlineChannels.collect { case h: HotChannel if h.a == actor => h }
      log.info(s"channel closed: channelId=${h.map(_.channelId).mkString("/")}")
      stay using d.copy(offlineChannels = offlineChannels -- h)

    case Event(_: Rebroadcast | "connected", _) => stay // ignored
  }

  when(INITIALIZING) {
    case Event(c: NewChannel, d@InitializingData(_, offlineChannels)) =>
      stay using d.copy(offlineChannels = offlineChannels + BrandNewChannel(c))

    case Event(remoteInit: Init, InitializingData(transport, offlineChannels)) =>
      import fr.acinq.eclair.Features._
      log.info(s"$remoteNodeId has features: initialRoutingSync=${Features.initialRoutingSync(remoteInit.localFeatures)}")
      if (Features.areSupported(remoteInit.localFeatures)) {
        if (Features.initialRoutingSync(remoteInit.localFeatures)) {
          router ! SendRoutingState(transport)
        }
        // let's bring existing/requested channels online
        val channels: Map[ChannelId, ActorRef] = offlineChannels.map {
          case BrandNewChannel(c) =>
            self ! c
            None
          case HotChannel(channelId, channel) =>
            channel ! INPUT_RECONNECTED(transport)
            Some((channelId -> channel))
        }.flatten.toMap
        goto(CONNECTED) using ConnectedData(transport, remoteInit, channels)
      } else {
        log.warning(s"incompatible features, disconnecting")
        transport ! PoisonPill
        stay
      }

    case Event(Terminated(actor), InitializingData(transport, offlineChannels)) if actor == transport =>
      log.warning(s"lost connection to $remoteNodeId")
      goto(DISCONNECTED) using DisconnectedData(offlineChannels)

    case Event(Terminated(actor), d@InitializingData(_, offlineChannels)) if offlineChannels.collect { case h: HotChannel if h.a == actor => h }.size > 0 =>
      val h = offlineChannels.collect { case h: HotChannel if h.a == actor => h }
      log.info(s"channel closed: channelId=${h.map(_.channelId).mkString("/")}")
      stay using d.copy(offlineChannels = offlineChannels -- h)
  }

  when(CONNECTED, stateTimeout = nodeParams.pingInterval) {
    case Event(StateTimeout, ConnectedData(transport, _, _)) =>
      // no need to use secure random here
      val pingSize = Random.nextInt(1000)
      val pongSize = Random.nextInt(1000)
      transport ! Ping(pongSize, BinaryData("00" * pingSize))
      stay

    case Event(Ping(pongLength, _), ConnectedData(transport, _, _)) =>
      // TODO: (optional) check against the expected data size tat we requested when we sent ping messages
      if (pongLength > 0) {
        transport ! Pong(BinaryData("00" * pongLength))
      }
      stay

    case Event(Pong(data), ConnectedData(transport, _, _)) =>
      // TODO: compute latency for remote peer ?
      log.debug(s"received pong with ${data.length} bytes")
      stay

    case Event(err@Error(channelId, reason), ConnectedData(transport, _, channels)) if channelId == CHANNELID_ZERO =>
      log.error(s"connection-level error, failing all channels! reason=${new String(reason)}")
      channels.values.foreach(_ forward err)
      transport ! PoisonPill
      stay

    case Event(msg: Error, ConnectedData(_, _, channels)) =>
      // error messages are a bit special because they can contain either temporaryChannelId or channelId (see BOLT 1)
      channels.get(TemporaryChannelId(msg.channelId)).orElse(channels.get(FinalChannelId(msg.channelId))) match {
        case Some(channel) => channel forward msg
        case None => log.warning(s"couldn't resolve channel for $msg")
      }
      stay

    case Event(msg: HasTemporaryChannelId, ConnectedData(_, _, channels)) if channels.contains(TemporaryChannelId(msg.temporaryChannelId)) =>
      val channel = channels(TemporaryChannelId(msg.temporaryChannelId))
      channel forward msg
      stay

    case Event(msg: HasChannelId, ConnectedData(_, _, channels)) if channels.contains(FinalChannelId(msg.channelId)) =>
      val channel = channels(FinalChannelId(msg.channelId))
      channel forward msg
      stay

    case Event(ChannelIdAssigned(channel, temporaryChannelId, channelId), d@ConnectedData(_, _, channels)) if channels.contains(TemporaryChannelId(temporaryChannelId)) =>
      log.info(s"channel id switch: previousId=$temporaryChannelId nextId=$channelId")
      // NB: we keep the temporary channel id because the switch is not always acknowledged at this point (see https://github.com/lightningnetwork/lightning-rfc/pull/151)
      // we won't clean it up, but we won't remember the temporary id on channel termination
      stay using d.copy(channels = channels + (FinalChannelId(channelId) -> channel))

    case Event(c: NewChannel, d@ConnectedData(transport, remoteInit, channels)) =>
      log.info(s"requesting a new channel to $remoteNodeId with fundingSatoshis=${c.fundingSatoshis} and pushMsat=${c.pushMsat}")
      val (channel, localParams) = createChannel(nodeParams, transport, funder = true, c.fundingSatoshis.toLong)
      val temporaryChannelId = randomBytes(32)
      channel ! INPUT_INIT_FUNDER(temporaryChannelId, c.fundingSatoshis.amount, c.pushMsat.amount, Globals.feeratePerKw.get, localParams, transport, remoteInit, c.channelFlags.getOrElse(nodeParams.channelFlags))
      stay using d.copy(channels = channels + (TemporaryChannelId(temporaryChannelId) -> channel))

    case Event(msg: OpenChannel, d@ConnectedData(transport, remoteInit, channels)) if !channels.contains(TemporaryChannelId(msg.temporaryChannelId)) =>
      log.info(s"accepting a new channel to $remoteNodeId")
      val (channel, localParams) = createChannel(nodeParams, transport, funder = false, fundingSatoshis = msg.fundingSatoshis)
      val temporaryChannelId = msg.temporaryChannelId
      channel ! INPUT_INIT_FUNDEE(temporaryChannelId, localParams, transport, remoteInit)
      channel ! msg
      stay using d.copy(channels = channels + (TemporaryChannelId(temporaryChannelId) -> channel))

    case Event(Rebroadcast(announcements, origins), ConnectedData(transport, _, _)) =>
      // we filter out announcements that we received from this node
      announcements.filterNot(ann => origins.getOrElse(ann, context.system.deadLetters) == self).foreach(transport forward _)
      stay

    case Event(msg: RoutingMessage, _) =>
      router forward msg
      stay

    case Event(Disconnect, ConnectedData(transport, _, _)) =>
      transport ! PoisonPill
      stay

    case Event(Terminated(actor), ConnectedData(transport, _, channels)) if actor == transport =>
      log.warning(s"lost connection to $remoteNodeId")
      channels.values.foreach(_ ! INPUT_DISCONNECTED)
      val c: Set[OfflineChannel] = channels.map(c => HotChannel(c._1, c._2)).toSet
      goto(DISCONNECTED) using DisconnectedData(c)

    case Event(Terminated(actor), d@ConnectedData(transport, _, channels)) if channels.values.toSet.contains(actor) =>
      // we will have at most 2 ids: a TemporaryChannelId and a FinalChannelId
      val channelIds = channels.filter(_._2 == actor).map(_._1)
      log.info(s"channel closed: channelId=${channelIds.mkString("/")}")
      if (channels.values.toSet - actor == Set.empty) {
        log.info(s"that was the last open channel, closing the connection")
        transport ! PoisonPill
      }
      stay using d.copy(channels = channels -- channelIds)

    case Event(h: HandshakeCompleted, ConnectedData(oldTransport, _, channels)) =>
      log.info(s"got new transport while already connected, switching to new transport")
      context unwatch oldTransport
      oldTransport ! PoisonPill
      channels.values.foreach(_ ! INPUT_DISCONNECTED)
      val c: Set[OfflineChannel] = channels.map(c => HotChannel(c._1, c._2)).toSet
      self ! h
      goto(DISCONNECTED) using DisconnectedData(c)
  }

  onTransition {
    case _ -> DISCONNECTED if nodeParams.autoReconnect => setTimer(RECONNECT_TIMER, Reconnect, 1 second, repeat = false)
    case DISCONNECTED -> _ if nodeParams.autoReconnect => cancelTimer(RECONNECT_TIMER)
  }

  def createChannel(nodeParams: NodeParams, transport: ActorRef, funder: Boolean, fundingSatoshis: Long): (ActorRef, LocalParams) = {
    val localParams = makeChannelParams(nodeParams, funder, fundingSatoshis)
    val channel = spawnChannel(nodeParams, transport)
    (channel, localParams)
  }

  def spawnChannel(nodeParams: NodeParams, transport: ActorRef): ActorRef = {
    val channel = context.actorOf(Channel.props(nodeParams, remoteNodeId, watcher, router, relayer))
    context watch channel
    channel
  }

  // a failing channel won't be restarted, it should handle its states
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Stop }

  initialize()

}

object Peer {

  val CHANNELID_ZERO = BinaryData("00" * 32)

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, address_opt: Option[InetSocketAddress], watcher: ActorRef, router: ActorRef, relayer: ActorRef, storedChannels: Set[HasCommitments]) = Props(new Peer(nodeParams, remoteNodeId, address_opt, watcher, router, relayer, storedChannels))

  def generateKey(nodeParams: NodeParams, keyPath: Seq[Long]): PrivateKey = DeterministicWallet.derivePrivateKey(nodeParams.extendedPrivateKey, keyPath).privateKey

  def makeChannelParams(nodeParams: NodeParams, isFunder: Boolean, fundingSatoshis: Long): LocalParams = {
    // all secrets are generated from the main seed
    // TODO: check this
    val keyIndex = secureRandom.nextInt(1000).toLong
    LocalParams(
      nodeId = nodeParams.privateKey.publicKey,
      dustLimitSatoshis = nodeParams.dustLimitSatoshis,
      maxHtlcValueInFlightMsat = nodeParams.maxHtlcValueInFlightMsat,
      channelReserveSatoshis = (nodeParams.reserveToFundingRatio * fundingSatoshis).toLong,
      htlcMinimumMsat = nodeParams.htlcMinimumMsat,
      toSelfDelay = nodeParams.delayBlocks,
      maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs,
      fundingPrivKey = generateKey(nodeParams, keyIndex :: 0L :: Nil),
      revocationSecret = generateKey(nodeParams, keyIndex :: 1L :: Nil),
      paymentKey = generateKey(nodeParams, keyIndex :: 2L :: Nil),
      delayedPaymentKey = generateKey(nodeParams, keyIndex :: 3L :: Nil),
      defaultFinalScriptPubKey = nodeParams.defaultFinalScriptPubKey,
      shaSeed = Crypto.sha256(generateKey(nodeParams, keyIndex :: 4L :: Nil).toBin), // TODO: check that
      isFunder = isFunder,
      globalFeatures = nodeParams.globalFeatures,
      localFeatures = nodeParams.localFeatures)
  }

}
