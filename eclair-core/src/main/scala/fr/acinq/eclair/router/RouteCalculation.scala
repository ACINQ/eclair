/*
 * Copyright 2020 ACINQ SAS
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
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Satoshi}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{RichWeight, RoutingHeuristics, WeightRatios}
import fr.acinq.eclair.router.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.eclair.{ShortChannelId, _}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.util.{Random, Try}

object RouteCalculation {

  def finalizeRoute(d: Data, fr: FinalizeRoute)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors

    val assistedChannels: Map[ShortChannelId, AssistedChannel] = fr.assistedRoutes.flatMap(toAssistedChannels(_, fr.hops.last, fr.amount)).toMap
    val extraEdges = assistedChannels.values.map(ac =>
      GraphEdge(ChannelDesc(ac.extraHop.shortChannelId, ac.extraHop.nodeId, ac.nextNodeId), toFakeUpdate(ac.extraHop, ac.htlcMaximum), htlcMaxToCapacity(ac.htlcMaximum), Some(ac.htlcMaximum))
    ).toSet
    val g = extraEdges.foldLeft(d.graph) { case (g: DirectedGraph, e: GraphEdge) => g.addEdge(e) }
    // split into sublists [(a,b),(b,c), ...] then get the edges between each of those pairs
    fr.hops.sliding(2).map { case List(v1, v2) => g.getEdgesBetween(v1, v2) }.toList match {
      case edges if edges.nonEmpty && edges.forall(_.nonEmpty) =>
        // select the largest edge (using balance when available, otherwise capacity).
        val selectedEdges = edges.map(es => es.maxBy(e => e.balance_opt.getOrElse(e.capacity.toMilliSatoshi)))
        val hops = selectedEdges.map(d => ChannelHop(d.desc.a, d.desc.b, d.update))
        ctx.sender ! RouteResponse(hops, Set.empty, Set.empty)
      case _ => // some nodes in the supplied route aren't connected in our graph
        ctx.sender ! Status.Failure(new IllegalArgumentException("Not all the nodes in the supplied route are connected with public channels"))
    }
    d
  }

  def handleRouteRequest(d: Data, routerConf: RouterConf, currentBlockHeight: Long, r: RouteRequest)(implicit ctx: ActorContext, log: LoggingAdapter): Data = {
    implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors

    // we convert extra routing info provided in the payment request to fake channel_update
    // it takes precedence over all other channel_updates we know
    val assistedChannels: Map[ShortChannelId, AssistedChannel] = r.assistedRoutes.flatMap(toAssistedChannels(_, r.target, r.amount)).toMap
    val extraEdges = assistedChannels.values.map(ac =>
      GraphEdge(ChannelDesc(ac.extraHop.shortChannelId, ac.extraHop.nodeId, ac.nextNodeId), toFakeUpdate(ac.extraHop, ac.htlcMaximum), htlcMaxToCapacity(ac.htlcMaximum), Some(ac.htlcMaximum))
    ).toSet
    val ignoredEdges = r.ignoreChannels ++ d.excludedChannels
    val defaultRouteParams: RouteParams = getDefaultRouteParams(routerConf)
    val params = r.routeParams.getOrElse(defaultRouteParams)
    val routesToFind = if (params.randomize) DEFAULT_ROUTES_COUNT else 1
    
    log.info(s"finding a route ${r.source}->${r.target} with assistedChannels={} ignoreNodes={} ignoreChannels={} excludedChannels={}", assistedChannels.keys.mkString(","), r.ignoreNodes.map(_.value).mkString(","), r.ignoreChannels.mkString(","), d.excludedChannels.mkString(","))
    log.info(s"finding a route with randomize={} params={}", routesToFind > 1, params)
    findRoute(d.graph, r.source, r.target, r.amount, numRoutes = routesToFind, extraEdges = extraEdges, ignoredEdges = ignoredEdges, ignoredVertices = r.ignoreNodes, routeParams = params, currentBlockHeight)
      .map(route => ctx.sender ! RouteResponse(route, r.ignoreNodes, r.ignoreChannels))
      .recover { case t => ctx.sender ! Status.Failure(t) }
    d
  }

  def toFakeUpdate(extraHop: ExtraHop, htlcMaximum: MilliSatoshi): ChannelUpdate = {
    // the `direction` bit in flags will not be accurate but it doesn't matter because it is not used
    // what matters is that the `disable` bit is 0 so that this update doesn't get filtered out
    ChannelUpdate(signature = ByteVector64.Zeroes, chainHash = ByteVector32.Zeroes, extraHop.shortChannelId, Platform.currentTime.milliseconds.toSeconds, messageFlags = 1, channelFlags = 0, extraHop.cltvExpiryDelta, htlcMinimumMsat = 0 msat, extraHop.feeBase, extraHop.feeProportionalMillionths, Some(htlcMaximum))
  }

  def toAssistedChannels(extraRoute: Seq[ExtraHop], targetNodeId: PublicKey, amount: MilliSatoshi): Map[ShortChannelId, AssistedChannel] = {
    // BOLT 11: "For each entry, the pubkey is the node ID of the start of the channel", and the last node is the destination
    // The invoice doesn't explicitly specify the channel's htlcMaximumMsat, but we can safely assume that the channel
    // should be able to route the payment, so we'll compute an htlcMaximumMsat accordingly.
    // We could also get the channel capacity from the blockchain (since we have the shortChannelId) but that's more expensive.
    // We also need to make sure the channel isn't excluded by our heuristics.
    val lastChannelCapacity = amount.max(RoutingHeuristics.CAPACITY_CHANNEL_LOW)
    val nextNodeIds = extraRoute.map(_.nodeId).drop(1) :+ targetNodeId
    extraRoute.zip(nextNodeIds).reverse.foldLeft((lastChannelCapacity, Map.empty[ShortChannelId, AssistedChannel])) {
      case ((amount, acs), (extraHop: ExtraHop, nextNodeId)) =>
        val nextAmount = amount + nodeFee(extraHop.feeBase, extraHop.feeProportionalMillionths, amount)
        (nextAmount, acs + (extraHop.shortChannelId -> AssistedChannel(extraHop, nextNodeId, nextAmount)))
    }._2
  }

  /** Bolt 11 routing hints don't include the channel's capacity, so we round up the maximum htlc amount. */
  def htlcMaxToCapacity(htlcMaximum: MilliSatoshi): Satoshi = htlcMaximum.truncateToSatoshi + 1.sat

  /**
   * This method is used after a payment failed, and we want to exclude some nodes that we know are failing
   */
  def getIgnoredChannelDesc(channels: Map[ShortChannelId, PublicChannel], ignoreNodes: Set[PublicKey]): Iterable[ChannelDesc] = {
    val desc = if (ignoreNodes.isEmpty) {
      Iterable.empty[ChannelDesc]
    } else {
      // expensive, but node blacklisting shouldn't happen often
      channels.values
        .filter(channelData => ignoreNodes.contains(channelData.ann.nodeId1) || ignoreNodes.contains(channelData.ann.nodeId2))
        .flatMap(channelData => Vector(ChannelDesc(channelData.ann.shortChannelId, channelData.ann.nodeId1, channelData.ann.nodeId2), ChannelDesc(channelData.ann.shortChannelId, channelData.ann.nodeId2, channelData.ann.nodeId1)))
    }
    desc
  }

  /**
   * https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#clarifications
   */
  val ROUTE_MAX_LENGTH = 20

  // Max allowed CLTV for a route
  val DEFAULT_ROUTE_MAX_CLTV = CltvExpiryDelta(1008)

  // The default number of routes we'll search for when findRoute is called with randomize = true
  val DEFAULT_ROUTES_COUNT = 3

  def getDefaultRouteParams(routerConf: RouterConf) = RouteParams(
    randomize = routerConf.randomizeRouteSelection,
    maxFeeBase = routerConf.searchMaxFeeBase.toMilliSatoshi,
    maxFeePct = routerConf.searchMaxFeePct,
    routeMaxLength = routerConf.searchMaxRouteLength,
    routeMaxCltv = routerConf.searchMaxCltv,
    ratios = routerConf.searchHeuristicsEnabled match {
      case false => None
      case true => Some(WeightRatios(
        cltvDeltaFactor = routerConf.searchRatioCltv,
        ageFactor = routerConf.searchRatioChannelAge,
        capacityFactor = routerConf.searchRatioChannelCapacity
      ))
    }
  )

  /**
   * Find a route in the graph between localNodeId and targetNodeId, returns the route.
   * Will perform a k-shortest path selection given the @param numRoutes and randomly select one of the result.
   *
   * @param g            graph of the whole network
   * @param localNodeId  sender node (payer)
   * @param targetNodeId target node (final recipient)
   * @param amount       the amount that will be sent along this route
   * @param numRoutes    the number of shortest-paths to find
   * @param extraEdges   a set of extra edges we want to CONSIDER during the search
   * @param ignoredEdges a set of extra edges we want to IGNORE during the search
   * @param routeParams  a set of parameters that can restrict the route search
   * @return the computed route to the destination @targetNodeId
   */
  def findRoute(g: DirectedGraph,
                localNodeId: PublicKey,
                targetNodeId: PublicKey,
                amount: MilliSatoshi,
                numRoutes: Int,
                extraEdges: Set[GraphEdge] = Set.empty,
                ignoredEdges: Set[ChannelDesc] = Set.empty,
                ignoredVertices: Set[PublicKey] = Set.empty,
                routeParams: RouteParams,
                currentBlockHeight: Long): Try[Seq[ChannelHop]] = Try {

    if (localNodeId == targetNodeId) throw CannotRouteToSelf

    def feeBaseOk(fee: MilliSatoshi): Boolean = fee <= routeParams.maxFeeBase

    def feePctOk(fee: MilliSatoshi, amount: MilliSatoshi): Boolean = {
      val maxFee = amount * routeParams.maxFeePct
      fee <= maxFee
    }

    def feeOk(fee: MilliSatoshi, amount: MilliSatoshi): Boolean = feeBaseOk(fee) || feePctOk(fee, amount)

    def lengthOk(length: Int): Boolean = length <= routeParams.routeMaxLength && length <= ROUTE_MAX_LENGTH

    def cltvOk(cltv: CltvExpiryDelta): Boolean = cltv <= routeParams.routeMaxCltv

    val boundaries: RichWeight => Boolean = { weight =>
      feeOk(weight.cost - amount, amount) && lengthOk(weight.length) && cltvOk(weight.cltv)
    }

    val foundRoutes = KamonExt.time(Metrics.FindRouteDuration.withTag(Tags.NumberOfRoutes, numRoutes).withTag(Tags.Amount, Tags.amountBucket(amount))) {
      Graph.yenKshortestPaths(g, localNodeId, targetNodeId, amount, ignoredEdges, ignoredVertices, extraEdges, numRoutes, routeParams.ratios, currentBlockHeight, boundaries).toList
    }
    foundRoutes match {
      case Nil if routeParams.routeMaxLength < ROUTE_MAX_LENGTH => // if not found within the constraints we relax and repeat the search
        Metrics.RouteLength.withTag(Tags.Amount, Tags.amountBucket(amount)).record(0)
        return findRoute(g, localNodeId, targetNodeId, amount, numRoutes, extraEdges, ignoredEdges, ignoredVertices, routeParams.copy(routeMaxLength = ROUTE_MAX_LENGTH, routeMaxCltv = DEFAULT_ROUTE_MAX_CLTV), currentBlockHeight)
      case Nil =>
        Metrics.RouteLength.withTag(Tags.Amount, Tags.amountBucket(amount)).record(0)
        throw RouteNotFound
      case foundRoutes =>
        val routes = foundRoutes.find(_.path.size == 1) match {
          case Some(directRoute) => directRoute :: Nil
          case _ => foundRoutes
        }
        // At this point 'routes' cannot be empty
        val randomizedRoutes = if (routeParams.randomize) Random.shuffle(routes) else routes
        val route = randomizedRoutes.head.path.map(graphEdgeToHop)
        Metrics.RouteLength.withTag(Tags.Amount, Tags.amountBucket(amount)).record(route.length)
        route
    }
  }
}
