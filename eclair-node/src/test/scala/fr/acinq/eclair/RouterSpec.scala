package fr.acinq.eclair

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.payment.PaymentLifecycle
import fr.acinq.eclair.router.{ChannelDesc, Router}
import lightning.route_step
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class RouterSpec extends FunSuite {

  test("calculate simple route") {

    val channels: Map[BinaryData, ChannelDesc] = Map(
      BinaryData("0a") -> ChannelDesc(BinaryData("0a"), BinaryData("01"), BinaryData("02")),
      BinaryData("0b") -> ChannelDesc(BinaryData("0b"), BinaryData("03"), BinaryData("02")),
      BinaryData("0c") -> ChannelDesc(BinaryData("0c"), BinaryData("03"), BinaryData("04")),
      BinaryData("0d") -> ChannelDesc(BinaryData("0d"), BinaryData("04"), BinaryData("05"))
    )

    val route = Router.findRouteDijkstra(BinaryData("01"), BinaryData("05"), channels)

    assert(route === BinaryData("01") :: BinaryData("02") :: BinaryData("03") :: BinaryData("04") :: BinaryData("05") :: Nil)

  }

  test("calculate simple route 2") {

    val channels: Map[BinaryData, ChannelDesc] = Map(
      BinaryData("99e542c274b073d215af02e57f814f3d16a2373a00ac52b49ef4a1949c912609") -> ChannelDesc(BinaryData("99e542c274b073d215af02e57f814f3d16a2373a00ac52b49ef4a1949c912609"), BinaryData("032b2e37d202658eb5216a698e52da665c25c5d04de0faf1d29aa2af7fb374a003"), BinaryData("0382887856e9f10a8a1ffade96b4009769141e5f3692f2ffc35fd4221f6057643b")),
      BinaryData("44d7f822e0498e21473a8a40045c9b7e7bd2e78730b5274cb5836e64bc0b6125") -> ChannelDesc(BinaryData("44d7f822e0498e21473a8a40045c9b7e7bd2e78730b5274cb5836e64bc0b6125"), BinaryData("023cda4e9506ce0a5fd3e156fc6d1bff16873375c8e823ee18aa36fa6844c0ae61"), BinaryData("0382887856e9f10a8a1ffade96b4009769141e5f3692f2ffc35fd4221f6057643b"))
    )

    val route = Router.findRouteDijkstra(BinaryData("032b2e37d202658eb5216a698e52da665c25c5d04de0faf1d29aa2af7fb374a003"), BinaryData("023cda4e9506ce0a5fd3e156fc6d1bff16873375c8e823ee18aa36fa6844c0ae61"), channels)

    assert(route === BinaryData("032b2e37d202658eb5216a698e52da665c25c5d04de0faf1d29aa2af7fb374a003") :: BinaryData("0382887856e9f10a8a1ffade96b4009769141e5f3692f2ffc35fd4221f6057643b") :: BinaryData("023cda4e9506ce0a5fd3e156fc6d1bff16873375c8e823ee18aa36fa6844c0ae61") :: Nil)

  }

  test("calculate simple route 3") {

    val channels: Map[BinaryData, ChannelDesc] = Map(
      BinaryData("178c78f5ec3ffa8cc15d9fa8119ec0d1ff7d4e4ff33297df5e68319fbc34b1bb") -> ChannelDesc(BinaryData("178c78f5ec3ffa8cc15d9fa8119ec0d1ff7d4e4ff33297df5e68319fbc34b1bb"), BinaryData("02f298bcafa3b0aa1d51a552d759a5add7d189222fe068d4ff2417dce43ea9daa1"), BinaryData("021acf75c92318d3723098294d2a6a4b08d9abba2ebb5f2df2b4a8e9153e96a5f4")),
      BinaryData("e9c89b691f80494e631a4eb7169c981a0be9484cdd51a2cb9975f8aba85a77c8") -> ChannelDesc(BinaryData("e9c89b691f80494e631a4eb7169c981a0be9484cdd51a2cb9975f8aba85a77c8"), BinaryData("031ef3016e2994eb40b75c051ba9089238dcb138fe4148169d6f3f8e114b446cc1"), BinaryData("03befb4f8ad1d87d4c41acbb316791fe157f305caf2123c848f448975aaf85c1bb")),
      BinaryData("2c2e2b5f1b70d9ab2ea30f5dd0f61d62edad10881d2add1a2796d94eac93e07e") -> ChannelDesc(BinaryData("2c2e2b5f1b70d9ab2ea30f5dd0f61d62edad10881d2add1a2796d94eac93e07e"), BinaryData("02f298bcafa3b0aa1d51a552d759a5add7d189222fe068d4ff2417dce43ea9daa1"), BinaryData("031ef3016e2994eb40b75c051ba9089238dcb138fe4148169d6f3f8e114b446cc1"))
    )

    val route = Router.findRouteDijkstra(BinaryData("03befb4f8ad1d87d4c41acbb316791fe157f305caf2123c848f448975aaf85c1bb"), BinaryData("021acf75c92318d3723098294d2a6a4b08d9abba2ebb5f2df2b4a8e9153e96a5f4"), channels)

    assert(route === BinaryData("03befb4f8ad1d87d4c41acbb316791fe157f305caf2123c848f448975aaf85c1bb") :: BinaryData("031ef3016e2994eb40b75c051ba9089238dcb138fe4148169d6f3f8e114b446cc1") :: BinaryData("02f298bcafa3b0aa1d51a552d759a5add7d189222fe068d4ff2417dce43ea9daa1") :: BinaryData("021acf75c92318d3723098294d2a6a4b08d9abba2ebb5f2df2b4a8e9153e96a5f4") :: Nil)
  }

  test("route not found") {

    val channels: Map[BinaryData, ChannelDesc] = Map(
      BinaryData("178c78f5ec3ffa8cc15d9fa8119ec0d1ff7d4e4ff33297df5e68319fbc34b1bb") -> ChannelDesc(BinaryData("178c78f5ec3ffa8cc15d9fa8119ec0d1ff7d4e4ff33297df5e68319fbc34b1bb"), BinaryData("02f298bcafa3b0aa1d51a552d759a5add7d189222fe068d4ff2417dce43ea9daa1"), BinaryData("021acf75c92318d3723098294d2a6a4b08d9abba2ebb5f2df2b4a8e9153e96a5f4")),
      BinaryData("e9c89b691f80494e631a4eb7169c981a0be9484cdd51a2cb9975f8aba85a77c8") -> ChannelDesc(BinaryData("e9c89b691f80494e631a4eb7169c981a0be9484cdd51a2cb9975f8aba85a77c8"), BinaryData("031ef3016e2994eb40b75c051ba9089238dcb138fe4148169d6f3f8e114b446cc1"), BinaryData("03befb4f8ad1d87d4c41acbb316791fe157f305caf2123c848f448975aaf85c1bb"))
    )

    intercept[RuntimeException] {
      Router.findRouteDijkstra(BinaryData("03befb4f8ad1d87d4c41acbb316791fe157f305caf2123c848f448975aaf85c1bb"), BinaryData("021acf75c92318d3723098294d2a6a4b08d9abba2ebb5f2df2b4a8e9153e96a5f4"), channels)
    }
  }

  test("route to self") {

    val channels: Map[BinaryData, ChannelDesc] = Map(
      BinaryData("0a") -> ChannelDesc(BinaryData("0a"), BinaryData("01"), BinaryData("02")),
      BinaryData("0b") -> ChannelDesc(BinaryData("0b"), BinaryData("01"), BinaryData("01"))
    )

    intercept[RuntimeException] {
      Router.findRouteDijkstra(BinaryData("01"), BinaryData("01"), channels)
    }
  }

  test("compute fees 2") {
    val nodeIds = Seq(BinaryData("00"), BinaryData("01"), BinaryData("02"))
    val amountMsat = 1000000
    val route = PaymentLifecycle.buildRoute(amountMsat, nodeIds)
    assert(route.steps.length == 4 && route.steps.last == route_step(0, next = route_step.Next.End(true)))
    assert(route.steps(2).amount == amountMsat)
    assert(route.steps.dropRight(1).map(_.next.bitcoin.get.key).map(bytestring2bin) == nodeIds)
    assert(route.steps(0).amount - route.steps(1).amount == nodeFee(Globals.fee_base_msat, Globals.fee_proportional_msat, route.steps(1).amount))
  }

  test("route to neighbor") {
    val channels: Map[BinaryData, ChannelDesc] = Map(
      BinaryData("0a") -> ChannelDesc(BinaryData("0a"), BinaryData("01"), BinaryData("02"))
    )
    Router.findRouteDijkstra(BinaryData("01"), BinaryData("02"), channels)
  }

  test("compute fees") {
    val nodeIds = Seq(BinaryData("00"), BinaryData("01"))
    val amountMsat = 300000000
    val route = PaymentLifecycle.buildRoute(amountMsat, nodeIds)
    assert(route.steps.length == 3 && route.steps.last == route_step(0, next = route_step.Next.End(true)))
    assert(route.steps(1).amount == amountMsat)
    assert(route.steps.dropRight(1).map(_.next.bitcoin.get.key).map(bytestring2bin) == nodeIds)
    assert(route.steps(0).amount - route.steps(1).amount == nodeFee(Globals.fee_base_msat, Globals.fee_proportional_msat, route.steps(1).amount))
  }

  test("compute example sig") {
    val data = BinaryData("00" * 32)
    val key = PrivateKey(BinaryData("11" * 32))
    val sig = Crypto.encodeSignature(Crypto.sign(data, key))
    assert(Crypto.isDERSignature(sig :+ 1.toByte))

  }

}
