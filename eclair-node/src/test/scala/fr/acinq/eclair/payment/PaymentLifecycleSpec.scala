package fr.acinq.eclair.payment

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.Status.Failure
import akka.testkit.TestProbe
import fr.acinq.eclair.Globals
import fr.acinq.eclair.router.BaseRouterSpec
import fr.acinq.eclair.wire.{UpdateFailHtlc, UpdateFulfillHtlc}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 29/08/2016.
  */
@RunWith(classOf[JUnitRunner])
class PaymentLifecycleSpec extends BaseRouterSpec {

  val initialBlockCount = 420000
  Globals.blockCount.set(initialBlockCount)

  test("payment failed (route not found)") { case (router, _) =>
    val paymentFSM = system.actorOf(PaymentLifecycle.props(a, router, TestProbe().ref))
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
    val paymentFSM = system.actorOf(PaymentLifecycle.props(a, router, TestProbe().ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = CreatePayment(142000L, "42" * 32, d)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.send(paymentFSM, UpdateFailHtlc(0, 0, "some reason".getBytes))

    val res = sender.expectMsgType[Failure]
    assert(res.cause.getMessage === "some reason")
  }

  test("payment succeeded") { case (router, _) =>
    val paymentFSM = system.actorOf(PaymentLifecycle.props(a, router, TestProbe().ref))
    val monitor = TestProbe()
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = CreatePayment(142000L, "42" * 32, d)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.send(paymentFSM, UpdateFulfillHtlc(0, 0, "42" * 32))

    val res = sender.expectMsgType[String]
    assert(res === "sent")

  }

}
