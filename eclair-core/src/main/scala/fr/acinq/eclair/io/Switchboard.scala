package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Status, SupervisorStrategy, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.router.Rebroadcast

/**
  * Ties network connections to peers.
  * Created by PM on 14/02/2017.
  */
class Switchboard(nodeParams: NodeParams, authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) extends Actor with ActorLogging {

  authenticator ! self

  // we load peers and channels from database
  private val initialPeers = {
    val channels = nodeParams.channelsDb.listChannels().groupBy(_.commitments.remoteParams.nodeId)
    val peers = nodeParams.peersDb.listPeers()
    channels
      .map {
        case (remoteNodeId, states) => (remoteNodeId, states, peers.get(remoteNodeId))
      }
      .map {
        case (remoteNodeId, states, address_opt) =>
          // we might not have an address if we didn't initiate the connection in the first place
          val peer = createOrGetPeer(Map(), remoteNodeId, previousKnownAddress = address_opt, offlineChannels = states.toSet)
          remoteNodeId -> peer
      }.toMap
  }

  def receive: Receive = main(initialPeers)

  def main(peers: Map[PublicKey, ActorRef]): Receive = {

    case Peer.Connect(NodeURI(publicKey, _)) if publicKey == nodeParams.nodeId =>
      sender ! Status.Failure(new RuntimeException("cannot open connection with oneself"))

    case c@Peer.Connect(NodeURI(remoteNodeId, _)) =>
      // we create a peer if it doesn't exist
      val peer = createOrGetPeer(peers, remoteNodeId, previousKnownAddress = None, offlineChannels = Set.empty)
      peer forward c
      context become main(peers + (remoteNodeId -> peer))

    case o@Peer.OpenChannel(remoteNodeId, _, _, _, _) =>
      peers.get(remoteNodeId) match {
        case Some(peer) => peer forward o
        case None => sender ! Status.Failure(new RuntimeException("no connection to peer"))
      }

    case Terminated(actor) =>
      peers.collectFirst {
        case (remoteNodeId, peer) if peer == actor =>
          log.debug(s"$actor is dead, removing from peers")
          nodeParams.peersDb.removePeer(remoteNodeId)
          context become main(peers - remoteNodeId)
      }

    case auth@Authenticator.Authenticated(_, _, remoteNodeId, _, _, _) =>
      // if this is an incoming connection, we might not yet have created the peer
      val peer = createOrGetPeer(peers, remoteNodeId, previousKnownAddress = None, offlineChannels = Set.empty)
      peer forward auth
      context become main(peers + (remoteNodeId -> peer))

    case r: Rebroadcast => peers.values.foreach(_ forward r)

    case 'peers => sender ! peers

  }

  /**
    *
    * @param peers
    * @param remoteNodeId
    * @param previousKnownAddress only to be set if we know for sure that this ip worked in the past
    * @param offlineChannels
    * @return
    */
  def createOrGetPeer(peers: Map[PublicKey, ActorRef], remoteNodeId: PublicKey, previousKnownAddress: Option[InetSocketAddress], offlineChannels: Set[HasCommitments]) = {
    peers.get(remoteNodeId) match {
      case Some(peer) => peer
      case None =>
        log.info(s"creating new peer current=${peers.size}")
        val peer = context.actorOf(Peer.props(nodeParams, remoteNodeId, authenticator, watcher, router, relayer, wallet), name = s"peer-$remoteNodeId")
        peer ! Peer.Init(previousKnownAddress, offlineChannels)
        context watch (peer)
        peer
    }
  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled message=$message")

  // we resume failing peers because they may have open channels that we don't want to close abruptly
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Resume }
}

object Switchboard {

  def props(nodeParams: NodeParams, authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) = Props(new Switchboard(nodeParams, authenticator, watcher, router, relayer, wallet))

}