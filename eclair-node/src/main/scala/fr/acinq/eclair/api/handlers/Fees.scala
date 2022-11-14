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
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors._

trait Fees {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val networkFees: Route = postRequest("networkfees") { implicit t =>
    formFields(fromFormParam(), toFormParam()) { (from, to) =>
      complete(eclairApi.networkFees(from, to))
    }
  }

  val updateRelayFee: Route = postRequest("updaterelayfee") { implicit t =>
    withNodesIdentifier { nodes =>
      formFields("feeBaseMsat".as[MilliSatoshi], "feeProportionalMillionths".as[Long]) { (feeBase, feeProportional) =>
        complete(eclairApi.updateRelayFee(nodes, feeBase, feeProportional))
      }
    }
  }

  val feeRoutes: Route = networkFees ~ updateRelayFee

}
