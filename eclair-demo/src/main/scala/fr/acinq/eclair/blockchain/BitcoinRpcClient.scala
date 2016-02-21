package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin._
import fr.acinq.eclair.channel
import fr.acinq.eclair.channel.Scripts
import grizzled.slf4j.Logging
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST._

import scala.concurrent.{Await, Future, ExecutionContext}
import scala.concurrent.duration._

/**
  * Created by PM on 21/02/2016.
  */
object BitcoinRpcClient extends Logging {

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
      FundTransactionResponse(tx1, changepos, fee) <- fundTransaction(bitcoind, tx)
      SignTransactionResponse(anchorTx, true) <- signTransaction(bitcoind, tx1)
      Some(pos) = Scripts.findPublicKeyScriptIndex(anchorTx, anchorOutputScript)
    } yield (anchorTx, pos)

    future
  }
}
