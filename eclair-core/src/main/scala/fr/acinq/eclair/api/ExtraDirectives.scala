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
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directives, Route}

import scala.concurrent.Future
import scala.util.{Failure, Success}

trait ExtraDirectives extends Directives {

  // custom directive to fail with HTTP 404 if the element was not found
  def completeWithFutureOption[T](fut: Future[Option[T]])(implicit marshaller: ToResponseMarshaller[T]): Route = onComplete(fut) {
    case Success(Some(t)) => complete(t)
    case Success(None) => complete(StatusCodes.NotFound)
    case Failure(thr) => reject
  }

}
