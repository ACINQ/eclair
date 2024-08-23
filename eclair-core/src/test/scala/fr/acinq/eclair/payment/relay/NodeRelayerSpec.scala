/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.payment.relay

import akka.actor.Status
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.adapter._
import com.softwaremill.quicklens.ModifyPimp
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, BlockHash, ByteVector32, Crypto}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{AsyncPaymentPrototype, BasicMultiPartPayment, PaymentSecret, VariableLengthOnion}
import fr.acinq.eclair.EncodedNodeId.ShortChannelIdDir
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Register, Upstream}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment.IncomingPaymentPacket.{RelayToBlindedPathsPacket, RelayToTrampolinePacket}
import fr.acinq.eclair.payment.Invoice.ExtraEdge
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.AsyncPaymentTriggerer.{AsyncPaymentCanceled, AsyncPaymentTimeout, AsyncPaymentTriggered, Watch}
import fr.acinq.eclair.payment.relay.NodeRelayer.PaymentKey
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.{PreimageReceived, SendMultiPartPayment}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPaymentToNode
import fr.acinq.eclair.payment.send.{BlindedRecipient, ClearRecipient}
import fr.acinq.eclair.reputation.ReputationRecorder
import fr.acinq.eclair.reputation.ReputationRecorder.{Confidence, GetTrampolineConfidence, RecordTrampolineFailure, RecordTrampolineSuccess}
import fr.acinq.eclair.router.Router.RouteRequest
import fr.acinq.eclair.router.{BalanceTooLow, RouteNotFound, Router}
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.wire.protocol.PaymentOnion.{FinalPayload, IntermediatePayload}
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv.{AllowedFeatures, PathId, PaymentConstraints}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, Bolt11Feature, CltvExpiry, CltvExpiryDelta, FeatureSupport, Features, MilliSatoshi, MilliSatoshiLong, NodeParams, RealShortChannelId, ShortChannelId, TestConstants, TimestampMilli, UInt64, randomBytes, randomBytes32, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.{ByteVector, HexStringSyntax}

import java.util.UUID
import scala.concurrent.duration._
import scala.util.Random

/**
 * Created by t-bast on 10/10/2019.
 */

class NodeRelayerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  import NodeRelayerSpec._

  case class FixtureParam(nodeParams: NodeParams, router: TestProbe[Any], register: TestProbe[Any], reputationRecorder: TestProbe[ReputationRecorder.Command], mockPayFSM: TestProbe[Any], eventListener: TestProbe[PaymentEvent], triggerer: TestProbe[AsyncPaymentTriggerer.Command]) {
    def createNodeRelay(packetIn: IncomingPaymentPacket.NodeRelayPacket, useRealPaymentFactory: Boolean = false): (ActorRef[NodeRelay.Command], TestProbe[NodeRelayer.Command]) = {
      val parent = TestProbe[NodeRelayer.Command]("parent-relayer")
      val outgoingPaymentFactory = if (useRealPaymentFactory) RealOutgoingPaymentFactory(this) else FakeOutgoingPaymentFactory(this)
      val nodeRelay = testKit.spawn(NodeRelay(nodeParams, parent.ref, register.ref.toClassic, Some(reputationRecorder.ref), relayId, packetIn, outgoingPaymentFactory, triggerer.ref, router.ref.toClassic))
      (nodeRelay, parent)
    }
  }

  case class FakeOutgoingPaymentFactory(f: FixtureParam) extends NodeRelay.OutgoingPaymentFactory {
    override def spawnOutgoingPayFSM(context: ActorContext[NodeRelay.Command], cfg: SendPaymentConfig, multiPart: Boolean): akka.actor.ActorRef = {
      f.mockPayFSM.ref ! cfg
      f.mockPayFSM.ref.toClassic
    }
  }

  case class RealOutgoingPaymentFactory(f: FixtureParam) extends NodeRelay.OutgoingPaymentFactory {
    override def spawnOutgoingPayFSM(context: ActorContext[NodeRelay.Command], cfg: SendPaymentConfig, multiPart: Boolean): akka.actor.ActorRef = {
      val outgoingPayFSM = NodeRelay.SimpleOutgoingPaymentFactory(f.nodeParams, f.router.ref.toClassic, f.register.ref.toClassic).spawnOutgoingPayFSM(context, cfg, multiPart)
      f.mockPayFSM.ref ! outgoingPayFSM
      outgoingPayFSM
    }
  }

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams = TestConstants.Bob.nodeParams
      .modify(_.multiPartPaymentExpiry).setTo(5 seconds)
      .modify(_.features).setToIf(test.tags.contains("async_payments"))(Features(AsyncPaymentPrototype -> Optional))
      .modify(_.relayParams.asyncPaymentsParams.holdTimeoutBlocks).setToIf(test.tags.contains("long_hold_timeout"))(200000) // timeout after payment expires
    val router = TestProbe[Any]("router")
    val register = TestProbe[Any]("register")
    val reputationRecorder = TestProbe[ReputationRecorder.Command]("reputation-recorder")
    val eventListener = TestProbe[PaymentEvent]("event-listener")
    system.eventStream ! EventStream.Subscribe(eventListener.ref)
    val mockPayFSM = TestProbe[Any]("pay-fsm")
    val triggerer = TestProbe[AsyncPaymentTriggerer.Command]("payment-triggerer")
    withFixture(test.toNoArgTest(FixtureParam(nodeParams, router, register, reputationRecorder, mockPayFSM, eventListener, triggerer)))
  }

  test("create child handlers for new payments") { f =>
    import f._
    val probe = TestProbe[Any]()
    val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, register.ref.toClassic, Some(reputationRecorder.ref), FakeOutgoingPaymentFactory(f), triggerer.ref, router.ref.toClassic))
    parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
    probe.expectMessage(Map.empty)

    val (paymentHash1, paymentSecret1) = (randomBytes32(), randomBytes32())
    val payment1 = createPartialIncomingPacket(paymentHash1, paymentSecret1)
    parentRelayer ! NodeRelayer.Relay(payment1, randomKey().publicKey)
    parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
    val pending1 = probe.expectMessageType[Map[PaymentKey, ActorRef[NodeRelay.Command]]]
    assert(pending1.keySet == Set(PaymentKey(paymentHash1, paymentSecret1)))

    val (paymentHash2, paymentSecret2) = (randomBytes32(), randomBytes32())
    val payment2 = createPartialIncomingPacket(paymentHash2, paymentSecret2)
    parentRelayer ! NodeRelayer.Relay(payment2, randomKey().publicKey)
    parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
    val pending2 = probe.expectMessageType[Map[PaymentKey, ActorRef[NodeRelay.Command]]]
    assert(pending2.keySet == Set(PaymentKey(paymentHash1, paymentSecret1), PaymentKey(paymentHash2, paymentSecret2)))

    val payment3a = createPartialIncomingPacket(paymentHash1, paymentSecret2)
    parentRelayer ! NodeRelayer.Relay(payment3a, randomKey().publicKey)
    parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
    val pending3 = probe.expectMessageType[Map[PaymentKey, ActorRef[NodeRelay.Command]]]
    assert(pending3.keySet == Set(PaymentKey(paymentHash1, paymentSecret1), PaymentKey(paymentHash2, paymentSecret2), PaymentKey(paymentHash1, paymentSecret2)))

    val payment3b = createPartialIncomingPacket(paymentHash1, paymentSecret2)
    parentRelayer ! NodeRelayer.Relay(payment3b, randomKey().publicKey)
    parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
    val pending4 = probe.expectMessageType[Map[PaymentKey, ActorRef[NodeRelay.Command]]]
    assert(pending4.keySet == Set(PaymentKey(paymentHash1, paymentSecret1), PaymentKey(paymentHash2, paymentSecret2), PaymentKey(paymentHash1, paymentSecret2)))

    register.expectNoMessage(100 millis)
  }

  test("stop child handlers when relay is complete") { f =>
    import f._
    val probe = TestProbe[Any]()
    val outgoingPaymentFactory = FakeOutgoingPaymentFactory(f)

    {
      val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, register.ref.toClassic, Some(reputationRecorder.ref), outgoingPaymentFactory, triggerer.ref, router.ref.toClassic))
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(Map.empty)
    }
    {
      val (paymentHash1, paymentSecret1, child1) = (randomBytes32(), randomBytes32(), TestProbe[NodeRelay.Command]())
      val (paymentHash2, paymentSecret2, child2) = (randomBytes32(), randomBytes32(), TestProbe[NodeRelay.Command]())
      val children = Map(PaymentKey(paymentHash1, paymentSecret1) -> child1.ref, PaymentKey(paymentHash2, paymentSecret2) -> child2.ref)
      val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, register.ref.toClassic, Some(reputationRecorder.ref), outgoingPaymentFactory, triggerer.ref, router.ref.toClassic, children))
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(children)

      parentRelayer ! NodeRelayer.RelayComplete(child1.ref, paymentHash1, paymentSecret1)
      child1.expectMessage(NodeRelay.Stop)
      parentRelayer ! NodeRelayer.RelayComplete(child1.ref, paymentHash1, paymentSecret1)
      child1.expectMessage(NodeRelay.Stop)
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(Map(PaymentKey(paymentHash2, paymentSecret2) -> child2.ref))
    }
    {
      val paymentHash = randomBytes32()
      val (paymentSecret1, child1) = (randomBytes32(), TestProbe[NodeRelay.Command]())
      val (paymentSecret2, child2) = (randomBytes32(), TestProbe[NodeRelay.Command]())
      val children = Map(PaymentKey(paymentHash, paymentSecret1) -> child1.ref, PaymentKey(paymentHash, paymentSecret2) -> child2.ref)
      val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, register.ref.toClassic, Some(reputationRecorder.ref), outgoingPaymentFactory, triggerer.ref, router.ref.toClassic, children))
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(children)

      parentRelayer ! NodeRelayer.RelayComplete(child1.ref, paymentHash, paymentSecret1)
      child1.expectMessage(NodeRelay.Stop)
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(Map(PaymentKey(paymentHash, paymentSecret2) -> child2.ref))
    }
    {
      val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, register.ref.toClassic, Some(reputationRecorder.ref), outgoingPaymentFactory, triggerer.ref, router.ref.toClassic))
      parentRelayer ! NodeRelayer.Relay(incomingMultiPart.head, randomKey().publicKey)
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      val pending1 = probe.expectMessageType[Map[PaymentKey, ActorRef[NodeRelay.Command]]]
      assert(pending1.size == 1)
      assert(pending1.head._1 == PaymentKey(paymentHash, incomingSecret))

      parentRelayer ! NodeRelayer.RelayComplete(pending1.head._2, paymentHash, incomingSecret)
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(Map.empty)

      parentRelayer ! NodeRelayer.Relay(incomingMultiPart.head, randomKey().publicKey)
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      val pending2 = probe.expectMessageType[Map[PaymentKey, ActorRef[NodeRelay.Command]]]
      assert(pending2.size == 1)
      assert(pending2.head._1 == pending1.head._1)
      assert(pending2.head._2 !== pending1.head._2)
    }
  }

  test("fail to relay when incoming multi-part payment times out") { f =>
    import f._

    val (nodeRelayer, parent) = f.createNodeRelay(incomingMultiPart.head)
    // Receive a partial upstream multi-part payment.
    incomingMultiPart.dropRight(1).foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey))
    // after a while the payment times out
    incomingMultiPart.dropRight(1).foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]](30 seconds)
      assert(fwd.channelId == p.add.channelId)
      val failure = Right(PaymentTimeout())
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, failure, commit = true))
    }

    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("fail all extraneous multi-part incoming HTLCs") { f =>
    import f._

    val (nodeRelayer, _) = f.createNodeRelay(incomingMultiPart.head)
    // We send all the parts of a mpp
    incomingMultiPart.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey))
    // and then one extra
    val extra = IncomingPaymentPacket.RelayToTrampolinePacket(
      UpdateAddHtlc(randomBytes32(), Random.nextInt(100), 1000 msat, paymentHash, CltvExpiry(499990), TestConstants.emptyOnionPacket, None, 1.0),
      FinalPayload.Standard.createPayload(1000 msat, incomingAmount, CltvExpiry(499990), incomingSecret, None),
      IntermediatePayload.NodeRelay.Standard(outgoingAmount, outgoingExpiry, outgoingNodeId),
      nextTrampolinePacket)
    nodeRelayer ! NodeRelay.Relay(extra, randomKey().publicKey)

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(1.0)

    // the extra payment will be rejected
    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId == extra.add.channelId)
    val failure = IncorrectOrUnknownPaymentDetails(extra.add.amountMsat, nodeParams.currentBlockHeight)
    assert(fwd.message == CMD_FAIL_HTLC(extra.add.id, Right(failure), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail all additional incoming HTLCs once already relayed out") { f =>
    import f._

    val (nodeRelayer, _) = f.createNodeRelay(incomingMultiPart.head)
    // Receive a complete upstream multi-part payment, which we relay out.
    incomingMultiPart.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey))

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(3.5 / 8)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, TimestampMilli.now(), randomKey().publicKey))), 3)
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)

    // Receive new extraneous multi-part HTLC.
    val i1 = IncomingPaymentPacket.RelayToTrampolinePacket(
      UpdateAddHtlc(randomBytes32(), Random.nextInt(100), 1000 msat, paymentHash, CltvExpiry(499990), TestConstants.emptyOnionPacket, None, 1.0),
      FinalPayload.Standard.createPayload(1000 msat, incomingAmount, CltvExpiry(499990), incomingSecret, None),
      IntermediatePayload.NodeRelay.Standard(outgoingAmount, outgoingExpiry, outgoingNodeId),
      nextTrampolinePacket)
    nodeRelayer ! NodeRelay.Relay(i1, randomKey().publicKey)

    val fwd1 = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd1.channelId == i1.add.channelId)
    val failure1 = IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)
    assert(fwd1.message == CMD_FAIL_HTLC(i1.add.id, Right(failure1), commit = true))

    // Receive new HTLC with different details, but for the same payment hash.
    val i2 = IncomingPaymentPacket.RelayToTrampolinePacket(
      UpdateAddHtlc(randomBytes32(), Random.nextInt(100), 1500 msat, paymentHash, CltvExpiry(499990), TestConstants.emptyOnionPacket, None, 1.0),
      PaymentOnion.FinalPayload.Standard.createPayload(1500 msat, 1500 msat, CltvExpiry(499990), incomingSecret, None),
      IntermediatePayload.NodeRelay.Standard(1250 msat, outgoingExpiry, outgoingNodeId),
      nextTrampolinePacket)
    nodeRelayer ! NodeRelay.Relay(i2, randomKey().publicKey)

    val fwd2 = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd1.channelId == i1.add.channelId)
    val failure2 = IncorrectOrUnknownPaymentDetails(1500 msat, nodeParams.currentBlockHeight)
    assert(fwd2.message == CMD_FAIL_HTLC(i2.add.id, Right(failure2), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when expiry is too soon (single-part)") { f =>
    import f._

    val expiryIn = CltvExpiry(500000) // not ok (delta = 100)
    val expiryOut = CltvExpiry(499900)
    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, expiryIn, 1000000 msat, expiryOut)
    val (nodeRelayer, _) = f.createNodeRelay(p)
    nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId == p.add.channelId)
    assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(TrampolineExpiryTooSoon()), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when final expiry is below chain height") { f =>
    import f._

    val expiryIn = CltvExpiry(500000)
    val expiryOut = CltvExpiry(300000) // not ok (chain height = 400000)
    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, expiryIn, 1000000 msat, expiryOut)
    val (nodeRelayer, _) = f.createNodeRelay(p)
    nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId == p.add.channelId)
    assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(TrampolineExpiryTooSoon()), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when expiry is too soon (multi-part)") { f =>
    import f._

    val expiryIn1 = CltvExpiry(510000) // ok
    val expiryIn2 = CltvExpiry(500000) // not ok (delta = 100)
    val expiryOut = CltvExpiry(499900)
    val p = Seq(
      createValidIncomingPacket(2000000 msat, 3000000 msat, expiryIn1, 2100000 msat, expiryOut),
      createValidIncomingPacket(1000000 msat, 3000000 msat, expiryIn2, 2100000 msat, expiryOut)
    )
    val (nodeRelayer, _) = f.createNodeRelay(p.head)
    p.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))

    p.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(TrampolineExpiryTooSoon()), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when not triggered before the hold timeout", Tag("async_payments")) { f =>
    import f._

    val (nodeRelayer, _) = createNodeRelay(incomingAsyncPayment.head)
    incomingAsyncPayment.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))

    // wait until the NodeRelay is waiting for the trigger
    eventListener.expectMessageType[WaitingToRelayPayment]
    mockPayFSM.expectNoMessage(100 millis) // we should NOT trigger a downstream payment before we received a trigger

    // publish notification that peer is unavailable at the timeout height
    val peerWatch = triggerer.expectMessageType[Watch]
    assert(asyncTimeoutHeight(nodeParams) < asyncSafetyHeight(incomingAsyncPayment, nodeParams))
    assert(peerWatch.timeout == asyncTimeoutHeight(nodeParams))
    peerWatch.replyTo ! AsyncPaymentTimeout

    incomingAsyncPayment.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(TemporaryNodeFailure()), commit = true))
    }
    register.expectNoMessage(100 millis)
  }

  test("relay the payment when triggered while waiting", Tag("async_payments"), Tag("long_hold_timeout")) { f =>
    import f._

    val (nodeRelayer, parent) = createNodeRelay(incomingAsyncPayment.head)
    incomingAsyncPayment.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))

    // wait until the NodeRelay is waiting for the trigger
    eventListener.expectMessageType[WaitingToRelayPayment]
    mockPayFSM.expectNoMessage(100 millis) // we should NOT trigger a downstream payment before we received a trigger

    // publish notification that peer is ready before the safety interval before the current incoming payment expires (and before the timeout height)
    val peerWatch = triggerer.expectMessageType[Watch]
    assert(asyncTimeoutHeight(nodeParams) > asyncSafetyHeight(incomingAsyncPayment, nodeParams))
    assert(peerWatch.timeout == asyncSafetyHeight(incomingAsyncPayment, nodeParams))
    peerWatch.replyTo ! AsyncPaymentTriggered

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(4.5 / 8)

    // upstream payment relayed
    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingAsyncPayment.map(p => Upstream.Hot.Channel(p.add, TimestampMilli.now(), randomKey().publicKey))), 4)
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    // A first downstream HTLC is fulfilled: we should immediately forward the fulfill upstream.
    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    incomingAsyncPayment.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))
    }

    // Once all the downstream payments have settled, we should emit the relayed event.
    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)).toSet == incomingAsyncPayment.map(i => (i.add.amountMsat, i.add.channelId)).toSet)
    assert(relayEvent.outgoing.nonEmpty)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    reputationRecorder.expectMessageType[RecordTrampolineSuccess]
    register.expectNoMessage(100 millis)
  }

  test("fail to relay when not triggered before the incoming expiry safety timeout", Tag("async_payments"), Tag("long_hold_timeout")) { f =>
    import f._

    val (nodeRelayer, _) = createNodeRelay(incomingAsyncPayment.head)
    incomingAsyncPayment.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))
    mockPayFSM.expectNoMessage(100 millis) // we should NOT trigger a downstream payment before we received a complete upstream payment

    // publish notification that peer is unavailable at the cancel-safety-before-timeout-block threshold before the current incoming payment expires (and before the timeout height)
    val peerWatch = triggerer.expectMessageType[Watch]
    assert(asyncTimeoutHeight(nodeParams) > asyncSafetyHeight(incomingAsyncPayment, nodeParams))
    assert(peerWatch.timeout == asyncSafetyHeight(incomingAsyncPayment, nodeParams))
    peerWatch.replyTo ! AsyncPaymentTimeout

    incomingAsyncPayment.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(TemporaryNodeFailure()), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("fail to relay payment when canceled by sender before timeout", Tag("async_payments")) { f =>
    import f._

    val (nodeRelayer, _) = createNodeRelay(incomingAsyncPayment.head)
    incomingAsyncPayment.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))

    // wait until the NodeRelay is waiting for the trigger
    eventListener.expectMessageType[WaitingToRelayPayment]
    mockPayFSM.expectNoMessage(100 millis) // we should NOT trigger a downstream payment before we received a trigger

    // fail the payment if waiting when payment sender sends cancel message
    nodeRelayer ! NodeRelay.WrappedPeerReadyResult(AsyncPaymentCanceled)

    incomingAsyncPayment.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(TemporaryNodeFailure()), commit = true))
    }
    register.expectNoMessage(100 millis)
  }

  test("relay the payment immediately when the async payment feature is disabled") { f =>
    import f._

    assert(!nodeParams.features.hasFeature(AsyncPaymentPrototype))

    val (nodeRelayer, parent) = createNodeRelay(incomingAsyncPayment.head)
    incomingAsyncPayment.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(6.5 / 8)

    // upstream payment relayed
    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingAsyncPayment.map(p => Upstream.Hot.Channel(p.add, TimestampMilli.now(), randomKey().publicKey))), 6)
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    // A first downstream HTLC is fulfilled: we should immediately forward the fulfill upstream.
    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    incomingAsyncPayment.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))
    }

    // Once all the downstream payments have settled, we should emit the relayed event.
    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)).toSet == incomingAsyncPayment.map(i => (i.add.amountMsat, i.add.channelId)).toSet)
    assert(relayEvent.outgoing.nonEmpty)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    reputationRecorder.expectMessageType[RecordTrampolineSuccess]
    register.expectNoMessage(100 millis)
  }

  test("fail to relay when fees are insufficient (single-part)") { f =>
    import f._

    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, CltvExpiry(500000), 1999000 msat, CltvExpiry(490000))
    val (nodeRelayer, _) = f.createNodeRelay(p)
    nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId == p.add.channelId)
    assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(TrampolineFeeInsufficient()), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when fees are insufficient (multi-part)") { f =>
    import f._

    val p = Seq(
      createValidIncomingPacket(2000000 msat, 3000000 msat, CltvExpiry(500000), 2999000 msat, CltvExpiry(400000)),
      createValidIncomingPacket(1000000 msat, 3000000 msat, CltvExpiry(500000), 2999000 msat, CltvExpiry(400000))
    )
    val (nodeRelayer, _) = f.createNodeRelay(p.head)
    p.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))

    p.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(TrampolineFeeInsufficient()), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when amount is 0 (single-part)") { f =>
    import f._

    val p = createValidIncomingPacket(5000000 msat, 5000000 msat, CltvExpiry(500000), 0 msat, CltvExpiry(490000))
    val (nodeRelayer, _) = f.createNodeRelay(p)
    nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId == p.add.channelId)
    assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(InvalidOnionPayload(UInt64(2), 0)), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when amount is 0 (multi-part)") { f =>
    import f._

    val p = Seq(
      createValidIncomingPacket(4000000 msat, 5000000 msat, CltvExpiry(500000), 0 msat, CltvExpiry(490000)),
      createValidIncomingPacket(1000000 msat, 5000000 msat, CltvExpiry(500000), 0 msat, CltvExpiry(490000))
    )
    val (nodeRelayer, _) = f.createNodeRelay(p.head)
    p.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))

    p.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(InvalidOnionPayload(UInt64(2), 0)), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("fail to relay because outgoing balance isn't sufficient (low fees)") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val (nodeRelayer, _) = f.createNodeRelay(incomingMultiPart.head)
    incomingMultiPart.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(1.0)

    mockPayFSM.expectMessageType[SendPaymentConfig]
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = mockPayFSM.expectMessageType[SendMultiPartPayment].replyTo

    // The proposed fees are low, so we ask the sender to raise them.
    nodeRelayerAdapters ! PaymentFailed(relayId, paymentHash, LocalFailure(outgoingAmount, Nil, BalanceTooLow) :: Nil)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(TrampolineFeeInsufficient()), commit = true))
    }

    reputationRecorder.expectMessageType[RecordTrampolineFailure]
    register.expectNoMessage(100 millis)
    eventListener.expectNoMessage(100 millis)
  }

  test("fail to relay because outgoing balance isn't sufficient (high fees)") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val incoming = Seq(
      createValidIncomingPacket(outgoingAmount, outgoingAmount * 2, CltvExpiry(500000), outgoingAmount, outgoingExpiry),
      createValidIncomingPacket(outgoingAmount, outgoingAmount * 2, CltvExpiry(500000), outgoingAmount, outgoingExpiry),
    )
    val (nodeRelayer, _) = f.createNodeRelay(incoming.head, useRealPaymentFactory = true)
    incoming.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(1.0)

    val payFSM = mockPayFSM.expectMessageType[akka.actor.ActorRef]
    router.expectMessageType[RouteRequest]
    payFSM ! Status.Failure(BalanceTooLow)

    incoming.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(TemporaryNodeFailure()), commit = true))
    }

    reputationRecorder.expectMessageType[RecordTrampolineFailure]
    register.expectNoMessage(100 millis)
  }

  test("fail to relay because incoming fee isn't enough to find routes downstream") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val (nodeRelayer, _) = f.createNodeRelay(incomingMultiPart.head, useRealPaymentFactory = true)
    incomingMultiPart.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(1.0)

    val payFSM = mockPayFSM.expectMessageType[akka.actor.ActorRef]
    router.expectMessageType[RouteRequest]

    // If we're having a hard time finding routes, raising the fee/cltv will likely help.
    val failures = LocalFailure(outgoingAmount, Nil, RouteNotFound) :: RemoteFailure(outgoingAmount, Nil, Sphinx.DecryptedFailurePacket(outgoingNodeId, PermanentNodeFailure())) :: LocalFailure(outgoingAmount, Nil, RouteNotFound) :: Nil
    payFSM ! PaymentFailed(relayId, incomingMultiPart.head.add.paymentHash, failures)

    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(TrampolineFeeInsufficient()), commit = true))
    }

    reputationRecorder.expectMessageType[RecordTrampolineFailure]
    register.expectNoMessage(100 millis)
  }

  test("fail to relay because of downstream failures") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val (nodeRelayer, _) = f.createNodeRelay(incomingMultiPart.head, useRealPaymentFactory = true)
    incomingMultiPart.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(1.0)

    val payFSM = mockPayFSM.expectMessageType[akka.actor.ActorRef]
    router.expectMessageType[RouteRequest]

    val failures = RemoteFailure(outgoingAmount, Nil, Sphinx.DecryptedFailurePacket(outgoingNodeId, FinalIncorrectHtlcAmount(42 msat))) :: UnreadableRemoteFailure(outgoingAmount, Nil) :: Nil
    payFSM ! PaymentFailed(relayId, incomingMultiPart.head.add.paymentHash, failures)

    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(FinalIncorrectHtlcAmount(42 msat)), commit = true))
    }

    reputationRecorder.expectMessageType[RecordTrampolineFailure]
    register.expectNoMessage(100 millis)
  }

  test("compute route params") { f =>
    import f._

    // Receive an upstream payment.
    val (nodeRelayer, _) = f.createNodeRelay(incomingSinglePart, useRealPaymentFactory = true)
    nodeRelayer ! NodeRelay.Relay(incomingSinglePart, randomKey().publicKey)

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(1.0)

    val routeRequest = router.expectMessageType[RouteRequest]
    val routeParams = routeRequest.routeParams
    assert(routeParams.boundaries.maxFeeProportional == 0) // should be disabled
    assert(routeParams.boundaries.maxFeeFlat == incomingAmount - outgoingAmount)
    assert(routeParams.boundaries.maxCltv == incomingSinglePart.add.cltvExpiry - outgoingExpiry)
    assert(routeParams.includeLocalChannelCost)
  }

  test("relay incoming multi-part payment") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val (nodeRelayer, parent) = f.createNodeRelay(incomingMultiPart.head)
    incomingMultiPart.dropRight(1).foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey))
    mockPayFSM.expectNoMessage(100 millis) // we should NOT trigger a downstream payment before we received a complete upstream payment

    nodeRelayer ! NodeRelay.Relay(incomingMultiPart.last, randomKey().publicKey)

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(3.5 / 8)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, TimestampMilli.now(), randomKey().publicKey))), 3)
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    // A first downstream HTLC is fulfilled: we should immediately forward the fulfill upstream.
    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))
    }

    // If the payment FSM sends us duplicate preimage events, we should not fulfill a second time upstream.
    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    register.expectNoMessage(100 millis)

    // Once all the downstream payments have settled, we should emit the relayed event.
    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)).toSet == incomingMultiPart.map(i => (i.add.amountMsat, i.add.channelId)).toSet)
    assert(relayEvent.outgoing.nonEmpty)
    reputationRecorder.expectMessageType[RecordTrampolineSuccess]
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay incoming single-part payment") { f =>
    import f._

    // Receive an upstream single-part payment.
    val (nodeRelayer, parent) = f.createNodeRelay(incomingSinglePart)
    nodeRelayer ! NodeRelay.Relay(incomingSinglePart, randomKey().publicKey)

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(1.0)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(Upstream.Hot.Channel(incomingSinglePart.add, TimestampMilli.now(), randomKey().publicKey) :: Nil), 7)
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    val incomingAdd = incomingSinglePart.add
    val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
    assert(fwd.channelId == incomingAdd.channelId)
    assert(fwd.message == CMD_FULFILL_HTLC(incomingAdd.id, paymentPreimage, commit = true))

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == Seq((incomingSinglePart.add.amountMsat, incomingSinglePart.add.channelId)))
    assert(relayEvent.outgoing.nonEmpty)
    reputationRecorder.expectMessageType[RecordTrampolineSuccess]
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay to non-trampoline recipient supporting multi-part") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val hints = List(ExtraHop(randomKey().publicKey, ShortChannelId(42), feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12)))
    val features = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional)
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(outgoingAmount * 3), paymentHash, outgoingNodeKey, Left("Some invoice"), CltvExpiryDelta(18), extraHops = List(hints), paymentMetadata = Some(hex"123456"), features = features)
    val incomingPayments = incomingMultiPart.map(incoming => incoming.copy(innerPayload = IntermediatePayload.NodeRelay.Standard.createNodeRelayToNonTrampolinePayload(
      incoming.innerPayload.amountToForward, outgoingAmount * 3, outgoingExpiry, outgoingNodeId, invoice
    )))
    val (nodeRelayer, parent) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey))

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(2.5 / 8)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, TimestampMilli.now(), randomKey().publicKey))), 2)
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    assert(outgoingPayment.recipient.nodeId == outgoingNodeId)
    assert(outgoingPayment.recipient.totalAmount == outgoingAmount)
    assert(outgoingPayment.recipient.expiry == outgoingExpiry)
    assert(outgoingPayment.recipient.extraEdges.head == ExtraEdge(hints.head.nodeId, outgoingNodeId, ShortChannelId(42), 10 msat, 1, CltvExpiryDelta(12), 1 msat, None))
    assert(outgoingPayment.recipient.isInstanceOf[ClearRecipient])
    val recipient = outgoingPayment.recipient.asInstanceOf[ClearRecipient]
    assert(recipient.nextTrampolineOnion_opt.isEmpty)
    assert(recipient.paymentSecret == invoice.paymentSecret) // we should use the provided secret
    assert(recipient.paymentMetadata_opt == invoice.paymentMetadata) // we should use the provided metadata
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == incomingMultiPart.map(i => (i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.nonEmpty)
    reputationRecorder.expectMessageType[RecordTrampolineSuccess]
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay to non-trampoline recipient without multi-part") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val hints = List(ExtraHop(randomKey().publicKey, ShortChannelId(42), feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12)))
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(outgoingAmount), paymentHash, outgoingNodeKey, Left("Some invoice"), CltvExpiryDelta(18), extraHops = List(hints), paymentMetadata = Some(hex"123456"))
    assert(!invoice.features.hasFeature(BasicMultiPartPayment))
    val incomingPayments = incomingMultiPart.map(incoming => incoming.copy(innerPayload = IntermediatePayload.NodeRelay.Standard.createNodeRelayToNonTrampolinePayload(
      incoming.innerPayload.amountToForward, incoming.innerPayload.amountToForward, outgoingExpiry, outgoingNodeId, invoice
    )))
    val (nodeRelayer, parent) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey))

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(0)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, TimestampMilli.now(), randomKey().publicKey))), 0)
    val outgoingPayment = mockPayFSM.expectMessageType[SendPaymentToNode]
    assert(outgoingPayment.recipient.nodeId == outgoingNodeId)
    assert(outgoingPayment.amount == outgoingAmount)
    assert(outgoingPayment.recipient.expiry == outgoingExpiry)
    assert(outgoingPayment.recipient.extraEdges.head == ExtraEdge(hints.head.nodeId, outgoingNodeId, ShortChannelId(42), 10 msat, 1, CltvExpiryDelta(12), 1 msat, None))
    assert(outgoingPayment.recipient.isInstanceOf[ClearRecipient])
    val recipient = outgoingPayment.recipient.asInstanceOf[ClearRecipient]
    assert(recipient.nextTrampolineOnion_opt.isEmpty)
    assert(recipient.paymentMetadata_opt == invoice.paymentMetadata) // we should use the provided metadata

    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == incomingMultiPart.map(i => (i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.length == 1)
    reputationRecorder.expectMessageType[RecordTrampolineSuccess]
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("fail to relay to non-trampoline recipient missing payment secret") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(outgoingAmount), paymentHash, randomKey(), Left("Some invoice"), CltvExpiryDelta(18))
    val incomingPayments = incomingMultiPart.map(incoming => {
      val innerPayload = IntermediatePayload.NodeRelay.Standard.createNodeRelayToNonTrampolinePayload(incoming.innerPayload.amountToForward, incoming.innerPayload.amountToForward, outgoingExpiry, outgoingNodeId, invoice)
      val invalidPayload = innerPayload.copy(records = TlvStream(innerPayload.records.records.collect { case r if !r.isInstanceOf[OnionPaymentPayloadTlv.PaymentData] => r })) // we remove the payment secret
      incoming.copy(innerPayload = invalidPayload)
    })
    val (nodeRelayer, _) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey))

    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(InvalidOnionPayload(UInt64(8), 0)), commit = true))
    }
  }

  def createPaymentBlindedRoute(nodeId: PublicKey, sessionKey: PrivateKey = randomKey(), pathId: ByteVector = randomBytes32()): PaymentBlindedRoute = {
    val selfPayload = blindedRouteDataCodec.encode(TlvStream(PathId(pathId), PaymentConstraints(CltvExpiry(1234567), 0 msat), AllowedFeatures(Features.empty))).require.bytes
    PaymentBlindedRoute(Sphinx.RouteBlinding.create(sessionKey, Seq(nodeId), Seq(selfPayload)).route, PaymentInfo(1 msat, 2, CltvExpiryDelta(3), 4 msat, 5 msat, Features.empty))
  }

  test("relay to blinded paths without multi-part") { f =>
    import f._

    val (payerKey, chain) = (randomKey(), BlockHash(randomBytes32()))
    val offer = Offer(None, Some("test offer"), outgoingNodeId, Features.empty, chain)
    val request = InvoiceRequest(offer, outgoingAmount, 1, Features.empty, payerKey, chain)
    val invoice = Bolt12Invoice(request, randomBytes32(), outgoingNodeKey, 300 seconds, Features.empty, Seq(createPaymentBlindedRoute(outgoingNodeId)))
    val incomingPayments = incomingMultiPart.map(incoming => RelayToBlindedPathsPacket(incoming.add, incoming.outerPayload, IntermediatePayload.NodeRelay.ToBlindedPaths(
      incoming.innerPayload.amountToForward, outgoingExpiry, invoice
    )))
    val (nodeRelayer, parent) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey))

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(1.0)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, TimestampMilli.now(), randomKey().publicKey))), 7, ignoreNodeId = true)
    val outgoingPayment = mockPayFSM.expectMessageType[SendPaymentToNode]
    assert(outgoingPayment.amount == outgoingAmount)
    assert(outgoingPayment.recipient.expiry == outgoingExpiry)
    assert(outgoingPayment.recipient.isInstanceOf[BlindedRecipient])

    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == incomingMultiPart.map(i => (i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.length == 1)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay to blinded paths with multi-part") { f =>
    import f._

    val (payerKey, chain) = (randomKey(), BlockHash(randomBytes32()))
    val offer = Offer(None, Some("test offer"), outgoingNodeId, Features(Features.BasicMultiPartPayment -> FeatureSupport.Optional), chain)
    val request = InvoiceRequest(offer, outgoingAmount, 1, Features(Features.BasicMultiPartPayment -> FeatureSupport.Optional), payerKey, chain)
    val invoice = Bolt12Invoice(request, randomBytes32(), outgoingNodeKey, 300 seconds, Features(Features.BasicMultiPartPayment -> FeatureSupport.Optional), Seq(createPaymentBlindedRoute(outgoingNodeId)))
    val incomingPayments = incomingMultiPart.map(incoming => RelayToBlindedPathsPacket(incoming.add, incoming.outerPayload, IntermediatePayload.NodeRelay.ToBlindedPaths(
      incoming.innerPayload.amountToForward, outgoingExpiry, invoice
    )))
    val (nodeRelayer, parent) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey))

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(1.5 / 8)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, TimestampMilli.now(), randomKey().publicKey))), 1, ignoreNodeId = true)
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    assert(outgoingPayment.recipient.totalAmount == outgoingAmount)
    assert(outgoingPayment.recipient.expiry == outgoingExpiry)
    assert(outgoingPayment.recipient.isInstanceOf[BlindedRecipient])

    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == incomingMultiPart.map(i => (i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.length == 1)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay to compact blinded paths") { f =>
    import f._

    val (payerKey, chain) = (randomKey(), BlockHash(randomBytes32()))
    val offer = Offer(None, Some("test offer"), outgoingNodeId, Features.empty, chain)
    val request = InvoiceRequest(offer, outgoingAmount, 1, Features.empty, payerKey, chain)
    val paymentBlindedRoute = createPaymentBlindedRoute(outgoingNodeId)
    val scidDir = ShortChannelIdDir(isNode1 = true, RealShortChannelId(123456L))
    val compactPaymentBlindedRoute = paymentBlindedRoute.copy(route = paymentBlindedRoute.route.copy(introductionNodeId = scidDir))
    val invoice = Bolt12Invoice(request, randomBytes32(), outgoingNodeKey, 300 seconds, Features.empty, Seq(compactPaymentBlindedRoute))
    val incomingPayments = incomingMultiPart.map(incoming => RelayToBlindedPathsPacket(incoming.add, incoming.outerPayload, IntermediatePayload.NodeRelay.ToBlindedPaths(
      incoming.innerPayload.amountToForward, outgoingExpiry, invoice
    )))
    val (nodeRelayer, parent) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey))

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(4.5 / 8)

    val getNodeId = router.expectMessageType[Router.GetNodeId]
    assert(getNodeId.isNode1 == scidDir.isNode1)
    assert(getNodeId.shortChannelId == scidDir.scid)
    getNodeId.replyTo ! Some(outgoingNodeId)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, TimestampMilli.now(), randomKey().publicKey))), 4, ignoreNodeId = true)
    val outgoingPayment = mockPayFSM.expectMessageType[SendPaymentToNode]
    assert(outgoingPayment.amount == outgoingAmount)
    assert(outgoingPayment.recipient.expiry == outgoingExpiry)
    assert(outgoingPayment.recipient.isInstanceOf[BlindedRecipient])

    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == incomingMultiPart.map(i => (i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.length == 1)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("fail to relay to compact blinded paths with unknown scid") { f =>
    import f._

    val (payerKey, chain) = (randomKey(), BlockHash(randomBytes32()))
    val offer = Offer(None, Some("test offer"), outgoingNodeId, Features.empty, chain)
    val request = InvoiceRequest(offer, outgoingAmount, 1, Features.empty, payerKey, chain)
    val paymentBlindedRoute = createPaymentBlindedRoute(outgoingNodeId)
    val scidDir = ShortChannelIdDir(isNode1 = true, RealShortChannelId(123456L))
    val compactPaymentBlindedRoute = paymentBlindedRoute.copy(route = paymentBlindedRoute.route.copy(introductionNodeId = scidDir))
    val invoice = Bolt12Invoice(request, randomBytes32(), outgoingNodeKey, 300 seconds, Features.empty, Seq(compactPaymentBlindedRoute))
    val incomingPayments = incomingMultiPart.map(incoming => RelayToBlindedPathsPacket(incoming.add, incoming.outerPayload, IntermediatePayload.NodeRelay.ToBlindedPaths(
      incoming.innerPayload.amountToForward, outgoingExpiry, invoice
    )))
    val (nodeRelayer, _) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey))

    reputationRecorder.expectMessageType[GetTrampolineConfidence].replyTo ! Confidence(1.0)

    val getNodeId = router.expectMessageType[Router.GetNodeId]
    assert(getNodeId.isNode1 == scidDir.isNode1)
    assert(getNodeId.shortChannelId == scidDir.scid)
    getNodeId.replyTo ! None

    mockPayFSM.expectNoMessage(100 millis)

    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, Right(UnknownNextPeer()), commit = true))
    }
  }

  def validateOutgoingCfg(outgoingCfg: SendPaymentConfig, upstream: Upstream, endorsement: Int, ignoreNodeId: Boolean = false): Unit = {
    assert(!outgoingCfg.publishEvent)
    assert(!outgoingCfg.storeInDb)
    assert(outgoingCfg.paymentHash == paymentHash)
    assert(outgoingCfg.invoice.isEmpty)
    assert(ignoreNodeId || outgoingCfg.recipientNodeId == outgoingNodeId)
    (outgoingCfg.upstream, upstream) match {
      case (Upstream.Hot.Trampoline(adds1), Upstream.Hot.Trampoline(adds2)) => assert(adds1.map(_.add) == adds2.map(_.add))
      case _ => assert(outgoingCfg.upstream == upstream)
    }
    assert((outgoingCfg.confidence * 7.999).toInt == endorsement)
  }

  def validateOutgoingPayment(outgoingPayment: SendMultiPartPayment): Unit = {
    assert(outgoingPayment.recipient.nodeId == outgoingNodeId)
    assert(outgoingPayment.recipient.totalAmount == outgoingAmount)
    assert(outgoingPayment.recipient.expiry == outgoingExpiry)
    assert(outgoingPayment.recipient.extraEdges == Nil)
    assert(outgoingPayment.recipient.isInstanceOf[ClearRecipient])
    val recipient = outgoingPayment.recipient.asInstanceOf[ClearRecipient]
    assert(recipient.paymentSecret !== incomingSecret) // we should generate a new outgoing secret
    assert(recipient.nextTrampolineOnion_opt.contains(nextTrampolinePacket))
  }

  def validateRelayEvent(e: TrampolinePaymentRelayed): Unit = {
    assert(e.amountIn == incomingAmount)
    assert(e.amountOut >= outgoingAmount) // outgoingAmount + routing fees
    assert(e.paymentHash == paymentHash)
  }

}

object NodeRelayerSpec {

  val relayId = UUID.randomUUID()

  val paymentPreimage = randomBytes32()
  val paymentHash = Crypto.sha256(paymentPreimage)

  // This is the result of decrypting the incoming trampoline onion packet.
  // It should be forwarded to the next trampoline node.
  val nextTrampolinePacket = OnionRoutingPacket(0, hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619", randomBytes(400), randomBytes32())

  val outgoingAmount = 40_000_000 msat
  val outgoingExpiry = CltvExpiry(490000)
  val outgoingNodeKey = randomKey()
  val outgoingNodeId = outgoingNodeKey.publicKey

  val incomingAmount = 41_000_000 msat
  val incomingSecret = randomBytes32()
  val incomingMultiPart = Seq(
    createValidIncomingPacket(15_000_000 msat, incomingAmount, CltvExpiry(500000), outgoingAmount, outgoingExpiry, endorsementIn = 6),
    createValidIncomingPacket(15_000_000 msat, incomingAmount, CltvExpiry(499999), outgoingAmount, outgoingExpiry, endorsementIn = 5),
    createValidIncomingPacket(11_000_000 msat, incomingAmount, CltvExpiry(499999), outgoingAmount, outgoingExpiry, endorsementIn = 7)
  )
  val incomingSinglePart = createValidIncomingPacket(incomingAmount, incomingAmount, CltvExpiry(500000), outgoingAmount, outgoingExpiry)
  val incomingAsyncPayment: Seq[RelayToTrampolinePacket] = incomingMultiPart.map(p => p.copy(innerPayload = IntermediatePayload.NodeRelay.Standard.createNodeRelayForAsyncPayment(p.innerPayload.amountToForward, p.innerPayload.outgoingCltv, outgoingNodeId)))

  def asyncTimeoutHeight(nodeParams: NodeParams): BlockHeight =
    nodeParams.currentBlockHeight + nodeParams.relayParams.asyncPaymentsParams.holdTimeoutBlocks

  def asyncSafetyHeight(paymentPackets: Seq[RelayToTrampolinePacket], nodeParams: NodeParams): BlockHeight =
    (paymentPackets.map(_.outerPayload.expiry).min - nodeParams.relayParams.asyncPaymentsParams.cancelSafetyBeforeTimeout).blockHeight

  def createSuccessEvent(): PaymentSent =
    PaymentSent(relayId, paymentHash, paymentPreimage, outgoingAmount, outgoingNodeId, Seq(PaymentSent.PartialPayment(UUID.randomUUID(), outgoingAmount, 10 msat, randomBytes32(), None)))

  def createValidIncomingPacket(amountIn: MilliSatoshi, totalAmountIn: MilliSatoshi, expiryIn: CltvExpiry, amountOut: MilliSatoshi, expiryOut: CltvExpiry, endorsementIn: Int = 7): RelayToTrampolinePacket = {
    val outerPayload = FinalPayload.Standard.createPayload(amountIn, totalAmountIn, expiryIn, incomingSecret, None)
    val tlvs = TlvStream[UpdateAddHtlcTlv](UpdateAddHtlcTlv.Endorsement(endorsementIn))
    RelayToTrampolinePacket(
      UpdateAddHtlc(randomBytes32(), Random.nextInt(100), amountIn, paymentHash, expiryIn, TestConstants.emptyOnionPacket, tlvs),
      outerPayload,
      IntermediatePayload.NodeRelay.Standard(amountOut, expiryOut, outgoingNodeId),
      nextTrampolinePacket)
  }

  def createPartialIncomingPacket(paymentHash: ByteVector32, paymentSecret: ByteVector32): RelayToTrampolinePacket = {
    val (expiryIn, expiryOut) = (CltvExpiry(500000), CltvExpiry(490000))
    val amountIn = incomingAmount / 2
    RelayToTrampolinePacket(
      UpdateAddHtlc(randomBytes32(), Random.nextInt(100), amountIn, paymentHash, expiryIn, TestConstants.emptyOnionPacket, None, 1.0),
      FinalPayload.Standard.createPayload(amountIn, incomingAmount, expiryIn, paymentSecret, None),
      IntermediatePayload.NodeRelay.Standard(outgoingAmount, expiryOut, outgoingNodeId),
      nextTrampolinePacket)
  }

}