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

import org.json4s.JsonAST.JValue

import java.io.IOException
import scala.concurrent.{ExecutionContext, Future}

trait BitcoinJsonRPCClient {

  def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue]

}

// @formatter:off
case class JsonRPCRequest(jsonrpc: String = "1.0", id: String = "scala-client", method: String, params: Seq[Any])
case class Error(code: Int, message: String)
case class JsonRPCResponse(result: JValue, error: Option[Error], id: String)
case class JsonRPCError(error: Error) extends IOException(s"${error.message} (code: ${error.code})")
// @formatter:on