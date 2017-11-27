package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Status, SupervisorStrategy, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{MilliSatoshi, Satoshi}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.channel.{Channel, HasCommitments}
import fr.acinq.eclair.crypto.TransportHandler.HandshakeCompleted
import fr.acinq.eclair.router.Rebroadcast

/**
  * Ties network connections to peers.
  * Created by PM on 14/02/2017.
  */
class Switchboard(nodeParams: NodeParams, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) extends Actor with ActorLogging {

  import Switchboard._

  // we load peers and channels from database
  val initialPeers = {
    val channels = nodeParams.channelsDb.listChannels().toList.groupBy(_.commitments.remoteParams.nodeId)
    val peers = nodeParams.peersDb.listPeers().toMap
    channels
      .map {
        case (remoteNodeId, states) => (remoteNodeId, states, peers.get(remoteNodeId))
      }
      .map {
        case (remoteNodeId, states, address_opt) =>
          // we might not have an address if we didn't initiate the connection in the first place
          val peer = createOrGetPeer(Map(), remoteNodeId, address_opt, states.toSet)
          (remoteNodeId -> peer)
      }.toMap
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
      nodeParams.peersDb.removePeer(remoteNodeId)
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
        val peer = context.actorOf(Peer.props(nodeParams, remoteNodeId, address_opt, watcher, router, relayer, wallet, offlineChannels), name = s"peer-$remoteNodeId")
        context watch (peer)
        peer
    }
  }

  // we resume failing peers because they may have open channels that we don't want to close abruptly
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Resume }
}

object Switchboard {

  def props(nodeParams: NodeParams, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) = Props(new Switchboard(nodeParams, watcher, router, relayer, wallet))

  // @formatter:off
  case class NewChannel(fundingSatoshis: Satoshi, pushMsat: MilliSatoshi, channelFlags: Option[Byte]) {
    require(fundingSatoshis.amount < Channel.MAX_FUNDING_SATOSHIS, s"fundingSatoshis must be less than ${Channel.MAX_FUNDING_SATOSHIS}")
    require(pushMsat.amount < 1000 * fundingSatoshis.amount, s"pushMsat must be less or equal to fundingSatoshis")
  }
  case class NewConnection(remoteNodeId: PublicKey, address: InetSocketAddress, newChannel_opt: Option[NewChannel])
  // @formatter:on

}