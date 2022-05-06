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

package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin.TxIn.SEQUENCE_FINAL
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, OutPoint, Satoshi, SatoshiLong, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.OnChainWallet.{FundTransactionResponse, MakeFundingTxResponse, OnChainBalance, SignTransactionResponse}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import scodec.bits._

import scala.concurrent.{ExecutionContext, Future, Promise}

/**
 * Created by PM on 06/07/2017.
 */
class DummyOnChainWallet extends OnChainWallet {

  import DummyOnChainWallet._

  var rolledback = Set.empty[Transaction]

  override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(1105 sat, 561 sat))

  override def getReceiveAddress(label: String)(implicit ec: ExecutionContext): Future[String] = Future.successful(dummyReceiveAddress)

  override def getReceivePubkey(receiveAddress: Option[String] = None)(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(dummyReceivePubkey)

  override def fundTransaction(tx: Transaction, feeRate: FeeratePerKw, replaceable: Boolean, lockUtxos: Boolean)(implicit ec: ExecutionContext): Future[FundTransactionResponse] = Future.successful(FundTransactionResponse(tx, 0 sat, None))

  override def signTransaction(tx: Transaction, allowIncomplete: Boolean)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = Future.successful(SignTransactionResponse(tx, complete = true))

  override def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[ByteVector32] = Future.successful(tx.txid)

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw)(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] =
    Future.successful(DummyOnChainWallet.makeDummyFundingTx(pubkeyScript, amount))

  override def commit(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(true)

  override def getTransaction(txId: ByteVector32)(implicit ec: ExecutionContext): Future[Transaction] = Future.failed(new RuntimeException("transaction not found"))

  override def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = {
    rolledback = rolledback + tx
    Future.successful(true)
  }

  override def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(false)

}

class NoOpOnChainWallet extends OnChainWallet {

  import DummyOnChainWallet._

  var rolledback = Seq.empty[Transaction]

  override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(1105 sat, 561 sat))

  override def getReceiveAddress(label: String)(implicit ec: ExecutionContext): Future[String] = Future.successful(dummyReceiveAddress)

  override def getReceivePubkey(receiveAddress: Option[String] = None)(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(dummyReceivePubkey)

  override def fundTransaction(tx: Transaction, feeRate: FeeratePerKw, replaceable: Boolean, lockUtxos: Boolean)(implicit ec: ExecutionContext): Future[FundTransactionResponse] = Promise().future // will never be completed

  override def signTransaction(tx: Transaction, allowIncomplete: Boolean)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = Promise().future // will never be completed

  override def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[ByteVector32] = Future.successful(tx.txid)

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw)(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] = Promise().future // will never be completed

  override def commit(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(true)

  override def getTransaction(txId: ByteVector32)(implicit ec: ExecutionContext): Future[Transaction] = Promise().future // will never be completed

  override def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = {
    rolledback = rolledback :+ tx
    Future.successful(true)
  }

  override def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(false)

}

object DummyOnChainWallet {

  val dummyReceiveAddress: String = "bcrt1qwcv8naajwn8fjhu8z59q9e6ucrqr068rlcenux"
  val dummyReceivePubkey: PublicKey = PublicKey(hex"028feba10d0eafd0fad8fe20e6d9206e6bd30242826de05c63f459a00aced24b12")

  def makeDummyFundingTx(pubkeyScript: ByteVector, amount: Satoshi): MakeFundingTxResponse = {
    val fundingTx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(ByteVector32(ByteVector.fill(32)(1)), 42), signatureScript = Nil, sequence = SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(amount, pubkeyScript) :: Nil,
      lockTime = 0
    )
    MakeFundingTxResponse(fundingTx, 0, 420 sat)
  }

}