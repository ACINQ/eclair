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

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.Features._
import fr.acinq.eclair.channel.{Channel, Upstream}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentPacketSpec._
import fr.acinq.eclair.payment.PaymentRequest.{ExtraHop, Features}
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.SendMultiPartPayment
import fr.acinq.eclair.payment.send.PaymentInitiator
import fr.acinq.eclair.payment.send.PaymentInitiator._
import fr.acinq.eclair.payment.send.PaymentLifecycle.{SendPayment, SendPaymentToRoute}
import fr.acinq.eclair.router.{NodeHop, RouteParams}
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire.{Onion, OnionCodecs, OnionTlv, TrampolineFeeInsufficient}
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, NodeParams, TestConstants, randomBytes32, randomKey}
import org.scalatest.{Outcome, Tag, fixture}
import scodec.bits.HexStringSyntax

import scala.concurrent.duration._

/**
 * Created by t-bast on 25/07/2019.
 */

class PaymentInitiatorSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, initiator: TestActorRef[PaymentInitiator], payFsm: TestProbe, multiPartPayFsm: TestProbe, sender: TestProbe, eventListener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val features = if (test.tags.contains("mpp_disabled")) hex"0a8a" else hex"028a8a"
    val nodeParams = TestConstants.Alice.nodeParams.copy(features = features)
    val (sender, payFsm, multiPartPayFsm) = (TestProbe(), TestProbe(), TestProbe())
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
    class TestPaymentInitiator extends PaymentInitiator(nodeParams, TestProbe().ref, TestProbe().ref, TestProbe().ref) {
      // @formatter:off
      override def spawnPaymentFsm(cfg: SendPaymentConfig): ActorRef = {
        payFsm.ref ! cfg
        payFsm.ref
      }
      override def spawnMultiPartPaymentFsm(cfg: SendPaymentConfig): ActorRef = {
        multiPartPayFsm.ref ! cfg
        multiPartPayFsm.ref
      }
      // @formatter:on
    }
    val initiator = TestActorRef(new TestPaymentInitiator().asInstanceOf[PaymentInitiator])
    withFixture(test.toNoArgTest(FixtureParam(nodeParams, initiator, payFsm, multiPartPayFsm, sender, eventListener)))
  }

  test("reject payment with unknown mandatory feature") { f =>
    import f._
    val unknownFeature = 42
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, randomKey, "Some invoice", features = Some(Features(unknownFeature)))
    val req = SendPaymentRequest(finalAmount + 100.msat, paymentHash, c, 1, CltvExpiryDelta(42), Some(pr))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    val fail = sender.expectMsgType[PaymentFailed]
    assert(fail.id === id)
    assert(fail.failures.head.isInstanceOf[LocalFailure])
  }

  test("forward payment with pre-defined route") { f =>
    import f._
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, "Some invoice", features = None)
    sender.send(initiator, SendPaymentToRouteRequest(finalAmount, finalAmount, None, None, pr, Channel.MIN_CLTV_EXPIRY_DELTA, Seq(a, b, c), None, 0 msat, CltvExpiryDelta(0), Nil))
    val payment = sender.expectMsgType[SendPaymentToRouteResponse]
    payFsm.expectMsg(SendPaymentConfig(payment.paymentId, payment.parentId, None, paymentHash, finalAmount, c, Upstream.Local(payment.paymentId), Some(pr), storeInDb = true, publishEvent = true, Nil))
    payFsm.expectMsg(SendPaymentToRoute(Seq(a, b, c), FinalLegacyPayload(finalAmount, Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(nodeParams.currentBlockHeight + 1))))
  }

  test("forward legacy payment") { f =>
    import f._
    val hints = Seq(Seq(ExtraHop(b, channelUpdate_bc.shortChannelId, feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12))))
    val routeParams = RouteParams(randomize = true, 15 msat, 1.5, 5, CltvExpiryDelta(561), None)
    sender.send(initiator, SendPaymentRequest(finalAmount, paymentHash, c, 1, CltvExpiryDelta(42), assistedRoutes = hints, routeParams = Some(routeParams)))
    val id1 = sender.expectMsgType[UUID]
    payFsm.expectMsg(SendPaymentConfig(id1, id1, None, paymentHash, finalAmount, c, Upstream.Local(id1), None, storeInDb = true, publishEvent = true, Nil))
    payFsm.expectMsg(SendPayment(c, FinalLegacyPayload(finalAmount, CltvExpiryDelta(42).toCltvExpiry(nodeParams.currentBlockHeight + 1)), 1, hints, Some(routeParams)))

    sender.send(initiator, SendPaymentRequest(finalAmount, paymentHash, e, 3))
    val id2 = sender.expectMsgType[UUID]
    payFsm.expectMsg(SendPaymentConfig(id2, id2, None, paymentHash, finalAmount, e, Upstream.Local(id2), None, storeInDb = true, publishEvent = true, Nil))
    payFsm.expectMsg(SendPayment(e, FinalLegacyPayload(finalAmount, Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(nodeParams.currentBlockHeight + 1)), 3))
  }

  test("forward legacy payment when multi-part deactivated", Tag("mpp_disabled")) { f =>
    import f._
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, randomKey, "Some MPP invoice", features = Some(Features(VariableLengthOnion.optional, PaymentSecret.optional, BasicMultiPartPayment.optional)))
    val req = SendPaymentRequest(finalAmount, paymentHash, c, 1, CltvExpiryDelta(42), Some(pr))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    payFsm.expectMsg(SendPaymentConfig(id, id, None, paymentHash, finalAmount, c, Upstream.Local(id), Some(pr), storeInDb = true, publishEvent = true, Nil))
    payFsm.expectMsg(SendPayment(c, FinalLegacyPayload(finalAmount, req.finalExpiry(nodeParams.currentBlockHeight)), 1))
  }

  test("forward multi-part payment") { f =>
    import f._
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, randomKey, "Some invoice", features = Some(Features(VariableLengthOnion.optional, PaymentSecret.optional, BasicMultiPartPayment.optional)))
    val req = SendPaymentRequest(finalAmount + 100.msat, paymentHash, c, 1, CltvExpiryDelta(42), Some(pr))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    multiPartPayFsm.expectMsg(SendPaymentConfig(id, id, None, paymentHash, finalAmount + 100.msat, c, Upstream.Local(id), Some(pr), storeInDb = true, publishEvent = true, Nil))
    multiPartPayFsm.expectMsg(SendMultiPartPayment(pr.paymentSecret.get, c, finalAmount + 100.msat, req.finalExpiry(nodeParams.currentBlockHeight), 1))
  }

  test("forward multi-part payment with pre-defined route") { f =>
    import f._
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, "Some invoice", features = Some(Features(VariableLengthOnion.optional, PaymentSecret.optional, BasicMultiPartPayment.optional)))
    val req = SendPaymentToRouteRequest(finalAmount / 2, finalAmount, None, None, pr, Channel.MIN_CLTV_EXPIRY_DELTA, Seq(a, b, c), None, 0 msat, CltvExpiryDelta(0), Nil)
    sender.send(initiator, req)
    val payment = sender.expectMsgType[SendPaymentToRouteResponse]
    payFsm.expectMsg(SendPaymentConfig(payment.paymentId, payment.parentId, None, paymentHash, finalAmount, c, Upstream.Local(payment.paymentId), Some(pr), storeInDb = true, publishEvent = true, Nil))
    val msg = payFsm.expectMsgType[SendPaymentToRoute]
    assert(msg.hops === Seq(a, b, c))
    assert(msg.finalPayload.amount === finalAmount / 2)
    assert(msg.finalPayload.paymentSecret === pr.paymentSecret)
    assert(msg.finalPayload.totalAmount === finalAmount)
  }

  test("forward trampoline payment") { f =>
    import f._
    val features = Features(VariableLengthOnion.optional, PaymentSecret.optional, BasicMultiPartPayment.optional, TrampolinePayment.optional)
    val ignoredRoutingHints = List(List(ExtraHop(b, channelUpdate_bc.shortChannelId, feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12))))
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, "Some phoenix invoice", features = Some(features), extraHops = ignoredRoutingHints)
    val trampolineFees = 21000 msat
    val req = SendTrampolinePaymentRequest(finalAmount, pr, b, Seq((trampolineFees, CltvExpiryDelta(12))), CltvExpiryDelta(9))
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
    assert(finalPayload.paymentSecret === pr.paymentSecret)
  }

  test("forward trampoline to legacy payment") { f =>
    import f._
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, "Some eclair-mobile invoice")
    val trampolineFees = 21000 msat
    val req = SendTrampolinePaymentRequest(finalAmount, pr, b, Seq((trampolineFees, CltvExpiryDelta(12))), CltvExpiryDelta(9))
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
    assert(trampolinePayload.invoiceFeatures === Some(hex"8200")) // var_onion_optin, payment_secret
  }

  test("reject trampoline to legacy payment for 0-value invoice") { f =>
    import f._
    // This is disabled because it would let the trampoline node steal the whole payment (if malicious).
    val routingHints = List(List(PaymentRequest.ExtraHop(b, channelUpdate_bc.shortChannelId, 10 msat, 100, CltvExpiryDelta(144))))
    val features = Features(VariableLengthOnion.optional, PaymentSecret.optional, BasicMultiPartPayment.optional)
    val pr = PaymentRequest(Block.RegtestGenesisBlock.hash, None, paymentHash, priv_a.privateKey, "#abittooreckless", None, None, routingHints, features = Some(features))
    val trampolineFees = 21000 msat
    val req = SendTrampolinePaymentRequest(finalAmount, pr, b, Seq((trampolineFees, CltvExpiryDelta(12))), CltvExpiryDelta(9))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    val fail = sender.expectMsgType[PaymentFailed]
    assert(fail.id === id)
    assert(fail.failures === LocalFailure(TrampolineLegacyAmountLessInvoice) :: Nil)

    multiPartPayFsm.expectNoMsg(50 millis)
    payFsm.expectNoMsg(50 millis)
  }

  test("retry trampoline payment") { f =>
    import f._
    val features = Features(VariableLengthOnion.optional, PaymentSecret.optional, BasicMultiPartPayment.optional, TrampolinePayment.optional)
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, "Some phoenix invoice", features = Some(features))
    val trampolineAttempts = (21000 msat, CltvExpiryDelta(12)) :: (25000 msat, CltvExpiryDelta(24)) :: Nil
    val req = SendTrampolinePaymentRequest(finalAmount, pr, b, trampolineAttempts, CltvExpiryDelta(9))
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
    val success = PaymentSent(cfg.parentId, pr.paymentHash, randomBytes32, finalAmount, c, Seq(PaymentSent.PartialPayment(UUID.randomUUID(), 1000 msat, 500 msat, randomBytes32, None)))
    multiPartPayFsm.send(initiator, success)
    sender.expectMsg(success)
    eventListener.expectMsg(success)
    sender.expectNoMsg(100 millis)
    eventListener.expectNoMsg(100 millis)
  }

  test("retry trampoline payment and fail") { f =>
    import f._
    val features = Features(VariableLengthOnion.optional, PaymentSecret.optional, BasicMultiPartPayment.optional, TrampolinePayment.optional)
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, "Some phoenix invoice", features = Some(features))
    val trampolineAttempts = (21000 msat, CltvExpiryDelta(12)) :: (25000 msat, CltvExpiryDelta(24)) :: Nil
    val req = SendTrampolinePaymentRequest(finalAmount, pr, b, trampolineAttempts, CltvExpiryDelta(9))
    sender.send(initiator, req)
    sender.expectMsgType[UUID]
    val cfg = multiPartPayFsm.expectMsgType[SendPaymentConfig]
    assert(cfg.storeInDb)
    assert(!cfg.publishEvent)

    val msg1 = multiPartPayFsm.expectMsgType[SendMultiPartPayment]
    assert(msg1.totalAmount === finalAmount + 21000.msat)

    // Simulate a failure which should trigger a retry.
    val failed = PaymentFailed(cfg.parentId, pr.paymentHash, Seq(RemoteFailure(Nil, Sphinx.DecryptedFailurePacket(b, TrampolineFeeInsufficient))))
    multiPartPayFsm.send(initiator, failed)
    multiPartPayFsm.expectMsgType[SendPaymentConfig]
    val msg2 = multiPartPayFsm.expectMsgType[SendMultiPartPayment]
    assert(msg2.totalAmount === finalAmount + 25000.msat)

    // Simulate a failure that exhausts payment attempts.
    multiPartPayFsm.send(initiator, failed)
    sender.expectMsg(failed)
    eventListener.expectMsg(failed)
    sender.expectNoMsg(100 millis)
    eventListener.expectNoMsg(100 millis)
  }

  test("forward trampoline payment with pre-defined route") { f =>
    import f._
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, "Some invoice")
    val trampolineFees = 100 msat
    val req = SendPaymentToRouteRequest(finalAmount + trampolineFees, finalAmount, None, None, pr, Channel.MIN_CLTV_EXPIRY_DELTA, Seq(a, b), None, trampolineFees, CltvExpiryDelta(144), Seq(b, c))
    sender.send(initiator, req)
    val payment = sender.expectMsgType[SendPaymentToRouteResponse]
    assert(payment.trampolineSecret.nonEmpty)
    payFsm.expectMsg(SendPaymentConfig(payment.paymentId, payment.parentId, None, paymentHash, finalAmount, c, Upstream.Local(payment.paymentId), Some(pr), storeInDb = true, publishEvent = true, Seq(NodeHop(b, c, CltvExpiryDelta(0), 0 msat))))
    val msg = payFsm.expectMsgType[SendPaymentToRoute]
    assert(msg.hops === Seq(a, b))
    assert(msg.finalPayload.amount === finalAmount + trampolineFees)
    assert(msg.finalPayload.paymentSecret === payment.trampolineSecret)
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
