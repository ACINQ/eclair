package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Block, Crypto, MilliSatoshi}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, PerHopPayload}
import fr.acinq.eclair.{Globals, randomKey, toShortId}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.compat.Platform
import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class RouteCalculationSpec extends FunSuite {

  val (a, b, c, d, e) = (randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)

  test("calculate simple route") {

    val channels = List(
      ChannelDesc(1L, a, b),
      ChannelDesc(2L, b, c),
      ChannelDesc(3L, c, d),
      ChannelDesc(4L, d, e)
    )

    val route = Router.findRouteDijkstra(a, e, channels)
    assert(route.map(_.id) === 1 :: 2 :: 3 :: 4 :: Nil)

  }

  test("randomize routes") {

    val channels = List(
      ChannelDesc(1L, a, b),
      ChannelDesc(2L, a, b),
      ChannelDesc(3L, b, c),
      ChannelDesc(4L, b, c),
      ChannelDesc(5L, c, d),
      ChannelDesc(6L, c, d),
      ChannelDesc(4L, d, e),
      ChannelDesc(5L, d, e)
    )

    val routes = for (i <- 0 until 10) yield Router.findRouteDijkstra(a, e, channels)
    assert(routes.exists(_ != routes.head))

  }

  test("no local channels") {

    val channels = List(
      ChannelDesc(2L, b, c),
      ChannelDesc(4L, d, e)
    )

    val exc = intercept[RuntimeException] {
      Router.findRouteDijkstra(a, e, channels)
    }
    assert(exc == RouteNotFound)
  }

  test("route not found") {

    val channels = List(
      ChannelDesc(1L, a, b),
      ChannelDesc(2L, b, c),
      ChannelDesc(4L, d, e)
    )

    val exc = intercept[RuntimeException] {
      Router.findRouteDijkstra(a, e, channels)
    }
    assert(exc == RouteNotFound)
  }

  test("route not found (unknown destination)") {

    val channels = List(
      ChannelDesc(1L, a, b),
      ChannelDesc(2L, b, c),
      ChannelDesc(3L, c, d)
    )

    val exc = intercept[RuntimeException] {
      Router.findRouteDijkstra(a, e, channels)
    }
    assert(exc == RouteNotFound)
  }

  test("route to self") {

    val channels = List(
      ChannelDesc(1L, a, b),
      ChannelDesc(2L, b, c),
      ChannelDesc(3L, c, d),
      ChannelDesc(4L, d, e)
    )

    val exc = intercept[RuntimeException] {
      Router.findRouteDijkstra(a, a, channels)
    }
    assert(exc == CannotRouteToSelf)
  }

  test("route to immediate neighbor") {
    val channels = List(
      ChannelDesc(1L, a, b),
      ChannelDesc(2L, b, c),
      ChannelDesc(3L, c, d),
      ChannelDesc(4L, d, e)
    )

    val route = Router.findRouteDijkstra(a, b, channels)
    assert(route.map(_.id) === 1 :: Nil)
  }

  test("directed graph") {
    val channels = List(
      ChannelDesc(1L, a, b),
      ChannelDesc(2L, b, c),
      ChannelDesc(3L, c, d),
      ChannelDesc(4L, d, e)
    )

    // a->e works, e->a fails

    Router.findRouteDijkstra(a, e, channels)

    intercept[RuntimeException] {
      Router.findRouteDijkstra(e, a, channels)
    }

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

    import scala.concurrent.ExecutionContext.Implicits.global
    val hops = Await.result(Router.findRoute(a, e, updates), 3 seconds)

    assert(hops === Hop(a, b, uab) :: Hop(b, c, ubc) :: Hop(c, d, ucd) :: Hop(d, e, ude) :: Nil)
  }

  test("stale channels pruning") {
    // set current block height
    Globals.blockCount.set(500000)

    // we only care about timestamps
    def channelAnnouncement(shortChannelId: Long) = ChannelAnnouncement("", "", "", "", "", "", shortChannelId, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey)

    def channelUpdate(shortChannelId: Long, timestamp: Long) = ChannelUpdate("", "", shortChannelId, timestamp, "", 0, 0, 0, 0)

    def desc(shortChannelId: Long) = ChannelDesc(shortChannelId, randomKey.publicKey, randomKey.publicKey)

    def daysAgoInBlocks(daysAgo: Int): Int = Globals.blockCount.get().toInt - 144 * daysAgo

    def daysAgoInSeconds(daysAgo: Int): Long = Platform.currentTime / 1000 - daysAgo * 24 * 3600

    // a is an old channel with an old channel update => PRUNED
    val id_a = toShortId(daysAgoInBlocks(16), 0, 0)
    val chan_a = channelAnnouncement(id_a)
    val upd_a = channelUpdate(id_a, daysAgoInSeconds(30))
    // b is an old channel with no channel update  => PRUNED
    val id_b = toShortId(daysAgoInBlocks(16), 1, 0)
    val chan_b = channelAnnouncement(id_b)
    // c is an old channel with a recent channel update  => KEPT
    val id_c = toShortId(daysAgoInBlocks(16), 2, 0)
    val chan_c = channelAnnouncement(id_c)
    val upd_c = channelUpdate(id_c, daysAgoInSeconds(2))
    // d is a recent channel with a recent channel update  => KEPT
    val id_d = toShortId(daysAgoInBlocks(2), 0, 0)
    val chan_d = channelAnnouncement(id_d)
    val upd_d = channelUpdate(id_d, daysAgoInSeconds(2))
    // e is a recent channel with no channel update  => KEPT
    val id_e = toShortId(daysAgoInBlocks(1), 0, 0)
    val chan_e = channelAnnouncement(id_e)

    val channels = Set(chan_a, chan_b, chan_c, chan_d, chan_e)
    val updates = Set(upd_a, upd_c, upd_d)

    val staleChannels = Router.getStaleChannels(channels, updates).toSet

    assert(staleChannels === Set(id_a, id_b))

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
