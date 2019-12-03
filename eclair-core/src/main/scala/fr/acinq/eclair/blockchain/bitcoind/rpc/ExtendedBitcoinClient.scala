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
import fr.acinq.eclair.blockchain.{GetTxWithMetaResponse, ImportMultiItem, UtxoStatus, ValidateResult, WatchAddressItem}
import fr.acinq.eclair.wire.ChannelAnnouncement
import kamon.Kamon
import org.json4s.JsonAST.{JValue, _}

import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Created by PM on 26/04/2016.
  */
class ExtendedBitcoinClient(val rpcClient: BitcoinJsonRPCClient) {

  implicit val formats = org.json4s.DefaultFormats

  def getBlock(height: Long)(implicit ec: ExecutionContext): Future[Block] = for {
    blockHash <- getBlockHash(height)
    block <- getBlock(blockHash)
  } yield block

  def getBlock(blockHash: String)(implicit ec: ExecutionContext): Future[Block] = {
    rpcClient.invoke("getblock", blockHash, 0).collect { case JString(rawBlock) => Block.read(rawBlock) }
  }

  def getBlockHash(height: Long)(implicit ec: ExecutionContext): Future[String] = {
    rpcClient.invoke("getblockhash", height).collect { case JString(blockHash) => blockHash }
  }

  def isAddressImported(address: String)(implicit ec: ExecutionContext): Future[Boolean] = {
    listReceivedByAddress(filter = Some(address)).map(_.nonEmpty)
  }

  def listReceivedByAddress(minConf: Int = 1, includeEmpty: Boolean = true, includeWatchOnly: Boolean = true, filter: Option[String] = None)(implicit ec: ExecutionContext): Future[List[WatchAddressItem]] = {
    (filter match {
      case Some(address) => rpcClient.invoke("listreceivedbyaddress", minConf, includeEmpty, includeWatchOnly, address)
      case None          => rpcClient.invoke("listreceivedbyaddress", minConf, includeEmpty, includeWatchOnly)
    }).collect {
      case JArray(elems) => elems.map { json =>
        WatchAddressItem(
          address = (json \ "address").extract[String],
          label = (json \ "label").extract[String]
        )
      }
    }
  }

  def setLabel(address: String, label: String)(implicit ec: ExecutionContext): Future[Unit] = {
    rpcClient.invoke("setlabel", address, label).map(_ => Unit)
  }

  def importMulti(scripts: Seq[ImportMultiItem], rescan: Boolean)(implicit ec: ExecutionContext): Future[Boolean] = {
    val options = JObject(("rescan", JBool(rescan)))
    val requests = JArray(scripts.map(el => JObject(
      ("scriptPubKey", JObject(("address", JString(el.address)))),
      ("timestamp", JInt(el.timestamp)),
      ("label", JString(el.label)),
      ("watchonly", JBool(true))
    )).toList)
    rpcClient.invoke("importmulti", requests, options)
      .collect { case JArray(arr) =>
        arr.forall { importResult =>
          val success = (importResult \ "success").extract[Boolean]
          val errorCode = (importResult \ "error" \ "code").extractOpt[Int]
          // code = -4 indicates the wallet already knows the key for this address, we silently continue
          //https://github.com/bitcoin/bitcoin/blob/d8a66626d63135fd245d5afc524b88b9a94d208b/test/functional/wallet_importmulti.py#L208
          success || errorCode.contains(-4)
        }
      }
      .recover { case e: JsonRPCError => throw new IllegalStateException(s"error while importing addresses: ${e.error.message}")}
  }

  def rescanBlockChain(rescanSinceHeight: Long)(implicit ec: ExecutionContext): Future[Unit] = {
    rpcClient.invoke("rescanblockchain", rescanSinceHeight).map(_ => Unit)
  }

  /**
    * Assumes the transaction is indexed by a previous call to 'importaddress'
    */
  def getTxConfirmations(txId: String)(implicit ec: ExecutionContext): Future[Option[Int]] =
    rpcClient.invoke("gettransaction", txId)
      .map(json => Some((json \ "confirmations").extractOrElse[Int](0)))
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None
      }

  /**
    * Assumes the transaction is indexed by a previous call to 'importaddress'
    */
  def getTxBlockHash(txId: String)(implicit ec: ExecutionContext): Future[Option[String]] =
    rpcClient.invoke("gettransaction", txId)
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
      prevblockhash = block.header.hashPreviousBlock.reverse.toHex
      res <- block.tx.find(tx => tx.txIn.exists(i => i.outPoint.txid.toString() == txid && i.outPoint.index == outputIndex)) match {
        case None => lookForSpendingTx(Some(prevblockhash), txid, outputIndex)
        case Some(tx) => Future.successful(tx)
      }
    } yield res

  def getMempool()(implicit ec: ExecutionContext): Future[Seq[Transaction]] =
    for {
      txids <- rpcClient.invoke("getrawmempool").map(json => json.extract[List[String]])
      txs <- Future.sequence(txids.map(getRawTransaction(_)))
    } yield txs

  /**
    * @param txId
    * @param ec
    * @return
    */
  def getRawTransaction(txId: String)(implicit ec: ExecutionContext): Future[Transaction] =
    rpcClient.invoke("getrawtransaction", txId) collect {
      case JString(raw) => Transaction.read(raw)
    }

  def getTransaction(txId: String)(implicit ec: ExecutionContext): Future[Transaction] =
    for {
      json <- rpcClient.invoke("gettransaction", txId)
      JString(hex) = json \ "hex"
    } yield Transaction.read(hex)

  /**
    * Assumes the transaction is indexed by a previous call to 'importaddress'
    */
  def getTransactionMeta(txId: String)(implicit ec: ExecutionContext): Future[GetTxWithMetaResponse] =
    for {
      tx_opt <- getTransaction(txId) map(Some(_)) recover { case _ => None }
      blockchaininfo <- rpcClient.invoke("getblockchaininfo")
      JInt(timestamp) = blockchaininfo \ "mediantime"
    } yield GetTxWithMetaResponse(txid = ByteVector32.fromValidHex(txId), tx_opt, timestamp.toLong)

  def isTransactionOutputSpendable(txId: String, outputIndex: Int, includeMempool: Boolean)(implicit ec: ExecutionContext): Future[Boolean] =
    for {
      json <- rpcClient.invoke("gettxout", txId, outputIndex, includeMempool)
    } yield json != JNull

  /**
    * Assumes the transaction is indexed by a previous call to 'importaddress'
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
      val span = Kamon.spanBuilder("validate-bitcoin-client").start()
      for {
        _ <- Future.successful(0)
        span0 = Kamon.spanBuilder("getblockhash").start()
        blockHash: String <- rpcClient.invoke("getblockhash", blockHeight).map(_.extractOrElse[String](ByteVector32.Zeroes.toHex))
        _ = span0.finish()
        span1 = Kamon.spanBuilder("getblock").start()
        // we force non verbose output to retrieve the entire serialized block
        block <- rpcClient.invoke("getblock", blockHash, 0).collect { case JString(s) => Block.read(s) }
        _ = span1.finish()
        tx = block.tx(txIndex)
        span3 = Kamon.spanBuilder("utxospendable-mempool").start()
        unspent <- isTransactionOutputSpendable(tx.txid.toHex, outputIndex, includeMempool = true)
        _ = span3.finish()
        fundingTxStatus <- if (unspent) {
          Future.successful(UtxoStatus.Unspent)
        } else {
          // if this returns true, it means that the spending tx is *not* in the blockchain
          isTransactionOutputSpendable(tx.txid.toHex, outputIndex, includeMempool = false).map {
            case res => UtxoStatus.Spent(spendingTxConfirmed = !res)
          }
        }
        _ = span.finish()
      } yield ValidateResult(c, Right((tx, fundingTxStatus)))

  } recover { case t: Throwable => ValidateResult(c, Left(t)) }

}
