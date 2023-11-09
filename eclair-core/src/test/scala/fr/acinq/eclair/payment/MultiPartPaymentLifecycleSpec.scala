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

import akka.actor.{ActorContext, ActorRef, Status}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, SatoshiLong}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{ChannelUnavailable, HtlcsTimedoutDownstream, RemoteCannotAffordFeesForNewHtlc}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.{FailureSummary, FailureType, OutgoingPaymentStatus}
import fr.acinq.eclair.payment.Invoice.ExtraEdge
import fr.acinq.eclair.payment.OutgoingPaymentPacket.Upstream
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle._
import fr.acinq.eclair.payment.send.PaymentError.RetryExhausted
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPaymentToRoute
import fr.acinq.eclair.payment.send._
import fr.acinq.eclair.router.BaseRouterSpec.{blindedRouteFromHops, channelHopFromUpdate}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.router.{Announcements, RouteNotFound}
import fr.acinq.eclair.wire.protocol._
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.HexStringSyntax

import java.util.UUID
import scala.concurrent.duration._

/**
 * Created by t-bast on 18/07/2019.
 */

class MultiPartPaymentLifecycleSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike {

  import MultiPartPaymentLifecycleSpec._

  case class FixtureParam(cfg: SendPaymentConfig,
                          nodeParams: NodeParams,
                          payFsm: TestFSMRef[MultiPartPaymentLifecycle.State, MultiPartPaymentLifecycle.Data, MultiPartPaymentLifecycle],
                          router: TestProbe,
                          sender: TestProbe,
                          childPayFsm: TestProbe,
                          eventListener: TestProbe,
                          metricsListener: TestProbe)

  case class FakePaymentFactory(childPayFsm: TestProbe) extends PaymentInitiator.PaymentFactory {
    override def spawnOutgoingPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef = childPayFsm.ref
  }

  override def withFixture(test: OneArgTest): Outcome = {
    val id = UUID.randomUUID()
    val cfg = SendPaymentConfig(id, id, Some("42"), paymentHash, randomKey().publicKey, Upstream.Local(id), None, None, storeInDb = true, publishEvent = true, recordPathFindingMetrics = true)
    val nodeParams = TestConstants.Alice.nodeParams
    val (childPayFsm, router, sender, eventListener, metricsListener) = (TestProbe(), TestProbe(), TestProbe(), TestProbe(), TestProbe())
    val paymentHandler = TestFSMRef(new MultiPartPaymentLifecycle(nodeParams, cfg, publishPreimage = true, router.ref, FakePaymentFactory(childPayFsm)))
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
    system.eventStream.subscribe(metricsListener.ref, classOf[PathFindingExperimentMetrics])
    withFixture(test.toNoArgTest(FixtureParam(cfg, nodeParams, paymentHandler, router, sender, childPayFsm, eventListener, metricsListener)))
  }

  test("successful first attempt (single part)") { f =>
    import f._

    assert(payFsm.stateName == WAIT_FOR_PAYMENT_REQUEST)
    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 1, routeParams.copy(randomize = true))
    sender.send(payFsm, payment)

    router.expectMsg(RouteRequest(nodeParams.nodeId, clearRecipient, routeParams.copy(randomize = false), allowMultiPart = true, paymentContext = Some(cfg.paymentContext)))
    assert(payFsm.stateName == WAIT_FOR_ROUTES)

    val singleRoute = Route(finalAmount, hop_ab_1 :: hop_be :: Nil, None)
    router.send(payFsm, RouteResponse(Seq(singleRoute)))
    val childPayment = childPayFsm.expectMsgType[SendPaymentToRoute]
    assert(childPayment.route == Right(singleRoute))
    assert(childPayment.amount == finalAmount)
    assert(childPayment.recipient == payment.recipient)
    assert(payFsm.stateName == PAYMENT_IN_PROGRESS)

    val result = fulfillPendingPayments(f, 1, e, finalAmount)
    assert(result.amountWithFees == finalAmount + 100.msat)
    assert(result.trampolineFees == 0.msat)
    assert(result.nonTrampolineFees == 100.msat)

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "SUCCESS")
    assert(metrics.experimentName == "my-test-experiment")
    assert(metrics.amount == finalAmount)
    assert(metrics.fees == 100.msat)
    metricsListener.expectNoMessage()
  }

  test("successful first attempt (multiple parts)") { f =>
    import f._

    assert(payFsm.stateName == WAIT_FOR_PAYMENT_REQUEST)
    val recipient = ClearRecipient(e, recipientFeatures, 1_200_000 msat, expiry, randomBytes32(), paymentMetadata_opt = Some(hex"012345"))
    val payment = SendMultiPartPayment(sender.ref, recipient, 1, routeParams.copy(randomize = false))
    sender.send(payFsm, payment)

    router.expectMsg(RouteRequest(nodeParams.nodeId, recipient, routeParams.copy(randomize = false), allowMultiPart = true, paymentContext = Some(cfg.paymentContext)))
    assert(payFsm.stateName == WAIT_FOR_ROUTES)

    val routes = Seq(
      Route(500_000 msat, hop_ab_1 :: hop_be :: Nil, None),
      Route(700_000 msat, hop_ac_1 :: hop_ce :: Nil, None),
    )
    router.send(payFsm, RouteResponse(routes))
    val childPayments = childPayFsm.expectMsgType[SendPaymentToRoute] :: childPayFsm.expectMsgType[SendPaymentToRoute] :: Nil
    assert(childPayments.map(_.route).toSet == routes.map(r => Right(r)).toSet)
    childPayments.foreach(childPayment => assert(childPayment.recipient == recipient))
    assert(childPayments.map(_.amount).toSet == Set(500_000 msat, 700_000 msat))
    assert(payFsm.stateName == PAYMENT_IN_PROGRESS)

    val result = fulfillPendingPayments(f, 2, e, 1_200_000 msat)
    assert(result.amountWithFees == 1_200_200.msat)
    assert(result.nonTrampolineFees == 200.msat)

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "SUCCESS")
    assert(metrics.experimentName == "my-test-experiment")
    assert(metrics.amount == 1_200_000.msat)
    assert(metrics.fees == 200.msat)
    metricsListener.expectNoMessage()
  }

  test("successful first attempt (trampoline)") { f =>
    import f._

    assert(payFsm.stateName == WAIT_FOR_PAYMENT_REQUEST)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), randomBytes32(), randomKey(), Left("invoice"), CltvExpiryDelta(12))
    val trampolineHop = NodeHop(e, invoice.nodeId, CltvExpiryDelta(50), 1000 msat)
    val recipient = ClearTrampolineRecipient(invoice, finalAmount, expiry, trampolineHop, randomBytes32())
    val payment = SendMultiPartPayment(sender.ref, recipient, 1, routeParams)
    sender.send(payFsm, payment)

    router.expectMsg(RouteRequest(nodeParams.nodeId, recipient, routeParams.copy(randomize = false), allowMultiPart = true, paymentContext = Some(cfg.paymentContext)))
    assert(payFsm.stateName == WAIT_FOR_ROUTES)

    val routes = Seq(
      Route(500_000 msat, hop_ab_1 :: hop_be :: Nil, Some(trampolineHop)),
      Route(501_000 msat, hop_ac_1 :: hop_ce :: Nil, Some(trampolineHop))
    )
    router.send(payFsm, RouteResponse(routes))
    val childPayments = childPayFsm.expectMsgType[SendPaymentToRoute] :: childPayFsm.expectMsgType[SendPaymentToRoute] :: Nil
    assert(childPayments.map(_.route).toSet == routes.map(r => Right(r)).toSet)
    childPayments.foreach(childPayment => assert(childPayment.recipient == recipient))
    assert(childPayments.map(_.amount).toSet == Set(500_000 msat, 501_000 msat))
    assert(payFsm.stateName == PAYMENT_IN_PROGRESS)

    val result = fulfillPendingPayments(f, 2, invoice.nodeId, finalAmount)
    assert(result.amountWithFees == 1_001_200.msat)
    assert(result.trampolineFees == 1000.msat)
    assert(result.nonTrampolineFees == 200.msat)

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "SUCCESS")
    assert(metrics.experimentName == "my-test-experiment")
    assert(metrics.amount == finalAmount)
    assert(metrics.fees == 1200.msat)
    metricsListener.expectNoMessage()
  }

  test("successful first attempt (blinded)") { f =>
    import f._

    assert(payFsm.stateName == WAIT_FOR_PAYMENT_REQUEST)
    val (_, hop_be, recipient) = blindedRouteFromHops(finalAmount, expiry, Seq(channelHopFromUpdate(b, e, channelUpdate_be)), blindedRouteExpiry, paymentPreimage)
    val payment = SendMultiPartPayment(sender.ref, recipient, 1, routeParams)
    sender.send(payFsm, payment)

    router.expectMsg(RouteRequest(nodeParams.nodeId, recipient, routeParams.copy(randomize = false), allowMultiPart = true, paymentContext = Some(cfg.paymentContext)))
    assert(payFsm.stateName == WAIT_FOR_ROUTES)

    val routes = Seq(
      Route(600_000 msat, Seq(hop_ab_1), Some(hop_be)),
      Route(400_000 msat, Seq(hop_ab_2), Some(hop_be)),
    )
    router.send(payFsm, RouteResponse(routes))
    val childPayments = childPayFsm.expectMsgType[SendPaymentToRoute] :: childPayFsm.expectMsgType[SendPaymentToRoute] :: Nil
    assert(childPayments.map(_.route).toSet == routes.map(r => Right(r)).toSet)
    childPayments.foreach(childPayment => assert(childPayment.recipient == recipient))
    assert(childPayments.map(_.amount).toSet == Set(400_000 msat, 600_000 msat))
    assert(payFsm.stateName == PAYMENT_IN_PROGRESS)

    val result = fulfillPendingPayments(f, 2, recipient.nodeId, finalAmount)
    assert(result.amountWithFees == 1_000_200.msat)
    assert(result.nonTrampolineFees == 200.msat)
  }

  test("successful retry") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 3, routeParams)
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    val failingRoute = Route(finalAmount, hop_ab_1 :: hop_be :: Nil, None)
    router.send(payFsm, RouteResponse(Seq(failingRoute)))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectNoMessage(100 millis)

    val childId = payFsm.stateData.asInstanceOf[PaymentProgress].pending.keys.head
    childPayFsm.send(payFsm, PaymentFailed(childId, paymentHash, Seq(RemoteFailure(failingRoute.amount, failingRoute.fullRoute, Sphinx.DecryptedFailurePacket(b, PermanentChannelFailure())))))
    // We retry ignoring the failing channel.
    router.expectMsg(RouteRequest(nodeParams.nodeId, clearRecipient, routeParams.copy(randomize = true), allowMultiPart = true, ignore = Ignore(Set.empty, Set(ChannelDesc(channelId_be, b, e))), paymentContext = Some(cfg.paymentContext)))
    router.send(payFsm, RouteResponse(Seq(Route(400_000 msat, hop_ac_1 :: hop_ce :: Nil, None), Route(600_000 msat, hop_ad :: hop_de :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]
    assert(!payFsm.stateData.asInstanceOf[PaymentProgress].pending.contains(childId))

    val result = fulfillPendingPayments(f, 2, e, finalAmount)
    assert(result.amountWithFees == 1_000_200.msat)
    assert(result.trampolineFees == 0.msat)
    assert(result.nonTrampolineFees == 200.msat)

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "SUCCESS")
    assert(metrics.experimentName == "my-test-experiment")
    assert(metrics.amount == finalAmount)
    assert(metrics.fees == 200.msat)
    metricsListener.expectNoMessage()
  }

  test("retry failures while waiting for routes") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 3, routeParams)
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(400_000 msat, hop_ab_1 :: hop_be :: Nil, None), Route(600_000 msat, hop_ab_2 :: hop_be :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectNoMessage(100 millis)

    val (failedId1, failedRoute1) :: (failedId2, failedRoute2) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentFailed(failedId1, paymentHash, Seq(RemoteFailure(failedRoute1.amount, failedRoute1.hops, Sphinx.DecryptedFailurePacket(b, TemporaryNodeFailure())))))

    // When we retry, we ignore the failing node and we let the router know about the remaining pending route.
    router.expectMsg(RouteRequest(nodeParams.nodeId, clearRecipient, routeParams.copy(randomize = true), Ignore(Set(b), Set.empty), pendingPayments = Seq(failedRoute2), allowMultiPart = true, paymentContext = Some(cfg.paymentContext)))
    // The second part fails while we're still waiting for new routes.
    childPayFsm.send(payFsm, PaymentFailed(failedId2, paymentHash, Seq(RemoteFailure(failedRoute2.amount, failedRoute2.hops, Sphinx.DecryptedFailurePacket(b, TemporaryNodeFailure())))))
    // We receive a response to our first request, but it's now obsolete: we re-sent a new route request that takes into
    // account the latest failures.
    router.send(payFsm, RouteResponse(Seq(Route(failedRoute1.amount, hop_ac_1 :: hop_ce :: Nil, None))))
    router.expectMsg(RouteRequest(nodeParams.nodeId, clearRecipient, routeParams.copy(randomize = true), Ignore(Set(b), Set.empty), allowMultiPart = true, paymentContext = Some(cfg.paymentContext)))
    awaitCond(payFsm.stateData.asInstanceOf[PaymentProgress].pending.isEmpty)
    childPayFsm.expectNoMessage(100 millis)

    // We receive new routes that work.
    router.send(payFsm, RouteResponse(Seq(Route(300_000 msat, hop_ac_1 :: hop_ce :: Nil, None), Route(700_000 msat, hop_ad :: hop_de :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val result = fulfillPendingPayments(f, 2, e, finalAmount)
    assert(result.amountWithFees == 1_000_200.msat)
    assert(result.nonTrampolineFees == 200.msat)

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "SUCCESS")
    assert(metrics.experimentName == "my-test-experiment")
    assert(metrics.amount == finalAmount)
    assert(metrics.fees == 200.msat)
    metricsListener.expectNoMessage()
  }

  test("retry local channel failures") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 3, routeParams)
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(finalAmount, hop_ab_1 :: hop_be :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectNoMessage(100 millis)

    val (failedId, failedRoute) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentFailed(failedId, paymentHash, Seq(LocalFailure(failedRoute.amount, failedRoute.fullRoute, RemoteCannotAffordFeesForNewHtlc(randomBytes32(), finalAmount, 15 sat, 0 sat, 15 sat)))))

    // We retry without the failing channel.
    router.expectMsg(RouteRequest(
      nodeParams.nodeId,
      clearRecipient,
      routeParams.copy(randomize = true),
      Ignore(Set.empty, Set(ChannelDesc(channelId_ab_1, a, b))),
      pendingPayments = Nil,
      allowMultiPart = true,
      paymentContext = Some(cfg.paymentContext)
    ))
  }

  test("retry without ignoring channels") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 3, routeParams)
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(500_000 msat, hop_ab_1 :: hop_be :: Nil, None), Route(500_000 msat, hop_ab_1 :: hop_be :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectNoMessage(100 millis)

    val (failedId, failedRoute) :: (_, pendingRoute) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentFailed(failedId, paymentHash, Seq(LocalFailure(failedRoute.amount, failedRoute.fullRoute, ChannelUnavailable(randomBytes32())))))

    // If the router doesn't find routes, we will retry without ignoring the channel: it may work with a different split
    // of the amount to send.
    val expectedRouteRequest = RouteRequest(
      nodeParams.nodeId,
      clearRecipient,
      routeParams.copy(randomize = true),
      Ignore(Set.empty, Set(ChannelDesc(channelId_ab_1, a, b))),
      pendingPayments = Seq(pendingRoute),
      allowMultiPart = true,
      paymentContext = Some(cfg.paymentContext))
    router.expectMsg(expectedRouteRequest)
    router.send(payFsm, Status.Failure(RouteNotFound))
    router.expectMsg(expectedRouteRequest.copy(ignore = Ignore.empty))

    router.send(payFsm, RouteResponse(Seq(Route(500_000 msat, hop_ac_1 :: hop_ce :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val result = fulfillPendingPayments(f, 2, e, finalAmount)
    assert(result.amountWithFees == 1_000_200.msat)

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "SUCCESS")
    assert(metrics.experimentName == "my-test-experiment")
    assert(metrics.amount == finalAmount)
    assert(metrics.fees == 200.msat)
    metricsListener.expectNoMessage()
  }

  test("retry with updated routing hints") { f =>
    import f._

    // The B -> E channel is private and provided in the invoice routing hints.
    val extraEdge = ExtraEdge(b, e, hop_be.shortChannelId, hop_be.params.relayFees.feeBase, hop_be.params.relayFees.feeProportionalMillionths, hop_be.params.cltvExpiryDelta, hop_be.params.htlcMinimum, hop_be.params.htlcMaximum_opt)
    val recipient = ClearRecipient(e, Features.empty, finalAmount, expiry, randomBytes32(), Seq(extraEdge))
    val payment = SendMultiPartPayment(sender.ref, recipient, 3, routeParams)
    sender.send(payFsm, payment)
    assert(router.expectMsgType[RouteRequest].target.extraEdges == Seq(extraEdge))
    val route = Route(finalAmount, hop_ab_1 :: hop_be :: Nil, None)
    router.send(payFsm, RouteResponse(Seq(route)))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectNoMessage(100 millis)

    // B changed his fees and expiry after the invoice was issued.
    val channelUpdate = channelUpdate_be.copy(feeBaseMsat = 250 msat, feeProportionalMillionths = 150, cltvExpiryDelta = CltvExpiryDelta(24))
    val childId = payFsm.stateData.asInstanceOf[PaymentProgress].pending.keys.head
    childPayFsm.send(payFsm, PaymentFailed(childId, paymentHash, Seq(RemoteFailure(route.amount, route.fullRoute, Sphinx.DecryptedFailurePacket(b, FeeInsufficient(finalAmount, channelUpdate))))))
    // We update the routing hints accordingly before requesting a new route.
    val extraEdge1 = extraEdge.copy(feeBase = 250 msat, feeProportionalMillionths = 150, cltvExpiryDelta = CltvExpiryDelta(24))
    assert(router.expectMsgType[RouteRequest].target.extraEdges == Seq(extraEdge1))
  }

  test("retry with ignored routing hints (temporary channel failure)") { f =>
    import f._

    // The B -> E channel is private and provided in the invoice routing hints.
    val extraEdge = ExtraEdge(b, e, hop_be.shortChannelId, hop_be.params.relayFees.feeBase, hop_be.params.relayFees.feeProportionalMillionths, hop_be.params.cltvExpiryDelta, hop_be.params.htlcMinimum, hop_be.params.htlcMaximum_opt)
    val recipient = ClearRecipient(e, Features.empty, finalAmount, expiry, randomBytes32(), Seq(extraEdge))
    val payment = SendMultiPartPayment(sender.ref, recipient, 3, routeParams)
    sender.send(payFsm, payment)
    assert(router.expectMsgType[RouteRequest].target.extraEdges == Seq(extraEdge))
    val route = Route(finalAmount, hop_ab_1 :: hop_be :: Nil, None)
    router.send(payFsm, RouteResponse(Seq(route)))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectNoMessage(100 millis)

    // B doesn't have enough liquidity on this channel.
    // NB: we need a channel update with a valid signature, otherwise we'll ignore the node instead of this specific channel.
    val channelUpdate = Announcements.makeChannelUpdate(channelUpdate_be.chainHash, priv_b, e, channelUpdate_be.shortChannelId, channelUpdate_be.cltvExpiryDelta, channelUpdate_be.htlcMinimumMsat, channelUpdate_be.feeBaseMsat, channelUpdate_be.feeProportionalMillionths, channelUpdate_be.htlcMaximumMsat)
    val childId = payFsm.stateData.asInstanceOf[PaymentProgress].pending.keys.head
    childPayFsm.send(payFsm, PaymentFailed(childId, paymentHash, Seq(RemoteFailure(route.amount, route.fullRoute, Sphinx.DecryptedFailurePacket(b, TemporaryChannelFailure(channelUpdate))))))
    // We update the routing hints accordingly before requesting a new route and ignore the channel.
    val routeRequest = router.expectMsgType[RouteRequest]
    assert(routeRequest.target.extraEdges == Seq(extraEdge))
    assert(routeRequest.ignore.channels.map(_.shortChannelId) == Set(channelUpdate.shortChannelId))
  }

  test("retry with ignored blinded route") { f =>
    import f._

    val (_, hop_be, recipient) = blindedRouteFromHops(finalAmount, expiry, Seq(channelHopFromUpdate(b, e, channelUpdate_be)), blindedRouteExpiry, paymentPreimage)
    val payment = SendMultiPartPayment(sender.ref, recipient, 3, routeParams)
    sender.send(payFsm, payment)
    assert(router.expectMsgType[RouteRequest].target.extraEdges == recipient.extraEdges)
    val route = Route(finalAmount, Seq(hop_ab_1), Some(hop_be))
    router.send(payFsm, RouteResponse(Seq(route)))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectNoMessage(100 millis)

    // The blinded route fails to relay the payment.
    val childId = payFsm.stateData.asInstanceOf[PaymentProgress].pending.keys.head
    childPayFsm.send(payFsm, PaymentFailed(childId, paymentHash, Seq(RemoteFailure(route.amount, route.fullRoute, Sphinx.DecryptedFailurePacket(b, InvalidOnionBlinding(randomBytes32()))))))
    // We retry and ignore that blinded route.
    val routeRequest = router.expectMsgType[RouteRequest]
    assert(routeRequest.ignore.channels.map(_.shortChannelId) == Set(hop_be.dummyId))
  }

  test("update routing hints") { () =>
    val recipient = ClearRecipient(e, Features.empty, finalAmount, expiry, randomBytes32(), Seq(
      ExtraEdge(a, b, ShortChannelId(1), 10 msat, 0, CltvExpiryDelta(12), 1 msat, None),
      ExtraEdge(b, c, ShortChannelId(2), 0 msat, 100, CltvExpiryDelta(24), 1 msat, None),
      ExtraEdge(a, c, ShortChannelId(3), 1 msat, 10, CltvExpiryDelta(144), 1 msat, None)
    ))

    def makeChannelUpdate(shortChannelId: ShortChannelId, feeBase: MilliSatoshi, feeProportional: Long, cltvExpiryDelta: CltvExpiryDelta): ChannelUpdate = {
      defaultChannelUpdate.copy(shortChannelId = shortChannelId, feeBaseMsat = feeBase, feeProportionalMillionths = feeProportional, cltvExpiryDelta = cltvExpiryDelta)
    }

    {
      val failures = Seq(
        LocalFailure(finalAmount, Nil, ChannelUnavailable(randomBytes32())),
        RemoteFailure(finalAmount, Nil, Sphinx.DecryptedFailurePacket(b, FeeInsufficient(100 msat, makeChannelUpdate(ShortChannelId(2), 15 msat, 150, CltvExpiryDelta(48))))),
        UnreadableRemoteFailure(finalAmount, Nil)
      )
      val extraEdges1 = Seq(
        ExtraEdge(a, b, ShortChannelId(1), 10 msat, 0, CltvExpiryDelta(12), 1 msat, None),
        ExtraEdge(b, c, ShortChannelId(2), 15 msat, 150, CltvExpiryDelta(48), defaultChannelUpdate.htlcMinimumMsat, Some(defaultChannelUpdate.htlcMaximumMsat)),
        ExtraEdge(a, c, ShortChannelId(3), 1 msat, 10, CltvExpiryDelta(144), 1 msat, None)
      )
      assert(extraEdges1.zip(PaymentFailure.updateExtraEdges(failures, recipient).extraEdges).forall { case (e1, e2) => e1 == e2 })
    }
    {
      val failures = Seq(
        RemoteFailure(finalAmount, Nil, Sphinx.DecryptedFailurePacket(a, FeeInsufficient(100 msat, makeChannelUpdate(ShortChannelId(1), 20 msat, 20, CltvExpiryDelta(20))))),
        RemoteFailure(finalAmount, Nil, Sphinx.DecryptedFailurePacket(b, FeeInsufficient(100 msat, makeChannelUpdate(ShortChannelId(2), 21 msat, 21, CltvExpiryDelta(21))))),
        RemoteFailure(finalAmount, Nil, Sphinx.DecryptedFailurePacket(a, FeeInsufficient(100 msat, makeChannelUpdate(ShortChannelId(3), 22 msat, 22, CltvExpiryDelta(22))))),
        RemoteFailure(finalAmount, Nil, Sphinx.DecryptedFailurePacket(a, FeeInsufficient(100 msat, makeChannelUpdate(ShortChannelId(1), 23 msat, 23, CltvExpiryDelta(23))))),
      )
      val extraEdges1 = Seq(
        ExtraEdge(a, b, ShortChannelId(1), 23 msat, 23, CltvExpiryDelta(23), defaultChannelUpdate.htlcMinimumMsat, Some(defaultChannelUpdate.htlcMaximumMsat)),
        ExtraEdge(b, c, ShortChannelId(2), 21 msat, 21, CltvExpiryDelta(21), defaultChannelUpdate.htlcMinimumMsat, Some(defaultChannelUpdate.htlcMaximumMsat)),
        ExtraEdge(a, c, ShortChannelId(3), 22 msat, 22, CltvExpiryDelta(22), defaultChannelUpdate.htlcMinimumMsat, Some(defaultChannelUpdate.htlcMaximumMsat))
      )
      assert(extraEdges1.zip(PaymentFailure.updateExtraEdges(failures, recipient).extraEdges).forall { case (e1, e2) => e1 == e2 })
    }
  }

  test("abort after too many failed attempts") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 2, routeParams)
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(500_000 msat, hop_ab_1 :: hop_be :: Nil, None), Route(500_000 msat, hop_ac_1 :: hop_ce :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (failedId1, failedRoute1) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head
    childPayFsm.send(payFsm, PaymentFailed(failedId1, paymentHash, Seq(UnreadableRemoteFailure(failedRoute1.amount, failedRoute1.hops))))
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(500_000 msat, hop_ad :: hop_de :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]

    assert(!payFsm.stateData.asInstanceOf[PaymentProgress].pending.contains(failedId1))
    val (failedId2, failedRoute2) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head
    val result = abortAfterFailure(f, PaymentFailed(failedId2, paymentHash, Seq(UnreadableRemoteFailure(failedRoute2.amount, failedRoute2.hops))))
    assert(result.failures.length >= 3)
    assert(result.failures.contains(LocalFailure(finalAmount, Nil, RetryExhausted)))

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "RECIPIENT_FAILURE")
    assert(metrics.experimentName == "my-test-experiment")
    assert(metrics.amount == finalAmount)
    assert(metrics.fees == 15_000.msat)
    metricsListener.expectNoMessage()
  }

  test("abort if no routes found") { f =>
    import f._

    sender.watch(payFsm)
    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 5, routeParams)
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, Status.Failure(RouteNotFound))

    val result = sender.expectMsgType[PaymentFailed]
    assert(result.id == cfg.id)
    assert(result.paymentHash == paymentHash)
    assert(result.failures == Seq(LocalFailure(finalAmount, Nil, RouteNotFound)))

    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(cfg.id)
    assert(outgoing.status.isInstanceOf[OutgoingPaymentStatus.Failed])
    assert(outgoing.status.asInstanceOf[OutgoingPaymentStatus.Failed].failures == Seq(FailureSummary(FailureType.LOCAL, RouteNotFound.getMessage, Nil, None)))

    sender.expectTerminated(payFsm)
    sender.expectNoMessage(100 millis)
    router.expectNoMessage(100 millis)
    childPayFsm.expectNoMessage(100 millis)

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "FAILURE")
    assert(metrics.experimentName == "my-test-experiment")
    assert(metrics.amount == finalAmount)
    assert(metrics.fees == 15_000.msat)
    metricsListener.expectNoMessage()
  }

  test("abort if recipient sends error") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 5, routeParams)
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(finalAmount, hop_ab_1 :: hop_be :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (failedId, failedRoute) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head
    val result = abortAfterFailure(f, PaymentFailed(failedId, paymentHash, Seq(RemoteFailure(failedRoute.amount, failedRoute.fullRoute, Sphinx.DecryptedFailurePacket(e, IncorrectOrUnknownPaymentDetails(600_000 msat, BlockHeight(0)))))))
    assert(result.failures.length == 1)

    val metrics = metricsListener.expectMsgType[PathFindingExperimentMetrics]
    assert(metrics.status == "RECIPIENT_FAILURE")
    assert(metrics.experimentName == "my-test-experiment")
    assert(metrics.amount == finalAmount)
    assert(metrics.fees == 15_000.msat)
    metricsListener.expectNoMessage()
  }

  test("abort if payment gets settled on chain") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 5, routeParams)
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(finalAmount, hop_ab_1 :: hop_be :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (failedId, failedRoute) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head
    val result = abortAfterFailure(f, PaymentFailed(failedId, paymentHash, Seq(LocalFailure(failedRoute.amount, failedRoute.fullRoute, HtlcsTimedoutDownstream(channelId = ByteVector32.One, htlcs = Set.empty)))))
    assert(result.failures.length == 1)
  }

  test("abort if recipient sends error during retry") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 5, routeParams)
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(400_000 msat, hop_ab_1 :: hop_be :: Nil, None), Route(600_000 msat, hop_ac_1 :: hop_ce :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (failedId1, failedRoute1) :: (failedId2, failedRoute2) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentFailed(failedId1, paymentHash, Seq(UnreadableRemoteFailure(failedRoute1.amount, failedRoute1.hops))))
    router.expectMsgType[RouteRequest]

    val result = abortAfterFailure(f, PaymentFailed(failedId2, paymentHash, Seq(RemoteFailure(failedRoute2.amount, failedRoute2.hops, Sphinx.DecryptedFailurePacket(e, PaymentTimeout())))))
    assert(result.failures.length == 2)
  }

  test("receive partial success after retriable failure (recipient spec violation)") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 5, routeParams)
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(400_000 msat, hop_ab_1 :: hop_be :: Nil, None), Route(600_000 msat, hop_ac_1 :: hop_ce :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (failedId, failedRoute) :: (successId, successRoute) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentFailed(failedId, paymentHash, Seq(UnreadableRemoteFailure(failedRoute.amount, failedRoute.fullRoute))))
    router.expectMsgType[RouteRequest]

    val result = fulfillPendingPayments(f, 1, e, finalAmount)
    assert(result.amountWithFees < finalAmount) // we got the preimage without paying the full amount
    assert(result.nonTrampolineFees == successRoute.channelFee(false)) // we paid the fee for only one of the partial payments
    assert(result.parts.length == 1 && result.parts.head.id == successId)
  }

  test("receive partial success after abort (recipient spec violation)") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 5, routeParams)
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(400_000 msat, hop_ab_1 :: hop_be :: Nil, None), Route(600_000 msat, hop_ac_1 :: hop_ce :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (failedId, failedRoute) :: (successId, successRoute) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentFailed(failedId, paymentHash, Seq(RemoteFailure(failedRoute.amount, failedRoute.fullRoute, Sphinx.DecryptedFailurePacket(e, PaymentTimeout())))))
    awaitCond(payFsm.stateName == PAYMENT_ABORTED)

    sender.watch(payFsm)
    childPayFsm.send(payFsm, PaymentSent(cfg.id, paymentHash, paymentPreimage, finalAmount, e, Seq(PaymentSent.PartialPayment(successId, successRoute.amount, successRoute.channelFee(false), randomBytes32(), Some(successRoute.fullRoute)))))
    sender.expectMsg(PreimageReceived(paymentHash, paymentPreimage))
    val result = sender.expectMsgType[PaymentSent]
    assert(result.id == cfg.id)
    assert(result.paymentHash == paymentHash)
    assert(result.paymentPreimage == paymentPreimage)
    assert(result.parts.length == 1 && result.parts.head.id == successId)
    assert(result.recipientAmount == finalAmount)
    assert(result.recipientNodeId == e)
    assert(result.amountWithFees < finalAmount) // we got the preimage without paying the full amount
    assert(result.nonTrampolineFees == successRoute.channelFee(false)) // we paid the fee for only one of the partial payments

    sender.expectTerminated(payFsm)
    sender.expectNoMessage(100 millis)
    router.expectNoMessage(100 millis)
    childPayFsm.expectNoMessage(100 millis)
  }

  test("receive partial failure after success (recipient spec violation)") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, clearRecipient, 5, routeParams)
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(400_000 msat, hop_ab_1 :: hop_be :: Nil, None), Route(600_000 msat, hop_ac_1 :: hop_ce :: Nil, None))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (childId, route) :: (failedId, failedRoute) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentSent(cfg.id, paymentHash, paymentPreimage, finalAmount, e, Seq(PaymentSent.PartialPayment(childId, route.amount, route.channelFee(false), randomBytes32(), Some(route.fullRoute)))))
    sender.expectMsg(PreimageReceived(paymentHash, paymentPreimage))
    awaitCond(payFsm.stateName == PAYMENT_SUCCEEDED)

    sender.watch(payFsm)
    childPayFsm.send(payFsm, PaymentFailed(failedId, paymentHash, Seq(RemoteFailure(failedRoute.amount, failedRoute.fullRoute, Sphinx.DecryptedFailurePacket(e, PaymentTimeout())))))
    val result = sender.expectMsgType[PaymentSent]
    assert(result.parts.length == 1 && result.parts.head.id == childId)
    assert(result.amountWithFees < finalAmount) // we got the preimage without paying the full amount
    assert(result.nonTrampolineFees == route.channelFee(false)) // we paid the fee for only one of the partial payments

    sender.expectTerminated(payFsm)
    sender.expectNoMessage(100 millis)
    router.expectNoMessage(100 millis)
    childPayFsm.expectNoMessage(100 millis)
  }

  def fulfillPendingPayments(f: FixtureParam, childCount: Int, recipientNodeId: PublicKey, recipientAmount: MilliSatoshi): PaymentSent = {
    import f._

    sender.watch(payFsm)
    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending
    assert(pending.size == childCount)

    val partialPayments = pending.map {
      case (childId, route) => PaymentSent.PartialPayment(childId, route.amount, route.channelFee(false) + route.blindedFee, randomBytes32(), Some(route.fullRoute))
    }
    partialPayments.foreach(pp => childPayFsm.send(payFsm, PaymentSent(cfg.id, paymentHash, paymentPreimage, finalAmount, e, Seq(pp))))
    sender.expectMsg(PreimageReceived(paymentHash, paymentPreimage))
    val result = sender.expectMsgType[PaymentSent]
    assert(result.id == cfg.id)
    assert(result.paymentHash == paymentHash)
    assert(result.paymentPreimage == paymentPreimage)
    assert(result.parts.toSet == partialPayments.toSet)
    assert(result.recipientAmount == recipientAmount)
    assert(result.recipientNodeId == recipientNodeId)

    sender.expectTerminated(payFsm)
    sender.expectNoMessage(100 millis)
    router.expectNoMessage(100 millis)
    childPayFsm.expectNoMessage(100 millis)

    result
  }

  def abortAfterFailure(f: FixtureParam, childFailure: PaymentFailed): PaymentFailed = {
    import f._

    sender.watch(payFsm)
    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending
    childPayFsm.send(payFsm, childFailure) // this failure should trigger an abort
    if (pending.size > 1) {
      awaitCond(payFsm.stateName == PAYMENT_ABORTED)
      assert(payFsm.stateData.asInstanceOf[PaymentAborted].pending.size == pending.size - 1)
      // Fail all remaining child payments.
      payFsm.stateData.asInstanceOf[PaymentAborted].pending.foreach(childId =>
        childPayFsm.send(payFsm, PaymentFailed(childId, paymentHash, Seq(RemoteFailure(pending(childId).amount, hop_ab_1 :: hop_be :: Nil, Sphinx.DecryptedFailurePacket(e, PaymentTimeout())))))
      )
    }

    val result = sender.expectMsgType[PaymentFailed]
    assert(result.id == cfg.id)
    assert(result.paymentHash == paymentHash)
    assert(result.failures.nonEmpty)

    sender.expectTerminated(payFsm)
    sender.expectNoMessage(100 millis)
    router.expectNoMessage(100 millis)
    childPayFsm.expectNoMessage(100 millis)

    result
  }

}

object MultiPartPaymentLifecycleSpec {

  val paymentPreimage = randomBytes32()
  val paymentHash = Crypto.sha256(paymentPreimage)
  val expiry = CltvExpiry(1105)
  val blindedRouteExpiry = CltvExpiry(500_000)
  val finalAmount = 1_000_000 msat
  val routeParams = PathFindingConf(
    randomize = false,
    boundaries = SearchBoundaries(
      15_000 msat,
      0.00,
      6,
      CltvExpiryDelta(1008)),
    Left(WeightRatios(1, 0, 0, 0, RelayFees(0 msat, 0))),
    MultiPartParams(1000 msat, 5),
    experimentName = "my-test-experiment",
    experimentPercentage = 100
  ).getDefaultRouteParams

  /**
   * We simulate a multi-part-friendly network:
   * .-----> b -------.
   * |                |
   * a ----> c -----> e
   * |                |
   * '-----> d -------'
   * where a has multiple channels with each of his peers.
   */

  val priv_a :: priv_b :: priv_c :: priv_d :: priv_e :: Nil = Seq.fill(5)(randomKey())
  val a :: b :: c :: d :: e :: Nil = Seq(priv_a, priv_b, priv_c, priv_d, priv_e).map(_.publicKey)
  val channelId_ab_1 = ShortChannelId(1)
  val channelId_ab_2 = ShortChannelId(2)
  val channelId_be = ShortChannelId(3)
  val channelId_ac_1 = ShortChannelId(11)
  val channelId_ac_2 = ShortChannelId(12)
  val channelId_ce = ShortChannelId(13)
  val channelId_ad = ShortChannelId(21)
  val channelId_de = ShortChannelId(22)
  val defaultChannelUpdate = ChannelUpdate(randomBytes64(), Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags.DUMMY, CltvExpiryDelta(12), 1 msat, 100 msat, 0, 2_000_000 msat)
  val channelUpdate_ab_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ab_1)
  val channelUpdate_ab_2 = defaultChannelUpdate.copy(shortChannelId = channelId_ab_2)
  val channelUpdate_be = defaultChannelUpdate.copy(shortChannelId = channelId_be)
  val channelUpdate_ac_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_1)
  val channelUpdate_ac_2 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_2)
  val channelUpdate_ce = defaultChannelUpdate.copy(shortChannelId = channelId_ce)
  val channelUpdate_ad = defaultChannelUpdate.copy(shortChannelId = channelId_ad)
  val channelUpdate_de = defaultChannelUpdate.copy(shortChannelId = channelId_de)

  val hop_ab_1 = channelHopFromUpdate(a, b, channelUpdate_ab_1)
  val hop_ab_2 = channelHopFromUpdate(a, b, channelUpdate_ab_2)
  val hop_be = channelHopFromUpdate(b, e, channelUpdate_be)
  val hop_ac_1 = channelHopFromUpdate(a, c, channelUpdate_ac_1)
  val hop_ac_2 = channelHopFromUpdate(a, c, channelUpdate_ac_2)
  val hop_ce = channelHopFromUpdate(c, e, channelUpdate_ce)
  val hop_ad = channelHopFromUpdate(a, d, channelUpdate_ad)
  val hop_de = channelHopFromUpdate(d, e, channelUpdate_de)

  val recipientFeatures = Features(
    Features.VariableLengthOnion -> FeatureSupport.Mandatory,
    Features.PaymentSecret -> FeatureSupport.Mandatory,
    Features.BasicMultiPartPayment -> FeatureSupport.Optional,
  ).invoiceFeatures()
  val clearRecipient = ClearRecipient(e, recipientFeatures, finalAmount, expiry, ByteVector32.One)

}