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

import akka.Done
import akka.actor.{Actor, ActorRef, DiagnosticActorLogging, Props, Terminated}
import akka.event.Logging.MDC
import akka.io.Tcp.SO.KeepAlive
import akka.io.{IO, Tcp}
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.io.Monitoring.Metrics
import fr.acinq.eclair.wire.protocol.IPAddress
import fr.acinq.eclair.{Logs, TimestampMilli}

import java.net.InetSocketAddress
import scala.concurrent.Promise

/**
 * Created by PM on 27/10/2015.
 */
class Server(keyPair: KeyPair, peerConnectionConf: PeerConnection.Conf, switchboard: ActorRef, router: ActorRef, address: InetSocketAddress, bound: Option[Promise[Done]] = None) extends Actor with DiagnosticActorLogging {

  import Server._
  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, address, options = KeepAlive(true) :: Nil, pullMode = true)

  override def receive: Receive = {
    case Bound(localAddress) =>
      bound.map(_.success(Done))
      log.info(s"bound on $localAddress")
      // Accept connections one by one.
      sender() ! ResumeAccepting(batchSize = 1)
      Metrics.IncomingConnectionsPending.withoutTags().update(0)
      context.become(listening(sender(), Map.empty))

    case CommandFailed(_: Bind) =>
      bound.map(_.failure(new RuntimeException("TCP bind failed")))
      context stop self
  }

  /**
   * @param pending incoming connections that haven't completed the BOLT 8 handshake yet, with the time at which we
   *                accepted them. Peers that connect without ever authenticating consume resources (memory, file
   *                descriptors and CPU), so we bound how many of them we're willing to keep around.
   */
  def listening(listener: ActorRef, pending: Map[ActorRef, TimestampMilli]): Receive = {
    case Connected(remote, _) =>
      val connection = sender()
      // NB: we check whether we have capacity *before* creating the peer connection: the rejection path must be as
      // cheap as possible, since it is exercised exactly when we're being flooded.
      checkLimits(pending) match {
        case Admission.Accept =>
          val pending1 = accept(connection, remote, pending)
          listener ! ResumeAccepting(batchSize = 1)
          Metrics.IncomingConnectionsPending.withoutTags().update(pending1.size)
          context.become(listening(listener, pending1))
        case Admission.Evict(evicted) =>
          // We make room for that new connection by dropping the oldest one that is pending authentication.
          Metrics.IncomingConnectionsEvicted.withoutTags().increment()
          log.debug("dropping pending connection to make room for incoming connection from {}", remote)
          evicted ! PeerConnection.Kill(PeerConnection.KillReason.TooManyPendingConnections)
          // We're above our limit: instead of sending ResumeAccepting immediately, we let the kernel backlog absorb
          // (and discard) the excess connection attempts. Meanwhile, the pending connections may also complete the
          // authentication handshake, which lets us free up resources before we start accepting new connections.
          context.system.scheduler.scheduleOnce(peerConnectionConf.pendingConnectionAcceptDelay, self, AcceptNext)(context.dispatcher)
          // NB: we remove the evicted connection immediately instead of waiting for its Terminated event, otherwise we
          // could select it again as eviction candidate for the next incoming connection.
          val pending1 = accept(connection, remote, pending - evicted)
          Metrics.IncomingConnectionsPending.withoutTags().update(pending1.size)
          context.become(listening(listener, pending1))
        case Admission.Reject =>
          // Every pending connection is still within its grace period: we protect them and reject the new connection.
          Metrics.IncomingConnectionsRejected.withoutTags().increment()
          log.debug("rejecting incoming connection from {}: too many pending connections", remote)
          connection ! Abort
          listener ! ResumeAccepting(batchSize = 1)
      }

    case AcceptNext => listener ! ResumeAccepting(batchSize = 1)

    case PeerConnection.Authenticated(peerConnection, _, _) =>
      // This connection isn't pending authentication anymore: we stop watching it and free up its slot.
      context.unwatch(peerConnection)
      val pending1 = pending - peerConnection
      Metrics.IncomingConnectionsPending.withoutTags().update(pending1.size)
      context.become(listening(listener, pending1))

    case Terminated(peerConnection) =>
      // The connection died before completing the BOLT 8 handshake (timeout, disconnection, or our own eviction).
      val pending1 = pending - peerConnection
      Metrics.IncomingConnectionsPending.withoutTags().update(pending1.size)
      context.become(listening(listener, pending1))

      // Confirmation that a connection we didn't have capacity for was indeed aborted: nothing to do.
    case _: ConnectionClosed => ()

    case GetPendingConnections(replyTo) => replyTo ! PendingConnections(pending.keySet)
  }

  private def accept(connection: ActorRef, remote: InetSocketAddress, pending: Map[ActorRef, TimestampMilli]): Map[ActorRef, TimestampMilli] = {
    log.info("connected to {}", remote)
    val peerConnection = context.actorOf(PeerConnection.props(
      keyPair = keyPair,
      conf = peerConnectionConf,
      switchboard = switchboard,
      router = router
    ))
    peerConnection ! PeerConnection.PendingAuth(connection, remoteNodeId_opt = None, address = IPAddress(remote.getAddress, remote.getPort), origin_opt = None, isPersistent = true, authTracker_opt = Some(self))
    context.watch(peerConnection)
    pending + (peerConnection -> TimestampMilli.now())
  }

  private def checkLimits(pending: Map[ActorRef, TimestampMilli]): Admission = {
    if (peerConnectionConf.maxPendingIncomingConnections == 0 || pending.size < peerConnectionConf.maxPendingIncomingConnections) {
      Admission.Accept
    } else {
      // NB: pending is guaranteed to be non-empty (otherwise we would be in the case above).
      val (oldest, acceptedAt) = pending.minBy(_._2)
      // When we've reached our maximum capacity, we don't immediately evict the oldest pending connection, otherwise
      // attackers could just spam us with new connections and we would evict pending honest connections before they
      // have a chance to complete the authentication handshake. This guarantees that honest connections can eventually
      // be accepted, only degrading the initial latency.
      if (TimestampMilli.now() - acceptedAt < peerConnectionConf.pendingConnectionMinAge) {
        Admission.Reject
      } else {
        Admission.Evict(oldest)
      }
    }
  }

  override def mdc(currentMessage: Any): MDC = Logs.mdc(Some(LogCategory.CONNECTION))
}

object Server {

  def props(keyPair: KeyPair, peerConnectionConf: PeerConnection.Conf, switchboard: ActorRef, router: ActorRef, address: InetSocketAddress, bound: Option[Promise[Done]] = None): Props = Props(new Server(keyPair, peerConnectionConf, switchboard, router: ActorRef, address, bound))

  /**
   * When we've reached our limits and have too many pending connections, we add a delay before accepting the next
   * connection, which allows pending connections to complete the authentication handshake. This message is sent after
   * the delay to resume listening.
   */
  private case object AcceptNext

  // @formatter:off
  private[io] case class GetPendingConnections(replyTo: ActorRef)
  private[io] case class PendingConnections(peerConnections: Set[ActorRef])
  // @formatter:on

  // @formatter:off
  private sealed trait Admission
  private object Admission {
    /** We have capacity for another pending connection. */
    case object Accept extends Admission
    /** We're at capacity, but the given pending connection is old enough to be dropped to make room. */
    case class Evict(peerConnection: ActorRef) extends Admission
    /** We're at capacity, and every pending connection is too recent to be dropped: we protect them instead by rejecting the new connection. */
    case object Reject extends Admission
  }
  // @formatter:on

}

