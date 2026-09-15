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
import fr.acinq.eclair.api.Service

trait OriginDirective {
  this: Service with EclairDirectives =>

  /**
   * A directive0 that rejects requests made by a web browser.
   *
   * Our API relies on HTTP basic authentication: once a browser has cached those credentials, it will attach them to
   * cross-site requests as well, which lets any web page the node operator visits forge authenticated API calls (see
   * https://owasp.org/www-community/attacks/csrf). Since our endpoints take form-encoded parameters, such a request
   * can be made with a plain HTML form and thus doesn't require CORS approval: the attacker cannot read the response,
   * but the side effects (sending funds on-chain, closing channels) have already happened.
   *
   * Browsers set the `Origin` header on every cross-site request and on every same-site POST, while the clients this
   * API is meant for (curl, eclair-cli, other back-ends) never set it: rejecting requests that carry an origin is thus
   * enough to close that attack vector.
   */
  def originChecked: Directive0 = optionalHeaderValueByName("Origin").tflatMap {
    case Tuple1(None) => pass // not a browser request
    case Tuple1(Some(origin)) =>
      logger.warn(s"rejecting API request from origin=$origin: this API cannot be used from a web browser")
      authorize(false)
  }

}
