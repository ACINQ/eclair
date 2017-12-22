package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{ActorRef, LoggingFSM, OneForOneStrategy, PoisonPill, Props, Status, SupervisorStrategy, Terminated}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Crypto, DeterministicWallet, MilliSatoshi, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler.Listener
import fr.acinq.eclair.router.{Rebroadcast, SendRoutingState}
import fr.acinq.eclair.wire

import scala.concurrent.duration._
import scala.util.Random

/**
  * Created by PM on 26/08/2016.
  */
class Peer(nodeParams: NodeParams, remoteNodeId: PublicKey, previousKnownAddress: Option[InetSocketAddress], authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet, storedChannels: Set[HasCommitments]) extends LoggingFSM[Peer.State, Peer.Data] {

  import Peer._

  startWith(DISCONNECTED, DisconnectedData(address_opt = previousKnownAddress, channels = storedChannels.map { state =>
    val channel = spawnChannel(nodeParams, context.system.deadLetters)
    channel ! INPUT_RESTORED(state)
    FinalChannelId(state.channelId) -> channel
  }.toMap, attempts = 0))

  when(DISCONNECTED) {
    case Event(Peer.Connect(NodeURI(_, address)), _) =>
      // even if we are in a reconnection loop, we immediately process explicit connection requests
      context.actorOf(Client.props(nodeParams, authenticator, address, remoteNodeId, origin = sender()))
      stay

    case Event(Reconnect, d@DisconnectedData(address_opt, channels, attempts)) =>
      address_opt match {
        case None => stay // no-op (this peer didn't initiate the connection and doesn't have the ip of the counterparty)
        case _ if channels.size == 0 => stay // no-op (no more channels with this peer)
        case Some(address) =>
          context.actorOf(Client.props(nodeParams, authenticator, address, remoteNodeId, origin = self))
          // exponential backoff retry with a finite max
          setTimer(RECONNECT_TIMER, Reconnect, Math.min(Math.pow(2, attempts), 60) seconds, repeat = false)
          stay using d.copy(attempts = attempts + 1)
      }

    case Event(Authenticator.Authenticated(_, transport, remoteNodeId, address_opt, origin_opt), DisconnectedData(_, channels, _)) =>
      log.debug(s"got authenticated connection to $remoteNodeId")
      transport ! Listener(self)
      context watch transport
      transport ! wire.Init(globalFeatures = nodeParams.globalFeatures, localFeatures = nodeParams.localFeatures)
      // we store the ip upon successful connection, keeping only the most recent one
      address_opt.map(address => nodeParams.peersDb.addOrUpdatePeer(remoteNodeId, address))
      goto(INITIALIZING) using InitializingData(address_opt, transport, channels, origin_opt)

    case Event(Terminated(actor), d@DisconnectedData(_, channels, _)) if channels.exists(_._2 == actor) =>
      val h = channels.filter(_._2 == actor).map(_._1)
      log.info(s"channel closed: channelId=${h.mkString("/")}")
      stay using d.copy(channels = channels -- h)

    case Event(_: Rebroadcast, _) => stay // ignored
  }

  when(INITIALIZING) {
    case Event(remoteInit: wire.Init, InitializingData(address_opt, transport, channels, origin_opt)) =>
      log.info(s"$remoteNodeId has features: initialRoutingSync=${Features.initialRoutingSync(remoteInit.localFeatures)}")
      if (Features.areSupported(remoteInit.localFeatures)) {
        origin_opt.map(origin => origin ! "connected")
        if (Features.initialRoutingSync(remoteInit.localFeatures)) {
          router ! SendRoutingState(transport)
        }
        // let's bring existing/requested channels online
        channels.values.foreach(_ ! INPUT_RECONNECTED(transport))
        goto(CONNECTED) using ConnectedData(address_opt, transport, remoteInit, channels)
      } else {
        log.warning(s"incompatible features, disconnecting")
        origin_opt.map(origin => origin ! "incompatible features")
        transport ! PoisonPill
        stay
      }

    case Event(o: Peer.OpenChannel, _) =>
      // we're almost there, just wait a little
      import scala.concurrent.ExecutionContext.Implicits.global
      context.system.scheduler.scheduleOnce(100 milliseconds, self, o)
      stay

    case Event(Terminated(actor), InitializingData(address_opt, transport, channels, _)) if actor == transport =>
      log.warning(s"lost connection to $remoteNodeId")
      goto(DISCONNECTED) using DisconnectedData(address_opt, channels)

    case Event(Terminated(actor), d@InitializingData(_, _, channels, _)) if channels.exists(_._2 == actor) =>
      val h = channels.filter(_._2 == actor).map(_._1)
      log.info(s"channel closed: channelId=${h.mkString("/")}")
      stay using d.copy(channels = channels -- h)
  }

  when(CONNECTED, stateTimeout = nodeParams.pingInterval) {
    case Event(StateTimeout, ConnectedData(_, transport, _, _)) =>
      // no need to use secure random here
      val pingSize = Random.nextInt(1000)
      val pongSize = Random.nextInt(1000)
      transport ! wire.Ping(pongSize, BinaryData("00" * pingSize))
      stay

    case Event(wire.Ping(pongLength, _), ConnectedData(_, transport, _, _)) =>
      // TODO: (optional) check against the expected data size tat we requested when we sent ping messages
      if (pongLength > 0) {
        transport ! wire.Pong(BinaryData("00" * pongLength))
      }
      stay

    case Event(wire.Pong(data), ConnectedData(_, _, _, _)) =>
      // TODO: compute latency for remote peer ?
      log.debug(s"received pong with ${data.length} bytes")
      stay

    case Event(err@wire.Error(channelId, reason), ConnectedData(_, transport, _, channels)) if channelId == CHANNELID_ZERO =>
      log.error(s"connection-level error, failing all channels! reason=${new String(reason)}")
      channels.values.foreach(_ forward err)
      transport ! PoisonPill
      stay

    case Event(msg: wire.Error, ConnectedData(_, transport, _, channels)) =>
      // error messages are a bit special because they can contain either temporaryChannelId or channelId (see BOLT 1)
      channels.get(FinalChannelId(msg.channelId)).orElse(channels.get(TemporaryChannelId(msg.channelId))) match {
        case Some(channel) => channel forward msg
        case None => transport ! wire.Error(msg.channelId, UNKNOWN_CHANNEL_MESSAGE)
      }
      stay

    case Event(c: Peer.OpenChannel, d@ConnectedData(_, transport, remoteInit, channels)) =>
      log.info(s"requesting a new channel to $remoteNodeId with fundingSatoshis=${c.fundingSatoshis} and pushMsat=${c.pushMsat}")
      val (channel, localParams) = createNewChannel(nodeParams, transport, funder = true, c.fundingSatoshis.toLong)
      sender ! "channel created"
      val temporaryChannelId = randomBytes(32)
      val networkFeeratePerKw = Globals.feeratesPerKw.get.block_1
      channel ! INPUT_INIT_FUNDER(temporaryChannelId, c.fundingSatoshis.amount, c.pushMsat.amount, networkFeeratePerKw, localParams, transport, remoteInit, c.channelFlags.getOrElse(nodeParams.channelFlags))
      stay using d.copy(channels = channels + (TemporaryChannelId(temporaryChannelId) -> channel))

    case Event(msg: wire.OpenChannel, d@ConnectedData(_, transport, remoteInit, channels)) =>
      channels.get(TemporaryChannelId(msg.temporaryChannelId)) match {
        case None =>
          log.info(s"accepting a new channel to $remoteNodeId")
          val (channel, localParams) = createNewChannel(nodeParams, transport, funder = false, fundingSatoshis = msg.fundingSatoshis)
          val temporaryChannelId = msg.temporaryChannelId
          channel ! INPUT_INIT_FUNDEE(temporaryChannelId, localParams, transport, remoteInit)
          channel ! msg
          stay using d.copy(channels = channels + (TemporaryChannelId(temporaryChannelId) -> channel))
        case Some(_) =>
          log.warning(s"ignoring open_channel with duplicate temporaryChannelId=${msg.temporaryChannelId}")
          stay
      }

    case Event(msg: wire.HasChannelId, ConnectedData(_, transport, _, channels)) =>
      channels.get(FinalChannelId(msg.channelId)) match {
        case Some(channel) => channel forward msg
        case None => transport ! wire.Error(msg.channelId, UNKNOWN_CHANNEL_MESSAGE)
      }
      stay

    case Event(msg: wire.HasTemporaryChannelId, ConnectedData(_, transport, _, channels)) =>
      channels.get(TemporaryChannelId(msg.temporaryChannelId)) match {
        case Some(channel) => channel forward msg
        case None => transport ! wire.Error(msg.temporaryChannelId, UNKNOWN_CHANNEL_MESSAGE)
      }
      stay

    case Event(ChannelIdAssigned(channel, _, temporaryChannelId, channelId), d@ConnectedData(_, _, _, channels)) if channels.contains(TemporaryChannelId(temporaryChannelId)) =>
      log.info(s"channel id switch: previousId=$temporaryChannelId nextId=$channelId")
      // NB: we keep the temporary channel id because the switch is not always acknowledged at this point (see https://github.com/lightningnetwork/lightning-rfc/pull/151)
      // we won't clean it up, but we won't remember the temporary id on channel termination
      stay using d.copy(channels = channels + (FinalChannelId(channelId) -> channel))

    case Event(Rebroadcast(announcements, origins), ConnectedData(_, transport, _, _)) =>
      // we filter out announcements that we received from this node
      announcements.filterNot(ann => origins.getOrElse(ann, context.system.deadLetters) == self).foreach(transport forward _)
      stay

    case Event(msg: wire.RoutingMessage, _) =>
      router forward msg
      stay

    case Event(Disconnect, ConnectedData(_, transport, _, _)) =>
      transport ! PoisonPill
      stay

    case Event(Terminated(actor), ConnectedData(address_opt, transport, _, channels)) if actor == transport =>
      log.warning(s"lost connection to $remoteNodeId")
      channels.values.foreach(_ ! INPUT_DISCONNECTED)
      goto(DISCONNECTED) using DisconnectedData(address_opt, channels)

    case Event(Terminated(actor), d@ConnectedData(_, transport, _, channels)) if channels.values.toSet.contains(actor) =>
      // we will have at most 2 ids: a TemporaryChannelId and a FinalChannelId
      val channelIds = channels.filter(_._2 == actor).map(_._1)
      log.info(s"channel closed: channelId=${channelIds.mkString("/")}")
      if (channels.values.toSet - actor == Set.empty) {
        log.info(s"that was the last open channel, closing the connection")
        transport ! PoisonPill
      }
      stay using d.copy(channels = channels -- channelIds)

    case Event(h: Authenticator.Authenticated, ConnectedData(address_opt, oldTransport, _, channels)) =>
      log.info(s"got new transport while already connected, switching to new transport")
      context unwatch oldTransport
      oldTransport ! PoisonPill
      channels.values.foreach(_ ! INPUT_DISCONNECTED)
      self ! h
      goto(DISCONNECTED) using DisconnectedData(address_opt, channels)
  }

  whenUnhandled {
    case Event(_: Peer.Connect, d) =>
      sender ! "already connected"
      stay

    case Event(_: Peer.OpenChannel, _) =>
      sender ! Status.Failure(new RuntimeException("not connected"))
      stay

    case Event(e@Status.Failure(Client.ConnectionFailed(_)), _) => stay // ignored

    case Event(GetPeerInfo, d) =>
      sender ! PeerInfo(remoteNodeId, stateName.toString, d.address_opt, d.channels.values.toSet.size) // we use toSet to dedup because a channel can have a TemporaryChannelId + a ChannelId
      stay
  }

  onTransition {
    case _ -> DISCONNECTED if nodeParams.autoReconnect && nextStateData.address_opt.isDefined => setTimer(RECONNECT_TIMER, Reconnect, 1 second, repeat = false)
    case DISCONNECTED -> _ if nodeParams.autoReconnect && stateData.address_opt.isDefined => cancelTimer(RECONNECT_TIMER)
  }

  def createNewChannel(nodeParams: NodeParams, transport: ActorRef, funder: Boolean, fundingSatoshis: Long): (ActorRef, LocalParams) = {
    val defaultFinalScriptPubKey = Helpers.getFinalScriptPubKey(wallet)
    val localParams = makeChannelParams(nodeParams, defaultFinalScriptPubKey, funder, fundingSatoshis)
    val channel = spawnChannel(nodeParams, transport)
    (channel, localParams)
  }

  def spawnChannel(nodeParams: NodeParams, transport: ActorRef): ActorRef = {
    val channel = context.actorOf(Channel.props(nodeParams, wallet, remoteNodeId, watcher, router, relayer))
    context watch channel
    channel
  }

  // a failing channel won't be restarted, it should handle its states
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Stop }

  initialize()

}

object Peer {

  val CHANNELID_ZERO = BinaryData("00" * 32)

  val UNKNOWN_CHANNEL_MESSAGE = "unknown channel".getBytes()

  val RECONNECT_TIMER = "reconnect"

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, previousKnownAddress: Option[InetSocketAddress], authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet, storedChannels: Set[HasCommitments]) = Props(new Peer(nodeParams, remoteNodeId, previousKnownAddress, authenticator, watcher, router, relayer, wallet: EclairWallet, storedChannels))

  // @formatter:off

  case object Reconnect
  case object Disconnect

  sealed trait ChannelId { def id: BinaryData }
  case class TemporaryChannelId(id: BinaryData) extends ChannelId
  case class FinalChannelId(id: BinaryData) extends ChannelId

  sealed trait Data {
    def address_opt: Option[InetSocketAddress]
    def channels: Map[ChannelId, ActorRef]
  }
  case class DisconnectedData(address_opt: Option[InetSocketAddress], channels: Map[ChannelId, ActorRef], attempts: Int = 0) extends Data
  case class InitializingData(address_opt: Option[InetSocketAddress], transport: ActorRef, channels: Map[ChannelId, ActorRef], origin_opt: Option[ActorRef]) extends Data
  case class ConnectedData(address_opt: Option[InetSocketAddress], transport: ActorRef, remoteInit: wire.Init, channels: Map[ChannelId, ActorRef]) extends Data

  sealed trait State
  case object DISCONNECTED extends State
  case object INITIALIZING extends State
  case object CONNECTED extends State

  case class Connect(uri: NodeURI)
  case class OpenChannel(remoteNodeId: PublicKey, fundingSatoshis: Satoshi, pushMsat: MilliSatoshi, channelFlags: Option[Byte]) {
    require(fundingSatoshis.amount < Channel.MAX_FUNDING_SATOSHIS, s"fundingSatoshis must be less than ${Channel.MAX_FUNDING_SATOSHIS}")
    require(pushMsat.amount <= 1000 * fundingSatoshis.amount, s"pushMsat must be less or equal to fundingSatoshis")
  }
  case object GetPeerInfo
  case class PeerInfo(nodeId: PublicKey, state: String, address: Option[InetSocketAddress], channels: Int)

  // @formatter:on


  def generateKey(nodeParams: NodeParams, keyPath: Seq[Long]): PrivateKey = DeterministicWallet.derivePrivateKey(nodeParams.extendedPrivateKey, keyPath).privateKey

  def makeChannelParams(nodeParams: NodeParams, defaultFinalScriptPubKey: BinaryData, isFunder: Boolean, fundingSatoshis: Long): LocalParams = {
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
      htlcKey = generateKey(nodeParams, keyIndex :: 4L :: Nil),
      defaultFinalScriptPubKey = defaultFinalScriptPubKey,
      shaSeed = Crypto.sha256(generateKey(nodeParams, keyIndex :: 5L :: Nil).toBin), // TODO: check that
      isFunder = isFunder,
      globalFeatures = nodeParams.globalFeatures,
      localFeatures = nodeParams.localFeatures)
  }

}
