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

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{ActorContext, ActorRef, typed}
import akka.testkit.{TestActorRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.Block
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.UInt64.Conversions._
import fr.acinq.eclair.channel.Upstream
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment.PaymentPacketSpec._
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment.send.BlindedPathsResolver.{FullBlindedRoute, ResolvedPath}
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.SendMultiPartPayment
import fr.acinq.eclair.payment.send.PaymentError.UnsupportedFeatures
import fr.acinq.eclair.payment.send.PaymentInitiator._
import fr.acinq.eclair.payment.send._
import fr.acinq.eclair.router.BlindedRouteCreation
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Bolt11Feature, Bolt12Feature, CltvExpiry, CltvExpiryDelta, EncodedNodeId, Feature, Features, MilliSatoshiLong, NodeParams, PaymentFinalExpiryConf, TestConstants, TestKitBaseClass, TimestampSecond, UnknownFeature, randomBytes32, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.{ByteVector, HexStringSyntax}

import java.util.UUID
import scala.concurrent.duration._

/**
 * Created by t-bast on 25/07/2019.
 */

class PaymentInitiatorSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike {

  object Tags {
    val DisableMPP = "mpp_disabled"
    val RandomizeFinalExpiry = "random_final_expiry"
  }

  case class FixtureParam(nodeParams: NodeParams, initiator: TestActorRef[PaymentInitiator], payFsm: TestProbe, trampolinePayFsm: TestProbe, multiPartPayFsm: TestProbe, sender: TestProbe, eventListener: TestProbe)

  val featuresWithoutMpp: Features[Bolt11Feature] = Features(
    VariableLengthOnion -> Mandatory,
    PaymentSecret -> Mandatory,
  )

  val featuresWithMpp: Features[Bolt11Feature] = Features(
    VariableLengthOnion -> Mandatory,
    PaymentSecret -> Mandatory,
    BasicMultiPartPayment -> Optional,
  )

  val featuresWithTrampoline: Features[Bolt11Feature] = Features(
    VariableLengthOnion -> Mandatory,
    PaymentSecret -> Mandatory,
    BasicMultiPartPayment -> Optional,
    TrampolinePaymentPrototype -> Optional,
  )

  case class FakePaymentFactory(payFsm: TestProbe, trampolinePayFsm: TestProbe, multiPartPayFsm: TestProbe) extends PaymentInitiator.MultiPartPaymentFactory {
    // @formatter:off
    override def spawnOutgoingPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef = {
      payFsm.ref ! cfg
      payFsm.ref
    }
    override def spawnOutgoingMultiPartPayment(context: ActorContext, cfg: SendPaymentConfig, publishPreimage: Boolean): ActorRef = {
      multiPartPayFsm.ref ! cfg
      multiPartPayFsm.ref
    }
    override def spawnOutgoingTrampolinePayment(context:  ActorContext): typed.ActorRef[TrampolinePaymentLifecycle.Command] = trampolinePayFsm.ref.toTyped
    // @formatter:on
  }

  override def withFixture(test: OneArgTest): Outcome = {
    val features = if (test.tags.contains(Tags.DisableMPP)) {
      featuresWithoutMpp
    } else {
      featuresWithMpp
    }
    val paymentFinalExpiry = if (test.tags.contains(Tags.RandomizeFinalExpiry)) {
      PaymentFinalExpiryConf(CltvExpiryDelta(50), CltvExpiryDelta(200))
    } else {
      PaymentFinalExpiryConf(CltvExpiryDelta(1), CltvExpiryDelta(1))
    }
    val nodeParams = TestConstants.Alice.nodeParams.copy(features = features.unscoped(), paymentFinalExpiry = paymentFinalExpiry)
    val (sender, payFsm, trampolinePayFsm, multiPartPayFsm) = (TestProbe(), TestProbe(), TestProbe(), TestProbe())
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
    val initiator = TestActorRef(new PaymentInitiator(nodeParams, FakePaymentFactory(payFsm, trampolinePayFsm, multiPartPayFsm)))
    withFixture(test.toNoArgTest(FixtureParam(nodeParams, initiator, payFsm, trampolinePayFsm, multiPartPayFsm, sender, eventListener)))
  }

  test("forward payment with user custom tlv records") { f =>
    import f._
    val customRecords = Set(GenericTlv(500L, hex"01020304"), GenericTlv(501L, hex"d34db33f"))
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, None, paymentHash, priv_c.privateKey, Left("test"), Channel.MIN_CLTV_EXPIRY_DELTA)
    val req = SendPaymentToNode(sender.ref, finalAmount, invoice, Nil, 1, userCustomTlvs = customRecords, routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams)
    sender.send(initiator, req)
    sender.expectMsgType[UUID]
    payFsm.expectMsgType[SendPaymentConfig]
    val payment = payFsm.expectMsgType[PaymentLifecycle.SendPayment]
    assert(payment.amount == finalAmount)
    assert(payment.recipient.nodeId == invoice.nodeId)
    assert(payment.recipient.totalAmount == finalAmount)
    assert(payment.recipient.expiry == invoice.minFinalCltvExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight + 1))
    assert(payment.recipient.isInstanceOf[ClearRecipient])
    assert(payment.recipient.asInstanceOf[ClearRecipient].customTlvs == customRecords)
  }

  test("forward keysend payment") { f =>
    import f._
    val req = SendSpontaneousPayment(finalAmount, c, paymentPreimage, 1, routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams)
    sender.send(initiator, req)
    sender.expectMsgType[UUID]
    payFsm.expectMsgType[SendPaymentConfig]
    val payment = payFsm.expectMsgType[PaymentLifecycle.SendPayment]
    assert(payment.amount == finalAmount)
    assert(payment.recipient.nodeId == c)
    assert(payment.recipient.totalAmount == finalAmount)
    assert(payment.recipient.expiry == Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(nodeParams.currentBlockHeight + 1))
    assert(payment.recipient.isInstanceOf[SpontaneousRecipient])
    assert(payment.recipient.asInstanceOf[SpontaneousRecipient].preimage == paymentPreimage)
  }

  test("reject payment with unsupported mandatory feature") { f =>
    import f._
    val testCases: Seq[Features[Feature]] = Seq(
      Features(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, PaymentMetadata -> Mandatory),
      Features(Map(VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory), unknown = Set(UnknownFeature(42))),
    )
    testCases.foreach { invoiceFeatures =>
      val taggedFields = List(
        Bolt11Invoice.PaymentHash(paymentHash),
        Bolt11Invoice.Description("Some invoice"),
        Bolt11Invoice.PaymentSecret(randomBytes32()),
        Bolt11Invoice.Expiry(3600),
        Bolt11Invoice.InvoiceFeatures(invoiceFeatures)
      )
      val invoice = Bolt11Invoice("lnbc", Some(finalAmount), TimestampSecond.now(), randomKey().publicKey, taggedFields, ByteVector.empty)
      val req = SendPaymentToNode(sender.ref, finalAmount + 100.msat, invoice, Nil, 1, routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams)
      sender.send(initiator, req)
      val id = sender.expectMsgType[UUID]
      val fail = sender.expectMsgType[PaymentFailed]
      assert(fail.id == id)
      assert(fail.failures.head.isInstanceOf[LocalFailure])
      assert(fail.failures.head.asInstanceOf[LocalFailure].t == UnsupportedFeatures(invoice.features))
    }
  }

  test("forward payment with pre-defined route") { f =>
    import f._
    val finalExpiryDelta = CltvExpiryDelta(36)
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some invoice"), finalExpiryDelta)
    val route = PredefinedNodeRoute(finalAmount, Seq(a, b, c))
    val request = SendPaymentToRoute(finalAmount, invoice, Nil, route, None, None)
    sender.send(initiator, request)
    val payment = sender.expectMsgType[SendPaymentToRouteResponse]
    payFsm.expectMsg(SendPaymentConfig(payment.paymentId, payment.parentId, None, paymentHash, c, Upstream.Local(payment.paymentId), Some(invoice), None, storeInDb = true, publishEvent = true, recordPathFindingMetrics = false))
    payFsm.expectMsg(PaymentLifecycle.SendPaymentToRoute(initiator, Left(route), ClearRecipient(invoice, finalAmount, finalExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight + 1), Set.empty)))

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentUUID(payment.paymentId)))
    sender.expectMsg(PaymentIsPending(payment.paymentId, invoice.paymentHash, PendingPaymentToRoute(sender.ref, request)))
    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentHash(invoice.paymentHash)))
    sender.expectMsg(PaymentIsPending(payment.paymentId, invoice.paymentHash, PendingPaymentToRoute(sender.ref, request)))

    val pf = PaymentFailed(payment.paymentId, invoice.paymentHash, Nil)
    payFsm.send(initiator, pf)
    sender.expectMsg(pf)
    eventListener.expectNoMessage(100 millis)

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentHash(invoice.paymentHash)))
    sender.expectMsg(NoPendingPayment(PaymentIdentifier.PaymentHash(invoice.paymentHash)))
  }

  test("forward single-part payment when multi-part deactivated", Tag(Tags.DisableMPP)) { f =>
    import f._
    val finalExpiryDelta = CltvExpiryDelta(24)
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some MPP invoice"), finalExpiryDelta, features = featuresWithMpp)
    val req = SendPaymentToNode(sender.ref, finalAmount, invoice, Nil, 1, routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams)
    assert(req.finalExpiry(nodeParams) == (finalExpiryDelta + 1).toCltvExpiry(nodeParams.currentBlockHeight))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    payFsm.expectMsg(SendPaymentConfig(id, id, None, paymentHash, c, Upstream.Local(id), Some(invoice), None, storeInDb = true, publishEvent = true, recordPathFindingMetrics = true))
    payFsm.expectMsg(PaymentLifecycle.SendPaymentToNode(initiator, ClearRecipient(invoice, finalAmount, req.finalExpiry(nodeParams), Set.empty), 1, nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams))

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentUUID(id)))
    sender.expectMsg(PaymentIsPending(id, invoice.paymentHash, PendingPaymentToNode(sender.ref, req)))
    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentHash(invoice.paymentHash)))
    sender.expectMsg(PaymentIsPending(id, invoice.paymentHash, PendingPaymentToNode(sender.ref, req)))

    val pf = PaymentFailed(id, invoice.paymentHash, Nil)
    payFsm.send(initiator, pf)
    sender.expectMsg(pf)
    eventListener.expectNoMessage(100 millis)

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentUUID(id)))
    sender.expectMsg(NoPendingPayment(PaymentIdentifier.PaymentUUID(id)))
  }

  test("forward multi-part payment") { f =>
    import f._
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some invoice"), CltvExpiryDelta(18), features = featuresWithMpp)
    val req = SendPaymentToNode(sender.ref, finalAmount + 100.msat, invoice, Nil, 1, routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams)
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    multiPartPayFsm.expectMsg(SendPaymentConfig(id, id, None, paymentHash, c, Upstream.Local(id), Some(invoice), None, storeInDb = true, publishEvent = true, recordPathFindingMetrics = true))
    multiPartPayFsm.expectMsg(SendMultiPartPayment(initiator, ClearRecipient(invoice, finalAmount + 100.msat, req.finalExpiry(nodeParams), Set.empty), 1, nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams))

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentUUID(id)))
    sender.expectMsg(PaymentIsPending(id, invoice.paymentHash, PendingPaymentToNode(sender.ref, req)))
    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentHash(invoice.paymentHash)))
    sender.expectMsg(PaymentIsPending(id, invoice.paymentHash, PendingPaymentToNode(sender.ref, req)))

    val ps = PaymentSent(id, invoice.paymentHash, randomBytes32(), finalAmount, priv_c.publicKey, Seq(PartialPayment(UUID.randomUUID(), finalAmount, 0 msat, randomBytes32(), None)), None)
    payFsm.send(initiator, ps)
    sender.expectMsg(ps)
    eventListener.expectNoMessage(100 millis)

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentUUID(id)))
    sender.expectMsg(NoPendingPayment(PaymentIdentifier.PaymentUUID(id)))
  }

  test("forward multi-part payment with randomized final expiry", Tag(Tags.RandomizeFinalExpiry)) { f =>
    import f._
    val invoiceFinalExpiryDelta = CltvExpiryDelta(6)
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some invoice"), invoiceFinalExpiryDelta, features = featuresWithMpp)
    val req = SendPaymentToNode(sender.ref, finalAmount, invoice, Nil, 1, routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams)
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    multiPartPayFsm.expectMsg(SendPaymentConfig(id, id, None, paymentHash, c, Upstream.Local(id), Some(invoice), None, storeInDb = true, publishEvent = true, recordPathFindingMetrics = true))
    val payment = multiPartPayFsm.expectMsgType[SendMultiPartPayment]
    val expiry = payment.recipient.asInstanceOf[ClearRecipient].expiry
    assert(nodeParams.currentBlockHeight + invoiceFinalExpiryDelta.toInt + 50 <= expiry.blockHeight)
    assert(expiry.blockHeight <= nodeParams.currentBlockHeight + invoiceFinalExpiryDelta.toInt + 200)
  }

  test("forward multi-part payment with pre-defined route") { f =>
    import f._
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some invoice"), CltvExpiryDelta(18), features = featuresWithMpp)
    val route = PredefinedChannelRoute(finalAmount / 2, c, Seq(channelUpdate_ab.shortChannelId, channelUpdate_bc.shortChannelId))
    val req = SendPaymentToRoute(finalAmount, invoice, Nil, route, None, None)
    sender.send(initiator, req)
    val payment = sender.expectMsgType[SendPaymentToRouteResponse]
    payFsm.expectMsg(SendPaymentConfig(payment.paymentId, payment.parentId, None, paymentHash, c, Upstream.Local(payment.paymentId), Some(invoice), None, storeInDb = true, publishEvent = true, recordPathFindingMetrics = false))
    val msg = payFsm.expectMsgType[PaymentLifecycle.SendPaymentToRoute]
    assert(msg.replyTo == initiator)
    assert(msg.route == Left(route))
    assert(msg.amount == finalAmount / 2)
    assert(msg.recipient.nodeId == c)
    assert(msg.recipient.totalAmount == finalAmount)
    assert(msg.recipient.expiry == req.finalExpiry(nodeParams))

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentUUID(payment.paymentId)))
    sender.expectMsg(PaymentIsPending(payment.paymentId, invoice.paymentHash, PendingPaymentToRoute(sender.ref, req)))
    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentHash(invoice.paymentHash)))
    sender.expectMsg(PaymentIsPending(payment.paymentId, invoice.paymentHash, PendingPaymentToRoute(sender.ref, req)))

    val pf = PaymentFailed(payment.paymentId, invoice.paymentHash, Nil)
    payFsm.send(initiator, pf)
    sender.expectMsg(pf)
    eventListener.expectNoMessage(100 millis)

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentHash(invoice.paymentHash)))
    sender.expectMsg(NoPendingPayment(PaymentIdentifier.PaymentHash(invoice.paymentHash)))
  }

  def createBolt12Invoice(features: Features[Bolt12Feature], payerKey: PrivateKey): Bolt12Invoice = {
    val offer = Offer(None, Some("Bolt12 r0cks"), e, features, Block.RegtestGenesisBlock.hash)
    val invoiceRequest = InvoiceRequest(offer, finalAmount, 1, features, payerKey, Block.RegtestGenesisBlock.hash)
    val blindedRoute = BlindedRouteCreation.createBlindedRouteFromHops(Nil, e, hex"2a2a2a2a", 1 msat, CltvExpiry(500_000)).route
    val paymentInfo = OfferTypes.PaymentInfo(1_000 msat, 0, CltvExpiryDelta(24), 0 msat, finalAmount, ByteVector.empty)
    Bolt12Invoice(invoiceRequest, paymentPreimage, priv_e.privateKey, 300 seconds, features, Seq(PaymentBlindedRoute(blindedRoute, paymentInfo)))
  }

  test("forward single-part blinded payment") { f =>
    import f._
    val payerKey = randomKey()
    val invoice = createBolt12Invoice(Features.empty, payerKey)
    val resolvedPaths = invoice.blindedPaths.map(path => {
      val introductionNodeId = path.route.firstNodeId.asInstanceOf[EncodedNodeId.WithPublicKey].publicKey
      ResolvedPath(FullBlindedRoute(introductionNodeId, path.route.firstPathKey, path.route.blindedHops), path.paymentInfo)
    })
    val req = SendPaymentToNode(sender.ref, finalAmount, invoice, resolvedPaths, 1, routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams, payerKey_opt = Some(payerKey))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    payFsm.expectMsg(SendPaymentConfig(id, id, None, paymentHash, invoice.nodeId, Upstream.Local(id), Some(invoice), Some(payerKey), storeInDb = true, publishEvent = true, recordPathFindingMetrics = true))
    val payment = payFsm.expectMsgType[PaymentLifecycle.SendPaymentToNode]
    assert(payment.amount == finalAmount)
    assert(payment.recipient.nodeId == invoice.nodeId)
    assert(payment.recipient.totalAmount == finalAmount)
    assert(payment.recipient.extraEdges.nonEmpty)
    assert(payment.recipient.expiry == req.finalExpiry(nodeParams))
    assert(payment.recipient.isInstanceOf[BlindedRecipient])

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentUUID(id)))
    sender.expectMsg(PaymentIsPending(id, invoice.paymentHash, PendingPaymentToNode(sender.ref, req)))
    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentHash(invoice.paymentHash)))
    sender.expectMsg(PaymentIsPending(id, invoice.paymentHash, PendingPaymentToNode(sender.ref, req)))

    val pf = PaymentFailed(id, invoice.paymentHash, Nil)
    payFsm.send(initiator, pf)
    sender.expectMsg(pf)
    eventListener.expectNoMessage(100 millis)

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentUUID(id)))
    sender.expectMsg(NoPendingPayment(PaymentIdentifier.PaymentUUID(id)))
  }

  test("forward multi-part blinded payment") { f =>
    import f._
    val payerKey = randomKey()
    val invoice = createBolt12Invoice(Features(BasicMultiPartPayment -> Optional), payerKey)
    val resolvedPaths = invoice.blindedPaths.map(path => {
      val introductionNodeId = path.route.firstNodeId.asInstanceOf[EncodedNodeId.WithPublicKey].publicKey
      ResolvedPath(FullBlindedRoute(introductionNodeId, path.route.firstPathKey, path.route.blindedHops), path.paymentInfo)
    })
    val req = SendPaymentToNode(sender.ref, finalAmount, invoice, resolvedPaths, 1, routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams, payerKey_opt = Some(payerKey))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    multiPartPayFsm.expectMsg(SendPaymentConfig(id, id, None, paymentHash, invoice.nodeId, Upstream.Local(id), Some(invoice), Some(payerKey), storeInDb = true, publishEvent = true, recordPathFindingMetrics = true))
    val payment = multiPartPayFsm.expectMsgType[SendMultiPartPayment]
    assert(payment.recipient.nodeId == invoice.nodeId)
    assert(payment.recipient.totalAmount == finalAmount)
    assert(payment.recipient.extraEdges.nonEmpty)
    assert(payment.recipient.expiry == req.finalExpiry(nodeParams))
    assert(payment.recipient.isInstanceOf[BlindedRecipient])

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentUUID(id)))
    sender.expectMsg(PaymentIsPending(id, invoice.paymentHash, PendingPaymentToNode(sender.ref, req)))
    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentHash(invoice.paymentHash)))
    sender.expectMsg(PaymentIsPending(id, invoice.paymentHash, PendingPaymentToNode(sender.ref, req)))

    val ps = PaymentSent(id, invoice.paymentHash, paymentPreimage, finalAmount, invoice.nodeId, Seq(PartialPayment(UUID.randomUUID(), finalAmount, 0 msat, randomBytes32(), None)), None)
    payFsm.send(initiator, ps)
    sender.expectMsg(ps)
    eventListener.expectNoMessage(100 millis)

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentUUID(id)))
    sender.expectMsg(NoPendingPayment(PaymentIdentifier.PaymentUUID(id)))
  }

  test("forward trampoline payment") { f =>
    import f._
    val ignoredRoutingHints = List(List(ExtraHop(b, channelUpdate_bc.shortChannelId, feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12))))
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some phoenix invoice"), CltvExpiryDelta(9), features = featuresWithTrampoline, extraHops = ignoredRoutingHints)
    val req = SendTrampolinePayment(sender.ref, invoice, b, nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams)
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    assert(trampolinePayFsm.expectMsgType[TrampolinePaymentLifecycle.SendPayment].invoice == invoice)

    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentUUID(id)))
    sender.expectMsg(PaymentIsPending(id, invoice.paymentHash, PendingTrampolinePayment(sender.ref, req)))
    sender.send(initiator, GetPayment(PaymentIdentifier.PaymentHash(invoice.paymentHash)))
    sender.expectMsg(PaymentIsPending(id, invoice.paymentHash, PendingTrampolinePayment(sender.ref, req)))
  }

  test("forward trampoline to legacy payment") { f =>
    import f._
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some wallet invoice"), CltvExpiryDelta(9))
    val req = SendTrampolinePayment(sender.ref, invoice, b, routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams)
    sender.send(initiator, req)
    sender.expectMsgType[UUID]
    assert(trampolinePayFsm.expectMsgType[TrampolinePaymentLifecycle.SendPayment].invoice == invoice)
  }

  test("reject trampoline to legacy payment for 0-value invoice") { f =>
    import f._
    // This is disabled because it would let the trampoline node steal the whole payment (if malicious).
    val routingHints = List(List(Bolt11Invoice.ExtraHop(b, channelUpdate_bc.shortChannelId, 10 msat, 100, CltvExpiryDelta(144))))
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, None, paymentHash, priv_a.privateKey, Left("#abittooreckless"), CltvExpiryDelta(18), None, None, routingHints, features = featuresWithMpp)
    val req = SendTrampolinePayment(sender.ref, invoice, b, routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams)
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    val fail = sender.expectMsgType[PaymentFailed]
    assert(fail.id == id)
    assert(fail.failures.head.isInstanceOf[LocalFailure])
    trampolinePayFsm.expectNoMessage(50 millis)
  }

}
