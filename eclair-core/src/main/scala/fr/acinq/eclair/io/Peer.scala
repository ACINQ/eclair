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

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, ExtendedActorSystem, FSM, OneForOneStrategy, PoisonPill, Props, Status, SupervisorStrategy, Terminated}
import akka.event.{BusLogging, DiagnosticLoggingAdapter}
import akka.event.Logging.MDC
import akka.util.Timeout
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, DeterministicWallet, Satoshi}
import fr.acinq.eclair.Features.Wumbo
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{wire, _}
import kamon.Kamon
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.util.Random

/**
 * This actor represents a logical peer. There is one [[Peer]] per unique remote node id at all time.
 *
 * The [[Peer] actor is mostly in charge of managing channels, stays alive when the peer is disconnected, and only dies
 * when there are no more channels.
 *
 * Everytime a new connection is established, it is sent to the [[Peer]] and replaces the previous one.
 *
 * Created by PM on 26/08/2016.
 */
class Peer(val nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, watcher: ActorRef, relayer: ActorRef, paymentHandler: ActorRef, wallet: EclairWallet) extends FSMDiagnosticActorLogging[Peer.State, Peer.Data] {

  import Peer._

  startWith(INSTANTIATING, Nothing)

  when(INSTANTIATING) {
    case Event(Init(previousKnownAddress, storedChannels), _) =>
      val channels = storedChannels.map { state =>
        val channel = spawnChannel(nodeParams, origin_opt = None)
        channel ! INPUT_RESTORED(state)
        FinalChannelId(state.channelId) -> channel
      }.toMap
      // When restarting, we will immediately reconnect, but then:
      // - we don't want all the subsequent reconnection attempts to be synchronized (herd effect)
      // - we don't want to go through the exponential backoff delay, because we were offline, not them, so there is no
      // reason to eagerly retry
      // That's why we set the next reconnection delay to a random value between MAX_RECONNECT_INTERVAL/2 and MAX_RECONNECT_INTERVAL.
      val firstNextReconnectionDelay = nodeParams.maxReconnectInterval.minus(Random.nextInt(nodeParams.maxReconnectInterval.toSeconds.toInt / 2).seconds)
      goto(DISCONNECTED) using DisconnectedData(previousKnownAddress, channels, firstNextReconnectionDelay) // when we restart, we will attempt to reconnect right away, but then we'll wait
  }

  when(DISCONNECTED) {
    case Event(Peer.Connect(_, address_opt), d: DisconnectedData) =>
      address_opt
        .map(hostAndPort2InetSocketAddress)
        .orElse(getPeerAddressFromNodeAnnouncement) match {
        case None =>
          sender ! "no address found"
          stay
        case Some(address) =>
          if (d.address_opt.contains(address)) {
            // we already know this address, we'll reconnect automatically
            sender ! "reconnection in progress"
            stay
          } else {
            // we immediately process explicit connection requests to new addresses
            context.actorOf(Client.props(nodeParams, context.parent, router, address, remoteNodeId, origin_opt = Some(sender())))
            stay using d.copy(address_opt = Some(address))
          }
      }

    case Event(Reconnect, d: DisconnectedData) =>
      d.address_opt.orElse(getPeerAddressFromNodeAnnouncement) match {
        case _ if d.channels.isEmpty => stay // no-op, no more channels with this peer
        case None => stay // no-op, we don't know any address to this peer and we won't try reconnecting again
        case Some(address) =>
          context.actorOf(Client.props(nodeParams, context.parent, router, address, remoteNodeId, origin_opt = None))
          log.info(s"reconnecting to $address (next reconnection in ${d.nextReconnectionDelay.toSeconds} seconds)")
          setTimer(RECONNECT_TIMER, Reconnect, d.nextReconnectionDelay, repeat = false)
          stay using d.copy(nextReconnectionDelay = nextReconnectionDelay(d.nextReconnectionDelay, nodeParams.maxReconnectInterval))
      }

    case Event(PeerConnection.ConnectionReady(peerConnection, remoteNodeId1, address, outgoing, localInit, remoteInit), d: DisconnectedData) =>
      require(remoteNodeId == remoteNodeId1, s"invalid nodeid: $remoteNodeId != $remoteNodeId1")
      log.debug(s"got authenticated connection to $remoteNodeId@${address.getHostString}:${address.getPort}")

      // We want to log all incoming/outgoing messages to/from this peer. Logging incoming messages is easy (they all go
      // through this actor), but for outgoing messages it's a bit trickier because channels directly send messages to
      // the connection. That's why we use this pass-through actor that just logs messages and forward them.
      val logConnection = context.actorOf(Props(new Actor {
        // we use this to log raw messages coming in and out of the peer
        val logMsgOut = new BusLogging(context.system.eventStream, "", classOf[Peer.MessageLogs], context.system.asInstanceOf[ExtendedActorSystem].logFilter) with DiagnosticLoggingAdapter
        context watch peerConnection
        override def receive: Receive = {
          case Terminated(_) =>
            context stop self
          case msg =>
            msg match {
              case _: LightningMessage =>
                logMsgOut.mdc(mdc(msg))
                logMsgOut.info("OUT msg={}", msg)
                logMsgOut.clearMDC()
              case _ => ()
            }
            peerConnection forward msg
        }

        override def postStop(): Unit = {
          peerConnection ! PoisonPill
          super.postStop()
        }
      }))

      context watch logConnection

      val address_opt = if (outgoing) {
        // we store the node address upon successful outgoing connection, so we can reconnect later
        // any previous address is overwritten
        NodeAddress.fromParts(address.getHostString, address.getPort).map(nodeAddress => nodeParams.db.peers.addOrUpdatePeer(remoteNodeId, nodeAddress))
        Some(address)
      } else None

      // let's bring existing/requested channels online
      d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_RECONNECTED(logConnection, localInit, remoteInit)) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)

      goto(CONNECTED) using ConnectedData(address_opt, logConnection, localInit, remoteInit, d.channels.map { case (k: ChannelId, v) => (k, v) })

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

      case Event(err@wire.Error(channelId, reason), d: ConnectedData) if channelId == CHANNELID_ZERO =>
        log.error(s"connection-level error, failing all channels! reason=${new String(reason.toArray)}")
        d.channels.values.toSet[ActorRef].foreach(_ forward err) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
        d.peerConnection ! PoisonPill
        stay

      case Event(err: wire.Error, d: ConnectedData) =>
        // error messages are a bit special because they can contain either temporaryChannelId or channelId (see BOLT 1)
        d.channels.get(FinalChannelId(err.channelId)).orElse(d.channels.get(TemporaryChannelId(err.channelId))) match {
          case Some(channel) => channel forward err
          case None => d.peerConnection ! wire.Error(err.channelId, UNKNOWN_CHANNEL_MESSAGE)
        }
        stay

      case Event(c: Peer.OpenChannel, d: ConnectedData) =>
        if (c.fundingSatoshis >= Channel.MAX_FUNDING && !Features.hasFeature(nodeParams.features, Wumbo)) {
          sender ! Status.Failure(new RuntimeException(s"fundingSatoshis=${c.fundingSatoshis} is too big, you must enable large channels support in 'eclair.features' to use funding above ${Channel.MAX_FUNDING} (see eclair.conf)"))
          stay
        } else if (c.fundingSatoshis >= Channel.MAX_FUNDING && !Features.hasFeature(d.remoteInit.features, Wumbo)) {
          sender ! Status.Failure(new RuntimeException(s"fundingSatoshis=${c.fundingSatoshis} is too big, the remote peer doesn't support wumbo"))
          stay
        } else if (c.fundingSatoshis > nodeParams.maxFundingSatoshis) {
          sender ! Status.Failure(new RuntimeException(s"fundingSatoshis=${c.fundingSatoshis} is too big for the current settings, increase 'eclair.max-funding-satoshis' (see eclair.conf)"))
          stay
        } else {
          val (channel, localParams) = createNewChannel(nodeParams, funder = true, c.fundingSatoshis, origin_opt = Some(sender))
          c.timeout_opt.map(openTimeout => context.system.scheduler.scheduleOnce(openTimeout.duration, channel, Channel.TickChannelOpenTimeout)(context.dispatcher))
          val temporaryChannelId = randomBytes32
          val channelFeeratePerKw = nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.commitmentBlockTarget)
          val fundingTxFeeratePerKw = c.fundingTxFeeratePerKw_opt.getOrElse(nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget))
          log.info(s"requesting a new channel with fundingSatoshis=${c.fundingSatoshis}, pushMsat=${c.pushMsat} and fundingFeeratePerByte=${c.fundingTxFeeratePerKw_opt} temporaryChannelId=$temporaryChannelId localParams=$localParams")
          channel ! INPUT_INIT_FUNDER(temporaryChannelId, c.fundingSatoshis, c.pushMsat, channelFeeratePerKw, fundingTxFeeratePerKw, localParams, d.peerConnection, d.remoteInit, c.channelFlags.getOrElse(nodeParams.channelFlags), ChannelVersion.STANDARD)
          stay using d.copy(channels = d.channels + (TemporaryChannelId(temporaryChannelId) -> channel))
        }

      case Event(msg: wire.OpenChannel, d: ConnectedData) =>
        d.channels.get(TemporaryChannelId(msg.temporaryChannelId)) match {
          case None =>
            val (channel, localParams) = createNewChannel(nodeParams, funder = false, fundingAmount = msg.fundingSatoshis, origin_opt = None)
            val temporaryChannelId = msg.temporaryChannelId
            log.info(s"accepting a new channel to $remoteNodeId temporaryChannelId=$temporaryChannelId localParams=$localParams")
            channel ! INPUT_INIT_FUNDEE(temporaryChannelId, localParams, d.peerConnection, d.remoteInit)
            channel ! msg
            stay using d.copy(channels = d.channels + (TemporaryChannelId(temporaryChannelId) -> channel))
          case Some(_) =>
            log.warning(s"ignoring open_channel with duplicate temporaryChannelId=${msg.temporaryChannelId}")
            stay
        }

      case Event(msg: wire.HasChannelId, d: ConnectedData) =>
        d.channels.get(FinalChannelId(msg.channelId)) match {
          case Some(channel) => channel forward msg
          case None => d.peerConnection ! wire.Error(msg.channelId, UNKNOWN_CHANNEL_MESSAGE)
        }
        stay

      case Event(msg: wire.HasTemporaryChannelId, d: ConnectedData) =>
        d.channels.get(TemporaryChannelId(msg.temporaryChannelId)) match {
          case Some(channel) => channel forward msg
          case None => d.peerConnection ! wire.Error(msg.temporaryChannelId, UNKNOWN_CHANNEL_MESSAGE)
        }
        stay

      case Event(ChannelIdAssigned(channel, _, temporaryChannelId, channelId), d: ConnectedData) if d.channels.contains(TemporaryChannelId(temporaryChannelId)) =>
        log.info(s"channel id switch: previousId=$temporaryChannelId nextId=$channelId")
        // NB: we keep the temporary channel id because the switch is not always acknowledged at this point (see https://github.com/lightningnetwork/lightning-rfc/pull/151)
        // we won't clean it up, but we won't remember the temporary id on channel termination
        stay using d.copy(channels = d.channels + (FinalChannelId(channelId) -> channel))

      case Event(Disconnect(nodeId), d: ConnectedData) if nodeId == remoteNodeId =>
        log.info(s"disconnecting")
        sender ! "disconnecting"
        d.peerConnection ! PoisonPill
        stay

      case Event(Terminated(actor), d: ConnectedData) if actor == d.peerConnection =>
        Logs.withMdc(diagLog)(Logs.mdc(category_opt = Some(Logs.LogCategory.CONNECTION))) {
          log.info(s"lost connection to $remoteNodeId")
        }
        if (d.channels.isEmpty) {
          // we have no existing channels, we can forget about this peer
          stopPeer()
        } else {
          d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
          goto(DISCONNECTED) using DisconnectedData(d.address_opt, d.channels.collect { case (k: FinalChannelId, v) => (k, v) })
        }

      case Event(Terminated(actor), d: ConnectedData) if d.channels.values.toSet.contains(actor) =>
        // we will have at most 2 ids: a TemporaryChannelId and a FinalChannelId
        val channelIds = d.channels.filter(_._2 == actor).keys
        log.info(s"channel closed: channelId=${channelIds.mkString("/")}")
        if (d.channels.values.toSet - actor == Set.empty) {
          log.info(s"that was the last open channel, closing the connection")
          d.peerConnection ! PoisonPill
        }
        stay using d.copy(channels = d.channels -- channelIds)

      case Event(connectionReady: PeerConnection.ConnectionReady, d: ConnectedData) =>
        log.info(s"got new connection, killing current one and switching")
        context unwatch d.peerConnection
        d.peerConnection ! PoisonPill
        d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
        self ! connectionReady
        goto(DISCONNECTED) using DisconnectedData(d.address_opt, d.channels.collect { case (k: FinalChannelId, v) => (k, v) })

      case Event(unhandledMsg: LightningMessage, _) =>
        log.warning("ignoring message {}", unhandledMsg)
        stay
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

    case Event(Peer.Reconnect, _) => stay // we got connected in the meantime

    case Event(msg, _) =>
      log.warning("unhandled msg {} in state {}", msg, stateName)
      stay

  }

  /**
   * The transition INSTANTIATING -> DISCONNECTED happens in 2 scenarios
   *   - Manual connection to a new peer: then when(DISCONNECTED) we expect a Peer.Connect from the switchboard
   *   - Eclair restart: The switchboard creates the peers and sends Init and then Peer.Reconnect to trigger reconnection attempts
   *
   * So when we see this transition we NO-OP because we don't want to start a Reconnect timer but the peer will receive the trigger
   * (Connect/Reconnect) messages from the switchboard.
   */
  onTransition {
    case INSTANTIATING -> DISCONNECTED => ()
    case _ -> DISCONNECTED if nodeParams.autoReconnect => setTimer(RECONNECT_TIMER, Reconnect, randomizeDelay(nodeParams.initialRandomReconnectDelay), repeat = false) // we add some randomization to not have peers reconnect to each other exactly at the same time
    case DISCONNECTED -> _ if nodeParams.autoReconnect => cancelTimer(RECONNECT_TIMER)
  }

  onTransition {
    case _ -> CONNECTED =>
      Metrics.connectedPeers.increment()
      context.system.eventStream.publish(PeerConnected(self, remoteNodeId))
    case CONNECTED -> DISCONNECTED =>
      Metrics.connectedPeers.decrement()
      context.system.eventStream.publish(PeerDisconnected(self, remoteNodeId))
  }

  onTermination {
    case StopEvent(_, CONNECTED, _: ConnectedData) =>
      // the transition handler won't be fired if we go directly from CONNECTED to closed
      Metrics.connectedPeers.decrement()
      context.system.eventStream.publish(PeerDisconnected(self, remoteNodeId))
  }

  def createNewChannel(nodeParams: NodeParams, funder: Boolean, fundingAmount: Satoshi, origin_opt: Option[ActorRef]): (ActorRef, LocalParams) = {
    val defaultFinalScriptPubKey = Helpers.getFinalScriptPubKey(wallet, nodeParams.chainHash)
    val localParams = makeChannelParams(nodeParams, defaultFinalScriptPubKey, funder, fundingAmount)
    val channel = spawnChannel(nodeParams, origin_opt)
    (channel, localParams)
  }

  def spawnChannel(nodeParams: NodeParams, origin_opt: Option[ActorRef]): ActorRef = {
    val channel = context.actorOf(Channel.props(nodeParams, wallet, remoteNodeId, watcher, relayer, origin_opt))
    context watch channel
    channel
  }

  def stopPeer(): State = {
    log.info("removing peer from db")
    nodeParams.db.peers.removePeer(remoteNodeId)
    stop(FSM.Normal)
  }

  // TODO gets the first of the list, improve selection?
  def getPeerAddressFromNodeAnnouncement: Option[InetSocketAddress] = {
    nodeParams.db.network.getNode(remoteNodeId).flatMap(_.addresses.headOption.map(_.socketAddress))
  }

  // a failing channel won't be restarted, it should handle its states
  // connection are stateless
  override val supervisorStrategy: OneForOneStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Stop }

  initialize()


  // we use this to log raw messages coming in and out of the peer
  val logMsgIn = new BusLogging(context.system.eventStream, "", classOf[Peer.MessageLogs], context.system.asInstanceOf[ExtendedActorSystem].logFilter) with DiagnosticLoggingAdapter

  override def aroundReceive(receive: Actor.Receive, msg: Any): Unit = {
    msg match {
      case _: LightningMessage =>
        logMsgIn.mdc(mdc(msg))
        logMsgIn.info("IN msg={}", msg)
        logMsgIn.clearMDC()
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

  val RECONNECT_TIMER = "reconnect"

  // @formatter:off
  val MAX_FUNDING_TX_ALREADY_SPENT = 10
  val MAX_FUNDING_TX_NOT_FOUND = 10
  // @formatter:on

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, router: ActorRef, watcher: ActorRef, relayer: ActorRef, paymentHandler: ActorRef, wallet: EclairWallet): Props = Props(new Peer(nodeParams, remoteNodeId, router, watcher, relayer, paymentHandler, wallet))

  // @formatter:off

  // used to identify the logger for raw messages
  case class MessageLogs()

  sealed trait ChannelId { def id: ByteVector32 }
  case class TemporaryChannelId(id: ByteVector32) extends ChannelId
  case class FinalChannelId(id: ByteVector32) extends ChannelId

  sealed trait Data {
    def address_opt: Option[InetSocketAddress]
    def channels: Map[_ <: ChannelId, ActorRef] // will be overridden by Map[FinalChannelId, ActorRef] or Map[ChannelId, ActorRef]
  }
  case object Nothing extends Data { override def address_opt = None; override def channels = Map.empty }
  case class DisconnectedData(address_opt: Option[InetSocketAddress], channels: Map[FinalChannelId, ActorRef], nextReconnectionDelay: FiniteDuration = randomizeDelay(10 seconds)) extends Data
  case class ConnectedData(address_opt: Option[InetSocketAddress], peerConnection: ActorRef, localInit: wire.Init, remoteInit: wire.Init, channels: Map[ChannelId, ActorRef]) extends Data

  sealed trait State
  case object INSTANTIATING extends State
  case object DISCONNECTED extends State
  case object CONNECTED extends State

  case class Init(previousKnownAddress: Option[InetSocketAddress], storedChannels: Set[HasCommitments])
  case class Connect(nodeId: PublicKey, address_opt: Option[HostAndPort]) {
    def uri: Option[NodeURI] = address_opt.map(NodeURI(nodeId, _))
  }
  object Connect {
    def apply(uri: NodeURI): Connect = new Connect(uri.nodeId, Some(uri.address))
  }
  case object Reconnect
  case class Disconnect(nodeId: PublicKey)
  case class OpenChannel(remoteNodeId: PublicKey, fundingSatoshis: Satoshi, pushMsat: MilliSatoshi, fundingTxFeeratePerKw_opt: Option[Long], channelFlags: Option[Byte], timeout_opt: Option[Timeout]) {
    require(pushMsat <= fundingSatoshis, s"pushMsat must be less or equal to fundingSatoshis")
    require(fundingSatoshis >= 0.sat, s"fundingSatoshis must be positive")
    require(pushMsat >= 0.msat, s"pushMsat must be positive")
    fundingTxFeeratePerKw_opt.foreach(feeratePerKw => require(feeratePerKw >= MinimumFeeratePerKw, s"fee rate $feeratePerKw is below minimum $MinimumFeeratePerKw rate/kw"))
  }
  case object GetPeerInfo
  case class PeerInfo(nodeId: PublicKey, state: String, address: Option[InetSocketAddress], channels: Int)

  case class PeerRoutingMessage(peerConnection: ActorRef, remoteNodeId: PublicKey, message: LightningMessage)

  // @formatter:on

  object Metrics {
    val peers = Kamon.rangeSampler("peers.count").withoutTags()
    val connectedPeers = Kamon.rangeSampler("peers.connected.count").withoutTags()
    val channels = Kamon.rangeSampler("channels.count").withoutTags()
  }

  def makeChannelParams(nodeParams: NodeParams, defaultFinalScriptPubKey: ByteVector, isFunder: Boolean, fundingAmount: Satoshi): LocalParams = {
    // we make sure that funder and fundee key path end differently
    val fundingKeyPath = nodeParams.keyManager.newFundingKeyPath(isFunder)
    makeChannelParams(nodeParams, defaultFinalScriptPubKey, isFunder, fundingAmount, fundingKeyPath)
  }

  def makeChannelParams(nodeParams: NodeParams, defaultFinalScriptPubKey: ByteVector, isFunder: Boolean, fundingAmount: Satoshi, fundingKeyPath: DeterministicWallet.KeyPath): LocalParams = {
    LocalParams(
      nodeParams.nodeId,
      fundingKeyPath,
      dustLimit = nodeParams.dustLimit,
      maxHtlcValueInFlightMsat = nodeParams.maxHtlcValueInFlightMsat,
      channelReserve = (fundingAmount * nodeParams.reserveToFundingRatio).max(nodeParams.dustLimit), // BOLT #2: make sure that our reserve is above our dust limit
      htlcMinimum = nodeParams.htlcMinimum,
      toSelfDelay = nodeParams.toRemoteDelayBlocks, // we choose their delay
      maxAcceptedHtlcs = nodeParams.maxAcceptedHtlcs,
      defaultFinalScriptPubKey = defaultFinalScriptPubKey,
      isFunder = isFunder,
      features = nodeParams.features)
  }

  def hostAndPort2InetSocketAddress(hostAndPort: HostAndPort): InetSocketAddress = new InetSocketAddress(hostAndPort.getHost, hostAndPort.getPort)

  /**
   * This helps preventing peers reconnection loops due to synchronization of reconnection attempts.
   */
  def randomizeDelay(initialRandomReconnectDelay: FiniteDuration): FiniteDuration = Random.nextInt(initialRandomReconnectDelay.toMillis.toInt).millis.max(200 milliseconds)

  /**
   * Exponential backoff retry with a finite max
   */
  def nextReconnectionDelay(currentDelay: FiniteDuration, maxReconnectInterval: FiniteDuration): FiniteDuration = (2 * currentDelay).min(maxReconnectInterval)
}
