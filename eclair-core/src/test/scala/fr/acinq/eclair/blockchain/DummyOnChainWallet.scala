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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, OutPoint, Satoshi, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.bitcoin.{Bech32, SigHash, SigVersion}
import fr.acinq.eclair.blockchain.OnChainWallet.{FundTransactionResponse, MakeFundingTxResponse, OnChainBalance, SignTransactionResponse}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.{randomBytes32, randomKey}
import scodec.bits._

import scala.concurrent.{ExecutionContext, Future, Promise}

/**
 * Created by PM on 06/07/2017.
 */
class DummyOnChainWallet extends OnChainWallet with OnchainPubkeyCache {

  import DummyOnChainWallet._

  val funded = collection.concurrent.TrieMap.empty[ByteVector32, Transaction]
  val published = collection.concurrent.TrieMap.empty[ByteVector32, Transaction]
  var rolledback = Set.empty[Transaction]

  override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(1105 sat, 561 sat))

  override def getReceiveAddress(label: String)(implicit ec: ExecutionContext): Future[String] = Future.successful(dummyReceiveAddress)

  override def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(dummyReceivePubkey)

  override def fundTransaction(tx: Transaction, feeRate: FeeratePerKw, replaceable: Boolean, externalInputsWeight: Map[OutPoint, Long])(implicit ec: ExecutionContext): Future[FundTransactionResponse] = {
    funded += (tx.txid -> tx)
    Future.successful(FundTransactionResponse(tx, 0 sat, None))
  }

  override def signTransaction(tx: Transaction, allowIncomplete: Boolean)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = Future.successful(SignTransactionResponse(tx, complete = true))

  override def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[ByteVector32] = {
    published += (tx.txid -> tx)
    Future.successful(tx.txid)
  }

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw)(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] = {
    val tx = DummyOnChainWallet.makeDummyFundingTx(pubkeyScript, amount)
    funded += (tx.fundingTx.txid -> tx.fundingTx)
    Future.successful(tx)
  }

  override def commit(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = publishTransaction(tx).map(_ => true)

  override def getTransaction(txId: ByteVector32)(implicit ec: ExecutionContext): Future[Transaction] = Future.failed(new RuntimeException("transaction not found"))

  override def getTxConfirmations(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Option[Int]] = Future.failed(new RuntimeException("transaction not found"))

  override def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = {
    rolledback = rolledback + tx
    Future.successful(true)
  }

  override def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(false)

  override def getP2wpkhPubkey(renew: Boolean): PublicKey = dummyReceivePubkey
}

class NoOpOnChainWallet extends OnChainWallet with OnchainPubkeyCache {

  import DummyOnChainWallet._

  var rolledback = Seq.empty[Transaction]
  var doubleSpent = Set.empty[ByteVector32]

  override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(1105 sat, 561 sat))

  override def getReceiveAddress(label: String)(implicit ec: ExecutionContext): Future[String] = Future.successful(dummyReceiveAddress)

  override def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(dummyReceivePubkey)

  override def fundTransaction(tx: Transaction, feeRate: FeeratePerKw, replaceable: Boolean, externalInputsWeight: Map[OutPoint, Long])(implicit ec: ExecutionContext): Future[FundTransactionResponse] = Promise().future // will never be completed

  override def signTransaction(tx: Transaction, allowIncomplete: Boolean)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = Promise().future // will never be completed

  override def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[ByteVector32] = Future.successful(tx.txid)

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw)(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] = Promise().future // will never be completed

  override def commit(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(true)

  override def getTransaction(txId: ByteVector32)(implicit ec: ExecutionContext): Future[Transaction] = Promise().future // will never be completed

  override def getTxConfirmations(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Option[Int]] = Promise().future // will never be completed

  override def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = {
    rolledback = rolledback :+ tx
    Future.successful(true)
  }

  override def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(doubleSpent.contains(tx.txid))

  override def getP2wpkhPubkey(renew: Boolean): PublicKey = dummyReceivePubkey
}

class SingleKeyOnChainWallet extends OnChainWallet with OnchainPubkeyCache {
  val privkey = randomKey()
  val pubkey = privkey.publicKey
  // We create a new dummy input transaction for every funding request.
  var inputs = Seq.empty[Transaction]
  var rolledback = Seq.empty[Transaction]
  var doubleSpent = Set.empty[ByteVector32]

  override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(1105 sat, 561 sat))

  override def getReceiveAddress(label: String)(implicit ec: ExecutionContext): Future[String] = Future.successful(Bech32.encodeWitnessAddress("bcrt", 0, pubkey.hash160.toArray))

  override def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(pubkey)

  override def fundTransaction(tx: Transaction, feeRate: FeeratePerKw, replaceable: Boolean, externalInputsWeight: Map[OutPoint, Long])(implicit ec: ExecutionContext): Future[FundTransactionResponse] = synchronized {
    val currentAmountIn = tx.txIn.flatMap(txIn => inputs.find(_.txid == txIn.outPoint.txid)).map(_.txOut.head.amount).sum
    val amountOut = tx.txOut.map(_.amount).sum
    // We add a single input to reach the desired feerate.
    val inputAmount = amountOut + 100_000.sat
    val inputTx = Transaction(2, Seq(TxIn(OutPoint(randomBytes32(), 1), Nil, 0)), Seq(TxOut(inputAmount, Script.pay2wpkh(pubkey))), 0)
    inputs = inputs :+ inputTx
    val dummyWitness = Script.witnessPay2wpkh(pubkey, ByteVector.fill(73)(0))
    val dummySignedTx = tx.copy(
      txIn = tx.txIn.map(_.copy(witness = dummyWitness)) :+ TxIn(OutPoint(inputTx, 0), ByteVector.empty, 0, dummyWitness),
      txOut = tx.txOut :+ TxOut(inputAmount, Script.pay2wpkh(pubkey)),
    )
    val fee = Transactions.weight2fee(feeRate, dummySignedTx.weight())
    val fundedTx = tx.copy(
      txIn = tx.txIn :+ TxIn(OutPoint(inputTx, 0), Nil, 0),
      txOut = tx.txOut :+ TxOut(inputAmount + currentAmountIn - amountOut - fee, Script.pay2wpkh(pubkey)),
    )
    Future.successful(FundTransactionResponse(fundedTx, fee, Some(tx.txOut.length)))
  }

  override def signTransaction(tx: Transaction, allowIncomplete: Boolean)(implicit ec: ExecutionContext): Future[SignTransactionResponse] = {
    val signedTx = tx.txIn.zipWithIndex.foldLeft(tx) {
      case (currentTx, (txIn, index)) => inputs.find(_.txid == txIn.outPoint.txid) match {
        case Some(inputTx) =>
          val sig = Transaction.signInput(currentTx, index, Script.pay2pkh(pubkey), SigHash.SIGHASH_ALL, inputTx.txOut.head.amount, SigVersion.SIGVERSION_WITNESS_V0, privkey)
          currentTx.updateWitness(index, Script.witnessPay2wpkh(pubkey, sig))
        case None => currentTx
      }
    }
    val complete = tx.txIn.forall(txIn => inputs.exists(_.txid == txIn.outPoint.txid))
    Future.successful(SignTransactionResponse(signedTx, complete))
  }

  override def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[ByteVector32] = Future.successful(tx.txid)

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw)(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] = {
    val tx = Transaction(2, Nil, Seq(TxOut(amount, pubkeyScript)), 0)
    for {
      fundedTx <- fundTransaction(tx, feeRatePerKw, replaceable = true)
      signedTx <- signTransaction(fundedTx.tx, allowIncomplete = true)
    } yield MakeFundingTxResponse(signedTx.tx, 0, fundedTx.fee)
  }

  override def commit(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(true)

  override def getTransaction(txId: ByteVector32)(implicit ec: ExecutionContext): Future[Transaction] = synchronized {
    inputs.find(_.txid == txId) match {
      case Some(tx) => Future.successful(tx)
      case None => Future.failed(new RuntimeException("tx not found"))
    }
  }

  override def getTxConfirmations(txid: ByteVector32)(implicit ec: ExecutionContext): Future[Option[Int]] = Future.successful(None)

  override def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = {
    rolledback = rolledback :+ tx
    Future.successful(true)
  }

  override def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(doubleSpent.contains(tx.txid))

  override def getP2wpkhPubkey(renew: Boolean): PublicKey = pubkey
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