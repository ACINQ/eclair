package fr.acinq.eclair.recovery

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, ActorSelection, PoisonPill, Props}
import fr.acinq.bitcoin.{ByteVector32, Transaction}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.channel.{Channel, HasCommitments, PleasePublishYourCommitment}
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.io.Peer.{ConnectedData, Disconnect, FinalChannelId}
import fr.acinq.eclair.io.Switchboard.peerActorName
import fr.acinq.eclair.io.{NodeURI, Peer, PeerConnected, Switchboard}
import fr.acinq.eclair.recovery.RecoveryFSM._
import fr.acinq.eclair.wire._
import grizzled.slf4j.Logging

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class RecoveryFSM(nodeURI: NodeURI, val nodeParams: NodeParams, authenticator: ActorRef, router: ActorRef, switchboard: ActorRef, val wallet: EclairWallet, blockchain: ActorRef, relayer: ActorRef, bitcoinJsonRPCClient: BitcoinJsonRPCClient) extends Actor with Logging {

  implicit val ec = context.system.dispatcher
  val bitcoinClient = new ExtendedBitcoinClient(bitcoinJsonRPCClient)

  context.system.eventStream.subscribe(self, classOf[PeerConnected])
  self ! RecoveryConnect(nodeURI)

  override def receive: Receive = waitingForConnection()

  def waitingForConnection(): Receive = {
    case RecoveryConnect(nodeURI: NodeURI) =>
      logger.info(s"creating new recovery peer")
      val peer = context.actorOf(Props(new RecoveryPeer(nodeParams, nodeURI.nodeId, authenticator, blockchain, router, relayer, wallet)))
      peer ! Peer.Init(previousKnownAddress = None, storedChannels = Set.empty)
      peer ! Peer.Connect(nodeURI.nodeId, Some(nodeURI.address))
      context.become(waitingForConnection())
    case PeerConnected(peer, nodeId) if nodeId == nodeURI.nodeId =>
      logger.info(s"Connected to remote $nodeId")
      context.become(waitingForRemoteChannelInfo(DATA_WAIT_FOR_REMOTE_INFO(peer)))
  }

  def waitingForRemoteChannelInfo(d: DATA_WAIT_FOR_REMOTE_INFO): Receive = {
    case ChannelFound(channelId, reestablish) =>
      logger.info(s"peer=${nodeURI.nodeId} knows channelId=$channelId")

      lookupFundingTx(channelId) match {
        case None =>
          logger.info(s"could not find funding transaction...disconnecting")
          d.peer ! Disconnect
          self ! PoisonPill

        case Some((fundingTx, outIndex)) =>
          logger.info(s"found unspent channel funding_tx=${fundingTx.txid} outputIndex=$outIndex")
          logger.info(s"asking remote to close the channel")
          d.peer ! Error(channelId, PleasePublishYourCommitment(channelId).toString)
          context.system.scheduler.scheduleOnce(10 seconds)(self ! CheckCommitmentPublished)
          context.become(waitForRemoteToPublishCommitment(DATA_WAIT_FOR_REMOTE_PUBLISH(d.peer, reestablish, fundingTx, outIndex)))
      }
  }

  def waitForRemoteToPublishCommitment(d: DATA_WAIT_FOR_REMOTE_PUBLISH): Receive = {
    case CheckCommitmentPublished =>
      bitcoinClient.lookForSpendingTx(None, d.fundingTx.txid.toHex, d.fundingOutIndex).onComplete {
        case Success(commitTx) =>
          recoverFromCommitment(commitTx)
          logger.info(s"recovery done")
          d.peer ! Disconnect
          self ! PoisonPill
        case Failure(_) => context.system.scheduler.scheduleOnce(10 seconds)(self ! CheckCommitmentPublished)
      }
  }

  def recoverFromCommitment(commitTx: Transaction) = {
    logger.info("we made it!")
  }

  def lookupFundingTx(channelId: ByteVector32): Option[(Transaction, Int)] = {
    val candidateFundingTxIds = fundingIds(channelId)
    logger.info(s"computed funding txids=${candidateFundingTxIds.map(_._1)}")
    val fundingTx_opt = Await.result(Future.sequence(candidateFundingTxIds.map { case (txId, _) =>
      transactionExists(txId)
    }).map(_.flatten.headOption), 60 seconds)

    fundingTx_opt.map { funding =>
      (funding, candidateFundingTxIds.find(_._1 == funding.txid).map(_._2).get)
    }
  }

  /**
    * Extracts the funding_txid and output index from channelId
    */
  def fundingIds(channelId: ByteVector32, limit: Int = 5): Seq[(ByteVector32, Int)] = {
    0 until limit map { i =>
      (fr.acinq.eclair.toLongId(channelId.reverse, i), i)
    }
  }

  def transactionExists(txId: ByteVector32): Future[Option[Transaction]] = {
    bitcoinClient.getTransaction(txId.toHex).collect {
      case tx: Transaction => Some(tx)
    }.recover {
      case _ => None
    }
  }

}

object RecoveryFSM {

  val actorName = "recovery-fsm-actor"

  sealed trait State
  case object WAIT_FOR_CONNECTION extends State
  case object WAIT_FOR_CHANNEL extends State

  sealed trait Data
  case class DATA_WAIT_FOR_REMOTE_INFO(peer: ActorRef) extends Data
  case class DATA_WAIT_FOR_REMOTE_PUBLISH(peer: ActorRef, channelReestablish: ChannelReestablish, fundingTx: Transaction, fundingOutIndex: Int) extends Data

  sealed trait Event
  case class RecoveryConnect(remote: NodeURI) extends Event
  case class ChannelFound(channelId: ByteVector32, reestablish: ChannelReestablish) extends Event
  case class SendErrorToRemote(error: Error) extends Event
  case object CheckCommitmentPublished extends Event
}

class RecoverySwitchBoard(nodeParams: NodeParams, authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) extends Switchboard(nodeParams, authenticator, watcher, router, relayer, wallet) {

  override def createOrGetPeer(remoteNodeId: PublicKey, previousKnownAddress: Option[InetSocketAddress], offlineChannels: Set[HasCommitments]): ActorRef = {
    getPeer(remoteNodeId) match {
      case Some(peer) => peer
      case None =>
        log.info(s"creating new recovery peer current=${context.children.size}")
        val peer = context.actorOf(Props(new RecoveryPeer(nodeParams, remoteNodeId, authenticator, watcher, router, relayer, wallet)), name = peerActorName(remoteNodeId))
        peer ! Peer.Init(previousKnownAddress, offlineChannels)
        peer
    }
  }

}

class RecoveryPeer(override val nodeParams: NodeParams, remoteNodeId: PublicKey, authenticator: ActorRef, watcher: ActorRef, router: ActorRef, relayer: ActorRef, wallet: EclairWallet) extends Peer(nodeParams, remoteNodeId, authenticator, watcher, router, relayer, wallet) {

  def recoveryFSM: ActorSelection = context.system.actorSelection(context.system / RecoveryFSM.actorName)

  override def whenConnected(event: Event): State = event match {
    case Event(SendErrorToRemote(error), d: ConnectedData) =>
      d.transport ! error
      stay

    case Event(msg: ChannelReestablish, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(msg)
      recoveryFSM ! ChannelFound(msg.channelId, msg)
      stay

    case _ => super.whenConnected(event)
  }

}