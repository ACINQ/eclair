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
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.{EclairDirectives, RouteFormat}
import fr.acinq.eclair.api.serde.FormParamExtractors._

import scala.concurrent.ExecutionContext

trait PathFinding {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  private implicit def ec: ExecutionContext = actorSystem.dispatcher

  val findRoute: Route = postRequest("findroute") { implicit t =>
    formFields(invoiceFormParam, amountMsatFormParam.?, "pathFindingExperimentName".?, routeFormatFormParam.?, "includeLocalChannelCost".as[Boolean].?, ignoreNodeIdsFormParam.?, ignoreShortChannelIdsFormParam.?, maxFeeMsatFormParam.?) {
      case (invoice, None, pathFindingExperimentName_opt, routeFormat_opt, includeLocalChannelCost_opt, ignoreNodeIds_opt, ignoreChannels_opt, maxFee_opt) if invoice.amount_opt.nonEmpty =>
        complete(eclairApi.findRoute(invoice.nodeId, invoice.amount_opt.get, pathFindingExperimentName_opt, invoice.extraEdges, includeLocalChannelCost_opt.getOrElse(false), ignoreNodeIds = ignoreNodeIds_opt.getOrElse(Nil), ignoreShortChannelIds = ignoreChannels_opt.getOrElse(Nil), maxFee_opt = maxFee_opt).map(r => RouteFormat.format(r, routeFormat_opt)))
      case (invoice, Some(overrideAmount), pathFindingExperimentName_opt, routeFormat_opt, includeLocalChannelCost_opt, ignoreNodeIds_opt, ignoreChannels_opt, maxFee_opt) =>
        complete(eclairApi.findRoute(invoice.nodeId, overrideAmount, pathFindingExperimentName_opt, invoice.extraEdges, includeLocalChannelCost_opt.getOrElse(false), ignoreNodeIds = ignoreNodeIds_opt.getOrElse(Nil), ignoreShortChannelIds = ignoreChannels_opt.getOrElse(Nil), maxFee_opt = maxFee_opt).map(r => RouteFormat.format(r, routeFormat_opt)))
      case _ => reject(MalformedFormFieldRejection(
        "invoice", "The invoice must have an amount or you need to specify one using 'amountMsat'"
      ))
    }
  }

  val findRouteToNode: Route = postRequest("findroutetonode") { implicit t =>
    formFields(nodeIdFormParam, amountMsatFormParam, "pathFindingExperimentName".?, routeFormatFormParam.?, "includeLocalChannelCost".as[Boolean].?, ignoreNodeIdsFormParam.?, ignoreShortChannelIdsFormParam.?, maxFeeMsatFormParam.?) {
      (nodeId, amount, pathFindingExperimentName_opt, routeFormat_opt, includeLocalChannelCost_opt, ignoreNodeIds_opt, ignoreChannels_opt, maxFee_opt) =>
        complete(eclairApi.findRoute(nodeId, amount, pathFindingExperimentName_opt, includeLocalChannelCost = includeLocalChannelCost_opt.getOrElse(false), ignoreNodeIds = ignoreNodeIds_opt.getOrElse(Nil), ignoreShortChannelIds = ignoreChannels_opt.getOrElse(Nil), maxFee_opt = maxFee_opt).map(r => RouteFormat.format(r, routeFormat_opt)))
    }
  }

  val findRouteBetweenNodes: Route = postRequest("findroutebetweennodes") { implicit t =>
    formFields("sourceNodeId".as[PublicKey], "targetNodeId".as[PublicKey], amountMsatFormParam, "pathFindingExperimentName".?, routeFormatFormParam.?, "includeLocalChannelCost".as[Boolean].?, ignoreNodeIdsFormParam.?, ignoreShortChannelIdsFormParam.?, maxFeeMsatFormParam.?) { (sourceNodeId, targetNodeId, amount, pathFindingExperimentName_opt, routeFormat_opt, includeLocalChannelCost_opt, ignoreNodeIds_opt, ignoreChannels_opt, maxFee_opt) =>
      complete(eclairApi.findRouteBetween(sourceNodeId, targetNodeId, amount, pathFindingExperimentName_opt, includeLocalChannelCost = includeLocalChannelCost_opt.getOrElse(false), ignoreNodeIds = ignoreNodeIds_opt.getOrElse(Nil), ignoreShortChannelIds = ignoreChannels_opt.getOrElse(Nil), maxFee_opt = maxFee_opt).map(r => RouteFormat.format(r, routeFormat_opt)))
    }
  }

  val node: Route = postRequest("node") { implicit t =>
    formFields(nodeIdFormParam) { nodeId =>
      completeOrNotFound(eclairApi.node(nodeId))
    }
  }

  val nodes: Route = postRequest("nodes") { implicit t =>
    formFields(nodeIdsFormParam.?) { nodeIds_opt =>
      complete(eclairApi.nodes(nodeIds_opt.map(_.toSet)))
    }
  }

  val pathFindingRoutes: Route = findRoute ~ findRouteToNode ~ findRouteBetweenNodes ~ node ~ nodes

}
