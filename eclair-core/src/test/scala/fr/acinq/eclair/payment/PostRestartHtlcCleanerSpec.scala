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
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus, PaymentType}
import fr.acinq.eclair.payment.OutgoingPacket.buildCommand
import fr.acinq.eclair.payment.PaymentPacketSpec._
import fr.acinq.eclair.payment.relay.Relayer.{ForwardFail, ForwardFulfill}
import fr.acinq.eclair.payment.relay.{CommandBuffer, Origin, Relayer}
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.transactions.{DirectedHtlc, Direction, IN, OUT}
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, LongToBtcAmount, NodeParams, TestConstants, TestkitBaseClass, randomBytes32}
import org.scalatest.Outcome
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by t-bast on 21/11/2019.
 */

class PostRestartHtlcCleanerSpec extends TestkitBaseClass {

  import PostRestartHtlcCleanerSpec._

  case class FixtureParam(nodeParams: NodeParams, commandBuffer: TestProbe, sender: TestProbe, eventListener: TestProbe) {
    def createRelayer(): ActorRef = {
      system.actorOf(Relayer.props(nodeParams, TestProbe().ref, TestProbe().ref, commandBuffer.ref, TestProbe().ref))
    }
  }

  override def withFixture(test: OneArgTest): Outcome = {
    within(30 seconds) {
      val nodeParams = TestConstants.Bob.nodeParams
      val commandBuffer = TestProbe()
      val eventListener = TestProbe()
      system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, commandBuffer, TestProbe(), eventListener)))
    }
  }

  test("clean up upstream HTLCs that weren't relayed downstream") { f =>
    import f._

    // We simulate the following state:
    //          (channel AB1)
    //    +-<-<- 2, 3 -<-<-<-<-<-+
    //    +->->- 0, 1, 4, 5 ->->-+
    //    |                      |
    //    A                      B ---> relayed (AB1, 0), (AB1, 5), (AB2, 2)
    //    |                      |
    //    +->->- 0, 2, 4  ->->->-+
    //    +-<-<- 1, 3 -<-<-<-<-<-+
    //          (channel AB2)

    val relayedPaymentHash = randomBytes32
    val relayed = Origin.Relayed(channelId_ab_1, 5, 10 msat, 10 msat)
    val trampolineRelayedPaymentHash = randomBytes32
    val trampolineRelayed = Origin.TrampolineRelayed((channelId_ab_1, 0L) :: (channelId_ab_2, 2L) :: Nil, None)

    val htlc_ab_1 = Seq(
      buildHtlc(0, channelId_ab_1, trampolineRelayedPaymentHash, IN),
      buildHtlc(1, channelId_ab_1, randomBytes32, IN), // not relayed
      buildHtlc(2, channelId_ab_1, randomBytes32, OUT),
      buildHtlc(3, channelId_ab_1, randomBytes32, OUT),
      buildHtlc(4, channelId_ab_1, randomBytes32, IN), // not relayed
      buildHtlc(5, channelId_ab_1, relayedPaymentHash, IN)
    )
    val htlc_ab_2 = Seq(
      buildHtlc(0, channelId_ab_2, randomBytes32, IN), // not relayed
      buildHtlc(1, channelId_ab_2, randomBytes32, OUT),
      buildHtlc(2, channelId_ab_2, trampolineRelayedPaymentHash, IN),
      buildHtlc(3, channelId_ab_2, randomBytes32, OUT),
      buildHtlc(4, channelId_ab_2, randomBytes32, IN) // not relayed
    )

    val channels = Seq(
      ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_1, Map(51L -> relayed, 1L -> trampolineRelayed)),
      ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_2, Map(4L -> trampolineRelayed))
    )

    // Prepare channels state before restart.
    channels.foreach(c => nodeParams.db.channels.addOrUpdateChannel(c))

    val channel = TestProbe()
    f.createRelayer()
    commandBuffer.expectNoMsg(100 millis) // nothing should happen while channels are still offline.

    // channel 1 goes to NORMAL state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, system.deadLetters, a, OFFLINE, NORMAL, channels.head))
    val fails_ab_1 = channel.expectMsgType[CMD_FAIL_HTLC] :: channel.expectMsgType[CMD_FAIL_HTLC] :: Nil
    assert(fails_ab_1.toSet === Set(CMD_FAIL_HTLC(1, Right(TemporaryNodeFailure), commit = true), CMD_FAIL_HTLC(4, Right(TemporaryNodeFailure), commit = true)))
    channel.expectNoMsg(100 millis)

    // channel 2 goes to NORMAL state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, system.deadLetters, a, OFFLINE, NORMAL, channels(1)))
    val fails_ab_2 = channel.expectMsgType[CMD_FAIL_HTLC] :: channel.expectMsgType[CMD_FAIL_HTLC] :: Nil
    assert(fails_ab_2.toSet === Set(CMD_FAIL_HTLC(0, Right(TemporaryNodeFailure), commit = true), CMD_FAIL_HTLC(4, Right(TemporaryNodeFailure), commit = true)))
    channel.expectNoMsg(100 millis)

    // let's assume that channel 1 was disconnected before having signed the fails, and gets connected again:
    system.eventStream.publish(ChannelStateChanged(channel.ref, system.deadLetters, a, OFFLINE, NORMAL, channels.head))
    val fails_ab_1_bis = channel.expectMsgType[CMD_FAIL_HTLC] :: channel.expectMsgType[CMD_FAIL_HTLC] :: Nil
    assert(fails_ab_1_bis.toSet === Set(CMD_FAIL_HTLC(1, Right(TemporaryNodeFailure), commit = true), CMD_FAIL_HTLC(4, Right(TemporaryNodeFailure), commit = true)))
    channel.expectNoMsg(100 millis)

    // let's now assume that channel 1 gets reconnected, and it had the time to fail the htlcs:
    val data1 = channels.head.copy(commitments = channels.head.commitments.copy(localCommit = channels.head.commitments.localCommit.copy(spec = channels.head.commitments.localCommit.spec.copy(htlcs = Set.empty))))
    system.eventStream.publish(ChannelStateChanged(channel.ref, system.deadLetters, a, OFFLINE, NORMAL, data1))
    channel.expectNoMsg(100 millis)

    // post-restart cleaner has cleaned up the htlcs, so next time it won't fail them anymore, even if we artificially submit the former state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, system.deadLetters, a, OFFLINE, NORMAL, channels.head))
    channel.expectNoMsg(100 millis)
  }

  test("clean up upstream HTLCs for which we're the final recipient") { f =>
    import f._

    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val invoice = PaymentRequest(Block.TestnetGenesisBlock.hash, Some(500 msat), paymentHash, TestConstants.Bob.keyManager.nodeKey.privateKey, "Some invoice")
    nodeParams.db.payments.addIncomingPayment(invoice, preimage)
    nodeParams.db.payments.receiveIncomingPayment(paymentHash, 5000 msat)

    val htlc_ab_1 = Seq(
      buildFinalHtlc(0, channelId_ab_1, randomBytes32),
      buildFinalHtlc(3, channelId_ab_1, paymentHash),
      buildFinalHtlc(5, channelId_ab_1, paymentHash),
      buildFinalHtlc(7, channelId_ab_1, randomBytes32)
    )
    val htlc_ab_2 = Seq(
      buildFinalHtlc(1, channelId_ab_2, randomBytes32),
      buildFinalHtlc(3, channelId_ab_2, randomBytes32),
      buildFinalHtlc(4, channelId_ab_2, paymentHash),
      buildFinalHtlc(9, channelId_ab_2, randomBytes32)
    )

    val channels = Seq(
      ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_1, Map.empty),
      ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_2, Map.empty)
    )

    // Prepare channels state before restart.
    channels.foreach(c => nodeParams.db.channels.addOrUpdateChannel(c))

    val channel = TestProbe()
    f.createRelayer()
    commandBuffer.expectNoMsg(100 millis) // nothing should happen while channels are still offline.

    // channel 1 goes to NORMAL state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, system.deadLetters, a, OFFLINE, NORMAL, channels.head))
    val expected1 = Set(
      CMD_FAIL_HTLC(0, Right(TemporaryNodeFailure), commit = true),
      CMD_FULFILL_HTLC(3, preimage, commit = true),
      CMD_FULFILL_HTLC(5, preimage, commit = true),
      CMD_FAIL_HTLC(7, Right(TemporaryNodeFailure), commit = true)
    )
    val received1 = expected1.map(_ => channel.expectMsgType[Command])
    assert(received1 === expected1)
    channel.expectNoMsg(100 millis)

    // channel 2 goes to NORMAL state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, system.deadLetters, a, OFFLINE, NORMAL, channels(1)))
    val expected2 = Set(
      CMD_FAIL_HTLC(1, Right(TemporaryNodeFailure), commit = true),
      CMD_FAIL_HTLC(3, Right(TemporaryNodeFailure), commit = true),
      CMD_FULFILL_HTLC(4, preimage, commit = true),
      CMD_FAIL_HTLC(9, Right(TemporaryNodeFailure), commit = true)
    )
    val received2 = expected2.map(_ => channel.expectMsgType[Command])
    assert(received2 === expected2)
    channel.expectNoMsg(100 millis)

    // let's assume that channel 1 was disconnected before having signed the updates, and gets connected again:
    system.eventStream.publish(ChannelStateChanged(channel.ref, system.deadLetters, a, OFFLINE, NORMAL, channels.head))
    val received3 = expected1.map(_ => channel.expectMsgType[Command])
    assert(received3 === expected1)
    channel.expectNoMsg(100 millis)

    // let's now assume that channel 1 gets reconnected, and it had the time to sign the htlcs:
    val data1 = channels.head.copy(commitments = channels.head.commitments.copy(localCommit = channels.head.commitments.localCommit.copy(spec = channels.head.commitments.localCommit.spec.copy(htlcs = Set.empty))))
    system.eventStream.publish(ChannelStateChanged(channel.ref, system.deadLetters, a, OFFLINE, NORMAL, data1))
    channel.expectNoMsg(100 millis)

    // post-restart cleaner has cleaned up the htlcs, so next time it won't fail them anymore, even if we artificially submit the former state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, system.deadLetters, a, OFFLINE, NORMAL, channels.head))
    channel.expectNoMsg(100 millis)
  }

  test("handle a local payment htlc-fail") { f =>
    import f._

    val testCase = setupLocalPayments(nodeParams)
    val relayer = createRelayer()
    commandBuffer.expectNoMsg(100 millis)

    sender.send(relayer, testCase.fails(1))
    eventListener.expectNoMsg(100 millis)
    // This is a multi-part payment, the second part is still pending.
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds(2)).get.status === OutgoingPaymentStatus.Pending)

    sender.send(relayer, testCase.fails(2))
    val e1 = eventListener.expectMsgType[PaymentFailed]
    assert(e1.id === testCase.parentId)
    assert(e1.paymentHash === paymentHash2)
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds(1)).get.status.isInstanceOf[OutgoingPaymentStatus.Failed])
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds(2)).get.status.isInstanceOf[OutgoingPaymentStatus.Failed])
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds.head).get.status === OutgoingPaymentStatus.Pending)

    sender.send(relayer, testCase.fails.head)
    val e2 = eventListener.expectMsgType[PaymentFailed]
    assert(e2.id === testCase.childIds.head)
    assert(e2.paymentHash === paymentHash1)
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds.head).get.status.isInstanceOf[OutgoingPaymentStatus.Failed])

    commandBuffer.expectNoMsg(100 millis)
  }

  test("handle a local payment htlc-fulfill") { f =>
    import f._

    val testCase = setupLocalPayments(nodeParams)
    val relayer = f.createRelayer()
    commandBuffer.expectNoMsg(100 millis)

    sender.send(relayer, testCase.fulfills(1))
    eventListener.expectNoMsg(100 millis)
    // This is a multi-part payment, the second part is still pending.
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds(2)).get.status === OutgoingPaymentStatus.Pending)

    sender.send(relayer, testCase.fulfills(2))
    val e1 = eventListener.expectMsgType[PaymentSent]
    assert(e1.id === testCase.parentId)
    assert(e1.paymentPreimage === preimage2)
    assert(e1.paymentHash === paymentHash2)
    assert(e1.parts.length === 2)
    assert(e1.amountWithFees === 2834.msat)
    assert(e1.recipientAmount === 2500.msat)
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds(1)).get.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds(2)).get.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds.head).get.status === OutgoingPaymentStatus.Pending)

    sender.send(relayer, testCase.fulfills.head)
    val e2 = eventListener.expectMsgType[PaymentSent]
    assert(e2.id === testCase.childIds.head)
    assert(e2.paymentPreimage === preimage1)
    assert(e2.paymentHash === paymentHash1)
    assert(e2.parts.length === 1)
    assert(e2.recipientAmount === 561.msat)
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds.head).get.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])

    commandBuffer.expectNoMsg(100 millis)
  }

  test("handle a trampoline relay htlc-fail") { f =>
    import f._

    val testCase = setupTrampolinePayments(nodeParams)
    val relayer = f.createRelayer()
    commandBuffer.expectNoMsg(100 millis)

    // This downstream HTLC has two upstream HTLCs.
    sender.send(relayer, buildForwardFail(testCase.downstream_1_1, testCase.upstream_1))
    val fails = commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]] :: commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]] :: Nil
    assert(fails.toSet === testCase.upstream_1.origins.map {
      case (channelId, htlcId) => CommandBuffer.CommandSend(channelId, CMD_FAIL_HTLC(htlcId, Right(TemporaryNodeFailure), commit = true))
    }.toSet)

    sender.send(relayer, buildForwardFail(testCase.downstream_1_1, testCase.upstream_1))
    commandBuffer.expectNoMsg(100 millis) // a duplicate failure should be ignored

    sender.send(relayer, buildForwardFail(testCase.downstream_2_1, testCase.upstream_2))
    sender.send(relayer, buildForwardFail(testCase.downstream_2_2, testCase.upstream_2))
    commandBuffer.expectNoMsg(100 millis) // there is still a third downstream payment pending

    sender.send(relayer, buildForwardFail(testCase.downstream_2_3, testCase.upstream_2))
    commandBuffer.expectMsg(testCase.upstream_2.origins.map {
      case (channelId, htlcId) => CommandBuffer.CommandSend(channelId, CMD_FAIL_HTLC(htlcId, Right(TemporaryNodeFailure), commit = true))
    }.head)

    commandBuffer.expectNoMsg(100 millis)
    eventListener.expectNoMsg(100 millis)
  }

  test("handle a trampoline relay htlc-fulfill") { f =>
    import f._

    val testCase = setupTrampolinePayments(nodeParams)
    val relayer = f.createRelayer()
    commandBuffer.expectNoMsg(100 millis)

    // This downstream HTLC has two upstream HTLCs.
    sender.send(relayer, buildForwardFulfill(testCase.downstream_1_1, testCase.upstream_1, preimage1))
    val fails = commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FULFILL_HTLC]] :: commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FULFILL_HTLC]] :: Nil
    assert(fails.toSet === testCase.upstream_1.origins.map {
      case (channelId, htlcId) => CommandBuffer.CommandSend(channelId, CMD_FULFILL_HTLC(htlcId, preimage1, commit = true))
    }.toSet)

    sender.send(relayer, buildForwardFulfill(testCase.downstream_1_1, testCase.upstream_1, preimage1))
    commandBuffer.expectNoMsg(100 millis) // a duplicate fulfill should be ignored

    // This payment has 3 downstream HTLCs, but we should fulfill upstream as soon as we receive the preimage.
    sender.send(relayer, buildForwardFulfill(testCase.downstream_2_1, testCase.upstream_2, preimage2))
    commandBuffer.expectMsg(testCase.upstream_2.origins.map {
      case (channelId, htlcId) => CommandBuffer.CommandSend(channelId, CMD_FULFILL_HTLC(htlcId, preimage2, commit = true))
    }.head)

    sender.send(relayer, buildForwardFulfill(testCase.downstream_2_2, testCase.upstream_2, preimage2))
    sender.send(relayer, buildForwardFulfill(testCase.downstream_2_3, testCase.upstream_2, preimage2))
    commandBuffer.expectNoMsg(100 millis) // the payment has already been fulfilled upstream
    eventListener.expectNoMsg(100 millis)
  }

  test("handle a trampoline relay htlc-fail followed by htlc-fulfill") { f =>
    import f._

    val testCase = setupTrampolinePayments(nodeParams)
    val relayer = f.createRelayer()
    commandBuffer.expectNoMsg(100 millis)

    sender.send(relayer, buildForwardFail(testCase.downstream_2_1, testCase.upstream_2))

    sender.send(relayer, buildForwardFulfill(testCase.downstream_2_2, testCase.upstream_2, preimage2))
    commandBuffer.expectMsg(testCase.upstream_2.origins.map {
      case (channelId, htlcId) => CommandBuffer.CommandSend(channelId, CMD_FULFILL_HTLC(htlcId, preimage2, commit = true))
    }.head)

    sender.send(relayer, buildForwardFail(testCase.downstream_2_3, testCase.upstream_2))
    commandBuffer.expectNoMsg(100 millis) // the payment has already been fulfilled upstream
    eventListener.expectNoMsg(100 millis)
  }

}

object PostRestartHtlcCleanerSpec {

  val channelId_ab_1 = randomBytes32
  val channelId_ab_2 = randomBytes32
  val channelId_bc_1 = randomBytes32
  val channelId_bc_2 = randomBytes32
  val channelId_bc_3 = randomBytes32

  val (preimage1, preimage2) = (randomBytes32, randomBytes32)
  val (paymentHash1, paymentHash2) = (Crypto.sha256(preimage1), Crypto.sha256(preimage2))

  def buildHtlc(htlcId: Long, channelId: ByteVector32, paymentHash: ByteVector32, direction: Direction): DirectedHtlc = {
    val (cmd, _) = buildCommand(Upstream.Local(UUID.randomUUID()), paymentHash, hops, FinalLegacyPayload(finalAmount, finalExpiry))
    val add = UpdateAddHtlc(channelId, htlcId, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    DirectedHtlc(direction, add)
  }

  def buildFinalHtlc(htlcId: Long, channelId: ByteVector32, paymentHash: ByteVector32): DirectedHtlc = {
    val (cmd, _) = buildCommand(Upstream.Local(UUID.randomUUID()), paymentHash, ChannelHop(a, TestConstants.Bob.nodeParams.nodeId, channelUpdate_ab) :: Nil, FinalLegacyPayload(finalAmount, finalExpiry))
    val add = UpdateAddHtlc(channelId, htlcId, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    DirectedHtlc(IN, add)
  }

  def buildForwardFail(add: UpdateAddHtlc, origin: Origin) =
    ForwardFail(UpdateFailHtlc(add.channelId, add.id, ByteVector.empty), origin, add)

  def buildForwardFulfill(add: UpdateAddHtlc, origin: Origin, preimage: ByteVector32) =
    ForwardFulfill(UpdateFulfillHtlc(add.channelId, add.id, preimage), origin, add)

  case class LocalPaymentTest(parentId: UUID, childIds: Seq[UUID], fails: Seq[ForwardFail], fulfills: Seq[ForwardFulfill])

  /**
   * We setup two outgoing payments:
   *  - the first one is a single-part payment
   *  - the second one is a multi-part payment split between two child payments
   */
  def setupLocalPayments(nodeParams: NodeParams): LocalPaymentTest = {
    val parentId = UUID.randomUUID()
    val (id1, id2, id3) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

    val add1 = UpdateAddHtlc(channelId_bc_1, 72, 561 msat, paymentHash1, CltvExpiry(4200), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val origin1 = Origin.Local(id1, None)
    val add2 = UpdateAddHtlc(channelId_bc_1, 75, 1105 msat, paymentHash2, CltvExpiry(4250), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val origin2 = Origin.Local(id2, None)
    val add3 = UpdateAddHtlc(channelId_bc_1, 78, 1729 msat, paymentHash2, CltvExpiry(4300), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val origin3 = Origin.Local(id3, None)

    // Prepare channels and payment state before restart.
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id1, id1, None, paymentHash1, PaymentType.Standard, add1.amountMsat, add1.amountMsat, c, 0, None, OutgoingPaymentStatus.Pending))
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id2, parentId, None, paymentHash2, PaymentType.Standard, add2.amountMsat, 2500 msat, c, 0, None, OutgoingPaymentStatus.Pending))
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id3, parentId, None, paymentHash2, PaymentType.Standard, add3.amountMsat, 2500 msat, c, 0, None, OutgoingPaymentStatus.Pending))
    nodeParams.db.channels.addOrUpdateChannel(ChannelCodecsSpec.makeChannelDataNormal(
      Seq(add1, add2, add3).map(add => DirectedHtlc(OUT, add)),
      Map(add1.id -> origin1, add2.id -> origin2, add3.id -> origin3))
    )

    val fails = Seq(buildForwardFail(add1, origin1), buildForwardFail(add2, origin2), buildForwardFail(add3, origin3))
    val fulfills = Seq(buildForwardFulfill(add1, origin1, preimage1), buildForwardFulfill(add2, origin2, preimage2), buildForwardFulfill(add3, origin3, preimage2))
    LocalPaymentTest(parentId, Seq(id1, id2, id3), fails, fulfills)
  }

  case class TrampolinePaymentTest(upstream_1: Origin.TrampolineRelayed,
                                   downstream_1_1: UpdateAddHtlc,
                                   upstream_2: Origin.TrampolineRelayed,
                                   downstream_2_1: UpdateAddHtlc,
                                   downstream_2_2: UpdateAddHtlc,
                                   downstream_2_3: UpdateAddHtlc)

  /**
   * We setup two trampoline relayed payments:
   *  - the first one has 2 upstream HTLCs and 1 downstream HTLC
   *  - the second one has 1 upstream HTLC and 3 downstream HTLCs
   */
  def setupTrampolinePayments(nodeParams: NodeParams): TrampolinePaymentTest = {
    // Upstream HTLCs.
    val htlc_ab_1 = Seq(
      buildHtlc(0, channelId_ab_1, paymentHash1, IN),
      buildHtlc(2, channelId_ab_1, randomBytes32, OUT), // ignored
      buildHtlc(3, channelId_ab_1, randomBytes32, OUT), // ignored
      buildHtlc(5, channelId_ab_1, paymentHash2, IN)
    )
    val htlc_ab_2 = Seq(
      buildHtlc(1, channelId_ab_2, randomBytes32, OUT), // ignored
      buildHtlc(7, channelId_ab_2, paymentHash1, IN),
      buildHtlc(9, channelId_ab_2, randomBytes32, OUT) // ignored
    )

    val origin_1 = Origin.TrampolineRelayed((channelId_ab_1, 0L) :: (channelId_ab_2, 7L) :: Nil, None)
    val origin_2 = Origin.TrampolineRelayed((channelId_ab_1, 5L) :: Nil, None)

    // Downstream HTLCs.
    val htlc_bc_1 = Seq(
      buildHtlc(1, channelId_bc_1, randomBytes32, IN), // ignored
      buildHtlc(6, channelId_bc_1, paymentHash1, OUT),
      buildHtlc(8, channelId_bc_1, paymentHash2, OUT)
    )
    val htlc_bc_2 = Seq(
      buildHtlc(0, channelId_bc_2, randomBytes32, IN), // ignored
      buildHtlc(1, channelId_bc_2, paymentHash2, OUT)
    )
    val htlc_bc_3 = Seq(
      buildHtlc(3, channelId_bc_3, randomBytes32, IN), // ignored
      buildHtlc(4, channelId_bc_3, paymentHash2, OUT),
      buildHtlc(5, channelId_bc_3, randomBytes32, IN) // ignored
    )

    val downstream_1_1 = UpdateAddHtlc(channelId_bc_1, 6L, finalAmount, paymentHash1, finalExpiry, TestConstants.emptyOnionPacket)
    val downstream_2_1 = UpdateAddHtlc(channelId_bc_1, 8L, finalAmount, paymentHash2, finalExpiry, TestConstants.emptyOnionPacket)
    val downstream_2_2 = UpdateAddHtlc(channelId_bc_2, 1L, finalAmount, paymentHash2, finalExpiry, TestConstants.emptyOnionPacket)
    val downstream_2_3 = UpdateAddHtlc(channelId_bc_3, 4L, finalAmount, paymentHash2, finalExpiry, TestConstants.emptyOnionPacket)

    val data_ab_1 = ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_1, Map.empty)
    val data_ab_2 = ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_2, Map.empty)
    val data_bc_1 = ChannelCodecsSpec.makeChannelDataNormal(htlc_bc_1, Map(6L -> origin_1, 8L -> origin_2))
    val data_bc_2 = ChannelCodecsSpec.makeChannelDataNormal(htlc_bc_2, Map(1L -> origin_2))
    val data_bc_3 = ChannelCodecsSpec.makeChannelDataNormal(htlc_bc_3, Map(4L -> origin_2))

    // Prepare channels state before restart.
    nodeParams.db.channels.addOrUpdateChannel(data_ab_1)
    nodeParams.db.channels.addOrUpdateChannel(data_ab_2)
    nodeParams.db.channels.addOrUpdateChannel(data_bc_1)
    nodeParams.db.channels.addOrUpdateChannel(data_bc_2)
    nodeParams.db.channels.addOrUpdateChannel(data_bc_3)

    TrampolinePaymentTest(origin_1, downstream_1_1, origin_2, downstream_2_1, downstream_2_2, downstream_2_3)
  }

}