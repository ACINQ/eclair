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
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors._

trait OnChain {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val getNewAddress: Route = postRequest("getnewaddress") { implicit t =>
    complete(eclairApi.newAddress())
  }

  val sendOnChain: Route = postRequest("sendonchain") { implicit t =>
    formFields("address".as[String], "amountSatoshis".as[Satoshi], "confirmationTarget".as[Long]) {
      (address, amount, confirmationTarget) =>
        complete(eclairApi.sendOnChain(address, amount, confirmationTarget))
    }
  }

  val onChainBalance: Route = postRequest("onchainbalance") { implicit t =>
    complete(eclairApi.onChainBalance())
  }

  val onChainTransactions: Route = postRequest("onchaintransactions") { implicit t =>
    formFields("count".as[Int].?, "skip".as[Int].?) { (count_opt, skip_opt) =>
      complete(eclairApi.onChainTransactions(count_opt.getOrElse(10), skip_opt.getOrElse(0)))
    }
  }

  val onChainRoutes: Route = getNewAddress ~ sendOnChain ~ onChainBalance ~ onChainTransactions

}
