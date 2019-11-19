package fr.acinq.eclair.recovery


import akka.actor.{ActorRef, FSM, Props}
import fr.acinq.bitcoin.{Base58, Base58Check, Bech32, ByteVector32, OP_0, OP_2, OP_CHECKMULTISIG, OP_CHECKSIG, OP_DUP, OP_EQUAL, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, OutPoint, Script, ScriptWitness, Transaction}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.channel.{Helpers, PleasePublishYourCommitment}
import fr.acinq.eclair.crypto.{Generators, KeyManager}
import fr.acinq.eclair.io.Peer.Disconnect
import fr.acinq.eclair.io.{NodeURI, Peer, PeerConnected}
import fr.acinq.eclair.recovery.RecoveryFSM._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire._
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Success

class RecoveryFSM(remoteNodeURI: NodeURI, nodeParams: NodeParams, wallet: EclairWallet, bitcoinJsonRPCClient: BitcoinJsonRPCClient) extends FSM[State, Data] with Logging {

  implicit val ec = context.system.dispatcher
  val bitcoinClient = new ExtendedBitcoinClient(bitcoinJsonRPCClient)
  val CHECK_POLL_INTERVAL = 3 seconds

  context.system.eventStream.subscribe(self, classOf[PeerConnected])

  startWith(RECOVERY_WAIT_FOR_CONNECTION, Nothing)

  self ! RecoveryConnect(remoteNodeURI)

  when(RECOVERY_WAIT_FOR_CONNECTION) {
    case Event(RecoveryConnect(nodeURI: NodeURI), Nothing) =>
      logger.info(s"creating new recovery peer")
      val peer = context.actorOf(Props(new RecoveryPeer(nodeParams, nodeURI.nodeId)))
      peer ! Peer.Connect(nodeURI.nodeId, Some(nodeURI.address))
      stay using DATA_WAIT_FOR_CONNECTION(nodeURI.nodeId)

    case Event(PeerConnected(peer, nodeId), d: DATA_WAIT_FOR_CONNECTION) if d.remoteNodeId == nodeId =>
      logger.info(s"connected to remote $nodeId")
      goto(RECOVERY_WAIT_FOR_CHANNEL) using DATA_WAIT_FOR_REMOTE_INFO(peer, nodeId)
  }

  when(RECOVERY_WAIT_FOR_CHANNEL) {
    case Event(ChannelFound(channelId, reestablish), d: DATA_WAIT_FOR_REMOTE_INFO) =>
      logger.info(s"peer=${d.remoteNodeId} knows channelId=$channelId")
      lookupFundingTx(channelId) match {
        case None =>
          logger.info(s"could not find funding transaction...disconnecting")
          d.peer ! Disconnect(d.remoteNodeId)
          stop()

        case Some((fundingTx, outIndex)) =>
          logger.info(s"found unspent channel funding_tx=${fundingTx.txid} outputIndex=$outIndex")
          logger.info(s"asking remote to close the channel")
          d.peer ! SendErrorToRemote(Error(channelId, PleasePublishYourCommitment(channelId).toString))
          context.system.scheduler.scheduleOnce(5 seconds)(self ! CheckCommitmentPublished)
          goto(RECOVERY_WAIT_FOR_COMMIT_PUBLISHED) using DATA_WAIT_FOR_REMOTE_PUBLISH(d.peer, reestablish, fundingTx, outIndex)
      }
  }

  when(RECOVERY_WAIT_FOR_COMMIT_PUBLISHED) {
    case Event(CheckCommitmentPublished, d: DATA_WAIT_FOR_REMOTE_PUBLISH) =>
      logger.info(s"looking for the commitment transaction")
      Await.ready(lookForCommitTx(d.fundingTx.txid, d.fundingOutIndex), 30 seconds).value match {
        case Some(Success(commitTx)) =>
          logger.info(s"found commitTx=${commitTx.txid}")

          val Some(remotePerCommitmentSecret) = d.channelReestablish.myCurrentPerCommitmentPoint
          val fundingPubKey = recoverFundingKeyFromCommitment(nodeParams, commitTx, d.channelReestablish)
          val channelKeyPath = KeyManager.channelKeyPath(fundingPubKey)
          val paymentBasePoint = nodeParams.keyManager.paymentPoint(channelKeyPath)
          val localPaymentKey = Generators.derivePubKey(paymentBasePoint.publicKey, remotePerCommitmentSecret)

          val finalScriptPubkey = Helpers.getFinalScriptPubKey(wallet, nodeParams.chainHash)
          val claimTx = Transactions.makeClaimP2WPKHOutputTx(commitTx, nodeParams.dustLimit, localPaymentKey, finalScriptPubkey, nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(6))
          val sig = nodeParams.keyManager.sign(claimTx, paymentBasePoint, remotePerCommitmentSecret)
          val claimSigned = Transactions.addSigs(claimTx, localPaymentKey, sig)
          logger.info(s"publishing claim-main-output transaction: address=${scriptPubKeyToAddress(finalScriptPubkey)} txid=${claimSigned.tx.txid}")
          bitcoinClient.publishTransaction(claimSigned.tx)
          context.system.scheduler.scheduleOnce(CHECK_POLL_INTERVAL)(self ! CheckClaimPublished)
          goto(RECOVERY_WAIT_FOR_CLAIM_PUBLISHED) using DATA_WAIT_FOR_CLAIM_TX(d.peer, claimSigned.tx)

        case _ =>
          context.system.scheduler.scheduleOnce(CHECK_POLL_INTERVAL)(self ! CheckCommitmentPublished)
          stay()
      }
  }

  when(RECOVERY_WAIT_FOR_CLAIM_PUBLISHED) {
    case Event(CheckClaimPublished, d: DATA_WAIT_FOR_CLAIM_TX) =>
      Await.ready(bitcoinClient.getTransaction(d.claimTx.txid.toHex), 30 seconds).value match {
        case Some(Success(claimTx)) =>
          logger.info(s"claim transaction published txid=${claimTx.txid}")
          d.peer ! Disconnect(remoteNodeURI.nodeId)
          stop()

        case _ =>
          bitcoinClient.publishTransaction(d.claimTx)
          context.system.scheduler.scheduleOnce(CHECK_POLL_INTERVAL)(self ! CheckClaimPublished)
          stay
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

  /**
    * Lookup a commitTx spending the fundingTx in the mempool and then in the blocks
    */
  def lookForCommitTx(fundingTxId: ByteVector32, fundingOutIndex: Int): Future[Transaction] = {
    bitcoinClient.getMempool().map { mempoolTxs =>
      mempoolTxs.find(_.txIn.exists(_.outPoint == OutPoint(fundingTxId.reverse, fundingOutIndex))).get
    }.recoverWith { case _ =>
      bitcoinClient.lookForSpendingTx(None, fundingTxId.toHex, fundingOutIndex)
    }
  }

  def scriptPubKeyToAddress(scriptPubKey: ByteVector) = Script.parse(scriptPubKey) match {
    case OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubKeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil =>
      Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pubKeyHash)
    case OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil =>
      Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, scriptHash)
    case OP_0 :: OP_PUSHDATA(pubKeyHash, _) :: Nil if pubKeyHash.length == 20 => Bech32.encodeWitnessAddress("bcrt", 0, pubKeyHash)
    case OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil if scriptHash.length == 32 => Bech32.encodeWitnessAddress("bcrt", 0, scriptHash)
    case _ => throw new IllegalArgumentException(s"non standard scriptPubkey=$scriptPubKey")
  }
}

object RecoveryFSM {

  val actorName = "recovery-fsm-actor"

  def props(nodeURI: NodeURI, nodeParams: NodeParams, wallet: EclairWallet, bitcoinJsonRPCClient: BitcoinJsonRPCClient) = Props(new RecoveryFSM(nodeURI, nodeParams, wallet, bitcoinJsonRPCClient))

  // formatter: off
  sealed trait State
  case object RECOVERY_WAIT_FOR_CONNECTION extends State
  case object RECOVERY_WAIT_FOR_CHANNEL extends State
  case object RECOVERY_WAIT_FOR_COMMIT_PUBLISHED extends State
  case object RECOVERY_WAIT_FOR_CLAIM_PUBLISHED extends State

  sealed trait Data
  case object Nothing extends Data
  case class DATA_WAIT_FOR_CONNECTION(remoteNodeId: PublicKey) extends Data
  case class DATA_WAIT_FOR_REMOTE_INFO(peer: ActorRef, remoteNodeId: PublicKey) extends Data
  case class DATA_WAIT_FOR_REMOTE_PUBLISH(peer: ActorRef, channelReestablish: ChannelReestablish, fundingTx: Transaction, fundingOutIndex: Int) extends Data
  case class DATA_WAIT_FOR_CLAIM_TX(peer: ActorRef, claimTx: Transaction) extends Data

  sealed trait Event
  case class RecoveryConnect(remote: NodeURI) extends Event
  case class ChannelFound(channelId: ByteVector32, reestablish: ChannelReestablish) extends Event
  case class SendErrorToRemote(error: Error) extends Event
  case object CheckCommitmentPublished extends Event
  case object CheckClaimPublished extends Event
  // formatter: on

  def recoverFundingKeyFromCommitment(nodeParams: NodeParams, commitTx: Transaction, channelReestablish: ChannelReestablish): PublicKey = {
    val (key1, key2) = extractKeysFromWitness(commitTx.txIn.head.witness, channelReestablish)

    if(isOurFundingKey(nodeParams.keyManager, commitTx, key1, channelReestablish))
      key1
    else if(isOurFundingKey(nodeParams.keyManager, commitTx, key2, channelReestablish))
      key2
    else
      throw new IllegalArgumentException("key not found, output trimmed?")
  }

  def extractKeysFromWitness(witness: ScriptWitness, channelReestablish: ChannelReestablish): (PublicKey, PublicKey) = {
    val ScriptWitness(Seq(ByteVector.empty, _, _, redeemScript)) = witness

    Script.parse(redeemScript) match {
      case OP_2 :: OP_PUSHDATA(key1, _) :: OP_PUSHDATA(key2, _) :: OP_2 :: OP_CHECKMULTISIG :: Nil => (PublicKey(key1), PublicKey(key2))
      case _ => throw new IllegalArgumentException(s"commitTx redeem script doesn't match, script=$redeemScript")
    }
  }

  def isOurFundingKey(keyManager: KeyManager, commitTx: Transaction, key: PublicKey, channelReestablish: ChannelReestablish): Boolean = {
    val channelKeyPath = KeyManager.channelKeyPath(key)
    val paymentBasePoint = keyManager.paymentPoint(channelKeyPath).publicKey
    val localPaymentKey = Generators.derivePubKey(paymentBasePoint, channelReestablish.myCurrentPerCommitmentPoint.get)
    val toRemoteScriptPubkey = Script.write(Script.pay2wpkh(localPaymentKey))

    commitTx.txOut.exists(_.publicKeyScript == toRemoteScriptPubkey)
  }

}