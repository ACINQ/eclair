/*
 * Copyright 2021 ACINQ SAS
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

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{ContentTypes, HttpResponse}
import fr.acinq.eclair.api.serde.JsonSupport._
import fr.acinq.eclair.router.Router.RouteResponse

// @formatter:off
sealed trait RouteFormat
case object NodeIdRouteFormat extends RouteFormat
case object ShortChannelIdRouteFormat extends RouteFormat
case object FullRouteFormat extends RouteFormat
// @formatter:on

object RouteFormat {

  val NODE_ID = "nodeId"
  val SHORT_CHANNEL_ID = "shortChannelId"
  val FULL = "full"

  def fromString(s: String): RouteFormat = s match {
    case NODE_ID => NodeIdRouteFormat
    case SHORT_CHANNEL_ID => ShortChannelIdRouteFormat
    case FULL => FullRouteFormat
    case _ => throw new IllegalArgumentException(s"invalid route format, possible values are ($NODE_ID, $SHORT_CHANNEL_ID, $FULL)")
  }

  def format(route: RouteResponse, format_opt: Option[RouteFormat]): HttpResponse = format(route, format_opt.getOrElse(NodeIdRouteFormat))

  def format(route: RouteResponse, format: RouteFormat): HttpResponse =
    HttpResponse(OK).withEntity(ContentTypes.`application/json`,
      format match {
        case NodeIdRouteFormat =>
          val nodeIds = route.routes.head.hops match {
            case rest :+ last => rest.map(_.nodeId) :+ last.nodeId :+ last.nextNodeId
            case Nil => Nil
          }
          serialization.write(nodeIds.toList.map(_.toString))
        case ShortChannelIdRouteFormat =>
          val shortChannelIds = route.routes.head.hops.map(_.lastUpdate.shortChannelId)
          serialization.write(shortChannelIds.toList.map(_.toString))
        case FullRouteFormat =>
          serialization.writePretty(route.routes)
      })
}

