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

import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.BlockHeight
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.RouteCalculationSpec.makeEdge
import fr.acinq.eclair.router.graph.structure.DirectedGraph
import fr.acinq.eclair.MilliSatoshiLong
import org.scalatest.funsuite.AnyFunSuite
import TestNodeKeys._
import fr.acinq.eclair.router.graph.path.{HeuristicsConstants, WeightRatios}

class ShortestPathFinderSpec extends AnyFunSuite {

  val shortestPathFinder = new ShortestPathFinder()

  test("amount with fees larger than channel capacity for C->D") {
    /*
    The channel C -> D is just large enough for the payment to go through but when adding the channel fee it becomes too big.
    A --> B --> C <-> D
                 \   /
                  \ /
                   E
    This tests that the success probability for the channel C -> D is computed properly and is positive.
    */
    val edgeAB = makeEdge(1L, a, b, 10001 msat, 0, capacity = 200000 sat)
    val edgeBC = makeEdge(2L, b, c, 10000 msat, 0, capacity = 200000 sat)
    val edgeCD = makeEdge(3L, c, d, 20001 msat, 0, capacity = 100011 sat)
    val edgeDC = makeEdge(4L, d, c, 1 msat, 0, capacity = 300000 sat)
    val edgeCE = makeEdge(5L, c, e, 10 msat, 0, capacity = 200000 sat)
    val edgeDE = makeEdge(6L, d, e, 9 msat, 0, capacity = 200000 sat)
    val graph = DirectedGraph(Seq(edgeAB, edgeBC, edgeCD, edgeDC, edgeCE, edgeDE))

    val path :: Nil = shortestPathFinder.yenKshortestPaths(graph, a, e, 100000000 msat,
      Set.empty, Set.empty, Set.empty, 1,
      Right(HeuristicsConstants(1.0E-8, RelayFees(2000 msat, 500), RelayFees(50 msat, 20), useLogProbability = true)),
      BlockHeight(714930), _ => true, includeLocalChannelCost = true)
    assert(path.path == Seq(edgeAB, edgeBC, edgeCE))
  }

  test("fees less along C->D->E than C->E") {
    /*
    The channel C -> D is large enough for the payment and associated fees to go through, but C -> E is not.
    A --> B --> C <-> D
                 \   /
                  \ /
                   E
    */
    val edgeAB = makeEdge(1L, a, b, 10001 msat, 0, capacity = 200000 sat)
    val edgeBC = makeEdge(2L, b, c, 10000 msat, 0, capacity = 200000 sat)
    val edgeCD = makeEdge(3L, c, d, 10001 msat, 0, capacity = 200000 sat)
    val edgeDC = makeEdge(4L, d, c, 10 msat, 0, capacity = 200000 sat)
    val edgeCE = makeEdge(5L, c, e, 10003 msat, 0, capacity = 200000 sat)
    val edgeDE = makeEdge(6L, d, e, 1 msat, 0, capacity = 200000 sat)
    val graph = DirectedGraph(Seq(edgeAB, edgeBC, edgeCD, edgeDC, edgeCE, edgeDE))

    val paths = shortestPathFinder.yenKshortestPaths(graph, a, e, 90000000 msat,
      Set.empty, Set.empty, Set.empty, 2,
      Left(WeightRatios(1, 0, 0, 0, RelayFees(0 msat, 0))),
      BlockHeight(714930), _ => true, includeLocalChannelCost = true)

    assert(paths.length == 2)
    assert(paths.head.path == Seq(edgeAB, edgeBC, edgeCD, edgeDE))
    assert(paths(1).path == Seq(edgeAB, edgeBC, edgeCE))
  }

  test("Path C->D->E selected because amount too great for C->E capacity") {
    /*
    The channel C -> D -> E is a valid path, but C -> E is not.
    A --> B --> C <-> D
                 \   /
                  \ /
                   E
    */
    val edgeAB = makeEdge(1L, a, b, 10001 msat, 0, capacity = 200000 sat)
    val edgeBC = makeEdge(2L, b, c, 10000 msat, 0, capacity = 200000 sat)
    val edgeCD = makeEdge(3L, c, d, 20001 msat, 0, capacity = 200000 sat)
    val edgeDC = makeEdge(4L, d, c, 10 msat, 0, capacity = 200000 sat)
    val edgeCE = makeEdge(5L, c, e, 10003 msat, 0, capacity = 10000 sat)
    val edgeDE = makeEdge(6L, d, e, 1 msat, 0, capacity = 200000 sat)
    val graph = DirectedGraph(Seq(edgeAB, edgeBC, edgeCD, edgeDC, edgeCE, edgeDE))

    val paths = shortestPathFinder.yenKshortestPaths(graph, a, e, 90000000 msat,
      Set.empty, Set.empty, Set.empty, 2,
      Left(path.WeightRatios(1, 0, 0, 0, RelayFees(0 msat, 0))),
      BlockHeight(714930), _ => true, includeLocalChannelCost = true)

    // Even though paths to find is 2, we only find 1 because that is all the valid paths that there are.
    assert(paths.length == 1)
    assert(paths.head.path == Seq(edgeAB, edgeBC, edgeCD, edgeDE))
  }

  /**
   * Find all the shortest paths using the example described in
   * https://en.wikipedia.org/wiki/Yen%27s_algorithm#:~:text=Yen's%20algorithm
   */
  test("all shortest paths are found") {
    // There will be 3 shortest paths.
    // Edge capacities are set to be the same so that only feeBase will affect the RichWeight.
    //  C --> D --> F
    //   \    ^    /|\
    //    \   |  /  | \
    //     \ | /    |  \
    //       E----->G-->H
    val edgeCD = makeEdge(1L, c, d, 301 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeDF = makeEdge(2L, d, f, 401 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeCE = makeEdge(3L, c, e, 201 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeED = makeEdge(4L, e, d, 101 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeEF = makeEdge(5L, e, f, 201 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeFG = makeEdge(6L, f, g, 201 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeFH = makeEdge(7L, f, h, 101 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeEG = makeEdge(8L, e, g, 301 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeGH = makeEdge(9L, g, h, 201 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val graph = DirectedGraph(Seq(edgeCD, edgeDF, edgeCE, edgeED, edgeEF, edgeFG, edgeFH, edgeEG, edgeGH))

    val paths = shortestPathFinder.yenKshortestPaths(graph, c, h, 10000000 msat,
      Set.empty, Set.empty, Set.empty, 3,
      Left(path.WeightRatios(1, 0, 0, 0, RelayFees(0 msat, 0))),
      BlockHeight(714930), _ => true, includeLocalChannelCost = true)
    assert(paths.length == 3)
    assert(paths.head.path == Seq(edgeCE, edgeEF, edgeFH))
    assert(paths(1).path == Seq(edgeCE, edgeEG, edgeGH))
    assert(paths(2).path == Seq(edgeCD, edgeDF, edgeFH))
  }

}
