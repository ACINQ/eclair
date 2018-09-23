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

import java.nio.charset.Charset

import gigahorse.support.okhttp.Gigahorse
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JValue
import org.json4s.jackson.{JsonMethods, Serialization}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class BasicBitcoinJsonRPCClient(user: String, password: String, host: String = "127.0.0.1", port: Int = 8332, ssl: Boolean = false) extends BitcoinJsonRPCClient {

  val scheme = if (ssl) "https" else "http"
  implicit val formats = DefaultFormats

  override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] =
    invoke(Seq(JsonRPCRequest(method = method, params = params))).map(l => jsonResponse2Exception(l.head).result)

  def jsonResponse2Exception(jsonRPCResponse: JsonRPCResponse): JsonRPCResponse = jsonRPCResponse match {
    case JsonRPCResponse(_, Some(error), _) => throw JsonRPCError(error)
    case o => o
  }

  def invoke(requests: Seq[JsonRPCRequest])(implicit ec: ExecutionContext): Future[Seq[JsonRPCResponse]] =
    for {
      res <- Gigahorse.withHttp(Gigahorse.config) { http =>
        val json = Serialization.write(requests)
        val r = Gigahorse.url(s"$scheme://$host:$port")
          .post(json, Charset.defaultCharset())
          .withHeaders(("content-type" -> "application/json"))
          .withAuth(user, password)
        http.run(r, Gigahorse.asString)
      }
      jsonRpcRes = JsonMethods.parse(res).extract[Seq[JsonRPCResponse]]
    } yield jsonRpcRes

}