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

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Btc, MilliBtc, Satoshi, SatoshiLong}
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol.ChannelUpdate

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.collection.mutable

object Graph {

  // @formatter:off
  /**
   * The cumulative weight of a set of edges (path in the graph).
   *
   * @param cost   amount to send to the recipient + each edge's fees
   * @param length number of edges in the path
   * @param cltv   sum of each edge's cltv
   * @param weight cost multiplied by a factor based on heuristics (see [[WeightRatios]]).
   */
  case class RichWeight(cost: MilliSatoshi, length: Int, cltv: CltvExpiryDelta, weight: Double) extends Ordered[RichWeight] {
    override def compare(that: RichWeight): Int = this.weight.compareTo(that.weight)
  }
  /**
   * We use heuristics to calculate the weight of an edge based on channel age, cltv delta and capacity.
   * We favor older channels, with bigger capacity and small cltv delta.
   */
  case class WeightRatios(biasFactor: Double, cltvDeltaFactor: Double, ageFactor: Double, capacityFactor: Double, hopCostBase: MilliSatoshi, hopCostMillionths: Long) {
    require(biasFactor + cltvDeltaFactor + ageFactor + capacityFactor == 1, "The sum of heuristics ratios must be 1")
  }
  case class WeightedNode(key: PublicKey, weight: RichWeight)
  case class WeightedPath(path: Seq[GraphEdge], weight: RichWeight)
  // @formatter:on

  /**
   * This comparator must be consistent with the "equals" behavior, thus for two weighted nodes with
   * the same weight we distinguish them by their public key.
   * See https://docs.oracle.com/javase/8/docs/api/java/util/Comparator.html
   */
  object NodeComparator extends Ordering[WeightedNode] {
    override def compare(x: WeightedNode, y: WeightedNode): Int = {
      val weightCmp = x.weight.compareTo(y.weight)
      if (weightCmp == 0) x.key.toString().compareTo(y.key.toString())
      else weightCmp
    }
  }

  implicit object PathComparator extends Ordering[WeightedPath] {
    override def compare(x: WeightedPath, y: WeightedPath): Int = y.weight.compare(x.weight)
  }

  /**
   * Yen's algorithm to find the k-shortest (loop-less) paths in a graph, uses dijkstra as search algo. Is guaranteed to
   * terminate finding at most @pathsToFind paths sorted by cost (the cheapest is in position 0).
   *
   * @param graph              the graph on which will be performed the search
   * @param sourceNode         the starting node of the path we're looking for (payer)
   * @param targetNode         the destination node of the path (recipient)
   * @param amount             amount to send to the last node
   * @param ignoredEdges       channels that should be avoided
   * @param ignoredVertices    nodes that should be avoided
   * @param extraEdges         additional edges that can be used (e.g. private channels from invoices)
   * @param pathsToFind        number of distinct paths to be returned
   * @param wr                 ratios used to 'weight' edges when searching for the shortest path
   * @param currentBlockHeight the height of the chain tip (latest block)
   * @param boundaries         a predicate function that can be used to impose limits on the outcome of the search
   */
  def yenKshortestPaths(graph: DirectedGraph,
                        sourceNode: PublicKey,
                        targetNode: PublicKey,
                        amount: MilliSatoshi,
                        ignoredEdges: Set[ChannelDesc],
                        ignoredVertices: Set[PublicKey],
                        extraEdges: Set[GraphEdge],
                        pathsToFind: Int,
                        wr: WeightRatios,
                        currentBlockHeight: Long,
                        boundaries: RichWeight => Boolean): Seq[WeightedPath] = {
    // find the shortest path (k = 0)
    val targetWeight = RichWeight(amount, 0, CltvExpiryDelta(0), 0)
    val shortestPath = dijkstraShortestPath(graph, sourceNode, targetNode, ignoredEdges, ignoredVertices, extraEdges, targetWeight, boundaries, currentBlockHeight, wr)
    if (shortestPath.isEmpty) {
      return Seq.empty // if we can't even find a single path, avoid returning a Seq(Seq.empty)
    }

    case class PathWithSpur(p: WeightedPath, spurIndex: Int)
    implicit object PathWithSpurComparator extends Ordering[PathWithSpur] {
      override def compare(x: PathWithSpur, y: PathWithSpur): Int = y.p.weight.compare(x.p.weight)
    }

    var allSpurPathsFound = false
    val shortestPaths = new mutable.Queue[PathWithSpur]
    shortestPaths.enqueue(PathWithSpur(WeightedPath(shortestPath, pathWeight(sourceNode, shortestPath, amount, currentBlockHeight, wr)), 0))
    // stores the candidates for the k-th shortest path, sorted by path cost
    val candidates = new mutable.PriorityQueue[PathWithSpur]

    // main loop
    for (k <- 1 until pathsToFind) {
      if (!allSpurPathsFound) {
        val PathWithSpur(WeightedPath(prevShortestPath, _), spurIndex) = shortestPaths(k - 1)
        // for every new edge in the path, we will try to find a different path before that edge
        for (i <- spurIndex until prevShortestPath.length) {
          // select the spur node as the i-th element from the target of the previous shortest path
          val spurNode = prevShortestPath(prevShortestPath.length - 1 - i).desc.b
          // select the sub-path from the spur node to the target
          val rootPathEdges = prevShortestPath.takeRight(i)
          // we ignore all the paths that we have already fully explored in previous iterations
          // if for example the spur node is D, and we already found shortest paths ending with A->D->E and B->D->E,
          // we want to ignore the A->D and B->D edges
          // [...] --> A --+
          //               |
          // [...] --> B --+--> D --> E
          //               |
          // [...] --> C --+
          val alreadyExploredEdges = shortestPaths.collect { case p if p.p.path.takeRight(i) == rootPathEdges => p.p.path(p.p.path.length - 1 - i).desc }.toSet
          // we also want to ignore any vertex on the root path to prevent loops
          val alreadyExploredVertices = rootPathEdges.map(_.desc.b).toSet
          val rootPathWeight = pathWeight(sourceNode, rootPathEdges, amount, currentBlockHeight, wr)
          // find the "spur" path, a sub-path going from the spur node to the target avoiding previously found sub-paths
          val spurPath = dijkstraShortestPath(graph, sourceNode, spurNode, ignoredEdges ++ alreadyExploredEdges, ignoredVertices ++ alreadyExploredVertices, extraEdges, rootPathWeight, boundaries, currentBlockHeight, wr)
          if (spurPath.nonEmpty) {
            val completePath = spurPath ++ rootPathEdges
            val candidatePath = WeightedPath(completePath, pathWeight(sourceNode, completePath, amount, currentBlockHeight, wr))
            candidates.enqueue(PathWithSpur(candidatePath, i))
          }
        }
      }

      if (candidates.isEmpty) {
        // handles the case of having exhausted all possible spur paths and it's impossible to reach the target from the source
        allSpurPathsFound = true
      } else {
        // move the best candidate to the shortestPaths container
        shortestPaths.enqueue(candidates.dequeue())
      }
    }

    shortestPaths.map(_.p).toSeq
  }

  /**
   * Finds the shortest path in the graph, uses a modified version of Dijkstra's algorithm that computes the shortest
   * path from the target to the source (this is because we want to calculate the weight of the edges correctly). The
   * graph @param g is optimized for querying the incoming edges given a vertex.
   *
   * @param g                  the graph on which will be performed the search
   * @param sourceNode         the starting node of the path we're looking for (payer)
   * @param targetNode         the destination node of the path
   * @param ignoredEdges       channels that should be avoided
   * @param ignoredVertices    nodes that should be avoided
   * @param extraEdges         additional edges that can be used (e.g. private channels from invoices)
   * @param initialWeight      weight that will be applied to the target node
   * @param boundaries         a predicate function that can be used to impose limits on the outcome of the search
   * @param currentBlockHeight the height of the chain tip (latest block)
   * @param wr                 ratios used to 'weight' edges when searching for the shortest path
   */
  private def dijkstraShortestPath(g: DirectedGraph,
                                   sourceNode: PublicKey,
                                   targetNode: PublicKey,
                                   ignoredEdges: Set[ChannelDesc],
                                   ignoredVertices: Set[PublicKey],
                                   extraEdges: Set[GraphEdge],
                                   initialWeight: RichWeight,
                                   boundaries: RichWeight => Boolean,
                                   currentBlockHeight: Long,
                                   wr: WeightRatios): Seq[GraphEdge] = {
    // the graph does not contain source/destination nodes
    val sourceNotInGraph = !g.containsVertex(sourceNode) && !extraEdges.exists(_.desc.a == sourceNode)
    val targetNotInGraph = !g.containsVertex(targetNode) && !extraEdges.exists(_.desc.b == targetNode)
    if (sourceNotInGraph || targetNotInGraph) {
      return Seq.empty
    }

    // conservative estimation to avoid over-allocating memory: this is not the actual optimal size for the maps,
    // because in the worst case scenario we will insert all the vertices.
    val initialCapacity = 100
    val bestWeights = mutable.HashMap.newBuilder[PublicKey, RichWeight](initialCapacity, mutable.HashMap.defaultLoadFactor).result()
    val bestEdges = mutable.HashMap.newBuilder[PublicKey, GraphEdge](initialCapacity, mutable.HashMap.defaultLoadFactor).result()
    // NB: we want the elements with smallest weight first, hence the `reverse`.
    val toExplore = mutable.PriorityQueue.empty[WeightedNode](NodeComparator.reverse)
    val visitedNodes = mutable.HashSet[PublicKey]()

    // initialize the queue and cost array with the initial weight
    bestWeights.put(targetNode, initialWeight)
    toExplore.enqueue(WeightedNode(targetNode, initialWeight))

    var targetFound = false
    while (toExplore.nonEmpty && !targetFound) {
      // node with the smallest distance from the target
      val current = toExplore.dequeue() // O(log(n))
      targetFound = current.key == sourceNode
      if (!targetFound && !visitedNodes.contains(current.key)) {
        visitedNodes += current.key
        // build the neighbors with optional extra edges
        val neighborEdges = {
          val extraNeighbors = extraEdges.filter(_.desc.b == current.key)
          // the resulting set must have only one element per shortChannelId; we prioritize extra edges
          g.getIncomingEdgesOf(current.key).filterNot(e => extraNeighbors.exists(_.desc.shortChannelId == e.desc.shortChannelId)) ++ extraNeighbors
        }
        neighborEdges.foreach { edge =>
          val neighbor = edge.desc.a
          // NB: this contains the amount (including fees) that will need to be sent to `neighbor`, but the amount that
          // will be relayed through that edge is the one in `currentWeight`.
          val neighborWeight = addEdgeWeight(sourceNode, edge, current.weight, currentBlockHeight, wr)
          val canRelayAmount = current.weight.cost <= edge.capacity &&
            edge.balance_opt.forall(current.weight.cost <= _) &&
            edge.update.htlcMaximumMsat.forall(current.weight.cost <= _) &&
            current.weight.cost >= edge.update.htlcMinimumMsat
          if (canRelayAmount && boundaries(neighborWeight) && !ignoredEdges.contains(edge.desc) && !ignoredVertices.contains(neighbor)) {
            val previousNeighborWeight = bestWeights.getOrElse(neighbor, RichWeight(MilliSatoshi(Long.MaxValue), Int.MaxValue, CltvExpiryDelta(Int.MaxValue), Double.MaxValue))
            // if this path between neighbor and the target has a shorter distance than previously known, we select it
            if (neighborWeight.weight < previousNeighborWeight.weight) {
              // update the best edge for this vertex
              bestEdges.put(neighbor, edge)
              // add this updated node to the list for further exploration
              toExplore.enqueue(WeightedNode(neighbor, neighborWeight)) // O(1)
              // update the minimum known distance array
              bestWeights.put(neighbor, neighborWeight)
            }
          }
        }
      }
    }

    if (targetFound) {
      val edgePath = new mutable.ArrayBuffer[GraphEdge](RouteCalculation.ROUTE_MAX_LENGTH)
      var current = bestEdges.get(sourceNode)
      while (current.isDefined) {
        edgePath += current.get
        current = bestEdges.get(current.get.desc.b)
      }
      edgePath.toSeq
    } else {
      Seq.empty[GraphEdge]
    }
  }

  /**
   * Add the given edge to the path and compute the new weight.
   *
   * @param sender             node sending the payment
   * @param edge               the edge we want to cross
   * @param prev               weight of the rest of the path
   * @param currentBlockHeight the height of the chain tip (latest block).
   * @param weightRatios       ratios used to 'weight' edges when searching for the shortest path
   */
  private def addEdgeWeight(sender: PublicKey, edge: GraphEdge, prev: RichWeight, currentBlockHeight: Long, weightRatios: WeightRatios): RichWeight = {
    val totalCost = if (edge.desc.a == sender) prev.cost else addEdgeFees(edge, prev.cost)
    val fee = totalCost - prev.cost
    val hopCost = nodeFee(weightRatios.hopCostBase, weightRatios.hopCostMillionths, prev.cost)
    val totalCltv = if (edge.desc.a == sender) prev.cltv else prev.cltv + edge.update.cltvExpiryDelta
    import RoutingHeuristics._

    // Every edge is weighted by funding block height where older blocks add less weight. The window considered is 1 year.
    val channelBlockHeight = ShortChannelId.coordinates(edge.desc.shortChannelId).blockHeight
    val ageFactor = normalize(channelBlockHeight, min = currentBlockHeight - BLOCK_TIME_ONE_YEAR, max = currentBlockHeight)

    // Every edge is weighted by channel capacity, larger channels add less weight
    val edgeMaxCapacity = edge.capacity.toMilliSatoshi
    val capFactor = 1 - normalize(edgeMaxCapacity.toLong, CAPACITY_CHANNEL_LOW.toLong, CAPACITY_CHANNEL_HIGH.toLong)

    // Every edge is weighted by its cltv-delta value, normalized
    val cltvFactor = normalize(edge.update.cltvExpiryDelta.toInt, CLTV_LOW, CLTV_HIGH)

    // NB we're guaranteed to have weightRatios and factors > 0
    val factor = weightRatios.biasFactor + (cltvFactor * weightRatios.cltvDeltaFactor) + (ageFactor * weightRatios.ageFactor) + (capFactor * weightRatios.capacityFactor)
    val totalWeight = prev.weight + (fee + hopCost).toLong * factor
    RichWeight(totalCost, prev.length + 1, totalCltv, totalWeight)
  }

  /**
   * Calculate the minimum amount that the start node needs to receive to be able to forward @amountWithFees to the end
   * node.
   *
   * @param edge            the edge we want to cross
   * @param amountToForward the value that this edge will have to carry along
   * @return the new amount updated with the necessary fees for this edge
   */
  private def addEdgeFees(edge: GraphEdge, amountToForward: MilliSatoshi): MilliSatoshi = {
    amountToForward + nodeFee(edge.update.feeBaseMsat, edge.update.feeProportionalMillionths, amountToForward)
  }

  /** Validate that all edges along the path can relay the amount with fees. */
  def validatePath(path: Seq[GraphEdge], amount: MilliSatoshi): Boolean = validateReversePath(path.reverse, amount)

  @tailrec
  private def validateReversePath(path: Seq[GraphEdge], amount: MilliSatoshi): Boolean = path.headOption match {
    case None => true
    case Some(edge) =>
      val canRelayAmount = amount <= edge.capacity &&
        edge.balance_opt.forall(amount <= _) &&
        edge.update.htlcMaximumMsat.forall(amount <= _) &&
        edge.update.htlcMinimumMsat <= amount
      if (canRelayAmount) validateReversePath(path.tail, addEdgeFees(edge, amount)) else false
  }

  /**
   * Calculates the total weighted cost of a path.
   * Note that the first hop from the sender is ignored: we don't pay a routing fee to ourselves.
   *
   * @param sender             node sending the payment
   * @param path               candidate path.
   * @param amount             amount to send to the last node.
   * @param currentBlockHeight the height of the chain tip (latest block).
   * @param wr                 ratios used to 'weight' edges when searching for the shortest path
   */
  def pathWeight(sender: PublicKey, path: Seq[GraphEdge], amount: MilliSatoshi, currentBlockHeight: Long, wr: WeightRatios): RichWeight = {
    path.foldRight(RichWeight(amount, 0, CltvExpiryDelta(0), 0)) { (edge, prev) =>
      addEdgeWeight(sender, edge, prev, currentBlockHeight, wr)
    }
  }

  object RoutingHeuristics {

    // Number of blocks in one year
    val BLOCK_TIME_ONE_YEAR = 365 * 24 * 6

    // Low/High bound for channel capacity
    val CAPACITY_CHANNEL_LOW = MilliBtc(1).toMilliSatoshi
    val CAPACITY_CHANNEL_HIGH = Btc(1).toMilliSatoshi

    // Low/High bound for CLTV channel value
    val CLTV_LOW = 9
    val CLTV_HIGH = 2016

    /**
     * Normalize the given value between (0, 1). If the @param value is outside the min/max window we flatten it to something very close to the
     * extremes but always bigger than zero so it's guaranteed to never return zero
     */
    def normalize(value: Double, min: Double, max: Double): Double = {
      if (value <= min) 0.00001D
      else if (value > max) 0.99999D
      else (value - min) / (max - min)
    }

  }

  object GraphStructure {

    /**
     * Representation of an edge of the graph
     *
     * @param desc        channel description
     * @param update      channel info
     * @param capacity    channel capacity
     * @param balance_opt (optional) available balance that can be sent through this edge
     */
    case class GraphEdge(desc: ChannelDesc, update: ChannelUpdate, capacity: Satoshi, balance_opt: Option[MilliSatoshi]) {

      def maxHtlcAmount(reservedCapacity: MilliSatoshi): MilliSatoshi = Seq(
        balance_opt.map(balance => balance - reservedCapacity),
        update.htlcMaximumMsat,
        Some(capacity.toMilliSatoshi - reservedCapacity)
      ).flatten.min.max(0 msat)

      def fee(amount: MilliSatoshi): MilliSatoshi = nodeFee(update.feeBaseMsat, update.feeProportionalMillionths, amount)

    }

    /** A graph data structure that uses an adjacency list, stores the incoming edges of the neighbors */
    case class DirectedGraph(private val vertices: Map[PublicKey, List[GraphEdge]]) {

      def addEdge(d: ChannelDesc, u: ChannelUpdate, capacity: Satoshi, balance_opt: Option[MilliSatoshi] = None): DirectedGraph = addEdge(GraphEdge(d, u, capacity, balance_opt))

      def addEdges(edges: Iterable[GraphEdge]): DirectedGraph = edges.foldLeft(this)((acc, edge) => acc.addEdge(edge))

      /**
       * Adds an edge to the graph. If one of the two vertices is not found it will be created.
       *
       * @param edge the edge that is going to be added to the graph
       * @return a new graph containing this edge
       */
      def addEdge(edge: GraphEdge): DirectedGraph = {
        val vertexIn = edge.desc.a
        val vertexOut = edge.desc.b
        val toAdd = edge.copy(capacity = DirectedGraph.getCapacity(edge.capacity, edge.update))
        // the graph is allowed to have multiple edges between the same vertices but only one per channel
        if (containsEdge(toAdd.desc)) {
          removeEdge(toAdd.desc).addEdge(toAdd) // the recursive call will have the original params
        } else {
          val withVertices = addVertex(vertexIn).addVertex(vertexOut)
          DirectedGraph(withVertices.vertices.updated(vertexOut, toAdd +: withVertices.vertices(vertexOut)))
        }
      }

      /**
       * Removes the edge corresponding to the given pair channel-desc/channel-update,
       * NB: this operation does NOT remove any vertex
       *
       * @param desc the channel description associated to the edge that will be removed
       * @return a new graph without this edge
       */
      def removeEdge(desc: ChannelDesc): DirectedGraph = {
        if (containsEdge(desc)) {
          DirectedGraph(vertices.updated(desc.b, vertices(desc.b).filterNot(_.desc == desc)))
        } else {
          this
        }
      }

      def removeEdges(descList: Iterable[ChannelDesc]): DirectedGraph = {
        descList.foldLeft(this)((acc, edge) => acc.removeEdge(edge))
      }

      /**
       * @return For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
       */
      def getEdge(edge: GraphEdge): Option[GraphEdge] = getEdge(edge.desc)

      def getEdge(desc: ChannelDesc): Option[GraphEdge] = {
        vertices.get(desc.b).flatMap { adj =>
          adj.find(e => e.desc.shortChannelId == desc.shortChannelId && e.desc.a == desc.a)
        }
      }

      /**
       * @param keyA the key associated with the starting vertex
       * @param keyB the key associated with the ending vertex
       * @return all the edges going from keyA --> keyB (there might be more than one if there are multiple channels)
       */
      def getEdgesBetween(keyA: PublicKey, keyB: PublicKey): Seq[GraphEdge] = {
        vertices.get(keyB) match {
          case None => Seq.empty
          case Some(adj) => adj.filter(e => e.desc.a == keyA)
        }
      }

      /**
       * @param keyB the key associated with the target vertex
       * @return all edges incoming to that vertex
       */
      def getIncomingEdgesOf(keyB: PublicKey): Seq[GraphEdge] = {
        vertices.getOrElse(keyB, List.empty)
      }

      /**
       * Removes a vertex and all its associated edges (both incoming and outgoing)
       */
      def removeVertex(key: PublicKey): DirectedGraph = {
        DirectedGraph(removeEdges(getIncomingEdgesOf(key).map(_.desc)).vertices - key)
      }

      /**
       * Adds a new vertex to the graph, starting with no edges
       */
      def addVertex(key: PublicKey): DirectedGraph = {
        vertices.get(key) match {
          case None => DirectedGraph(vertices + (key -> List.empty))
          case _ => this
        }
      }

      /**
       * Note this operation will traverse all edges in the graph (expensive)
       *
       * @return a list of the outgoing edges of the given vertex. If the vertex doesn't exists an empty list is returned.
       */
      def edgesOf(key: PublicKey): Seq[GraphEdge] = {
        edgeSet().filter(_.desc.a == key).toSeq
      }

      /**
       * @return the set of all the vertices in this graph
       */
      def vertexSet(): Set[PublicKey] = vertices.keySet

      /**
       * @return an iterator of all the edges in this graph
       */
      def edgeSet(): Iterable[GraphEdge] = vertices.values.flatten

      /**
       * @return true if this graph contain a vertex with this key, false otherwise
       */
      def containsVertex(key: PublicKey): Boolean = vertices.contains(key)

      /**
       * @return true if this edge desc is in the graph. For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
       */
      def containsEdge(desc: ChannelDesc): Boolean = {
        vertices.get(desc.b) match {
          case None => false
          case Some(adj) => adj.exists(neighbor => neighbor.desc.shortChannelId == desc.shortChannelId && neighbor.desc.a == desc.a)
        }
      }

      def prettyPrint(): String = {
        vertices.foldLeft("") { case (acc, (vertex, adj)) =>
          acc + s"[${vertex.toString().take(5)}]: ${adj.map("-> " + _.desc.b.toString().take(5))} \n"
        }
      }
    }

    object DirectedGraph {

      // @formatter:off
      def apply(): DirectedGraph = new DirectedGraph(Map())
      def apply(key: PublicKey): DirectedGraph = new DirectedGraph(Map(key -> List.empty))
      def apply(edge: GraphEdge): DirectedGraph = DirectedGraph().addEdge(edge)
      def apply(edges: Seq[GraphEdge]): DirectedGraph = DirectedGraph().addEdges(edges)
      // @formatter:on

      /**
       * This is the recommended way of initializing the network graph (from a public network DB).
       * We only use public channels at first; private channels will be added one by one as they come online, and removed
       * as they go offline.
       * Private channels may be used to route payments, but most of the time, they will be the first or last hop.
       *
       * @param channels map of all known public channels in the network.
       */
      def makeGraph(channels: SortedMap[ShortChannelId, PublicChannel]): DirectedGraph = {
        // initialize the map with the appropriate size to avoid resizing during the graph initialization
        val mutableMap = new mutable.HashMap[PublicKey, List[GraphEdge]](initialCapacity = channels.size + 1, mutable.HashMap.defaultLoadFactor)

        // add all the vertices and edges in one go
        channels.values.foreach { channel =>
          channel.update_1_opt.foreach { u1 =>
            val desc1 = Router.getDesc(u1, channel.ann)
            addDescToMap(desc1, u1, channel.capacity, channel.meta_opt.map(_.balance1))
          }
          channel.update_2_opt.foreach { u2 =>
            val desc2 = Router.getDesc(u2, channel.ann)
            addDescToMap(desc2, u2, channel.capacity, channel.meta_opt.map(_.balance2))
          }
        }

        def addDescToMap(desc: ChannelDesc, u: ChannelUpdate, capacity: Satoshi, balance_opt: Option[MilliSatoshi]): Unit = {
          mutableMap.put(desc.b, GraphEdge(desc, u, getCapacity(capacity, u), balance_opt) +: mutableMap.getOrElse(desc.b, List.empty[GraphEdge]))
          if (!mutableMap.contains(desc.a)) {
            mutableMap += desc.a -> List.empty[GraphEdge]
          }
        }

        new DirectedGraph(mutableMap.toMap)
      }

      def graphEdgeToHop(graphEdge: GraphEdge): ChannelHop = ChannelHop(graphEdge.desc.a, graphEdge.desc.b, graphEdge.update)

      /** We need a strictly positive capacity, otherwise path-finding will ignore the edge. */
      def getCapacity(capacity: Satoshi, update: ChannelUpdate): Satoshi = {
        if (capacity > 0.sat) {
          capacity
        } else {
          update.htlcMaximumMsat.map(_.truncateToSatoshi + 1.sat).getOrElse(RoutingHeuristics.CAPACITY_CHANNEL_HIGH.truncateToSatoshi)
        }
      }
    }

  }

}
