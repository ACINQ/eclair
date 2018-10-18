/*
 * Copyright 2018 ACINQ SAS
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

import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.ByteOrder

import akka.actor.{ActorRef, OneForOneStrategy, PoisonPill, Props, Status, SupervisorStrategy, Terminated}
import akka.event.Logging.MDC
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet, MilliSatoshi, Protocol, Satoshi}
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{wire, _}
import scodec.Attempt

import scala.concurrent.duration._
import scala.util.Random

/**
  * Created by PM on 26/08/2016.
  */
class Peer(nodeParams: NodeParams, remoteNodeId: PublicKey, authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) extends FSMDiagnosticActorLogging[Peer.State, Peer.Data] {

  import Peer._

  startWith(INSTANTIATING, Nothing())

  when(INSTANTIATING) {
    case Event(Init(previousKnownAddress, storedChannels), _) =>
      val channels = storedChannels.map { state =>
        val channel = spawnChannel(nodeParams, origin_opt = None)
        channel ! INPUT_RESTORED(state)
        FinalChannelId(state.channelId) -> channel
      }.toMap
      goto(DISCONNECTED) using DisconnectedData(previousKnownAddress, channels)
  }

  when(DISCONNECTED) {
    case Event(Peer.Connect(NodeURI(_, address)), _) =>
      // even if we are in a reconnection loop, we immediately process explicit connection requests
      context.actorOf(Client.props(nodeParams, authenticator, new InetSocketAddress(address.getHost, address.getPort), remoteNodeId, origin_opt = Some(sender())))
      stay

    case Event(Reconnect, d@DisconnectedData(address_opt, channels, attempts)) =>
      address_opt match {
        case None => stay // no-op (this peer didn't initiate the connection and doesn't have the ip of the counterparty)
        case _ if channels.isEmpty => stay // no-op (no more channels with this peer)
        case Some(address) =>
          context.actorOf(Client.props(nodeParams, authenticator, address, remoteNodeId, origin_opt = None))
          // exponential backoff retry with a finite max
          setTimer(RECONNECT_TIMER, Reconnect, Math.min(10 + Math.pow(2, attempts), 60) seconds, repeat = false)
          stay using d.copy(attempts = attempts + 1)
      }

    case Event(Authenticator.Authenticated(_, transport, remoteNodeId, address, outgoing, origin_opt), DisconnectedData(_, channels, _)) =>
      log.debug(s"got authenticated connection to $remoteNodeId@${address.getHostString}:${address.getPort}")
      transport ! TransportHandler.Listener(self)
      context watch transport
      transport ! wire.Init(globalFeatures = nodeParams.globalFeatures, localFeatures = nodeParams.localFeatures)

      // we store the ip upon successful outgoing connection, keeping only the most recent one
      if (outgoing) {
        nodeParams.peersDb.addOrUpdatePeer(remoteNodeId, address)
      }
      goto(INITIALIZING) using InitializingData(if (outgoing) Some(address) else None, transport, channels, origin_opt)

    case Event(Terminated(actor), d@DisconnectedData(_, channels, _)) if channels.exists(_._2 == actor) =>
      val h = channels.filter(_._2 == actor).map(_._1)
      log.info(s"channel closed: channelId=${h.mkString("/")}")
      stay using d.copy(channels = channels -- h)

    case Event(_: wire.LightningMessage, _) => stay // we probably just got disconnected and that's the last messages we received
  }

  when(INITIALIZING) {
    case Event(remoteInit: wire.Init, InitializingData(address_opt, transport, channels, origin_opt)) =>
      transport ! TransportHandler.ReadAck(remoteInit)
      val remoteHasInitialRoutingSync = Features.hasFeature(remoteInit.localFeatures, Features.INITIAL_ROUTING_SYNC_BIT_OPTIONAL)
      val remoteHasChannelRangeQueriesOptional = Features.hasFeature(remoteInit.localFeatures, Features.CHANNEL_RANGE_QUERIES_BIT_OPTIONAL)
      val remoteHasChannelRangeQueriesMandatory = Features.hasFeature(remoteInit.localFeatures, Features.CHANNEL_RANGE_QUERIES_BIT_MANDATORY)
      log.info(s"$remoteNodeId has features: initialRoutingSync=$remoteHasInitialRoutingSync channelRangeQueriesOptional=$remoteHasChannelRangeQueriesOptional channelRangeQueriesMandatory=$remoteHasChannelRangeQueriesMandatory")
      if (Features.areSupported(remoteInit.localFeatures)) {
        origin_opt.map(origin => origin ! "connected")

        if (remoteHasInitialRoutingSync) {
          if (remoteHasChannelRangeQueriesOptional || remoteHasChannelRangeQueriesMandatory) {
            // if they support channel queries we do nothing, they will send us their filters
            log.info("{} has set initial routing sync and support channel range queries, we do nothing (they will send us a query)", remoteNodeId)
          } else {
            // "old" nodes, do as before
            router ! GetRoutingState
          }
        }
        if (remoteHasChannelRangeQueriesOptional || remoteHasChannelRangeQueriesMandatory) {
          // if they support channel queries, always ask for their filter
          router ! SendChannelQuery(remoteNodeId, transport)
        }

        // let's bring existing/requested channels online
        channels.values.toSet[ActorRef].foreach(_ ! INPUT_RECONNECTED(transport)) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
        goto(CONNECTED) using ConnectedData(address_opt, transport, remoteInit, channels.map { case (k: ChannelId, v) => (k, v) })
      } else {
        log.warning(s"incompatible features, disconnecting")
        origin_opt.map(origin => origin ! Status.Failure(new RuntimeException("incompatible features")))
        transport ! PoisonPill
        stay
      }

    case Event(Authenticator.Authenticated(connection, _, _, _, _, origin_opt), _) =>
      // two connections in parallel
      origin_opt.map(origin => origin ! Status.Failure(new RuntimeException("there is another connection attempt in progress")))
      // we kill this one
      log.warning(s"killing parallel connection $connection")
      connection ! PoisonPill
      stay

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
    case Event(StateTimeout, ConnectedData(_, transport, _, _, _, _)) =>
      // no need to use secure random here
      val pingSize = Random.nextInt(1000)
      val pongSize = Random.nextInt(1000)
      transport ! wire.Ping(pongSize, BinaryData("00" * pingSize))
      stay

    case Event(ping@wire.Ping(pongLength, _), ConnectedData(_, transport, _, _, _, _)) =>
      transport ! TransportHandler.ReadAck(ping)
      // TODO: (optional) check against the expected data size tat we requested when we sent ping messages
      if (pongLength > 0) {
        transport ! wire.Pong(BinaryData("00" * pongLength))
      }
      stay

    case Event(pong@wire.Pong(data), ConnectedData(_, transport, _, _, _, _)) =>
      transport ! TransportHandler.ReadAck(pong)
      // TODO: compute latency for remote peer ?
      log.debug(s"received pong with ${data.length} bytes")
      stay

    case Event(err@wire.Error(channelId, reason), ConnectedData(_, transport, _, channels, _, _)) if channelId == CHANNELID_ZERO =>
      transport ! TransportHandler.ReadAck(err)
      log.error(s"connection-level error, failing all channels! reason=${new String(reason)}")
      channels.values.toSet[ActorRef].foreach(_ forward err) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
      transport ! PoisonPill
      stay

    case Event(err: wire.Error, ConnectedData(_, transport, _, channels, _, _)) =>
      transport ! TransportHandler.ReadAck(err)
      // error messages are a bit special because they can contain either temporaryChannelId or channelId (see BOLT 1)
      channels.get(FinalChannelId(err.channelId)).orElse(channels.get(TemporaryChannelId(err.channelId))) match {
        case Some(channel) => channel forward err
        case None => transport ! wire.Error(err.channelId, UNKNOWN_CHANNEL_MESSAGE)
      }
      stay

    case Event(c: Peer.OpenChannel, d@ConnectedData(_, transport, remoteInit, channels, _, _)) =>
      log.info(s"requesting a new channel to $remoteNodeId with fundingSatoshis=${c.fundingSatoshis}, pushMsat=${c.pushMsat} and fundingFeeratePerByte=${c.fundingTxFeeratePerKw_opt}")
      val (channel, localParams) = createNewChannel(nodeParams, funder = true, c.fundingSatoshis.toLong, origin_opt = Some(sender))
      val temporaryChannelId = randomBytes(32)
      val channelFeeratePerKw = Globals.feeratesPerKw.get.blocks_2
      val fundingTxFeeratePerKw = c.fundingTxFeeratePerKw_opt.getOrElse(Globals.feeratesPerKw.get.blocks_6)
      channel ! INPUT_INIT_FUNDER(temporaryChannelId, c.fundingSatoshis.amount, c.pushMsat.amount, channelFeeratePerKw, fundingTxFeeratePerKw, localParams, transport, remoteInit, c.channelFlags.getOrElse(nodeParams.channelFlags))
      stay using d.copy(channels = channels + (TemporaryChannelId(temporaryChannelId) -> channel))

    case Event(msg: wire.OpenChannel, d@ConnectedData(_, transport, remoteInit, channels, _, _)) =>
      transport ! TransportHandler.ReadAck(msg)
      channels.get(TemporaryChannelId(msg.temporaryChannelId)) match {
        case None =>
          log.info(s"accepting a new channel to $remoteNodeId")
          val (channel, localParams) = createNewChannel(nodeParams, funder = false, fundingSatoshis = msg.fundingSatoshis, origin_opt = None)
          val temporaryChannelId = msg.temporaryChannelId
          channel ! INPUT_INIT_FUNDEE(temporaryChannelId, localParams, transport, remoteInit)
          channel ! msg
          stay using d.copy(channels = channels + (TemporaryChannelId(temporaryChannelId) -> channel))
        case Some(_) =>
          log.warning(s"ignoring open_channel with duplicate temporaryChannelId=${msg.temporaryChannelId}")
          stay
      }

    case Event(msg: wire.HasChannelId, ConnectedData(_, transport, _, channels, _, _)) =>
      transport ! TransportHandler.ReadAck(msg)
      channels.get(FinalChannelId(msg.channelId)) match {
        case Some(channel) => channel forward msg
        case None => transport ! wire.Error(msg.channelId, UNKNOWN_CHANNEL_MESSAGE)
      }
      stay

    case Event(msg: wire.HasTemporaryChannelId, ConnectedData(_, transport, _, channels, _, _)) =>
      transport ! TransportHandler.ReadAck(msg)
      channels.get(TemporaryChannelId(msg.temporaryChannelId)) match {
        case Some(channel) => channel forward msg
        case None => transport ! wire.Error(msg.temporaryChannelId, UNKNOWN_CHANNEL_MESSAGE)
      }
      stay

    case Event(ChannelIdAssigned(channel, _, temporaryChannelId, channelId), d@ConnectedData(_, _, _, channels, _, _)) if channels.contains(TemporaryChannelId(temporaryChannelId)) =>
      log.info(s"channel id switch: previousId=$temporaryChannelId nextId=$channelId")
      // NB: we keep the temporary channel id because the switch is not always acknowledged at this point (see https://github.com/lightningnetwork/lightning-rfc/pull/151)
      // we won't clean it up, but we won't remember the temporary id on channel termination
      stay using d.copy(channels = channels + (FinalChannelId(channelId) -> channel))

    case Event(RoutingState(channels, updates, nodes), ConnectedData(_, transport, _, _, _, _)) =>
      // let's send the messages
      def send(announcements: Iterable[_ <: LightningMessage]) = announcements.foldLeft(0) {
        case (c, ann) =>
          transport ! ann
          c + 1
      }

      log.info(s"sending all announcements to {}", remoteNodeId)
      val channelsSent = send(channels)
      val nodesSent = send(nodes)
      val updatesSent = send(updates)
      log.info(s"sent all announcements to {}: channels={} updates={} nodes={}", remoteNodeId, channelsSent, updatesSent, nodesSent)
      stay

    case Event(rebroadcast: Rebroadcast, ConnectedData(_, transport, _, _, maybeGossipTimestampFilter, _)) =>

      /**
        * Send and count in a single iteration
        */
      def sendAndCount(msgs: Map[_ <: RoutingMessage, Set[ActorRef]]): Int = msgs.foldLeft(0) {
        case (count, (_, origins)) if origins.contains(self) =>
          // the announcement came from this peer, we don't send it back
          count
        case (count, (msg: HasTimestamp, _)) if !timestampInRange(msg, maybeGossipTimestampFilter) =>
          // the peer has set up a filter on timestamp and this message is out of range
          count
        case (count, (msg, _)) =>
          transport ! msg
          count + 1
      }

      val channelsSent = sendAndCount(rebroadcast.channels)
      val updatesSent = sendAndCount(rebroadcast.updates)
      val nodesSent = sendAndCount(rebroadcast.nodes)

      if (channelsSent > 0 || updatesSent > 0 || nodesSent > 0) {
        log.info(s"sent announcements to {}: channels={} updates={} nodes={}", remoteNodeId, channelsSent, updatesSent, nodesSent)
      }
      stay

    case Event(msg: GossipTimestampFilter, data: ConnectedData) =>
      // special case: time range filters are peer specific and must not be sent to the router
      sender ! TransportHandler.ReadAck(msg)
      if (msg.chainHash != nodeParams.chainHash) {
        log.warning("received gossip_timestamp_range message for chain {}, we're on {}", msg.chainHash, nodeParams.chainHash)
        stay
      } else {
        log.info(s"setting up gossipTimestampFilter=$msg")
        // update their timestamp filter
        stay using data.copy(gossipTimestampFilter = Some(msg))
      }

    case Event(msg: wire.RoutingMessage, ConnectedData(_, transport, _, _, _, behavior)) =>
      msg match {
        case _: ChannelAnnouncement | _: ChannelUpdate | _: NodeAnnouncement if behavior.ignoreNetworkAnnouncement =>
          // this peer is currently under embargo!
          sender ! TransportHandler.ReadAck(msg)
        case _ =>
          // Note: we don't ack messages here because we don't want them to be stacked in the router's mailbox
          router ! PeerRoutingMessage(transport, remoteNodeId, msg)
      }
      stay

    case Event(readAck: TransportHandler.ReadAck, ConnectedData(_, transport, _, _, _, _)) =>
      // we just forward acks from router to transport
      transport forward readAck
      stay

    case Event(badMessage: BadMessage, data@ConnectedData(_, transport, _, _, _, behavior)) =>
      val behavior1 = badMessage match {
        case InvalidSignature(r) =>
          val bin: String = LightningMessageCodecs.lightningMessageCodec.encode(r) match {
            case Attempt.Successful(b) => b.toHex
            case _ => "unknown"
          }
          log.error(s"peer sent us a routing message with invalid sig: r=$r bin=$bin")
          // for now we just return an error, maybe ban the peer in the future?
          transport ! Error(CHANNELID_ZERO, s"bad announcement sig! bin=$bin".getBytes())
          behavior
        case ChannelClosed(_) =>
          if (behavior.ignoreNetworkAnnouncement) {
            // we already are ignoring announcements, we may have additional notifications for announcements that were received right before our ban
            behavior.copy(fundingTxAlreadySpentCount = behavior.fundingTxAlreadySpentCount + 1)
          } else if (behavior.fundingTxAlreadySpentCount < MAX_FUNDING_TX_ALREADY_SPENT) {
            behavior.copy(fundingTxAlreadySpentCount = behavior.fundingTxAlreadySpentCount + 1)
          } else {
            log.warning(s"peer sent us too many channel announcements with funding tx already spent (count=${behavior.fundingTxAlreadySpentCount + 1}), ignoring network announcements for $IGNORE_NETWORK_ANNOUNCEMENTS_PERIOD")
            import scala.concurrent.ExecutionContext.Implicits.global
            context.system.scheduler.scheduleOnce(IGNORE_NETWORK_ANNOUNCEMENTS_PERIOD, self, ResumeAnnouncements)
            behavior.copy(fundingTxAlreadySpentCount = behavior.fundingTxAlreadySpentCount + 1, ignoreNetworkAnnouncement = true)
          }
        case NonexistingChannel(_) =>
          // this should never happen, unless we are not in sync or there is a 6+ blocks reorg
          if (behavior.ignoreNetworkAnnouncement) {
            // we already are ignoring announcements, we may have additional notifications for announcements that were received right before our ban
            behavior.copy(fundingTxNotFoundCount = behavior.fundingTxNotFoundCount + 1)
          } else if (behavior.fundingTxNotFoundCount < MAX_FUNDING_TX_NOT_FOUND) {
            behavior.copy(fundingTxNotFoundCount = behavior.fundingTxNotFoundCount + 1)
          } else {
            log.warning(s"peer sent us too many channel announcements with non-existing funding tx (count=${behavior.fundingTxNotFoundCount + 1}), ignoring network announcements for $IGNORE_NETWORK_ANNOUNCEMENTS_PERIOD")
            import scala.concurrent.ExecutionContext.Implicits.global
            context.system.scheduler.scheduleOnce(IGNORE_NETWORK_ANNOUNCEMENTS_PERIOD, self, ResumeAnnouncements)
            behavior.copy(fundingTxNotFoundCount = behavior.fundingTxNotFoundCount + 1, ignoreNetworkAnnouncement = true)
          }
      }
      stay using data.copy(behavior = behavior1)

    case Event(ResumeAnnouncements, data: ConnectedData) =>
      log.info(s"resuming processing of network announcements for peer")
      stay using data.copy(behavior = data.behavior.copy(fundingTxAlreadySpentCount = 0, ignoreNetworkAnnouncement = false))

    case Event(Disconnect, ConnectedData(_, transport, _, _, _, _)) =>
      transport ! PoisonPill
      stay

    case Event(Terminated(actor), ConnectedData(address_opt, transport, _, channels, _, _)) if actor == transport =>
      log.info(s"lost connection to $remoteNodeId")
      channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
      goto(DISCONNECTED) using DisconnectedData(address_opt, channels.collect { case (k: FinalChannelId, v) => (k, v) })

    case Event(Terminated(actor), d@ConnectedData(_, transport, _, channels, _, _)) if channels.values.toSet.contains(actor) =>
      // we will have at most 2 ids: a TemporaryChannelId and a FinalChannelId
      val channelIds = channels.filter(_._2 == actor).keys
      log.info(s"channel closed: channelId=${channelIds.mkString("/")}")
      if (channels.values.toSet - actor == Set.empty) {
        log.info(s"that was the last open channel, closing the connection")
        transport ! PoisonPill
      }
      stay using d.copy(channels = channels -- channelIds)

    case Event(h: Authenticator.Authenticated, ConnectedData(address_opt, oldTransport, _, channels, _, _)) =>
      log.info(s"got new transport while already connected, switching to new transport")
      context unwatch oldTransport
      oldTransport ! PoisonPill
      channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
      self ! h
      goto(DISCONNECTED) using DisconnectedData(address_opt, channels.collect { case (k: FinalChannelId, v) => (k, v) })
  }

  whenUnhandled {
    case Event(_: Peer.Connect, _) =>
      sender ! "already connected"
      stay

    case Event(_: Peer.OpenChannel, _) =>
      sender ! Status.Failure(new RuntimeException("not connected"))
      stay

    case Event(GetPeerInfo, d) =>
      sender ! PeerInfo(remoteNodeId, stateName.toString, d.address_opt, d.channels.values.toSet.size) // we use toSet to dedup because a channel can have a TemporaryChannelId + a ChannelId
      stay

    case Event(_: Rebroadcast, _) => stay // ignored

    case Event(_: RoutingState, _) => stay // ignored

    case Event(_: TransportHandler.ReadAck, _) => stay // ignored
  }

  onTransition {
    case _ -> DISCONNECTED if nodeParams.autoReconnect && nextStateData.address_opt.isDefined => setTimer(RECONNECT_TIMER, Reconnect, 1 second, repeat = false)
    case DISCONNECTED -> _ if nodeParams.autoReconnect && stateData.address_opt.isDefined => cancelTimer(RECONNECT_TIMER)
  }

  def createNewChannel(nodeParams: NodeParams, funder: Boolean, fundingSatoshis: Long, origin_opt: Option[ActorRef]): (ActorRef, LocalParams) = {
    val defaultFinalScriptPubKey = Helpers.getFinalScriptPubKey(wallet, nodeParams.chainHash)
    val localParams = makeChannelParams(nodeParams, defaultFinalScriptPubKey, funder, fundingSatoshis)
    val channel = spawnChannel(nodeParams, origin_opt)
    (channel, localParams)
  }

  def spawnChannel(nodeParams: NodeParams, origin_opt: Option[ActorRef]): ActorRef = {
    val channel = context.actorOf(Channel.props(nodeParams, wallet, remoteNodeId, watcher, router, relayer, origin_opt))
    context watch channel
    channel
  }

  // a failing channel won't be restarted, it should handle its states
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Stop }

  initialize()

  override def mdc(currentMessage: Any): MDC = Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))

}

object Peer {

  val CHANNELID_ZERO = BinaryData("00" * 32)

  val UNKNOWN_CHANNEL_MESSAGE = "unknown channel".getBytes()

  val RECONNECT_TIMER = "reconnect"

  val MAX_FUNDING_TX_ALREADY_SPENT = 10

  val MAX_FUNDING_TX_NOT_FOUND = 10

  val IGNORE_NETWORK_ANNOUNCEMENTS_PERIOD = 5 minutes

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) = Props(new Peer(nodeParams, remoteNodeId, authenticator, watcher, router, relayer, wallet: EclairWallet))

  // @formatter:off

  sealed trait ChannelId { def id: BinaryData }
  case class TemporaryChannelId(id: BinaryData) extends ChannelId
  case class FinalChannelId(id: BinaryData) extends ChannelId

  sealed trait Data {
    def address_opt: Option[InetSocketAddress]
    def channels: Map[_ <: ChannelId, ActorRef] // will be overridden by Map[FinalChannelId, ActorRef] or Map[ChannelId, ActorRef]
  }
  case class Nothing() extends Data { override def address_opt = None; override def channels = Map.empty }
  case class DisconnectedData(address_opt: Option[InetSocketAddress], channels: Map[FinalChannelId, ActorRef], attempts: Int = 0) extends Data
  case class InitializingData(address_opt: Option[InetSocketAddress], transport: ActorRef, channels: Map[FinalChannelId, ActorRef], origin_opt: Option[ActorRef]) extends Data
  case class ConnectedData(address_opt: Option[InetSocketAddress], transport: ActorRef, remoteInit: wire.Init, channels: Map[ChannelId, ActorRef], gossipTimestampFilter: Option[GossipTimestampFilter] = None, behavior: Behavior = Behavior()) extends Data

  sealed trait State
  case object INSTANTIATING extends State
  case object DISCONNECTED extends State
  case object INITIALIZING extends State
  case object CONNECTED extends State

  case class Init(previousKnownAddress: Option[InetSocketAddress], storedChannels: Set[HasCommitments])
  case class Connect(uri: NodeURI)
  case object Reconnect
  case object Disconnect
  case object ResumeAnnouncements
  case class OpenChannel(remoteNodeId: PublicKey, fundingSatoshis: Satoshi, pushMsat: MilliSatoshi, fundingTxFeeratePerKw_opt: Option[Long], channelFlags: Option[Byte]) {
    require(fundingSatoshis.amount < Channel.MAX_FUNDING_SATOSHIS, s"fundingSatoshis must be less than ${Channel.MAX_FUNDING_SATOSHIS}")
    require(pushMsat.amount <= 1000 * fundingSatoshis.amount, s"pushMsat must be less or equal to fundingSatoshis")
    require(fundingSatoshis.amount >= 0, s"fundingSatoshis must be positive")
    require(pushMsat.amount >= 0, s"pushMsat must be positive")
    require(fundingTxFeeratePerKw_opt.getOrElse(0L) >= 0, s"funding tx feerate must be positive")
  }
  case object GetPeerInfo
  case class PeerInfo(nodeId: PublicKey, state: String, address: Option[InetSocketAddress], channels: Int)

  case class PeerRoutingMessage(transport: ActorRef, remoteNodeId: PublicKey, message: RoutingMessage)

  sealed trait BadMessage
  case class InvalidSignature(r: RoutingMessage) extends BadMessage
  case class ChannelClosed(c: ChannelAnnouncement) extends BadMessage
  case class NonexistingChannel(c: ChannelAnnouncement) extends BadMessage

  case class Behavior(fundingTxAlreadySpentCount: Int = 0, fundingTxNotFoundCount: Int = 0, ignoreNetworkAnnouncement: Boolean = false)

  // @formatter:on

  def makeChannelParams(nodeParams: NodeParams, defaultFinalScriptPubKey: BinaryData, isFunder: Boolean, fundingSatoshis: Long): LocalParams = {
    val entropy = new Array[Byte](16)
    secureRandom.nextBytes(entropy)
    val bis = new ByteArrayInputStream(entropy)
    val channelKeyPath = DeterministicWallet.KeyPath(Seq(Protocol.uint32(bis, ByteOrder.BIG_ENDIAN), Protocol.uint32(bis, ByteOrder.BIG_ENDIAN), Protocol.uint32(bis, ByteOrder.BIG_ENDIAN), Protocol.uint32(bis, ByteOrder.BIG_ENDIAN)))
    makeChannelParams(nodeParams, defaultFinalScriptPubKey, isFunder, fundingSatoshis, channelKeyPath)
  }

  def makeChannelParams(nodeParams: NodeParams, defaultFinalScriptPubKey: BinaryData, isFunder: Boolean, fundingSatoshis: Long, channelKeyPath: DeterministicWallet.KeyPath): LocalParams = {
    LocalParams(
      nodeParams.nodeId,
      channelKeyPath,
      dustLimitSatoshis = nodeParams.dustLimitSatoshis,
      maxHtlcValueInFlightMsat = nodeParams.maxHtlcValueInFlightMsat,
      channelReserveSatoshis = Math.max((nodeParams.reserveToFundingRatio * fundingSatoshis).toLong, nodeParams.dustLimitSatoshis), // BOLT #2: make sure that our reserve is above our dust limit
      htlcMinimumMsat = nodeParams.htlcMinimumMsat,
      toSelfDelay = nodeParams.toRemoteDelayBlocks, // we choose their delay
      maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs,
      defaultFinalScriptPubKey = defaultFinalScriptPubKey,
      isFunder = isFunder,
      globalFeatures = nodeParams.globalFeatures,
      localFeatures = nodeParams.localFeatures)
  }

  /**
    * Peer may want to filter announcements based on timestamp
    *
    * @param gossipTimestampFilter_opt optional gossip timestamp range
    * @return
    *           - true if the msg's timestamp is in the requested range, or if there is no filtering
    *           - false otherwise
    */
  def timestampInRange(msg: HasTimestamp, gossipTimestampFilter_opt: Option[GossipTimestampFilter]): Boolean = {
    // check if this message has a timestamp that matches our timestamp filter
    gossipTimestampFilter_opt match {
      case None => true // no filtering
      case Some(GossipTimestampFilter(_, firstTimestamp, timestampRange)) => msg.timestamp >= firstTimestamp && msg.timestamp <= firstTimestamp + timestampRange
    }
  }
}
