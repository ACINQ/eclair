package fr.acinq.eclair

import akka.actor.ActorSystem
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Satoshi, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.ExtendedBitcoinClient.SignTransactionResponse
import fr.acinq.eclair.blockchain.peer.{NewBlock, NewTransaction}
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.transactions.Scripts

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 26/04/2016.
  */
class TestBitcoinClient()(implicit system: ActorSystem) extends ExtendedBitcoinClient(new BitcoinJsonRPCClient("", "", "", 0)) {

  import scala.concurrent.ExecutionContext.Implicits.global

  system.scheduler.schedule(100 milliseconds, 100 milliseconds, new Runnable {
    override def run(): Unit = system.eventStream.publish(NewBlock(null)) // blocks are not actually interpreted
  })

  override def makeFundingTx(ourCommitPub: PublicKey, theirCommitPub: PublicKey, amount: Satoshi)(implicit ec: ExecutionContext): Future[(Transaction, Int)] = {
    val anchorTx = Transaction(version = 1,
      txIn = Seq.empty[TxIn],
      txOut = TxOut(amount, Script.pay2wsh(Scripts.multiSig2of2(ourCommitPub, theirCommitPub))) :: Nil,
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

  override def fundTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[ExtendedBitcoinClient.FundTransactionResponse] = ???

  override def signTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = ???

  override def getTransactionShortId(txId: String)(implicit ec: ExecutionContext): Future[(Int, Int)] = Future.successful((42000, 42))

}
