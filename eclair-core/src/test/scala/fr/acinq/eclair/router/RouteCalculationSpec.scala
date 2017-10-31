package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Block, Crypto, MilliSatoshi}
import fr.acinq.eclair.randomKey
import fr.acinq.eclair.wire.{ChannelUpdate, PerHopPayload}
import fr.acinq.eclair.payment._
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
    // E (sender) -> D - public -> C - private -> B - private -> A (receiver)

    val amount = MilliSatoshi(100000000L)
    val paymentPreimage = BinaryData("0" * 32)
    val paymentHash = Crypto.sha256(paymentPreimage)
    val privateKey = PrivateKey("bb77027e3b6ef55f3b16eb6973d124f68e0c2afc16accc00a44ec6b3d1e58cc601")

    // Ask router for a route from 02f0b230e53723ccc331db140edc518be1ee5ab29a508104a4be2f5be922c928e8 (node C)
    // to 0299439d988cbf31388d59e3d6f9e184e7a0739b8b8fcdc298957216833935f9d3 (node A)
    val hopCB = Hop(PublicKey("02f0b230e53723ccc331db140edc518be1ee5ab29a508104a4be2f5be922c928e8"),
      PublicKey("032b4af42b5e8089a7a06005ead9ac4667527390ee39c998b7b0307f0d81d7f4ac"),
      ChannelUpdate("3044022075bc283539935b1bc126035ef98d0f9bcd5dd7b0832b0a6175dc14a5ee12d47102203d141a4da4f83fca9d65bddfb9ee6ea5cdfcdb364de062d1370500f511b8370701",
        "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f", 24412456671576064L, 1509366313, BinaryData("0000"), 144, 1000, 546000, 10))

    val hopBA = Hop(PublicKey("032b4af42b5e8089a7a06005ead9ac4667527390ee39c998b7b0307f0d81d7f4ac"),
      PublicKey("0299439d988cbf31388d59e3d6f9e184e7a0739b8b8fcdc298957216833935f9d3"),
      ChannelUpdate("304402205e9b28e26add5417ad97f6eb161229dd7db0d7848e146a1856a8841238bc627902203cc59996ca490375fd76a3327adfb7c5150ee3288ad1663b8c4fbe8908eb489a01",
        "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f", 23366821113626624L, 1509455356, BinaryData("0001"), 144, 1000, 546000, 10))

    val reverseRoute = List(hopBA, hopCB)
    val extraRoute = PaymentHop.buildExtra(reverseRoute, amount.amount)

    assert(extraRoute === List(ExtraHop(PublicKey("02f0b230e53723ccc331db140edc518be1ee5ab29a508104a4be2f5be922c928e8"), 24412456671576064L, 547005, 144),
      ExtraHop(PublicKey("032b4af42b5e8089a7a06005ead9ac4667527390ee39c998b7b0307f0d81d7f4ac") ,23366821113626624L, 547000, 144)))

    // Receiver side

    // Ask router for a route D -> C
    val hopDC = Hop(PublicKey("03c1b07dbe10e178216150b49646ded556466ed15368857fa721cf1acd9d9a6f24"),
      PublicKey("02f0b230e53723ccc331db140edc518be1ee5ab29a508104a4be2f5be922c928e8"),
      ChannelUpdate("3044022060c1034092d4e41d75271eb619ef0a0f00d0b5a61c4245e0f14eeac91a3c823202200da9c8b8067e73c32aea41cb9eec050ce49cb944877d9abb3b08be2dea92497301",
        "06226e46111a0b59caaf126043eb5bbf28c34f3a5e332a1fc7b2b73cf188910f", 24403660578553856L, 1509456040, BinaryData("0001"), 144, 1000, 546000, 10))

    val (amt, expiry, payloads) = PaymentLifecycle.buildPayloads(amount.amount, 10, Seq(hopDC) ++ extraRoute)

    assert(payloads === List(PerHopPayload(24403660578553856L, 101094005L, 298),
      PerHopPayload(24412456671576064L, 100547000L, 154), PerHopPayload(23366821113626624L, 100000000L, 10), PerHopPayload(0L, 100000000L, 10)))

    assert(amt == 101641015L)
    assert(expiry == 442)
  }

}
