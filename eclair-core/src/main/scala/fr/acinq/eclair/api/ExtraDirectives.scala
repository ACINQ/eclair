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

package fr.acinq.eclair.api

import akka.http.scaladsl.marshalling.ToResponseMarshaller
import akka.http.scaladsl.model.{ContentTypes, HttpResponse}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.{Directive1, Directives, MalformedFormFieldRejection, Route}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.api.FormParamExtractors.{sha256HashUnmarshaller, shortChannelIdUnmarshaller}
import fr.acinq.eclair.api.JsonSupport._
import scala.concurrent.Future
import scala.util.{Failure, Success}

trait ExtraDirectives extends Directives {

  val shortChannelId = "shortChannelId".as[ShortChannelId](shortChannelIdUnmarshaller)
  val channelId = "channelId".as[ByteVector32](sha256HashUnmarshaller)

  // custom directive to fail with HTTP 404 (and JSON response) if the element was not found
  def completeOrNotFound[T](fut: Future[Option[T]])(implicit marshaller: ToResponseMarshaller[T]): Route = onComplete(fut) {
    case Success(Some(t)) => complete(t)
    case Success(None) =>
      complete(HttpResponse(NotFound).withEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse("Not found"))))
    case Failure(_) => reject
  }

  def channelOrShortChannelId: Directive1[Either[ByteVector32, ShortChannelId]] = formFields(channelId.?, shortChannelId.?).tflatMap {
    case (None, None) => reject(MalformedFormFieldRejection("channelId/shortChannelId", "Must specify either the channelId or shortChannelId"))
    case (Some(channelId), None) => provide(Left(channelId))
    case (None, Some(shortChannelId)) => provide(Right(shortChannelId))
    case _ => reject
  }

}
