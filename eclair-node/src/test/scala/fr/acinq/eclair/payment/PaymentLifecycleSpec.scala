package fr.acinq.eclair.payment

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.Status.Failure
import akka.testkit.TestProbe
import fr.acinq.eclair.router.BaseRouterSpec
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 29/08/2016.
  */
@RunWith(classOf[JUnitRunner])
class PaymentLifecycleSpec extends BaseRouterSpec {

  val initialBlockCount = 420000

  test("payment failed (route not found)") { case (router, _) =>
    val paymentFSM = system.actorOf(PaymentLifecycle.props(a, router, initialBlockCount))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = CreatePayment(142000L, "42" * 32, f)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    val res = sender.expectMsgType[Failure]
    assert(res.cause.getMessage === "route not found")
  }

  test("payment failed (htlc failed)") { case (router, _) =>
    val paymentFSM = system.actorOf(PaymentLifecycle.props(a, router, initialBlockCount))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = CreatePayment(142000L, "42" * 32, d)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.send(paymentFSM, PaymentFailed(null, request.paymentHash, "some reason"))

    val res = sender.expectMsgType[Failure]
    assert(res.cause.getMessage === "some reason")
  }

  test("payment succeeded") { case (router, _) =>
    val paymentFSM = system.actorOf(PaymentLifecycle.props(a, router, initialBlockCount))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = CreatePayment(142000L, "42" * 32, d)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.send(paymentFSM, PaymentSent(null, request.paymentHash))

    val res = sender.expectMsgType[String]
    assert(res === "sent")
  }

}
