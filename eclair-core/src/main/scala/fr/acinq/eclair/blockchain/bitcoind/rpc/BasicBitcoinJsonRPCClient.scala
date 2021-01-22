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

import com.softwaremill.sttp._
import com.softwaremill.sttp.json4s._
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.KamonExt
import fr.acinq.eclair.blockchain.Monitoring.{Metrics, Tags}
import org.json4s.JsonAST.{JString, JValue}
import org.json4s.jackson.Serialization
import org.json4s.{CustomSerializer, DefaultFormats}

import scala.concurrent.{ExecutionContext, Future}

class BasicBitcoinJsonRPCClient(user: String, password: String, host: String = "127.0.0.1", port: Int = 8332, ssl: Boolean = false, wallet: Option[String] = None)(implicit http: SttpBackend[Future, Nothing]) extends BitcoinJsonRPCClient {

  // necessary to properly serialize ByteVector32 into String readable by bitcoind
  object ByteVector32Serializer extends CustomSerializer[ByteVector32](_ => ( {
    null
  }, {
    case x: ByteVector32 => JString(x.toHex)
  }))

  implicit val formats = DefaultFormats.withBigDecimal + ByteVector32Serializer
  private val scheme = if (ssl) "https" else "http"
  private val serviceUri = wallet match {
    case Some(name) => uri"$scheme://$host:$port/wallet/$name"
    case None => uri"$scheme://$host:$port"
  }
  implicit val serialization = Serialization

  override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] =
    invoke(Seq(JsonRPCRequest(method = method, params = params))).map(l => jsonResponse2Exception(l.head).result)

  def jsonResponse2Exception(jsonRPCResponse: JsonRPCResponse): JsonRPCResponse = jsonRPCResponse match {
    case JsonRPCResponse(_, Some(error), _) => throw JsonRPCError(error)
    case o => o
  }

  def invoke(requests: Seq[JsonRPCRequest])(implicit ec: ExecutionContext): Future[Seq[JsonRPCResponse]] = {
    requests.groupBy(_.method).foreach {
      case (method, calls) => Metrics.RpcBasicInvokeCount.withTag(Tags.Method, method).increment(calls.size)
    }
    KamonExt.timeFuture(Metrics.RpcBasicInvokeDuration.withoutTags()) {
      for {
        res <- sttp
          .post(serviceUri)
          .body(requests)
          .auth.basic(user, password)
          .response(asJson[Seq[JsonRPCResponse]])
          .send()
      } yield res.unsafeBody
    }
  }

}