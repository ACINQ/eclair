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

import akka.actor.{ActorRef, FSM, Props, Terminated}
import akka.event.Logging.MDC
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.db.{NetworkDb, PeersDb}
import fr.acinq.eclair.io.Monitoring.Metrics
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Logs, NodeParams}

import scala.compat.Platform
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.Random

/**
 * This actor handles outgoing connections to a peer. The goal is to free the [[Peer]] actor from the reconnection logic
 * and have it just react to already established connections, independently of whether those connections are incoming or
 * outgoing.
 *
 * The base assumption is that the [[Peer]] will send its state transitions to the [[ReconnectionTask]] actor.
 *
 * This is more complicated than it seems and there are various corner cases to consider:
 * - multiple available addresses
 * - concurrent outgoing connections and conflict between automated/user-requested attempts
 * - concurrent incoming/outgoing connections and risk of reconnection loops
 * - etc.
 */
class ReconnectionTask(nodeParams: NodeParams, remoteNodeId: PublicKey, switchboard: ActorRef, router: ActorRef) extends FSMDiagnosticActorLogging[ReconnectionTask.State, ReconnectionTask.Data] {

  import ReconnectionTask._

  startWith(IDLE, IdleData(Nothing))

  when(CONNECTING) {
    case Event(Terminated(actor), d: ConnectingData) if actor == d.connection =>
      log.info(s"connection failed, next reconnection in ${d.nextReconnectionDelay.toSeconds} seconds")
      setReconnectTimer(d.nextReconnectionDelay)
      goto(WAITING) using WaitingData(nextReconnectionDelay(d.nextReconnectionDelay, nodeParams.maxReconnectInterval))

    case Event(FSM.Transition(_, Peer.DISCONNECTED, Peer.CONNECTED), d) =>
      log.info("peer is connected")
      goto(IDLE) using IdleData(d)
  }

  when(WAITING) {
    case Event(TickReconnect, d: WaitingData) =>
      // we query the db every time because it may have been updated in the meantime (e.g. with network announcements)
      getPeerAddressFromDb(nodeParams.db.peers, nodeParams.db.network, remoteNodeId) match {
        case Some(address) =>
          val connection = connect(address, origin_opt = None)
          goto(CONNECTING) using ConnectingData(connection, address, d.nextReconnectionDelay)
        case None =>
          // we don't have an address for that peer, nothing to do
          goto(IDLE) using IdleData(d)
      }

    case Event(FSM.Transition(_, Peer.DISCONNECTED, Peer.CONNECTED), d) =>
      log.info("peer is connected")
      cancelTimer(RECONNECT_TIMER)
      goto(IDLE) using IdleData(d)
  }

  when(IDLE) {
    case Event(FSM.Transition(_, Peer.INSTANTIATING | Peer.CONNECTED, Peer.DISCONNECTED), d: IdleData) =>
      if (nodeParams.autoReconnect) {
        val (initialDelay, firstNextReconnectionDelay) = d.previousData match {
          case Nothing =>
            // The random initialDelay adds a minimum delay, which is important for a first connection to a new peer which advertises
            // a public address. Right after the peer and this reconnection actor will be created, there will be a race between
            // this automated connection task that uses data from network db, and the Peer.Connect command that may use the same
            // address or a different one. This delay will cause the automated connection task to lose the race and prevent
            // unnecessary parallel connections
            val initialDelay = randomizeDelay(nodeParams.initialRandomReconnectDelay)
            // When restarting, we will ~immediately reconnect, but then:
            // - we don't want all the subsequent reconnection attempts to be synchronized (herd effect)
            // - we don't want to go through the exponential backoff delay, because we were offline, not them, so there is no
            // reason to eagerly retry
            // That's why we set the next reconnection delay to a random value between MAX_RECONNECT_INTERVAL/2 and MAX_RECONNECT_INTERVAL.
            val firstNextReconnectionDelay = nodeParams.maxReconnectInterval.minus(Random.nextInt(nodeParams.maxReconnectInterval.toSeconds.toInt / 2).seconds)
            (initialDelay, firstNextReconnectionDelay)
          case cd: ConnectingData if System.currentTimeMillis.milliseconds - d.since < 30.seconds =>
            log.info("peer is disconnected (shortly after connection was established)")
            // If our latest successful connection attempt was less than 30 seconds ago, we pick up the exponential
            // back-off retry delay where we left it. The goal is to address cases where the reconnection is successful,
            // but we are disconnected right away.
            val initialDelay = cd.nextReconnectionDelay
            val firstNextReconnectionDelay = nextReconnectionDelay(initialDelay, nodeParams.maxReconnectInterval)
            (initialDelay, firstNextReconnectionDelay)
          case _ =>
            log.info("peer is disconnected")
            // Randomizing the initial delay is important in the case of a reconnection. If both peers have a public
            // address, they may attempt to simultaneously connect back to each other, which could result in reconnection loop,
            // given that each new connection will cause the previous one to be killed.
            val initialDelay = randomizeDelay(nodeParams.initialRandomReconnectDelay)
            val firstNextReconnectionDelay = nextReconnectionDelay(initialDelay, nodeParams.maxReconnectInterval)
            (initialDelay, firstNextReconnectionDelay)
        }
        setReconnectTimer(initialDelay)
        goto(WAITING) using WaitingData(firstNextReconnectionDelay)
      } else {
        stay
      }

    case Event(FSM.Transition(_, Peer.DISCONNECTED, Peer.CONNECTED), _) =>
      log.info("peer is connected")
      stay
  }

  whenUnhandled {
    case Event(_: Terminated, _) => stay

    case Event(TickReconnect, _) => stay

    case Event(FSM.Transition(_, Peer.INSTANTIATING, Peer.INSTANTIATING), _) => stay // instantiation transition

    case Event(Peer.Connect(_, hostAndPort_opt), _) =>
      // manual connection requests happen completely independently of the automated reconnection process;
      // we initiate a connection but don't modify our state.
      // if we are already connecting/connected, the peer will kill any duplicate connections
      hostAndPort_opt
        .map(hostAndPort2InetSocketAddress)
        .orElse(getPeerAddressFromDb(nodeParams.db.peers, nodeParams.db.network, remoteNodeId)) match {
        case Some(address) => connect(address, Some(sender))
        case None => sender ! "no address found"
      }
      stay
  }

  private def setReconnectTimer(delay: FiniteDuration): Unit = setTimer(RECONNECT_TIMER, TickReconnect, delay, repeat = false)

  private def connect(address: InetSocketAddress, origin_opt: Option[ActorRef]): ActorRef = {
    log.info(s"connecting to $address")
    val connection = context.actorOf(Client.props(nodeParams, switchboard, router, address, remoteNodeId, origin_opt = origin_opt))
    context.watch(connection)
    Metrics.ReconnectionsAttempts.withoutTags().increment()
    connection
  }

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(Some(LogCategory.CONNECTION), Some(remoteNodeId))
  }

}

object ReconnectionTask {

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey, switchboard: ActorRef, router: ActorRef): Props = Props(new ReconnectionTask(nodeParams, remoteNodeId, switchboard, router))

  val RECONNECT_TIMER = "reconnect"

  case object TickReconnect

  // @formatter:off
  sealed trait State
  case object INIT extends State
  case object CONNECTING extends State
  case object WAITING extends State
  case object IDLE extends State
  // @formatter:on

  // @formatter:off
  sealed trait Data
  case object Nothing extends Data
  case class IdleData(previousData: Data, since: FiniteDuration = System.currentTimeMillis.milliseconds) extends Data
  case class ConnectingData(connection: ActorRef, to: InetSocketAddress, nextReconnectionDelay: FiniteDuration) extends Data
  case class WaitingData(nextReconnectionDelay: FiniteDuration) extends Data
  // @formatter:on

  def getPeerAddressFromDb(peersDb: PeersDb, networkDb: NetworkDb, remoteNodeId: PublicKey): Option[InetSocketAddress] = {
    peersDb.getPeer(remoteNodeId) // TODO should we start with the network db which may be more up to date? or rotate?
      .orElse(networkDb.getNode(remoteNodeId).flatMap(_.addresses.headOption)) // TODO gets the first of the list, improve selection?
      .map(_.socketAddress)
  }

  def hostAndPort2InetSocketAddress(hostAndPort: HostAndPort): InetSocketAddress = new InetSocketAddress(hostAndPort.getHost, hostAndPort.getPort)

  /**
   * This helps prevent peers reconnection loops due to synchronization of reconnection attempts.
   */
  def randomizeDelay(initialRandomReconnectDelay: FiniteDuration): FiniteDuration = Random.nextInt(initialRandomReconnectDelay.toMillis.toInt).millis.max(200 milliseconds)

  /**
   * Exponential backoff retry with a finite max
   */
  def nextReconnectionDelay(currentDelay: FiniteDuration, maxReconnectInterval: FiniteDuration): FiniteDuration = (2 * currentDelay).min(maxReconnectInterval)

}
