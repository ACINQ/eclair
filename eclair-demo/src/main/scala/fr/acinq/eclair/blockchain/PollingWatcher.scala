package fr.acinq.eclair.blockchain


import akka.actor.{Cancellable, Actor, ActorLogging}
import akka.pattern.pipe
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel
import fr.acinq.eclair.channel.{Scripts, BITCOIN_ANCHOR_SPENT}
import grizzled.slf4j.Logging
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST._
import scala.concurrent.{Await, Promise, Future, ExecutionContext}
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
      val cancellable = context.system.scheduler.schedule(2 seconds, 10 seconds)(w match {
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
              findSpendingTransaction(client, txId.toString(), outputIndex).map(tx => channel ! (BITCOIN_ANCHOR_SPENT, tx))
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
      PollingWatcher.publishTransaction(client, tx).onFailure {
        case t: Throwable => log.error(t, s"cannot publish tx ${Hex.toHexString(Transaction.write(tx))}")
      }

    case MakeAnchor(ourCommitPub, theirCommitPub, amount) =>
      PollingWatcher.makeAnchorTx(client, ourCommitPub, theirCommitPub, amount).pipeTo(sender)
  }
}

object PollingWatcher extends Logging {

  implicit val formats = org.json4s.DefaultFormats

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

  def publishTransaction(client: BitcoinJsonRPCClient, hex: String)(implicit ec: ExecutionContext): Future[String] =
    client.invoke("sendrawtransaction", hex).map {
      case JString(txid) => txid
    }

  def publishTransaction(client: BitcoinJsonRPCClient, tx: Transaction)(implicit ec: ExecutionContext): Future[String] =
    publishTransaction(client, Hex.toHexString(Transaction.write(tx)))

  // TODO : this is very dirty
  // we only check the memory pool and the last block, and throw an error if tx was not found
  def findSpendingTransaction(client: BitcoinJsonRPCClient, txid: String, outputIndex: Int)(implicit ec: ExecutionContext): Future[Transaction] = {
    for {
      mempool <- client.invoke("getrawmempool").map(_.extract[List[String]])
      bestblockhash <- client.invoke("getbestblockhash").map(_.extract[String])
      bestblock <- client.invoke("getblock", bestblockhash).map(b => (b \ "tx").extract[List[String]])
      txs <- Future {
        for(txid <- mempool ++ bestblock) yield {
          Await.result(client.invoke("getrawtransaction", txid).map(json => {
            Transaction.read(json.extract[String])
          }).recover {
            case t: Throwable => Transaction(0, Seq(), Seq(), 0)
          }, 20 seconds)
        }
      }
      tx = txs.find(tx => tx.txIn.exists(input => input.outPoint.txid == txid && input.outPoint.index == outputIndex)).getOrElse(throw new RuntimeException("tx not found!"))
    } yield tx
  }

  def makeAnchorTx(bitcoind: BitcoinJsonRPCClient, ourCommitPub: BinaryData, theirCommitPub: BinaryData, amount: Long)(implicit ec: ExecutionContext): Future[(Transaction, Int)] = {
    val anchorOutputScript = channel.Scripts.anchorPubkeyScript(ourCommitPub, theirCommitPub)
    val tx = Transaction(version = 1, txIn = Seq.empty[TxIn], txOut = TxOut(amount, anchorOutputScript) :: Nil, lockTime = 0)
    val future = for {
      FundTransactionResponse(tx1, changepos, fee) <- PollingWatcher.fundTransaction(bitcoind, tx)
      SignTransactionResponse(anchorTx, true) <- PollingWatcher.signTransaction(bitcoind, tx1)
      Some(pos) = Scripts.findPublicKeyScriptIndex(anchorTx, anchorOutputScript)
    } yield (anchorTx, pos)

    future
  }
}

object MyTest extends App {
  import ExecutionContext.Implicits.global
  implicit val formats = org.json4s.DefaultFormats

  val client = new BitcoinJsonRPCClient("foo", "bar", port = 18332)
  println(Await.result(PollingWatcher.findSpendingTransaction(client, "d9690555e8de580901202975f5a249febfc12fad57fb4d3ff20cd6a7316ff5b3", 1), 10 seconds))
}