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

import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.RoutingHeuristics
import fr.acinq.eclair.router.RouteCalculationSpec._
import fr.acinq.eclair.router.Router.{ChannelDesc, PublicChannel}
import fr.acinq.eclair.{MilliSatoshiLong, ShortChannelId}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.collection.immutable.SortedMap

class GraphSpec extends AnyFunSuite {

  val (a, b, c, d, e, f, g) = (
    PublicKey.fromHex("02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), //a
    PublicKey.fromHex("03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), //b
    PublicKey.fromHex("0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), //c
    PublicKey.fromHex("029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), //d
    PublicKey.fromHex("02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"), //e
    PublicKey.fromHex("03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc"), //f
    PublicKey.fromHex("03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f") //g
  )

  // +---- D -------+
  // |              |
  // A --- B ------ C
  //       |        |
  //       +--- E --+
  def makeTestGraph() =
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
    assert(graph.vertexSet().size === 5)

    val otherGraph = graph.addVertex(a) // adding the same vertex twice!
    assert(otherGraph.vertexSet().size === 5)

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

    assert(graphWithEdges.edgesOf(a).size === 2)
    assert(graphWithEdges.edgesOf(b).size === 1)
    assert(graphWithEdges.edgesOf(c).size === 1)
    assert(graphWithEdges.edgesOf(d).size === 1)
    assert(graphWithEdges.edgesOf(e).size === 0)

    val withRemovedEdges = graphWithEdges.removeEdge(edgeAD.desc)
    assert(withRemovedEdges.edgesOf(d).size === 1)
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

    assert(graph.vertexSet().size === 5)
    assert(graph.edgesOf(c).size === 1)
    assert(graph.getIncomingEdgesOf(c).size === 2)
    assert(graph.edgeSet().size === 6)
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

    assert(graph.edgeSet().size === 6)
    assert(graph.containsEdge(edgeBE.desc))

    val withRemovedEdge = graph.removeEdge(edgeBE.desc)
    assert(withRemovedEdge.edgeSet().size === 5)

    val withRemovedList = graph.removeEdges(Seq(edgeAD.desc, edgeDC.desc))
    assert(withRemovedList.edgeSet().size === 4)

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
    assert(edgesAB.size === 1) // there should be an edge a --> b
    assert(edgesAB.head.desc.a === a)
    assert(edgesAB.head.desc.b === b)

    val bIncoming = graph.getIncomingEdgesOf(b)
    assert(bIncoming.size === 1)
    assert(bIncoming.exists(_.desc.a === a)) // there should be an edge a --> b
    assert(bIncoming.exists(_.desc.b === b))

    val bOutgoing = graph.edgesOf(b)
    assert(bOutgoing.size === 1)
    assert(bOutgoing.exists(_.desc.a === b))
    assert(bOutgoing.exists(_.desc.b === c))
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
    assert(mutatedGraph2.getEdgesBetween(a, b).size === 2)
    assert(mutatedGraph2.getEdge(edgeForTheSameChannel).get.update.feeBaseMsat === 30.msat)
  }

  test("remove a vertex with incoming edges and check those edges are removed too") {
    val graph = makeTestGraph()
    assert(graph.vertexSet().size === 5)
    assert(graph.containsVertex(e))
    assert(graph.containsEdge(descFromNodes(5, c, e)))
    assert(graph.containsEdge(descFromNodes(6, b, e)))

    // E has 2 incoming edges
    val withoutE = graph.removeVertex(e)

    assert(withoutE.vertexSet().size === 4)
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

    assert(graph.edgesOf(a).toSet === Set(edgeAB, edgeAD))
    assert(graph.getIncomingEdgesOf(a) === Nil)
    assert(graph.edgesOf(c) === Nil)
    assert(graph.getIncomingEdgesOf(c).toSet === Set(edgeBC, edgeDC))

    val edgeAB1 = edgeAB.copy(balance_opt = Some(200000 msat))
    val edgeBC1 = edgeBC.copy(balance_opt = Some(150000 msat))
    val graph1 = graph.addEdge(edgeAB1).addEdge(edgeBC1)

    assert(graph1.edgesOf(a).toSet === Set(edgeAB1, edgeAD))
    assert(graph1.getIncomingEdgesOf(a) === Nil)
    assert(graph1.edgesOf(c) === Nil)
    assert(graph1.getIncomingEdgesOf(c).toSet === Set(edgeBC1, edgeDC))
  }

  def descFromNodes(shortChannelId: Long, a: PublicKey, b: PublicKey): ChannelDesc = makeEdge(shortChannelId, a, b, 0 msat, 0).desc

  def edgeFromNodes(shortChannelId: Long, a: PublicKey, b: PublicKey): GraphEdge = makeEdge(shortChannelId, a, b, 0 msat, 0)

}
