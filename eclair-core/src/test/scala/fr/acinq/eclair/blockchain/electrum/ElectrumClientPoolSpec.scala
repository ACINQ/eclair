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

package fr.acinq.eclair.blockchain.electrum

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import akka.util.Timeout
import fr.acinq.bitcoin.{ByteVector32, Crypto, Transaction}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient._
import grizzled.slf4j.Logging
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}
import scodec.bits._

import scala.concurrent.duration._
import scala.util.Random


class ElectrumClientPoolSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with Logging with BeforeAndAfterAll {
  var pool: ActorRef = _
  val probe = TestProbe()
  // this is tx #2690 of block #500000
  val referenceTx = Transaction.read("0200000001983c5b32ced1de5ae97d3ce9b7436f8bb0487d15bf81e5cae97b1e238dc395c6000000006a47304402205957c75766e391350eba2c7b752f0056cb34b353648ecd0992a8a81fc9bcfe980220629c286592842d152cdde71177cd83086619744a533f262473298cacf60193500121021b8b51f74dbf0ac1e766d162c8707b5e8d89fc59da0796f3b4505e7c0fb4cf31feffffff0276bd0101000000001976a914219de672ba773aa0bc2e15cdd9d2e69b734138fa88ac3e692001000000001976a914301706dede031e9fb4b60836e073a4761855f6b188ac09a10700")
  val scriptHash = Crypto.sha256(referenceTx.txOut(0).publicKeyScript).reverse
  val serverAddresses = {
    val stream = classOf[ElectrumClientSpec].getResourceAsStream("/electrum/servers_mainnet.json")
    val addresses = ElectrumClientPool.readServerAddresses(stream, sslEnabled = false)
    stream.close()
    addresses
  }

  implicit val timeout = 20 seconds

  import concurrent.ExecutionContext.Implicits.global

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  test("pick a random, unused server address") {
    val usedAddresses = Random.shuffle(serverAddresses.toSeq).take(serverAddresses.size / 2).map(_.adress).toSet
    for(_ <- 1 to 10) {
      val Some(pick) = ElectrumClientPool.pickAddress(serverAddresses, usedAddresses)
      assert(!usedAddresses.contains(pick.adress))
    }
  }

  test("init an electrumx connection pool") {
    val random = new Random()
    val stream = classOf[ElectrumClientSpec].getResourceAsStream("/electrum/servers_mainnet.json")
    val addresses = random.shuffle(serverAddresses.toSeq).take(2).toSet + ElectrumClientPool.ElectrumServerAddress(new InetSocketAddress("electrum.acinq.co", 50002), SSL.STRICT)
    stream.close()
    assert(addresses.nonEmpty)
    pool = system.actorOf(Props(new ElectrumClientPool(addresses)), "electrum-client")
  }

  test("connect to an electrumx mainnet server") {
    probe.send(pool, AddStatusListener(probe.ref))
    // make sure our master is stable, if the first master that we select is behind the other servers we will switch
    // during the first few seconds
    awaitCond({
      probe.expectMsgType[ElectrumReady](30 seconds)
      probe.receiveOne(5 seconds) == null
    }, max = 60 seconds, interval = 1000 millis)  }

  test("get transaction") {
    probe.send(pool, GetTransaction(referenceTx.txid))
    val GetTransactionResponse(tx) = probe.expectMsgType[GetTransactionResponse](timeout)
    assert(tx == referenceTx)
  }

  test("get merkle tree") {
    probe.send(pool, GetMerkle(referenceTx.txid, 500000))
    val response = probe.expectMsgType[GetMerkleResponse](timeout)
    assert(response.txid == referenceTx.txid)
    assert(response.block_height == 500000)
    assert(response.pos == 2690)
    assert(response.root == ByteVector32(hex"1f6231ed3de07345b607ec2a39b2d01bec2fe10dfb7f516ba4958a42691c9531"))
  }

  test("header subscription") {
    val probe1 = TestProbe()
    probe1.send(pool, HeaderSubscription(probe1.ref))
    val HeaderSubscriptionResponse(_, header) = probe1.expectMsgType[HeaderSubscriptionResponse](timeout)
    logger.info(s"received header for block ${header.blockId}")
  }

  test("scripthash subscription") {
    val probe1 = TestProbe()
    probe1.send(pool, ScriptHashSubscription(scriptHash, probe1.ref))
    val ScriptHashSubscriptionResponse(scriptHash1, status) = probe1.expectMsgType[ScriptHashSubscriptionResponse](timeout)
    assert(status != "")
  }

  test("get scripthash history") {
    probe.send(pool, GetScriptHashHistory(scriptHash))
    val GetScriptHashHistoryResponse(scriptHash1, history) = probe.expectMsgType[GetScriptHashHistoryResponse](timeout)
    assert(history.contains((TransactionHistoryItem(500000, referenceTx.txid))))
  }

  test("list script unspents") {
    probe.send(pool, ScriptHashListUnspent(scriptHash))
    val ScriptHashListUnspentResponse(scriptHash1, unspents) = probe.expectMsgType[ScriptHashListUnspentResponse](timeout)
    assert(unspents.isEmpty)
  }
}
