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

package fr.acinq.eclair

import com.ning.http.client.{AsyncCompletionHandler, AsyncHttpClient, AsyncHttpClientConfig, Response}
import grizzled.slf4j.Logging
import org.json4s.DefaultFormats
import org.json4s.JsonAST.{JNothing, JValue}
import org.json4s.jackson.JsonMethods.parse

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

object HttpHelper extends Logging {

  val client = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setAcceptAnyCertificate(true).build())

  implicit val formats = DefaultFormats

  def get(url: String)(implicit ec: ExecutionContext): Future[JValue] = {
    val promise = Promise[JValue]
    client
      .prepareGet(url)
      .execute(new AsyncCompletionHandler[Unit] {
        override def onCompleted(response: Response): Unit = {
          Try(parse(response.getResponseBody)) match {
            case Success(json) => promise.success(json)
            case Failure(t) => promise.success(JNothing)
          }
        }
      })
    val f = promise.future
    f onFailure {
      case t: Throwable => logger.error(s"GET $url failed: ", t)
    }
    f
  }

}
