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

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{ClassicActorContextOps, ClassicActorRefOps}
import akka.actor.{Actor, ActorContext, ActorRef, ExtendedActorSystem, FSM, OneForOneStrategy, PossiblyHarmful, Props, Status, SupervisorStrategy, Terminated, typed}
import akka.event.Logging.MDC
import akka.event.{BusLogging, DiagnosticLoggingAdapter}
import akka.util.Timeout
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong}
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.NotificationsLogger.NotifyNodeOperator
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{OnChainChannelFunder, OnchainPubkeyCache}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.io.MessageRelay.Status
import fr.acinq.eclair.io.Monitoring.Metrics
import fr.acinq.eclair.io.OpenChannelInterceptor.{OpenChannelInitiator, OpenChannelNonInitiator}
import fr.acinq.eclair.io.PeerConnection.KillReason
import fr.acinq.eclair.io.Switchboard.RelayMessage
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.remote.EclairInternalsSerializer.RemoteTypes
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol.{Error, HasChannelId, HasTemporaryChannelId, LightningMessage, NodeAddress, OnionMessage, RoutingMessage, UnknownMessage, Warning}

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
class Peer(val nodeParams: NodeParams, remoteNodeId: PublicKey, wallet: OnchainPubkeyCache, channelFactory: Peer.ChannelFactory, switchboard: ActorRef, pendingChannelsRateLimiter: typed.ActorRef[PendingChannelsRateLimiter.Command]) extends FSMDiagnosticActorLogging[Peer.State, Peer.Data] {

  import Peer._

  startWith(INSTANTIATING, Nothing)

  when(INSTANTIATING) {
    case Event(Init(storedChannels), _) =>
      val channels = storedChannels.map { state =>
        val channel = spawnChannel(origin_opt = None)
        channel ! INPUT_RESTORED(state)
        FinalChannelId(state.channelId) -> channel
      }.toMap
      goto(DISCONNECTED) using DisconnectedData(channels) // when we restart, we will attempt to reconnect right away, but then we'll wait
  }

  when(DISCONNECTED) {
    case Event(p: Peer.Connect, _) =>
      reconnectionTask forward p
      stay()

    case Event(connectionReady: PeerConnection.ConnectionReady, d: DisconnectedData) =>
      gotoConnected(connectionReady, d.channels.map { case (k: ChannelId, v) => (k, v) })

    case Event(Terminated(actor), d: DisconnectedData) if d.channels.values.toSet.contains(actor) =>
      // we have at most 2 ids: a TemporaryChannelId and a FinalChannelId
      val channelIds = d.channels.filter(_._2 == actor).keys
      log.info(s"channel closed: channelId=${channelIds.mkString("/")}")
      val channels1 = d.channels -- channelIds
      if (channels1.isEmpty) {
        log.info("that was the last open channel")
        context.system.eventStream.publish(LastChannelClosed(self, remoteNodeId))
        // we have no existing channels, we can forget about this peer
        stopPeer()
      } else {
        stay() using d.copy(channels = channels1)
      }

    // This event is usually handled while we're connected, but if our peer disconnects right when we're emitting this,
    // we still want to record the channelId mapping.
    case Event(ChannelIdAssigned(channel, _, temporaryChannelId, channelId), d: DisconnectedData) =>
      log.info(s"channel id switch: previousId=$temporaryChannelId nextId=$channelId")
      stay() using d.copy(channels = d.channels + (FinalChannelId(channelId) -> channel))

    case Event(e: SpawnChannelInitiator, _) =>
      e.origin ! Status.Failure(new RuntimeException("channel creation failed: disconnected"))
      stay()

    case Event(_: SpawnChannelNonInitiator, _) => stay() // we got disconnected before creating the channel actor

    case Event(_: LightningMessage, _) => stay() // we probably just got disconnected and that's the last messages we received
  }

  when(CONNECTED) {
    dropStaleMessages {
      case Event(c: Peer.Connect, d: ConnectedData) =>
        c.replyTo ! PeerConnection.ConnectionResult.AlreadyConnected(d.peerConnection, self)
        stay()

      case Event(Peer.OutgoingMessage(msg, peerConnection), d: ConnectedData) if peerConnection == d.peerConnection => // this is an outgoing message, but we need to make sure that this is for the current active connection
        logMessage(msg, "OUT")
        d.peerConnection forward msg
        stay()

      case Event(warning: Warning, _: ConnectedData) =>
        log.warning("peer sent warning: {}", warning.toAscii)
        // NB: we don't forward warnings to the channel actors, they shouldn't take any automatic action.
        // It's up to the node operator to decide what to do to address the warning.
        stay()

      case Event(err@Error(channelId, reason, _), d: ConnectedData) if channelId == CHANNELID_ZERO =>
        log.error(s"connection-level error, failing all channels! reason=${new String(reason.toArray)}")
        context.system.eventStream.publish(NotifyNodeOperator(NotificationsLogger.Info, s"$remoteNodeId sent us a connection-level error, closing all channels (reason=${new String(reason.toArray)})"))
        d.channels.values.toSet[ActorRef].foreach(_ forward err) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
        d.peerConnection ! PeerConnection.Kill(KillReason.AllChannelsFail)
        stay()

      case Event(err: Error, d: ConnectedData) =>
        // error messages are a bit special because they can contain either temporaryChannelId or channelId (see BOLT 1)
        d.channels.get(FinalChannelId(err.channelId)).orElse(d.channels.get(TemporaryChannelId(err.channelId))) match {
          case Some(channel) => channel forward err
          case None => () // let's not create a ping-pong of error messages here
        }
        stay()

      case Event(c: Peer.OpenChannel, d: ConnectedData) =>
        openChannelInterceptor ! OpenChannelInitiator(sender().toTyped, remoteNodeId, c, d.localFeatures, d.remoteFeatures)
        stay()

      case Event(SpawnChannelInitiator(c, channelConfig, channelType, localParams, origin), d: ConnectedData) =>
        val channel = spawnChannel(Some(origin))
        c.timeout_opt.map(openTimeout => context.system.scheduler.scheduleOnce(openTimeout.duration, channel, Channel.TickChannelOpenTimeout)(context.dispatcher))
        val dualFunded = Features.canUseFeature(d.localFeatures, d.remoteFeatures, Features.DualFunding)
        val requireConfirmedInputs = c.requireConfirmedInputsOverride_opt.getOrElse(nodeParams.channelConf.requireConfirmedInputsForDualFunding)
        val temporaryChannelId = if (dualFunded) {
          Helpers.dualFundedTemporaryChannelId(nodeParams, localParams, channelConfig)
        } else {
          randomBytes32()
        }
        val fundingTxFeerate = c.fundingTxFeerate_opt.getOrElse(nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget))
        val commitTxFeerate = nodeParams.onChainFeeConf.getCommitmentFeerate(remoteNodeId, channelType, c.fundingAmount, None)
        log.info(s"requesting a new channel with type=$channelType fundingAmount=${c.fundingAmount} dualFunded=$dualFunded pushAmount=${c.pushAmount_opt} fundingFeerate=$fundingTxFeerate temporaryChannelId=$temporaryChannelId localParams=$localParams")
        channel ! INPUT_INIT_CHANNEL_INITIATOR(temporaryChannelId, c.fundingAmount, dualFunded, commitTxFeerate, fundingTxFeerate, c.pushAmount_opt, requireConfirmedInputs, localParams, d.peerConnection, d.remoteInit, c.channelFlags_opt.getOrElse(nodeParams.channelConf.channelFlags), channelConfig, channelType, c.channelOrigin)
        stay() using d.copy(channels = d.channels + (TemporaryChannelId(temporaryChannelId) -> channel))

      case Event(open: protocol.OpenChannel, d: ConnectedData) =>
        d.channels.get(TemporaryChannelId(open.temporaryChannelId)) match {
          case None =>
            openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), d.localFeatures, d.remoteFeatures, d.peerConnection.toTyped)
            stay()
          case Some(_) =>
            log.warning("ignoring open_channel with duplicate temporaryChannelId={}", open.temporaryChannelId)
            stay()
        }

      case Event(open: protocol.OpenDualFundedChannel, d: ConnectedData) =>
        d.channels.get(TemporaryChannelId(open.temporaryChannelId)) match {
          case None if Features.canUseFeature(d.localFeatures, d.remoteFeatures, Features.DualFunding) =>
            openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Right(open), d.localFeatures, d.remoteFeatures, d.peerConnection.toTyped)
            stay()
          case None =>
            log.info("rejecting open_channel2: dual funding is not supported")
            self ! Peer.OutgoingMessage(Error(open.temporaryChannelId, "dual funding is not supported"), d.peerConnection)
            stay()
          case Some(_) =>
            log.warning("ignoring open_channel2 with duplicate temporaryChannelId={}", open.temporaryChannelId)
            stay()
        }

      case Event(SpawnChannelNonInitiator(open, channelConfig, channelType, localParams, peerConnection), d: ConnectedData) =>
        val temporaryChannelId = open.fold(_.temporaryChannelId, _.temporaryChannelId)
        if (peerConnection == d.peerConnection) {
          val channel = spawnChannel(None)
          log.info(s"accepting a new channel with type=$channelType temporaryChannelId=$temporaryChannelId localParams=$localParams")
          open match {
            case Left(open) =>
              channel ! INPUT_INIT_CHANNEL_NON_INITIATOR(open.temporaryChannelId, None, dualFunded = false, None, localParams, d.peerConnection, d.remoteInit, channelConfig, channelType)
              channel ! open
            case Right(open) =>
              // NB: we don't add a contribution to the funding amount.
              channel ! INPUT_INIT_CHANNEL_NON_INITIATOR(open.temporaryChannelId, None, dualFunded = true, None, localParams, d.peerConnection, d.remoteInit, channelConfig, channelType)
              channel ! open
          }
          stay() using d.copy(channels = d.channels + (TemporaryChannelId(temporaryChannelId) -> channel))
        } else {
          log.warning("ignoring open_channel request that reconnected during channel intercept, temporaryChannelId={}", temporaryChannelId)
          context.system.eventStream.publish(ChannelAborted(ActorRef.noSender, remoteNodeId, temporaryChannelId))
          stay()
        }

      case Event(msg: HasChannelId, d: ConnectedData) =>
        d.channels.get(FinalChannelId(msg.channelId)) match {
          case Some(channel) => channel forward msg
          case None => replyUnknownChannel(d.peerConnection, msg.channelId)
        }
        stay()

      case Event(msg: HasTemporaryChannelId, d: ConnectedData) =>
        d.channels.get(TemporaryChannelId(msg.temporaryChannelId)) match {
          case Some(channel) => channel forward msg
          case None => replyUnknownChannel(d.peerConnection, msg.temporaryChannelId)
        }
        stay()

      case Event(ChannelIdAssigned(channel, _, temporaryChannelId, channelId), d: ConnectedData) if d.channels.contains(TemporaryChannelId(temporaryChannelId)) =>
        log.info(s"channel id switch: previousId=$temporaryChannelId nextId=$channelId")
        // we have our first channel with that peer: let's sync our routing table
        if (!d.channels.keys.exists(_.isInstanceOf[FinalChannelId])) {
          d.peerConnection ! PeerConnection.DoSync(replacePrevious = false)
        }
        // NB: we keep the temporary channel id because the switch is not always acknowledged at this point (see https://github.com/lightningnetwork/lightning-rfc/pull/151)
        // we won't clean it up, but we won't remember the temporary id on channel termination
        stay() using d.copy(channels = d.channels + (FinalChannelId(channelId) -> channel))

      case Event(Disconnect(nodeId), d: ConnectedData) if nodeId == remoteNodeId =>
        log.debug("disconnecting")
        sender() ! "disconnecting"
        d.peerConnection ! PeerConnection.Kill(KillReason.UserRequest)
        stay()

      case Event(ConnectionDown(peerConnection), d: ConnectedData) if peerConnection == d.peerConnection =>
        Logs.withMdc(diagLog)(Logs.mdc(category_opt = Some(Logs.LogCategory.CONNECTION))) {
          log.debug("connection lost")
        }
        if (d.channels.isEmpty) {
          // we have no existing channels, we can forget about this peer
          stopPeer()
        } else {
          d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
          goto(DISCONNECTED) using DisconnectedData(d.channels.collect { case (k: FinalChannelId, v) => (k, v) })
        }

      case Event(Terminated(actor), d: ConnectedData) if d.channels.values.toSet.contains(actor) =>
        // we have at most 2 ids: a TemporaryChannelId and a FinalChannelId
        val channelIds = d.channels.filter(_._2 == actor).keys
        log.info(s"channel closed: channelId=${channelIds.mkString("/")}")
        val channels1 = d.channels -- channelIds
        if (channels1.isEmpty) {
          log.info("that was the last open channel, closing the connection")
          context.system.eventStream.publish(LastChannelClosed(self, remoteNodeId))
          d.peerConnection ! PeerConnection.Kill(KillReason.NoRemainingChannel)
        }
        stay() using d.copy(channels = channels1)

      case Event(connectionReady: PeerConnection.ConnectionReady, d: ConnectedData) =>
        log.debug(s"got new connection, killing current one and switching")
        d.peerConnection ! PeerConnection.Kill(KillReason.ConnectionReplaced)
        d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
        gotoConnected(connectionReady, d.channels)

      case Event(msg: OnionMessage, _: ConnectedData) =>
        if (nodeParams.features.hasFeature(Features.OnionMessages)) {
          OnionMessages.process(nodeParams.privateKey, msg) match {
            case OnionMessages.DropMessage(reason) =>
              log.debug(s"dropping message from ${remoteNodeId.value.toHex}: ${reason.toString}")
            case OnionMessages.SendMessage(nextNodeId, message) =>
              switchboard ! RelayMessage(randomBytes32(), Some(remoteNodeId), nextNodeId, message, nodeParams.onionMessageConfig.relayPolicy, None)
            case received: OnionMessages.ReceiveMessage =>
              log.info(s"received message from ${remoteNodeId.value.toHex}: $received")
              context.system.eventStream.publish(received)
          }
        }
        stay()

      case Event(RelayOnionMessage(messageId, msg, replyTo_opt), d: ConnectedData) =>
        d.peerConnection ! msg
        replyTo_opt.foreach(_ ! MessageRelay.Sent(messageId))
        stay()

      case Event(unknownMsg: UnknownMessage, d: ConnectedData) if nodeParams.pluginMessageTags.contains(unknownMsg.tag) =>
        context.system.eventStream.publish(UnknownMessageReceived(self, remoteNodeId, unknownMsg, d.connectionInfo))
        stay()

      case Event(RelayUnknownMessage(unknownMsg: UnknownMessage), d: ConnectedData) if nodeParams.pluginMessageTags.contains(unknownMsg.tag) =>
        logMessage(unknownMsg, "OUT")
        d.peerConnection forward unknownMsg
        stay()

      case Event(unhandledMsg: LightningMessage, _) =>
        log.warning("ignoring message {}", unhandledMsg)
        stay()
    }
  }

  whenUnhandled {
    case Event(_: Peer.OpenChannel, _) =>
      sender() ! Status.Failure(new RuntimeException("not connected"))
      stay()

    case Event(_: Peer.Disconnect, _) =>
      sender() ! Status.Failure(new RuntimeException("not connected"))
      stay()

    case Event(r: GetPeerInfo, d) =>
      val replyTo = r.replyTo.getOrElse(sender().toTyped)
      replyTo ! PeerInfo(self, remoteNodeId, stateName, d match {
        case c: ConnectedData => Some(c.address)
        case _ => None
      }, d.channels.values.toSet)
      stay()

    case Event(_: Peer.OutgoingMessage, _) => stay() // we got disconnected or reconnected and this message was for the previous connection

    case Event(RelayOnionMessage(messageId, _, replyTo_opt), _) =>
      replyTo_opt.foreach(_ ! MessageRelay.Disconnected(messageId))
      stay()
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
    log.debug("got authenticated connection to address {}", connectionReady.address)

    if (connectionReady.outgoing) {
      // we store the node address upon successful outgoing connection, so we can reconnect later
      // any previous address is overwritten
      nodeParams.db.peers.addOrUpdatePeer(remoteNodeId, connectionReady.address)
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
    case Event(msg: LightningMessage, d: ConnectedData) if sender() != d.peerConnection =>
      log.warning("dropping message from stale connection: {}", msg)
      stay()
    case e if s.isDefinedAt(e) =>
      s(e)
  }

  def spawnChannel(origin_opt: Option[ActorRef]): ActorRef = {
    val channel = channelFactory.spawn(context, remoteNodeId, origin_opt)
    context watch channel
    channel
  }

  def replyUnknownChannel(peerConnection: ActorRef, unknownChannelId: ByteVector32): Unit = {
    val msg = Warning(unknownChannelId, "unknown channel")
    self ! Peer.OutgoingMessage(msg, peerConnection)
  }

  // resume the openChannelInterceptor in case of failure, we always want the open channel request to succeed or fail
  private val openChannelInterceptor = context.spawnAnonymous(Behaviors.supervise(OpenChannelInterceptor(context.self.toTyped, nodeParams, remoteNodeId, wallet, pendingChannelsRateLimiter)).onFailure(typed.SupervisorStrategy.resume))

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
    Logs.mdc(LogCategory(currentMessage), Some(remoteNodeId), Logs.channelId(currentMessage), nodeAlias_opt = Some(nodeParams.alias))
  }

}

object Peer {

  val CHANNELID_ZERO: ByteVector32 = ByteVector32.Zeroes

  trait ChannelFactory {
    def spawn(context: ActorContext, remoteNodeId: PublicKey, origin_opt: Option[ActorRef]): ActorRef
  }

  case class SimpleChannelFactory(nodeParams: NodeParams, watcher: typed.ActorRef[ZmqWatcher.Command], relayer: ActorRef, wallet: OnChainChannelFunder with OnchainPubkeyCache, txPublisherFactory: Channel.TxPublisherFactory) extends ChannelFactory {
    override def spawn(context: ActorContext, remoteNodeId: PublicKey, origin_opt: Option[ActorRef]): ActorRef =
      context.actorOf(Channel.props(nodeParams, wallet, remoteNodeId, watcher, relayer, txPublisherFactory, origin_opt))
  }

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, wallet: OnchainPubkeyCache, channelFactory: ChannelFactory, switchboard: ActorRef, pendingChannelsRateLimiter: typed.ActorRef[PendingChannelsRateLimiter.Command]): Props = Props(new Peer(nodeParams, remoteNodeId, wallet, channelFactory, switchboard, pendingChannelsRateLimiter))

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
  case class ConnectedData(address: NodeAddress, peerConnection: ActorRef, localInit: protocol.Init, remoteInit: protocol.Init, channels: Map[ChannelId, ActorRef]) extends Data {
    val connectionInfo: ConnectionInfo = ConnectionInfo(address, peerConnection, localInit, remoteInit)
    def localFeatures: Features[InitFeature] = localInit.features
    def remoteFeatures: Features[InitFeature] = remoteInit.features
  }

  sealed trait State
  case object INSTANTIATING extends State
  case object DISCONNECTED extends State
  case object CONNECTED extends State

  case class Init(storedChannels: Set[PersistentChannelData])
  case class Connect(nodeId: PublicKey, address_opt: Option[NodeAddress], replyTo: ActorRef, isPersistent: Boolean) {
    def uri: Option[NodeURI] = address_opt.map(NodeURI(nodeId, _))
  }
  object Connect {
    def apply(uri: NodeURI, replyTo: ActorRef, isPersistent: Boolean): Connect = new Connect(uri.nodeId, Some(uri.address), replyTo, isPersistent)
  }

  case class Disconnect(nodeId: PublicKey) extends PossiblyHarmful

  case class OpenChannel(remoteNodeId: PublicKey,
                         fundingAmount: Satoshi,
                         channelType_opt: Option[SupportedChannelType],
                         pushAmount_opt: Option[MilliSatoshi],
                         fundingTxFeerate_opt: Option[FeeratePerKw],
                         channelFlags_opt: Option[ChannelFlags],
                         timeout_opt: Option[Timeout],
                         requireConfirmedInputsOverride_opt: Option[Boolean] = None,
                         disableMaxHtlcValueInFlight: Boolean = false,
                         channelOrigin: ChannelOrigin = ChannelOrigin.Default) extends PossiblyHarmful {
    require(!(channelType_opt.exists(_.features.contains(Features.ScidAlias)) && channelFlags_opt.exists(_.announceChannel)), "option_scid_alias is not compatible with public channels")
    require(fundingAmount > 0.sat, s"funding amount must be positive")
    pushAmount_opt.foreach(pushAmount => {
      require(pushAmount >= 0.msat, s"pushAmount must be positive")
      require(pushAmount <= fundingAmount, s"pushAmount must be less than or equal to funding amount")
    })
    fundingTxFeerate_opt.foreach(feerate => require(feerate >= FeeratePerKw.MinimumFeeratePerKw, s"fee rate $feerate is below minimum ${FeeratePerKw.MinimumFeeratePerKw}"))
  }

  case class SpawnChannelInitiator(cmd: Peer.OpenChannel, channelConfig: ChannelConfig, channelType: SupportedChannelType, localParams: LocalParams, origin: ActorRef)
  case class SpawnChannelNonInitiator(open: Either[protocol.OpenChannel, protocol.OpenDualFundedChannel], channelConfig: ChannelConfig, channelType: SupportedChannelType, localParams: LocalParams, peerConnection: ActorRef)

  case class GetPeerInfo(replyTo: Option[typed.ActorRef[PeerInfoResponse]])
  sealed trait PeerInfoResponse {
    def nodeId: PublicKey
  }
  case class PeerInfo(peer: ActorRef, nodeId: PublicKey, state: State, address: Option[NodeAddress], channels: Set[ActorRef]) extends PeerInfoResponse
  case class PeerNotFound(nodeId: PublicKey) extends PeerInfoResponse { override def toString: String = s"peer $nodeId not found" }

  case class PeerRoutingMessage(peerConnection: ActorRef, remoteNodeId: PublicKey, message: RoutingMessage) extends RemoteTypes

  /**
   * Dedicated command for outgoing messages for logging purposes.
   *
   * To preserve sequentiality of messages in the event of disconnections and reconnections, we provide a reference to
   * the connection that the message is valid for. If the actual connection was reset in the meantime, the [[Peer]]
   * will simply drop the message.
   */
  case class OutgoingMessage(msg: LightningMessage, peerConnection: ActorRef)

  case class Transition(previousData: Peer.Data, nextData: Peer.Data)

  /**
   * Sent by the peer-connection to notify the peer that the connection is down.
   * We could use watchWith on the peer-connection but it doesn't work with akka cluster when untrusted mode is enabled
   */
  case class ConnectionDown(peerConnection: ActorRef) extends RemoteTypes

  case class RelayOnionMessage(messageId: ByteVector32, msg: OnionMessage, replyTo_opt: Option[typed.ActorRef[Status]])

  case class RelayUnknownMessage(unknownMessage: UnknownMessage)
  // @formatter:on
}
