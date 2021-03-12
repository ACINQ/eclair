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

import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.directives.Credentials
import fr.acinq.eclair.api.Service

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

trait AuthDirective {
  this: Service with EclairDirectives =>

  /**
   * A directive0 that passes whenever valid basic credentials are provided. We
   * are not interested in the extracted username.
   *
   * @return
   */
  def authenticated: Directive0 = authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator).tflatMap { _ => pass }

  private def userPassAuthenticator(credentials: Credentials): Future[Option[String]] = credentials match {
    case p@Credentials.Provided(id) if p.verify(password) => Future.successful(Some(id))
    case _ => akka.pattern.after(1 second, using = actorSystem.scheduler)(Future.successful(None))(actorSystem.dispatcher) // force a 1 sec pause to deter brute force
  }

}
