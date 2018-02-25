package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Block, Crypto}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Router.getValidAnnouncements
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, randomKey, toShortId}
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

  def makeUpdate(shortChannelId: Long, nodeId1: PublicKey, nodeId2: PublicKey, feeBaseMsat: Int, feeProportionalMillionth: Int): (ChannelDesc, ChannelUpdate) = {
    val DUMMY_SIG = BinaryData("3045022100e0a180fdd0fe38037cc878c03832861b40a29d32bd7b40b10c9e1efc8c1468a002205ae06d1624896d0d29f4b31e32772ea3cb1b4d7ed4e077e5da28dcc33c0e781201")
    (ChannelDesc(shortChannelId, nodeId1, nodeId2) -> ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, shortChannelId, 0L, "0000", 1, 42, feeBaseMsat, feeProportionalMillionth))
  }

  def hops2Ids(route: Seq[Hop]) = route.map(hop => hop.lastUpdate.shortChannelId)

  val (a, b, c, d, e) = (randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)

  test("calculate simple route") {

    val updates = List(
       makeUpdate(1L, a, b, 0, 0),
       makeUpdate(2L, b, c, 0, 0),
       makeUpdate(3L, c, d, 0, 0),
       makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val route = Router.findRoute(a, e, updates)
    assert(route.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))
  }

  test("no local channels") {

    val updates = List(
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val route = Router.findRoute(a, e, updates)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("route not found") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val route = Router.findRoute(a, e, updates)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("route not found (unknown destination)") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0)
    ).toMap

    val route = Router.findRoute(a, e, updates)
    assert(route.map(hops2Ids) === Failure(RouteNotFound))
  }

  test("route to self") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0)
    ).toMap

    val route = Router.findRoute(a, a, updates)
    assert(route.map(hops2Ids) === Failure(CannotRouteToSelf))
  }

  test("route to immediate neighbor") {

    val updates = List(
      makeUpdate(1L, a, b, 0, 0),
      makeUpdate(2L, b, c, 0, 0),
      makeUpdate(3L, c, d, 0, 0),
      makeUpdate(4L, d, e, 0, 0)
    ).toMap

    val route = Router.findRoute(a, b, updates)
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

    val route1 = Router.findRoute(a, e, updates)
    assert(route1.map(hops2Ids) === Success(1 :: 2 :: 3 :: 4 :: Nil))

    val route2 = Router.findRoute(e, a, updates)
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

    val uab = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, 1L, 0L, "0000", 1, 42, 2500, 140)
    val uba = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, 1L, 1L, "0001", 1, 43, 2501, 141)
    val ubc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, 2L, 1L, "0000", 1, 44, 2502, 142)
    val ucb = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, 2L, 1L, "0001", 1, 45, 2503, 143)
    val ucd = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, 3L, 1L, "0000", 1, 46, 2504, 144)
    val udc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, 3L, 1L, "0001", 1, 47, 2505, 145)
    val ude = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, 4L, 1L, "0000", 1, 48, 2506, 146)
    val ued = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, 4L, 1L, "0001", 1, 49, 2507, 147)

    val updates = Map(
      ChannelDesc(1L, a, b) -> uab,
      ChannelDesc(1L, b, a) -> uba,
      ChannelDesc(2L, b, c) -> ubc,
      ChannelDesc(2L, c, b) -> ucb,
      ChannelDesc(3L, c, d) -> ucd,
      ChannelDesc(3L, d, c) -> udc,
      ChannelDesc(4L, d, e) -> ude,
      ChannelDesc(4L, e, d) -> ued
    )

    val hops = Router.findRoute(a, e, updates).get

    assert(hops === Hop(a, b, uab) :: Hop(b, c, ubc) :: Hop(c, d, ucd) :: Hop(d, e, ude) :: Nil)
  }

  test("stale channels pruning") {
    // set current block height
    Globals.blockCount.set(500000)

    def nodeAnnouncement(nodeId: PublicKey) = NodeAnnouncement("", "", 0, nodeId, Color(0, 0, 0), "", Nil)

    // we only care about timestamps and nodes ids
    def channelAnnouncement(shortChannelId: Long, node1: PublicKey, node2: PublicKey) = ChannelAnnouncement("", "", "", "", "", "", shortChannelId, node1, node2, randomKey.publicKey, randomKey.publicKey)

    def channelUpdate(shortChannelId: Long, timestamp: Long) = ChannelUpdate("", "", shortChannelId, timestamp, "", 0, 0, 0, 0)

    def daysAgoInBlocks(daysAgo: Int): Int = Globals.blockCount.get().toInt - 144 * daysAgo

    def daysAgoInSeconds(daysAgo: Int): Long = Platform.currentTime / 1000 - daysAgo * 24 * 3600

    // note: those are *ordered*
    val (node_1, node_2, node_3, node_4) = (nodeAnnouncement(PublicKey("02ca1f8792292fd2ad4001b578e962861cc1120f0140d050e87ce1d143f7179031")), nodeAnnouncement(PublicKey("028689a991673e0888580fc7cd3fb3e8a1b62e7e7f65a5fc9899f44b88307331d8")), nodeAnnouncement(PublicKey("036eee3325d246a54e32aa5c215777493e4867b2b22570c307283f5e160c1997cd")), nodeAnnouncement(PublicKey("039311b2ee0e47fe40e9d35a72416e7c3b6263abb12bce15d250e0e5e20f11029d")))

    // a is an old channel with an old channel update => PRUNED
    val id_a = toShortId(daysAgoInBlocks(16), 0, 0)
    val chan_a = channelAnnouncement(id_a, node_1.nodeId, node_2.nodeId)
    val upd_a = channelUpdate(id_a, daysAgoInSeconds(30))
    // b is an old channel with no channel update  => PRUNED
    val id_b = toShortId(daysAgoInBlocks(16), 1, 0)
    val chan_b = channelAnnouncement(id_b, node_2.nodeId, node_3.nodeId)
    // c is an old channel with a recent channel update  => KEPT
    val id_c = toShortId(daysAgoInBlocks(16), 2, 0)
    val chan_c = channelAnnouncement(id_c, node_1.nodeId, node_3.nodeId)
    val upd_c = channelUpdate(id_c, daysAgoInSeconds(2))
    // d is a recent channel with a recent channel update  => KEPT
    val id_d = toShortId(daysAgoInBlocks(2), 0, 0)
    val chan_d = channelAnnouncement(id_d, node_3.nodeId, node_4.nodeId)
    val upd_d = channelUpdate(id_d, daysAgoInSeconds(2))
    // e is a recent channel with no channel update  => KEPT
    val id_e = toShortId(daysAgoInBlocks(1), 0, 0)
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

    val (validChannels, validNodes, validUpdates) = getValidAnnouncements(channels, nodes, updates)
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

    val extraHop1 = ExtraHop(a, 1, 10, 11, 12)
    val extraHop2 = ExtraHop(b, 2, 20, 21, 22)
    val extraHop3 = ExtraHop(c, 3, 30, 31, 32)
    val extraHop4 = ExtraHop(d, 4, 40, 41, 42)

    val extraHops = extraHop1 :: extraHop2 :: extraHop3 :: extraHop4 :: Nil

    val fakeUpdates = Router.toFakeUpdates(extraHops, e)

    assert(fakeUpdates == Map(
      ChannelDesc(extraHop1.shortChannelId, a, b) -> Router.toFakeUpdate(extraHop1),
      ChannelDesc(extraHop2.shortChannelId, b, c) -> Router.toFakeUpdate(extraHop2),
      ChannelDesc(extraHop3.shortChannelId, c, d) -> Router.toFakeUpdate(extraHop3),
      ChannelDesc(extraHop4.shortChannelId, d, e) -> Router.toFakeUpdate(extraHop4)
    ))

  }

}
