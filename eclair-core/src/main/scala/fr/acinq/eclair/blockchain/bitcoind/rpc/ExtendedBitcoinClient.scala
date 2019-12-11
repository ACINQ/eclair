/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.bitcoind.rpc

import fr.acinq.bitcoin._
import fr.acinq.eclair.ShortChannelId.coordinates
import fr.acinq.eclair.TxCoordinates
import fr.acinq.eclair.blockchain.{GetTxWithMetaResponse, UtxoStatus, ValidateResult}
import fr.acinq.eclair.wire.ChannelAnnouncement
import kamon.Kamon
import org.json4s.JsonAST._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * Created by PM on 26/04/2016.
  */
class ExtendedBitcoinClient(val rpcClient: BitcoinJsonRPCClient) {

  implicit val formats = org.json4s.DefaultFormats

  def getTxConfirmations(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Option[Int]] =
    rpcClient.invoke("getrawtransaction", txid.toHex, 1) // we choose verbose output to get the number of confirmations
      .map(json => Some((json \ "confirmations").extractOrElse[Int](0)))
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None
      }

  def getTxBlockHash(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Option[ByteVector32]] =
    rpcClient.invoke("getrawtransaction", txid.toHex, 1) // we choose verbose output to get the number of confirmations
      .map(json => (json \ "blockhash").extractOpt[String].map(ByteVector32.fromValidHex))
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None
      }

  def lookForSpendingTx(blockhash_opt: Option[ByteVector32], txid: ByteVector32, outputIndex: Int)(implicit ec: ExecutionContext): Future[Transaction] =
    for {
      blockhash <- blockhash_opt match {
        case Some(b) => Future.successful(b)
        case None => rpcClient.invoke("getbestblockhash") collect { case JString(b) => ByteVector32.fromValidHex(b) }
      }
      // with a verbosity of 0, getblock returns the raw serialized block
      block <- rpcClient.invoke("getblock", blockhash.toHex, 0).collect { case JString(b) => Block.read(b) }
      prevblockhash = block.header.hashPreviousBlock.reverse
      res <- block.tx.find(tx => tx.txIn.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex)) match {
        case None => lookForSpendingTx(Some(prevblockhash), txid, outputIndex)
        case Some(tx) => Future.successful(tx)
      }
    } yield res

  def getMempool()(implicit ec: ExecutionContext): Future[Seq[Transaction]] =
    for {
      txids <- rpcClient.invoke("getrawmempool").map(json => json.extract[List[String]].map(ByteVector32.fromValidHex))
      txs <- Future.sequence(txids.map(getTransaction(_)))
    } yield txs

  /**
    * @param txid
    * @param ec
    * @return
    */
  def getRawTransaction(txid: ByteVector32)(implicit ec: ExecutionContext): Future[String] =
    rpcClient.invoke("getrawtransaction", txid.toHex) collect {
      case JString(raw) => raw
    }

  def getTransaction(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Transaction] =
    getRawTransaction(txid).map(raw => Transaction.read(raw))

  def getTransactionMeta(txid: ByteVector32)(implicit ec: ExecutionContext): Future[GetTxWithMetaResponse] =
    for {
      tx_opt <- getTransaction(txid) map(Some(_)) recover { case _ => None }
      blockchaininfo <- rpcClient.invoke("getblockchaininfo")
      JInt(timestamp) = blockchaininfo \ "mediantime"
    } yield GetTxWithMetaResponse(txid = txid, tx_opt, timestamp.toLong)

  def isTransactionOutputSpendable(txid: ByteVector32, outputIndex: Int, includeMempool: Boolean)(implicit ec: ExecutionContext): Future[Boolean] =
    for {
      json <- rpcClient.invoke("gettxout", txid.toHex, outputIndex, includeMempool)
    } yield json != JNull

  /**
    *
    * @param txid transaction id
    * @param ec
    * @return a Future[height, index] where height is the height of the block where this transaction was published, and index is
    *         the index of the transaction in that block
    */
  def getTransactionShortId(txid: ByteVector32)(implicit ec: ExecutionContext): Future[(Int, Int)] = {
    val future = for {
      Some(blockHash) <- getTxBlockHash(txid)
      json <- rpcClient.invoke("getblock", blockHash.toHex)
      JInt(height) = json \ "height"
      JString(hash) = json \ "hash"
      JArray(txs) = json \ "tx"
      index = txs.indexOf(JString(txid.toHex))
    } yield (height.toInt, index)

    future
  }

  /**
    * Publish a transaction on the bitcoin network.
    *
    * Note that this method is idempotent, meaning that if the tx was already published a long time ago, then this is
    * considered a success even if bitcoin core rejects this new attempt.
    *
    * @param tx
    * @param ec
    * @return
    */
  def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[String] =
    rpcClient.invoke("sendrawtransaction", tx.toString()) collect {
      case JString(txid) => txid
    } recoverWith {
      case JsonRPCError(Error(-27, _)) =>
        // "transaction already in block chain (code: -27)" ignore error
        Future.successful(tx.txid.toString())
      case e@JsonRPCError(Error(-25, _)) =>
        // "missing inputs (code: -25)" it may be that the tx has already been published and its output spent
        getRawTransaction(tx.txid).map { case _ => tx.txid.toString() }.recoverWith { case _ => Future.failed[String](e) }
    }

  /**
    * We need this to compute absolute timeouts expressed in number of blocks (where getBlockCount would be equivalent
    * to time.now())
    *
    * @param ec
    * @return the current number of blocks in the active chain
    */
  def getBlockCount(implicit ec: ExecutionContext): Future[Long] =
    rpcClient.invoke("getblockcount") collect {
      case JInt(count) => count.toLong
    }

  def validate(c: ChannelAnnouncement)(implicit ec: ExecutionContext): Future[ValidateResult] = {
    val TxCoordinates(blockHeight, txIndex, outputIndex) = coordinates(c.shortChannelId)
      val span = Kamon.spanBuilder("validate-bitcoin-client").start()
      for {
        _ <- Future.successful(0)
        span0 = Kamon.spanBuilder("getblockhash").start()
        blockHash: String <- rpcClient.invoke("getblockhash", blockHeight).map(_.extractOrElse[String](ByteVector32.Zeroes.toHex))
        _ = span0.finish()
        span1 = Kamon.spanBuilder("getblock").start()
        txid: ByteVector32 <- rpcClient.invoke("getblock", blockHash).map {
          case json => Try {
            val JArray(txs) = json \ "tx"
            ByteVector32.fromValidHex(txs(txIndex).extract[String])
          } getOrElse ByteVector32.Zeroes
        }
        _ = span1.finish()
        span2 = Kamon.spanBuilder("getrawtx").start()
        tx <- getRawTransaction(txid)
        _ = span2.finish()
        span3 = Kamon.spanBuilder("utxospendable-mempool").start()
        unspent <- isTransactionOutputSpendable(txid, outputIndex, includeMempool = true)
        _ = span3.finish()
        fundingTxStatus <- if (unspent) {
          Future.successful(UtxoStatus.Unspent)
        } else {
          // if this returns true, it means that the spending tx is *not* in the blockchain
          isTransactionOutputSpendable(txid, outputIndex, includeMempool = false).map {
            case res => UtxoStatus.Spent(spendingTxConfirmed = !res)
          }
        }
        _ = span.finish()
      } yield ValidateResult(c, Right((Transaction.read(tx), fundingTxStatus)))

  } recover { case t: Throwable => ValidateResult(c, Left(t)) }

}
