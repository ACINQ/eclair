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

import fr.acinq.eclair.KamonExt
import fr.acinq.eclair.blockchain.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.json.{ByteVector32KmpSerializer, ByteVector32Serializer}
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JValue
import org.json4s.jackson.Serialization
import sttp.client3._
import sttp.client3.json4s._
import sttp.model.StatusCode

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class BasicBitcoinJsonRPCClient(rpcAuthMethod: BitcoinJsonRPCAuthMethod, host: String = "127.0.0.1", port: Int = 8332, ssl: Boolean = false, wallet: Option[String] = None)(implicit sb: SttpBackend[Future, _]) extends BitcoinJsonRPCClient {

  implicit val formats = DefaultFormats.withBigDecimal + ByteVector32Serializer + ByteVector32KmpSerializer

  private val scheme = if (ssl) "https" else "http"
  private val serviceUri = wallet match {
    case Some(name) => uri"$scheme://$host:$port/wallet/$name"
    case None => uri"$scheme://$host:$port"
  }
  private val credentials = new AtomicReference[BitcoinJsonRPCCredentials](rpcAuthMethod.credentials)
  implicit val serialization = Serialization

  override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] =
    invoke(Seq(JsonRPCRequest(method = method, params = params))).map(l => jsonResponse2Exception(l.head).result)

  def jsonResponse2Exception(jsonRPCResponse: JsonRPCResponse): JsonRPCResponse = jsonRPCResponse match {
    case JsonRPCResponse(_, Some(error), _) => throw JsonRPCError(error)
    case o => o
  }

  private def send(requests: Seq[JsonRPCRequest], user: String, password: String)(implicit ec: ExecutionContext): Future[Response[Either[ResponseException[String, Exception], Seq[JsonRPCResponse]]]] = {
    requests.groupBy(_.method).foreach {
      case (method, calls) => Metrics.RpcBasicInvokeCount.withTag(Tags.Method, method).increment(calls.size)
    }
    // for the duration metric, we use a "mixed" method for batched requests
    KamonExt.timeFuture(Metrics.RpcBasicInvokeDuration.withTag(Tags.Method, if (requests.size == 1) requests.head.method else "mixed")) {
      for {
        response <- basicRequest
          .post(serviceUri)
          .body(requests)
          .auth.basic(user, password)
          .response(asJson[Seq[JsonRPCResponse]])
          .send(sb)
      } yield response
    }
  }

  def invoke(requests: Seq[JsonRPCRequest])(implicit ec: ExecutionContext): Future[Seq[JsonRPCResponse]] = {
    val BitcoinJsonRPCCredentials(user, password) = credentials.get()
    send(requests, user, password).flatMap {
      response =>
        response.code match {
          case StatusCode.Unauthorized => rpcAuthMethod match {
            case _: BitcoinJsonRPCAuthMethod.UserPassword => Future.failed(new IllegalArgumentException("could not authenticate to bitcoind RPC server: check your configured user/password"))
            case BitcoinJsonRPCAuthMethod.SafeCookie(path, _) =>
              // bitcoind may have restarted and generated a new cookie file, let's read it again and retry
              BitcoinJsonRPCAuthMethod.readCookie(path) match {
                case Success(cookie) =>
                  credentials.set(cookie.credentials)
                  send(requests, cookie.credentials.user, cookie.credentials.password).map(_.body.fold(exc => throw exc, jvalue => jvalue))
                case Failure(e) => Future.failed(e)
              }
          }
          case _ => Future.successful(response.body.fold(exc => throw exc, jvalue => jvalue))
        }
    }
  }
}

case class BitcoinJsonRPCCredentials(user: String, password: String)

// @formatter:off
sealed abstract class BitcoinJsonRPCAuthMethod {
  def credentials: BitcoinJsonRPCCredentials
}
object BitcoinJsonRPCAuthMethod {
  case class UserPassword(user: String, password: String) extends BitcoinJsonRPCAuthMethod { override val credentials = BitcoinJsonRPCCredentials(user, password) }
  case class SafeCookie(path: String, credentials: BitcoinJsonRPCCredentials = BitcoinJsonRPCCredentials("","")) extends BitcoinJsonRPCAuthMethod

  def readCookie(path: String): Try[SafeCookie] = Try {
    val cookieContents = Files.readString(Path.of(path), StandardCharsets.UTF_8).split(':')
    SafeCookie(path, BitcoinJsonRPCCredentials(cookieContents(0), cookieContents(1)))
  }
}
// @formatter:on