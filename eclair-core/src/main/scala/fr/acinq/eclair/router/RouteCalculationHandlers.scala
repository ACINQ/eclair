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

package fr.acinq.eclair.router

import akka.actor.{ActorContext, ActorRef, Status}
import akka.event.LoggingAdapter
import fr.acinq.eclair.{ShortChannelId, _}
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router._

object RouteCalculationHandlers {

  def finalizeRoute(d: Data, fr: FinalizeRoute)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    import fr.{assistedRoutes, hops => partialHops}

    // NB: using a capacity of 0 msat will impact the path-finding algorithm. However here we don't run any path-finding, so it's ok.
    val assistedChannels: Map[ShortChannelId, AssistedChannel] = assistedRoutes.flatMap(toAssistedChannels(_, partialHops.last, 0 msat)).toMap
    val extraEdges = assistedChannels.values.map(ac => GraphEdge(ChannelDesc(ac.extraHop.shortChannelId, ac.extraHop.nodeId, ac.nextNodeId), toFakeUpdate(ac.extraHop, ac.htlcMaximum))).toSet
    val g = extraEdges.foldLeft(d.graph) { case (g: DirectedGraph, e: GraphEdge) => g.addEdge(e) }
    // split into sublists [(a,b),(b,c), ...] then get the edges between each of those pairs
    partialHops.sliding(2).map { case List(v1, v2) => g.getEdgesBetween(v1, v2) }.toList match {
      case edges if edges.nonEmpty && edges.forall(_.nonEmpty) =>
        val selectedEdges = edges.map(_.maxBy(_.update.htlcMaximumMsat.getOrElse(0 msat))) // select the largest edge
        val hops = selectedEdges.map(d => ChannelHop(d.desc.a, d.desc.b, d.update))
        ctx.sender ! RouteResponse(hops, Set.empty, Set.empty)
      case _ => // some nodes in the supplied route aren't connected in our graph
        ctx.sender ! Status.Failure(new IllegalArgumentException("Not all the nodes in the supplied route are connected with public channels"))
    }
    d
  }

  def handleRouteRequest(d: Data, routerConf: RouterConf, currentBlockHeight: Long, r: RouteRequest)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors
    import r._

    // we convert extra routing info provided in the payment request to fake channel_update
    // it takes precedence over all other channel_updates we know
    val assistedChannels: Map[ShortChannelId, AssistedChannel] = assistedRoutes.flatMap(toAssistedChannels(_, target, amount)).toMap
    val extraEdges = assistedChannels.values.map(ac => GraphEdge(ChannelDesc(ac.extraHop.shortChannelId, ac.extraHop.nodeId, ac.nextNodeId), toFakeUpdate(ac.extraHop, ac.htlcMaximum))).toSet
    val ignoredEdges = ignoreChannels ++ d.excludedChannels
    val defaultRouteParams: RouteParams = getDefaultRouteParams(routerConf)
    val params = routeParams.getOrElse(defaultRouteParams)
    val routesToFind = if (params.randomize) DEFAULT_ROUTES_COUNT else 1

    log.info(s"finding a route $source->$target with assistedChannels={} ignoreNodes={} ignoreChannels={} excludedChannels={}", assistedChannels.keys.mkString(","), ignoreNodes.map(_.value).mkString(","), ignoreChannels.mkString(","), d.excludedChannels.mkString(","))
    log.info(s"finding a route with randomize={} params={}", routesToFind > 1, params)
    findRoute(d.graph, source, target, amount, numRoutes = routesToFind, extraEdges = extraEdges, ignoredEdges = ignoredEdges, ignoredVertices = ignoreNodes, routeParams = params, currentBlockHeight)
      .map(r => ctx.sender ! RouteResponse(r, ignoreNodes, ignoreChannels))
      .recover { case t => ctx.sender ! Status.Failure(t) }
    d
  }

}
