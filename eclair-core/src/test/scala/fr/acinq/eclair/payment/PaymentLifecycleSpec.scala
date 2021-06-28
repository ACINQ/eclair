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

import akka.actor.ActorRef
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Script.{pay2wsh, write}
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto, SatoshiLong, Transaction, TxOut}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{UtxoStatus, ValidateRequest, ValidateResult, WatchExternalChannelSpent}
import fr.acinq.eclair.channel.Register.{ForwardShortId, ForwardShortIdFailure}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus, PaymentType}
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle
import fr.acinq.eclair.payment.send.PaymentLifecycle._
import fr.acinq.eclair.router.Announcements.makeChannelUpdate
import fr.acinq.eclair.router.BaseRouterSpec.channelAnnouncement
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.router._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol._

import java.util.UUID
import scala.concurrent.duration._

/**
 * Created by PM on 29/08/2016.
 */

class PaymentLifecycleSpec extends BaseRouterSpec {

  val defaultAmountMsat = 142000000 msat
  val defaultMaxFee = 4260000 msat // 3% of defaultAmountMsat
  val defaultExpiry = Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(40000)
  val defaultPaymentPreimage = randomBytes32()
  val defaultPaymentHash = Crypto.sha256(defaultPaymentPreimage)
  val defaultOrigin = Origin.LocalCold(UUID.randomUUID())
  val defaultExternalId = UUID.randomUUID().toString
  val defaultInvoice = PaymentRequest(Block.RegtestGenesisBlock.hash, None, defaultPaymentHash, priv_d, "test", Channel.MIN_CLTV_EXPIRY_DELTA)

  def defaultRouteRequest(source: PublicKey, target: PublicKey, cfg: SendPaymentConfig): RouteRequest = RouteRequest(source, target, defaultAmountMsat, defaultMaxFee, paymentContext = Some(cfg.paymentContext))

  case class PaymentFixture(cfg: SendPaymentConfig,
                            nodeParams: NodeParams,
                            paymentFSM: TestFSMRef[PaymentLifecycle.State, PaymentLifecycle.Data, PaymentLifecycle],
                            routerForwarder: TestProbe,
                            register: TestProbe,
                            sender: TestProbe,
                            monitor: TestProbe,
                            eventListener: TestProbe)

  def createPaymentLifecycle(storeInDb: Boolean = true, publishEvent: Boolean = true): PaymentFixture = {
    val (id, parentId) = (UUID.randomUUID(), UUID.randomUUID())
    val nodeParams = TestConstants.Alice.nodeParams.copy(nodeKeyManager = testNodeKeyManager, channelKeyManager = testChannelKeyManager)
    val cfg = SendPaymentConfig(id, parentId, Some(defaultExternalId), defaultPaymentHash, defaultAmountMsat, d, Upstream.Local(id), Some(defaultInvoice), storeInDb, publishEvent, Nil)
    val (routerForwarder, register, sender, monitor, eventListener) = (TestProbe(), TestProbe(), TestProbe(), TestProbe(), TestProbe())
    val paymentFSM = TestFSMRef(new PaymentLifecycle(nodeParams, cfg, routerForwarder.ref, register.ref))
    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
    PaymentFixture(cfg, nodeParams, paymentFSM, routerForwarder, register, sender, monitor, eventListener)
  }

  def addCompleted(result: HtlcResult) = {
    RES_ADD_SETTLED(
      origin = defaultOrigin,
      htlc = UpdateAddHtlc(ByteVector32.Zeroes, 0, defaultAmountMsat, defaultPaymentHash, defaultExpiry, TestConstants.emptyOnionPacket),
      result)
  }

  test("send to route") { _ =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    // pre-computed route going from A to D
    val route = Route(defaultAmountMsat, ChannelHop(a, b, update_ab) :: ChannelHop(b, c, update_bc) :: ChannelHop(c, d, update_cd) :: Nil)
    val request = SendPaymentToRoute(sender.ref, Right(route), Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get))
    sender.send(paymentFSM, request)
    routerForwarder.expectNoMsg(100 millis) // we don't need the router, we have the pre-computed route
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(id)
    assert(outgoing.copy(createdAt = 0) === OutgoingPayment(id, parentId, Some(defaultExternalId), defaultPaymentHash, PaymentType.Standard, defaultAmountMsat, defaultAmountMsat, d, 0, Some(defaultInvoice), OutgoingPaymentStatus.Pending))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFulfill(UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentPreimage))))

    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id === parentId)
    assert(ps.parts.head.route === Some(route.hops))
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))
  }

  test("send to route (node_id only)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    // pre-computed route going from A to D
    val route = PredefinedNodeRoute(Seq(a, b, c, d))
    val request = SendPaymentToRoute(sender.ref, Left(route), Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get))

    sender.send(paymentFSM, request)
    routerForwarder.expectMsg(FinalizeRoute(defaultAmountMsat, route, paymentContext = Some(cfg.paymentContext)))
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    routerForwarder.forward(routerFixture.router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(id)
    assert(outgoing.copy(createdAt = 0) === OutgoingPayment(id, parentId, Some(defaultExternalId), defaultPaymentHash, PaymentType.Standard, defaultAmountMsat, defaultAmountMsat, d, 0, Some(defaultInvoice), OutgoingPaymentStatus.Pending))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFulfill(UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentPreimage))))

    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id === parentId)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))
  }

  test("send to route (nodes not found in the graph)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val brokenRoute = SendPaymentToRoute(sender.ref, Left(PredefinedNodeRoute(Seq(randomKey().publicKey, randomKey().publicKey, randomKey().publicKey))), Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get))
    sender.send(paymentFSM, brokenRoute)
    routerForwarder.expectMsgType[FinalizeRoute]
    routerForwarder.forward(routerFixture.router)

    val failureMessage = eventListener.expectMsgType[PaymentFailed].failures.head.asInstanceOf[LocalFailure].t.getMessage
    assert(failureMessage == "Not all the nodes in the supplied route are connected with public channels")
  }

  test("send to route (channels not found in the graph)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val brokenRoute = SendPaymentToRoute(sender.ref, Left(PredefinedChannelRoute(randomKey().publicKey, Seq(ShortChannelId(1), ShortChannelId(2)))), Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get))
    sender.send(paymentFSM, brokenRoute)
    routerForwarder.expectMsgType[FinalizeRoute]
    routerForwarder.forward(routerFixture.router)

    val failureMessage = eventListener.expectMsgType[PaymentFailed].failures.head.asInstanceOf[LocalFailure].t.getMessage
    assert(failureMessage == "The sequence of channels provided cannot be used to build a route to the target node")
  }

  test("send to route (routing hints)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    val recipient = randomKey().publicKey
    val route = PredefinedNodeRoute(Seq(a, b, c, recipient))
    val routingHint = Seq(Seq(ExtraHop(c, ShortChannelId(561), 1 msat, 100, CltvExpiryDelta(144))))
    val request = SendPaymentToRoute(sender.ref, Left(route), Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), routingHint)

    sender.send(paymentFSM, request)
    routerForwarder.expectMsg(FinalizeRoute(defaultAmountMsat, route, routingHint, paymentContext = Some(cfg.paymentContext)))
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    routerForwarder.forward(routerFixture.router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    // Payment accepted by the recipient.
    sender.send(paymentFSM, addCompleted(HtlcResult.OnChainFulfill(defaultPaymentPreimage)))

    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id === parentId)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))
  }

  test("payment failed (route not found)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    val request = SendPayment(sender.ref, f, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 5)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val routeRequest = routerForwarder.expectMsgType[RouteRequest]
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    routerForwarder.forward(routerFixture.router, routeRequest)
    assert(sender.expectMsgType[PaymentFailed].failures === LocalFailure(Nil, RouteNotFound) :: Nil)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment failed (route too expensive)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 5, routeParams = Some(RouteParams(randomize = false, 100 msat, 0.0, 20, CltvExpiryDelta(2016), None, MultiPartParams(10000 msat, 5))))
    sender.send(paymentFSM, request)
    val routeRequest = routerForwarder.expectMsgType[RouteRequest]
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    routerForwarder.forward(routerFixture.router, routeRequest)
    val Seq(LocalFailure(Nil, RouteNotFound)) = sender.expectMsgType[PaymentFailed].failures
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment failed (unparsable failure)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 2)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsg(defaultRouteRequest(a, d, cfg))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, _, ignore1, route) = paymentFSM.stateData
    assert(ignore1.nodes.isEmpty)

    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd1))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, randomBytes32())))) // unparsable message

    // then the payment lifecycle will ask for a new route excluding all intermediate nodes
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg).copy(ignore = Ignore(Set(c), Set.empty)))

    // let's simulate a response by the router with another route
    sender.send(paymentFSM, RouteResponse(route :: Nil))
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd2, _, _, ignore2, _) = paymentFSM.stateData
    assert(ignore2.nodes === Set(c))
    // and reply a 2nd time with an unparsable failure
    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd2))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash)))) // unparsable message

    // we allow 2 tries, so we send a 2nd request to the router
    assert(sender.expectMsgType[PaymentFailed].failures === UnreadableRemoteFailure(route.hops) :: UnreadableRemoteFailure(route.hops) :: Nil)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed])) // after last attempt the payment is failed
  }

  test("payment failed (local error)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))
    routerForwarder.expectMsgType[RouteRequest]
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)

    val WaitingForComplete(_, cmd1, Nil, _, _, _) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd1))
    sender.send(paymentFSM, RES_ADD_FAILED(cmd1, ChannelUnavailable(ByteVector32.Zeroes), None))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg).copy(ignore = Ignore(Set.empty, Set(ChannelDesc(channelId_ab, a, b)))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending)) // payment is still pending because the error is recoverable
  }

  test("payment failed (register error)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))
    routerForwarder.expectMsgType[RouteRequest]
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)

    val fwd = register.expectMsgType[ForwardShortId[CMD_ADD_HTLC]]
    register.send(paymentFSM, ForwardShortIdFailure(fwd))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg).copy(ignore = Ignore(Set.empty, Set(ChannelDesc(channelId_ab, a, b)))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending)) // payment is still pending because the error is recoverable
  }

  test("payment failed (first hop returns an UpdateFailMalformedHtlc)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, _, _, _) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd1))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFailMalformed(UpdateFailMalformedHtlc(ByteVector32.Zeroes, 0, randomBytes32(), FailureMessageCodecs.BADONION))))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(defaultRouteRequest(a, d, cfg).copy(ignore = Ignore(Set.empty, Set(ChannelDesc(channelId_ab, a, b)))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))
  }

  test("payment failed (first htlc failed on-chain)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, _, _, _) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd1))
    sender.send(paymentFSM, addCompleted(HtlcResult.OnChainFail(HtlcsTimedoutDownstream(randomBytes32(), Set.empty))))

    // this error is fatal
    routerForwarder.expectNoMsg(100 millis)
    sender.expectMsgType[PaymentFailed]
  }

  test("payment failed (disconnected before signing the first htlc)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, _, _, _) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd1))
    val update_bc_disabled = update_bc.copy(channelFlags = Announcements.makeChannelFlags(isNode1 = true, enable = false))
    sender.send(paymentFSM, addCompleted(HtlcResult.DisconnectedBeforeSigned(update_bc_disabled)))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(defaultRouteRequest(a, d, cfg).copy(ignore = Ignore(Set.empty, Set(ChannelDesc(channelId_ab, a, b)))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))
  }

  test("payment failed (TemporaryChannelFailure)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets1, _, route) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd1))
    val failure = TemporaryChannelFailure(update_bc)
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))))

    // payment lifecycle will ask the router to temporarily exclude this channel from its route calculations
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(update_bc.shortChannelId, b, c)))
    routerForwarder.forward(routerFixture.router)
    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(update_bc)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(defaultRouteRequest(a, d, cfg).copy(ignore = Ignore(Set.empty, Set(ChannelDesc(update_bc.shortChannelId, b, c)))))
    routerForwarder.forward(routerFixture.router)
    // we allow 2 tries, so we send a 2nd request to the router
    assert(sender.expectMsgType[PaymentFailed].failures === RemoteFailure(route.hops, Sphinx.DecryptedFailurePacket(b, failure)) :: LocalFailure(Nil, RouteNotFound) :: Nil)
  }

  test("payment failed (Update)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 5)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets1, _, route1) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd1))

    // we change the cltv expiry
    val channelUpdate_bc_modified = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, CltvExpiryDelta(42), htlcMinimumMsat = update_bc.htlcMinimumMsat, feeBaseMsat = update_bc.feeBaseMsat, feeProportionalMillionths = update_bc.feeProportionalMillionths, htlcMaximumMsat = update_bc.htlcMaximumMsat.get)
    val failure = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))))

    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending)) // 1 failure but not final, the payment is still PENDING
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg))
    routerForwarder.forward(routerFixture.router)

    // router answers with a new route, taking into account the new update
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd2, _, sharedSecrets2, _, route2) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd2))

    // we change the cltv expiry one more time
    val channelUpdate_bc_modified_2 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, CltvExpiryDelta(43), htlcMinimumMsat = update_bc.htlcMinimumMsat, feeBaseMsat = update_bc.feeBaseMsat, feeProportionalMillionths = update_bc.feeProportionalMillionths, htlcMaximumMsat = update_bc.htlcMaximumMsat.get)
    val failure2 = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified_2)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets2.head._1, failure2)))))

    // this time the payment lifecycle will ask the router to temporarily exclude this channel from its route calculations
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(update_bc.shortChannelId, b, c)))
    routerForwarder.forward(routerFixture.router)
    // but it will still forward the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified_2)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg))
    routerForwarder.forward(routerFixture.router)

    // this time the router can't find a route: game over
    assert(sender.expectMsgType[PaymentFailed].failures === RemoteFailure(route1.hops, Sphinx.DecryptedFailurePacket(b, failure)) :: RemoteFailure(route2.hops, Sphinx.DecryptedFailurePacket(b, failure2)) :: LocalFailure(Nil, RouteNotFound) :: Nil)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment failed (Update in last attempt)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 1)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets1, _, _) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd1))

    // the node replies with a temporary failure containing the same update as the one we already have (likely a balance issue)
    val failure = TemporaryChannelFailure(update_bc)
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))))
    // we should temporarily exclude that channel
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(update_bc.shortChannelId, b, c)))
    routerForwarder.expectMsg(update_bc)

    // this was a single attempt payment
    sender.expectMsgType[PaymentFailed]
  }

  test("payment failed (Update in assisted route)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    // we build an assisted route for channel bc and cd
    val assistedRoutes = Seq(Seq(
      ExtraHop(b, channelId_bc, update_bc.feeBaseMsat, update_bc.feeProportionalMillionths, update_bc.cltvExpiryDelta),
      ExtraHop(c, channelId_cd, update_cd.feeBaseMsat, update_cd.feeProportionalMillionths, update_cd.cltvExpiryDelta)
    ))

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 5, assistedRoutes = assistedRoutes)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg).copy(assistedRoutes = assistedRoutes))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets1, _, _) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd1))

    // we change the cltv expiry
    val channelUpdate_bc_modified = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, channelId_bc, CltvExpiryDelta(42), htlcMinimumMsat = update_bc.htlcMinimumMsat, feeBaseMsat = update_bc.feeBaseMsat, feeProportionalMillionths = update_bc.feeProportionalMillionths, htlcMaximumMsat = update_bc.htlcMaximumMsat.get)
    val failure = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))))

    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending)) // 1 failure but not final, the payment is still PENDING
    val assistedRoutes1 = Seq(Seq(
      ExtraHop(b, channelId_bc, update_bc.feeBaseMsat, update_bc.feeProportionalMillionths, channelUpdate_bc_modified.cltvExpiryDelta),
      ExtraHop(c, channelId_cd, update_cd.feeBaseMsat, update_cd.feeProportionalMillionths, update_cd.cltvExpiryDelta)
    ))
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg).copy(assistedRoutes = assistedRoutes1))
    routerForwarder.forward(routerFixture.router)

    // router answers with a new route, taking into account the new update
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd2, _, _, _, _) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd2))
    assert(cmd2.cltvExpiry > cmd1.cltvExpiry)
  }

  test("payment failed (Update disabled in assisted route)") { routerFixture =>
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    // we build an assisted route for channel cd
    val assistedRoutes = Seq(Seq(ExtraHop(c, channelId_cd, update_cd.feeBaseMsat, update_cd.feeProportionalMillionths, update_cd.cltvExpiryDelta)))
    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 1, assistedRoutes = assistedRoutes)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg).copy(assistedRoutes = assistedRoutes))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets1, _, _) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd1))

    // we disable the channel
    val channelUpdate_cd_disabled = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, d, channelId_cd, CltvExpiryDelta(42), update_cd.htlcMinimumMsat, update_cd.feeBaseMsat, update_cd.feeProportionalMillionths, update_cd.htlcMaximumMsat.get, enable = false)
    val failure = ChannelDisabled(channelUpdate_cd_disabled.messageFlags, channelUpdate_cd_disabled.channelFlags, channelUpdate_cd_disabled)
    val failureOnion = Sphinx.FailurePacket.wrap(Sphinx.FailurePacket.create(sharedSecrets1(1)._1, failure), sharedSecrets1.head._1)
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, failureOnion))))

    routerForwarder.expectMsg(channelUpdate_cd_disabled)
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(update_cd.shortChannelId, c, d)))
  }

  def testPermanentFailure(router: ActorRef, failure: FailureMessage): Unit = {
    val payFixture = createPaymentLifecycle()
    import payFixture._
    import cfg._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 2)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets1, _, route1) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(paymentFSM, channelId_ab, cmd1))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))))

    // payment lifecycle forwards the embedded channelUpdate to the router
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(defaultRouteRequest(nodeParams.nodeId, d, cfg).copy(ignore = Ignore(Set.empty, Set(ChannelDesc(channelId_bc, b, c)))))
    routerForwarder.forward(router)
    // we allow 2 tries, so we send a 2nd request to the router, which won't find another route

    assert(sender.expectMsgType[PaymentFailed].failures === RemoteFailure(route1.hops, Sphinx.DecryptedFailurePacket(b, failure)) :: LocalFailure(Nil, RouteNotFound) :: Nil)
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
    import cfg._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 5)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsgType[RouteRequest]
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    routerForwarder.forward(routerFixture.router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status === OutgoingPaymentStatus.Pending))
    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(id)
    assert(outgoing.copy(createdAt = 0) === OutgoingPayment(id, parentId, Some(defaultExternalId), defaultPaymentHash, PaymentType.Standard, defaultAmountMsat, defaultAmountMsat, d, 0, Some(defaultInvoice), OutgoingPaymentStatus.Pending))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFulfill(UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentPreimage))))

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
    import routerFixture._

    // the network will be a --(1)--> b ---(2)--> c --(3)--> d
    //                     |          |
    //                     |          +---(100)---+
    //                     |                      | and b -> h has fees = 0
    //                     +---(5)--> g ---(6)--> h
    // and e --(4)--> f (we are a)
    val channelId_bh = ShortChannelId(420000, 100, 0)
    val chan_bh = channelAnnouncement(channelId_bh, priv_b, priv_h, priv_funding_b, priv_funding_h)
    val channelUpdate_bh = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, h, channelId_bh, CltvExpiryDelta(9), htlcMinimumMsat = 0 msat, feeBaseMsat = 0 msat, feeProportionalMillionths = 0, htlcMaximumMsat = 500000000 msat)
    val channelUpdate_hb = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_h, b, channelId_bh, CltvExpiryDelta(9), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 8, htlcMaximumMsat = 500000000 msat)
    assert(Router.getDesc(channelUpdate_bh, chan_bh) === ChannelDesc(channelId_bh, priv_b.publicKey, priv_h.publicKey))
    val peerConnection = TestProbe()
    router ! PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_bh)
    router ! PeerRoutingMessage(peerConnection.ref, remoteNodeId, channelUpdate_bh)
    router ! PeerRoutingMessage(peerConnection.ref, remoteNodeId, channelUpdate_hb)
    assert(watcher.expectMsgType[ValidateRequest].ann === chan_bh)
    watcher.send(router, ValidateResult(chan_bh, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_b, funding_h)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
    watcher.expectMsgType[WatchExternalChannelSpent]

    val payFixture = createPaymentLifecycle()
    import payFixture._

    // we send a payment to H
    val request = SendPayment(sender.ref, h, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 5)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsgType[RouteRequest]

    // the route will be A -> B -> H where B -> H has a channel_update with fees=0
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    routerForwarder.forward(router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.send(paymentFSM, addCompleted(HtlcResult.OnChainFulfill(defaultPaymentPreimage)))
    val paymentOK = sender.expectMsgType[PaymentSent]
    val PaymentSent(_, _, paymentOK.paymentPreimage, finalAmount, _, PartialPayment(_, request.finalPayload.amount, fee, ByteVector32.Zeroes, _, _) :: Nil) = eventListener.expectMsgType[PaymentSent]
    assert(finalAmount === defaultAmountMsat)

    // NB: A -> B doesn't pay fees because it's our direct neighbor
    // NB: B -> H doesn't asks for fees at all
    assert(fee === 0.msat)
    assert(paymentOK.recipientAmount === request.finalPayload.amount)
  }

  test("filter errors properly") { _ =>
    val failures = Seq(
      LocalFailure(Nil, RouteNotFound),
      RemoteFailure(ChannelHop(a, b, update_ab) :: Nil, Sphinx.DecryptedFailurePacket(a, TemporaryNodeFailure)),
      LocalFailure(ChannelHop(a, b, update_ab) :: Nil, ChannelUnavailable(ByteVector32.Zeroes)),
      LocalFailure(Nil, RouteNotFound)
    )
    val filtered = PaymentFailure.transformForUser(failures)
    val expected = Seq(
      LocalFailure(Nil, RouteNotFound),
      RemoteFailure(ChannelHop(a, b, update_ab) :: Nil, Sphinx.DecryptedFailurePacket(a, TemporaryNodeFailure)),
      LocalFailure(ChannelHop(a, b, update_ab) :: Nil, ChannelUnavailable(ByteVector32.Zeroes))
    )
    assert(filtered === expected)
  }

  test("ignore failed nodes/channels") { _ =>
    val route_abcd = ChannelHop(a, b, update_ab) :: ChannelHop(b, c, update_bc) :: ChannelHop(c, d, update_cd) :: Nil
    val testCases = Seq(
      // local failures -> ignore first channel if there is one
      (LocalFailure(Nil, RouteNotFound), Set.empty, Set.empty),
      (LocalFailure(NodeHop(a, b, CltvExpiryDelta(144), 0 msat) :: NodeHop(b, c, CltvExpiryDelta(144), 0 msat) :: Nil, RouteNotFound), Set.empty, Set.empty),
      (LocalFailure(route_abcd, new RuntimeException("fatal")), Set.empty, Set(ChannelDesc(channelId_ab, a, b))),
      // remote failure from final recipient -> all intermediate nodes behaved correctly
      (RemoteFailure(route_abcd, Sphinx.DecryptedFailurePacket(d, IncorrectOrUnknownPaymentDetails(100 msat, 42))), Set.empty, Set.empty),
      // remote failures from intermediate nodes -> depending on the failure, ignore either the failing node or its outgoing channel
      (RemoteFailure(route_abcd, Sphinx.DecryptedFailurePacket(b, PermanentNodeFailure)), Set(b), Set.empty),
      (RemoteFailure(route_abcd, Sphinx.DecryptedFailurePacket(c, TemporaryNodeFailure)), Set(c), Set.empty),
      (RemoteFailure(route_abcd, Sphinx.DecryptedFailurePacket(b, PermanentChannelFailure)), Set.empty, Set(ChannelDesc(channelId_bc, b, c))),
      (RemoteFailure(route_abcd, Sphinx.DecryptedFailurePacket(c, UnknownNextPeer)), Set.empty, Set(ChannelDesc(channelId_cd, c, d))),
      (RemoteFailure(route_abcd, Sphinx.DecryptedFailurePacket(b, FeeInsufficient(100 msat, update_bc))), Set.empty, Set.empty),
      // unreadable remote failures -> blacklist all nodes except our direct peer and the final recipient
      (UnreadableRemoteFailure(ChannelHop(a, b, update_ab) :: Nil), Set.empty, Set.empty),
      (UnreadableRemoteFailure(ChannelHop(a, b, update_ab) :: ChannelHop(b, c, update_bc) :: ChannelHop(c, d, update_cd) :: ChannelHop(d, e, null) :: Nil), Set(c, d), Set.empty)
    )

    for ((failure, expectedNodes, expectedChannels) <- testCases) {
      val ignore = PaymentFailure.updateIgnored(failure, Ignore.empty)
      assert(ignore.nodes === expectedNodes, failure)
      assert(ignore.channels === expectedChannels, failure)
    }

    val failures = Seq(
      RemoteFailure(route_abcd, Sphinx.DecryptedFailurePacket(c, TemporaryNodeFailure)),
      RemoteFailure(route_abcd, Sphinx.DecryptedFailurePacket(b, UnknownNextPeer)),
      LocalFailure(route_abcd, new RuntimeException("fatal"))
    )
    val ignore = PaymentFailure.updateIgnored(failures, Ignore.empty)
    assert(ignore.nodes === Set(c))
    assert(ignore.channels === Set(ChannelDesc(channelId_ab, a, b), ChannelDesc(channelId_bc, b, c)))
  }

  test("disable database and events") { routerFixture =>
    val payFixture = createPaymentLifecycle(storeInDb = false, publishEvent = false)
    import payFixture._
    import cfg._

    val request = SendPayment(sender.ref, d, Onion.createSinglePartPayload(defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret.get), 3)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsgType[RouteRequest]
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    routerForwarder.forward(routerFixture.router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    assert(nodeParams.db.payments.getOutgoingPayment(id) === None)

    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFulfill(UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentPreimage))))
    sender.expectMsgType[PaymentSent]
    assert(nodeParams.db.payments.getOutgoingPayment(id) === None)
    eventListener.expectNoMsg(100 millis)
  }

}
