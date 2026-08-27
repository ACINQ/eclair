/*
 * Copyright 2026 ACINQ SAS
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

import akka.actor.Props
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.Block
import fr.acinq.eclair.TestKitBaseClass
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCAuthMethod.UserPassword
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BatchingClient, JsonRPCRequest, JsonRPCResponse}
import org.json4s.JsonAST.JString
import org.scalatest.funsuite.AnyFunSuiteLike

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.{ExecutionContext, Future, Promise}

class BatchingClientSpec extends TestKitBaseClass with AnyFunSuiteLike {

  test("match reordered batch responses by request id") {
    val invocations = new LinkedBlockingQueue[Seq[JsonRPCRequest]]()
    val completions = new LinkedBlockingQueue[Promise[Seq[JsonRPCResponse]]]()
    val rpcClient = new BasicBitcoinJsonRPCClient(
      Block.RegtestGenesisBlock.hash,
      rpcAuthMethod = UserPassword("", ""),
      host = "localhost",
      port = 0)(sb = null) {
      override def invoke(requests: Seq[JsonRPCRequest])(implicit ec: ExecutionContext): Future[Seq[JsonRPCResponse]] = {
        invocations.add(requests)
        val completion = Promise[Seq[JsonRPCResponse]]()
        completions.add(completion)
        completion.future
      }
    }
    val client = system.actorOf(Props(new BatchingClient(rpcClient)))
    val blocker = TestProbe()
    val requestorA = TestProbe()
    val requestorB = TestProbe()

    blocker.send(client, JsonRPCRequest(id = "blocker", method = "getblockcount", params = Nil))
    assert(invocations.poll(3, TimeUnit.SECONDS).map(_.id) == Seq("blocker"))

    requestorA.send(client, JsonRPCRequest(id = "request-a", method = "getrawtransaction", params = Seq("tx-a")))
    requestorB.send(client, JsonRPCRequest(id = "request-b", method = "getrawtransaction", params = Seq("tx-b")))
    Thread.sleep(200)
    completions.poll(3, TimeUnit.SECONDS).success(Seq(JsonRPCResponse(JString("blocker-result"), None, "blocker")))
    blocker.expectMsg(JString("blocker-result"))

    val batchedRequests = invocations.poll(3, TimeUnit.SECONDS)
    assert(batchedRequests.map(_.id) == Seq("request-a", "request-b"))
    completions.poll(3, TimeUnit.SECONDS).success(Seq(
      JsonRPCResponse(JString("result-b"), None, "request-b"),
      JsonRPCResponse(JString("result-a"), None, "request-a")))

    requestorA.expectMsg(JString("result-a"))
    requestorB.expectMsg(JString("result-b"))
  }

}
