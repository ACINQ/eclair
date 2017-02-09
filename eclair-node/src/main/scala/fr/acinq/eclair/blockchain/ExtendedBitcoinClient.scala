package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.rpc.{BitcoinJsonRPCClient, JsonRPCError}
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 26/04/2016.
  */
class ExtendedBitcoinClient(val client: BitcoinJsonRPCClient) {

  import ExtendedBitcoinClient._

  implicit val formats = org.json4s.DefaultFormats

  // TODO: this will probably not be needed once segwit is merged into core
  val protocolVersion = Protocol.PROTOCOL_VERSION

  def tx2Hex(tx: Transaction): String = Hex.toHexString(Transaction.write(tx, protocolVersion))

  def hex2tx(hex: String): Transaction = Transaction.read(hex, protocolVersion)

  def getTxConfirmations(txId: String)(implicit ec: ExecutionContext): Future[Option[Int]] =
    client.invoke("getrawtransaction", txId, 1) // we choose verbose output to get the number of confirmations
      .map(json => Some((json \ "confirmations").extract[Int]))
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None
      }

  def getTxBlockHash(txId: String)(implicit ec: ExecutionContext): Future[Option[String]] =
    client.invoke("getrawtransaction", txId, 1) // we choose verbose output to get the number of confirmations
      .map(json => Some((json \ "blockhash").extract[String]))
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None
      }

  /**
    * *used in interop test*
    * tell bitcoind to sent bitcoins from a specific local account
    *
    * @param account     name of the local account to send bitcoins from
    * @param destination destination address
    * @param amount      amount in BTC (not milliBTC, not Satoshis !!)
    * @param ec          execution context
    * @return a Future[txid] where txid (a String) is the is of the tx that sends the bitcoins
    */
  def sendFromAccount(account: String, destination: String, amount: Double)(implicit ec: ExecutionContext): Future[String] =
    client.invoke("sendfrom", account, destination, amount) collect {
      case JString(txid) => txid
    }

  /**
    * @param txId
    * @param ec
    * @return
    */
  def getRawTransaction(txId: String)(implicit ec: ExecutionContext): Future[String] =
    client.invoke("getrawtransaction", txId) collect {
      case JString(raw) => raw
    }

  def getTransaction(txId: String)(implicit ec: ExecutionContext): Future[Transaction] =
    getRawTransaction(txId).map(raw => Transaction.read(raw))

  def getTransaction(height: Int, index: Int)(implicit ec: ExecutionContext): Future[Transaction] =
    for {
      hash <- client.invoke("getblockhash", height).map(json => json.extract[String])
      json <- client.invoke("getblock", hash)
      JArray(txs) = json \ "tx"
      txid = txs(index).extract[String]
      tx <- getTransaction(txid)
    } yield tx

  def isTransactionOuputSpendable(txId: String, ouputIndex: Int, includeMempool: Boolean)(implicit ec: ExecutionContext): Future[Boolean] =
    for {
      json <- client.invoke("gettxout", txId, ouputIndex, includeMempool)
    } yield json != JNull


  /**
    *
    * @param txId transaction id
    * @param ec
    * @return a Future[height, index] where height is the height of the block where this transaction was published, and index is
    *         the index of the transaction in that block
    */
  def getTransactionShortId(txId: String)(implicit ec: ExecutionContext): Future[(Int, Int)] = {
    val future = for {
      Some(blockHash) <- getTxBlockHash(txId)
      json <- client.invoke("getblock", blockHash)
      JInt(height) = json \ "height"
      JString(hash) = json \ "hash"
      JArray(txs) = json \ "tx"
      index = txs.indexOf(JString(txId))
    } yield (height.toInt, index)

    future
  }

  def fundTransaction(hex: String)(implicit ec: ExecutionContext): Future[FundTransactionResponse] = {
    client.invoke("fundrawtransaction", hex /*hex.take(4) + "0000" + hex.drop(4)*/).map(json => {
      val JString(hex) = json \ "hex"
      val JInt(changepos) = json \ "changepos"
      val JDouble(fee) = json \ "fee"
      FundTransactionResponse(Transaction.read(hex), changepos.intValue(), fee)
    })
  }

  def fundTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[FundTransactionResponse] =
    fundTransaction(tx2Hex(tx))

  def signTransaction(hex: String)(implicit ec: ExecutionContext): Future[SignTransactionResponse] =
    client.invoke("signrawtransaction", hex).map(json => {
      val JString(hex) = json \ "hex"
      val JBool(complete) = json \ "complete"
      SignTransactionResponse(Transaction.read(hex), complete)
    })

  def signTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[SignTransactionResponse] =
    signTransaction(tx2Hex(tx))

  def publishTransaction(hex: String)(implicit ec: ExecutionContext): Future[String] =
    client.invoke("sendrawtransaction", hex) collect {
      case JString(txid) => txid
    }

  def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[String] =
    publishTransaction(tx2Hex(tx))

  def makeFundingTx(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, amount: Satoshi)(implicit ec: ExecutionContext): Future[(Transaction, Int)] = {
    val (partialTx, pubkeyScript) = Transactions.makePartialFundingTx(amount, localFundingPubkey, remoteFundingPubkey)
    for {
      FundTransactionResponse(unsignedTx, changepos, fee) <- fundTransaction(partialTx)
      SignTransactionResponse(fundingTx, true) <- signTransaction(unsignedTx)
      pos = Transactions.findPubKeyScriptIndex(fundingTx, pubkeyScript)
    } yield (fundingTx, pos)
  }

  /**
    * *used in interop tests*
    *
    * @param fundingPriv
    * @param ourCommitPub
    * @param theirCommitPub
    * @param amount
    * @param ec
    * @return
    */
  def makeFundingTx(fundingPriv: PrivateKey, ourCommitPub: PublicKey, theirCommitPub: PublicKey, amount: Btc)(implicit ec: ExecutionContext): Future[(Transaction, Int)] = {
    val pub = fundingPriv.publicKey
    val script = write(pay2sh(pay2wpkh(pub)))
    val address = Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, script)
    for {
      id <- sendFromAccount("", address, amount.amount.toDouble)
      tx <- getTransaction(id)
      pos = Transactions.findPubKeyScriptIndex(tx, script)
      output = tx.txOut(pos)
      anchorOutputScript = write(pay2wsh(Scripts.multiSig2of2(ourCommitPub, theirCommitPub)))
      tx1 = Transaction(version = 2, txIn = TxIn(OutPoint(tx, pos), Nil, 0xffffffffL) :: Nil, txOut = TxOut(amount, anchorOutputScript) :: Nil, lockTime = 0)
      pubKeyScript = write(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pub.toBin)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)
      sig = Transaction.signInput(tx1, 0, pubKeyScript, SIGHASH_ALL, output.amount, 1, fundingPriv)
      witness = ScriptWitness(Seq(sig, pub))
      tx2 = tx1.updateWitness(0, witness)
      pos1 = Transactions.findPubKeyScriptIndex(tx2, anchorOutputScript)
    } yield (tx2, pos1)
  }

  /**
    * We need this to compute absolute timeouts expressed in number of blocks (where getBlockCount would be equivalent
    * to time.now())
    *
    * @param ec
    * @return the current number of blocks in the active chain
    */
  def getBlockCount(implicit ec: ExecutionContext): Future[Long] =
    client.invoke("getblockcount") collect {
      case JInt(count) => count.toLong
    }
}

object ExtendedBitcoinClient {

  case class FundTransactionResponse(tx: Transaction, changepos: Int, fee: Double)

  case class SignTransactionResponse(tx: Transaction, complete: Boolean)

}
