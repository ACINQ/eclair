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

package fr.acinq.eclair.api

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId}
import fr.acinq.eclair.payment.PaymentRequest
import FormParamExtractors._
import JsonSupport.serialization
import JsonSupport.json4sJacksonFormats
import shapeless.HNil
import spray.http.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import spray.httpx.marshalling.Marshaller
import spray.routing.directives.OnCompleteFutureMagnet
import spray.routing.{Directive1, Directives, MalformedFormFieldRejection}

import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global

trait ExtraDirectives extends Directives {

  // named and typed URL parameters used across several routes
  val shortChannelIdFormParam = "shortChannelId".as[ShortChannelId](shortChannelIdUnmarshaller)
  val channelIdFormParam = "channelId".as[ByteVector32](sha256HashUnmarshaller)
  val nodeIdFormParam = "nodeId".as[PublicKey]
  val paymentHashFormParam = "paymentHash".as[ByteVector32](sha256HashUnmarshaller)
  val fromFormParam = "from".as[Long]
  val toFormParam = "to".as[Long]
  val amountMsatFormParam = "amountMsat".as[MilliSatoshi]
  val invoiceFormParam = "invoice".as[PaymentRequest]

  // custom directive to fail with HTTP 404 (and JSON response) if the element was not found
  def completeOrNotFound[T](fut: Future[Option[T]])(implicit marshaller: Marshaller[T]) = onComplete(OnCompleteFutureMagnet(fut)) {
    case Success(Some(t)) => complete(t)
    case Success(None) =>
      complete(HttpResponse(StatusCodes.NotFound).withEntity(HttpEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse("Not found")))))
    case Failure(_) => reject
  }

  import shapeless.::
  def withChannelIdentifier: Directive1[Either[ByteVector32, ShortChannelId]] = formFields(channelIdFormParam.?, shortChannelIdFormParam.?).hflatMap {
    case None :: None :: HNil => reject(MalformedFormFieldRejection("channelId/shortChannelId", "Must specify either the channelId or shortChannelId"))
    case Some(channelId) :: None :: HNil => provide(Left(channelId))
    case None :: Some(shortChannelId) :: HNil => provide(Right(shortChannelId))
    case _ => reject(MalformedFormFieldRejection("channelId/shortChannelId", "Must specify either the channelId or shortChannelId"))
  }

}
