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

import akka.actor.{ActorRef, Status}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{Block, Crypto}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.{ChannelFlags, ChannelUnavailable}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.{FailureSummary, FailureType, OutgoingPaymentStatus}
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle._
import fr.acinq.eclair.payment.send.PaymentError.RetryExhausted
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPaymentToRoute
import fr.acinq.eclair.router.RouteNotFound
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire._
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.{ByteVector, HexStringSyntax}

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
                          eventListener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val id = UUID.randomUUID()
    val cfg = SendPaymentConfig(id, id, Some("42"), paymentHash, finalAmount, finalRecipient, Upstream.Local(id), None, storeInDb = true, publishEvent = true, Nil)
    val nodeParams = TestConstants.Alice.nodeParams
    val (childPayFsm, router, sender, eventListener) = (TestProbe(), TestProbe(), TestProbe(), TestProbe())
    class TestMultiPartPaymentLifecycle extends MultiPartPaymentLifecycle(nodeParams, cfg, router.ref, TestProbe().ref) {
      override def spawnChildPaymentFsm(childId: UUID): ActorRef = childPayFsm.ref
    }
    val paymentHandler = TestFSMRef(new TestMultiPartPaymentLifecycle().asInstanceOf[MultiPartPaymentLifecycle])
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
    withFixture(test.toNoArgTest(FixtureParam(cfg, nodeParams, paymentHandler, router, sender, childPayFsm, eventListener)))
  }

  test("successful first attempt (single part)") { f =>
    import f._

    assert(payFsm.stateName === WAIT_FOR_PAYMENT_REQUEST)
    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, finalAmount, expiry, 1, routeParams = Some(routeParams.copy(randomize = true)))
    sender.send(payFsm, payment)

    router.expectMsg(RouteRequest(nodeParams.nodeId, e, finalAmount, maxFee, routeParams = Some(routeParams.copy(randomize = false)), allowMultiPart = true, paymentContext = Some(cfg.paymentContext)))
    assert(payFsm.stateName === WAIT_FOR_ROUTES)

    val singleRoute = Route(finalAmount, hop_ab_1 :: hop_be :: Nil)
    router.send(payFsm, RouteResponse(Seq(singleRoute)))
    val childPayment = childPayFsm.expectMsgType[SendPaymentToRoute]
    assert(childPayment.route === Right(singleRoute))
    assert(childPayment.finalPayload.expiry === expiry)
    assert(childPayment.finalPayload.paymentSecret === Some(payment.paymentSecret))
    assert(childPayment.finalPayload.amount === finalAmount)
    assert(childPayment.finalPayload.totalAmount === finalAmount)
    assert(payFsm.stateName === PAYMENT_IN_PROGRESS)

    val result = fulfillPendingPayments(f, 1)
    assert(result.amountWithFees === finalAmount + 100.msat)
    assert(result.trampolineFees === 0.msat)
    assert(result.nonTrampolineFees === 100.msat)
  }

  test("successful first attempt (multiple parts)") { f =>
    import f._

    assert(payFsm.stateName === WAIT_FOR_PAYMENT_REQUEST)
    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, 1200000 msat, expiry, 1, routeParams = Some(routeParams.copy(randomize = false)))
    sender.send(payFsm, payment)

    router.expectMsg(RouteRequest(nodeParams.nodeId, e, 1200000 msat, maxFee, routeParams = Some(routeParams.copy(randomize = false)), allowMultiPart = true, paymentContext = Some(cfg.paymentContext)))
    assert(payFsm.stateName === WAIT_FOR_ROUTES)

    val routes = Seq(
      Route(500000 msat, hop_ab_1 :: hop_be :: Nil),
      Route(700000 msat, hop_ac_1 :: hop_ce :: Nil),
    )
    router.send(payFsm, RouteResponse(routes))
    val childPayments = childPayFsm.expectMsgType[SendPaymentToRoute] :: childPayFsm.expectMsgType[SendPaymentToRoute] :: Nil
    assert(childPayments.map(_.route).toSet === routes.map(r => Right(r)).toSet)
    assert(childPayments.map(_.finalPayload.expiry).toSet === Set(expiry))
    assert(childPayments.map(_.finalPayload.paymentSecret.get).toSet === Set(payment.paymentSecret))
    assert(childPayments.map(_.finalPayload.amount).toSet === Set(500000 msat, 700000 msat))
    assert(childPayments.map(_.finalPayload.totalAmount).toSet === Set(1200000 msat))
    assert(payFsm.stateName === PAYMENT_IN_PROGRESS)

    val result = fulfillPendingPayments(f, 2)
    assert(result.amountWithFees === 1200200.msat)
    assert(result.trampolineFees === 200000.msat)
    assert(result.nonTrampolineFees === 200.msat)
  }

  test("send custom tlv records") { f =>
    import f._

    // We include a bunch of additional tlv records.
    val trampolineTlv = OnionTlv.TrampolineOnion(OnionRoutingPacket(0, ByteVector.fill(33)(0), ByteVector.fill(400)(0), randomBytes32))
    val userCustomTlv = GenericTlv(UInt64(561), hex"deadbeef")
    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, finalAmount + 1000.msat, expiry, 1, routeParams = Some(routeParams), additionalTlvs = Seq(trampolineTlv), userCustomTlvs = Seq(userCustomTlv))
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(500000 msat, hop_ab_1 :: hop_be :: Nil), Route(501000 msat, hop_ac_1 :: hop_ce :: Nil))))
    val childPayments = childPayFsm.expectMsgType[SendPaymentToRoute] :: childPayFsm.expectMsgType[SendPaymentToRoute] :: Nil
    childPayments.map(_.finalPayload.asInstanceOf[Onion.FinalTlvPayload]).foreach(p => {
      assert(p.records.get[OnionTlv.TrampolineOnion] === Some(trampolineTlv))
      assert(p.records.unknown.toSeq === Seq(userCustomTlv))
    })

    val result = fulfillPendingPayments(f, 2)
    assert(result.trampolineFees === 1000.msat)
  }

  test("successful retry") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, finalAmount, expiry, 3, routeParams = Some(routeParams))
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    val failingRoute = Route(finalAmount, hop_ab_1 :: hop_be :: Nil)
    router.send(payFsm, RouteResponse(Seq(failingRoute)))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectNoMsg(100 millis)

    val childId = payFsm.stateData.asInstanceOf[PaymentProgress].pending.keys.head
    childPayFsm.send(payFsm, PaymentFailed(childId, paymentHash, Seq(RemoteFailure(failingRoute.hops, Sphinx.DecryptedFailurePacket(b, PermanentChannelFailure)))))
    // We retry ignoring the failing channel.
    router.expectMsg(RouteRequest(nodeParams.nodeId, e, finalAmount, maxFee, routeParams = Some(routeParams.copy(randomize = true)), allowMultiPart = true, ignore = Ignore(Set.empty, Set(ChannelDesc(channelId_be, b, e))), paymentContext = Some(cfg.paymentContext)))
    router.send(payFsm, RouteResponse(Seq(Route(400000 msat, hop_ac_1 :: hop_ce :: Nil), Route(600000 msat, hop_ad :: hop_de :: Nil))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]
    assert(!payFsm.stateData.asInstanceOf[PaymentProgress].pending.contains(childId))

    val result = fulfillPendingPayments(f, 2)
    assert(result.amountWithFees === 1000200.msat)
    assert(result.trampolineFees === 0.msat)
    assert(result.nonTrampolineFees === 200.msat)
  }

  test("retry failures while waiting for routes") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, finalAmount, expiry, 3, routeParams = Some(routeParams))
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(400000 msat, hop_ab_1 :: hop_be :: Nil), Route(600000 msat, hop_ab_2 :: hop_be :: Nil))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectNoMsg(100 millis)

    val (failedId1, failedRoute1) :: (failedId2, failedRoute2) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentFailed(failedId1, paymentHash, Seq(RemoteFailure(failedRoute1.hops, Sphinx.DecryptedFailurePacket(b, TemporaryNodeFailure)))))

    // When we retry, we ignore the failing node and we let the router know about the remaining pending route.
    router.expectMsg(RouteRequest(nodeParams.nodeId, e, failedRoute1.amount, maxFee - failedRoute1.fee, ignore = Ignore(Set(b), Set.empty), pendingPayments = Seq(failedRoute2), allowMultiPart = true, routeParams = Some(routeParams.copy(randomize = true)), paymentContext = Some(cfg.paymentContext)))
    // The second part fails while we're still waiting for new routes.
    childPayFsm.send(payFsm, PaymentFailed(failedId2, paymentHash, Seq(RemoteFailure(failedRoute2.hops, Sphinx.DecryptedFailurePacket(b, TemporaryNodeFailure)))))
    // We receive a response to our first request, but it's now obsolete: we re-sent a new route request that takes into
    // account the latest failures.
    router.send(payFsm, RouteResponse(Seq(Route(failedRoute1.amount, hop_ac_1 :: hop_ce :: Nil))))
    router.expectMsg(RouteRequest(nodeParams.nodeId, e, finalAmount, maxFee, ignore = Ignore(Set(b), Set.empty), allowMultiPart = true, routeParams = Some(routeParams.copy(randomize = true)), paymentContext = Some(cfg.paymentContext)))
    awaitCond(payFsm.stateData.asInstanceOf[PaymentProgress].pending.isEmpty)
    childPayFsm.expectNoMsg(100 millis)

    // We receive new routes that work.
    router.send(payFsm, RouteResponse(Seq(Route(300000 msat, hop_ac_1 :: hop_ce :: Nil), Route(700000 msat, hop_ad :: hop_de :: Nil))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val result = fulfillPendingPayments(f, 2)
    assert(result.amountWithFees === 1000200.msat)
    assert(result.nonTrampolineFees === 200.msat)
  }

  test("retry without ignoring channels") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, finalAmount, expiry, 3, routeParams = Some(routeParams))
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(500000 msat, hop_ab_1 :: hop_be :: Nil), Route(500000 msat, hop_ab_1 :: hop_be :: Nil))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectNoMsg(100 millis)

    val (failedId, failedRoute) :: (_, pendingRoute) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentFailed(failedId, paymentHash, Seq(LocalFailure(failedRoute.hops, ChannelUnavailable(randomBytes32)))))

    // If the router doesn't find routes, we will retry without ignoring the channel: it may work with a different split
    // of the amount to send.
    val expectedRouteRequest = RouteRequest(
      nodeParams.nodeId, e,
      failedRoute.amount, maxFee - failedRoute.fee,
      ignore = Ignore(Set.empty, Set(ChannelDesc(channelId_ab_1, a, b))),
      pendingPayments = Seq(pendingRoute),
      allowMultiPart = true,
      routeParams = Some(routeParams.copy(randomize = true)),
      paymentContext = Some(cfg.paymentContext))
    router.expectMsg(expectedRouteRequest)
    router.send(payFsm, Status.Failure(RouteNotFound))
    router.expectMsg(expectedRouteRequest.copy(ignore = Ignore.empty))

    router.send(payFsm, RouteResponse(Seq(Route(500000 msat, hop_ac_1 :: hop_ce :: Nil))))
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val result = fulfillPendingPayments(f, 2)
    assert(result.amountWithFees === 1000200.msat)
  }

  test("abort after too many failed attempts") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, finalAmount, expiry, 2, routeParams = Some(routeParams))
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(500000 msat, hop_ab_1 :: hop_be :: Nil), Route(500000 msat, hop_ac_1 :: hop_ce :: Nil))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (failedId1, failedRoute1) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head
    childPayFsm.send(payFsm, PaymentFailed(failedId1, paymentHash, Seq(UnreadableRemoteFailure(failedRoute1.hops))))
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(500000 msat, hop_ad :: hop_de :: Nil))))
    childPayFsm.expectMsgType[SendPaymentToRoute]

    assert(!payFsm.stateData.asInstanceOf[PaymentProgress].pending.contains(failedId1))
    val (failedId2, failedRoute2) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head
    val result = abortAfterFailure(f, PaymentFailed(failedId2, paymentHash, Seq(UnreadableRemoteFailure(failedRoute2.hops))))
    assert(result.failures.length >= 3)
    assert(result.failures.contains(LocalFailure(Nil, RetryExhausted)))
  }

  test("abort if no routes found") { f =>
    import f._

    sender.watch(payFsm)
    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, finalAmount, expiry, 5, routeParams = Some(routeParams))
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, Status.Failure(RouteNotFound))

    val result = sender.expectMsgType[PaymentFailed]
    assert(result.id === cfg.id)
    assert(result.paymentHash === paymentHash)
    assert(result.failures === Seq(LocalFailure(Nil, RouteNotFound)))

    val Some(outgoing) = nodeParams.db.payments.getOutgoingPayment(cfg.id)
    assert(outgoing.status.isInstanceOf[OutgoingPaymentStatus.Failed])
    assert(outgoing.status.asInstanceOf[OutgoingPaymentStatus.Failed].failures === Seq(FailureSummary(FailureType.LOCAL, RouteNotFound.getMessage, Nil)))

    sender.expectTerminated(payFsm)
    sender.expectNoMsg(100 millis)
    router.expectNoMsg(100 millis)
    childPayFsm.expectNoMsg(100 millis)
  }

  test("abort if recipient sends error") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, finalAmount, expiry, 5, routeParams = Some(routeParams))
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(finalAmount, hop_ab_1 :: hop_be :: Nil))))
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (failedId, failedRoute) = payFsm.stateData.asInstanceOf[PaymentProgress].pending.head
    val result = abortAfterFailure(f, PaymentFailed(failedId, paymentHash, Seq(RemoteFailure(failedRoute.hops, Sphinx.DecryptedFailurePacket(e, IncorrectOrUnknownPaymentDetails(600000 msat, 0))))))
    assert(result.failures.length === 1)
  }

  test("abort if recipient sends error during retry") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, finalAmount, expiry, 5, routeParams = Some(routeParams))
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(400000 msat, hop_ab_1 :: hop_be :: Nil), Route(600000 msat, hop_ac_1 :: hop_ce :: Nil))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (failedId1, failedRoute1) :: (failedId2, failedRoute2) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentFailed(failedId1, paymentHash, Seq(UnreadableRemoteFailure(failedRoute1.hops))))
    router.expectMsgType[RouteRequest]

    val result = abortAfterFailure(f, PaymentFailed(failedId2, paymentHash, Seq(RemoteFailure(failedRoute2.hops, Sphinx.DecryptedFailurePacket(e, PaymentTimeout)))))
    assert(result.failures.length === 2)
  }

  test("receive partial success after retriable failure (recipient spec violation)") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, finalAmount, expiry, 5, routeParams = Some(routeParams))
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(400000 msat, hop_ab_1 :: hop_be :: Nil), Route(600000 msat, hop_ac_1 :: hop_ce :: Nil))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (failedId, failedRoute) :: (successId, successRoute) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentFailed(failedId, paymentHash, Seq(UnreadableRemoteFailure(failedRoute.hops))))
    router.expectMsgType[RouteRequest]

    val result = fulfillPendingPayments(f, 1)
    assert(result.amountWithFees < finalAmount) // we got the preimage without paying the full amount
    assert(result.nonTrampolineFees === successRoute.fee) // we paid the fee for only one of the partial payments
    assert(result.parts.length === 1 && result.parts.head.id === successId)
  }

  test("receive partial success after abort (recipient spec violation)") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, finalAmount, expiry, 5, routeParams = Some(routeParams))
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(400000 msat, hop_ab_1 :: hop_be :: Nil), Route(600000 msat, hop_ac_1 :: hop_ce :: Nil))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (failedId, failedRoute) :: (successId, successRoute) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentFailed(failedId, paymentHash, Seq(RemoteFailure(failedRoute.hops, Sphinx.DecryptedFailurePacket(e, PaymentTimeout)))))
    awaitCond(payFsm.stateName === PAYMENT_ABORTED)

    sender.watch(payFsm)
    childPayFsm.send(payFsm, PaymentSent(cfg.id, paymentHash, paymentPreimage, finalAmount, e, Seq(PaymentSent.PartialPayment(successId, successRoute.amount, successRoute.fee, randomBytes32, Some(successRoute.hops)))))
    sender.expectMsg(PreimageReceived(paymentHash, paymentPreimage))
    val result = sender.expectMsgType[PaymentSent]
    assert(result.id === cfg.id)
    assert(result.paymentHash === paymentHash)
    assert(result.paymentPreimage === paymentPreimage)
    assert(result.parts.length === 1 && result.parts.head.id === successId)
    assert(result.recipientAmount === finalAmount)
    assert(result.recipientNodeId === finalRecipient)
    assert(result.amountWithFees < finalAmount) // we got the preimage without paying the full amount
    assert(result.nonTrampolineFees === successRoute.fee) // we paid the fee for only one of the partial payments

    sender.expectTerminated(payFsm)
    sender.expectNoMsg(100 millis)
    router.expectNoMsg(100 millis)
    childPayFsm.expectNoMsg(100 millis)
  }

  test("receive partial failure after success (recipient spec violation)") { f =>
    import f._

    val payment = SendMultiPartPayment(sender.ref, randomBytes32, e, finalAmount, expiry, 5, routeParams = Some(routeParams))
    sender.send(payFsm, payment)
    router.expectMsgType[RouteRequest]
    router.send(payFsm, RouteResponse(Seq(Route(400000 msat, hop_ab_1 :: hop_be :: Nil), Route(600000 msat, hop_ac_1 :: hop_ce :: Nil))))
    childPayFsm.expectMsgType[SendPaymentToRoute]
    childPayFsm.expectMsgType[SendPaymentToRoute]

    val (childId, route) :: (failedId, failedRoute) :: Nil = payFsm.stateData.asInstanceOf[PaymentProgress].pending.toSeq
    childPayFsm.send(payFsm, PaymentSent(cfg.id, paymentHash, paymentPreimage, finalAmount, e, Seq(PaymentSent.PartialPayment(childId, route.amount, route.fee, randomBytes32, Some(route.hops)))))
    sender.expectMsg(PreimageReceived(paymentHash, paymentPreimage))
    awaitCond(payFsm.stateName === PAYMENT_SUCCEEDED)

    sender.watch(payFsm)
    childPayFsm.send(payFsm, PaymentFailed(failedId, paymentHash, Seq(RemoteFailure(failedRoute.hops, Sphinx.DecryptedFailurePacket(e, PaymentTimeout)))))
    val result = sender.expectMsgType[PaymentSent]
    assert(result.parts.length === 1 && result.parts.head.id === childId)
    assert(result.amountWithFees < finalAmount) // we got the preimage without paying the full amount
    assert(result.nonTrampolineFees === route.fee) // we paid the fee for only one of the partial payments

    sender.expectTerminated(payFsm)
    sender.expectNoMsg(100 millis)
    router.expectNoMsg(100 millis)
    childPayFsm.expectNoMsg(100 millis)
  }

  def fulfillPendingPayments(f: FixtureParam, childCount: Int): PaymentSent = {
    import f._

    sender.watch(payFsm)
    val pending = payFsm.stateData.asInstanceOf[PaymentProgress].pending
    assert(pending.size === childCount)

    val partialPayments = pending.map {
      case (childId, route) => PaymentSent.PartialPayment(childId, route.amount, route.fee, randomBytes32, Some(route.hops))
    }
    partialPayments.foreach(pp => childPayFsm.send(payFsm, PaymentSent(cfg.id, paymentHash, paymentPreimage, finalAmount, e, Seq(pp))))
    sender.expectMsg(PreimageReceived(paymentHash, paymentPreimage))
    val result = sender.expectMsgType[PaymentSent]
    assert(result.id === cfg.id)
    assert(result.paymentHash === paymentHash)
    assert(result.paymentPreimage === paymentPreimage)
    assert(result.parts.toSet === partialPayments.toSet)
    assert(result.recipientAmount === finalAmount)
    assert(result.recipientNodeId === finalRecipient)

    sender.expectTerminated(payFsm)
    sender.expectNoMsg(100 millis)
    router.expectNoMsg(100 millis)
    childPayFsm.expectNoMsg(100 millis)

    result
  }

  def abortAfterFailure(f: FixtureParam, childFailure: PaymentFailed): PaymentFailed = {
    import f._

    sender.watch(payFsm)
    val pendingCount = payFsm.stateData.asInstanceOf[PaymentProgress].pending.size
    childPayFsm.send(payFsm, childFailure) // this failure should trigger an abort
    if (pendingCount > 1) {
      awaitCond(payFsm.stateName === PAYMENT_ABORTED)
      assert(payFsm.stateData.asInstanceOf[PaymentAborted].pending.size === pendingCount - 1)
      // Fail all remaining child payments.
      payFsm.stateData.asInstanceOf[PaymentAborted].pending.foreach(childId =>
        childPayFsm.send(payFsm, PaymentFailed(childId, paymentHash, Seq(RemoteFailure(hop_ab_1 :: hop_be :: Nil, Sphinx.DecryptedFailurePacket(e, PaymentTimeout)))))
      )
    }

    val result = sender.expectMsgType[PaymentFailed]
    assert(result.id === cfg.id)
    assert(result.paymentHash === paymentHash)
    assert(result.failures.nonEmpty)

    sender.expectTerminated(payFsm)
    sender.expectNoMsg(100 millis)
    router.expectNoMsg(100 millis)
    childPayFsm.expectNoMsg(100 millis)

    result
  }

}

object MultiPartPaymentLifecycleSpec {

  val paymentPreimage = randomBytes32
  val paymentHash = Crypto.sha256(paymentPreimage)
  val expiry = CltvExpiry(1105)
  val finalAmount = 1000000 msat
  val finalRecipient = randomKey.publicKey
  val routeParams = RouteParams(randomize = false, 15000 msat, 0.01, 6, CltvExpiryDelta(1008), None, MultiPartParams(1000 msat, 5))
  val maxFee = 15000 msat // max fee for the defaultAmount

  /**
   * We simulate a multi-part-friendly network:
   * .-----> b -------.
   * |                |
   * a ----> c -----> e
   * |                |
   * '-----> d -------'
   * where a has multiple channels with each of his peers.
   */

  val a :: b :: c :: d :: e :: Nil = Seq.fill(5)(randomKey.publicKey)
  val channelId_ab_1 = ShortChannelId(1)
  val channelId_ab_2 = ShortChannelId(2)
  val channelId_be = ShortChannelId(3)
  val channelId_ac_1 = ShortChannelId(11)
  val channelId_ac_2 = ShortChannelId(12)
  val channelId_ce = ShortChannelId(13)
  val channelId_ad = ShortChannelId(21)
  val channelId_de = ShortChannelId(22)
  val defaultChannelUpdate = ChannelUpdate(randomBytes64, Block.RegtestGenesisBlock.hash, ShortChannelId(0), 0, 1, ChannelFlags.AnnounceChannel, CltvExpiryDelta(12), 1 msat, 100 msat, 0, Some(2000000 msat))
  val channelUpdate_ab_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ab_1)
  val channelUpdate_ab_2 = defaultChannelUpdate.copy(shortChannelId = channelId_ab_2)
  val channelUpdate_be = defaultChannelUpdate.copy(shortChannelId = channelId_be)
  val channelUpdate_ac_1 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_1)
  val channelUpdate_ac_2 = defaultChannelUpdate.copy(shortChannelId = channelId_ac_2)
  val channelUpdate_ce = defaultChannelUpdate.copy(shortChannelId = channelId_ce)
  val channelUpdate_ad = defaultChannelUpdate.copy(shortChannelId = channelId_ad)
  val channelUpdate_de = defaultChannelUpdate.copy(shortChannelId = channelId_de)

  val hop_ab_1 = ChannelHop(a, b, channelUpdate_ab_1)
  val hop_ab_2 = ChannelHop(a, b, channelUpdate_ab_2)
  val hop_be = ChannelHop(b, e, channelUpdate_be)
  val hop_ac_1 = ChannelHop(a, c, channelUpdate_ac_1)
  val hop_ac_2 = ChannelHop(a, c, channelUpdate_ac_2)
  val hop_ce = ChannelHop(c, e, channelUpdate_ce)
  val hop_ad = ChannelHop(a, d, channelUpdate_ad)
  val hop_de = ChannelHop(d, e, channelUpdate_de)

}