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
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto}
import fr.acinq.eclair.Features.{BasicMultiPartPayment, PaymentSecret, VariableLengthOnion}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Register}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.payment.PaymentRequest.{ExtraHop, PaymentRequestFeatures}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.{PreimageReceived, SendMultiPartPayment}
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPayment
import fr.acinq.eclair.router.Router.RouteRequest
import fr.acinq.eclair.router.{BalanceTooLow, RouteNotFound}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshi, MilliSatoshiLong, NodeParams, ShortChannelId, TestConstants, nodeFee, randomBytes, randomBytes32, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.HexStringSyntax

import java.util.UUID
import scala.concurrent.duration._
import scala.util.Random

/**
 * Created by t-bast on 10/10/2019.
 */

class NodeRelayerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  import NodeRelayerSpec._

  case class FixtureParam(nodeParams: NodeParams, nodeRelayer: ActorRef[NodeRelay.Command], parent: TestProbe[NodeRelayer.Command], router: TestProbe[Any], register: TestProbe[Any], mockPayFSM: TestProbe[Any], eventListener: TestProbe[PaymentEvent])

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams = TestConstants.Bob.nodeParams.copy(multiPartPaymentExpiry = 5 seconds)
    val parent = TestProbe[NodeRelayer.Command]("parent-relayer")
    val router = TestProbe[Any]("router")
    val register = TestProbe[Any]("register")
    val eventListener = TestProbe[PaymentEvent]("event-listener")
    system.eventStream ! EventStream.Subscribe(eventListener.ref)
    val mockPayFSM = TestProbe[Any]("pay-fsm")
    val fsmFactory = if (test.tags.contains("mock-fsm")) {
      new NodeRelay.FsmFactory {
        override def spawnOutgoingPayFSM(context: ActorContext[NodeRelay.Command], nodeParams: NodeParams, router: akka.actor.ActorRef, register: akka.actor.ActorRef, cfg: SendPaymentConfig, multiPart: Boolean): akka.actor.ActorRef = {
          mockPayFSM.ref ! cfg
          mockPayFSM.ref.toClassic
        }
      }
    } else {
      new NodeRelay.FsmFactory {
        override def spawnOutgoingPayFSM(context: ActorContext[NodeRelay.Command], nodeParams: NodeParams, router: akka.actor.ActorRef, register: akka.actor.ActorRef, cfg: SendPaymentConfig, multiPart: Boolean): akka.actor.ActorRef = {
          val fsm = super.spawnOutgoingPayFSM(context, nodeParams, router, register, cfg, multiPart)
          mockPayFSM.ref ! fsm
          fsm
        }
      }
    }
    val nodeRelay = testKit.spawn(NodeRelay(nodeParams, parent.ref, router.ref.toClassic, register.ref.toClassic, relayId, paymentHash, fsmFactory))
    withFixture(test.toNoArgTest(FixtureParam(nodeParams, nodeRelay, parent, router, register, mockPayFSM, eventListener)))
  }

  test("stop child handler when relay is complete") { f =>
    import f._
    val probe = TestProbe[Any]

    {
      val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, router.ref.toClassic, register.ref.toClassic))
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(Map.empty)
    }
    {
      val (paymentHash1, child1) = (randomBytes32, TestProbe[NodeRelay.Command])
      val (paymentHash2, child2) = (randomBytes32, TestProbe[NodeRelay.Command])
      val children = Map(paymentHash1 -> child1.ref, paymentHash2 -> child2.ref)
      val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, router.ref.toClassic, register.ref.toClassic, children))
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(children)

      parentRelayer ! NodeRelayer.RelayComplete(child1.ref, paymentHash1)
      child1.expectMessage(NodeRelay.Stop)
      parentRelayer ! NodeRelayer.RelayComplete(child1.ref, paymentHash1)
      child1.expectMessage(NodeRelay.Stop)
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(children - paymentHash1)
    }
    {
      val parentRelayer = testKit.spawn(NodeRelayer(nodeParams, router.ref.toClassic, register.ref.toClassic))
      parentRelayer ! NodeRelayer.Relay(incomingMultiPart.head)
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      val pending1 = probe.expectMessageType[Map[ByteVector32, ActorRef[NodeRelay.Command]]]
      assert(pending1.size === 1)
      assert(pending1.head._1 === paymentHash)

      parentRelayer ! NodeRelayer.RelayComplete(pending1.head._2, paymentHash)
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      probe.expectMessage(Map.empty)

      parentRelayer ! NodeRelayer.Relay(incomingMultiPart.head)
      parentRelayer ! NodeRelayer.GetPendingPayments(probe.ref.toClassic)
      val pending2 = probe.expectMessageType[Map[ByteVector32, ActorRef[NodeRelay.Command]]]
      assert(pending2.size === 1)
      assert(pending2.head._1 === paymentHash)
      assert(pending2.head._2 !== pending1.head._2)
    }
  }

  test("fail to relay when incoming multi-part payment times out") { f =>
    import f._

    // Receive a partial upstream multi-part payment.
    incomingMultiPart.dropRight(1).foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming))
    // after a while the payment times out
    incomingMultiPart.dropRight(1).foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]](30 seconds)
      assert(fwd.channelId === p.add.channelId)
      val failure = Right(PaymentTimeout)
      assert(fwd.message === CMD_FAIL_HTLC(p.add.id, failure, commit = true))
    }

    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("fail all extraneous multi-part incoming HTLCs") { f =>
    import f._

    // We send all the parts of a mpp
    incomingMultiPart.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming))
    // and then one extra
    val extra = IncomingPacket.NodeRelayPacket(
      UpdateAddHtlc(randomBytes32, Random.nextInt(100), 1000 msat, paymentHash, CltvExpiry(499990), TestConstants.emptyOnionPacket),
      Onion.createMultiPartPayload(1000 msat, incomingAmount, CltvExpiry(499990), incomingSecret),
      Onion.createNodeRelayPayload(outgoingAmount, outgoingExpiry, outgoingNodeId),
      nextTrampolinePacket)
    nodeRelayer ! NodeRelay.Relay(extra)

    // the extra payment will be rejected
    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId === extra.add.channelId)
    val failure = IncorrectOrUnknownPaymentDetails(extra.add.amountMsat, nodeParams.currentBlockHeight)
    assert(fwd.message === CMD_FAIL_HTLC(extra.add.id, Right(failure), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail all additional incoming HTLCs once already relayed out", Tag("mock-fsm")) { f =>
    import f._

    // Receive a complete upstream multi-part payment, which we relay out.
    incomingMultiPart.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming))

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Trampoline(incomingMultiPart.map(_.add)))
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)

    // Receive new extraneous multi-part HTLC.
    val i1 = IncomingPacket.NodeRelayPacket(
      UpdateAddHtlc(randomBytes32, Random.nextInt(100), 1000 msat, paymentHash, CltvExpiry(499990), TestConstants.emptyOnionPacket),
      Onion.createMultiPartPayload(1000 msat, incomingAmount, CltvExpiry(499990), incomingSecret),
      Onion.createNodeRelayPayload(outgoingAmount, outgoingExpiry, outgoingNodeId),
      nextTrampolinePacket)
    nodeRelayer ! NodeRelay.Relay(i1)

    val fwd1 = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd1.channelId === i1.add.channelId)
    val failure1 = IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)
    assert(fwd1.message === CMD_FAIL_HTLC(i1.add.id, Right(failure1), commit = true))

    // Receive new HTLC with different details, but for the same payment hash.
    val i2 = IncomingPacket.NodeRelayPacket(
      UpdateAddHtlc(randomBytes32, Random.nextInt(100), 1500 msat, paymentHash, CltvExpiry(499990), TestConstants.emptyOnionPacket),
      Onion.createSinglePartPayload(1500 msat, CltvExpiry(499990), Some(randomBytes32)),
      Onion.createNodeRelayPayload(1250 msat, outgoingExpiry, outgoingNodeId),
      nextTrampolinePacket)
    nodeRelayer ! NodeRelay.Relay(i2)

    val fwd2 = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd1.channelId === i1.add.channelId)
    val failure2 = IncorrectOrUnknownPaymentDetails(1500 msat, nodeParams.currentBlockHeight)
    assert(fwd2.message === CMD_FAIL_HTLC(i2.add.id, Right(failure2), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay an incoming payment without payment secret") { f =>
    import f._

    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, CltvExpiry(500000), outgoingAmount, outgoingExpiry).copy(
      outerPayload = Onion.createSinglePartPayload(2000000 msat, CltvExpiry(500000)) // missing outer payment secret
    )
    nodeRelayer ! NodeRelay.Relay(p)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId === p.add.channelId)
    val failure = IncorrectOrUnknownPaymentDetails(2000000 msat, nodeParams.currentBlockHeight)
    assert(fwd.message === CMD_FAIL_HTLC(p.add.id, Right(failure), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when incoming payment secrets don't match") { f =>
    import f._

    val p1 = createValidIncomingPacket(2000000 msat, 3000000 msat, CltvExpiry(500000), 2500000 msat, outgoingExpiry)
    val p2 = createValidIncomingPacket(1000000 msat, 3000000 msat, CltvExpiry(500000), 2500000 msat, outgoingExpiry).copy(
      outerPayload = Onion.createMultiPartPayload(1000000 msat, 3000000 msat, CltvExpiry(500000), randomBytes32)
    )
    nodeRelayer ! NodeRelay.Relay(p1)
    nodeRelayer ! NodeRelay.Relay(p2)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId === p2.add.channelId)
    val failure = IncorrectOrUnknownPaymentDetails(1000000 msat, nodeParams.currentBlockHeight)
    assert(fwd.message === CMD_FAIL_HTLC(p2.add.id, Right(failure), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when expiry is too soon (single-part)") { f =>
    import f._

    val expiryIn = CltvExpiry(500000) // not ok (delta = 100)
    val expiryOut = CltvExpiry(499900)
    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, expiryIn, 1000000 msat, expiryOut)
    nodeRelayer ! NodeRelay.Relay(p)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId === p.add.channelId)
    assert(fwd.message === CMD_FAIL_HTLC(p.add.id, Right(TrampolineExpiryTooSoon), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when final expiry is below chain height") { f =>
    import f._

    val expiryIn = CltvExpiry(500000)
    val expiryOut = CltvExpiry(300000) // not ok (chain heigh = 400000)
    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, expiryIn, 1000000 msat, expiryOut)
    nodeRelayer ! NodeRelay.Relay(p)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId === p.add.channelId)
    assert(fwd.message === CMD_FAIL_HTLC(p.add.id, Right(TrampolineExpiryTooSoon), commit = true))

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
    p.foreach(p => nodeRelayer ! NodeRelay.Relay(p))

    p.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId === p.add.channelId)
      assert(fwd.message === CMD_FAIL_HTLC(p.add.id, Right(TrampolineExpiryTooSoon), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when fees are insufficient (single-part)") { f =>
    import f._

    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, CltvExpiry(500000), 1999000 msat, CltvExpiry(490000))
    nodeRelayer ! NodeRelay.Relay(p)

    val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId === p.add.channelId)
    assert(fwd.message === CMD_FAIL_HTLC(p.add.id, Right(TrampolineFeeInsufficient), commit = true))

    register.expectNoMessage(100 millis)
  }

  test("fail to relay when fees are insufficient (multi-part)") { f =>
    import f._

    val p = Seq(
      createValidIncomingPacket(2000000 msat, 3000000 msat, CltvExpiry(500000), 2999000 msat, CltvExpiry(400000)),
      createValidIncomingPacket(1000000 msat, 3000000 msat, CltvExpiry(500000), 2999000 msat, CltvExpiry(400000))
    )
    p.foreach(p => nodeRelayer ! NodeRelay.Relay(p))

    p.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId === p.add.channelId)
      assert(fwd.message === CMD_FAIL_HTLC(p.add.id, Right(TrampolineFeeInsufficient), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("fail to relay because outgoing balance isn't sufficient (low fees)", Tag("mock-fsm")) { f =>
    import f._

    // Receive an upstream multi-part payment.
    incomingMultiPart.foreach(p => nodeRelayer ! NodeRelay.Relay(p))

    mockPayFSM.expectMessageType[SendPaymentConfig]
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = mockPayFSM.expectMessageType[SendMultiPartPayment].replyTo

    // The proposed fees are low, so we ask the sender to raise them.
    nodeRelayerAdapters ! PaymentFailed(relayId, paymentHash, LocalFailure(Nil, BalanceTooLow) :: Nil)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId === p.add.channelId)
      assert(fwd.message === CMD_FAIL_HTLC(p.add.id, Right(TrampolineFeeInsufficient), commit = true))
    }

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
    incoming.foreach(p => nodeRelayer ! NodeRelay.Relay(p))

    val payFSM = mockPayFSM.expectMessageType[akka.actor.ActorRef]
    router.expectMessageType[RouteRequest]
    payFSM ! Status.Failure(BalanceTooLow)

    incoming.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId === p.add.channelId)
      assert(fwd.message === CMD_FAIL_HTLC(p.add.id, Right(TemporaryNodeFailure), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("fail to relay because incoming fee isn't enough to find routes downstream") { f =>
    import f._

    // Receive an upstream multi-part payment.
    incomingMultiPart.foreach(p => nodeRelayer ! NodeRelay.Relay(p))

    val payFSM = mockPayFSM.expectMessageType[akka.actor.ActorRef]
    router.expectMessageType[RouteRequest]

    // If we're having a hard time finding routes, raising the fee/cltv will likely help.
    val failures = LocalFailure(Nil, RouteNotFound) :: RemoteFailure(Nil, Sphinx.DecryptedFailurePacket(outgoingNodeId, PermanentNodeFailure)) :: LocalFailure(Nil, RouteNotFound) :: Nil
    payFSM ! PaymentFailed(relayId, incomingMultiPart.head.add.paymentHash, failures)

    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId === p.add.channelId)
      assert(fwd.message === CMD_FAIL_HTLC(p.add.id, Right(TrampolineFeeInsufficient), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("fail to relay because of downstream failures") { f =>
    import f._

    // Receive an upstream multi-part payment.
    incomingMultiPart.foreach(p => nodeRelayer ! NodeRelay.Relay(p))

    val payFSM = mockPayFSM.expectMessageType[akka.actor.ActorRef]
    router.expectMessageType[RouteRequest]

    val failures = RemoteFailure(Nil, Sphinx.DecryptedFailurePacket(outgoingNodeId, FinalIncorrectHtlcAmount(42 msat))) :: UnreadableRemoteFailure(Nil) :: Nil
    payFSM ! PaymentFailed(relayId, incomingMultiPart.head.add.paymentHash, failures)

    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId === p.add.channelId)
      assert(fwd.message === CMD_FAIL_HTLC(p.add.id, Right(FinalIncorrectHtlcAmount(42 msat)), commit = true))
    }

    register.expectNoMessage(100 millis)
  }

  test("compute route params") { f =>
    import f._

    // Receive an upstream multi-part payment.
    nodeRelayer ! NodeRelay.Relay(incomingSinglePart)

    val routeRequest = router.expectMessageType[RouteRequest]

    val routeParams = routeRequest.routeParams.get
    val fee = nodeFee(nodeParams.feeBase, nodeParams.feeProportionalMillionth, outgoingAmount)
    assert(routeParams.maxFeePct === 0) // should be disabled
    assert(routeParams.maxFeeBase === incomingAmount - outgoingAmount - fee) // we collect our fee and then use what remains for the rest of the route
    assert(routeParams.routeMaxCltv === incomingSinglePart.add.cltvExpiry - outgoingExpiry - nodeParams.expiryDelta) // we apply our cltv delta
  }

  test("relay incoming multi-part payment", Tag("mock-fsm")) { f =>
    import f._

    // Receive an upstream multi-part payment.
    incomingMultiPart.dropRight(1).foreach(p => nodeRelayer ! NodeRelay.Relay(p))
    router.expectNoMessage(100 millis) // we should NOT trigger a downstream payment before we received a complete upstream payment

    nodeRelayer ! NodeRelay.Relay(incomingMultiPart.last)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Trampoline(incomingMultiPart.map(_.add)))
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    // A first downstream HTLC is fulfilled: we should immediately forward the fulfill upstream.
    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId === p.add.channelId)
      assert(fwd.message === CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))
    }

    // If the payment FSM sends us duplicate preimage events, we should not fulfill a second time upstream.
    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    register.expectNoMessage(100 millis)

    // Once all the downstream payments have settled, we should emit the relayed event.
    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.toSet === incomingMultiPart.map(i => PaymentRelayed.Part(i.add.amountMsat, i.add.channelId)).toSet)
    assert(relayEvent.outgoing.nonEmpty)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay incoming single-part payment", Tag("mock-fsm")) { f =>
    import f._

    // Receive an upstream single-part payment.
    nodeRelayer ! NodeRelay.Relay(incomingSinglePart)

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Trampoline(incomingSinglePart.add :: Nil))
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    val incomingAdd = incomingSinglePart.add
    val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
    assert(fwd.channelId === incomingAdd.channelId)
    assert(fwd.message === CMD_FULFILL_HTLC(incomingAdd.id, paymentPreimage, commit = true))

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming === Seq(PaymentRelayed.Part(incomingSinglePart.add.amountMsat, incomingSinglePart.add.channelId)))
    assert(relayEvent.outgoing.nonEmpty)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay to non-trampoline recipient supporting multi-part", Tag("mock-fsm")) { f =>
    import f._

    // Receive an upstream multi-part payment.
    val hints = List(List(ExtraHop(outgoingNodeId, ShortChannelId(42), feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12))))
    val features = PaymentRequestFeatures(VariableLengthOnion.optional, PaymentSecret.mandatory, BasicMultiPartPayment.optional)
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(outgoingAmount * 3), paymentHash, randomKey, "Some invoice", CltvExpiryDelta(18), extraHops = hints, features = Some(features))
    incomingMultiPart.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming.copy(innerPayload = Onion.createNodeRelayToNonTrampolinePayload(
      incoming.innerPayload.amountToForward, outgoingAmount * 3, outgoingExpiry, outgoingNodeId, pr
    ))))

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Trampoline(incomingMultiPart.map(_.add)))
    val outgoingPayment = mockPayFSM.expectMessageType[SendMultiPartPayment]
    assert(outgoingPayment.paymentSecret === pr.paymentSecret.get) // we should use the provided secret
    assert(outgoingPayment.totalAmount === outgoingAmount)
    assert(outgoingPayment.targetExpiry === outgoingExpiry)
    assert(outgoingPayment.targetNodeId === outgoingNodeId)
    assert(outgoingPayment.additionalTlvs === Nil)
    assert(outgoingPayment.routeParams.isDefined)
    assert(outgoingPayment.assistedRoutes === hints)
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId === p.add.channelId)
      assert(fwd.message === CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming === incomingMultiPart.map(i => PaymentRelayed.Part(i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.nonEmpty)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  test("relay to non-trampoline recipient without multi-part", Tag("mock-fsm")) { f =>
    import f._

    // Receive an upstream multi-part payment.
    val hints = List(List(ExtraHop(outgoingNodeId, ShortChannelId(42), feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12))))
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(outgoingAmount), paymentHash, randomKey, "Some invoice", CltvExpiryDelta(18), extraHops = hints, features = Some(PaymentRequestFeatures()))
    incomingMultiPart.foreach(incoming => nodeRelayer ! NodeRelay.Relay(incoming.copy(innerPayload = Onion.createNodeRelayToNonTrampolinePayload(
      incoming.innerPayload.amountToForward, incoming.innerPayload.amountToForward, outgoingExpiry, outgoingNodeId, pr
    ))))

    val outgoingCfg = mockPayFSM.expectMessageType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.Trampoline(incomingMultiPart.map(_.add)))
    val outgoingPayment = mockPayFSM.expectMessageType[SendPayment]
    assert(outgoingPayment.finalPayload.amount === outgoingAmount)
    assert(outgoingPayment.finalPayload.expiry === outgoingExpiry)
    assert(outgoingPayment.targetNodeId === outgoingNodeId)
    assert(outgoingPayment.routeParams.isDefined)
    assert(outgoingPayment.assistedRoutes === hints)
    // those are adapters for pay-fsm messages
    val nodeRelayerAdapters = outgoingPayment.replyTo

    nodeRelayerAdapters ! PreimageReceived(paymentHash, paymentPreimage)
    incomingMultiPart.foreach { p =>
      val fwd = register.expectMessageType[Register.Forward[CMD_FULFILL_HTLC]]
      assert(fwd.channelId === p.add.channelId)
      assert(fwd.message === CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))
    }

    nodeRelayerAdapters ! createSuccessEvent()
    val relayEvent = eventListener.expectMessageType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming === incomingMultiPart.map(i => PaymentRelayed.Part(i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.length === 1)
    parent.expectMessageType[NodeRelayer.RelayComplete]
    register.expectNoMessage(100 millis)
  }

  def validateOutgoingCfg(outgoingCfg: SendPaymentConfig, upstream: Upstream): Unit = {
    assert(!outgoingCfg.publishEvent)
    assert(!outgoingCfg.storeInDb)
    assert(outgoingCfg.paymentHash === paymentHash)
    assert(outgoingCfg.paymentRequest === None)
    assert(outgoingCfg.recipientAmount === outgoingAmount)
    assert(outgoingCfg.recipientNodeId === outgoingNodeId)
    assert(outgoingCfg.upstream === upstream)
  }

  def validateOutgoingPayment(outgoingPayment: SendMultiPartPayment): Unit = {
    assert(outgoingPayment.paymentSecret !== incomingSecret) // we should generate a new outgoing secret
    assert(outgoingPayment.totalAmount === outgoingAmount)
    assert(outgoingPayment.targetExpiry === outgoingExpiry)
    assert(outgoingPayment.targetNodeId === outgoingNodeId)
    assert(outgoingPayment.additionalTlvs === Seq(OnionTlv.TrampolineOnion(nextTrampolinePacket)))
    assert(outgoingPayment.routeParams.isDefined)
    assert(outgoingPayment.assistedRoutes === Nil)
  }

  def validateRelayEvent(e: TrampolinePaymentRelayed): Unit = {
    assert(e.amountIn === incomingAmount)
    assert(e.amountOut >= outgoingAmount) // outgoingAmount + routing fees
    assert(e.paymentHash === paymentHash)
  }

}

object NodeRelayerSpec {

  val relayId = UUID.randomUUID()

  val paymentPreimage = randomBytes32
  val paymentHash = Crypto.sha256(paymentPreimage)

  // This is the result of decrypting the incoming trampoline onion packet.
  // It should be forwarded to the next trampoline node.
  val nextTrampolinePacket = OnionRoutingPacket(0, hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619", randomBytes(Sphinx.TrampolinePacket.PayloadLength), randomBytes32)

  val outgoingAmount = 4000000 msat
  val outgoingExpiry = CltvExpiry(490000)
  val outgoingNodeId = randomKey.publicKey

  val incomingAmount = 5000000 msat
  val incomingSecret = randomBytes32
  val incomingMultiPart = Seq(
    createValidIncomingPacket(2000000 msat, incomingAmount, CltvExpiry(500000), outgoingAmount, outgoingExpiry),
    createValidIncomingPacket(2000000 msat, incomingAmount, CltvExpiry(499999), outgoingAmount, outgoingExpiry),
    createValidIncomingPacket(1000000 msat, incomingAmount, CltvExpiry(499999), outgoingAmount, outgoingExpiry)
  )
  val incomingSinglePart =
    createValidIncomingPacket(incomingAmount, incomingAmount, CltvExpiry(500000), outgoingAmount, outgoingExpiry)

  def createSuccessEvent(): PaymentSent =
    PaymentSent(relayId, paymentHash, paymentPreimage, outgoingAmount, outgoingNodeId, Seq(PaymentSent.PartialPayment(UUID.randomUUID(), outgoingAmount, 10 msat, randomBytes32, None)))

  def createValidIncomingPacket(amountIn: MilliSatoshi, totalAmountIn: MilliSatoshi, expiryIn: CltvExpiry, amountOut: MilliSatoshi, expiryOut: CltvExpiry): IncomingPacket.NodeRelayPacket = {
    val outerPayload = if (amountIn == totalAmountIn) {
      Onion.createSinglePartPayload(amountIn, expiryIn, Some(incomingSecret))
    } else {
      Onion.createMultiPartPayload(amountIn, totalAmountIn, expiryIn, incomingSecret)
    }
    IncomingPacket.NodeRelayPacket(
      UpdateAddHtlc(randomBytes32, Random.nextInt(100), amountIn, paymentHash, expiryIn, TestConstants.emptyOnionPacket),
      outerPayload,
      Onion.createNodeRelayPayload(amountOut, expiryOut, outgoingNodeId),
      nextTrampolinePacket)
  }

}