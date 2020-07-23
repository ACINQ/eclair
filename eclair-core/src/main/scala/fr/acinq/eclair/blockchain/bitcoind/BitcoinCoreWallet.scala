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

package fr.acinq.eclair.blockchain.bitcoind

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinJsonRPCClient, Error, ExtendedBitcoinClient, JsonRPCError}
import fr.acinq.eclair.transactions.Transactions
import grizzled.slf4j.Logging
import org.json4s.JsonAST._
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 06/07/2017.
  */
class BitcoinCoreWallet(rpcClient: BitcoinJsonRPCClient)(implicit ec: ExecutionContext) extends EclairWallet with Logging {

  import BitcoinCoreWallet._

  val bitcoinClient = new ExtendedBitcoinClient(rpcClient)

  def fundTransaction(hex: String, lockUnspents: Boolean, feeRatePerKw: Long): Future[FundTransactionResponse] = {
    val feeRatePerKB = BigDecimal(feerateKw2KB(feeRatePerKw))
    rpcClient.invoke("fundrawtransaction", hex, Options(lockUnspents, feeRatePerKB.bigDecimal.scaleByPowerOfTen(-8))).map(json => {
      val JString(hex) = json \ "hex"
      val JInt(changepos) = json \ "changepos"
      val JDecimal(fee) = json \ "fee"
      FundTransactionResponse(Transaction.read(hex), changepos.intValue, toSatoshi(fee))
    })
  }

  def fundTransaction(tx: Transaction, lockUnspents: Boolean, feeRatePerKw: Long): Future[FundTransactionResponse] = fundTransaction(Transaction.write(tx).toHex, lockUnspents, feeRatePerKw)

  def signTransaction(hex: String): Future[SignTransactionResponse] =
    rpcClient.invoke("signrawtransactionwithwallet", hex).map(json => {
      val JString(hex) = json \ "hex"
      val JBool(complete) = json \ "complete"
      if (!complete) {
        val message = (json \ "errors" \\ classOf[JString]).mkString(",")
        throw JsonRPCError(Error(-1, message))
      }
      SignTransactionResponse(Transaction.read(hex), complete)
    })

  def signTransaction(tx: Transaction): Future[SignTransactionResponse] = signTransaction(Transaction.write(tx).toHex)

  def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[String] = bitcoinClient.publishTransaction(tx)

  def listTransactions(count: Int, skip: Int): Future[List[WalletTransaction]] = rpcClient.invoke("listtransactions", "*", count, skip).map {
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
      WalletTransaction(address, toSatoshi(amount), fee, blockHash, confirmations.toLong, ByteVector32.fromValidHex(txid), timestamp.toLong)
    }).reverse
    case _ => Nil
  }

  def sendToAddress(address: String, amount: Satoshi, confirmationTarget: Long): Future[ByteVector32] = {
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

  /**
   *
   * @param outPoints outpoints to unlock
   * @return true if all outpoints were successfully unlocked, false otherwise
   */
  def unlockOutpoints(outPoints: Seq[OutPoint])(implicit ec: ExecutionContext): Future[Boolean] = {
    // we unlock utxos one by one and not as a list as it would fail at the first utxo that is not actually lock and the rest would not be processed
    val futures = outPoints
      .map(outPoint => Utxo(outPoint.txid, outPoint.index))
      .map(utxo => rpcClient
        .invoke("lockunspent", true, List(utxo))
        .mapTo[JBool]
        .map {
          case JBool(result) => result
        }
        .recover {
          case JsonRPCError(error) if error.message.contains("expected locked output") =>
            true // we consider that the outpoint was successfully unlocked (since it was not locked to begin with)
          case t =>
            logger.warn(s"Cannot unlock utxo=$utxo", t)
            false
        })
    val future = Future.sequence(futures)
    // return true if all outpoints were unlocked false otherwise
    future.map(_.forall(b => b))
  }

  override def getBalance: Future[OnChainBalance] = rpcClient.invoke("getbalances").map(json => {
    val JDecimal(confirmed) = json \ "mine" \ "trusted"
    val JDecimal(unconfirmed) = json \ "mine" \ "untrusted_pending"
    OnChainBalance(toSatoshi(confirmed), toSatoshi(unconfirmed))
  })

  override def getReceiveAddress: Future[String] = for {
    JString(address) <- rpcClient.invoke("getnewaddress")
  } yield address

  override def getReceivePubkey(receiveAddress: Option[String] = None): Future[Crypto.PublicKey] = for {
    address <- receiveAddress.map(Future.successful).getOrElse(getReceiveAddress)
    JString(rawKey) <- rpcClient.invoke("getaddressinfo", address).map(_ \ "pubkey")
  } yield PublicKey(ByteVector.fromValidHex(rawKey))

  private def signTransactionOrUnlock(tx: Transaction): Future[SignTransactionResponse] = {
    val f = signTransaction(tx)
    // if signature fails (e.g. because wallet is encrypted) we need to unlock the utxos
    f.recoverWith { case _ =>
      unlockOutpoints(tx.txIn.map(_.outPoint))
        .recover { case t: Throwable => logger.warn(s"Cannot unlock failed transaction's UTXOs txid=${tx.txid}", t); t } // no-op, just add a log in case of failure
        .flatMap { case _ => f } // return signTransaction error
        .recoverWith { case _ => f } // return signTransaction error
    }
  }

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: Long): Future[MakeFundingTxResponse] = {
    // partial funding tx
    val partialFundingTx = Transaction(
      version = 2,
      txIn = Seq.empty[TxIn],
      txOut = TxOut(amount, pubkeyScript) :: Nil,
      lockTime = 0)
    for {
      // we ask bitcoin core to add inputs to the funding tx, and use the specified change address
      FundTransactionResponse(unsignedFundingTx, _, fee) <- fundTransaction(partialFundingTx, lockUnspents = true, feeRatePerKw)
      // now let's sign the funding tx
      SignTransactionResponse(fundingTx, true) <- signTransactionOrUnlock(unsignedFundingTx)
      // there will probably be a change output, so we need to find which output is ours
      outputIndex <- Transactions.findPubKeyScriptIndex(fundingTx, pubkeyScript, amount_opt = None) match {
        case Right(outputIndex) => Future.successful(outputIndex)
        case Left(skipped) => Future.failed(new RuntimeException(skipped.toString))
      }
      _ = logger.debug(s"created funding txid=${fundingTx.txid} outputIndex=$outputIndex fee=$fee")
    } yield MakeFundingTxResponse(fundingTx, outputIndex, fee)
  }

  override def commit(tx: Transaction): Future[Boolean] = publishTransaction(tx)
    .map(_ => true) // if bitcoind says OK, then we consider the tx successfully published
    .recoverWith {
      case e =>
        logger.warn(s"txid=${tx.txid} error=$e")
        bitcoinClient.getTransaction(tx.txid)
          .map(_ => true) // tx is in the mempool, we consider that it was published
          .recoverWith {
            case _ =>
              rollback(tx).map { _ => false }.recover { case _ => false } // we use transform here because we want to return false in all cases even if rollback fails
          }
    }
    .recover { case _ => true } // in all other cases we consider that the tx has been published

  override def rollback(tx: Transaction): Future[Boolean] = unlockOutpoints(tx.txIn.map(_.outPoint)) // we unlock all utxos used by the tx

  override def doubleSpent(tx: Transaction): Future[Boolean] =
    for {
      exists <- bitcoinClient.getTransaction(tx.txid)
        .map(_ => true) // we have found the transaction
        .recover {
          case JsonRPCError(Error(_, message)) if message.contains("index") =>
            sys.error("Fatal error: bitcoind is indexing!!")
            System.exit(1) // bitcoind is indexing, that's a fatal error!!
            false // won't be reached
          case _ => false
        }
      doublespent <- if (exists) {
        // if the tx is in the blockchain, it can't have been double-spent
        Future.successful(false)
      } else {
        // if the tx wasn't in the blockchain and one of it's input has been spent, it is double-spent
        // NB: we don't look in the mempool, so it means that we will only consider that the tx has been double-spent if
        // the overriding transaction has been confirmed at least once
        Future.sequence(tx.txIn.map(txIn => bitcoinClient.isTransactionOutputSpendable(txIn.outPoint.txid, txIn.outPoint.index.toInt, includeMempool = false))).map(_.exists(_ == false))
      }
    } yield doublespent

}

object BitcoinCoreWallet {

  // @formatter:off
  case class Options(lockUnspents: Boolean, feeRate: BigDecimal)
  case class Utxo(txid: ByteVector32, vout: Long)
  case class WalletTransaction(address: String, amount: Satoshi, fees: Satoshi, blockHash: ByteVector32, confirmations: Long, txid: ByteVector32, timestamp: Long)
  case class FundTransactionResponse(tx: Transaction, changepos: Int, fee: Satoshi)
  case class SignTransactionResponse(tx: Transaction, complete: Boolean)
  // @formatter:on

  private def toSatoshi(amount: BigDecimal): Satoshi = Satoshi(amount.bigDecimal.scaleByPowerOfTen(8).longValue)

}