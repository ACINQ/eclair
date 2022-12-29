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

import akka.actor.{ActorRef, Props}
import akka.cluster.Cluster
import akka.cluster.pubsub.DistributedPubSub
import akka.cluster.pubsub.DistributedPubSubMediator.Send
import akka.event.Logging.MDC
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.io.Monitoring.Metrics
import fr.acinq.eclair.wire.protocol.{NodeAddress, OnionAddress}
import fr.acinq.eclair.{FSMDiagnosticActorLogging, Logs, NodeParams, TimestampMilli}

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
class ReconnectionTask(nodeParams: NodeParams, remoteNodeId: PublicKey) extends FSMDiagnosticActorLogging[ReconnectionTask.State, ReconnectionTask.Data] {

  import ReconnectionTask._

  startWith(IDLE, IdleData(Nothing))

  when(CONNECTING) {
    case Event(failure: PeerConnection.ConnectionResult.Failure, d: ConnectingData) =>
      log.info(s"connection failed (reason=$failure), next reconnection in ${d.nextReconnectionDelay.toSeconds} seconds")
      setReconnectTimer(d.nextReconnectionDelay)
      goto(WAITING) using WaitingData(nextReconnectionDelay(d.nextReconnectionDelay, nodeParams.maxReconnectInterval))

    case Event(Peer.Transition(_, _: Peer.ConnectedData), d) =>
      log.debug("peer is connected")
      goto(IDLE) using IdleData(d)
  }

  when(WAITING) {
    case Event(TickReconnect, d: WaitingData) =>
      // we query the db every time because it may have been updated in the meantime (e.g. with network announcements)
      getPeerAddressFromDb(nodeParams, remoteNodeId) match {
        case Some(address) =>
          connect(address, origin = self, isPersistent = true)
          goto(CONNECTING) using ConnectingData(address, d.nextReconnectionDelay)
        case None =>
          // we don't have an address for that peer, nothing to do
          goto(IDLE) using IdleData(d)
      }

    case Event(Peer.Transition(_, _: Peer.ConnectedData), d) =>
      log.debug("peer is connected")
      cancelTimer(RECONNECT_TIMER)
      goto(IDLE) using IdleData(d)
  }

  when(IDLE) {
    case Event(Peer.Transition(previousPeerData, nextPeerData: Peer.DisconnectedData), d: IdleData) =>
      if (nodeParams.autoReconnect && nextPeerData.channels.nonEmpty) { // we only reconnect if nodeParams explicitly instructs us to or there are existing channels
        val (initialDelay, firstNextReconnectionDelay) = (previousPeerData, d.previousData) match {
          case (Peer.Nothing, _) =>
            // When restarting, we add some randomization before the first reconnection attempt to avoid herd effect
            // We also add a fixed delay to give time to the front to boot up
            val initialDelay = nodeParams.initialRandomReconnectDelay + randomizeDelay(nodeParams.initialRandomReconnectDelay)
            // When restarting, we will ~immediately reconnect, but then:
            // - we don't want all the subsequent reconnection attempts to be synchronized (herd effect)
            // - we don't want to go through the exponential backoff delay, because we were offline, not them, so there is no
            // reason to eagerly retry
            // That's why we set the next reconnection delay to a random value between MAX_RECONNECT_INTERVAL/2 and MAX_RECONNECT_INTERVAL.
            val firstNextReconnectionDelay = nodeParams.maxReconnectInterval.minus(Random.nextInt(nodeParams.maxReconnectInterval.toSeconds.toInt / 2).seconds)
            log.debug("first connection attempt in {}", initialDelay)
            (initialDelay, firstNextReconnectionDelay)
          case (_, cd: ConnectingData) if TimestampMilli.now() - d.since < 30.seconds =>
            // If our latest successful connection attempt was less than 30 seconds ago, we pick up the exponential
            // back-off retry delay where we left it. The goal is to address cases where the reconnection is successful,
            // but we are disconnected right away.
            val initialDelay = cd.nextReconnectionDelay
            val firstNextReconnectionDelay = nextReconnectionDelay(initialDelay, nodeParams.maxReconnectInterval)
            log.info("peer got disconnected shortly after connection was established, next reconnection in {}", initialDelay)
            (initialDelay, firstNextReconnectionDelay)
          case _ =>
            // Randomizing the initial delay is important in the case of a reconnection. If both peers have a public
            // address, they may attempt to simultaneously connect back to each other, which could result in reconnection loop,
            // given that each new connection will cause the previous one to be killed.
            // We also add a fixed delay to give time to the front to boot up
            val initialDelay = nodeParams.initialRandomReconnectDelay + randomizeDelay(nodeParams.initialRandomReconnectDelay)
            val firstNextReconnectionDelay = nextReconnectionDelay(initialDelay, nodeParams.maxReconnectInterval)
            log.info("peer is disconnected, next reconnection in {}", initialDelay)
            (initialDelay, firstNextReconnectionDelay)
        }
        setReconnectTimer(initialDelay)
        goto(WAITING) using WaitingData(firstNextReconnectionDelay)
      } else {
        stay()
      }

    case Event(Peer.Transition(_, _: Peer.ConnectedData), _) =>
      log.debug("peer is connected")
      stay()
  }

  whenUnhandled {
    case Event(_: PeerConnection.ConnectionResult, _) => stay()

    case Event(TickReconnect, _) => stay()

    case Event(Peer.Connect(_, address_opt, replyTo, isPersistent), _) =>
      // manual connection requests happen completely independently of the automated reconnection process;
      // we initiate a connection but don't modify our state.
      // if we are already connecting/connected, the peer will kill any duplicate connections
      address_opt
        .orElse(getPeerAddressFromDb(nodeParams, remoteNodeId)) match {
        case Some(address) => connect(address, origin = replyTo, isPersistent)
        case None => replyTo ! PeerConnection.ConnectionResult.NoAddressFound
      }
      stay()
  }

  private def setReconnectTimer(delay: FiniteDuration): Unit = startSingleTimer(RECONNECT_TIMER, TickReconnect, delay)

  // activate the extension only on demand, so that tests pass
  lazy val mediator = DistributedPubSub(context.system).mediator

  private def connect(address: NodeAddress, origin: ActorRef, isPersistent: Boolean): Unit = {
    log.info(s"connecting to $address")
    val req = ClientSpawner.ConnectionRequest(remoteNodeId, address, origin, isPersistent)
    if (context.system.hasExtension(Cluster)) {
      mediator ! Send(path = "/user/client-spawner", msg = req, localAffinity = false)
    } else {
      context.system.eventStream.publish(req)
    }
    Metrics.ReconnectionsAttempts.withoutTags().increment()
  }

  override def mdc(currentMessage: Any): MDC = {
    Logs.mdc(Some(LogCategory.CONNECTION), Some(remoteNodeId), nodeAlias_opt = Some(nodeParams.alias))
  }

}

object ReconnectionTask {

  def props(nodeParams: NodeParams, remoteNodeId: PublicKey): Props = Props(new ReconnectionTask(nodeParams, remoteNodeId))

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
  case class IdleData(previousData: Data, since: TimestampMilli = TimestampMilli.now()) extends Data
  case class ConnectingData(to: NodeAddress, nextReconnectionDelay: FiniteDuration) extends Data
  case class WaitingData(nextReconnectionDelay: FiniteDuration) extends Data
  // @formatter:on

  def selectNodeAddress(nodeParams: NodeParams, nodeAddresses: Seq[NodeAddress]): Option[NodeAddress] = {
    // it doesn't make sense to mix tor and clearnet addresses, so we separate them and decide whether we use one or the other
    val torAddresses = nodeAddresses.collect { case o: OnionAddress => o }
    val clearnetAddresses = nodeAddresses diff torAddresses
    val selectedAddresses = nodeParams.socksProxy_opt match {
      case Some(params) if clearnetAddresses.nonEmpty && params.useForTor && (!params.useForIPv4 || !params.useForIPv6) =>
        // Remote has clearnet (and possibly tor addresses), and we support tor, but we have configured it to only use
        // tor when strictly necessary. In this case we will only connect over clearnet.
        clearnetAddresses
      case Some(params) if torAddresses.nonEmpty && params.useForTor =>
        // In all other cases, if they have a tor address and we support tor, we use tor.
        torAddresses
      case _ =>
        // Otherwise, if we don't support tor or they don't have a tor address, we use clearnet.
        clearnetAddresses
    }
    // finally, we pick an address at random
    if (selectedAddresses.nonEmpty) {
      Some(selectedAddresses(Random.nextInt(selectedAddresses.size)))
    } else {
      None
    }
  }

  def getPeerAddressFromDb(nodeParams: NodeParams, remoteNodeId: PublicKey): Option[NodeAddress] = {
    val nodeAddresses = nodeParams.db.peers.getPeer(remoteNodeId).toSeq ++ nodeParams.db.network.getNode(remoteNodeId).toList.flatMap(_.validAddresses)
    selectNodeAddress(nodeParams, nodeAddresses)
  }

  /**
   * This helps prevent peers reconnection loops due to synchronization of reconnection attempts.
   */
  def randomizeDelay(initialRandomReconnectDelay: FiniteDuration): FiniteDuration = Random.nextInt(initialRandomReconnectDelay.toMillis.toInt).millis.max(200 milliseconds)

  /**
   * Exponential backoff retry with a finite max
   */
  def nextReconnectionDelay(currentDelay: FiniteDuration, maxReconnectInterval: FiniteDuration): FiniteDuration = (2 * currentDelay).min(maxReconnectInterval)

}
