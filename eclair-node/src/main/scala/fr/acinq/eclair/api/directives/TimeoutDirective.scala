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

import akka.http.scaladsl.model.{ContentTypes, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directive0, Directive1, Directives}
import akka.util.Timeout
import fr.acinq.eclair.api.serde.FormParamExtractors._
import fr.acinq.eclair.api.serde.JsonSupport
import fr.acinq.eclair.api.serde.JsonSupport._

import scala.concurrent.duration.DurationInt

trait TimeoutDirective extends Directives {

  import JsonSupport.{formats, serialization}

  /**
   * Extracts a given request timeout from an optional form field. Provides either the
   * extracted Timeout or a default Timeout to the inner route.
   */
  def withTimeout: Directive1[Timeout] = extractTimeout.tflatMap { timeout =>
    withTimeoutRequest(timeout._1) & provide(timeout._1)
  }

  private val timeoutResponse: HttpRequest => HttpResponse = { _ =>
    HttpResponse(StatusCodes.RequestTimeout).withEntity(
      ContentTypes.`application/json`, serialization.writePretty(ErrorResponse("request timed out"))
    )
  }

  private def withTimeoutRequest(t: Timeout): Directive0 = withRequestTimeout(t.duration + 2.seconds) &
    withRequestTimeoutResponse(timeoutResponse)

  private def extractTimeout: Directive1[Timeout] = formField("timeoutSeconds".as[Timeout].?).tflatMap { opt =>
    provide(opt._1.getOrElse(Timeout(30 seconds)))
  }

}
