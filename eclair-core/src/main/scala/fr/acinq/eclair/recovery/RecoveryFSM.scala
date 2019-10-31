package fr.acinq.eclair.recovery

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, Props}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.channel.{Channel, HasCommitments}
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.{ConnectedData, FinalChannelId}
import fr.acinq.eclair.io.Switchboard.peerActorName
import fr.acinq.eclair.io.{NodeURI, Peer, PeerConnected, Switchboard}
import fr.acinq.eclair.recovery.RecoveryFSM._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{wire, _}
import grizzled.slf4j.Logging

class RecoveryFSM(nodeURI: NodeURI, val nodeParams: NodeParams, authenticator: ActorRef, router: ActorRef, switchboard: ActorRef, val wallet: EclairWallet, blockchain: ActorRef, relayer: ActorRef) extends Actor with Logging {

  context.system.eventStream.subscribe(self, classOf[PeerConnected])
  self ! RecoveryConnect(nodeURI)

  override def receive: Receive = waitingForConnection()

  def waitingForConnection(): Receive = {
    case c@RecoveryConnect(nodeURI: NodeURI) =>
      logger.info(s"creating new recovery peer")
      val peer = context.actorOf(Props(new RecoveryPeer(nodeParams, nodeURI.nodeId, authenticator, blockchain, router, relayer, wallet, self)))
      peer ! Peer.Init(previousKnownAddress = None, storedChannels = Set.empty)
      peer ! Peer.Connect(nodeURI.nodeId, Some(nodeURI.address))
      context.become(waitingForConnection())
    case PeerConnected(_, nodeId) if nodeId == nodeURI.nodeId =>
      logger.info(s"Connected to remote $nodeId")
      context.become(waitingForChannel())
  }

  def waitingForChannel(): Receive = {
    case ChannelFound(channelId) =>
      logger.info(s"peer=${nodeURI.nodeId} knows channelId=$channelId")
      context.become(waitingForChannel())
  }

}

object RecoveryFSM {

  sealed trait State
  case object WAIT_FOR_CONNECTION extends State
  case object WAIT_FOR_CHANNEL extends State

  sealed trait Data
  case object Empty extends Data

  sealed trait Event
  case class RecoveryConnect(remote: NodeURI) extends Event
  case class ChannelFound(channelId: ByteVector32) extends Event
}

class RecoverySwitchBoard(nodeParams: NodeParams, authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) extends Switchboard(nodeParams, authenticator, watcher, router, relayer, wallet) {

  override def createOrGetPeer(remoteNodeId: PublicKey, previousKnownAddress: Option[InetSocketAddress], offlineChannels: Set[HasCommitments]): ActorRef = {
    getPeer(remoteNodeId) match {
      case Some(peer) => peer
      case None =>
        log.info(s"creating new recovery peer current=${context.children.size}")
        val peer = context.actorOf(Props(new RecoveryPeer(nodeParams, remoteNodeId, authenticator, watcher, router, relayer, wallet, self)), name = peerActorName(remoteNodeId))
        peer ! Peer.Init(previousKnownAddress, offlineChannels)
        peer
    }
  }

}

class RecoveryPeer(override val nodeParams: NodeParams, remoteNodeId: PublicKey, authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet, recoveryFSM: ActorRef) extends Peer(nodeParams, remoteNodeId, authenticator, watcher, router, relayer, wallet) {

  override def whenConnected(event: Event): State = event match {
    case Event(msg: HasChannelId, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(msg)
      recoveryFSM ! ChannelFound(msg.channelId)
      d.channels.get(FinalChannelId(msg.channelId)) match {
        case Some(channel) => channel forward msg
        case None => d.transport ! wire.Error(msg.channelId, Peer.UNKNOWN_CHANNEL_MESSAGE)
      }
      stay

    case _ => super.whenConnected(event)
  }

}

//class RecoveryChannel(override val nodeParams: NodeParams, wallet: EclairWallet, remoteNodeId: PublicKey, blockchain: ActorRef, router: ActorRef, relayer: ActorRef, origin_opt: Option[ActorRef] = None) extends Channel(nodeParams, wallet, remoteNodeId, blockchain, router, relayer, origin_opt) {
//
//  log.info("!! Using recovery channel !!")
//
//}
