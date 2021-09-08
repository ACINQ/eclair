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

import akka.actor.{ActorContext, ActorRef}
import akka.testkit.{TestActorRef, TestProbe}
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.UInt64.Conversions._
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.payment.PaymentPacketSpec._
import fr.acinq.eclair.payment.PaymentRequest.{ExtraHop, PaymentRequestFeatures}
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.SendMultiPartPayment
import fr.acinq.eclair.payment.send.PaymentInitiator._
import fr.acinq.eclair.payment.send.{PaymentError, PaymentInitiator, PaymentLifecycle}
import fr.acinq.eclair.router.{RouteCalculation, RouteNotFound}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol.Onion.FinalTlvPayload
import fr.acinq.eclair.wire.protocol.OnionTlv.{AmountToForward, KeySend, OutgoingCltv}
import fr.acinq.eclair.wire.protocol.{Onion, OnionCodecs, OnionTlv, TrampolineFeeInsufficient, _}
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshiLong, NodeParams, TestConstants, TestKitBaseClass, randomBytes32, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.HexStringSyntax

import java.util.UUID
import scala.concurrent.duration._

/**
 * Created by t-bast on 25/07/2019.
 */

class PaymentInitiatorSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, initiator: TestActorRef[PaymentInitiator], payFsm: TestProbe, multiPartPayFsm: TestProbe, sender: TestProbe, eventListener: TestProbe)

  val featuresWithoutMpp = Features(
    VariableLengthOnion -> Mandatory,
    PaymentSecret -> Mandatory
  )

  val featuresWithMpp = Features(
    VariableLengthOnion -> Mandatory,
    PaymentSecret -> Mandatory,
    BasicMultiPartPayment -> Optional,
  )

  case class FakePaymentFactory(payFsm: TestProbe, multiPartPayFsm: TestProbe) extends PaymentInitiator.MultiPartPaymentFactory {
    // @formatter:off
    override def spawnOutgoingPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef = {
      payFsm.ref ! cfg
      payFsm.ref
    }
    override def spawnOutgoingMultiPartPayment(context: ActorContext, cfg: SendPaymentConfig): ActorRef = {
      multiPartPayFsm.ref ! cfg
      multiPartPayFsm.ref
    }
    // @formatter:on
  }

  override def withFixture(test: OneArgTest): Outcome = {
    val features = if (test.tags.contains("mpp_disabled")) featuresWithoutMpp else featuresWithMpp
    val nodeParams = TestConstants.Alice.nodeParams.copy(features = features)
    val (sender, payFsm, multiPartPayFsm) = (TestProbe(), TestProbe(), TestProbe())
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
    val initiator = TestActorRef(new PaymentInitiator(nodeParams, FakePaymentFactory(payFsm, multiPartPayFsm)))
    withFixture(test.toNoArgTest(FixtureParam(nodeParams, initiator, payFsm, multiPartPayFsm, sender, eventListener)))
  }

  test("forward payment with user custom tlv records") { f =>
    import f._
    val customRecords = Seq(GenericTlv(500L, hex"01020304"), GenericTlv(501L, hex"d34db33f"))
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, None, paymentHash, priv_c.privateKey, Left("test"), Channel.MIN_CLTV_EXPIRY_DELTA)
    val req = SendPaymentToNode(finalAmount, pr, 1, Channel.MIN_CLTV_EXPIRY_DELTA, userCustomTlvs = customRecords, routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get()))
    sender.send(initiator, req)
    sender.expectMsgType[UUID]
    payFsm.expectMsgType[SendPaymentConfig]
    val FinalTlvPayload(tlvs) = payFsm.expectMsgType[PaymentLifecycle.SendPayment].finalPayload
    assert(tlvs.get[AmountToForward].get.amount == finalAmount)
    assert(tlvs.get[OutgoingCltv].get.cltv == req.fallbackFinalExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight + 1))
    assert(tlvs.unknown == customRecords)
  }

  test("forward keysend payment") { f =>
    import f._
    val req = SendSpontaneousPayment(finalAmount, c, paymentPreimage, 1, routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get()))
    sender.send(initiator, req)
    sender.expectMsgType[UUID]
    payFsm.expectMsgType[SendPaymentConfig]
    val FinalTlvPayload(tlvs) = payFsm.expectMsgType[PaymentLifecycle.SendPayment].finalPayload
    assert(tlvs.get[AmountToForward].get.amount == finalAmount)
    assert(tlvs.get[OutgoingCltv].get.cltv == Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(nodeParams.currentBlockHeight + 1))
    assert(tlvs.get[KeySend].get.paymentPreimage == paymentPreimage)
    assert(tlvs.unknown.isEmpty)
  }

  test("reject payment with unknown mandatory feature") { f =>
    import f._
    val unknownFeature = 42
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, randomKey(), Left("Some invoice"), CltvExpiryDelta(18), features = PaymentRequestFeatures(Features.VariableLengthOnion.mandatory, Features.PaymentSecret.mandatory, unknownFeature))
    val req = SendPaymentToNode(finalAmount + 100.msat, pr, 1, CltvExpiryDelta(42), routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get()))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    val fail = sender.expectMsgType[PaymentFailed]
    assert(fail.id === id)
    assert(fail.failures.head.isInstanceOf[LocalFailure])
  }

  test("forward payment with pre-defined route") { f =>
    import f._
    // we prioritize the invoice's finalExpiryDelta over the one from SendPaymentToRouteRequest
    val ignoredFinalExpiryDelta = CltvExpiryDelta(18)
    val finalExpiryDelta = CltvExpiryDelta(36)
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some invoice"), finalExpiryDelta)
    val route = PredefinedNodeRoute(Seq(a, b, c))
    sender.send(initiator, SendPaymentToRoute(finalAmount, finalAmount, pr, ignoredFinalExpiryDelta, route, None, None, None, 0 msat, CltvExpiryDelta(0), Nil))
    val payment = sender.expectMsgType[SendPaymentToRouteResponse]
    payFsm.expectMsg(SendPaymentConfig(payment.paymentId, payment.parentId, None, paymentHash, finalAmount, c, Upstream.Local(payment.paymentId), Some(pr), storeInDb = true, publishEvent = true, recordMetrics = false, Nil))
    payFsm.expectMsg(PaymentLifecycle.SendPaymentToRoute(sender.ref, Left(route), Onion.createSinglePartPayload(finalAmount, finalExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight + 1), pr.paymentSecret.get)))
  }

  test("forward single-part payment when multi-part deactivated", Tag("mpp_disabled")) { f =>
    import f._
    val finalExpiryDelta = CltvExpiryDelta(24)
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some MPP invoice"), finalExpiryDelta, features = PaymentRequestFeatures(VariableLengthOnion.mandatory, PaymentSecret.mandatory, BasicMultiPartPayment.optional))
    val req = SendPaymentToNode(finalAmount, pr, 1, /* ignored since the invoice provides it */ CltvExpiryDelta(12), routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get()))
    assert(req.finalExpiry(nodeParams.currentBlockHeight) === (finalExpiryDelta + 1).toCltvExpiry(nodeParams.currentBlockHeight))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    payFsm.expectMsg(SendPaymentConfig(id, id, None, paymentHash, finalAmount, c, Upstream.Local(id), Some(pr), storeInDb = true, publishEvent = true, recordMetrics = true, Nil))
    payFsm.expectMsg(PaymentLifecycle.SendPaymentToNode(sender.ref, c, FinalTlvPayload(TlvStream(OnionTlv.AmountToForward(finalAmount), OnionTlv.OutgoingCltv(req.finalExpiry(nodeParams.currentBlockHeight)), OnionTlv.PaymentData(pr.paymentSecret.get, finalAmount))), 1, routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get())))
  }

  test("forward multi-part payment") { f =>
    import f._
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some invoice"), CltvExpiryDelta(18), features = PaymentRequestFeatures(VariableLengthOnion.mandatory, PaymentSecret.mandatory, BasicMultiPartPayment.optional))
    val req = SendPaymentToNode(finalAmount + 100.msat, pr, 1, CltvExpiryDelta(42), routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get()))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    multiPartPayFsm.expectMsg(SendPaymentConfig(id, id, None, paymentHash, finalAmount + 100.msat, c, Upstream.Local(id), Some(pr), storeInDb = true, publishEvent = true, recordMetrics = true, Nil))
    multiPartPayFsm.expectMsg(SendMultiPartPayment(sender.ref, pr.paymentSecret.get, c, finalAmount + 100.msat, req.finalExpiry(nodeParams.currentBlockHeight), 1, routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get())))
  }

  test("forward multi-part payment with pre-defined route") { f =>
    import f._
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some invoice"), CltvExpiryDelta(18), features = PaymentRequestFeatures(VariableLengthOnion.mandatory, PaymentSecret.mandatory, BasicMultiPartPayment.optional))
    val route = PredefinedChannelRoute(c, Seq(channelUpdate_ab.shortChannelId, channelUpdate_bc.shortChannelId))
    val req = SendPaymentToRoute(finalAmount / 2, finalAmount, pr, Channel.MIN_CLTV_EXPIRY_DELTA, route, None, None, None, 0 msat, CltvExpiryDelta(0), Nil)
    sender.send(initiator, req)
    val payment = sender.expectMsgType[SendPaymentToRouteResponse]
    payFsm.expectMsg(SendPaymentConfig(payment.paymentId, payment.parentId, None, paymentHash, finalAmount, c, Upstream.Local(payment.paymentId), Some(pr), storeInDb = true, publishEvent = true, recordMetrics = false, Nil))
    val msg = payFsm.expectMsgType[PaymentLifecycle.SendPaymentToRoute]
    assert(msg.route === Left(route))
    assert(msg.finalPayload.amount === finalAmount / 2)
    assert(msg.finalPayload.expiry === req.finalExpiry(nodeParams.currentBlockHeight))
    assert(msg.finalPayload.paymentSecret === pr.paymentSecret.get)
    assert(msg.finalPayload.totalAmount === finalAmount)
  }

  test("forward trampoline payment") { f =>
    import f._
    val features = PaymentRequestFeatures(VariableLengthOnion.mandatory, PaymentSecret.mandatory, BasicMultiPartPayment.optional, TrampolinePayment.optional)
    val ignoredRoutingHints = List(List(ExtraHop(b, channelUpdate_bc.shortChannelId, feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12))))
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some phoenix invoice"), CltvExpiryDelta(9), features = features, extraHops = ignoredRoutingHints)
    val trampolineFees = 21000 msat
    val req = SendTrampolinePayment(finalAmount, pr, b, Seq((trampolineFees, CltvExpiryDelta(12))), /* ignored since the invoice provides it */ CltvExpiryDelta(18), routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get()))
    sender.send(initiator, req)
    sender.expectMsgType[UUID]
    multiPartPayFsm.expectMsgType[SendPaymentConfig]

    val msg = multiPartPayFsm.expectMsgType[SendMultiPartPayment]
    assert(msg.paymentSecret !== pr.paymentSecret.get) // we should not leak the invoice secret to the trampoline node
    assert(msg.targetNodeId === b)
    assert(msg.targetExpiry.toLong === currentBlockCount + 9 + 12 + 1)
    assert(msg.totalAmount === finalAmount + trampolineFees)
    assert(msg.additionalTlvs.head.isInstanceOf[OnionTlv.TrampolineOnion])
    assert(msg.maxAttempts === nodeParams.maxPaymentAttempts)

    // Verify that the trampoline node can correctly peel the trampoline onion.
    val trampolineOnion = msg.additionalTlvs.head.asInstanceOf[OnionTlv.TrampolineOnion].packet
    val Right(decrypted) = Sphinx.TrampolinePacket.peel(priv_b.privateKey, pr.paymentHash, trampolineOnion)
    assert(!decrypted.isLastPacket)
    val trampolinePayload = OnionCodecs.nodeRelayPerHopPayloadCodec.decode(decrypted.payload.bits).require.value
    assert(trampolinePayload.amountToForward === finalAmount)
    assert(trampolinePayload.totalAmount === finalAmount)
    assert(trampolinePayload.outgoingCltv.toLong === currentBlockCount + 9 + 1)
    assert(trampolinePayload.outgoingNodeId === c)
    assert(trampolinePayload.paymentSecret === None) // we're not leaking the invoice secret to the trampoline node
    assert(trampolinePayload.invoiceRoutingInfo === None)
    assert(trampolinePayload.invoiceFeatures === None)

    // Verify that the recipient can correctly peel the trampoline onion.
    val Right(decrypted1) = Sphinx.TrampolinePacket.peel(priv_c.privateKey, pr.paymentHash, decrypted.nextPacket)
    assert(decrypted1.isLastPacket)
    val finalPayload = OnionCodecs.finalPerHopPayloadCodec.decode(decrypted1.payload.bits).require.value
    assert(finalPayload.amount === finalAmount)
    assert(finalPayload.totalAmount === finalAmount)
    assert(finalPayload.expiry.toLong === currentBlockCount + 9 + 1)
    assert(finalPayload.paymentSecret === pr.paymentSecret.get)
  }

  test("forward trampoline to legacy payment") { f =>
    import f._
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some eclair-mobile invoice"), CltvExpiryDelta(9))
    val trampolineFees = 21000 msat
    val req = SendTrampolinePayment(finalAmount, pr, b, Seq((trampolineFees, CltvExpiryDelta(12))), routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get()))
    sender.send(initiator, req)
    sender.expectMsgType[UUID]
    multiPartPayFsm.expectMsgType[SendPaymentConfig]

    val msg = multiPartPayFsm.expectMsgType[SendMultiPartPayment]
    assert(msg.paymentSecret !== pr.paymentSecret.get) // we should not leak the invoice secret to the trampoline node
    assert(msg.targetNodeId === b)
    assert(msg.targetExpiry.toLong === currentBlockCount + 9 + 12 + 1)
    assert(msg.totalAmount === finalAmount + trampolineFees)
    assert(msg.additionalTlvs.head.isInstanceOf[OnionTlv.TrampolineOnion])

    // Verify that the trampoline node can correctly peel the trampoline onion.
    val trampolineOnion = msg.additionalTlvs.head.asInstanceOf[OnionTlv.TrampolineOnion].packet
    val Right(decrypted) = Sphinx.TrampolinePacket.peel(priv_b.privateKey, pr.paymentHash, trampolineOnion)
    assert(!decrypted.isLastPacket)
    val trampolinePayload = OnionCodecs.nodeRelayPerHopPayloadCodec.decode(decrypted.payload.bits).require.value
    assert(trampolinePayload.amountToForward === finalAmount)
    assert(trampolinePayload.totalAmount === finalAmount)
    assert(trampolinePayload.outgoingCltv.toLong === currentBlockCount + 9 + 1)
    assert(trampolinePayload.outgoingNodeId === c)
    assert(trampolinePayload.paymentSecret === pr.paymentSecret)
    assert(trampolinePayload.invoiceFeatures === Some(hex"4100")) // var_onion_optin, payment_secret
  }

  test("reject trampoline to legacy payment for 0-value invoice") { f =>
    import f._
    // This is disabled because it would let the trampoline node steal the whole payment (if malicious).
    val routingHints = List(List(PaymentRequest.ExtraHop(b, channelUpdate_bc.shortChannelId, 10 msat, 100, CltvExpiryDelta(144))))
    val features = PaymentRequestFeatures(VariableLengthOnion.mandatory, PaymentSecret.mandatory, BasicMultiPartPayment.optional)
    val pr = PaymentRequest(Block.RegtestGenesisBlock.hash, None, paymentHash, priv_a.privateKey, Left("#abittooreckless"), CltvExpiryDelta(18), None, None, routingHints, features = features)
    val trampolineFees = 21000 msat
    val req = SendTrampolinePayment(finalAmount, pr, b, Seq((trampolineFees, CltvExpiryDelta(12))), CltvExpiryDelta(9), routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get()))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    val fail = sender.expectMsgType[PaymentFailed]
    assert(fail.id === id)
    assert(fail.failures === LocalFailure(Nil, PaymentError.TrampolineLegacyAmountLessInvoice) :: Nil)

    multiPartPayFsm.expectNoMessage(50 millis)
    payFsm.expectNoMessage(50 millis)
  }

  test("retry trampoline payment") { f =>
    import f._
    val features = PaymentRequestFeatures(VariableLengthOnion.mandatory, PaymentSecret.mandatory, BasicMultiPartPayment.optional, TrampolinePayment.optional)
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some phoenix invoice"), CltvExpiryDelta(18), features = features)
    val trampolineAttempts = (21000 msat, CltvExpiryDelta(12)) :: (25000 msat, CltvExpiryDelta(24)) :: Nil
    val req = SendTrampolinePayment(finalAmount, pr, b, trampolineAttempts, CltvExpiryDelta(9), routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get()))
    sender.send(initiator, req)
    sender.expectMsgType[UUID]
    val cfg = multiPartPayFsm.expectMsgType[SendPaymentConfig]
    assert(cfg.storeInDb)
    assert(!cfg.publishEvent)

    val msg1 = multiPartPayFsm.expectMsgType[SendMultiPartPayment]
    assert(msg1.totalAmount === finalAmount + 21000.msat)

    // Simulate a failure which should trigger a retry.
    multiPartPayFsm.send(initiator, PaymentFailed(cfg.parentId, pr.paymentHash, Seq(RemoteFailure(Nil, Sphinx.DecryptedFailurePacket(b, TrampolineFeeInsufficient)))))
    multiPartPayFsm.expectMsgType[SendPaymentConfig]
    val msg2 = multiPartPayFsm.expectMsgType[SendMultiPartPayment]
    assert(msg2.totalAmount === finalAmount + 25000.msat)

    // Simulate success which should publish the event and respond to the original sender.
    val success = PaymentSent(cfg.parentId, pr.paymentHash, randomBytes32(), finalAmount, c, Seq(PaymentSent.PartialPayment(UUID.randomUUID(), 1000 msat, 500 msat, randomBytes32(), None)))
    multiPartPayFsm.send(initiator, success)
    sender.expectMsg(success)
    eventListener.expectMsg(success)
    sender.expectNoMessage(100 millis)
    eventListener.expectNoMessage(100 millis)
  }

  test("retry trampoline payment and fail") { f =>
    import f._
    val features = PaymentRequestFeatures(VariableLengthOnion.mandatory, PaymentSecret.mandatory, BasicMultiPartPayment.optional, TrampolinePayment.optional)
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some phoenix invoice"), CltvExpiryDelta(18), features = features)
    val trampolineAttempts = (21000 msat, CltvExpiryDelta(12)) :: (25000 msat, CltvExpiryDelta(24)) :: Nil
    val req = SendTrampolinePayment(finalAmount, pr, b, trampolineAttempts, CltvExpiryDelta(9), routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get()))
    sender.send(initiator, req)
    sender.expectMsgType[UUID]
    val cfg = multiPartPayFsm.expectMsgType[SendPaymentConfig]
    assert(cfg.storeInDb)
    assert(!cfg.publishEvent)

    val msg1 = multiPartPayFsm.expectMsgType[SendMultiPartPayment]
    assert(msg1.totalAmount === finalAmount + 21000.msat)

    // Simulate a failure which should trigger a retry.
    multiPartPayFsm.send(initiator, PaymentFailed(cfg.parentId, pr.paymentHash, Seq(RemoteFailure(Nil, Sphinx.DecryptedFailurePacket(b, TrampolineFeeInsufficient)))))
    multiPartPayFsm.expectMsgType[SendPaymentConfig]
    val msg2 = multiPartPayFsm.expectMsgType[SendMultiPartPayment]
    assert(msg2.totalAmount === finalAmount + 25000.msat)

    // Simulate a failure that exhausts payment attempts.
    val failed = PaymentFailed(cfg.parentId, pr.paymentHash, Seq(RemoteFailure(Nil, Sphinx.DecryptedFailurePacket(b, TemporaryNodeFailure))))
    multiPartPayFsm.send(initiator, failed)
    sender.expectMsg(failed)
    eventListener.expectMsg(failed)
    sender.expectNoMessage(100 millis)
    eventListener.expectNoMessage(100 millis)
  }

  test("retry trampoline payment and fail (route not found)") { f =>
    import f._
    val features = PaymentRequestFeatures(VariableLengthOnion.mandatory, PaymentSecret.mandatory, BasicMultiPartPayment.optional, TrampolinePayment.optional)
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some phoenix invoice"), CltvExpiryDelta(18), features = features)
    val trampolineAttempts = (21000 msat, CltvExpiryDelta(12)) :: (25000 msat, CltvExpiryDelta(24)) :: Nil
    val req = SendTrampolinePayment(finalAmount, pr, b, trampolineAttempts, CltvExpiryDelta(9), routeParams = RouteCalculation.getDefaultRouteParams(nodeParams.routerConf.pathFindingExperimentConf.get()))
    sender.send(initiator, req)
    sender.expectMsgType[UUID]

    val cfg = multiPartPayFsm.expectMsgType[SendPaymentConfig]
    val msg1 = multiPartPayFsm.expectMsgType[SendMultiPartPayment]
    assert(msg1.totalAmount === finalAmount + 21000.msat)
    // Trampoline node couldn't find a route for the given fee.
    val failed = PaymentFailed(cfg.parentId, pr.paymentHash, Seq(RemoteFailure(Nil, Sphinx.DecryptedFailurePacket(b, TrampolineFeeInsufficient))))
    multiPartPayFsm.send(initiator, failed)
    multiPartPayFsm.expectMsgType[SendPaymentConfig]
    val msg2 = multiPartPayFsm.expectMsgType[SendMultiPartPayment]
    assert(msg2.totalAmount === finalAmount + 25000.msat)
    // Trampoline node couldn't find a route even with the increased fee.
    multiPartPayFsm.send(initiator, failed)

    val failure = sender.expectMsgType[PaymentFailed]
    assert(failure.failures === Seq(LocalFailure(Seq(NodeHop(nodeParams.nodeId, b, nodeParams.expiryDelta, 0 msat), NodeHop(b, c, CltvExpiryDelta(24), 25000 msat)), RouteNotFound)))
    eventListener.expectMsg(failure)
    sender.expectNoMessage(100 millis)
    eventListener.expectNoMessage(100 millis)
  }

  test("forward trampoline payment with pre-defined route") { f =>
    import f._
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("Some invoice"), CltvExpiryDelta(18))
    val trampolineFees = 100 msat
    val route = PredefinedNodeRoute(Seq(a, b))
    val req = SendPaymentToRoute(finalAmount + trampolineFees, finalAmount, pr, Channel.MIN_CLTV_EXPIRY_DELTA, route, None, None, None, trampolineFees, CltvExpiryDelta(144), Seq(b, c))
    sender.send(initiator, req)
    val payment = sender.expectMsgType[SendPaymentToRouteResponse]
    assert(payment.trampolineSecret.nonEmpty)
    payFsm.expectMsg(SendPaymentConfig(payment.paymentId, payment.parentId, None, paymentHash, finalAmount, c, Upstream.Local(payment.paymentId), Some(pr), storeInDb = true, publishEvent = true, recordMetrics = false, Seq(NodeHop(b, c, CltvExpiryDelta(0), 0 msat))))
    val msg = payFsm.expectMsgType[PaymentLifecycle.SendPaymentToRoute]
    assert(msg.route === Left(route))
    assert(msg.finalPayload.amount === finalAmount + trampolineFees)
    assert(msg.finalPayload.paymentSecret === payment.trampolineSecret.get)
    assert(msg.finalPayload.totalAmount === finalAmount + trampolineFees)
    assert(msg.finalPayload.isInstanceOf[Onion.FinalTlvPayload])
    val trampolineOnion = msg.finalPayload.asInstanceOf[Onion.FinalTlvPayload].records.get[OnionTlv.TrampolineOnion]
    assert(trampolineOnion.nonEmpty)

    // Verify that the trampoline node can correctly peel the trampoline onion.
    val Right(decrypted) = Sphinx.TrampolinePacket.peel(priv_b.privateKey, pr.paymentHash, trampolineOnion.get.packet)
    assert(!decrypted.isLastPacket)
    val trampolinePayload = OnionCodecs.nodeRelayPerHopPayloadCodec.decode(decrypted.payload.bits).require.value
    assert(trampolinePayload.amountToForward === finalAmount)
    assert(trampolinePayload.totalAmount === finalAmount)
    assert(trampolinePayload.outgoingNodeId === c)
    assert(trampolinePayload.paymentSecret === pr.paymentSecret)
  }

}
