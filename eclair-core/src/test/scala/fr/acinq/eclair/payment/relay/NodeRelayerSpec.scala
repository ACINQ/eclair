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

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.adapter._
import com.softwaremill.quicklens.ModifyPimp
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto}
import fr.acinq.eclair.EncodedNodeId.ShortChannelIdDir
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{AsyncPaymentPrototype, BasicMultiPartPayment, PaymentSecret, VariableLengthOnion}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.io.{Peer, PeerReadyManager, Switchboard}
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment.IncomingPaymentPacket.{RelayToBlindedPathsPacket, RelayToNonTrampolinePacket, RelayToTrampolinePacket}
import fr.acinq.eclair.payment.Invoice.ExtraEdge
import fr.acinq.eclair.payment.OutgoingPaymentPacket.NodePayload
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.NodeRelayer.PaymentKey
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.{PreimageReceived, SendMultiPartPayment}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPaymentToNode
import fr.acinq.eclair.payment.send.{BlindedRecipient, ClearRecipient}
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.router.Router.{ChannelHop, HopRelayParams, PaymentRouteNotFound, RouteRequest}
import fr.acinq.eclair.router.{BalanceTooLow, BlindedRouteCreation, RouteNotFound, Router}
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.wire.protocol.PaymentOnion.{FinalPayload, IntermediatePayload}
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv.{AllowedFeatures, PathId, PaymentConstraints}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Alias, BlockHeight, Bolt11Feature, Bolt12Feature, CltvExpiry, CltvExpiryDelta, EncodedNodeId, FeatureSupport, Features, MilliSatoshi, MilliSatoshiLong, NodeParams, RealShortChannelId, ShortChannelId, TestConstants, TimestampMilli, UInt64, randomBytes32, randomKey}
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

  val wakeUpEnabled = "wake_up_enabled"
  val wakeUpTimeout = "wake_up_timeout"
  val onTheFlyFunding = "on_the_fly_funding"

  case class FixtureParam(nodeParams: NodeParams, router: TestProbe[Any], register: TestProbe[Any], mockPayFSM: TestProbe[Any], eventListener: TestProbe[PaymentEvent]) {
    def createNodeRelay(packetIn: IncomingPaymentPacket.NodeRelayPacket, useRealPaymentFactory: Boolean = false): (ActorRef[NodeRelay.Command], TestProbe[NodeRelayer.Command]) = {
      val parent = TestProbe[NodeRelayer.Command]("parent-relayer")
      val outgoingPaymentFactory = if (useRealPaymentFactory) RealOutgoingPaymentFactory(this) else FakeOutgoingPaymentFactory(this)
      val nodeRelay = testKit.spawn(NodeRelay(nodeParams, parent.ref, register.ref.toClassic, relayId, packetIn, outgoingPaymentFactory, router.ref.toClassic))
      (nodeRelay, parent)
    }

    def createWakeUpActors(): (TestProbe[PeerReadyManager.Register], TestProbe[Switchboard.GetPeerInfo]) = {
      val peerReadyManager = TestProbe[PeerReadyManager.Register]()
      system.receptionist ! Receptionist.Register(PeerReadyManager.PeerReadyManagerServiceKey, peerReadyManager.ref)
      val switchboard = TestProbe[Switchboard.GetPeerInfo]()
      system.receptionist ! Receptionist.Register(Switchboard.SwitchboardServiceKey, switchboard.ref)
      (peerReadyManager, switchboard)
    }

    def cleanUpWakeUpActors(peerReadyManager: TestProbe[PeerReadyManager.Register], switchboard: TestProbe[Switchboard.GetPeerInfo]): Unit = {
      system.receptionist ! Receptionist.Deregister(PeerReadyManager.PeerReadyManagerServiceKey, peerReadyManager.ref)
      system.receptionist ! Receptionist.Deregister(Switchboard.SwitchboardServiceKey, switchboard.ref)
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
      val outgoingPayFSM = NodeRelay.SimpleOutgoingPaymentFactory(f.nodeParams, f.router.ref.toClassic, f.register.ref.toClassic, None).spawnOutgoingPayFSM(context, cfg, multiPart)
      f.mockPayFSM.ref ! outgoingPayFSM
      outgoingPayFSM
    }
  }

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams = TestConstants.Bob.nodeParams
      .modify(_.multiPartPaymentExpiry).setTo(5 seconds)
      .modify(_.relayParams.asyncPaymentsParams.holdTimeoutBlocks).setToIf(test.tags.contains("long_hold_timeout"))(200000) // timeout after payment expires
      .modify(_.peerWakeUpConfig.enabled).setToIf(test.tags.contains(wakeUpEnabled))(true)
      .modify(_.peerWakeUpConfig.timeout).setToIf(test.tags.contains(wakeUpTimeout))(100 millis)
      .modify(_.features.activated).usingIf(test.tags.contains(onTheFlyFunding))(_ + (Features.OnTheFlyFunding -> FeatureSupport.Optional))
      .modify(_.features.activated).usingIf(test.tags.contains(wakeUpEnabled))(_ + (Features.WakeUpNotificationClient -> FeatureSupport.Optional))
    val router = TestProbe[Any]("router")
    val register = TestProbe[Any]("register")
    val eventListener = TestProbe[PaymentEvent]("event-listener")
    system.eventStream ! EventStream.Subscribe(eventListener.ref)
    val mockPayFSM = TestProbe[Any]("pay-fsm")
    withFixture(test.toNoArgTest(FixtureParam(nodeParams, router, register, mockPayFSM, eventListener)))
  }

  test("create child handlers for new payments") { f =>
    import f._
    val probe = TestProbe[Any]()
    val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, register.ref.toClassic, FakeOutgoingPaymentFactory(f), router.ref.toClassic))
    parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
    probe.expectMessage(Map.empty)

    val (paymentHash1, paymentSecret1) = (randomBytes32(), randomBytes32())
    val payment1 = createPartialIncomingPacket(paymentHash1, paymentSecret1)
    parentRelayer ! NodeRelayer.Relay(payment1, randomKey().publicKey, 0.01)
    parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
    val pending1 = probe.expectMessageType[Map[PaymentKey, ActorRef[NodeRelay.Command]]]
    assert(pending1.keySet == Set(PaymentKey(paymentHash1, paymentSecret1)))

    val (paymentHash2, paymentSecret2) = (randomBytes32(), randomBytes32())
    val payment2 = createPartialIncomingPacket(paymentHash2, paymentSecret2)
    parentRelayer ! NodeRelayer.Relay(payment2, randomKey().publicKey, 0.01)
    parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
    val pending2 = probe.expectMessageType[Map[PaymentKey, ActorRef[NodeRelay.Command]]]
    assert(pending2.keySet == Set(PaymentKey(paymentHash1, paymentSecret1), PaymentKey(paymentHash2, paymentSecret2)))

    val payment3a = createPartialIncomingPacket(paymentHash1, paymentSecret2)
    parentRelayer ! NodeRelayer.Relay(payment3a, randomKey().publicKey, 0.01)
    parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
    val pending3 = probe.expectMessageType[Map[PaymentKey, ActorRef[NodeRelay.Command]]]
    assert(pending3.keySet == Set(PaymentKey(paymentHash1, paymentSecret1), PaymentKey(paymentHash2, paymentSecret2), PaymentKey(paymentHash1, paymentSecret2)))

    val payment3b = createPartialIncomingPacket(paymentHash1, paymentSecret2)
    parentRelayer ! NodeRelayer.Relay(payment3b, randomKey().publicKey, 0.01)
    parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
    val pending4 = probe.expectMessageType[Map[PaymentKey, ActorRef[NodeRelay.Command]]]
    assert(pending4.keySet == Set(PaymentKey(paymentHash1, paymentSecret1), PaymentKey(paymentHash2, paymentSecret2), PaymentKey(paymentHash1, paymentSecret2)))

    register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
  }

  test("stop child handlers when relay is complete") { f =>
    import f._
    val probe = TestProbe[Any]()
    val outgoingPaymentFactory = FakeOutgoingPaymentFactory(f)

    {
      val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, register.ref.toClassic, outgoingPaymentFactory, router.ref.toClassic))
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(Map.empty)
    }
    {
      val (paymentHash1, paymentSecret1, child1) = (randomBytes32(), randomBytes32(), TestProbe[NodeRelay.Command]())
      val (paymentHash2, paymentSecret2, child2) = (randomBytes32(), randomBytes32(), TestProbe[NodeRelay.Command]())
      val children = Map(PaymentKey(paymentHash1, paymentSecret1) -> child1.ref, PaymentKey(paymentHash2, paymentSecret2) -> child2.ref)
      val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, register.ref.toClassic, outgoingPaymentFactory, router.ref.toClassic, children))
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
      val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, register.ref.toClassic, outgoingPaymentFactory, router.ref.toClassic, children))
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(children)

      parentRelayer ! NodeRelayer.RelayComplete(child1.ref, paymentHash, paymentSecret1)
      child1.expectMessage(NodeRelay.Stop)
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(Map(PaymentKey(paymentHash, paymentSecret2) -> child2.ref))
    }
    {
      val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, register.ref.toClassic, outgoingPaymentFactory, router.ref.toClassic))
      parentRelayer ! NodeRelayer.Relay(incomingMultiPart.head, randomKey().publicKey, 0.01)
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      val pending1 = probe.expectMessageType[Map[PaymentKey, ActorRef[NodeRelay.Command]]]
      assert(pending1.size == 1)
      assert(pending1.head._1 == PaymentKey(paymentHash, incomingSecret))

      parentRelayer ! NodeRelayer.RelayComplete(pending1.head._2, paymentHash, incomingSecret)
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(Map.empty)

      parentRelayer ! NodeRelayer.Relay(incomingMultiPart.head, randomKey().publicKey, 0.01)
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
    incomingMultiPart.dropRight(1).foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))
    // after a while the payment times out
    incomingMultiPart.dropRight(1).foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]](30 seconds)
      assert(fwd.channelId == p.add.channelId)
      val failure = FailureReason.LocalFailure(PaymentTimeout())
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, failure, Some(FailureAttributionData(p.receivedAt, None)), commit = true))
    }

    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("fail all extraneous multi-part incoming HTLCs") { f =>
    import f._

    val (nodeRelayer, _) = f.createNodeRelay(incomingMultiPart.head)
    // We send all the parts of a mpp
    incomingMultiPart.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))
    // and then one extra
    val extraReceivedAt = TimestampMilli.now()
    val extra = IncomingPaymentPacket.RelayToTrampolinePacket(
      UpdateAddHtlc(randomBytes32(), Random.nextInt(100), 1000 msat, paymentHash, CltvExpiry(499990), TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None),
      FinalPayload.Standard.createPayload(1000 msat, incomingAmount, CltvExpiry(499990), incomingSecret, None),
      IntermediatePayload.NodeRelay.Standard(outgoingAmount, outgoingExpiry, outgoingNodeId),
      createTrampolinePacket(outgoingAmount, outgoingExpiry),
      extraReceivedAt)
    nodeRelayer ! NodeRelay.Relay(extra, randomKey().publicKey, 0.01)

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

    // the extra payment will be rejected
    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId == extra.add.channelId)
    val failure = FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(extra.add.amountMsat, nodeParams.currentBlockHeight))
    assert(fwd.message == CMD_FAIL_HTLC(extra.add.id, failure, Some(FailureAttributionData(extraReceivedAt, None)), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail all additional incoming HTLCs once already relayed out") { f =>
    import f._

    val (nodeRelayer, _) = f.createNodeRelay(incomingMultiPart.head)
    // Receive a complete upstream multi-part payment, which we relay out.
    incomingMultiPart.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList))
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)

    // Receive new extraneous multi-part HTLC.
    val receivedAt1 = TimestampMilli.now()
    val i1 = IncomingPaymentPacket.RelayToTrampolinePacket(
      UpdateAddHtlc(randomBytes32(), Random.nextInt(100), 1000 msat, paymentHash, CltvExpiry(499990), TestConstants.emptyOnionPacket, None, 6, None),
      FinalPayload.Standard.createPayload(1000 msat, incomingAmount, CltvExpiry(499990), incomingSecret, None),
      IntermediatePayload.NodeRelay.Standard(outgoingAmount, outgoingExpiry, outgoingNodeId),
      createTrampolinePacket(outgoingAmount, outgoingExpiry),
      receivedAt1)
    nodeRelayer ! NodeRelay.Relay(i1, randomKey().publicKey, 0.01)

    val fwd1 = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd1.channelId == i1.add.channelId)
    val failure1 = FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight))
    assert(fwd1.message == CMD_FAIL_HTLC(i1.add.id, failure1, Some(FailureAttributionData(receivedAt1, None)), commit = true))

    // Receive new HTLC with different details, but for the same payment hash.
    val receivedAt2 = TimestampMilli.now() + 1.millis
    val i2 = IncomingPaymentPacket.RelayToTrampolinePacket(
      UpdateAddHtlc(randomBytes32(), Random.nextInt(100), 1500 msat, paymentHash, CltvExpiry(499990), TestConstants.emptyOnionPacket, None, 4, None),
      PaymentOnion.FinalPayload.Standard.createPayload(1500 msat, 1500 msat, CltvExpiry(499990), incomingSecret, None),
      IntermediatePayload.NodeRelay.Standard(1250 msat, outgoingExpiry, outgoingNodeId),
      createTrampolinePacket(outgoingAmount, outgoingExpiry),
      receivedAt2)
    nodeRelayer ! NodeRelay.Relay(i2, randomKey().publicKey, 0.01)

    val fwd2 = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd1.channelId == i1.add.channelId)
    val failure2 = FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(1500 msat, nodeParams.currentBlockHeight))
    assert(fwd2.message == CMD_FAIL_HTLC(i2.add.id, failure2, Some(FailureAttributionData(receivedAt2, None)), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when expiry is too soon (single-part)") { f =>
    import f._

    val expiryIn = CltvExpiry(500000) // not ok (delta = 100)
    val expiryOut = CltvExpiry(499900)
    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, expiryIn, 1000000 msat, expiryOut, TimestampMilli.now())
    val (nodeRelayer, _) = f.createNodeRelay(p)
    nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId == p.add.channelId)
    assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(TrampolineExpiryTooSoon()), Some(FailureAttributionData(p.receivedAt, Some(p.receivedAt))), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when final expiry is below chain height") { f =>
    import f._

    val expiryIn = CltvExpiry(500000)
    val expiryOut = CltvExpiry(300000) // not ok (chain height = 400000)
    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, expiryIn, 1000000 msat, expiryOut, TimestampMilli.now())
    val (nodeRelayer, _) = f.createNodeRelay(p)
    nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId == p.add.channelId)
    assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(TrampolineExpiryTooSoon()), Some(FailureAttributionData(p.receivedAt, Some(p.receivedAt))), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when expiry is too soon (multi-part)") { f =>
    import f._

    val expiryIn1 = CltvExpiry(510000) // ok
    val expiryIn2 = CltvExpiry(500000) // not ok (delta = 100)
    val expiryOut = CltvExpiry(499900)
    val p = Seq(
      createValidIncomingPacket(2000000 msat, 3000000 msat, expiryIn1, 2100000 msat, expiryOut, TimestampMilli(10)),
      createValidIncomingPacket(1000000 msat, 3000000 msat, expiryIn2, 2100000 msat, expiryOut, TimestampMilli(20))
    )
    val (nodeRelayer, _) = f.createNodeRelay(p.head)
    p.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01))

    p.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(TrampolineExpiryTooSoon()), Some(FailureAttributionData(p.receivedAt, Some(TimestampMilli(20)))), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("relay the payment immediately when the async payment feature is disabled") { f =>
    import f._

    assert(!nodeParams.features.hasFeature(AsyncPaymentPrototype))

    val (nodeRelayer, parent) = createNodeRelay(incomingAsyncPayment.head)
    incomingAsyncPayment.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01))

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

    // upstream payment relayed
    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingAsyncPayment.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList))
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    // A first downstream HTLC is fulfilled: we should immediately forward the fulfill upstream.
    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage, None)
    incomingAsyncPayment.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, Some(FulfillAttributionData(p.receivedAt, Some(incomingAsyncPayment.last.receivedAt), None)), commit = true))
    }

    // Once all the downstream payments have settled, we should emit the relayed event.
    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)).toSet == incomingAsyncPayment.map(i => (i.add.amountMsat, i.add.channelId)).toSet)
    assert(relayEvent.outgoing.nonEmpty)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("fail to relay when fees are insufficient (single-part)") { f =>
    import f._

    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, CltvExpiry(500000), 1999000 msat, CltvExpiry(490000), TimestampMilli.now())
    val (nodeRelayer, _) = f.createNodeRelay(p)
    nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId == p.add.channelId)
    assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(TrampolineFeeInsufficient()), Some(FailureAttributionData(p.receivedAt, Some(p.receivedAt))), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when fees are insufficient (multi-part)") { f =>
    import f._

    val p = Seq(
      createValidIncomingPacket(2000000 msat, 3000000 msat, CltvExpiry(500000), 2999000 msat, CltvExpiry(400000), TimestampMilli(153)),
      createValidIncomingPacket(1000000 msat, 3000000 msat, CltvExpiry(500000), 2999000 msat, CltvExpiry(400000), TimestampMilli(486))
    )
    val (nodeRelayer, _) = f.createNodeRelay(p.head)
    p.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01))

    p.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(TrampolineFeeInsufficient()), Some(FailureAttributionData(p.receivedAt, Some(TimestampMilli(486)))), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when amount is 0 (single-part)") { f =>
    import f._

    val p = createValidIncomingPacket(5000000 msat, 5000000 msat, CltvExpiry(500000), 0 msat, CltvExpiry(490000), TimestampMilli.now())
    val (nodeRelayer, _) = f.createNodeRelay(p)
    nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId == p.add.channelId)
    assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(InvalidOnionPayload(UInt64(2), 0)), Some(FailureAttributionData(p.receivedAt, Some(p.receivedAt))), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when amount is 0 (multi-part)") { f =>
    import f._

    val p = Seq(
      createValidIncomingPacket(4000000 msat, 5000000 msat, CltvExpiry(500000), 0 msat, CltvExpiry(490000), TimestampMilli(7)),
      createValidIncomingPacket(1000000 msat, 5000000 msat, CltvExpiry(500000), 0 msat, CltvExpiry(490000), TimestampMilli(9))
    )
    val (nodeRelayer, _) = f.createNodeRelay(p.head)
    p.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01))

    p.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(InvalidOnionPayload(UInt64(2), 0)), Some(FailureAttributionData(p.receivedAt, Some(TimestampMilli(9)))), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("fail to relay because outgoing balance isn't sufficient (low fees)") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val (nodeRelayer, _) = f.createNodeRelay(incomingMultiPart.head)
    incomingMultiPart.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01))

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

    mockPayFSM.expectMessageType[SendPaymentConfig]
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = mockPayFSM.expectMessageType[SendMultiPartPayment].replyTo

    // The proposed fees are low, so we ask the sender to raise them.
    nodeRelayerAdapters ! PaymentFailed(relayId, paymentHash, LocalFailure(outgoingAmount, Nil, BalanceTooLow) :: Nil)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(TrampolineFeeInsufficient()), Some(FailureAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt))), commit = true))
    }

    register.expectNoMessage(100 millis)
    eventListener.expectNoMessage(100 millis)
  }

  test("fail to relay because outgoing balance isn't sufficient (high fees)") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val incoming = Seq(
      createValidIncomingPacket(outgoingAmount, outgoingAmount * 2, CltvExpiry(500000), outgoingAmount, outgoingExpiry, TimestampMilli(1)),
      createValidIncomingPacket(outgoingAmount, outgoingAmount * 2, CltvExpiry(500000), outgoingAmount, outgoingExpiry, TimestampMilli(2)),
    )
    val (nodeRelayer, _) = f.createNodeRelay(incoming.head, useRealPaymentFactory = true)
    incoming.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01))

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

    val payFSM = mockPayFSM.expectMessageType[akka.actor.ActorRef]
    router.expectMessageType[RouteRequest]
    payFSM ! PaymentRouteNotFound(BalanceTooLow)

    incoming.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(TemporaryNodeFailure()), Some(FailureAttributionData(p.receivedAt, Some(incoming.last.receivedAt))), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("fail to relay because incoming fee isn't enough to find routes downstream") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val (nodeRelayer, _) = f.createNodeRelay(incomingMultiPart.head, useRealPaymentFactory = true)
    incomingMultiPart.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01))

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

    val payFSM = mockPayFSM.expectMessageType[akka.actor.ActorRef]
    router.expectMessageType[RouteRequest]

    // If we're having a hard time finding routes, raising the fee/cltv will likely help.
    val failures = LocalFailure(outgoingAmount, Nil, RouteNotFound) :: RemoteFailure(outgoingAmount, Nil, Sphinx.DecryptedFailurePacket(outgoingNodeId, PermanentNodeFailure())) :: LocalFailure(outgoingAmount, Nil, RouteNotFound) :: Nil
    payFSM ! PaymentFailed(relayId, incomingMultiPart.head.add.paymentHash, failures)

    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(TrampolineFeeInsufficient()), Some(FailureAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt))), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("fail to relay because of downstream failures") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val (nodeRelayer, _) = f.createNodeRelay(incomingMultiPart.head, useRealPaymentFactory = true)
    incomingMultiPart.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01))

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

    val payFSM = mockPayFSM.expectMessageType[akka.actor.ActorRef]
    router.expectMessageType[RouteRequest]

    val failures = RemoteFailure(outgoingAmount, Nil, Sphinx.DecryptedFailurePacket(outgoingNodeId, FinalIncorrectHtlcAmount(42 msat))) :: UnreadableRemoteFailure(outgoingAmount, Nil, Sphinx.CannotDecryptFailurePacket(ByteVector.empty, None), Nil) :: Nil
    payFSM ! PaymentFailed(relayId, incomingMultiPart.head.add.paymentHash, failures)

    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(FinalIncorrectHtlcAmount(42 msat)), Some(FailureAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt))), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("compute route params") { f =>
    import f._

    // Receive an upstream payment.
    val (nodeRelayer, _) = f.createNodeRelay(incomingSinglePart, useRealPaymentFactory = true)
    nodeRelayer ! NodeRelay.Relay(incomingSinglePart, randomKey().publicKey, 0.01)

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

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
    incomingMultiPart.dropRight(1).foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01))
    mockPayFSM.expectNoMessage(100 millis) // we should NOT trigger a downstream payment before we received a complete upstream payment

    nodeRelayer ! NodeRelay.Relay(incomingMultiPart.last, randomKey().publicKey, 0.01)

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList))
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    // A first downstream HTLC is fulfilled: we should immediately forward the fulfill upstream.
    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage, None)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, Some(FulfillAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt), None)), commit = true))
    }

    // If the payment FSM sends us duplicate preimage events, we should not fulfill a second time upstream.
    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage, None)
    register.expectNoMessage(100 millis)

    // Once all the downstream payments have settled, we should emit the relayed event.
    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)).toSet == incomingMultiPart.map(i => (i.add.amountMsat, i.add.channelId)).toSet)
    assert(relayEvent.outgoing.nonEmpty)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("ignore downstream failures after fulfill") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val (nodeRelayer, parent) = f.createNodeRelay(incomingMultiPart.head)
    incomingMultiPart.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01))

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

    mockPayFSM.expectMessageType[SendPaymentConfig]
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    // A first downstream HTLC is fulfilled: we immediately forward the fulfill upstream.
    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage, None)
    val fulfills = incomingMultiPart.map { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, Some(FulfillAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt), None)), commit = true))
      fwd
    }
    // We store the commands in our DB in case we restart before relaying them upstream.
    val upstreamChannels = fulfills.map(_.channelId).toSet
    upstreamChannels.foreach(channelId => {
      eventually(assert(nodeParams.db.pendingCommands.listSettlementCommands(channelId).toSet == fulfills.filter(_.channelId == channelId).map(_.message.copy(commit = false)).toSet))
    })

    // The remaining downstream HTLCs are failed (e.g. because a revoked commitment confirmed that doesn't include them).
    // The corresponding commands conflict with the previous fulfill and are ignored.
    val downstreamHtlc = UpdateAddHtlc(randomBytes32(), 7, outgoingAmount, paymentHash, outgoingExpiry, TestConstants.emptyOnionPacket, None, 3, None)
    val failure = LocalFailure(outgoingAmount, Nil, HtlcOverriddenByLocalCommit(randomBytes32(), downstreamHtlc))
    nodeRelayerAdapters ! PaymentFailed(relayId, incomingMultiPart.head.add.paymentHash, Seq(failure))
    eventListener.expectNoMessage(100 millis) // the payment didn't succeed, but didn't fail either, so we just ignore it
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
    upstreamChannels.foreach(channelId => {
      assert(nodeParams.db.pendingCommands.listSettlementCommands(channelId).toSet == fulfills.filter(_.channelId == channelId).map(_.message.copy(commit = false)).toSet)
    })
  }

  test("relay incoming single-part payment") { f =>
    import f._

    // Receive an upstream single-part payment.
    val (nodeRelayer, parent) = f.createNodeRelay(incomingSinglePart)
    nodeRelayer ! NodeRelay.Relay(incomingSinglePart, randomKey().publicKey, 0.01)

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(Upstream.Hot.Channel(incomingSinglePart.add, incomingSinglePart.receivedAt, randomKey().publicKey, 0.01) :: Nil))
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage, None)
    val incomingAdd = incomingSinglePart.add
    val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
    assert(fwd.channelId == incomingAdd.channelId)
    assert(fwd.message == CMD_FULFILL_HTLC(incomingAdd.id, paymentPreimage, Some(FulfillAttributionData(incomingSinglePart.receivedAt, Some(incomingSinglePart.receivedAt), None)), commit = true))

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == Seq((incomingSinglePart.add.amountMsat, incomingSinglePart.add.channelId)))
    assert(relayEvent.outgoing.nonEmpty)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay incoming multi-part payment with on-the-fly funding", Tag(wakeUpEnabled), Tag(onTheFlyFunding)) { f =>
    import f._

    val (peerReadyManager, switchboard) = createWakeUpActors()

    // Receive an upstream multi-part payment.
    val (nodeRelayer, parent) = f.createNodeRelay(incomingMultiPart.head)
    incomingMultiPart.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01))

    // We first check if the outgoing node is our peer and supports wake-up notifications.
    val peerFeaturesRequest = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]]
    assert(peerFeaturesRequest.nodeId == outgoingNodeId)
    peerFeaturesRequest.message.replyTo.foreach(_ ! Peer.PeerInfo(TestProbe[Any]().ref.toClassic, outgoingNodeId, Peer.DISCONNECTED, Some(nodeParams.features.initFeatures()), None, Set.empty))

    // The remote node is a wallet node: we wake them up before relaying the payment.
    val wakeUp = peerReadyManager.expectMessageType[PeerReadyManager.Register]
    assert(wakeUp.remoteNodeId == outgoingNodeId)
    wakeUp.replyTo ! PeerReadyManager.Registered(outgoingNodeId, otherAttempts = 0)
    val peerInfo = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    assert(peerInfo.remoteNodeId == outgoingNodeId)
    peerInfo.replyTo ! Peer.PeerInfo(TestProbe[Any]().ref.toClassic, outgoingNodeId, Peer.CONNECTED, Some(nodeParams.features.initFeatures()), None, Set.empty)
    cleanUpWakeUpActors(peerReadyManager, switchboard)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList))
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)

    // The outgoing payment fails because we don't have enough balance: we trigger on-the-fly funding.
    outgoingPayment.replyTo ! PaymentFailed(relayId, paymentHash, LocalFailure(outgoingAmount, Nil, BalanceTooLow) :: Nil)
    val fwd = register.expectMessageType[Register.ForwardNodeId[Peer.ProposeOnTheFlyFunding]]
    assert(fwd.nodeId == outgoingNodeId)
    assert(fwd.message.nextPathKey_opt.isEmpty)
    assert(fwd.message.onion.payload.size == PaymentOnionCodecs.paymentOnionPayloadLength)
    // We verify that the next node is able to decrypt the onion that we will send in will_add_htlc.
    val dummyAdd = UpdateAddHtlc(randomBytes32(), 0, fwd.message.amount, fwd.message.paymentHash, fwd.message.expiry, fwd.message.onion, None, 7, None)
    val Right(incoming) = IncomingPaymentPacket.decrypt(dummyAdd, outgoingNodeKey, nodeParams.features)
    assert(incoming.isInstanceOf[IncomingPaymentPacket.FinalPacket])
    val finalPayload = incoming.asInstanceOf[IncomingPaymentPacket.FinalPacket].payload.asInstanceOf[FinalPayload.Standard]
    assert(finalPayload.amount == fwd.message.amount)
    assert(finalPayload.expiry == fwd.message.expiry)
    assert(finalPayload.paymentSecret == paymentSecret)

    // Once on-the-fly funding has been proposed, the payment isn't our responsibility anymore.
    fwd.message.replyTo ! Peer.ProposeOnTheFlyFundingResponse.Proposed
    parent.expectMessageType[NodeRelayer.RelayComplete]
  }

  test("relay incoming multi-part payment with on-the-fly funding (non-liquidity failure)", Tag(wakeUpEnabled), Tag(onTheFlyFunding)) { f =>
    import f._

    val (peerReadyManager, switchboard) = createWakeUpActors()

    // Receive an upstream multi-part payment.
    val (nodeRelayer, parent) = f.createNodeRelay(incomingMultiPart.head)
    incomingMultiPart.foreach(p => nodeRelayer ! NodeRelay.Relay(p, randomKey().publicKey, 0.01))

    // We first check if the outgoing node is our peer and supports wake-up notifications.
    val peerFeaturesRequest = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]]
    assert(peerFeaturesRequest.nodeId == outgoingNodeId)
    peerFeaturesRequest.message.replyTo.foreach(_ ! Peer.PeerInfo(TestProbe[Any]().ref.toClassic, outgoingNodeId, Peer.DISCONNECTED, Some(nodeParams.features.initFeatures()), None, Set.empty))

    // The remote node is a wallet node: we wake them up before relaying the payment.
    peerReadyManager.expectMessageType[PeerReadyManager.Register].replyTo ! PeerReadyManager.Registered(outgoingNodeId, otherAttempts = 0)
    val peerInfo = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    assert(peerInfo.remoteNodeId == outgoingNodeId)
    peerInfo.replyTo ! Peer.PeerInfo(TestProbe[Any]().ref.toClassic, outgoingNodeId, Peer.CONNECTED, Some(nodeParams.features.initFeatures()), None, Set.empty)
    cleanUpWakeUpActors(peerReadyManager, switchboard)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList))
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)

    // The outgoing payment fails, but it's not a liquidity issue.
    outgoingPayment.replyTo ! PaymentFailed(relayId, paymentHash, RemoteFailure(outgoingAmount, Nil, Sphinx.DecryptedFailurePacket(outgoingNodeId, TemporaryNodeFailure())) :: Nil)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(TemporaryNodeFailure()), Some(FailureAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt))), commit = true))
    }
    parent.expectMessageType[NodeRelayer.RelayComplete]
  }

  test("relay to non-trampoline recipient supporting multi-part") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val hints = List(ExtraHop(randomKey().publicKey, ShortChannelId(42), feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12)))
    val features = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional)
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(outgoingAmount * 3), paymentHash, outgoingNodeKey, Left("Some invoice"), CltvExpiryDelta(18), extraHops = List(hints), paymentMetadata = Some(hex"123456"), features = features)
    val incomingPayments = incomingMultiPart.map(incoming => {
      val innerPayload = IntermediatePayload.NodeRelay.ToNonTrampoline(incoming.innerPayload.amountToForward, outgoingAmount * 3, outgoingExpiry, outgoingNodeId, invoice)
      RelayToNonTrampolinePacket(incoming.add, incoming.outerPayload, innerPayload, incoming.receivedAt)
    })
    val (nodeRelayer, parent) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList))
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

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage, None)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, Some(FulfillAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt), None)), commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == incomingMultiPart.map(i => (i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.nonEmpty)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay to non-trampoline recipient without multi-part") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val hints = List(ExtraHop(randomKey().publicKey, ShortChannelId(42), feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12)))
    val invoice = Bolt11Invoice(Block.LivenetGenesisBlock.hash, Some(outgoingAmount), paymentHash, outgoingNodeKey, Left("Some invoice"), CltvExpiryDelta(18), extraHops = List(hints), paymentMetadata = Some(hex"123456"))
    assert(!invoice.features.hasFeature(BasicMultiPartPayment))
    val incomingPayments = incomingMultiPart.map(incoming => {
      val innerPayload = IntermediatePayload.NodeRelay.ToNonTrampoline(incoming.innerPayload.amountToForward, incoming.innerPayload.amountToForward, outgoingExpiry, outgoingNodeId, invoice)
      RelayToNonTrampolinePacket(incoming.add, incoming.outerPayload, innerPayload, incoming.receivedAt)
    })
    val (nodeRelayer, parent) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))

    val getPeerInfo = register.expectMessageType[Register.ForwardNodeId[Peer.GetPeerInfo]](100 millis)
    getPeerInfo.message.replyTo.foreach(_ ! Peer.PeerNotFound(getPeerInfo.nodeId))

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingMultiPart.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList))
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

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage, None)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, Some(FulfillAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt), None)), commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == incomingMultiPart.map(i => (i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.length == 1)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay to blinded paths without multi-part") { f =>
    import f._

    val incomingPayments = createIncomingPaymentsToRemoteBlindedPath(Features.empty, None)
    val (nodeRelayer, parent) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingPayments.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList), ignoreNodeId = true)
    val outgoingPayment = mockPayFSM.expectMessageType[SendPaymentToNode]
    assert(outgoingPayment.amount == outgoingAmount)
    assert(outgoingPayment.recipient.expiry == outgoingExpiry)
    assert(outgoingPayment.recipient.isInstanceOf[BlindedRecipient])

    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage, None)
    incomingPayments.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, Some(FulfillAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt), None)), commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == incomingPayments.map(i => (i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.length == 1)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay to blinded paths with multi-part") { f =>
    import f._

    val incomingPayments = createIncomingPaymentsToRemoteBlindedPath(Features(Features.BasicMultiPartPayment -> FeatureSupport.Optional), None)
    val (nodeRelayer, parent) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingPayments.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList), ignoreNodeId = true)
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    assert(outgoingPayment.recipient.totalAmount == outgoingAmount)
    assert(outgoingPayment.recipient.expiry == outgoingExpiry)
    assert(outgoingPayment.recipient.isInstanceOf[BlindedRecipient])

    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage, None)
    incomingPayments.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, Some(FulfillAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt), None)), commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == incomingPayments.map(i => (i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.length == 1)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay to blinded path with wake-up", Tag(wakeUpEnabled)) { f =>
    import f._

    val (peerReadyManager, switchboard) = createWakeUpActors()

    val incomingPayments = createIncomingPaymentsToWalletBlindedPath(nodeParams)
    val (nodeRelayer, parent) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))

    // The remote node is a wallet node: we try to wake them up before relaying the payment.
    peerReadyManager.expectMessageType[PeerReadyManager.Register].replyTo ! PeerReadyManager.Registered(outgoingNodeId, otherAttempts = 0)
    val wakeUp = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    assert(wakeUp.remoteNodeId == outgoingNodeId)
    wakeUp.replyTo ! Peer.PeerInfo(TestProbe[Any]().ref.toClassic, outgoingNodeId, Peer.CONNECTED, Some(nodeParams.features.initFeatures()), None, Set.empty)
    cleanUpWakeUpActors(peerReadyManager, switchboard)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingPayments.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList), ignoreNodeId = true)
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    assert(outgoingPayment.recipient.totalAmount == outgoingAmount)
    assert(outgoingPayment.recipient.expiry == outgoingExpiry)
    assert(outgoingPayment.recipient.isInstanceOf[BlindedRecipient])

    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage, None)
    incomingPayments.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, Some(FulfillAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt), None)), commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == incomingPayments.map(i => (i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.length == 1)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("fail to relay to blinded path when wake-up fails", Tag(wakeUpEnabled), Tag(wakeUpTimeout)) { f =>
    import f._

    val (peerReadyManager, switchboard) = createWakeUpActors()

    val incomingPayments = createIncomingPaymentsToWalletBlindedPath(nodeParams)
    val (nodeRelayer, _) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))

    // The remote node is a wallet node: we try to wake them up before relaying the payment, but it times out.
    peerReadyManager.expectMessageType[PeerReadyManager.Register].replyTo ! PeerReadyManager.Registered(outgoingNodeId, otherAttempts = 3)
    assert(switchboard.expectMessageType[Switchboard.GetPeerInfo].remoteNodeId == outgoingNodeId)
    cleanUpWakeUpActors(peerReadyManager, switchboard)
    mockPayFSM.expectNoMessage(100 millis)

    incomingPayments.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(UnknownNextPeer()), Some(FailureAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt))), commit = true))
    }
  }

  test("relay to blinded path with on-the-fly funding", Tag(wakeUpEnabled), Tag(onTheFlyFunding)) { f =>
    import f._

    val (peerReadyManager, switchboard) = createWakeUpActors()

    val incomingPayments = createIncomingPaymentsToWalletBlindedPath(nodeParams)
    val (nodeRelayer, parent) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))

    // The remote node is a wallet node: we wake them up before relaying the payment.
    peerReadyManager.expectMessageType[PeerReadyManager.Register].replyTo ! PeerReadyManager.Registered(outgoingNodeId, otherAttempts = 1)
    val peerInfo = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    peerInfo.replyTo ! Peer.PeerInfo(TestProbe[Any]().ref.toClassic, outgoingNodeId, Peer.CONNECTED, Some(nodeParams.features.initFeatures()), None, Set.empty)
    cleanUpWakeUpActors(peerReadyManager, switchboard)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingPayments.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList), ignoreNodeId = true)
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]

    // The outgoing payment fails because we don't have enough balance: we trigger on-the-fly funding.
    outgoingPayment.replyTo ! PaymentFailed(relayId, paymentHash, LocalFailure(outgoingAmount, Nil, BalanceTooLow) :: Nil)
    val fwd = register.expectMessageType[Register.ForwardNodeId[Peer.ProposeOnTheFlyFunding]]
    assert(fwd.nodeId == outgoingNodeId)
    assert(fwd.message.nextPathKey_opt.nonEmpty)
    assert(fwd.message.onion.payload.size == PaymentOnionCodecs.paymentOnionPayloadLength)
    // We verify that the next node is able to decrypt the onion that we will send in will_add_htlc.
    val dummyAdd = UpdateAddHtlc(randomBytes32(), 0, fwd.message.amount, fwd.message.paymentHash, fwd.message.expiry, fwd.message.onion, fwd.message.nextPathKey_opt, 7, None)
    val Right(incoming) = IncomingPaymentPacket.decrypt(dummyAdd, outgoingNodeKey, nodeParams.features)
    assert(incoming.isInstanceOf[IncomingPaymentPacket.FinalPacket])
    val finalPayload = incoming.asInstanceOf[IncomingPaymentPacket.FinalPacket].payload.asInstanceOf[FinalPayload.Blinded]
    assert(finalPayload.amount == fwd.message.amount)
    assert(finalPayload.expiry == fwd.message.expiry)
    assert(finalPayload.pathId == hex"deadbeef")

    // Once on-the-fly funding has been proposed, the payment isn't our responsibility anymore.
    fwd.message.replyTo ! Peer.ProposeOnTheFlyFundingResponse.Proposed
    parent.expectMessageType[NodeRelayer.RelayComplete]
  }

  test("relay to blinded path with on-the-fly funding failure", Tag(wakeUpEnabled), Tag(onTheFlyFunding)) { f =>
    import f._

    val (peerReadyManager, switchboard) = createWakeUpActors()

    val incomingPayments = createIncomingPaymentsToWalletBlindedPath(nodeParams)
    val (nodeRelayer, _) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))

    // The remote node is a wallet node: we wake them up before relaying the payment.
    peerReadyManager.expectMessageType[PeerReadyManager.Register].replyTo ! PeerReadyManager.Registered(outgoingNodeId, otherAttempts = 0)
    val peerInfo = switchboard.expectMessageType[Switchboard.GetPeerInfo]
    peerInfo.replyTo ! Peer.PeerInfo(TestProbe[Any]().ref.toClassic, outgoingNodeId, Peer.CONNECTED, Some(nodeParams.features.initFeatures()), None, Set.empty)
    cleanUpWakeUpActors(peerReadyManager, switchboard)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingPayments.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList), ignoreNodeId = true)
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]

    // The outgoing payment fails because we don't have enough balance: we trigger on-the-fly funding, but can't reach our peer.
    outgoingPayment.replyTo ! PaymentFailed(relayId, paymentHash, LocalFailure(outgoingAmount, Nil, BalanceTooLow) :: Nil)
    val fwd = register.expectMessageType[Register.ForwardNodeId[Peer.ProposeOnTheFlyFunding]]
    fwd.message.replyTo ! Peer.ProposeOnTheFlyFundingResponse.NotAvailable("peer disconnected")
    // We fail the payments immediately since the recipient isn't available.
    incomingPayments.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(UnknownNextPeer()), Some(FailureAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt))), commit = true))
    }
  }

  test("relay to compact blinded paths") { f =>
    import f._

    val scidDir = ShortChannelIdDir(isNode1 = true, RealShortChannelId(123456L))
    val incomingPayments = createIncomingPaymentsToRemoteBlindedPath(Features.empty, Some(scidDir))
    val (nodeRelayer, parent) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))

    val getNodeId = router.expectMessageType[Router.GetNodeId]
    assert(getNodeId.isNode1 == scidDir.isNode1)
    assert(getNodeId.shortChannelId == scidDir.scid)
    getNodeId.replyTo ! Some(outgoingNodeId)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Hot.Trampoline(incomingPayments.map(p => Upstream.Hot.Channel(p.add, p.receivedAt, randomKey().publicKey, 0.01)).toList), ignoreNodeId = true)
    val outgoingPayment = mockPayFSM.expectMessageType[SendPaymentToNode]
    assert(outgoingPayment.amount == outgoingAmount)
    assert(outgoingPayment.recipient.expiry == outgoingExpiry)
    assert(outgoingPayment.recipient.isInstanceOf[BlindedRecipient])

    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage, None)
    incomingPayments.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FULFILL_HTLC(p.add.id, paymentPreimage, Some(FulfillAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt), None)), commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.map(p => (p.amount, p.channelId)) == incomingPayments.map(i => (i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.length == 1)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("fail to relay to compact blinded paths with unknown scid") { f =>
    import f._

    val scidDir = ShortChannelIdDir(isNode1 = true, RealShortChannelId(123456L))
    val incomingPayments = createIncomingPaymentsToRemoteBlindedPath(Features.empty, Some(scidDir))
    val (nodeRelayer, _) = f.createNodeRelay(incomingPayments.head)
    incomingPayments.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming, randomKey().publicKey, 0.01))

    val getNodeId = router.expectMessageType[Router.GetNodeId]
    assert(getNodeId.isNode1 == scidDir.isNode1)
    assert(getNodeId.shortChannelId == scidDir.scid)
    getNodeId.replyTo ! None

    mockPayFSM.expectNoMessage(100 millis)

    incomingPayments.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId == p.add.channelId)
      assert(fwd.message == CMD_FAIL_HTLC(p.add.id, FailureReason.LocalFailure(UnknownNextPeer()), Some(FailureAttributionData(p.receivedAt, Some(incomingMultiPart.last.receivedAt))), commit = true))
    }
  }

  def validateOutgoingCfg(outgoingCfg: SendPaymentConfig, upstream: Upstream, ignoreNodeId: Boolean = false): Unit = {
    assert(!outgoingCfg.publishEvent)
    assert(!outgoingCfg.storeInDb)
    assert(outgoingCfg.paymentHash == paymentHash)
    assert(outgoingCfg.invoice.isEmpty)
    assert(ignoreNodeId || outgoingCfg.recipientNodeId == outgoingNodeId)
    (outgoingCfg.upstream, upstream) match {
      case (Upstream.Hot.Trampoline(adds1), Upstream.Hot.Trampoline(adds2)) => assert(adds1.map(_.add) == adds2.map(_.add))
      case _ => assert(outgoingCfg.upstream == upstream)
    }
  }

  def validateOutgoingPayment(outgoingPayment: SendMultiPartPayment): Unit = {
    assert(outgoingPayment.recipient.nodeId == outgoingNodeId)
    assert(outgoingPayment.recipient.totalAmount == outgoingAmount)
    assert(outgoingPayment.recipient.expiry == outgoingExpiry)
    assert(outgoingPayment.recipient.extraEdges == Nil)
    assert(outgoingPayment.recipient.isInstanceOf[ClearRecipient])
    val recipient = outgoingPayment.recipient.asInstanceOf[ClearRecipient]
    assert(recipient.paymentSecret !== incomingSecret) // we should generate a new outgoing secret
    assert(recipient.nextTrampolineOnion_opt.nonEmpty)
    // The recipient is able to decrypt the trampoline onion.
    recipient.nextTrampolineOnion_opt.foreach(onion => assert(IncomingPaymentPacket.decryptOnion(paymentHash, outgoingNodeKey, onion).isRight))
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
  val paymentSecret = randomBytes32()

  val outgoingAmount = 40_000_000 msat
  val outgoingExpiry = CltvExpiry(490000)
  val outgoingNodeKey = randomKey()
  val outgoingNodeId = outgoingNodeKey.publicKey

  val incomingAmount = 41_000_000 msat
  val incomingSecret = randomBytes32()
  val incomingMultiPart = Seq(
    createValidIncomingPacket(15_000_000 msat, incomingAmount, CltvExpiry(500000), outgoingAmount, outgoingExpiry, TimestampMilli(1000), endorsementIn = 6),
    createValidIncomingPacket(15_000_000 msat, incomingAmount, CltvExpiry(499999), outgoingAmount, outgoingExpiry, TimestampMilli(2000), endorsementIn = 5),
    createValidIncomingPacket(11_000_000 msat, incomingAmount, CltvExpiry(499999), outgoingAmount, outgoingExpiry, TimestampMilli(3000), endorsementIn = 7)
  )
  val incomingSinglePart = createValidIncomingPacket(incomingAmount, incomingAmount, CltvExpiry(500000), outgoingAmount, outgoingExpiry, TimestampMilli(5000))
  val incomingAsyncPayment: Seq[RelayToTrampolinePacket] = incomingMultiPart.map(p => p.copy(innerPayload = IntermediatePayload.NodeRelay.Standard.createNodeRelayForAsyncPayment(p.innerPayload.amountToForward, p.innerPayload.outgoingCltv, outgoingNodeId)))

  def asyncTimeoutHeight(nodeParams: NodeParams): BlockHeight =
    nodeParams.currentBlockHeight + nodeParams.relayParams.asyncPaymentsParams.holdTimeoutBlocks

  def asyncSafetyHeight(paymentPackets: Seq[RelayToTrampolinePacket], nodeParams: NodeParams): BlockHeight =
    (paymentPackets.map(_.outerPayload.expiry).min - nodeParams.relayParams.asyncPaymentsParams.cancelSafetyBeforeTimeout).blockHeight

  def createSuccessEvent(): PaymentSent =
    PaymentSent(relayId, paymentHash, paymentPreimage, outgoingAmount, outgoingNodeId, Seq(PaymentSent.PartialPayment(UUID.randomUUID(), outgoingAmount, 10 msat, randomBytes32(), None)), None)

  def createTrampolinePacket(amount: MilliSatoshi, expiry: CltvExpiry): OnionRoutingPacket = {
    val payload = NodePayload(outgoingNodeId, FinalPayload.Standard.createPayload(amount, amount, expiry, paymentSecret))
    val Right(onion) = OutgoingPaymentPacket.buildOnion(Seq(payload), paymentHash, None)
    onion.packet
  }

  def createValidIncomingPacket(amountIn: MilliSatoshi, totalAmountIn: MilliSatoshi, expiryIn: CltvExpiry, amountOut: MilliSatoshi, expiryOut: CltvExpiry, receivedAt: TimestampMilli, endorsementIn: Int = 7): RelayToTrampolinePacket = {
    val outerPayload = FinalPayload.Standard.createPayload(amountIn, totalAmountIn, expiryIn, incomingSecret, None)
    val tlvs = TlvStream[UpdateAddHtlcTlv](UpdateAddHtlcTlv.Endorsement(endorsementIn))
    RelayToTrampolinePacket(
      UpdateAddHtlc(randomBytes32(), Random.nextInt(100), amountIn, paymentHash, expiryIn, TestConstants.emptyOnionPacket, tlvs),
      outerPayload,
      IntermediatePayload.NodeRelay.Standard(amountOut, expiryOut, outgoingNodeId),
      createTrampolinePacket(amountOut, expiryOut),
      receivedAt)
  }

  def createPartialIncomingPacket(paymentHash: ByteVector32, paymentSecret: ByteVector32): RelayToTrampolinePacket = {
    val (expiryIn, expiryOut) = (CltvExpiry(500000), CltvExpiry(490000))
    val amountIn = incomingAmount / 2
    RelayToTrampolinePacket(
      UpdateAddHtlc(randomBytes32(), Random.nextInt(100), amountIn, paymentHash, expiryIn, TestConstants.emptyOnionPacket, None, 7, None),
      FinalPayload.Standard.createPayload(amountIn, incomingAmount, expiryIn, paymentSecret, None),
      IntermediatePayload.NodeRelay.Standard(outgoingAmount, expiryOut, outgoingNodeId),
      createTrampolinePacket(outgoingAmount, expiryOut),
      TimestampMilli.now())
  }

  def createPaymentBlindedRoute(nodeId: PublicKey, sessionKey: PrivateKey = randomKey(), pathId: ByteVector = randomBytes32()): PaymentBlindedRoute = {
    val selfPayload = blindedRouteDataCodec.encode(TlvStream(PathId(pathId), PaymentConstraints(CltvExpiry(1234567), 0 msat), AllowedFeatures(Features.empty))).require.bytes
    PaymentBlindedRoute(Sphinx.RouteBlinding.create(sessionKey, Seq(nodeId), Seq(selfPayload)).route, PaymentInfo(1 msat, 2, CltvExpiryDelta(3), 4 msat, 5 msat, ByteVector.empty))
  }

  /** Create payments to a blinded path that starts at a remote node. */
  def createIncomingPaymentsToRemoteBlindedPath(features: Features[Bolt12Feature], scidDir_opt: Option[EncodedNodeId.ShortChannelIdDir]): Seq[RelayToBlindedPathsPacket] = {
    val offer = Offer(None, Some("test offer"), outgoingNodeId, features, Block.RegtestGenesisBlock.hash)
    val request = InvoiceRequest(offer, outgoingAmount, 1, features, randomKey(), Block.RegtestGenesisBlock.hash)
    val paymentBlindedRoute = scidDir_opt match {
      case Some(scidDir) =>
        val nonCompact = createPaymentBlindedRoute(outgoingNodeId)
        nonCompact.copy(route = nonCompact.route.copy(firstNodeId = scidDir))
      case None =>
        createPaymentBlindedRoute(outgoingNodeId)
    }
    val invoice = Bolt12Invoice(request, randomBytes32(), outgoingNodeKey, 300 seconds, features, Seq(paymentBlindedRoute))
    incomingMultiPart.map(incoming => {
      val innerPayload = IntermediatePayload.NodeRelay.ToBlindedPaths(incoming.innerPayload.amountToForward, outgoingExpiry, invoice)
      RelayToBlindedPathsPacket(incoming.add, incoming.outerPayload, innerPayload, incoming.receivedAt)
    })
  }

  /** Create payments to a blinded path that starts at our node and relays to a wallet node. */
  def createIncomingPaymentsToWalletBlindedPath(nodeParams: NodeParams): Seq[RelayToBlindedPathsPacket] = {
    val features: Features[Bolt12Feature] = Features(Features.BasicMultiPartPayment -> FeatureSupport.Optional)
    val offer = Offer(None, Some("test offer"), outgoingNodeId, features, Block.RegtestGenesisBlock.hash)
    val request = InvoiceRequest(offer, outgoingAmount, 1, Features(Features.BasicMultiPartPayment -> FeatureSupport.Optional), randomKey(), Block.RegtestGenesisBlock.hash)
    val edge = ExtraEdge(nodeParams.nodeId, outgoingNodeId, Alias(561), 2_000_000 msat, 250, CltvExpiryDelta(144), 1 msat, None)
    val hop = ChannelHop(edge.shortChannelId, nodeParams.nodeId, outgoingNodeId, HopRelayParams.FromHint(edge))
    val route = BlindedRouteCreation.createBlindedRouteToWallet(hop, hex"deadbeef", 1 msat, outgoingExpiry).route
    val paymentInfo = BlindedRouteCreation.aggregatePaymentInfo(outgoingAmount, Seq(hop), CltvExpiryDelta(12))
    val invoice = Bolt12Invoice(request, randomBytes32(), outgoingNodeKey, 300 seconds, features, Seq(PaymentBlindedRoute(route, paymentInfo)))
    incomingMultiPart.map(incoming => {
      val innerPayload = IntermediatePayload.NodeRelay.ToBlindedPaths(incoming.innerPayload.amountToForward, outgoingExpiry, invoice)
      RelayToBlindedPathsPacket(incoming.add, incoming.outerPayload, innerPayload, incoming.receivedAt)
    })
  }

}