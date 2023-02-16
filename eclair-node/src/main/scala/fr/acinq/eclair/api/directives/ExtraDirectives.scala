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

package fr.acinq.eclair.api.directives

import akka.http.scaladsl.common.{NameDefaultUnmarshallerReceptacle, NameReceptacle, NameUnmarshallerReceptacle}
import akka.http.scaladsl.marshalling.ToResponseMarshaller
import akka.http.scaladsl.model.StatusCodes.NotFound
import akka.http.scaladsl.model.{ContentTypes, HttpResponse}
import akka.http.scaladsl.server.{Directive1, Directives, MalformedFormFieldRejection, Route}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.ApiTypes.ChannelIdentifier
import fr.acinq.eclair.api.serde.FormParamExtractors._
import fr.acinq.eclair.api.serde.JsonSupport._
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.wire.protocol.OfferTypes.Offer
import fr.acinq.eclair.{MilliSatoshi, Paginated, ShortChannelId, TimestampSecond}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

trait ExtraDirectives extends Directives {

  // named and typed URL parameters used across several routes
  val shortChannelIdFormParam: NameUnmarshallerReceptacle[ShortChannelId] = "shortChannelId".as[ShortChannelId](shortChannelIdUnmarshaller)
  val shortChannelIdsFormParam: NameUnmarshallerReceptacle[List[ShortChannelId]] = "shortChannelIds".as[List[ShortChannelId]](shortChannelIdsUnmarshaller)
  val channelIdFormParam: NameUnmarshallerReceptacle[ByteVector32] = "channelId".as[ByteVector32](bytes32Unmarshaller)
  val channelIdsFormParam: NameUnmarshallerReceptacle[List[ByteVector32]] = "channelIds".as[List[ByteVector32]](bytes32ListUnmarshaller)
  val nodeIdFormParam: NameReceptacle[PublicKey] = "nodeId".as[PublicKey]
  val nodeIdsFormParam: NameUnmarshallerReceptacle[List[PublicKey]] = "nodeIds".as[List[PublicKey]](pubkeyListUnmarshaller)
  val paymentHashFormParam: NameUnmarshallerReceptacle[ByteVector32] = "paymentHash".as[ByteVector32](bytes32Unmarshaller)
  val amountMsatFormParam: NameReceptacle[MilliSatoshi] = "amountMsat".as[MilliSatoshi]
  val invoiceFormParam: NameReceptacle[Bolt11Invoice] = "invoice".as[Bolt11Invoice]
  val routeFormatFormParam: NameUnmarshallerReceptacle[RouteFormat] = "format".as[RouteFormat](routeFormatUnmarshaller)
  val ignoreNodeIdsFormParam: NameUnmarshallerReceptacle[List[PublicKey]] = "ignoreNodeIds".as[List[PublicKey]](pubkeyListUnmarshaller)
  val ignoreShortChannelIdsFormParam: NameUnmarshallerReceptacle[List[ShortChannelId]] = "ignoreShortChannelIds".as[List[ShortChannelId]](shortChannelIdsUnmarshaller)
  val maxFeeMsatFormParam: NameReceptacle[MilliSatoshi] = "maxFeeMsat".as[MilliSatoshi]
  val countFormParam: NameReceptacle[Int] = "count".as[Int]
  val skipFormParam: NameReceptacle[Int] = "skip".as[Int]
  val offerFormParam: NameUnmarshallerReceptacle[Offer] = "offer".as[Offer](offerUnmarshaller)

  // @formatter:off
  // We limit default values to avoid accidentally reading too much data from the DB.
  // We cannot use a val, otherwise the default values would not be recomputed for every request.
  def fromFormParam(): NameDefaultUnmarshallerReceptacle[TimestampSecond] = "from".as[TimestampSecond](timestampSecondUnmarshaller).?(TimestampSecond.now() - 1.day)
  def toFormParam(): NameDefaultUnmarshallerReceptacle[TimestampSecond] = "to".as[TimestampSecond](timestampSecondUnmarshaller).?(TimestampSecond.now())
  // @formatter:on

  // custom directive to fail with HTTP 404 (and JSON response) if the element was not found
  def completeOrNotFound[T](fut: Future[Option[T]])(implicit marshaller: ToResponseMarshaller[T]): Route = onComplete(fut) {
    case Success(Some(t)) => complete(t)
    case Success(None) =>
      complete(HttpResponse(NotFound).withEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse("Not found"))))
    case Failure(_) => reject
  }

  def withPaginated: Directive1[Option[Paginated]] = formFields(countFormParam.?, skipFormParam.?).tflatMap {
    case (Some(count), Some(skip)) => provide(Some(Paginated(count = count, skip = skip)))
    case (Some(count), None) => provide(Some(Paginated(count = count, skip = 0)))
    case (None, Some(_)) => reject(MalformedFormFieldRejection("skip", "Must specify count"))
    case (None, None) => provide(None)
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

  def withNodesIdentifier: Directive1[List[PublicKey]] = formFields(nodeIdFormParam.?, nodeIdsFormParam.?).tflatMap {
    case (Some(nodeId), None) => provide(List(nodeId))
    case (None, Some(nodeIds)) => provide(nodeIds)
    case _ => reject(MalformedFormFieldRejection("nodeId(s)", "Must specify nodeId or nodeIds (but not both)"))
  }

  def withRoute: Directive1[Either[Seq[ShortChannelId], Seq[PublicKey]]] = formFields(shortChannelIdsFormParam.?, nodeIdsFormParam.?).tflatMap {
    case (None, None) => reject(MalformedFormFieldRejection("nodeIds/shortChannelIds", "Must specify either nodeIds or shortChannelIds (but not both)"))
    case (Some(shortChannelIds), _) => provide(Left(shortChannelIds))
    case (_, Some(nodeIds)) => provide(Right(nodeIds))
  }

}
