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
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.Script.{pay2wsh, write}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, SatoshiLong, Transaction, TxOut}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{UtxoStatus, ValidateRequest, ValidateResult, WatchExternalChannelSpent}
import fr.acinq.eclair.channel.Register.{ForwardShortId, ForwardShortIdFailure}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus, PaymentType}
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.payment.Invoice.ExtraEdge
import fr.acinq.eclair.payment.OutgoingPaymentPacket.Upstream
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle._
import fr.acinq.eclair.payment.send.{ClearRecipient, PaymentLifecycle}
import fr.acinq.eclair.router.Announcements.makeChannelUpdate
import fr.acinq.eclair.router.BaseRouterSpec.{blindedRouteFromHops, channelAnnouncement, channelHopFromUpdate}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.router._
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector

import java.util.UUID
import scala.concurrent.duration._

/**
 * Created by PM on 29/08/2016.
 */

class PaymentLifecycleSpec extends BaseRouterSpec {

  val defaultAmountMsat = 142_000_000 msat
  val defaultExpiry = Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(BlockHeight(40_000))
  val defaultRouteExpiry = CltvExpiry(100_000)
  val defaultPaymentPreimage = randomBytes32()
  val defaultPaymentHash = Crypto.sha256(defaultPaymentPreimage)
  val defaultOrigin = Origin.LocalCold(UUID.randomUUID())
  val defaultExternalId = UUID.randomUUID().toString
  val defaultInvoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, None, defaultPaymentHash, priv_d, Left("test"), Channel.MIN_CLTV_EXPIRY_DELTA)
  val defaultRecipient = ClearRecipient(defaultInvoice, defaultAmountMsat, defaultExpiry, Set.empty)
  val defaultRouteParams = TestConstants.Alice.nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams

  def defaultRouteRequest(source: PublicKey, cfg: SendPaymentConfig): RouteRequest = RouteRequest(source, defaultRecipient, defaultRouteParams, paymentContext = Some(cfg.paymentContext))

  case class PaymentFixture(cfg: SendPaymentConfig,
                            nodeParams: NodeParams,
                            paymentFSM: TestFSMRef[PaymentLifecycle.State, PaymentLifecycle.Data, PaymentLifecycle],
                            routerForwarder: TestProbe,
                            register: TestProbe,
                            sender: TestProbe,
                            monitor: TestProbe,
                            eventListener: TestProbe,
                            metricsListener: TestProbe)

  def createPaymentLifecycle(invoice: Invoice, storeInDb: Boolean = true, publishEvent: Boolean = true, recordMetrics: Boolean = true): PaymentFixture = {
    val (id, parentId) = (UUID.randomUUID(), UUID.randomUUID())
    val nodeParams = TestConstants.Alice.nodeParams.copy(nodeKeyManager = testNodeKeyManager, channelKeyManager = testChannelKeyManager)
    val cfg = SendPaymentConfig(id, parentId, Some(defaultExternalId), defaultPaymentHash, invoice.nodeId, Upstream.Local(id), Some(invoice), None, storeInDb, publishEvent, recordMetrics)
    val (routerForwarder, register, sender, monitor, eventListener, metricsListener) = (TestProbe(), TestProbe(), TestProbe(), TestProbe(), TestProbe(), TestProbe())
    val paymentFSM = TestFSMRef(new PaymentLifecycle(nodeParams, cfg, routerForwarder.ref, register.ref))
    paymentFSM ! SubscribeTransitionCallBack(monitor.ref)
    val CurrentState(_, WAITING_FOR_REQUEST) = monitor.expectMsgClass(classOf[CurrentState[_]])
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
    system.eventStream.subscribe(metricsListener.ref, classOf[PathFindingExperimentMetrics])
    PaymentFixture(cfg, nodeParams, paymentFSM, routerForwarder, register, sender, monitor, eventListener, metricsListener)
  }

  def addCompleted(result: HtlcResult) = {
    RES_ADD_SETTLED(
      origin = defaultOrigin,
      htlc = UpdateAddHtlc(ByteVector32.Zeroes, 0, defaultAmountMsat, defaultPaymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None),
      result)
  }

  test("send to route") { () =>
    val payFixture = createPaymentLifecycle(defaultInvoice, recordMetrics = false)
    import payFixture._
    import cfg._

    // pre-computed route going from A to D
    val route = Route(defaultAmountMsat, channelHopFromUpdate(a, b, update_ab) :: channelHopFromUpdate(b, c, update_bc) :: channelHopFromUpdate(c, d, update_cd) :: Nil, None)
    val request = SendPaymentToRoute(sender.ref, Right(route), defaultRecipient)
    sender.send(paymentFSM, request)
    routerForwarder.expectNoMessage(100 millis) // we don't need the router, we have the pre-computed route
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(id)
    assert(outgoing.copy(createdAt = 0 unixms) == OutgoingPayment(id, parentId, Some(defaultExternalId), defaultPaymentHash, PaymentType.Standard, defaultAmountMsat, defaultAmountMsat, d, 0 unixms, Some(defaultInvoice), None, OutgoingPaymentStatus.Pending))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFulfill(UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentPreimage))))

    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == parentId)
    assert(ps.parts.head.route.contains(route.hops))
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))

    metricsListener.expectNoMessage(100 millis)

    assert(routerForwarder.expectMsgType[RouteDidRelay].route === route)
  }

  test("send to route (node_id only)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice, recordMetrics = false)
    import payFixture._
    import cfg._

    // pre-computed route going from A to D
    val route = PredefinedNodeRoute(defaultAmountMsat, Seq(a, b, c, d))
    val request = SendPaymentToRoute(sender.ref, Left(route), defaultRecipient)

    sender.send(paymentFSM, request)
    routerForwarder.expectMsg(FinalizeRoute(route, paymentContext = Some(cfg.paymentContext)))
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    routerForwarder.forward(routerFixture.router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(id)
    assert(outgoing.copy(createdAt = 0 unixms) == OutgoingPayment(id, parentId, Some(defaultExternalId), defaultPaymentHash, PaymentType.Standard, defaultAmountMsat, defaultAmountMsat, d, 0 unixms, Some(defaultInvoice), None, OutgoingPaymentStatus.Pending))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFulfill(UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentPreimage))))

    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == parentId)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))

    metricsListener.expectNoMessage(100 millis)

    assert(routerForwarder.expectMsgType[RouteDidRelay].route.hops.map(_.nodeId) === Seq(a, b, c))
  }

  test("send to route (nodes not found in the graph)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice, recordMetrics = false)
    import payFixture._

    val brokenRoute = SendPaymentToRoute(sender.ref, Left(PredefinedNodeRoute(defaultAmountMsat, Seq(randomKey().publicKey, randomKey().publicKey, randomKey().publicKey))), defaultRecipient)
    sender.send(paymentFSM, brokenRoute)
    routerForwarder.expectMsgType[FinalizeRoute]
    routerForwarder.forward(routerFixture.router)

    val failureMessage = eventListener.expectMsgType[PaymentFailed].failures.head.asInstanceOf[LocalFailure].t.getMessage
    assert(failureMessage == "Not all the nodes in the supplied route are connected with public channels")

    metricsListener.expectNoMessage(100 millis)

    routerForwarder.expectNoMessage(100 millis)
  }

  test("send to route (channels not found in the graph)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice, recordMetrics = false)
    import payFixture._

    val brokenRoute = SendPaymentToRoute(sender.ref, Left(PredefinedChannelRoute(defaultAmountMsat, randomKey().publicKey, Seq(ShortChannelId(1), ShortChannelId(2)))), defaultRecipient)
    sender.send(paymentFSM, brokenRoute)
    routerForwarder.expectMsgType[FinalizeRoute]
    routerForwarder.forward(routerFixture.router)

    val failureMessage = eventListener.expectMsgType[PaymentFailed].failures.head.asInstanceOf[LocalFailure].t.getMessage
    assert(failureMessage == "The sequence of channels provided cannot be used to build a route to the target node")

    metricsListener.expectNoMessage(100 millis)

    routerForwarder.expectNoMessage(100 millis)
  }

  test("send to route (routing hints)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice, recordMetrics = false)
    import payFixture._
    import cfg._

    val recipientNodeId = randomKey().publicKey
    val route = PredefinedNodeRoute(defaultAmountMsat, Seq(a, b, c, recipientNodeId))
    val extraEdges = Seq(ExtraEdge(c, recipientNodeId, ShortChannelId(561), 1 msat, 100, CltvExpiryDelta(144), 1 msat, None))
    val recipient = ClearRecipient(recipientNodeId, Features.empty, defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret, extraEdges)
    val request = SendPaymentToRoute(sender.ref, Left(route), recipient)

    sender.send(paymentFSM, request)
    routerForwarder.expectMsg(FinalizeRoute(route, extraEdges, paymentContext = Some(cfg.paymentContext)))
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    routerForwarder.forward(routerFixture.router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    // Payment accepted by the recipient.
    sender.send(paymentFSM, addCompleted(HtlcResult.OnChainFulfill(defaultPaymentPreimage)))

    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == parentId)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))

    metricsListener.expectNoMessage(100 millis)

    assert(routerForwarder.expectMsgType[RouteDidRelay].route.hops.map(_.nodeId) === Seq(a, b, c))
  }

  test("send to route (blinded route)") { () =>
    val (invoice, blindedHop, recipient) = blindedRouteFromHops(defaultAmountMsat, defaultExpiry, Seq(channelHopFromUpdate(b, c, update_bc)), defaultRouteExpiry, defaultPaymentPreimage)
    val route = Route(defaultAmountMsat, Seq(channelHopFromUpdate(a, b, update_ab)), Some(blindedHop))
    val payFixture = createPaymentLifecycle(invoice)
    import payFixture._

    val request = SendPaymentToRoute(sender.ref, Right(route), recipient)
    sender.send(paymentFSM, request)
    routerForwarder.expectNoMessage(100 millis) // we don't need the router, we have the pre-computed route
    awaitCond(nodeParams.db.payments.getOutgoingPayment(cfg.id).exists(_.status == OutgoingPaymentStatus.Pending))
    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(cfg.id)
    assert(outgoing.amount == defaultAmountMsat)
    assert(outgoing.recipientAmount == defaultAmountMsat)
    assert(outgoing.invoice.contains(invoice))
    assert(outgoing.status == OutgoingPaymentStatus.Pending)

    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFulfill(UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentPreimage))))
    val ps = sender.expectMsgType[PaymentSent]
    assert(ps.id == cfg.parentId)
    assert(ps.parts.head.route.contains(route.hops ++ Seq(blindedHop)))
    awaitCond(nodeParams.db.payments.getOutgoingPayment(cfg.id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))

    assert(routerForwarder.expectMsgType[RouteDidRelay].route === route)
  }

  test("payment failed (route not found)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    val request = SendPaymentToNode(sender.ref, ClearRecipient(f, Features.empty, defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret), 5, defaultRouteParams)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val routeRequest = routerForwarder.expectMsgType[RouteRequest]
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))

    routerForwarder.forward(routerFixture.router, routeRequest)
    assert(sender.expectMsgType[PaymentFailed].failures == LocalFailure(defaultAmountMsat, Nil, RouteNotFound) :: Nil)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "FAILURE")
    assert(metrics.experimentName == "alice-test-experiment")
    assert(metrics.amount == defaultAmountMsat)
    assert(metrics.fees == 4_260_000.msat)
    metricsListener.expectNoMessage(100 millis)

    routerForwarder.expectNoMessage(100 millis)
  }

  test("payment failed (route too expensive)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    val routeParams = PathFindingConf(
      randomize = false,
      boundaries = SearchBoundaries(100 msat, 0.0, 20, CltvExpiryDelta(2016)),
      Left(WeightRatios(1, 0, 0, 0, RelayFees(0 msat, 0))),
      MultiPartParams(10_000 msat, 5),
      "my-test-experiment",
      experimentPercentage = 100
    ).getDefaultRouteParams
    val request = SendPaymentToNode(sender.ref, defaultRecipient, 5, routeParams)
    sender.send(paymentFSM, request)
    val routeRequest = routerForwarder.expectMsgType[RouteRequest]
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    routerForwarder.forward(routerFixture.router, routeRequest)
    val Seq(LocalFailure(_, Nil, RouteNotFound)) = sender.expectMsgType[PaymentFailed].failures
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "FAILURE")
    assert(metrics.experimentName == "my-test-experiment")
    assert(metrics.amount == defaultAmountMsat)
    assert(metrics.fees == 100.msat)
    metricsListener.expectNoMessage(100 millis)

    routerForwarder.expectNoMessage(100 millis)
  }

  test("payment failed (cannot build onion)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    val paymentMetadataTooBig = ByteVector.fromValidHex("01" * 1300)
    val request = SendPaymentToNode(sender.ref, ClearRecipient(d, Features.empty, defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret, paymentMetadata_opt = Some(paymentMetadataTooBig)), 5, defaultRouteParams)
    sender.send(paymentFSM, request)
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    val routeRequest = routerForwarder.expectMsgType[RouteRequest]
    routerForwarder.forward(routerFixture.router, routeRequest)

    val pf = sender.expectMsgType[PaymentFailed]
    assert(pf.failures.length == 1)
    assert(pf.failures.head.isInstanceOf[LocalFailure])
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))

    routerForwarder.expectNoMessage(100 millis)
  }

  test("payment failed (unparsable failure)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    val request = SendPaymentToNode(sender.ref, defaultRecipient, 2, defaultRouteParams)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, _, ignore1, route) = paymentFSM.stateData
    assert(ignore1.nodes.isEmpty)

    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd1))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, randomBytes32())))) // unparsable message

    // then the payment lifecycle will ask for a new route excluding all intermediate nodes
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg).copy(ignore = Ignore(Set(c), Set.empty)))

    // let's simulate a response by the router with another route
    sender.send(paymentFSM, RouteResponse(route :: Nil))
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd2, _, _, ignore2, _) = paymentFSM.stateData
    assert(ignore2.nodes == Set(c))
    // and reply a 2nd time with an unparsable failure
    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd2))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, defaultPaymentHash)))) // unparsable message

    // we allow 2 tries, so we send a 2nd request to the router
    assert(sender.expectMsgType[PaymentFailed].failures == UnreadableRemoteFailure(route.amount, route.hops) :: UnreadableRemoteFailure(route.amount, route.hops) :: Nil)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed])) // after last attempt the payment is failed

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "FAILURE")
    assert(metrics.experimentName == "alice-test-experiment")
    assert(metrics.amount == defaultAmountMsat)
    assert(metrics.fees == 4_260_000.msat)
    metricsListener.expectNoMessage(100 millis)

    routerForwarder.expectNoMessage(100 millis)
  }

  test("payment failed (local error)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    val request = SendPaymentToNode(sender.ref, defaultRecipient, 2, defaultRouteParams)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
    routerForwarder.expectMsgType[RouteRequest]
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)

    val WaitingForComplete(_, cmd1, Nil, _, _, _) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd1))
    sender.send(paymentFSM, RES_ADD_FAILED(cmd1, ChannelUnavailable(ByteVector32.Zeroes), None))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg).copy(ignore = Ignore(Set.empty, Set(ChannelDesc(scid_ab, a, b)))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending)) // payment is still pending because the error is recoverable
  }

  test("payment failed (register error)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    val request = SendPaymentToNode(sender.ref, defaultRecipient, 2, defaultRouteParams)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
    routerForwarder.expectMsgType[RouteRequest]
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)

    val fwd = register.expectMsgType[ForwardShortId[CMD_ADD_HTLC]]
    register.send(paymentFSM, ForwardShortIdFailure(fwd))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg).copy(ignore = Ignore(Set.empty, Set(ChannelDesc(scid_ab, a, b)))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending)) // payment is still pending because the error is recoverable
  }

  test("payment failed (first hop returns an UpdateFailMalformedHtlc)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    val request = SendPaymentToNode(sender.ref, defaultRecipient, 2, defaultRouteParams)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, _, _, _) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd1))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFailMalformed(UpdateFailMalformedHtlc(ByteVector32.Zeroes, 0, randomBytes32(), FailureMessageCodecs.BADONION))))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg).copy(ignore = Ignore(Set.empty, Set(ChannelDesc(scid_ab, a, b)))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
  }

  test("payment failed (first htlc failed on-chain)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    val request = SendPaymentToNode(sender.ref, defaultRecipient, 2, defaultRouteParams)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, _, _, _) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd1))
    sender.send(paymentFSM, addCompleted(HtlcResult.OnChainFail(HtlcsTimedoutDownstream(randomBytes32(), Set.empty))))

    // this error is fatal
    routerForwarder.expectNoMessage(100 millis)
    sender.expectMsgType[PaymentFailed]
  }

  test("payment failed (disconnected before signing the first htlc)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    val request = SendPaymentToNode(sender.ref, defaultRecipient, 2, defaultRouteParams)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, _, _, _) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd1))
    val update_bc_disabled = update_bc.copy(channelFlags = ChannelUpdate.ChannelFlags(isNode1 = true, isEnabled = false))
    sender.send(paymentFSM, addCompleted(HtlcResult.DisconnectedBeforeSigned(update_bc_disabled)))

    // then the payment lifecycle will ask for a new route excluding the channel
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg).copy(ignore = Ignore(Set.empty, Set(ChannelDesc(scid_ab, a, b)))))
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
  }

  test("payment failed (TemporaryChannelFailure)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._

    val request = SendPaymentToNode(sender.ref, defaultRecipient, 2, defaultRouteParams)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets1, _, route) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd1))
    val failure = TemporaryChannelFailure(update_bc)
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))))

    // payment lifecycle will ask the router to temporarily exclude this channel from its route calculations
    assert(routerForwarder.expectMsgType[ChannelCouldNotRelay].hop.shortChannelId == update_bc.shortChannelId)
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(update_bc.shortChannelId, b, c), Some(nodeParams.routerConf.channelExcludeDuration)))
    routerForwarder.forward(routerFixture.router)
    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(update_bc)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg).copy(ignore = Ignore(Set.empty, Set(ChannelDesc(update_bc.shortChannelId, b, c)))))
    routerForwarder.forward(routerFixture.router)
    // we allow 2 tries, so we send a 2nd request to the router
    assert(sender.expectMsgType[PaymentFailed].failures == RemoteFailure(route.amount, route.hops, Sphinx.DecryptedFailurePacket(b, failure)) :: LocalFailure(defaultAmountMsat, Nil, RouteNotFound) :: Nil)
  }

  test("payment failed (Update)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    val request = SendPaymentToNode(sender.ref, defaultRecipient, 5, defaultRouteParams)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets1, _, route1) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd1))

    // we change the cltv expiry
    val channelUpdate_bc_modified = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, scid_bc, CltvExpiryDelta(42), htlcMinimumMsat = update_bc.htlcMinimumMsat, feeBaseMsat = update_bc.feeBaseMsat, feeProportionalMillionths = update_bc.feeProportionalMillionths, htlcMaximumMsat = update_bc.htlcMaximumMsat)
    val failure = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))))

    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending)) // 1 failure but not final, the payment is still PENDING
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg))
    routerForwarder.forward(routerFixture.router)

    // router answers with a new route, taking into account the new update
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd2, _, sharedSecrets2, _, route2) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd2))

    // we change the cltv expiry one more time
    val channelUpdate_bc_modified_2 = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, scid_bc, CltvExpiryDelta(43), htlcMinimumMsat = update_bc.htlcMinimumMsat, feeBaseMsat = update_bc.feeBaseMsat, feeProportionalMillionths = update_bc.feeProportionalMillionths, htlcMaximumMsat = update_bc.htlcMaximumMsat)
    val failure2 = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified_2)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets2.head._1, failure2)))))

    // this time the payment lifecycle will ask the router to temporarily exclude this channel from its route calculations
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(update_bc.shortChannelId, b, c), Some(nodeParams.routerConf.channelExcludeDuration)))
    routerForwarder.forward(routerFixture.router)
    // but it will still forward the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified_2)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg))
    routerForwarder.forward(routerFixture.router)

    // this time the router can't find a route: game over
    assert(sender.expectMsgType[PaymentFailed].failures == RemoteFailure(route1.amount, route1.hops, Sphinx.DecryptedFailurePacket(b, failure)) :: RemoteFailure(route2.amount, route2.hops, Sphinx.DecryptedFailurePacket(b, failure2)) :: LocalFailure(defaultAmountMsat, Nil, RouteNotFound) :: Nil)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))

    routerForwarder.expectNoMessage(100 millis)
  }

  test("payment failed (Update in last attempt)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._

    val request = SendPaymentToNode(sender.ref, defaultRecipient, 1, defaultRouteParams)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets1, _, _) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd1))

    // the node replies with a temporary failure containing the same update as the one we already have (likely a balance issue)
    val failure = TemporaryChannelFailure(update_bc)
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))))
    // we should temporarily exclude that channel
    assert(routerForwarder.expectMsgType[ChannelCouldNotRelay].hop.shortChannelId == update_bc.shortChannelId)
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(update_bc.shortChannelId, b, c), Some(nodeParams.routerConf.channelExcludeDuration)))
    routerForwarder.expectMsg(update_bc)

    // this was a single attempt payment
    sender.expectMsgType[PaymentFailed]
  }

  test("payment failed (Update in assisted route)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    // we build an assisted route for channel bc and cd
    val recipient = ClearRecipient(d, Features.empty, defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret, Seq(
      ExtraEdge(b, c, scid_bc, update_bc.feeBaseMsat, update_bc.feeProportionalMillionths, update_bc.cltvExpiryDelta, 1 msat, None),
      ExtraEdge(c, d, scid_cd, update_cd.feeBaseMsat, update_cd.feeProportionalMillionths, update_cd.cltvExpiryDelta, 1 msat, None)
    ))
    val request = SendPaymentToNode(sender.ref, recipient, 5, defaultRouteParams)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg).copy(target = recipient))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets1, _, _) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd1))

    // we change the cltv expiry
    val channelUpdate_bc_modified = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, scid_bc, CltvExpiryDelta(42), htlcMinimumMsat = update_bc.htlcMinimumMsat, feeBaseMsat = update_bc.feeBaseMsat, feeProportionalMillionths = update_bc.feeProportionalMillionths, htlcMaximumMsat = update_bc.htlcMaximumMsat)
    val failure = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))))

    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending)) // 1 failure but not final, the payment is still PENDING
    val extraEdges1 = Seq(
      recipient.extraEdges(0).update(channelUpdate_bc_modified),
      recipient.extraEdges(1)
    )
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg).copy(target = recipient.copy(extraEdges = extraEdges1)))
    routerForwarder.forward(routerFixture.router)

    // router answers with a new route, taking into account the new update
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd2, _, _, _, _) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd2))
    assert(cmd2.cltvExpiry > cmd1.cltvExpiry)
  }

  test("payment failed (Update disabled in assisted route)") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    // we build an assisted route for channel cd
    val recipient = ClearRecipient(d, Features.empty, defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret, Seq(
      ExtraEdge(c, d, scid_cd, update_cd.feeBaseMsat, update_cd.feeProportionalMillionths, update_cd.cltvExpiryDelta, 1 msat, None)
    ))
    val request = SendPaymentToNode(sender.ref, recipient, 1, defaultRouteParams)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))

    routerForwarder.expectMsg(defaultRouteRequest(a, cfg).copy(target = recipient))
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets1, _, _) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd1))

    // we disable the channel
    val channelUpdate_cd_disabled = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_c, d, scid_cd, CltvExpiryDelta(42), update_cd.htlcMinimumMsat, update_cd.feeBaseMsat, update_cd.feeProportionalMillionths, update_cd.htlcMaximumMsat, enable = false)
    val failure = ChannelDisabled(channelUpdate_cd_disabled.messageFlags, channelUpdate_cd_disabled.channelFlags, channelUpdate_cd_disabled)
    val failureOnion = Sphinx.FailurePacket.wrap(Sphinx.FailurePacket.create(sharedSecrets1(1)._1, failure), sharedSecrets1.head._1)
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, failureOnion))))

    assert(routerForwarder.expectMsgType[RouteCouldRelay].route.hops.map(_.shortChannelId) == Seq(update_ab, update_bc).map(_.shortChannelId))
    routerForwarder.expectMsg(ExcludeChannel(ChannelDesc(update_cd.shortChannelId, c, d), Some(nodeParams.routerConf.channelExcludeDuration)))
    routerForwarder.expectMsg(channelUpdate_cd_disabled)
  }

  def testPermanentFailure(router: ActorRef, failure: FailureMessage): Unit = {
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    val request = SendPaymentToNode(sender.ref, defaultRecipient, 2, defaultRouteParams)
    sender.send(paymentFSM, request)
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE && nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))

    val WaitingForRoute(_, Nil, _) = paymentFSM.stateData
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg))
    routerForwarder.forward(router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets1, _, route1) = paymentFSM.stateData

    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd1))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))))

    // payment lifecycle forwards the embedded channelUpdate to the router
    awaitCond(paymentFSM.stateName == WAITING_FOR_ROUTE)
    routerForwarder.expectMsg(defaultRouteRequest(a, cfg).copy(ignore = Ignore(Set.empty, Set(ChannelDesc(scid_bc, b, c)))))
    routerForwarder.forward(router)
    // we allow 2 tries, so we send a 2nd request to the router, which won't find another route

    assert(sender.expectMsgType[PaymentFailed].failures == RemoteFailure(route1.amount, route1.hops, Sphinx.DecryptedFailurePacket(b, failure)) :: LocalFailure(defaultAmountMsat, Nil, RouteNotFound) :: Nil)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment failed (PermanentChannelFailure)") { routerFixture =>
    testPermanentFailure(routerFixture.router, PermanentChannelFailure())
  }

  test("payment failed (deprecated permanent failure)") { routerFixture =>
    import scodec.bits.HexStringSyntax
    // PERM | 17 (final_expiry_too_soon) has been deprecated but older nodes might still use it.
    testPermanentFailure(routerFixture.router, FailureMessageCodecs.failureMessageCodec.decode(hex"4011".bits).require.value)
  }

  test("payment failed (blinded route)") { routerFixture =>
    val (invoice, blindedHop, recipient) = blindedRouteFromHops(defaultAmountMsat, defaultExpiry, Seq(channelHopFromUpdate(b, c, update_bc)), defaultRouteExpiry, defaultPaymentPreimage)
    assert(recipient.extraEdges.length == 1)
    val payFixture = createPaymentLifecycle(invoice)
    import payFixture._

    val request = SendPaymentToNode(sender.ref, recipient, 2, defaultRouteParams)
    sender.send(paymentFSM, request)

    routerForwarder.expectMsgType[RouteRequest]
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    val WaitingForComplete(_, cmd1, Nil, sharedSecrets, _, route) = paymentFSM.stateData
    register.expectMsg(ForwardShortId(paymentFSM.toTyped, scid_ab, cmd1))

    // The payment fails inside the blinded route: the introduction node sends back an error.
    val failure = InvalidOnionBlinding(randomBytes32())
    val failureOnion = Sphinx.FailurePacket.create(sharedSecrets.head._1, failure)
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, failureOnion))))

    // We retry but we exclude the failed blinded route.
    val routeRequest = routerForwarder.expectMsgType[RouteRequest]
    assert(routeRequest.target == recipient)
    assert(routeRequest.ignore.channels.map(_.shortChannelId) == Set(blindedHop.dummyId))
    routerForwarder.forward(routerFixture.router)

    // Without the blinded route, the router cannot find a route to the recipient.
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.failures == Seq(RemoteFailure(defaultAmountMsat, route.hops ++ Seq(blindedHop), Sphinx.DecryptedFailurePacket(b, failure)), LocalFailure(defaultAmountMsat, Nil, RouteNotFound)))
    awaitCond(nodeParams.db.payments.getOutgoingPayment(cfg.id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Failed]))
  }

  test("payment succeeded") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    val request = SendPaymentToNode(sender.ref, defaultRecipient, 5, defaultRouteParams)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsgType[RouteRequest]
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    routerForwarder.forward(routerFixture.router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(id)
    assert(outgoing.copy(createdAt = 0 unixms) == OutgoingPayment(id, parentId, Some(defaultExternalId), defaultPaymentHash, PaymentType.Standard, defaultAmountMsat, defaultAmountMsat, d, 0 unixms, Some(defaultInvoice), None, OutgoingPaymentStatus.Pending))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFulfill(UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentPreimage))))

    val ps = eventListener.expectMsgType[PaymentSent]
    assert(ps.id == parentId)
    assert(ps.feesPaid > 0.msat)
    assert(ps.recipientAmount == defaultAmountMsat)
    assert(ps.paymentHash == defaultPaymentHash)
    assert(ps.paymentPreimage == defaultPaymentPreimage)
    assert(ps.parts.head.id == id)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "SUCCESS")
    assert(metrics.experimentName == "alice-test-experiment")
    assert(metrics.amount == defaultAmountMsat)
    assert(metrics.fees == 730.msat)
    metricsListener.expectNoMessage(100 millis)

    assert(routerForwarder.expectMsgType[RouteDidRelay].route.hops.map(_.shortChannelId) == Seq(update_ab, update_bc, update_cd).map(_.shortChannelId))
  }

  test("payment succeeded to a channel with fees=0") { routerFixture =>
    import routerFixture._

    // the network will be a --(1)--> b ---(2)--> c --(3)--> d
    //                     |          |
    //                     |          +---(100)---+
    //                     |                      | and b -> h has fees = 0
    //                     +---(5)--> g ---(6)--> h
    // and e --(4)--> f (we are a)
    val channelId_bh = RealShortChannelId(BlockHeight(420000), 100, 0)
    val chan_bh = channelAnnouncement(channelId_bh, priv_b, priv_h, priv_funding_b, priv_funding_h)
    val channelUpdate_bh = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, h, channelId_bh, CltvExpiryDelta(9), htlcMinimumMsat = 0 msat, feeBaseMsat = 0 msat, feeProportionalMillionths = 0, htlcMaximumMsat = 500000000 msat)
    val channelUpdate_hb = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_h, b, channelId_bh, CltvExpiryDelta(9), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 8, htlcMaximumMsat = 500000000 msat)
    assert(ChannelDesc(channelUpdate_bh, chan_bh) == ChannelDesc(channelId_bh, priv_b.publicKey, priv_h.publicKey))
    val peerConnection = TestProbe()
    router ! PeerRoutingMessage(peerConnection.ref, remoteNodeId, chan_bh)
    router ! PeerRoutingMessage(peerConnection.ref, remoteNodeId, channelUpdate_bh)
    router ! PeerRoutingMessage(peerConnection.ref, remoteNodeId, channelUpdate_hb)
    assert(watcher.expectMsgType[ValidateRequest].ann == chan_bh)
    watcher.send(router, ValidateResult(chan_bh, Right((Transaction(version = 0, txIn = Nil, txOut = TxOut(1000000 sat, write(pay2wsh(Scripts.multiSig2of2(funding_b, funding_h)))) :: Nil, lockTime = 0), UtxoStatus.Unspent))))
    watcher.expectMsgType[WatchExternalChannelSpent]

    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._

    // we send a payment to H
    val request = SendPaymentToNode(sender.ref, ClearRecipient(h, Features.empty, defaultAmountMsat, defaultExpiry, defaultInvoice.paymentSecret), 5, defaultRouteParams)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsgType[RouteRequest]

    // the route will be A -> B -> H where B -> H has a channel_update with fees=0
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    routerForwarder.forward(router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])

    sender.send(paymentFSM, addCompleted(HtlcResult.OnChainFulfill(defaultPaymentPreimage)))
    val paymentOK = sender.expectMsgType[PaymentSent]
    val PaymentSent(_, _, paymentOK.paymentPreimage, finalAmount, _, PartialPayment(_, partAmount, fee, ByteVector32.Zeroes, _, _) :: Nil) = eventListener.expectMsgType[PaymentSent]
    assert(partAmount == request.amount)
    assert(finalAmount == defaultAmountMsat)

    // NB: A -> B doesn't pay fees because it's our direct neighbor
    // NB: B -> H doesn't asks for fees at all
    assert(fee == 0.msat)
    assert(paymentOK.recipientAmount == request.amount)

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "SUCCESS")
    assert(metrics.experimentName == "alice-test-experiment")
    assert(metrics.amount == defaultAmountMsat)
    assert(metrics.fees == 0.msat)
    metricsListener.expectNoMessage(100 millis)

    assert(routerForwarder.expectMsgType[RouteDidRelay].route.hops.map(_.shortChannelId) == Seq(update_ab, channelUpdate_bh).map(_.shortChannelId))
  }

  test("payment success (blinded route)") { routerFixture =>
    val (invoice, blindedHop, recipient) = blindedRouteFromHops(defaultAmountMsat, defaultExpiry, Seq(channelHopFromUpdate(b, c, update_bc)), defaultRouteExpiry, defaultPaymentPreimage)
    assert(recipient.extraEdges.length == 1)
    val payFixture = createPaymentLifecycle(invoice)
    import payFixture._

    val request = SendPaymentToNode(sender.ref, recipient, 2, defaultRouteParams)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsgType[RouteRequest]
    routerForwarder.forward(routerFixture.router)
    awaitCond(paymentFSM.stateName == WAITING_FOR_PAYMENT_COMPLETE)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(cfg.id).exists(_.status == OutgoingPaymentStatus.Pending))
    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(cfg.id)
    assert(outgoing.copy(createdAt = 0 unixms) == OutgoingPayment(cfg.id, cfg.parentId, Some(defaultExternalId), defaultPaymentHash, PaymentType.Blinded, defaultAmountMsat, defaultAmountMsat, recipient.nodeId, 0 unixms, Some(invoice), None, OutgoingPaymentStatus.Pending))
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFulfill(UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentPreimage))))

    val ps = eventListener.expectMsgType[PaymentSent]
    assert(ps.id == cfg.parentId)
    assert(ps.feesPaid == blindedHop.fee(defaultAmountMsat))
    assert(ps.recipientAmount == defaultAmountMsat)
    assert(ps.paymentPreimage == defaultPaymentPreimage)
    awaitCond(nodeParams.db.payments.getOutgoingPayment(cfg.id).exists(_.status.isInstanceOf[OutgoingPaymentStatus.Succeeded]))

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "SUCCESS")
    assert(metrics.amount == defaultAmountMsat)
    assert(metrics.fees == blindedHop.fee(defaultAmountMsat))
    metricsListener.expectNoMessage(100 millis)

    assert(routerForwarder.expectMsgType[RouteDidRelay].route.hops.map(_.shortChannelId) == Seq(update_ab.shortChannelId))
  }

  test("filter errors properly") { () =>
    val failures = Seq(
      LocalFailure(defaultAmountMsat, Nil, RouteNotFound),
      RemoteFailure(defaultAmountMsat, channelHopFromUpdate(a, b, update_ab) :: Nil, Sphinx.DecryptedFailurePacket(a, TemporaryNodeFailure())),
      LocalFailure(defaultAmountMsat, channelHopFromUpdate(a, b, update_ab) :: Nil, ChannelUnavailable(ByteVector32.Zeroes)),
      LocalFailure(defaultAmountMsat, Nil, RouteNotFound)
    )
    val filtered = PaymentFailure.transformForUser(failures)
    val expected = Seq(
      LocalFailure(defaultAmountMsat, Nil, RouteNotFound),
      RemoteFailure(defaultAmountMsat, channelHopFromUpdate(a, b, update_ab) :: Nil, Sphinx.DecryptedFailurePacket(a, TemporaryNodeFailure())),
      LocalFailure(defaultAmountMsat, channelHopFromUpdate(a, b, update_ab) :: Nil, ChannelUnavailable(ByteVector32.Zeroes))
    )
    assert(filtered == expected)
  }

  test("ignore failed nodes/channels") { () =>
    val route_abcd = channelHopFromUpdate(a, b, update_ab) :: channelHopFromUpdate(b, c, update_bc) :: channelHopFromUpdate(c, d, update_cd) :: Nil
    val update_de = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_d, e, ShortChannelId(1729), CltvExpiryDelta(3), htlcMinimumMsat = 0 msat, feeBaseMsat = 10 msat, feeProportionalMillionths = 4, htlcMaximumMsat = htlcMaximum)
    val (_, blindedHop_bc, _) = blindedRouteFromHops(defaultAmountMsat, defaultExpiry, Seq(channelHopFromUpdate(b, c, update_bc), channelHopFromUpdate(c, d, update_cd)), defaultRouteExpiry, defaultPaymentPreimage)
    val blindedRoute_abc = channelHopFromUpdate(a, b, update_ab) :: blindedHop_bc :: Nil
    val (_, blindedHop_de, _) = blindedRouteFromHops(defaultAmountMsat, defaultExpiry, Seq(channelHopFromUpdate(d, e, update_de)), defaultRouteExpiry, defaultPaymentPreimage)
    val testCases = Seq(
      // local failures -> ignore first channel if there is one
      (LocalFailure(defaultAmountMsat, Nil, RouteNotFound), Set.empty, Set.empty),
      (LocalFailure(defaultAmountMsat, NodeHop(a, b, CltvExpiryDelta(144), 0 msat) :: NodeHop(b, c, CltvExpiryDelta(144), 0 msat) :: Nil, RouteNotFound), Set.empty, Set.empty),
      (LocalFailure(defaultAmountMsat, route_abcd, new RuntimeException("fatal")), Set.empty, Set(ChannelDesc(scid_ab, a, b))),
      // remote failure from final recipient -> all intermediate nodes behaved correctly
      (RemoteFailure(defaultAmountMsat, route_abcd, Sphinx.DecryptedFailurePacket(d, IncorrectOrUnknownPaymentDetails(100 msat, BlockHeight(42)))), Set.empty, Set.empty),
      // remote failures from intermediate nodes -> depending on the failure, ignore either the failing node or its outgoing channel
      (RemoteFailure(defaultAmountMsat, route_abcd, Sphinx.DecryptedFailurePacket(b, PermanentNodeFailure())), Set(b), Set.empty),
      (RemoteFailure(defaultAmountMsat, route_abcd, Sphinx.DecryptedFailurePacket(c, TemporaryNodeFailure())), Set(c), Set.empty),
      (RemoteFailure(defaultAmountMsat, route_abcd, Sphinx.DecryptedFailurePacket(b, PermanentChannelFailure())), Set.empty, Set(ChannelDesc(scid_bc, b, c))),
      (RemoteFailure(defaultAmountMsat, route_abcd, Sphinx.DecryptedFailurePacket(c, UnknownNextPeer())), Set.empty, Set(ChannelDesc(scid_cd, c, d))),
      (RemoteFailure(defaultAmountMsat, route_abcd, Sphinx.DecryptedFailurePacket(b, FeeInsufficient(100 msat, update_bc))), Set.empty, Set.empty),
      (RemoteFailure(defaultAmountMsat, blindedRoute_abc, Sphinx.DecryptedFailurePacket(b, InvalidOnionBlinding(randomBytes32()))), Set.empty, Set(ChannelDesc(blindedHop_bc.dummyId, blindedHop_bc.nodeId, blindedHop_bc.nextNodeId))),
      (RemoteFailure(defaultAmountMsat, blindedRoute_abc, Sphinx.DecryptedFailurePacket(blindedHop_bc.route.blindedNodeIds(1), InvalidOnionBlinding(randomBytes32()))), Set.empty, Set(ChannelDesc(blindedHop_bc.dummyId, blindedHop_bc.nodeId, blindedHop_bc.nextNodeId))),
      // unreadable remote failures -> blacklist all nodes except our direct peer, the final recipient or the last hop
      (UnreadableRemoteFailure(defaultAmountMsat, channelHopFromUpdate(a, b, update_ab) :: Nil), Set.empty, Set.empty),
      (UnreadableRemoteFailure(defaultAmountMsat, channelHopFromUpdate(a, b, update_ab) :: channelHopFromUpdate(b, c, update_bc) :: channelHopFromUpdate(c, d, update_cd) :: Nil), Set(c), Set.empty),
      (UnreadableRemoteFailure(defaultAmountMsat, channelHopFromUpdate(a, b, update_ab) :: channelHopFromUpdate(b, c, update_bc) :: channelHopFromUpdate(c, d, update_cd) :: channelHopFromUpdate(d, e, update_de) :: Nil), Set(c, d), Set.empty),
      (UnreadableRemoteFailure(defaultAmountMsat, channelHopFromUpdate(a, b, update_ab) :: channelHopFromUpdate(b, c, update_bc) :: channelHopFromUpdate(c, d, update_cd) :: NodeHop(d, e, CltvExpiryDelta(24), 0 msat) :: Nil), Set(c), Set.empty),
      (UnreadableRemoteFailure(defaultAmountMsat, channelHopFromUpdate(a, b, update_ab) :: channelHopFromUpdate(b, c, update_bc) :: channelHopFromUpdate(c, d, update_cd) :: blindedHop_de :: Nil), Set(c), Set.empty),
    )

    for ((failure, expectedNodes, expectedChannels) <- testCases) {
      val ignore = PaymentFailure.updateIgnored(failure, Ignore.empty)
      assert(ignore.nodes == expectedNodes, failure)
      assert(ignore.channels == expectedChannels, failure)
    }

    val failures = Seq(
      RemoteFailure(defaultAmountMsat, route_abcd, Sphinx.DecryptedFailurePacket(c, TemporaryNodeFailure())),
      RemoteFailure(defaultAmountMsat, route_abcd, Sphinx.DecryptedFailurePacket(b, UnknownNextPeer())),
      LocalFailure(defaultAmountMsat, route_abcd, new RuntimeException("fatal"))
    )
    val ignore = PaymentFailure.updateIgnored(failures, Ignore.empty)
    assert(ignore.nodes == Set(c))
    assert(ignore.channels == Set(ChannelDesc(scid_ab, a, b), ChannelDesc(scid_bc, b, c)))
  }

  test("disable database and events") { routerFixture =>
    val payFixture = createPaymentLifecycle(defaultInvoice, storeInDb = false, publishEvent = false, recordMetrics = false)
    import payFixture._
    import cfg._

    val request = SendPaymentToNode(sender.ref, defaultRecipient, 3, defaultRouteParams)
    sender.send(paymentFSM, request)
    routerForwarder.expectMsgType[RouteRequest]
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])
    routerForwarder.forward(routerFixture.router)
    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    assert(nodeParams.db.payments.getOutgoingPayment(id).isEmpty)

    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFulfill(UpdateFulfillHtlc(ByteVector32.Zeroes, 0, defaultPaymentPreimage))))
    sender.expectMsgType[PaymentSent]
    assert(nodeParams.db.payments.getOutgoingPayment(id).isEmpty)
    eventListener.expectNoMessage(100 millis)

    assert(routerForwarder.expectMsgType[RouteDidRelay].route.hops.map(_.nextNodeId) == Seq(b, c, d))
  }

  test("send to route (no retry on error") { () =>
    val payFixture = createPaymentLifecycle(defaultInvoice)
    import payFixture._
    import cfg._

    // pre-computed route going from A to D
    val route = Route(defaultAmountMsat, channelHopFromUpdate(a, b, update_ab) :: channelHopFromUpdate(b, c, update_bc) :: channelHopFromUpdate(c, d, update_cd) :: Nil, None)
    val request = SendPaymentToRoute(sender.ref, Right(route), defaultRecipient)
    sender.send(paymentFSM, request)
    routerForwarder.expectNoMessage(100 millis) // we don't need the router, we have the pre-computed route
    val Transition(_, WAITING_FOR_REQUEST, WAITING_FOR_ROUTE) = monitor.expectMsgClass(classOf[Transition[_]])

    val Transition(_, WAITING_FOR_ROUTE, WAITING_FOR_PAYMENT_COMPLETE) = monitor.expectMsgClass(classOf[Transition[_]])
    val WaitingForComplete(_, _, Nil, sharedSecrets1, _, _) = paymentFSM.stateData
    awaitCond(nodeParams.db.payments.getOutgoingPayment(id).exists(_.status == OutgoingPaymentStatus.Pending))
    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(id)
    assert(outgoing.copy(createdAt = 0 unixms) == OutgoingPayment(id, parentId, Some(defaultExternalId), defaultPaymentHash, PaymentType.Standard, defaultAmountMsat, defaultAmountMsat, d, 0 unixms, Some(defaultInvoice), None, OutgoingPaymentStatus.Pending))

    // we change the cltv expiry
    val channelUpdate_bc_modified = makeChannelUpdate(Block.RegtestGenesisBlock.hash, priv_b, c, scid_bc, CltvExpiryDelta(42), htlcMinimumMsat = update_bc.htlcMinimumMsat, feeBaseMsat = update_bc.feeBaseMsat, feeProportionalMillionths = update_bc.feeProportionalMillionths, htlcMaximumMsat = update_bc.htlcMaximumMsat)
    val failure = IncorrectCltvExpiry(CltvExpiry(5), channelUpdate_bc_modified)
    // and node replies with a failure containing a new channel update
    sender.send(paymentFSM, addCompleted(HtlcResult.RemoteFail(UpdateFailHtlc(ByteVector32.Zeroes, 0, Sphinx.FailurePacket.create(sharedSecrets1.head._1, failure)))))

    // payment lifecycle forwards the embedded channelUpdate to the router
    routerForwarder.expectMsg(channelUpdate_bc_modified)

    // The payment fails without retrying
    sender.expectMsgType[PaymentFailed]
  }

}
