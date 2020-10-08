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

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Status, SupervisorStrategy}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._

/**
 * Ties network connections to peers.
 * Created by PM on 14/02/2017.
 */
class Switchboard(nodeParams: NodeParams, watcher: ActorRef, relayer: ActorRef, wallet: EclairWallet) extends Actor with ActorLogging {

  import Switchboard._

  // we load channels from database
  {
    // Check if channels that are still in CLOSING state have actually been closed. This can happen when the app is stopped
    // just after a channel state has transitioned to CLOSED and before it has effectively been removed.
    // Closed channels will be removed, other channels will be restored.
    val (channels, closedChannels) = nodeParams.db.channels.listLocalChannels().partition(c => Closing.isClosed(c, None).isEmpty)
    closedChannels.foreach(c => {
      log.info(s"closing channel ${c.channelId}")
      nodeParams.db.channels.removeChannel(c.channelId)
    })

    channels
      .groupBy(_.commitments.remoteParams.nodeId)
      .map { case (remoteNodeId, states) => createOrGetPeer(remoteNodeId, offlineChannels = states.toSet) }
  }

  def receive: Receive = {

    case Peer.Connect(publicKey, _) if publicKey == nodeParams.nodeId =>
      sender ! Status.Failure(new RuntimeException("cannot open connection with oneself"))

    case c: Peer.Connect =>
      // we create a peer if it doesn't exist
      val peer = createOrGetPeer(c.nodeId, offlineChannels = Set.empty)
      peer forward c

    case d: Peer.Disconnect =>
      getPeer(d.nodeId) match {
        case Some(peer) => peer forward d
        case None => sender ! Status.Failure(new RuntimeException("peer not found"))
      }

    case o: Peer.OpenChannel =>
      getPeer(o.remoteNodeId) match {
        case Some(peer) => peer forward o
        case None => sender ! Status.Failure(new RuntimeException("no connection to peer"))
      }

    case authenticated: PeerConnection.Authenticated =>
      // if this is an incoming connection, we might not yet have created the peer
      val peer = createOrGetPeer(authenticated.remoteNodeId, offlineChannels = Set.empty)
      val features = nodeParams.featuresFor(authenticated.remoteNodeId).maskFeaturesForEclairMobile()
      val doSync = nodeParams.syncWhitelist.isEmpty || nodeParams.syncWhitelist.contains(authenticated.remoteNodeId)
      authenticated.peerConnection ! PeerConnection.InitializeConnection(peer, nodeParams.chainHash, features, doSync)

    case Symbol("peers") => sender ! context.children

  }

  /**
   * Retrieves a peer based on its public key.
   *
   * NB: Internally akka uses a TreeMap to store the binding, so this lookup is O(log(N)) where N is the number of
   * peers. We could make it O(1) by using our own HashMap, but it creates other problems when we need to remove an
   * existing peer. This seems like a reasonable trade-off because we only make this call once per connection, and N
   * should never be very big anyway.
   */
  def getPeer(remoteNodeId: PublicKey): Option[ActorRef] = context.child(peerActorName(remoteNodeId))

  def createPeer(remoteNodeId: PublicKey): ActorRef = context.actorOf(Peer.props(nodeParams, remoteNodeId, watcher, relayer, wallet), name = peerActorName(remoteNodeId))

  def createOrGetPeer(remoteNodeId: PublicKey, offlineChannels: Set[HasCommitments]): ActorRef = {
    getPeer(remoteNodeId) match {
      case Some(peer) => peer
      case None =>
        log.info(s"creating new peer current=${context.children.size}")
        val peer = createPeer(remoteNodeId)
        peer ! Peer.Init(offlineChannels)
        peer
    }
  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled message=$message")

  // we resume failing peers because they may have open channels that we don't want to close abruptly
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Resume }
}

object Switchboard {

  def props(nodeParams: NodeParams, watcher: ActorRef, relayer: ActorRef, wallet: EclairWallet) = Props(new Switchboard(nodeParams, watcher, relayer, wallet))

  def peerActorName(remoteNodeId: PublicKey): String = s"peer-$remoteNodeId"

}
