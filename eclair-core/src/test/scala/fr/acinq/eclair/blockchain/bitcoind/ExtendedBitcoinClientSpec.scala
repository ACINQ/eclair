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
import fr.acinq.bitcoin.{Btc, BtcDouble, Transaction}
import fr.acinq.eclair.TestKitBaseClass
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, ExtendedBitcoinClient}
import grizzled.slf4j.Logging
import org.json4s.JsonAST._
import org.json4s.{DefaultFormats, Formats}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters._

class ExtendedBitcoinClientSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll with Logging {

  val commonConfig = ConfigFactory.parseMap(Map(
    "eclair.chain" -> "regtest",
    "eclair.spv" -> false,
    "eclair.server.public-ips.1" -> "localhost",
    "eclair.bitcoind.port" -> bitcoindPort,
    "eclair.bitcoind.rpcport" -> bitcoindRpcPort,
    "eclair.router-broadcast-interval" -> "2 second",
    "eclair.auto-reconnect" -> false).asJava)
  val config = ConfigFactory.load(commonConfig).getConfig("eclair")

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

  def sendToAddress(address: String, amount: Btc, sender: TestProbe = TestProbe()): Transaction = {
    sender.send(bitcoincli, BitcoinReq("sendtoaddress", address, amount.toDouble))
    val JString(txid1) = sender.expectMsgType[JString]
    sender.send(bitcoincli, BitcoinReq("getrawtransaction", txid1))
    val JString(rawTx) = sender.expectMsgType[JString]
    Transaction.read(rawTx)
  }

  test("send transaction idempotent") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))

    val sender = TestProbe()
    bitcoinClient.invoke("getnewaddress").pipeTo(sender.ref)
    val JString(address) = sender.expectMsgType[JString]
    bitcoinClient.invoke("createrawtransaction", Array.empty, Map(address -> 6)).pipeTo(sender.ref)
    val JString(noinputTx) = sender.expectMsgType[JString]
    bitcoinClient.invoke("fundrawtransaction", noinputTx).pipeTo(sender.ref)
    val json = sender.expectMsgType[JValue]
    val JString(unsignedtx) = json \ "hex"
    val JInt(changePos) = json \ "changepos"
    bitcoinClient.invoke("signrawtransactionwithwallet", unsignedtx).pipeTo(sender.ref)
    val JString(signedTx) = sender.expectMsgType[JValue] \ "hex"
    val tx = Transaction.read(signedTx)

    // test starts here
    val client = new ExtendedBitcoinClient(bitcoinClient)
    // we publish it a first time
    client.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)
    // we publish the tx a second time to test idempotence
    client.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)
    // let's confirm the tx
    sender.send(bitcoincli, BitcoinReq("getnewaddress"))
    val JString(generatingAddress) = sender.expectMsgType[JValue]
    bitcoinClient.invoke("generatetoaddress", 1, generatingAddress).pipeTo(sender.ref)
    sender.expectMsgType[JValue]
    // and publish the tx a third time to test idempotence
    client.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)

    // now let's spend the output of the tx
    val spendingTx = {
      val pos = if (changePos == 0) 1 else 0
      bitcoinClient.invoke("createrawtransaction", Array(Map("txid" -> tx.txid.toHex, "vout" -> pos)), Map(address -> 5.99999)).pipeTo(sender.ref)
      val JString(unsignedtx) = sender.expectMsgType[JValue]
      bitcoinClient.invoke("signrawtransactionwithwallet", unsignedtx).pipeTo(sender.ref)
      val JString(signedTx) = sender.expectMsgType[JValue] \ "hex"
      Transaction.read(signedTx)
    }
    client.publishTransaction(spendingTx).pipeTo(sender.ref)
    sender.expectMsg(spendingTx.txid)

    // and publish the tx a fourth time to test idempotence
    client.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)
    // let's confirm the tx
    bitcoinClient.invoke("generatetoaddress", 1, generatingAddress).pipeTo(sender.ref)
    sender.expectMsgType[JValue]
    // and publish the tx a fifth time to test idempotence
    client.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(tx.txid)

    // this one should be rejected
    client.publishTransaction(Transaction.read("02000000000101b9e2a3f518fd74e696d258fed3c78c43f84504e76c99212e01cf225083619acf00000000000d0199800136b34b00000000001600145464ce1e5967773922506e285780339d72423244040047304402206795df1fd93c285d9028c384aacf28b43679f1c3f40215fd7bd1abbfb816ee5a022047a25b8c128e692d4717b6dd7b805aa24ecbbd20cfd664ab37a5096577d4a15d014730440220770f44121ed0e71ec4b482dded976f2febd7500dfd084108e07f3ce1e85ec7f5022025b32dc0d551c47136ce41bfb80f5a10de95c0babb22a3ae2d38e6688b32fcb20147522102c2662ab3e4fa18a141d3be3317c6ee134aff10e6cd0a91282a25bf75c0481ebc2102e952dd98d79aa796289fa438e4fdeb06ed8589ff2a0f032b0cfcb4d7b564bc3252aea58d1120")).pipeTo(sender.ref)
    sender.expectMsgType[Failure]
  }

  test("detect if tx has been doublespent") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))
    val client = new ExtendedBitcoinClient(bitcoinClient)
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
    bitcoinClient.invoke("createrawtransaction", inputs, Map(address -> tx1.txOut.map(_.amount).sum.toLong * 1.0 / 1e8)).pipeTo(sender.ref)
    val JString(unsignedtx2) = sender.expectMsgType[JValue]
    bitcoinClient.invoke("signrawtransactionwithwallet", unsignedtx2).pipeTo(sender.ref)
    val JString(signedTx2) = sender.expectMsgType[JValue] \ "hex"
    val tx2 = Transaction.read(signedTx2)

    // tx1/tx2 haven't been published, so tx1 isn't double spent
    client.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(false)
    // let's publish tx2
    client.publishTransaction(tx2).pipeTo(sender.ref)
    sender.expectMsg(tx2.txid)
    // tx2 hasn't been confirmed so tx1 is still not considered double-spent
    client.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(false)
    // let's confirm tx2
    generateBlocks(bitcoincli, 1)
    // this time tx1 has been double spent
    client.doubleSpent(tx1).pipeTo(sender.ref)
    sender.expectMsg(true)
  }

  test("find spending transaction of a given output") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))
    val client = new ExtendedBitcoinClient(bitcoinClient)
    val sender = TestProbe()

    client.getBlockCount.pipeTo(sender.ref)
    val blockCount = sender.expectMsgType[Long]

    bitcoinClient.invoke("getnewaddress").pipeTo(sender.ref)
    val JString(address) = sender.expectMsgType[JString]

    val tx1 = sendToAddress(address, 5 btc, sender)

    // Transaction is still in the mempool at that point
    client.getTxConfirmations(tx1.txid).pipeTo(sender.ref)
    sender.expectMsg(Some(0))
    client.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = false).pipeTo(sender.ref)
    sender.expectMsg(false)
    client.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = true).pipeTo(sender.ref)
    sender.expectMsg(true)

    // Let's confirm our transaction.
    generateBlocks(bitcoincli, 1)
    client.getBlockCount.pipeTo(sender.ref)
    val blockCount1 = sender.expectMsgType[Long]
    assert(blockCount1 === blockCount + 1)
    client.getTxConfirmations(tx1.txid).pipeTo(sender.ref)
    sender.expectMsg(Some(1))
    client.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = false).pipeTo(sender.ref)
    sender.expectMsg(true)
    client.isTransactionOutputSpendable(tx1.txid, 0, includeMempool = true).pipeTo(sender.ref)
    sender.expectMsg(true)

    generateBlocks(bitcoincli, 1)
    client.lookForSpendingTx(None, tx1.txIn.head.outPoint.txid, tx1.txIn.head.outPoint.index.toInt).pipeTo(sender.ref)
    sender.expectMsg(tx1)
  }

}