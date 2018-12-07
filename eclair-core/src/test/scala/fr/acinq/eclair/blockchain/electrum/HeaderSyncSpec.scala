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
import fr.acinq.bitcoin._
import grizzled.slf4j.Logging
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class HeaderSyncSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with Logging with BeforeAndAfterAll {

  import ElectrumClient._

  var client: ActorRef = _
  val probe = TestProbe()

  override protected def beforeAll(): Unit = {
    client = system.actorOf(Props(new ElectrumClient(new InetSocketAddress("qtornado.com", 50001))), "electrum-client")
    probe.send(client, AddStatusListener(probe.ref))
    probe.expectMsgType[ElectrumReady](15 seconds)
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  test("build from checkpoints") {
    val checkpoints = CheckPoint.load(Block.LivenetGenesisBlock.hash)
    val checkpointHeight = checkpoints.size * 2016 - 1

    // get the first header after the last checkpoint
    probe.send(client, GetHeader(checkpointHeight + 1))
    val GetHeaderResponse(checkPointHeader) = probe.expectMsgType[GetHeaderResponse]
    val blockchain = Blockchain.fromCheckpoints(Block.LivenetGenesisBlock.hash, checkpoints, checkPointHeader.blockHeader)

    // get the next chunks of headers
    probe.send(client, GetHeaders(blockchain.tip.height + 1, 2016))
    val GetHeadersResponse(start1, headers1, _) = probe.expectMsgType[GetHeadersResponse]
    probe.send(client, GetHeaders(start1 + headers1.length, 2016))
    val GetHeadersResponse(start2, headers2, _) = probe.expectMsgType[GetHeadersResponse]

    // check that we can add our headers
    val blockchain1 = Blockchain.addHeaders(blockchain, headers1)
    val blockchain2 = Blockchain.addHeaders(blockchain1, headers2)
    assert(blockchain2.bestChain.length == 1 + 2016 + 2016)

    // check that we handle orphan blocks properly
    val blockchain3 = Blockchain.addHeaders(blockchain, headers1.drop(100))
    assert(blockchain3.orphans.size == headers1.size - 100)
    val blockchain4 = Blockchain.addHeaders(blockchain3, headers1.take(100))
    assert(blockchain4.bestChain.length == 1 + headers1.size)
  }

  test("initial header download") {
    val checkpoints = CheckPoint.load(Block.LivenetGenesisBlock.hash)
    val checkpointHeight = checkpoints.size * 2016 - 1

    // get the first header after the last checkpoint
    probe.send(client, GetHeader(checkpointHeight + 1))
    val GetHeaderResponse(checkPointHeader) = probe.expectMsgType[GetHeaderResponse]
    var blockchain = Blockchain.fromCheckpoints(Block.LivenetGenesisBlock.hash, checkpoints, checkPointHeader.blockHeader)

    // get the remote server tip
    val dummy = TestProbe()
    probe.send(client, HeaderSubscription(dummy.ref))
    val HeaderSubscriptionResponse(tip) = dummy.expectMsgType[HeaderSubscriptionResponse]

    // download headers
    while (blockchain.tip.height < tip.block_height) {
      probe.send(client, GetHeaders(blockchain.tip.height + 1, 2016))
      val GetHeadersResponse(start_height, headers, _) = probe.expectMsgType[GetHeadersResponse]
      blockchain = Blockchain.addHeaders(blockchain, headers)
    }

    println(blockchain.tip)
  }
}
