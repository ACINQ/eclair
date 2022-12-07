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
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair._
import fr.acinq.eclair.payment.send._
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{InfiniteLoop, NegativeProbability, RichWeight}
import fr.acinq.eclair.router.Monitoring.{Metrics, Tags}
import fr.acinq.eclair.router.Router._
import kamon.tag.TagSet

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.{Failure, Random, Success, Try}

object RouteCalculation {

  private def getEdgeRelayScid(d: Data, localNodeId: PublicKey, e: GraphEdge): ShortChannelId = {
    if (e.desc.b == localNodeId) {
      // We are the destination of that edge: local graph edges always use either the local alias or the real scid.
      // We want to use the remote alias when available, because our peer won't understand our local alias.
      d.resolve(e.desc.shortChannelId) match {
        case Some(c: PrivateChannel) => c.shortIds.remoteAlias_opt.getOrElse(e.desc.shortChannelId)
        case _ => e.desc.shortChannelId
      }
    } else {
      e.desc.shortChannelId
    }
  }

  def finalizeRoute(d: Data, localNodeId: PublicKey, fr: FinalizeRoute)(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Data = {
    Logs.withMdc(log)(Logs.mdc(
      category_opt = Some(LogCategory.PAYMENT),
      parentPaymentId_opt = fr.paymentContext.map(_.parentId),
      paymentId_opt = fr.paymentContext.map(_.id),
      paymentHash_opt = fr.paymentContext.map(_.paymentHash))) {
      implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors

      val extraEdges = fr.extraEdges.map(GraphEdge(_))
      val g = extraEdges.foldLeft(d.graphWithBalances.graph) { case (g: DirectedGraph, e: GraphEdge) => g.addEdge(e) }

      fr.route match {
        case PredefinedNodeRoute(amount, hops) =>
          // split into sublists [(a,b),(b,c), ...] then get the edges between each of those pairs
          hops.sliding(2).map { case List(v1, v2) => g.getEdgesBetween(v1, v2) }.toList match {
            case edges if edges.nonEmpty && edges.forall(_.nonEmpty) =>
              // select the largest edge (using balance when available, otherwise capacity).
              val selectedEdges = edges.map(es => es.maxBy(e => e.balance_opt.getOrElse(e.capacity.toMilliSatoshi)))
              val hops = selectedEdges.map(e => ChannelHop(getEdgeRelayScid(d, localNodeId, e), e.desc.a, e.desc.b, e.params))
              ctx.sender() ! RouteResponse(Route(amount, hops, None) :: Nil)
            case _ =>
              // some nodes in the supplied route aren't connected in our graph
              ctx.sender() ! Status.Failure(new IllegalArgumentException("Not all the nodes in the supplied route are connected with public channels"))
          }
        case PredefinedChannelRoute(amount, targetNodeId, shortChannelIds) =>
          val (end, hops) = shortChannelIds.foldLeft((localNodeId, Seq.empty[ChannelHop])) {
            case ((currentNode, previousHops), shortChannelId) =>
              val channelDesc_opt = d.resolve(shortChannelId) match {
                case Some(c: PublicChannel) => currentNode match {
                  case c.nodeId1 => Some(ChannelDesc(shortChannelId, c.nodeId1, c.nodeId2))
                  case c.nodeId2 => Some(ChannelDesc(shortChannelId, c.nodeId2, c.nodeId1))
                  case _ => None
                }
                case Some(c: PrivateChannel) => currentNode match {
                  case c.nodeId1 => Some(ChannelDesc(c.shortIds.localAlias, c.nodeId1, c.nodeId2))
                  case c.nodeId2 => Some(ChannelDesc(c.shortIds.localAlias, c.nodeId2, c.nodeId1))
                  case _ => None
                }
                case None => extraEdges.find(e => e.desc.shortChannelId == shortChannelId && e.desc.a == currentNode).map(_.desc)
              }
              channelDesc_opt.flatMap(c => g.getEdge(c)) match {
                case Some(edge) => (edge.desc.b, previousHops :+ ChannelHop(getEdgeRelayScid(d, localNodeId, edge), edge.desc.a, edge.desc.b, edge.params))
                case None => (currentNode, previousHops)
              }
          }
          if (end != targetNodeId || hops.length != shortChannelIds.length) {
            ctx.sender() ! Status.Failure(new IllegalArgumentException("The sequence of channels provided cannot be used to build a route to the target node"))
          } else {
            ctx.sender() ! RouteResponse(Route(amount, hops, None) :: Nil)
          }
      }

      d
    }
  }

  /**
   * Based on the type of recipient for the payment, this function returns:
   *  - the node to which routes should be found
   *  - the amount that should be sent to that node
   *  - the maximum allowed fee for routes to that node
   *  - an optional set of additional graph edges
   *
   * The routes found must then be post-processed by calling [[addFinalHop]].
   */
  private def computeTarget(r: RouteRequest, ignoredEdges: Set[ChannelDesc]): (PublicKey, MilliSatoshi, MilliSatoshi, Set[GraphEdge]) = {
    val pendingAmount = r.pendingPayments.map(_.amount).sum
    val totalMaxFee = r.routeParams.getMaxFee(r.target.totalAmount)
    val pendingChannelFee = r.pendingPayments.map(_.channelFee(r.routeParams.includeLocalChannelCost)).sum
    r.target match {
      case recipient: ClearRecipient =>
        val targetNodeId = recipient.nodeId
        val amountToSend = recipient.totalAmount - pendingAmount
        val maxFee = totalMaxFee - pendingChannelFee
        val extraEdges = recipient.extraEdges
          .filter(_.sourceNodeId != r.source) // we ignore routing hints for our own channels, we have more accurate information
          .map(GraphEdge(_))
          .filterNot(e => ignoredEdges.contains(e.desc))
          .toSet
        (targetNodeId, amountToSend, maxFee, extraEdges)
      case recipient: SpontaneousRecipient =>
        val targetNodeId = recipient.nodeId
        val amountToSend = recipient.totalAmount - pendingAmount
        val maxFee = totalMaxFee - pendingChannelFee
        (targetNodeId, amountToSend, maxFee, Set.empty)
      case recipient: BlindedRecipient =>
        // Blinded routes all end at a different (blinded) node, so we create graph edges in which they lead to the same node.
        val targetNodeId = randomKey().publicKey
        val extraEdges = recipient.extraEdges
          .map(_.copy(targetNodeId = targetNodeId))
          .filterNot(e => ignoredEdges.exists(_.shortChannelId == e.shortChannelId))
          // For blinded routes, the maximum htlc field is used to indicate the maximum amount that can be sent through the route.
          .map(e => GraphEdge(e).copy(balance_opt = e.htlcMaximum_opt))
          .toSet
        val amountToSend = recipient.totalAmount - pendingAmount
        // When we are the introduction node and includeLocalChannelCost is false, we cannot easily remove the fee for
        // the first hop in the blinded route (we would need to decrypt the route and fetch the corresponding channel).
        // In that case, we will slightly over-estimate the fee we're paying, but at least we won't exceed our fee budget.
        val maxFee = totalMaxFee - pendingChannelFee - r.pendingPayments.map(_.blindedFee).sum
        (targetNodeId, amountToSend, maxFee, extraEdges)
      case recipient: ClearTrampolineRecipient =>
        // Trampoline payments require finding routes to the trampoline node, not the final recipient.
        // This also ensures that we correctly take the trampoline fee into account only once, even when using MPP to
        // reach the trampoline node (which will aggregate the incoming MPP payment and re-split as necessary).
        val targetNodeId = recipient.trampolineHop.nodeId
        val amountToSend = recipient.trampolineAmount - pendingAmount
        val maxFee = totalMaxFee - pendingChannelFee - recipient.trampolineFee
        (targetNodeId, amountToSend, maxFee, Set.empty)
    }
  }

  private def addFinalHop(recipient: Recipient, routes: Seq[Route]): Seq[Route] = {
    routes.flatMap(route => {
      recipient match {
        case _: ClearRecipient => Some(route)
        case _: SpontaneousRecipient => Some(route)
        case recipient: ClearTrampolineRecipient => Some(route.copy(finalHop_opt = Some(recipient.trampolineHop)))
        case recipient: BlindedRecipient =>
          route.hops.lastOption.flatMap {
            hop => recipient.blindedHops.find(_.dummyId == hop.shortChannelId)
          }.map {
            blindedHop => Route(route.amount, route.hops.dropRight(1), Some(blindedHop))
          }
      }
    })
  }

  def handleRouteRequest(d: Data, currentBlockHeight: BlockHeight, r: RouteRequest)(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Data = {
    Logs.withMdc(log)(Logs.mdc(
      category_opt = Some(LogCategory.PAYMENT),
      parentPaymentId_opt = r.paymentContext.map(_.parentId),
      paymentId_opt = r.paymentContext.map(_.id),
      paymentHash_opt = r.paymentContext.map(_.paymentHash))) {
      implicit val sender: ActorRef = ctx.self // necessary to preserve origin when sending messages to other actors

      val ignoredEdges = r.ignore.channels ++ d.excludedChannels.keySet
      val (targetNodeId, amountToSend, maxFee, extraEdges) = computeTarget(r, ignoredEdges)
      val routesToFind = if (r.routeParams.randomize) DEFAULT_ROUTES_COUNT else 1

      log.info(s"finding routes ${r.source}->$targetNodeId with assistedChannels={} ignoreNodes={} ignoreChannels={} excludedChannels={}", extraEdges.map(_.desc.shortChannelId).mkString(","), r.ignore.nodes.map(_.value).mkString(","), r.ignore.channels.mkString(","), d.excludedChannels.mkString(","))
      log.info("finding routes with params={}, multiPart={}", r.routeParams, r.allowMultiPart)
      log.info("local channels to target node: {}", d.graphWithBalances.graph.getEdgesBetween(r.source, targetNodeId).map(e => s"${e.desc.shortChannelId} (${e.balance_opt}/${e.capacity})").mkString(", "))
      val tags = TagSet.Empty.withTag(Tags.MultiPart, r.allowMultiPart).withTag(Tags.Amount, Tags.amountBucket(amountToSend))
      KamonExt.time(Metrics.FindRouteDuration.withTags(tags.withTag(Tags.NumberOfRoutes, routesToFind.toLong))) {
        val result = if (r.allowMultiPart) {
          findMultiPartRoute(d.graphWithBalances.graph, r.source, targetNodeId, amountToSend, maxFee, extraEdges, ignoredEdges, r.ignore.nodes, r.pendingPayments, r.routeParams, currentBlockHeight)
        } else {
          findRoute(d.graphWithBalances.graph, r.source, targetNodeId, amountToSend, maxFee, routesToFind, extraEdges, ignoredEdges, r.ignore.nodes, r.routeParams, currentBlockHeight)
        }
        result.map(routes => addFinalHop(r.target, routes)) match {
          case Success(routes) =>
            // Note that we don't record the length of the whole route: we ignore the trampoline hop because we only
            // care about the part that we found ourselves (and we don't even know the length that will be used between
            // trampoline nodes).
            Metrics.RouteResults.withTags(tags).record(routes.length)
            routes.foreach(route => Metrics.RouteLength.withTags(tags).record(route.hops.length))
            ctx.sender() ! RouteResponse(routes)
          case Failure(failure: InfiniteLoop) =>
            log.error(s"found infinite loop ${failure.path.map(edge => edge.desc).mkString(" -> ")}")
            Metrics.FindRouteErrors.withTags(tags.withTag(Tags.Error, "InfiniteLoop")).increment()
            ctx.sender() ! Status.Failure(failure)
          case Failure(failure: NegativeProbability) =>
            log.error(s"computed negative probability: edge=${failure.edge}, weight=${failure.weight}, heuristicsConstants=${failure.heuristicsConstants}")
            Metrics.FindRouteErrors.withTags(tags.withTag(Tags.Error, "NegativeProbability")).increment()
            ctx.sender() ! Status.Failure(failure)
          case Failure(t) =>
            val failure = if (isNeighborBalanceTooLow(d.graphWithBalances.graph, r.source, targetNodeId, amountToSend)) BalanceTooLow else t
            Metrics.FindRouteErrors.withTags(tags.withTag(Tags.Error, failure.getClass.getSimpleName)).increment()
            ctx.sender() ! Status.Failure(failure)
        }
      }
      d
    }
  }

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
                currentBlockHeight: BlockHeight): Try[Seq[Route]] = Try {
    findRouteInternal(g, localNodeId, targetNodeId, amount, maxFee, numRoutes, extraEdges, ignoredEdges, ignoredVertices, routeParams, currentBlockHeight) match {
      case Right(routes) => routes.map(route => Route(amount, route.path.map(graphEdgeToHop), None))
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
                                currentBlockHeight: BlockHeight): Either[RouterException, Seq[Graph.WeightedPath]] = {
    require(amount > 0.msat, "route amount must be strictly positive")

    if (localNodeId == targetNodeId) return Left(CannotRouteToSelf)

    def feeOk(fee: MilliSatoshi): Boolean = fee <= maxFee

    def lengthOk(length: Int): Boolean = length <= routeParams.boundaries.maxRouteLength && length <= ROUTE_MAX_LENGTH

    def cltvOk(cltv: CltvExpiryDelta): Boolean = cltv <= routeParams.boundaries.maxCltv

    val boundaries: RichWeight => Boolean = { weight => feeOk(weight.amount - amount) && lengthOk(weight.length) && cltvOk(weight.cltv) }

    val foundRoutes: Seq[Graph.WeightedPath] = Graph.yenKshortestPaths(g, localNodeId, targetNodeId, amount, ignoredEdges, ignoredVertices, extraEdges, numRoutes, routeParams.heuristics, currentBlockHeight, boundaries, routeParams.includeLocalChannelCost)
    if (foundRoutes.nonEmpty) {
      val (directRoutes, indirectRoutes) = foundRoutes.partition(_.path.length == 1)
      val routes = if (routeParams.randomize) {
        Random.shuffle(directRoutes) ++ Random.shuffle(indirectRoutes)
      } else {
        directRoutes ++ indirectRoutes
      }
      Right(routes)
    } else if (routeParams.boundaries.maxRouteLength < ROUTE_MAX_LENGTH) {
      // if not found within the constraints we relax and repeat the search
      val relaxedRouteParams = routeParams
        .modify(_.boundaries.maxRouteLength).setTo(ROUTE_MAX_LENGTH)
        .modify(_.boundaries.maxCltv).setTo(DEFAULT_ROUTE_MAX_CLTV)
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
                         currentBlockHeight: BlockHeight): Try[Seq[Route]] = Try {
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
                                         currentBlockHeight: BlockHeight): Either[RouterException, Seq[Route]] = {
    // We use Yen's k-shortest paths to find many paths for chunks of the total amount.
    // When the recipient is a direct peer, we have complete visibility on our local channels so we can use more accurate MPP parameters.
    val routeParams1 = {
      case class DirectChannel(balance: MilliSatoshi, isEmpty: Boolean)
      val directChannels = g.getEdgesBetween(localNodeId, targetNodeId).collect {
        // We should always have balance information available for local channels.
        // NB: htlcMinimumMsat is set by our peer and may be 0 msat (even though it's not recommended).
        case GraphEdge(_, params, _, Some(balance)) => DirectChannel(balance, balance <= 0.msat || balance < params.htlcMinimum)
      }
      // If we have direct channels to the target, we can use them all.
      // We also count empty channels, which allows replacing them with a non-direct route (multiple hops).
      val numRoutes = routeParams.mpp.maxParts.max(directChannels.length)
      // We want to ensure that the set of routes we find have enough capacity to allow sending the total amount,
      // without excluding routes with small capacity when the total amount is small.
      val minPartAmount = routeParams.mpp.minPartAmount.max(amount / numRoutes).min(amount)
      routeParams.copy(mpp = MultiPartParams(minPartAmount, numRoutes))
    }
    findRouteInternal(g, localNodeId, targetNodeId, routeParams1.mpp.minPartAmount, maxFee, routeParams1.mpp.maxParts, extraEdges, ignoredEdges, ignoredVertices, routeParams1, currentBlockHeight) match {
      case Right(routes) =>
        // We use these shortest paths to find a set of non-conflicting HTLCs that send the total amount.
        split(amount, mutable.Queue(routes: _*), initializeUsedCapacity(pendingHtlcs), routeParams1) match {
          case Right(routes) if validateMultiPartRoute(amount, maxFee, routes, routeParams.includeLocalChannelCost) => Right(routes)
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
    val firstHopMaxAmount = route.head.maxHtlcAmount(usedCapacity.getOrElse(route.head.desc.shortChannelId, 0 msat))
    val amount = route.drop(1).foldLeft(firstHopMaxAmount) { case (amount, edge) =>
      // We compute fees going forward instead of backwards. That means we will slightly overestimate the fees of some
      // edges, but we will always stay inside the capacity bounds we computed.
      val amountMinusFees = amount - edge.fee(amount)
      val edgeMaxAmount = edge.maxHtlcAmount(usedCapacity.getOrElse(edge.desc.shortChannelId, 0 msat))
      amountMinusFees.min(edgeMaxAmount)
    }
    Route(amount.max(0 msat), route.map(graphEdgeToHop), None)
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
    route.hops.foldRight(route.amount) { case (hop, amount) =>
      usedCapacity.updateWith(hop.shortChannelId)(previous => Some(amount + previous.getOrElse(0 msat)))
      amount + hop.fee(amount)
    }
  }

  private def validateMultiPartRoute(amount: MilliSatoshi, maxFee: MilliSatoshi, routes: Seq[Route], includeLocalChannelCost: Boolean): Boolean = {
    val amountOk = routes.map(_.amount).sum == amount
    val feeOk = routes.map(_.channelFee(includeLocalChannelCost)).sum <= maxFee
    amountOk && feeOk
  }

  /**
   * Checks if we are directly connected to the target but don't have enough balance in our local channels to send the
   * requested amount. We could potentially relay the payment by using indirect routes, but since we're connected to
   * the target node it means we'd like to reach it via direct channels as much as possible.
   */
  private def isNeighborBalanceTooLow(g: DirectedGraph, source: PublicKey, target: PublicKey, amount: MilliSatoshi): Boolean = {
    val neighborEdges = g.getEdgesBetween(source, target)
    neighborEdges.nonEmpty && neighborEdges.map(e => e.balance_opt.getOrElse(e.capacity.toMilliSatoshi)).sum < amount
  }

}
