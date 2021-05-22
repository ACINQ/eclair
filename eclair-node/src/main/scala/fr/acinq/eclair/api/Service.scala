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
import akka.http.scaladsl.server._
import fr.acinq.eclair.{Eclair, RouteProvider}
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.handlers._
import grizzled.slf4j.Logging

trait Service extends EclairDirectives with WebSocket with Node with Channel with Fees with PathFinding with Invoice with Payment with Message with OnChain with Logging {

  /**
   * Allows router access to the API password as configured in eclair.conf
   */
  def password: String

  /**
   * The API of Eclair core.
   */
  val eclairApi: Eclair

  /**
   * ActorSystem on which to run the http service.
   */
  implicit val actorSystem: ActorSystem

  /**
   * Collect routes from all sub-routers here.
   * This is the main entrypoint for the global http request router of the API service.
   * This is where we handle errors to ensure all routes are correctly tried before rejecting.
   */
  def finalRoutes(extraRouteProviders: Seq[RouteProvider] = Nil): Route = securedHandler {
    val baseRoutes = nodeRoutes ~ channelRoutes ~ feeRoutes ~ pathFindingRoutes ~ invoiceRoutes ~ paymentRoutes ~ messageRoutes ~ onChainRoutes ~ webSocket
    extraRouteProviders.map(_.route(this)).foldLeft(baseRoutes)(_ ~ _)
  }
}
