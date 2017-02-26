package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status, Terminated}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi, ScriptElt}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.channel.ChannelRecord
import fr.acinq.eclair.crypto.TransportHandler.HandshakeCompleted
import fr.acinq.eclair.db.{ChannelState, SimpleDb}

/**
  * Ties network connections to peers.
  * Created by PM on 14/02/2017.
  */
class Switchboard(watcher: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: BinaryData, db: SimpleDb) extends Actor with ActorLogging {

  import Switchboard._
  val peerDb = Peer.makePeerDb(db)

  def receive: Receive = main(Map(), Map())


  def main(peers: Map[PublicKey, ActorRef], connections: Map[PublicKey, ActorRef]): Receive = {

    case PeerRecord(publicKey, address) if peers.contains(publicKey) => ()

    case PeerRecord(publicKey, address) =>
      val peer = createPeer(publicKey, address)
      context become main(peers + (publicKey -> peer), connections)

    case ChannelRecord(id, ChannelState(remotePubKey, _, _)) if !peers.contains(remotePubKey) =>
      log.warning(s"received channel data for unknown peer $remotePubKey")

    case channelRecord: ChannelRecord => peers(channelRecord.state.remotePubKey) forward channelRecord

    case NewConnection(Globals.Node.publicKey, _, _) =>
      sender ! Status.Failure(new RuntimeException("cannot open connection with oneself"))

    case NewConnection(remoteNodeId, address, newChannel_opt) =>
      val connection = connections.get(remoteNodeId) match {
        case Some(connection) =>
          log.info(s"already connected to nodeId=$remoteNodeId")
          sender ! s"already connected to nodeId=$remoteNodeId"
          connection
        case None =>
          log.info(s"connecting to $remoteNodeId @ $address")
          val connection = context.actorOf(Client.props(self, address, remoteNodeId, sender))
          context watch(connection)
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
    peerDb.put(remoteNodeId, PeerRecord(remoteNodeId, address_opt))
    context.actorOf(Peer.props(remoteNodeId, address_opt, watcher, router, relayer, defaultFinalScriptPubKey, db), name = s"peer-$remoteNodeId")
  }
}

object Switchboard {

  def props(watcher: ActorRef, router: ActorRef, relayer: ActorRef, defaultFinalScriptPubKey: BinaryData, db: SimpleDb) = Props(classOf[Switchboard], watcher, router, relayer, defaultFinalScriptPubKey, db)

  // @formatter:off
  case class NewChannel(fundingSatoshis: Satoshi, pushMsat: MilliSatoshi)
  case class NewConnection(remoteNodeId: PublicKey, address: InetSocketAddress, newChannel_opt: Option[NewChannel])
  // @formatter:on

}