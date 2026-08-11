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

package fr.acinq.eclair.blockchain.bitcoind.rpc

import fr.acinq.bitcoin.scalacompat.BlockHash
import org.json4s.JsonAST.JValue

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.{ExecutionContext, Future}

trait BitcoinJsonRPCClient {
  // @formatter:off  
  def chainHash: BlockHash
  def wallet: Option[String]
  def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue]
  // @formatter:on
}

case class JsonRPCRequest(jsonrpc: String = "1.0", id: String, method: String, params: Seq[Any])

object JsonRPCRequest {
  private val nextId = new AtomicLong(0)

  /** Generate a unique request ID. */
  def nextRequestId(): String = s"scala-client-${nextId.incrementAndGet()}"
}

// @formatter:off
case class JsonRPCResponse(result: JValue, error: Option[Error], id: String)
case class Error(code: Int, message: String)
case class JsonRPCError(error: Error) extends IOException(s"${error.message} (code: ${error.code})")
// @formatter:on