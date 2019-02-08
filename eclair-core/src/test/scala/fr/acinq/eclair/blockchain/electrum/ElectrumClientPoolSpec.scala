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

package fr.acinq.eclair.blockchain.electrum

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.{BinaryData, Crypto, Transaction}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._
import grizzled.slf4j.Logging
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.concurrent.duration._
import scala.util.Random


class ElectrumClientPoolSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with Logging with BeforeAndAfterAll {
  var pool: ActorRef = _
  val probe = TestProbe()
  val referenceTx = Transaction.read("0200000003947e307df3ab452d23f02b5a65f4ada1804ee733e168e6197b0bd6cc79932b6c010000006a473044022069346ec6526454a481690a3664609f9e8032c34553015cfa2e9b25ebb420a33002206998f21a2aa771ad92a0c1083f4181a3acdb0d42ca51d01be1309da2ffb9cecf012102b4568cc6ee751f6d39f4a908b1fcffdb878f5f784a26a48c0acb0acff9d88e3bfeffffff966d9d969cd5f95bfd53003a35fcc1a50f4fb51f211596e6472583fdc5d38470000000006b4830450221009c9757515009c5709b5b678d678185202b817ef9a69ffb954144615ab11762210220732216384da4bf79340e9c46d0effba6ba92982cca998adfc3f354cec7715f800121035f7c3e077108035026f4ebd5d6ca696ef088d4f34d45d94eab4c41202ec74f9bfefffffff8d5062f5b04455c6cfa7e3f250e5a4fb44308ba2b86baf77f9ad0d782f57071010000006a47304402207f9f7dd91fe537a26d5554105977e3949a5c8c4ef53a6a3bff6da2d36eff928f02202b9427bef487a1825fd0c3c6851d17d5f19e6d73dfee22bf06db591929a2044d012102b4568cc6ee751f6d39f4a908b1fcffdb878f5f784a26a48c0acb0acff9d88e3bfeffffff02809698000000000017a914c82753548fdf4be1c3c7b14872c90b5198e67eaa876e642500000000001976a914e2365ec29471b3e271388b22eadf0e7f54d307a788ac6f771200")
  val scriptHash: BinaryData = Crypto.sha256(referenceTx.txOut(0).publicKeyScript).reverse
  import scala.concurrent.ExecutionContext.Implicits.global

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  test("init an electrumx connection pool") {
    val stream = classOf[ElectrumClientSpec].getResourceAsStream("/electrum/servers_testnet.json")
    val addresses = ElectrumClientPool.readServerAddresses(stream, sslEnabled = false)
    assert(addresses.nonEmpty)
    stream.close()
    pool = system.actorOf(Props(new ElectrumClientPool(addresses)), "electrum-client")
  }

  test("connect to an electrumx testnet server") {
    probe.send(pool, AddStatusListener(probe.ref))
    // make sure our master is stable, if the first master that we select is behind the other servers we will switch
    // during the first few seconds
    awaitCond({
      probe.expectMsgType[ElectrumReady]
      probe.receiveOne(5 seconds) == null
    }, max = 15 seconds, interval = 1000 millis)  }

  test("get transaction") {
    probe.send(pool, GetTransaction("c5efb5cbd35a44ba956b18100be0a91c9c33af4c7f31be20e33741d95f04e202"))
    val GetTransactionResponse(tx) = probe.expectMsgType[GetTransactionResponse]
    assert(tx.txid == BinaryData("c5efb5cbd35a44ba956b18100be0a91c9c33af4c7f31be20e33741d95f04e202"))
  }

  test("get merkle tree") {
    probe.send(pool, GetMerkle("c5efb5cbd35a44ba956b18100be0a91c9c33af4c7f31be20e33741d95f04e202", 1210223))
    val response = probe.expectMsgType[GetMerkleResponse]
    assert(response.txid == BinaryData("c5efb5cbd35a44ba956b18100be0a91c9c33af4c7f31be20e33741d95f04e202"))
    assert(response.block_height == 1210223)
    assert(response.pos == 28)
    assert(response.root == BinaryData("203a52cf3cc071467e5b8780d62d5dfb672bf7bc0841bc823691961ea23402fb"))
  }

  test("header subscription") {
    val probe1 = TestProbe()
    probe1.send(pool, HeaderSubscription(probe1.ref))
    val HeaderSubscriptionResponse(_, header) = probe1.expectMsgType[HeaderSubscriptionResponse]
    logger.info(s"received header for block ${header.blockId}")
  }

  test("scripthash subscription") {
    val probe1 = TestProbe()
    probe1.send(pool, ScriptHashSubscription(scriptHash, probe1.ref))
    val ScriptHashSubscriptionResponse(scriptHash1, status) = probe1.expectMsgType[ScriptHashSubscriptionResponse]
    assert(status != "")
  }

  test("get scripthash history") {
    probe.send(pool, GetScriptHashHistory(scriptHash))
    val GetScriptHashHistoryResponse(scriptHash1, history) = probe.expectMsgType[GetScriptHashHistoryResponse]
    assert(history.contains((TransactionHistoryItem(1210224, "3903726806aa044fe59f40e42eed71bded068b43aaa9e2d716e38b7825412de0"))))
  }

  test("list script unspents") {
    probe.send(pool, ScriptHashListUnspent(scriptHash))
    val ScriptHashListUnspentResponse(scriptHash1, unspents) = probe.expectMsgType[ScriptHashListUnspentResponse]
    assert(unspents.contains(UnspentItem("3903726806aa044fe59f40e42eed71bded068b43aaa9e2d716e38b7825412de0", 0, 10000000L, 1210224L)))
  }
}
