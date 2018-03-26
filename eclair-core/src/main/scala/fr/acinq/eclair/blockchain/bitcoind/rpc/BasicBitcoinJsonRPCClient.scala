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

import akka.actor.ActorSystem
import com.ning.http.client._
import org.json4s.DefaultFormats
import org.json4s.JsonAST.{JInt, JNull, JString, JValue}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future, Promise}

class BasicBitcoinJsonRPCClient(config: AsyncHttpClientConfig, host: String, port: Int, ssl: Boolean)(implicit system: ActorSystem) extends BitcoinJsonRPCClient {

  def this(user: String, password: String, host: String = "127.0.0.1", port: Int = 8332, ssl: Boolean = false)(implicit system: ActorSystem) = this(
    new AsyncHttpClientConfig.Builder()
      .setRealm(new Realm.RealmBuilder().setPrincipal(user).setPassword(password).setUsePreemptiveAuth(true).setScheme(Realm.AuthScheme.BASIC).build)
      .build,
    host,
    port,
    ssl
  )

  val client: AsyncHttpClient = new AsyncHttpClient(config)

  implicit val formats = DefaultFormats

  override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] =
    invoke(JsonRPCRequest(method = method, params = params))

  def jsonResponse2Exception(jsonRPCResponse: JsonRPCResponse): JsonRPCResponse = jsonRPCResponse match {
    case JsonRPCResponse(_, Some(error), _) => throw JsonRPCError(error)
    case o => o
  }

  def invoke(request: JsonRPCRequest)(implicit ec: ExecutionContext): Future[JValue] = {
    val promise = Promise[JValue]()
    client
      .preparePost((if (ssl) "https" else "http") + s"://$host:$port/")
      .addHeader("Content-Type", "application/json")
      .setBody(write(request))
      .execute(new AsyncCompletionHandler[Unit] {
        override def onCompleted(response: Response): Unit =
          try {
            val jvalue = parse(response.getResponseBody)
            val jerror = jvalue \ "error"
            val result = jvalue \ "result"
            if (jerror != JNull) {
              for {
                JInt(code) <- jerror \ "code"
                JString(message) <- jerror \ "message"
              } yield promise.failure(new JsonRPCError(Error(code.toInt, message)))
            } else {
              promise.success(result)
            }
          } catch {
            case t: Throwable => promise.failure(t)
          }

        override def onThrowable(t: Throwable): Unit = promise.failure(t)
      })
    promise.future
  }

  def invoke(request: Seq[(String, Seq[Any])])(implicit ec: ExecutionContext): Future[Seq[JValue]] = ???

}