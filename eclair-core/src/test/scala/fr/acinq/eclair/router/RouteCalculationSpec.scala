/*
 * Copyright 2018 ACINQ SAS
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

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Block, Crypto}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{RichWeight, WeightRatios}
import fr.acinq.eclair.router.Graph.RichWeight
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, ShortChannelId, randomKey}
import org.scalatest.FunSuite

import scala.util.{Failure, Success}

/**
  * Created by PM on 31/05/2016.
  */

class RouteCalculationSpec extends FunSuite {

  import RouteCalculationSpec._

  val (a, b, c, d, e, f) = (randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)

  test("calculate simple route") {

    val updates = List(
      makeUpdate(1L, a, b, 1, 10, cltvDelta = 1),
      makeUpdate(2L, b, c, 1, 10, cltvDelta = 1),
      makeUpdate(3L, c, d, 1, 10, cltvDelta = 1),
      makeUpdate(4L, d, e, 1, 10, cltvDelta = 1)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)

    assert(route.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("calculate the shortest path (correct fees)") {

    val (a, b, c, d, e, f) = (
      PublicKey("02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // a: source
      PublicKey("03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey("0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey("029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), // d: target
      PublicKey("03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      PublicKey("020c65be6f9252e85ae2fe9a46eed892cb89565e2157730e78311b1621a0db4b22")
    )

    // note: we don't actually use floating point numbers
    // cost(CD) = 10005 = amountMsat + 1 + (amountMsat * 400 / 1000000)
    // cost(BC) = 10009,0015 = (cost(CD) + 1 + (cost(CD) * 300 / 1000000)
    // cost(FD) = 10002 = amountMsat + 1 + (amountMsat * 100 / 1000000)
    // cost(EF) = 10007,0008 = cost(FD) + 1 + (cost(FD) * 400 / 1000000)
    // cost(AE) = 10007 -> A is source, shortest path found
    // cost(AB) = 10009

    val amountMsat = 10000
    val expectedCost = 10007

    val updates = List(
      makeUpdate(1L, a, b, feeBaseMsat = 1, feeProportionalMillionth = 200, minHtlcMsat = 0),
      makeUpdate(4L, a, e, feeBaseMsat = 1, feeProportionalMillionth = 200, minHtlcMsat = 0),
      makeUpdate(2L, b, c, feeBaseMsat = 1, feeProportionalMillionth = 300, minHtlcMsat = 0),
      makeUpdate(3L, c, d, feeBaseMsat = 1, feeProportionalMillionth = 400, minHtlcMsat = 0),
      makeUpdate(5L, e, f, feeBaseMsat = 1, feeProportionalMillionth = 400, minHtlcMsat = 0),
      makeUpdate(6L, f, d, feeBaseMsat = 1, feeProportionalMillionth = 100, minHtlcMsat = 0)
    ).toMap

    val graph = makeGraph(updates)

    val Success(route) = Router.findRoute(graph, a, d, amountMsat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)

    val totalCost = Graph.pathWeight(hops2Edges(route), amountMsat, false, 0, None).cost

    assert(hops2Ids(route) === 4 :: 5 :: 6 :: Nil)
    assert(totalCost === expectedCost)

    // now channel 5 could route the amount (10000) but not the amount + fees (10007)
    val (desc, update) = makeUpdate(5L, e, f, feeBaseMsat = 1, feeProportionalMillionth = 400, minHtlcMsat = 0, maxHtlcMsat = Some(10005L))
    val graph1 = graph.addEdge(desc, update)

    val Success(route1) = Router.findRoute(graph1, a, d, amountMsat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)

    assert(hops2Ids(route1) === 1 :: 2 :: 3 :: Nil)
  }

  test("calculate route considering the direct channel pays no fees") {
    val updates = List(
      makeUpdate(1L, a, b, 5, 0), // a -> b
      makeUpdate(2L, a, d, 15, 0), // a -> d  this goes a bit closer to the target and asks for higher fees but is a direct channel
      makeUpdate(3L, b, c, 5, 0), // b -> c
      makeUpdate(4L, c, d, 5, 0), // c -> d
      makeUpdate(5L, d, e, 5, 0) // d -> e
    ).toMap

    val g = makeGraph(updates)
    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)

    assert(route.map(hops2Ids) === Success(2 :: 5 :: Nil))
  }

  test("calculate simple route (add and remove edges") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))

    val graphWithRemovedEdge = g.removeEdge(ChannelDesc(ShortChannelId(3L), c, d))
    val route2 = Router.findRoute(graphWithRemovedEdge, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route2.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("calculate the shortest path (hardcoded nodes)") {

    val (f, g, h, i) = (
      PublicKey("02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // source
      PublicKey("03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey("0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey("029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // target
    )

    val updates = List(
      makeUpdate(1L, f, g, 0, 0),
      makeUpdate(2L, g, h, 0, 0),
      makeUpdate(3L, h, i, 0, 0),
      makeUpdate(4L, f, h, 50, 0) // more expensive
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Success(4 :: 3 :: Nil))

  }

  test("calculate the shortest path (select direct channel)") {

    val (f, g, h, i) = (
      PublicKey("02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // source
      PublicKey("03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey("0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey("029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // target
    )

    val updates = List(
      makeUpdate(1L, f, g, 0, 0),
      makeUpdate(4L, f, i, 50, 0), // our starting node F has a direct channel with I
      makeUpdate(2L, g, h, 0, 0),
      makeUpdate(3L, h, i, 0, 0)
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 2, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Success(4 :: Nil))
  }

  test("if there are multiple channels between the same node, select the cheapest") {

    val (f, g, h, i) = (
      PublicKey("02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
      PublicKey("03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
      PublicKey("0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
      PublicKey("029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
    )

    val updates = List(
      makeUpdate(1L, f, g, 0, 0),
      makeUpdate(2L, g, h, 5, 5), // expensive  g -> h channel
      makeUpdate(6L, g, h, 0, 0), // cheap      g -> h channel
      makeUpdate(3L, h, i, 0, 0)
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Success(1 :: 6 :: 3 :: Nil))
  }

  test("calculate longer but cheaper route") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0),
      makeUpdate(4L, d, e, 0, 0),
      makeUpdate(5L, b, e, 10, 10)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("no local channels") {

    val updates = List(
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("route not found") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("route not found (source OR target node not connected)") {

    val updates = List(
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(4L, c, d, 0, 0)
    ).toMap

    val g = makeGraph(updates).addVertex(a).addVertex(e)

    assert(Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS) === Failure(RouteNotFound))
    assert(Router.findRoute(g, b, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS) === Failure(RouteNotFound))
  }

  test("route not found (amount too high OR too low)") {

    val highAmount = DEFAULT_AMOUNT_MSAT * 10
    val lowAmount = DEFAULT_AMOUNT_MSAT / 10

    val updatesHi = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0, maxHtlcMsat = Some(DEFAULT_AMOUNT_MSAT)),
      makeUpdate(3L, c, d, 0, 0)
    ).toMap

    val updatesLo = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0, minHtlcMsat = DEFAULT_AMOUNT_MSAT),
      makeUpdate(3L, c, d, 0, 0)
    ).toMap

    val g = makeGraph(updatesHi)
    val g1 = makeGraph(updatesLo)

    assert(Router.findRoute(g, a, d, highAmount, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS) === Failure(RouteNotFound))
    assert(Router.findRoute(g1, a, d, lowAmount, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS) === Failure(RouteNotFound))
  }

  test("route to self") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, a, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Failure(CannotRouteToSelf))
  }

  test("route to immediate neighbor") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, b, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Success(1 :: Nil))
  }

  test("directed graph") {
    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    // a->e works, e->a fails

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))

    val route2 = Router.findRoute(g, e, a, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route2.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("compute an example sig") {
    val data = BinaryData("00" * 32)
    val key = PrivateKey(BinaryData("11" * 32))
    val sig = Crypto.encodeSignature(Crypto.sign(data, key))
    assert(Crypto.isDERSignature(sig :+ 1.toByte))
  }

  test("calculate route and return metadata") {

    val DUMMY_SIG = BinaryData("3045022100e0a180fdd0fe38037cc878c03832861b40a29d32bd7b40b10c9e1efc8c1468a002205ae06d1624896d0d29f4b31e32772ea3cb1b4d7ed4e077e5da28dcc33c0e781201")

    val uab = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(1L), 0L, 0, 0, 1, 42, 2500, 140, None)
    val uba = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(1L), 1L, 0, 1, 1, 43, 2501, 141, None)
    val ubc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(2L), 1L, 0, 0, 1, 44, 2502, 142, None)
    val ucb = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(2L), 1L, 0, 1, 1, 45, 2503, 143, None)
    val ucd = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(3L), 1L, 1, 0, 1, 46, 2504, 144, Some(500000000L))
    val udc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(3L), 1L, 0, 1, 1, 47, 2505, 145, None)
    val ude = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(4L), 1L, 0, 0, 1, 48, 2506, 146, None)
    val ued = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(4L), 1L, 0, 1, 1, 49, 2507, 147, None)

    val updates = Map(
      ChannelDesc(ShortChannelId(1L), a, b) -> uab,
      ChannelDesc(ShortChannelId(1L), b, a) -> uba,
      ChannelDesc(ShortChannelId(2L), b, c) -> ubc,
      ChannelDesc(ShortChannelId(2L), c, b) -> ucb,
      ChannelDesc(ShortChannelId(3L), c, d) -> ucd,
      ChannelDesc(ShortChannelId(3L), d, c) -> udc,
      ChannelDesc(ShortChannelId(4L), d, e) -> ude,
      ChannelDesc(ShortChannelId(4L), e, d) -> ued
    )

    val g = makeGraph(updates)

    val hops = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS).get

    assert(hops === Hop(a, b, uab) :: Hop(b, c, ubc) :: Hop(c, d, ucd) :: Hop(d, e, ude) :: Nil)
  }

  test("convert extra hops to channel_update") {
    val a = randomKey.publicKey
    val b = randomKey.publicKey
    val c = randomKey.publicKey
    val d = randomKey.publicKey
    val e = randomKey.publicKey

    val extraHop1 = ExtraHop(a, ShortChannelId(1), 10, 11, 12)
    val extraHop2 = ExtraHop(b, ShortChannelId(2), 20, 21, 22)
    val extraHop3 = ExtraHop(c, ShortChannelId(3), 30, 31, 32)
    val extraHop4 = ExtraHop(d, ShortChannelId(4), 40, 41, 42)

    val extraHops = extraHop1 :: extraHop2 :: extraHop3 :: extraHop4 :: Nil

    val fakeUpdates = Router.toFakeUpdates(extraHops, e)

    assert(fakeUpdates == Map(
      ChannelDesc(extraHop1.shortChannelId, a, b) -> Router.toFakeUpdate(extraHop1),
      ChannelDesc(extraHop2.shortChannelId, b, c) -> Router.toFakeUpdate(extraHop2),
      ChannelDesc(extraHop3.shortChannelId, c, d) -> Router.toFakeUpdate(extraHop3),
      ChannelDesc(extraHop4.shortChannelId, d, e) -> Router.toFakeUpdate(extraHop4)
    ))

  }

  test("blacklist routes") {
    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, ignoredEdges = Set(ChannelDesc(ShortChannelId(3L), c, d)), routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Failure(RouteNotFound))

    // verify that we left the graph untouched
    assert(g.containsEdge(makeUpdate(3L, c, d, 0, 0)._1)) // c -> d
    assert(g.containsVertex(c))
    assert(g.containsVertex(d))

    // make sure we can find a route if without the blacklist
    val route2 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route2.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("route to a destination that is not in the graph (with assisted routes)") {
    val updates = List(
      makeUpdate(1L, a, b, 10, 10),
      makeUpdate(2L, b, c, 10, 10),
      makeUpdate(3L, c, d, 10, 10)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))

    // now we add the missing edge to reach the destination
    val (extraDesc, extraUpdate) = makeUpdate(4L, d, e, 5, 5)
    val extraGraphEdges = Set(GraphEdge(extraDesc, extraUpdate))

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, extraEdges = extraGraphEdges, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }


  test("verify that extra hops takes precedence over known channels") {
    val updates = List(
      makeUpdate(1L, a, b, 10, 10),
      makeUpdate(2L, b, c, 10, 10),
      makeUpdate(3L, c, d, 10, 10),
      makeUpdate(4L, d, e, 10, 10)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
    assert(route1.get(1).lastUpdate.feeBaseMsat == 10)

    val (extraDesc, extraUpdate) = makeUpdate(2L, b, c, 5, 5)

    val extraGraphEdges = Set(GraphEdge(extraDesc, extraUpdate))

    val route2 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, extraEdges = extraGraphEdges, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route2.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
    assert(route2.get(1).lastUpdate.feeBaseMsat == 5)
  }

  test("compute ignored channels") {

    val f = randomKey.publicKey
    val g = randomKey.publicKey
    val h = randomKey.publicKey
    val i = randomKey.publicKey
    val j = randomKey.publicKey

    val channels = Map(
      ShortChannelId(1L) -> makeChannel(1L, a, b),
      ShortChannelId(2L) -> makeChannel(2L, b, c),
      ShortChannelId(3L) -> makeChannel(3L, c, d),
      ShortChannelId(4L) -> makeChannel(4L, d, e),
      ShortChannelId(5L) -> makeChannel(5L, f, g),
      ShortChannelId(6L) -> makeChannel(6L, f, h),
      ShortChannelId(7L) -> makeChannel(7L, h, i),
      ShortChannelId(8L) -> makeChannel(8L, i, j)

    )
    val updates = List(
      makeUpdate(1L, a, b, 10, 10),
      makeUpdate(2L, b, c, 10, 10),
      makeUpdate(2L, c, b, 10, 10),
      makeUpdate(3L, c, d, 10, 10),
      makeUpdate(4L, d, e, 10, 10),
      makeUpdate(5L, f, g, 10, 10),
      makeUpdate(6L, f, h, 10, 10),
      makeUpdate(7L, h, i, 10, 10),
      makeUpdate(8L, i, j, 10, 10)
    ).toMap

    val ignored = Router.getIgnoredChannelDesc(updates, ignoreNodes = Set(c, j, randomKey.publicKey))

    assert(ignored.toSet === Set(
      ChannelDesc(ShortChannelId(2L), b, c),
      ChannelDesc(ShortChannelId(2L), c, b),
      ChannelDesc(ShortChannelId(3L), c, d),
      ChannelDesc(ShortChannelId(8L), i, j)
    ))
  }

  test("limit routes to 20 hops") {

    val nodes = (for (_ <- 0 until 22) yield randomKey.publicKey).toList

    val updates = nodes
      .zip(nodes.drop(1)) // (0, 1) :: (1, 2) :: ...
      .zipWithIndex // ((0, 1), 0) :: ((1, 2), 1) :: ...
      .map { case ((na, nb), index) => makeUpdate(index, na, nb, 5, 0) }
      .toMap

    val g = makeGraph(updates)

    assert(Router.findRoute(g, nodes(0), nodes(18), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS).map(hops2Ids) === Success(0 until 18))
    assert(Router.findRoute(g, nodes(0), nodes(19), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS).map(hops2Ids) === Success(0 until 19))
    assert(Router.findRoute(g, nodes(0), nodes(20), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS).map(hops2Ids) === Success(0 until 20))
    assert(Router.findRoute(g, nodes(0), nodes(21), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS).map(hops2Ids) === Failure(RouteNotFound))
  }

  test("ignore cheaper route when it has more than 20 hops") {

    val nodes = (for (_ <- 0 until 50) yield randomKey.publicKey).toList

    val updates = nodes
      .zip(nodes.drop(1)) // (0, 1) :: (1, 2) :: ...
      .zipWithIndex // ((0, 1), 0) :: ((1, 2), 1) :: ...
      .map { case ((na, nb), index) => makeUpdate(index, na, nb, 1, 0) }
      .toMap

    val updates2 = updates + makeUpdate(99, nodes(2), nodes(48), 1000, 0) // expensive shorter route

    val g = makeGraph(updates2)

    val route = Router.findRoute(g, nodes(0), nodes(49), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route.map(hops2Ids) === Success(0 :: 1 :: 99 :: 48 :: Nil))
  }

  test("ignore cheaper route when it has more than the requested CLTV") {

    val f = randomKey.publicKey

    val g = makeGraph(List(
      makeUpdate(1, a, b, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 50),
      makeUpdate(2, b, c, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 50),
      makeUpdate(3, c, d, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 50),
      makeUpdate(4, a, e, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 9),
      makeUpdate(5, e, f, feeBaseMsat = 5, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 9),
      makeUpdate(6, f, d, feeBaseMsat = 5, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 9)
    ).toMap)

    val route = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(routeMaxCltv = 28))
    assert(route.map(hops2Ids) === Success(4 :: 5 :: 6 :: Nil))
  }

  test("ignore cheaper route when it grows longer than the requested size") {

    val f = randomKey.publicKey

    val g = makeGraph(List(
      makeUpdate(1, a, b, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 9),
      makeUpdate(2, b, c, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 9),
      makeUpdate(3, c, d, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 9),
      makeUpdate(4, d, e, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 9),
      makeUpdate(5, e, f, feeBaseMsat = 5, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 9),
      makeUpdate(6, b, f, feeBaseMsat = 5, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 9)
    ).toMap)

    val route = Router.findRoute(g, a, f, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(routeMaxLength = 3))
    assert(route.map(hops2Ids) === Success(1 :: 6 :: Nil))
  }

  test("ignore loops") {

    val updates = List(
      makeUpdate(1L, a, b, 10, 10),
      makeUpdate(2L, b, c, 10, 10),
      makeUpdate(3L, c, a, 10, 10),
      makeUpdate(4L, c, d, 10, 10),
      makeUpdate(5L, d, e, 10, 10)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 4 :: 5 :: Nil))
  }


  /**
    *
    * +---+            +---+            +---+
    * | A +-----+      | B +----------> | C |
    * +-+-+     |      +-+-+            +-+-+
    * ^       |        ^                |
    * |       |        |                |
    * |       v----> + |                |
    * +-+-+            <-+-+            +-+-+
    * | D +----------> | E +----------> | F |
    * +---+            +---+            +---+
    *
    */
  test("find the k-shortest paths in a graph, k=4") {

    val (a, b, c, d, e, f) = (
      PublicKey("02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), //a
      PublicKey("03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), //b
      PublicKey("0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), //c
      PublicKey("029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), //d
      PublicKey("02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"), //e
      PublicKey("03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc") //f
    )


    val edges = Seq(
      makeUpdate(1L, d, a, 1, 0),
      makeUpdate(2L, d, e, 1, 0),
      makeUpdate(3L, a, e, 1, 0),
      makeUpdate(4L, e, b, 1, 0),
      makeUpdate(5L, e, f, 1, 0),
      makeUpdate(6L, b, c, 1, 0),
      makeUpdate(7L, c, f, 1, 0)
    ).toMap

    val graph = DirectedGraph.makeGraph(edges)

    val fourShortestPaths = Graph.yenKshortestPaths(graph, d, f, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, pathsToFind = 4, None, 0, noopBoundaries)

    assert(fourShortestPaths.size === 4)
    assert(hops2Ids(fourShortestPaths(0).path.map(graphEdgeToHop)) === 2 :: 5 :: Nil) // D -> E -> F
    assert(hops2Ids(fourShortestPaths(1).path.map(graphEdgeToHop)) === 1 :: 3 :: 5 :: Nil) // D -> A -> E -> F
    assert(hops2Ids(fourShortestPaths(2).path.map(graphEdgeToHop)) === 2 :: 4 :: 6 :: 7 :: Nil) // D -> E -> B -> C -> F
    assert(hops2Ids(fourShortestPaths(3).path.map(graphEdgeToHop)) === 1 :: 3 :: 4 :: 6 :: 7 :: Nil) // D -> A -> E -> B -> C -> F
  }

  test("find the k shortest path (wikipedia example)") {
    val (c, d, e, f, g, h) = (
      PublicKey("02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), //c
      PublicKey("03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), //d
      PublicKey("0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), //e
      PublicKey("029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), //f
      PublicKey("02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"), //g
      PublicKey("03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc") //h
    )


    val edges = Seq(
      makeUpdate(10L, c, e, 2, 0),
      makeUpdate(20L, c, d, 3, 0),
      makeUpdate(30L, d, f, 4, 5), // D- > F has a higher cost to distinguish it from the 2nd cheapest route
      makeUpdate(40L, e, d, 1, 0),
      makeUpdate(50L, e, f, 2, 0),
      makeUpdate(60L, e, g, 3, 0),
      makeUpdate(70L, f, g, 2, 0),
      makeUpdate(80L, f, h, 1, 0),
      makeUpdate(90L, g, h, 2, 0)
    )

    val graph = DirectedGraph().addEdges(edges)

    val twoShortestPaths = Graph.yenKshortestPaths(graph, c, h, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, pathsToFind = 2, None, 0, noopBoundaries)

    assert(twoShortestPaths.size === 2)
    val shortest = twoShortestPaths(0)
    assert(hops2Ids(shortest.path.map(graphEdgeToHop)) === 10 :: 50 :: 80 :: Nil) // C -> E -> F -> H

    val secondShortest = twoShortestPaths(1)
    assert(hops2Ids(secondShortest.path.map(graphEdgeToHop)) === 10 :: 60 :: 90 :: Nil) // C -> E -> G -> H
  }

  test("terminate looking for k-shortest path if there are no more alternative paths than k, must not consider routes going back on their steps") {

    val f = randomKey.publicKey

    // simple graph with only 2 possible paths from A to F
    val edges = Seq(
      makeUpdate(1L, a, b, 1, 0),
      makeUpdate(1L, b, a, 1, 0),
      makeUpdate(2L, b, c, 1, 0),
      makeUpdate(2L, c, b, 1, 0),
      makeUpdate(3L, c, f, 1, 0),
      makeUpdate(3L, f, c, 1, 0),
      makeUpdate(4L, c, d, 1, 0),
      makeUpdate(4L, d, c, 1, 0),
      makeUpdate(41L, d, c, 1, 0), // there is more than one D -> C channel
      makeUpdate(5L, d, e, 1, 0),
      makeUpdate(5L, e, d, 1, 0),
      makeUpdate(6L, e, f, 1, 0),
      makeUpdate(6L, f, e, 1, 0)
    )

    val graph = DirectedGraph().addEdges(edges)

    //we ask for 3 shortest paths but only 2 can be found
    val foundPaths = Graph.yenKshortestPaths(graph, a, f, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, pathsToFind = 3, None, 0, noopBoundaries)

    assert(foundPaths.size === 2)
    assert(hops2Ids(foundPaths(0).path.map(graphEdgeToHop)) === 1 :: 2 :: 3 :: Nil) // A -> B -> C -> F
    assert(hops2Ids(foundPaths(1).path.map(graphEdgeToHop)) === 1 :: 2 :: 4 :: 5 :: 6 :: Nil) // A -> B -> C -> D -> E -> F
  }

  test("select a random route below the requested fee") {

    val strictFeeParams = DEFAULT_ROUTE_PARAMS.copy(maxFeeBaseMsat = 7, maxFeePct = 0)

    // A -> B -> C -> D has total cost of 10000005
    // A -> E -> C -> D has total cost of 11080003 !!
    // A -> E -> F -> D has total cost of 10000006
    val g = makeGraph(List(
      makeUpdate(1L, a, b, feeBaseMsat = 1, 0),
      makeUpdate(4L, a, e, feeBaseMsat = 1, 0),
      makeUpdate(2L, b, c, feeBaseMsat = 2, 0),
      makeUpdate(3L, c, d, feeBaseMsat = 3, 0),
      makeUpdate(5L, e, f, feeBaseMsat = 3, 0),
      makeUpdate(6L, f, d, feeBaseMsat = 3, 0),
      makeUpdate(7L, e, c, feeBaseMsat = 9, 0)
    ).toMap)

    (for {_ <- 0 to 10} yield Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 3, routeParams = strictFeeParams)).map {
      case Failure(thr) => assert(false, thr)
      case Success(someRoute) =>

        val routeCost = Graph.pathWeight(hops2Edges(someRoute), DEFAULT_AMOUNT_MSAT, isPartial = false, 0, None).cost - DEFAULT_AMOUNT_MSAT

        // over the three routes we could only get the 2 cheapest because the third is too expensive (over 7msat of fees)
        assert(routeCost == 5 || routeCost == 6)
    }
  }

  test("Use weight ratios to when computing the edge weight") {

    val largeCapacity = 8000000000L

    // A -> B -> C -> D is 'fee optimized', lower fees route (totFees = 2, totCltv = 4000)
    // A -> E -> F -> D is 'timeout optimized', lower CLTV route (totFees = 3, totCltv = 18)
    // A -> E -> C -> D is 'capacity optimized', more recent channel/larger capacity route
    val updates = List(
      makeUpdate(1L, a, b, feeBaseMsat = 0, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 13),
      makeUpdate(4L, a, e, feeBaseMsat = 0, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 12),
      makeUpdate(2L, b, c, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 500),
      makeUpdate(3L, c, d, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 500),
      makeUpdate(5L, e, f, feeBaseMsat = 2, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 9),
      makeUpdate(6L, f, d, feeBaseMsat = 2, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 9),
      makeUpdate(7L, e, c, feeBaseMsat = 2, 0, minHtlcMsat = 0, maxHtlcMsat = Some(largeCapacity), cltvDelta = 12)
    ).toMap

    val g = makeGraph(updates)

    val Success(routeFeeOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 0, routeParams = DEFAULT_ROUTE_PARAMS)
    assert(hops2Nodes(routeFeeOptimized) === (a, b) :: (b, c) :: (c, d) :: Nil)

    val Success(routeCltvOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 0, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      cltvDeltaFactor = 1,
      ageFactor = 0,
      capacityFactor = 0
    ))))

    assert(hops2Nodes(routeCltvOptimized) === (a, e) :: (e, f) :: (f, d) :: Nil)

    val Success(routeCapacityOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 0, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      cltvDeltaFactor = 0,
      ageFactor = 0,
      capacityFactor = 1
    ))))

    assert(hops2Nodes(routeCapacityOptimized) === (a, e) :: (e, c) :: (c, d) :: Nil)
  }

  test("prefer going through an older channel if fees and CLTV are the same") {

    val currentBlockHeight = 554000

    val g = makeGraph(List(
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight}x0x1"), a, b, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 144),
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight}x0x4"), a, e, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 144),
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight - 3000}x0x2"), b, c, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 144), // younger channel
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight - 3000}x0x3"), c, d, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 144),
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight}x0x5"), e, f, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 144),
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight}x0x6"), f, d, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 144)
    ).toMap)

    Globals.blockCount.set(currentBlockHeight)

    val Success(routeScoreOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT / 2, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      ageFactor = 0.33,
      cltvDeltaFactor = 0.33,
      capacityFactor = 0.33
    ))))

    assert(hops2Nodes(routeScoreOptimized) === (a, b) :: (b, c) :: (c, d) :: Nil)
  }

  test("prefer a route with a smaller total CLTV if fees and score are the same") {

    val g = makeGraph(List(
      makeUpdateShort(ShortChannelId(s"0x0x1"), a, b, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 12),
      makeUpdateShort(ShortChannelId(s"0x0x4"), a, e, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 12),
      makeUpdateShort(ShortChannelId(s"0x0x2"), b, c, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 10), // smaller CLTV
      makeUpdateShort(ShortChannelId(s"0x0x3"), c, d, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 12),
      makeUpdateShort(ShortChannelId(s"0x0x5"), e, f, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 12),
      makeUpdateShort(ShortChannelId(s"0x0x6"), f, d, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 12)
    ).toMap)


    val Success(routeScoreOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      ageFactor = 0.33,
      cltvDeltaFactor = 0.33,
      capacityFactor = 0.33
    ))))

    assert(hops2Nodes(routeScoreOptimized) === (a, b) :: (b, c) :: (c, d) :: Nil)
  }


  test("avoid a route that breaks off the max CLTV") {

    // A -> B -> C -> D is cheaper but has a total CLTV > 2016!
    // A -> E -> F -> D is more expensive but has a total CLTV < 2016
    val g = makeGraph(List(
      makeUpdateShort(ShortChannelId(s"0x0x1"), a, b, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 144),
      makeUpdateShort(ShortChannelId(s"0x0x4"), a, e, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 144),
      makeUpdateShort(ShortChannelId(s"0x0x2"), b, c, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 1000),
      makeUpdateShort(ShortChannelId(s"0x0x3"), c, d, feeBaseMsat = 1, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 900),
      makeUpdateShort(ShortChannelId(s"0x0x5"), e, f, feeBaseMsat = 10, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 144),
      makeUpdateShort(ShortChannelId(s"0x0x6"), f, d, feeBaseMsat = 10, 0, minHtlcMsat = 0, maxHtlcMsat = None, cltvDelta = 144)
    ).toMap)

    val Success(routeScoreOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT / 2, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      ageFactor = 0.33,
      cltvDeltaFactor = 0.33,
      capacityFactor = 0.33
    ))))

    assert(hops2Nodes(routeScoreOptimized) === (a, e) :: (e, f) :: (f, d) :: Nil)
  }
}

object RouteCalculationSpec {

  val noopBoundaries = { _: RichWeight => true }

  val DEFAULT_AMOUNT_MSAT = 10000000

  val DEFAULT_ROUTE_PARAMS = RouteParams(maxFeeBaseMsat = 21000, maxFeePct = 0.03, routeMaxCltv = 2016, routeMaxLength = 6, ratios = None)

  val DUMMY_SIG = BinaryData("3045022100e0a180fdd0fe38037cc878c03832861b40a29d32bd7b40b10c9e1efc8c1468a002205ae06d1624896d0d29f4b31e32772ea3cb1b4d7ed4e077e5da28dcc33c0e781201")

  def makeChannel(shortChannelId: Long, nodeIdA: PublicKey, nodeIdB: PublicKey) = {
    val (nodeId1, nodeId2) = if (Announcements.isNode1(nodeIdA, nodeIdB)) (nodeIdA, nodeIdB) else (nodeIdB, nodeIdA)
    ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, "", Block.RegtestGenesisBlock.hash, ShortChannelId(shortChannelId), nodeId1, nodeId2, randomKey.publicKey, randomKey.publicKey)
  }

  def makeUpdate(shortChannelId: Long, nodeId1: PublicKey, nodeId2: PublicKey, feeBaseMsat: Int, feeProportionalMillionth: Int, minHtlcMsat: Long = DEFAULT_AMOUNT_MSAT, maxHtlcMsat: Option[Long] = None, cltvDelta: Int = 0): (ChannelDesc, ChannelUpdate) = {
    makeUpdateShort(ShortChannelId(shortChannelId), nodeId1, nodeId2, feeBaseMsat, feeProportionalMillionth, minHtlcMsat, maxHtlcMsat, cltvDelta)
  }

  def makeUpdateShort(shortChannelId: ShortChannelId, nodeId1: PublicKey, nodeId2: PublicKey, feeBaseMsat: Int, feeProportionalMillionth: Int, minHtlcMsat: Long = DEFAULT_AMOUNT_MSAT, maxHtlcMsat: Option[Long] = None, cltvDelta: Int = 0): (ChannelDesc, ChannelUpdate) =
    ChannelDesc(shortChannelId, nodeId1, nodeId2) -> ChannelUpdate(
      signature = DUMMY_SIG,
      chainHash = Block.RegtestGenesisBlock.hash,
      shortChannelId = shortChannelId,
      timestamp = 0L,
      messageFlags = maxHtlcMsat match {
        case Some(_) => 1
        case None => 0
      },
      channelFlags = 0,
      cltvExpiryDelta = cltvDelta,
      htlcMinimumMsat = minHtlcMsat,
      feeBaseMsat = feeBaseMsat,
      feeProportionalMillionths = feeProportionalMillionth,
      htlcMaximumMsat = maxHtlcMsat
    )

  def makeGraph(updates: Map[ChannelDesc, ChannelUpdate]) = DirectedGraph.makeGraph(updates)

  def hops2Ids(route: Seq[Hop]) = route.map(hop => hop.lastUpdate.shortChannelId.toLong)

  def hops2Edges(route: Seq[Hop]) = route.map(hop => GraphEdge(ChannelDesc(hop.lastUpdate.shortChannelId, hop.nodeId, hop.nextNodeId), hop.lastUpdate))

  def hops2ShortChannelIds(route: Seq[Hop]) = route.map(hop => hop.lastUpdate.shortChannelId.toString).toList

  def hops2Nodes(route: Seq[Hop]) = route.map(hop => (hop.nodeId, hop.nextNodeId))

}
