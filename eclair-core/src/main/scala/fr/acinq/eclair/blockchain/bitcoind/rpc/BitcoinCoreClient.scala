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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.bitcoin.{Bech32, Block}
import fr.acinq.eclair.ShortChannelId.coordinates
import fr.acinq.eclair.blockchain.OnChainWallet
import fr.acinq.eclair.blockchain.OnChainWallet.{FundTransactionResponse, MakeFundingTxResponse, OnChainBalance, SignTransactionResponse}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{GetTxWithMetaResponse, UtxoStatus, ValidateResult}
import fr.acinq.eclair.blockchain.fee.{FeeratePerKB, FeeratePerKw}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol.ChannelAnnouncement
import fr.acinq.eclair.{BlockHeight, TimestampSecond, TxCoordinates}
import grizzled.slf4j.Logging
import org.json4s.Formats
import org.json4s.JsonAST._
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.{Failure, Success, Try}

/**
 * Created by PM on 26/04/2016.
 */

/**
 * The Bitcoin Core client provides some high-level utility methods to interact with Bitcoin Core.
 */
class BitcoinCoreClient(val rpcClient: BitcoinJsonRPCClient) extends OnChainWallet with Logging {

  import BitcoinCoreClient._

  implicit val formats: Formats = org.json4s.DefaultFormats

  //------------------------- TRANSACTIONS  -------------------------//

  def getTransaction(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Transaction] =
    getRawTransaction(txid).map(raw => Transaction.read(raw))

  private def getRawTransaction(txid: ByteVector32)(implicit ec: ExecutionContext): Future[String] =
    rpcClient.invoke("getrawtransaction", txid).collect {
      case JString(raw) => raw
    }

  def getTransactionMeta(txid: ByteVector32)(implicit ec: ExecutionContext): Future[GetTxWithMetaResponse] =
    for {
      tx_opt <- getTransaction(txid).map(Some(_)).recover { case _ => None }
      blockchainInfo <- rpcClient.invoke("getblockchaininfo")
      JInt(timestamp) = blockchainInfo \ "mediantime"
    } yield GetTxWithMetaResponse(txid, tx_opt, TimestampSecond(timestamp.toLong))

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
  def getTransactionShortId(txid: ByteVector32)(implicit ec: ExecutionContext): Future[(BlockHeight, Int)] =
    for {
      Some(blockHash) <- getTxBlockHash(txid)
      json <- rpcClient.invoke("getblock", blockHash)
      JInt(height) = json \ "height"
      JArray(txs) = json \ "tx"
      index = txs.indexOf(JString(txid.toHex))
    } yield (BlockHeight(height.toInt), index)

  /**
   * Return true if this output can potentially be spent.
   *
   * Note that if this function returns false, that doesn't mean the output cannot be spent. The output could be unknown
   * (not in the blockchain nor in the mempool) but could reappear later and be spendable at that point. If you want to
   * ensure that an output is not spendable anymore, you should use [[isTransactionOutputSpent]].
   */
  def isTransactionOutputSpendable(txid: ByteVector32, outputIndex: Int, includeMempool: Boolean)(implicit ec: ExecutionContext): Future[Boolean] =
    for {
      json <- rpcClient.invoke("gettxout", txid, outputIndex, includeMempool)
    } yield json != JNull

  /**
   * Return true if this output has already been spent by a confirmed transaction.
   * Note that a reorg may invalidate the result of this function and make a spent output spendable again.
   */
  private def isTransactionOutputSpent(txid: ByteVector32, outputIndex: Int)(implicit ec: ExecutionContext): Future[Boolean] = {
    getTxConfirmations(txid).flatMap {
      case Some(confirmations) if confirmations > 0 =>
        // There is an important limitation when using isTransactionOutputSpendable: if it returns false, it can mean a
        // few different things:
        //  - the input has been spent
        //  - the input is coming from an unconfirmed transaction (in the mempool) but can be unspent
        //  - the input is unknown (it may come from an unconfirmed transaction that we don't have in our mempool)
        //
        // The only way to make sure that our output has been spent is to verify that it is coming from a confirmed
        // transaction and that it has been spent by another confirmed transaction. We want to ignore the mempool to
        // only consider spending transactions that have been confirmed.
        isTransactionOutputSpendable(txid, outputIndex, includeMempool = false).map(r => !r)
      case _ =>
        // If the output itself isn't in the blockchain, it cannot be spent by a confirmed transaction.
        Future.successful(false)
    }
  }

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
      doubleSpent <- if (exists) {
        // if the tx is in the blockchain or in the mempool, it can't have been double-spent
        Future.successful(false)
      } else {
        // The only way to make sure that our transaction has been double-spent is to find an input that is coming from
        // a confirmed transaction and that it has been spent by another confirmed transaction.
        //
        // Note that if our transaction only had unconfirmed inputs and the transactions creating those inputs have
        // themselves been double-spent, we will never be able to consider our transaction double-spent. With the
        // information we have, these unknown inputs could eventually reappear and the transaction could be broadcast
        // again.
        Future.sequence(tx.txIn.map(txIn => isTransactionOutputSpent(txIn.outPoint.txid, txIn.outPoint.index.toInt))).map(_.exists(_ == true))
      }
    } yield doubleSpent

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
  def lookForSpendingTx(blockhash_opt: Option[ByteVector32], txid: ByteVector32, outputIndex: Int)(implicit ec: ExecutionContext): Future[Transaction] = {
    lookForSpendingTx(blockhash_opt.map(KotlinUtils.scala2kmp), KotlinUtils.scala2kmp(txid), outputIndex)
  }

  def lookForSpendingTx(blockhash_opt: Option[fr.acinq.bitcoin.ByteVector32], txid: fr.acinq.bitcoin.ByteVector32, outputIndex: Int)(implicit ec: ExecutionContext): Future[Transaction] =
    for {
      blockhash <- blockhash_opt match {
        case Some(b) => Future.successful(b)
        case None => rpcClient.invoke("getbestblockhash").collect { case JString(b) => ByteVector32.fromValidHex(b) }
      }
      // with a verbosity of 0, getblock returns the raw serialized block
      block <- rpcClient.invoke("getblock", blockhash, 0).collect { case JString(b) => Block.read(b) }
      prevblockhash = block.header.hashPreviousBlock.reversed()
      res <- block.tx.asScala.find(tx => tx.txIn.asScala.exists(i => i.outPoint.txid == txid && i.outPoint.index == outputIndex)) match {
        case None => lookForSpendingTx(Some(prevblockhash), txid, outputIndex)
        case Some(tx) => Future.successful(KotlinUtils.kmp2scala(tx))
      }
    } yield res

  def listTransactions(count: Int, skip: Int)(implicit ec: ExecutionContext): Future[List[WalletTx]] = rpcClient.invoke("listtransactions", "*", count, skip).map {
    case JArray(txs) => txs.map(tx => {
      val JString(address) = tx \ "address"
      val JDecimal(amount) = tx \ "amount"
      // fee is optional and only included for sent transactions
      val fee = tx \ "fee" match {
        case JDecimal(fee) => toSatoshi(fee)
        case _ => Satoshi(0)
      }
      val JInt(confirmations) = tx \ "confirmations"
      // while transactions are still in the mempool, block hash will no be included
      val blockHash = tx \ "blockhash" match {
        case JString(blockHash) => ByteVector32.fromValidHex(blockHash)
        case _ => ByteVector32.Zeroes
      }
      val JString(txid) = tx \ "txid"
      val JInt(timestamp) = tx \ "time"
      WalletTx(address, toSatoshi(amount), fee, blockHash, confirmations.toLong, ByteVector32.fromValidHex(txid), timestamp.toLong)
    }).reverse
    case _ => Nil
  }

  //------------------------- FUNDING  -------------------------//

  def fundTransaction(tx: Transaction, options: FundTransactionOptions)(implicit ec: ExecutionContext): Future[FundTransactionResponse] = {
    rpcClient.invoke("fundrawtransaction", tx.toString(), options).map(json => {
      val JString(hex) = json \ "hex"
      val JInt(changePos) = json \ "changepos"
      val JDecimal(fee) = json \ "fee"
      val fundedTx = Transaction.read(hex)
      val changePos_opt = if (changePos >= 0) Some(changePos.intValue) else None
      FundTransactionResponse(fundedTx, toSatoshi(fee), changePos_opt)
    })
  }

  def fundTransaction(tx: Transaction, feeRate: FeeratePerKw, replaceable: Boolean, externalInputsWeight: Map[OutPoint, Long] = Map.empty)(implicit ec: ExecutionContext): Future[FundTransactionResponse] = {
    fundTransaction(tx, FundTransactionOptions(feeRate, replaceable, inputWeights = externalInputsWeight.map { case (outpoint, weight) => InputWeight(outpoint, weight) }.toSeq))
  }

  def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, targetFeerate: FeeratePerKw)(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] = {
    val partialFundingTx = Transaction(
      version = 2,
      txIn = Seq.empty[TxIn],
      txOut = TxOut(amount, pubkeyScript) :: Nil,
      lockTime = 0)
    for {
      feerate <- mempoolMinFee().map(minFee => FeeratePerKw(minFee).max(targetFeerate))
      // we ask bitcoin core to add inputs to the funding tx, and use the specified change address
      fundTxResponse <- fundTransaction(partialFundingTx, FundTransactionOptions(feerate))
      // now let's sign the funding tx
      SignTransactionResponse(fundingTx, true) <- signTransactionOrUnlock(fundTxResponse.tx)
      // there will probably be a change output, so we need to find which output is ours
      outputIndex <- Transactions.findPubKeyScriptIndex(fundingTx, pubkeyScript) match {
        case Right(outputIndex) => Future.successful(outputIndex)
        case Left(skipped) => Future.failed(new RuntimeException(skipped.toString))
      }
      _ = logger.debug(s"created funding txid=${fundingTx.txid} outputIndex=$outputIndex fee=${fundTxResponse.fee}")
    } yield MakeFundingTxResponse(fundingTx, outputIndex, fundTxResponse.fee)
  }

  def commit(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = publishTransaction(tx).transformWith {
    case Success(_) => Future.successful(true)
    case Failure(e) =>
      logger.warn(s"txid=${tx.txid} error=$e")
      getTransaction(tx.txid).transformWith {
        case Success(_) => Future.successful(true) // tx is in the mempool, we consider that it was published
        case Failure(_) => rollback(tx).transform(_ => Success(false)) // we use transform here because we want to return false in all cases even if rollback fails
      }
  }

  //------------------------- SIGNING  -------------------------//

  def signTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = signTransaction(tx, Nil)

  def signTransaction(tx: Transaction, allowIncomplete: Boolean)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = signTransaction(tx, Nil, allowIncomplete)

  def signTransaction(tx: Transaction, previousTxs: Seq[PreviousTx], allowIncomplete: Boolean = false)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = {
    rpcClient.invoke("signrawtransactionwithwallet", tx.toString(), previousTxs).map(json => {
      val JString(hex) = json \ "hex"
      val JBool(complete) = json \ "complete"
      if (!complete && !allowIncomplete) {
        val JArray(errors) = json \ "errors"
        val message = errors.map(error => {
          val JString(txid) = error \ "txid"
          val JInt(vout) = error \ "vout"
          val JString(scriptSig) = error \ "scriptSig"
          val JString(message) = error \ "error"
          s"txid=$txid vout=$vout scriptSig=$scriptSig error=$message"
        }).mkString(", ")
        throw JsonRPCError(Error(-1, message))
      }
      SignTransactionResponse(Transaction.read(hex), complete)
    })
  }

  private def signTransactionOrUnlock(tx: Transaction)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = {
    val f = signTransaction(tx)
    // if signature fails (e.g. because wallet is encrypted) we need to unlock the utxos
    f.recoverWith { case _ =>
      unlockOutpoints(tx.txIn.map(_.outPoint))
        .recover { case t: Throwable => // no-op, just add a log in case of failure
          logger.warn(s"Cannot unlock failed transaction's UTXOs txid=${tx.txid}", t)
          t
        }
        .flatMap(_ => f) // return signTransaction error
        .recoverWith { case _ => f } // return signTransaction error
    }
  }

  //------------------------- PUBLISHING  -------------------------//

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

  /**
   * Mark a transaction as abandoned, which will allow for its wallet inputs to be re-spent.
   * This method can be used to replace "stuck" or evicted transactions.
   * It only works on transactions which are not included in a block and are not currently in the mempool.
   */
  def abandonTransaction(txId: ByteVector32)(implicit ec: ExecutionContext): Future[Boolean] = {
    rpcClient.invoke("abandontransaction", txId).map(_ => true).recover(_ => false)
  }

  /** List all outpoints that are currently locked. */
  def listLockedOutpoints()(implicit ec: ExecutionContext): Future[Set[OutPoint]] = {
    rpcClient.invoke("listlockunspent").collect {
      case JArray(locks) => locks.map(item => {
        val JString(txid) = item \ "txid"
        val JInt(vout) = item \ "vout"
        OutPoint(ByteVector32.fromValidHex(txid).reverse, vout.toInt)
      }).toSet
    }
  }

  /**
   * @param outPoints outpoints to unlock.
   * @return true if all outpoints were successfully unlocked, false otherwise.
   */
  def unlockOutpoints(outPoints: Seq[OutPoint])(implicit ec: ExecutionContext): Future[Boolean] = {
    // we unlock utxos one by one and not as a list as it would fail at the first utxo that is not actually locked and the rest would not be processed
    val futures = outPoints
      .map(outPoint => UnlockOutpoint(outPoint.txid, outPoint.index))
      .map(utxo => rpcClient
        .invoke("lockunspent", true, List(utxo))
        .mapTo[JBool]
        .transformWith {
          case Success(JBool(result)) => Future.successful(result)
          case Failure(JsonRPCError(error)) if error.message.contains("expected locked output") =>
            Future.successful(true) // we consider that the outpoint was successfully unlocked (since it was not locked to begin with)
          case Failure(_) =>
            Future.successful(false)
        })
    val future = Future.sequence(futures)
    // return true if all outpoints were unlocked false otherwise
    future.map(_.forall(b => b))
  }

  def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = unlockOutpoints(tx.txIn.map(_.outPoint)) // we unlock all utxos used by the tx

  //------------------------- ADDRESSES  -------------------------//

  def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = rpcClient.invoke("getbalances").map(json => {
    val JDecimal(confirmed) = json \ "mine" \ "trusted"
    val JDecimal(unconfirmed) = json \ "mine" \ "untrusted_pending"
    OnChainBalance(toSatoshi(confirmed), toSatoshi(unconfirmed))
  })

  def getReceiveAddress(label: String)(implicit ec: ExecutionContext): Future[String] = for {
    JString(address) <- rpcClient.invoke("getnewaddress", label)
  } yield address

  def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = for {
    address <- rpcClient.invoke("getnewaddress", "", "bech32")
    JString(rawKey) <- rpcClient.invoke("getaddressinfo", address).map(_ \ "pubkey")
  } yield PublicKey(ByteVector.fromValidHex(rawKey))

  /**
   * @return the public key hash of a bech32 raw change address.
   */
  def getChangeAddress()(implicit ec: ExecutionContext): Future[ByteVector] = {
    rpcClient.invoke("getrawchangeaddress", "bech32").collect {
      case JString(changeAddress) =>
        val pubkeyHash = ByteVector.view(Bech32.decodeWitnessAddress(changeAddress).getThird)
        pubkeyHash
    }
  }

  def sendToAddress(address: String, amount: Satoshi, confirmationTarget: Long)(implicit ec: ExecutionContext): Future[ByteVector32] = {
    rpcClient.invoke(
      "sendtoaddress",
      address,
      amount.toBtc.toBigDecimal,
      "sent via eclair",
      "",
      false, // subtractfeefromamount
      true, // replaceable
      confirmationTarget).collect {
      case JString(txid) => ByteVector32.fromValidHex(txid)
    }
  }

  //------------------------- MEMPOOL  -------------------------//

  def getMempool()(implicit ec: ExecutionContext): Future[Seq[Transaction]] =
    for {
      txids <- rpcClient.invoke("getrawmempool").map(json => json.extract[List[String]].map(ByteVector32.fromValidHex))
      // NB: if a transaction is evicted before we've called getTransaction, we need to ignore it instead of failing.
      txs <- Future.sequence(txids.map(getTransaction(_).map(Some(_)).recover { case _ => None }))
    } yield txs.flatten

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
      MempoolTx(txid, vsize.toLong, weight.toLong, replaceable, toSatoshi(fees), ancestorCount.toInt - 1, toSatoshi(ancestorFees), descendantCount.toInt - 1, toSatoshi(descendantFees))
    })
  }

  def mempoolMinFee()(implicit ec: ExecutionContext): Future[FeeratePerKB] =
    rpcClient.invoke("getmempoolinfo").map(json => json \ "mempoolminfee" match {
      case JDecimal(feerate) => FeeratePerKB(Btc(feerate).toSatoshi)
      case JInt(feerate) => FeeratePerKB(Btc(feerate.toLong).toSatoshi)
      case other => throw new RuntimeException(s"mempoolminfee failed: $other")
    })

  //------------------------- BLOCKCHAIN  -------------------------//

  def getBlockHeight()(implicit ec: ExecutionContext): Future[BlockHeight] =
    rpcClient.invoke("getblockcount").collect {
      case JInt(count) => BlockHeight(count.toLong)
    }

  def validate(c: ChannelAnnouncement)(implicit ec: ExecutionContext): Future[ValidateResult] = {
    val TxCoordinates(blockHeight, txIndex, outputIndex) = coordinates(c.shortChannelId)
    for {
      blockHash <- rpcClient.invoke("getblockhash", blockHeight.toInt).map(_.extractOpt[String].map(ByteVector32.fromValidHex).getOrElse(ByteVector32.Zeroes))
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

  def listUnspent()(implicit ec: ExecutionContext): Future[Seq[Utxo]] = rpcClient.invoke("listunspent", /* minconf */ 0).collect {
    case JArray(values) => values.map(utxo => {
      val JInt(confirmations) = utxo \ "confirmations"
      val JBool(safe) = utxo \ "safe"
      val JDecimal(amount) = utxo \ "amount"
      val JString(txid) = utxo \ "txid"
      val label = utxo \ "label" match {
        case JString(label) => Some(label)
        case _ => None
      }
      Utxo(ByteVector32.fromValidHex(txid), (amount.doubleValue * 1000).millibtc, confirmations.toLong, safe, label)
    })
  }

}

object BitcoinCoreClient {

  /**
   * When funding transactions that contain non-wallet inputs, we need to specify their maximum weight to let bitcoind
   * compute the total weight of the (funded) transaction and set the fee accordingly.
   */
  case class InputWeight(txid: String, vout: Long, weight: Long)

  object InputWeight {
    def apply(outPoint: OutPoint, weight: Long): InputWeight = InputWeight(outPoint.txid.toHex, outPoint.index, weight)
  }

  case class FundTransactionOptions(feeRate: BigDecimal, replaceable: Boolean, lockUnspents: Boolean, changePosition: Option[Int], input_weights: Option[Seq[InputWeight]])

  object FundTransactionOptions {
    def apply(feerate: FeeratePerKw, replaceable: Boolean = true, changePosition: Option[Int] = None, inputWeights: Seq[InputWeight] = Nil): FundTransactionOptions = {
      FundTransactionOptions(
        BigDecimal(FeeratePerKB(feerate).toLong).bigDecimal.scaleByPowerOfTen(-8),
        replaceable,
        // We must *always* lock inputs selected for funding, otherwise locking wouldn't work at all, as the following
        // scenario highlights:
        //  - we fund a transaction for which we don't lock utxos
        //  - we fund another unrelated transaction for which we lock utxos
        //  - the second transaction ends up using the same utxos as the first one
        //  - but the first transaction confirms, invalidating the second one
        // This would break the assumptions of the second transaction: its inputs are locked, so it doesn't expect to
        // potentially be double-spent.
        lockUnspents = true,
        changePosition,
        if (inputWeights.isEmpty) None else Some(inputWeights)
      )
    }
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

  /**
   * Information about a transaction currently in the mempool.
   *
   * @param txid            transaction id.
   * @param vsize           virtual transaction size as defined in BIP 141.
   * @param weight          transaction weight as defined in BIP 141.
   * @param replaceable     Whether this transaction could be replaced with RBF (BIP125).
   * @param fees            transaction fees.
   * @param ancestorCount   number of unconfirmed parent transactions.
   * @param ancestorFees    transactions fees for the package consisting of this transaction and its unconfirmed parents.
   * @param descendantCount number of unconfirmed child transactions.
   * @param descendantFees  transactions fees for the package consisting of this transaction and its unconfirmed children (without its unconfirmed parents).
   */
  case class MempoolTx(txid: ByteVector32, vsize: Long, weight: Long, replaceable: Boolean, fees: Satoshi, ancestorCount: Int, ancestorFees: Satoshi, descendantCount: Int, descendantFees: Satoshi)

  case class WalletTx(address: String, amount: Satoshi, fees: Satoshi, blockHash: ByteVector32, confirmations: Long, txid: ByteVector32, timestamp: Long)

  case class UnlockOutpoint(txid: ByteVector32, vout: Long)

  case class Utxo(txid: ByteVector32, amount: MilliBtc, confirmations: Long, safe: Boolean, label_opt: Option[String])

  def toSatoshi(btcAmount: BigDecimal): Satoshi = Satoshi(btcAmount.bigDecimal.scaleByPowerOfTen(8).longValue)

}