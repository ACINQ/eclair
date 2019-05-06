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

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, Crypto, Transaction}
import grizzled.slf4j.Logging
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}
import scodec.bits._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


class ElectrumClientSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with Logging with BeforeAndAfterAll {

  import ElectrumClient._

  var client: ActorRef = _
  val probe = TestProbe()
  // this is tx #2690 of block #500000
  val referenceTx = Transaction.read("0200000001983c5b32ced1de5ae97d3ce9b7436f8bb0487d15bf81e5cae97b1e238dc395c6000000006a47304402205957c75766e391350eba2c7b752f0056cb34b353648ecd0992a8a81fc9bcfe980220629c286592842d152cdde71177cd83086619744a533f262473298cacf60193500121021b8b51f74dbf0ac1e766d162c8707b5e8d89fc59da0796f3b4505e7c0fb4cf31feffffff0276bd0101000000001976a914219de672ba773aa0bc2e15cdd9d2e69b734138fa88ac3e692001000000001976a914301706dede031e9fb4b60836e073a4761855f6b188ac09a10700")
  val scriptHash = Crypto.sha256(referenceTx.txOut(0).publicKeyScript).reverse

  override protected def beforeAll(): Unit = {
    client = system.actorOf(Props(new ElectrumClient(new InetSocketAddress("electrum.acinq.co", 50002), SSL.STRICT)), "electrum-client")
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  test("connect to an electrumx mainnet server") {
    probe.send(client, AddStatusListener(probe.ref))
    probe.expectMsgType[ElectrumReady](15 seconds)
  }

  test("get transaction") {
    probe.send(client, GetTransaction(referenceTx.txid))
    val GetTransactionResponse(tx) = probe.expectMsgType[GetTransactionResponse]
    assert(tx == referenceTx)
  }

  test("get header") {
    probe.send(client, GetHeader(100000))
    val GetHeaderResponse(height, header) = probe.expectMsgType[GetHeaderResponse]
    assert(header.blockId == ByteVector32(hex"000000000003ba27aa200b1cecaad478d2b00432346c3f1f3986da1afd33e506"))
  }

  test("get headers") {
    val start = (500000 / 2016) * 2016
    probe.send(client, GetHeaders(start, 2016))
    val GetHeadersResponse(start1, headers, _) = probe.expectMsgType[GetHeadersResponse]
    assert(start1 == start)
    assert(headers.size == 2016)
  }

  test("get merkle tree") {
    probe.send(client, GetMerkle(referenceTx.txid, 500000))
    val response = probe.expectMsgType[GetMerkleResponse]
    assert(response.txid == referenceTx.txid)
    assert(response.block_height == 500000)
    assert(response.pos == 2690)
    assert(response.root == ByteVector32(hex"1f6231ed3de07345b607ec2a39b2d01bec2fe10dfb7f516ba4958a42691c9531"))
  }

  test("header subscription") {
    val probe1 = TestProbe()
    probe1.send(client, HeaderSubscription(probe1.ref))
    val HeaderSubscriptionResponse(_, header) = probe1.expectMsgType[HeaderSubscriptionResponse]
    logger.info(s"received header for block ${header.blockId}")
  }

  test("scripthash subscription") {
    val probe1 = TestProbe()
    probe1.send(client, ScriptHashSubscription(scriptHash, probe1.ref))
    val ScriptHashSubscriptionResponse(scriptHash1, status) = probe1.expectMsgType[ScriptHashSubscriptionResponse]
    assert(status != "")
  }

  test("get scripthash history") {
    probe.send(client, GetScriptHashHistory(scriptHash))
    val GetScriptHashHistoryResponse(scriptHash1, history) = probe.expectMsgType[GetScriptHashHistoryResponse]
    assert(history.contains((TransactionHistoryItem(500000, referenceTx.txid))))
  }

  test("list script unspents") {
    probe.send(client, ScriptHashListUnspent(scriptHash))
    val ScriptHashListUnspentResponse(scriptHash1, unspents) = probe.expectMsgType[ScriptHashListUnspentResponse]
    assert(unspents.isEmpty)
  }
}
