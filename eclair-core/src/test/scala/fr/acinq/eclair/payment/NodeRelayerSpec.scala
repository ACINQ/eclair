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

import akka.actor.ActorRef
import akka.testkit.{TestActorRef, TestProbe}
import fr.acinq.bitcoin.{Block, Crypto}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Upstream}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentRequest.{ExtraHop, Features}
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM
import fr.acinq.eclair.payment.relay.{CommandBuffer, NodeRelayer, Origin, Relayer}
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.SendMultiPartPayment
import fr.acinq.eclair.payment.send.PaymentError
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentConfig
import fr.acinq.eclair.payment.send.PaymentLifecycle.SendPayment
import fr.acinq.eclair.router.RouteNotFound
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, LongToBtcAmount, MilliSatoshi, NodeParams, ShortChannelId, TestConstants, TestkitBaseClass, nodeFee, randomBytes, randomBytes32, randomKey}
import org.scalatest.Outcome
import scodec.bits.HexStringSyntax

import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.Random

/**
 * Created by t-bast on 10/10/2019.
 */

class NodeRelayerSpec extends TestkitBaseClass {

  import NodeRelayerSpec._

  case class FixtureParam(nodeParams: NodeParams, nodeRelayer: TestActorRef[NodeRelayer], relayer: TestProbe, outgoingPayFSM: TestProbe, commandBuffer: TestProbe, eventListener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    within(30 seconds) {
      val nodeParams = TestConstants.Bob.nodeParams
      val outgoingPayFSM = TestProbe()
      val (relayer, router, commandBuffer, register, eventListener) = (TestProbe(), TestProbe(), TestProbe(), TestProbe(), TestProbe())
      system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
      class TestNodeRelayer extends NodeRelayer(nodeParams, relayer.ref, router.ref, commandBuffer.ref, register.ref) {
        override def spawnOutgoingPayFSM(cfg: SendPaymentConfig, multiPart: Boolean): ActorRef = {
          outgoingPayFSM.ref ! cfg
          outgoingPayFSM.ref
        }
      }
      val nodeRelayer = TestActorRef(new TestNodeRelayer().asInstanceOf[NodeRelayer])
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, nodeRelayer, relayer, outgoingPayFSM, commandBuffer, eventListener)))
    }
  }

  test("fail to relay when incoming multi-part payment times out") { f =>
    import f._

    // Receive a partial upstream multi-part payment.
    incomingMultiPart.dropRight(1).foreach(incoming => relayer.send(nodeRelayer, incoming))

    val sender = TestProbe()
    val parts = incomingMultiPart.dropRight(1).map(i => MultiPartPaymentFSM.HtlcPart(incomingAmount, i.add))
    sender.send(nodeRelayer, MultiPartPaymentFSM.MultiPartPaymentFailed(paymentHash, PaymentTimeout, Queue(parts: _*)))

    incomingMultiPart.dropRight(1).foreach(p => commandBuffer.expectMsg(CommandBuffer.CommandSend(p.add.channelId, CMD_FAIL_HTLC(p.add.id, Right(PaymentTimeout), commit = true))))
    sender.expectNoMsg(100 millis)
    outgoingPayFSM.expectNoMsg(100 millis)
  }

  test("fail all extraneous multi-part incoming HTLCs") { f =>
    import f._

    val sender = TestProbe()
    val partial = MultiPartPaymentFSM.HtlcPart(incomingAmount, UpdateAddHtlc(randomBytes32, 15, 100 msat, paymentHash, CltvExpiry(42000), TestConstants.emptyOnionPacket))
    sender.send(nodeRelayer, MultiPartPaymentFSM.ExtraPaymentReceived(paymentHash, partial, Some(InvalidRealm)))

    commandBuffer.expectMsg(CommandBuffer.CommandSend(partial.htlc.channelId, CMD_FAIL_HTLC(partial.htlc.id, Right(InvalidRealm), commit = true)))
    sender.expectNoMsg(100 millis)
    outgoingPayFSM.expectNoMsg(100 millis)
  }

  test("fail all additional incoming HTLCs once already relayed out") { f =>
    import f._

    // Receive a complete upstream multi-part payment, which we relay out.
    incomingMultiPart.foreach(incoming => relayer.send(nodeRelayer, incoming))
    val outgoingCfg = outgoingPayFSM.expectMsgType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.TrampolineRelayed(incomingMultiPart.map(_.add)))
    val outgoingPayment = outgoingPayFSM.expectMsgType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)

    // Receive new extraneous multi-part HTLC.
    val i1 = IncomingPacket.NodeRelayPacket(
      UpdateAddHtlc(randomBytes32, Random.nextInt(100), 1000 msat, paymentHash, CltvExpiry(499990), TestConstants.emptyOnionPacket),
      Onion.createMultiPartPayload(1000 msat, incomingAmount, CltvExpiry(499990), incomingSecret),
      Onion.createNodeRelayPayload(outgoingAmount, outgoingExpiry, outgoingNodeId),
      nextTrampolinePacket)
    relayer.send(nodeRelayer, i1)
    commandBuffer.expectMsg(CommandBuffer.CommandSend(i1.add.channelId, CMD_FAIL_HTLC(i1.add.id, Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)), commit = true)))

    // Receive new HTLC with different details, but for the same payment hash.
    val i2 = IncomingPacket.NodeRelayPacket(
      UpdateAddHtlc(randomBytes32, Random.nextInt(100), 1500 msat, paymentHash, CltvExpiry(499990), TestConstants.emptyOnionPacket),
      Onion.createSinglePartPayload(1500 msat, CltvExpiry(499990), Some(randomBytes32)),
      Onion.createNodeRelayPayload(1250 msat, outgoingExpiry, outgoingNodeId),
      nextTrampolinePacket)
    relayer.send(nodeRelayer, i2)
    commandBuffer.expectMsg(CommandBuffer.CommandSend(i2.add.channelId, CMD_FAIL_HTLC(i2.add.id, Right(IncorrectOrUnknownPaymentDetails(1500 msat, nodeParams.currentBlockHeight)), commit = true)))

    outgoingPayFSM.expectNoMsg(100 millis)
  }

  test("fail to relay an incoming payment without payment secret") { f =>
    import f._

    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, CltvExpiry(500000), outgoingAmount, outgoingExpiry).copy(
      outerPayload = Onion.createSinglePartPayload(2000000 msat, CltvExpiry(500000)) // missing outer payment secret
    )
    relayer.send(nodeRelayer, p)

    val failure = IncorrectOrUnknownPaymentDetails(2000000 msat, nodeParams.currentBlockHeight)
    commandBuffer.expectMsg(CommandBuffer.CommandSend(p.add.channelId, CMD_FAIL_HTLC(p.add.id, Right(failure), commit = true)))
    outgoingPayFSM.expectNoMsg(100 millis)
  }

  test("fail to relay when incoming payment secrets don't match") { f =>
    import f._

    val p1 = createValidIncomingPacket(2000000 msat, 3000000 msat, CltvExpiry(500000), 2500000 msat, outgoingExpiry)
    val p2 = createValidIncomingPacket(1000000 msat, 3000000 msat, CltvExpiry(500000), 2500000 msat, outgoingExpiry).copy(
      outerPayload = Onion.createMultiPartPayload(1000000 msat, 3000000 msat, CltvExpiry(500000), randomBytes32)
    )
    relayer.send(nodeRelayer, p1)
    relayer.send(nodeRelayer, p2)

    val failure = IncorrectOrUnknownPaymentDetails(1000000 msat, nodeParams.currentBlockHeight)
    commandBuffer.expectMsg(CommandBuffer.CommandSend(p2.add.channelId, CMD_FAIL_HTLC(p2.add.id, Right(failure), commit = true)))
    commandBuffer.expectNoMsg(100 millis)
    outgoingPayFSM.expectNoMsg(100 millis)
  }

  test("fail to relay when expiry is too soon (single-part)") { f =>
    import f._

    val expiryIn = CltvExpiry(500000) // not ok (delta = 100)
    val expiryOut = CltvExpiry(499900)
    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, expiryIn, 1000000 msat, expiryOut)
    relayer.send(nodeRelayer, p)

    commandBuffer.expectMsg(CommandBuffer.CommandSend(p.add.channelId, CMD_FAIL_HTLC(p.add.id, Right(TrampolineExpiryTooSoon), commit = true)))
    commandBuffer.expectNoMsg(100 millis)
    outgoingPayFSM.expectNoMsg(100 millis)
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
    p.foreach(p => relayer.send(nodeRelayer, p))

    p.foreach(p => commandBuffer.expectMsg(CommandBuffer.CommandSend(p.add.channelId, CMD_FAIL_HTLC(p.add.id, Right(TrampolineExpiryTooSoon), commit = true))))
    commandBuffer.expectNoMsg(100 millis)
    outgoingPayFSM.expectNoMsg(100 millis)
  }

  test("fail to relay when fees are insufficient (single-part)") { f =>
    import f._

    val p = createValidIncomingPacket(2000000 msat, 2000000 msat, CltvExpiry(500000), 1999000 msat, CltvExpiry(490000))
    relayer.send(nodeRelayer, p)

    commandBuffer.expectMsg(CommandBuffer.CommandSend(p.add.channelId, CMD_FAIL_HTLC(p.add.id, Right(TrampolineFeeInsufficient), commit = true)))
    commandBuffer.expectNoMsg(100 millis)
    outgoingPayFSM.expectNoMsg(100 millis)
  }

  test("fail to relay when fees are insufficient (multi-part)") { f =>
    import f._

    val p = Seq(
      createValidIncomingPacket(2000000 msat, 3000000 msat, CltvExpiry(500000), 2999000 msat, CltvExpiry(400000)),
      createValidIncomingPacket(1000000 msat, 3000000 msat, CltvExpiry(500000), 2999000 msat, CltvExpiry(400000))
    )
    p.foreach(p => relayer.send(nodeRelayer, p))

    p.foreach(p => commandBuffer.expectMsg(CommandBuffer.CommandSend(p.add.channelId, CMD_FAIL_HTLC(p.add.id, Right(TrampolineFeeInsufficient), commit = true))))
    commandBuffer.expectNoMsg(100 millis)
    outgoingPayFSM.expectNoMsg(100 millis)
  }

  test("fail to relay because outgoing balance isn't sufficient") { f =>
    import f._

    // Receive an upstream multi-part payment.
    incomingMultiPart.foreach(p => relayer.send(nodeRelayer, p))
    val outgoingPaymentId = outgoingPayFSM.expectMsgType[SendPaymentConfig].id
    outgoingPayFSM.expectMsgType[SendMultiPartPayment]

    outgoingPayFSM.send(nodeRelayer, PaymentFailed(outgoingPaymentId, paymentHash, LocalFailure(PaymentError.BalanceTooLow) :: Nil))
    incomingMultiPart.foreach(p => commandBuffer.expectMsg(CommandBuffer.CommandSend(p.add.channelId, CMD_FAIL_HTLC(p.add.id, Right(TemporaryNodeFailure), commit = true))))
    commandBuffer.expectNoMsg(100 millis)
    eventListener.expectNoMsg(100 millis)
  }

  test("fail to relay because incoming fee isn't enough to find routes downstream") { f =>
    import f._

    // Receive an upstream multi-part payment.
    incomingMultiPart.foreach(p => relayer.send(nodeRelayer, p))
    val outgoingPaymentId = outgoingPayFSM.expectMsgType[SendPaymentConfig].id
    outgoingPayFSM.expectMsgType[SendMultiPartPayment]

    // If we're having a hard time finding routes, raising the fee/cltv will likely help.
    val failures = LocalFailure(RouteNotFound) :: RemoteFailure(Nil, Sphinx.DecryptedFailurePacket(outgoingNodeId, PermanentNodeFailure)) :: LocalFailure(RouteNotFound) :: Nil
    outgoingPayFSM.send(nodeRelayer, PaymentFailed(outgoingPaymentId, paymentHash, failures))
    incomingMultiPart.foreach(p => commandBuffer.expectMsg(CommandBuffer.CommandSend(p.add.channelId, CMD_FAIL_HTLC(p.add.id, Right(TrampolineFeeInsufficient), commit = true))))
    commandBuffer.expectNoMsg(100 millis)
    eventListener.expectNoMsg(100 millis)
  }

  test("fail to relay because of downstream failures") { f =>
    import f._

    // Receive an upstream multi-part payment.
    incomingMultiPart.foreach(p => relayer.send(nodeRelayer, p))
    val outgoingPaymentId = outgoingPayFSM.expectMsgType[SendPaymentConfig].id
    outgoingPayFSM.expectMsgType[SendMultiPartPayment]

    val failures = RemoteFailure(Nil, Sphinx.DecryptedFailurePacket(outgoingNodeId, FinalIncorrectHtlcAmount(42 msat))) :: UnreadableRemoteFailure(Nil) :: LocalFailure(RouteNotFound) :: Nil
    outgoingPayFSM.send(nodeRelayer, PaymentFailed(outgoingPaymentId, paymentHash, failures))
    incomingMultiPart.foreach(p => commandBuffer.expectMsg(CommandBuffer.CommandSend(p.add.channelId, CMD_FAIL_HTLC(p.add.id, Right(FinalIncorrectHtlcAmount(42 msat)), commit = true))))
    commandBuffer.expectNoMsg(100 millis)
    eventListener.expectNoMsg(100 millis)
  }

  test("compute route params") { f =>
    import f._

    relayer.send(nodeRelayer, incomingSinglePart)
    outgoingPayFSM.expectMsgType[SendPaymentConfig]
    val routeParams = outgoingPayFSM.expectMsgType[SendMultiPartPayment].routeParams.get
    val fee = nodeFee(nodeParams.feeBase, nodeParams.feeProportionalMillionth, outgoingAmount)
    assert(routeParams.maxFeePct === 0) // should be disabled
    assert(routeParams.maxFeeBase === incomingAmount - outgoingAmount - fee) // we collect our fee and then use what remains for the rest of the route
    assert(routeParams.routeMaxCltv === incomingSinglePart.add.cltvExpiry - outgoingExpiry - nodeParams.expiryDeltaBlocks) // we apply our cltv delta
  }

  test("relay incoming multi-part payment") { f =>
    import f._

    // Receive an upstream multi-part payment.
    incomingMultiPart.dropRight(1).foreach(incoming => relayer.send(nodeRelayer, incoming))
    outgoingPayFSM.expectNoMsg(100 millis) // we should NOT trigger a downstream payment before we received a complete upstream payment
    relayer.send(nodeRelayer, incomingMultiPart.last)

    val outgoingCfg = outgoingPayFSM.expectMsgType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.TrampolineRelayed(incomingMultiPart.map(_.add)))
    val outgoingPayment = outgoingPayFSM.expectMsgType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)

    // A first downstream HTLC is fulfilled.
    val ff1 = createDownstreamFulfill(outgoingPayFSM.ref)
    relayer.send(nodeRelayer, ff1)
    outgoingPayFSM.expectMsg(ff1)
    // We should immediately forward the fulfill upstream.
    incomingMultiPart.foreach(p => commandBuffer.expectMsg(CommandBuffer.CommandSend(p.add.channelId, CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))))

    // A second downstream HTLC is fulfilled.
    val ff2 = createDownstreamFulfill(outgoingPayFSM.ref)
    relayer.send(nodeRelayer, ff2)
    outgoingPayFSM.expectMsg(ff2)
    // We should not fulfill a second time upstream.
    commandBuffer.expectNoMsg(100 millis)

    // Once all the downstream payments have settled, we should emit the relayed event.
    outgoingPayFSM.send(nodeRelayer, createSuccessEvent(outgoingCfg.id))
    val relayEvent = eventListener.expectMsgType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming.toSet === incomingMultiPart.map(i => PaymentRelayed.Part(i.add.amountMsat, i.add.channelId)).toSet)
    assert(relayEvent.outgoing.nonEmpty)
    commandBuffer.expectNoMsg(100 millis)
  }

  test("relay incoming single-part payment") { f =>
    import f._

    // Receive an upstream single-part payment.
    relayer.send(nodeRelayer, incomingSinglePart)

    val outgoingCfg = outgoingPayFSM.expectMsgType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.TrampolineRelayed(incomingSinglePart.add :: Nil))
    val outgoingPayment = outgoingPayFSM.expectMsgType[SendMultiPartPayment]
    validateOutgoingPayment(outgoingPayment)

    val ff = createDownstreamFulfill(outgoingPayFSM.ref)
    relayer.send(nodeRelayer, ff)
    outgoingPayFSM.expectMsg(ff)
    val incomingAdd = incomingSinglePart.add
    commandBuffer.expectMsg(CommandBuffer.CommandSend(incomingAdd.channelId, CMD_FULFILL_HTLC(incomingAdd.id, paymentPreimage, commit = true)))

    outgoingPayFSM.send(nodeRelayer, createSuccessEvent(outgoingCfg.id))
    val relayEvent = eventListener.expectMsgType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming === Seq(PaymentRelayed.Part(incomingSinglePart.add.amountMsat, incomingSinglePart.add.channelId)))
    assert(relayEvent.outgoing.nonEmpty)
    commandBuffer.expectNoMsg(100 millis)
  }

  // TODO: re-activate this test once we have better MPP split to remote legacy recipients
  ignore("relay to non-trampoline recipient supporting multi-part") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val hints = List(List(ExtraHop(outgoingNodeId, ShortChannelId(42), feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12))))
    val features = Features(VariableLengthOnion.optional, PaymentSecret.mandatory, BasicMultiPartPayment.optional)
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(outgoingAmount * 3), paymentHash, randomKey, "Some invoice", extraHops = hints, features = Some(features))
    incomingMultiPart.foreach(incoming => relayer.send(nodeRelayer, incoming.copy(innerPayload = Onion.createNodeRelayToNonTrampolinePayload(
      incoming.innerPayload.amountToForward, outgoingAmount * 3, outgoingExpiry, outgoingNodeId, pr
    ))))

    val outgoingCfg = outgoingPayFSM.expectMsgType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.TrampolineRelayed(incomingMultiPart.map(_.add)))
    val outgoingPayment = outgoingPayFSM.expectMsgType[SendMultiPartPayment]
    assert(outgoingPayment.paymentSecret === pr.paymentSecret.get) // we should use the provided secret
    assert(outgoingPayment.totalAmount === outgoingAmount)
    assert(outgoingPayment.targetExpiry === outgoingExpiry)
    assert(outgoingPayment.targetNodeId === outgoingNodeId)
    assert(outgoingPayment.additionalTlvs === Nil)
    assert(outgoingPayment.routeParams.isDefined)
    assert(outgoingPayment.assistedRoutes === hints)

    val ff = createDownstreamFulfill(outgoingPayFSM.ref)
    relayer.send(nodeRelayer, ff)
    outgoingPayFSM.expectMsg(ff)
    incomingMultiPart.foreach(p => commandBuffer.expectMsg(CommandBuffer.CommandSend(p.add.channelId, CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))))

    outgoingPayFSM.send(nodeRelayer, createSuccessEvent(outgoingCfg.id))
    val relayEvent = eventListener.expectMsgType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming === incomingMultiPart.map(i => PaymentRelayed.Part(i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.nonEmpty)
    commandBuffer.expectNoMsg(100 millis)
  }

  test("relay to non-trampoline recipient without multi-part") { f =>
    import f._

    // Receive an upstream multi-part payment.
    val hints = List(List(ExtraHop(outgoingNodeId, ShortChannelId(42), feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12))))
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(outgoingAmount), paymentHash, randomKey, "Some invoice", extraHops = hints, features = Some(Features()))
    incomingMultiPart.foreach(incoming => relayer.send(nodeRelayer, incoming.copy(innerPayload = Onion.createNodeRelayToNonTrampolinePayload(
      incoming.innerPayload.amountToForward, incoming.innerPayload.amountToForward, outgoingExpiry, outgoingNodeId, pr
    ))))

    val outgoingCfg = outgoingPayFSM.expectMsgType[SendPaymentConfig]
    validateOutgoingCfg(outgoingCfg, Upstream.TrampolineRelayed(incomingMultiPart.map(_.add)))
    val outgoingPayment = outgoingPayFSM.expectMsgType[SendPayment]
    assert(outgoingPayment.routePrefix === Nil)
    assert(outgoingPayment.finalPayload.amount === outgoingAmount)
    assert(outgoingPayment.finalPayload.expiry === outgoingExpiry)
    assert(outgoingPayment.targetNodeId === outgoingNodeId)
    assert(outgoingPayment.routeParams.isDefined)
    assert(outgoingPayment.assistedRoutes === hints)

    val ff = createDownstreamFulfill(outgoingPayFSM.ref)
    relayer.send(nodeRelayer, ff)
    outgoingPayFSM.expectMsg(ff)
    incomingMultiPart.foreach(p => commandBuffer.expectMsg(CommandBuffer.CommandSend(p.add.channelId, CMD_FULFILL_HTLC(p.add.id, paymentPreimage, commit = true))))

    outgoingPayFSM.send(nodeRelayer, createSuccessEvent(outgoingCfg.id))
    val relayEvent = eventListener.expectMsgType[TrampolinePaymentRelayed]
    validateRelayEvent(relayEvent)
    assert(relayEvent.incoming === incomingMultiPart.map(i => PaymentRelayed.Part(i.add.amountMsat, i.add.channelId)))
    assert(relayEvent.outgoing.length === 1)
    commandBuffer.expectNoMsg(100 millis)
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

  def createDownstreamFulfill(payFSM: ActorRef): Relayer.ForwardFulfill = {
    val origin = Origin.TrampolineRelayed(null, Some(payFSM))
    Relayer.ForwardRemoteFulfill(UpdateFulfillHtlc(randomBytes32, Random.nextInt(100), paymentPreimage), origin, null)
  }

  def createSuccessEvent(id: UUID): PaymentSent =
    PaymentSent(id, paymentHash, paymentPreimage, outgoingAmount, outgoingNodeId, Seq(PaymentSent.PartialPayment(id, outgoingAmount, 10 msat, randomBytes32, None)))

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