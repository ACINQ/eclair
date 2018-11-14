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
import fr.acinq.bitcoin.Transaction
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, ExtendedBitcoinClient}
import grizzled.slf4j.Logging
import org.json4s.JsonAST._
import org.json4s.{DefaultFormats, JString}
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global


class ExtendedBitcoinClientSpec extends TestKit(ActorSystem("test")) with BitcoindService with FunSuiteLike with BeforeAndAfterAll with Logging {

  val commonConfig = ConfigFactory.parseMap(Map("eclair.chain" -> "regtest", "eclair.spv" -> false, "eclair.server.public-ips.1" -> "localhost", "eclair.bitcoind.port" -> 28333, "eclair.bitcoind.rpcport" -> 28332, "eclair.bitcoind.zmq" -> "tcp://127.0.0.1:28334", "eclair.router-broadcast-interval" -> "2 second", "eclair.auto-reconnect" -> false))
  val config = ConfigFactory.load(commonConfig).getConfig("eclair")

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
    bitcoinClient.invoke("signrawtransaction", unsignedtx).pipeTo(sender.ref)
    val JString(signedTx) = sender.expectMsgType[JValue] \ "hex"
    val tx = Transaction.read(signedTx)
    val txid = tx.txid.toString()

    // test starts here
    val client = new ExtendedBitcoinClient(bitcoinClient)
    // we publish it a first time
    client.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(txid)
    // we publish the tx a second time to test idempotence
    client.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(txid)
    // let's confirm the tx
    bitcoinClient.invoke("generate", 1).pipeTo(sender.ref)
    sender.expectMsgType[JValue]
    // and publish the tx a third time to test idempotence
    client.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(txid)

    // now let's spent the output of the tx
    val spendingTx = {
      val pos = if (changePos == 0) 1 else 0
      bitcoinClient.invoke("createrawtransaction", Array(Map("txid" -> txid, "vout" -> pos)), Map(address -> 5.99999)).pipeTo(sender.ref)
      val JString(unsignedtx) = sender.expectMsgType[JValue]
      bitcoinClient.invoke("signrawtransaction", unsignedtx).pipeTo(sender.ref)
      val JString(signedTx) = sender.expectMsgType[JValue] \ "hex"
      signedTx
    }
    bitcoinClient.invoke("sendrawtransaction", spendingTx).pipeTo(sender.ref)
    val JString(spendingTxid) = sender.expectMsgType[JValue]

    // and publish the tx a fourth time to test idempotence
    client.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(txid)
    // let's confirm the tx
    bitcoinClient.invoke("generate", 1).pipeTo(sender.ref)
    sender.expectMsgType[JValue]
    // and publish the tx a fifth time to test idempotence
    client.publishTransaction(tx).pipeTo(sender.ref)
    sender.expectMsg(txid)

    // this one should be rejected
    client.publishTransaction(Transaction.read("02000000000101b9e2a3f518fd74e696d258fed3c78c43f84504e76c99212e01cf225083619acf00000000000d0199800136b34b00000000001600145464ce1e5967773922506e285780339d72423244040047304402206795df1fd93c285d9028c384aacf28b43679f1c3f40215fd7bd1abbfb816ee5a022047a25b8c128e692d4717b6dd7b805aa24ecbbd20cfd664ab37a5096577d4a15d014730440220770f44121ed0e71ec4b482dded976f2febd7500dfd084108e07f3ce1e85ec7f5022025b32dc0d551c47136ce41bfb80f5a10de95c0babb22a3ae2d38e6688b32fcb20147522102c2662ab3e4fa18a141d3be3317c6ee134aff10e6cd0a91282a25bf75c0481ebc2102e952dd98d79aa796289fa438e4fdeb06ed8589ff2a0f032b0cfcb4d7b564bc3252aea58d1120")).pipeTo(sender.ref)
    sender.expectMsgType[Failure]
  }
}