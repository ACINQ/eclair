package fr.acinq.eclair

import akka.actor.ActorSystem
import fr.acinq.bitcoin.{BinaryData, BitcoinJsonRPCClient, Satoshi, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.peer.{NewBlock, NewTransaction}
import fr.acinq.eclair.channel.Scripts

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Created by PM on 26/04/2016.
  */
class TestBitcoinClient()(implicit system: ActorSystem) extends ExtendedBitcoinClient(new BitcoinJsonRPCClient("", "", "", 0)) {

  client.client.close()

  import scala.concurrent.ExecutionContext.Implicits.global
  system.scheduler.schedule(100 milliseconds, 100 milliseconds, new Runnable {
    override def run(): Unit = system.eventStream.publish(NewBlock(null)) // blocks are not actually interpreted
  })

  override def makeAnchorTx(ourCommitPub: BinaryData, theirCommitPub: BinaryData, amount: Satoshi)(implicit ec: ExecutionContext): Future[(Transaction, Int)] = {
    val anchorTx = Transaction(version = 1,
      txIn = Seq.empty[TxIn],
      txOut = TxOut(amount, Scripts.anchorPubkeyScript(ourCommitPub, theirCommitPub)) :: Nil,
      lockTime = 0
    )
    Future.successful((anchorTx, 0))
  }

  override def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[String] = {
    system.eventStream.publish(NewTransaction(tx))
    Future.successful(tx.txid.toString())
  }

  override def getTxConfirmations(txId: String)(implicit ec: ExecutionContext): Future[Option[Int]] = Future.successful(Some(10))

  override def getTransaction(txId: String)(implicit ec: ExecutionContext): Future[Transaction] = ???

  override def fundTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[FundTransactionResponse] = ???

  override def signTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = ???

}
