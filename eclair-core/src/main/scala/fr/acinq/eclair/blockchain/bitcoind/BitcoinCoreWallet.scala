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
import fr.acinq.eclair.blockchain.OnChainWallet.{MakeFundingTxResponse, OnChainBalance}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient.{FundTransactionOptions, FundTransactionResponse, SignTransactionResponse, toSatoshi}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.blockchain.fee.{FeeratePerKB, FeeratePerKw}
import fr.acinq.eclair.transactions.Transactions
import grizzled.slf4j.Logging
import org.json4s.JsonAST._
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future}
import scala.math.BigDecimal.long2bigDecimal
import scala.util.{Failure, Success}

/**
 * Created by PM on 06/07/2017.
 */
class BitcoinCoreWallet(rpcClient: BitcoinJsonRPCClient)(implicit ec: ExecutionContext) extends OnChainWallet with Logging {

  import BitcoinCoreWallet._

  val bitcoinClient = new ExtendedBitcoinClient(rpcClient)

  def fundTransaction(tx: Transaction, lockUtxos: Boolean, feerate: FeeratePerKw): Future[FundTransactionResponse] = {
    val requestedFeeRatePerKB = FeeratePerKB(feerate)
    rpcClient.invoke("getmempoolinfo").map(json => json \ "mempoolminfee" match {
      case JDecimal(feerate) => FeeratePerKB(Btc(feerate).toSatoshi).max(requestedFeeRatePerKB)
      case JInt(feerate) => FeeratePerKB(Btc(feerate.toLong).toSatoshi).max(requestedFeeRatePerKB)
      case other =>
        logger.warn(s"cannot retrieve mempool minimum fee: $other")
        requestedFeeRatePerKB
    }).flatMap(feeRatePerKB => {
      bitcoinClient.fundTransaction(tx, FundTransactionOptions(FeeratePerKw(feeRatePerKB), lockUtxos = lockUtxos))
    })
  }

  def signTransaction(tx: Transaction): Future[SignTransactionResponse] = {
    bitcoinClient.signTransaction(tx, Nil)
  }

  private def signTransactionOrUnlock(tx: Transaction): Future[SignTransactionResponse] = {
    val f = signTransaction(tx)
    // if signature fails (e.g. because wallet is encrypted) we need to unlock the utxos
    f.recoverWith { case _ =>
      bitcoinClient.unlockOutpoints(tx.txIn.map(_.outPoint))
        .recover { case t: Throwable => // no-op, just add a log in case of failure
          logger.warn(s"Cannot unlock failed transaction's UTXOs txid=${tx.txid}", t)
          t
        }
        .flatMap(_ => f) // return signTransaction error
        .recoverWith { case _ => f } // return signTransaction error
    }
  }

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

  override def onChainBalance(): Future[OnChainBalance] = rpcClient.invoke("getbalances").map(json => {
    val JDecimal(confirmed) = json \ "mine" \ "trusted"
    val JDecimal(unconfirmed) = json \ "mine" \ "untrusted_pending"
    OnChainBalance(toSatoshi(confirmed), toSatoshi(unconfirmed))
  })

  override def getReceiveAddress(label: String): Future[String] = for {
    JString(address) <- rpcClient.invoke("getnewaddress", label)
  } yield address

  override def getReceivePubkey(receiveAddress: Option[String] = None): Future[Crypto.PublicKey] = for {
    address <- receiveAddress.map(Future.successful).getOrElse(getReceiveAddress())
    JString(rawKey) <- rpcClient.invoke("getaddressinfo", address).map(_ \ "pubkey")
  } yield PublicKey(ByteVector.fromValidHex(rawKey))

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feerate: FeeratePerKw): Future[MakeFundingTxResponse] = {
    val partialFundingTx = Transaction(
      version = 2,
      txIn = Seq.empty[TxIn],
      txOut = TxOut(amount, pubkeyScript) :: Nil,
      lockTime = 0)
    for {
      // we ask bitcoin core to add inputs to the funding tx, and use the specified change address
      fundTxResponse <- fundTransaction(partialFundingTx, lockUtxos = true, feerate)
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

  override def commit(tx: Transaction): Future[Boolean] = bitcoinClient.publishTransaction(tx).transformWith {
    case Success(_) => Future.successful(true)
    case Failure(e) =>
      logger.warn(s"txid=${tx.txid} error=$e")
      bitcoinClient.getTransaction(tx.txid).transformWith {
        case Success(_) => Future.successful(true) // tx is in the mempool, we consider that it was published
        case Failure(_) => rollback(tx).transform(_ => Success(false)) // we use transform here because we want to return false in all cases even if rollback fails
      }
  }

  override def rollback(tx: Transaction): Future[Boolean] = bitcoinClient.unlockOutpoints(tx.txIn.map(_.outPoint)) // we unlock all utxos used by the tx

  override def doubleSpent(tx: Transaction): Future[Boolean] = bitcoinClient.doubleSpent(tx)

}

object BitcoinCoreWallet {
  case class WalletTransaction(address: String, amount: Satoshi, fees: Satoshi, blockHash: ByteVector32, confirmations: Long, txid: ByteVector32, timestamp: Long)
}