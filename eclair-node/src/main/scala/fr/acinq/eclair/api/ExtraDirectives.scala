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

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.ToResponseMarshaller
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `no-store`, public}
import akka.http.scaladsl.model.headers.{`Access-Control-Allow-Headers`, `Access-Control-Allow-Methods`, `Cache-Control`}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.{Directive, Directive0, Directive1, Directives, ExceptionHandler, MalformedFormFieldRejection, PathMatcher, RejectionHandler, Route}
import akka.util.Timeout
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ApiTypes.ChannelIdentifier
import fr.acinq.eclair.api.FormParamExtractors._
import fr.acinq.eclair.api.JsonSupport._
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId}
import grizzled.slf4j.Logging

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

trait ExtraDirectives extends Directives {
  this: Logging =>

  implicit val actorSystem: ActorSystem

  // timeout for reading request parameters from the underlying stream
  val paramParsingTimeout = 5 seconds

  val apiExceptionHandler = ExceptionHandler {
    case t: IllegalArgumentException =>
      logger.error(s"API call failed with cause=${t.getMessage}", t)
      complete(StatusCodes.BadRequest, ErrorResponse(t.getMessage))
    case t: Throwable =>
      logger.error(s"API call failed with cause=${t.getMessage}", t)
      complete(StatusCodes.InternalServerError, ErrorResponse(t.getMessage))
  }

  // map all the rejections to a JSON error object ErrorResponse
  val apiRejectionHandler = RejectionHandler.default.mapRejectionResponse {
    case res@HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
      res.withEntity(HttpEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse(ent.data.utf8String))))
  }

  val customHeaders = `Access-Control-Allow-Headers`("Content-Type, Authorization") ::
    `Access-Control-Allow-Methods`(POST) ::
    `Cache-Control`(public, `no-store`, `max-age`(0)) :: Nil

  val timeoutResponse: HttpRequest => HttpResponse = { _ =>
    HttpResponse(StatusCodes.RequestTimeout).withEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse("request timed out")))
  }

  def password: String

  def userPassAuthenticator(credentials: Credentials): Future[Option[String]] = credentials match {
    case p@Credentials.Provided(id) if p.verify(password) => Future.successful(Some(id))
    case _ => akka.pattern.after(1 second, using = actorSystem.scheduler)(Future.successful(None))(actorSystem.dispatcher) // force a 1 sec pause to deter brute force
  }


  // named and typed URL parameters used across several routes
  val shortChannelIdFormParam = "shortChannelId".as[ShortChannelId](shortChannelIdUnmarshaller)
  val shortChannelIdsFormParam = "shortChannelIds".as[List[ShortChannelId]](shortChannelIdsUnmarshaller)
  val channelIdFormParam = "channelId".as[ByteVector32](sha256HashUnmarshaller)
  val channelIdsFormParam = "channelIds".as[List[ByteVector32]](sha256HashesUnmarshaller)
  val nodeIdFormParam = "nodeId".as[PublicKey]
  val nodeIdsFormParam = "nodeIds".as[List[PublicKey]](pubkeyListUnmarshaller)
  val paymentHashFormParam = "paymentHash".as[ByteVector32](sha256HashUnmarshaller)
  val fromFormParam = "from".as[Long]
  val toFormParam = "to".as[Long]
  val amountMsatFormParam = "amountMsat".as[MilliSatoshi]
  val invoiceFormParam = "invoice".as[PaymentRequest]

  // custom directive to fail with HTTP 404 (and JSON response) if the element was not found
  def completeOrNotFound[T](fut: Future[Option[T]])(implicit marshaller: ToResponseMarshaller[T]): Route = onComplete(fut) {
    case Success(Some(t)) => complete(t)
    case Success(None) =>
      complete(HttpResponse(NotFound).withEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse("Not found"))))
    case Failure(_) => reject
  }

  def withChannelIdentifier: Directive1[ChannelIdentifier] = formFields(channelIdFormParam.?, shortChannelIdFormParam.?).tflatMap {
    case (Some(channelId), None) => provide(Left(channelId))
    case (None, Some(shortChannelId)) => provide(Right(shortChannelId))
    case _ => reject(MalformedFormFieldRejection("channelId/shortChannelId", "Must specify either the channelId or shortChannelId (not both)"))
  }

  def withChannelsIdentifier: Directive1[List[ChannelIdentifier]] = formFields(channelIdFormParam.?, channelIdsFormParam.?, shortChannelIdFormParam.?, shortChannelIdsFormParam.?).tflatMap {
    case (None, None, None, None) => reject(MalformedFormFieldRejection("channelId(s)/shortChannelId(s)", "Must specify channelId, channelIds, shortChannelId or shortChannelIds"))
    case (channelId_opt, channelIds_opt, shortChannelId_opt, shortChannelIds_opt) =>
      val channelId: List[ChannelIdentifier] = channelId_opt.map(cid => Left(cid)).toList
      val channelIds: List[ChannelIdentifier] = channelIds_opt.map(_.map(cid => Left(cid))).toList.flatten
      val shortChannelId: List[ChannelIdentifier] = shortChannelId_opt.map(scid => Right(scid)).toList
      val shortChannelIds: List[ChannelIdentifier] = shortChannelIds_opt.map(_.map(scid => Right(scid))).toList.flatten
      provide((channelId ++ channelIds ++ shortChannelId ++ shortChannelIds).distinct)
  }

  def withRoute: Directive1[Either[Seq[ShortChannelId], Seq[PublicKey]]] = formFields(shortChannelIdsFormParam.?, nodeIdsFormParam.?).tflatMap {
    case (None, None) => reject(MalformedFormFieldRejection("nodeIds/shortChannelIds", "Must specify either nodeIds or shortChannelIds (but not both)"))
    case (Some(shortChannelIds), _) => provide(Left(shortChannelIds))
    case (_, Some(nodeIds)) => provide(Right(nodeIds))
  }

  def handled: Directive0 = handleExceptions(apiExceptionHandler) &
   handleExceptions(apiExceptionHandler) &
   handleRejections(apiRejectionHandler)

  def authenticated: Directive0 = authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator).tflatMap { _ =>
    pass
  }

  def withTimeout:Directive[Tuple1[Timeout]] = formFields("timeoutSeconds".as[Timeout].?).tflatMap { tm_opt =>
     withTimeoutRequest(tm_opt._1.getOrElse(Timeout(30 seconds)))
     val timeout: Timeout = tm_opt._1.getOrElse(Timeout(30 seconds))
     withTimeoutRequest(timeout) & provide(timeout)
   }

  def withTimeoutRequest(t:Timeout): Directive0 = withRequestTimeout(t.duration + 2.seconds) &
    withRequestTimeoutResponse(timeoutResponse)

  def eclairRoute: Directive[Tuple1[Timeout]] = respondWithDefaultHeaders(customHeaders) &
    handled & toStrictEntity(paramParsingTimeout) & authenticated & withTimeout


  def postRequest(p:String):Directive[Tuple1[Timeout]] = post & path(p) & eclairRoute

  def getRequest(p:String):Directive[Tuple1[Timeout]] = get & path(p) & eclairRoute


}
