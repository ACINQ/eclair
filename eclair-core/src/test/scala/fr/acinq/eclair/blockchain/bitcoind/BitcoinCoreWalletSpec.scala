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

package fr.acinq.eclair.blockchain.bitcoind

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{ByteVector32, Block, MilliBtc, OutPoint, Satoshi, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet.FundTransactionResponse
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, JsonRPCError}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.{addressToPublicKeyScript, randomKey}
import grizzled.slf4j.Logging
import org.json4s.JsonAST._
import org.json4s.{DefaultFormats, JString}
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Try}


class BitcoinCoreWalletSpec extends TestKit(ActorSystem("test")) with BitcoindService with FunSuiteLike with BeforeAndAfterAll with Logging {

  val commonConfig = ConfigFactory.parseMap(Map(
    "eclair.chain" -> "regtest",
    "eclair.spv" -> false,
    "eclair.server.public-ips.1" -> "localhost",
    "eclair.bitcoind.port" -> 28333,
    "eclair.bitcoind.rpcport" -> 28332,
    "eclair.router-broadcast-interval" -> "2 second",
    "eclair.auto-reconnect" -> false))
  val config = ConfigFactory.load(commonConfig).getConfig("eclair")

  val walletPassword = Random.alphanumeric.take(8).mkString

  implicit val formats = DefaultFormats

  override def beforeAll(): Unit = {
    startBitcoind()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  test("wait bitcoind ready") {
    waitForBitcoindReady()
  }

  test("absence of rounding") {
    val hexIn = "020000000001404b4c0000000000220020822eb4234126c5fc84910e51a161a9b7af94eb67a2344f7031db247e0ecc2f9200000000"
    val hexOut = "02000000013361e994f6bd5cbe9dc9e8cb3acdc12bc1510a3596469d9fc03cfddd71b223720000000000feffffff02c821354a00000000160014b6aa25d6f2a692517f2cf1ad55f243a5ba672cac404b4c0000000000220020822eb4234126c5fc84910e51a161a9b7af94eb67a2344f7031db247e0ecc2f9200000000"

    0 to 9 foreach { satoshi =>

      val apiAmount = JDecimal(BigDecimal(s"0.0000000$satoshi"))

      val bitcoinClient = new BasicBitcoinJsonRPCClient(
        user = config.getString("bitcoind.rpcuser"),
        password = config.getString("bitcoind.rpcpassword"),
        host = config.getString("bitcoind.host"),
        port = config.getInt("bitcoind.rpcport")) {

        override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] = method match {
          case "getbalance" => Future(apiAmount)
          case "fundrawtransaction" => Future(JObject(List("hex" -> JString(hexOut), "changepos" -> JInt(1), "fee" -> apiAmount)))
          case _ => Future.failed(new RuntimeException(s"Test BasicBitcoinJsonRPCClient: method $method is not supported"))
        }
      }

      val wallet = new BitcoinCoreWallet(bitcoinClient)

      val sender = TestProbe()

      wallet.getBalance.pipeTo(sender.ref)
      assert(sender.expectMsgType[Satoshi] == Satoshi(satoshi))

      wallet.fundTransaction(hexIn, false, 250).pipeTo(sender.ref)
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
    wallet.getFinalAddress.pipeTo(sender.ref)
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
      txOut = TxOut(Satoshi(1000000), addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)) :: Nil,
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
    val wallet = new BitcoinCoreWallet(bitcoinClient)

    val sender = TestProbe()

    wallet.getBalance.pipeTo(sender.ref)
    assert(sender.expectMsgType[Satoshi] > Satoshi(0))

    wallet.getFinalAddress.pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    assert(Try(addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)).isSuccess)

    val fundingTxes = for (i <- 0 to 3) yield {
      val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey.publicKey, randomKey.publicKey)))
      wallet.makeFundingTx(pubkeyScript, MilliBtc(50), 249).pipeTo(sender.ref)
      assert(sender.expectMsgType[Failure].cause.asInstanceOf[JsonRPCError].error.message.contains("Transaction too large for fee policy"))
      wallet.makeFundingTx(pubkeyScript, MilliBtc(50), 250).pipeTo(sender.ref)
      val MakeFundingTxResponse(fundingTx, _, _) = sender.expectMsgType[MakeFundingTxResponse]
      fundingTx
    }

    sender.send(bitcoincli, BitcoinReq("listlockunspent"))
    assert(sender.expectMsgType[JValue](10 seconds).children.size === 4)

    wallet.commit(fundingTxes(0)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    wallet.rollback(fundingTxes(1)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    wallet.commit(fundingTxes(2)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    wallet.rollback(fundingTxes(3)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    sender.send(bitcoincli, BitcoinReq("getrawtransaction", fundingTxes(0).txid.toString()))
    assert(sender.expectMsgType[JString](10 seconds).s === fundingTxes(0).toString())

    sender.send(bitcoincli, BitcoinReq("getrawtransaction", fundingTxes(2).txid.toString()))
    assert(sender.expectMsgType[JString](10 seconds).s === fundingTxes(2).toString())

    // NB: from 0.17.0 on bitcoin core will clear locks when a tx is published
    sender.send(bitcoincli, BitcoinReq("listlockunspent"))
    assert(sender.expectMsgType[JValue](10 seconds).children.size === 0)
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
    assert(sender.expectMsgType[Satoshi] > Satoshi(0))

    wallet.getFinalAddress.pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    assert(Try(addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)).isSuccess)

    sender.send(bitcoincli, BitcoinReq("listlockunspent"))
    assert(sender.expectMsgType[JValue](10 seconds).children.size === 0)

    val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey.publicKey, randomKey.publicKey)))
    wallet.makeFundingTx(pubkeyScript, MilliBtc(50), 10000).pipeTo(sender.ref)
    val error = sender.expectMsgType[Failure].cause.asInstanceOf[JsonRPCError].error
    assert(error.message.contains("Please enter the wallet passphrase with walletpassphrase first"))

    sender.send(bitcoincli, BitcoinReq("listlockunspent"))
    assert(sender.expectMsgType[JValue](10 seconds).children.size === 0)

    sender.send(bitcoincli, BitcoinReq("walletpassphrase", walletPassword, 10))
    sender.expectMsgType[JValue]

    wallet.makeFundingTx(pubkeyScript, MilliBtc(50), 10000).pipeTo(sender.ref)
    val MakeFundingTxResponse(fundingTx, _, _) = sender.expectMsgType[MakeFundingTxResponse]

    wallet.commit(fundingTx).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    wallet.getBalance.pipeTo(sender.ref)
    assert(sender.expectMsgType[Satoshi] > Satoshi(0))
  }

  test("detect if tx has been doublespent") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))
    val wallet = new BitcoinCoreWallet(bitcoinClient)

    val sender = TestProbe()

    // first let's create a tx
    val address = "n2YKngjUp139nkjKvZGnfLRN6HzzYxJsje"
    bitcoinClient.invoke("createrawtransaction", Array.empty, Map(address -> 6)).pipeTo(sender.ref)
    val JString(noinputTx1) = sender.expectMsgType[JString]
    bitcoinClient.invoke("fundrawtransaction", noinputTx1).pipeTo(sender.ref)
    val json = sender.expectMsgType[JValue]
    val JString(unsignedtx1) = json \ "hex"
    bitcoinClient.invoke("signrawtransactionwithwallet", unsignedtx1).pipeTo(sender.ref)
    val JString(signedTx1) = sender.expectMsgType[JValue] \ "hex"
    val tx1 = Transaction.read(signedTx1)
    // let's then generate another tx that double spends the first one
    val inputs = tx1.txIn.map(txIn => Map("txid" -> txIn.outPoint.txid.toString, "vout" -> txIn.outPoint.index)).toArray
    bitcoinClient.invoke("createrawtransaction", inputs, Map(address -> tx1.txOut.map(_.amount.toLong).sum * 1.0 / 1e8)).pipeTo(sender.ref)
    val JString(unsignedtx2) = sender.expectMsgType[JValue]
    bitcoinClient.invoke("signrawtransactionwithwallet", unsignedtx2).pipeTo(sender.ref)
    val JString(signedTx2) = sender.expectMsgType[JValue] \ "hex"
    val tx2 = Transaction.read(signedTx2)

    // test starts here

    // tx1/tx2 haven't been published, so tx1 isn't double spent
    wallet.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(false)
    // let's publish tx2
    bitcoinClient.invoke("sendrawtransaction", tx2.toString).pipeTo(sender.ref)
    val JString(_) = sender.expectMsgType[JValue]
    // this time tx1 has been double spent
    wallet.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(true)
  }

}
