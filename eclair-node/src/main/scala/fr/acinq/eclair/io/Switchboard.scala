package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.crypto.TransportHandler.HandshakeCompleted

/**
  * Ties network connections to peers.
  * Created by PM on 14/02/2017.
  */
class Switchboard(nodeParams: NodeParams, watcher: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: BinaryData) extends Actor with ActorLogging {

  import Switchboard._

  def receive: Receive = main(Map(), Map())


  def main(peers: Map[PublicKey, ActorRef], connections: Map[PublicKey, ActorRef]): Receive = {

    case PeerRecord(publicKey, address) if peers.contains(publicKey) => ()

    case PeerRecord(publicKey, address) =>
      val peer = createPeer(publicKey, address)
      context become main(peers + (publicKey -> peer), connections)

    case channelState: HasCommitments if !peers.contains(channelState.commitments.remoteParams.nodeId) =>
      log.warning(s"received channel data for unknown peer ${channelState.commitments.remoteParams.nodeId}")

    case channelState: HasCommitments => peers(channelState.commitments.remoteParams.nodeId) forward channelState

    case NewConnection(publicKey, _, _) if publicKey == nodeParams.privateKey.publicKey =>

    case NewConnection(remoteNodeId, address, newChannel_opt) =>
      val connection = connections.get(remoteNodeId) match {
        case Some(connection) =>
          log.info(s"already connected to nodeId=$remoteNodeId")
          sender ! s"already connected to nodeId=$remoteNodeId"
          connection
        case None =>
          log.info(s"connecting to $remoteNodeId @ $address")
          val connection = context.actorOf(Client.props(nodeParams, self, address, remoteNodeId, sender))
          context watch (connection)
          connection
      }
      val peer = peers.get(remoteNodeId) match {
        case Some(peer) => peer
        case None => createPeer(remoteNodeId, Some(address))
      }
      newChannel_opt.foreach(peer forward _)
      context become main(peers + (remoteNodeId -> peer), connections + (remoteNodeId -> connection))

    case Terminated(actor) if connections.values.toSet.contains(actor) =>
      log.info(s"$actor is dead, removing from connections")
      val remoteNodeId = connections.find(_._2 == actor).get._1
      context become main(peers, connections - remoteNodeId)

    case h@HandshakeCompleted(_, remoteNodeId) =>
      val peer = peers.getOrElse(remoteNodeId, createPeer(remoteNodeId, None))
      peer forward h
      context become main(peers + (remoteNodeId -> peer), connections)

    case 'peers =>
      sender ! peers.keys

  }

  def createPeer(remoteNodeId: PublicKey, address_opt: Option[InetSocketAddress]) = {
    nodeParams.peersDb.put(remoteNodeId, PeerRecord(remoteNodeId, address_opt))
    context.actorOf(Peer.props(nodeParams, remoteNodeId, address_opt, watcher, router, relayer, defaultFinalScriptPubKey), name = s"peer-$remoteNodeId")
  }
}

object Switchboard {

  def props(nodeParams: NodeParams, watcher: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: BinaryData) = Props(new Switchboard(nodeParams, watcher, router, relayer, defaultFinalScriptPubKey))

  // @formatter:off
  case class NewChannel(fundingSatoshis: Satoshi, pushMsat: MilliSatoshi)
  case class NewConnection(remoteNodeId: PublicKey, address: InetSocketAddress, newChannel_opt: Option[NewChannel])
  // @formatter:on

}