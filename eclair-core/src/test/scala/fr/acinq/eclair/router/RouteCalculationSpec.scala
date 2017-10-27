package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Block, Crypto}
import fr.acinq.eclair.randomKey
import fr.acinq.eclair.wire.{ChannelUpdate, PerHopPayload}
import fr.acinq.eclair.payment.{ExtraHop, Hop, PaymentHop, PaymentLifecycle}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

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

    val routes = for(i <- 0 until 10) yield Router.findRouteDijkstra(a, e, channels)
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

  test("calculate route with extra hops") {
    val DUMMY_SIG = BinaryData("3045022100e0a180fdd0fe38037cc878c03832861b40a29d32bd7b40b10c9e1efc8c1468a002205ae06d1624896d0d29f4b31e32772ea3cb1b4d7ed4e077e5da28dcc33c0e781201")

    val uab = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, 1L, 0L, "0000", 1, 42, 2500, 140)
    val ubc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, 2L, 1L, "0000", 1, 44, 2502, 142)
    val publicHops: Seq[Hop] = Hop(a, b, uab) :: Hop(b, c, ubc) :: Nil

    val ucd = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, 13390952114749440L, 1508747148L, BinaryData.empty, 144, 100, 546000, 10)
    val ude = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, 11091873301069824L, 1508752623L, BinaryData.empty, 144, 100, 546000, 10)
    val d: PublicKey = PublicKey("0299439d988cbf31388d59e3d6f9e184e7a0739b8b8fcdc298957216833935f9d3")
    val e: PublicKey = PublicKey("02f0b230e53723ccc331db140edc518be1ee5ab29a508104a4be2f5be922c928e8")

    val reversePathFromRecipient = Hop(d, e, ude) :: Hop(c, d, ucd) :: Nil
    val extraHops: Seq[ExtraHop] = PaymentHop.buildExtra(reversePathFromRecipient, 100000L)

    // (a -> b), (b -> c) ++ ((d -> e), (c -> d)).reverse
    val (_, _, payloads) = PaymentLifecycle.buildPayloads(100000L, 6, publicHops ++ extraHops.reverse)
    assert(payloads === List(PerHopPayload(1L, 1194678L, 295), PerHopPayload(2L, 1192007L, 294), PerHopPayload(11091873301069824L, 646006L, 150), PerHopPayload(13390952114749440L, 100000L, 6), PerHopPayload(0L, 100000L, 6)))
  }

}
