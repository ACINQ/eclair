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

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Crypto, OutPoint, Psbt, Satoshi, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.OnChainWallet.{MakeFundingTxResponse, OnChainBalance}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.randomKey
import scodec.bits._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success

/**
 * Created by PM on 06/07/2017.
 */
class DummyOnChainWallet extends OnChainWallet {

  import DummyOnChainWallet._

  var rolledback = Set.empty[Transaction]

  override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(1105 sat, 561 sat))

  override def getReceiveAddress(label: String)(implicit ec: ExecutionContext): Future[String] = Future.successful(dummyReceiveAddress)

  override def getReceivePubkey(receiveAddress: Option[String] = None)(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(dummyReceivePubkey)

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw)(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] =
    Future.successful(DummyOnChainWallet.makeDummyFundingTx(pubkeyScript, amount))

  override def commit(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(true)

  override def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = {
    rolledback = rolledback + tx
    Future.successful(true)
  }

  override def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(false)

}

class NoOpOnChainWallet extends OnChainWallet {

  import DummyOnChainWallet._

  override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(1105 sat, 561 sat))

  override def getReceiveAddress(label: String)(implicit ec: ExecutionContext): Future[String] = Future.successful(dummyReceiveAddress)

  override def getReceivePubkey(receiveAddress: Option[String] = None)(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(dummyReceivePubkey)

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw)(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] = Promise[MakeFundingTxResponse]().future // will never be completed

  override def commit(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(true)

  override def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(true)

  override def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(false)

}

object DummyOnChainWallet {

  val dummyReceiveAddress: String = "bcrt1qwcv8naajwn8fjhu8z59q9e6ucrqr068rlcenux"
  val dummyReceivePubkey: PublicKey = PublicKey(hex"028feba10d0eafd0fad8fe20e6d9206e6bd30242826de05c63f459a00aced24b12")

  def makeDummyFundingTx(pubkeyScript: ByteVector, amount: Satoshi): MakeFundingTxResponse = {
    val key = randomKey()
    val baseTx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, Script.pay2wpkh(key.publicKey)) :: Nil, lockTime = 0)
    val fundingTx = Transaction(version = 2,
      txIn = TxIn(OutPoint(baseTx, 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(amount, pubkeyScript) :: Nil,
      lockTime = 0)
    val Success(psbt) = Psbt(fundingTx)
      .update(baseTx, 0, witnessScript = Some(Script.pay2pkh(key.publicKey)))
      .flatMap(p => p.sign(key, 0))
      .flatMap(p => p.finalize(0, Script.witnessPay2wpkh(key.publicKey, p.inputs(0).partialSigs(key.publicKey))))
    MakeFundingTxResponse(psbt, 0, 420 sat)
  }

}