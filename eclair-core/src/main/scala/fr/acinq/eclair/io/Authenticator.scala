package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.crypto.TransportHandler.HandshakeCompleted
import fr.acinq.eclair.io.Authenticator.{Authenticated, AuthenticationFailed, PendingAuth}
import fr.acinq.eclair.wire.{LightningMessage, LightningMessageCodecs}

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
    case pending@PendingAuth(connection, _, outgoingConnection_opt) =>
      val transport = context.actorOf(Props(
        new TransportHandler[LightningMessage](
          KeyPair(nodeParams.privateKey.publicKey.toBin, nodeParams.privateKey.toBin),
          outgoingConnection_opt.map(_.remoteNodeId),
          connection = connection,
          codec = LightningMessageCodecs.lightningMessageCodec)))
      context watch transport
      context become (ready(switchboard, authenticating + (transport -> pending)))

    case HandshakeCompleted(connection, transport, remoteNodeId) if authenticating.contains(transport) =>
      val pendingAuth = authenticating(transport)
      log.info(s"connection authenticated with $remoteNodeId address=${pendingAuth.outgoingConnection_opt.map(_.address).getOrElse("(incoming)")}")
      switchboard ! Authenticated(connection, transport, remoteNodeId, pendingAuth.outgoingConnection_opt.map(_.address), pendingAuth.origin_opt)
      context become ready(switchboard, authenticating - transport)

    case Terminated(transport) =>
      authenticating.get(transport) match {
        case Some(pendingAuth) =>
          // we send an error only when we are the initiator
          pendingAuth.origin_opt.map(origin => pendingAuth.outgoingConnection_opt.map(_.address).map(address => origin ! Status.Failure(AuthenticationFailed(address))))
          context become ready(switchboard, authenticating - transport)
        case None => ()
      }

  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled message=$message")
}

object Authenticator {

  def props(nodeParams: NodeParams): Props = Props(new Authenticator(nodeParams))

  // @formatter:off
  case class OutgoingConnection(remoteNodeId: PublicKey, address: InetSocketAddress)
  case class PendingAuth(connection: ActorRef, origin_opt: Option[ActorRef], outgoingConnection_opt: Option[OutgoingConnection])
  case class Authenticated(connection: ActorRef, transport: ActorRef, remoteNodeId: PublicKey, address_opt: Option[InetSocketAddress], origin: Option[ActorRef])
  case class AuthenticationFailed(address: InetSocketAddress) extends RuntimeException(s"connection failed to $address")
  // @formatter:on

}
