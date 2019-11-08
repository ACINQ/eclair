package fr.acinq.eclair.recovery

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSelection, FSM, OneForOneStrategy, PoisonPill, Status, SupervisorStrategy, Terminated}
import akka.event.Logging.MDC
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Authenticator.{Authenticated, PendingAuth}
import fr.acinq.eclair.io._
import fr.acinq.eclair.recovery.RecoveryPeer._
import fr.acinq.eclair.recovery.RecoveryFSM.{ChannelFound, SendErrorToRemote}
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelReestablish, GossipTimestampFilter, LightningMessage, NodeAddress, Ping, Pong, RoutingMessage}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Features, Logs, NodeParams, SimpleSupervisor, wire}
import scodec.bits.ByteVector

import scala.compat.Platform
import scala.concurrent.duration._
import scala.util.Random

class RecoveryPeer(val nodeParams: NodeParams, remoteNodeId: PublicKey) extends FSMDiagnosticActorLogging[RecoveryPeer.State, RecoveryPeer.Data] {

  def recoveryFSM: ActorSelection = context.system.actorSelection(context.system / RecoveryFSM.actorName)

  val authenticator = context.system.actorOf(SimpleSupervisor.props(Authenticator.props(nodeParams), "authenticator", SupervisorStrategy.Resume))
  authenticator ! self // register this actor as the receiver of the authentication handshake

  startWith(INSTANTIATING, RecoveryPeer.Nothing())

  when(INSTANTIATING) {
    case Event(Init(previousKnownAddress, _), _) =>
      val channels = Map.empty[FinalChannelId, ActorRef]
      val firstNextReconnectionDelay = nodeParams.maxReconnectInterval.minus(Random.nextInt(nodeParams.maxReconnectInterval.toSeconds.toInt / 2).seconds)
      goto(DISCONNECTED) using DisconnectedData(previousKnownAddress, channels, firstNextReconnectionDelay) // when we restart, we will attempt to reconnect right away, but then we'll wait
  }

  when(DISCONNECTED) {
    case Event(p: PendingAuth, _) =>
      authenticator ! p
      stay

    case Event(RecoveryPeer.Connect(_, Some(address)), d: DisconnectedData) =>
      val inetAddress = Peer.hostAndPort2InetSocketAddress(address)
      context.actorOf(Client.props(nodeParams, self, inetAddress, remoteNodeId, origin_opt = Some(sender())))
      stay using d.copy(address_opt = Some(inetAddress))

    case Event(Authenticated(_, transport, remoteNodeId1, address, outgoing, origin_opt), d: DisconnectedData) =>
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
  }

  when(INITIALIZING) {
    case Event(remoteInit: wire.Init, d: InitializingData) =>
      d.transport ! TransportHandler.ReadAck(remoteInit)
      log.info(s"peer is using globalFeatures=${remoteInit.globalFeatures.toBin} and localFeatures=${remoteInit.localFeatures.toBin}")

      if (Features.areSupported(remoteInit.localFeatures)) {
        d.origin_opt.foreach(origin => origin ! "connected")
        val rebroadcastDelay = Random.nextInt(nodeParams.routerConf.routerBroadcastInterval.toSeconds.toInt).seconds
        log.info(s"rebroadcast will be delayed by $rebroadcastDelay")
        goto(CONNECTED) using ConnectedData(d.address_opt, d.transport, d.localInit, remoteInit, d.channels.map { case (k: ChannelId, v) => (k, v) }, rebroadcastDelay) forMax (30 seconds) // forMax will trigger a StateTimeout
      } else {
        log.warning(s"incompatible features, disconnecting")
        d.origin_opt.foreach(origin => origin ! Status.Failure(new RuntimeException("incompatible features")))
        d.transport ! PoisonPill
        stay
      }

    case Event(Authenticated(connection, _, _, _, _, origin_opt), _) =>
      // two connections in parallel
      origin_opt.foreach(origin => origin ! Status.Failure(new RuntimeException("there is another connection attempt in progress")))
      // we kill this one
      log.warning(s"killing parallel connection $connection")
      connection ! PoisonPill
      stay

    case Event(Terminated(actor), d: InitializingData) if actor == d.transport =>
      log.warning(s"lost connection to $remoteNodeId")
      goto(DISCONNECTED) using DisconnectedData(d.address_opt, d.channels)

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
    case Event(SendErrorToRemote(error), d: ConnectedData) =>
      log.info(s"recoveryFSM is sending an error to the peer")
      d.transport ! error
      stay

    case Event(msg: ChannelReestablish, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(msg)
      recoveryFSM ! ChannelFound(msg.channelId, msg)
      // when recovering we don't immediately reply channel_reestablish/error
      stay

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

    case Event(err@wire.Error(channelId, reason), d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(err)
      log.error(s"connection-level error! channelId=$channelId reason=${new String(reason.toArray)}")
      d.transport ! err
      stay

    case Event(msg: wire.OpenChannel, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(msg)
      log.info(s"peer sent us OpenChannel")
      stay

    case Event(msg: wire.HasChannelId, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(msg)
      log.info(s"received $msg from $remoteNodeId")
      stay

    case Event(msg: wire.HasTemporaryChannelId, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(msg)
      log.info(s"received $msg from $remoteNodeId")
      stay

    case Event(msg: wire.RoutingMessage, d: ConnectedData) =>
      log.info(s"peer sent us a $msg")
      // ACK and do nothing
      sender ! TransportHandler.ReadAck(msg)
      stay

    case Event(readAck: TransportHandler.ReadAck, d: ConnectedData) =>
      // we just forward acks from router to transport
      d.transport forward readAck
      stay

    case Event(Disconnect(nodeId), d: ConnectedData) if nodeId == remoteNodeId =>
      log.info(s"disconnecting")
      sender ! "disconnecting"
      d.transport ! PoisonPill
      stay

    case Event(Terminated(actor), d: ConnectedData) if actor == d.transport =>
      log.info(s"lost connection to $remoteNodeId")
      stop(FSM.Normal)

    case Event(h: Authenticated, d: ConnectedData) =>
      log.info(s"got new transport while already connected, switching to new transport")
      context unwatch d.transport
      d.transport ! PoisonPill
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
  }

  onTransition {
    case _ -> CONNECTED =>
      context.system.eventStream.publish(PeerConnected(self, remoteNodeId))
    case CONNECTED -> DISCONNECTED =>
      context.system.eventStream.publish(PeerDisconnected(self, remoteNodeId))
  }

  onTermination {
    case StopEvent(_, CONNECTED, d: ConnectedData) =>
      // the transition handler won't be fired if we go directly from CONNECTED to closed
      context.system.eventStream.publish(PeerDisconnected(self, remoteNodeId))
  }

  // a failing channel won't be restarted, it should handle its states
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Stop }

  initialize()

  override def mdc(currentMessage: Any): MDC = Logs.mdc(remoteNodeId_opt = Some(remoteNodeId))


}

object RecoveryPeer {

  val UNKNOWN_CHANNEL_MESSAGE = ByteVector.view("unknown channel".getBytes())

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
  case object SendPing
  case class PeerInfo(nodeId: PublicKey, state: String, address: Option[InetSocketAddress], channels: Int)

  case class PeerRoutingMessage(transport: ActorRef, remoteNodeId: PublicKey, message: RoutingMessage)

  case class DelayedRebroadcast(rebroadcast: Rebroadcast)

  sealed trait BadMessage
  case class InvalidSignature(r: RoutingMessage) extends BadMessage
  case class InvalidAnnouncement(c: ChannelAnnouncement) extends BadMessage
  case class ChannelClosed(c: ChannelAnnouncement) extends BadMessage

  case class Behavior(fundingTxAlreadySpentCount: Int = 0, fundingTxNotFoundCount: Int = 0, ignoreNetworkAnnouncement: Boolean = false)

  sealed trait ChannelId { def id: ByteVector32 }
  case class TemporaryChannelId(id: ByteVector32) extends ChannelId
  case class FinalChannelId(id: ByteVector32) extends ChannelId

}
