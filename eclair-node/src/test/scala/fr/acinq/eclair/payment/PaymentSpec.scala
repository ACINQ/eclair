package fr.acinq.eclair.payment

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import lightning.route_step
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class PaymentSpec extends FunSuite {

  /*test("compute fees") {
    val nodeIds = Seq(BinaryData("00"), BinaryData("01"))
    val amountMsat = 300000000
    val route = PaymentLifecycle.buildRoute(amountMsat, nodeIds)
    assert(route.steps.length == 3 && route.steps.last == route_step(0, next = route_step.Next.End(true)))
    assert(route.steps(1).amount == amountMsat)
    assert(route.steps.dropRight(1).map(_.next.bitcoin.get.key).map(bytestring2bin) == nodeIds)
    assert(route.steps(0).amount - route.steps(1).amount == nodeFee(Globals.fee_base_msat, Globals.fee_proportional_msat, route.steps(1).amount))
  }

  test("compute fees 2") {
    val nodeIds = Seq(BinaryData("00"), BinaryData("01"), BinaryData("02"))
    val amountMsat = 1000000
    val route = PaymentLifecycle.buildRoute(amountMsat, nodeIds)
    assert(route.steps.length == 4 && route.steps.last == route_step(0, next = route_step.Next.End(true)))
    assert(route.steps(2).amount == amountMsat)
    assert(route.steps.dropRight(1).map(_.next.bitcoin.get.key).map(bytestring2bin) == nodeIds)
    assert(route.steps(0).amount - route.steps(1).amount == nodeFee(Globals.fee_base_msat, Globals.fee_proportional_msat, route.steps(1).amount))
  }*/

}
