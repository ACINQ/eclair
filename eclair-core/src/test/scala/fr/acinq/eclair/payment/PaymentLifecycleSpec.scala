package fr.acinq.eclair.payment

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.Status
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.MilliSatoshi
import fr.acinq.eclair.Globals
import fr.acinq.eclair.channel.{AddHtlcFailed, ChannelUnavailable}
import fr.acinq.eclair.channel.Register.ForwardShortId
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.ErrorPacket
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire._
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

  test("payment failed (unparsable failure)") { case (router, _) =>
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
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, hops) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc("00" * 32, 0, "42" * 32)) // unparsable message

    // then the payment lifecycle will ask for a new route excluding all intermediate nodes
    routerForwarder.expectMsg(RouteRequest(a, d, ignoreNodes = Set(c), ignoreChannels = Set.empty))

    // let's simulate a response by the router with another route
    sender.send(paymentFSM, RouteResponse(hops, Set(c), Set.empty))
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd2, _, _, _, _, _) = paymentFSM.stateData
    // and reply a 2nd time with an unparsable failure
    relayer.expectMsg(ForwardShortId(channelId_ab, cmd2))
    sender.send(paymentFSM, UpdateFailHtlc("00" * 32, 0, "42" * 32)) // unparsable message

    // we allow 2 tries, so we send a 2nd request to the router
    sender.expectMsg(PaymentFailed(request.paymentHash, UnreadableRemoteFailure(hops) :: UnreadableRemoteFailure(hops) :: Nil))
  }

  test("payment failed (local error)") { case (router, _) =>
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
    routerForwarder.expectMsg(RouteRequest(a, d, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, hops) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, Status.Failure(AddHtlcFailed("00" * 32, request.paymentHash, ChannelUnavailable("00" * 32), Local(Some(paymentFSM.underlying.self)), None)))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(RouteRequest(a, d, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_ab, a, b))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
  }

  test("payment failed (first hop returns an UpdateFailMalformedHtlc)") { case (router, _) =>
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
    routerForwarder.expectMsg(RouteRequest(a, d, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, hops) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailMalformedHtlc("00" * 32, 0, "42" * 32, FailureMessageCodecs.BADONION))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(RouteRequest(a, d, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_ab, a, b))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
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
    routerForwarder.expectMsg(RouteRequest(a, d, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData

    val failure = TemporaryChannelFailure(channelUpdate_bc)

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc("00" * 32, 0, Sphinx.createErrorPacket(sharedSecrets1.head._1, failure)))

    // payment lifecycle will ask the router to temporarily exclude this channel from its route calculations
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(channelUpdate_bc.shortChannelId, b, c)))
    routerForwarder.forward(router)
    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(a, d, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    // we allow 2 tries, so we send a 2nd request to the router
    sender.expectMsg(PaymentFailed(request.paymentHash, RemoteFailure(hops, ErrorPacket(b, failure)) :: LocalFailure(RouteNotFound) :: Nil))
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
    routerForwarder.expectMsg(RouteRequest(a, d, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData

    val failure = PermanentChannelFailure

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc("00" * 32, 0, Sphinx.createErrorPacket(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(a, d, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_bc, b, c))))
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

    val paymentOK = sender.expectMsgType[PaymentSucceeded]
    assert(paymentOK.amountMsat > request.amountMsat)
    val PaymentSent(MilliSatoshi(request.amountMsat), feesPaid, request.paymentHash, paymentOK.paymentPreimage) = eventListener.expectMsgType[PaymentSent]
    assert(feesPaid.amount > 0)
  }

  test("filter errors properly") { case _ =>
    val failures = LocalFailure(RouteNotFound) :: RemoteFailure(Hop(a, b, channelUpdate_ab) :: Nil, ErrorPacket(a, TemporaryNodeFailure)) :: LocalFailure(AddHtlcFailed("00" * 32, "00" * 32, ChannelUnavailable("00" * 32), Local(None), None)) :: LocalFailure(RouteNotFound) :: Nil
    val filtered = PaymentLifecycle.transformForUser(failures)
    assert(filtered == LocalFailure(RouteNotFound) :: RemoteFailure(Hop(a, b, channelUpdate_ab) :: Nil, ErrorPacket(a, TemporaryNodeFailure)) :: LocalFailure(ChannelUnavailable("00" * 32)) :: Nil)
  }
}
