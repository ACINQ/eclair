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

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directive0, ExceptionHandler, RejectionHandler}
import fr.acinq.eclair.api.Service

trait ErrorDirective {
  this: Service with EclairDirectives =>

  /**
   * Handles API exceptions and rejections. Produces json formatted error responses.
   */
  def handled: Directive0 = handleExceptions(apiExceptionHandler) & handleRejections(apiRejectionHandler)

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  private val apiExceptionHandler = ExceptionHandler {
    case t: IllegalArgumentException =>
      logger.error(s"API call failed with cause=${t.getMessage}")
      complete(StatusCodes.BadRequest, ErrorResponse(t.getMessage))
    case t: Throwable =>
      logger.error(s"API call failed with cause=${t.getMessage}")
      complete(StatusCodes.InternalServerError, ErrorResponse(t.getMessage))
  }

  // map all the rejections to a JSON error object ErrorResponse
  private val apiRejectionHandler = RejectionHandler.default.mapRejectionResponse {
    case res@HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
      res.withEntity(HttpEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse(ent.data.utf8String))))
  }

}
