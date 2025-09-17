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
import fr.acinq.bitcoin.psbt.{KeyPathWithMaster, Psbt, TaprootBip32DerivationPath}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector64, Crypto, KotlinUtils, OutPoint, Satoshi, SatoshiLong, Script, ScriptElt, ScriptWitness, Transaction, TxId, TxIn, TxOut}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.blockchain.OnChainWallet.{FundTransactionResponse, MakeFundingTxResponse, OnChainBalance, ProcessPsbtResponse}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.AddressType
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.crypto.keymanager.LocalOnChainKeyManager
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.{TimestampSecond, randomBytes32}
import scodec.bits._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Random, Success}

/**
 * Created by PM on 06/07/2017.
 */
class DummyOnChainWallet extends OnChainWallet with OnChainPubkeyCache {

  import DummyOnChainWallet._

  val funded = collection.concurrent.TrieMap.empty[TxId, Transaction]
  val published = collection.concurrent.TrieMap.empty[TxId, Transaction]
  var rolledback = Set.empty[Transaction]
  var abandoned = Set.empty[TxId]

  override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(1105 sat, 561 sat))

  override def getReceivePublicKeyScript(addressType: Option[AddressType] = None)(implicit ec: ExecutionContext): Future[Seq[ScriptElt]] = Future.successful(addressType match {
    case Some(AddressType.P2tr) => Script.pay2tr(dummyReceivePubkey.xOnly)
    case _ => Script.pay2wpkh(dummyReceivePubkey)
  })

  override def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(dummyReceivePubkey)

  override def fundTransaction(tx: Transaction, feeRate: FeeratePerKw, replaceable: Boolean, changePosition: Option[Int], externalInputsWeight: Map[OutPoint, Long], minInputConfirmations_opt: Option[Int], feeBudget_opt: Option[Satoshi])(implicit ec: ExecutionContext): Future[FundTransactionResponse] = {
    funded += (tx.txid -> tx)
    Future.successful(FundTransactionResponse(tx, 0 sat, None))
  }

  override def signPsbt(psbt: Psbt, ourInputs: Seq[Int], ourOutputs: Seq[Int])(implicit ec: ExecutionContext): Future[ProcessPsbtResponse] = Future.successful(ProcessPsbtResponse(psbt, complete = true))

  override def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[TxId] = {
    published += (tx.txid -> tx)
    Future.successful(tx.txid)
  }

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw, feeBudget_opt: Option[Satoshi])(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] = {
    val tx = DummyOnChainWallet.makeDummyFundingTx(pubkeyScript, amount)
    funded += (tx.fundingTx.txid -> tx.fundingTx)
    Future.successful(tx)
  }

  override def commit(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = publishTransaction(tx).map(_ => true)

  override def getTransaction(txId: TxId)(implicit ec: ExecutionContext): Future[Transaction] = Future.failed(new RuntimeException("transaction not found"))

  override def getTxConfirmations(txid: TxId)(implicit ec: ExecutionContext): Future[Option[Int]] = Future.failed(new RuntimeException("transaction not found"))

  override def isTransactionOutputSpendable(txid: TxId, outputIndex: Int, includeMempool: Boolean)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(true)

  override def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = {
    rolledback = rolledback + tx
    Future.successful(true)
  }

  override def abandon(txId: TxId)(implicit ec: ExecutionContext): Future[Boolean] = {
    abandoned = abandoned + txId
    Future.successful(true)
  }

  override def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(false)

  override def getP2wpkhPubkey(renew: Boolean): PublicKey = dummyReceivePubkey

  override def getReceivePublicKeyScript(renew: Boolean): Seq[ScriptElt] = Script.pay2tr(dummyReceivePubkey.xOnly)
}

class NoOpOnChainWallet extends OnChainWallet with OnChainPubkeyCache {

  import DummyOnChainWallet._

  var rolledback = Seq.empty[Transaction]
  var doubleSpent = Set.empty[TxId]
  var abandoned = Set.empty[TxId]

  override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(1105 sat, 561 sat))

  override def getReceivePublicKeyScript(addressType: Option[AddressType] = None)(implicit ec: ExecutionContext): Future[Seq[ScriptElt]] = Future.successful(addressType match {
    case Some(AddressType.P2tr) => Script.pay2tr(dummyReceivePubkey.xOnly)
    case _ => Script.pay2wpkh(dummyReceivePubkey)
  })

  override def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(dummyReceivePubkey)

  override def fundTransaction(tx: Transaction, feeRate: FeeratePerKw, replaceable: Boolean, changePosition: Option[Int], externalInputsWeight: Map[OutPoint, Long], minInputConfirmations_opt: Option[Int], feeBudget_opt: Option[Satoshi])(implicit ec: ExecutionContext): Future[FundTransactionResponse] = Promise().future // will never be completed

  override def signPsbt(psbt: Psbt, ourInputs: Seq[Int], ourOutputs: Seq[Int])(implicit ec: ExecutionContext): Future[ProcessPsbtResponse] = Promise().future // will never be completed

  override def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[TxId] = Future.successful(tx.txid)

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw, feeBudget_opt: Option[Satoshi])(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] = Promise().future // will never be completed

  override def commit(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(true)

  override def getTransaction(txId: TxId)(implicit ec: ExecutionContext): Future[Transaction] = Promise().future // will never be completed

  override def getTxConfirmations(txid: TxId)(implicit ec: ExecutionContext): Future[Option[Int]] = Promise().future // will never be completed

  override def isTransactionOutputSpendable(txid: TxId, outputIndex: Int, includeMempool: Boolean)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(true)

  override def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = {
    rolledback = rolledback :+ tx
    Future.successful(true)
  }

  override def abandon(txId: TxId)(implicit ec: ExecutionContext): Future[Boolean] = {
    abandoned = abandoned + txId
    Future.successful(true)
  }

  override def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(doubleSpent.contains(tx.txid))

  override def getP2wpkhPubkey(renew: Boolean): PublicKey = dummyReceivePubkey

  override def getReceivePublicKeyScript(renew: Boolean): Seq[ScriptElt] = Script.pay2tr(dummyReceivePubkey.xOnly)
}

class SingleKeyOnChainWallet extends OnChainWallet with OnChainPubkeyCache {

  import fr.acinq.bitcoin.scalacompat.KotlinUtils._

  // We simulate a BIP84/BIP86 wallet that uses a single public key for each segwit version.
  private val keyManager = new LocalOnChainKeyManager("test-wallet", seed = randomBytes32(), walletTimestamp = TimestampSecond.now(), chainHash = Block.RegtestGenesisBlock.hash)
  private val bip84path = KeyPath("m/84'/1'/0'/0/0")
  private val (p2wpkhPublicKey, _) = keyManager.derivePublicKey(bip84path)
  private val p2wpkhScript = Script.pay2wpkh(p2wpkhPublicKey)
  private val bip86path = KeyPath("m/86'/1'/0'/0/0")
  private val p2trPublicKey = keyManager.derivePublicKey(bip86path)._1.xOnly
  private val p2trScript = Script.pay2tr(p2trPublicKey, None)

  // We create a new dummy input transaction for every funding request.
  var inputs = Seq.empty[Transaction]
  val published = collection.concurrent.TrieMap.empty[TxId, Transaction]
  var rolledback = Seq.empty[Transaction]
  var doubleSpent = Set.empty[TxId]
  var abandoned = Set.empty[TxId]

  override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(1105 sat, 561 sat))

  override def getReceivePublicKeyScript(addressType: Option[AddressType] = None)(implicit ec: ExecutionContext): Future[Seq[ScriptElt]] = Future.successful(addressType match {
    case Some(AddressType.P2wpkh) => p2wpkhScript
    case _ => p2trScript
  })

  override def getP2wpkhPubkey()(implicit ec: ExecutionContext): Future[Crypto.PublicKey] = Future.successful(p2wpkhPublicKey)

  override def fundTransaction(tx: Transaction, feeRate: FeeratePerKw, replaceable: Boolean, changePosition: Option[Int], externalInputsWeight: Map[OutPoint, Long], minInputConfirmations_opt: Option[Int], feeBudget_opt: Option[Satoshi])(implicit ec: ExecutionContext): Future[FundTransactionResponse] = synchronized {
    val currentAmountIn = tx.txIn.flatMap(txIn => inputs.find(_.txid == txIn.outPoint.txid).flatMap(_.txOut.lift(txIn.outPoint.index.toInt))).map(_.amount).sum
    val amountOut = tx.txOut.map(_.amount).sum
    // We add a single input to reach the desired feerate.
    val inputAmount = amountOut + 100_000.sat
    // We randomly use either p2wpkh or p2tr.
    val script = if (Random.nextBoolean()) p2trScript else p2wpkhScript
    val dummyP2wpkhWitness = Script.witnessPay2wpkh(p2wpkhPublicKey, ByteVector.fill(73)(0))
    val dummyP2trWitness = Script.witnessKeyPathPay2tr(ByteVector64.Zeroes)
    val inputTx = Transaction(2, Seq(TxIn(OutPoint(randomTxId(), 1), Nil, 0)), Seq(TxOut(inputAmount, script)), 0)
    inputs = inputs :+ inputTx
    val dummySignedTx = tx.copy(
      txIn = tx.txIn.filterNot(i => externalInputsWeight.contains(i.outPoint)).appended(TxIn(OutPoint(inputTx, 0), ByteVector.empty, 0, ScriptWitness.empty)).map(txIn => {
        val isP2tr = inputs.find(_.txid == txIn.outPoint.txid).map(_.txOut(txIn.outPoint.index.toInt).publicKeyScript).map(Script.parse).exists(Script.isPay2tr)
        txIn.copy(witness = if (isP2tr) dummyP2trWitness else dummyP2wpkhWitness)
      }),
      txOut = tx.txOut :+ TxOut(inputAmount, script),
    )
    val fee = Transactions.weight2fee(feeRate, dummySignedTx.weight() + externalInputsWeight.values.sum.toInt)
    feeBudget_opt match {
      case Some(feeBudget) if fee > feeBudget =>
        Future.failed(new RuntimeException(s"mining fee is higher than budget ($fee > $feeBudget)"))
      case _ =>
        val fundedTx = tx.copy(
          txIn = tx.txIn :+ TxIn(OutPoint(inputTx, 0), Nil, 0),
          txOut = tx.txOut :+ TxOut(inputAmount + currentAmountIn - amountOut - fee, script),
        )
        Future.successful(FundTransactionResponse(fundedTx, fee, Some(tx.txOut.length)))
    }
  }

  override def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[TxId] = {
    inputs = inputs :+ tx
    published += (tx.txid -> tx)
    Future.successful(tx.txid)
  }

  override def signPsbt(psbt: Psbt, ourInputs: Seq[Int], ourOutputs: Seq[Int])(implicit ec: ExecutionContext): Future[ProcessPsbtResponse] = {
    import scala.jdk.CollectionConverters.SeqHasAsJava

    implicit def scala2kmpScript(input: Seq[ScriptElt]): java.util.List[fr.acinq.bitcoin.ScriptElt] = input.map(scala2kmp).asJava

    // We update all of our inputs (p2wpkh or p2tr).
    val updatedInputs = KotlinUtils.kmp2scala(psbt.global.tx).txIn.foldLeft(psbt) {
      case (currentPsbt, txIn) => inputs.find(_.txid == txIn.outPoint.txid) match {
        case Some(inputTx) if inputTx.txOut(txIn.outPoint.index.toInt).publicKeyScript == Script.write(p2wpkhScript) =>
          val Right(updated) = for {
            p0 <- currentPsbt.updateWitnessInput(txIn.outPoint, inputTx.txOut(txIn.outPoint.index.toInt), null, Script.pay2pkh(p2wpkhPublicKey), null, java.util.Map.of(p2wpkhPublicKey, new KeyPathWithMaster(0, bip84path)), null, null, java.util.Map.of())
            p1 <- p0.updateNonWitnessInput(inputTx, txIn.outPoint.index.toInt, null, null, java.util.Map.of())
          } yield p1
          updated
        case Some(inputTx) if inputTx.txOut(txIn.outPoint.index.toInt).publicKeyScript == Script.write(p2trScript) =>
          currentPsbt.updateWitnessInput(txIn.outPoint, inputTx.txOut(txIn.outPoint.index.toInt), null, null, null, java.util.Map.of(), null, p2trPublicKey, java.util.Map.of(p2trPublicKey, new TaprootBip32DerivationPath(java.util.List.of(), 0, bip86path))).getRight
        case _ => currentPsbt
      }
    }
    // We update all of our outputs with BIP32 derivation details.
    val updatedOutputs = KotlinUtils.kmp2scala(psbt.global.tx).txOut.zipWithIndex.foldLeft(updatedInputs) {
      case (currentPsbt, (txOut, index)) if txOut.publicKeyScript == Script.write(p2wpkhScript) =>
        currentPsbt.updateWitnessOutput(index, null, null, java.util.Map.of(p2wpkhPublicKey, new KeyPathWithMaster(0, bip84path)), null, java.util.Map.of()).getRight
      case (currentPsbt, (txOut, index)) if txOut.publicKeyScript == Script.write(p2trScript) =>
        currentPsbt.updateWitnessOutput(index, null, null, java.util.Map.of(), p2trPublicKey, java.util.Map.of(p2trPublicKey, new TaprootBip32DerivationPath(java.util.List.of(), 0, bip86path))).getRight
      case (currentPsbt, _) => currentPsbt
    }
    // We sign our inputs.
    keyManager.sign(updatedOutputs, ourInputs, ourOutputs) match {
      case Success(signedPsbt) => Future.successful(ProcessPsbtResponse(signedPsbt, signedPsbt.extract().isRight))
      case Failure(error) => Future.failed(error)
    }
  }

  override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: FeeratePerKw, feeBudget_opt: Option[Satoshi])(implicit ec: ExecutionContext): Future[MakeFundingTxResponse] = {
    val tx = Transaction(2, Nil, Seq(TxOut(amount, pubkeyScript)), 0)
    for {
      fundedTx <- fundTransaction(tx, feeRatePerKw, feeBudget_opt = feeBudget_opt)
      signedPsbt <- signPsbt(new Psbt(fundedTx.tx), fundedTx.tx.txIn.indices, Nil)
      Right(signedTx) = signedPsbt.finalTx_opt
    } yield MakeFundingTxResponse(signedTx, 0, fundedTx.fee)
  }

  override def commit(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(true)

  override def getTransaction(txId: TxId)(implicit ec: ExecutionContext): Future[Transaction] = synchronized {
    inputs.find(_.txid == txId) match {
      case Some(tx) => Future.successful(tx)
      case None => Future.failed(new RuntimeException(s"txid=$txId not found"))
    }
  }

  override def getTxConfirmations(txid: TxId)(implicit ec: ExecutionContext): Future[Option[Int]] = Future.successful(None)

  override def isTransactionOutputSpendable(txid: TxId, outputIndex: Int, includeMempool: Boolean)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(true)

  override def rollback(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = {
    rolledback = rolledback :+ tx
    Future.successful(true)
  }

  override def abandon(txId: TxId)(implicit ec: ExecutionContext): Future[Boolean] = {
    abandoned = abandoned + txId
    Future.successful(true)
  }

  override def doubleSpent(tx: Transaction)(implicit ec: ExecutionContext): Future[Boolean] = Future.successful(doubleSpent.contains(tx.txid))

  override def getP2wpkhPubkey(renew: Boolean): PublicKey = p2wpkhPublicKey

  override def getReceivePublicKeyScript(renew: Boolean): Seq[ScriptElt] = p2trScript
}

object DummyOnChainWallet {
  val dummyReceivePubkey: PublicKey = PublicKey(hex"028feba10d0eafd0fad8fe20e6d9206e6bd30242826de05c63f459a00aced24b12")

  def makeDummyFundingTx(pubkeyScript: ByteVector, amount: Satoshi): MakeFundingTxResponse = {
    val fundingTx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(TxId.fromValidHex("0101010101010101010101010101010101010101010101010101010101010101"), 42), signatureScript = Nil, sequence = SEQUENCE_FINAL) :: Nil,
      txOut = TxOut(amount, pubkeyScript) :: Nil,
      lockTime = 0
    )
    MakeFundingTxResponse(fundingTx, 0, 420 sat)
  }

}