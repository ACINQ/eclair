package fr.acinq.eclair.payment

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.Status.Failure
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.MilliSatoshi
import fr.acinq.eclair.Globals
import fr.acinq.eclair.channel.Register.ForwardShortId
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.ErrorPacket
import fr.acinq.eclair.router.{BaseRouterSpec, RouteNotFound, RouteRequest}
import fr.acinq.eclair.wire.{PermanentChannelFailure, TemporaryChannelFailure, UpdateFailHtlc, UpdateFulfillHtlc}
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

    val request = SendPayment(142000L, "42" * 32, f)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.expectMsg(PaymentFailed(request.paymentHash, LocalFailure(RouteNotFound) :: Nil))
  }

  test("payment failed (TemporaryChannelFailure)") { case (router, _) =>
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(a, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(142000L, "42" * 32, d, maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData

    val failure = TemporaryChannelFailure(channelUpdate_bc)

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc("00" * 32, 0, Sphinx.createErrorPacket(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(a, d, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    // we allow 2 tries, so we send a 2nd request to the router
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd2, _, sharedSecrets2, _, _, _) = paymentFSM.stateData
    relayer.expectMsg(ForwardShortId(channelId_ab, cmd2))
    sender.send(paymentFSM, UpdateFailHtlc("00" * 32, 0, Sphinx.createErrorPacket(sharedSecrets2.head._1, failure)))

    sender.expectMsg(PaymentFailed(request.paymentHash, RemoteFailure(hops, ErrorPacket(b, failure)) :: RemoteFailure(hops, ErrorPacket(b, failure)) :: Nil))
  }

  test("payment failed (PermanentChannelFailure)") { case (router, _) =>
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(a, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(142000L, "42" * 32, d, maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData

    val failure = PermanentChannelFailure

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc("00" * 32, 0, Sphinx.createErrorPacket(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(a, d, ignoreNodes = Set.empty, ignoreChannels = Set(channelId_bc)))
    routerForwarder.forward(router)
    // we allow 2 tries, so we send a 2nd request to the router, which won't find another route

    sender.expectMsg(PaymentFailed(request.paymentHash, RemoteFailure(hops, ErrorPacket(b, failure)) :: LocalFailure(RouteNotFound) :: Nil))
  }

  test("payment succeeded") { case (router, _) =>
    val paymentFSM = system.actorOf(PaymentLifecycle.props(a, router, TestProbe().ref))
    val monitor = TestProbe()
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(142000L, "42" * 32, d)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.send(paymentFSM, UpdateFulfillHtlc("00" * 32, 0, "42" * 32))

    sender.expectMsgType[PaymentSucceeded]
    val PaymentSent(MilliSatoshi(request.amountMsat), feesPaid, request.paymentHash) = eventListener.expectMsgType[PaymentSent]
    assert(feesPaid.amount > 0)

  }

}
