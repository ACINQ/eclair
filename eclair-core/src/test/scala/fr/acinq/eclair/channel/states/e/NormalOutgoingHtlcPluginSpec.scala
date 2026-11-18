/*
 * Copyright 2026 ACINQ SAS
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

package fr.acinq.eclair.channel.states.e

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.ValidateOutgoingHtlcPlugin.{CommitmentBalance, OutgoingHtlcInfo, OutgoingHtlcOrigin}
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol._
import org.scalatest.{Outcome, Tag}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration._
import scala.jdk.CollectionConverters.CollectionHasAsScala

/**
 * Tests for [[ValidateOutgoingHtlcPlugin]]: plugins that may reject outgoing HTLCs before they're added to a channel.
 */
class NormalOutgoingHtlcPluginSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  /** A plugin that records every call it receives and whose decision can be changed by tests. */
  class TestHtlcValidator(override val name: String) extends ValidateOutgoingHtlcPlugin {
    val calls = new ConcurrentLinkedQueue[OutgoingHtlcInfo]()
    @volatile var validate: OutgoingHtlcInfo => Either[String, Unit] = _ => Right(())

    override def validateOutgoingHtlc(htlc: OutgoingHtlcInfo): Either[String, Unit] = {
      calls.add(htlc)
      validate(htlc)
    }
  }

  case class FixtureParam(setup: SetupFixture, validator1: TestHtlcValidator, validator2: TestHtlcValidator)

  override def withFixture(test: OneArgTest): Outcome = {
    val validator1 = new TestHtlcValidator("validator-1")
    val validator2 = new TestHtlcValidator("validator-2")
    val aliceNodeParams = TestConstants.Alice.nodeParams
    val setup = init(nodeParamsA = aliceNodeParams.copy(pluginParams = aliceNodeParams.pluginParams ++ Seq(validator1, validator2)), tags = test.tags)
    import setup._
    within(30 seconds) {
      reachNormal(setup, test.tags)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(FixtureParam(setup, validator1, validator2)))
    }
  }

  private def makeCmd(amount: MilliSatoshi, replyTo: ActorRef, upstream: Upstream.Hot, currentBlockHeight: BlockHeight): CMD_ADD_HTLC = {
    CMD_ADD_HTLC(replyTo, amount, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, Reputation.Score.max(accountable = false), None, Origin.Hot(replyTo, upstream))
  }

  private def relayedUpstream(amountOut: MilliSatoshi, currentBlockHeight: BlockHeight): Upstream.Hot.Channel = {
    val htlcIn = UpdateAddHtlc(channelId = randomBytes32(), id = 7, amountMsat = amountOut + 10_000.msat, paymentHash = randomBytes32(), cltvExpiry = CltvExpiryDelta(150).toCltvExpiry(currentBlockHeight), onionRoutingPacket = TestConstants.emptyOnionPacket, pathKey_opt = None, accountable = false, fundingFee_opt = None)
    Upstream.Hot.Channel(htlcIn, TimestampMilli.now(), randomKey().publicKey, 0.1)
  }

  test("send htlc when every plugin accepts it") { f =>
    import f._
    import f.setup._

    val commitments = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(commitments.channelParams.localParams.paysCommitTxFees)
    val sender = TestProbe()
    val upstream = Upstream.Local(UUID.randomUUID())
    val cmd = makeCmd(50_000_000 msat, sender.ref, upstream, currentBlockHeight)
    alice ! cmd
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
    assert(htlc.id == 0 && htlc.amountMsat == cmd.amount)

    // Plugins receive the balances that include the new HTLC. Since we pay the commit tx fees, the new HTLC output also
    // reduces the amount we can send: we keep a fee buffer that pays for it at twice the current feerate.
    val htlcOutputFee = Transactions.htlcOutputFee(commitments.latest.remoteCommit.spec.commitTxFeerate * 2, commitments.latest.commitmentFormat)
    assert(htlcOutputFee > 0.msat)
    val expected = OutgoingHtlcInfo(
      remoteNodeId = bob.underlyingActor.nodeParams.nodeId,
      channelId = htlc.channelId,
      paymentHash = cmd.paymentHash,
      amount = cmd.amount,
      cltvExpiry = cmd.cltvExpiry,
      origin = OutgoingHtlcOrigin.Local(upstream.id),
      commitments = Seq(CommitmentBalance(commitments.latest.fundingTxId, TestConstants.fundingSatoshis, localBalanceAfter = 750_000_000 msat)), // we started with 800k sat
      availableBalanceForSendAfter = commitments.availableBalanceForSend - cmd.amount - htlcOutputFee,
    )
    assert(validator1.calls.asScala.toSeq == Seq(expected))
    assert(validator2.calls.asScala.toSeq == Seq(expected))
  }

  test("reject htlc when a plugin rejects it") { f =>
    import f._
    import f.setup._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[OutgoingHtlcNotAdded])
    validator2.validate = _ => Left("balance too low")

    val sender = TestProbe()
    val cmd = makeCmd(50_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    alice ! cmd
    val failure = sender.expectMsgType[RES_ADD_FAILED[ChannelException]]
    assert(failure.t == OutgoingHtlcRejectedByPlugin(initialState.channelId, cmd.amount, "validator-2", "balance too low"))
    assert(failure.channelUpdate.contains(initialState.channelUpdate))
    assert(listener.expectMsgType[OutgoingHtlcNotAdded].reason == failure.t)
    alice2bob.expectNoMessage(100 millis)
    assert(alice.stateData == initialState)

    // The rejected HTLC didn't consume an HTLC id.
    validator2.validate = _ => Right(())
    alice ! makeCmd(50_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    assert(alice2bob.expectMsgType[UpdateAddHtlc].id == 0)
  }

  test("stop at the first plugin that rejects the htlc") { f =>
    import f._
    import f.setup._

    validator1.validate = _ => Left("rejected by validator-1")
    validator2.validate = _ => Left("rejected by validator-2")

    val sender = TestProbe()
    alice ! makeCmd(50_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    val failure = sender.expectMsgType[RES_ADD_FAILED[ChannelException]]
    assert(failure.t.asInstanceOf[OutgoingHtlcRejectedByPlugin].plugin == "validator-1")
    assert(validator1.calls.size() == 1)
    assert(validator2.calls.isEmpty)
    alice2bob.expectNoMessage(100 millis)
  }

  test("reject htlc when a plugin throws") { f =>
    import f._
    import f.setup._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    val errors = Seq(
      new IllegalStateException("boom") -> "plugin error: java.lang.IllegalStateException: boom",
      // The exception class is included even when there is no message.
      new NullPointerException() -> "plugin error: java.lang.NullPointerException",
    )
    errors.foreach { case (error, expectedReason) =>
      validator1.validate = _ => throw error
      val cmd = makeCmd(50_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
      alice ! cmd
      val failure = sender.expectMsgType[RES_ADD_FAILED[ChannelException]]
      assert(failure.t == OutgoingHtlcRejectedByPlugin(initialState.channelId, cmd.amount, "validator-1", expectedReason))
    }
    assert(validator2.calls.isEmpty)
    alice2bob.expectNoMessage(100 millis)
    assert(alice.stateData == initialState)
  }

  test("restore the interrupted flag when a plugin throws an InterruptedException") { f =>
    import f._
    import f.setup._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    validator1.validate = _ => throw new InterruptedException("interrupted")
    val sender = TestProbe()
    val cmd = makeCmd(50_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    // The channel actor uses a CallingThreadDispatcher: the plugin runs on the test thread.
    alice ! cmd
    // This also clears the flag, so that the test thread can keep waiting for messages.
    assert(Thread.interrupted())
    val failure = sender.expectMsgType[RES_ADD_FAILED[ChannelException]]
    assert(failure.t == OutgoingHtlcRejectedByPlugin(initialState.channelId, cmd.amount, "validator-1", "plugin error: java.lang.InterruptedException: interrupted"))
    assert(alice.stateName == NORMAL)
    assert(alice.stateData == initialState)
  }

  test("reject htlc without closing the channel when a plugin throws a fatal error") { f =>
    import f._
    import f.setup._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    // Those errors aren't caught by NonFatal: they must not reach the channel's default exception handler, which would
    // force-close the channel. A LinkageError is what we'd get if a plugin was compiled against another eclair version.
    val errors = Seq(new NoClassDefFoundError("fr/acinq/eclair/MissingClass"), new StackOverflowError())
    errors.foreach { error =>
      validator1.validate = _ => throw error
      val cmd = makeCmd(50_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
      alice ! cmd
      val failure = sender.expectMsgType[RES_ADD_FAILED[ChannelException]]
      assert(failure.t == OutgoingHtlcRejectedByPlugin(initialState.channelId, cmd.amount, "validator-1", s"plugin error: $error"))
    }
    alice2bob.expectNoMessage(100 millis)
    assert(alice.stateName == NORMAL)
    assert(alice.stateData == initialState)
  }

  test("reject htlc without closing the channel when a plugin returns null") { f =>
    import f._
    import f.setup._

    // This can easily happen with plugins written in Java or Kotlin.
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    validator1.validate = _ => null
    val sender = TestProbe()
    val cmd = makeCmd(50_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    alice ! cmd
    val failure = sender.expectMsgType[RES_ADD_FAILED[ChannelException]]
    assert(failure.t == OutgoingHtlcRejectedByPlugin(initialState.channelId, cmd.amount, "validator-1", "plugin error: invalid result (null)"))
    assert(validator2.calls.isEmpty)
    alice2bob.expectNoMessage(100 millis)
    assert(alice.stateName == NORMAL)
    assert(alice.stateData == initialState)
  }

  test("reject htlc without closing the channel when a plugin rejects without a reason") { f =>
    import f._
    import f.setup._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    validator1.validate = _ => Left(null)
    val sender = TestProbe()
    val cmd = makeCmd(50_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    alice ! cmd
    val failure = sender.expectMsgType[RES_ADD_FAILED[ChannelException]]
    assert(failure.t == OutgoingHtlcRejectedByPlugin(initialState.channelId, cmd.amount, "validator-1", "no reason provided"))
    alice2bob.expectNoMessage(100 millis)
    assert(alice.stateName == NORMAL)
    assert(alice.stateData == initialState)
  }

  test("reject htlc without closing the channel when a plugin throws an exception that cannot be printed") { f =>
    import f._
    import f.setup._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    class UnprintableException extends RuntimeException {
      override def getMessage: String = throw new IllegalStateException("cannot print")
      override def toString: String = throw new IllegalStateException("cannot print")
    }
    validator1.validate = _ => throw new UnprintableException()
    val sender = TestProbe()
    val cmd = makeCmd(50_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    alice ! cmd
    val failure = sender.expectMsgType[RES_ADD_FAILED[ChannelException]]
    assert(failure.t == OutgoingHtlcRejectedByPlugin(initialState.channelId, cmd.amount, "validator-1", s"plugin error: ${classOf[UnprintableException].getName}"))
    alice2bob.expectNoMessage(100 millis)
    assert(alice.stateName == NORMAL)
    assert(alice.stateData == initialState)
  }

  test("reject htlc without closing the channel when a plugin's name throws") { f =>
    import f._
    import f.setup._

    // We use a dedicated channel, because plugins are part of the node parameters.
    class BrokenNameValidator extends ValidateOutgoingHtlcPlugin {
      override def name: String = throw new NoSuchMethodError("name")
      override def validateOutgoingHtlc(htlc: OutgoingHtlcInfo): Either[String, Unit] = Left("rejected")
    }
    val aliceNodeParams = TestConstants.Alice.nodeParams
    val setup1 = init(nodeParamsA = aliceNodeParams.copy(pluginParams = aliceNodeParams.pluginParams :+ new BrokenNameValidator()))
    reachNormal(setup1)
    awaitCond(setup1.alice.stateName == NORMAL)
    val initialState = setup1.alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    val cmd = makeCmd(50_000_000 msat, sender.ref, localUpstream(), setup1.currentBlockHeight)
    setup1.alice ! cmd
    val failure = sender.expectMsgType[RES_ADD_FAILED[ChannelException]]
    assert(failure.t == OutgoingHtlcRejectedByPlugin(initialState.channelId, cmd.amount, classOf[BrokenNameValidator].getName, "rejected"))
    setup1.alice2bob.expectNoMessage(100 millis)
    assert(setup1.alice.stateName == NORMAL)
    assert(setup1.alice.stateData == initialState)
  }

  test("let plugins match their decision with the htlc that is eventually added or not") { f =>
    import f._
    import f.setup._

    // A plugin that accepts an HTLC doesn't know whether the next plugins also accept it: it must use the outgoing HTLC
    // events to find out whether the HTLC was actually added, which requires identifying the HTLC.
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[OutgoingHtlcAdded])
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[OutgoingHtlcNotAdded])
    val sender = TestProbe()

    validator2.validate = _ => Left("rejected by validator-2")
    val rejectedCmd = makeCmd(50_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    alice ! rejectedCmd
    sender.expectMsgType[RES_ADD_FAILED[ChannelException]]
    val accepted1 = validator1.calls.poll()
    assert(accepted1.paymentHash == rejectedCmd.paymentHash && accepted1.cltvExpiry == rejectedCmd.cltvExpiry)
    val notAdded = listener.expectMsgType[OutgoingHtlcNotAdded]
    assert((notAdded.channelId, notAdded.paymentHash, notAdded.amount, notAdded.expiry) == (accepted1.channelId, accepted1.paymentHash, accepted1.amount, accepted1.cltvExpiry))

    validator2.validate = _ => Right(())
    val acceptedCmd = makeCmd(40_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    alice ! acceptedCmd
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    val accepted2 = validator1.calls.poll()
    val added = listener.expectMsgType[OutgoingHtlcAdded]
    assert((added.add.channelId, added.add.paymentHash, added.add.amountMsat, added.add.cltvExpiry) == (accepted2.channelId, accepted2.paymentHash, accepted2.amount, accepted2.cltvExpiry))
  }

  test("provide the htlc origin to plugins") { f =>
    import f._
    import f.setup._

    validator1.validate = htlc => htlc.origin match {
      case _: OutgoingHtlcOrigin.Local => Right(())
      case _ => Left("relaying is disabled")
    }
    val sender = TestProbe()

    // Channel relay: rejected.
    val channelUpstream = relayedUpstream(50_000_000 msat, currentBlockHeight)
    alice ! makeCmd(50_000_000 msat, sender.ref, channelUpstream, currentBlockHeight)
    assert(sender.expectMsgType[RES_ADD_FAILED[ChannelException]].t.isInstanceOf[OutgoingHtlcRejectedByPlugin])
    assert(validator1.calls.poll().origin == OutgoingHtlcOrigin.ChannelRelay(channelUpstream.receivedFrom, channelUpstream.add.channelId, channelUpstream.amountIn))

    // Trampoline relay: rejected.
    val trampolineUpstream = Upstream.Hot.Trampoline(List(relayedUpstream(20_000_000 msat, currentBlockHeight), relayedUpstream(30_000_000 msat, currentBlockHeight)))
    alice ! makeCmd(45_000_000 msat, sender.ref, trampolineUpstream, currentBlockHeight)
    assert(sender.expectMsgType[RES_ADD_FAILED[ChannelException]].t.isInstanceOf[OutgoingHtlcRejectedByPlugin])
    assert(validator1.calls.poll().origin == OutgoingHtlcOrigin.TrampolineRelay(trampolineUpstream.received.map(_.receivedFrom).toSet, 50_020_000 msat))
    alice2bob.expectNoMessage(100 millis)

    // Local payment: accepted.
    val localPayment = Upstream.Local(UUID.randomUUID())
    alice ! makeCmd(50_000_000 msat, sender.ref, localPayment, currentBlockHeight)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    assert(validator1.calls.poll().origin == OutgoingHtlcOrigin.Local(localPayment.id))
  }

  test("don't call plugins for incoming htlcs") { f =>
    import f._
    import f.setup._

    addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    assert(validator1.calls.isEmpty)
    assert(validator2.calls.isEmpty)
  }

  test("don't call plugins for on-the-fly funded htlcs") { f =>
    import f._
    import f.setup._

    validator1.validate = _ => Left("rejected")
    // We have already added liquidity for this HTLC: our peer must use it to pay the funding fee.
    val sender = TestProbe()
    val fundingFee = LiquidityAds.FundingFee(5_000_000 msat, randomTxId())
    val cmd = makeCmd(50_000_000 msat, sender.ref, relayedUpstream(50_000_000 msat, currentBlockHeight), currentBlockHeight).copy(fundingFee_opt = Some(fundingFee))
    alice ! cmd
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    assert(alice2bob.expectMsgType[UpdateAddHtlc].fundingFee_opt.contains(fundingFee))
    assert(validator1.calls.isEmpty)
    assert(validator2.calls.isEmpty)
  }

  test("enforce a minimum channel balance") { f =>
    import f._
    import f.setup._

    val minBalance = 500_000_000.msat
    validator1.validate = htlc => if (htlc.commitments.forall(_.localBalanceAfter >= minBalance)) Right(()) else Left(s"balance would go below $minBalance")
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)

    // 800k sat -> 600k sat: accepted.
    addHtlc(200_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // 600k sat -> 450k sat: rejected, because the HTLC that is still pending counts.
    val sender = TestProbe()
    alice ! makeCmd(150_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    assert(sender.expectMsgType[RES_ADD_FAILED[ChannelException]].t.isInstanceOf[OutgoingHtlcRejectedByPlugin])
    alice2bob.expectNoMessage(100 millis)

    // 600k sat -> 500k sat: accepted, we're exactly at the minimum.
    alice ! makeCmd(100_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
  }

  test("provide the balance of every commitment while a splice is unconfirmed", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    import f.setup._

    alice2bob.ignoreMsg { case _: ChannelUpdate | _: ChannelReady => true }
    bob2alice.ignoreMsg { case _: ChannelUpdate | _: ChannelReady => true }
    val initialCommitment = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest
    assert(initialCommitment.capacity == 1_500_000.sat)
    assert(initialCommitment.localCommit.spec.toLocal == 800_000_000.msat)

    // Our peer adds funds to the channel: the splice transaction stays unconfirmed during this test.
    val spliceSender = TestProbe()
    bob ! CMD_SPLICE(spliceSender.ref, Some(SpliceIn(500_000 sat)), None, None, None)
    val steps: Seq[(TestProbe, ActorRef, Class[_])] = Seq(
      (bob2alice, alice, classOf[Stfu]), (alice2bob, bob, classOf[Stfu]),
      (bob2alice, alice, classOf[SpliceInit]), (alice2bob, bob, classOf[SpliceAck]),
      (bob2alice, alice, classOf[TxAddInput]), (alice2bob, bob, classOf[TxComplete]), // shared input
      (bob2alice, alice, classOf[TxAddInput]), (alice2bob, bob, classOf[TxComplete]), // bob's wallet input
      (bob2alice, alice, classOf[TxAddOutput]), (alice2bob, bob, classOf[TxComplete]), // bob's change output
      (bob2alice, alice, classOf[TxAddOutput]), (alice2bob, bob, classOf[TxComplete]), (bob2alice, alice, classOf[TxComplete]), // shared output
      (alice2bob, bob, classOf[CommitSig]), (bob2alice, alice, classOf[CommitSig]),
      (alice2bob, bob, classOf[TxSignatures]), (bob2alice, alice, classOf[TxSignatures]),
    )
    steps.foreach { case (probe, destination, messageType) =>
      probe.expectMsgClass(messageType)
      probe.forward(destination)
    }
    spliceSender.expectMsgType[RES_SPLICE]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 2)
    val spliceCommitment = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest
    assert(spliceCommitment.capacity == 2_000_000.sat)
    assert(spliceCommitment.localCommit.spec.toLocal == 800_000_000.msat)

    // Plugins receive each commitment's capacity with its own balance, the latest first.
    val sender = TestProbe()
    alice ! makeCmd(50_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    assert(validator1.calls.poll().commitments == Seq(
      CommitmentBalance(spliceCommitment.fundingTxId, 2_000_000 sat, 750_000_000 msat),
      CommitmentBalance(initialCommitment.fundingTxId, 1_500_000 sat, 750_000_000 msat),
    ))

    // A plugin that keeps 35% of the capacity in every commitment needs 700k sat once the splice confirms: 650k sat is
    // too low, even though it is enough for the initial commitment (525k sat), whose capacity is the smallest.
    validator1.validate = htlc => htlc.commitments.find(c => c.localBalanceAfter < c.capacity * 35 / 100) match {
      case Some(c) => Left(s"balance would go below 35% of ${c.capacity}")
      case None => Right(())
    }
    alice ! makeCmd(100_000_000 msat, sender.ref, localUpstream(), currentBlockHeight)
    assert(sender.expectMsgType[RES_ADD_FAILED[ChannelException]].t == OutgoingHtlcRejectedByPlugin(spliceCommitment.channelId, 100_000_000 msat, "validator-1", "balance would go below 35% of 2000000 sat"))
    alice2bob.expectNoMessage(100 millis)
  }

}
