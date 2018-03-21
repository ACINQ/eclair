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

package fr.acinq.eclair.blockchain.bitcoind.rpc

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import org.json4s.JsonAST

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class BatchingBitcoinJsonRPCClient(rpcClient: BasicBitcoinJsonRPCClient)(implicit system: ActorSystem, ec: ExecutionContext) extends BitcoinJsonRPCClient {

  implicit val timeout = Timeout(1 hour)

  val batchingClient = system.actorOf(Props(new BatchingClient(rpcClient)), name = "batching-client")

  override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JsonAST.JValue] =
    (batchingClient ? JsonRPCRequest(method = method, params = params)).mapTo[JsonAST.JValue]
}
