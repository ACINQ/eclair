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
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, Btc, ByteVector32, MilliBtc, OutPoint, Satoshi, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet.{FundTransactionResponse, SignTransactionResponse, WalletTransaction}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, ExtendedBitcoinClient, JsonRPCError}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.{LongToBtcAmount, TestKitBaseClass, addressToPublicKeyScript, randomKey}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JString, _}
import org.json4s.{DefaultFormats, Formats}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Random, Try}

class BitcoinCoreWalletSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll with Logging {

  val commonConfig = ConfigFactory.parseMap(Map(
    "eclair.chain" -> "regtest",
    "eclair.spv" -> false,
    "eclair.server.public-ips.1" -> "localhost",
    "eclair.bitcoind.port" -> bitcoindPort,
    "eclair.bitcoind.rpcport" -> bitcoindRpcPort,
    "eclair.router-broadcast-interval" -> "2 second",
    "eclair.auto-reconnect" -> false).asJava)
  val config = ConfigFactory.load(commonConfig).getConfig("eclair")

  val walletPassword = Random.alphanumeric.take(8).mkString

  implicit val formats: Formats = DefaultFormats

  override def beforeAll(): Unit = {
    startBitcoind()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  test("wait bitcoind ready") {
    waitForBitcoindReady()
  }

  def getLocks(sender: TestProbe = TestProbe()): Set[OutPoint] = {
    sender.send(bitcoincli, BitcoinReq("listlockunspent"))
    val JArray(locks) = sender.expectMsgType[JValue]
    val txids = locks.map { item =>
      val JString(txid) = item \ "txid"
      val JInt(vout) = item \ "vout"
      OutPoint(ByteVector32.fromValidHex(txid).reverse, vout.toInt)
    }
    txids.toSet
  }

  test("unlock transaction inputs if publishing fails") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))
    val wallet = new BitcoinCoreWallet(bitcoinClient)
    val sender = TestProbe()
    val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey.publicKey, randomKey.publicKey)))

    // create a huge tx so we make sure it has > 1 inputs
    wallet.makeFundingTx(pubkeyScript, Btc(250), FeeratePerKw(1000 sat)).pipeTo(sender.ref)
    val MakeFundingTxResponse(fundingTx, outputIndex, _) = sender.expectMsgType[MakeFundingTxResponse]

    // spend the first 2 inputs
    val tx1 = fundingTx.copy(
      txIn = fundingTx.txIn.take(2),
      txOut = fundingTx.txOut.updated(outputIndex, fundingTx.txOut(outputIndex).copy(amount = Btc(50)))
    )
    wallet.signTransaction(tx1).pipeTo(sender.ref)
    val SignTransactionResponse(tx2, true) = sender.expectMsgType[SignTransactionResponse]

    wallet.commit(tx2).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    // fundingTx inputs are still locked except for the first 2 that were just spent
    val expectedLocks = fundingTx.txIn.drop(2).map(_.outPoint).toSet
    awaitCond({
      val locks = getLocks(sender)
      expectedLocks -- locks isEmpty
    }, max = 10 seconds, interval = 1 second)

    // publishing fundingTx will fail as its first 2 inputs are already spent by tx above in the mempool
    wallet.commit(fundingTx).pipeTo(sender.ref)
    val result = sender.expectMsgType[Boolean]
    assert(!result)

    // and all locked inputs should now be unlocked
    awaitCond({
      val locks = getLocks(sender)
      locks isEmpty
    }, max = 10 seconds, interval = 1 second)
  }

  test("unlock outpoints correctly") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))
    val wallet = new BitcoinCoreWallet(bitcoinClient)
    val sender = TestProbe()
    val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey.publicKey, randomKey.publicKey)))

    {
      // test #1: unlock outpoints that are actually locked
      // create a huge tx so we make sure it has > 1 inputs
      wallet.makeFundingTx(pubkeyScript, Btc(250), FeeratePerKw(1000 sat)).pipeTo(sender.ref)
      val MakeFundingTxResponse(fundingTx, _, _) = sender.expectMsgType[MakeFundingTxResponse]
      assert(fundingTx.txIn.size > 2)
      assert(getLocks(sender) == fundingTx.txIn.map(_.outPoint).toSet)
      wallet.rollback(fundingTx).pipeTo(sender.ref)
      assert(sender.expectMsgType[Boolean])
    }
    {
      // test #2: some outpoints are locked, some are unlocked
      wallet.makeFundingTx(pubkeyScript, Btc(250), FeeratePerKw(1000 sat)).pipeTo(sender.ref)
      val MakeFundingTxResponse(fundingTx, _, _) = sender.expectMsgType[MakeFundingTxResponse]
      assert(fundingTx.txIn.size > 2)
      assert(getLocks(sender) == fundingTx.txIn.map(_.outPoint).toSet)

      // unlock the first 2 outpoints
      val tx1 = fundingTx.copy(txIn = fundingTx.txIn.take(2))
      wallet.rollback(tx1).pipeTo(sender.ref)
      assert(sender.expectMsgType[Boolean])
      assert(getLocks(sender) == fundingTx.txIn.drop(2).map(_.outPoint).toSet)

      // and try to unlock all outpoints: it should work too
      wallet.rollback(fundingTx).pipeTo(sender.ref)
      assert(sender.expectMsgType[Boolean])
      assert(getLocks(sender) isEmpty)
    }
  }

  test("absence of rounding") {
    val txIn = Transaction(1, Nil, Nil, 42)
    val hexOut = "02000000013361e994f6bd5cbe9dc9e8cb3acdc12bc1510a3596469d9fc03cfddd71b223720000000000feffffff02c821354a00000000160014b6aa25d6f2a692517f2cf1ad55f243a5ba672cac404b4c0000000000220020822eb4234126c5fc84910e51a161a9b7af94eb67a2344f7031db247e0ecc2f9200000000"

    0 to 9 foreach { satoshi =>
      val apiAmount = JDecimal(BigDecimal(s"0.0000000$satoshi"))
      val bitcoinClient = new BasicBitcoinJsonRPCClient(
        user = config.getString("bitcoind.rpcuser"),
        password = config.getString("bitcoind.rpcpassword"),
        host = config.getString("bitcoind.host"),
        port = config.getInt("bitcoind.rpcport")) {
        override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] = method match {
          case "getbalances" => Future(JObject("mine" -> JObject("trusted" -> apiAmount, "untrusted_pending" -> apiAmount)))(ec)
          case "fundrawtransaction" => Future(JObject(List("hex" -> JString(hexOut), "changepos" -> JInt(1), "fee" -> apiAmount)))(ec)
          case _ => Future.failed(new RuntimeException(s"Test BasicBitcoinJsonRPCClient: method $method is not supported"))
        }
      }

      val sender = TestProbe()
      val wallet = new BitcoinCoreWallet(bitcoinClient)
      wallet.getBalance.pipeTo(sender.ref)
      assert(sender.expectMsgType[OnChainBalance] === OnChainBalance(Satoshi(satoshi), Satoshi(satoshi)))

      wallet.fundTransaction(txIn, lockUnspents = false, FeeratePerKw(250 sat)).pipeTo(sender.ref)
      val FundTransactionResponse(_, _, fee) = sender.expectMsgType[FundTransactionResponse]
      assert(fee == Satoshi(satoshi))
    }
  }

  test("handle errors when signing transactions") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))
    val wallet = new BitcoinCoreWallet(bitcoinClient)
    val sender = TestProbe()

    // create a transaction that spends UTXOs that don't exist
    wallet.getReceiveAddress.pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    val unknownTxids = Seq(
      ByteVector32.fromValidHex("01" * 32),
      ByteVector32.fromValidHex("02" * 32),
      ByteVector32.fromValidHex("03" * 32)
    )
    val unsignedTx = Transaction(version = 2,
      txIn = Seq(
        TxIn(OutPoint(unknownTxids(0), 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL),
        TxIn(OutPoint(unknownTxids(1), 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL),
        TxIn(OutPoint(unknownTxids(2), 0), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL)
      ),
      txOut = TxOut(1000000 sat, addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)) :: Nil,
      lockTime = 0)

    // signing it should fail, and the error message should contain the txids of the UTXOs that could not be used
    wallet.signTransaction(unsignedTx).pipeTo(sender.ref)
    val Failure(JsonRPCError(error)) = sender.expectMsgType[Failure]
    unknownTxids.foreach(id => assert(error.message.contains(id.toString())))
  }

  test("create/commit/rollback funding txes") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))
    val extendedClient = new ExtendedBitcoinClient(bitcoinClient)
    val wallet = new BitcoinCoreWallet(bitcoinClient)
    val sender = TestProbe()

    wallet.getBalance.pipeTo(sender.ref)
    assert(sender.expectMsgType[OnChainBalance].confirmed > 0.sat)

    wallet.getReceiveAddress.pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    assert(Try(addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)).isSuccess)

    val fundingTxes = for (_ <- 0 to 3) yield {
      val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey.publicKey, randomKey.publicKey)))
      wallet.makeFundingTx(pubkeyScript, MilliBtc(50), FeeratePerKw(200 sat)).pipeTo(sender.ref) // create a tx with an invalid feerate (too little)
      val belowFeeFundingTx = sender.expectMsgType[MakeFundingTxResponse].fundingTx
      extendedClient.publishTransaction(belowFeeFundingTx).pipeTo(sender.ref) // try publishing the tx
      assert(sender.expectMsgType[Failure].cause.asInstanceOf[JsonRPCError].error.message.contains("min relay fee not met"))
      wallet.rollback(belowFeeFundingTx).pipeTo(sender.ref) // rollback the locked outputs
      assert(sender.expectMsgType[Boolean])

      // now fund a tx with correct feerate
      wallet.makeFundingTx(pubkeyScript, MilliBtc(50), FeeratePerKw(250 sat)).pipeTo(sender.ref)
      sender.expectMsgType[MakeFundingTxResponse].fundingTx
    }

    assert(getLocks(sender).size === 4)

    wallet.commit(fundingTxes(0)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    wallet.rollback(fundingTxes(1)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    wallet.commit(fundingTxes(2)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    wallet.rollback(fundingTxes(3)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    extendedClient.getTransaction(fundingTxes(0).txid).pipeTo(sender.ref)
    sender.expectMsg(fundingTxes(0))

    extendedClient.getTransaction(fundingTxes(2).txid).pipeTo(sender.ref)
    sender.expectMsg(fundingTxes(2))

    // NB: from 0.17.0 on bitcoin core will clear locks when a tx is published
    assert(getLocks(sender).isEmpty)
  }

  test("encrypt wallet") {
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("encryptwallet", walletPassword))
    stopBitcoind()
    startBitcoind()
    waitForBitcoindReady()
  }

  test("unlock failed funding txes") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))
    val wallet = new BitcoinCoreWallet(bitcoinClient)
    val sender = TestProbe()

    wallet.getBalance.pipeTo(sender.ref)
    assert(sender.expectMsgType[OnChainBalance].confirmed > 0.sat)

    wallet.getReceiveAddress.pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    assert(Try(addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)).isSuccess)

    assert(getLocks(sender).isEmpty)

    val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey.publicKey, randomKey.publicKey)))
    wallet.makeFundingTx(pubkeyScript, MilliBtc(50), FeeratePerKw(10000 sat)).pipeTo(sender.ref)
    val error = sender.expectMsgType[Failure].cause.asInstanceOf[JsonRPCError].error
    assert(error.message.contains("Please enter the wallet passphrase with walletpassphrase first"))

    assert(getLocks(sender).isEmpty)

    sender.send(bitcoincli, BitcoinReq("walletpassphrase", walletPassword, 10))
    sender.expectMsgType[JValue]

    wallet.makeFundingTx(pubkeyScript, MilliBtc(50), FeeratePerKw(10000 sat)).pipeTo(sender.ref)
    val MakeFundingTxResponse(fundingTx, _, _) = sender.expectMsgType[MakeFundingTxResponse]

    wallet.commit(fundingTx).pipeTo(sender.ref)
    sender.expectMsg(true)

    wallet.getBalance.pipeTo(sender.ref)
    assert(sender.expectMsgType[OnChainBalance].confirmed > 0.sat)
  }

  test("getReceivePubkey should return the raw pubkey for the receive address") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))
    val wallet = new BitcoinCoreWallet(bitcoinClient)
    val sender = TestProbe()

    wallet.getReceiveAddress.pipeTo(sender.ref)
    val address = sender.expectMsgType[String]

    wallet.getReceivePubkey(receiveAddress = Some(address)).pipeTo(sender.ref)
    val receiveKey = sender.expectMsgType[PublicKey]
    assert(addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash) === Script.pay2wpkh(receiveKey))
  }

  test("send and list transactions") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))
    val wallet = new BitcoinCoreWallet(bitcoinClient)
    val sender = TestProbe()

    wallet.getBalance.pipeTo(sender.ref)
    val initialBalance = sender.expectMsgType[OnChainBalance]
    assert(initialBalance.unconfirmed === 0.sat)
    assert(initialBalance.confirmed > 50.btc.toSatoshi)

    val address = "n2YKngjUp139nkjKvZGnfLRN6HzzYxJsje"
    val amount = 150.mbtc.toSatoshi
    wallet.sendToAddress(address, amount, 3).pipeTo(sender.ref)
    val txid = sender.expectMsgType[ByteVector32]

    wallet.listTransactions(25, 0).pipeTo(sender.ref)
    val Some(tx1) = sender.expectMsgType[List[WalletTransaction]].collectFirst { case tx if tx.txid == txid => tx }
    assert(tx1.address === address)
    assert(tx1.amount === -amount)
    assert(tx1.fees < 0.sat)
    assert(tx1.confirmations === 0)

    wallet.getBalance.pipeTo(sender.ref)
    // NB: we use + because these amounts are already negative
    sender.expectMsg(initialBalance.copy(confirmed = initialBalance.confirmed + tx1.amount + tx1.fees))

    generateBlocks(bitcoincli, 1)
    wallet.listTransactions(25, 0).pipeTo(sender.ref)
    val Some(tx2) = sender.expectMsgType[List[WalletTransaction]].collectFirst { case tx if tx.txid == txid => tx }
    assert(tx2.address === address)
    assert(tx2.amount === -amount)
    assert(tx2.fees < 0.sat)
    assert(tx2.confirmations === 1)
  }

}
