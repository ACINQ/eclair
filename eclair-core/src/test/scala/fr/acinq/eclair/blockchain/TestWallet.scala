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

import fr.acinq.bitcoin.{ByteVector32, Crypto, OP_PUSHDATA, OutPoint, Satoshi, Script, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.eclair.randomBytes
import scodec.bits.ByteVector

import scala.concurrent.Future
import scala.util.Try

/**
  * Created by PM on 06/07/2017.
  */
class TestWallet extends EclairWallet {

  var rolledback = Set.empty[Transaction]

  override def getBalance: Future[Satoshi] = ???

  override def getFinalAddress: Future[String] = Future.successful("bcrt1q82l6tngfd7stp2amhd8w2crn7dfy3qyelzywtn")

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: Long, lockUnspent: Boolean = true): Future[MakeFundingTxResponse] =
    Future.successful(TestWallet.makeDummyFundingTx(pubkeyScript, amount, feeRatePerKw))

  override def signTransactionComplete(tx: Transaction): Future[Transaction] = Future.successful {
    tx.updateWitness(0, ScriptWitness(Seq(randomBytes(73))))
  }

  override def commit(tx: Transaction): Future[Boolean] = Future.successful(true)

  override def rollback(tx: Transaction): Future[Boolean] = {
    rolledback = rolledback + tx
    Future.successful(true)
  }

  override def doubleSpent(tx: Transaction): Future[Boolean] = Future.successful(false)
}

object TestWallet {

  def makeDummyFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: Long): MakeFundingTxResponse = {
    val fundingTx = Transaction(version = 2,
      txIn = TxIn(OutPoint(ByteVector32(ByteVector.fill(32)(1)), 42), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(amount, pubkeyScript) :: Nil,
      lockTime = 0)
    MakeFundingTxResponse(fundingTx, 0, Satoshi(420))
  }
}