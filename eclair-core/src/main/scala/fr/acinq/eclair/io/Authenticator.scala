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

import akka.actor.{Actor, ActorRef, DiagnosticActorLogging, OneForOneStrategy, Props, Status, SupervisorStrategy, Terminated}
import akka.event.Logging.MDC
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.crypto.TransportHandler.HandshakeCompleted
import fr.acinq.eclair.io.Authenticator.{Authenticated, AuthenticationFailed, PendingAuth}
import fr.acinq.eclair.wire.LightningMessageCodecs
import fr.acinq.eclair.{Logs, NodeParams}
import kamon.Kamon

/**
  * The purpose of this class is to serve as a buffer for newly connection before they are authenticated
  * (meaning that a crypto handshake as successfully been completed).
  *
  * All incoming/outgoing connections are processed here, before being sent to the switchboard
  */
class Authenticator(nodeParams: NodeParams, router: ActorRef) extends Actor with DiagnosticActorLogging {

  override def receive: Receive = {
    case switchboard: ActorRef => context become ready(switchboard, Map.empty, 0)
  }

  def ready(switchboard: ActorRef, authenticating: Map[ActorRef, PendingAuth], counter: Long): Receive = {
    case pending@PendingAuth(connection, remoteNodeId_opt, address, _) =>
      log.debug(s"authenticating connection to ${address.getHostString}:${address.getPort} (pending=${authenticating.size} handlers=${context.children.size})")
      Kamon.counter("peers.connecting.count").withTag("state", "authenticating").increment()
      val transport = context.actorOf(TransportHandler.props(
        KeyPair(nodeParams.nodeId.value, nodeParams.privateKey.value),
        remoteNodeId_opt.map(_.value),
        connection = connection,
        codec = LightningMessageCodecs.meteredLightningMessageCodec),
        name = s"transport-$counter")
      context watch transport
      context become ready(switchboard, authenticating + (transport -> pending), counter + 1)

    case HandshakeCompleted(_, transport, remoteNodeId) if authenticating.contains(transport) =>
      val pendingAuth = authenticating(transport)
      import pendingAuth.{address, remoteNodeId_opt}
      val outgoing = remoteNodeId_opt.isDefined
      log.info(s"connection authenticated with $remoteNodeId@${address.getHostString}:${address.getPort} direction=${if (outgoing) "outgoing" else "incoming"}")
      Kamon.counter("peers.connecting.count").withTag("state", "authenticated").increment()
      val peerConnection = context.actorOf(PeerConnection.props(
        nodeParams = nodeParams,
        remoteNodeId = remoteNodeId,
        address = address,
        outgoing = outgoing,
        origin_opt = pendingAuth.origin_opt,
        transport = transport,
        authenticator = self,
        router = router
      ), name = s"peer-conn-$counter")
      switchboard ! Authenticated(peerConnection, remoteNodeId, address, remoteNodeId_opt.isDefined, pendingAuth.origin_opt)
      context become ready(switchboard, authenticating - transport, counter + 1)

    case Terminated(transport) =>
      authenticating.get(transport) match {
        case Some(pendingAuth) =>
          // we send an error only when we are the initiator
          pendingAuth.origin_opt.foreach(origin => origin ! Status.Failure(AuthenticationFailed(pendingAuth.address)))
          context become ready(switchboard, authenticating - transport, counter)
        case None => ()
      }

  }

  // we should not restart a failing transport-handler (NB: logging is handled in the transport)
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) { case _ => SupervisorStrategy.Stop }

  override def mdc(currentMessage: Any): MDC = {
    val remoteNodeId_opt = currentMessage match {
      case PendingAuth(_, remoteNodeId_opt, _, _) => remoteNodeId_opt
      case HandshakeCompleted(_, _, remoteNodeId) => Some(remoteNodeId)
      case _ => None
    }
    Logs.mdc(Some(LogCategory.CONNECTION), remoteNodeId_opt = remoteNodeId_opt)
  }
}

object Authenticator {

  def props(nodeParams: NodeParams, router: ActorRef): Props = Props(new Authenticator(nodeParams, router))

  // @formatter:off
  case class OutgoingConnection(remoteNodeId: PublicKey, address: InetSocketAddress)
  case class PendingAuth(connection: ActorRef, remoteNodeId_opt: Option[PublicKey], address: InetSocketAddress, origin_opt: Option[ActorRef])
  case class Authenticated(peerConnection: ActorRef, remoteNodeId: PublicKey, address: InetSocketAddress, outgoing: Boolean, origin_opt: Option[ActorRef])
  case class AuthenticationFailed(address: InetSocketAddress) extends RuntimeException(s"connection failed to $address")
  // @formatter:on

}
