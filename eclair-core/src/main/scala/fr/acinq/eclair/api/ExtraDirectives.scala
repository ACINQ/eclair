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
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.api.FormParamExtractors.{sha256HashUnmarshaller, shortChannelIdUnmarshaller}
import fr.acinq.eclair.api.JsonSupport._
import fr.acinq.eclair.payment.PaymentRequest
import scala.concurrent.Future
import scala.util.{Failure, Success}

trait ExtraDirectives extends Directives {

  // named and typed URL parameters used across several routes
  val shortChannelIdFormParam = "shortChannelId".as[ShortChannelId](shortChannelIdUnmarshaller)
  val channelIdFormParam = "channelId".as[ByteVector32](sha256HashUnmarshaller)
  val nodeIdFormParam = "nodeId".as[PublicKey]
  val paymentHashFormParam = "paymentHash".as[ByteVector32](sha256HashUnmarshaller)
  val fromFormParam = "from".as[Long]
  val toFormParam = "to".as[Long]
  val amountMsatFormParam = "amountMsat".as[Long]
  val invoiceFormParam = "invoice".as[PaymentRequest]

  // custom directive to fail with HTTP 404 (and JSON response) if the element was not found
  def completeOrNotFound[T](fut: Future[Option[T]])(implicit marshaller: ToResponseMarshaller[T]): Route = onComplete(fut) {
    case Success(Some(t)) => complete(t)
    case Success(None) =>
      complete(HttpResponse(NotFound).withEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse("Not found"))))
    case Failure(_) => reject
  }

  def withChannelIdentifier: Directive1[Either[ByteVector32, ShortChannelId]] = formFields(channelIdFormParam.?, shortChannelIdFormParam.?).tflatMap {
    case (None, None) => reject(MalformedFormFieldRejection("channelId/shortChannelId", "Must specify either the channelId or shortChannelId"))
    case (Some(channelId), None) => provide(Left(channelId))
    case (None, Some(shortChannelId)) => provide(Right(shortChannelId))
    case _ => reject(MalformedFormFieldRejection("channelId/shortChannelId", "Must specify either the channelId or shortChannelId"))
  }

}
