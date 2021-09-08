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

import akka.http.scaladsl.server.{MalformedFormFieldRejection, Route}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.{EclairDirectives, RouteFormat}
import fr.acinq.eclair.api.serde.FormParamExtractors._
import fr.acinq.eclair.payment.PaymentRequest

import scala.concurrent.ExecutionContext

trait PathFinding {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val findRoute: Route = postRequest("findroute") { implicit t =>

    implicit val ec: ExecutionContext = actorSystem.dispatcher

    formFields(invoiceFormParam, amountMsatFormParam.?, routeFormat.?) {
      case (invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _), None, routeFormat) =>
        complete(eclairApi.findRoute(nodeId, amount, invoice.routingInfo).map(r => RouteFormat.format(r, routeFormat)))
      case (invoice, Some(overrideAmount), routeFormat) =>
        complete(eclairApi.findRoute(invoice.nodeId, overrideAmount, invoice.routingInfo).map(r => RouteFormat.format(r, routeFormat)))
      case _ => reject(MalformedFormFieldRejection(
        "invoice", "The invoice must have an amount or you need to specify one using 'amountMsat'"
      ))
    }
  }

  val findRouteToNode: Route = postRequest("findroutetonode") { implicit t =>

    implicit val ec: ExecutionContext = actorSystem.dispatcher

    formFields(nodeIdFormParam, amountMsatFormParam, routeFormat.?) { (nodeId, amount, routeFormat) =>
      complete(eclairApi.findRoute(nodeId, amount).map(r => RouteFormat.format(r, routeFormat)))
    }
  }

  val findRouteBetweenNodes: Route = postRequest("findroutebetweennodes") { implicit t =>

    implicit val ec: ExecutionContext = actorSystem.dispatcher

    formFields("sourceNodeId".as[PublicKey], "targetNodeId".as[PublicKey], amountMsatFormParam, routeFormat.?) { (sourceNodeId, targetNodeId, amount, routeFormat) =>
      complete(eclairApi.findRouteBetween(sourceNodeId, targetNodeId, amount).map(r => RouteFormat.format(r, routeFormat)))
    }
  }

  val networkStats: Route = postRequest("networkstats") { implicit t =>
    complete(eclairApi.networkStats())
  }

  val nodes: Route = postRequest("nodes") { implicit t =>
    formFields(nodeIdsFormParam.?) { nodeIds_opt =>
      complete(eclairApi.nodes(nodeIds_opt.map(_.toSet)))
    }
  }

  val pathFindingRoutes: Route = findRoute ~ findRouteToNode ~ findRouteBetweenNodes ~ networkStats ~ nodes

}
