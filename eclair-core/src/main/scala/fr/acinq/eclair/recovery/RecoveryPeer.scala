package fr.acinq.eclair.recovery

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSelection, FSM, OneForOneStrategy, PoisonPill, Status, SupervisorStrategy, Terminated}
import akka.event.Logging.MDC
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Authenticator.{Authenticated, PendingAuth}
import fr.acinq.eclair.io._
import fr.acinq.eclair.recovery.RecoveryPeer._
import fr.acinq.eclair.recovery.RecoveryFSM.{ChannelFound, SendErrorToRemote}
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelReestablish, GossipTimestampFilter, LightningMessage, NodeAddress, Ping, Pong, RoutingMessage}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Features, Logs, NodeParams, SimpleSupervisor, wire}
import scodec.bits.ByteVector

class RecoveryPeer(val nodeParams: NodeParams, remoteNodeId: PublicKey) extends FSMDiagnosticActorLogging[RecoveryPeer.State, RecoveryPeer.Data] {

  def recoveryFSM: ActorSelection = context.system.actorSelection(context.system / RecoveryFSM.actorName)

  val authenticator = context.system.actorOf(SimpleSupervisor.props(Authenticator.props(nodeParams), "authenticator", SupervisorStrategy.Resume))
  authenticator ! self // register this actor as the receiver of the authentication handshake

  startWith(DISCONNECTED, DisconnectedData(address_opt = None))

  when(DISCONNECTED) {
    // sent by Client after establishing a TCP connection
    case Event(p: PendingAuth, _) =>
      authenticator ! p
      stay

    case Event(Peer.Connect(_, Some(address)), d: DisconnectedData) =>
      val inetAddress = Peer.hostAndPort2InetSocketAddress(address)
      context.actorOf(Client.props(nodeParams, self, inetAddress, remoteNodeId, origin_opt = Some(sender())))
      stay using d.copy(address_opt = Some(inetAddress))

    case Event(Authenticated(_, transport, remoteNodeId1, address, _, origin_opt), d: DisconnectedData) =>
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

      goto(INITIALIZING) using InitializingData(Some(address), transport, origin_opt, localInit)
  }

  when(INITIALIZING) {
    case Event(remoteInit: wire.Init, d: InitializingData) =>
      d.transport ! TransportHandler.ReadAck(remoteInit)
      log.info(s"peer is using globalFeatures=${remoteInit.globalFeatures.toBin} and localFeatures=${remoteInit.localFeatures.toBin}")
      if(!Features.areSupported(remoteInit.localFeatures)) {
        log.warning(s"peer has unsupported features, continuing anyway")
      }
      goto(CONNECTED) using ConnectedData(d.address_opt, d.transport, d.localInit, remoteInit)

    case Event(Terminated(actor), d: InitializingData) if actor == d.transport =>
      log.warning(s"lost connection to $remoteNodeId")
      goto(DISCONNECTED) using DisconnectedData(d.address_opt)

    case Event(Peer.Disconnect(nodeId), d: InitializingData) if nodeId == remoteNodeId =>
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


    case Event(err@wire.Error(channelId, reason), d: ConnectedData) if channelId == CHANNELID_ZERO =>
      d.transport ! TransportHandler.ReadAck(err)
      log.error(s"connection-level error! channelId=$channelId reason=${new String(reason.toArray)}")
      d.transport ! wire.Error(err.channelId, UNKNOWN_CHANNEL_MESSAGE)
      d.transport ! PoisonPill
      goto(DISCONNECTED) using DisconnectedData(None)

    case Event(msg: wire.HasChannelId, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(msg)
      log.info(s"received ${msg.getClass.getSimpleName} from $remoteNodeId")
      stay

    case Event(msg: wire.HasTemporaryChannelId, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(msg)
      log.info(s"received ${msg.getClass.getSimpleName} from $remoteNodeId")
      stay

    case Event(msg: wire.RoutingMessage, _) =>
      log.info(s"peer sent us a ${msg.getClass.getSimpleName}")
      // ACK and do nothing, we're in recovery mode
      sender ! TransportHandler.ReadAck(msg)
      stay

    case Event(readAck: TransportHandler.ReadAck, d: ConnectedData) =>
      // we just forward acks from router to transport
      d.transport forward readAck
      stay

    case Event(Peer.Disconnect(nodeId), d: ConnectedData) if nodeId == remoteNodeId =>
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
      goto(DISCONNECTED) using DisconnectedData(d.address_opt)

    case Event(unhandledMsg: LightningMessage, d: ConnectedData) =>
      // we ack unhandled messages because we don't want to block further reads on the connection
      d.transport ! TransportHandler.ReadAck(unhandledMsg)
      log.warning(s"acking unhandled message $unhandledMsg")
      stay
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

  val CHANNELID_ZERO = ByteVector32.Zeroes

  val UNKNOWN_CHANNEL_MESSAGE = ByteVector.view("unknown channel".getBytes())

  sealed trait Data
  case class DisconnectedData(address_opt: Option[InetSocketAddress]) extends Data
  case class InitializingData(address_opt: Option[InetSocketAddress], transport: ActorRef, origin_opt: Option[ActorRef], localInit: wire.Init) extends Data
  case class ConnectedData(address_opt: Option[InetSocketAddress], transport: ActorRef, localInit: wire.Init, remoteInit: wire.Init) extends Data

  sealed trait State
  case object DISCONNECTED extends State
  case object INITIALIZING extends State
  case object CONNECTED extends State
}
