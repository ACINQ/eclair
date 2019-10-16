/*
 * Copyright 2019 ACINQ SAS
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
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto, Transaction, TxOut}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{UtxoStatus, ValidateRequest, ValidateResult, WatchSpentBasic}
import fr.acinq.eclair.channel.Register.ForwardShortId
import fr.acinq.eclair.channel.{AddHtlcFailed, Channel, ChannelUnavailable}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus}
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.payment.PaymentInitiator.SendPaymentRequest
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.router.Announcements.{makeChannelUpdate, makeNodeAnnouncement}
import fr.acinq.eclair.router._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire._
import scodec.bits.HexStringSyntax

/**
 * Created by PM on 29/08/2016.
 */

class PaymentLifecycleSpec extends BaseRouterSpec {

  val defaultAmountMsat = 142000000 msat
  val defaultExpiryDelta = Channel.MIN_CLTV_EXPIRY_DELTA
  val defaultPaymentHash = randomBytes32
  val defaultExternalId = UUID.randomUUID().toString
  val defaultPaymentRequest = SendPaymentRequest(defaultAmountMsat, defaultPaymentHash, d, 1, externalId = Some(defaultExternalId))

  test("send to route") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val id = UUID.randomUUID()
    val paymentDb = nodeParams.db.payments
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(id, defaultPaymentRequest, paymentDb)
    val paymentFSM = system.actorOf(PaymentLifecycle.props(nodeParams, progressHandler, router, TestProbe().ref))
    val monitor = TestProbe()
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    // pre-computed route going from A to D
    val request = SendPaymentToRoute(defaultPaymentHash, Seq(a, b, c, d), FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)))

    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(paymentDb.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
    val Some(outgoing) = paymentDb.getOutgoingPayment(id)
    assert(outgoing.copy(createdAt = 0) === OutgoingPayment(id, id, Some(defaultExternalId), defaultPaymentHash, defaultAmountMsat, d, 0, None, OutgoingPaymentStatus.Pending))
    sender.send(paymentFSM, UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash))

    sender.expectMsgType[PaymentSent]
    awaitCond(paymentDb.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))
  }

  test("send to route (edges not found in the graph)") { fixture =>
    import fixture._

    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val id = UUID.randomUUID()
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(id, defaultPaymentRequest, nodeParams.db.payments)
    val paymentFSM = system.actorOf(PaymentLifecycle.props(nodeParams, progressHandler, router, TestProbe().ref))
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    val brokenRoute = SendPaymentToRoute(randomBytes32, Seq(randomKey.publicKey, randomKey.publicKey, randomKey.publicKey), FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)))
    sender.send(paymentFSM, brokenRoute)
    val failureMessage = eventListener.expectMsgType[PaymentFailed].failures.head.asInstanceOf[LocalFailure].t.getMessage
    assert(failureMessage == "Not all the nodes in the supplied route are connected with public channels")
  }

  test("payment failed (route not found)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val paymentDb = nodeParams.db.payments
    val id = UUID.randomUUID()
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(id, defaultPaymentRequest.copy(targetNodeId = f), paymentDb)
    val routerForwarder = TestProbe()
    val paymentFSM = system.actorOf(PaymentLifecycle.props(nodeParams, progressHandler, routerForwarder.ref, TestProbe().ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultPaymentHash, f, FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)), maxAttempts = 5)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val routeRequest = routerForwarder.expectMsgType[RouteRequest]
    awaitCond(paymentDb.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    routerForwarder.forward(router, routeRequest)
    assert(sender.expectMsgType[PaymentFailed].failures === LocalFailure(RouteNotFound) :: Nil)
    awaitCond(paymentDb.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment failed (route too expensive)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val paymentDb = nodeParams.db.payments
    val id = UUID.randomUUID()
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(id, defaultPaymentRequest, paymentDb)
    val paymentFSM = system.actorOf(PaymentLifecycle.props(nodeParams, progressHandler, router, TestProbe().ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultPaymentHash, d, FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)), maxAttempts = 5, routeParams = Some(RouteParams(randomize = false, maxFeeBase = 100 msat, maxFeePct = 0.0, routeMaxLength = 20, routeMaxCltv = CltvExpiryDelta(2016), ratios = None)))
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    val Seq(LocalFailure(RouteNotFound)) = sender.expectMsgType[PaymentFailed].failures
    awaitCond(paymentDb.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment failed (unparsable failure)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val paymentDb = nodeParams.db.payments
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(id, defaultPaymentRequest, paymentDb)
    val paymentFSM = TestFSMRef(new PaymentLifecycle(nodeParams, progressHandler, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultPaymentHash, d, FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)), maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, hops) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash)) // unparsable message

    // then the payment lifecycle will ask for a new route excluding all intermediate nodes
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, ignoreNodes = Set(c), ignoreChannels = Set.empty))

    // let's simulate a response by the router with another route
    sender.send(paymentFSM, RouteResponse(hops, Set(c), Set.empty))
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd2, _, _, _, _, _) = paymentFSM.stateData
    // and reply a 2nd time with an unparsable failure
    relayer.expectMsg(ForwardShortId(channelId_ab, cmd2))
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash)) // unparsable message

    // we allow 2 tries, so we send a 2nd request to the router
    assert(sender.expectMsgType[PaymentFailed].failures === UnreadableRemoteFailure(hops) :: UnreadableRemoteFailure(hops) :: Nil)
    awaitCond(paymentDb.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed])) // after last attempt the payment is failed
  }

  test("payment failed (local error)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val paymentDb = nodeParams.db.payments
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(id, defaultPaymentRequest, paymentDb)
    val paymentFSM = TestFSMRef(new PaymentLifecycle(nodeParams, progressHandler, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultPaymentHash, d, FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)), maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, _) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, Status.Failure(AddHtlcFailed(ByteVector32.Zeroes, request.paymentHash, ChannelUnavailable(ByteVector32.Zeroes), Local(id, Some(paymentFSM.underlying.self)), None, None)))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_ab, a, b))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending)) // payment is still pending because the error is recoverable
  }

  test("payment failed (first hop returns an UpdateFailMalformedHtlc)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val paymentDb = nodeParams.db.payments
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(id, defaultPaymentRequest, paymentDb)
    val paymentFSM = TestFSMRef(new PaymentLifecycle(nodeParams, progressHandler, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultPaymentHash, d, FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)), maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, _) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailMalformedHtlc(ByteVector32.Zeroes, 0, randomBytes32, FailureMessageCodecs.BADONION))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_ab, a, b))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))
  }

  test("payment failed (TemporaryChannelFailure)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(id, defaultPaymentRequest, nodeParams.db.payments)
    val paymentFSM = TestFSMRef(new PaymentLifecycle(nodeParams, progressHandler, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultPaymentHash, d, FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)), maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData

    val failure = TemporaryChannelFailure(channelUpdate_bc)

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))

    // payment lifecycle will ask the router to temporarily exclude this channel from its route calculations
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(channelUpdate_bc.shortChannelId, b, c)))
    routerForwarder.forward(router)
    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    // we allow 2 tries, so we send a 2nd request to the router
    assert(sender.expectMsgType[PaymentFailed].failures === RemoteFailure(hops, Sphinx.DecryptedFailurePacket(b, failure)) :: LocalFailure(RouteNotFound) :: Nil)
  }

  test("payment failed (Update)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val paymentDb = nodeParams.db.payments
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(id, defaultPaymentRequest, paymentDb)
    val paymentFSM = TestFSMRef(new PaymentLifecycle(nodeParams, progressHandler, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultPaymentHash, d, FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)), maxAttempts = 5)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData
    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))

    // we change the cltv expiry
    val channelUpdate_bc_modified = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, CltvExpiryDelta(42), htlcMinimumMsat = channelUpdate_bc.htlcMinimumMsat, feeBaseMsat = channelUpdate_bc.feeBaseMsat, feeProportionalMillionths = channelUpdate_bc.feeProportionalMillionths, htlcMaximumMsat = channelUpdate_bc.htlcMaximumMsat.get)
    val failure = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending)) // 1 failure but not final, the payment is still PENDING
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)

    // router answers with a new route, taking into account the new update
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd2, _, sharedSecrets2, _, _, hops2) = paymentFSM.stateData
    relayer.expectMsg(ForwardShortId(channelId_ab, cmd2))

    // we change the cltv expiry one more time
    val channelUpdate_bc_modified_2 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, CltvExpiryDelta(43), htlcMinimumMsat = channelUpdate_bc.htlcMinimumMsat, feeBaseMsat = channelUpdate_bc.feeBaseMsat, feeProportionalMillionths = channelUpdate_bc.feeProportionalMillionths, htlcMaximumMsat = channelUpdate_bc.htlcMaximumMsat.get)
    val failure2 = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified_2)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets2.head._1, failure2)))

    // this time the payment lifecycle will ask the router to temporarily exclude this channel from its route calculations
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(channelUpdate_bc.shortChannelId, b, c)))
    routerForwarder.forward(router)
    // but it will still forward the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified_2)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)

    // this time the router can't find a route: game over
    assert(sender.expectMsgType[PaymentFailed].failures === RemoteFailure(hops, Sphinx.DecryptedFailurePacket(b, failure)) :: RemoteFailure(hops2, Sphinx.DecryptedFailurePacket(b, failure2)) :: LocalFailure(RouteNotFound) :: Nil)
    awaitCond(paymentDb.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment failed (Update in assisted route)") { fixture =>
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val paymentDb = nodeParams.db.payments
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(id, defaultPaymentRequest, paymentDb)
    val paymentFSM = TestFSMRef(new PaymentLifecycle(nodeParams, progressHandler, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    // we build an assisted route for channel bc and cd
    val assistedRoutes = Seq(Seq(
      ExtraHop(b, channelId_bc, channelUpdate_bc.feeBaseMsat, channelUpdate_bc.feeProportionalMillionths, channelUpdate_bc.cltvExpiryDelta),
      ExtraHop(c, channelId_cd, channelUpdate_cd.feeBaseMsat, channelUpdate_cd.feeProportionalMillionths, channelUpdate_cd.cltvExpiryDelta)
    ))

    val request = SendPayment(defaultPaymentHash, d, FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)), maxAttempts = 5, assistedRoutes = assistedRoutes)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = assistedRoutes, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, _) = paymentFSM.stateData
    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))

    // we change the cltv expiry
    val channelUpdate_bc_modified = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, CltvExpiryDelta(42), htlcMinimumMsat = channelUpdate_bc.htlcMinimumMsat, feeBaseMsat = channelUpdate_bc.feeBaseMsat, feeProportionalMillionths = channelUpdate_bc.feeProportionalMillionths, htlcMaximumMsat = channelUpdate_bc.htlcMaximumMsat.get)
    val failure = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending)) // 1 failure but not final, the payment is still PENDING
    val assistedRoutes1 = Seq(Seq(
      ExtraHop(b, channelId_bc, channelUpdate_bc.feeBaseMsat, channelUpdate_bc.feeProportionalMillionths, channelUpdate_bc_modified.cltvExpiryDelta),
      ExtraHop(c, channelId_cd, channelUpdate_cd.feeBaseMsat, channelUpdate_cd.feeProportionalMillionths, channelUpdate_cd.cltvExpiryDelta)
    ))
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = assistedRoutes1, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)

    // router answers with a new route, taking into account the new update
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd2, _, _, _, _, _) = paymentFSM.stateData
    relayer.expectMsg(ForwardShortId(channelId_ab, cmd2))
    assert(cmd2.cltvExpiry > cmd1.cltvExpiry)
  }

  def testPermanentFailure(fixture: FixtureParam, failure: FailureMessage): Unit = {
    import fixture._
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val paymentDb = nodeParams.db.payments
    val relayer = TestProbe()
    val routerForwarder = TestProbe()
    val id = UUID.randomUUID()
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(id, defaultPaymentRequest, paymentDb)
    val paymentFSM = TestFSMRef(new PaymentLifecycle(nodeParams, progressHandler, routerForwarder.ref, relayer.ref))
    val monitor = TestProbe()
    val sender = TestProbe()

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(defaultPaymentHash, d, FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)), maxAttempts = 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && paymentDb.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData

    relayer.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_bc, b, c))))
    routerForwarder.forward(router)
    // we allow 2 tries, so we send a 2nd request to the router, which won't find another route

    assert(sender.expectMsgType[PaymentFailed].failures === RemoteFailure(hops, Sphinx.DecryptedFailurePacket(b, failure)) :: LocalFailure(RouteNotFound) :: Nil)
    awaitCond(paymentDb.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment failed (PermanentChannelFailure)") { fixture =>
    testPermanentFailure(fixture, PermanentChannelFailure)
  }

  test("payment failed (deprecated permanent failure)") { fixture =>
    import scodec.bits.HexStringSyntax
    // PERM | 17 (final_expiry_too_soon) has been deprecated but older nodes might still use it.
    testPermanentFailure(fixture, FailureMessageCodecs.failureMessageCodec.decode(hex"4011".bits).require.value)
  }

  test("payment succeeded") { fixture =>
    import fixture._
    val paymentPreimage = randomBytes32
    val paymentHash = Crypto.sha256(paymentPreimage)
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val paymentDb = nodeParams.db.payments
    val id = UUID.randomUUID()
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(id, defaultPaymentRequest.copy(paymentHash = paymentHash), paymentDb)
    val paymentFSM = system.actorOf(PaymentLifecycle.props(nodeParams, progressHandler, router, TestProbe().ref))
    val monitor = TestProbe()
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    val request = SendPayment(paymentHash, d, FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)), maxAttempts = 5)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(paymentDb.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))
    val Some(outgoing) = paymentDb.getOutgoingPayment(id)
    assert(outgoing.copy(createdAt = 0) === OutgoingPayment(id, id, Some(defaultExternalId), paymentHash, defaultAmountMsat, d, 0, None, OutgoingPaymentStatus.Pending))
    sender.send(paymentFSM, UpdateFulfillHtlc(ByteVector32.Zeroes, 0, paymentPreimage))

    val ps = eventListener.expectMsgType[PaymentSent]
    assert(ps.feesPaid > 0.msat)
    assert(ps.amount === defaultAmountMsat)
    assert(ps.paymentHash === paymentHash)
    assert(ps.paymentPreimage === paymentPreimage)
    awaitCond(paymentDb.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))
  }

  test("payment succeeded to a channel with fees=0") { fixture =>
    import fixture._
    import fr.acinq.eclair.randomKey
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    // the network will be a --(1)--> b ---(2)--> c --(3)--> d  and e --(4)--> f (we are a) and b -> g has fees=0
    //                                 \
    //                                  \--(5)--> g

    val (priv_g, priv_funding_g) = (randomKey, randomKey)
    val (g, funding_g) = (priv_g.publicKey, priv_funding_g.publicKey)
    val ann_g = makeNodeAnnouncement(priv_g, "node-G", Color(-30, 10, -50), Nil, hex"0200")
    val channelId_bg = ShortChannelId(420000, 5, 0)
    val chan_bg = channelAnnouncement(channelId_bg, priv_b, priv_g, priv_funding_b, priv_funding_g)
    val channelUpdate_bg = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, g, channelId_bg, CltvExpiryDelta(9), htlcMinimumMsat = 0 msat, feeBaseMsat = 0 msat, feeProportionalMillionths = 0, htlcMaximumMsat = 500000000 msat)
    val channelUpdate_gb = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_g, b, channelId_bg, CltvExpiryDelta(9), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 8, htlcMaximumMsat = 500000000 msat)
    assert(Router.getDesc(channelUpdate_bg, chan_bg) === ChannelDesc(chan_bg.shortChannelId, priv_b.publicKey, priv_g.publicKey))
    router ! PeerRoutingMessage(null, remoteNodeId, chan_bg)
    router ! PeerRoutingMessage(null, remoteNodeId, ann_g)
    router ! PeerRoutingMessage(null, remoteNodeId, channelUpdate_bg)
    router ! PeerRoutingMessage(null, remoteNodeId, channelUpdate_gb)
    watcher.expectMsg(ValidateRequest(chan_bg))
    watcher.send(router, ValidateResult(chan_bg, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_b, funding_g)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
    watcher.expectMsgType[WatchSpentBasic]

    // actual test begins
    val progressHandler = PaymentLifecycle.DefaultPaymentProgressHandler(UUID.randomUUID(), defaultPaymentRequest.copy(targetNodeId = g), nodeParams.db.payments)
    val paymentFSM = system.actorOf(PaymentLifecycle.props(nodeParams, progressHandler, router, TestProbe().ref))
    val monitor = TestProbe()
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])

    // we send a payment to G which is just after the
    val request = SendPayment(defaultPaymentHash, g, FinalLegacyPayload(defaultAmountMsat, defaultExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight)), maxAttempts = 5)
    sender.send(paymentFSM, request)

    // the route will be A -> B -> G where B -> G has a channel_update with fees=0
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.send(paymentFSM, UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash))

    val paymentOK = sender.expectMsgType[PaymentSent]
    val PaymentSent(_, request.paymentHash, paymentOK.paymentPreimage, PartialPayment(_, request.finalPayload.amount, fee, ByteVector32.Zeroes, _, _) :: Nil) = eventListener.expectMsgType[PaymentSent]

    // during the route computation the fees were treated as if they were 1msat but when sending the onion we actually put zero
    // NB: A -> B doesn't pay fees because it's our direct neighbor
    // NB: B -> G doesn't asks for fees at all
    assert(fee === 0.msat)
    assert(fee === paymentOK.amount - request.finalPayload.amount)
  }

  test("filter errors properly") { _ =>
    val failures = LocalFailure(RouteNotFound) :: RemoteFailure(Hop(a, b, channelUpdate_ab) :: Nil, Sphinx.DecryptedFailurePacket(a, TemporaryNodeFailure)) :: LocalFailure(AddHtlcFailed(ByteVector32.Zeroes, ByteVector32.Zeroes, ChannelUnavailable(ByteVector32.Zeroes), Local(UUID.randomUUID(), None), None, None)) :: LocalFailure(RouteNotFound) :: Nil
    val filtered = PaymentFailure.transformForUser(failures)
    assert(filtered == LocalFailure(RouteNotFound) :: RemoteFailure(Hop(a, b, channelUpdate_ab) :: Nil, Sphinx.DecryptedFailurePacket(a, TemporaryNodeFailure)) :: LocalFailure(ChannelUnavailable(ByteVector32.Zeroes)) :: Nil)
  }

}
