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

import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{ClassicActorContextOps, ClassicActorRefOps, ClassicActorSystemOps, TypedActorRefOps}
import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, OneForOneStrategy, Props, Stash, Status, SupervisorStrategy, typed}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.blockchain.OnChainPubkeyCache
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.IncomingConnectionsTracker.TrackIncomingConnection
import fr.acinq.eclair.io.Peer.{PeerInfoResponse, PeerNotFound}
import fr.acinq.eclair.payment.relay.OnTheFlyFunding
import fr.acinq.eclair.remote.EclairInternalsSerializer.RemoteTypes
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.RouterConf
import fr.acinq.eclair.{NodeParams, SubscriptionsComplete}

/**
 * Ties network connections to peers.
 * Created by PM on 14/02/2017.
 */
class Switchboard(nodeParams: NodeParams, peerFactory: Switchboard.PeerFactory) extends Actor with Stash with ActorLogging {

  import Switchboard._

  private val incomingConnectionsTracker = context.spawn(Behaviors.supervise(IncomingConnectionsTracker(nodeParams, context.self.toTyped)).onFailure(typed.SupervisorStrategy.resume), name = "incoming-connections-tracker")

  context.system.eventStream.subscribe(self, classOf[ChannelIdAssigned])
  context.system.eventStream.subscribe(self, classOf[LastChannelClosed])
  context.system.eventStream.publish(SubscriptionsComplete(this.getClass))

  context.system.toTyped.receptionist ! Receptionist.Register(SwitchboardServiceKey, context.self.toTyped[GetPeerInfo])

  def receive: Receive = {
    case init: Init =>
      // Check if channels that are still in CLOSING state have actually been closed. This can happen when the app is stopped
      // just after a channel state has transitioned to CLOSED and before it has effectively been removed.
      // Closed channels will be removed, other channels will be restored.
      val (channels, closedChannels) = init.channels.partition(c => Closing.isClosed(c, None).isEmpty)
      closedChannels.foreach(c => {
        log.info(s"closing channel ${c.channelId}")
        nodeParams.db.channels.removeChannel(c.channelId)
      })
      val peersWithChannels = channels.groupBy(_.remoteNodeId)
      val peersWithOnTheFlyFunding = nodeParams.db.liquidity.listPendingOnTheFlyFunding()
      peersWithChannels.foreach { case (remoteNodeId, states) => createOrGetPeer(remoteNodeId, offlineChannels = states.toSet, peersWithOnTheFlyFunding.getOrElse(remoteNodeId, Map.empty)) }
      // We must re-create peers that have a funded on-the-fly payment, even if they don't have a channel yet.
      // We will retry relaying that payment and complete the on-the-fly funding.
      (peersWithOnTheFlyFunding -- peersWithChannels.keySet).foreach {
        case (remoteNodeId, pending) => createOrGetPeer(remoteNodeId, Set.empty, pending)
      }
      val peerCapacities = channels.map {
        case channelData: ChannelDataWithoutCommitments => (channelData.remoteNodeId, 0L)
        case channelData: ChannelDataWithCommitments => (channelData.remoteNodeId, channelData.commitments.capacity.toLong)
      }.groupMapReduce[PublicKey, Long](_._1)(_._2)(_ + _)
      val topCapacityPeers = peerCapacities.toSeq.sortBy(_._2).takeRight(nodeParams.routerConf.syncConf.peerLimit).map(_._1).toSet
      log.info("restoring {} peer(s) with {} channel(s) and {} peers with pending on-the-fly funding", peersWithChannels.size, channels.size, (peersWithOnTheFlyFunding.keySet -- peersWithChannels.keySet).size)
      unstashAll()
      context.become(normal(peersWithChannels.keySet, topCapacityPeers))
    case _ =>
      stash()
  }

  def normal(peersWithChannels: Set[PublicKey], peersToSyncWith: Set[PublicKey]): Receive = {

    case Peer.Connect(publicKey, _, _, _) if publicKey == nodeParams.nodeId =>
      sender() ! Status.Failure(new RuntimeException("cannot open connection with oneself"))

    case Peer.Connect(nodeId, address_opt, replyTo, isPersistent) =>
      // We create a peer if it doesn't exist: when the peer doesn't exist, we can be sure that we don't have channels,
      // otherwise the peer would have been created during the initialization step.
      // We're also sure that we don't have pending on-the-fly funding attempts, otherwise the peer would have also
      // been created during the initialization step.
      val peer = createOrGetPeer(nodeId, offlineChannels = Set.empty, pendingOnTheFlyFunding = Map.empty)
      val c = if (replyTo == ActorRef.noSender) {
        Peer.Connect(nodeId, address_opt, sender(), isPersistent)
      } else {
        Peer.Connect(nodeId, address_opt, replyTo, isPersistent)
      }
      peer forward c

    case d: Peer.Disconnect =>
      val replyTo = d.replyTo_opt.getOrElse(sender().toTyped)
      getPeer(d.nodeId) match {
        case Some(peer) => peer forward d
        case None => replyTo ! PeerNotFound(d.nodeId)
      }

    case o: Peer.OpenChannel =>
      getPeer(o.remoteNodeId) match {
        case Some(peer) => peer forward o
        case None => sender() ! Status.Failure(new RuntimeException(s"peer ${o.remoteNodeId} not found"))
      }

    case authenticated: PeerConnection.Authenticated =>
      // If this is an incoming connection, we might not yet have created the peer.
      val peer = createOrGetPeer(authenticated.remoteNodeId, offlineChannels = Set.empty, pendingOnTheFlyFunding = Map.empty)
      val features = nodeParams.initFeaturesFor(authenticated.remoteNodeId)
      val hasChannels = peersWithChannels.contains(authenticated.remoteNodeId)
      val doSync = peersToSyncWith.contains(authenticated.remoteNodeId) || nodeParams.routerConf.syncConf.whitelist.contains(authenticated.remoteNodeId)
      authenticated.peerConnection ! PeerConnection.InitializeConnection(peer, nodeParams.chainHash, features, doSync, nodeParams.liquidityAdsConfig.rates_opt)
      if (!hasChannels && !authenticated.outgoing) {
        incomingConnectionsTracker ! TrackIncomingConnection(authenticated.remoteNodeId)
      }

    case ChannelIdAssigned(_, remoteNodeId, _, _) =>
      val peersToSyncWith1 = if (peersToSyncWith.size < nodeParams.routerConf.syncConf.peerLimit) peersToSyncWith + remoteNodeId else peersToSyncWith
      context.become(normal(peersWithChannels + remoteNodeId, peersToSyncWith1))

    case LastChannelClosed(_, remoteNodeId) => context.become(normal(peersWithChannels - remoteNodeId, peersToSyncWith - remoteNodeId))

    case GetPeers => sender() ! context.children.filterNot(_ == incomingConnectionsTracker.toClassic)

    case GetPeerInfo(replyTo, remoteNodeId) =>
      getPeer(remoteNodeId) match {
        case Some(peer) => peer ! Peer.GetPeerInfo(Some(replyTo))
        case None => replyTo ! Peer.PeerNotFound(remoteNodeId)
      }

    case GetRouterPeerConf => sender() ! RouterPeerConf(nodeParams.routerConf, nodeParams.peerConnectionConf)
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

  def createOrGetPeer(remoteNodeId: PublicKey, offlineChannels: Set[PersistentChannelData], pendingOnTheFlyFunding: Map[ByteVector32, OnTheFlyFunding.Pending]): ActorRef = {
    getPeer(remoteNodeId) match {
      case Some(peer) => peer
      case None =>
        // do not count the incoming-connections-tracker child actor that is not a peer
        log.debug(s"creating new peer (current={})", context.children.size - 1)
        val peer = createPeer(remoteNodeId)
        peer ! Peer.Init(offlineChannels, pendingOnTheFlyFunding)
        peer
    }
  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled message=$message")

  // we resume failing peers because they may have open channels that we don't want to close abruptly
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Resume }
}

object Switchboard {

  val SwitchboardServiceKey: ServiceKey[GetPeerInfo] = ServiceKey[GetPeerInfo]("switchboard")

  trait PeerFactory {
    def spawn(context: ActorContext, remoteNodeId: PublicKey): ActorRef
  }

  case class SimplePeerFactory(nodeParams: NodeParams, wallet: OnChainPubkeyCache, channelFactory: Peer.ChannelFactory, pendingChannelsRateLimiter: typed.ActorRef[PendingChannelsRateLimiter.Command], register: ActorRef, router: typed.ActorRef[Router.GetNodeId]) extends PeerFactory {
    override def spawn(context: ActorContext, remoteNodeId: PublicKey): ActorRef =
      context.actorOf(Peer.props(nodeParams, remoteNodeId, wallet, channelFactory, context.self, register, router, pendingChannelsRateLimiter), name = peerActorName(remoteNodeId))
  }

  def props(nodeParams: NodeParams, peerFactory: PeerFactory) = Props(new Switchboard(nodeParams, peerFactory))

  def peerActorName(remoteNodeId: PublicKey): String = s"peer-$remoteNodeId"

  // @formatter:off
  case class Init(channels: Seq[PersistentChannelData])

  case object GetPeers
  case class GetPeerInfo(replyTo: typed.ActorRef[PeerInfoResponse], remoteNodeId: PublicKey)

  case object GetRouterPeerConf extends RemoteTypes
  case class RouterPeerConf(routerConf: RouterConf, peerConf: PeerConnection.Conf) extends RemoteTypes
  // @formatter:on

}
