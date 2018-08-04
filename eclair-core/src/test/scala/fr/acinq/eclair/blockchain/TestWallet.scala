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

package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin.{BinaryData, Crypto, OP_PUSHDATA, OutPoint, Satoshi, Script, Transaction, TxIn, TxOut}

import scala.concurrent.Future
import scala.util.Try

/**
  * Created by PM on 06/07/2017.
  */
class TestWallet extends EclairWallet {

  override def getBalance: Future[Satoshi] = ???

  override def getFinalAddress: Future[String] = Future.successful("2MsRZ1asG6k94m6GYUufDGaZJMoJ4EV5JKs")

  override def makeFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long): Future[MakeFundingTxResponse] =
    Future.successful(TestWallet.makeDummyFundingTx(pubkeyScript, amount, feeRatePerKw))

  override def commit(tx: Transaction): Future[Boolean] = Future.successful(true)

  override def rollback(tx: Transaction): Future[Boolean] = Future.successful(true)
}

object TestWallet {

  def makeDummyFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long): MakeFundingTxResponse = {
    val fundingTx = Transaction(version = 2,
      txIn = TxIn(OutPoint("42" * 32, 42), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(amount, pubkeyScript) :: Nil,
      lockTime = 0)
    MakeFundingTxResponse(fundingTx, 0, Satoshi(420))
  }

  def malleateTx(tx: Transaction): Transaction = {
    val inputs1 = tx.txIn.map(input => Script.parse(input.signatureScript) match {
      case OP_PUSHDATA(sig, _) :: OP_PUSHDATA(pub, _) :: Nil if pub.length == 33 && Try(Crypto.decodeSignature(sig)).isSuccess =>
        val (r, s) = Crypto.decodeSignature(sig)
        val s1 = Crypto.curve.getN.subtract(s)
        val sig1 = Crypto.encodeSignature(r, s1)
        input.copy(signatureScript = Script.write(OP_PUSHDATA(sig1) :: OP_PUSHDATA(pub) :: Nil))
    })
    val tx1 = tx.copy(txIn = inputs1)
    tx1
  }
}