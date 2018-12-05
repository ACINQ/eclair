package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.router.Graph.UndirectedWeightedGraph
import org.scalatest.FunSuite
import RouteCalculationSpec._
import fr.acinq.eclair.router.Graph.UndirectedWeightedGraph.GraphEdge
import fr.acinq.eclair.wire.ChannelUpdate

class GraphSpec extends FunSuite {

  val (a, b, c, d, e) = (
    PublicKey("02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), //a
    PublicKey("03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), //b
    PublicKey("0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), //c
    PublicKey("029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), //d
    PublicKey("02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a")  //e
  )

  test("instantiate a graph, with vertices and then add edges") {

    val graph = UndirectedWeightedGraph(a)
      .addVertex(b)
      .addVertex(c)
      .addVertex(d)
      .addVertex(e)

    assert(graph.containsVertex(a) && graph.containsVertex(e))
    assert(graph.vertexSet().size === 5)

    val otherGraph = graph.addVertex(a) //adding the same vertex twice!
    assert(otherGraph.vertexSet().size === 5)

    // add some edges to the graph

    val (descAB, updateAB) = makeUpdate(1L, a, b, 0, 0)
    val (descBC, updateBC) = makeUpdate(2L, b, c, 0, 0)
    val (descAD, updateAD) = makeUpdate(3L, a, d, 0, 0)
    val (descDC, updateDC) = makeUpdate(4L, d, c, 0, 0)
    val (descCE, updateCE) = makeUpdate(5L, c, e, 0, 0)

    val graphWithEdges = graph
        .addEdge(descAB, updateAB)
        .addEdge(descAD, updateAD)
        .addEdge(descBC, updateBC)
        .addEdge(descDC, updateDC)
        .addEdge(descCE, updateCE)

    assert(graphWithEdges.edgesOf(a).size === 2)
    assert(graphWithEdges.edgesOf(b).size === 2)
    assert(graphWithEdges.edgesOf(c).size === 3)
    assert(graphWithEdges.edgesOf(d).size === 2)
    assert(graphWithEdges.edgesOf(e).size === 1)

    val withRemovedEdges = graphWithEdges.removeEdge(descDC)

    assert(withRemovedEdges.edgesOf(c).size === 2)
  }


  test("instantiate a graph adding edges only") {

    val edgeAB = edgeFromDesc(makeUpdate(1L, a, b, 0, 0))
    val (descBC, updateBC) = makeUpdate(2L, b, c, 0, 0)
    val (descAD, updateAD) = makeUpdate(3L, a, d, 0, 0)
    val (descDC, updateDC) = makeUpdate(4L, d, c, 0, 0)
    val (descCE, updateCE) = makeUpdate(5L, c, e, 0, 0)
    val (descBE, updateBE) = makeUpdate(6L, b, e, 0, 0)

    val graph = UndirectedWeightedGraph(edgeAB)
      .addEdge(descAD, updateAD)
      .addEdge(descBC, updateBC)
      .addEdge(descDC, updateDC)
      .addEdge(descCE, updateCE)
      .addEdge(descBE, updateBE)

    assert(graph.vertexSet().size === 5)
    assert(graph.edgesOf(c).size === 3)
    assert(graph.edgeSet().size === 6)

    val withRemovedEdge = graph.removeEdge(descBE)

    assert(withRemovedEdge.edgeSet().size === 5)

  }

  def edgeFromDesc(tuple: (ChannelDesc, ChannelUpdate) ): GraphEdge = GraphEdge(tuple._1, tuple._2)
}
