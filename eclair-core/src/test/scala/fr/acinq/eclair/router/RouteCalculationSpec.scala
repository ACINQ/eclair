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
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{ShortChannelId, randomKey}
import org.jgrapht.graph.DirectedWeightedPseudograph
import org.scalatest.FunSuite

import scala.util.{Failure, Success}

/**
  * Created by PM on 31/05/2016.
  */

class RouteCalculationSpec extends FunSuite {

  import RouteCalculationSpec._

  val (a, b, c, d, e) = (randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)

  test("calculate simple route") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e)
    assert(route.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))

  }

  test("calculate simple route (add and remove edges") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))

    Router.removeEdge(g, ChannelDesc(ShortChannelId(3L), c, d))
    val route2 = Router.findRoute(g, a, e)
    assert(route2.map(hops2Ids) === Failure(RouteNotFound))

  }

  test("calculate longer but cheaper route") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0),
      makeUpdate(4L, d, e, 0, 0),
      makeUpdate(5L, a, e, 10, 10)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e)
    assert(route.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("no local channels") {

    val updates = List(
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("route not found") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("route not found (unknown destination)") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, e)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("route to self") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0)
    ).toMap

    val g = makeGraph(updates)

    val route = Router.findRoute(g, a, a)
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

    val route = Router.findRoute(g, a, b)
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

    val route1 = Router.findRoute(g, a, e)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))

    val route2 = Router.findRoute(g, e, a)
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

    val hops = Router.findRoute(g, a, e).get

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

    val route1 = Router.findRoute(g, a, e, withoutEdges = ChannelDesc(ShortChannelId(3L), c, d) :: Nil)
    assert(route1.map(hops2Ids) === Failure(RouteNotFound))

    // verify that we left the graph untouched
    assert(g.containsEdge(c, d))
    assert(g.containsVertex(c))
    assert(g.containsVertex(d))

    // make sure we can find a route if without the blacklist
    val route2 = Router.findRoute(g, a, e)
    assert(route2.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("verify that extra hops takes precedence over known channels") {
    val updates = List(
      makeUpdate(1L, a, b, 10, 10),
      makeUpdate(2L, b, c, 10, 10),
      makeUpdate(3L, c, d, 10, 10),
      makeUpdate(4L, d, e, 10, 10)
    ).toMap

    val g = makeGraph(updates)

    val route1 = Router.findRoute(g, a, e)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
    assert(route1.get.head.lastUpdate.feeBaseMsat == 10)

    val route2 = Router.findRoute(g, a, e, withEdges = Map(makeUpdate(1L, a, b, 5, 5)))
    assert(route2.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
    assert(route2.get.head.lastUpdate.feeBaseMsat == 5)
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

}

object RouteCalculationSpec {

  val DUMMY_SIG = BinaryData("3045022100e0a180fdd0fe38037cc878c03832861b40a29d32bd7b40b10c9e1efc8c1468a002205ae06d1624896d0d29f4b31e32772ea3cb1b4d7ed4e077e5da28dcc33c0e781201")

  def makeChannel(shortChannelId: Long, nodeIdA: PublicKey, nodeIdB: PublicKey) = {
    val (nodeId1, nodeId2) = if (Announcements.isNode1(nodeIdA, nodeIdB)) (nodeIdA, nodeIdB) else (nodeIdB, nodeIdA)
    ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, "", Block.RegtestGenesisBlock.hash, ShortChannelId(shortChannelId), nodeId1, nodeId2, randomKey.publicKey, randomKey.publicKey)
  }

  def makeUpdate(shortChannelId: Long, nodeId1: PublicKey, nodeId2: PublicKey, feeBaseMsat: Int, feeProportionalMillionth: Int): (ChannelDesc, ChannelUpdate) =
    (ChannelDesc(ShortChannelId(shortChannelId), nodeId1, nodeId2) -> ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(shortChannelId), 0L, 0, 0, 1, 42, feeBaseMsat, feeProportionalMillionth, None))


  def makeGraph(updates: Map[ChannelDesc, ChannelUpdate]) = {
    val g = new DirectedWeightedPseudograph[PublicKey, DescEdge](classOf[DescEdge])
    updates.foreach { case (d, u) => Router.addEdge(g, d, u) }
    g
  }

  def hops2Ids(route: Seq[Hop]) = route.map(hop => hop.lastUpdate.shortChannelId.toLong)

}
