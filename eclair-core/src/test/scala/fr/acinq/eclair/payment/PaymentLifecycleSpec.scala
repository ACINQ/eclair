/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.payment

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.Status
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{BinaryData, Block, MilliSatoshi}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.channel.Register.ForwardShortId
import fr.acinq.eclair.channel.{AddHtlcFailed, ChannelUnavailable}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.ErrorPacket
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.router.Announcements.makeChannelUpdate
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire._

/**
  * Created by PM on 29/08/2016.
  */

class PaymentLifecycleSpec extends BaseRouterSpec {

  val initialBlockCount = 420000
  Globals.blockCount.set(initialBlockCount)

  val defaultAmountMsat = 142000000L
  val defaultPaymentHash = BinaryData("42" * 32)

  test("payment failed (route not found)") { fixture =>
    import fixture._
    val paymentFSM = system.actorOf(PaymentLifecycle.props(a, router, TestProbe().ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, f)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.expectMsg(PaymentFailed(request.paymentHash, LocalFailure(RouteNotFound) :: Nil))
  }

  test("payment failed (route too expensive)") { fixture =>
    import fixture._
    val paymentFSM = system.actorOf(PaymentLifecycle.props(a, router, TestProbe().ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, routeParams = Some(RouteParams(maxFeeBaseMsat = 100, maxFeePct = 0.0, routeMaxLength = 20, routeMaxCltv = 2016, ratios = None)))
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    val Seq(LocalFailure(RouteNotFound)) = sender.expectMsgType[PaymentFailed].failures
  }

  test("payment failed (unparsable failure)") { fixture =>
    import fixture._
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(a, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, hops) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc("00" * 32, 0, defaultPaymentHash)) // unparsable message

    // then the payment lifecycle will ask for a new route excluding all intermediate nodes
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, ignoreNodes = Set(c), ignoreChannels = Set.empty))

    // let's simulate a response by the router with another route
    sender.send(paymentFSM, RouteResponse(hops, Set(c), Set.empty))
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd2, _, _, _, _, _) = paymentFSM.stateData
    // and reply a 2nd time with an unparsable failure
    relayer.expectMsg(ForwardShortId(channelId_ab, cmd2))
    sender.send(paymentFSM, UpdateFailHtlc("00" * 32, 0, defaultPaymentHash)) // unparsable message

    // we allow 2 tries, so we send a 2nd request to the router
    sender.expectMsg(PaymentFailed(request.paymentHash, UnreadableRemoteFailure(hops) :: UnreadableRemoteFailure(hops) :: Nil))
  }

  test("payment failed (local error)") { fixture =>
    import fixture._
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(a, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, hops) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, Status.Failure(AddHtlcFailed("00" * 32, request.paymentHash, ChannelUnavailable("00" * 32), Local(Some(paymentFSM.underlying.self)), None, None)))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_ab, a, b))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
  }

  test("payment failed (first hop returns an UpdateFailMalformedHtlc)") { fixture =>
    import fixture._
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(a, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, hops) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailMalformedHtlc("00" * 32, 0, defaultPaymentHash, FailureMessageCodecs.BADONION))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_ab, a, b))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
  }

  test("payment failed (TemporaryChannelFailure)") { fixture =>
    import fixture._
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(a, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
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
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    // we allow 2 tries, so we send a 2nd request to the router
    sender.expectMsg(PaymentFailed(request.paymentHash, RemoteFailure(hops, ErrorPacket(b, failure)) :: LocalFailure(RouteNotFound) :: Nil))
  }

  test("payment failed (Update)") { fixture =>
    import fixture._
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(a, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, maxAttempts = 5)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData
    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))

    // we change the cltv expiry
    val channelUpdate_bc_modified = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, cltvExpiryDelta = 42, htlcMinimumMsat = channelUpdate_bc.htlcMinimumMsat, feeBaseMsat = channelUpdate_bc.feeBaseMsat, feeProportionalMillionths = channelUpdate_bc.feeProportionalMillionths, htlcMaximumMsat = channelUpdate_bc.htlcMaximumMsat.get)
    val failure = IncorrectCltvExpiry(5, channelUpdate_bc_modified)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, UpdateFailHtlc("00" * 32, 0, Sphinx.createErrorPacket(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)

    // router answers with a new route, taking into account the new update
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd2, _, sharedSecrets2, _, _, hops2) = paymentFSM.stateData
    relayer.expectMsg(ForwardShortId(channelId_ab, cmd2))

    // we change the cltv expiry one more time
    val channelUpdate_bc_modified_2 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, cltvExpiryDelta = 43, htlcMinimumMsat = channelUpdate_bc.htlcMinimumMsat, feeBaseMsat = channelUpdate_bc.feeBaseMsat, feeProportionalMillionths = channelUpdate_bc.feeProportionalMillionths, htlcMaximumMsat = channelUpdate_bc.htlcMaximumMsat.get)
    val failure2 = IncorrectCltvExpiry(5, channelUpdate_bc_modified_2)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, UpdateFailHtlc("00" * 32, 0, Sphinx.createErrorPacket(sharedSecrets2.head._1, failure2)))

    // this time the payment lifecycle will ask the router to temporarily exclude this channel from its route calculations
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(channelUpdate_bc.shortChannelId, b, c)))
    routerForwarder.forward(router)
    // but it will still forward the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified_2)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)

    // this time the router can't find a route: game over
    sender.expectMsg(PaymentFailed(request.paymentHash, RemoteFailure(hops, ErrorPacket(b, failure)) :: RemoteFailure(hops2, ErrorPacket(b, failure2)) :: LocalFailure(RouteNotFound) :: Nil))
  }

  test("payment failed (PermanentChannelFailure)") { fixture =>
    import fixture._
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(a, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData

    val failure = PermanentChannelFailure

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc("00" * 32, 0, Sphinx.createErrorPacket(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_bc, b, c))))
    routerForwarder.forward(router)
    // we allow 2 tries, so we send a 2nd request to the router, which won't find another route

    sender.expectMsg(PaymentFailed(request.paymentHash, RemoteFailure(hops, ErrorPacket(b, failure)) :: LocalFailure(RouteNotFound) :: Nil))
  }

  test("payment succeeded") { fixture =>
    import fixture._
    val paymentFSM = system.actorOf(PaymentLifecycle.props(a, router, TestProbe().ref))
    val monitor = TestProbe()
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.send(paymentFSM, UpdateFulfillHtlc("00" * 32, 0, defaultPaymentHash))

    val paymentOK = sender.expectMsgType[PaymentSucceeded]
    val PaymentSent(MilliSatoshi(request.amountMsat), fee, request.paymentHash, paymentOK.paymentPreimage, _, _) = eventListener.expectMsgType[PaymentSent]
    assert(fee > MilliSatoshi(0))
    assert(fee === MilliSatoshi(paymentOK.amountMsat - request.amountMsat))
  }

  test("filter errors properly") { fixture =>
    val failures = LocalFailure(RouteNotFound) :: RemoteFailure(Hop(a, b, channelUpdate_ab) :: Nil, ErrorPacket(a, TemporaryNodeFailure)) :: LocalFailure(AddHtlcFailed("00" * 32, "00" * 32, ChannelUnavailable("00" * 32), Local(None), None, None)) :: LocalFailure(RouteNotFound) :: Nil
    val filtered = PaymentLifecycle.transformForUser(failures)
    assert(filtered == LocalFailure(RouteNotFound) :: RemoteFailure(Hop(a, b, channelUpdate_ab) :: Nil, ErrorPacket(a, TemporaryNodeFailure)) :: LocalFailure(ChannelUnavailable("00" * 32)) :: Nil)
  }
}
