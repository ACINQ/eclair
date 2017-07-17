package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Status, SupervisorStrategy, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.crypto.TransportHandler.HandshakeCompleted
import fr.acinq.eclair.router.Rebroadcast

/**
  * Ties network connections to peers.
  * Created by PM on 14/02/2017.
  */
class Switchboard(nodeParams: NodeParams, watcher: ActorRef, router: ActorRef, relayer: ActorRef) extends Actor with ActorLogging {

  import Switchboard._

  // we load peers and channels from database
  val initialPeers = nodeParams.channelsDb.values
    .groupBy(_.commitments.remoteParams.nodeId)
      .map {
        case (remoteNodeId, states) =>
          // we might not have an adress if we didn't initiate the connection in the first place
          val address_opt = nodeParams.peersDb.get(remoteNodeId).map(_.address)
          val peer = createOrGetPeer(Map(), remoteNodeId, address_opt, states.toSet)
          (remoteNodeId -> peer)
      }

  def receive: Receive = main(initialPeers, Map())

  def main(peers: Map[PublicKey, ActorRef], connections: Map[PublicKey, ActorRef]): Receive = {

    case NewConnection(publicKey, _, _) if publicKey == nodeParams.privateKey.publicKey =>
      sender ! Status.Failure(new RuntimeException("cannot open connection with oneself"))

    case NewConnection(remoteNodeId, address, newChannel_opt) =>
      val connection = connections.get(remoteNodeId) match {
        case Some(connection) =>
          log.info(s"already connected to nodeId=$remoteNodeId")
          sender ! s"already connected to nodeId=$remoteNodeId"
          connection
        case None =>
          log.info(s"connecting to $remoteNodeId @ $address on behalf of $sender")
          val connection = context.actorOf(Client.props(nodeParams, self, address, remoteNodeId, sender))
          context watch (connection)
          connection
      }
      val peer = createOrGetPeer(peers, remoteNodeId, Some(address), Set.empty)
      newChannel_opt.foreach(peer forward _)
      context become main(peers + (remoteNodeId -> peer), connections + (remoteNodeId -> connection))

    case Terminated(actor) if connections.values.toSet.contains(actor) =>
      log.info(s"$actor is dead, removing from connections")
      val remoteNodeId = connections.find(_._2 == actor).get._1
      context become main(peers, connections - remoteNodeId)

    case Terminated(actor) if peers.values.toSet.contains(actor) =>
      log.info(s"$actor is dead, removing from peers/connections/db")
      val remoteNodeId = peers.find(_._2 == actor).get._1
      nodeParams.peersDb.delete(remoteNodeId)
      context become main(peers - remoteNodeId, connections - remoteNodeId)

    case h@HandshakeCompleted(_, remoteNodeId) =>
      val peer = createOrGetPeer(peers, remoteNodeId, None, Set.empty)
      peer forward h
      context become main(peers + (remoteNodeId -> peer), connections)

    case r: Rebroadcast => peers.values.foreach(_ forward r)

    case 'peers => sender ! peers

    case 'connections => sender ! connections

  }

  def createOrGetPeer(peers: Map[PublicKey, ActorRef], remoteNodeId: PublicKey, address_opt: Option[InetSocketAddress], offlineChannels: Set[HasCommitments]) = {
    peers.get(remoteNodeId) match {
      case Some(peer) => peer
      case None =>
        val peer = context.actorOf(Peer.props(nodeParams, remoteNodeId, address_opt, watcher, router, relayer, offlineChannels), name = s"peer-$remoteNodeId")
        context watch (peer)
        peer
    }
  }

  // we resume failing peers because they may have open channels that we don't want to close abruptly
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Resume }
}

object Switchboard {

  def props(nodeParams: NodeParams, watcher: ActorRef, router: ActorRef, relayer: ActorRef) = Props(new Switchboard(nodeParams, watcher, router, relayer))

  // @formatter:off
  case class NewChannel(fundingSatoshis: Satoshi, pushMsat: MilliSatoshi, channelFlags: Option[Byte])
  case class NewConnection(remoteNodeId: PublicKey, address: InetSocketAddress, newChannel_opt: Option[NewChannel])
  // @formatter:on

}