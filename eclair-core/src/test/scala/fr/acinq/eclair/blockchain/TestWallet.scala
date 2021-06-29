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
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.randomKey
import scodec.bits._

import scala.concurrent.Future
import scala.util.Success

/**
 * Created by PM on 06/07/2017.
 */
class TestWallet extends EclairWallet {

  var rolledback = Set.empty[Transaction]

  override def getBalance: Future[OnChainBalance] = Future.successful(OnChainBalance(1105 sat, 561 sat))

  override def getReceiveAddress(label: String): Future[String] = Future.successful("bcrt1qwcv8naajwn8fjhu8z59q9e6ucrqr068rlcenux")

  override def getReceivePubkey(receiveAddress: Option[String] = None): Future[Crypto.PublicKey] = Future.successful(PublicKey(hex"028feba10d0eafd0fad8fe20e6d9206e6bd30242826de05c63f459a00aced24b12"))

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw): Future[MakeFundingTxResponse] =
    Future.successful(TestWallet.makeDummyFundingTx(pubkeyScript, amount))

  override def commit(tx: Transaction): Future[Boolean] = Future.successful(true)

  override def rollback(tx: Transaction): Future[Boolean] = {
    rolledback = rolledback + tx
    Future.successful(true)
  }

  override def doubleSpent(tx: Transaction): Future[Boolean] = Future.successful(false)
}

object TestWallet {

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