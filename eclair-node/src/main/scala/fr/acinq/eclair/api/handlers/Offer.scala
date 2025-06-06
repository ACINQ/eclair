/*
 * Copyright 2025 ACINQ SAS
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
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors._

trait Offer {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val createOffer: Route = postRequest("createoffer") { implicit t =>
    formFields("description".?, amountMsatFormParam.?, "expireInSeconds".as[Long].?, "issuer".?, "blindedPathsFirstNodeId".as[PublicKey].?) {
      (description_opt, amount_opt, expireInSeconds_opt, issuer_opt, blindedPathsFirstNodeId_opt) => complete(eclairApi.createOffer(description_opt, amount_opt, expireInSeconds_opt, issuer_opt, blindedPathsFirstNodeId_opt))
    }
  }

  val disableOffer: Route = postRequest("disableoffer") { implicit t =>
    formFields(offerFormParam) { offer =>
      complete(eclairApi.disableOffer(offer))
    }
  }

  val listoffers: Route = postRequest("listoffers") { implicit t =>
    formFields("activeOnly".as[Boolean].?) { onlyActive =>
      complete(eclairApi.listOffers(onlyActive.getOrElse(true)))
    }
  }

  val parseOffer: Route = postRequest("parseoffer") { implicit t =>
    formFields(offerFormParam) { offer =>
      complete(offer)
    }
  }

  val offerRoutes: Route = createOffer ~ disableOffer ~ listoffers ~ parseOffer

}
