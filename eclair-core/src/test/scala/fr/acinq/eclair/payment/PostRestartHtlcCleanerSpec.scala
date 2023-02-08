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

import akka.Done
import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.event.LoggingAdapter
import akka.testkit.TestProbe
import com.softwaremill.quicklens.{ModifyPimp, QuicklensAt}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, OutPoint, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchTxConfirmedTriggered
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.ChannelStateTestsBase
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus, PaymentType}
import fr.acinq.eclair.payment.OutgoingPaymentPacket.{Upstream, buildOutgoingPayment}
import fr.acinq.eclair.payment.PaymentPacketSpec._
import fr.acinq.eclair.payment.relay.{PostRestartHtlcCleaner, Relayer}
import fr.acinq.eclair.payment.send.SpontaneousRecipient
import fr.acinq.eclair.router.BaseRouterSpec.channelHopFromUpdate
import fr.acinq.eclair.router.Router.Route
import fr.acinq.eclair.transactions.Transactions.{ClaimRemoteDelayedOutputTx, InputInfo}
import fr.acinq.eclair.transactions.{DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.internal.channel.{ChannelCodecs, ChannelCodecsSpec}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, CustomCommitmentsPlugin, MilliSatoshiLong, NodeParams, TestConstants, TestKitBaseClass, TimestampMilliLong, randomBytes32, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, ParallelTestExecution}
import scodec.bits.ByteVector

import java.util.UUID
import scala.concurrent.Promise
import scala.concurrent.duration._

/**
 * Created by t-bast on 21/11/2019.
 */

class PostRestartHtlcCleanerSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ParallelTestExecution with ChannelStateTestsBase {

  import PostRestartHtlcCleanerSpec._

  case class FixtureParam(nodeParams: NodeParams, register: TestProbe, sender: TestProbe, eventListener: TestProbe) {
    def createRelayer(nodeParams1: NodeParams): (ActorRef, ActorRef) = {
      val relayer = system.actorOf(Relayer.props(nodeParams1, TestProbe().ref, register.ref, TestProbe().ref, TestProbe().ref.toTyped))
      // we need ensure the post-htlc-restart child actor is initialized
      sender.send(relayer, Relayer.GetChildActors(sender.ref))
      (relayer, sender.expectMsgType[Relayer.ChildActors].postRestartCleaner)
    }
  }

  override def withFixture(test: OneArgTest): Outcome = {
    within(30 seconds) {
      val nodeParams = TestConstants.Bob.nodeParams
      val register = TestProbe()
      val eventListener = TestProbe()
      system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, register, TestProbe(), eventListener)))
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

    val relayedPaymentHash = randomBytes32()
    val relayed = Origin.ChannelRelayedCold(channelId_ab_1, 5, 10 msat, 10 msat)
    val trampolineRelayedPaymentHash = randomBytes32()
    val trampolineRelayed = Origin.TrampolineRelayedCold((channelId_ab_1, 0L) :: (channelId_ab_2, 2L) :: Nil)

    val htlc_ab_1 = Seq(
      buildHtlcIn(0, channelId_ab_1, trampolineRelayedPaymentHash),
      buildHtlcIn(1, channelId_ab_1, randomBytes32()), // not relayed
      buildHtlcOut(2, channelId_ab_1, randomBytes32()),
      buildHtlcOut(3, channelId_ab_1, randomBytes32()),
      buildHtlcIn(4, channelId_ab_1, randomBytes32()), // not relayed
      buildHtlcIn(5, channelId_ab_1, relayedPaymentHash)
    )
    val htlc_ab_2 = Seq(
      buildHtlcIn(0, channelId_ab_2, randomBytes32()), // not relayed
      buildHtlcOut(1, channelId_ab_2, randomBytes32()),
      buildHtlcIn(2, channelId_ab_2, trampolineRelayedPaymentHash),
      buildHtlcOut(3, channelId_ab_2, randomBytes32()),
      buildHtlcIn(4, channelId_ab_2, randomBytes32()) // not relayed
    )

    val channels = Seq(
      ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_1, Map(51L -> relayed, 1L -> trampolineRelayed)),
      ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_2, Map(4L -> trampolineRelayed))
    )

    val channel = TestProbe()
    val (relayer, _) = f.createRelayer(nodeParams)
    relayer ! PostRestartHtlcCleaner.Init(channels)
    register.expectNoMessage(100 millis) // nothing should happen while channels are still offline.

    // channel 1 goes to NORMAL state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, channels.head.commitments.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(channels.head.commitments)))
    val fails_ab_1 = channel.expectMsgType[CMD_FAIL_HTLC] :: channel.expectMsgType[CMD_FAIL_HTLC] :: Nil
    assert(fails_ab_1.toSet == Set(CMD_FAIL_HTLC(1, Right(TemporaryNodeFailure()), commit = true), CMD_FAIL_HTLC(4, Right(TemporaryNodeFailure()), commit = true)))
    channel.expectNoMessage(100 millis)

    // channel 2 goes to NORMAL state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, channels(1).commitments.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(channels(1).commitments)))
    val fails_ab_2 = channel.expectMsgType[CMD_FAIL_HTLC] :: channel.expectMsgType[CMD_FAIL_HTLC] :: Nil
    assert(fails_ab_2.toSet == Set(CMD_FAIL_HTLC(0, Right(TemporaryNodeFailure()), commit = true), CMD_FAIL_HTLC(4, Right(TemporaryNodeFailure()), commit = true)))
    channel.expectNoMessage(100 millis)

    // let's assume that channel 1 was disconnected before having signed the fails, and gets connected again:
    system.eventStream.publish(ChannelStateChanged(channel.ref, channels.head.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(channels.head.commitments)))
    val fails_ab_1_bis = channel.expectMsgType[CMD_FAIL_HTLC] :: channel.expectMsgType[CMD_FAIL_HTLC] :: Nil
    assert(fails_ab_1_bis.toSet == Set(CMD_FAIL_HTLC(1, Right(TemporaryNodeFailure()), commit = true), CMD_FAIL_HTLC(4, Right(TemporaryNodeFailure()), commit = true)))
    channel.expectNoMessage(100 millis)

    // let's now assume that channel 1 gets reconnected, and it had the time to fail the htlcs:
    val data1 = channels.head.modify(_.commitments.active.at(0).localCommit.spec.htlcs).setTo(Set.empty)
    system.eventStream.publish(ChannelStateChanged(channel.ref, data1.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(data1.commitments)))
    channel.expectNoMessage(100 millis)

    // post-restart cleaner has cleaned up the htlcs, so next time it won't fail them anymore, even if we artificially submit the former state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, channels.head.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(channels.head.commitments)))
    channel.expectNoMessage(100 millis)
  }

  test("clean up upstream HTLCs for which we're the final recipient") { f =>
    import f._

    val preimage = randomBytes32()
    val paymentHash = Crypto.sha256(preimage)
    val invoice = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(500 msat), paymentHash, TestConstants.Bob.nodeKeyManager.nodeKey.privateKey, Left("Some invoice"), CltvExpiryDelta(18))
    nodeParams.db.payments.addIncomingPayment(invoice, preimage)
    nodeParams.db.payments.receiveIncomingPayment(paymentHash, 5000 msat)

    val htlc_ab_1 = Seq(
      buildFinalHtlc(0, channelId_ab_1, randomBytes32()),
      buildFinalHtlc(3, channelId_ab_1, paymentHash),
      buildFinalHtlc(5, channelId_ab_1, paymentHash),
      buildFinalHtlc(7, channelId_ab_1, randomBytes32())
    )
    val htlc_ab_2 = Seq(
      buildFinalHtlc(1, channelId_ab_2, randomBytes32()),
      buildFinalHtlc(3, channelId_ab_2, randomBytes32()),
      buildFinalHtlc(4, channelId_ab_2, paymentHash),
      buildFinalHtlc(9, channelId_ab_2, randomBytes32())
    )

    val channels = Seq(
      ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_1, Map.empty),
      ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_2, Map.empty)
    )

    val channel = TestProbe()
    val (relayer, _) = f.createRelayer(nodeParams)
    relayer ! PostRestartHtlcCleaner.Init(channels)
    register.expectNoMessage(100 millis) // nothing should happen while channels are still offline.

    // channel 1 goes to NORMAL state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, channels.head.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(channels.head.commitments)))
    val expected1 = Set(
      CMD_FAIL_HTLC(0, Right(TemporaryNodeFailure()), commit = true),
      CMD_FULFILL_HTLC(3, preimage, commit = true),
      CMD_FULFILL_HTLC(5, preimage, commit = true),
      CMD_FAIL_HTLC(7, Right(TemporaryNodeFailure()), commit = true)
    )
    val received1 = expected1.map(_ => channel.expectMsgType[Command])
    assert(received1 == expected1)
    channel.expectNoMessage(100 millis)

    // channel 2 goes to NORMAL state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, channels(1).channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(channels(1).commitments)))
    val expected2 = Set(
      CMD_FAIL_HTLC(1, Right(TemporaryNodeFailure()), commit = true),
      CMD_FAIL_HTLC(3, Right(TemporaryNodeFailure()), commit = true),
      CMD_FULFILL_HTLC(4, preimage, commit = true),
      CMD_FAIL_HTLC(9, Right(TemporaryNodeFailure()), commit = true)
    )
    val received2 = expected2.map(_ => channel.expectMsgType[Command])
    assert(received2 == expected2)
    channel.expectNoMessage(100 millis)

    // let's assume that channel 1 was disconnected before having signed the updates, and gets connected again:
    system.eventStream.publish(ChannelStateChanged(channel.ref, channels.head.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(channels.head.commitments)))
    val received3 = expected1.map(_ => channel.expectMsgType[Command])
    assert(received3 == expected1)
    channel.expectNoMessage(100 millis)

    // let's now assume that channel 1 gets reconnected, and it had the time to sign the htlcs:
    val data1 = channels.head.modify(_.commitments.active.at(0).localCommit.spec.htlcs).setTo(Set.empty)
    system.eventStream.publish(ChannelStateChanged(channel.ref, data1.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(data1.commitments)))
    channel.expectNoMessage(100 millis)

    // post-restart cleaner has cleaned up the htlcs, so next time it won't fail them anymore, even if we artificially submit the former state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, channels.head.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(channels.head.commitments)))
    channel.expectNoMessage(100 millis)
  }

  test("handle a local payment htlc-fail") { f =>
    import f._

    val testCase = setupLocalPayments(nodeParams)
    val (relayer, _) = createRelayer(nodeParams)
    relayer ! PostRestartHtlcCleaner.Init(List(testCase.channel))
    register.expectNoMessage(100 millis)

    sender.send(relayer, testCase.fails(1))
    eventListener.expectNoMessage(100 millis)
    // This is a multi-part payment, the second part is still pending.
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds(2)).get.status == OutgoingPaymentStatus.Pending)

    sender.send(relayer, testCase.fails(2))
    val e1 = eventListener.expectMsgType[PaymentFailed]
    assert(e1.id == testCase.parentId)
    assert(e1.paymentHash == paymentHash2)
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds(1)).get.status.isInstanceOf[OutgoingPaymentStatus.Failed])
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds(2)).get.status.isInstanceOf[OutgoingPaymentStatus.Failed])
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds.head).get.status == OutgoingPaymentStatus.Pending)

    sender.send(relayer, testCase.fails.head)
    val e2 = eventListener.expectMsgType[PaymentFailed]
    assert(e2.id == testCase.childIds.head)
    assert(e2.paymentHash == paymentHash1)
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds.head).get.status.isInstanceOf[OutgoingPaymentStatus.Failed])

    register.expectNoMessage(100 millis)
  }

  test("handle a local payment htlc-fulfill") { f =>
    import f._

    val testCase = setupLocalPayments(nodeParams)
    val (relayer, _) = f.createRelayer(nodeParams)
    relayer ! PostRestartHtlcCleaner.Init(List(testCase.channel))
    register.expectNoMessage(100 millis)

    sender.send(relayer, testCase.fulfills(1))
    eventListener.expectNoMessage(100 millis)
    // This is a multi-part payment, the second part is still pending.
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds(2)).get.status == OutgoingPaymentStatus.Pending)

    sender.send(relayer, testCase.fulfills(2))
    val e1 = eventListener.expectMsgType[PaymentSent]
    assert(e1.id == testCase.parentId)
    assert(e1.paymentPreimage == preimage2)
    assert(e1.paymentHash == paymentHash2)
    assert(e1.parts.length == 2)
    assert(e1.amountWithFees == 2834.msat)
    assert(e1.recipientAmount == 2500.msat)
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds(1)).get.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds(2)).get.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds.head).get.status == OutgoingPaymentStatus.Pending)

    sender.send(relayer, testCase.fulfills.head)
    val e2 = eventListener.expectMsgType[PaymentSent]
    assert(e2.id == testCase.childIds.head)
    assert(e2.paymentPreimage == preimage1)
    assert(e2.paymentHash == paymentHash1)
    assert(e2.parts.length == 1)
    assert(e2.recipientAmount == 561.msat)
    assert(nodeParams.db.payments.getOutgoingPayment(testCase.childIds.head).get.status.isInstanceOf[OutgoingPaymentStatus.Succeeded])

    register.expectNoMessage(100 millis)
  }

  test("ignore htlcs in downstream channels that have already been settled upstream") { f =>
    import f._

    val testCase = setupTrampolinePayments()
    val initialized = Promise[Done]()
    val postRestart = system.actorOf(PostRestartHtlcCleaner.props(nodeParams, register.ref, Some(initialized)))
    postRestart ! PostRestartHtlcCleaner.Init(testCase.channels)
    awaitCond(initialized.isCompleted)
    register.expectNoMessage(100 millis)

    val probe = TestProbe()
    probe.send(postRestart, PostRestartHtlcCleaner.GetBrokenHtlcs)
    val brokenHtlcs = probe.expectMsgType[PostRestartHtlcCleaner.BrokenHtlcs]
    assert(brokenHtlcs.notRelayed.map(htlc => (htlc.add.id, htlc.add.channelId)).toSet == testCase.notRelayed)
    assert(brokenHtlcs.relayedOut == Map(
      testCase.origin_1 -> Set(testCase.downstream_1_1).map(htlc => (htlc.channelId, htlc.id)),
      testCase.origin_2 -> Set(testCase.downstream_2_1, testCase.downstream_2_2, testCase.downstream_2_3).map(htlc => (htlc.channelId, htlc.id))
    ))
  }

  test("ignore htlcs in closing downstream channels that have been settled on-chain") { f =>
    import f._

    // There are three pending payments.
    // Payment 1:
    //  * 2 upstream htlcs
    //  * 1 downstream htlc that timed out on-chain
    //  * 1 downstream dust htlc that wasn't included in the on-chain commitment tx
    //  * 1 downstream htlc that was signed only locally and wasn't included in the on-chain commitment tx
    //  * this payment should be failed instantly when the upstream channels come back online
    // Payment 2:
    //  * 2 upstream htlcs
    //  * 1 downstream htlc that timed out on-chain
    //  * 1 downstream htlc that will be resolved on-chain
    //  * this payment should be fulfilled upstream once we receive the preimage
    // Payment 3:
    //  * 1 upstream htlc
    //  * 1 downstream htlc that timed out on-chain but doesn't have enough confirmations
    //  * this payment should not be failed when the upstream channels come back online: we should wait for the htlc-timeout
    //    transaction to have enough confirmations

    // Upstream HTLCs.
    val htlc_upstream_1 = Seq(buildHtlcIn(0, channelId_ab_1, paymentHash1), buildHtlcIn(5, channelId_ab_1, paymentHash2))
    val htlc_upstream_2 = Seq(buildHtlcIn(7, channelId_ab_2, paymentHash1), buildHtlcIn(9, channelId_ab_2, paymentHash2))
    val htlc_upstream_3 = Seq(buildHtlcIn(11, randomBytes32(), paymentHash3))
    val upstream_1 = Upstream.Trampoline(htlc_upstream_1.head.add :: htlc_upstream_2.head.add :: Nil)
    val upstream_2 = Upstream.Trampoline(htlc_upstream_1(1).add :: htlc_upstream_2(1).add :: Nil)
    val upstream_3 = Upstream.Trampoline(htlc_upstream_3.head.add :: Nil)
    val data_upstream_1 = ChannelCodecsSpec.makeChannelDataNormal(htlc_upstream_1, Map.empty)
    val data_upstream_2 = ChannelCodecsSpec.makeChannelDataNormal(htlc_upstream_2, Map.empty)
    val data_upstream_3 = ChannelCodecsSpec.makeChannelDataNormal(htlc_upstream_3, Map.empty)

    // Downstream HTLCs in closing channel.
    val (data_downstream, htlc_2_2) = {
      val setup = init()
      import setup._
      reachNormal(setup)
      val currentBlockHeight = alice.underlyingActor.nodeParams.currentBlockHeight

      {
        // Will be timed out.
        val (_, cmd) = makeCmdAdd(20000000 msat, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight, preimage1, upstream_1)
        addHtlc(cmd, alice, bob, alice2bob, bob2alice)
      }
      {
        // Dust, will not reach the blockchain.
        val (_, cmd) = makeCmdAdd(300000 msat, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight, preimage1, upstream_1)
        addHtlc(cmd, alice, bob, alice2bob, bob2alice)
      }
      {
        // Will be timed out.
        val (_, cmd) = makeCmdAdd(25000000 msat, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight, preimage2, upstream_2)
        addHtlc(cmd, alice, bob, alice2bob, bob2alice)
      }
      val htlc_2_2 = {
        // Will be fulfilled.
        val (_, cmd) = makeCmdAdd(30000000 msat, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight, preimage2, upstream_2)
        addHtlc(cmd, alice, bob, alice2bob, bob2alice)
      }
      {
        // Will be timed out but waiting for on-chain confirmations.
        val (_, cmd) = makeCmdAdd(31000000 msat, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight + 5, preimage3, upstream_3)
        addHtlc(cmd, alice, bob, alice2bob, bob2alice)
      }
      crossSign(alice, bob, alice2bob, bob2alice)

      {
        // Only signed locally, will not reach the blockchain.
        val (_, cmd) = makeCmdAdd(22000000 msat, bob.underlyingActor.nodeParams.nodeId, currentBlockHeight, preimage1, upstream_1)
        addHtlc(cmd, alice, bob, alice2bob, bob2alice)
        sender.send(alice, CMD_SIGN())
        alice2bob.expectMsgType[CommitSig]
      }

      val closingState = localClose(alice, alice2blockchain)
      alice ! WatchTxConfirmedTriggered(BlockHeight(42), 0, closingState.commitTx)
      // All committed htlcs timed out except the last two; one will be fulfilled later and the other will timeout later.
      assert(closingState.htlcTxs.size == 4)
      assert(getHtlcTimeoutTxs(closingState).length == 4)
      val htlcTxs = getHtlcTimeoutTxs(closingState).sortBy(_.tx.txOut.map(_.amount).sum)
      htlcTxs.reverse.drop(2).zipWithIndex.foreach {
        case (htlcTx, i) => alice ! WatchTxConfirmedTriggered(BlockHeight(201), i, htlcTx.tx)
      }
      (alice.stateData.asInstanceOf[DATA_CLOSING], htlc_2_2)
    }

    val channels = stored(data_upstream_1, data_upstream_2, data_upstream_3, data_downstream)

    val (relayer, _) = f.createRelayer(nodeParams)
    relayer ! PostRestartHtlcCleaner.Init(channels)
    register.expectNoMessage(100 millis) // nothing should happen while channels are still offline.

    val (channel_upstream_1, channel_upstream_2, channel_upstream_3) = (TestProbe(), TestProbe(), TestProbe())
    system.eventStream.publish(ChannelStateChanged(channel_upstream_1.ref, data_upstream_1.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(data_upstream_1.commitments)))
    system.eventStream.publish(ChannelStateChanged(channel_upstream_2.ref, data_upstream_2.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(data_upstream_2.commitments)))
    system.eventStream.publish(ChannelStateChanged(channel_upstream_3.ref, data_upstream_3.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(data_upstream_3.commitments)))

    // Payment 1 should fail instantly.
    channel_upstream_1.expectMsg(CMD_FAIL_HTLC(0, Right(TemporaryNodeFailure()), commit = true))
    channel_upstream_2.expectMsg(CMD_FAIL_HTLC(7, Right(TemporaryNodeFailure()), commit = true))
    channel_upstream_1.expectNoMessage(100 millis)
    channel_upstream_2.expectNoMessage(100 millis)

    // Payment 2 should fulfill once we receive the preimage.
    val origin_2 = Origin.TrampolineRelayedCold(upstream_2.adds.map(u => (u.channelId, u.id)).toList)
    sender.send(relayer, RES_ADD_SETTLED(origin_2, htlc_2_2, HtlcResult.OnChainFulfill(preimage2)))
    register.expectMsgAllOf(
      Register.Forward(replyTo = null, channelId_ab_1, CMD_FULFILL_HTLC(5, preimage2, commit = true)),
      Register.Forward(replyTo = null, channelId_ab_2, CMD_FULFILL_HTLC(9, preimage2, commit = true))
    )

    // Payment 3 should not be failed: we are still waiting for on-chain confirmation.
    channel_upstream_3.expectNoMessage(100 millis)
  }

  test("ignore htlcs that have a pending settlement command") { f =>
    import f._

    // Our channel contains two HTLCs that should be settled as soon as our peer comes back online.
    // The downstream HTLCs have already been settled, so they may look like they haven't been relayed but in fact they have.
    val htlc_ab = Seq(
      buildHtlcIn(1, channelId_ab_1, randomBytes32()),
      buildHtlcIn(4, channelId_ab_1, randomBytes32()),
    )
    val channelData = ChannelCodecsSpec.makeChannelDataNormal(htlc_ab, Map.empty)
    nodeParams.db.pendingCommands.addSettlementCommand(channelId_ab_1, CMD_FULFILL_HTLC(1, randomBytes32()))
    nodeParams.db.pendingCommands.addSettlementCommand(channelId_ab_1, CMD_FAIL_HTLC(4, Right(PermanentChannelFailure())))

    val (_, postRestart) = f.createRelayer(nodeParams)
    postRestart ! PostRestartHtlcCleaner.Init(List(channelData))
    sender.send(postRestart, PostRestartHtlcCleaner.GetBrokenHtlcs)
    val brokenHtlcs = sender.expectMsgType[PostRestartHtlcCleaner.BrokenHtlcs]
    assert(brokenHtlcs.relayedOut.isEmpty)
    assert(brokenHtlcs.notRelayed.isEmpty)
  }

  test("ignore htlcs in downstream revoked close") { f =>
    import f._

    val (paymentHash1, paymentHash2) = (randomBytes32(), randomBytes32())
    val htlc_ab = Seq(
      buildHtlcIn(1, channelId_ab_1, paymentHash1),
      buildHtlcIn(2, channelId_ab_1, paymentHash1),
      buildHtlcIn(4, channelId_ab_1, paymentHash2),
    )
    val upstreamChannel = ChannelCodecsSpec.makeChannelDataNormal(htlc_ab, Map.empty)
    val htlc_bc = Seq(
      buildHtlcOut(2, channelId_bc_1, paymentHash1),
      buildHtlcOut(3, channelId_bc_1, paymentHash1),
      buildHtlcOut(4, channelId_bc_1, paymentHash1),
      buildHtlcOut(5, channelId_bc_1, paymentHash2),
    )
    val origins: Map[Long, Origin] = Map(
      2L -> Origin.TrampolineRelayedCold((channelId_ab_1, 1L) :: (channelId_ab_1, 2L) :: Nil),
      3L -> Origin.TrampolineRelayedCold((channelId_ab_1, 1L) :: (channelId_ab_1, 2L) :: Nil),
      4L -> Origin.TrampolineRelayedCold((channelId_ab_1, 1L) :: (channelId_ab_1, 2L) :: Nil),
      5L -> Origin.ChannelRelayedCold(channelId_ab_1, 4, 550 msat, 500 msat),
    )
    val downstreamChannel = {
      val normal = ChannelCodecsSpec.makeChannelDataNormal(htlc_bc, origins)
      // NB: this isn't actually a revoked commit tx, but we don't check that here, if the channel says it's a revoked
      // commit we accept it as such, so it simplifies the test.
      val revokedCommitTx = normal.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.copy(txOut = Seq(TxOut(4500 sat, Script.pay2wpkh(randomKey().publicKey))))
      val dummyClaimMainTx = Transaction(2, Seq(TxIn(OutPoint(revokedCommitTx, 0), Nil, 0)), Seq(revokedCommitTx.txOut.head.copy(amount = 4000 sat)), 0)
      val dummyClaimMain = ClaimRemoteDelayedOutputTx(InputInfo(OutPoint(revokedCommitTx, 0), revokedCommitTx.txOut.head, Nil), dummyClaimMainTx)
      val rcp = RevokedCommitPublished(revokedCommitTx, Some(dummyClaimMain), None, Nil, Nil, Map(revokedCommitTx.txIn.head.outPoint -> revokedCommitTx))
      DATA_CLOSING(normal.commitments, BlockHeight(0), Script.write(Script.pay2wpkh(randomKey().publicKey)), mutualCloseProposed = Nil, revokedCommitPublished = List(rcp))
    }

    val channels = List(upstreamChannel, downstreamChannel)
    assert(Closing.isClosed(downstreamChannel, None).isEmpty)

    val (_, postRestart) = f.createRelayer(nodeParams)
    postRestart ! PostRestartHtlcCleaner.Init(channels)
    sender.send(postRestart, PostRestartHtlcCleaner.GetBrokenHtlcs)
    val brokenHtlcs = sender.expectMsgType[PostRestartHtlcCleaner.BrokenHtlcs]
    assert(brokenHtlcs.relayedOut.isEmpty)
    assert(brokenHtlcs.notRelayed == htlc_ab.map(htlc => PostRestartHtlcCleaner.IncomingHtlc(htlc.add, None)))
  }

  test("handle a channel relay htlc-fail") { f =>
    import f._

    val testCase = setupChannelRelayedPayments()
    val (relayer, _) = f.createRelayer(nodeParams)
    relayer ! PostRestartHtlcCleaner.Init(testCase.channels)
    register.expectNoMessage(100 millis)

    sender.send(relayer, buildForwardFail(testCase.downstream, testCase.origin))
    register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]

    sender.send(relayer, buildForwardFail(testCase.downstream, testCase.origin))
    register.expectNoMessage(100 millis) // the payment has already been failed upstream
    eventListener.expectNoMessage(100 millis)
  }

  test("handle a channel relay htlc-fulfill") { f =>
    import f._

    val testCase = setupChannelRelayedPayments()
    val (relayer, _) = f.createRelayer(nodeParams)
    relayer ! PostRestartHtlcCleaner.Init(testCase.channels)
    register.expectNoMessage(100 millis)

    sender.send(relayer, buildForwardFulfill(testCase.downstream, testCase.origin, preimage1))
    register.expectMsg(Register.Forward(null, testCase.origin.originChannelId, CMD_FULFILL_HTLC(testCase.origin.originHtlcId, preimage1, commit = true)))
    eventListener.expectMsgType[ChannelPaymentRelayed]

    sender.send(relayer, buildForwardFulfill(testCase.downstream, testCase.origin, preimage1))
    register.expectNoMessage(100 millis) // the payment has already been fulfilled upstream
    eventListener.expectNoMessage(100 millis)
  }

  test("handle a trampoline relay htlc-fail") { f =>
    import f._

    val testCase = setupTrampolinePayments()
    val (relayer, _) = f.createRelayer(nodeParams)
    relayer ! PostRestartHtlcCleaner.Init(testCase.channels)
    register.expectNoMessage(100 millis)

    // This downstream HTLC has two upstream HTLCs.
    sender.send(relayer, buildForwardFail(testCase.downstream_1_1, testCase.origin_1))
    val fails = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]] :: register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]] :: Nil
    assert(fails.toSet == testCase.origin_1.htlcs.map {
      case (channelId, htlcId) => Register.Forward(null, channelId, CMD_FAIL_HTLC(htlcId, Right(TemporaryNodeFailure()), commit = true))
    }.toSet)

    sender.send(relayer, buildForwardFail(testCase.downstream_1_1, testCase.origin_1))
    register.expectNoMessage(100 millis) // a duplicate failure should be ignored

    sender.send(relayer, buildForwardOnChainFail(testCase.downstream_2_1, testCase.origin_2))
    sender.send(relayer, buildForwardFail(testCase.downstream_2_2, testCase.origin_2))
    register.expectNoMessage(100 millis) // there is still a third downstream payment pending

    sender.send(relayer, buildForwardFail(testCase.downstream_2_3, testCase.origin_2))
    register.expectMsg(testCase.origin_2.htlcs.map {
      case (channelId, htlcId) => Register.Forward(null, channelId, CMD_FAIL_HTLC(htlcId, Right(TemporaryNodeFailure()), commit = true))
    }.head)

    register.expectNoMessage(100 millis)
    eventListener.expectNoMessage(100 millis)
  }

  test("handle a trampoline relay htlc-fulfill") { f =>
    import f._

    val testCase = setupTrampolinePayments()
    val (relayer, _) = f.createRelayer(nodeParams)
    relayer ! PostRestartHtlcCleaner.Init(testCase.channels)
    register.expectNoMessage(100 millis)

    // This downstream HTLC has two upstream HTLCs.
    sender.send(relayer, buildForwardFulfill(testCase.downstream_1_1, testCase.origin_1, preimage1))
    val fulfills = register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]] :: register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]] :: Nil
    assert(fulfills.toSet == testCase.origin_1.htlcs.map {
      case (channelId, htlcId) => Register.Forward(null, channelId, CMD_FULFILL_HTLC(htlcId, preimage1, commit = true))
    }.toSet)

    sender.send(relayer, buildForwardFulfill(testCase.downstream_1_1, testCase.origin_1, preimage1))
    register.expectNoMessage(100 millis) // a duplicate fulfill should be ignored

    // This payment has 3 downstream HTLCs, but we should fulfill upstream as soon as we receive the preimage.
    sender.send(relayer, buildForwardFulfill(testCase.downstream_2_1, testCase.origin_2, preimage2))
    register.expectMsg(testCase.origin_2.htlcs.map {
      case (channelId, htlcId) => Register.Forward(null, channelId, CMD_FULFILL_HTLC(htlcId, preimage2, commit = true))
    }.head)

    sender.send(relayer, buildForwardFulfill(testCase.downstream_2_2, testCase.origin_2, preimage2))
    sender.send(relayer, buildForwardFulfill(testCase.downstream_2_3, testCase.origin_2, preimage2))
    register.expectNoMessage(100 millis) // the payment has already been fulfilled upstream
    eventListener.expectNoMessage(100 millis)
  }

  test("handle a trampoline relay htlc-fail followed by htlc-fulfill") { f =>
    import f._

    val testCase = setupTrampolinePayments()
    val (relayer, _) = f.createRelayer(nodeParams)
    relayer ! PostRestartHtlcCleaner.Init(testCase.channels)
    register.expectNoMessage(100 millis)

    sender.send(relayer, buildForwardFail(testCase.downstream_2_1, testCase.origin_2))
    sender.send(relayer, buildForwardFulfill(testCase.downstream_2_2, testCase.origin_2, preimage2))
    register.expectMsg(testCase.origin_2.htlcs.map {
      case (channelId, htlcId) => Register.Forward(null, channelId, CMD_FULFILL_HTLC(htlcId, preimage2, commit = true))
    }.head)

    sender.send(relayer, buildForwardFail(testCase.downstream_2_3, testCase.origin_2))
    register.expectNoMessage(100 millis) // the payment has already been fulfilled upstream
    eventListener.expectNoMessage(100 millis)
  }

  test("relayed standard->non-standard HTLC is retained") { f =>
    import f._

    val relayedPaymentHash = randomBytes32()
    val trampolineRelayedPaymentHash = randomBytes32()
    val trampolineRelayed = Origin.TrampolineRelayedCold((channelId_ab_2, 0L) :: Nil)
    val relayedHtlcIn = buildHtlcIn(0L, channelId_ab_2, trampolineRelayedPaymentHash)
    val nonRelayedHtlcIn = buildHtlcIn(1L, channelId_ab_2, relayedPaymentHash)

    // @formatter:off
    val pluginParams = new CustomCommitmentsPlugin {
      override def name = "test with outgoing HTLC to remote"
      override def getIncomingHtlcs(np: NodeParams, log: LoggingAdapter): Seq[PostRestartHtlcCleaner.IncomingHtlc] = List.empty
      override def getHtlcsRelayedOut(htlcsIn: Seq[PostRestartHtlcCleaner.IncomingHtlc], np: NodeParams, log: LoggingAdapter): Map[Origin, Set[(ByteVector32, Long)]] = Map(trampolineRelayed -> Set((channelId_ab_1, 10L)))
    }
    // @formatter:on

    val nodeParams1 = nodeParams.copy(pluginParams = List(pluginParams))
    val c = ChannelCodecsSpec.makeChannelDataNormal(List(relayedHtlcIn, nonRelayedHtlcIn), Map.empty)

    val channel = TestProbe()
    val (relayer, _) = f.createRelayer(nodeParams1)
    relayer ! PostRestartHtlcCleaner.Init(List(c))
    register.expectNoMessage(100 millis) // nothing should happen while channels are still offline.

    // Standard channel goes to NORMAL state:
    system.eventStream.publish(ChannelStateChanged(channel.ref, c.commitments.channelId, system.deadLetters, a, OFFLINE, NORMAL, Some(c.commitments)))
    channel.expectMsg(CMD_FAIL_HTLC(1L, Right(TemporaryNodeFailure()), commit = true))
    channel.expectNoMessage(100 millis)
  }

  test("non-standard HTLC CMD_FAIL in relayDb is retained") { f =>
    import f._

    val trampolineRelayedPaymentHash = randomBytes32()
    val relayedHtlc1In = buildHtlcIn(0L, channelId_ab_1, trampolineRelayedPaymentHash)

    // @formatter:off
    val pluginParams = new CustomCommitmentsPlugin {
      override def name = "test with incoming HTLC from remote"
      override def getIncomingHtlcs(np: NodeParams, log: LoggingAdapter): Seq[PostRestartHtlcCleaner.IncomingHtlc] = List(PostRestartHtlcCleaner.IncomingHtlc(relayedHtlc1In.add, None))
      override def getHtlcsRelayedOut(htlcsIn: Seq[PostRestartHtlcCleaner.IncomingHtlc], np: NodeParams, log: LoggingAdapter): Map[Origin, Set[(ByteVector32, Long)]] = Map.empty
    }
    // @formatter:on

    val cmd1 = CMD_FAIL_HTLC(id = 0L, reason = Left(ByteVector.empty), replyTo_opt = None)
    val cmd2 = CMD_FAIL_HTLC(id = 1L, reason = Left(ByteVector.empty), replyTo_opt = None)
    val nodeParams1 = nodeParams.copy(pluginParams = List(pluginParams))
    nodeParams1.db.pendingCommands.addSettlementCommand(channelId_ab_1, cmd1)
    nodeParams1.db.pendingCommands.addSettlementCommand(channelId_ab_1, cmd2)
    val (relayer, _) = f.createRelayer(nodeParams1)
    relayer ! PostRestartHtlcCleaner.Init(Nil)
    register.expectNoMessage(100 millis)
    awaitCond(Seq(cmd1) == nodeParams1.db.pendingCommands.listSettlementCommands(channelId_ab_1))
  }

}

object PostRestartHtlcCleanerSpec {

  val channelId_ab_1 = randomBytes32()
  val channelId_ab_2 = randomBytes32()
  val channelId_bc_1 = randomBytes32()
  val channelId_bc_2 = randomBytes32()
  val channelId_bc_3 = randomBytes32()
  val channelId_bc_4 = randomBytes32()
  val channelId_bc_5 = randomBytes32()

  val (preimage1, preimage2, preimage3) = (randomBytes32(), randomBytes32(), randomBytes32())
  val (paymentHash1, paymentHash2, paymentHash3) = (Crypto.sha256(preimage1), Crypto.sha256(preimage2), Crypto.sha256(preimage3))

  def buildHtlc(htlcId: Long, channelId: ByteVector32, paymentHash: ByteVector32): UpdateAddHtlc = {
    val Right(payment) = buildOutgoingPayment(ActorRef.noSender, priv_a.privateKey, Upstream.Local(UUID.randomUUID()), paymentHash, Route(finalAmount, hops, None), SpontaneousRecipient(e, finalAmount, finalExpiry, randomBytes32()))
    UpdateAddHtlc(channelId, htlcId, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None)
  }

  def buildHtlcIn(htlcId: Long, channelId: ByteVector32, paymentHash: ByteVector32): DirectedHtlc = IncomingHtlc(buildHtlc(htlcId, channelId, paymentHash))

  def buildHtlcOut(htlcId: Long, channelId: ByteVector32, paymentHash: ByteVector32): DirectedHtlc = OutgoingHtlc(buildHtlc(htlcId, channelId, paymentHash))

  def buildFinalHtlc(htlcId: Long, channelId: ByteVector32, paymentHash: ByteVector32): DirectedHtlc = {
    val Right(payment) = buildOutgoingPayment(ActorRef.noSender, priv_a.privateKey, Upstream.Local(UUID.randomUUID()), paymentHash, Route(finalAmount, Seq(channelHopFromUpdate(a, b, channelUpdate_ab)), None), SpontaneousRecipient(b, finalAmount, finalExpiry, randomBytes32()))
    IncomingHtlc(UpdateAddHtlc(channelId, htlcId, payment.cmd.amount, paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None))
  }

  def buildForwardFail(add: UpdateAddHtlc, origin: Origin.Cold): RES_ADD_SETTLED[Origin.Cold, HtlcResult.Fail] =
    RES_ADD_SETTLED(origin, add, HtlcResult.RemoteFail(UpdateFailHtlc(add.channelId, add.id, ByteVector.empty)))

  def buildForwardOnChainFail(add: UpdateAddHtlc, origin: Origin.Cold): RES_ADD_SETTLED[Origin.Cold, HtlcResult.Fail] =
    RES_ADD_SETTLED(origin, add, HtlcResult.OnChainFail(HtlcsTimedoutDownstream(add.channelId, Set(add))))

  def buildForwardFulfill(add: UpdateAddHtlc, origin: Origin.Cold, preimage: ByteVector32): RES_ADD_SETTLED[Origin.Cold, HtlcResult.Fulfill] =
    RES_ADD_SETTLED(origin, add, HtlcResult.RemoteFulfill(UpdateFulfillHtlc(add.channelId, add.id, preimage)))

  case class LocalPaymentTest(channel: PersistentChannelData, parentId: UUID, childIds: Seq[UUID], fails: Seq[RES_ADD_SETTLED[Origin.Cold, HtlcResult.Fail]], fulfills: Seq[RES_ADD_SETTLED[Origin.Cold, HtlcResult.Fulfill]])

  /**
   * We setup two outgoing payments:
   *  - the first one is a single-part payment
   *  - the second one is a multi-part payment split between two child payments
   */
  def setupLocalPayments(nodeParams: NodeParams): LocalPaymentTest = {
    val parentId = UUID.randomUUID()
    val (id1, id2, id3) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

    val add1 = UpdateAddHtlc(channelId_bc_1, 72, 561 msat, paymentHash1, CltvExpiry(4200), onionRoutingPacket = TestConstants.emptyOnionPacket, blinding_opt = None)
    val origin1 = Origin.LocalCold(id1)
    val add2 = UpdateAddHtlc(channelId_bc_1, 75, 1105 msat, paymentHash2, CltvExpiry(4250), onionRoutingPacket = TestConstants.emptyOnionPacket, blinding_opt = None)
    val origin2 = Origin.LocalCold(id2)
    val add3 = UpdateAddHtlc(channelId_bc_1, 78, 1729 msat, paymentHash2, CltvExpiry(4300), onionRoutingPacket = TestConstants.emptyOnionPacket, blinding_opt = None)
    val origin3 = Origin.LocalCold(id3)

    // Prepare channels and payment state before restart.
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id1, id1, None, paymentHash1, PaymentType.Standard, add1.amountMsat, add1.amountMsat, c, 0 unixms, None, None, OutgoingPaymentStatus.Pending))
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id2, parentId, None, paymentHash2, PaymentType.Standard, add2.amountMsat, 2500 msat, c, 0 unixms, None, None, OutgoingPaymentStatus.Pending))
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(id3, parentId, None, paymentHash2, PaymentType.Standard, add3.amountMsat, 2500 msat, c, 0 unixms, None, None, OutgoingPaymentStatus.Pending))
    val channel = ChannelCodecsSpec.makeChannelDataNormal(
      Seq(add1, add2, add3).map(add => OutgoingHtlc(add)),
      Map(add1.id -> origin1, add2.id -> origin2, add3.id -> origin3)
    )

    val fails = Seq(buildForwardFail(add1, origin1), buildForwardFail(add2, origin2), buildForwardFail(add3, origin3))
    val fulfills = Seq(buildForwardFulfill(add1, origin1, preimage1), buildForwardFulfill(add2, origin2, preimage2), buildForwardFulfill(add3, origin3, preimage2))
    LocalPaymentTest(channel, parentId, Seq(id1, id2, id3), fails, fulfills)
  }

  case class ChannelRelayedPaymentTest(channels: Seq[PersistentChannelData], origin: Origin.ChannelRelayedCold, downstream: UpdateAddHtlc, notRelayed: Set[(Long, ByteVector32)])

  def setupChannelRelayedPayments(): ChannelRelayedPaymentTest = {
    // Upstream HTLCs.
    val htlc_ab_1 = Seq(
      buildHtlcIn(0, channelId_ab_1, paymentHash1)
    )

    val origin_1 = Origin.ChannelRelayedCold(htlc_ab_1.head.add.channelId, htlc_ab_1.head.add.id, htlc_ab_1.head.add.amountMsat, htlc_ab_1.head.add.amountMsat - 100.msat)

    val htlc_bc_1 = Seq(
      buildHtlcOut(6, channelId_bc_1, paymentHash1)
    )

    val data_ab_1 = ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_1, Map.empty)
    val data_bc_1 = ChannelCodecsSpec.makeChannelDataNormal(htlc_bc_1, Map(6L -> origin_1))

    val channels = List(data_ab_1, data_bc_1)

    val downstream_1 = htlc_bc_1.head.add

    ChannelRelayedPaymentTest(channels, origin_1, downstream_1, Set.empty)
  }

  case class TrampolinePaymentTest(channels: Seq[PersistentChannelData],
                                   origin_1: Origin.TrampolineRelayedCold,
                                   downstream_1_1: UpdateAddHtlc,
                                   origin_2: Origin.TrampolineRelayedCold,
                                   downstream_2_1: UpdateAddHtlc,
                                   downstream_2_2: UpdateAddHtlc,
                                   downstream_2_3: UpdateAddHtlc,
                                   notRelayed: Set[(Long, ByteVector32)])

  /**
   * We setup three trampoline relayed payments:
   *  - the first one has 2 upstream HTLCs and 1 downstream HTLC
   *  - the second one has 1 upstream HTLC and 3 downstream HTLCs
   *  - the third one has 2 downstream HTLCs temporarily stuck in closing channels: the upstream HTLCs have been
   *    correctly resolved when the channel went to closing, so we should ignore that payment (downstream will eventually
   *    settle on-chain).
   *
   * We also setup one normal relayed payment:
   *  - the downstream HTLC is stuck in a closing channel: the upstream HTLC has been correctly resolved, so we should
   *    ignore that payment (downstream will eventually settle on-chain).
   */
  def setupTrampolinePayments(): TrampolinePaymentTest = {
    // Upstream HTLCs.
    val htlc_ab_1 = Seq(
      buildHtlcIn(0, channelId_ab_1, paymentHash1),
      buildHtlcOut(2, channelId_ab_1, randomBytes32()), // ignored
      buildHtlcOut(3, channelId_ab_1, randomBytes32()), // ignored
      buildHtlcIn(5, channelId_ab_1, paymentHash2)
    )
    val htlc_ab_2 = Seq(
      buildHtlcOut(1, channelId_ab_2, randomBytes32()), // ignored
      buildHtlcIn(7, channelId_ab_2, paymentHash1),
      buildHtlcOut(9, channelId_ab_2, randomBytes32()) // ignored
    )

    val origin_1 = Origin.TrampolineRelayedCold((channelId_ab_1, 0L) :: (channelId_ab_2, 7L) :: Nil)
    val origin_2 = Origin.TrampolineRelayedCold((channelId_ab_1, 5L) :: Nil)
    // The following two origins reference upstream HTLCs that have already been settled.
    // They should be ignored by the post-restart clean-up.
    val origin_3 = Origin.TrampolineRelayedCold((channelId_ab_1, 57L) :: Nil)
    val origin_4 = Origin.ChannelRelayedCold(channelId_ab_2, 57, 150 msat, 100 msat)

    // Downstream HTLCs.
    val htlc_bc_1 = Seq(
      buildHtlcIn(1, channelId_bc_1, randomBytes32()), // not relayed
      buildHtlcOut(6, channelId_bc_1, paymentHash1),
      buildHtlcOut(8, channelId_bc_1, paymentHash2)
    )
    val htlc_bc_2 = Seq(
      buildHtlcIn(0, channelId_bc_2, randomBytes32()), // not relayed
      buildHtlcOut(1, channelId_bc_2, paymentHash2)
    )
    val htlc_bc_3 = Seq(
      buildHtlcIn(3, channelId_bc_3, randomBytes32()), // not relayed
      buildHtlcOut(4, channelId_bc_3, paymentHash2),
      buildHtlcIn(5, channelId_bc_3, randomBytes32()) // not relayed
    )
    val htlc_bc_4 = Seq(
      buildHtlcOut(5, channelId_bc_4, paymentHash3),
      buildHtlcIn(7, channelId_bc_4, paymentHash3) // not relayed
    )
    val htlc_bc_5 = Seq(
      buildHtlcOut(2, channelId_bc_5, paymentHash3),
      buildHtlcOut(4, channelId_bc_5, randomBytes32()) // channel relayed timing out downstream
    )

    val notRelayed = Set((1L, channelId_bc_1), (0L, channelId_bc_2), (3L, channelId_bc_3), (5L, channelId_bc_3), (7L, channelId_bc_4))

    val downstream_1_1 = UpdateAddHtlc(channelId_bc_1, 6L, finalAmount, paymentHash1, finalExpiry, TestConstants.emptyOnionPacket, None)
    val downstream_2_1 = UpdateAddHtlc(channelId_bc_1, 8L, finalAmount, paymentHash2, finalExpiry, TestConstants.emptyOnionPacket, None)
    val downstream_2_2 = UpdateAddHtlc(channelId_bc_2, 1L, finalAmount, paymentHash2, finalExpiry, TestConstants.emptyOnionPacket, None)
    val downstream_2_3 = UpdateAddHtlc(channelId_bc_3, 4L, finalAmount, paymentHash2, finalExpiry, TestConstants.emptyOnionPacket, None)

    val data_ab_1 = ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_1, Map.empty)
    val data_ab_2 = ChannelCodecsSpec.makeChannelDataNormal(htlc_ab_2, Map.empty)
    val data_bc_1 = ChannelCodecsSpec.makeChannelDataNormal(htlc_bc_1, Map(6L -> origin_1, 8L -> origin_2))
    val data_bc_2 = ChannelCodecsSpec.makeChannelDataNormal(htlc_bc_2, Map(1L -> origin_2))
    val data_bc_3 = ChannelCodecsSpec.makeChannelDataNormal(htlc_bc_3, Map(4L -> origin_2))
    val data_bc_4 = ChannelCodecsSpec.makeChannelDataNormal(htlc_bc_4, Map(5L -> origin_3))
    val data_bc_5 = ChannelCodecsSpec.makeChannelDataNormal(htlc_bc_5, Map(2L -> origin_3, 4L -> origin_4))

    val channels = List(data_ab_1, data_ab_2, data_bc_1, data_bc_2, data_bc_3, data_bc_4, data_bc_5)

    TrampolinePaymentTest(channels, origin_1, downstream_1_1, origin_2, downstream_2_1, downstream_2_2, downstream_2_3, notRelayed)
  }

  /**
   * The [[PostRestartHtlcCleaner]] only deals with data that has been persisted, and has [[Origin.Cold]] origins. We
   * simulate that by doing a codec roundtrip.
   */
  def stored(channels: PersistentChannelData*): Seq[PersistentChannelData] =
    channels.map(c => ChannelCodecs.channelDataCodec.decode(ChannelCodecs.channelDataCodec.encode(c).require).require.value)

}