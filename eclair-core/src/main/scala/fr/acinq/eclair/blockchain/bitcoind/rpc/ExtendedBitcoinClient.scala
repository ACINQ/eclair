/*
 * Copyright 2018 ACINQ SAS
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
import fr.acinq.eclair.blockchain.{UtxoStatus, ValidateResult}
import fr.acinq.eclair.wire.ChannelAnnouncement
import org.json4s.JsonAST._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * Created by PM on 26/04/2016.
  */
class ExtendedBitcoinClient(val rpcClient: BitcoinJsonRPCClient) {

  implicit val formats = org.json4s.DefaultFormats

  def getTxConfirmations(txId: String)(implicit ec: ExecutionContext): Future[Option[Int]] =
    rpcClient.invoke("getrawtransaction", txId, 1) // we choose verbose output to get the number of confirmations
      .map(json => Some((json \ "confirmations").extractOrElse[Int](0)))
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None
      }

  def getTxBlockHash(txId: String)(implicit ec: ExecutionContext): Future[Option[String]] =
    rpcClient.invoke("getrawtransaction", txId, 1) // we choose verbose output to get the number of confirmations
      .map(json => (json \ "blockhash").extractOpt[String])
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None
      }

  def lookForSpendingTx(blockhash_opt: Option[String], txid: String, outputIndex: Int)(implicit ec: ExecutionContext): Future[Transaction] =
    for {
      blockhash <- blockhash_opt match {
        case Some(b) => Future.successful(b)
        case None => rpcClient.invoke("getbestblockhash") collect { case JString(b) => b }
      }
      // with a verbosity of 0, getblock returns the raw serialized block
      block <- rpcClient.invoke("getblock", blockhash, 0).collect { case JString(b) => Block.read(b) }
      prevblockhash = BinaryData(block.header.hashPreviousBlock.reverse).toString
      res <- block.tx.find(tx => tx.txIn.exists(i => i.outPoint.txid.toString() == txid && i.outPoint.index == outputIndex)) match {
        case None => lookForSpendingTx(Some(prevblockhash), txid, outputIndex)
        case Some(tx) => Future.successful(tx)
      }
    } yield res

  def getMempool()(implicit ec: ExecutionContext): Future[Seq[Transaction]] =
    for {
      txids <- rpcClient.invoke("getrawmempool").map(json => json.extract[List[String]])
      txs <- Future.sequence(txids.map(getTransaction(_)))
    } yield txs

  /**
    * @param txId
    * @param ec
    * @return
    */
  def getRawTransaction(txId: String)(implicit ec: ExecutionContext): Future[String] =
    rpcClient.invoke("getrawtransaction", txId) collect {
      case JString(raw) => raw
    }

  def getTransaction(txId: String)(implicit ec: ExecutionContext): Future[Transaction] =
    getRawTransaction(txId).map(raw => Transaction.read(raw))

  def isTransactionOutputSpendable(txId: String, outputIndex: Int, includeMempool: Boolean)(implicit ec: ExecutionContext): Future[Boolean] =
    for {
      json <- rpcClient.invoke("gettxout", txId, outputIndex, includeMempool)
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
      json <- rpcClient.invoke("getblock", blockHash)
      JInt(height) = json \ "height"
      JString(hash) = json \ "hash"
      JArray(txs) = json \ "tx"
      index = txs.indexOf(JString(txId))
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
        getRawTransaction(tx.txid.toString()).map { case _ => tx.txid.toString() }.recoverWith { case _ => Future.failed[String](e) }
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

    for {
      blockHash: String <- rpcClient.invoke("getblockhash", blockHeight).map(_.extractOrElse[String]("00" * 32))
      txid: String <- rpcClient.invoke("getblock", blockHash).map {
        case json => Try {
          val JArray(txs) = json \ "tx"
          txs(txIndex).extract[String]
        } getOrElse ("00" * 32)
      }
      tx <- getRawTransaction(txid)
      unspent <- isTransactionOutputSpendable(txid, outputIndex, includeMempool = true)
      fundingTxStatus <- if (unspent) {
        Future.successful(UtxoStatus.Unspent)
      } else {
        // if this returns true, it means that the spending tx is *not* in the blockchain
        isTransactionOutputSpendable(txid, outputIndex, includeMempool = false).map {
          case res => UtxoStatus.Spent(spendingTxConfirmed = !res)
        }
      }
    } yield ValidateResult(c, Right((Transaction.read(tx), fundingTxStatus)))

  } recover { case t: Throwable => ValidateResult(c, Left(t)) }

}
