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

import java.util.UUID

import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.actor.Status
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{Block, ByteVector32, MilliSatoshi, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.blockchain.{UtxoStatus, ValidateRequest, ValidateResult, WatchSpentBasic}
import fr.acinq.eclair.channel.Register.ForwardShortId
import fr.acinq.eclair.channel.{AddHtlcFailed, ChannelUnavailable}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.ErrorPacket
import fr.acinq.eclair.db.SentPayment.SentPaymentStatus
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.router.Announcements.{makeChannelUpdate, makeNodeAnnouncement}
import fr.acinq.eclair.router._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
import fr.acinq.eclair._

/**
  * Created by PM on 29/08/2016.
  */

class PaymentLifecycleSpec extends BaseRouterSpec {

  val initialBlockCount = 420000
  Globals.blockCount.set(initialBlockCount)

  val defaultAmountMsat = 142000000L
  val defaultPaymentHash = randomBytes32


  test("payment failed (route not found)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams
    val paymentDb = nodeParams.db.payments
    val id = UUID.randomUUID()
    val paymentFSM = system.actorOf(PaymentLifecycle.props(id, a, router, TestProbe().ref, nodeParams))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, f)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    assert(paymentDb.getSent(id).exists(_.status == SentPaymentStatus.PENDING))

    sender.expectMsg(PaymentFailed(id, request.paymentHash, LocalFailure(RouteNotFound) :: Nil))
    awaitCond(paymentDb.getSent(id).exists(_.status == SentPaymentStatus.FAILED))
  }

  test("payment failed (route too expensive)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams
    val paymentDb = nodeParams.db.payments
    val id = UUID.randomUUID()
    val paymentFSM = system.actorOf(PaymentLifecycle.props(id, a, router, TestProbe().ref, nodeParams))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, routeParams = Some(RouteParams(randomize = false, maxFeeBaseMsat = 100, maxFeePct = 0.0, routeMaxLength = 20, routeMaxCltv = 2016, ratios = None)))
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    val Seq(LocalFailure(RouteNotFound)) = sender.expectMsgType[PaymentFailed].failures
    awaitCond(paymentDb.getSent(id).exists(_.status == SentPaymentStatus.FAILED))
  }

  test("payment failed (unparsable failure)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams
    val paymentDb = nodeParams.db.payments
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(id, a, routerForwarder.ref, relayer.ref, nodeParams))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getSent(id).exists(_.status == SentPaymentStatus.PENDING))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, hops) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash)) // unparsable message

    // then the payment lifecycle will ask for a new route excluding all intermediate nodes
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, ignoreNodes = Set(c), ignoreChannels = Set.empty))

    // let's simulate a response by the router with another route
    sender.send(paymentFSM, RouteResponse(hops, Set(c), Set.empty))
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd2, _, _, _, _, _) = paymentFSM.stateData
    // and reply a 2nd time with an unparsable failure
    relayer.expectMsg(ForwardShortId(channelId_ab, cmd2))
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash)) // unparsable message

    // we allow 2 tries, so we send a 2nd request to the router
    sender.expectMsg(PaymentFailed(id, request.paymentHash, UnreadableRemoteFailure(hops) :: UnreadableRemoteFailure(hops) :: Nil))
    awaitCond(paymentDb.getSent(id).exists(_.status == SentPaymentStatus.FAILED)) // after last attempt the payment is failed
  }

  test("payment failed (local error)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams
    val paymentDb = nodeParams.db.payments
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(id, a, routerForwarder.ref, relayer.ref, nodeParams))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getSent(id).exists(_.status == SentPaymentStatus.PENDING))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, hops) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, Status.Failure(AddHtlcFailed(ByteVector32.Zeroes, request.paymentHash, ChannelUnavailable(ByteVector32.Zeroes), Local(id, Some(paymentFSM.underlying.self)), None, None)))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_ab, a, b))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getSent(id).exists(_.status == SentPaymentStatus.PENDING)) // payment is still pending because the error is recoverable
  }

  test("payment failed (first hop returns an UpdateFailMalformedHtlc)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams
    val paymentDb = nodeParams.db.payments
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(id, a, routerForwarder.ref, relayer.ref, nodeParams))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getSent(id).exists(_.status == SentPaymentStatus.PENDING))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, hops) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailMalformedHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash, FailureMessageCodecs.BADONION))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_ab, a, b))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getSent(id).exists(_.status == SentPaymentStatus.PENDING))
  }

  test("payment failed (TemporaryChannelFailure)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(id, a, routerForwarder.ref, relayer.ref, nodeParams))
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
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.createErrorPacket(sharedSecrets1.head._1, failure)))

    // payment lifecycle will ask the router to temporarily exclude this channel from its route calculations
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(channelUpdate_bc.shortChannelId, b, c)))
    routerForwarder.forward(router)
    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    // we allow 2 tries, so we send a 2nd request to the router
    sender.expectMsg(PaymentFailed(id, request.paymentHash, RemoteFailure(hops, ErrorPacket(b, failure)) :: LocalFailure(RouteNotFound) :: Nil))
  }

  test("payment failed (Update)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams
    val paymentDb = nodeParams.db.payments
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(id, a, routerForwarder.ref, relayer.ref, nodeParams))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, maxAttempts = 5)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getSent(id).exists(_.status == SentPaymentStatus.PENDING))

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
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.createErrorPacket(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getSent(id).exists(_.status == SentPaymentStatus.PENDING)) // 1 failure but not final, the payment is still PENDING
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
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.createErrorPacket(sharedSecrets2.head._1, failure2)))

    // this time the payment lifecycle will ask the router to temporarily exclude this channel from its route calculations
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(channelUpdate_bc.shortChannelId, b, c)))
    routerForwarder.forward(router)
    // but it will still forward the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified_2)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)

    // this time the router can't find a route: game over
    sender.expectMsg(PaymentFailed(id, request.paymentHash, RemoteFailure(hops, ErrorPacket(b, failure)) :: RemoteFailure(hops2, ErrorPacket(b, failure2)) :: LocalFailure(RouteNotFound) :: Nil))
    awaitCond(paymentDb.getSent(id).exists(_.status == SentPaymentStatus.FAILED))
  }

  test("payment failed (PermanentChannelFailure)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams
    val paymentDb = nodeParams.db.payments
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val paymentFSM = TestFSMRef(new PaymentLifecycle(id, a, routerForwarder.ref, relayer.ref, nodeParams))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, d, maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getSent(id).exists(_.status == SentPaymentStatus.PENDING))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData

    val failure = PermanentChannelFailure

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.createErrorPacket(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_bc, b, c))))
    routerForwarder.forward(router)
    // we allow 2 tries, so we send a 2nd request to the router, which won't find another route

    sender.expectMsg(PaymentFailed(id, request.paymentHash, RemoteFailure(hops, ErrorPacket(b, failure)) :: LocalFailure(RouteNotFound) :: Nil))
    awaitCond(paymentDb.getSent(id).exists(_.status == SentPaymentStatus.FAILED))
  }

  test("payment succeeded") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams
    val paymentDb = nodeParams.db.payments
    val id = UUID.randomUUID()
    val paymentFSM = system.actorOf(PaymentLifecycle.props(id, a, router, TestProbe().ref, nodeParams))
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
    awaitCond(paymentDb.getSent(id).exists(_.status == SentPaymentStatus.PENDING))
    sender.send(paymentFSM, UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash))

    val paymentOK = sender.expectMsgType[PaymentSucceeded]
    val PaymentSent(_, MilliSatoshi(request.amountMsat), fee, request.paymentHash, paymentOK.paymentPreimage, _, _) = eventListener.expectMsgType[PaymentSent]
    assert(fee > MilliSatoshi(0))
    assert(fee === MilliSatoshi(paymentOK.amountMsat - request.amountMsat))
    awaitCond(paymentDb.getSent(id).exists(_.status == SentPaymentStatus.SUCCEEDED))
  }

  test("payment succeeded to a channel with fees=0") { fixture =>
    import fixture._
    import fr.acinq.eclair.randomKey
    val nodeParams = TestConstants.Alice.nodeParams
    // the network will be a --(1)--> b ---(2)--> c --(3)--> d  and e --(4)--> f (we are a) and b -> g has fees=0
    //                                 \
    //                                  \--(5)--> g

    val (priv_g, priv_funding_g) = (randomKey, randomKey)
    val (g, funding_g) = (priv_g.publicKey, priv_funding_g.publicKey)
    val ann_g = makeNodeAnnouncement(priv_g, "node-G", Color(-30, 10, -50), Nil)
    val channelId_bg = ShortChannelId(420000, 5, 0)
    val chan_bg = channelAnnouncement(channelId_bg, priv_b, priv_g, priv_funding_b, priv_funding_g)
    val channelUpdate_bg = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, g, channelId_bg, cltvExpiryDelta = 9, htlcMinimumMsat = 0, feeBaseMsat = 0, feeProportionalMillionths = 0, htlcMaximumMsat = 500000000L)
    val channelUpdate_gb = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_g, b, channelId_bg, cltvExpiryDelta = 9, htlcMinimumMsat = 0, feeBaseMsat = 10, feeProportionalMillionths = 8, htlcMaximumMsat = 500000000L)
    assert(Router.getDesc(channelUpdate_bg, chan_bg) === ChannelDesc(chan_bg.shortChannelId, priv_b.publicKey, priv_g.publicKey))
    router ! PeerRoutingMessage(null, remoteNodeId, chan_bg)
    router ! PeerRoutingMessage(null, remoteNodeId, ann_g)
    router ! PeerRoutingMessage(null, remoteNodeId, channelUpdate_bg)
    router ! PeerRoutingMessage(null, remoteNodeId, channelUpdate_gb)
    watcher.expectMsg(ValidateRequest(chan_bg))
    watcher.send(router, ValidateResult(chan_bg, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(Satoshi(1000000), write(pay2wsh(Scripts.multiSig2of2(funding_b, funding_g)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
    watcher.expectMsgType[WatchSpentBasic]

    // actual test begins
    val paymentFSM = system.actorOf(PaymentLifecycle.props(UUID.randomUUID(), a, router, TestProbe().ref, nodeParams))
    val monitor = TestProbe()
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    // we send a payment to G which is just after the
    val request = SendPayment(defaultAmountMsat, defaultPaymentHash, g)
    sender.send(paymentFSM, request)

    // the route will be A -> B -> G where B -> G has a channel_update with fees=0
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.send(paymentFSM, UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash))

    val paymentOK = sender.expectMsgType[PaymentSucceeded]
    val PaymentSent(_, MilliSatoshi(request.amountMsat), fee, request.paymentHash, paymentOK.paymentPreimage, _, _) = eventListener.expectMsgType[PaymentSent]

    // during the route computation the fees were treated as if they were 1msat but when sending the onion we actually put zero
    // NB: A -> B doesn't pay fees because it's our direct neighbor
    // NB: B -> G doesn't asks for fees at all
    assert(fee === MilliSatoshi(0))
    assert(fee === MilliSatoshi(paymentOK.amountMsat - request.amountMsat))
  }

  test("filter errors properly") { _ =>
    val failures = LocalFailure(RouteNotFound) :: RemoteFailure(Hop(a, b, channelUpdate_ab) :: Nil, ErrorPacket(a, TemporaryNodeFailure)) :: LocalFailure(AddHtlcFailed(ByteVector32.Zeroes, ByteVector32.Zeroes, ChannelUnavailable(ByteVector32.Zeroes), Local(UUID.randomUUID(), None), None, None)) :: LocalFailure(RouteNotFound) :: Nil
    val filtered = PaymentLifecycle.transformForUser(failures)
    assert(filtered == LocalFailure(RouteNotFound) :: RemoteFailure(Hop(a, b, channelUpdate_ab) :: Nil, ErrorPacket(a, TemporaryNodeFailure)) :: LocalFailure(ChannelUnavailable(ByteVector32.Zeroes)) :: Nil)
  }
}
