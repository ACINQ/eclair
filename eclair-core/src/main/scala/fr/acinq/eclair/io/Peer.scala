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

import akka.actor.{ActorRef, FSM, OneForOneStrategy, PoisonPill, Props, Status, SupervisorStrategy, Terminated}
import akka.event.Logging.MDC
import akka.util.Timeout
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32, DeterministicWallet, Satoshi}
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{wire, _}
import kamon.Kamon
import scodec.Attempt
import scodec.bits.ByteVector

import scala.compat.Platform
import scala.concurrent.duration._
import scala.util.Random

/**
 * Created by PM on 26/08/2016.
 */
class Peer(val nodeParams: NodeParams, remoteNodeId: PublicKey, authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) extends FSMDiagnosticActorLogging[Peer.State, Peer.Data] {

  import Peer._

  startWith(INSTANTIATING, Nothing())

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
            context.actorOf(Client.props(nodeParams, authenticator, address, remoteNodeId, origin_opt = Some(sender())))
            stay using d.copy(address_opt = Some(address))
          }
      }

    case Event(Reconnect, d: DisconnectedData) =>
      d.address_opt.orElse(getPeerAddressFromNodeAnnouncement) match {
        case _ if d.channels.isEmpty => stay // no-op, no more channels with this peer
        case None => stay // no-op, we don't know any address to this peer and we won't try reconnecting again
        case Some(address) =>
          context.actorOf(Client.props(nodeParams, authenticator, address, remoteNodeId, origin_opt = None))
          log.info(s"reconnecting to $address (next reconnection in ${d.nextReconnectionDelay.toSeconds} seconds)")
          setTimer(RECONNECT_TIMER, Reconnect, d.nextReconnectionDelay, repeat = false)
          stay using d.copy(nextReconnectionDelay = nextReconnectionDelay(d.nextReconnectionDelay, nodeParams.maxReconnectInterval))
      }

    case Event(Authenticator.Authenticated(_, transport, remoteNodeId1, address, outgoing, origin_opt), d: DisconnectedData) =>
      require(remoteNodeId == remoteNodeId1, s"invalid nodeid: $remoteNodeId != $remoteNodeId1")
      log.debug(s"got authenticated connection to $remoteNodeId@${address.getHostString}:${address.getPort}")
      transport ! TransportHandler.Listener(self)
      context watch transport
      val localInit = nodeParams.overrideFeatures.get(remoteNodeId) match {
        case Some((gf, lf)) => wire.Init(globalFeatures = gf, localFeatures = lf)
        case None => wire.Init(globalFeatures = nodeParams.globalFeatures, localFeatures = nodeParams.localFeatures)
      }
      log.info(s"using globalFeatures=${localInit.globalFeatures.toBin} and localFeatures=${localInit.localFeatures.toBin}")
      transport ! localInit

      val address_opt = if (outgoing) {
        // we store the node address upon successful outgoing connection, so we can reconnect later
        // any previous address is overwritten
        NodeAddress.fromParts(address.getHostString, address.getPort).map(nodeAddress => nodeParams.db.peers.addOrUpdatePeer(remoteNodeId, nodeAddress))
        Some(address)
      } else None

      goto(INITIALIZING) using InitializingData(address_opt, transport, d.channels, origin_opt, localInit)

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

  when(INITIALIZING) {
    case Event(remoteInit: wire.Init, d: InitializingData) =>
      d.transport ! TransportHandler.ReadAck(remoteInit)

      log.info(s"peer is using globalFeatures=${remoteInit.globalFeatures.toBin} and localFeatures=${remoteInit.localFeatures.toBin}")

      if (Features.areSupported(remoteInit.localFeatures)) {
        d.origin_opt.foreach(origin => origin ! "connected")

        import Features._

        def hasLocalFeature(bit: Int) = Features.hasFeature(d.localInit.localFeatures, bit)

        def hasRemoteFeature(bit: Int) = Features.hasFeature(remoteInit.localFeatures, bit)

        val canUseChannelRangeQueries = (hasLocalFeature(CHANNEL_RANGE_QUERIES_BIT_OPTIONAL) || hasLocalFeature(CHANNEL_RANGE_QUERIES_BIT_MANDATORY)) && (hasRemoteFeature(CHANNEL_RANGE_QUERIES_BIT_OPTIONAL) || hasRemoteFeature(CHANNEL_RANGE_QUERIES_BIT_MANDATORY))

        val canUseChannelRangeQueriesEx = (hasLocalFeature(CHANNEL_RANGE_QUERIES_EX_BIT_OPTIONAL) || hasLocalFeature(CHANNEL_RANGE_QUERIES_EX_BIT_MANDATORY)) && (hasRemoteFeature(CHANNEL_RANGE_QUERIES_EX_BIT_OPTIONAL) || hasRemoteFeature(CHANNEL_RANGE_QUERIES_EX_BIT_MANDATORY))

        if (canUseChannelRangeQueries || canUseChannelRangeQueriesEx) {
          // if they support channel queries we don't send routing info yet, if they want it they will query us
          // we will query them, using extended queries if supported
          val flags_opt = if (canUseChannelRangeQueriesEx) Some(QueryChannelRangeTlv.QueryFlags(QueryChannelRangeTlv.QueryFlags.WANT_ALL)) else None
          if (nodeParams.syncWhitelist.isEmpty || nodeParams.syncWhitelist.contains(remoteNodeId)) {
            log.info(s"sending sync channel range query with flags_opt=$flags_opt")
            router ! SendChannelQuery(remoteNodeId, d.transport, flags_opt = flags_opt)
          } else {
            log.info("not syncing with this peer")
          }
        } else if (hasRemoteFeature(INITIAL_ROUTING_SYNC_BIT_OPTIONAL)) {
          // "old" nodes, do as before
          log.info("peer requested a full routing table dump")
          router ! GetRoutingState
        }

        // let's bring existing/requested channels online
        d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_RECONNECTED(d.transport, d.localInit, remoteInit)) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
        // we will delay all rebroadcasts with this value in order to prevent herd effects (each peer has a different delay)
        val rebroadcastDelay = Random.nextInt(nodeParams.routerConf.routerBroadcastInterval.toSeconds.toInt).seconds
        log.info(s"rebroadcast will be delayed by $rebroadcastDelay")
        goto(CONNECTED) using ConnectedData(d.address_opt, d.transport, d.localInit, remoteInit, d.channels.map { case (k: ChannelId, v) => (k, v) }, rebroadcastDelay) forMax (30 seconds) // forMax will trigger a StateTimeout
      } else {
        log.warning(s"incompatible features, disconnecting")
        d.origin_opt.foreach(origin => origin ! Status.Failure(new RuntimeException("incompatible features")))
        d.transport ! PoisonPill
        stay
      }

    case Event(Authenticator.Authenticated(connection, _, _, _, _, origin_opt), _) =>
      // two connections in parallel
      origin_opt.foreach(origin => origin ! Status.Failure(new RuntimeException("there is another connection attempt in progress")))
      // we kill this one
      log.warning(s"killing parallel connection $connection")
      connection ! PoisonPill
      stay

    case Event(Terminated(actor), d: InitializingData) if actor == d.transport =>
      log.warning(s"lost connection to $remoteNodeId")
      goto(DISCONNECTED) using DisconnectedData(d.address_opt, d.channels)

    case Event(Terminated(actor), d: InitializingData) if d.channels.exists(_._2 == actor) =>
      val h = d.channels.filter(_._2 == actor).keys
      log.info(s"channel closed: channelId=${h.mkString("/")}")
      val channels1 = d.channels -- h
      if (channels1.isEmpty) {
        // we have no existing channels, we can forget about this peer
        stopPeer()
      } else {
        stay using d.copy(channels = channels1)
      }

    case Event(Disconnect(nodeId), d: InitializingData) if nodeId == remoteNodeId =>
      log.info("disconnecting")
      sender ! "disconnecting"
      d.transport ! PoisonPill
      stay

    case Event(unhandledMsg: LightningMessage, d: InitializingData) =>
      // we ack unhandled messages because we don't want to block further reads on the connection
      d.transport ! TransportHandler.ReadAck(unhandledMsg)
      log.warning(s"acking unhandled message $unhandledMsg")
      stay
  }

  when(CONNECTED) {
    case Event(StateTimeout, _: ConnectedData) =>
      // the first ping is sent after the connection has been quiet for a while
      // we don't want to send pings right after connection, because peer will be syncing and may not be able to
      // answer to our ping quickly enough, which will make us close the connection
      log.debug(s"no messages sent/received for a while, start sending pings")
      self ! SendPing
      setStateTimeout(CONNECTED, None) // cancels the state timeout (it will be reset with forMax)
      stay

    case Event(SendPing, d: ConnectedData) =>
      if (d.expectedPong_opt.isEmpty) {
        // no need to use secure random here
        val pingSize = Random.nextInt(1000)
        val pongSize = Random.nextInt(1000)
        val ping = wire.Ping(pongSize, ByteVector.fill(pingSize)(0))
        setTimer(PingTimeout.toString, PingTimeout(ping), nodeParams.pingTimeout, repeat = false)
        d.transport ! ping
        stay using d.copy(expectedPong_opt = Some(ExpectedPong(ping)))
      } else {
        log.warning(s"can't send ping, already have one in flight")
        stay
      }

    case Event(PingTimeout(ping), d: ConnectedData) =>
      if (nodeParams.pingDisconnect) {
        log.warning(s"no response to ping=$ping, closing connection")
        d.transport ! PoisonPill
      } else {
        log.warning(s"no response to ping=$ping (ignored)")
      }
      stay

    case Event(ping@wire.Ping(pongLength, _), d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(ping)
      if (pongLength <= 65532) {
        // see BOLT 1: we reply only if requested pong length is acceptable
        d.transport ! wire.Pong(ByteVector.fill(pongLength)(0.toByte))
      } else {
        log.warning(s"ignoring invalid ping with pongLength=${ping.pongLength}")
      }
      stay

    case Event(pong@wire.Pong(data), d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(pong)
      d.expectedPong_opt match {
        case Some(ExpectedPong(ping, timestamp)) if ping.pongLength == data.length =>
          // we use the pong size to correlate between pings and pongs
          val latency = Platform.currentTime - timestamp
          log.debug(s"received pong with latency=$latency")
          cancelTimer(PingTimeout.toString())
          // pings are sent periodically with some randomization
          val nextDelay = nodeParams.pingInterval + Random.nextInt(10).seconds
          setTimer(SendPing.toString, SendPing, nextDelay, repeat = false)
        case None =>
          log.debug(s"received unexpected pong with size=${data.length}")
      }
      stay using d.copy(expectedPong_opt = None)

    case Event(err@wire.Error(channelId, reason), d: ConnectedData) if channelId == CHANNELID_ZERO =>
      d.transport ! TransportHandler.ReadAck(err)
      log.error(s"connection-level error, failing all channels! reason=${new String(reason.toArray)}")
      d.channels.values.toSet[ActorRef].foreach(_ forward err) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
      d.transport ! PoisonPill
      stay

    case Event(err: wire.Error, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(err)
      // error messages are a bit special because they can contain either temporaryChannelId or channelId (see BOLT 1)
      d.channels.get(FinalChannelId(err.channelId)).orElse(d.channels.get(TemporaryChannelId(err.channelId))) match {
        case Some(channel) => channel forward err
        case None => d.transport ! wire.Error(err.channelId, UNKNOWN_CHANNEL_MESSAGE)
      }
      stay

    case Event(c: Peer.OpenChannel, d: ConnectedData) =>
      val (channel, localParams) = createNewChannel(nodeParams, funder = true, c.fundingSatoshis, origin_opt = Some(sender))
      c.timeout_opt.map(openTimeout => context.system.scheduler.scheduleOnce(openTimeout.duration, channel, Channel.TickChannelOpenTimeout)(context.dispatcher))
      val temporaryChannelId = randomBytes32
      val channelFeeratePerKw = nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.commitmentBlockTarget)
      val fundingTxFeeratePerKw = c.fundingTxFeeratePerKw_opt.getOrElse(nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget))
      log.info(s"requesting a new channel with fundingSatoshis=${c.fundingSatoshis}, pushMsat=${c.pushMsat} and fundingFeeratePerByte=${c.fundingTxFeeratePerKw_opt} temporaryChannelId=$temporaryChannelId localParams=$localParams")
      channel ! INPUT_INIT_FUNDER(temporaryChannelId, c.fundingSatoshis, c.pushMsat, channelFeeratePerKw, fundingTxFeeratePerKw, localParams, d.transport, d.remoteInit, c.channelFlags.getOrElse(nodeParams.channelFlags), ChannelVersion.STANDARD)
      stay using d.copy(channels = d.channels + (TemporaryChannelId(temporaryChannelId) -> channel))

    case Event(msg: wire.OpenChannel, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(msg)
      d.channels.get(TemporaryChannelId(msg.temporaryChannelId)) match {
        case None =>
          val (channel, localParams) = createNewChannel(nodeParams, funder = false, fundingAmount = msg.fundingSatoshis, origin_opt = None)
          val temporaryChannelId = msg.temporaryChannelId
          log.info(s"accepting a new channel to $remoteNodeId temporaryChannelId=$temporaryChannelId localParams=$localParams")
          channel ! INPUT_INIT_FUNDEE(temporaryChannelId, localParams, d.transport, d.remoteInit)
          channel ! msg
          stay using d.copy(channels = d.channels + (TemporaryChannelId(temporaryChannelId) -> channel))
        case Some(_) =>
          log.warning(s"ignoring open_channel with duplicate temporaryChannelId=${msg.temporaryChannelId}")
          stay
      }

    case Event(msg: wire.HasChannelId, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(msg)
      d.channels.get(FinalChannelId(msg.channelId)) match {
        case Some(channel) => channel forward msg
        case None => d.transport ! wire.Error(msg.channelId, UNKNOWN_CHANNEL_MESSAGE)
      }
      stay

    case Event(msg: wire.HasTemporaryChannelId, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(msg)
      d.channels.get(TemporaryChannelId(msg.temporaryChannelId)) match {
        case Some(channel) => channel forward msg
        case None => d.transport ! wire.Error(msg.temporaryChannelId, UNKNOWN_CHANNEL_MESSAGE)
      }
      stay

    case Event(ChannelIdAssigned(channel, _, temporaryChannelId, channelId), d: ConnectedData) if d.channels.contains(TemporaryChannelId(temporaryChannelId)) =>
      log.info(s"channel id switch: previousId=$temporaryChannelId nextId=$channelId")
      // NB: we keep the temporary channel id because the switch is not always acknowledged at this point (see https://github.com/lightningnetwork/lightning-rfc/pull/151)
      // we won't clean it up, but we won't remember the temporary id on channel termination
      stay using d.copy(channels = d.channels + (FinalChannelId(channelId) -> channel))

    case Event(RoutingState(channels, nodes), d: ConnectedData) =>
      // let's send the messages
      def send(announcements: Iterable[_ <: LightningMessage]) = announcements.foldLeft(0) {
        case (c, ann) =>
          d.transport ! ann
          c + 1
      }

      log.info(s"sending all announcements to {}", remoteNodeId)
      val channelsSent = send(channels.map(_.ann))
      val nodesSent = send(nodes)
      val updatesSent = send(channels.flatMap(c => c.update_1_opt.toSeq ++ c.update_2_opt.toSeq))
      log.info(s"sent all announcements to {}: channels={} updates={} nodes={}", remoteNodeId, channelsSent, updatesSent, nodesSent)
      stay

    case Event(rebroadcast: Rebroadcast, d: ConnectedData) =>
      context.system.scheduler.scheduleOnce(d.rebroadcastDelay, self, DelayedRebroadcast(rebroadcast))(context.dispatcher)
      stay

    case Event(DelayedRebroadcast(rebroadcast), d: ConnectedData) =>

      /**
       * Send and count in a single iteration
       */
      def sendAndCount(msgs: Map[_ <: RoutingMessage, Set[ActorRef]]): Int = msgs.foldLeft(0) {
        case (count, (_, origins)) if origins.contains(self) =>
          // the announcement came from this peer, we don't send it back
          count
        case (count, (msg, _)) if !timestampInRange(msg, d.gossipTimestampFilter) =>
          // the peer has set up a filter on timestamp and this message is out of range
          count
        case (count, (msg, _)) =>
          d.transport ! msg
          count + 1
      }

      val channelsSent = sendAndCount(rebroadcast.channels)
      val updatesSent = sendAndCount(rebroadcast.updates)
      val nodesSent = sendAndCount(rebroadcast.nodes)

      if (channelsSent > 0 || updatesSent > 0 || nodesSent > 0) {
        log.info(s"sent announcements to {}: channels={} updates={} nodes={}", remoteNodeId, channelsSent, updatesSent, nodesSent)
      }
      stay

    case Event(msg: GossipTimestampFilter, d: ConnectedData) =>
      // special case: time range filters are peer specific and must not be sent to the router
      sender ! TransportHandler.ReadAck(msg)
      if (msg.chainHash != nodeParams.chainHash) {
        log.warning("received gossip_timestamp_range message for chain {}, we're on {}", msg.chainHash, nodeParams.chainHash)
        stay
      } else {
        log.info(s"setting up gossipTimestampFilter=$msg")
        // update their timestamp filter
        stay using d.copy(gossipTimestampFilter = Some(msg))
      }

    case Event(msg: wire.RoutingMessage, d: ConnectedData) =>
      msg match {
        case _: ChannelAnnouncement | _: ChannelUpdate | _: NodeAnnouncement if d.behavior.ignoreNetworkAnnouncement =>
          // this peer is currently under embargo!
          sender ! TransportHandler.ReadAck(msg)
        case _ =>
          // Note: we don't ack messages here because we don't want them to be stacked in the router's mailbox
          router ! PeerRoutingMessage(d.transport, remoteNodeId, msg)
      }
      stay

    case Event(readAck: TransportHandler.ReadAck, d: ConnectedData) =>
      // we just forward acks from router to transport
      d.transport forward readAck
      stay

    case Event(badMessage: BadMessage, d: ConnectedData) =>
      val behavior1 = badMessage match {
        case InvalidSignature(r) =>
          val bin: String = LightningMessageCodecs.lightningMessageCodec.encode(r) match {
            case Attempt.Successful(b) => b.toHex
            case _ => "unknown"
          }
          log.error(s"peer sent us a routing message with invalid sig: r=$r bin=$bin")
          // for now we just return an error, maybe ban the peer in the future?
          // TODO: this doesn't actually disconnect the peer, once we introduce peer banning we should actively disconnect
          d.transport ! Error(CHANNELID_ZERO, ByteVector.view(s"bad announcement sig! bin=$bin".getBytes()))
          d.behavior
        case InvalidAnnouncement(c) =>
          // they seem to be sending us fake announcements?
          log.error(s"couldn't find funding tx with valid scripts for shortChannelId=${c.shortChannelId}")
          // for now we just return an error, maybe ban the peer in the future?
          // TODO: this doesn't actually disconnect the peer, once we introduce peer banning we should actively disconnect
          d.transport ! Error(CHANNELID_ZERO, ByteVector.view(s"couldn't verify channel! shortChannelId=${c.shortChannelId}".getBytes()))
          d.behavior
        case ChannelClosed(_) =>
          if (d.behavior.ignoreNetworkAnnouncement) {
            // we already are ignoring announcements, we may have additional notifications for announcements that were received right before our ban
            d.behavior.copy(fundingTxAlreadySpentCount = d.behavior.fundingTxAlreadySpentCount + 1)
          } else if (d.behavior.fundingTxAlreadySpentCount < MAX_FUNDING_TX_ALREADY_SPENT) {
            d.behavior.copy(fundingTxAlreadySpentCount = d.behavior.fundingTxAlreadySpentCount + 1)
          } else {
            log.warning(s"peer sent us too many channel announcements with funding tx already spent (count=${d.behavior.fundingTxAlreadySpentCount + 1}), ignoring network announcements for $IGNORE_NETWORK_ANNOUNCEMENTS_PERIOD")
            setTimer(ResumeAnnouncements.toString, ResumeAnnouncements, IGNORE_NETWORK_ANNOUNCEMENTS_PERIOD, repeat = false)
            d.behavior.copy(fundingTxAlreadySpentCount = d.behavior.fundingTxAlreadySpentCount + 1, ignoreNetworkAnnouncement = true)
          }
      }
      stay using d.copy(behavior = behavior1)

    case Event(ResumeAnnouncements, d: ConnectedData) =>
      log.info(s"resuming processing of network announcements for peer")
      stay using d.copy(behavior = d.behavior.copy(fundingTxAlreadySpentCount = 0, ignoreNetworkAnnouncement = false))

    case Event(Disconnect(nodeId), d: ConnectedData) if nodeId == remoteNodeId =>
      log.info(s"disconnecting")
      sender ! "disconnecting"
      d.transport ! PoisonPill
      stay

    case Event(Terminated(actor), d: ConnectedData) if actor == d.transport =>
      log.info(s"lost connection to $remoteNodeId")
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
        d.transport ! PoisonPill
      }
      stay using d.copy(channels = d.channels -- channelIds)

    case Event(h: Authenticator.Authenticated, d: ConnectedData) =>
      log.info(s"got new transport while already connected, switching to new transport")
      context unwatch d.transport
      d.transport ! PoisonPill
      d.channels.values.toSet[ActorRef].foreach(_ ! INPUT_DISCONNECTED) // we deduplicate with toSet because there might be two entries per channel (tmp id and final id)
      self ! h
      goto(DISCONNECTED) using DisconnectedData(d.address_opt, d.channels.collect { case (k: FinalChannelId, v) => (k, v) })

    case Event(unhandledMsg: LightningMessage, d: ConnectedData) =>
      // we ack unhandled messages because we don't want to block further reads on the connection
      d.transport ! TransportHandler.ReadAck(unhandledMsg)
      log.warning(s"acking unhandled message $unhandledMsg")
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

    case Event(_: Rebroadcast, _) => stay // ignored

    case Event(_: DelayedRebroadcast, _) => stay // ignored

    case Event(_: RoutingState, _) => stay // ignored

    case Event(_: TransportHandler.ReadAck, _) => stay // ignored

    case Event(Peer.Reconnect, _) => stay // we got connected in the meantime

    case Event(SendPing, _) => stay // we got disconnected in the meantime

    case Event(_: Pong, _) => stay // we got disconnected before receiving the pong

    case Event(_: PingTimeout, _) => stay // we got disconnected after sending a ping

    case Event(_: BadMessage, _) => stay // we got disconnected while syncing
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
    case _ -> DISCONNECTED if nodeParams.autoReconnect => setTimer(RECONNECT_TIMER, Reconnect, Random.nextInt(nodeParams.initialRandomReconnectDelay.toMillis.toInt).millis, repeat = false) // we add some randomization to not have peers reconnect to each other exactly at the same time
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
    case StopEvent(_, CONNECTED, d: ConnectedData) =>
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
    val channel = context.actorOf(Channel.props(nodeParams, wallet, remoteNodeId, watcher, router, relayer, origin_opt))
    context watch channel
    channel
  }

  def stopPeer() = {
    log.info("removing peer from db")
    nodeParams.db.peers.removePeer(remoteNodeId)
    stop(FSM.Normal)
  }

  // TODO gets the first of the list, improve selection?
  def getPeerAddressFromNodeAnnouncement: Option[InetSocketAddress] = {
    nodeParams.db.network.getNode(remoteNodeId).flatMap(_.addresses.headOption.map(_.socketAddress))
  }

  // a failing channel won't be restarted, it should handle its states
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Stop }

  initialize()

  override def mdc(currentMessage: Any): MDC = Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))
}

object Peer {

  val CHANNELID_ZERO = ByteVector32.Zeroes

  val UNKNOWN_CHANNEL_MESSAGE = ByteVector.view("unknown channel".getBytes())

  val RECONNECT_TIMER = "reconnect"

  val MAX_FUNDING_TX_ALREADY_SPENT = 10

  val MAX_FUNDING_TX_NOT_FOUND = 10

  val IGNORE_NETWORK_ANNOUNCEMENTS_PERIOD = 5 minutes

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) = Props(new Peer(nodeParams, remoteNodeId, authenticator, watcher, router, relayer, wallet))

  // @formatter:off

  sealed trait ChannelId { def id: ByteVector32 }
  case class TemporaryChannelId(id: ByteVector32) extends ChannelId
  case class FinalChannelId(id: ByteVector32) extends ChannelId

  sealed trait Data {
    def address_opt: Option[InetSocketAddress]
    def channels: Map[_ <: ChannelId, ActorRef] // will be overridden by Map[FinalChannelId, ActorRef] or Map[ChannelId, ActorRef]
  }
  case class Nothing() extends Data { override def address_opt = None; override def channels = Map.empty }
  case class DisconnectedData(address_opt: Option[InetSocketAddress], channels: Map[FinalChannelId, ActorRef], nextReconnectionDelay: FiniteDuration = 10 seconds) extends Data
  case class InitializingData(address_opt: Option[InetSocketAddress], transport: ActorRef, channels: Map[FinalChannelId, ActorRef], origin_opt: Option[ActorRef], localInit: wire.Init) extends Data
  case class ConnectedData(address_opt: Option[InetSocketAddress], transport: ActorRef, localInit: wire.Init, remoteInit: wire.Init, channels: Map[ChannelId, ActorRef], rebroadcastDelay: FiniteDuration, gossipTimestampFilter: Option[GossipTimestampFilter] = None, behavior: Behavior = Behavior(), expectedPong_opt: Option[ExpectedPong] = None) extends Data
  case class ExpectedPong(ping: Ping, timestamp: Long = Platform.currentTime)
  case class PingTimeout(ping: Ping)

  sealed trait State
  case object INSTANTIATING extends State
  case object DISCONNECTED extends State
  case object INITIALIZING extends State
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
  case object ResumeAnnouncements
  case class OpenChannel(remoteNodeId: PublicKey, fundingSatoshis: Satoshi, pushMsat: MilliSatoshi, fundingTxFeeratePerKw_opt: Option[Long], channelFlags: Option[Byte], timeout_opt: Option[Timeout]) {
    require(fundingSatoshis < Channel.MAX_FUNDING, s"fundingSatoshis must be less than ${Channel.MAX_FUNDING}")
    require(pushMsat <= fundingSatoshis, s"pushMsat must be less or equal to fundingSatoshis")
    require(fundingSatoshis >= 0.sat, s"fundingSatoshis must be positive")
    require(pushMsat >= 0.msat, s"pushMsat must be positive")
    fundingTxFeeratePerKw_opt.foreach(feeratePerKw => require(feeratePerKw >= MinimumFeeratePerKw, s"fee rate $feeratePerKw is below minimum $MinimumFeeratePerKw rate/kw"))
  }
  case object GetPeerInfo
  case object SendPing
  case class PeerInfo(nodeId: PublicKey, state: String, address: Option[InetSocketAddress], channels: Int)

  case class PeerRoutingMessage(transport: ActorRef, remoteNodeId: PublicKey, message: RoutingMessage)

  case class DelayedRebroadcast(rebroadcast: Rebroadcast)

  sealed trait BadMessage
  case class InvalidSignature(r: RoutingMessage) extends BadMessage
  case class InvalidAnnouncement(c: ChannelAnnouncement) extends BadMessage
  case class ChannelClosed(c: ChannelAnnouncement) extends BadMessage

  case class Behavior(fundingTxAlreadySpentCount: Int = 0, fundingTxNotFoundCount: Int = 0, ignoreNetworkAnnouncement: Boolean = false)

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
      globalFeatures = nodeParams.globalFeatures,
      localFeatures = nodeParams.localFeatures)
  }

  /**
   * Peer may want to filter announcements based on timestamp
   *
   * @param gossipTimestampFilter_opt optional gossip timestamp range
   * @return
   *           - true if there is a filter and msg has no timestamp, or has one that matches the filter
   *           - false otherwise
   */
  def timestampInRange(msg: RoutingMessage, gossipTimestampFilter_opt: Option[GossipTimestampFilter]): Boolean = {
    // check if this message has a timestamp that matches our timestamp filter
    (msg, gossipTimestampFilter_opt) match {
      case (_, None) => false // BOLT 7: A node which wants any gossip messages would have to send this, otherwise [...] no gossip messages would be received.
      case (hasTs: HasTimestamp, Some(GossipTimestampFilter(_, firstTimestamp, timestampRange))) => hasTs.timestamp >= firstTimestamp && hasTs.timestamp <= firstTimestamp + timestampRange
      case _ => true // if there is a filter and message doesn't have a timestamp (e.g. channel_announcement), then we send it
    }
  }

  def hostAndPort2InetSocketAddress(hostAndPort: HostAndPort): InetSocketAddress = new InetSocketAddress(hostAndPort.getHost, hostAndPort.getPort)

  /**
   * Exponential backoff retry with a finite max
   */
  def nextReconnectionDelay(currentDelay: FiniteDuration, maxReconnectInterval: FiniteDuration): FiniteDuration = (2 * currentDelay).min(maxReconnectInterval)
}
