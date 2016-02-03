package fr.acinq.eclair.blockchain

import akka.actor.{Cancellable, Actor, ActorLogging}
import fr.acinq.bitcoin.{Transaction, JsonRPCError, BitcoinJsonRPCClient}
import org.bouncycastle.util.encoders.Hex
import org.json4s.{FieldSerializer, CustomSerializer}
import org.json4s.JsonAST._
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._

/**
  * Simple blockchain watcher that periodically polls bitcoin-core using rpc api
  * /!\ Obviously not scalable /!\
  * Created by PM on 28/08/2015.
  */
class PollingWatcher(client: BitcoinJsonRPCClient)(implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  import PollingWatcher._

  context.become(watching(Map()))

  override def receive: Receive = ???

  def watching(watches: Map[Watch, Cancellable]): Receive = {
    case w: WatchConfirmedBasedOnOutputs => log.warning(s"ignoring $w (not implemented)")

    case w: WatchLost => log.warning(s"ignoring $w (not implemented)")

    case w: Watch if !watches.contains(w) =>
      log.info(s"adding watch $w for $sender")
      val cancellable = context.system.scheduler.schedule(2 seconds, 30 seconds)(w match {
        case w@WatchConfirmed(channel, txId, minDepth, event) =>
          getTxConfirmations(client, txId.toString).map(_ match {
            case Some(confirmations) if confirmations >= minDepth =>
              channel ! event
              self !('remove, w)
            case _ => {}
          })
        case w@WatchSpent(channel, txId, outputIndex, minDepth, event) =>
          for {
            conf <- getTxConfirmations(client, txId.toString)
            unspent <- isUnspent(client, txId.toString, outputIndex)
          } yield {
            if (conf.isDefined && !unspent) {
              // NOTE : isSpent=!isUnspent only works if the parent transaction actually exists (which we assume to be true)
              channel ! event
              self !('remove, w)
            } else {}
          }
      })
      context.become(watching(watches + (w -> cancellable)))

    case ('remove, w: Watch) if watches.contains(w) =>
      watches(w).cancel()
      context.become(watching(watches - w))

    case Publish(tx) =>
      log.info(s"publishing tx $tx")
  }
}

object PollingWatcher {

  implicit val formats = org.json4s.DefaultFormats + new TransactionSerializer

  class TransactionSerializer extends CustomSerializer[Transaction](format => (
    {
      case JString(x) => Transaction.read(x)
    },
    {
      case x: Transaction => JString(Hex.toHexString(Transaction.write(x)))
    }
    ))

  def getTxConfirmations(client: BitcoinJsonRPCClient, txId: String)(implicit ec: ExecutionContext): Future[Option[Int]] =
    client.invoke("getrawtransaction", txId, 1) // we choose verbose output to get the number of confirmations
      .map(json => Some((json \ "confirmations").extract[Int]))
      .recover {
        case t: JsonRPCError if t.code == -5 => None
      }

  def isUnspent(client: BitcoinJsonRPCClient, txId: String, outputIndex: Int)(implicit ec: ExecutionContext): Future[Boolean] =
    client.invoke("gettxout", txId, outputIndex, true) // mempool=true so that we are warned as soon as possible
      .map(json => json != JNull)

  /**
    * tell bitcoind to sent bitcoins from a specific local account
    *
    * @param client      bitcoind client
    * @param account     name of the local account to send bitcoins from
    * @param destination destination address
    * @param amount      amount in BTC (not milliBTC, not Satoshis !!)
    * @param ec          execution context
    * @return a Future[txid] where txid (a String) is the is of the tx that sends the bitcoins
    */
  def sendFromAccount(client: BitcoinJsonRPCClient, account: String, destination: String, amount: Double)(implicit ec: ExecutionContext): Future[String] =
    client.invoke("sendfrom", account, destination, amount).map {
      case JString(txid) => txid
    }

  def getTransaction(client: BitcoinJsonRPCClient, txId: String)(implicit ec: ExecutionContext): Future[Transaction] =
    client.invoke("getrawtransaction", txId) map {
      case JString(raw) => Transaction.read(raw)
    }

  case class FundTransactionResponse(tx: Transaction, changepos: Int, fee: Double)

  def fundTransaction(client: BitcoinJsonRPCClient, hex: String)(implicit ec: ExecutionContext): Future[FundTransactionResponse] = {
    client.invoke("fundrawtransaction", hex).map(json => {
      val JString(hex) = json \ "hex"
      val JInt(changepos) = json \ "changepos"
      val JDouble(fee) = json \ "fee"
      FundTransactionResponse(Transaction.read(hex), changepos.intValue(), fee)
    })
  }

  def fundTransaction(client: BitcoinJsonRPCClient, tx: Transaction)(implicit ec: ExecutionContext): Future[FundTransactionResponse] =
    fundTransaction(client, Hex.toHexString(Transaction.write(tx)))

  case class SignTransactionResponse(tx: Transaction, complete: Boolean)

  def signTransaction(client: BitcoinJsonRPCClient, hex: String)(implicit ec: ExecutionContext): Future[SignTransactionResponse] =
    client.invoke("signrawtransaction", hex).map(json => {
      val JString(hex) = json \ "hex"
      val JBool(complete) = json \ "complete"
      SignTransactionResponse(Transaction.read(hex), complete)
    })

  def signTransaction(client: BitcoinJsonRPCClient, tx: Transaction)(implicit ec: ExecutionContext): Future[SignTransactionResponse] =
    signTransaction(client, Hex.toHexString(Transaction.write(tx)))
}

/*object MyTest extends App {
  import ExecutionContext.Implicits.global
  implicit val formats = org.json4s.DefaultFormats

  val client = new BitcoinJsonRPCClient("foo", "bar")
  println(Await.result(BlockchainWatcher.getTxConfirmations(client, "28ec4853f134c416757cda8ef3243df549c823d02c2aa5e3c148557ab04a2aa8"), 10 seconds))
  println(Await.result(BlockchainWatcher.isUnspent(client, "c1e932badc68b8d07b714ee87b71dadafad3a3c0058266544ae61fd679481d7a", 0), 10 seconds))
}*/
