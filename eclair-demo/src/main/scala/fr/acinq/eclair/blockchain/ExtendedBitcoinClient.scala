package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin.{BinaryData, BitcoinJsonRPCClient, JsonRPCError, Protocol, Satoshi, Transaction, TxIn, TxOut}
import fr.acinq.eclair.channel
import fr.acinq.eclair.channel.Scripts
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Created by PM on 26/04/2016.
  */
class ExtendedBitcoinClient(client: BitcoinJsonRPCClient) {

  implicit val formats = org.json4s.DefaultFormats

  // TODO: this will probably not be needed once segwit is merged into core
  val protocolVersion = Protocol.PROTOCOL_VERSION | Transaction.SERIALIZE_TRANSACTION_WITNESS

  def tx2Hex(tx: Transaction): String = Hex.toHexString(Transaction.write(tx, protocolVersion))

  def hex2tx(hex: String) : Transaction = Transaction.read(hex, protocolVersion)

  def getTxConfirmations(txId: String)(implicit ec: ExecutionContext): Future[Option[Int]] =
    client.invoke("getrawtransaction", txId, 1) // we choose verbose output to get the number of confirmations
      .map(json => Some((json \ "confirmations").extract[Int]))
      .recover {
        case t: JsonRPCError if t.code == -5 => None
      }

  def isUnspent(txId: String, outputIndex: Int)(implicit ec: ExecutionContext): Future[Boolean] =
    client.invoke("gettxout", txId, outputIndex, true) // mempool=true so that we are warned as soon as possible
      .map(json => json != JNull)

  /**
    * tell bitcoind to sent bitcoins from a specific local account
    *
    * @param account     name of the local account to send bitcoins from
    * @param destination destination address
    * @param amount      amount in BTC (not milliBTC, not Satoshis !!)
    * @param ec          execution context
    * @return a Future[txid] where txid (a String) is the is of the tx that sends the bitcoins
    */
  def sendFromAccount(account: String, destination: String, amount: Double)(implicit ec: ExecutionContext): Future[String] =
    client.invoke("sendfrom", account, destination, amount).map {
      case JString(txid) => txid
    }

  def getTransaction(txId: String)(implicit ec: ExecutionContext): Future[Transaction] =
    client.invoke("getrawtransaction", txId) map {
      case JString(raw) => Transaction.read(raw)
    }

  case class FundTransactionResponse(tx: Transaction, changepos: Int, fee: Double)

  def fundTransaction(hex: String)(implicit ec: ExecutionContext): Future[FundTransactionResponse] = {
    client.invoke("fundrawtransaction", hex.take(4) + "0000" + hex.drop(4)).map(json => {
      val JString(hex) = json \ "hex"
      val JInt(changepos) = json \ "changepos"
      val JDouble(fee) = json \ "fee"
      FundTransactionResponse(Transaction.read(hex), changepos.intValue(), fee)
    })
  }

  def fundTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[FundTransactionResponse] =
    fundTransaction(tx2Hex(tx))

  case class SignTransactionResponse(tx: Transaction, complete: Boolean)

  def signTransaction(hex: String)(implicit ec: ExecutionContext): Future[SignTransactionResponse] =
    client.invoke("signrawtransaction", hex).map(json => {
      val JString(hex) = json \ "hex"
      val JBool(complete) = json \ "complete"
      SignTransactionResponse(Transaction.read(hex), complete)
    })

  def signTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[SignTransactionResponse] =
    signTransaction(tx2Hex(tx))

  def publishTransaction(hex: String)(implicit ec: ExecutionContext): Future[String] =
    client.invoke("sendrawtransaction", hex).map {
      case JString(txid) => txid
    }

  def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[String] =
    publishTransaction(tx2Hex(tx))

  // TODO : this is very dirty
  // we only check the memory pool and the last block, and throw an error if tx was not found
  def findSpendingTransaction(txid: String, outputIndex: Int)(implicit ec: ExecutionContext): Future[Transaction] = {
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

  def makeAnchorTx(ourCommitPub: BinaryData, theirCommitPub: BinaryData, amount: Long)(implicit ec: ExecutionContext): Future[(Transaction, Int)] = {
    val anchorOutputScript = channel.Scripts.anchorPubkeyScript(ourCommitPub, theirCommitPub)
    val tx = Transaction(version = 2, txIn = Seq.empty[TxIn], txOut = TxOut(Satoshi(amount), anchorOutputScript) :: Nil, lockTime = 0)
    val future = for {
      FundTransactionResponse(tx1, changepos, fee) <- fundTransaction(tx)
      SignTransactionResponse(anchorTx, true) <- signTransaction(tx1)
      Some(pos) = Scripts.findPublicKeyScriptIndex(anchorTx, anchorOutputScript)
    } yield (anchorTx, pos)

    future
  }

}
