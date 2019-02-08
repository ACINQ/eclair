/*
 * Copyright 2018 ACINQ SAS
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

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Status, SupervisorStrategy}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.channel.{HasCommitments, _}
import fr.acinq.eclair.payment.Relayer.RelayPayload
import fr.acinq.eclair.payment.{Relayed, Relayer}
import fr.acinq.eclair.router.Rebroadcast
import fr.acinq.eclair.transactions.{IN, OUT}
import fr.acinq.eclair.wire.{TemporaryNodeFailure, UpdateAddHtlc}
import grizzled.slf4j.Logging

import scala.util.Success

/**
  * Ties network connections to peers.
  * Created by PM on 14/02/2017.
  */
class Switchboard(nodeParams: NodeParams, authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) extends Actor with ActorLogging {

  import Switchboard._

  authenticator ! self

  // we load peers and channels from database
  {
    val channels = nodeParams.channelsDb.listChannels()
    val peers = nodeParams.peersDb.listPeers()

    checkBrokenHtlcsLink(channels, nodeParams.privateKey) match {
      case Nil => ()
      case brokenHtlcs =>
        val brokenHtlcKiller = context.actorOf(Props[HtlcReaper], name = "htlc-reaper")
        brokenHtlcKiller ! brokenHtlcs
    }

    channels
      .groupBy(_.commitments.remoteParams.nodeId)
      .map {
        case (remoteNodeId, states) => (remoteNodeId, states, peers.get(remoteNodeId))
      }
      .foreach {
        case (remoteNodeId, states, nodeaddress_opt) =>
          // we might not have an address if we didn't initiate the connection in the first place
          val address_opt = nodeaddress_opt.map(_.socketAddress)
          createOrGetPeer(remoteNodeId, previousKnownAddress = address_opt, offlineChannels = states.toSet)
      }
  }

  def receive: Receive = {

    case Peer.Connect(NodeURI(publicKey, _)) if publicKey == nodeParams.nodeId =>
      sender ! Status.Failure(new RuntimeException("cannot open connection with oneself"))

    case c@Peer.Connect(NodeURI(remoteNodeId, _)) =>
      // we create a peer if it doesn't exist
      val peer = createOrGetPeer(remoteNodeId, previousKnownAddress = None, offlineChannels = Set.empty)
      peer forward c

    case o@Peer.OpenChannel(remoteNodeId, _, _, _, _) =>
      getPeer(remoteNodeId) match {
        case Some(peer) => peer forward o
        case None => sender ! Status.Failure(new RuntimeException("no connection to peer"))
      }

    case auth@Authenticator.Authenticated(_, _, remoteNodeId, _, _, _) =>
      // if this is an incoming connection, we might not yet have created the peer
      val peer = createOrGetPeer(remoteNodeId, previousKnownAddress = None, offlineChannels = Set.empty)
      peer forward auth

    case r: Rebroadcast => context.children.foreach(_ forward r)

    case 'peers => sender ! context.children

  }

  def peerActorName(remoteNodeId: PublicKey): String = s"peer-$remoteNodeId"

  /**
    * Retrieves a peer based on its public key.
    *
    * NB: Internally akka uses a TreeMap to store the binding, so this lookup is O(log(N)) where N is the number of
    * peers. We could make it O(1) by using our own HashMap, but it creates other problems when we need to remove an
    * existing peer. This seems like a reasonable trade-off because we only make this call once per connection, and N
    * should never be very big anyway.
    *
    * @param remoteNodeId
    * @return
    */
  def getPeer(remoteNodeId: PublicKey): Option[ActorRef] = context.child(peerActorName(remoteNodeId))

  /**
    *
    * @param remoteNodeId
    * @param previousKnownAddress only to be set if we know for sure that this ip worked in the past
    * @param offlineChannels
    * @return
    */
  def createOrGetPeer(remoteNodeId: PublicKey, previousKnownAddress: Option[InetSocketAddress], offlineChannels: Set[HasCommitments]) = {
    getPeer(remoteNodeId) match {
      case Some(peer) => peer
      case None =>
        log.info(s"creating new peer current=${context.children.size}")
        val peer = context.actorOf(Peer.props(nodeParams, remoteNodeId, authenticator, watcher, router, relayer, wallet), name = peerActorName(remoteNodeId))
        peer ! Peer.Init(previousKnownAddress, offlineChannels)
        peer
    }
  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled message=$message")

  // we resume failing peers because they may have open channels that we don't want to close abruptly
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Resume }
}

object Switchboard extends Logging {

  def props(nodeParams: NodeParams, authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) = Props(new Switchboard(nodeParams, authenticator, watcher, router, relayer, wallet))

  /**
    * If we have stopped eclair while it was forwarding HTLCs, it is possible that we are in a state were an incoming HTLC
    * was committed by both sides, but we didn't have time to send and/or sign the corresponding HTLC to the downstream node.
    *
    * In that case, if we do nothing, the incoming HTLC will eventually expire and we won't lose money, but the channel will
    * get closed, which is a major inconvenience.
    *
    * This check will detect this and will allow us to fast-fail HTLCs and thus preserve channels.
    *
    * @param channels
    * @return
    */
  def checkBrokenHtlcsLink(channels: Seq[HasCommitments], privateKey: PrivateKey): Seq[UpdateAddHtlc] = {

    // We are interested in incoming HTLCs, that have been *cross-signed*. They signed it first, so the HTLC will first
    // appear in our commitment tx, and later on in their commitment when we subsequently sign it.
    // That's why we need to look in *their* commitment with direction=OUT.
    val htlcs_in = channels
      .flatMap(_.commitments.remoteCommit.spec.htlcs)
      .filter(_.direction == OUT)
      .map(_.add)
      .map(Relayer.tryParsePacket(_, privateKey))
      .collect { case Success(RelayPayload(add, _, _)) => add } // we only consider htlcs that are relayed, not the ones for which we are the final node

    // Here we do it differently because we need the origin information.
    val relayed_out = channels
      .flatMap(_.commitments.originChannels.values)
      .collect { case r: Relayed => r }
      .toSet

    val htlcs_broken = htlcs_in.filterNot(htlc_in => relayed_out.exists(r => r.originChannelId == htlc_in.channelId && r.originHtlcId == htlc_in.id))

    logger.info(s"htlcs_in=${htlcs_in.size} htlcs_out=${relayed_out.size} htlcs_broken=${htlcs_broken.size}")

    htlcs_broken
  }

}

class HtlcReaper extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])

  override def receive: Receive = {
    case initialHtlcs: Seq[UpdateAddHtlc]@unchecked => context become main(initialHtlcs)
  }

  def main(htlcs: Seq[UpdateAddHtlc]): Receive = {
    case ChannelStateChanged(channel, _, _, WAIT_FOR_INIT_INTERNAL | OFFLINE | SYNCING, NORMAL | SHUTDOWN | CLOSING, data: HasCommitments) =>
      val acked = htlcs
        .filter(_.channelId == data.channelId) // only consider htlcs related to this channel
        .filter {
        case htlc if Commitments.getHtlcCrossSigned(data.commitments, IN, htlc.id).isDefined =>
          // this htlc is cross signed in the current commitment, we can fail it
          log.info(s"failing broken htlc=$htlc")
          channel ! CMD_FAIL_HTLC(htlc.id, Right(TemporaryNodeFailure), commit = true)
          false // the channel may very well be disconnected before we sign (=ack) the fail, so we keep it for now
        case _ =>
          true // the htlc has already been failed, we can forget about it now
      }
      acked.foreach(htlc => log.info(s"forgetting htlc id=${htlc.id} channelId=${htlc.channelId}"))
      context become main(htlcs diff acked)
  }


}
