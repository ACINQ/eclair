package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Status, SupervisorStrategy, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.crypto.TransportHandler.HandshakeCompleted
import fr.acinq.eclair.io.Authenticator.{Authenticated, AuthenticationFailed, PendingAuth}
import fr.acinq.eclair.wire.LightningMessageCodecs

/**
  * The purpose of this class is to serve as a buffer for newly connection before they are authenticated
  * (meaning that a crypto handshake as successfully been completed).
  *
  * All incoming/outgoing connections are processed here, before being sent to the switchboard
  */
class Authenticator(nodeParams: NodeParams) extends Actor with ActorLogging {

  override def receive: Receive = {
    case switchboard: ActorRef => context become ready(switchboard, Map.empty)
  }

  def ready(switchboard: ActorRef, authenticating: Map[ActorRef, PendingAuth]): Receive = {
    case pending@PendingAuth(connection, remoteNodeId_opt, address, _) =>
      log.debug(s"authenticating connection to ${address.getHostString}:${address.getPort} (pending=${authenticating.size} handlers=${context.children.size})")
      val transport = context.actorOf(TransportHandler.props(
        KeyPair(nodeParams.nodeId.toBin, nodeParams.privateKey.toBin),
        remoteNodeId_opt.map(_.toBin),
        connection = connection,
        codec = LightningMessageCodecs.lightningMessageCodec))
      context watch transport
      context become (ready(switchboard, authenticating + (transport -> pending)))

    case HandshakeCompleted(connection, transport, remoteNodeId) if authenticating.contains(transport) =>
      val pendingAuth = authenticating(transport)
      import pendingAuth.{address, remoteNodeId_opt}
      val outgoing = remoteNodeId_opt.isDefined
      log.info(s"connection authenticated with $remoteNodeId@${address.getHostString}:${address.getPort} direction=${if (outgoing) "outgoing" else "incoming"}")
      switchboard ! Authenticated(connection, transport, remoteNodeId, address, remoteNodeId_opt.isDefined, pendingAuth.origin_opt)
      context become ready(switchboard, authenticating - transport)

    case Terminated(transport) =>
      authenticating.get(transport) match {
        case Some(pendingAuth) =>
          // we send an error only when we are the initiator
          pendingAuth.origin_opt.map(origin => origin ! Status.Failure(AuthenticationFailed(pendingAuth.address)))
          context become ready(switchboard, authenticating - transport)
        case None => ()
      }

  }

  // we should not restart a failing transport-handler
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Stop }
}

object Authenticator {

  def props(nodeParams: NodeParams): Props = Props(new Authenticator(nodeParams))

  // @formatter:off
  case class OutgoingConnection(remoteNodeId: PublicKey, address: InetSocketAddress)
  case class PendingAuth(connection: ActorRef, remoteNodeId_opt: Option[PublicKey], address: InetSocketAddress, origin_opt: Option[ActorRef])
  case class Authenticated(connection: ActorRef, transport: ActorRef, remoteNodeId: PublicKey, address: InetSocketAddress, outgoing: Boolean, origin_opt: Option[ActorRef])
  case class AuthenticationFailed(address: InetSocketAddress) extends RuntimeException(s"connection failed to $address")
  // @formatter:on

}
