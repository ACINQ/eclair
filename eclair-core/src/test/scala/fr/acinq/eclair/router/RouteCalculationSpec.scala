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
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Satoshi, SatoshiLong}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{RichWeight, WeightRatios}
import fr.acinq.eclair.router.RouteCalculation._
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, ShortChannelId, ToMilliSatoshiConversion, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.{ParallelTestExecution, Tag}
import scodec.bits._

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.util.{Failure, Random, Success}

/**
 * Created by PM on 31/05/2016.
 */

class RouteCalculationSpec extends AnyFunSuite with ParallelTestExecution {

  import RouteCalculationSpec._

  val (a, b, c, d, e, f) = (randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, randomKey().publicKey)

  test("calculate simple route") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 1 msat, 10, cltvDelta = CltvExpiryDelta(1), balance_opt = Some(DEFAULT_AMOUNT_MSAT * 2)),
      makeEdge(2L, b, c, 1 msat, 10, cltvDelta = CltvExpiryDelta(1)),
      makeEdge(3L, c, d, 1 msat, 10, cltvDelta = CltvExpiryDelta(1)),
      makeEdge(4L, d, e, 1 msat, 10, cltvDelta = CltvExpiryDelta(1))
    ))

    val Success(route :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route) === 1 :: 2 :: 3 :: 4 :: Nil)
  }

  test("check fee against max pct properly") {
    // fee is acceptable if it is either:
    //  - below our maximum fee base
    //  - below our maximum fraction of the paid amount
    // here we have a maximum fee base of 1 msat, and all our updates have a base fee of 10 msat
    // so our fee will always be above the base fee, and we will always check that it is below our maximum percentage
    // of the amount being paid
    val routeParams = DEFAULT_ROUTE_PARAMS.copy(maxFeeBase = 1 msat)
    val maxFee = routeParams.getMaxFee(DEFAULT_AMOUNT_MSAT)

    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 10 msat, 10, cltvDelta = CltvExpiryDelta(1)),
      makeEdge(2L, b, c, 10 msat, 10, cltvDelta = CltvExpiryDelta(1)),
      makeEdge(3L, c, d, 10 msat, 10, cltvDelta = CltvExpiryDelta(1)),
      makeEdge(4L, d, e, 10 msat, 10, cltvDelta = CltvExpiryDelta(1))
    ))

    val Success(route :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, maxFee, numRoutes = 1, routeParams = routeParams, currentBlockHeight = 400000)
    assert(route2Ids(route) === 1 :: 2 :: 3 :: 4 :: Nil)
  }

  test("calculate the shortest path (correct fees)") {
    val (a, b, c, d, e, f) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // a: source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), // d: target
      PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
      PublicKey(hex"020c65be6f9252e85ae2fe9a46eed892cb89565e2157730e78311b1621a0db4b22")
    )

    // note: we don't actually use floating point numbers
    // cost(CD) = 10005 = amountMsat + 1 + (amountMsat * 400 / 1000000)
    // cost(BC) = 10009,0015 = (cost(CD) + 1 + (cost(CD) * 300 / 1000000)
    // cost(FD) = 10002 = amountMsat + 1 + (amountMsat * 100 / 1000000)
    // cost(EF) = 10007,0008 = cost(FD) + 1 + (cost(FD) * 400 / 1000000)
    // cost(AE) = 10007 -> A is source, shortest path found
    // cost(AB) = 10009
    //
    // The amounts that need to be sent through each edge are then:
    //
    //                 +--- A ---+
    // 10009,0015 msat |         | 10007,0008 msat
    //                 B         E
    //      10005 msat |         | 10002 msat
    //                 C         F
    //      10000 msat |         | 10000 msat
    //                 +--> D <--+

    val amount = 10000 msat
    val expectedCost = 10007 msat
    val graph = DirectedGraph(List(
      makeEdge(1L, a, b, feeBase = 1 msat, feeProportionalMillionth = 200, minHtlc = 0 msat),
      makeEdge(4L, a, e, feeBase = 1 msat, feeProportionalMillionth = 200, minHtlc = 0 msat),
      makeEdge(2L, b, c, feeBase = 1 msat, feeProportionalMillionth = 300, minHtlc = 0 msat),
      makeEdge(3L, c, d, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat),
      makeEdge(5L, e, f, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat),
      makeEdge(6L, f, d, feeBase = 1 msat, feeProportionalMillionth = 100, minHtlc = 0 msat)
    ))

    val Success(route :: Nil) = findRoute(graph, a, d, amount, maxFee = 7 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    val weightedPath = Graph.pathWeight(a, route2Edges(route), amount, 0, NO_WEIGHT_RATIOS)
    assert(route2Ids(route) === 4 :: 5 :: 6 :: Nil)
    assert(weightedPath.length === 3)
    assert(weightedPath.cost === expectedCost)

    // update channel 5 so that it can route the final amount (10000) but not the amount + fees (10002)
    val graph1 = graph.addEdge(makeEdge(5L, e, f, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat, maxHtlc = Some(10001 msat)))
    val graph2 = graph.addEdge(makeEdge(5L, e, f, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat, capacity = 10 sat))
    val graph3 = graph.addEdge(makeEdge(5L, e, f, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat, balance_opt = Some(10001 msat)))
    for (g <- Seq(graph1, graph2, graph3)) {
      val Success(route1 :: Nil) = findRoute(g, a, d, amount, maxFee = 10 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      assert(route2Ids(route1) === 1 :: 2 :: 3 :: Nil)
    }
  }

  test("calculate route considering the direct channel pays no fees") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 5 msat, 0), // a -> b
      makeEdge(2L, a, d, 15 msat, 0), // a -> d  this goes a bit closer to the target and asks for higher fees but is a direct channel
      makeEdge(3L, b, c, 5 msat, 0), // b -> c
      makeEdge(4L, c, d, 5 msat, 0), // c -> d
      makeEdge(5L, d, e, 5 msat, 0) // d -> e
    ))

    val Success(route :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route) === 2 :: 5 :: Nil)
  }

  test("calculate simple route (add and remove edges") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 0 msat, 0),
      makeEdge(2L, b, c, 0 msat, 0),
      makeEdge(3L, c, d, 0 msat, 0),
      makeEdge(4L, d, e, 0 msat, 0)
    ))

    val Success(route1 :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route1) === 1 :: 2 :: 3 :: 4 :: Nil)

    val graphWithRemovedEdge = g.removeEdge(ChannelDesc(ShortChannelId(3L), c, d))
    val route2 = findRoute(graphWithRemovedEdge, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2 === Failure(RouteNotFound))
  }

  test("calculate the shortest path (hardcoded nodes)") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // target
    )

    val graph = DirectedGraph(List(
      makeEdge(1L, f, g, 1 msat, 0),
      makeEdge(2L, g, h, 1 msat, 0),
      makeEdge(3L, h, i, 1 msat, 0),
      makeEdge(4L, f, h, 50 msat, 0) // more expensive but fee will be ignored since f is the payer
    ))

    val Success(route :: Nil) = findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route) === 4 :: 3 :: Nil)
  }

  test("calculate the shortest path (select direct channel)") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // target
    )

    val graph = DirectedGraph(List(
      makeEdge(1L, f, g, 0 msat, 0),
      makeEdge(4L, f, i, 50 msat, 0), // our starting node F has a direct channel with I
      makeEdge(2L, g, h, 0 msat, 0),
      makeEdge(3L, h, i, 0 msat, 0)
    ))

    val Success(route1 :: route2 :: Nil) = findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 2, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route1) === 4 :: Nil)
    assert(route2Ids(route2) === 1 :: 2 :: 3 :: Nil)
  }

  test("find a route using channels with htlMaximumMsat close to the payment amount") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
    )

    val graph = DirectedGraph(List(
      makeEdge(1L, f, g, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 50.msat)),
      // the maximum htlc allowed by this channel is only 50 msat greater than what we're sending
      makeEdge(2L, g, h, 1 msat, 0, maxHtlc = Some(DEFAULT_AMOUNT_MSAT + 50.msat)),
      makeEdge(3L, h, i, 1 msat, 0)
    ))

    val Success(route :: Nil) = findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route) === 1 :: 2 :: 3 :: Nil)
  }

  test("find a route using channels with htlMinimumMsat close to the payment amount") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
    )

    val graph = DirectedGraph(List(
      makeEdge(1L, f, g, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 50.msat)),
      // this channel requires a minimum amount that is larger than what we are sending
      makeEdge(2L, g, h, 1 msat, 0, minHtlc = DEFAULT_AMOUNT_MSAT + 50.msat),
      makeEdge(3L, h, i, 1 msat, 0)
    ))

    val route = findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route === Failure(RouteNotFound))
  }

  test("if there are multiple channels between the same node, select the cheapest") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
    )

    val graph = DirectedGraph(List(
      makeEdge(1L, f, g, 0 msat, 0),
      makeEdge(2L, g, h, 5 msat, 5), // expensive  g -> h channel
      makeEdge(6L, g, h, 0 msat, 0), // cheap      g -> h channel
      makeEdge(3L, h, i, 0 msat, 0)
    ))

    val Success(route :: Nil) = findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route) === 1 :: 6 :: 3 :: Nil)
  }

  test("if there are multiple channels between the same node, select one that has enough balance") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
    )

    val graph = DirectedGraph(List(
      makeEdge(1L, f, g, 0 msat, 0),
      makeEdge(2L, g, h, 5 msat, 5, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 1.msat)), // expensive g -> h channel with enough balance
      makeEdge(6L, g, h, 0 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT - 10.msat)), // cheap g -> h channel without enough balance
      makeEdge(3L, h, i, 0 msat, 0)
    ))

    val Success(route :: Nil) = findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route) === 1 :: 2 :: 3 :: Nil)
  }

  test("calculate longer but cheaper route") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 0 msat, 0),
      makeEdge(2L, b, c, 0 msat, 0),
      makeEdge(3L, c, d, 0 msat, 0),
      makeEdge(4L, d, e, 0 msat, 0),
      makeEdge(5L, b, e, 10 msat, 10)
    ))

    val Success(route :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route) === 1 :: 2 :: 3 :: 4 :: Nil)
  }

  test("no local channels") {
    val g = DirectedGraph(List(
      makeEdge(2L, b, c, 0 msat, 0),
      makeEdge(4L, d, e, 0 msat, 0)
    ))

    val route = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route === Failure(RouteNotFound))
  }

  test("route not found") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 0 msat, 0),
      makeEdge(2L, b, c, 0 msat, 0),
      makeEdge(4L, d, e, 0 msat, 0)
    ))

    val route = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route === Failure(RouteNotFound))
  }

  test("route not found (source OR target node not connected)") {
    val g = DirectedGraph(List(
      makeEdge(2L, b, c, 0 msat, 0),
      makeEdge(4L, c, d, 0 msat, 0)
    )).addVertex(a).addVertex(e)

    assert(findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000) === Failure(RouteNotFound))
    assert(findRoute(g, b, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000) === Failure(RouteNotFound))
  }

  test("route not found (amount too high OR too low)") {
    val highAmount = DEFAULT_AMOUNT_MSAT * 10
    val lowAmount = DEFAULT_AMOUNT_MSAT / 10

    val edgesHi = List(
      makeEdge(1L, a, b, 0 msat, 0),
      makeEdge(2L, b, c, 0 msat, 0, maxHtlc = Some(DEFAULT_AMOUNT_MSAT)),
      makeEdge(3L, c, d, 0 msat, 0)
    )

    val edgesLo = List(
      makeEdge(1L, a, b, 0 msat, 0),
      makeEdge(2L, b, c, 0 msat, 0, minHtlc = DEFAULT_AMOUNT_MSAT),
      makeEdge(3L, c, d, 0 msat, 0)
    )

    val g = DirectedGraph(edgesHi)
    val g1 = DirectedGraph(edgesLo)

    assert(findRoute(g, a, d, highAmount, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000) === Failure(RouteNotFound))
    assert(findRoute(g1, a, d, lowAmount, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000) === Failure(RouteNotFound))
  }

  test("route not found (balance too low)") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 1 msat, 2, minHtlc = 10000 msat),
      makeEdge(2L, b, c, 1 msat, 2, minHtlc = 10000 msat),
      makeEdge(3L, c, d, 1 msat, 2, minHtlc = 10000 msat)
    ))
    assert(findRoute(g, a, d, 15000 msat, 100 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000).isSuccess)

    // not enough balance on the last edge
    val g1 = DirectedGraph(List(
      makeEdge(1L, a, b, 1 msat, 2, minHtlc = 10000 msat),
      makeEdge(2L, b, c, 1 msat, 2, minHtlc = 10000 msat),
      makeEdge(3L, c, d, 1 msat, 2, minHtlc = 10000 msat, balance_opt = Some(10000 msat))
    ))
    // not enough balance on intermediate edge (taking fee into account)
    val g2 = DirectedGraph(List(
      makeEdge(1L, a, b, 1 msat, 2, minHtlc = 10000 msat),
      makeEdge(2L, b, c, 1 msat, 2, minHtlc = 10000 msat, balance_opt = Some(15000 msat)),
      makeEdge(3L, c, d, 1 msat, 2, minHtlc = 10000 msat)
    ))
    // no enough balance on first edge (taking fee into account)
    val g3 = DirectedGraph(List(
      makeEdge(1L, a, b, 1 msat, 2, minHtlc = 10000 msat, balance_opt = Some(15000 msat)),
      makeEdge(2L, b, c, 1 msat, 2, minHtlc = 10000 msat),
      makeEdge(3L, c, d, 1 msat, 2, minHtlc = 10000 msat)
    ))
    Seq(g1, g2, g3).foreach(g => assert(findRoute(g, a, d, 15000 msat, 100 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000) === Failure(RouteNotFound)))
  }

  test("route to self") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 0 msat, 0),
      makeEdge(2L, b, c, 0 msat, 0),
      makeEdge(3L, c, d, 0 msat, 0)
    ))

    val route = findRoute(g, a, a, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route === Failure(CannotRouteToSelf))
  }

  test("route to immediate neighbor") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 0 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT)),
      makeEdge(2L, b, c, 0 msat, 0),
      makeEdge(3L, c, d, 0 msat, 0),
      makeEdge(4L, d, e, 0 msat, 0)
    ))

    val Success(route :: Nil) = findRoute(g, a, b, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route) === 1 :: Nil)
  }

  test("directed graph") {
    // a->e works, e->a fails
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 0 msat, 0),
      makeEdge(2L, b, c, 0 msat, 0),
      makeEdge(3L, c, d, 0 msat, 0),
      makeEdge(4L, d, e, 0 msat, 0)
    ))

    val Success(route1 :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route1) === 1 :: 2 :: 3 :: 4 :: Nil)

    val route2 = findRoute(g, e, a, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2 === Failure(RouteNotFound))
  }

  test("calculate route and return metadata") {
    val DUMMY_SIG = Transactions.PlaceHolderSig

    val uab = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(1L), 0L, 0, 0, CltvExpiryDelta(1), 42 msat, 2500 msat, 140, None)
    val uba = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(1L), 1L, 0, 1, CltvExpiryDelta(1), 43 msat, 2501 msat, 141, None)
    val ubc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(2L), 1L, 0, 0, CltvExpiryDelta(1), 44 msat, 2502 msat, 142, None)
    val ucb = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(2L), 1L, 0, 1, CltvExpiryDelta(1), 45 msat, 2503 msat, 143, None)
    val ucd = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(3L), 1L, 1, 0, CltvExpiryDelta(1), 46 msat, 2504 msat, 144, Some(500000000 msat))
    val udc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(3L), 1L, 0, 1, CltvExpiryDelta(1), 47 msat, 2505 msat, 145, None)
    val ude = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(4L), 1L, 0, 0, CltvExpiryDelta(1), 48 msat, 2506 msat, 146, None)
    val ued = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(4L), 1L, 0, 1, CltvExpiryDelta(1), 49 msat, 2507 msat, 147, None)

    val edges = Seq(
      GraphEdge(ChannelDesc(ShortChannelId(1L), a, b), uab, DEFAULT_CAPACITY, None),
      GraphEdge(ChannelDesc(ShortChannelId(1L), b, a), uba, DEFAULT_CAPACITY, None),
      GraphEdge(ChannelDesc(ShortChannelId(2L), b, c), ubc, DEFAULT_CAPACITY, None),
      GraphEdge(ChannelDesc(ShortChannelId(2L), c, b), ucb, DEFAULT_CAPACITY, None),
      GraphEdge(ChannelDesc(ShortChannelId(3L), c, d), ucd, DEFAULT_CAPACITY, None),
      GraphEdge(ChannelDesc(ShortChannelId(3L), d, c), udc, DEFAULT_CAPACITY, None),
      GraphEdge(ChannelDesc(ShortChannelId(4L), d, e), ude, DEFAULT_CAPACITY, None),
      GraphEdge(ChannelDesc(ShortChannelId(4L), e, d), ued, DEFAULT_CAPACITY, None)
    )

    val g = DirectedGraph(edges)
    val Success(route :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.hops === ChannelHop(a, b, uab) :: ChannelHop(b, c, ubc) :: ChannelHop(c, d, ucd) :: ChannelHop(d, e, ude) :: Nil)
  }

  test("convert extra hops to assisted channels") {
    val a = randomKey().publicKey
    val b = randomKey().publicKey
    val c = randomKey().publicKey
    val d = randomKey().publicKey
    val e = randomKey().publicKey

    val extraHop1 = ExtraHop(a, ShortChannelId(1), 12.sat.toMilliSatoshi, 10000, CltvExpiryDelta(12))
    val extraHop2 = ExtraHop(b, ShortChannelId(2), 200.sat.toMilliSatoshi, 0, CltvExpiryDelta(22))
    val extraHop3 = ExtraHop(c, ShortChannelId(3), 150.sat.toMilliSatoshi, 0, CltvExpiryDelta(32))
    val extraHop4 = ExtraHop(d, ShortChannelId(4), 50.sat.toMilliSatoshi, 0, CltvExpiryDelta(42))
    val extraHops = extraHop1 :: extraHop2 :: extraHop3 :: extraHop4 :: Nil

    val amount = 90000 sat // below RoutingHeuristics.CAPACITY_CHANNEL_LOW
    val assistedChannels = toAssistedChannels(extraHops, e, amount.toMilliSatoshi)

    assert(assistedChannels(extraHop4.shortChannelId) === AssistedChannel(extraHop4, e, 100050.sat.toMilliSatoshi))
    assert(assistedChannels(extraHop3.shortChannelId) === AssistedChannel(extraHop3, d, 100200.sat.toMilliSatoshi))
    assert(assistedChannels(extraHop2.shortChannelId) === AssistedChannel(extraHop2, c, 100400.sat.toMilliSatoshi))
    assert(assistedChannels(extraHop1.shortChannelId) === AssistedChannel(extraHop1, b, 101416.sat.toMilliSatoshi))
  }

  test("blacklist routes") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 0 msat, 0),
      makeEdge(2L, b, c, 0 msat, 0),
      makeEdge(3L, c, d, 0 msat, 0),
      makeEdge(4L, d, e, 0 msat, 0)
    ))

    val route1 = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, ignoredEdges = Set(ChannelDesc(ShortChannelId(3L), c, d)), routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route1 === Failure(RouteNotFound))

    // verify that we left the graph untouched
    assert(g.containsEdge(ChannelDesc(ShortChannelId(3), c, d)))
    assert(g.containsVertex(c))
    assert(g.containsVertex(d))

    // make sure we can find a route if without the blacklist
    val Success(route2 :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route2) === 1 :: 2 :: 3 :: 4 :: Nil)
  }

  test("route to a destination that is not in the graph (with assisted routes)") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 10 msat, 10),
      makeEdge(2L, b, c, 10 msat, 10),
      makeEdge(3L, c, d, 10 msat, 10)
    ))

    val route = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route === Failure(RouteNotFound))

    // now we add the missing edge to reach the destination
    val extraGraphEdges = Set(makeEdge(4L, d, e, 5 msat, 5))
    val Success(route1 :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, extraEdges = extraGraphEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route1) === 1 :: 2 :: 3 :: 4 :: Nil)
  }

  test("route from a source that is not in the graph (with assisted routes)") {
    val g = DirectedGraph(List(
      makeEdge(2L, b, c, 10 msat, 10),
      makeEdge(3L, c, d, 10 msat, 10)
    ))

    val route = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route === Failure(RouteNotFound))

    // now we add the missing starting edge
    val extraGraphEdges = Set(makeEdge(1L, a, b, 5 msat, 5))
    val Success(route1 :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, extraEdges = extraGraphEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route1) === 1 :: 2 :: 3 :: Nil)
  }

  test("verify that extra hops takes precedence over known channels") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 10 msat, 10),
      makeEdge(2L, b, c, 10 msat, 10),
      makeEdge(3L, c, d, 10 msat, 10),
      makeEdge(4L, d, e, 10 msat, 10)
    ))

    val Success(route1 :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route1) === 1 :: 2 :: 3 :: 4 :: Nil)
    assert(route1.hops(1).lastUpdate.feeBaseMsat === 10.msat)

    val extraGraphEdges = Set(makeEdge(2L, b, c, 5 msat, 5))
    val Success(route2 :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, extraEdges = extraGraphEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route2) === 1 :: 2 :: 3 :: 4 :: Nil)
    assert(route2.hops(1).lastUpdate.feeBaseMsat === 5.msat)
  }

  test("compute ignored channels") {
    val f = randomKey().publicKey
    val g = randomKey().publicKey
    val h = randomKey().publicKey
    val i = randomKey().publicKey
    val j = randomKey().publicKey

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

    val edges = List(
      makeEdge(1L, a, b, 10 msat, 10),
      makeEdge(2L, b, c, 10 msat, 10),
      makeEdge(2L, c, b, 10 msat, 10),
      makeEdge(3L, c, d, 10 msat, 10),
      makeEdge(4L, d, e, 10 msat, 10),
      makeEdge(5L, f, g, 10 msat, 10),
      makeEdge(6L, f, h, 10 msat, 10),
      makeEdge(7L, h, i, 10 msat, 10),
      makeEdge(8L, i, j, 10 msat, 10)
    )

    val publicChannels = channels.map { case (shortChannelId, announcement) =>
      val update = edges.find(_.desc.shortChannelId == shortChannelId).get.update
      val (update_1_opt, update_2_opt) = if (Announcements.isNode1(update.channelFlags)) (Some(update), None) else (None, Some(update))
      val pc = PublicChannel(announcement, ByteVector32.Zeroes, Satoshi(1000), update_1_opt, update_2_opt, None)
      (shortChannelId, pc)
    }

    val ignored = getIgnoredChannelDesc(publicChannels, ignoreNodes = Set(c, j, randomKey().publicKey))
    assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(2L), b, c)))
    assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(2L), c, b)))
    assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(3L), c, d)))
    assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(8L), i, j)))
  }

  test("limit routes to 20 hops") {
    val nodes = (for (_ <- 0 until 22) yield randomKey().publicKey).toList
    val edges = nodes
      .zip(nodes.drop(1)) // (0, 1) :: (1, 2) :: ...
      .zipWithIndex // ((0, 1), 0) :: ((1, 2), 1) :: ...
      .map { case ((na, nb), index) => makeEdge(index, na, nb, 5 msat, 0) }

    val g = DirectedGraph(edges)

    assert(findRoute(g, nodes(0), nodes(18), DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000).map(r => route2Ids(r.head)) === Success(0 until 18))
    assert(findRoute(g, nodes(0), nodes(19), DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000).map(r => route2Ids(r.head)) === Success(0 until 19))
    assert(findRoute(g, nodes(0), nodes(20), DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000).map(r => route2Ids(r.head)) === Success(0 until 20))
    assert(findRoute(g, nodes(0), nodes(21), DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000) === Failure(RouteNotFound))
  }

  test("ignore cheaper route when it has more than 20 hops") {
    val nodes = (for (_ <- 0 until 50) yield randomKey().publicKey).toList

    val edges = nodes
      .zip(nodes.drop(1)) // (0, 1) :: (1, 2) :: ...
      .zipWithIndex // ((0, 1), 0) :: ((1, 2), 1) :: ...
      .map { case ((na, nb), index) => makeEdge(index, na, nb, 1 msat, 0) }

    val expensiveShortEdge = makeEdge(99, nodes(2), nodes(48), 1000 msat, 0) // expensive shorter route

    val g = DirectedGraph(expensiveShortEdge :: edges)

    val Success(route :: Nil) = findRoute(g, nodes(0), nodes(49), DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route) === 0 :: 1 :: 99 :: 48 :: Nil)
  }

  test("ignore cheaper route when it has more than the requested CLTV") {
    val f = randomKey().publicKey
    val g = DirectedGraph(List(
      makeEdge(1, a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(50)),
      makeEdge(2, b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(50)),
      makeEdge(3, c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(50)),
      makeEdge(4, a, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeEdge(5, e, f, feeBase = 5 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeEdge(6, f, d, feeBase = 5 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9))
    ))

    val Success(route :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(routeMaxCltv = CltvExpiryDelta(28)), currentBlockHeight = 400000)
    assert(route2Ids(route) === 4 :: 5 :: 6 :: Nil)
  }

  test("ignore cheaper route when it grows longer than the requested size") {
    val f = randomKey().publicKey
    val g = DirectedGraph(List(
      makeEdge(1, a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeEdge(2, b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeEdge(3, c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeEdge(4, d, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeEdge(5, e, f, feeBase = 5 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeEdge(6, b, f, feeBase = 5 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9))
    ))

    val Success(route :: Nil) = findRoute(g, a, f, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(routeMaxLength = 3), currentBlockHeight = 400000)
    assert(route2Ids(route) === 1 :: 6 :: Nil)
  }

  test("ignore loops") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 10 msat, 10),
      makeEdge(2L, b, c, 10 msat, 10),
      makeEdge(3L, c, a, 10 msat, 10),
      makeEdge(4L, c, d, 10 msat, 10),
      makeEdge(5L, d, e, 10 msat, 10)
    ))

    val Success(route :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route) === 1 :: 2 :: 4 :: 5 :: Nil)
  }

  test("ensure the route calculation terminates correctly when selecting 0-fees edges") {
    // the graph contains a possible 0-cost path that goes back on its steps ( e -> f, f -> e )
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 10 msat, 10), // a -> b
      makeEdge(2L, b, c, 10 msat, 10),
      makeEdge(4L, c, d, 10 msat, 10),
      makeEdge(3L, b, e, 0 msat, 0), // b -> e
      makeEdge(6L, e, f, 0 msat, 0), // e -> f
      makeEdge(6L, f, e, 0 msat, 0), // e <- f
      makeEdge(5L, e, d, 0 msat, 0) // e -> d
    ))

    val Success(route :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Ids(route) === 1 :: 3 :: 5 :: Nil)
  }

  // +---+                       +---+    +---+
  // | A |-----+            +--->| B |--->| C |
  // +---+     |            |    +---+    +---+
  //   ^       |    +---+   |               |
  //   |       +--->| E |---+               |
  //   |       |    +---+   |               |
  // +---+     |            |    +---+      |
  // | D |-----+            +--->| F |<-----+
  // +---+                       +---+
  test("find the k-shortest paths in a graph, k=4") {
    val (a, b, c, d, e, f) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"),
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"),
      PublicKey(hex"02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"),
      PublicKey(hex"03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc")
    )

    val g1 = DirectedGraph(Seq(
      makeEdge(1L, d, a, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 4.msat)),
      makeEdge(2L, d, e, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 3.msat)),
      makeEdge(3L, a, e, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 3.msat)),
      makeEdge(4L, e, b, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 2.msat)),
      makeEdge(5L, e, f, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT)),
      makeEdge(6L, b, c, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 1.msat)),
      makeEdge(7L, c, f, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT))
    ))

    val fourShortestPaths = Graph.yenKshortestPaths(g1, d, f, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 4, NO_WEIGHT_RATIOS, 0, noopBoundaries)
    assert(fourShortestPaths.size === 4)
    assert(hops2Ids(fourShortestPaths(0).path.map(graphEdgeToHop)) === 2 :: 5 :: Nil) // D -> E -> F
    assert(hops2Ids(fourShortestPaths(1).path.map(graphEdgeToHop)) === 1 :: 3 :: 5 :: Nil) // D -> A -> E -> F
    assert(hops2Ids(fourShortestPaths(2).path.map(graphEdgeToHop)) === 2 :: 4 :: 6 :: 7 :: Nil) // D -> E -> B -> C -> F
    assert(hops2Ids(fourShortestPaths(3).path.map(graphEdgeToHop)) === 1 :: 3 :: 4 :: 6 :: 7 :: Nil) // D -> A -> E -> B -> C -> F

    // Update balance D -> A to evict the last path (balance too low)
    val g2 = g1.addEdge(makeEdge(1L, d, a, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 3.msat)))
    val threeShortestPaths = Graph.yenKshortestPaths(g2, d, f, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 4, NO_WEIGHT_RATIOS, 0, noopBoundaries)
    assert(threeShortestPaths.size === 3)
    assert(hops2Ids(threeShortestPaths(0).path.map(graphEdgeToHop)) === 2 :: 5 :: Nil) // D -> E -> F
    assert(hops2Ids(threeShortestPaths(1).path.map(graphEdgeToHop)) === 1 :: 3 :: 5 :: Nil) // D -> A -> E -> F
    assert(hops2Ids(threeShortestPaths(2).path.map(graphEdgeToHop)) === 2 :: 4 :: 6 :: 7 :: Nil) // D -> E -> B -> C -> F
  }

  test("find the k shortest path (wikipedia example)") {
    val (c, d, e, f, g, h) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"),
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"),
      PublicKey(hex"02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"),
      PublicKey(hex"03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc")
    )

    val graph = DirectedGraph(Seq(
      makeEdge(10L, c, e, 2 msat, 0),
      makeEdge(20L, c, d, 3 msat, 0),
      makeEdge(30L, d, f, 4 msat, 5), // D- > F has a higher cost to distinguish it from the 2nd cheapest route
      makeEdge(40L, e, d, 1 msat, 0),
      makeEdge(50L, e, f, 2 msat, 0),
      makeEdge(60L, e, g, 3 msat, 0),
      makeEdge(70L, f, g, 2 msat, 0),
      makeEdge(80L, f, h, 1 msat, 0),
      makeEdge(90L, g, h, 2 msat, 0)
    ))

    val twoShortestPaths = Graph.yenKshortestPaths(graph, c, h, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 2, NO_WEIGHT_RATIOS, 0, noopBoundaries)

    assert(twoShortestPaths.size === 2)
    val shortest = twoShortestPaths(0)
    assert(hops2Ids(shortest.path.map(graphEdgeToHop)) === 10 :: 50 :: 80 :: Nil) // C -> E -> F -> H

    val secondShortest = twoShortestPaths(1)
    assert(hops2Ids(secondShortest.path.map(graphEdgeToHop)) === 10 :: 60 :: 90 :: Nil) // C -> E -> G -> H
  }

  test("terminate looking for k-shortest path if there are no more alternative paths than k, must not consider routes going back on their steps") {
    val f = randomKey().publicKey

    // simple graph with only 2 possible paths from A to F
    val graph = DirectedGraph(Seq(
      makeEdge(1L, a, b, 1 msat, 0),
      makeEdge(1L, b, a, 1 msat, 0),
      makeEdge(2L, b, c, 1 msat, 0),
      makeEdge(2L, c, b, 1 msat, 0),
      makeEdge(3L, c, f, 1 msat, 0),
      makeEdge(3L, f, c, 1 msat, 0),
      makeEdge(4L, c, d, 1 msat, 0),
      makeEdge(4L, d, c, 1 msat, 0),
      makeEdge(41L, d, c, 1 msat, 0), // there is more than one D -> C channel
      makeEdge(5L, d, e, 1 msat, 0),
      makeEdge(5L, e, d, 1 msat, 0),
      makeEdge(6L, e, f, 1 msat, 0),
      makeEdge(6L, f, e, 1 msat, 0)
    ))

    // we ask for 3 shortest paths but only 2 can be found
    val foundPaths = Graph.yenKshortestPaths(graph, a, f, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 3, NO_WEIGHT_RATIOS, 0, noopBoundaries)
    assert(foundPaths.size === 2)
    assert(hops2Ids(foundPaths(0).path.map(graphEdgeToHop)) === 1 :: 2 :: 3 :: Nil) // A -> B -> C -> F
    assert(hops2Ids(foundPaths(1).path.map(graphEdgeToHop)) === 1 :: 2 :: 4 :: 5 :: 6 :: Nil) // A -> B -> C -> D -> E -> F
  }

  test("select a random route below the requested fee") {
    val strictFeeParams = DEFAULT_ROUTE_PARAMS.copy(maxFeeBase = 7 msat, maxFeePct = 0, randomize = true)
    val strictFee = strictFeeParams.getMaxFee(DEFAULT_AMOUNT_MSAT)
    assert(strictFee === 7.msat)

    // A -> B -> C -> D has total cost of 10000005
    // A -> E -> C -> D has total cost of 10000103 !!
    // A -> E -> F -> D has total cost of 10000006
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, feeBase = 1 msat, 0),
      makeEdge(2L, b, c, feeBase = 2 msat, 0),
      makeEdge(3L, c, d, feeBase = 3 msat, 0),
      makeEdge(4L, a, e, feeBase = 1 msat, 0),
      makeEdge(5L, e, f, feeBase = 3 msat, 0),
      makeEdge(6L, f, d, feeBase = 3 msat, 0),
      makeEdge(7L, e, c, feeBase = 100 msat, 0)
    ))

    for (_ <- 0 to 10) {
      val Success(routes) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, strictFee, numRoutes = 3, routeParams = strictFeeParams, currentBlockHeight = 400000)
      assert(routes.length === 2, routes)
      val weightedPath = Graph.pathWeight(a, route2Edges(routes.head), DEFAULT_AMOUNT_MSAT, 400000, NO_WEIGHT_RATIOS)
      val totalFees = weightedPath.cost - DEFAULT_AMOUNT_MSAT
      // over the three routes we could only get the 2 cheapest because the third is too expensive (over 7 msat of fees)
      assert(totalFees === 5.msat || totalFees === 6.msat)
      assert(weightedPath.length === 3)
    }
  }

  test("use weight ratios when computing the edge weight") {
    val defaultCapacity = 15000 sat
    val largeCapacity = 8000000 sat

    // A -> B -> C -> D is 'fee optimized', lower fees route (totFees = 2, totCltv = 4000)
    // A -> E -> F -> D is 'timeout optimized', lower CLTV route (totFees = 3, totCltv = 18)
    // A -> E -> C -> D is 'capacity optimized', more recent channel/larger capacity route
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, feeBase = 0 msat, 1000, minHtlc = 0 msat, capacity = defaultCapacity, cltvDelta = CltvExpiryDelta(13)),
      makeEdge(4L, a, e, feeBase = 0 msat, 1000, minHtlc = 0 msat, capacity = defaultCapacity, cltvDelta = CltvExpiryDelta(12)),
      makeEdge(2L, b, c, feeBase = 1 msat, 1000, minHtlc = 0 msat, capacity = defaultCapacity, cltvDelta = CltvExpiryDelta(500)),
      makeEdge(3L, c, d, feeBase = 1 msat, 1000, minHtlc = 0 msat, capacity = defaultCapacity, cltvDelta = CltvExpiryDelta(500)),
      makeEdge(5L, e, f, feeBase = 2 msat, 1000, minHtlc = 0 msat, capacity = defaultCapacity, cltvDelta = CltvExpiryDelta(9)),
      makeEdge(6L, f, d, feeBase = 2 msat, 1000, minHtlc = 0 msat, capacity = defaultCapacity, cltvDelta = CltvExpiryDelta(9)),
      makeEdge(7L, e, c, feeBase = 2 msat, 1000, minHtlc = 0 msat, capacity = largeCapacity, cltvDelta = CltvExpiryDelta(12))
    ))

    val Success(routeFeeOptimized :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2Nodes(routeFeeOptimized) === (a, b) :: (b, c) :: (c, d) :: Nil)

    val Success(routeCltvOptimized :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = WeightRatios(
      biasFactor = 0,
      cltvDeltaFactor = 1,
      ageFactor = 0,
      capacityFactor = 0,
      hopCostBase = 0 msat,
      hopCostMillionths = 0
    )), currentBlockHeight = 400000)
    assert(route2Nodes(routeCltvOptimized) === (a, e) :: (e, f) :: (f, d) :: Nil)

    val Success(routeCapacityOptimized :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = WeightRatios(
      biasFactor = 0,
      cltvDeltaFactor = 0,
      ageFactor = 0,
      capacityFactor = 1,
      hopCostBase = 0 msat,
      hopCostMillionths = 0
    )), currentBlockHeight = 400000)
    assert(route2Nodes(routeCapacityOptimized) === (a, e) :: (e, c) :: (c, d) :: Nil)
  }

  test("prefer going through an older channel if fees and CLTV are the same") {
    val currentBlockHeight = 554000

    val g = DirectedGraph(List(
      makeEdge(ShortChannelId(s"${currentBlockHeight}x0x1").toLong, a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeEdge(ShortChannelId(s"${currentBlockHeight}x0x4").toLong, a, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeEdge(ShortChannelId(s"${currentBlockHeight - 3000}x0x2").toLong, b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)), // younger channel
      makeEdge(ShortChannelId(s"${currentBlockHeight - 3000}x0x3").toLong, c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeEdge(ShortChannelId(s"${currentBlockHeight}x0x5").toLong, e, f, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeEdge(ShortChannelId(s"${currentBlockHeight}x0x6").toLong, f, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144))
    ))

    val Success(routeScoreOptimized :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT / 2, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = WeightRatios(
      biasFactor = 0.01,
      ageFactor = 0.33,
      cltvDeltaFactor = 0.33,
      capacityFactor = 0.33,
      hopCostBase = 0 msat,
      hopCostMillionths = 0
    )), currentBlockHeight = currentBlockHeight)

    assert(route2Nodes(routeScoreOptimized) === (a, b) :: (b, c) :: (c, d) :: Nil)
  }

  test("prefer a route with a smaller total CLTV if fees and score are the same") {
    val g = DirectedGraph(List(
      makeEdge(1, a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeEdge(4, a, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeEdge(2, b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(10)), // smaller CLTV
      makeEdge(3, c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeEdge(5, e, f, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeEdge(6, f, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12))
    ))

    val Success(routeScoreOptimized :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = WeightRatios(
      biasFactor = 0.01,
      ageFactor = 0.33,
      cltvDeltaFactor = 0.33,
      capacityFactor = 0.33,
      hopCostBase = 0 msat,
      hopCostMillionths = 0
    )), currentBlockHeight = 400000)

    assert(route2Nodes(routeScoreOptimized) === (a, b) :: (b, c) :: (c, d) :: Nil)
  }

  test("avoid a route that breaks off the max CLTV") {
    // A -> B -> C -> D is cheaper but has a total CLTV > 2016!
    // A -> E -> F -> D is more expensive but has a total CLTV < 2016
    val g = DirectedGraph(List(
      makeEdge(1, a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeEdge(4, a, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeEdge(2, b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(1000)),
      makeEdge(3, c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(900)),
      makeEdge(5, e, f, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeEdge(6, f, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144))
    ))

    val Success(routeScoreOptimized :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT / 2, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = WeightRatios(
      biasFactor = 0.01,
      ageFactor = 0.33,
      cltvDeltaFactor = 0.33,
      capacityFactor = 0.33,
      hopCostBase = 0 msat,
      hopCostMillionths = 0
    )), currentBlockHeight = 400000)

    assert(route2Nodes(routeScoreOptimized) === (a, e) :: (e, f) :: (f, d) :: Nil)
  }

  test("cost function is monotonic") {
    // This test have a channel (542280x2156x0) that according to heuristics is very convenient but actually useless to reach the target,
    // then if the cost function is not monotonic the path-finding breaks because the result path contains a loop.
    val updates = SortedMap(
      ShortChannelId("565643x1216x0") -> PublicChannel(
        ann = makeChannel(ShortChannelId("565643x1216x0").toLong, PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca")),
        fundingTxid = ByteVector32.Zeroes,
        capacity = DEFAULT_CAPACITY,
        update_1_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("565643x1216x0"), 0, 1.toByte, 0.toByte, CltvExpiryDelta(14), htlcMinimumMsat = 1 msat, feeBaseMsat = 1000 msat, 10, Some(4294967295L msat))),
        update_2_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("565643x1216x0"), 0, 1.toByte, 1.toByte, CltvExpiryDelta(144), htlcMinimumMsat = 0 msat, feeBaseMsat = 1000 msat, 100, Some(15000000000L msat))),
        meta_opt = None
      ),
      ShortChannelId("542280x2156x0") -> PublicChannel(
        ann = makeChannel(ShortChannelId("542280x2156x0").toLong, PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), PublicKey(hex"03cb7983dc247f9f81a0fa2dfa3ce1c255365f7279c8dd143e086ca333df10e278")),
        fundingTxid = ByteVector32.Zeroes,
        capacity = DEFAULT_CAPACITY,
        update_1_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("542280x2156x0"), 0, 1.toByte, 0.toByte, CltvExpiryDelta(144), htlcMinimumMsat = 1000 msat, feeBaseMsat = 1000 msat, 100, Some(16777000000L msat))),
        update_2_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("542280x2156x0"), 0, 1.toByte, 1.toByte, CltvExpiryDelta(144), htlcMinimumMsat = 1 msat, feeBaseMsat = 667 msat, 1, Some(16777000000L msat))),
        meta_opt = None
      ),
      ShortChannelId("565779x2711x0") -> PublicChannel(
        ann = makeChannel(ShortChannelId("565779x2711x0").toLong, PublicKey(hex"036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96"), PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")),
        fundingTxid = ByteVector32.Zeroes,
        capacity = DEFAULT_CAPACITY,
        update_1_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("565779x2711x0"), 0, 1.toByte, 0.toByte, CltvExpiryDelta(144), htlcMinimumMsat = 1 msat, feeBaseMsat = 1000 msat, 100, Some(230000000L msat))),
        update_2_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("565779x2711x0"), 0, 1.toByte, 3.toByte, CltvExpiryDelta(144), htlcMinimumMsat = 1 msat, feeBaseMsat = 1000 msat, 100, Some(230000000L msat))),
        meta_opt = None
      )
    )

    val g = DirectedGraph.makeGraph(updates)
    val params = DEFAULT_ROUTE_PARAMS.copy(
      routeMaxCltv = CltvExpiryDelta(1008),
      ratios = WeightRatios(biasFactor = 0, cltvDeltaFactor = 0.15, ageFactor = 0.35, capacityFactor = 0.5, hopCostBase = 0 msat, hopCostMillionths = 0),
    )
    val thisNode = PublicKey(hex"036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96")
    val targetNode = PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca")
    val amount = 351000 msat

    val Success(route :: Nil) = findRoute(g, thisNode, targetNode, amount, DEFAULT_MAX_FEE, 1, Set.empty, Set.empty, Set.empty, params, currentBlockHeight = 567634) // simulate mainnet block for heuristic
    assert(route.length == 2)
    assert(route.hops.last.nextNodeId == targetNode)
  }

  test("validate path fees") {
    val ab = makeEdge(1L, a, b, feeBase = 100 msat, 10000, minHtlc = 150 msat, maxHtlc = Some(300 msat), capacity = 1 sat, balance_opt = Some(260 msat))
    val bc = makeEdge(10L, b, c, feeBase = 5 msat, 10000, minHtlc = 100 msat, maxHtlc = Some(400 msat), capacity = 1 sat)
    val cd = makeEdge(20L, c, d, feeBase = 5 msat, 10000, minHtlc = 50 msat, maxHtlc = Some(500 msat), capacity = 1 sat)

    assert(Graph.validatePath(Nil, 200 msat)) // ok
    assert(Graph.validatePath(Seq(ab), 260 msat)) // ok
    assert(!Graph.validatePath(Seq(ab), 10000 msat)) // above max-htlc
    assert(Graph.validatePath(Seq(ab, bc), 250 msat)) // ok
    assert(!Graph.validatePath(Seq(ab, bc), 255 msat)) // above balance (AB)
    assert(Graph.validatePath(Seq(ab, bc, cd), 200 msat)) // ok
    assert(!Graph.validatePath(Seq(ab, bc, cd), 25 msat)) // below min-htlc (CD)
    assert(!Graph.validatePath(Seq(ab, bc, cd), 60 msat)) // below min-htlc (BC)
    assert(!Graph.validatePath(Seq(ab, bc, cd), 110 msat)) // below min-htlc (AB)
    assert(!Graph.validatePath(Seq(ab, bc, cd), 550 msat)) // above max-htlc (CD)
    assert(!Graph.validatePath(Seq(ab, bc, cd), 450 msat)) // above max-htlc (BC)
    assert(!Graph.validatePath(Seq(ab, bc, cd), 350 msat)) // above max-htlc (AB)
    assert(!Graph.validatePath(Seq(ab, bc, cd), 250 msat)) // above balance (AB)
  }

  test("calculate multipart route to neighbor (many channels, known balance)") {
    val amount = 65000 msat
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(15000 msat)),
      makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1 msat, balance_opt = Some(25000 msat)),
      makeEdge(3L, a, b, 1 msat, 50, minHtlc = 1 msat, balance_opt = Some(20000 msat)),
      makeEdge(4L, a, b, 100 msat, 20, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
    ))
    // We set max-parts to 3, but it should be ignored when sending to a direct neighbor.
    val routeParams = DEFAULT_ROUTE_PARAMS.copy(mpp = MultiPartParams(2500 msat, 3))

    {
      val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = routeParams, currentBlockHeight = 400000)
      assert(routes.length === 4, routes)
      assert(routes.forall(_.length == 1), routes)
      checkRouteAmounts(routes, amount, 0 msat)
    }
    {
      val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = routeParams.copy(randomize = true), currentBlockHeight = 400000)
      assert(routes.length >= 4, routes)
      assert(routes.forall(_.length == 1), routes)
      checkRouteAmounts(routes, amount, 0 msat)
    }
    {
      // We set min-part-amount to a value that would exclude channels 1 and 4, but it should be ignored when sending to a direct neighbor.
      val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = routeParams.copy(mpp = MultiPartParams(20000 msat, 3)), currentBlockHeight = 400000)
      assert(routes.length === 4, routes)
      assert(routes.forall(_.length == 1), routes)
      checkRouteAmounts(routes, amount, 0 msat)
    }
  }

  test("calculate multipart route to neighbor (single channel, known balance)") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(25000 msat)),
      makeEdge(2L, a, c, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(50000 msat)),
      makeEdge(3L, c, b, 1 msat, 0, minHtlc = 1 msat),
      makeEdge(4L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
    ))

    val amount = 25000 msat
    val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(routes.length === 1, routes)
    checkRouteAmounts(routes, amount, 0 msat)
    assert(route2Ids(routes.head) === 1L :: Nil)
  }

  test("calculate multipart route to neighbor (many channels, some balance unknown)") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(15000 msat)),
      makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1 msat, balance_opt = Some(25000 msat)),
      makeEdge(3L, a, b, 1 msat, 50, minHtlc = 1 msat, balance_opt = None, capacity = 20 sat),
      makeEdge(4L, a, b, 100 msat, 20, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
      makeEdge(5L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
    ))

    val amount = 65000 msat
    val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(routes.length === 4, routes)
    assert(routes.forall(_.length == 1), routes)
    checkRouteAmounts(routes, amount, 0 msat)
  }

  test("calculate multipart route to neighbor (many channels, some empty)") {
    val amount = 35000 msat
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(15000 msat)),
      makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1 msat, balance_opt = Some(0 msat)),
      makeEdge(3L, a, b, 1 msat, 50, minHtlc = 1 msat, balance_opt = None, capacity = 15 sat),
      makeEdge(4L, a, b, 1 msat, 0, minHtlc = 0 msat, balance_opt = Some(0 msat)),
      makeEdge(5L, a, b, 100 msat, 20, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
      makeEdge(6L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
      makeEdge(7L, a, d, 0 msat, 0, minHtlc = 0 msat, balance_opt = Some(0 msat)),
    ))

    {
      val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      assert(routes.length === 3, routes)
      assert(routes.forall(_.length == 1), routes)
      checkIgnoredChannels(routes, 2L)
      checkRouteAmounts(routes, amount, 0 msat)
    }
    {
      val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = true), currentBlockHeight = 400000)
      assert(routes.length >= 3, routes)
      assert(routes.forall(_.length == 1), routes)
      checkIgnoredChannels(routes, 2L)
      checkRouteAmounts(routes, amount, 0 msat)
    }
  }

  test("calculate multipart route to neighbor (ignored channels)") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(15000 msat)),
      makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1 msat, balance_opt = Some(25000 msat)),
      makeEdge(3L, a, b, 1 msat, 50, minHtlc = 1 msat, balance_opt = None, capacity = 50 sat),
      makeEdge(4L, a, b, 100 msat, 20, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
      makeEdge(5L, a, b, 1 msat, 10, minHtlc = 1 msat, balance_opt = None, capacity = 10 sat),
      makeEdge(6L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
    ))

    val amount = 20000 msat
    val ignoredEdges = Set(ChannelDesc(ShortChannelId(2L), a, b), ChannelDesc(ShortChannelId(3L), a, b))
    val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, ignoredEdges = ignoredEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(routes.forall(_.length == 1), routes)
    checkIgnoredChannels(routes, 2L, 3L)
    checkRouteAmounts(routes, amount, 0 msat)
  }

  test("calculate multipart route to neighbor (pending htlcs ignored for local channels)") {
    val edge_ab_1 = makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(15000 msat))
    val edge_ab_2 = makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1 msat, balance_opt = Some(25000 msat))
    val edge_ab_3 = makeEdge(3L, a, b, 1 msat, 50, minHtlc = 1 msat, balance_opt = None, capacity = 15 sat)
    val g = DirectedGraph(List(
      edge_ab_1,
      edge_ab_2,
      edge_ab_3,
      makeEdge(4L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
    ))

    val amount = 50000 msat
    // These pending HTLCs will have already been taken into account in the edge's `balance_opt` field: findMultiPartRoute
    // should ignore this information.
    val pendingHtlcs = Seq(Route(10000 msat, ChannelHop(a, b, edge_ab_1.update) :: Nil), Route(5000 msat, ChannelHop(a, b, edge_ab_2.update) :: Nil))
    val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, pendingHtlcs = pendingHtlcs, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(routes.forall(_.length == 1), routes)
    checkRouteAmounts(routes, amount, 0 msat)
  }

  test("calculate multipart route to neighbor (restricted htlc_maximum_msat)") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 25 msat, 15, minHtlc = 1 msat, maxHtlc = Some(5000 msat), balance_opt = Some(18000 msat)),
      makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1 msat, maxHtlc = Some(5000 msat), balance_opt = Some(23000 msat)),
      makeEdge(3L, a, b, 1 msat, 50, minHtlc = 1 msat, maxHtlc = Some(5000 msat), balance_opt = Some(21000 msat)),
      makeEdge(4L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
    ))

    val amount = 50000 msat
    val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(routes.forall(_.length == 1), routes)
    assert(routes.length >= 10, routes)
    assert(routes.forall(_.amount <= 5000.msat), routes)
    checkRouteAmounts(routes, amount, 0 msat)
  }

  test("calculate multipart route to neighbor (restricted htlc_minimum_msat)") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 25 msat, 15, minHtlc = 2500 msat, balance_opt = Some(18000 msat)),
      makeEdge(2L, a, b, 15 msat, 10, minHtlc = 2500 msat, balance_opt = Some(7000 msat)),
      makeEdge(3L, a, b, 1 msat, 50, minHtlc = 2500 msat, balance_opt = Some(10000 msat)),
      makeEdge(4L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
    ))

    val amount = 30000 msat
    val routeParams = DEFAULT_ROUTE_PARAMS.copy(mpp = MultiPartParams(2500 msat, 5))
    val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = routeParams, currentBlockHeight = 400000)
    assert(routes.forall(_.length == 1), routes)
    assert(routes.length == 3, routes)
    checkRouteAmounts(routes, amount, 0 msat)
  }

  test("calculate multipart route to neighbor (through remote channels)") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 25 msat, 15, minHtlc = 1000 msat, balance_opt = Some(18000 msat)),
      makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1000 msat, balance_opt = Some(7000 msat)),
      makeEdge(3L, a, c, 1000 msat, 10000, minHtlc = 1000 msat, balance_opt = Some(10000 msat)),
      makeEdge(4L, c, b, 10 msat, 1000, minHtlc = 1000 msat),
      makeEdge(5L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(25000 msat)),
    ))

    val amount = 30000 msat
    val maxFeeTooLow = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(maxFeeTooLow === Failure(RouteNotFound))

    val Success(routes) = findMultiPartRoute(g, a, b, amount, 20 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(routes.forall(_.length <= 2), routes)
    assert(routes.length == 3, routes)
    checkRouteAmounts(routes, amount, 20 msat)
  }

  test("cannot find multipart route to neighbor (not enough balance)") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 0 msat, 0, minHtlc = 1 msat, balance_opt = Some(15000 msat)),
      makeEdge(2L, a, b, 0 msat, 0, minHtlc = 1 msat, balance_opt = Some(5000 msat)),
      makeEdge(3L, a, b, 0 msat, 0, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
      makeEdge(4L, a, d, 0 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
    ))

    {
      val result = findMultiPartRoute(g, a, b, 40000 msat, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      assert(result === Failure(RouteNotFound))
    }
    {
      val result = findMultiPartRoute(g, a, b, 40000 msat, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = true), currentBlockHeight = 400000)
      assert(result === Failure(RouteNotFound))
    }
  }

  test("cannot find multipart route to neighbor (not enough capacity)") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 0 msat, 0, minHtlc = 1 msat, capacity = 1500 sat),
      makeEdge(2L, a, b, 0 msat, 0, minHtlc = 1 msat, capacity = 2000 sat),
      makeEdge(3L, a, b, 0 msat, 0, minHtlc = 1 msat, capacity = 1200 sat),
      makeEdge(4L, a, d, 0 msat, 0, minHtlc = 1 msat, capacity = 4500 sat),
    ))

    val result = findMultiPartRoute(g, a, b, 5000000 msat, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(result === Failure(RouteNotFound))
  }

  test("cannot find multipart route to neighbor (restricted htlc_minimum_msat)") {
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 25 msat, 15, minHtlc = 5000 msat, balance_opt = Some(6000 msat)),
      makeEdge(2L, a, b, 15 msat, 10, minHtlc = 5000 msat, balance_opt = Some(7000 msat)),
      makeEdge(3L, a, d, 0 msat, 0, minHtlc = 5000 msat, balance_opt = Some(9000 msat)),
    ))

    {
      val result = findMultiPartRoute(g, a, b, 10000 msat, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      assert(result === Failure(RouteNotFound))
    }
    {
      val result = findMultiPartRoute(g, a, b, 10000 msat, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = true), currentBlockHeight = 400000)
      assert(result === Failure(RouteNotFound))
    }
  }

  test("calculate multipart route to remote node (many local channels)") {
    // +-------+
    // |       |
    // A ----- C ----- E
    // |               |
    // +--- B --- D ---+
    val (amount, maxFee) = (30000 msat, 150 msat)
    val edge_ab = makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(15000 msat))
    val g = DirectedGraph(List(
      edge_ab,
      makeEdge(2L, b, d, 15 msat, 0, minHtlc = 1 msat, capacity = 25 sat),
      makeEdge(3L, d, e, 15 msat, 0, minHtlc = 0 msat, capacity = 20 sat),
      makeEdge(4L, a, c, 1 msat, 50, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
      makeEdge(5L, a, c, 1 msat, 50, minHtlc = 1 msat, balance_opt = Some(8000 msat)),
      makeEdge(6L, c, e, 50 msat, 30, minHtlc = 1 msat, capacity = 20 sat),
    ))

    {
      val Success(routes) = findMultiPartRoute(g, a, e, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      checkRouteAmounts(routes, amount, maxFee)
      assert(routes2Ids(routes) === Set(Seq(1L, 2L, 3L), Seq(4L, 6L), Seq(5L, 6L)))
    }
    {
      // Update A - B with unknown balance, capacity should be used instead.
      val g1 = g.addEdge(edge_ab.copy(capacity = 15 sat, balance_opt = None))
      val Success(routes) = findMultiPartRoute(g1, a, e, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      checkRouteAmounts(routes, amount, maxFee)
      assert(routes2Ids(routes) === Set(Seq(1L, 2L, 3L), Seq(4L, 6L), Seq(5L, 6L)))
    }
    {
      // Randomize routes.
      val Success(routes) = findMultiPartRoute(g, a, e, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = true), currentBlockHeight = 400000)
      checkRouteAmounts(routes, amount, maxFee)
    }
    {
      // Update balance A - B to be too low.
      val g1 = g.addEdge(edge_ab.copy(balance_opt = Some(2000 msat)))
      val failure = findMultiPartRoute(g1, a, e, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      assert(failure === Failure(RouteNotFound))
    }
    {
      // Update capacity A - B to be too low.
      val g1 = g.addEdge(edge_ab.copy(capacity = 5 sat, balance_opt = None))
      val failure = findMultiPartRoute(g1, a, e, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      assert(failure === Failure(RouteNotFound))
    }
    {
      // Try to find a route with a maxFee that's too low.
      val maxFeeTooLow = 100 msat
      val failure = findMultiPartRoute(g, a, e, amount, maxFeeTooLow, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      assert(failure === Failure(RouteNotFound))
    }
  }

  test("calculate multipart route to remote node (tiny amount)") {
    // A ----- C ----- E
    // |               |
    // +--- B --- D ---+
    // Our balance and the amount we want to send are below the minimum part amount.
    val routeParams = DEFAULT_ROUTE_PARAMS.copy(mpp = MultiPartParams(5000 msat, 5))
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(1500 msat)),
      makeEdge(2L, b, d, 15 msat, 0, minHtlc = 1 msat, capacity = 25 sat),
      makeEdge(3L, d, e, 15 msat, 0, minHtlc = 1 msat, capacity = 20 sat),
      makeEdge(4L, a, c, 1 msat, 50, minHtlc = 1 msat, balance_opt = Some(1000 msat)),
      makeEdge(5L, c, e, 50 msat, 30, minHtlc = 1 msat, capacity = 20 sat),
    ))

    {
      // We can send single-part tiny payments.
      val (amount, maxFee) = (1400 msat, 30 msat)
      val Success(routes) = findMultiPartRoute(g, a, e, amount, maxFee, routeParams = routeParams, currentBlockHeight = 400000)
      checkRouteAmounts(routes, amount, maxFee)
    }
    {
      // But we don't want to split such tiny amounts.
      val (amount, maxFee) = (2000 msat, 150 msat)
      val failure = findMultiPartRoute(g, a, e, amount, maxFee, routeParams = routeParams, currentBlockHeight = 400000)
      assert(failure === Failure(RouteNotFound))
    }
  }

  test("calculate multipart route to remote node (single path)") {
    val (amount, maxFee) = (100000 msat, 500 msat)
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(500000 msat)),
      makeEdge(2L, b, c, 10 msat, 30, minHtlc = 1 msat, capacity = 150 sat),
      makeEdge(3L, c, d, 15 msat, 50, minHtlc = 1 msat, capacity = 150 sat),
    ))

    val Success(routes) = findMultiPartRoute(g, a, d, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    checkRouteAmounts(routes, amount, maxFee)
    assert(routes.length === 1, "payment shouldn't be split when we have one path with enough capacity")
    assert(routes2Ids(routes) === Set(Seq(1L, 2L, 3L)))
  }

  test("calculate multipart route to remote node (single local channel)") {
    //       +--- C ---+
    //       |         |
    // A --- B ------- D --- F
    //       |               |
    //       +----- E -------+
    val (amount, maxFee) = (400000 msat, 250 msat)
    val edge_ab = makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(500000 msat))
    val g = DirectedGraph(List(
      edge_ab,
      makeEdge(2L, b, c, 10 msat, 30, minHtlc = 1 msat, capacity = 150 sat),
      makeEdge(3L, c, d, 15 msat, 50, minHtlc = 1 msat, capacity = 150 sat),
      makeEdge(4L, b, d, 20 msat, 75, minHtlc = 1 msat, capacity = 180 sat),
      makeEdge(5L, d, f, 5 msat, 50, minHtlc = 1 msat, capacity = 300 sat),
      makeEdge(6L, b, e, 15 msat, 80, minHtlc = 1 msat, capacity = 210 sat),
      makeEdge(7L, e, f, 15 msat, 100, minHtlc = 1 msat, capacity = 200 sat),
    ))

    {
      val Success(routes) = findMultiPartRoute(g, a, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      checkRouteAmounts(routes, amount, maxFee)
      assert(routes2Ids(routes) === Set(Seq(1L, 2L, 3L, 5L), Seq(1L, 4L, 5L), Seq(1L, 6L, 7L)))
    }
    {
      // Randomize routes.
      val Success(routes) = findMultiPartRoute(g, a, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = true), currentBlockHeight = 400000)
      checkRouteAmounts(routes, amount, maxFee)
    }
    {
      // Update A - B with unknown balance, capacity should be used instead.
      val g1 = g.addEdge(edge_ab.copy(capacity = 500 sat, balance_opt = None))
      val Success(routes) = findMultiPartRoute(g1, a, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      checkRouteAmounts(routes, amount, maxFee)
      assert(routes2Ids(routes) === Set(Seq(1L, 2L, 3L, 5L), Seq(1L, 4L, 5L), Seq(1L, 6L, 7L)))
    }
    {
      // Update balance A - B to be too low to cover fees.
      val g1 = g.addEdge(edge_ab.copy(balance_opt = Some(400000 msat)))
      val failure = findMultiPartRoute(g1, a, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      assert(failure === Failure(RouteNotFound))
    }
    {
      // Update capacity A - B to be too low to cover fees.
      val g1 = g.addEdge(edge_ab.copy(capacity = 400 sat, balance_opt = None))
      val failure = findMultiPartRoute(g1, a, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      assert(failure === Failure(RouteNotFound))
    }
    {
      // Try to find a route with a maxFee that's too low.
      val maxFeeTooLow = 100 msat
      val failure = findMultiPartRoute(g, a, f, amount, maxFeeTooLow, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      assert(failure === Failure(RouteNotFound))
    }
  }

  test("calculate multipart route to remote node (ignored channels and nodes)") {
    //  +----- B --xxx-- C -----+
    //  | +-------- D --------+ |
    //  | |                   | |
    // +---+    (empty x2)   +---+
    // | A | --------------- | F |
    // +---+                 +---+
    //  | |    (not empty)    | |
    //  | +-------------------+ |
    //  +---------- E ----------+
    val (amount, maxFee) = (25000 msat, 5 msat)
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(75000 msat)),
      makeEdge(2L, b, c, 1 msat, 0, minHtlc = 1 msat, capacity = 150 sat),
      makeEdge(3L, c, f, 1 msat, 0, minHtlc = 1 msat, capacity = 150 sat),
      makeEdge(4L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(85000 msat)),
      makeEdge(5L, d, f, 1 msat, 0, minHtlc = 1 msat, capacity = 300 sat),
      makeEdge(6L, a, f, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(0 msat)),
      makeEdge(7L, a, f, 0 msat, 0, minHtlc = 0 msat, balance_opt = Some(0 msat)),
      makeEdge(8L, a, f, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
      makeEdge(9L, a, e, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(18000 msat)),
      makeEdge(10L, e, f, 1 msat, 0, minHtlc = 1 msat, capacity = 15 sat),
    ))

    val ignoredNodes = Set(d)
    val ignoredChannels = Set(ChannelDesc(ShortChannelId(2L), b, c))
    val Success(routes) = findMultiPartRoute(g, a, f, amount, maxFee, ignoredEdges = ignoredChannels, ignoredVertices = ignoredNodes, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    checkRouteAmounts(routes, amount, maxFee)
    assert(routes2Ids(routes) === Set(Seq(8L), Seq(9L, 10L)))
  }

  test("calculate multipart route to remote node (restricted htlc_minimum_msat and htlc_maximum_msat)") {
    // +----- B -----+
    // |             |
    // A----- C ---- E
    // |             |
    // +----- D -----+
    val (amount, maxFee) = (15000 msat, 5 msat)
    val g = DirectedGraph(List(
      // The A -> B -> E path is impossible because the A -> B balance is lower than the B -> E htlc_minimum_msat.
      makeEdge(1L, a, b, 1 msat, 0, minHtlc = 500 msat, balance_opt = Some(7000 msat)),
      makeEdge(2L, b, e, 1 msat, 0, minHtlc = 10000 msat, capacity = 50 sat),
      makeEdge(3L, a, c, 1 msat, 0, minHtlc = 500 msat, balance_opt = Some(10000 msat)),
      makeEdge(4L, c, e, 1 msat, 0, minHtlc = 500 msat, maxHtlc = Some(4000 msat), capacity = 50 sat),
      makeEdge(5L, a, d, 1 msat, 0, minHtlc = 500 msat, balance_opt = Some(10000 msat)),
      makeEdge(6L, d, e, 1 msat, 0, minHtlc = 500 msat, maxHtlc = Some(4000 msat), capacity = 50 sat),
    ))

    val Success(routes) = findMultiPartRoute(g, a, e, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    checkRouteAmounts(routes, amount, maxFee)
    assert(routes.length >= 4, routes)
    assert(routes.forall(_.amount <= 4000.msat), routes)
    assert(routes.forall(_.amount >= 500.msat), routes)
    checkIgnoredChannels(routes, 1L, 2L)

    val maxFeeTooLow = 3 msat
    val failure = findMultiPartRoute(g, a, e, amount, maxFeeTooLow, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(failure === Failure(RouteNotFound))
  }

  test("calculate multipart route to remote node (complex graph)") {
    // +---+                       +---+    +---+
    // | A |-----+            +--->| B |--->| C |
    // +---+     |            |    +---+    +---+
    //   ^       |    +---+   |               |
    //   |       +--->| E |---+               |
    //   |       |    +---+   |               |
    // +---+     |            |    +---+      |
    // | D |-----+            +--->| F |<-----+
    // +---+                       +---+
    val g = DirectedGraph(Seq(
      makeEdge(1L, d, a, 100 msat, 1000, minHtlc = 1000 msat, balance_opt = Some(80000 msat)),
      makeEdge(2L, d, e, 100 msat, 1000, minHtlc = 1500 msat, balance_opt = Some(20000 msat)),
      makeEdge(3L, a, e, 5 msat, 50, minHtlc = 1200 msat, capacity = 100 sat),
      makeEdge(4L, e, f, 25 msat, 1000, minHtlc = 1300 msat, capacity = 25 sat),
      makeEdge(5L, e, b, 10 msat, 100, minHtlc = 1100 msat, capacity = 75 sat),
      makeEdge(6L, b, c, 5 msat, 50, minHtlc = 1000 msat, capacity = 20 sat),
      makeEdge(7L, c, f, 5 msat, 10, minHtlc = 1500 msat, capacity = 50 sat)
    ))
    val routeParams = DEFAULT_ROUTE_PARAMS.copy(mpp = MultiPartParams(1500 msat, 10))

    {
      val (amount, maxFee) = (15000 msat, 50 msat)
      val Success(routes) = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams, currentBlockHeight = 400000)
      checkRouteAmounts(routes, amount, maxFee)
    }
    {
      val (amount, maxFee) = (25000 msat, 100 msat)
      val Success(routes) = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams, currentBlockHeight = 400000)
      checkRouteAmounts(routes, amount, maxFee)
    }
    {
      val (amount, maxFee) = (25000 msat, 50 msat)
      val failure = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams, currentBlockHeight = 400000)
      assert(failure === Failure(RouteNotFound))
    }
    {
      val (amount, maxFee) = (40000 msat, 100 msat)
      val Success(routes) = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams, currentBlockHeight = 400000)
      checkRouteAmounts(routes, amount, maxFee)
    }
    {
      val (amount, maxFee) = (40000 msat, 100 msat)
      val Success(routes) = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams.copy(randomize = true), currentBlockHeight = 400000)
      checkRouteAmounts(routes, amount, maxFee)
    }
    {
      val (amount, maxFee) = (40000 msat, 50 msat)
      val failure = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams, currentBlockHeight = 400000)
      assert(failure === Failure(RouteNotFound))
    }
  }

  test("calculate multipart route to remote node (with extra edges)") {
    // +--- B ---+
    // A         D (---) E (---) F
    // +--- C ---+
    val (amount, maxFeeE, maxFeeF) = (10000 msat, 50 msat, 100 msat)
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 1 msat, 0, minHtlc = 1 msat, maxHtlc = Some(4000 msat), balance_opt = Some(7000 msat)),
      makeEdge(2L, b, d, 1 msat, 0, minHtlc = 1 msat, capacity = 50 sat),
      makeEdge(3L, a, c, 1 msat, 0, minHtlc = 1 msat, maxHtlc = Some(4000 msat), balance_opt = Some(6000 msat)),
      makeEdge(4L, c, d, 1 msat, 0, minHtlc = 1 msat, capacity = 40 sat),
    ))
    val extraEdges = Set(
      makeEdge(10L, d, e, 10 msat, 100, minHtlc = 500 msat, capacity = 15 sat),
      makeEdge(11L, e, f, 5 msat, 100, minHtlc = 500 msat, capacity = 10 sat),
    )

    val Success(routes1) = findMultiPartRoute(g, a, e, amount, maxFeeE, extraEdges = extraEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    checkRouteAmounts(routes1, amount, maxFeeE)
    assert(routes1.length >= 3, routes1)
    assert(routes1.forall(_.amount <= 4000.msat), routes1)

    val Success(routes2) = findMultiPartRoute(g, a, f, amount, maxFeeF, extraEdges = extraEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    checkRouteAmounts(routes2, amount, maxFeeF)
    assert(routes2.length >= 3, routes2)
    assert(routes2.forall(_.amount <= 4000.msat), routes2)

    val maxFeeTooLow = 40 msat
    val failure = findMultiPartRoute(g, a, f, amount, maxFeeTooLow, extraEdges = extraEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(failure === Failure(RouteNotFound))
  }

  test("calculate multipart route to remote node (pending htlcs)") {
    // +----- B -----+
    // |             |
    // A----- C ---- E
    // |             |
    // +----- D -----+
    val (amount, maxFee) = (15000 msat, 100 msat)
    val edge_ab = makeEdge(1L, a, b, 1 msat, 0, minHtlc = 100 msat, balance_opt = Some(5000 msat))
    val edge_be = makeEdge(2L, b, e, 1 msat, 0, minHtlc = 100 msat, capacity = 5 sat)
    val g = DirectedGraph(List(
      // The A -> B -> E route is the most economic one, but we already have a pending HTLC in it.
      edge_ab,
      edge_be,
      makeEdge(3L, a, c, 50 msat, 0, minHtlc = 100 msat, balance_opt = Some(10000 msat)),
      makeEdge(4L, c, e, 50 msat, 0, minHtlc = 100 msat, capacity = 25 sat),
      makeEdge(5L, a, d, 50 msat, 0, minHtlc = 100 msat, balance_opt = Some(10000 msat)),
      makeEdge(6L, d, e, 50 msat, 0, minHtlc = 100 msat, capacity = 25 sat),
    ))

    val pendingHtlcs = Seq(Route(5000 msat, ChannelHop(a, b, edge_ab.update) :: ChannelHop(b, e, edge_be.update) :: Nil))
    val Success(routes) = findMultiPartRoute(g, a, e, amount, maxFee, pendingHtlcs = pendingHtlcs, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(routes.forall(_.length == 2), routes)
    checkRouteAmounts(routes, amount, maxFee)
    checkIgnoredChannels(routes, 1L, 2L)
  }

  test("calculate multipart route for full amount or fail", Tag("fuzzy")) {
    //   +------------------------------------+
    //   |                                    |
    //   |                                    v
    // +---+                       +---+    +---+
    // | A |-----+      +--------->| B |--->| C |
    // +---+     |      |          +---+    +---+
    //   ^       |    +---+                   |
    //   |       +--->| E |----------+        |
    //   |            +---+          |        |
    //   |              ^            v        |
    // +---+            |          +---+      |
    // | D |------------+          | F |<-----+
    // +---+                       +---+
    //   |                           ^
    //   |                           |
    //   +---------------------------+
    for (_ <- 1 to 100) {
      val amount = (100 + Random.nextLong(200000)).msat
      val maxFee = 50.msat.max(amount * 0.03)
      val g = DirectedGraph(List(
        makeEdge(1L, d, f, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat, balance_opt = Some(Random.nextLong(2 * amount.toLong).msat)),
        makeEdge(2L, d, a, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat, balance_opt = Some(Random.nextLong(2 * amount.toLong).msat)),
        makeEdge(3L, d, e, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat, balance_opt = Some(Random.nextLong(2 * amount.toLong).msat)),
        makeEdge(4L, a, c, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat),
        makeEdge(5L, a, e, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat),
        makeEdge(6L, e, f, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat),
        makeEdge(7L, e, b, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat),
        makeEdge(8L, b, c, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat),
        makeEdge(9L, c, f, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat)
      ))

      findMultiPartRoute(g, d, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = true), currentBlockHeight = 400000) match {
        case Success(routes) => checkRouteAmounts(routes, amount, maxFee)
        case Failure(ex) => assert(ex === RouteNotFound)
      }
    }
  }

  test("loop trap") {
    //       +-----------------+
    //       |                 |
    //       |                 v
    // A --> B --> C --> D --> E
    //       ^     |
    //       |     |
    //       F <---+
    val g = DirectedGraph(List(
      makeEdge(1L, a, b, 1000 msat, 1000),
      makeEdge(2L, b, c, 1000 msat, 1000),
      makeEdge(3L, c, d, 1000 msat, 1000),
      makeEdge(4L, d, e, 1000 msat, 1000),
      makeEdge(5L, b, e, 1000 msat, 1000),
      makeEdge(6L, c, f, 1000 msat, 1000),
      makeEdge(7L, f, b, 1000 msat, 1000),
    ))

    val Success(routes) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 3, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(routes.length == 2)
    val route1 :: route2 :: Nil = routes
    assert(route2Ids(route1) === 1 :: 5 :: Nil)
    assert(route2Ids(route2) === 1 :: 2 :: 3 :: 4 :: Nil)
  }

  test("reversed loop trap") {
    //       +-----------------+
    //       |                 |
    //       v                 |
    // A <-- B <-- C <-- D <-- E
    //       |     ^
    //       |     |
    //       F ----+
    val g = DirectedGraph(List(
      makeEdge(1L, b, a, 1000 msat, 1000),
      makeEdge(2L, c, b, 1000 msat, 1000),
      makeEdge(3L, d, c, 1000 msat, 1000),
      makeEdge(4L, e, d, 1000 msat, 1000),
      makeEdge(5L, e, b, 1000 msat, 1000),
      makeEdge(6L, f, c, 1000 msat, 1000),
      makeEdge(7L, b, f, 1000 msat, 1000),
    ))

    val Success(routes) = findRoute(g, e, a, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 3, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(routes.length == 2)
    val route1 :: route2 :: Nil = routes
    assert(route2Ids(route1) === 5 :: 1 :: Nil)
    assert(route2Ids(route2) === 4 :: 3 :: 2 :: 1 :: Nil)
  }

  test("k-shortest paths must be distinct") {
    //   +----> N ---> N         N ---> N ----+
    //  /        \    /           \    /        \
    // A          +--+    (...)    +--+          B
    //  \        /    \           /    \        /
    //   +----> N ---> N         N ---> N ----+

    def makeEdges(n: Int): Seq[GraphEdge] = {
      val nodes = new Array[(PublicKey, PublicKey)](n)
      for (i <- nodes.indices) {
        nodes(i) = (randomKey().publicKey, randomKey().publicKey)
      }
      val q = new mutable.Queue[GraphEdge]
      // One path is shorter to maximise the overlap between the n-shortest paths, they will all be like the shortest path with a single hop changed.
      q.enqueue(makeEdge(1L, a, nodes(0)._1, 100 msat, 90))
      q.enqueue(makeEdge(2L, a, nodes(0)._2, 100 msat, 100))
      for (i <- 0 until (n - 1)) {
        q.enqueue(makeEdge(4 * i + 3, nodes(i)._1, nodes(i + 1)._1, 100 msat, 90))
        q.enqueue(makeEdge(4 * i + 4, nodes(i)._1, nodes(i + 1)._2, 100 msat, 90))
        q.enqueue(makeEdge(4 * i + 5, nodes(i)._2, nodes(i + 1)._1, 100 msat, 100))
        q.enqueue(makeEdge(4 * i + 6, nodes(i)._2, nodes(i + 1)._2, 100 msat, 100))
      }
      q.enqueue(makeEdge(4 * n, nodes(n - 1)._1, b, 100 msat, 90))
      q.enqueue(makeEdge(4 * n + 1, nodes(n - 1)._2, b, 100 msat, 100))
      q.toSeq
    }

    val g = DirectedGraph(makeEdges(10))

    val Success(routes) = findRoute(g, a, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 10, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(routes.distinct.length == 10)
  }

  test("all paths are shortest") {
    //   +----> N ---> N         N ---> N ----+
    //  /        \    /           \    /        \
    // A          +--+    (...)    +--+          B
    //  \        /    \           /    \        /
    //   +----> N ---> N         N ---> N ----+

    def makeEdges(n: Int): Seq[GraphEdge] = {
      val nodes = new Array[(PublicKey, PublicKey)](n)
      for (i <- nodes.indices) {
        nodes(i) = (randomKey().publicKey, randomKey().publicKey)
      }
      val q = new mutable.Queue[GraphEdge]
      q.enqueue(makeEdge(1L, a, nodes(0)._1, 100 msat, 100))
      q.enqueue(makeEdge(2L, a, nodes(0)._2, 100 msat, 100))
      for (i <- 0 until (n - 1)) {
        q.enqueue(makeEdge(4 * i + 3, nodes(i)._1, nodes(i + 1)._1, 100 msat, 100))
        q.enqueue(makeEdge(4 * i + 4, nodes(i)._1, nodes(i + 1)._2, 100 msat, 100))
        q.enqueue(makeEdge(4 * i + 5, nodes(i)._2, nodes(i + 1)._1, 100 msat, 100))
        q.enqueue(makeEdge(4 * i + 6, nodes(i)._2, nodes(i + 1)._2, 100 msat, 100))
      }
      q.enqueue(makeEdge(4 * n, nodes(n - 1)._1, b, 100 msat, 100))
      q.enqueue(makeEdge(4 * n + 1, nodes(n - 1)._2, b, 100 msat, 100))
      q.toSeq
    }

    val g = DirectedGraph(makeEdges(10))

    val Success(routes) = findRoute(g, a, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 10, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(routes.distinct.length == 10)
    val fees = routes.map(_.fee)
    assert(fees.forall(_ == fees.head))
  }

  test("penalty per hop") {
    //  S ---> A ---> B
    //         |      ^
    //         v      |
    //         C ---> D
    //         |      ^
    //         v      |
    //         E ---> F
    val start = randomKey().publicKey
    val g = DirectedGraph(List(
      makeEdge(0L, start, a, 0 msat, 0),
      makeEdge(1L, a, b, 1000 msat, 1000),
      makeEdge(2L, a, c, 0 msat, 0),
      makeEdge(3L, c, d, 700 msat, 1000),
      makeEdge(4L, d, b, 0 msat, 0),
      makeEdge(5L, c, e, 0 msat, 0),
      makeEdge(6L, e, f, 600 msat, 1000),
      makeEdge(7L, f, d, 0 msat, 0),
    ))

    { // No hop cost
      val Success(routes) = findRoute(g, start, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
      assert(routes.distinct.length == 1)
      val route :: Nil = routes
      assert(route2Ids(route) === 0 :: 2 :: 5 :: 6 :: 7 :: 4 :: Nil)
    }
    { // small base hop cost
      val Success(routes) = findRoute(g, start, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = WeightRatios(1, 0, 0, 0, 100 msat, 0)), currentBlockHeight = 400000)
      assert(routes.distinct.length == 1)
      val route :: Nil = routes
      assert(route2Ids(route) === 0 :: 2 :: 3 :: 4 :: Nil)
    }
    { // large proportional hop cost
      val Success(routes) = findRoute(g, start, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = WeightRatios(1, 0, 0, 0, 0 msat, 200)), currentBlockHeight = 400000)
      assert(routes.distinct.length == 1)
      val route :: Nil = routes
      assert(route2Ids(route) === 0 :: 1 :: Nil)
    }
  }
}

object RouteCalculationSpec {

  val noopBoundaries = { _: RichWeight => true }

  val DEFAULT_AMOUNT_MSAT = 10000000 msat
  val DEFAULT_MAX_FEE = 100000 msat
  val DEFAULT_CAPACITY = 100000 sat

  val NO_WEIGHT_RATIOS: WeightRatios = WeightRatios(1, 0, 0, 0, 0 msat, 0)
  val DEFAULT_ROUTE_PARAMS = RouteParams(randomize = false, 21000 msat, 0.03, 6, CltvExpiryDelta(2016), NO_WEIGHT_RATIOS, MultiPartParams(1000 msat, 10))

  val DUMMY_SIG = Transactions.PlaceHolderSig

  def makeChannel(shortChannelId: Long, nodeIdA: PublicKey, nodeIdB: PublicKey): ChannelAnnouncement = {
    val (nodeId1, nodeId2) = if (Announcements.isNode1(nodeIdA, nodeIdB)) (nodeIdA, nodeIdB) else (nodeIdB, nodeIdA)
    ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, Features.empty, Block.RegtestGenesisBlock.hash, ShortChannelId(shortChannelId), nodeId1, nodeId2, randomKey().publicKey, randomKey().publicKey)
  }

  def makeEdge(shortChannelId: Long,
               nodeId1: PublicKey,
               nodeId2: PublicKey,
               feeBase: MilliSatoshi,
               feeProportionalMillionth: Int,
               minHtlc: MilliSatoshi = DEFAULT_AMOUNT_MSAT,
               maxHtlc: Option[MilliSatoshi] = None,
               cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0),
               capacity: Satoshi = DEFAULT_CAPACITY,
               balance_opt: Option[MilliSatoshi] = None): GraphEdge = {
    val update = makeUpdateShort(ShortChannelId(shortChannelId), nodeId1, nodeId2, feeBase, feeProportionalMillionth, minHtlc, maxHtlc, cltvDelta)
    GraphEdge(ChannelDesc(ShortChannelId(shortChannelId), nodeId1, nodeId2), update, capacity, balance_opt)
  }

  def makeUpdateShort(shortChannelId: ShortChannelId, nodeId1: PublicKey, nodeId2: PublicKey, feeBase: MilliSatoshi, feeProportionalMillionth: Int, minHtlc: MilliSatoshi = DEFAULT_AMOUNT_MSAT, maxHtlc: Option[MilliSatoshi] = None, cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0), timestamp: Long = 0): ChannelUpdate =
    ChannelUpdate(
      signature = DUMMY_SIG,
      chainHash = Block.RegtestGenesisBlock.hash,
      shortChannelId = shortChannelId,
      timestamp = timestamp,
      messageFlags = maxHtlc match {
        case Some(_) => 1
        case None => 0
      },
      channelFlags = if (Announcements.isNode1(nodeId1, nodeId2)) 0 else 1,
      cltvExpiryDelta = cltvDelta,
      htlcMinimumMsat = minHtlc,
      feeBaseMsat = feeBase,
      feeProportionalMillionths = feeProportionalMillionth,
      htlcMaximumMsat = maxHtlc
    )

  def hops2Ids(hops: Seq[ChannelHop]): Seq[Long] = hops.map(hop => hop.lastUpdate.shortChannelId.toLong)

  def route2Ids(route: Route): Seq[Long] = hops2Ids(route.hops)

  def routes2Ids(routes: Seq[Route]): Set[Seq[Long]] = routes.map(route2Ids).toSet

  def route2Edges(route: Route): Seq[GraphEdge] = route.hops.map(hop => GraphEdge(ChannelDesc(hop.lastUpdate.shortChannelId, hop.nodeId, hop.nextNodeId), hop.lastUpdate, 0 sat, None))

  def route2Nodes(route: Route): Seq[(PublicKey, PublicKey)] = route.hops.map(hop => (hop.nodeId, hop.nextNodeId))

  def checkIgnoredChannels(routes: Seq[Route], shortChannelIds: Long*): Unit = {
    shortChannelIds.foreach(shortChannelId => routes.foreach(route => {
      assert(route.hops.forall(_.lastUpdate.shortChannelId.toLong != shortChannelId), route)
    }))
  }

  def checkRouteAmounts(routes: Seq[Route], totalAmount: MilliSatoshi, maxFee: MilliSatoshi): Unit = {
    assert(routes.map(_.amount).sum == totalAmount, routes)
    assert(routes.map(_.fee).sum <= maxFee, routes)
  }

}
