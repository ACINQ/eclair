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
import akka.event.DiagnosticLoggingAdapter
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Satoshi, SatoshiLong}
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair._
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{RichWeight, RoutingHeuristics, WeightRatios}
import fr.acinq.eclair.router.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import kamon.tag.TagSet

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.{Failure, Random, Success, Try}

object RouteCalculation {

  def finalizeRoute(d: Data, localNodeId: PublicKey, fr: FinalizeRoute)(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Data = {
    Logs.withMdc(log)(Logs.mdc(
      category_opt = Some(LogCategory.PAYMENT),
      parentPaymentId_opt = fr.paymentContext.map(_.parentId),
      paymentId_opt = fr.paymentContext.map(_.id),
      paymentHash_opt = fr.paymentContext.map(_.paymentHash))) {
      implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors

      val assistedChannels: Map[ShortChannelId, AssistedChannel] = fr.assistedRoutes.flatMap(toAssistedChannels(_, fr.route.targetNodeId, fr.amount)).toMap
      val extraEdges = assistedChannels.values.map(ac =>
        GraphEdge(ChannelDesc(ac.extraHop.shortChannelId, ac.extraHop.nodeId, ac.nextNodeId), toFakeUpdate(ac.extraHop, ac.htlcMaximum), htlcMaxToCapacity(ac.htlcMaximum), Some(ac.htlcMaximum))
      ).toSet
      val g = extraEdges.foldLeft(d.graph) { case (g: DirectedGraph, e: GraphEdge) => g.addEdge(e) }

      fr.route match {
        case PredefinedNodeRoute(hops) =>
          // split into sublists [(a,b),(b,c), ...] then get the edges between each of those pairs
          hops.sliding(2).map { case List(v1, v2) => g.getEdgesBetween(v1, v2) }.toList match {
            case edges if edges.nonEmpty && edges.forall(_.nonEmpty) =>
              // select the largest edge (using balance when available, otherwise capacity).
              val selectedEdges = edges.map(es => es.maxBy(e => e.balance_opt.getOrElse(e.capacity.toMilliSatoshi)))
              val hops = selectedEdges.map(d => ChannelHop(d.desc.a, d.desc.b, d.update))
              ctx.sender ! RouteResponse(Route(fr.amount, hops) :: Nil)
            case _ =>
              // some nodes in the supplied route aren't connected in our graph
              ctx.sender ! Status.Failure(new IllegalArgumentException("Not all the nodes in the supplied route are connected with public channels"))
          }
        case PredefinedChannelRoute(targetNodeId, channels) =>
          val (end, hops) = channels.foldLeft((localNodeId, Seq.empty[ChannelHop])) {
            case ((start, current), shortChannelId) =>
              d.channels.get(shortChannelId).flatMap(c => start match {
                case c.ann.nodeId1 => g.getEdge(ChannelDesc(shortChannelId, c.ann.nodeId1, c.ann.nodeId2))
                case c.ann.nodeId2 => g.getEdge(ChannelDesc(shortChannelId, c.ann.nodeId2, c.ann.nodeId1))
                case _ => None
              }) match {
                case Some(edge) => (edge.desc.b, current :+ ChannelHop(edge.desc.a, edge.desc.b, edge.update))
                case None => (start, current)
              }
          }
          if (end != targetNodeId || hops.length != channels.length) {
            ctx.sender ! Status.Failure(new IllegalArgumentException("The sequence of channels provided cannot be used to build a route to the target node"))
          } else {
            ctx.sender ! RouteResponse(Route(fr.amount, hops) :: Nil)
          }
      }

      d
    }
  }

  def handleRouteRequest(d: Data, routerConf: RouterConf, currentBlockHeight: Long, r: RouteRequest)(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Data = {
    Logs.withMdc(log)(Logs.mdc(
      category_opt = Some(LogCategory.PAYMENT),
      parentPaymentId_opt = r.paymentContext.map(_.parentId),
      paymentId_opt = r.paymentContext.map(_.id),
      paymentHash_opt = r.paymentContext.map(_.paymentHash))) {
      implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors

      // we convert extra routing info provided in the payment request to fake channel_update
      // it takes precedence over all other channel_updates we know
      val assistedChannels: Map[ShortChannelId, AssistedChannel] = r.assistedRoutes.flatMap(toAssistedChannels(_, r.target, r.amount))
        .filterNot { case (_, ac) => ac.extraHop.nodeId == r.source } // we ignore routing hints for our own channels, we have more accurate information
        .toMap
      val extraEdges = assistedChannels.values.map(ac =>
        GraphEdge(ChannelDesc(ac.extraHop.shortChannelId, ac.extraHop.nodeId, ac.nextNodeId), toFakeUpdate(ac.extraHop, ac.htlcMaximum), htlcMaxToCapacity(ac.htlcMaximum), Some(ac.htlcMaximum))
      ).toSet
      val ignoredEdges = r.ignore.channels ++ d.excludedChannels
      val params = r.routeParams.getOrElse(getDefaultRouteParams(routerConf))
      val routesToFind = if (params.randomize) DEFAULT_ROUTES_COUNT else 1

      log.info(s"finding routes ${r.source}->${r.target} with assistedChannels={} ignoreNodes={} ignoreChannels={} excludedChannels={}", assistedChannels.keys.mkString(","), r.ignore.nodes.map(_.value).mkString(","), r.ignore.channels.mkString(","), d.excludedChannels.mkString(","))
      log.info("finding routes with randomize={} params={}", params.randomize, params)
      val tags = TagSet.Empty.withTag(Tags.MultiPart, r.allowMultiPart).withTag(Tags.Amount, Tags.amountBucket(r.amount))
      KamonExt.time(Metrics.FindRouteDuration.withTags(tags.withTag(Tags.NumberOfRoutes, routesToFind.toLong))) {
        val result = if (r.allowMultiPart) {
          findMultiPartRoute(d.graph, r.source, r.target, r.amount, r.maxFee, extraEdges, ignoredEdges, r.ignore.nodes, r.pendingPayments, params, currentBlockHeight)
        } else {
          findRoute(d.graph, r.source, r.target, r.amount, r.maxFee, routesToFind, extraEdges, ignoredEdges, r.ignore.nodes, params, currentBlockHeight)
        }
        result match {
          case Success(routes) =>
            Metrics.RouteResults.withTags(tags).record(routes.length)
            routes.foreach(route => Metrics.RouteLength.withTags(tags).record(route.length))
            ctx.sender ! RouteResponse(routes)
          case Failure(t) =>
            val failure = if (isNeighborBalanceTooLow(d.graph, r)) BalanceTooLow else t
            Metrics.FindRouteErrors.withTags(tags.withTag(Tags.Error, failure.getClass.getSimpleName)).increment()
            ctx.sender ! Status.Failure(failure)
        }
      }
      d
    }
  }

  private def toFakeUpdate(extraHop: ExtraHop, htlcMaximum: MilliSatoshi): ChannelUpdate = {
    // the `direction` bit in flags will not be accurate but it doesn't matter because it is not used
    // what matters is that the `disable` bit is 0 so that this update doesn't get filtered out
    ChannelUpdate(signature = ByteVector64.Zeroes, chainHash = ByteVector32.Zeroes, extraHop.shortChannelId, System.currentTimeMillis.milliseconds.toSeconds, messageFlags = 1, channelFlags = 0, extraHop.cltvExpiryDelta, htlcMinimumMsat = 0 msat, extraHop.feeBase, extraHop.feeProportionalMillionths, Some(htlcMaximum))
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

  def toChannelDescs(extraRoute: Seq[ExtraHop], targetNodeId: PublicKey): Seq[ChannelDesc] = {
    val nextNodeIds = extraRoute.map(_.nodeId).drop(1) :+ targetNodeId
    extraRoute.zip(nextNodeIds).map { case (hop, nextNodeId) => ChannelDesc(hop.shortChannelId, hop.nodeId, nextNodeId) }
  }

  /** Bolt 11 routing hints don't include the channel's capacity, so we round up the maximum htlc amount. */
  private def htlcMaxToCapacity(htlcMaximum: MilliSatoshi): Satoshi = htlcMaximum.truncateToSatoshi + 1.sat

  /** This method is used after a payment failed, and we want to exclude some nodes that we know are failing */
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

  /** https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#clarifications */
  val ROUTE_MAX_LENGTH = 20

  /** Max allowed CLTV for a route (one week) */
  val DEFAULT_ROUTE_MAX_CLTV = CltvExpiryDelta(1008)

  /** The default number of routes we'll search for when findRoute is called with randomize = true */
  val DEFAULT_ROUTES_COUNT = 3

  def getDefaultRouteParams(routerConf: RouterConf): RouteParams = RouteParams(
    randomize = routerConf.randomizeRouteSelection,
    maxFeeBase = routerConf.searchMaxFeeBase.toMilliSatoshi,
    maxFeePct = routerConf.searchMaxFeePct,
    routeMaxLength = routerConf.searchMaxRouteLength,
    routeMaxCltv = routerConf.searchMaxCltv,
    ratios = WeightRatios(
      biasFactor = routerConf.searchRatioBias,
      cltvDeltaFactor = routerConf.searchRatioCltv,
      ageFactor = routerConf.searchRatioChannelAge,
      capacityFactor = routerConf.searchRatioChannelCapacity,
      hopCostBase = routerConf.searchHopCostBase,
      hopCostMillionths = routerConf.searchHopCostMillionths
    ),
    mpp = MultiPartParams(routerConf.mppMinPartAmount, routerConf.mppMaxParts)
  )

  /**
   * Find a route in the graph between localNodeId and targetNodeId, returns the route.
   * Will perform a k-shortest path selection given the @param numRoutes and randomly select one of the result.
   *
   * @param g               graph of the whole network
   * @param localNodeId     sender node (payer)
   * @param targetNodeId    target node (final recipient)
   * @param amount          the amount that the target node should receive
   * @param maxFee          the maximum fee of a resulting route
   * @param numRoutes       the number of routes to find
   * @param extraEdges      a set of extra edges we want to CONSIDER during the search
   * @param ignoredEdges    a set of extra edges we want to IGNORE during the search
   * @param ignoredVertices a set of extra vertices we want to IGNORE during the search
   * @param routeParams     a set of parameters that can restrict the route search
   * @return the computed routes to the destination @param targetNodeId
   */
  def findRoute(g: DirectedGraph,
                localNodeId: PublicKey,
                targetNodeId: PublicKey,
                amount: MilliSatoshi,
                maxFee: MilliSatoshi,
                numRoutes: Int,
                extraEdges: Set[GraphEdge] = Set.empty,
                ignoredEdges: Set[ChannelDesc] = Set.empty,
                ignoredVertices: Set[PublicKey] = Set.empty,
                routeParams: RouteParams,
                currentBlockHeight: Long): Try[Seq[Route]] = Try {
    findRouteInternal(g, localNodeId, targetNodeId, amount, maxFee, numRoutes, extraEdges, ignoredEdges, ignoredVertices, routeParams, currentBlockHeight) match {
      case Right(routes) => routes.map(route => Route(amount, route.path.map(graphEdgeToHop)))
      case Left(ex) => return Failure(ex)
    }
  }

  @tailrec
  private def findRouteInternal(g: DirectedGraph,
                                localNodeId: PublicKey,
                                targetNodeId: PublicKey,
                                amount: MilliSatoshi,
                                maxFee: MilliSatoshi,
                                numRoutes: Int,
                                extraEdges: Set[GraphEdge] = Set.empty,
                                ignoredEdges: Set[ChannelDesc] = Set.empty,
                                ignoredVertices: Set[PublicKey] = Set.empty,
                                routeParams: RouteParams,
                                currentBlockHeight: Long): Either[RouterException, Seq[Graph.WeightedPath]] = {
    require(amount > 0.msat, "route amount must be strictly positive")

    if (localNodeId == targetNodeId) return Left(CannotRouteToSelf)

    def feeOk(fee: MilliSatoshi): Boolean = fee <= maxFee

    def lengthOk(length: Int): Boolean = length <= routeParams.routeMaxLength && length <= ROUTE_MAX_LENGTH

    def cltvOk(cltv: CltvExpiryDelta): Boolean = cltv <= routeParams.routeMaxCltv

    val boundaries: RichWeight => Boolean = { weight => feeOk(weight.cost - amount) && lengthOk(weight.length) && cltvOk(weight.cltv) }

    val foundRoutes: Seq[Graph.WeightedPath] = Graph.yenKshortestPaths(g, localNodeId, targetNodeId, amount, ignoredEdges, ignoredVertices, extraEdges, numRoutes, routeParams.ratios, currentBlockHeight, boundaries)
    if (foundRoutes.nonEmpty) {
      val (directRoutes, indirectRoutes) = foundRoutes.partition(_.path.length == 1)
      val routes = if (routeParams.randomize) {
        Random.shuffle(directRoutes) ++ Random.shuffle(indirectRoutes)
      } else {
        directRoutes ++ indirectRoutes
      }
      Right(routes)
    } else if (routeParams.routeMaxLength < ROUTE_MAX_LENGTH) {
      // if not found within the constraints we relax and repeat the search
      val relaxedRouteParams = routeParams.copy(routeMaxLength = ROUTE_MAX_LENGTH, routeMaxCltv = DEFAULT_ROUTE_MAX_CLTV)
      findRouteInternal(g, localNodeId, targetNodeId, amount, maxFee, numRoutes, extraEdges, ignoredEdges, ignoredVertices, relaxedRouteParams, currentBlockHeight)
    } else {
      Left(RouteNotFound)
    }
  }

  /**
   * Find a multi-part route in the graph between localNodeId and targetNodeId.
   *
   * @param g               graph of the whole network
   * @param localNodeId     sender node (payer)
   * @param targetNodeId    target node (final recipient)
   * @param amount          the amount that the target node should receive
   * @param maxFee          the maximum fee of a resulting route
   * @param extraEdges      a set of extra edges we want to CONSIDER during the search
   * @param ignoredEdges    a set of extra edges we want to IGNORE during the search
   * @param ignoredVertices a set of extra vertices we want to IGNORE during the search
   * @param pendingHtlcs    a list of htlcs that have already been sent for that multi-part payment (used to avoid finding conflicting HTLCs)
   * @param routeParams     a set of parameters that can restrict the route search
   * @return a set of disjoint routes to the destination @param targetNodeId with the payment amount split between them
   */
  def findMultiPartRoute(g: DirectedGraph,
                         localNodeId: PublicKey,
                         targetNodeId: PublicKey,
                         amount: MilliSatoshi,
                         maxFee: MilliSatoshi,
                         extraEdges: Set[GraphEdge] = Set.empty,
                         ignoredEdges: Set[ChannelDesc] = Set.empty,
                         ignoredVertices: Set[PublicKey] = Set.empty,
                         pendingHtlcs: Seq[Route] = Nil,
                         routeParams: RouteParams,
                         currentBlockHeight: Long): Try[Seq[Route]] = Try {
    val result = findMultiPartRouteInternal(g, localNodeId, targetNodeId, amount, maxFee, extraEdges, ignoredEdges, ignoredVertices, pendingHtlcs, routeParams, currentBlockHeight) match {
      case Right(routes) => Right(routes)
      case Left(RouteNotFound) if routeParams.randomize =>
        // If we couldn't find a randomized solution, fallback to a deterministic one.
        findMultiPartRouteInternal(g, localNodeId, targetNodeId, amount, maxFee, extraEdges, ignoredEdges, ignoredVertices, pendingHtlcs, routeParams.copy(randomize = false), currentBlockHeight)
      case Left(ex) => Left(ex)
    }
    result match {
      case Right(routes) => routes
      case Left(ex) => return Failure(ex)
    }
  }

  private def findMultiPartRouteInternal(g: DirectedGraph,
                                         localNodeId: PublicKey,
                                         targetNodeId: PublicKey,
                                         amount: MilliSatoshi,
                                         maxFee: MilliSatoshi,
                                         extraEdges: Set[GraphEdge] = Set.empty,
                                         ignoredEdges: Set[ChannelDesc] = Set.empty,
                                         ignoredVertices: Set[PublicKey] = Set.empty,
                                         pendingHtlcs: Seq[Route] = Nil,
                                         routeParams: RouteParams,
                                         currentBlockHeight: Long): Either[RouterException, Seq[Route]] = {
    // We use Yen's k-shortest paths to find many paths for chunks of the total amount.
    // When the recipient is a direct peer, we have complete visibility on our local channels so we can use more accurate MPP parameters.
    val routeParams1 = {
      case class DirectChannel(balance: MilliSatoshi, isEmpty: Boolean)
      val directChannels = g.getEdgesBetween(localNodeId, targetNodeId).collect {
        // We should always have balance information available for local channels.
        // NB: htlcMinimumMsat is set by our peer and may be 0 msat (even though it's not recommended).
        case GraphEdge(_, update, _, Some(balance)) => DirectChannel(balance, balance <= 0.msat || balance < update.htlcMinimumMsat)
      }
      // If we have direct channels to the target, we can use them all.
      // We also count empty channels, which allows replacing them with a non-direct route (multiple hops).
      val numRoutes = routeParams.mpp.maxParts.max(directChannels.length)
      // If we have direct channels to the target, we can use them all, even if they have only a small balance left.
      val minPartAmount = (amount +: routeParams.mpp.minPartAmount +: directChannels.filter(!_.isEmpty).map(_.balance)).min
      routeParams.copy(mpp = MultiPartParams(minPartAmount, numRoutes))
    }
    findRouteInternal(g, localNodeId, targetNodeId, routeParams1.mpp.minPartAmount, maxFee, routeParams1.mpp.maxParts, extraEdges, ignoredEdges, ignoredVertices, routeParams1, currentBlockHeight) match {
      case Right(routes) =>
        // We use these shortest paths to find a set of non-conflicting HTLCs that send the total amount.
        split(amount, mutable.Queue(routes: _*), initializeUsedCapacity(pendingHtlcs), routeParams1) match {
          case Right(routes) if validateMultiPartRoute(amount, maxFee, routes) => Right(routes)
          case _ => Left(RouteNotFound)
        }
      case Left(ex) => Left(ex)
    }
  }

  @tailrec
  private def split(amount: MilliSatoshi, paths: mutable.Queue[Graph.WeightedPath], usedCapacity: mutable.Map[ShortChannelId, MilliSatoshi], routeParams: RouteParams, selectedRoutes: Seq[Route] = Nil): Either[RouterException, Seq[Route]] = {
    if (amount == 0.msat) {
      Right(selectedRoutes)
    } else if (paths.isEmpty) {
      Left(RouteNotFound)
    } else {
      val current = paths.dequeue()
      val candidate = computeRouteMaxAmount(current.path, usedCapacity)
      if (candidate.amount < routeParams.mpp.minPartAmount.min(amount)) {
        // this route doesn't have enough capacity left: we remove it and continue.
        split(amount, paths, usedCapacity, routeParams, selectedRoutes)
      } else {
        val route = if (routeParams.randomize) {
          // randomly choose the amount to be between 20% and 100% of the available capacity.
          val randomizedAmount = candidate.amount * ((20d + Random.nextInt(81)) / 100)
          if (randomizedAmount < routeParams.mpp.minPartAmount) {
            candidate.copy(amount = routeParams.mpp.minPartAmount.min(amount))
          } else {
            candidate.copy(amount = randomizedAmount.min(amount))
          }
        } else {
          candidate.copy(amount = candidate.amount.min(amount))
        }
        updateUsedCapacity(route, usedCapacity)
        // NB: we re-enqueue the current path, it may still have capacity for a second HTLC.
        split(amount - route.amount, paths.enqueue(current), usedCapacity, routeParams, route +: selectedRoutes)
      }
    }
  }

  /** Compute the maximum amount that we can send through the given route. */
  private def computeRouteMaxAmount(route: Seq[GraphEdge], usedCapacity: mutable.Map[ShortChannelId, MilliSatoshi]): Route = {
    val firstHopMaxAmount = route.head.maxHtlcAmount(usedCapacity.getOrElse(route.head.update.shortChannelId, 0 msat))
    val amount = route.drop(1).foldLeft(firstHopMaxAmount) { case (amount, edge) =>
      // We compute fees going forward instead of backwards. That means we will slightly overestimate the fees of some
      // edges, but we will always stay inside the capacity bounds we computed.
      val amountMinusFees = amount - edge.fee(amount)
      val edgeMaxAmount = edge.maxHtlcAmount(usedCapacity.getOrElse(edge.update.shortChannelId, 0 msat))
      amountMinusFees.min(edgeMaxAmount)
    }
    Route(amount.max(0 msat), route.map(graphEdgeToHop))
  }

  /** Initialize known used capacity based on pending HTLCs. */
  private def initializeUsedCapacity(pendingHtlcs: Seq[Route]): mutable.Map[ShortChannelId, MilliSatoshi] = {
    val usedCapacity = mutable.Map.empty[ShortChannelId, MilliSatoshi]
    // We always skip the first hop: since they are local channels, we already take into account those sent HTLCs in the
    // channel balance (which overrides the channel capacity in route calculation).
    pendingHtlcs.filter(_.hops.length > 1).foreach(route => updateUsedCapacity(route.copy(hops = route.hops.tail), usedCapacity))
    usedCapacity
  }

  /** Update used capacity by taking into account an HTLC sent to the given route. */
  private def updateUsedCapacity(route: Route, usedCapacity: mutable.Map[ShortChannelId, MilliSatoshi]): Unit = {
    route.hops.reverse.foldLeft(route.amount) { case (amount, hop) =>
      usedCapacity.updateWith(hop.lastUpdate.shortChannelId)(previous => Some(amount + previous.getOrElse(0 msat)))
      amount + hop.fee(amount)
    }
  }

  private def validateMultiPartRoute(amount: MilliSatoshi, maxFee: MilliSatoshi, routes: Seq[Route]): Boolean = {
    val amountOk = routes.map(_.amount).sum == amount
    val feeOk = routes.map(_.fee).sum <= maxFee
    amountOk && feeOk
  }

  /**
   * Checks if we are directly connected to the target but don't have enough balance in our local channels to send the
   * requested amount. We could potentially relay the payment by using indirect routes, but since we're connected to
   * the target node it means we'd like to reach it via direct channels as much as possible.
   */
  private def isNeighborBalanceTooLow(g: DirectedGraph, r: RouteRequest): Boolean = {
    val neighborEdges = g.getEdgesBetween(r.source, r.target)
    neighborEdges.nonEmpty && neighborEdges.map(e => e.balance_opt.getOrElse(e.capacity.toMilliSatoshi)).sum < r.amount
  }

}
