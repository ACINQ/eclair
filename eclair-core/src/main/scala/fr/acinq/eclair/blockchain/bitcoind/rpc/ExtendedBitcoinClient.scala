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
import fr.acinq.eclair.blockchain.fee.{FeeratePerKB, FeeratePerKw}
import fr.acinq.eclair.blockchain.{GetTxWithMetaResponse, UtxoStatus, ValidateResult}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.ChannelAnnouncement
import org.json4s.Formats
import org.json4s.JsonAST._
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * Created by PM on 26/04/2016.
 */

/**
 * The ExtendedBitcoinClient adds some high-level utility methods to interact with Bitcoin Core.
 * Note that all wallet utilities (signing transactions, setting fees, locking outputs, etc) can be found in
 * [[fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet]].
 */
class ExtendedBitcoinClient(val rpcClient: BitcoinJsonRPCClient) {

  import ExtendedBitcoinClient._

  implicit val formats: Formats = org.json4s.DefaultFormats

  def getTransaction(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Transaction] =
    getRawTransaction(txid).map(raw => Transaction.read(raw))

  private def getRawTransaction(txid: ByteVector32)(implicit ec: ExecutionContext): Future[String] =
    rpcClient.invoke("getrawtransaction", txid).collect {
      case JString(raw) => raw
    }

  def getTransactionMeta(txid: ByteVector32)(implicit ec: ExecutionContext): Future[GetTxWithMetaResponse] =
    for {
      tx_opt <- getTransaction(txid).map(Some(_)).recover { case _ => None }
      blockchaininfo <- rpcClient.invoke("getblockchaininfo")
      JInt(timestamp) = blockchaininfo \ "mediantime"
    } yield GetTxWithMetaResponse(txid, tx_opt, timestamp.toLong)

  /** Get the number of confirmations of a given transaction. */
  def getTxConfirmations(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Option[Int]] =
    rpcClient.invoke("getrawtransaction", txid, 1 /* verbose output is needed to get the number of confirmations */)
      .map(json => Some((json \ "confirmations").extractOrElse[Int](0)))
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None // Invalid or non-wallet transaction id (code: -5)
      }

  /** Get the hash of the block containing a given transaction. */
  private def getTxBlockHash(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Option[ByteVector32]] =
    rpcClient.invoke("getrawtransaction", txid, 1 /* verbose output is needed to get the block hash */)
      .map(json => (json \ "blockhash").extractOpt[String].map(ByteVector32.fromValidHex))
      .recover {
        case t: JsonRPCError if t.error.code == -5 => None // Invalid or non-wallet transaction id (code: -5)
      }

  /**
   * @return a Future[height, index] where height is the height of the block where this transaction was published, and
   *         index is the index of the transaction in that block.
   */
  def getTransactionShortId(txid: ByteVector32)(implicit ec: ExecutionContext): Future[(Int, Int)] =
    for {
      Some(blockHash) <- getTxBlockHash(txid)
      json <- rpcClient.invoke("getblock", blockHash)
      JInt(height) = json \ "height"
      JArray(txs) = json \ "tx"
      index = txs.indexOf(JString(txid.toHex))
    } yield (height.toInt, index)

  def fundTransaction(tx: Transaction, options: FundTransactionOptions)(implicit ec: ExecutionContext): Future[FundTransactionResponse] = {
    rpcClient.invoke("fundrawtransaction", tx.toString(), options).map(json => {
      val JString(hex) = json \ "hex"
      val JInt(changePos) = json \ "changepos"
      val JDecimal(fee) = json \ "fee"
      val fundedTx = Transaction.read(hex)
      val amountIn = toSatoshi(fee) + fundedTx.txOut.map(_.amount).sum
      val changePos_opt = if (changePos >= 0) Some(changePos.intValue) else None
      FundTransactionResponse(fundedTx, amountIn, changePos_opt)
    })
  }

  /**
   * @return the public key hash of a bech32 raw change address.
   */
  def getChangeAddress()(implicit ec: ExecutionContext): Future[ByteVector] = {
    rpcClient.invoke("getrawchangeaddress", "bech32").collect {
      case JString(changeAddress) =>
        val (_, _, pubkeyHash) = Bech32.decodeWitnessAddress(changeAddress)
        pubkeyHash
    }
  }

  def signTransaction(tx: Transaction, previousTxs: Seq[PreviousTx])(implicit ec: ExecutionContext): Future[SignTransactionResponse] = {
    rpcClient.invoke("signrawtransactionwithwallet", tx.toString(), previousTxs).map(json => {
      val JString(hex) = json \ "hex"
      val JBool(complete) = json \ "complete"
      if (!complete) {
        val message = (json \ "errors" \\ classOf[JString]).mkString(",")
        throw JsonRPCError(Error(-1, message))
      }
      SignTransactionResponse(Transaction.read(hex), complete)
    })
  }

  /**
   * Publish a transaction on the bitcoin network.
   *
   * Note that this method is idempotent, meaning that if the tx was already published a long time ago, then this is
   * considered a success even if bitcoin core rejects this new attempt.
   *
   * @return the transaction id (txid)
   */
  def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[ByteVector32] =
    rpcClient.invoke("sendrawtransaction", tx.toString()).collect {
      case JString(txid) => ByteVector32.fromValidHex(txid)
    }.recoverWith {
      case JsonRPCError(Error(-27, _)) =>
        // "transaction already in block chain (code: -27)"
        Future.successful(tx.txid)
      case e@JsonRPCError(Error(-25, _)) =>
        // "missing inputs (code: -25)": it may be that the tx has already been published and its output spent.
        getRawTransaction(tx.txid).map(_ => tx.txid).recoverWith { case _ => Future.failed(e) }
    }

  def isTransactionOutputSpendable(txid: ByteVector32, outputIndex: Int, includeMempool: Boolean)(implicit ec: ExecutionContext): Future[Boolean] =
    for {
      json <- rpcClient.invoke("gettxout", txid, outputIndex, includeMempool)
    } yield json != JNull

  def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] =
    for {
      exists <- getTransaction(tx.txid)
        .map(_ => true) // we have found the transaction
        .recover {
          case JsonRPCError(Error(_, message)) if message.contains("index") =>
            sys.error("Fatal error: bitcoind is indexing!!")
            sys.exit(1) // bitcoind is indexing, that's a fatal error!!
            false // won't be reached
          case _ => false
        }
      doublespent <- if (exists) {
        // if the tx is in the blockchain, it can't have been double-spent
        Future.successful(false)
      } else {
        // if the tx wasn't in the blockchain and one of its inputs has been spent, it is double-spent
        // NB: we don't look in the mempool, so it means that we will only consider that the tx has been double-spent if
        // the overriding transaction has been confirmed
        Future.sequence(tx.txIn.map(txIn => isTransactionOutputSpendable(txIn.outPoint.txid, txIn.outPoint.index.toInt, includeMempool = false))).map(_.exists(_ == false))
      }
    } yield doublespent

  /**
   * Iterate over blocks to find the transaction that has spent a given output.
   * NB: only call this method when you're sure the output has been spent, otherwise this will iterate over the whole
   * blockchain history.
   *
   * @param blockhash_opt hash of a block *after* the output has been spent. If not provided, we will use the blockchain tip.
   * @param txid          id of the transaction output that has been spent.
   * @param outputIndex   index of the transaction output that has been spent.
   * @return the transaction spending the given output.
   */
  def lookForSpendingTx(blockhash_opt: Option[ByteVector32], txid: ByteVector32, outputIndex: Int)(implicit ec: ExecutionContext): Future[Transaction] =
    for {
      blockhash <- blockhash_opt match {
        case Some(b) => Future.successful(b)
        case None => rpcClient.invoke("getbestblockhash").collect { case JString(b) => ByteVector32.fromValidHex(b) }
      }
      // with a verbosity of 0, getblock returns the raw serialized block
      block <- rpcClient.invoke("getblock", blockhash, 0).collect { case JString(b) => Block.read(b) }
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

  def getMempoolTx(txid: ByteVector32)(implicit ec: ExecutionContext): Future[MempoolTx] = {
    rpcClient.invoke("getmempoolentry", txid).map(json => {
      val JInt(vsize) = json \ "vsize"
      val JInt(weight) = json \ "weight"
      val JInt(ancestorCount) = json \ "ancestorcount"
      val JInt(descendantCount) = json \ "descendantcount"
      val JDecimal(fees) = json \ "fees" \ "base"
      val JDecimal(ancestorFees) = json \ "fees" \ "ancestor"
      val JDecimal(descendantFees) = json \ "fees" \ "descendant"
      val JBool(replaceable) = json \ "bip125-replaceable"
      // NB: bitcoind counts the transaction itself as its own ancestor and descendant, which is confusing: we fix that by decrementing these counters.
      MempoolTx(vsize.toLong, weight.toLong, replaceable, toSatoshi(fees), ancestorCount.toInt - 1, toSatoshi(ancestorFees), descendantCount.toInt - 1, toSatoshi(descendantFees))
    })
  }

  def getBlockCount(implicit ec: ExecutionContext): Future[Long] =
    rpcClient.invoke("getblockcount").collect {
      case JInt(count) => count.toLong
    }

  def validate(c: ChannelAnnouncement)(implicit ec: ExecutionContext): Future[ValidateResult] = {
    val TxCoordinates(blockHeight, txIndex, outputIndex) = coordinates(c.shortChannelId)
    for {
      blockHash <- rpcClient.invoke("getblockhash", blockHeight).map(_.extractOpt[String].map(ByteVector32.fromValidHex).getOrElse(ByteVector32.Zeroes))
      txid: ByteVector32 <- rpcClient.invoke("getblock", blockHash).map(json => Try {
        val JArray(txs) = json \ "tx"
        ByteVector32.fromValidHex(txs(txIndex).extract[String])
      }.getOrElse(ByteVector32.Zeroes))
      tx <- getRawTransaction(txid)
      unspent <- isTransactionOutputSpendable(txid, outputIndex, includeMempool = true)
      fundingTxStatus <- if (unspent) {
        Future.successful(UtxoStatus.Unspent)
      } else {
        // if this returns true, it means that the spending tx is *not* in the blockchain
        isTransactionOutputSpendable(txid, outputIndex, includeMempool = false).map(res => UtxoStatus.Spent(spendingTxConfirmed = !res))
      }
    } yield ValidateResult(c, Right((Transaction.read(tx), fundingTxStatus)))
  } recover {
    case t: Throwable => ValidateResult(c, Left(t))
  }

}

object ExtendedBitcoinClient {

  case class FundTransactionOptions(feeRate: BigDecimal, replaceable: Boolean, lockUnspents: Boolean, changePosition: Option[Int])

  object FundTransactionOptions {
    def apply(feerate: FeeratePerKw, replaceable: Boolean = true, lockUtxos: Boolean = false, changePosition: Option[Int] = None): FundTransactionOptions = {
      FundTransactionOptions(BigDecimal(FeeratePerKB(feerate).toLong).bigDecimal.scaleByPowerOfTen(-8), replaceable, lockUtxos, changePosition)
    }
  }

  case class FundTransactionResponse(tx: Transaction, amountIn: Satoshi, changePosition: Option[Int]) {
    val fee: Satoshi = amountIn - tx.txOut.map(_.amount).sum
  }

  case class PreviousTx(txid: ByteVector32, vout: Long, scriptPubKey: String, redeemScript: String, witnessScript: String, amount: BigDecimal)

  object PreviousTx {
    def apply(inputInfo: Transactions.InputInfo, witness: ScriptWitness): PreviousTx = PreviousTx(
      inputInfo.outPoint.txid,
      inputInfo.outPoint.index,
      inputInfo.txOut.publicKeyScript.toHex,
      inputInfo.redeemScript.toHex,
      ScriptWitness.write(witness).toHex,
      inputInfo.txOut.amount.toBtc.toBigDecimal
    )
  }

  case class SignTransactionResponse(tx: Transaction, complete: Boolean)

  /**
   * Information about a transaction currently in the mempool.
   *
   * @param vsize           virtual transaction size as defined in BIP 141.
   * @param weight          transaction weight as defined in BIP 141.
   * @param replaceable     Whether this transaction could be replaced with RBF (BIP125).
   * @param fees            transaction fees.
   * @param ancestorCount   number of unconfirmed parent transactions.
   * @param ancestorFees    transactions fees for the package consisting of this transaction and its unconfirmed parents.
   * @param descendantCount number of unconfirmed child transactions.
   * @param descendantFees  transactions fees for the package consisting of this transaction and its unconfirmed children (without its unconfirmed parents).
   */
  case class MempoolTx(vsize: Long, weight: Long, replaceable: Boolean, fees: Satoshi, ancestorCount: Int, ancestorFees: Satoshi, descendantCount: Int, descendantFees: Satoshi)

  def toSatoshi(btcAmount: BigDecimal): Satoshi = Satoshi(btcAmount.bigDecimal.scaleByPowerOfTen(8).longValue)

}