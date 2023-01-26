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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{HeuristicsConstants, WeightRatios, yenKshortestPaths}
import fr.acinq.eclair.router.RouteCalculationSpec._
import fr.acinq.eclair.router.Router.ChannelDesc
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, ShortChannelId}
import org.scalactic.Tolerance.convertNumericToPlusOrMinusWrapper
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

class GraphSpec extends AnyFunSuite {

  val (a, b, c, d, e, f, g, h) = (
    PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), //a
    PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), //b
    PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), //c
    PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), //d
    PublicKey(hex"02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"), //e
    PublicKey(hex"03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc"), //f
    PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), //g
    PublicKey(hex"03bfddd2253b42fe12edd37f9071a3883830ed61a4bc347eeac63421629cf032b5") //h
  )

  // +---- D -------+
  // |              |
  // A --- B ------ C
  //       |        |
  //       +--- E --+
  private def makeTestGraph() =
    DirectedGraph().addEdges(Seq(
      makeEdge(1L, a, b, 0 msat, 0),
      makeEdge(2L, b, c, 0 msat, 0),
      makeEdge(3L, a, d, 0 msat, 0),
      makeEdge(4L, d, c, 0 msat, 0),
      makeEdge(5L, c, e, 0 msat, 0),
      makeEdge(6L, b, e, 0 msat, 0)
    ))

  test("instantiate a graph, with vertices and then add edges") {
    val graph = DirectedGraph(a)
      .addVertex(b)
      .addVertex(c)
      .addVertex(d)
      .addVertex(e)

    assert(graph.containsVertex(a) && graph.containsVertex(e))
    assert(graph.vertexSet().size == 5)

    val otherGraph = graph.addVertex(a) // adding the same vertex twice!
    assert(otherGraph.vertexSet().size == 5)

    // add some edges to the graph
    val edgeAB = makeEdge(1L, a, b, 0 msat, 0)
    val edgeBC = makeEdge(2L, b, c, 0 msat, 0)
    val edgeAD = makeEdge(3L, a, d, 0 msat, 0)
    val edgeDC = makeEdge(4L, d, c, 0 msat, 0)
    val edgeCE = makeEdge(5L, c, e, 0 msat, 0)

    val graphWithEdges = graph
      .addEdge(edgeAB)
      .addEdge(edgeAD)
      .addEdge(edgeBC)
      .addEdge(edgeDC)
      .addEdge(edgeCE)

    assert(graphWithEdges.edgesOf(a).size == 2)
    assert(graphWithEdges.edgesOf(b).size == 1)
    assert(graphWithEdges.edgesOf(c).size == 1)
    assert(graphWithEdges.edgesOf(d).size == 1)
    assert(graphWithEdges.edgesOf(e).size == 0)

    val withRemovedEdges = graphWithEdges.removeEdge(edgeAD.desc)
    assert(withRemovedEdges.edgesOf(d).size == 1)
  }

  test("instantiate a graph adding edges only") {
    val edgeAB = makeEdge(1L, a, b, 0 msat, 0)
    val edgeBC = makeEdge(2L, b, c, 0 msat, 0)
    val edgeAD = makeEdge(3L, a, d, 0 msat, 0)
    val edgeDC = makeEdge(4L, d, c, 0 msat, 0)
    val edgeCE = makeEdge(5L, c, e, 0 msat, 0)
    val edgeBE = makeEdge(6L, b, e, 0 msat, 0)

    val graph = DirectedGraph(edgeAB)
      .addEdge(edgeAD)
      .addEdge(edgeBC)
      .addEdge(edgeDC)
      .addEdge(edgeCE)
      .addEdge(edgeBE)

    assert(graph.vertexSet().size == 5)
    assert(graph.edgesOf(c).size == 1)
    assert(graph.getIncomingEdgesOf(c).size == 2)
    assert(graph.edgeSet().size == 6)
  }

  test("containsEdge should return true if the graph contains that edge, false otherwise") {
    val graph = DirectedGraph(Seq(
      makeEdge(1L, a, b, 0 msat, 0),
      makeEdge(2L, b, c, 0 msat, 0),
      makeEdge(3L, c, d, 0 msat, 0),
      makeEdge(4L, d, e, 0 msat, 0)
    ))

    assert(graph.containsEdge(descFromNodes(1, a, b)))
    assert(!graph.containsEdge(descFromNodes(5, b, a)))
    assert(graph.containsEdge(descFromNodes(2, b, c)))
    assert(graph.containsEdge(descFromNodes(3, c, d)))
    assert(graph.containsEdge(descFromNodes(4, d, e)))
    assert(graph.containsEdge(ChannelDesc(ShortChannelId(4L), d, e))) // by channel desc
    assert(!graph.containsEdge(ChannelDesc(ShortChannelId(4L), a, g))) // by channel desc
    assert(!graph.containsEdge(descFromNodes(50, a, e)))
    assert(!graph.containsEdge(descFromNodes(66, c, f))) // f isn't even in the graph
  }

  test("should remove a set of edges") {
    val graph = makeTestGraph()

    val edgeBE = makeEdge(6L, b, e, 0 msat, 0)
    val edgeCE = makeEdge(5L, c, e, 0 msat, 0)
    val edgeAD = makeEdge(3L, a, d, 0 msat, 0)
    val edgeDC = makeEdge(4L, d, c, 0 msat, 0)

    assert(graph.edgeSet().size == 6)
    assert(graph.containsEdge(edgeBE.desc))

    val withRemovedEdge = graph.removeEdge(edgeBE.desc)
    assert(withRemovedEdge.edgeSet().size == 5)

    val withRemovedList = graph.removeEdges(Seq(edgeAD.desc, edgeDC.desc))
    assert(withRemovedList.edgeSet().size == 4)

    val withoutAnyIncomingEdgeInE = graph.removeEdges(Seq(edgeBE.desc, edgeCE.desc))
    assert(withoutAnyIncomingEdgeInE.containsVertex(e))
    assert(withoutAnyIncomingEdgeInE.edgesOf(e).isEmpty)
  }

  test("should get an edge given two vertices") {
    // contains an edge A --> B
    val graph = DirectedGraph(Seq(
      makeEdge(1L, a, b, 0 msat, 0),
      makeEdge(2L, b, c, 0 msat, 0)
    ))

    val edgesAB = graph.getEdgesBetween(a, b)
    assert(edgesAB.size == 1) // there should be an edge a --> b
    assert(edgesAB.head.desc.a == a)
    assert(edgesAB.head.desc.b == b)

    val bIncoming = graph.getIncomingEdgesOf(b)
    assert(bIncoming.size == 1)
    assert(bIncoming.exists(_.desc.a == a)) // there should be an edge a --> b
    assert(bIncoming.exists(_.desc.b == b))

    val bOutgoing = graph.edgesOf(b)
    assert(bOutgoing.size == 1)
    assert(bOutgoing.exists(_.desc.a == b))
    assert(bOutgoing.exists(_.desc.b == c))
  }

  test("there can be multiple edges between the same vertices") {
    val graph = makeTestGraph()
    // A --> B , A --> D
    assert(graph.edgesOf(a).size == 2)

    // now add a new edge a -> b but with a different channel update and a different ShortChannelId
    val newEdgeForNewChannel = makeEdge(15L, a, b, 20 msat, 0)
    val mutatedGraph = graph.addEdge(newEdgeForNewChannel)

    assert(mutatedGraph.edgesOf(a).size == 3)

    // if the ShortChannelId is the same we replace the edge and the update, this edge have an update with a different 'feeBaseMsat'
    val edgeForTheSameChannel = makeEdge(15L, a, b, 30 msat, 0)
    val mutatedGraph2 = mutatedGraph.addEdge(edgeForTheSameChannel)

    assert(mutatedGraph2.edgesOf(a).size == 3) // A --> B , A --> B , A --> D
    assert(mutatedGraph2.getEdgesBetween(a, b).size == 2)
    assert(mutatedGraph2.getEdge(edgeForTheSameChannel).get.params.relayFees.feeBase == 30.msat)
  }

  test("remove a vertex with incoming edges and check those edges are removed too") {
    val graph = makeTestGraph()
    assert(graph.vertexSet().size == 5)
    assert(graph.containsVertex(e))
    assert(graph.containsEdge(descFromNodes(5, c, e)))
    assert(graph.containsEdge(descFromNodes(6, b, e)))

    // E has 2 incoming edges
    val withoutE = graph.removeVertex(e)

    assert(withoutE.vertexSet().size == 4)
    assert(!withoutE.containsVertex(e))
    assert(!withoutE.containsEdge(descFromNodes(5, c, e)))
    assert(!withoutE.containsEdge(descFromNodes(6, b, e)))
  }

  test("update edge balance") {
    val edgeAB = makeEdge(1L, a, b, 0 msat, 0, capacity = 1500 sat, balance_opt = Some(300000 msat))
    val edgeBC = makeEdge(2L, b, c, 0 msat, 0, capacity = 500 sat, balance_opt = None)
    val edgeAD = makeEdge(3L, a, d, 0 msat, 0, capacity = 1000 sat, balance_opt = Some(50000 msat))
    val edgeDC = makeEdge(4L, d, c, 0 msat, 0, capacity = 800 sat, balance_opt = Some(50000 msat))
    val graph = DirectedGraph(Seq(edgeAB, edgeAD, edgeBC, edgeDC))

    assert(graph.edgesOf(a).toSet == Set(edgeAB, edgeAD))
    assert(graph.getIncomingEdgesOf(a) == Nil)
    assert(graph.edgesOf(c) == Nil)
    assert(graph.getIncomingEdgesOf(c).toSet == Set(edgeBC, edgeDC))

    val edgeAB1 = edgeAB.copy(balance_opt = Some(200000 msat))
    val edgeBC1 = edgeBC.copy(balance_opt = Some(150000 msat))
    val graph1 = graph.addEdge(edgeAB1).addEdge(edgeBC1)

    assert(graph1.edgesOf(a).toSet == Set(edgeAB1, edgeAD))
    assert(graph1.getIncomingEdgesOf(a) == Nil)
    assert(graph1.edgesOf(c) == Nil)
    assert(graph1.getIncomingEdgesOf(c).toSet == Set(edgeBC1, edgeDC))
  }

  def descFromNodes(shortChannelId: Long, a: PublicKey, b: PublicKey): ChannelDesc = makeEdge(shortChannelId, a, b, 0 msat, 0).desc

  def edgeFromNodes(shortChannelId: Long, a: PublicKey, b: PublicKey): GraphEdge = makeEdge(shortChannelId, a, b, 0 msat, 0)

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

    val path :: Nil = yenKshortestPaths(graph, a, e, 100000000 msat,
      Set.empty, Set.empty, Set.empty, 1,
      Right(HeuristicsConstants(1.0E-8, RelayFees(2000 msat, 500), RelayFees(50 msat, 20), useLogProbability = true)),
      BlockHeight(714930), _ => true, includeLocalChannelCost = true)
    assert(path.path == Seq(edgeAB, edgeBC, edgeCE))
  }

  test("fees less along C->D->E than C->E") {
    /*
    The channel going through C -> D -> E  (fee = 1001 + 1 = 1002) is considered shorter than going
    through C -> E (fee = 1003) because the fees are less.
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

    val paths = yenKshortestPaths(graph, a, e, 90000000 msat,
      Set.empty, Set.empty, Set.empty, 2,
      Left(WeightRatios(1, 0, 0, 0, RelayFees(0 msat, 0))),
      BlockHeight(714930), _ => true, includeLocalChannelCost = true)

    assert(paths.length == 2)
    assert(paths(0).path == Seq(edgeAB, edgeBC, edgeCD, edgeDE))
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

    val paths = yenKshortestPaths(graph, a, e, 90000000 msat,
      Set.empty, Set.empty, Set.empty, 2,
      Left(WeightRatios(1, 0, 0, 0, RelayFees(0 msat, 0))),
      BlockHeight(714930), _ => true, includeLocalChannelCost = true)

    // Even though paths to find is 2, we only find 1 because that is all the valid paths that there are.
    assert(paths.length == 1)
    assert(paths.head.path == Seq(edgeAB, edgeBC, edgeCD, edgeDE))
  }

  /**
   * Find all the shortest paths using the example described in
   * https://en.wikipedia.org/wiki/Yen's_algorithm#Example
   */
  test("all shortest paths are found") {
    // There will be 3 shortest paths.
    // Edge capacities are set to be the same so that only feeBase will affect the RichWeight.
    // C --> D --> F
    //   \   ^   / | \
    //    \  |  /  |  \
    //     \ | /   |   \
    //       E --> G --> H
    val edgeCD = makeEdge(1L, c, d, 3 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeDF = makeEdge(2L, d, f, 4 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeCE = makeEdge(3L, c, e, 2 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeED = makeEdge(4L, e, d, 1 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeEF = makeEdge(5L, e, f, 2 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeFG = makeEdge(6L, f, g, 2 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeFH = makeEdge(7L, f, h, 1 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeEG = makeEdge(8L, e, g, 3 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeGH = makeEdge(9L, g, h, 2 msat, 0, capacity = 100000 sat, minHtlc = 1000 msat)
    val graph = DirectedGraph(Seq(edgeCD, edgeDF, edgeCE, edgeED, edgeEF, edgeFG, edgeFH, edgeEG, edgeGH))

    val paths = yenKshortestPaths(graph, c, h, 10000000 msat,
      Set.empty, Set.empty, Set.empty, 3,
      Left(WeightRatios(1, 0, 0, 0, RelayFees(0 msat, 0))),
      BlockHeight(714930), _ => true, includeLocalChannelCost = true)
    assert(paths.length == 3)
    assert(paths(0).path == Seq(edgeCE, edgeEF, edgeFH))
    assert(paths(1).path == Seq(edgeCE, edgeEG, edgeGH))
    assert(paths(2).path == Seq(edgeCD, edgeDF, edgeFH))
  }

  test("RoutingHeuristics.normalize") {
      // value inside the range
      assert(Graph.RoutingHeuristics.normalize(value = 10, min = 0, max = 100) === (10.0 / 100.0) +- 0.001)
      assert(Graph.RoutingHeuristics.normalize(value = 20, min = 10, max = 200) === (10.0 / 190.0) +- 0.001)
      assert(Graph.RoutingHeuristics.normalize(value = -11, min = -100, max = -10) === (89.0 / 90.0) +- 0.001)

      // value on the bounds
      assert(Graph.RoutingHeuristics.normalize(value = 0, min = 0, max = 100) > 0)
      assert(Graph.RoutingHeuristics.normalize(value = 10, min = 10, max = 200) > 0)
      assert(Graph.RoutingHeuristics.normalize(value = -100, min = -100, max = -10) > 0)
      assert(Graph.RoutingHeuristics.normalize(value = 9.1, min = 10, max = 200) > 0)
      assert(Graph.RoutingHeuristics.normalize(value = 100, min = 0, max = 100) < 1)
      assert(Graph.RoutingHeuristics.normalize(value = 200, min = 10, max = 200) < 1)

      // value outside the range
      assert(Graph.RoutingHeuristics.normalize(value = 105.2, min = 0, max = 100) < 1)

      // Should throw exception if min > max
      assertThrows[IllegalArgumentException](
        Graph.RoutingHeuristics.normalize(value = 9, min = 10, max = 1)
      )
  }

  test("local channel is preferred") {
    // Direct edge
    val edgeAB = makeEdge(1L, a, b, 100 msat, 1000, capacity = 100000 sat, minHtlc = 1000 msat)
    // Cheaper path
    val edgeAC = makeEdge(2L, a, c, 1 msat, 3, capacity = 100000 sat, minHtlc = 1000 msat)
    val edgeCB = makeEdge(3L, c, b, 2 msat, 4, capacity = 100000 sat, minHtlc = 1000 msat)
    val graph = DirectedGraph(Seq(edgeAB, edgeAC, edgeCB))

    val paths = yenKshortestPaths(graph, a, b, 10000000 msat,
      Set.empty, Set.empty, Set.empty, 1,
      Left(WeightRatios(1, 0, 0, 0, RelayFees(0 msat, 0))),
      BlockHeight(714930), _ => true, includeLocalChannelCost = true)
    assert(paths.head.path == Seq(edgeAB))
  }
}
