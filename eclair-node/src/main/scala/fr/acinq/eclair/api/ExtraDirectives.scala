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
import fr.acinq.eclair.ApiTypes.ChannelIdentifier
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
  val shortChannelIdFormParam_opt = "shortChannelId".as[Option[ShortChannelId]](shortChannelIdUnmarshaller)
  val shortChannelIdsFormParam_opt = "shortChannelIds".as[Option[List[ShortChannelId]]](shortChannelIdsUnmarshaller)
  val channelIdFormParam_opt = "channelId".as[Option[ByteVector32]](sha256HashUnmarshaller)
  val channelIdsFormParam_opt = "channelIds".as[Option[List[ByteVector32]]](sha256HashesUnmarshaller)
  val nodeIdFormParam_opt = "nodeId".as[Option[PublicKey]](publicKeyUnmarshaller)
  val paymentHashFormParam_opt = "paymentHash".as[Option[ByteVector32]](sha256HashUnmarshaller)
  val fromFormParam_opt = "from".as[Long]
  val toFormParam_opt = "to".as[Long]
  val amountMsatFormParam_opt = "amountMsat".as[Option[MilliSatoshi]](millisatoshiUnmarshaller)
  val invoiceFormParam_opt = "invoice".as[Option[PaymentRequest]](bolt11Unmarshaller)

  // custom directive to fail with HTTP 404 (and JSON response) if the element was not found
  def completeOrNotFound[T](fut: Future[Option[T]])(implicit marshaller: Marshaller[T]) = onComplete(OnCompleteFutureMagnet(fut)) {
    case Success(Some(t)) => complete(t)
    case Success(None) =>
      complete(HttpResponse(StatusCodes.NotFound).withEntity(HttpEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse("Not found")))))
    case Failure(_) => reject
  }

  import shapeless.::
  def withChannelIdentifier: Directive1[ChannelIdentifier] = formFields(channelIdFormParam_opt, shortChannelIdFormParam_opt).hflatMap {
    case Some(channelId) :: None :: HNil => provide(Left(channelId))
    case None :: Some(shortChannelId) :: HNil => provide(Right(shortChannelId))
    case _ => reject(MalformedFormFieldRejection("channelId/shortChannelId", "Must specify either the channelId or shortChannelId (not both)"))
  }

  def withChannelsIdentifier: Directive1[List[ChannelIdentifier]] = formFields(channelIdFormParam_opt, channelIdsFormParam_opt, shortChannelIdFormParam_opt, shortChannelIdsFormParam_opt).hflatMap {
    case None :: None :: None :: None :: HNil => reject(MalformedFormFieldRejection("channelId(s)/shortChannelId(s)", "Must specify channelId, channelIds, shortChannelId or shortChannelIds"))
    case channelId_opt :: channelIds_opt :: shortChannelId_opt :: shortChannelIds_opt :: HNil =>
      val channelId: List[ChannelIdentifier] = channelId_opt.map(cid => Left(cid)).toList
      val channelIds: List[ChannelIdentifier] = channelIds_opt.map(_.map(cid => Left(cid))).toList.flatten
      val shortChannelId: List[ChannelIdentifier] = shortChannelId_opt.map(scid => Right(scid)).toList
      val shortChannelIds: List[ChannelIdentifier] = shortChannelIds_opt.map(_.map(scid => Right(scid))).toList.flatten
      provide((channelId ++ channelIds ++ shortChannelId ++ shortChannelIds).distinct)
  }

}
