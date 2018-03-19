package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Block, Crypto}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, ShortChannelId, randomKey}
import org.jgrapht.graph.DirectedWeightedPseudograph
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.compat.Platform
import scala.util.{Failure, Success}

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class RouteCalculationSpec extends FunSuite {

  val DUMMY_SIG = BinaryData("3045022100e0a180fdd0fe38037cc878c03832861b40a29d32bd7b40b10c9e1efc8c1468a002205ae06d1624896d0d29f4b31e32772ea3cb1b4d7ed4e077e5da28dcc33c0e781201")

  def makeChannel(shortChannelId: Long, nodeIdA: PublicKey, nodeIdB: PublicKey) = {
    val (nodeId1, nodeId2) = if (Announcements.isNode1(nodeIdA, nodeIdB)) (nodeIdA, nodeIdB) else (nodeIdB, nodeIdA)
    ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, "", Block.RegtestGenesisBlock.hash, ShortChannelId(shortChannelId), nodeId1, nodeId2, randomKey.publicKey, randomKey.publicKey)
  }

  def makeUpdate(shortChannelId: Long, nodeId1: PublicKey, nodeId2: PublicKey, feeBaseMsat: Int, feeProportionalMillionth: Int): (ChannelDesc, ChannelUpdate) =
    (ChannelDesc(ShortChannelId(shortChannelId), nodeId1, nodeId2) -> ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(shortChannelId), 0L, "0000", 1, 42, feeBaseMsat, feeProportionalMillionth))


  def makeGraph(updates: Map[ChannelDesc, ChannelUpdate]) = {
    val g = new DirectedWeightedPseudograph[PublicKey, DescEdge](classOf[DescEdge])
    updates.foreach { case (d, u) => Router.addEdge(g, d, u) }
    g
  }

  def hops2Ids(route: Seq[Hop]) = route.map(hop => hop.lastUpdate.shortChannelId.toLong)

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

    val uab = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(1L), 0L, "0000", 1, 42, 2500, 140)
    val uba = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(1L), 1L, "0001", 1, 43, 2501, 141)
    val ubc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(2L), 1L, "0000", 1, 44, 2502, 142)
    val ucb = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(2L), 1L, "0001", 1, 45, 2503, 143)
    val ucd = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(3L), 1L, "0000", 1, 46, 2504, 144)
    val udc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(3L), 1L, "0001", 1, 47, 2505, 145)
    val ude = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(4L), 1L, "0000", 1, 48, 2506, 146)
    val ued = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(4L), 1L, "0001", 1, 49, 2507, 147)

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

  test("stale channels pruning") {
    // set current block height
    Globals.blockCount.set(500000)

    def nodeAnnouncement(nodeId: PublicKey) = NodeAnnouncement("", "", 0, nodeId, Color(0, 0, 0), "", Nil)

    // we only care about timestamps and nodes ids
    def channelAnnouncement(shortChannelId: ShortChannelId, node1: PublicKey, node2: PublicKey) = ChannelAnnouncement("", "", "", "", "", "", shortChannelId, node1, node2, randomKey.publicKey, randomKey.publicKey)

    def channelUpdate(shortChannelId: ShortChannelId, timestamp: Long) = ChannelUpdate("", "", shortChannelId, timestamp, "", 0, 0, 0, 0)

    def daysAgoInBlocks(daysAgo: Int): Int = Globals.blockCount.get().toInt - 144 * daysAgo

    def daysAgoInSeconds(daysAgo: Int): Long = Platform.currentTime / 1000 - daysAgo * 24 * 3600

    // note: those are *ordered*
    val (node_1, node_2, node_3, node_4) = (nodeAnnouncement(PublicKey("02ca1f8792292fd2ad4001b578e962861cc1120f0140d050e87ce1d143f7179031")), nodeAnnouncement(PublicKey("028689a991673e0888580fc7cd3fb3e8a1b62e7e7f65a5fc9899f44b88307331d8")), nodeAnnouncement(PublicKey("036eee3325d246a54e32aa5c215777493e4867b2b22570c307283f5e160c1997cd")), nodeAnnouncement(PublicKey("039311b2ee0e47fe40e9d35a72416e7c3b6263abb12bce15d250e0e5e20f11029d")))

    // a is an old channel with an old channel update => PRUNED
    val id_a = ShortChannelId(daysAgoInBlocks(16), 0, 0)
    val chan_a = channelAnnouncement(id_a, node_1.nodeId, node_2.nodeId)
    val upd_a = channelUpdate(id_a, daysAgoInSeconds(30))
    // b is an old channel with no channel update  => PRUNED
    val id_b = ShortChannelId(daysAgoInBlocks(16), 1, 0)
    val chan_b = channelAnnouncement(id_b, node_2.nodeId, node_3.nodeId)
    // c is an old channel with a recent channel update  => KEPT
    val id_c = ShortChannelId(daysAgoInBlocks(16), 2, 0)
    val chan_c = channelAnnouncement(id_c, node_1.nodeId, node_3.nodeId)
    val upd_c = channelUpdate(id_c, daysAgoInSeconds(2))
    // d is a recent channel with a recent channel update  => KEPT
    val id_d = ShortChannelId(daysAgoInBlocks(2), 0, 0)
    val chan_d = channelAnnouncement(id_d, node_3.nodeId, node_4.nodeId)
    val upd_d = channelUpdate(id_d, daysAgoInSeconds(2))
    // e is a recent channel with no channel update  => KEPT
    val id_e = ShortChannelId(daysAgoInBlocks(1), 0, 0)
    val chan_e = channelAnnouncement(id_e, node_1.nodeId, randomKey.publicKey)

    val nodes = Map(
      node_1.nodeId -> node_1,
      node_2.nodeId -> node_2,
      node_3.nodeId -> node_3,
      node_4.nodeId -> node_4
    )
    val channels = Map(
      chan_a.shortChannelId -> chan_a,
      chan_b.shortChannelId -> chan_b,
      chan_c.shortChannelId -> chan_c,
      chan_d.shortChannelId -> chan_d,
      chan_e.shortChannelId -> chan_e
    )
    val updates = Map(
      ChannelDesc(chan_a.shortChannelId, chan_a.nodeId1, chan_a.nodeId2) -> upd_a,
      ChannelDesc(chan_c.shortChannelId, chan_c.nodeId1, chan_c.nodeId2) -> upd_c,
      ChannelDesc(chan_d.shortChannelId, chan_d.nodeId1, chan_d.nodeId2) -> upd_d
    )

    val staleChannels = Router.getStaleChannels(channels.values, updates).toSet
    assert(staleChannels === Set(id_a, id_b))

    val (validChannels, validNodes, validUpdates) = Router.getValidAnnouncements(channels, nodes, updates)
    assert(validChannels.toSet === Set(chan_c, chan_d, chan_e))
    assert(validNodes.toSet === Set(node_1, node_3, node_4)) // node 2 has been pruned because its only channel was pruned
    assert(validUpdates.toSet === Set(upd_c, upd_d))

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
