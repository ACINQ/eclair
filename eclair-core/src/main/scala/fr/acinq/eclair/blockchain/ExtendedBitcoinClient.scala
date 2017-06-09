package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.rpc.{BitcoinJsonRPCClient, JsonRPCError}
import fr.acinq.eclair.channel.Helpers
import fr.acinq.eclair.fromShortId
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.ChannelAnnouncement
import org.json4s.JsonAST._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * Created by PM on 26/04/2016.
  */
class ExtendedBitcoinClient(val client: BitcoinJsonRPCClient) {

  import ExtendedBitcoinClient._

  implicit val formats = org.json4s.DefaultFormats

  // TODO: this will probably not be needed once segwit is merged into core
  val protocolVersion = Protocol.PROTOCOL_VERSION

  def tx2Hex(tx: Transaction): String = toHexString(Transaction.write(tx, protocolVersion))

  def hex2tx(hex: String): Transaction = Transaction.read(hex, protocolVersion)

  def getTxConfirmations(txId: String)(implicit ec: ExecutionContext): Future[Option[Int]] =
    client.invoke("getrawtransaction", txId, 1) // we choose verbose output to get the number of confirmations
      .map(json => Some((json \ "confirmations").extractOrElse[Int](0)))
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None
      }

  def getTxBlockHash(txId: String)(implicit ec: ExecutionContext): Future[Option[String]] =
    client.invoke("getrawtransaction", txId, 1) // we choose verbose output to get the number of confirmations
      .map(json => (json \ "blockhash").extractOpt[String])
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None
      }

  def getBlockHashesSinceBlockHash(blockHash: String, previous: Seq[String] = Nil)(implicit ec: ExecutionContext): Future[Seq[String]] =
    for {
      nextblockhash_opt <- client.invoke("getblock", blockHash).map(json => ((json \ "nextblockhash").extractOpt[String]))
      res <- nextblockhash_opt match {
        case Some(nextBlockHash) => getBlockHashesSinceBlockHash(nextBlockHash, previous :+ nextBlockHash)
        case None => Future.successful(previous)
      }
    } yield res

  def getTxsSinceBlockHash(blockHash: String, previous: Seq[Transaction] = Nil)(implicit ec: ExecutionContext): Future[Seq[Transaction]] =
    for {
      (nextblockhash_opt, txids) <- client.invoke("getblock", blockHash).map(json => ((json \ "nextblockhash").extractOpt[String], (json \ "tx").extract[List[String]]))
      next <- Future.sequence(txids.map(getTransaction(_)))
      res <- nextblockhash_opt match {
        case Some(nextBlockHash) => getTxsSinceBlockHash(nextBlockHash, previous ++ next)
        case None => Future.successful(previous ++ next)
      }
    } yield res

  def getMempool()(implicit ec: ExecutionContext): Future[Seq[Transaction]] =
    for {
      txids <- client.invoke("getrawmempool").map(json => json.extract[List[String]])
      txs <- Future.sequence(txids.map(getTransaction(_)))
    } yield txs

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


  def makeFundingTx(localFundingPubkey: PublicKey, remoteFundingPubkey: PublicKey, amount: Satoshi, feeRatePerKw: Long)(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] =
    for {
    // ask for a new address and the corresponding private key
      JString(address) <- client.invoke("getnewaddress")
      JString(wif) <- client.invoke("dumpprivkey", address)
      JString(segwitAddress) <- client.invoke("addwitnessaddress", address)
      (prefix, raw) = Base58Check.decode(wif)
      priv = PrivateKey(raw, compressed = true)
      pub = priv.publicKey
      // create a tx that sends money to a P2SH(WPKH) output that matches our private key
      parentFee = Satoshi(250 * 2 * 2 * feeRatePerKw / 1024)
      partialParentTx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount + parentFee, Script.pay2sh(Script.pay2wpkh(pub))) :: Nil, lockTime = 0L)
      FundTransactionResponse(unsignedParentTx, _, _) <- fundTransaction(partialParentTx)
      // this is the first tx that we will publish, a standard tx which send money to our p2wpkh address
      SignTransactionResponse(parentTx, true) <- signTransaction(unsignedParentTx)
      // now we create the funding tx
      (partialFundingTx, _) = Transactions.makePartialFundingTx(amount, localFundingPubkey, remoteFundingPubkey)
      // and update it to spend from our segwit tx
      pos = Transactions.findPubKeyScriptIndex(parentTx, Script.pay2sh(Script.pay2wpkh(pub)))
      unsignedFundingTx = partialFundingTx.copy(txIn = TxIn(OutPoint(parentTx, pos), sequence = TxIn.SEQUENCE_FINAL, signatureScript = Nil) :: Nil)
    } yield Helpers.Funding.sign(MakeFundingTxResponse(parentTx, unsignedFundingTx, 0, priv))


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

  /**
    * We need this to keep commitment tx fees in sync with the state of the network
    *
    * @param nBlocks number of blocks until tx is confirmed
    * @param ec
    * @return the current
    */
  def estimateSmartFee(nBlocks: Int)(implicit ec: ExecutionContext): Future[Long] =
    client.invoke("estimatesmartfee", nBlocks).map(json => {
      json \ "feerate" match {
        case JDouble(feerate) => Btc(feerate).toLong
        case JInt(feerate) if feerate.toLong < 0 => feerate.toLong
        case JInt(feerate) => Btc(feerate.toLong).toLong
      }
    })

  def getParallel(awaiting: Seq[ChannelAnnouncement]): Future[ParallelGetResponse] = {
    case class TxCoordinate(blockHeight: Int, txIndex: Int, outputIndex: Int)

    val coordinates = awaiting.map {
      case c =>
        val (blockHeight, txIndex, outputIndex) = fromShortId(c.shortChannelId)
        TxCoordinate(blockHeight, txIndex, outputIndex)
    }.zipWithIndex

    import ExecutionContext.Implicits.global
    implicit val formats = org.json4s.DefaultFormats

    for {
      blockHashes: Seq[String] <- client.invoke(coordinates.map(coord => ("getblockhash", coord._1.blockHeight :: Nil))).map(_.map(_.extractOrElse[String]("00" * 32)))
      txids: Seq[String] <- client.invoke(blockHashes.map(h => ("getblock", h :: Nil)))
        .map(_.zipWithIndex)
        .map(_.map {
          case (json, idx) => Try {
            val JArray(txs) = json \ "tx"
            txs(coordinates(idx)._1.txIndex).extract[String]
          } getOrElse ("00" * 32)
        })
      txs <- client.invoke(txids.map(txid => ("getrawtransaction", txid :: Nil))).map(_.map {
        case JString(raw) => Some(Transaction.read(raw))
        case _ => None
      })
      unspent <- client.invoke(txids.zipWithIndex.map(txid => ("gettxout", txid._1 :: coordinates(txid._2)._1.outputIndex :: true :: Nil))).map(_.map(_ != JNull))
    } yield ParallelGetResponse(awaiting.zip(txs.zip(unspent)).map(x => IndividualResult(x._1, x._2._1, x._2._2)))

  }
}

object ExtendedBitcoinClient {

  case class FundTransactionResponse(tx: Transaction, changepos: Int, fee: Double)

  case class SignTransactionResponse(tx: Transaction, complete: Boolean)

}


/*object Test extends App {

  import scala.concurrent.duration._
  import ExecutionContext.Implicits.global
  implicit val system = ActorSystem()
  implicit val timeout = Timeout(30 seconds)

  val bitcoin_client = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
    user = "foo",
    password = "bar",
    host = "localhost",
    port = 28332))

  println(Await.result(bitcoin_client.getTxBlockHash("dcb0abfa822402ce379fedd7bbbb2c824e53ef300313594c39282da1efd35f17"), 10 seconds))
}*/
