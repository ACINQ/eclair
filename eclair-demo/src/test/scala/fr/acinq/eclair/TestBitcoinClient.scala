package fr.acinq.eclair

import akka.actor.ActorRef
import fr.acinq.bitcoin.{BinaryData, BitcoinJsonRPCClient, Satoshi, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.ExtendedBitcoinClient
import fr.acinq.eclair.channel.Scripts

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 26/04/2016.
  */
class TestBitcoinClient(probe: Option[ActorRef]= None) extends ExtendedBitcoinClient(new BitcoinJsonRPCClient("", "", "", 0)) {

  client.client.close()

  override def makeAnchorTx(ourCommitPub: BinaryData, theirCommitPub: BinaryData, amount: Long)(implicit ec: ExecutionContext): Future[(Transaction, Int)] = {
    val anchorTx = Transaction(version = 1,
      txIn = Seq.empty[TxIn],
      txOut = TxOut(Satoshi(amount), Scripts.anchorPubkeyScript(ourCommitPub, theirCommitPub)) :: Nil,
      lockTime = 0
    )
    Future.successful((anchorTx, 0))
  }

  override def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[String] = {
    probe.map(_ ! tx)
    Future.successful(tx.txid.toString())
  }

  override def getTxConfirmations(txId: String)(implicit ec: ExecutionContext): Future[Option[Int]] = Future.successful(Some(10))

  override def isUnspent(txId: String, outputIndex: Int)(implicit ec: ExecutionContext): Future[Boolean] = ???

  override def getTransaction(txId: String)(implicit ec: ExecutionContext): Future[Transaction] = ???

  override def fundTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[FundTransactionResponse] = ???

  override def signTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = ???

}
