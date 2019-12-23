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
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64, Satoshi}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{RichWeight, WeightRatios}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, MilliSatoshi, ShortChannelId, ToMilliSatoshiConversion, randomKey}
import org.scalatest.{FunSuite, ParallelTestExecution}
import scodec.bits._

import scala.collection.immutable.SortedMap
import scala.util.{Failure, Success}

/**
 * Created by PM on 31/05/2016.
 */

class RouteCalculationSpec extends FunSuite with ParallelTestExecution {

  import RouteCalculationSpec._

  val (a, b, c, d, e, f) = (randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)

  test("calculate simple route") {
    val updates = List(
      makeUpdate(1L, a, b, 1 msat, 10, cltvDelta = CltvExpiryDelta(1)),
      makeUpdate(2L, b, c, 1 msat, 10, cltvDelta = CltvExpiryDelta(1)),
      makeUpdate(3L, c, d, 1 msat, 10, cltvDelta = CltvExpiryDelta(1)),
      makeUpdate(4L, d, e, 1 msat, 10, cltvDelta = CltvExpiryDelta(1))
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)

    assert(route.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("check fee against max pct properly") {
    // fee is acceptable is it is either
    // - below our maximum fee base
    // - below our maximum fraction of the paid amount

    // here we have a maximum fee base of 1 msat, and all our updates have a base fee of 10 msat
    // so our fee will always be above the base fee, and we will always check that it is below our maximum percentage
    // of the amount being paid

    val updates = List(
      makeUpdate(1L, a, b, 10 msat, 10, cltvDelta = CltvExpiryDelta(1)),
      makeUpdate(2L, b, c, 10 msat, 10, cltvDelta = CltvExpiryDelta(1)),
      makeUpdate(3L, c, d, 10 msat, 10, cltvDelta = CltvExpiryDelta(1)),
      makeUpdate(4L, d, e, 10 msat, 10, cltvDelta = CltvExpiryDelta(1))
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(maxFeeBase = 1 msat), currentBlockHeight = 400000)

    assert(route.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
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

    val amount = 10000 msat
    val expectedCost = 10007 msat

    val updates = List(
      makeUpdate(1L, a, b, feeBase = 1 msat, feeProportionalMillionth = 200, minHtlc = 0 msat),
      makeUpdate(4L, a, e, feeBase = 1 msat, feeProportionalMillionth = 200, minHtlc = 0 msat),
      makeUpdate(2L, b, c, feeBase = 1 msat, feeProportionalMillionth = 300, minHtlc = 0 msat),
      makeUpdate(3L, c, d, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat),
      makeUpdate(5L, e, f, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat),
      makeUpdate(6L, f, d, feeBase = 1 msat, feeProportionalMillionth = 100, minHtlc = 0 msat)
    ).toMap

    val graph = makeGraph(updates)

    val Success(route) = Router.findRoute(graph, a, d, amount, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)

    val totalCost = Graph.pathWeight(hops2Edges(route), amount, isPartial = false, 0, None).cost

    assert(hops2Ids(route) === 4 :: 5 :: 6 :: Nil)
    assert(totalCost === expectedCost)

    // now channel 5 could route the amount (10000) but not the amount + fees (10007)
    val (desc, update) = makeUpdate(5L, e, f, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat, maxHtlc = Some(10005 msat))
    val graph1 = graph.addEdge(desc, update)

    val Success(route1) = Router.findRoute(graph1, a, d, amount, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)

    assert(hops2Ids(route1) === 1 :: 2 :: 3 :: Nil)
  }

  test("calculate route considering the direct channel pays no fees") {
    val updates = List(
      makeUpdate(1L, a, b, 5 msat, 0), // a -> b
      makeUpdate(2L, a, d, 15 msat, 0), // a -> d  this goes a bit closer to the target and asks for higher fees but is a direct channel
      makeUpdate(3L, b, c, 5 msat, 0), // b -> c
      makeUpdate(4L, c, d, 5 msat, 0), // c -> d
      makeUpdate(5L, d, e, 5 msat, 0) // d -> e
    ).toMap

    val g = makeGraph(updates)
    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)

    assert(route.map(hops2Ids) === Success(2 :: 5 :: Nil))
  }

  test("calculate simple route (add and remove edges") {
    val updates = List(
      makeUpdate(1L, a, b, 0 msat, 0),
      makeUpdate(2L, b, c, 0 msat, 0),
      makeUpdate(3L, c, d, 0 msat, 0),
      makeUpdate(4L, d, e, 0 msat, 0)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))

    val graphWithRemovedEdge = g.removeEdge(ChannelDesc(ShortChannelId(3L), c, d))
    val route2 = Router.findRoute(graphWithRemovedEdge, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("calculate the shortest path (hardcoded nodes)") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // target
    )

    val updates = List(
      makeUpdate(1L, f, g, 0 msat, 0),
      makeUpdate(2L, g, h, 0 msat, 0),
      makeUpdate(3L, h, i, 0 msat, 0),
      makeUpdate(4L, f, h, 50 msat, 0) // more expensive
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Success(4 :: 3 :: Nil))

  }

  test("calculate the shortest path (select direct channel)") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // target
    )

    val updates = List(
      makeUpdate(1L, f, g, 0 msat, 0),
      makeUpdate(4L, f, i, 50 msat, 0), // our starting node F has a direct channel with I
      makeUpdate(2L, g, h, 0 msat, 0),
      makeUpdate(3L, h, i, 0 msat, 0)
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 2, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Success(4 :: Nil))
  }

  test("find a route using channels with htlMaximumMsat close to the payment amount") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
    )

    val updates = List(
      makeUpdate(1L, f, g, 1 msat, 0),
      // the maximum htlc allowed by this channel is only 50msat greater than what we're sending
      makeUpdate(2L, g, h, 1 msat, 0, maxHtlc = Some(DEFAULT_AMOUNT_MSAT + 50.msat)),
      makeUpdate(3L, h, i, 1 msat, 0)
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.map(hops2Ids) == Success(1 :: 2 :: 3 :: Nil))
  }

  test("find a route using channels with htlMinimumMsat close to the payment amount") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
    )

    val updates = List(
      makeUpdate(1L, f, g, 1 msat, 0),
      // this channel requires a minimum amount that is larger than what we are sending
      makeUpdate(2L, g, h, 1 msat, 0, minHtlc = DEFAULT_AMOUNT_MSAT + 50.msat),
      makeUpdate(3L, h, i, 1 msat, 0)
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("if there are multiple channels between the same node, select the cheapest") {
    val (f, g, h, i) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
    )

    val updates = List(
      makeUpdate(1L, f, g, 0 msat, 0),
      makeUpdate(2L, g, h, 5 msat, 5), // expensive  g -> h channel
      makeUpdate(6L, g, h, 0 msat, 0), // cheap      g -> h channel
      makeUpdate(3L, h, i, 0 msat, 0)
    ).toMap

    val graph = makeGraph(updates)

    val route = Router.findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Success(1 :: 6 :: 3 :: Nil))
  }

  test("calculate longer but cheaper route") {
    val updates = List(
      makeUpdate(1L, a, b, 0 msat, 0),
      makeUpdate(2L, b, c, 0 msat, 0),
      makeUpdate(3L, c, d, 0 msat, 0),
      makeUpdate(4L, d, e, 0 msat, 0),
      makeUpdate(5L, b, e, 10 msat, 10)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("no local channels") {
    val updates = List(
      makeUpdate(2L, b, c, 0 msat, 0),
      makeUpdate(4L, d, e, 0 msat, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("route not found") {
    val updates = List(
      makeUpdate(1L, a, b, 0 msat, 0),
      makeUpdate(2L, b, c, 0 msat, 0),
      makeUpdate(4L, d, e, 0 msat, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("route not found (source OR target node not connected)") {
    val updates = List(
      makeUpdate(2L, b, c, 0 msat, 0),
      makeUpdate(4L, c, d, 0 msat, 0)
    ).toMap

    val g = makeGraph(updates).addVertex(a).addVertex(e)

    assert(Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000) === Failure(RouteNotFound))
    assert(Router.findRoute(g, b, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000) === Failure(RouteNotFound))
  }

  test("route not found (amount too high OR too low)") {
    val highAmount = DEFAULT_AMOUNT_MSAT * 10
    val lowAmount = DEFAULT_AMOUNT_MSAT / 10

    val updatesHi = List(
      makeUpdate(1L, a, b, 0 msat, 0),
      makeUpdate(2L, b, c, 0 msat, 0, maxHtlc = Some(DEFAULT_AMOUNT_MSAT)),
      makeUpdate(3L, c, d, 0 msat, 0)
    ).toMap

    val updatesLo = List(
      makeUpdate(1L, a, b, 0 msat, 0),
      makeUpdate(2L, b, c, 0 msat, 0, minHtlc = DEFAULT_AMOUNT_MSAT),
      makeUpdate(3L, c, d, 0 msat, 0)
    ).toMap

    val g = makeGraph(updatesHi)
    val g1 = makeGraph(updatesLo)

    assert(Router.findRoute(g, a, d, highAmount, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000) === Failure(RouteNotFound))
    assert(Router.findRoute(g1, a, d, lowAmount, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000) === Failure(RouteNotFound))
  }

  test("route to self") {
    val updates = List(
      makeUpdate(1L, a, b, 0 msat, 0),
      makeUpdate(2L, b, c, 0 msat, 0),
      makeUpdate(3L, c, d, 0 msat, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, a, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Failure(CannotRouteToSelf))
  }

  test("route to immediate neighbor") {
    val updates = List(
      makeUpdate(1L, a, b, 0 msat, 0),
      makeUpdate(2L, b, c, 0 msat, 0),
      makeUpdate(3L, c, d, 0 msat, 0),
      makeUpdate(4L, d, e, 0 msat, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, b, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Success(1 :: Nil))
  }

  test("directed graph") {
    val updates = List(
      makeUpdate(1L, a, b, 0 msat, 0),
      makeUpdate(2L, b, c, 0 msat, 0),
      makeUpdate(3L, c, d, 0 msat, 0),
      makeUpdate(4L, d, e, 0 msat, 0)
    ).toMap

    // a->e works, e->a fails

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))

    val route2 = Router.findRoute(g, e, a, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2.map(hops2Ids) === Failure(RouteNotFound))
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

    val hops = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000).get

    assert(hops === ChannelHop(a, b, uab) :: ChannelHop(b, c, ubc) :: ChannelHop(c, d, ucd) :: ChannelHop(d, e, ude) :: Nil)
  }

  test("convert extra hops to assisted channels") {
    val a = randomKey.publicKey
    val b = randomKey.publicKey
    val c = randomKey.publicKey
    val d = randomKey.publicKey
    val e = randomKey.publicKey

    val extraHop1 = ExtraHop(a, ShortChannelId(1), 12.sat.toMilliSatoshi, 10000, CltvExpiryDelta(12))
    val extraHop2 = ExtraHop(b, ShortChannelId(2), 200.sat.toMilliSatoshi, 0, CltvExpiryDelta(22))
    val extraHop3 = ExtraHop(c, ShortChannelId(3), 150.sat.toMilliSatoshi, 0, CltvExpiryDelta(32))
    val extraHop4 = ExtraHop(d, ShortChannelId(4), 50.sat.toMilliSatoshi, 0, CltvExpiryDelta(42))
    val extraHops = extraHop1 :: extraHop2 :: extraHop3 :: extraHop4 :: Nil

    val amount = 900 sat // below RoutingHeuristics.CAPACITY_CHANNEL_LOW
    val assistedChannels = Router.toAssistedChannels(extraHops, e, amount.toMilliSatoshi)

    assert(assistedChannels(extraHop4.shortChannelId) === AssistedChannel(extraHop4, e, 1050.sat.toMilliSatoshi))
    assert(assistedChannels(extraHop3.shortChannelId) === AssistedChannel(extraHop3, d, 1200.sat.toMilliSatoshi))
    assert(assistedChannels(extraHop2.shortChannelId) === AssistedChannel(extraHop2, c, 1400.sat.toMilliSatoshi))
    assert(assistedChannels(extraHop1.shortChannelId) === AssistedChannel(extraHop1, b, 1426.sat.toMilliSatoshi))
  }

  test("blacklist routes") {
    val updates = List(
      makeUpdate(1L, a, b, 0 msat, 0),
      makeUpdate(2L, b, c, 0 msat, 0),
      makeUpdate(3L, c, d, 0 msat, 0),
      makeUpdate(4L, d, e, 0 msat, 0)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, ignoredEdges = Set(ChannelDesc(ShortChannelId(3L), c, d)), routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route1.map(hops2Ids) === Failure(RouteNotFound))

    // verify that we left the graph untouched
    assert(g.containsEdge(makeUpdate(3L, c, d, 0 msat, 0)._1)) // c -> d
    assert(g.containsVertex(c))
    assert(g.containsVertex(d))

    // make sure we can find a route if without the blacklist
    val route2 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("route to a destination that is not in the graph (with assisted routes)") {
    val updates = List(
      makeUpdate(1L, a, b, 10 msat, 10),
      makeUpdate(2L, b, c, 10 msat, 10),
      makeUpdate(3L, c, d, 10 msat, 10)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))

    // now we add the missing edge to reach the destination
    val (extraDesc, extraUpdate) = makeUpdate(4L, d, e, 5 msat, 5)
    val extraGraphEdges = Set(GraphEdge(extraDesc, extraUpdate))

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, extraEdges = extraGraphEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("verify that extra hops takes precedence over known channels") {
    val updates = List(
      makeUpdate(1L, a, b, 10 msat, 10),
      makeUpdate(2L, b, c, 10 msat, 10),
      makeUpdate(3L, c, d, 10 msat, 10),
      makeUpdate(4L, d, e, 10 msat, 10)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
    assert(route1.get(1).lastUpdate.feeBaseMsat === 10.msat)

    val (extraDesc, extraUpdate) = makeUpdate(2L, b, c, 5 msat, 5)

    val extraGraphEdges = Set(GraphEdge(extraDesc, extraUpdate))

    val route2 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, extraEdges = extraGraphEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route2.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
    assert(route2.get(1).lastUpdate.feeBaseMsat === 5.msat)
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
      makeUpdate(1L, a, b, 10 msat, 10),
      makeUpdate(2L, b, c, 10 msat, 10),
      makeUpdate(2L, c, b, 10 msat, 10),
      makeUpdate(3L, c, d, 10 msat, 10),
      makeUpdate(4L, d, e, 10 msat, 10),
      makeUpdate(5L, f, g, 10 msat, 10),
      makeUpdate(6L, f, h, 10 msat, 10),
      makeUpdate(7L, h, i, 10 msat, 10),
      makeUpdate(8L, i, j, 10 msat, 10)
    ).toMap

    val publicChannels = channels.map { case (shortChannelId, announcement) =>
      val (_, update) = updates.find { case (d, _) => d.shortChannelId == shortChannelId }.get
      val (update_1_opt, update_2_opt) = if (Announcements.isNode1(update.channelFlags)) (Some(update), None) else (None, Some(update))
      val pc = PublicChannel(announcement, ByteVector32.Zeroes, Satoshi(1000), update_1_opt, update_2_opt)
      (shortChannelId, pc)
    }

    val ignored = Router.getIgnoredChannelDesc(publicChannels, ignoreNodes = Set(c, j, randomKey.publicKey))

    assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(2L), b, c)))
    assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(2L), c, b)))
    assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(3L), c, d)))
    assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(8L), i, j)))
  }

  test("limit routes to 20 hops") {
    val nodes = (for (_ <- 0 until 22) yield randomKey.publicKey).toList
    val updates = nodes
      .zip(nodes.drop(1)) // (0, 1) :: (1, 2) :: ...
      .zipWithIndex // ((0, 1), 0) :: ((1, 2), 1) :: ...
      .map { case ((na, nb), index) => makeUpdate(index, na, nb, 5 msat, 0) }
      .toMap

    val g = makeGraph(updates)

    assert(Router.findRoute(g, nodes(0), nodes(18), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000).map(hops2Ids) === Success(0 until 18))
    assert(Router.findRoute(g, nodes(0), nodes(19), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000).map(hops2Ids) === Success(0 until 19))
    assert(Router.findRoute(g, nodes(0), nodes(20), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000).map(hops2Ids) === Success(0 until 20))
    assert(Router.findRoute(g, nodes(0), nodes(21), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000).map(hops2Ids) === Failure(RouteNotFound))
  }

  test("ignore cheaper route when it has more than 20 hops") {
    val nodes = (for (_ <- 0 until 50) yield randomKey.publicKey).toList

    val updates = nodes
      .zip(nodes.drop(1)) // (0, 1) :: (1, 2) :: ...
      .zipWithIndex // ((0, 1), 0) :: ((1, 2), 1) :: ...
      .map { case ((na, nb), index) => makeUpdate(index, na, nb, 1 msat, 0) }
      .toMap

    val updates2 = updates + makeUpdate(99, nodes(2), nodes(48), 1000 msat, 0) // expensive shorter route

    val g = makeGraph(updates2)

    val route = Router.findRoute(g, nodes(0), nodes(49), DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Success(0 :: 1 :: 99 :: 48 :: Nil))
  }

  test("ignore cheaper route when it has more than the requested CLTV") {
    val f = randomKey.publicKey

    val g = makeGraph(List(
      makeUpdate(1, a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(50)),
      makeUpdate(2, b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(50)),
      makeUpdate(3, c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(50)),
      makeUpdate(4, a, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(5, e, f, feeBase = 5 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(6, f, d, feeBase = 5 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9))
    ).toMap)

    val route = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(routeMaxCltv = CltvExpiryDelta(28)), currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Success(4 :: 5 :: 6 :: Nil))
  }

  test("ignore cheaper route when it grows longer than the requested size") {
    val f = randomKey.publicKey

    val g = makeGraph(List(
      makeUpdate(1, a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(2, b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(3, c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(4, d, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(5, e, f, feeBase = 5 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(6, b, f, feeBase = 5 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9))
    ).toMap)

    val route = Router.findRoute(g, a, f, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(routeMaxLength = 3), currentBlockHeight = 400000)
    assert(route.map(hops2Ids) === Success(1 :: 6 :: Nil))
  }

  test("ignore loops") {
    val updates = List(
      makeUpdate(1L, a, b, 10 msat, 10),
      makeUpdate(2L, b, c, 10 msat, 10),
      makeUpdate(3L, c, a, 10 msat, 10),
      makeUpdate(4L, c, d, 10 msat, 10),
      makeUpdate(5L, d, e, 10 msat, 10)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 4 :: 5 :: Nil))
  }

  test("ensure the route calculation terminates correctly when selecting 0-fees edges") {
    // the graph contains a possible 0-cost path that goes back on its steps ( e -> f, f -> e )
    val updates = List(
      makeUpdate(1L, a, b, 10 msat, 10), // a -> b
      makeUpdate(2L, b, c, 10 msat, 10),
      makeUpdate(4L, c, d, 10 msat, 10),
      makeUpdate(3L, b, e, 0 msat, 0), // b -> e
      makeUpdate(6L, e, f, 0 msat, 0), // e -> f
      makeUpdate(6L, f, e, 0 msat, 0), // e <- f
      makeUpdate(5L, e, d, 0 msat, 0) // e -> d
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(route1.map(hops2Ids) === Success(1 :: 3 :: 5 :: Nil))
  }

  // @formatter:off
  /**
   * +---+            +---+            +---+
   * | A +-----+      | B +----------> | C |
   * +-+-+     |      +-+-+            +-+-+
   *   ^       |        ^                |
   *   |       |        |                |
   *   |       v----> + |                |
   * +-+-+            <-+-+            +-+-+
   * | D +----------> | E +----------> | F |
   * +---+            +---+            +---+
   */
  // @formatter:on
  test("find the k-shortest paths in a graph, k=4") {
    val (a, b, c, d, e, f) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), //a
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), //b
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), //c
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), //d
      PublicKey(hex"02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"), //e
      PublicKey(hex"03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc") //f
    )

    val edges = Seq(
      makeUpdate(1L, d, a, 1 msat, 0),
      makeUpdate(2L, d, e, 1 msat, 0),
      makeUpdate(3L, a, e, 1 msat, 0),
      makeUpdate(4L, e, b, 1 msat, 0),
      makeUpdate(5L, e, f, 1 msat, 0),
      makeUpdate(6L, b, c, 1 msat, 0),
      makeUpdate(7L, c, f, 1 msat, 0)
    )

    val graph = DirectedGraph().addEdges(edges)

    val fourShortestPaths = Graph.yenKshortestPaths(graph, d, f, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 4, None, 0, noopBoundaries)

    assert(fourShortestPaths.size === 4)
    assert(hops2Ids(fourShortestPaths(0).path.map(graphEdgeToHop)) === 2 :: 5 :: Nil) // D -> E -> F
    assert(hops2Ids(fourShortestPaths(1).path.map(graphEdgeToHop)) === 1 :: 3 :: 5 :: Nil) // D -> A -> E -> F
    assert(hops2Ids(fourShortestPaths(2).path.map(graphEdgeToHop)) === 2 :: 4 :: 6 :: 7 :: Nil) // D -> E -> B -> C -> F
    assert(hops2Ids(fourShortestPaths(3).path.map(graphEdgeToHop)) === 1 :: 3 :: 4 :: 6 :: 7 :: Nil) // D -> A -> E -> B -> C -> F
  }

  test("find the k shortest path (wikipedia example)") {
    val (c, d, e, f, g, h) = (
      PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), //c
      PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), //d
      PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), //e
      PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), //f
      PublicKey(hex"02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"), //g
      PublicKey(hex"03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc") //h
    )

    val edges = Seq(
      makeUpdate(10L, c, e, 2 msat, 0),
      makeUpdate(20L, c, d, 3 msat, 0),
      makeUpdate(30L, d, f, 4 msat, 5), // D- > F has a higher cost to distinguish it from the 2nd cheapest route
      makeUpdate(40L, e, d, 1 msat, 0),
      makeUpdate(50L, e, f, 2 msat, 0),
      makeUpdate(60L, e, g, 3 msat, 0),
      makeUpdate(70L, f, g, 2 msat, 0),
      makeUpdate(80L, f, h, 1 msat, 0),
      makeUpdate(90L, g, h, 2 msat, 0)
    )

    val graph = DirectedGraph().addEdges(edges)

    val twoShortestPaths = Graph.yenKshortestPaths(graph, c, h, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 2, None, 0, noopBoundaries)

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
      makeUpdate(1L, a, b, 1 msat, 0),
      makeUpdate(1L, b, a, 1 msat, 0),
      makeUpdate(2L, b, c, 1 msat, 0),
      makeUpdate(2L, c, b, 1 msat, 0),
      makeUpdate(3L, c, f, 1 msat, 0),
      makeUpdate(3L, f, c, 1 msat, 0),
      makeUpdate(4L, c, d, 1 msat, 0),
      makeUpdate(4L, d, c, 1 msat, 0),
      makeUpdate(41L, d, c, 1 msat, 0), // there is more than one D -> C channel
      makeUpdate(5L, d, e, 1 msat, 0),
      makeUpdate(5L, e, d, 1 msat, 0),
      makeUpdate(6L, e, f, 1 msat, 0),
      makeUpdate(6L, f, e, 1 msat, 0)
    )

    val graph = DirectedGraph().addEdges(edges)

    //we ask for 3 shortest paths but only 2 can be found
    val foundPaths = Graph.yenKshortestPaths(graph, a, f, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 3, None, 0, noopBoundaries)

    assert(foundPaths.size === 2)
    assert(hops2Ids(foundPaths(0).path.map(graphEdgeToHop)) === 1 :: 2 :: 3 :: Nil) // A -> B -> C -> F
    assert(hops2Ids(foundPaths(1).path.map(graphEdgeToHop)) === 1 :: 2 :: 4 :: 5 :: 6 :: Nil) // A -> B -> C -> D -> E -> F
  }

  test("select a random route below the requested fee") {
    val strictFeeParams = DEFAULT_ROUTE_PARAMS.copy(maxFeeBase = 7 msat, maxFeePct = 0)

    // A -> B -> C -> D has total cost of 10000005
    // A -> E -> C -> D has total cost of 11080003 !!
    // A -> E -> F -> D has total cost of 10000006
    val g = makeGraph(List(
      makeUpdate(1L, a, b, feeBase = 1 msat, 0),
      makeUpdate(4L, a, e, feeBase = 1 msat, 0),
      makeUpdate(2L, b, c, feeBase = 2 msat, 0),
      makeUpdate(3L, c, d, feeBase = 3 msat, 0),
      makeUpdate(5L, e, f, feeBase = 3 msat, 0),
      makeUpdate(6L, f, d, feeBase = 3 msat, 0),
      makeUpdate(7L, e, c, feeBase = 9 msat, 0)
    ).toMap)

    (for {_ <- 0 to 10} yield Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 3, routeParams = strictFeeParams, currentBlockHeight = 400000)).map {
      case Failure(thr) => fail(thr)
      case Success(someRoute) =>

        val routeCost = Graph.pathWeight(hops2Edges(someRoute), DEFAULT_AMOUNT_MSAT, isPartial = false, 0, None).cost - DEFAULT_AMOUNT_MSAT

        // over the three routes we could only get the 2 cheapest because the third is too expensive (over 7msat of fees)
        assert(routeCost === 5.msat || routeCost === 6.msat)
    }
  }

  test("Use weight ratios to when computing the edge weight") {
    val largeCapacity = 8000000000L msat

    // A -> B -> C -> D is 'fee optimized', lower fees route (totFees = 2, totCltv = 4000)
    // A -> E -> F -> D is 'timeout optimized', lower CLTV route (totFees = 3, totCltv = 18)
    // A -> E -> C -> D is 'capacity optimized', more recent channel/larger capacity route
    val updates = List(
      makeUpdate(1L, a, b, feeBase = 0 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(13)),
      makeUpdate(4L, a, e, feeBase = 0 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(12)),
      makeUpdate(2L, b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(500)),
      makeUpdate(3L, c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(500)),
      makeUpdate(5L, e, f, feeBase = 2 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(6L, f, d, feeBase = 2 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
      makeUpdate(7L, e, c, feeBase = 2 msat, 0, minHtlc = 0 msat, maxHtlc = Some(largeCapacity), CltvExpiryDelta(12))
    ).toMap

    val g = makeGraph(updates)

    val Success(routeFeeOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 0, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = 400000)
    assert(hops2Nodes(routeFeeOptimized) === (a, b) :: (b, c) :: (c, d) :: Nil)

    val Success(routeCltvOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 0, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      cltvDeltaFactor = 1,
      ageFactor = 0,
      capacityFactor = 0
    ))), currentBlockHeight = 400000)

    assert(hops2Nodes(routeCltvOptimized) === (a, e) :: (e, f) :: (f, d) :: Nil)

    val Success(routeCapacityOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 0, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      cltvDeltaFactor = 0,
      ageFactor = 0,
      capacityFactor = 1
    ))), currentBlockHeight = 400000)

    assert(hops2Nodes(routeCapacityOptimized) === (a, e) :: (e, c) :: (c, d) :: Nil)
  }

  test("prefer going through an older channel if fees and CLTV are the same") {
    val currentBlockHeight = 554000

    val g = makeGraph(List(
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight}x0x1"), a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight}x0x4"), a, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight - 3000}x0x2"), b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)), // younger channel
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight - 3000}x0x3"), c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight}x0x5"), e, f, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"${currentBlockHeight}x0x6"), f, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144))
    ).toMap)

    val Success(routeScoreOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT / 2, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      ageFactor = 0.33,
      cltvDeltaFactor = 0.33,
      capacityFactor = 0.33
    ))), currentBlockHeight = currentBlockHeight)

    assert(hops2Nodes(routeScoreOptimized) === (a, b) :: (b, c) :: (c, d) :: Nil)
  }

  test("prefer a route with a smaller total CLTV if fees and score are the same") {
    val g = makeGraph(List(
      makeUpdateShort(ShortChannelId(s"0x0x1"), a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeUpdateShort(ShortChannelId(s"0x0x4"), a, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeUpdateShort(ShortChannelId(s"0x0x2"), b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(10)), // smaller CLTV
      makeUpdateShort(ShortChannelId(s"0x0x3"), c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeUpdateShort(ShortChannelId(s"0x0x5"), e, f, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
      makeUpdateShort(ShortChannelId(s"0x0x6"), f, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12))
    ).toMap)

    val Success(routeScoreOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      ageFactor = 0.33,
      cltvDeltaFactor = 0.33,
      capacityFactor = 0.33
    ))), currentBlockHeight = 400000)

    assert(hops2Nodes(routeScoreOptimized) === (a, b) :: (b, c) :: (c, d) :: Nil)
  }

  test("avoid a route that breaks off the max CLTV") {
    // A -> B -> C -> D is cheaper but has a total CLTV > 2016!
    // A -> E -> F -> D is more expensive but has a total CLTV < 2016
    val g = makeGraph(List(
      makeUpdateShort(ShortChannelId(s"0x0x1"), a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"0x0x4"), a, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"0x0x2"), b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(1000)),
      makeUpdateShort(ShortChannelId(s"0x0x3"), c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(900)),
      makeUpdateShort(ShortChannelId(s"0x0x5"), e, f, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
      makeUpdateShort(ShortChannelId(s"0x0x6"), f, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144))
    ).toMap)

    val Success(routeScoreOptimized) = Router.findRoute(g, a, d, DEFAULT_AMOUNT_MSAT / 2, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(ratios = Some(WeightRatios(
      ageFactor = 0.33,
      cltvDeltaFactor = 0.33,
      capacityFactor = 0.33
    ))), currentBlockHeight = 400000)

    assert(hops2Nodes(routeScoreOptimized) === (a, e) :: (e, f) :: (f, d) :: Nil)
  }

  test("cost function is monotonic") {
    // This test have a channel (542280x2156x0) that according to heuristics is very convenient but actually useless to reach the target,
    // then if the cost function is not monotonic the path-finding breaks because the result path contains a loop.
    val updates = SortedMap(
      ShortChannelId("565643x1216x0") -> PublicChannel(
        ann = makeChannel(ShortChannelId("565643x1216x0").toLong, PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca")),
        fundingTxid = ByteVector32.Zeroes,
        capacity = 0 sat,
        update_1_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("565643x1216x0"), 0, 1.toByte, 0.toByte, CltvExpiryDelta(14), htlcMinimumMsat = 1 msat, feeBaseMsat = 1000 msat, 10, Some(4294967295L msat))),
        update_2_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("565643x1216x0"), 0, 1.toByte, 1.toByte, CltvExpiryDelta(144), htlcMinimumMsat = 0 msat, feeBaseMsat = 1000 msat, 100, Some(15000000000L msat)))
      ),
      ShortChannelId("542280x2156x0") -> PublicChannel(
        ann = makeChannel(ShortChannelId("542280x2156x0").toLong, PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), PublicKey(hex"03cb7983dc247f9f81a0fa2dfa3ce1c255365f7279c8dd143e086ca333df10e278")),
        fundingTxid = ByteVector32.Zeroes,
        capacity = 0 sat,
        update_1_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("542280x2156x0"), 0, 1.toByte, 0.toByte, CltvExpiryDelta(144), htlcMinimumMsat = 1000 msat, feeBaseMsat = 1000 msat, 100, Some(16777000000L msat))),
        update_2_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("542280x2156x0"), 0, 1.toByte, 1.toByte, CltvExpiryDelta(144), htlcMinimumMsat = 1 msat, feeBaseMsat = 667 msat, 1, Some(16777000000L msat)))
      ),
      ShortChannelId("565779x2711x0") -> PublicChannel(
        ann = makeChannel(ShortChannelId("565779x2711x0").toLong, PublicKey(hex"036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96"), PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")),
        fundingTxid = ByteVector32.Zeroes,
        capacity = 0 sat,
        update_1_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("565779x2711x0"), 0, 1.toByte, 0.toByte, CltvExpiryDelta(144), htlcMinimumMsat = 1 msat, feeBaseMsat = 1000 msat, 100, Some(230000000L msat))),
        update_2_opt = Some(ChannelUpdate(ByteVector64.Zeroes, ByteVector32.Zeroes, ShortChannelId("565779x2711x0"), 0, 1.toByte, 3.toByte, CltvExpiryDelta(144), htlcMinimumMsat = 1 msat, feeBaseMsat = 1000 msat, 100, Some(230000000L msat)))
      )
    )

    val g = DirectedGraph.makeGraph(updates)

    val params = RouteParams(randomize = false, maxFeeBase = 21000 msat, maxFeePct = 0.03, routeMaxCltv = CltvExpiryDelta(1008), routeMaxLength = 6, ratios = Some(
      WeightRatios(cltvDeltaFactor = 0.15, ageFactor = 0.35, capacityFactor = 0.5)
    ))
    val thisNode = PublicKey(hex"036d65409c41ab7380a43448f257809e7496b52bf92057c09c4f300cbd61c50d96")
    val targetNode = PublicKey(hex"024655b768ef40951b20053a5c4b951606d4d86085d51238f2c67c7dec29c792ca")
    val amount = 351000 msat

    val Success(route) = Router.findRoute(g, thisNode, targetNode, amount, 1, Set.empty, Set.empty, Set.empty, params, currentBlockHeight = 567634) // simulate mainnet block for heuristic

    assert(route.size == 2)
    assert(route.last.nextNodeId == targetNode)
  }
}

object RouteCalculationSpec {

  val noopBoundaries = { _: RichWeight => true }

  val DEFAULT_AMOUNT_MSAT = 10000000 msat

  val DEFAULT_ROUTE_PARAMS = RouteParams(randomize = false, maxFeeBase = 21000 msat, maxFeePct = 0.03, routeMaxCltv = CltvExpiryDelta(2016), routeMaxLength = 6, ratios = None)

  val DUMMY_SIG = Transactions.PlaceHolderSig

  def makeChannel(shortChannelId: Long, nodeIdA: PublicKey, nodeIdB: PublicKey) = {
    val (nodeId1, nodeId2) = if (Announcements.isNode1(nodeIdA, nodeIdB)) (nodeIdA, nodeIdB) else (nodeIdB, nodeIdA)
    ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, ByteVector.empty, Block.RegtestGenesisBlock.hash, ShortChannelId(shortChannelId), nodeId1, nodeId2, randomKey.publicKey, randomKey.publicKey)
  }

  def makeUpdate(shortChannelId: Long, nodeId1: PublicKey, nodeId2: PublicKey, feeBase: MilliSatoshi, feeProportionalMillionth: Int, minHtlc: MilliSatoshi = DEFAULT_AMOUNT_MSAT, maxHtlc: Option[MilliSatoshi] = None, cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0)): (ChannelDesc, ChannelUpdate) = {
    makeUpdateShort(ShortChannelId(shortChannelId), nodeId1, nodeId2, feeBase, feeProportionalMillionth, minHtlc, maxHtlc, cltvDelta)
  }

  def makeUpdateShort(shortChannelId: ShortChannelId, nodeId1: PublicKey, nodeId2: PublicKey, feeBase: MilliSatoshi, feeProportionalMillionth: Int, minHtlc: MilliSatoshi = DEFAULT_AMOUNT_MSAT, maxHtlc: Option[MilliSatoshi] = None, cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0), timestamp: Long = 0): (ChannelDesc, ChannelUpdate) =
    ChannelDesc(shortChannelId, nodeId1, nodeId2) -> ChannelUpdate(
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

  def makeGraph(updates: Map[ChannelDesc, ChannelUpdate]) = DirectedGraph().addEdges(updates.toSeq)

  def hops2Ids(route: Seq[ChannelHop]) = route.map(hop => hop.lastUpdate.shortChannelId.toLong)

  def hops2Edges(route: Seq[ChannelHop]) = route.map(hop => GraphEdge(ChannelDesc(hop.lastUpdate.shortChannelId, hop.nodeId, hop.nextNodeId), hop.lastUpdate))

  def hops2ShortChannelIds(route: Seq[ChannelHop]) = route.map(hop => hop.lastUpdate.shortChannelId.toString).toList

  def hops2Nodes(route: Seq[ChannelHop]) = route.map(hop => (hop.nodeId, hop.nextNodeId))

}
