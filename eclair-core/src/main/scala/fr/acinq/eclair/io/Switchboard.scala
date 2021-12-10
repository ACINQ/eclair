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

import akka.actor.typed.scaladsl.adapter.{ClassicActorContextOps, ClassicActorRefOps}
import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, OneForOneStrategy, Props, Status, SupervisorStrategy}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.OnChainAddressGenerator
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.remote.EclairInternalsSerializer.RemoteTypes
import fr.acinq.eclair.router.Router.RouterConf

/**
 * Ties network connections to peers.
 * Created by PM on 14/02/2017.
 */
class Switchboard(nodeParams: NodeParams, peerFactory: Switchboard.PeerFactory) extends Actor with ActorLogging {

  import Switchboard._

  context.system.eventStream.subscribe(self, classOf[ChannelIdAssigned])
  context.system.eventStream.subscribe(self, classOf[LastChannelClosed])

  // we load channels from database
  private def initialPeersWithChannels(): Set[PublicKey] = {
    // Check if channels that are still in CLOSING state have actually been closed. This can happen when the app is stopped
    // just after a channel state has transitioned to CLOSED and before it has effectively been removed.
    // Closed channels will be removed, other channels will be restored.
    val (channels, closedChannels) = nodeParams.db.channels.listLocalChannels().partition(c => Closing.isClosed(c, None).isEmpty)
    closedChannels.foreach(c => {
      log.info(s"closing channel ${c.channelId}")
      nodeParams.db.channels.removeChannel(c.channelId)
    })

    val peerChannels = channels.groupBy(_.commitments.remoteParams.nodeId)
    peerChannels.foreach { case (remoteNodeId, states) => createOrGetPeer(remoteNodeId, offlineChannels = states.toSet) }
    log.info("restoring {} peer(s) with {} channel(s)", peerChannels.size, channels.size)
    peerChannels.keySet
  }

  def receive: Receive = normal(initialPeersWithChannels())

  def normal(peersWithChannels: Set[PublicKey]): Receive = {

    case Peer.Connect(publicKey, _, _) if publicKey == nodeParams.nodeId =>
      sender() ! Status.Failure(new RuntimeException("cannot open connection with oneself"))

    case Peer.Connect(nodeId, address_opt, replyTo) =>
      // we create a peer if it doesn't exist: when the peer doesn't exist, we can be sure that we don't have channels,
      // otherwise the peer would have been created during the initialization step.
      val peer = createOrGetPeer(nodeId, offlineChannels = Set.empty)
      val c = if (replyTo == ActorRef.noSender) {
        Peer.Connect(nodeId, address_opt, sender())
      } else {
        Peer.Connect(nodeId, address_opt, replyTo)
      }
      peer forward c

    case d: Peer.Disconnect =>
      getPeer(d.nodeId) match {
        case Some(peer) => peer forward d
        case None => sender() ! Status.Failure(new RuntimeException("peer not found"))
      }

    case o: Peer.OpenChannel =>
      getPeer(o.remoteNodeId) match {
        case Some(peer) => peer forward o
        case None => sender() ! Status.Failure(new RuntimeException("no connection to peer"))
      }

    case authenticated: PeerConnection.Authenticated =>
      // if this is an incoming connection, we might not yet have created the peer
      val peer = createOrGetPeer(authenticated.remoteNodeId, offlineChannels = Set.empty)
      val features = nodeParams.featuresFor(authenticated.remoteNodeId)
      // if the peer is whitelisted, we sync with them, otherwise we only sync with peers with whom we have at least one channel
      val doSync = nodeParams.syncWhitelist.contains(authenticated.remoteNodeId) || (nodeParams.syncWhitelist.isEmpty && peersWithChannels.contains(authenticated.remoteNodeId))
      authenticated.peerConnection ! PeerConnection.InitializeConnection(peer, nodeParams.chainHash, features, doSync)

    case ChannelIdAssigned(_, remoteNodeId, _, _) => context.become(normal(peersWithChannels + remoteNodeId))

    case LastChannelClosed(_, remoteNodeId) => context.become(normal(peersWithChannels - remoteNodeId))

    case GetPeers => sender() ! context.children

    case GetPeerInfo(replyTo, remoteNodeId) =>
      getPeer(remoteNodeId) match {
        case Some(peer) => replyTo ! KnownPeer(peer, peersWithChannels.contains(remoteNodeId))
        case None => replyTo ! UnknownPeer
      }

    case GetRouterPeerConf => sender() ! RouterPeerConf(nodeParams.routerConf, nodeParams.peerConnectionConf)

    case OnionMessages.SendMessage(nextNodeId, dataToRelay) =>
      val relay = context.spawnAnonymous(MessageRelay())
      relay ! MessageRelay.RelayMessage(self, nextNodeId, dataToRelay, sender().toTyped)
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

  def createPeer(remoteNodeId: PublicKey): ActorRef = peerFactory.spawn(context, remoteNodeId)

  def createOrGetPeer(remoteNodeId: PublicKey, offlineChannels: Set[HasCommitments]): ActorRef = {
    getPeer(remoteNodeId) match {
      case Some(peer) => peer
      case None =>
        log.debug(s"creating new peer (current={})", context.children.size)
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

  trait PeerFactory {
    def spawn(context: ActorContext, remoteNodeId: PublicKey): ActorRef
  }

  case class SimplePeerFactory(nodeParams: NodeParams, wallet: OnChainAddressGenerator, channelFactory: Peer.ChannelFactory) extends PeerFactory {
    override def spawn(context: ActorContext, remoteNodeId: PublicKey): ActorRef =
      context.actorOf(Peer.props(nodeParams, remoteNodeId, wallet, channelFactory, context.self), name = peerActorName(remoteNodeId))
  }

  def props(nodeParams: NodeParams, peerFactory: PeerFactory) = Props(new Switchboard(nodeParams, peerFactory))

  def peerActorName(remoteNodeId: PublicKey): String = s"peer-$remoteNodeId"

  // @formatter:off
  case object GetPeers

  case class GetPeerInfo(replyTo: ActorRef, remoteNodeId: PublicKey)
  sealed trait PeerInfo
  case class UnknownPeer(remoteNodeId: PublicKey) extends PeerInfo
  case class KnownPeer(peer: ActorRef, hasChannel: Boolean) extends PeerInfo

  case object GetRouterPeerConf extends RemoteTypes
  case class RouterPeerConf(routerConf: RouterConf, peerConf: PeerConnection.Conf) extends RemoteTypes
  // @formatter:on

}
