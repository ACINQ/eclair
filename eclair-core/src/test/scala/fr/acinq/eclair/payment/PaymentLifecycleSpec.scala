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
import akka.actor.{ActorRef, Status}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto, Transaction, TxOut}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.{UtxoStatus, ValidateRequest, ValidateResult, WatchSpentBasic}
import fr.acinq.eclair.channel.Register.ForwardShortId
import fr.acinq.eclair.channel.{AddHtlcFailed, Channel, ChannelUnavailable, Upstream}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus, PaymentType}
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment.relay.Origin.Local
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPaymentConfig, SendPaymentRequest}
import fr.acinq.eclair.payment.send.PaymentLifecycle
import fr.acinq.eclair.payment.send.PaymentLifecycle._
import fr.acinq.eclair.router.Announcements.{makeChannelUpdate, makeNodeAnnouncement}
import fr.acinq.eclair.router._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire._
import scodec.bits.HexStringSyntax

import scala.concurrent.duration._

/**
 * Created by PM on 29/08/2016.
 */

class PaymentLifecycleSpec extends BaseRouterSpec {

  val defaultAmountMsat = 142000000 msat
  val defaultExpiry = Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(40000)
  val defaultPaymentPreimage = randomBytes32
  val defaultPaymentHash = Crypto.sha256(defaultPaymentPreimage)
  val defaultExternalId = UUID.randomUUID().toString
  val defaultPaymentRequest = SendPaymentRequest(defaultAmountMsat, defaultPaymentHash, d, 1, externalId = Some(defaultExternalId))

  case class PaymentFixture(id: UUID,
                            parentId: UUID,
                            nodeParams: NodeParams,
                            paymentFSM: TestFSMRef[PaymentLifecycle.State, PaymentLifecycle.Data, PaymentLifecycle],
                            routerForwarder: TestProbe,
                            register: TestProbe,
                            sender: TestProbe,
                            monitor: TestProbe,
                            eventListener: TestProbe)

  def createPaymentLifecycle(storeInDb: Boolean = true, publishEvent: Boolean = true): PaymentFixture = {
    val (id, parentId) = (UUID.randomUUID(), UUID.randomUUID())
    val nodeParams = TestConstants.Alice.nodeParams.copy(keyManager = testKeyManager)
    val cfg = SendPaymentConfig(id, parentId, Some(defaultExternalId), defaultPaymentHash, defaultAmountMsat, d, Upstream.Local(id), defaultPaymentRequest.paymentRequest, storeInDb, publishEvent, Nil)
    val (routerForwarder, register, sender, monitor, eventListener) = (TestProbe(), TestProbe(), TestProbe(), TestProbe(), TestProbe())
    val paymentFSM = TestFSMRef(new PaymentLifecycle(nodeParams, cfg, routerForwarder.ref, register.ref))
    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
    PaymentFixture(id, parentId, nodeParams, paymentFSM, routerForwarder, register, sender, monitor, eventListener)
  }

  test("send to route") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    // pre-computed route going from A to D
    val request = SendPaymentToRoute(Seq(a, b, c, d), FinalLegacyPayload(defaultAmountMsat, defaultExpiry))

    sender.send(paymentFSM, request)
    routerForwarder.expectMsg(FinalizeRoute(Seq(a, b, c, d)))
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    routerForwarder.forward(routerFixture.router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(id)
    assert(outgoing.copy(createdAt = 0) === OutgoingPayment(id, parentId, Some(defaultExternalId), defaultPaymentHash, PaymentType.Standard, defaultAmountMsat, defaultAmountMsat, d, 0, None, OutgoingPaymentStatus.Pending))
    sender.send(paymentFSM, UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash))

    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id === parentId)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))
  }

  test("send to route (edges not found in the graph)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val brokenRoute = SendPaymentToRoute(Seq(randomKey.publicKey, randomKey.publicKey, randomKey.publicKey), FinalLegacyPayload(defaultAmountMsat, defaultExpiry))
    sender.send(paymentFSM, brokenRoute)
    routerForwarder.expectMsgType[FinalizeRoute]
    routerForwarder.forward(routerFixture.router)

    val failureMessage = eventListener.expectMsgType[PaymentFailed].failures.head.asInstanceOf[LocalFailure].t.getMessage
    assert(failureMessage == "Not all the nodes in the supplied route are connected with public channels")
  }

  test("send with route prefix") { _ =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(d, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 3, routePrefix = Seq(ChannelHop(a, b, channelUpdate_ab), ChannelHop(b, c, channelUpdate_bc)))
    sender.send(paymentFSM, request)
    routerForwarder.expectMsg(RouteRequest(c, d, defaultAmountMsat, ignoreNodes = Set(a, b)))
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))

    routerForwarder.send(paymentFSM, RouteResponse(Seq(ChannelHop(c, d, channelUpdate_cd)), Set.empty, Set.empty))
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
  }

  test("send with whole route prefix") { _ =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(c, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 3, routePrefix = Seq(ChannelHop(a, b, channelUpdate_ab), ChannelHop(b, c, channelUpdate_bc)))
    sender.send(paymentFSM, request)
    routerForwarder.expectNoMsg(50 millis) // we don't need the router when we already have the whole route
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
  }

  test("send with route prefix and retry") { _ =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(d, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 3, routePrefix = Seq(ChannelHop(a, b, channelUpdate_ab), ChannelHop(b, c, channelUpdate_bc)))
    sender.send(paymentFSM, request)
    routerForwarder.expectMsg(RouteRequest(c, d, defaultAmountMsat, ignoreNodes = Set(a, b)))
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))

    routerForwarder.send(paymentFSM, RouteResponse(Seq(ChannelHop(c, d, channelUpdate_cd)), Set(a, b), Set.empty))
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.send(paymentFSM, UpdateFailHtlc(randomBytes32, 0, randomBytes(Sphinx.FailurePacket.PacketLength)))
    routerForwarder.expectMsg(RouteRequest(c, d, defaultAmountMsat, ignoreNodes = Set(a, b, c)))
    val Transition(_, WAITING_FOR_PAYMENT_COMPLETE, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    assert(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
  }

  test("payment failed (route not found)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(f, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 5)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val routeRequest = routerForwarder.expectMsgType[RouteRequest]
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    routerForwarder.forward(routerFixture.router, routeRequest)
    assert(sender.expectMsgType[PaymentFailed].failures === LocalFailure(RouteNotFound) :: Nil)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment failed (route too expensive)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(d, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 5, routeParams = Some(RouteParams(randomize = false, maxFeeBase = 100 msat, maxFeePct = 0.0, routeMaxLength = 20, routeMaxCltv = CltvExpiryDelta(2016), ratios = None)))
    sender.send(paymentFSM, request)
    val routeRequest = routerForwarder.expectMsgType[RouteRequest]
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    routerForwarder.forward(routerFixture.router, routeRequest)
    val Seq(LocalFailure(RouteNotFound)) = sender.expectMsgType[PaymentFailed].failures
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment failed (unparsable failure)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(d, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 2)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, hops) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash)) // unparsable message

    // then the payment lifecycle will ask for a new route excluding all intermediate nodes
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, ignoreNodes = Set(c), ignoreChannels = Set.empty))

    // let's simulate a response by the router with another route
    sender.send(paymentFSM, RouteResponse(hops, Set(c), Set.empty))
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd2, _, _, _, _, _) = paymentFSM.stateData
    // and reply a 2nd time with an unparsable failure
    register.expectMsg(ForwardShortId(channelId_ab, cmd2))
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash)) // unparsable message

    // we allow 2 tries, so we send a 2nd request to the router
    assert(sender.expectMsgType[PaymentFailed].failures === UnreadableRemoteFailure(hops) :: UnreadableRemoteFailure(hops) :: Nil)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed])) // after last attempt the payment is failed
  }

  test("payment failed (local error)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(d, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, _) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, Status.Failure(AddHtlcFailed(ByteVector32.Zeroes, defaultPaymentHash, ChannelUnavailable(ByteVector32.Zeroes), Local(id, Some(paymentFSM.underlying.self)), None, None)))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_ab, a, b))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending)) // payment is still pending because the error is recoverable
  }

  test("payment failed (first hop returns an UpdateFailMalformedHtlc)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(d, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, _, _, _, _) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailMalformedHtlc(ByteVector32.Zeroes, 0, randomBytes32, FailureMessageCodecs.BADONION))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_ab, a, b))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))
  }

  test("payment failed (TemporaryChannelFailure)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(d, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(channelId_ab, cmd1))
    val failure = TemporaryChannelFailure(channelUpdate_bc)
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))

    // payment lifecycle will ask the router to temporarily exclude this channel from its route calculations
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(channelUpdate_bc.shortChannelId, b, c)))
    routerForwarder.forward(routerFixture.router)
    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(a, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(routerFixture.router)
    // we allow 2 tries, so we send a 2nd request to the router
    assert(sender.expectMsgType[PaymentFailed].failures === RemoteFailure(hops, Sphinx.DecryptedFailurePacket(b, failure)) :: LocalFailure(RouteNotFound) :: Nil)
  }

  test("payment failed (Update)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(d, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 5)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(channelId_ab, cmd1))

    // we change the cltv expiry
    val channelUpdate_bc_modified = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, CltvExpiryDelta(42), htlcMinimumMsat = channelUpdate_bc.htlcMinimumMsat, feeBaseMsat = channelUpdate_bc.feeBaseMsat, feeProportionalMillionths = channelUpdate_bc.feeProportionalMillionths, htlcMaximumMsat = channelUpdate_bc.htlcMaximumMsat.get)
    val failure = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending)) // 1 failure but not final, the payment is still PENDING
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(routerFixture.router)

    // router answers with a new route, taking into account the new update
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd2, _, sharedSecrets2, _, _, hops2) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(channelId_ab, cmd2))

    // we change the cltv expiry one more time
    val channelUpdate_bc_modified_2 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, CltvExpiryDelta(43), htlcMinimumMsat = channelUpdate_bc.htlcMinimumMsat, feeBaseMsat = channelUpdate_bc.feeBaseMsat, feeProportionalMillionths = channelUpdate_bc.feeProportionalMillionths, htlcMaximumMsat = channelUpdate_bc.htlcMaximumMsat.get)
    val failure2 = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified_2)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets2.head._1, failure2)))

    // this time the payment lifecycle will ask the router to temporarily exclude this channel from its route calculations
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(channelUpdate_bc.shortChannelId, b, c)))
    routerForwarder.forward(routerFixture.router)
    // but it will still forward the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified_2)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(routerFixture.router)

    // this time the router can't find a route: game over
    assert(sender.expectMsgType[PaymentFailed].failures === RemoteFailure(hops, Sphinx.DecryptedFailurePacket(b, failure)) :: RemoteFailure(hops2, Sphinx.DecryptedFailurePacket(b, failure2)) :: LocalFailure(RouteNotFound) :: Nil)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment failed (Update in assisted route)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    // we build an assisted route for channel bc and cd
    val assistedRoutes = Seq(Seq(
      ExtraHop(b, channelId_bc, channelUpdate_bc.feeBaseMsat, channelUpdate_bc.feeProportionalMillionths, channelUpdate_bc.cltvExpiryDelta),
      ExtraHop(c, channelId_cd, channelUpdate_cd.feeBaseMsat, channelUpdate_cd.feeProportionalMillionths, channelUpdate_cd.cltvExpiryDelta)
    ))

    val request = SendPayment(d, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 5, assistedRoutes = assistedRoutes)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = assistedRoutes, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, _) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(channelId_ab, cmd1))

    // we change the cltv expiry
    val channelUpdate_bc_modified = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, CltvExpiryDelta(42), htlcMinimumMsat = channelUpdate_bc.htlcMinimumMsat, feeBaseMsat = channelUpdate_bc.feeBaseMsat, feeProportionalMillionths = channelUpdate_bc.feeProportionalMillionths, htlcMaximumMsat = channelUpdate_bc.htlcMaximumMsat.get)
    val failure = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending)) // 1 failure but not final, the payment is still PENDING
    val assistedRoutes1 = Seq(Seq(
      ExtraHop(b, channelId_bc, channelUpdate_bc.feeBaseMsat, channelUpdate_bc.feeProportionalMillionths, channelUpdate_bc_modified.cltvExpiryDelta),
      ExtraHop(c, channelId_cd, channelUpdate_cd.feeBaseMsat, channelUpdate_cd.feeProportionalMillionths, channelUpdate_cd.cltvExpiryDelta)
    ))
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = assistedRoutes1, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(routerFixture.router)

    // router answers with a new route, taking into account the new update
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd2, _, _, _, _, _) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(channelId_ab, cmd2))
    assert(cmd2.cltvExpiry > cmd1.cltvExpiry)
  }

  def testPermanentFailure(router: ActorRef, failure: FailureMessage): Unit = {
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(d, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, _, Nil) = paymentFSM.stateData
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set.empty))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, _, cmd1, Nil, sharedSecrets1, _, _, hops) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(channelId_ab, cmd1))
    sender.send(paymentFSM, UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))

    // payment lifecycle forwards the embedded channelUpdate to the router
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(RouteRequest(nodeParams.nodeId, d, defaultAmountMsat, assistedRoutes = Nil, ignoreNodes = Set.empty, ignoreChannels = Set(ChannelDesc(channelId_bc, b, c))))
    routerForwarder.forward(router)
    // we allow 2 tries, so we send a 2nd request to the router, which won't find another route

    assert(sender.expectMsgType[PaymentFailed].failures === RemoteFailure(hops, Sphinx.DecryptedFailurePacket(b, failure)) :: LocalFailure(RouteNotFound) :: Nil)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment failed (PermanentChannelFailure)") { routerFixture =>
    testPermanentFailure(routerFixture.router, PermanentChannelFailure)
  }

  test("payment failed (deprecated permanent failure)") { routerFixture =>
    import scodec.bits.HexStringSyntax
    // PERM | 17 (final_expiry_too_soon) has been deprecated but older nodes might still use it.
    testPermanentFailure(routerFixture.router, FailureMessageCodecs.failureMessageCodec.decode(hex"4011".bits).require.value)
  }

  test("payment succeeded") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(d, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 5)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsgType[RouteRequest]
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    routerForwarder.forward(routerFixture.router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))
    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(id)
    assert(outgoing.copy(createdAt = 0) === OutgoingPayment(id, parentId, Some(defaultExternalId), defaultPaymentHash, PaymentType.Standard, defaultAmountMsat, defaultAmountMsat, d, 0, None, OutgoingPaymentStatus.Pending))
    sender.send(paymentFSM, UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentPreimage))

    val ps = eventListener.expectMsgType[PaymentSent]
    assert(ps.id === parentId)
    assert(ps.feesPaid > 0.msat)
    assert(ps.recipientAmount === defaultAmountMsat)
    assert(ps.paymentHash === defaultPaymentHash)
    assert(ps.paymentPreimage === defaultPaymentPreimage)
    assert(ps.parts.head.id === id)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))
  }

  test("payment succeeded to a channel with fees=0") { routerFixture =>
    import fr.acinq.eclair.randomKey
    import routerFixture._

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

    val payFixture = createPaymentLifecycle()
    import payFixture._

    // we send a payment to G
    val request = SendPayment(g, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 5)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsgType[RouteRequest]

    // the route will be A -> B -> G where B -> G has a channel_update with fees=0
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    routerForwarder.forward(router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.send(paymentFSM, UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash))
    val paymentOK = sender.expectMsgType[PaymentSent]
    val PaymentSent(_, _, paymentOK.paymentPreimage, finalAmount, _, PartialPayment(_, request.finalPayload.amount, fee, ByteVector32.Zeroes, _, _) :: Nil) = eventListener.expectMsgType[PaymentSent]
    assert(finalAmount === defaultAmountMsat)

    // during the route computation the fees were treated as if they were 1msat but when sending the onion we actually put zero
    // NB: A -> B doesn't pay fees because it's our direct neighbor
    // NB: B -> G doesn't asks for fees at all
    assert(fee === 0.msat)
    assert(paymentOK.recipientAmount === request.finalPayload.amount)
  }

  test("filter errors properly") { _ =>
    val failures = LocalFailure(RouteNotFound) :: RemoteFailure(ChannelHop(a, b, channelUpdate_ab) :: Nil, Sphinx.DecryptedFailurePacket(a, TemporaryNodeFailure)) :: LocalFailure(AddHtlcFailed(ByteVector32.Zeroes, ByteVector32.Zeroes, ChannelUnavailable(ByteVector32.Zeroes), Local(UUID.randomUUID(), None), None, None)) :: LocalFailure(RouteNotFound) :: Nil
    val filtered = PaymentFailure.transformForUser(failures)
    assert(filtered == LocalFailure(RouteNotFound) :: RemoteFailure(ChannelHop(a, b, channelUpdate_ab) :: Nil, Sphinx.DecryptedFailurePacket(a, TemporaryNodeFailure)) :: LocalFailure(ChannelUnavailable(ByteVector32.Zeroes)) :: Nil)
  }

  test("disable database and events") { routerFixture =>
    val payFixture = createPaymentLifecycle(storeInDb = false, publishEvent = false)
    import payFixture._

    val request = SendPayment(d, FinalLegacyPayload(defaultAmountMsat, defaultExpiry), 3)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsgType[RouteRequest]
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    routerForwarder.forward(routerFixture.router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    assert(nodeParams.db.payments.getOutgoingPayment(id) === None)

    sender.send(paymentFSM, UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentPreimage))
    sender.expectMsgType[PaymentSent]
    assert(nodeParams.db.payments.getOutgoingPayment(id) === None)
    eventListener.expectNoMsg(100 millis)
  }

}
