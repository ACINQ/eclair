/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.io

import akka.actor.{Actor, ActorRef, ExtendedActorSystem, FSM, OneForOneStrategy, PossiblyHarmful, Props, Status, SupervisorStrategy, Terminated}
import akka.event.Logging.MDC
import akka.event.{BusLogging, DiagnosticLoggingAdapter}
import akka.util.Timeout
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, DeterministicWallet, Satoshi, SatoshiLong, Script}
import fr.acinq.eclair.Features.Wumbo
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Monitoring.Metrics
import fr.acinq.eclair.io.PeerConnection.KillReason
import fr.acinq.eclair.remote.EclairInternalsSerializer.RemoteTypes
import fr.acinq.eclair.wire._
import scodec.bits.ByteVector

import java.net.InetSocketAddress

/**
 * This actor represents a logical peer. There is one [[Peer]] per unique remote node id at all time.
 *
 * The [[Peer]] actor is mostly in charge of managing channels, stays alive when the peer is disconnected, and only dies
 * when there are no more channels.
 *
 * Everytime a new connection is established, it is sent to the [[Peer]] and replaces the previous one.
 *
 * Created by PM on 26/08/2016.
 */
class Peer(val nodeParams: NodeParams, remoteNodeId: PublicKey, watcher: ActorRef, relayer: ActorRef, wallet: EclairWallet) extends FSMDiagnosticActorLogging[Peer.State, Peer.Data] {

  import Peer._

  startWith(INSTANTIATING, Nothing)

  when(INSTANTIATING) {
    case Event(Init(storedChannels), _) =>
      val channels = storedChannels.map { state =>
        val channel = spawnChannel(nodeParams, origin_opt = None)
        channel ! INPUT_RESTORED(state)
        FinalChannelId(state.channelId) -> channel
      }.toMap

      goto(DISCONNECTED) using DisconnectedData(channels) // when we restart, we will attempt to reconnect right away, but then we'll wait
  }

  when(DISCONNECTED) {
    case Event(p: Peer.Connect, _) =>
      reconnectionTask forward p
      stay

    case Event(connectionReady: PeerConnection.ConnectionReady, d: DisconnectedData) =>
      gotoConnected(connectionReady, d.channels.map { case (k: ChannelId, v) => (k, v) })

    case Event(Terminated(actor), d: DisconnectedData) if d.channels.exists(_._2 == actor) =>
      val h = d.channels.filter(_._2 == actor).keys
      log.info(s"channel closed: channelId=${h.mkString("/")}")
      val channels1 = d.channels -- h
      if (channels1.isEmpty) {
        // we have no existing channels, we can forget about this peer
        stopPeer()
      } else {
        stay using d.copy(channels = channels1)
      }

    case Event(_: wire.LightningMessage, _) => stay // we probably just got disconnected and that's the last messages we received
  }

  when(CONNECTED) {
    dropStaleMessages {
      case Event(_: Peer.Connect, _) =>
        sender ! PeerConnection.ConnectionResult.AlreadyConnected
        stay

      case Event(Channel.OutgoingMessage(msg, peerConnection), d: ConnectedData) if peerConnection == d.peerConnection => // this is an outgoing message, but we need to make sure that this is for the current active connection
        logMessage(msg, "OUT")
        d.peerConnection forward msg
        stay

      case Event(err@wire.Error(channelId, reason), d: ConnectedData) if channelId == CHANNELID_ZERO =>
        log.error(s"connection-level error, failing all channels! reason=${new String(reason.toArray)}")
        d.channels.values.toSet[ActorRef].foreach(_ forward err) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
        d.peerConnection ! PeerConnection.Kill(KillReason.AllChannelsFail)
        stay

      case Event(err: wire.Error, d: ConnectedData) =>
        // error messages are a bit special because they can contain either temporaryChannelId or channelId (see BOLT 1)
        d.channels.get(FinalChannelId(err.channelId)).orElse(d.channels.get(TemporaryChannelId(err.channelId))) match {
          case Some(channel) => channel forward err
          case None => () // let's not create a ping-pong of error messages here
        }
        stay

      case Event(c: Peer.OpenChannel, d: ConnectedData) =>
        if (c.fundingSatoshis >= Channel.MAX_FUNDING && !d.localFeatures.hasFeature(Wumbo)) {
          sender ! Status.Failure(new RuntimeException(s"fundingSatoshis=${c.fundingSatoshis} is too big, you must enable large channels support in 'eclair.features' to use funding above ${Channel.MAX_FUNDING} (see eclair.conf)"))
          stay
        } else if (c.fundingSatoshis >= Channel.MAX_FUNDING && !d.remoteFeatures.hasFeature(Wumbo)) {
          sender ! Status.Failure(new RuntimeException(s"fundingSatoshis=${c.fundingSatoshis} is too big, the remote peer doesn't support wumbo"))
          stay
        } else if (c.fundingSatoshis > nodeParams.maxFundingSatoshis) {
          sender ! Status.Failure(new RuntimeException(s"fundingSatoshis=${c.fundingSatoshis} is too big for the current settings, increase 'eclair.max-funding-satoshis' (see eclair.conf)"))
          stay
        } else {
          val channelVersion = ChannelVersion.pickChannelVersion(d.localFeatures, d.remoteFeatures)
          val (channel, localParams) = createNewChannel(nodeParams, d.localFeatures, funder = true, c.fundingSatoshis, origin_opt = Some(sender), channelVersion)
          c.timeout_opt.map(openTimeout => context.system.scheduler.scheduleOnce(openTimeout.duration, channel, Channel.TickChannelOpenTimeout)(context.dispatcher))
          val temporaryChannelId = randomBytes32
          val channelFeeratePerKw = nodeParams.onChainFeeConf.getCommitmentFeerate(remoteNodeId, channelVersion, c.fundingSatoshis, None)
          val fundingTxFeeratePerKw = c.fundingTxFeeratePerKw_opt.getOrElse(nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget))
          log.info(s"requesting a new channel with fundingSatoshis=${c.fundingSatoshis}, pushMsat=${c.pushMsat} and fundingFeeratePerByte=${c.fundingTxFeeratePerKw_opt} temporaryChannelId=$temporaryChannelId localParams=$localParams")
          channel ! INPUT_INIT_FUNDER(temporaryChannelId, c.fundingSatoshis, c.pushMsat, channelFeeratePerKw, fundingTxFeeratePerKw, c.initialRelayFees_opt, localParams, d.peerConnection, d.remoteInit, c.channelFlags.getOrElse(nodeParams.channelFlags), channelVersion)
          stay using d.copy(channels = d.channels + (TemporaryChannelId(temporaryChannelId) -> channel))
        }

      case Event(msg: wire.OpenChannel, d: ConnectedData) =>
        d.channels.get(TemporaryChannelId(msg.temporaryChannelId)) match {
          case None =>
            val channelVersion = ChannelVersion.pickChannelVersion(d.localFeatures, d.remoteFeatures)
            val (channel, localParams) = createNewChannel(nodeParams, d.localFeatures, funder = false, fundingAmount = msg.fundingSatoshis, origin_opt = None, channelVersion)
            val temporaryChannelId = msg.temporaryChannelId
            log.info(s"accepting a new channel with temporaryChannelId=$temporaryChannelId localParams=$localParams")
            channel ! INPUT_INIT_FUNDEE(temporaryChannelId, localParams, d.peerConnection, d.remoteInit, channelVersion)
            channel ! msg
            stay using d.copy(channels = d.channels + (TemporaryChannelId(temporaryChannelId) -> channel))
          case Some(_) =>
            log.warning(s"ignoring open_channel with duplicate temporaryChannelId=${msg.temporaryChannelId}")
            stay
        }

      case Event(msg: wire.HasChannelId, d: ConnectedData) =>
        d.channels.get(FinalChannelId(msg.channelId)) match {
          case Some(channel) => channel forward msg
          case None => replyUnknownChannel(d.peerConnection, msg.channelId)
        }
        stay

      case Event(msg: wire.HasTemporaryChannelId, d: ConnectedData) =>
        d.channels.get(TemporaryChannelId(msg.temporaryChannelId)) match {
          case Some(channel) => channel forward msg
          case None => replyUnknownChannel(d.peerConnection, msg.temporaryChannelId)
        }
        stay

      case Event(ChannelIdAssigned(channel, _, temporaryChannelId, channelId), d: ConnectedData) if d.channels.contains(TemporaryChannelId(temporaryChannelId)) =>
        log.info(s"channel id switch: previousId=$temporaryChannelId nextId=$channelId")
        // we have our first channel with that peer: let's sync our routing table
        if (!d.channels.keys.exists(_.isInstanceOf[FinalChannelId])) {
          d.peerConnection ! PeerConnection.DoSync(replacePrevious = false)
        }
        // NB: we keep the temporary channel id because the switch is not always acknowledged at this point (see https://github.com/lightningnetwork/lightning-rfc/pull/151)
        // we won't clean it up, but we won't remember the temporary id on channel termination
        stay using d.copy(channels = d.channels + (FinalChannelId(channelId) -> channel))

      case Event(Disconnect(nodeId), d: ConnectedData) if nodeId == remoteNodeId =>
        log.info("disconnecting")
        sender ! "disconnecting"
        d.peerConnection ! PeerConnection.Kill(KillReason.UserRequest)
        stay

      case Event(ConnectionDown(peerConnection), d: ConnectedData) if peerConnection == d.peerConnection =>
        Logs.withMdc(diagLog)(Logs.mdc(category_opt = Some(Logs.LogCategory.CONNECTION))) {
          log.info("connection lost")
        }
        if (d.channels.isEmpty) {
          // we have no existing channels, we can forget about this peer
          stopPeer()
        } else {
          d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
          goto(DISCONNECTED) using DisconnectedData(d.channels.collect { case (k: FinalChannelId, v) => (k, v) })
        }

      case Event(Terminated(actor), d: ConnectedData) if d.channels.values.toSet.contains(actor) =>
        // we will have at most 2 ids: a TemporaryChannelId and a FinalChannelId
        val channelIds = d.channels.filter(_._2 == actor).keys
        log.info(s"channel closed: channelId=${channelIds.mkString("/")}")
        if (d.channels.values.toSet - actor == Set.empty) {
          log.info(s"that was the last open channel, closing the connection")
          context.system.eventStream.publish(LastChannelClosed(self, remoteNodeId))
          d.peerConnection ! PeerConnection.Kill(KillReason.NoRemainingChannel)
        }
        stay using d.copy(channels = d.channels -- channelIds)

      case Event(connectionReady: PeerConnection.ConnectionReady, d: ConnectedData) =>
        log.info(s"got new connection, killing current one and switching")
        d.peerConnection ! PeerConnection.Kill(KillReason.ConnectionReplaced)
        d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
        gotoConnected(connectionReady, d.channels)

      case Event(unknownMsg: UnknownMessage, d: ConnectedData) if nodeParams.pluginMessageTags.contains(unknownMsg.tag) =>
        context.system.eventStream.publish(UnknownMessageReceived(self, remoteNodeId, unknownMsg, d.connectionInfo))
        stay

      case Event(unhandledMsg: LightningMessage, _) =>
        log.warning("ignoring message {}", unhandledMsg)
        stay
    }
  }

  whenUnhandled {
    case Event(_: Peer.OpenChannel, _) =>
      sender ! Status.Failure(new RuntimeException("not connected"))
      stay

    case Event(GetPeerInfo, d) =>
      sender ! PeerInfo(remoteNodeId, stateName.toString, d match {
        case c: ConnectedData => Some(c.address)
        case _ => None
      }, d.channels.values.toSet.size) // we use toSet to dedup because a channel can have a TemporaryChannelId + a ChannelId
      stay

    case Event(_: Channel.OutgoingMessage, _) => stay // we got disconnected or reconnected and this message was for the previous connection
  }

  private val reconnectionTask = context.actorOf(ReconnectionTask.props(nodeParams, remoteNodeId), "reconnection-task")

  onTransition {
    case _ -> (DISCONNECTED | CONNECTED) => reconnectionTask ! Peer.Transition(stateData, nextStateData)
  }

  onTransition {
    case DISCONNECTED -> CONNECTED =>
      Metrics.PeersConnected.withoutTags().increment()
      context.system.eventStream.publish(PeerConnected(self, remoteNodeId, nextStateData.asInstanceOf[Peer.ConnectedData].connectionInfo))
    case CONNECTED -> CONNECTED => // connection switch
      context.system.eventStream.publish(PeerConnected(self, remoteNodeId, nextStateData.asInstanceOf[Peer.ConnectedData].connectionInfo))
    case CONNECTED -> DISCONNECTED =>
      Metrics.PeersConnected.withoutTags().decrement()
      context.system.eventStream.publish(PeerDisconnected(self, remoteNodeId))
  }

  onTermination {
    case StopEvent(_, CONNECTED, _: ConnectedData) =>
      // the transition handler won't be fired if we go directly from CONNECTED to closed
      Metrics.PeersConnected.withoutTags().decrement()
      context.system.eventStream.publish(PeerDisconnected(self, remoteNodeId))
  }

  def gotoConnected(connectionReady: PeerConnection.ConnectionReady, channels: Map[ChannelId, ActorRef]): State = {
    require(remoteNodeId == connectionReady.remoteNodeId, s"invalid nodeid: $remoteNodeId != ${connectionReady.remoteNodeId}")
    log.debug("got authenticated connection to address {}:{}", connectionReady.address.getHostString, connectionReady.address.getPort)

    if (connectionReady.outgoing) {
      // we store the node address upon successful outgoing connection, so we can reconnect later
      // any previous address is overwritten
      NodeAddress.fromParts(connectionReady.address.getHostString, connectionReady.address.getPort).map(nodeAddress => nodeParams.db.peers.addOrUpdatePeer(remoteNodeId, nodeAddress))
    }

    // let's bring existing/requested channels online
    channels.values.toSet[ActorRef].foreach(_ ! INPUT_RECONNECTED(connectionReady.peerConnection, connectionReady.localInit, connectionReady.remoteInit)) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)

    goto(CONNECTED) using ConnectedData(connectionReady.address, connectionReady.peerConnection, connectionReady.localInit, connectionReady.remoteInit, channels)
  }

  /**
   * We need to ignore [[LightningMessage]] not sent by the current [[PeerConnection]]. This may happen if we switch
   * between connections.
   */
  def dropStaleMessages(s: StateFunction): StateFunction = {
    case Event(msg: LightningMessage, d: ConnectedData) if sender != d.peerConnection =>
      log.warning("dropping message from stale connection: {}", msg)
      stay
    case e if s.isDefinedAt(e) =>
      s(e)
  }

  def createNewChannel(nodeParams: NodeParams, features: Features, funder: Boolean, fundingAmount: Satoshi, origin_opt: Option[ActorRef], channelVersion: ChannelVersion): (ActorRef, LocalParams) = {
    val (finalScript, walletStaticPaymentBasepoint) = channelVersion match {
      case v if v.paysDirectlyToWallet =>
        val walletKey = Helpers.getWalletPaymentBasepoint(wallet)
        (Script.write(Script.pay2wpkh(walletKey)), Some(walletKey))
      case _ =>
        (Helpers.getFinalScriptPubKey(wallet, nodeParams.chainHash), None)
    }
    val localParams = makeChannelParams(nodeParams, features, finalScript, walletStaticPaymentBasepoint, funder, fundingAmount)
    val channel = spawnChannel(nodeParams, origin_opt)
    (channel, localParams)
  }

  def spawnChannel(nodeParams: NodeParams, origin_opt: Option[ActorRef]): ActorRef = {
    val channel = context.actorOf(Channel.props(nodeParams, wallet, remoteNodeId, watcher, relayer, origin_opt))
    context watch channel
    channel
  }

  def replyUnknownChannel(peerConnection: ActorRef, unknownChannelId: ByteVector32): Unit = {
    val msg = wire.Error(unknownChannelId, UNKNOWN_CHANNEL_MESSAGE)
    logMessage(msg, "OUT")
    peerConnection ! msg
  }

  def stopPeer(): State = {
    log.info("removing peer from db")
    nodeParams.db.peers.removePeer(remoteNodeId)
    stop(FSM.Normal)
  }

  // a failing channel won't be restarted, it should handle its states
  // connection are stateless
  override val supervisorStrategy: OneForOneStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Stop }

  initialize()

  // we use this to log raw messages coming in and out of the peer
  private val msgLogger = new BusLogging(context.system.eventStream, "", classOf[Peer.MessageLogs], context.system.asInstanceOf[ExtendedActorSystem].logFilter) with DiagnosticLoggingAdapter

  private def logMessage(msg: LightningMessage, direction: String): Unit = {
    require(direction == "IN" || direction == "OUT")
    msgLogger.mdc(mdc(msg))
    msgLogger.info(s"$direction msg={}", msg)
    msgLogger.clearMDC()
  }

  override def aroundReceive(receive: Actor.Receive, msg: Any): Unit = {
    msg match {
      case lm: LightningMessage => logMessage(lm, "IN")
      case _ => ()
    }
    super.aroundReceive(receive, msg)
  }

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(LogCategory(currentMessage), Some(remoteNodeId), Logs.channelId(currentMessage))
  }

}

object Peer {

  // @formatter:off
  val CHANNELID_ZERO: ByteVector32 = ByteVector32.Zeroes
  val UNKNOWN_CHANNEL_MESSAGE: ByteVector = ByteVector.view("unknown channel".getBytes())
  // @formatter:on

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, watcher: ActorRef, relayer: ActorRef, wallet: EclairWallet): Props = Props(new Peer(nodeParams, remoteNodeId, watcher, relayer: ActorRef, wallet))

  // @formatter:off

  // used to identify the logger for raw messages
  case class MessageLogs()

  sealed trait ChannelId { def id: ByteVector32 }
  case class TemporaryChannelId(id: ByteVector32) extends ChannelId
  case class FinalChannelId(id: ByteVector32) extends ChannelId

  sealed trait Data {
    def channels: Map[_ <: ChannelId, ActorRef] // will be overridden by Map[FinalChannelId, ActorRef] or Map[ChannelId, ActorRef]
  }
  case object Nothing extends Data { override def channels = Map.empty }
  case class DisconnectedData(channels: Map[FinalChannelId, ActorRef]) extends Data
  case class ConnectedData(address: InetSocketAddress, peerConnection: ActorRef, localInit: wire.Init, remoteInit: wire.Init, channels: Map[ChannelId, ActorRef]) extends Data {
    val connectionInfo: ConnectionInfo = ConnectionInfo(address, peerConnection, localInit, remoteInit)
    def localFeatures: Features = localInit.features
    def remoteFeatures: Features = remoteInit.features
  }

  sealed trait State
  case object INSTANTIATING extends State
  case object DISCONNECTED extends State
  case object CONNECTED extends State

  case class Init(storedChannels: Set[HasCommitments])
  case class Connect(nodeId: PublicKey, address_opt: Option[HostAndPort]) {
    def uri: Option[NodeURI] = address_opt.map(NodeURI(nodeId, _))
  }
  object Connect {
    def apply(uri: NodeURI): Connect = new Connect(uri.nodeId, Some(uri.address))
  }

  case class Disconnect(nodeId: PublicKey) extends PossiblyHarmful
  case class OpenChannel(remoteNodeId: PublicKey, fundingSatoshis: Satoshi, pushMsat: MilliSatoshi, fundingTxFeeratePerKw_opt: Option[FeeratePerKw], initialRelayFees_opt: Option[(MilliSatoshi, Int)], channelFlags: Option[Byte], timeout_opt: Option[Timeout]) extends PossiblyHarmful {
    require(pushMsat <= fundingSatoshis, s"pushMsat must be less or equal to fundingSatoshis")
    require(fundingSatoshis >= 0.sat, s"fundingSatoshis must be positive")
    require(pushMsat >= 0.msat, s"pushMsat must be positive")
    fundingTxFeeratePerKw_opt.foreach(feeratePerKw => require(feeratePerKw >= FeeratePerKw.MinimumFeeratePerKw, s"fee rate $feeratePerKw is below minimum ${FeeratePerKw.MinimumFeeratePerKw} rate/kw"))
  }
  case object GetPeerInfo
  case class PeerInfo(nodeId: PublicKey, state: String, address: Option[InetSocketAddress], channels: Int)

  case class PeerRoutingMessage(peerConnection: ActorRef, remoteNodeId: PublicKey, message: RoutingMessage) extends RemoteTypes

  case class Transition(previousData: Peer.Data, nextData: Peer.Data)

  /**
   * Sent by the peer-connection to notify the peer that the connection is down.
   * We could use watchWith on the peer-connection but it doesn't work with akka cluster when untrusted mode is enabled
   */
  case class ConnectionDown(peerConnection: ActorRef) extends RemoteTypes

  // @formatter:on

  def makeChannelParams(nodeParams: NodeParams, features: Features, defaultFinalScriptPubkey: ByteVector, walletStaticPaymentBasepoint: Option[PublicKey], isFunder: Boolean, fundingAmount: Satoshi): LocalParams = {
    // we make sure that funder and fundee key path end differently
    val fundingKeyPath = nodeParams.channelKeyManager.newFundingKeyPath(isFunder)
    makeChannelParams(nodeParams, features, defaultFinalScriptPubkey, walletStaticPaymentBasepoint, isFunder, fundingAmount, fundingKeyPath)
  }

  def makeChannelParams(nodeParams: NodeParams, features: Features, defaultFinalScriptPubkey: ByteVector, walletStaticPaymentBasepoint: Option[PublicKey], isFunder: Boolean, fundingAmount: Satoshi, fundingKeyPath: DeterministicWallet.KeyPath): LocalParams = {
    LocalParams(
      nodeParams.nodeId,
      fundingKeyPath,
      dustLimit = nodeParams.dustLimit,
      maxHtlcValueInFlightMsat = nodeParams.maxHtlcValueInFlightMsat,
      channelReserve = (fundingAmount * nodeParams.reserveToFundingRatio).max(nodeParams.dustLimit), // BOLT #2: make sure that our reserve is above our dust limit
      htlcMinimum = nodeParams.htlcMinimum,
      toSelfDelay = nodeParams.toRemoteDelay, // we choose their delay
      maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs,
      isFunder = isFunder,
      defaultFinalScriptPubKey = defaultFinalScriptPubkey,
      walletStaticPaymentBasepoint = walletStaticPaymentBasepoint,
      features = features)
  }
}
