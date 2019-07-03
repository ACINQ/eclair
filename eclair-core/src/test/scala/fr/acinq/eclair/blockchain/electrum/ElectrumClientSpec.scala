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
  val height = 500000
  val position = 2690
  val merkleProof = List(
    hex"b500cd85cd6c7e0e570b82728dd516646536a477b61cc82056505d84a5820dc3",
    hex"c98798c2e576566a92b23d2405f59d95c506966a6e26fecfb356d6447a199546",
    hex"930d95c428546812fd11f8242904a9a1ba05d2140cd3a83be0e2ed794821c9ec",
    hex"90c97965b12f4262fe9bf95bc37ff7d6362902745eaa822ecf0cf85801fa8b48",
    hex"23792d51fddd6e439ed4c92ad9f19a9b73fc9d5c52bdd69039be70ad6619a1aa",
    hex"4b73075f29a0abdcec2c83c2cfafc5f304d2c19dcacb50a88a023df725468760",
    hex"f80225a32a5ce4ef0703822c6aa29692431a816dec77d9b1baa5b09c3ba29bfb",
    hex"4858ac33f2022383d3b4dd674666a0880557d02a155073be93231a02ecbb81f4",
    hex"eb5b142030ed4e0b55a8ba5a7b5b783a0a24e0c2fd67c1cfa2f7b308db00c38a",
    hex"86858812c3837d209110f7ea79de485abdfd22039467a8aa15a8d85856ee7d30",
    hex"de20eb85f2e9ad525a6fb5c618682b6bdce2fa83df836a698f31575c4e5b3d38",
    hex"98bd1048e04ff1b0af5856d9890cd708d8d67ad6f3a01f777130fbc16810eeb3")
    .map(ByteVector32(_))

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

  test("get transaction id from position") {
    probe.send(client, GetTransactionIdFromPosition(height, position))
    probe.expectMsg(GetTransactionIdFromPositionResponse(referenceTx.txid, height, position, Nil))
  }

  test("get transaction id from position with merkle proof") {
    probe.send(client, GetTransactionIdFromPosition(height, position, merkle = true))
    probe.expectMsg(GetTransactionIdFromPositionResponse(referenceTx.txid, height, position, merkleProof))
  }

  test("get transaction") {
    probe.send(client, GetTransaction(referenceTx.txid))
    val GetTransactionResponse(tx, _) = probe.expectMsgType[GetTransactionResponse]
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
    assert(history.contains(TransactionHistoryItem(500000, referenceTx.txid)))
  }

  test("list script unspents") {
    probe.send(client, ScriptHashListUnspent(scriptHash))
    val ScriptHashListUnspentResponse(scriptHash1, unspents) = probe.expectMsgType[ScriptHashListUnspentResponse]
    assert(unspents.isEmpty)
  }

}
