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

package fr.acinq.eclair.api.handlers

import akka.http.scaladsl.server.Route
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.EclairDirectives

trait Balance {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val usableBalances: Route = postRequest("usablebalances") { implicit t =>
    complete(eclairApi.usableBalances())
  }

  val onChainBalance: Route = postRequest("onchainbalance") { implicit t =>
    complete(eclairApi.onChainBalance())
  }

  val balanceRoutes: Route = usableBalances ~ onChainBalance
}
