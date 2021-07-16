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

import akka.http.scaladsl.server.{Directive0, Directive1, Directives}
import akka.util.Timeout
import fr.acinq.eclair.api.Service

import scala.concurrent.duration.DurationInt

trait EclairDirectives extends Directives with TimeoutDirective with ErrorDirective with AuthDirective with DefaultHeaders with ExtraDirectives {
  this: Service =>

  /**
   * Prepares inner routes to be exposed as public API with default headers, basic authentication and error handling.
   * Must be applied *after* aggregating all the inner routes.
   */
  def securedHandler: Directive0 = toStrictEntity(5 seconds) & eclairHeaders & handled & authenticated

  /**
   * Provides a Timeout to the inner route either from request param or the default.
   */
  private def standardHandler: Directive1[Timeout] = toStrictEntity(5 seconds) & withTimeout

  /**
   * Handles POST requests with given simple path. The inner route is wrapped in a standard handler and provides a Timeout as parameter.
   */
  def postRequest(p: String): Directive1[Timeout] = standardHandler & post & path(p)

  /**
   * Handles GET requests with given simple path. The inner route is wrapped in a standard handler and provides a Timeout as parameter.
   */
  def getRequest(p: String): Directive1[Timeout] = standardHandler & get & path(p)

}
