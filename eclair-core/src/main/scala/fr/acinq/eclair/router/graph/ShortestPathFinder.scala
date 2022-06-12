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

package fr.acinq.eclair.router.graph

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong}
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta}
import fr.acinq.eclair.router.RouteCalculation
import fr.acinq.eclair.router.Router.ChannelDesc
import fr.acinq.eclair.router.graph.path.{HeuristicsConstants, Path, RichWeight, WeightRatios}
import fr.acinq.eclair.router.graph.path.Path.{InfiniteLoop, NodeComparator, WeightedNode, WeightedPath}
import fr.acinq.eclair.router.graph.structure.{DirectedGraph, GraphEdge}

import scala.collection.mutable

class ShortestPathFinder {

  /**
   * Yen's algorithm to find the k-shortest (loop-less) paths in a graph, uses dijkstra as search algo. Is guaranteed to
   * terminate finding at most @pathsToFind paths sorted by cost (the cheapest is in position 0).
   *
   * @param graph                   the graph on which will be performed the search
   * @param sourceNode              the starting node of the path we're looking for (payer)
   * @param targetNode              the destination node of the path (recipient)
   * @param amount                  amount to send to the last node
   * @param ignoredEdges            channels that should be avoided
   * @param ignoredVertices         nodes that should be avoided
   * @param extraEdges              additional edges that can be used (e.g. private channels from invoices)
   * @param pathsToFind             number of distinct paths to be returned
   * @param wr                      ratios used to 'weight' edges when searching for the shortest path
   * @param currentBlockHeight      the height of the chain tip (latest block)
   * @param boundaries              a predicate function that can be used to impose limits on the outcome of the search
   * @param includeLocalChannelCost if the path is for relaying and we need to include the cost of the local channel
   */
  def yenKshortestPaths(graph: DirectedGraph,
                        sourceNode: PublicKey,
                        targetNode: PublicKey,
                        amount: MilliSatoshi,
                        ignoredEdges: Set[ChannelDesc],
                        ignoredVertices: Set[PublicKey],
                        extraEdges: Set[GraphEdge],
                        pathsToFind: Int,
                        wr: Either[WeightRatios, HeuristicsConstants],
                        currentBlockHeight: BlockHeight,
                        boundaries: RichWeight => Boolean,
                        includeLocalChannelCost: Boolean): Seq[WeightedPath] = {
    // find the shortest path (k = 0)
    val targetWeight = RichWeight(amount, 0, CltvExpiryDelta(0), 1.0, 0 msat, 0 msat, 0.0)
    val shortestPath = dijkstraShortestPath(graph, sourceNode, targetNode, ignoredEdges, ignoredVertices, extraEdges, targetWeight, boundaries, currentBlockHeight, wr, includeLocalChannelCost)
    if (shortestPath.isEmpty) {
      return Seq.empty // if we can't even find a single path, avoid returning a Seq(Seq.empty)
    }

    case class PathWithSpur(p: WeightedPath, spurIndex: Int)
    implicit object PathWithSpurComparator extends Ordering[PathWithSpur] {
      override def compare(x: PathWithSpur, y: PathWithSpur): Int = y.p.weight.compare(x.p.weight)
    }

    var allSpurPathsFound = false
    val shortestPaths = new mutable.Queue[PathWithSpur]
    shortestPaths.enqueue(PathWithSpur(WeightedPath(shortestPath, Path.pathWeight(sourceNode, shortestPath, amount, currentBlockHeight, wr, includeLocalChannelCost)), 0))
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
          // if for example, the spur node is D, and we already found shortest paths ending with A->D->E and B->D->E,
          // then we want to ignore the A->D and B->D edges
          // [...] --> A --+
          //               |
          // [...] --> B --+--> D --> E
          //               |
          // [...] --> C --+
          val alreadyExploredEdges = shortestPaths.collect { case p if p.p.path.takeRight(i) == rootPathEdges => p.p.path(p.p.path.length - 1 - i).desc }.toSet
          // we also want to ignore any vertex on the root path to prevent loops
          val alreadyExploredVertices = rootPathEdges.map(_.desc.b).toSet
          val rootPathWeight = Path.pathWeight(sourceNode, rootPathEdges, amount, currentBlockHeight, wr, includeLocalChannelCost)
          // find the "spur" path, a sub-path going from the spur node to the target avoiding previously found sub-paths
          val spurPath = dijkstraShortestPath(graph, sourceNode, spurNode, ignoredEdges ++ alreadyExploredEdges, ignoredVertices ++ alreadyExploredVertices, extraEdges, rootPathWeight, boundaries, currentBlockHeight, wr, includeLocalChannelCost)
          if (spurPath.nonEmpty) {
            val completePath = spurPath ++ rootPathEdges
            val candidatePath = WeightedPath(completePath, Path.pathWeight(sourceNode, completePath, amount, currentBlockHeight, wr, includeLocalChannelCost))
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
   * @param g                       the graph on which will be performed the search
   * @param sourceNode              the starting node of the path we're looking for (payer)
   * @param targetNode              the destination node of the path
   * @param ignoredEdges            channels that should be avoided
   * @param ignoredVertices         nodes that should be avoided
   * @param extraEdges              additional edges that can be used (e.g. private channels from invoices)
   * @param initialWeight           weight that will be applied to the target node
   * @param boundaries              a predicate function that can be used to impose limits on the outcome of the search
   * @param currentBlockHeight      the height of the chain tip (latest block)
   * @param wr                      ratios used to 'weight' edges when searching for the shortest path
   * @param includeLocalChannelCost if the path is for relaying and we need to include the cost of the local channel
   */
  private def dijkstraShortestPath(g: DirectedGraph,
                                   sourceNode: PublicKey,
                                   targetNode: PublicKey,
                                   ignoredEdges: Set[ChannelDesc],
                                   ignoredVertices: Set[PublicKey],
                                   extraEdges: Set[GraphEdge],
                                   initialWeight: RichWeight,
                                   boundaries: RichWeight => Boolean,
                                   currentBlockHeight: BlockHeight,
                                   wr: Either[WeightRatios, HeuristicsConstants],
                                   includeLocalChannelCost: Boolean): Seq[GraphEdge] = {
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
          if (current.weight.amount <= edge.capacity &&
            edge.balance_opt.forall(current.weight.amount <= _) &&
            edge.params.htlcMaximum_opt.forall(current.weight.amount <= _) &&
            current.weight.amount >= edge.params.htlcMinimum &&
            !ignoredEdges.contains(edge.desc) &&
            !ignoredVertices.contains(neighbor)) {
            // NB: this contains the amount (including fees) that will need to be sent to `neighbor`, but the amount that
            // will be relayed through that edge is the one in `currentWeight`.
            val neighborWeight = Path.addEdgeWeight(sourceNode, edge, current.weight, currentBlockHeight, wr, includeLocalChannelCost)
            if (boundaries(neighborWeight)) {
              val richWeight = RichWeight(MilliSatoshi(Long.MaxValue), Int.MaxValue, CltvExpiryDelta(Int.MaxValue), 0.0, MilliSatoshi(Long.MaxValue), MilliSatoshi(Long.MaxValue), Double.MaxValue)
              val previousNeighborWeight = bestWeights.getOrElse(neighbor, richWeight)
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
    }

    if (targetFound) {
      val edgePath = new mutable.ArrayBuffer[GraphEdge](RouteCalculation.ROUTE_MAX_LENGTH)
      var current = bestEdges.get(sourceNode)
      while (current.isDefined) {
        edgePath += current.get
        current = bestEdges.get(current.get.desc.b)
        if (edgePath.length > RouteCalculation.ROUTE_MAX_LENGTH) {
          throw InfiniteLoop(edgePath.toSeq)
        }
      }
      edgePath.toSeq
    } else {
      Seq.empty[GraphEdge]
    }
  }

}
