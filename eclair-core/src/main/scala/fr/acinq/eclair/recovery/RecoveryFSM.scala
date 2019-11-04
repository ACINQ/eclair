package fr.acinq.eclair.recovery

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, ActorSelection, PoisonPill, Props}
import fr.acinq.bitcoin.{ByteVector32, OP_2, OP_CHECKMULTISIG, OP_PUSHDATA, OutPoint, Script, ScriptWitness, Transaction}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.channel.{Channel, ChannelClosed, Commitments, DATA_CLOSING, HasCommitments, Helpers, INPUT_RESTORED, PleasePublishYourCommitment}
import fr.acinq.eclair.crypto.{Generators, KeyManager, TransportHandler}
import fr.acinq.eclair.io.Peer.{ConnectedData, Disconnect, FinalChannelId}
import fr.acinq.eclair.io.Switchboard.peerActorName
import fr.acinq.eclair.io.{NodeURI, Peer, PeerConnected, Switchboard}
import fr.acinq.eclair.recovery.RecoveryFSM._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire._
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

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
          context.system.scheduler.scheduleOnce(5 seconds)(self ! CheckCommitmentPublished)
          context.become(waitForRemoteToPublishCommitment(DATA_WAIT_FOR_REMOTE_PUBLISH(d.peer, reestablish, fundingTx, outIndex)))
      }
  }

  def waitForRemoteToPublishCommitment(d: DATA_WAIT_FOR_REMOTE_PUBLISH): Receive = {
    case CheckCommitmentPublished =>
      logger.info(s"looking for the commitment transaction")
      bitcoinClient.lookForSpendingTx(None, d.fundingTx.txid.toHex, d.fundingOutIndex).onComplete {
        case Success(commitTx) =>
          logger.info(s"found commitTx=${commitTx.txid}")

          val commitmentNumber = Transactions.decodeTxNumber(commitTx.txIn.head.sequence, commitTx.lockTime)
          assert(commitmentNumber == d.channelReestablish.nextLocalCommitmentNumber - 1)

          val fundingPubKey = recoverFundingKeyFromCommitment(nodeParams, commitTx, d.channelReestablish, commitmentNumber)
          val channelKeyPath = KeyManager.channelKeyPath(fundingPubKey)
          val commitmentPoint = nodeParams.keyManager.commitmentPoint(channelKeyPath, commitmentNumber)
          val paymentBasePoint = nodeParams.keyManager.paymentPoint(channelKeyPath)
          val localPaymentKey = Generators.derivePubKey(paymentBasePoint.publicKey, commitmentPoint)

          val finalScriptPubkey = Helpers.getFinalScriptPubKey(wallet, nodeParams.chainHash)
          val claimTx = Transactions.makeClaimP2WPKHOutputTx(commitTx, nodeParams.dustLimit, localPaymentKey, finalScriptPubkey, nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(6))
          val sig = nodeParams.keyManager.sign(claimTx, paymentBasePoint, d.channelReestablish.myCurrentPerCommitmentPoint.get)
          val claimSigned = Transactions.addSigs(claimTx, localPaymentKey, sig)
          bitcoinClient.publishTransaction(claimSigned.tx)
          context.system.scheduler.scheduleOnce(5 seconds)(self ! CheckClaimPublished)
          context.become(waitToPublishClaimTx(DATA_WAIT_FOR_CLAIM_TX(d.peer, claimSigned.tx)))

        case Failure(_) =>
          context.system.scheduler.scheduleOnce(5 seconds)(self ! CheckCommitmentPublished)
      }
  }

  def waitToPublishClaimTx(d: DATA_WAIT_FOR_CLAIM_TX): Receive = {
    case CheckClaimPublished =>
      bitcoinClient.getTransaction(d.claimTx.txid.toHex).onComplete {
        case Success(claimTx) =>
          logger.info(s"claim transaction published txid=${claimTx.txid}")
          d.peer ! Disconnect
          self ! PoisonPill
        case Failure(_) =>
          bitcoinClient.publishTransaction(d.claimTx)
          context.system.scheduler.scheduleOnce(5 seconds)(self ! CheckClaimPublished)
      }
  }

  /**
    * Given a channelId tries to guess the fundingTxId and retrieve the funding transaction
    */
  def lookupFundingTx(channelId: ByteVector32): Option[(Transaction, Int)] = {
    val candidateFundingTxIds = fundingIds(channelId)
    val fundingTx_opt = Await.result(Future.sequence(candidateFundingTxIds.map { case (txId, _) =>
      getTransaction(txId)
    }).map(_.flatten.headOption), 60 seconds)

    fundingTx_opt.map { funding =>
      (funding, candidateFundingTxIds.find(_._1 == funding.txid).map(_._2).get)
    }
  }

  /**
    * Extracts the funding_txid and output index from channelId, brute forces the ids up to @param limit
    */
  def fundingIds(channelId: ByteVector32, limit: Int = 5): Seq[(ByteVector32, Int)] = {
    0 until limit map { i =>
      (fr.acinq.eclair.toLongId(channelId.reverse, i), i)
    }
  }

  def getTransaction(txId: ByteVector32): Future[Option[Transaction]] = {
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
  case class DATA_WAIT_FOR_CLAIM_TX(peer: ActorRef, claimTx: Transaction) extends Data

  sealed trait Event
  case class RecoveryConnect(remote: NodeURI) extends Event
  case class ChannelFound(channelId: ByteVector32, reestablish: ChannelReestablish) extends Event
  case class SendErrorToRemote(error: Error) extends Event
  case object CheckCommitmentPublished extends Event
  case object CheckClaimPublished extends Event

  // extract our funding pubkey from witness
  def recoverFundingKeyFromCommitment(nodeParams: NodeParams, commitTx: Transaction, channelReestablish: ChannelReestablish, commitmentNumber: Long): PublicKey = {
    val (key1, key2) = extractKeysFromWitness(commitTx.txIn.head.witness, channelReestablish, commitmentNumber)

    if(isOurChannelKey(nodeParams.keyManager, commitTx, key1, commitmentNumber))
      key1
    else if(isOurChannelKey(nodeParams.keyManager, commitTx, key2, commitmentNumber))
      key2
    else
      throw new IllegalArgumentException("key not found")
  }

  def extractKeysFromWitness(witness: ScriptWitness, channelReestablish: ChannelReestablish, commitmentNumber: Long): (PublicKey, PublicKey) = {
    val ScriptWitness(Seq(ByteVector.empty, sig1, sig2, redeemScript)) = witness

    Script.parse(redeemScript) match {
      case OP_2 :: OP_PUSHDATA(key1, _) :: OP_PUSHDATA(key2, _) :: OP_2 :: OP_CHECKMULTISIG :: Nil => (PublicKey(key1), PublicKey(key2))
      case _ => throw new IllegalArgumentException(s"commitTx redeem script doesn't match, script=$redeemScript")
    }
  }

  def isOurChannelKey(keyManager: KeyManager, commitTx: Transaction, key: PublicKey, commitmentNumber: Long): Boolean = {
    val channelKeyPath = KeyManager.channelKeyPath(key)
    val commitmentPoint = keyManager.commitmentPoint(channelKeyPath, commitmentNumber)
    val paymentBasePoint = keyManager.paymentPoint(channelKeyPath).publicKey
    val localPaymentKey = Generators.derivePubKey(paymentBasePoint, commitmentPoint)

    val toRemoteScriptPubkey = Script.write(Script.pay2wpkh(localPaymentKey))
    commitTx.txOut.exists(_.publicKeyScript == toRemoteScriptPubkey)
  }

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

  override def whenConnected: StateFunction = {
    case Event(SendErrorToRemote(error), d: ConnectedData) =>
      d.transport ! error
      stay

    case Event(msg: ChannelReestablish, d: ConnectedData) =>
      d.transport ! TransportHandler.ReadAck(msg)
      recoveryFSM ! ChannelFound(msg.channelId, msg)
      // when recovering we don't immediately reply channel_reestablish/error
      stay

    case event => super.whenConnected(event)
  }

}