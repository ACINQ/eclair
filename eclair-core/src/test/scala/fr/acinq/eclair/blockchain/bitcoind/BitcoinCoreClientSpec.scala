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

import akka.actor.Status.Failure
import akka.pattern.pipe
import akka.testkit.TestProbe
import fr.acinq.bitcoin
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, BtcDouble, ByteVector32, MilliBtcDouble, OutPoint, Satoshi, SatoshiLong, Script, ScriptWitness, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.OnChainWallet.{FundTransactionResponse, MakeFundingTxResponse, OnChainBalance, SignTransactionResponse}
import fr.acinq.eclair.blockchain.WatcherSpec.{createSpendManyP2WPKH, createSpendP2WPKH}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCAuthMethod.UserPassword
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinCoreClient, JsonRPCError}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.{Scripts, Transactions}
import fr.acinq.eclair.{BlockHeight, TestConstants, TestKitBaseClass, addressToPublicKeyScript, randomKey}
import grizzled.slf4j.Logging
import org.json4s.JsonAST._
import org.json4s.{DefaultFormats, Formats}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Try}

class BitcoinCoreClientSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll with Logging {

  implicit val formats: Formats = DefaultFormats

  override def beforeAll(): Unit = {
    startBitcoind()
    waitForBitcoindReady()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  test("encrypt wallet") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    val walletPassword = Random.alphanumeric.take(8).mkString
    sender.send(bitcoincli, BitcoinReq("encryptwallet", walletPassword))
    sender.expectMsgType[JString](60 seconds)
    restartBitcoind(sender)

    val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
    bitcoinClient.makeFundingTx(pubkeyScript, 50 millibtc, FeeratePerKw(10000 sat)).pipeTo(sender.ref)
    val error = sender.expectMsgType[Failure].cause.asInstanceOf[JsonRPCError].error
    assert(error.message.contains("Please enter the wallet passphrase with walletpassphrase first"))

    sender.send(bitcoincli, BitcoinReq("walletpassphrase", walletPassword, 3600)) // wallet stay unlocked for 3600s
    sender.expectMsgType[JValue]
  }

  test("fund transactions") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    val txToRemote = {
      val txNotFunded = Transaction(2, Nil, TxOut(150000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
      bitcoinClient.fundTransaction(txNotFunded, FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      assert(fundTxResponse.changePosition.nonEmpty)
      assert(fundTxResponse.amountIn > 0.sat)
      assert(fundTxResponse.fee > 0.sat)
      fundTxResponse.tx.txIn.foreach(txIn => assert(txIn.signatureScript.isEmpty && txIn.witness.isNull))
      fundTxResponse.tx.txIn.foreach(txIn => assert(txIn.sequence == bitcoin.TxIn.SEQUENCE_FINAL - 2))

      bitcoinClient.signTransaction(fundTxResponse.tx, Nil).pipeTo(sender.ref)
      val signTxResponse = sender.expectMsgType[SignTransactionResponse]
      assert(signTxResponse.complete)
      assert(signTxResponse.tx.txOut.size == 2)

      bitcoinClient.publishTransaction(signTxResponse.tx).pipeTo(sender.ref)
      sender.expectMsg(signTxResponse.tx.txid)
      generateBlocks(1)
      signTxResponse.tx
    }
    {
      // txs with no outputs are not supported.
      val emptyTx = Transaction(2, Nil, Nil, 0)
      bitcoinClient.fundTransaction(emptyTx, FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
      sender.expectMsgType[Failure]
    }
    {
      // bitcoind requires that "all existing inputs must have their previous output transaction be in the wallet".
      val txNonWalletInputs = Transaction(2, Seq(TxIn(OutPoint(txToRemote, 0), Nil, 0), TxIn(OutPoint(txToRemote, 1), Nil, 0)), Seq(TxOut(100000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
      bitcoinClient.fundTransaction(txNonWalletInputs, FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
      sender.expectMsgType[Failure]
    }
    {
      // we can increase the feerate.
      bitcoinClient.fundTransaction(Transaction(2, Nil, TxOut(250000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0), FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
      val fundTxResponse1 = sender.expectMsgType[FundTransactionResponse]
      bitcoinClient.fundTransaction(fundTxResponse1.tx, FundTransactionOptions(TestConstants.feeratePerKw * 2)).pipeTo(sender.ref)
      val fundTxResponse2 = sender.expectMsgType[FundTransactionResponse]
      assert(fundTxResponse1.tx !== fundTxResponse2.tx)
      assert(fundTxResponse1.fee < fundTxResponse2.fee)
    }
    {
      // we can control where the change output is inserted and opt-out of RBF.
      val txManyOutputs = Transaction(2, Nil, TxOut(410000 sat, Script.pay2wpkh(randomKey().publicKey)) :: TxOut(230000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
      bitcoinClient.fundTransaction(txManyOutputs, FundTransactionOptions(TestConstants.feeratePerKw, replaceable = false, changePosition = Some(1))).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      assert(fundTxResponse.tx.txOut.size == 3)
      assert(fundTxResponse.changePosition == Some(1))
      assert(!Set(230000 sat, 410000 sat).contains(fundTxResponse.tx.txOut(1).amount))
      assert(Set(230000 sat, 410000 sat) == Set(fundTxResponse.tx.txOut.head.amount, fundTxResponse.tx.txOut.last.amount))
      fundTxResponse.tx.txIn.foreach(txIn => assert(txIn.sequence == bitcoin.TxIn.SEQUENCE_FINAL - 1))
    }
  }

  test("absence of rounding") {
    val txIn = Transaction(1, Nil, Nil, 42)
    val hexOut = "02000000013361e994f6bd5cbe9dc9e8cb3acdc12bc1510a3596469d9fc03cfddd71b223720000000000feffffff02c821354a00000000160014b6aa25d6f2a692517f2cf1ad55f243a5ba672cac404b4c0000000000220020822eb4234126c5fc84910e51a161a9b7af94eb67a2344f7031db247e0ecc2f9200000000"

    0 to 9 foreach { satoshi =>
      val apiAmount = JDecimal(BigDecimal(s"0.0000000$satoshi"))
      val rpcClient = new BasicBitcoinJsonRPCClient(rpcAuthMethod = UserPassword("foo", "bar"), host = "localhost", port = 0) {
        override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] = method match {
          case "getbalances" => Future(JObject("mine" -> JObject("trusted" -> apiAmount, "untrusted_pending" -> apiAmount)))(ec)
          case "getmempoolinfo" => Future(JObject("mempoolminfee" -> JDecimal(0.0002)))(ec)
          case "fundrawtransaction" => Future(JObject(List("hex" -> JString(hexOut), "changepos" -> JInt(1), "fee" -> apiAmount)))(ec)
          case _ => Future.failed(new RuntimeException(s"Test BasicBitcoinJsonRPCClient: method $method is not supported"))
        }
      }

      val sender = TestProbe()
      val bitcoinClient = new BitcoinCoreClient(rpcClient)
      bitcoinClient.onChainBalance().pipeTo(sender.ref)
      assert(sender.expectMsgType[OnChainBalance] == OnChainBalance(Satoshi(satoshi), Satoshi(satoshi)))

      bitcoinClient.fundTransaction(txIn, FundTransactionOptions(FeeratePerKw(250 sat))).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      assert(fundTxResponse.fee == Satoshi(satoshi))
    }
  }

  test("create/commit/rollback funding txs") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    bitcoinClient.onChainBalance().pipeTo(sender.ref)
    assert(sender.expectMsgType[OnChainBalance].confirmed > 0.sat)

    bitcoinClient.getReceiveAddress().pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    assert(Try(addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)).isSuccess)

    val fundingTxs = for (_ <- 0 to 3) yield {
      val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
      bitcoinClient.makeFundingTx(pubkeyScript, Satoshi(500), FeeratePerKw(250 sat)).pipeTo(sender.ref)
      val fundingTx = sender.expectMsgType[MakeFundingTxResponse].fundingTx
      bitcoinClient.publishTransaction(fundingTx.copy(txIn = Nil)).pipeTo(sender.ref) // try publishing an invalid version of the tx
      sender.expectMsgType[Failure]
      bitcoinClient.rollback(fundingTx).pipeTo(sender.ref) // rollback the locked outputs
      assert(sender.expectMsgType[Boolean])

      // now fund a tx with correct feerate
      bitcoinClient.makeFundingTx(pubkeyScript, 50 millibtc, FeeratePerKw(250 sat)).pipeTo(sender.ref)
      sender.expectMsgType[MakeFundingTxResponse].fundingTx
    }

    assert(getLocks(sender).size == 4)

    bitcoinClient.commit(fundingTxs(0)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    bitcoinClient.rollback(fundingTxs(1)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    bitcoinClient.commit(fundingTxs(2)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    bitcoinClient.rollback(fundingTxs(3)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    bitcoinClient.getTransaction(fundingTxs(0).txid).pipeTo(sender.ref)
    sender.expectMsg(fundingTxs(0))

    bitcoinClient.getTransaction(fundingTxs(2).txid).pipeTo(sender.ref)
    sender.expectMsg(fundingTxs(2))

    // NB: from 0.17.0 on bitcoin core will clear locks when a tx is published
    assert(getLocks(sender).isEmpty)
  }

  test("ensure feerate is always above min-relay-fee") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
    // 200 sat/kw is below the min-relay-fee
    bitcoinClient.makeFundingTx(pubkeyScript, 5 millibtc, FeeratePerKw(200 sat)).pipeTo(sender.ref)
    val MakeFundingTxResponse(fundingTx, _, _) = sender.expectMsgType[MakeFundingTxResponse]

    bitcoinClient.commit(fundingTx).pipeTo(sender.ref)
    sender.expectMsg(true)
  }

  test("unlock failed funding txs") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    bitcoinClient.onChainBalance().pipeTo(sender.ref)
    assert(sender.expectMsgType[OnChainBalance].confirmed > 0.sat)

    bitcoinClient.getReceiveAddress().pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    assert(Try(addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)).isSuccess)

    assert(getLocks(sender).isEmpty)

    val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
    bitcoinClient.makeFundingTx(pubkeyScript, 50 millibtc, FeeratePerKw(10000 sat)).pipeTo(sender.ref)
    val MakeFundingTxResponse(fundingTx, _, _) = sender.expectMsgType[MakeFundingTxResponse]

    bitcoinClient.commit(fundingTx).pipeTo(sender.ref)
    sender.expectMsg(true)

    bitcoinClient.onChainBalance().pipeTo(sender.ref)
    assert(sender.expectMsgType[OnChainBalance].confirmed > 0.sat)
  }

  test("unlock utxos when transaction is published") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    generateBlocks(1) // generate a block to ensure we start with an empty mempool

    // create a first transaction with multiple inputs
    val tx1 = {
      val fundedTxs = (1 to 3).map(_ => {
        val txNotFunded = Transaction(2, Nil, TxOut(15000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
        bitcoinClient.fundTransaction(txNotFunded, FundTransactionOptions(TestConstants.feeratePerKw, lockUtxos = true)).pipeTo(sender.ref)
        sender.expectMsgType[FundTransactionResponse].tx
      })
      val fundedTx = Transaction(2, fundedTxs.flatMap(_.txIn), fundedTxs.flatMap(_.txOut), 0)
      assert(fundedTx.txIn.length >= 3)

      // tx inputs should be locked
      val lockedUtxos = getLocks(sender)
      fundedTx.txIn.foreach(txIn => assert(lockedUtxos.contains(txIn.outPoint)))

      bitcoinClient.signTransaction(fundedTx, Nil).pipeTo(sender.ref)
      val signTxResponse = sender.expectMsgType[SignTransactionResponse]
      bitcoinClient.publishTransaction(signTxResponse.tx).pipeTo(sender.ref)
      sender.expectMsg(signTxResponse.tx.txid)
      // once the tx is published, the inputs should be automatically unlocked
      assert(getLocks(sender).isEmpty)
      signTxResponse.tx
    }

    // create a second transaction that double-spends one of the inputs of the first transaction
    val tx2 = {
      val txNotFunded = tx1.copy(txIn = tx1.txIn.take(1))
      bitcoinClient.fundTransaction(txNotFunded, FundTransactionOptions(TestConstants.feeratePerKw * 2, lockUtxos = true)).pipeTo(sender.ref)
      val fundedTx = sender.expectMsgType[FundTransactionResponse].tx
      assert(fundedTx.txIn.length >= 2) // we added at least one new input

      // newly added inputs should be locked
      val lockedUtxos = getLocks(sender)
      fundedTx.txIn.foreach(txIn => assert(lockedUtxos.contains(txIn.outPoint)))

      bitcoinClient.signTransaction(fundedTx, Nil).pipeTo(sender.ref)
      val signTxResponse = sender.expectMsgType[SignTransactionResponse]
      bitcoinClient.publishTransaction(signTxResponse.tx).pipeTo(sender.ref)
      sender.expectMsg(signTxResponse.tx.txid)
      // once the tx is published, the inputs should be automatically unlocked
      assert(getLocks(sender).isEmpty)
      signTxResponse.tx
    }

    // tx2 replaced tx1 in the mempool
    bitcoinClient.getMempool().pipeTo(sender.ref)
    val mempoolTxs = sender.expectMsgType[Seq[Transaction]]
    assert(mempoolTxs.length == 1)
    assert(mempoolTxs.head.txid == tx2.txid)
    assert(tx2.txIn.map(_.outPoint).intersect(tx1.txIn.map(_.outPoint)).length == 1)
  }

  test("unlock transaction inputs if publishing fails") {
    val sender = TestProbe()
    val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    // create a huge tx so we make sure it has > 1 inputs
    bitcoinClient.makeFundingTx(pubkeyScript, 250 btc, FeeratePerKw(1000 sat)).pipeTo(sender.ref)
    val MakeFundingTxResponse(fundingTx, outputIndex, _) = sender.expectMsgType[MakeFundingTxResponse]

    // spend the first 2 inputs
    val tx1 = fundingTx.copy(
      txIn = fundingTx.txIn.take(2),
      txOut = fundingTx.txOut.updated(outputIndex, fundingTx.txOut(outputIndex).copy(amount = 50 btc))
    )
    bitcoinClient.signTransaction(tx1).pipeTo(sender.ref)
    val SignTransactionResponse(tx2, true) = sender.expectMsgType[SignTransactionResponse]

    bitcoinClient.commit(tx2).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    // fundingTx inputs are still locked except for the first 2 that were just spent
    val expectedLocks = fundingTx.txIn.drop(2).map(_.outPoint).toSet
    awaitCond({
      val locks = getLocks(sender)
      expectedLocks -- locks isEmpty
    }, max = 10 seconds, interval = 1 second)

    // publishing fundingTx will fail as its first 2 inputs are already spent by tx above in the mempool
    bitcoinClient.commit(fundingTx).pipeTo(sender.ref)
    val result = sender.expectMsgType[Boolean]
    assert(!result)

    // and all locked inputs should now be unlocked
    awaitCond({
      val locks = getLocks(sender)
      locks isEmpty
    }, max = 10 seconds, interval = 1 second)
  }

  test("unlock outpoints correctly") {
    val sender = TestProbe()
    val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey().publicKey, randomKey().publicKey)))
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    {
      // test #1: unlock outpoints that are actually locked
      // create a huge tx so we make sure it has > 1 inputs
      bitcoinClient.makeFundingTx(pubkeyScript, 250 btc, FeeratePerKw(1000 sat)).pipeTo(sender.ref)
      val MakeFundingTxResponse(fundingTx, _, _) = sender.expectMsgType[MakeFundingTxResponse]
      assert(fundingTx.txIn.size > 2)
      assert(getLocks(sender) == fundingTx.txIn.map(_.outPoint).toSet)
      bitcoinClient.rollback(fundingTx).pipeTo(sender.ref)
      assert(sender.expectMsgType[Boolean])
    }
    {
      // test #2: some outpoints are locked, some are unlocked
      bitcoinClient.makeFundingTx(pubkeyScript, 250 btc, FeeratePerKw(1000 sat)).pipeTo(sender.ref)
      val MakeFundingTxResponse(fundingTx, _, _) = sender.expectMsgType[MakeFundingTxResponse]
      assert(fundingTx.txIn.size > 2)
      assert(getLocks(sender) == fundingTx.txIn.map(_.outPoint).toSet)

      // unlock the first 2 outpoints
      val tx1 = fundingTx.copy(txIn = fundingTx.txIn.take(2))
      bitcoinClient.rollback(tx1).pipeTo(sender.ref)
      assert(sender.expectMsgType[Boolean])
      assert(getLocks(sender) == fundingTx.txIn.drop(2).map(_.outPoint).toSet)

      // and try to unlock all outpoints: it should work too
      bitcoinClient.rollback(fundingTx).pipeTo(sender.ref)
      assert(sender.expectMsgType[Boolean])
      assert(getLocks(sender) isEmpty)
    }
  }

  test("sign transactions") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    val nonWalletKey = randomKey()
    val opts = FundTransactionOptions(TestConstants.feeratePerKw, changePosition = Some(1))
    bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(250000 sat, Script.pay2wpkh(nonWalletKey.publicKey))), 0), opts).pipeTo(sender.ref)
    val fundedTx = sender.expectMsgType[FundTransactionResponse].tx
    bitcoinClient.signTransaction(fundedTx, Nil).pipeTo(sender.ref)
    val txToRemote = sender.expectMsgType[SignTransactionResponse].tx
    bitcoinClient.publishTransaction(txToRemote).pipeTo(sender.ref)
    sender.expectMsg(txToRemote.txid)
    generateBlocks(1)

    {
      bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(400000 sat, Script.pay2wpkh(randomKey().publicKey))), 0), opts).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      val txWithNonWalletInput = fundTxResponse.tx.copy(txIn = TxIn(OutPoint(txToRemote, 0), ByteVector.empty, 0) +: fundTxResponse.tx.txIn)
      val walletInputTxs = txWithNonWalletInput.txIn.tail.map(txIn => {
        bitcoinClient.getTransaction(txIn.outPoint.txid).pipeTo(sender.ref)
        sender.expectMsgType[Transaction]
      })

      // bitcoind returns an error if there are unsigned non-wallet input.
      bitcoinClient.signTransaction(txWithNonWalletInput, Nil).pipeTo(sender.ref)
      val Failure(JsonRPCError(error)) = sender.expectMsgType[Failure]
      assert(error.message.contains(txToRemote.txid.toHex))

      // we can ignore that error with allowIncomplete = true, and in that case bitcoind signs the wallet inputs.
      bitcoinClient.signTransaction(txWithNonWalletInput, Nil, allowIncomplete = true).pipeTo(sender.ref)
      val signTxResponse1 = sender.expectMsgType[SignTransactionResponse]
      assert(!signTxResponse1.complete)
      signTxResponse1.tx.txIn.tail.foreach(walletTxIn => assert(walletTxIn.witness.stack.nonEmpty))

      // if the non-wallet inputs are signed, bitcoind signs the remaining wallet inputs.
      val nonWalletSig = Transaction.signInput(txWithNonWalletInput, 0, Script.pay2pkh(nonWalletKey.publicKey), bitcoin.SigHash.SIGHASH_ALL, txToRemote.txOut.head.amount, bitcoin.SigVersion.SIGVERSION_WITNESS_V0, nonWalletKey)
      val nonWalletWitness = ScriptWitness(Seq(nonWalletSig, nonWalletKey.publicKey.value))
      val txWithSignedNonWalletInput = txWithNonWalletInput.updateWitness(0, nonWalletWitness)
      bitcoinClient.signTransaction(txWithSignedNonWalletInput, Nil).pipeTo(sender.ref)
      val signTxResponse2 = sender.expectMsgType[SignTransactionResponse]
      assert(signTxResponse2.complete)
      Transaction.correctlySpends(signTxResponse2.tx, txToRemote +: walletInputTxs, bitcoin.ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    {
      // bitcoind does not sign inputs that have already been confirmed.
      bitcoinClient.signTransaction(fundedTx, Nil).pipeTo(sender.ref)
      val Failure(JsonRPCError(error)) = sender.expectMsgType[Failure]
      assert(error.message.contains("not found or already spent"))
    }
    {
      // bitcoind lets us double-spend ourselves.
      bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(75000 sat, Script.pay2wpkh(randomKey().publicKey))), 0), opts).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      bitcoinClient.signTransaction(fundTxResponse.tx, Nil).pipeTo(sender.ref)
      assert(sender.expectMsgType[SignTransactionResponse].complete)
      bitcoinClient.signTransaction(fundTxResponse.tx.copy(txOut = Seq(TxOut(85000 sat, Script.pay2wpkh(randomKey().publicKey)))), Nil).pipeTo(sender.ref)
      assert(sender.expectMsgType[SignTransactionResponse].complete)
    }
    {
      // create an unconfirmed utxo to a non-wallet address.
      bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(125000 sat, Script.pay2wpkh(nonWalletKey.publicKey))), 0), opts).pipeTo(sender.ref)
      bitcoinClient.signTransaction(sender.expectMsgType[FundTransactionResponse].tx, Nil).pipeTo(sender.ref)
      val unconfirmedTx = sender.expectMsgType[SignTransactionResponse].tx
      bitcoinClient.publishTransaction(unconfirmedTx).pipeTo(sender.ref)
      sender.expectMsg(unconfirmedTx.txid)
      // bitcoind lets us use this unconfirmed non-wallet input.
      bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(350000 sat, Script.pay2wpkh(randomKey().publicKey))), 0), opts).pipeTo(sender.ref)
      val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
      val txWithUnconfirmedInput = fundTxResponse.tx.copy(txIn = TxIn(OutPoint(unconfirmedTx, 0), ByteVector.empty, 0) +: fundTxResponse.tx.txIn)
      val nonWalletSig = Transaction.signInput(txWithUnconfirmedInput, 0, Script.pay2pkh(nonWalletKey.publicKey), bitcoin.SigHash.SIGHASH_ALL, unconfirmedTx.txOut.head.amount, bitcoin.SigVersion.SIGVERSION_WITNESS_V0, nonWalletKey)
      val nonWalletWitness = ScriptWitness(Seq(nonWalletSig, nonWalletKey.publicKey.value))
      val txWithSignedUnconfirmedInput = txWithUnconfirmedInput.updateWitness(0, nonWalletWitness)
      val previousTx = PreviousTx(Transactions.InputInfo(OutPoint(unconfirmedTx.txid, 0), unconfirmedTx.txOut.head, Script.pay2pkh(nonWalletKey.publicKey)), nonWalletWitness)
      bitcoinClient.signTransaction(txWithSignedUnconfirmedInput, Seq(previousTx)).pipeTo(sender.ref)
      assert(sender.expectMsgType[SignTransactionResponse].complete)
    }
  }

  test("publish transaction idempotent") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    val priv = randomKey()
    val noInputTx = Transaction(2, Nil, TxOut(6.btc.toSatoshi, Script.pay2wpkh(priv.publicKey)) :: Nil, 0)
    bitcoinClient.fundTransaction(noInputTx, FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
    val fundTxResponse = sender.expectMsgType[FundTransactionResponse]
    val changePos = fundTxResponse.changePosition.get
    bitcoinClient.signTransaction(fundTxResponse.tx, Nil).pipeTo(sender.ref)
    val tx = sender.expectMsgType[SignTransactionResponse].tx

    // we publish the tx a first time
    bitcoinClient.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)
    // we publish the tx a second time to test idempotence
    bitcoinClient.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)
    // let's confirm the tx
    generateBlocks(1)
    // and publish the tx a third time to test idempotence
    bitcoinClient.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)

    // now let's spend the output of the tx
    val spendingTx = {
      val address = getNewAddress(sender)
      val pos = if (changePos == 0) 1 else 0
      bitcoinrpcclient.invoke("createrawtransaction", Array(Map("txid" -> tx.txid.toHex, "vout" -> pos)), Map(address -> 5.999)).pipeTo(sender.ref)
      val JString(unsignedTxStr) = sender.expectMsgType[JValue]
      val unsignedTx = Transaction.read(unsignedTxStr)
      val sig = Transaction.signInput(unsignedTx, 0, Script.pay2pkh(priv.publicKey), bitcoin.SigHash.SIGHASH_ALL, 6.btc.toSatoshi, bitcoin.SigVersion.SIGVERSION_WITNESS_V0, priv)
      unsignedTx.updateWitness(0, Script.witnessPay2wpkh(priv.publicKey, sig))
    }
    bitcoinClient.publishTransaction(spendingTx).pipeTo(sender.ref)
    sender.expectMsg(spendingTx.txid)

    // and publish the tx a fourth time to test idempotence
    bitcoinClient.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)
    // let's confirm the tx
    generateBlocks(1)
    // and publish the tx a fifth time to test idempotence
    bitcoinClient.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)
  }

  test("publish invalid transactions") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    // that tx has inputs that don't exist
    val txWithUnknownInputs = Transaction.read("02000000000101b9e2a3f518fd74e696d258fed3c78c43f84504e76c99212e01cf225083619acf00000000000d0199800136b34b00000000001600145464ce1e5967773922506e285780339d72423244040047304402206795df1fd93c285d9028c384aacf28b43679f1c3f40215fd7bd1abbfb816ee5a022047a25b8c128e692d4717b6dd7b805aa24ecbbd20cfd664ab37a5096577d4a15d014730440220770f44121ed0e71ec4b482dded976f2febd7500dfd084108e07f3ce1e85ec7f5022025b32dc0d551c47136ce41bfb80f5a10de95c0babb22a3ae2d38e6688b32fcb20147522102c2662ab3e4fa18a141d3be3317c6ee134aff10e6cd0a91282a25bf75c0481ebc2102e952dd98d79aa796289fa438e4fdeb06ed8589ff2a0f032b0cfcb4d7b564bc3252aea58d1120")
    bitcoinClient.publishTransaction(txWithUnknownInputs).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    // invalid txs shouldn't be found in either the mempool or the blockchain
    bitcoinClient.getTxConfirmations(txWithUnknownInputs.txid).pipeTo(sender.ref)
    sender.expectMsg(None)

    bitcoinClient.fundTransaction(Transaction(2, Nil, TxOut(100000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0), FundTransactionOptions(TestConstants.feeratePerKw)).pipeTo(sender.ref)
    val txUnsignedInputs = sender.expectMsgType[FundTransactionResponse].tx
    bitcoinClient.publishTransaction(txUnsignedInputs).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    bitcoinClient.signTransaction(txUnsignedInputs, Nil).pipeTo(sender.ref)
    val signTxResponse = sender.expectMsgType[SignTransactionResponse]
    assert(signTxResponse.complete)

    val txWithNoOutputs = signTxResponse.tx.copy(txOut = Nil)
    bitcoinClient.publishTransaction(txWithNoOutputs).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    bitcoinClient.getBlockHeight().pipeTo(sender.ref)
    val blockHeight = sender.expectMsgType[BlockHeight]
    val txWithFutureCltv = signTxResponse.tx.copy(lockTime = blockHeight.toLong + 1)
    bitcoinClient.publishTransaction(txWithFutureCltv).pipeTo(sender.ref)
    sender.expectMsgType[Failure]

    bitcoinClient.publishTransaction(signTxResponse.tx).pipeTo(sender.ref)
    sender.expectMsg(signTxResponse.tx.txid)
  }

  test("send and list transactions") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    bitcoinClient.onChainBalance().pipeTo(sender.ref)
    val initialBalance = sender.expectMsgType[OnChainBalance]
    assert(initialBalance.unconfirmed == 0.sat)
    assert(initialBalance.confirmed > 50.btc.toSatoshi)

    val address = "n2YKngjUp139nkjKvZGnfLRN6HzzYxJsje"
    val amount = 150.millibtc.toSatoshi
    bitcoinClient.sendToAddress(address, amount, 3).pipeTo(sender.ref)
    val txid = sender.expectMsgType[ByteVector32]

    bitcoinClient.listTransactions(25, 0).pipeTo(sender.ref)
    val Some(tx1) = sender.expectMsgType[List[WalletTx]].collectFirst { case tx if tx.txid == txid => tx }
    assert(tx1.address == address)
    assert(tx1.amount == -amount)
    assert(tx1.fees < 0.sat)
    assert(tx1.confirmations == 0)

    bitcoinClient.onChainBalance().pipeTo(sender.ref)
    // NB: we use + because these amounts are already negative
    sender.expectMsg(initialBalance.copy(confirmed = initialBalance.confirmed + tx1.amount + tx1.fees))

    generateBlocks(1)
    bitcoinClient.listTransactions(25, 0).pipeTo(sender.ref)
    val Some(tx2) = sender.expectMsgType[List[WalletTx]].collectFirst { case tx if tx.txid == txid => tx }
    assert(tx2.address == address)
    assert(tx2.amount == -amount)
    assert(tx2.fees < 0.sat)
    assert(tx2.confirmations == 1)
  }

  test("get mempool transaction") {
    val sender = TestProbe()
    val address = getNewAddress(sender)
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    def spendWalletTx(tx: Transaction, fees: Satoshi): Transaction = {
      val inputs = tx.txOut.indices.map(vout => Map("txid" -> tx.txid, "vout" -> vout))
      val amount = tx.txOut.map(_.amount).sum - fees
      bitcoinrpcclient.invoke("createrawtransaction", inputs, Map(address -> amount.toBtc.toBigDecimal)).pipeTo(sender.ref)
      val JString(unsignedTx) = sender.expectMsgType[JValue]
      bitcoinClient.signTransaction(Transaction.read(unsignedTx), Nil).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[SignTransactionResponse].tx
      bitcoinClient.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
      signedTx
    }

    val tx1 = sendToAddress(address, 0.5 btc, sender)
    val tx2 = spendWalletTx(tx1, 5000 sat)
    val tx3 = spendWalletTx(tx2, 7500 sat)

    bitcoinClient.getMempoolTx(tx1.txid).pipeTo(sender.ref)
    val mempoolTx1 = sender.expectMsgType[MempoolTx]
    assert(mempoolTx1.ancestorCount == 0)
    assert(mempoolTx1.descendantCount == 2)
    assert(mempoolTx1.fees == mempoolTx1.ancestorFees)
    assert(mempoolTx1.descendantFees == mempoolTx1.fees + 12500.sat)

    bitcoinClient.getMempoolTx(tx2.txid).pipeTo(sender.ref)
    val mempoolTx2 = sender.expectMsgType[MempoolTx]
    assert(mempoolTx2.ancestorCount == 1)
    assert(mempoolTx2.descendantCount == 1)
    assert(mempoolTx2.fees == 5000.sat)
    assert(mempoolTx2.descendantFees == 12500.sat)
    assert(mempoolTx2.ancestorFees == mempoolTx1.fees + 5000.sat)

    bitcoinClient.getMempoolTx(tx3.txid).pipeTo(sender.ref)
    val mempoolTx3 = sender.expectMsgType[MempoolTx]
    assert(mempoolTx3.ancestorCount == 2)
    assert(mempoolTx3.descendantCount == 0)
    assert(mempoolTx3.fees == 7500.sat)
    assert(mempoolTx3.descendantFees == mempoolTx3.fees)
    assert(mempoolTx3.ancestorFees == mempoolTx1.fees + 12500.sat)
  }

  test("abandon transaction") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    // Broadcast a wallet transaction.
    val opts = FundTransactionOptions(TestConstants.feeratePerKw, changePosition = Some(1))
    bitcoinClient.fundTransaction(Transaction(2, Nil, Seq(TxOut(250000 sat, Script.pay2wpkh(randomKey().publicKey))), 0), opts).pipeTo(sender.ref)
    val fundedTx1 = sender.expectMsgType[FundTransactionResponse].tx
    bitcoinClient.signTransaction(fundedTx1, Nil).pipeTo(sender.ref)
    val signedTx1 = sender.expectMsgType[SignTransactionResponse].tx
    bitcoinClient.publishTransaction(signedTx1).pipeTo(sender.ref)
    sender.expectMsg(signedTx1.txid)

    // Double-spend that transaction.
    val fundedTx2 = fundedTx1.copy(txOut = TxOut(200000 sat, Script.pay2wpkh(randomKey().publicKey)) +: fundedTx1.txOut.tail)
    bitcoinClient.signTransaction(fundedTx2, Nil).pipeTo(sender.ref)
    val signedTx2 = sender.expectMsgType[SignTransactionResponse].tx
    assert(signedTx2.txid != signedTx1.txid)
    bitcoinClient.publishTransaction(signedTx2).pipeTo(sender.ref)
    sender.expectMsg(signedTx2.txid)

    // Abandon the first wallet transaction.
    bitcoinClient.abandonTransaction(signedTx1.txid).pipeTo(sender.ref)
    sender.expectMsg(true)

    // Abandoning an already-abandoned transaction is a no-op.
    bitcoinClient.abandonTransaction(signedTx1.txid).pipeTo(sender.ref)
    sender.expectMsg(true)

    // We can't abandon the second transaction (it's in the mempool).
    bitcoinClient.abandonTransaction(signedTx2.txid).pipeTo(sender.ref)
    sender.expectMsg(false)

    // We can't abandon a confirmed transaction.
    bitcoinClient.abandonTransaction(signedTx2.txIn.head.outPoint.txid).pipeTo(sender.ref)
    sender.expectMsg(false)
  }

  test("detect if tx has been double-spent") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    // first let's create a tx
    val noInputTx1 = Transaction(2, Nil, Seq(TxOut(500_000 sat, Script.pay2wpkh(randomKey().publicKey))), 0)
    bitcoinClient.fundTransaction(noInputTx1, FundTransactionOptions(FeeratePerKw(2500 sat))).pipeTo(sender.ref)
    val unsignedTx1 = sender.expectMsgType[FundTransactionResponse].tx
    bitcoinClient.signTransaction(unsignedTx1).pipeTo(sender.ref)
    val tx1 = sender.expectMsgType[SignTransactionResponse].tx

    // let's then generate another tx that double spends the first one
    val unsignedTx2 = tx1.copy(txOut = Seq(TxOut(tx1.txOut.map(_.amount).sum, Script.pay2wpkh(randomKey().publicKey))))
    bitcoinClient.signTransaction(unsignedTx2).pipeTo(sender.ref)
    val tx2 = sender.expectMsgType[SignTransactionResponse].tx

    // tx1/tx2 haven't been published, so tx1 isn't double-spent
    bitcoinClient.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(false)
    // let's publish tx2
    bitcoinClient.publishTransaction(tx2).pipeTo(sender.ref)
    sender.expectMsg(tx2.txid)
    // tx2 hasn't been confirmed so tx1 is still not considered double-spent
    bitcoinClient.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(false)
    // tx2 isn't considered double-spent either
    bitcoinClient.doubleSpent(tx2).pipeTo(sender.ref)
    sender.expectMsg(false)
    // let's confirm tx2
    generateBlocks(1)
    // this time tx1 has been double-spent
    bitcoinClient.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(true)
    // and tx2 isn't considered double-spent since it's confirmed
    bitcoinClient.doubleSpent(tx2).pipeTo(sender.ref)
    sender.expectMsg(false)
  }

  test("detect if tx has been double-spent (with unconfirmed inputs)") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    val priv = randomKey()

    // Let's create one confirmed and one unconfirmed utxo.
    val (confirmedParentTx, unconfirmedParentTx) = {
      val txs = Seq(400_000 sat, 500_000 sat).map(amount => {
        val noInputTx = Transaction(2, Nil, Seq(TxOut(amount, Script.pay2wpkh(priv.publicKey))), 0)
        bitcoinClient.fundTransaction(noInputTx, FundTransactionOptions(FeeratePerKw(2500 sat), lockUtxos = true)).pipeTo(sender.ref)
        val unsignedTx = sender.expectMsgType[FundTransactionResponse].tx
        bitcoinClient.signTransaction(unsignedTx).pipeTo(sender.ref)
        sender.expectMsgType[SignTransactionResponse].tx
      })
      bitcoinClient.publishTransaction(txs.head).pipeTo(sender.ref)
      sender.expectMsg(txs.head.txid)
      generateBlocks(1)
      bitcoinClient.publishTransaction(txs.last).pipeTo(sender.ref)
      sender.expectMsg(txs.last.txid)
      (txs.head, txs.last)
    }

    // Let's spend those unconfirmed utxos.
    val childTx = createSpendManyP2WPKH(Seq(confirmedParentTx, unconfirmedParentTx), priv, priv.publicKey, 500 sat, 0, 0)
    // The tx hasn't been published, so it isn't double-spent.
    bitcoinClient.doubleSpent(childTx).pipeTo(sender.ref)
    sender.expectMsg(false)
    // We publish the tx and verify it isn't double-spent.
    bitcoinClient.publishTransaction(childTx).pipeTo(sender.ref)
    sender.expectMsg(childTx.txid)
    bitcoinClient.doubleSpent(childTx).pipeTo(sender.ref)
    sender.expectMsg(false)

    // We double-spend the unconfirmed parent, which evicts our child transaction.
    {
      val previousAmountOut = unconfirmedParentTx.txOut.map(_.amount).sum
      val unsignedTx = unconfirmedParentTx.copy(txOut = Seq(TxOut(previousAmountOut - 50_000.sat, Script.pay2wpkh(randomKey().publicKey))))
      bitcoinClient.signTransaction(unsignedTx).pipeTo(sender.ref)
      val signedTx = sender.expectMsgType[SignTransactionResponse].tx
      bitcoinClient.publishTransaction(signedTx).pipeTo(sender.ref)
      sender.expectMsg(signedTx.txid)
    }

    // We can't know whether the child transaction is double-spent or not, as its unconfirmed input is now unknown: it's
    // not in the blockchain nor in the mempool. This unknown input may reappear in the future and the tx could then be
    // published again.
    bitcoinClient.doubleSpent(childTx).pipeTo(sender.ref)
    sender.expectMsg(false)

    // We double-spend the confirmed input.
    val spendingTx = createSpendP2WPKH(confirmedParentTx, priv, priv.publicKey, 600 sat, 0, 0)
    bitcoinClient.publishTransaction(spendingTx).pipeTo(sender.ref)
    sender.expectMsg(spendingTx.txid)
    // While the spending transaction is unconfirmed, we don't consider our transaction double-spent.
    bitcoinClient.doubleSpent(childTx).pipeTo(sender.ref)
    sender.expectMsg(false)
    // Once the spending transaction confirms, we know that our transaction is double-spent.
    generateBlocks(1)
    bitcoinClient.doubleSpent(childTx).pipeTo(sender.ref)
    sender.expectMsg(true)
  }

  test("find spending transaction of a given output") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    bitcoinClient.getBlockHeight().pipeTo(sender.ref)
    val blockHeight = sender.expectMsgType[BlockHeight]

    val address = getNewAddress(sender)
    val tx1 = sendToAddress(address, 5 btc, sender)

    // Transaction is still in the mempool at that point
    bitcoinClient.getTxConfirmations(tx1.txid).pipeTo(sender.ref)
    sender.expectMsg(Some(0))
    // If we omit the mempool, tx1's input is still considered unspent.
    bitcoinClient.isTransactionOutputSpendable(tx1.txIn.head.outPoint.txid, tx1.txIn.head.outPoint.index.toInt, includeMempool = false).pipeTo(sender.ref)
    sender.expectMsg(true)
    // If we include the mempool, we see that tx1's input is now spent.
    bitcoinClient.isTransactionOutputSpendable(tx1.txIn.head.outPoint.txid, tx1.txIn.head.outPoint.index.toInt, includeMempool = true).pipeTo(sender.ref)
    sender.expectMsg(false)
    // If we omit the mempool, tx1's output is not considered spendable because we can't even find that output.
    bitcoinClient.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = false).pipeTo(sender.ref)
    sender.expectMsg(false)
    // If we include the mempool, we see that tx1 produces an output that is still unspent.
    bitcoinClient.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = true).pipeTo(sender.ref)
    sender.expectMsg(true)

    // Let's confirm our transaction.
    generateBlocks(1)
    bitcoinClient.getBlockHeight().pipeTo(sender.ref)
    val blockHeight1 = sender.expectMsgType[BlockHeight]
    assert(blockHeight1 == blockHeight + 1)
    bitcoinClient.getTxConfirmations(tx1.txid).pipeTo(sender.ref)
    sender.expectMsg(Some(1))
    bitcoinClient.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = false).pipeTo(sender.ref)
    sender.expectMsg(true)
    bitcoinClient.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = true).pipeTo(sender.ref)
    sender.expectMsg(true)

    generateBlocks(1)
    bitcoinClient.lookForSpendingTx(None, tx1.txIn.head.outPoint.txid, tx1.txIn.head.outPoint.index.toInt).pipeTo(sender.ref)
    sender.expectMsg(tx1)
  }

  test("compute pubkey from a receive address") {
    val sender = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)

    bitcoinClient.getReceiveAddress().pipeTo(sender.ref)
    val address = sender.expectMsgType[String]

    bitcoinClient.getReceivePubkey(receiveAddress = Some(address)).pipeTo(sender.ref)
    val receiveKey = sender.expectMsgType[PublicKey]
    assert(addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash) == Script.pay2wpkh(receiveKey))
  }

}