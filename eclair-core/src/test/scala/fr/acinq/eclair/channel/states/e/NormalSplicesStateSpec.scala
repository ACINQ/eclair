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

package fr.acinq.eclair.channel.states.e

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.NumericSatoshi.abs
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong, Transaction, TxIn}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Closing.{LocalClose, RemoteClose, RevokedClose}
import fr.acinq.eclair.channel.LocalFundingStatus.DualFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.FullySignedSharedTransaction
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx, PublishTx, SetChannelId}
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.{FakeTxPublisherFactory, PimpTestFSM}
import fr.acinq.eclair.channel.states.ChannelStateTestsTags.{AnchorOutputsZeroFeeHtlcTxs, NoMaxHtlcValueInFlight, ZeroConf}
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.testutils.PimpTestProbe.convert
import fr.acinq.eclair.transactions.DirectedHtlc.{incoming, outgoing}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.ClaimLocalAnchorOutputTx
import fr.acinq.eclair.wire.protocol._
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import org.scalatest.{Outcome, Tag}
import scodec.bits.HexStringSyntax

/**
 * Created by PM on 23/12/2022.
 */

class NormalSplicesStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  override def withFixture(test: OneArgTest): Outcome = {
    val tags = test.tags + ChannelStateTestsTags.DualFunding + ChannelStateTestsTags.Splicing
    val setup = init(tags = tags)
    import setup._
    reachNormal(setup, tags)
    alice2bob.ignoreMsg { case _: ChannelUpdate => true }
    bob2alice.ignoreMsg { case _: ChannelUpdate => true }
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    withFixture(test.toNoArgTest(setup))
  }

  private val defaultSpliceOutScriptPubKey = hex"0020aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

  private def useQuiescence(f: FixtureParam): Boolean = f.alice.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.params.useQuiescence

  private def initiateSpliceWithoutSigs(f: FixtureParam, spliceIn_opt: Option[SpliceIn] = None, spliceOut_opt: Option[SpliceOut] = None): TestProbe = {
    import f._

    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt, spliceOut_opt)
    alice ! cmd
    if (useQuiescence(f)) {
      exchangeStfu(f)
    }
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)

    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    if (spliceIn_opt.isDefined) {
      alice2bob.expectMsgType[TxAddInput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxComplete]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxAddOutput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxComplete]
      bob2alice.forward(alice)
    }
    if (spliceOut_opt.isDefined) {
      alice2bob.expectMsgType[TxAddOutput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxComplete]
      bob2alice.forward(alice)
    }
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    sender
  }

  private def exchangeSpliceSigs(f: FixtureParam, sender: TestProbe): Transaction = {
    import f._

    val commitSigBob = bob2alice.fishForMessage() {
      case _: CommitSig => true
      case _: ChannelReady => false
    }
    bob2alice.forward(alice, commitSigBob)
    val commitSigAlice = alice2bob.fishForMessage() {
      case _: CommitSig => true
      case _: ChannelReady => false
    }
    alice2bob.forward(bob, commitSigAlice)

    val txSigsBob = bob2alice.fishForMessage() {
      case _: TxSignatures => true
      case _: ChannelUpdate => false
    }
    bob2alice.forward(alice, txSigsBob)
    val txSigsAlice = alice2bob.fishForMessage() {
      case _: TxSignatures => true
      case _: ChannelUpdate => false
    }
    alice2bob.forward(bob, txSigsAlice)

    sender.expectMsgType[RES_SPLICE]

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.isInstanceOf[FullySignedSharedTransaction])
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.isInstanceOf[FullySignedSharedTransaction])
    alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
  }

  private def initiateSplice(f: FixtureParam, spliceIn_opt: Option[SpliceIn] = None, spliceOut_opt: Option[SpliceOut] = None): Transaction = {
    val sender = initiateSpliceWithoutSigs(f, spliceIn_opt, spliceOut_opt)
    exchangeSpliceSigs(f, sender)
  }

  private def exchangeStfu(f: FixtureParam): Unit = {
    import f._
    alice2bob.expectMsgType[Stfu]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Stfu]
    bob2alice.forward(alice)
  }

  case class TestHtlcs(aliceToBob: Seq[(ByteVector32, UpdateAddHtlc)], bobToAlice: Seq[(ByteVector32, UpdateAddHtlc)])

  private def setupHtlcs(f: FixtureParam): TestHtlcs = {
    import f._

    if (useQuiescence(f)) {
      // add htlcs in both directions
      val htlcsAliceToBob = Seq(
        addHtlc(15_000_000 msat, alice, bob, alice2bob, bob2alice),
        addHtlc(15_000_000 msat, alice, bob, alice2bob, bob2alice)
      )
      crossSign(alice, bob, alice2bob, bob2alice)
      val htlcsBobToAlice = Seq(
        addHtlc(20_000_000 msat, bob, alice, bob2alice, alice2bob),
        addHtlc(15_000_000 msat, bob, alice, bob2alice, alice2bob)
      )
      crossSign(bob, alice, bob2alice, alice2bob)

      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      assert(initialState.commitments.latest.capacity == 1_500_000.sat)
      assert(initialState.commitments.latest.localCommit.spec.toLocal == 770_000_000.msat)
      assert(initialState.commitments.latest.localCommit.spec.toRemote == 665_000_000.msat)

      TestHtlcs(htlcsAliceToBob, htlcsBobToAlice)
    } else {
      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      assert(initialState.commitments.latest.capacity == 1_500_000.sat)
      assert(initialState.commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
      assert(initialState.commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)
      TestHtlcs(Seq.empty, Seq.empty)
    }
  }

  def spliceOutFee(f: FixtureParam, capacity: Satoshi): Satoshi = {
    import f._

    // When we only splice-out, the fees are paid by deducing them from the next funding amount.
    val fundingTx = alice.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.latest.localFundingStatus.signedTx_opt.get
    val feerate = alice.nodeParams.onChainFeeConf.getFundingFeerate(alice.nodeParams.currentFeerates)
    val expectedMiningFee = Transactions.weight2fee(feerate, fundingTx.weight())
    val actualMiningFee = capacity - alice.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.latest.capacity
    // Fee computation is approximate (signature size isn't constant).
    assert(actualMiningFee >= 0.sat && abs(actualMiningFee - expectedMiningFee) < 100.sat)
    actualMiningFee
  }

  def checkPostSpliceState(f: FixtureParam, spliceOutFee: Satoshi): Unit = {
    import f._

    // if the swap includes a splice-in, swap-out fees will be paid from bitcoind so final capacity is predictable
    val (outgoingHtlcs, incomingHtlcs) = if (useQuiescence(f)) (30_000_000.msat, 35_000_000.msat) else (0.msat, 0.msat)
    val postSpliceState = alice.stateData.asInstanceOf[ChannelDataWithCommitments]
    assert(postSpliceState.commitments.latest.capacity == 1_900_000.sat - spliceOutFee)
    assert(postSpliceState.commitments.latest.localCommit.spec.toLocal == 1_200_000_000.msat - spliceOutFee - outgoingHtlcs)
    assert(postSpliceState.commitments.latest.localCommit.spec.toRemote == 700_000_000.msat - incomingHtlcs)
    assert(postSpliceState.commitments.latest.localCommit.spec.htlcs.collect(incoming).toSeq.map(_.amountMsat).sum == incomingHtlcs)
    assert(postSpliceState.commitments.latest.localCommit.spec.htlcs.collect(outgoing).toSeq.map(_.amountMsat).sum == outgoingHtlcs)
  }

  def resolveHtlcs(f: FixtureParam, htlcs: TestHtlcs, spliceOutFee: Satoshi): Unit = {
    import f._

    checkPostSpliceState(f, spliceOutFee)

    if (useQuiescence(f)) {
      // resolve pre-splice HTLCs after splice
      val Seq((preimage1a, htlc1a), (preimage2a, htlc2a)) = htlcs.aliceToBob
      val Seq((preimage1b, htlc1b), (preimage2b, htlc2b)) = htlcs.bobToAlice
      fulfillHtlc(htlc1a.id, preimage1a, bob, alice, bob2alice, alice2bob)
      fulfillHtlc(htlc2a.id, preimage2a, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      fulfillHtlc(htlc1b.id, preimage1b, alice, bob, alice2bob, bob2alice)
      fulfillHtlc(htlc2b.id, preimage2b, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.localCommit.spec.htlcs.collect(outgoing).isEmpty)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.remoteCommit.spec.htlcs.collect(outgoing).isEmpty)
      assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.localCommit.spec.htlcs.collect(outgoing).isEmpty)
      assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.remoteCommit.spec.htlcs.collect(outgoing).isEmpty)
    }

    val settledHtlcs = if (useQuiescence(f)) 5_000_000.msat else 0.msat
    val finalState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(finalState.commitments.latest.capacity == 1_900_000.sat - spliceOutFee)
    assert(finalState.commitments.latest.localCommit.spec.toLocal == 1_200_000_000.msat - spliceOutFee + settledHtlcs)
    assert(finalState.commitments.latest.localCommit.spec.toRemote == 700_000_000.msat - settledHtlcs)
  }

  test("recv CMD_SPLICE (splice-in)") { f =>
    import f._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.capacity == 1_500_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
    assert(initialState.commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.capacity == 2_000_000.sat)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == 1_300_000_000.msat)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)
  }

  test("recv CMD_SPLICE (splice-in, non dual-funded channel)") { () =>
    val f = init(tags = Set(ChannelStateTestsTags.DualFunding, ChannelStateTestsTags.Splicing))
    import f._

    reachNormal(f, tags = Set(ChannelStateTestsTags.Splicing)) // we open a non dual-funded channel
    alice2bob.ignoreMsg { case _: ChannelUpdate => true }
    bob2alice.ignoreMsg { case _: ChannelUpdate => true }
    awaitCond(alice.stateName == NORMAL && bob.stateName == NORMAL)
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(!initialState.commitments.params.channelFeatures.hasFeature(Features.DualFunding))
    assert(initialState.commitments.latest.capacity == 1_000_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
    assert(initialState.commitments.latest.localCommit.spec.toRemote == 200_000_000.msat)
    // The channel reserve is set by each participant when not using dual-funding.
    assert(initialState.commitments.latest.localChannelReserve == 20_000.sat)
    assert(initialState.commitments.latest.remoteChannelReserve == 10_000.sat)

    // We can splice on top of a non dual-funded channel.
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    val postSpliceState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(!postSpliceState.commitments.params.channelFeatures.hasFeature(Features.DualFunding))
    assert(postSpliceState.commitments.latest.capacity == 1_500_000.sat)
    assert(postSpliceState.commitments.latest.localCommit.spec.toLocal == 1_300_000_000.msat)
    assert(postSpliceState.commitments.latest.localCommit.spec.toRemote == 200_000_000.msat)
    // The channel reserve is now implicitly set to 1% of the channel capacity on both sides.
    assert(postSpliceState.commitments.latest.localChannelReserve == 15_000.sat)
    assert(postSpliceState.commitments.latest.remoteChannelReserve == 15_000.sat)
  }

  test("recv CMD_SPLICE (splice-in, local and remote commit index mismatch)", Tag(ChannelStateTestsTags.Quiescence)) { f =>
    import f._

    // Alice and Bob asynchronously exchange HTLCs, which makes their commit indices diverge.
    val (r1, add1) = addHtlc(15_000_000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    val (r2, add2) = addHtlc(10_000_000 msat, bob, alice, bob2alice, alice2bob)
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectNoMessage(100 millis)

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.capacity == 1_500_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 785_000_000.msat)
    assert(initialState.commitments.latest.localCommit.spec.toRemote == 690_000_000.msat)
    assert(initialState.commitments.latest.localCommit.index == 1)
    assert(initialState.commitments.latest.remoteCommit.index == 2)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.capacity == 2_000_000.sat)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == 1_285_000_000.msat)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toRemote == 690_000_000.msat)

    // Resolve pending HTLCs (we have two active commitments).
    fulfillHtlc(add1.id, r1, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(add2.id, r2, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    (1 to 2).foreach { _ =>
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
    }
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    (1 to 2).foreach { _ =>
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)
    }
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    (1 to 2).foreach { _ =>
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
    }
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectNoMessage(100 millis)

    val finalState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(finalState.commitments.latest.localCommit.spec.toLocal == 1_295_000_000.msat)
    assert(finalState.commitments.latest.localCommit.spec.toRemote == 705_000_000.msat)
    assert(finalState.commitments.latest.localCommit.index == 2)
    assert(finalState.commitments.latest.remoteCommit.index == 4)
  }

  test("recv CMD_SPLICE (splice-out)") { f =>
    import f._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.capacity == 1_500_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
    assert(initialState.commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)

    initiateSplice(f, spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))

    // initiator pays the fee
    val fee = spliceOutFee(f, capacity = 1_400_000.sat)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == 700_000_000.msat - fee)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)
  }

  test("recv CMD_SPLICE (splice-out, would go below reserve)") { f =>
    import f._

    setupHtlcs(f)

    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = None, Some(SpliceOut(780_000.sat, defaultSpliceOutScriptPubKey)))
    alice ! cmd
    sender.expectMsgType[RES_FAILURE[_, _]]
  }

  test("recv CMD_SPLICE (splice-out, would go below reserve, quiescent)", Tag(ChannelStateTestsTags.Quiescence), Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    setupHtlcs(f)

    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = None, Some(SpliceOut(760_000 sat, defaultSpliceOutScriptPubKey)))
    alice ! cmd
    exchangeStfu(f)
    sender.expectMsgType[RES_FAILURE[_, _]]
  }

  test("recv CMD_SPLICE (splice-in, feerate too low)") { f =>
    import f._

    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = None)
    alice ! cmd
    // we tweak the feerate
    val spliceInit = alice2bob.expectMsgType[SpliceInit].copy(feerate = FeeratePerKw(100.sat))
    bob.setFeerates(alice.nodeParams.currentFeerates.copy(minimum = FeeratePerKw(101.sat)))
    alice2bob.forward(bob, spliceInit)
    val txAbortBob = bob2alice.expectMsgType[TxAbort]
    bob2alice.forward(alice, txAbortBob)
    val txAbortAlice = alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob, txAbortAlice)
    sender.expectMsgType[RES_FAILURE[_, _]]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
  }

  test("recv CMD_SPLICE (remote splices out below its reserve)") { f =>
    import f._

    val sender = TestProbe()
    val bobBalance = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal
    alice ! CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(100_000 sat)), spliceOut_opt = None)
    val spliceInit = alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob, spliceInit)
    val spliceAck = bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice, spliceAck.copy(fundingContribution = -(bobBalance.truncateToSatoshi + 1.sat)))
    val txAbortAlice = alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob, txAbortAlice)
    val txAbortBob = bob2alice.expectMsgType[TxAbort]
    bob2alice.forward(alice, txAbortBob)
    sender.expectMsgType[RES_FAILURE[_, _]]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
  }

  def testSpliceInAndOutCmd(f: FixtureParam): Unit = {
    val htlcs = setupHtlcs(f)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))

    resolveHtlcs(f, htlcs, spliceOutFee = 0.sat)
  }

  test("recv CMD_SPLICE (splice-in + splice-out)") { f =>
    testSpliceInAndOutCmd(f)
  }

  test("recv CMD_SPLICE (splice-in + splice-out, quiescence)", Tag(ChannelStateTestsTags.Quiescence)) { f =>
    testSpliceInAndOutCmd(f)
  }

  test("recv TxAbort (before TxComplete)") { f =>
    import f._

    val sender = TestProbe()
    alice ! CMD_SPLICE(sender.ref, spliceIn_opt = None, spliceOut_opt = Some(SpliceOut(50_000 sat, defaultSpliceOutScriptPubKey)))
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    // Bob decides to abort the splice attempt.
    bob2alice.forward(alice, TxAbort(channelId(alice), "changed my mind!"))
    alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob, TxAbort(channelId(bob), "changed my mind!"))
    bob2alice.expectMsgType[TxAbort]
    sender.expectMsgType[RES_FAILURE[_, _]]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
  }

  test("recv TxAbort (after TxComplete)") { f =>
    import f._

    val sender = TestProbe()
    alice ! CMD_SPLICE(sender.ref, spliceIn_opt = None, spliceOut_opt = Some(SpliceOut(50_000 sat, defaultSpliceOutScriptPubKey)))
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    bob2alice.expectMsgType[CommitSig]
    sender.expectMsgType[RES_SPLICE]
    // Alice decides to abort the splice attempt.
    alice2bob.forward(bob, TxAbort(channelId(alice), "internal error"))
    bob2alice.expectMsgType[TxAbort]
    bob2alice.forward(alice, TxAbort(channelId(bob), "internal error"))
    alice2bob.expectMsgType[TxAbort]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
  }

  test("recv TxAbort (after CommitSig)") { f =>
    import f._

    val sender = TestProbe()
    alice ! CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(50_000 sat)), spliceOut_opt = None)
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    val output1 = alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    // We forward a duplicate of the first output, which will make bob abort after receiving tx_complete.
    alice2bob.forward(bob, output1.copy(serialId = UInt64(100)))
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    val commitSigAlice = alice2bob.expectMsgType[CommitSig]
    val txAbortBob = bob2alice.expectMsgType[TxAbort]
    sender.expectMsgType[RES_SPLICE]

    // Bob ignores Alice's commit_sig.
    alice2bob.forward(bob, commitSigAlice)
    bob2alice.expectNoMessage(100 millis)
    bob2blockchain.expectNoMessage(100 millis)

    // Alice acks Bob's tx_abort.
    bob2alice.forward(alice, txAbortBob)
    alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
  }

  test("recv WatchFundingConfirmedTriggered on splice tx", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val sender = TestProbe()
    // command for a large payment (larger than local balance pre-slice)
    val cmd = CMD_ADD_HTLC(sender.ref, 1_000_000_000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    // first attempt at payment fails (not enough balance)
    alice ! cmd
    sender.expectMsgType[RES_ADD_FAILED[_]]
    alice2bob.expectNoMessage(100 millis)

    val fundingTx = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2blockchain.expectNoMessage(100 millis)

    // the splice tx isn't yet confirmed, payment still fails
    alice ! cmd
    sender.expectMsgType[RES_ADD_FAILED[_]]

    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)

    // the splice tx is only considered locked by alice, payment still fails
    alice ! cmd
    sender.expectMsgType[RES_ADD_FAILED[_]]

    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)

    // now the payment works!
    alice ! cmd
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
  }

  private def setup2Splices(f: FixtureParam): (Transaction, Transaction) = {
    import f._

    val fundingTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    alice2blockchain.expectNoMessage(100 millis)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    bob2blockchain.expectNoMessage(100 millis)

    val fundingTx2 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    alice2blockchain.expectWatchFundingConfirmed(fundingTx2.txid)
    alice2blockchain.expectNoMessage(100 millis)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx2.txid)
    bob2blockchain.expectNoMessage(100 millis)

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2, 1, 0))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2, 1, 0))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)

    (fundingTx1, fundingTx2)
  }

  test("splice local/remote locking", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val (fundingTx1, fundingTx2) = setup2Splices(f)
    val commitAlice1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active(1).localCommit.commitTxAndRemoteSig.commitTx.tx
    val commitAlice2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active(0).localCommit.commitTxAndRemoteSig.commitTx.tx
    val commitBob1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active(1).localCommit.commitTxAndRemoteSig.commitTx.tx
    val commitBob2 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active(0).localCommit.commitTxAndRemoteSig.commitTx.tx

    // splice 1 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx1.txid)
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx1.txid)
    bob2alice.forward(alice)
    alice2blockchain.expectWatchFundingSpent(fundingTx1.txid, Some(Set(fundingTx2.txid, commitAlice1.txid, commitBob1.txid)))
    bob2blockchain.expectWatchFundingSpent(fundingTx1.txid, Some(Set(fundingTx2.txid, commitAlice1.txid, commitBob1.txid)))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2, 1))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)

    // splice 2 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx2.txid)
    bob2alice.forward(alice)
    alice2blockchain.expectWatchFundingSpent(fundingTx2.txid, Some(Set(commitAlice2.txid, commitBob2.txid)))
    bob2blockchain.expectWatchFundingSpent(fundingTx2.txid, Some(Set(commitAlice2.txid, commitBob2.txid)))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
  }

  test("splice local/remote locking (zero-conf)", Tag(NoMaxHtlcValueInFlight), Tag(ZeroConf), Tag(AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val fundingTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(250_000 sat)))
    alice2blockchain.expectWatchPublished(fundingTx1.txid)
    alice ! WatchPublishedTriggered(fundingTx1)
    alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    alice2blockchain.expectNoMessage(100 millis)
    bob2blockchain.expectWatchPublished(fundingTx1.txid)
    bob ! WatchPublishedTriggered(fundingTx1)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    bob2blockchain.expectNoMessage(100 millis)

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    bob2alice.forward(alice)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.fundingTxId == fundingTx1.txid)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.fundingTxId == fundingTx1.txid)
  }

  test("splice local/remote locking (reverse order)", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val (fundingTx1, fundingTx2) = setup2Splices(f)
    val commitAlice2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.localCommit.commitTxAndRemoteSig.commitTx.tx
    val commitBob2 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.localCommit.commitTxAndRemoteSig.commitTx.tx

    // splice 2 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx2.txid)
    bob2alice.forward(alice)
    alice2blockchain.expectWatchFundingSpent(fundingTx2.txid, Some(Set(commitAlice2.txid, commitBob2.txid)))
    bob2blockchain.expectWatchFundingSpent(fundingTx2.txid, Some(Set(commitAlice2.txid, commitBob2.txid)))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)

    // splice 1 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    // we don't send a splice_locked for the older tx
    alice2bob.expectNoMessage(100 millis)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    // we don't send a splice_locked for the older tx
    bob2alice.expectNoMessage(100 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
  }

  test("splice local/remote locking (intermingled)", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val (fundingTx1, fundingTx2) = setup2Splices(f)

    // splice 1 confirms on alice, splice 2 confirms on bob
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx1.txid)
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx2.txid)
    bob2alice.forward(alice)
    alice2blockchain.expectWatchFundingSpent(fundingTx1.txid)
    bob2blockchain.expectWatchFundingSpent(fundingTx2.txid)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2, 1))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2, 1))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)

    // splice 2 confirms on bob, splice 1 confirms on alice
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2alice.expectNoMessage(100 millis)
    bob2alice.forward(alice)
    alice2blockchain.expectWatchFundingSpent(fundingTx2.txid)
    bob2blockchain.expectNoMessage(100 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
  }

  test("emit post-splice events", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.capacity == 1_500_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
    assert(initialState.commitments.latest.localCommit.spec.toRemote == 700_000_000.msat)

    val aliceEvents = TestProbe()
    val bobEvents = TestProbe()
    systemA.eventStream.subscribe(aliceEvents.ref, classOf[AvailableBalanceChanged])
    systemA.eventStream.subscribe(aliceEvents.ref, classOf[LocalChannelUpdate])
    systemA.eventStream.subscribe(aliceEvents.ref, classOf[LocalChannelDown])
    systemB.eventStream.subscribe(bobEvents.ref, classOf[AvailableBalanceChanged])
    systemB.eventStream.subscribe(bobEvents.ref, classOf[LocalChannelUpdate])
    systemB.eventStream.subscribe(bobEvents.ref, classOf[LocalChannelDown])

    val (fundingTx1, fundingTx2) = setup2Splices(f)

    // splices haven't been locked, so no event is emitted
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectNoMessage(100 millis)

    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectNoMessage(100 millis)

    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    aliceEvents.expectAvailableBalanceChanged(balance = 1_300_000_000.msat, capacity = 2_000_000.sat)
    bobEvents.expectAvailableBalanceChanged(balance = 700_000_000.msat, capacity = 2_000_000.sat)
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectNoMessage(100 millis)

    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectNoMessage(100 millis)

    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    aliceEvents.expectAvailableBalanceChanged(balance = 1_800_000_000.msat, capacity = 2_500_000.sat)
    bobEvents.expectAvailableBalanceChanged(balance = 700_000_000.msat, capacity = 2_500_000.sat)
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectNoMessage(100 millis)
  }

  test("recv CMD_ADD_HTLC with multiple commitments") { f =>
    import f._
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    val sender = TestProbe()
    alice ! CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice ! CMD_SIGN()
    val sig1 = alice2bob.expectMsgType[CommitSig]
    assert(sig1.batchSize == 2)
    alice2bob.forward(bob)
    val sig2 = alice2bob.expectMsgType[CommitSig]
    assert(sig2.batchSize == 2)
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.forall(_.localCommit.spec.htlcs.size == 1))
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.forall(_.localCommit.spec.htlcs.size == 1))
  }

  test("recv CMD_ADD_HTLC while a splice is requested") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice ! CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_ADD_FAILED[_]]
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv CMD_ADD_HTLC while a splice is in progress") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]
    alice ! CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_ADD_FAILED[_]]
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv UpdateAddHtlc while a splice is requested") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    // we're holding the splice_init to create a race

    val (_, cmdAdd: CMD_ADD_HTLC) = makeCmdAdd(5_000_000 msat, bob.underlyingActor.remoteNodeId, bob.underlyingActor.nodeParams.currentBlockHeight)
    bob ! cmdAdd
    bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)
    // now we forward the splice_init
    alice2bob.forward(bob)
    // bob rejects the SpliceInit because they have a pending htlc
    bob2alice.expectMsgType[TxAbort]
    bob2alice.forward(alice)
    // alice returns a warning and schedules a disconnect after receiving UpdateAddHtlc
    alice2bob.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateAddHtlc").getMessage))
    // alice confirms the splice abort
    alice2bob.expectMsgType[TxAbort]
    // the htlc is not added
    assert(!alice.stateData.asInstanceOf[DATA_NORMAL].commitments.hasPendingOrProposedHtlcs)
  }

  test("recv UpdateAddHtlc while a splice is in progress") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]

    // have to build a htlc manually because eclair would refuse to accept this command as it's forbidden
    val fakeHtlc = UpdateAddHtlc(channelId = randomBytes32(), id = 5656, amountMsat = 50000000 msat, cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), paymentHash = randomBytes32(), onionRoutingPacket = TestConstants.emptyOnionPacket, blinding_opt = None)
    bob2alice.forward(alice, fakeHtlc)
    // alice returns a warning and schedules a disconnect after receiving UpdateAddHtlc
    alice2bob.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateAddHtlc").getMessage))
    // the htlc is not added
    assert(!alice.stateData.asInstanceOf[DATA_NORMAL].commitments.hasPendingOrProposedHtlcs)
  }

  test("recv UpdateAddHtlc before splice confirms (zero-conf)", Tag(ZeroConf), Tag(AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val spliceTx = initiateSplice(f, spliceOut_opt = Some(SpliceOut(50_000 sat, defaultSpliceOutScriptPubKey)))
    alice ! WatchPublishedTriggered(spliceTx)
    val spliceLockedAlice = alice2bob.expectMsgType[SpliceLocked]
    bob ! WatchPublishedTriggered(spliceTx)
    val spliceLockedBob = bob2alice.expectMsgType[SpliceLocked]
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 2)
    val (preimage, htlc) = addHtlc(25_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    alice2bob.forward(bob, spliceLockedAlice)
    bob2alice.forward(alice, spliceLockedBob)

    fulfillHtlc(htlc.id, preimage, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.localCommit.spec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.head.localCommit.spec.htlcs.size == 1)
  }

  test("recv UpdateAddHtlc while splice is being locked", Tag(ZeroConf), Tag(AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    initiateSplice(f, spliceOut_opt = Some(SpliceOut(50_000 sat, defaultSpliceOutScriptPubKey)))
    val spliceTx = initiateSplice(f, spliceOut_opt = Some(SpliceOut(50_000 sat, defaultSpliceOutScriptPubKey)))
    alice ! WatchPublishedTriggered(spliceTx)
    val spliceLockedAlice = alice2bob.expectMsgType[SpliceLocked]
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 3)

    // Alice adds a new HTLC, and sends commit_sigs before receiving Bob's splice_locked.
    //
    //   Alice                           Bob
    //     |        splice_locked         |
    //     |----------------------------->|
    //     |       update_add_htlc        |
    //     |----------------------------->|
    //     |         commit_sig           | batch_size = 3
    //     |----------------------------->|
    //     |        splice_locked         |
    //     |<-----------------------------|
    //     |         commit_sig           | batch_size = 3
    //     |----------------------------->|
    //     |         commit_sig           | batch_size = 3
    //     |----------------------------->|
    //     |       revoke_and_ack         |
    //     |<-----------------------------|
    //     |         commit_sig           | batch_size = 1
    //     |<-----------------------------|
    //     |       revoke_and_ack         |
    //     |----------------------------->|

    alice2bob.forward(bob, spliceLockedAlice)
    val (preimage, htlc) = addHtlc(20_000_000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    val commitSigsAlice = (1 to 3).map(_ => alice2bob.expectMsgType[CommitSig])
    alice2bob.forward(bob, commitSigsAlice(0))
    bob ! WatchPublishedTriggered(spliceTx)
    val spliceLockedBob = bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice, spliceLockedBob)
    alice2bob.forward(bob, commitSigsAlice(1))
    alice2bob.forward(bob, commitSigsAlice(2))
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    assert(bob2alice.expectMsgType[CommitSig].batchSize == 1)
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 2)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 2)

    // Bob fulfills the HTLC.
    fulfillHtlc(htlc.id, preimage, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    val aliceCommitments = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(aliceCommitments.active.head.localCommit.spec.htlcs.isEmpty)
    aliceCommitments.inactive.foreach(c => assert(c.localCommit.index < aliceCommitments.localCommitIndex))
    val bobCommitments = bob.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(bobCommitments.active.head.localCommit.spec.htlcs.isEmpty)
    bobCommitments.inactive.foreach(c => assert(c.localCommit.index < bobCommitments.localCommitIndex))
  }

  private def disconnect(f: FixtureParam): Unit = {
    import f._

    alice ! INPUT_DISCONNECTED
    bob ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
  }

  private def reconnect(f: FixtureParam, interceptFundingDeeplyBuried: Boolean = true): (ChannelReestablish, ChannelReestablish) = {
    import f._

    val aliceInit = Init(alice.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.params.localParams.initFeatures)
    val bobInit = Init(bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.params.localParams.initFeatures)
    alice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
    val channelReestablishAlice = alice2bob.expectMsgType[ChannelReestablish]
    alice2bob.forward(bob)
    val channelReestablishBob = bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)

    if (interceptFundingDeeplyBuried) {
      alice2blockchain.expectMsgType[WatchFundingDeeplyBuried]
      bob2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    }

    (channelReestablishAlice, channelReestablishBob)
  }

  test("disconnect (commit_sig not sent)") { f =>
    import f._

    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)

    alice ! INPUT_DISCONNECTED
    sender.expectMsgType[RES_FAILURE[_, _]]
    awaitCond(alice.stateName == OFFLINE)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
  }

  def testDisconnectCommitSigNotReceived(f: FixtureParam): Unit = {
    import f._

    val htlcs = setupHtlcs(f)

    val sender = initiateSpliceWithoutSigs(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    alice2bob.expectMsgType[CommitSig] // Bob doesn't receive Alice's commit_sig
    bob2alice.expectMsgType[CommitSig] // Alice doesn't receive Bob's commit_sig
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus.isInstanceOf[SpliceStatus.SpliceWaitingForSigs])
    val spliceStatus = alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus.asInstanceOf[SpliceStatus.SpliceWaitingForSigs]

    disconnect(f)
    val (channelReestablishAlice, channelReestablishBob) = reconnect(f)
    assert(channelReestablishAlice.nextFundingTxId_opt.contains(spliceStatus.signingSession.fundingTx.txId))
    assert(channelReestablishBob.nextFundingTxId_opt.contains(spliceStatus.signingSession.fundingTx.txId))

    val spliceTx = exchangeSpliceSigs(f, sender)
    alice2blockchain.expectWatchFundingConfirmed(spliceTx.txid)
    bob2blockchain.expectWatchFundingConfirmed(spliceTx.txid)
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)

    resolveHtlcs(f, htlcs, 0.sat)
  }

  test("disconnect (commit_sig not received)") { f =>
    testDisconnectCommitSigNotReceived(f)
  }

  test("disconnect (commit_sig not received, quiescence)", Tag(ChannelStateTestsTags.Quiescence)) { f =>
    testDisconnectCommitSigNotReceived(f)
  }

  def testDisconnectCommitSigReceivedByAlice(f: FixtureParam): Unit = {
    import f._

    val htlcs = setupHtlcs(f)

    val sender = initiateSpliceWithoutSigs(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    alice2bob.expectMsgType[CommitSig] // Bob doesn't receive Alice's commit_sig
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus.isInstanceOf[SpliceStatus.SpliceWaitingForSigs])
    val spliceStatus = alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus.asInstanceOf[SpliceStatus.SpliceWaitingForSigs]

    disconnect(f)
    val (channelReestablishAlice, channelReestablishBob) = reconnect(f)
    assert(channelReestablishAlice.nextFundingTxId_opt.contains(spliceStatus.signingSession.fundingTx.txId))
    assert(channelReestablishBob.nextFundingTxId_opt.contains(spliceStatus.signingSession.fundingTx.txId))

    val spliceTx = exchangeSpliceSigs(f, sender)
    alice2blockchain.expectWatchFundingConfirmed(spliceTx.txid)
    bob2blockchain.expectWatchFundingConfirmed(spliceTx.txid)
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)

    resolveHtlcs(f, htlcs, spliceOutFee = 0.sat)
  }

  test("disconnect (commit_sig received by alice)") { f =>
    testDisconnectCommitSigReceivedByAlice(f)
  }

  test("disconnect (commit_sig received by alice, quiescence)", Tag(ChannelStateTestsTags.Quiescence)) { f =>
    testDisconnectCommitSigReceivedByAlice(f)
  }

  def testDisconnectTxSignaturesSentByBob(f: FixtureParam): Unit = {
    import f._

    val htlcs = setupHtlcs(f)

    val sender = initiateSpliceWithoutSigs(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    val spliceTxId = bob2alice.expectMsgType[TxSignatures].txId // Alice doesn't receive Bob's tx_signatures
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)

    disconnect(f)
    val (channelReestablishAlice, channelReestablishBob) = reconnect(f, interceptFundingDeeplyBuried = false)
    assert(channelReestablishAlice.nextFundingTxId_opt.contains(spliceTxId))
    assert(channelReestablishBob.nextFundingTxId_opt.contains(spliceTxId))
    alice2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    bob2blockchain.expectWatchFundingConfirmed(spliceTxId)

    val spliceTx = exchangeSpliceSigs(f, sender)
    alice2blockchain.expectWatchFundingConfirmed(spliceTx.txid)
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)

    resolveHtlcs(f, htlcs, spliceOutFee = 0.sat)
  }

  test("disconnect (tx_signatures sent by bob)") { f =>
    testDisconnectTxSignaturesSentByBob(f)
  }

  test("disconnect (tx_signatures sent by bob, quiescence)", Tag(ChannelStateTestsTags.Quiescence)) { f =>
    testDisconnectTxSignaturesSentByBob(f)
  }

  def testDisconnectTxSignaturesReceivedByAlice(f: FixtureParam): Unit = {
    import f._

    val htlcs = setupHtlcs(f)

    initiateSpliceWithoutSigs(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    val spliceTxId = alice2bob.expectMsgType[TxSignatures].txId // Bob doesn't receive Alice's tx_signatures
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)

    disconnect(f)
    val (channelReestablishAlice, channelReestablishBob) = reconnect(f, interceptFundingDeeplyBuried = false)
    assert(channelReestablishAlice.nextFundingTxId_opt.isEmpty)
    assert(channelReestablishBob.nextFundingTxId_opt.contains(spliceTxId))
    alice2blockchain.expectWatchFundingConfirmed(spliceTxId)
    bob2blockchain.expectWatchFundingConfirmed(spliceTxId)

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 2)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 2)
    val spliceTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get

    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)

    resolveHtlcs(f, htlcs, spliceOutFee = 0.sat)
  }

  test("disconnect (tx_signatures received by alice)") { f =>
    testDisconnectTxSignaturesReceivedByAlice(f)
  }

  test("disconnect (tx_signatures received by alice, quiescence)", Tag(ChannelStateTestsTags.Quiescence)) { f =>
    testDisconnectTxSignaturesReceivedByAlice(f)
  }

  def testDisconnectTxSignaturesReceivedByAliceZeroConf(f: FixtureParam): Unit = {
    import f._

    val htlcs = setupHtlcs(f)

    initiateSpliceWithoutSigs(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    val spliceTxId = alice2bob.expectMsgType[TxSignatures].txId // Bob doesn't receive Alice's tx_signatures
    alice2blockchain.expectMsgType[WatchPublished]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    val spliceTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice ! WatchPublishedTriggered(spliceTx)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == spliceTxId) // Bob doesn't receive Alice's splice_locked

    disconnect(f)
    val (channelReestablishAlice, channelReestablishBob) = reconnect(f, interceptFundingDeeplyBuried = false)
    assert(channelReestablishAlice.nextFundingTxId_opt.isEmpty)
    assert(channelReestablishBob.nextFundingTxId_opt.contains(spliceTxId))
    alice2blockchain.expectWatchFundingConfirmed(spliceTxId)
    bob2blockchain.expectWatchPublished(spliceTxId)
    bob2blockchain.expectMsgType[WatchFundingDeeplyBuried]

    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == spliceTx.txid)
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)

    resolveHtlcs(f, htlcs, spliceOutFee = 0.sat)
  }

  test("disconnect (tx_signatures received by alice, zero-conf)", Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testDisconnectTxSignaturesReceivedByAliceZeroConf(f)
  }

  test("disconnect (tx_signatures received by alice, zero-conf, quiescence)", Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.Quiescence)) { f =>
    testDisconnectTxSignaturesReceivedByAliceZeroConf(f)
  }


  test("disconnect (tx_signatures sent by alice, splice confirms while bob is offline)") { f =>
    import f._

    val sender = initiateSpliceWithoutSigs(f, spliceOut_opt = Some(SpliceOut(20_000 sat, defaultSpliceOutScriptPubKey)))
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures] // Bob doesn't receive Alice's tx_signatures
    sender.expectMsgType[RES_SPLICE]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)

    // The splice transaction confirms while Bob is offline.
    val spliceTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    disconnect(f)
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)

    val (channelReestablishAlice, channelReestablishBob) = reconnect(f, interceptFundingDeeplyBuried = false)
    assert(channelReestablishAlice.nextFundingTxId_opt.isEmpty)
    assert(channelReestablishBob.nextFundingTxId_opt.contains(spliceTx.txid))
    bob2alice.expectNoMessage(100 millis)

    // Bob receives Alice's tx_signatures, which completes the splice.
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
  }

  test("don't resend splice_locked when zero-conf channel confirms", Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectMsgType[WatchPublished]
    // splice tx gets published, alice sends splice_locked
    alice ! WatchPublishedTriggered(fundingTx)
    alice2bob.expectMsgType[SpliceLocked]
    alice2blockchain.expectWatchFundingConfirmed(fundingTx.txid)
    // splice tx confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
    alice2bob.expectNoMessage(100 millis)
    alice2blockchain.expectWatchFundingSpent(fundingTx.txid)
  }

  test("re-send splice_locked on reconnection") { f =>
    import f._

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed1a = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    val watchConfirmed1b = bob2blockchain.expectMsgType[WatchFundingConfirmed]
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed2a = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    val watchConfirmed2b = bob2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
    // we now have two unconfirmed splices

    alice2bob.ignoreMsg { case _: ChannelUpdate => true }
    bob2alice.ignoreMsg { case _: ChannelUpdate => true }

    disconnect(f)
    reconnect(f)

    // channel_ready are not re-sent because the channel has already been used (for building splices)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // splice 1 confirms on alice's side
    watchConfirmed1a.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]

    disconnect(f)
    reconnect(f)

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    alice2bob.forward(bob)

    // splice 2 confirms on alice's side
    watchConfirmed2a.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]

    disconnect(f)
    reconnect(f)

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // splice 1 confirms on bob's side
    watchConfirmed1b.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    bob2alice.forward(alice)
    bob2blockchain.expectMsgType[WatchFundingSpent]

    disconnect(f)
    reconnect(f)

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx1.txid)
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // splice 2 confirms on bob's side
    watchConfirmed2b.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    bob2blockchain.expectMsgType[WatchFundingSpent]

    // NB: we disconnect *before* transmitting the splice_confirmed to alice
    disconnect(f)
    reconnect(f)

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    // this time alice received the splice_confirmed for funding tx 2
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    disconnect(f)
    reconnect(f)

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxid == fundingTx2.txid)
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
  }

  /** Check type of published transactions */
  def assertPublished(probe: TestProbe, desc: String): Transaction = {
    val p = probe.expectMsgType[PublishTx]
    assert(desc == p.desc)
    p match {
      case p: PublishFinalTx => p.tx
      case p: PublishReplaceableTx => p.txInfo.tx
    }
  }

  def testForceCloseWithMultipleSplicesSimple(f: FixtureParam): Unit = {
    import f._

    val htlcs = setupHtlcs(f)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed1 = alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    initiateSplice(f, spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed2 = alice2blockchain.expectWatchFundingConfirmed(fundingTx2.txid)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    // we now have two unconfirmed splices

    alice ! CMD_FORCECLOSE(ActorRef.noSender)
    alice2bob.expectMsgType[Error]
    val commitTx2 = assertPublished(alice2blockchain, "commit-tx")
    Transaction.correctlySpends(commitTx2, Seq(fundingTx2), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    val claimMainDelayed2 = assertPublished(alice2blockchain, "local-main-delayed")
    // alice publishes her htlc timeout transactions
    val htlcsTxsOut = htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "htlc-timeout"))
    htlcsTxsOut.foreach(tx => Transaction.correctlySpends(tx, Seq(commitTx2), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

    val watchConfirmedCommit2 = alice2blockchain.expectWatchTxConfirmed(commitTx2.txid)
    val watchConfirmedClaimMainDelayed2 = alice2blockchain.expectWatchTxConfirmed(claimMainDelayed2.txid)
    // watch for all htlc outputs from local commit-tx to be spent
    val watchHtlcsOut = htlcs.aliceToBob.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    htlcs.bobToAlice.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])

    // splice 1 confirms
    watchConfirmed1.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]

    // splice 2 confirms
    watchConfirmed2.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]

    // commit tx confirms
    watchConfirmedCommit2.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, commitTx2)
    // claim-main-delayed tx confirms
    watchConfirmedClaimMainDelayed2.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, claimMainDelayed2)
    // alice's htlc-timeout txs confirm
    watchHtlcsOut.zip(htlcsTxsOut).foreach { case (watch, tx) => watch.replyTo ! WatchOutputSpentTriggered(tx) }
    htlcsTxsOut.foreach { tx =>
      alice2blockchain.expectWatchTxConfirmed(tx.txid)
      alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, tx)
    }

    // alice publishes, watches and confirms their 2nd-stage htlc-delayed txs
    htlcs.aliceToBob.foreach { _ =>
      val tx = assertPublished(alice2blockchain, "htlc-delayed")
      alice2blockchain.expectWatchTxConfirmed(tx.txid)
      alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, tx)
    }

    // confirm bob's htlc-timeout txs
    val remoteOutpoints = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.map(rcp => rcp.htlcTxs.filter(_._2.isEmpty).keys).toSeq.flatten
    assert(remoteOutpoints.size == htlcs.bobToAlice.size)
    remoteOutpoints.foreach { out => alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, htlcsTxsOut.head.copy(txIn = Seq(TxIn(out, Nil, 0)))) }
    alice2blockchain.expectNoMessage(100 millis)

    checkPostSpliceState(f, spliceOutFee(f, capacity = 1_900_000.sat))

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[LocalClose]))
  }

  test("force-close with multiple splices (simple)") { f =>
    testForceCloseWithMultipleSplicesSimple(f)
  }

  test("force-close with multiple splices (simple, quiescence)", Tag(ChannelStateTestsTags.Quiescence)) { f =>
    testForceCloseWithMultipleSplicesSimple(f)
  }

  def testForceCloseWithMultipleSplicesPreviousActiveRemote(f: FixtureParam): Unit = {
    import f._

    val htlcs = setupHtlcs(f)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val watchConfirmed1 = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    // we now have two unconfirmed splices

    alice ! CMD_FORCECLOSE(ActorRef.noSender)
    alice2bob.expectMsgType[Error]
    val aliceCommitTx2 = assertPublished(alice2blockchain, "commit-tx")
    assertPublished(alice2blockchain, "local-main-delayed")
    // alice publishes her htlc timeout transactions
    val htlcsTxsOut = htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "htlc-timeout"))
    htlcsTxsOut.foreach(tx => Transaction.correctlySpends(tx, Seq(aliceCommitTx2), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

    alice2blockchain.expectMsgType[WatchTxConfirmed]
    alice2blockchain.expectMsgType[WatchTxConfirmed]
    // watch for all htlc outputs from local commit-tx to be spent
    htlcs.aliceToBob.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    htlcs.bobToAlice.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])

    // splice 1 confirms
    watchConfirmed1.replyTo ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2blockchain.expectWatchFundingSpent(fundingTx1.txid)

    // bob publishes his commit tx for splice 1 (which double-spends splice 2)
    val bobCommitment1 = bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.active.find(_.fundingTxIndex == 1).get
    val bobCommitTx1 = bobCommitment1.fullySignedLocalCommitTx(bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.params, bob.underlyingActor.keyManager).tx
    Transaction.correctlySpends(bobCommitTx1, Seq(fundingTx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice ! WatchFundingSpentTriggered(bobCommitTx1)
    val watchAlternativeConfirmed = alice2blockchain.expectMsgType[WatchAlternativeCommitTxConfirmed]
    alice2blockchain.expectNoMessage(100 millis)
    // remote commit tx confirms
    watchAlternativeConfirmed.replyTo ! WatchAlternativeCommitTxConfirmedTriggered(BlockHeight(400000), 42, bobCommitTx1)

    // we're back to the normal handling of remote commit
    val claimMain = assertPublished(alice2blockchain, "remote-main")
    val htlcsTxsOut1 = htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "claim-htlc-timeout"))
    htlcsTxsOut1.foreach(tx => Transaction.correctlySpends(tx, Seq(bobCommitTx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    val watchConfirmedRemoteCommit = alice2blockchain.expectWatchTxConfirmed(bobCommitTx1.txid)
    // this one fires immediately, tx is already confirmed
    watchConfirmedRemoteCommit.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, bobCommitTx1)

    val watchConfirmedClaimMain = alice2blockchain.expectWatchTxConfirmed(claimMain.txid)
    watchConfirmedClaimMain.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, claimMain)

    // alice's htlc-timeout transactions confirm
    val watchHtlcsOut1 = htlcs.aliceToBob.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    htlcs.bobToAlice.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    watchHtlcsOut1.zip(htlcsTxsOut1).foreach { case (watch, tx) => watch.replyTo ! WatchOutputSpentTriggered(tx) }
    htlcsTxsOut1.foreach { tx =>
      alice2blockchain.expectWatchTxConfirmed(tx.txid)
      alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, tx)
    }

    // bob's htlc-timeout transactions confirm
    bobCommitment1.localCommit.htlcTxsAndRemoteSigs.foreach(txAndSig => alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, txAndSig.htlcTx.tx))
    alice2blockchain.expectNoMessage(100 millis)

    checkPostSpliceState(f, spliceOutFee = 0.sat)

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[RemoteClose]))
  }

  test("force-close with multiple splices (previous active remote)") { f =>
    testForceCloseWithMultipleSplicesPreviousActiveRemote(f)
  }

  test("force-close with multiple splices (previous active remote, quiescence)", Tag(ChannelStateTestsTags.Quiescence)) { f =>
    testForceCloseWithMultipleSplicesPreviousActiveRemote(f)
  }

  def testForceCloseWithMultipleSplicesPreviousActiveRevoked(f: FixtureParam): Unit = {
    import f._

    val htlcs = setupHtlcs(f)

    // pay 10_000_000 msat to bob that will be paid back to alice after the splices
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 10_000_000 msat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    // remember bob's commitment for later
    val bobCommit1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    // we now have two unconfirmed splices, both active

    // bob makes a payment
    val (preimage, add) = addHtlc(10_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(add.id, preimage, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // funding tx1 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2blockchain.expectWatchFundingSpent(fundingTx1.txid)
    // bob publishes a revoked commitment for fundingTx1!
    val bobRevokedCommitTx = bobCommit1.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobRevokedCommitTx)
    // alice watches bob's revoked commit tx, and force-closes with her latest commitment
    assert(alice2blockchain.expectMsgType[WatchAlternativeCommitTxConfirmed].txId == bobRevokedCommitTx.txid)
    val aliceCommitTx2 = assertPublished(alice2blockchain, "commit-tx")
    val claimMainDelayed2 = assertPublished(alice2blockchain, "local-main-delayed")
    htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "htlc-timeout"))
    alice2blockchain.expectWatchTxConfirmed(aliceCommitTx2.txid)
    alice2blockchain.expectWatchTxConfirmed(claimMainDelayed2.txid)
    htlcs.aliceToBob.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    htlcs.bobToAlice.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])

    // bob's revoked tx wins
    alice ! WatchAlternativeCommitTxConfirmedTriggered(BlockHeight(400000), 42, bobRevokedCommitTx)
    // alice reacts by punishing bob
    val aliceClaimMain = assertPublished(alice2blockchain, "remote-main")
    val aliceMainPenalty = assertPublished(alice2blockchain, "main-penalty")
    val aliceHtlcsPenalty = htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "htlc-penalty")) ++ htlcs.bobToAlice.map(_ => assertPublished(alice2blockchain, "htlc-penalty"))
    aliceHtlcsPenalty.foreach(tx => Transaction.correctlySpends(tx, Seq(bobRevokedCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    alice2blockchain.expectWatchTxConfirmed(bobRevokedCommitTx.txid)
    alice2blockchain.expectWatchTxConfirmed(aliceClaimMain.txid)
    assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == bobRevokedCommitTx.txid)
    aliceHtlcsPenalty.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    alice2blockchain.expectNoMessage(100 millis)

    // alice's main penalty txs confirm
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, bobRevokedCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, aliceClaimMain)
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, aliceMainPenalty)
    // alice's htlc-penalty txs confirm
    aliceHtlcsPenalty.foreach { tx => alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, tx) }

    checkPostSpliceState(f, spliceOutFee = 0.sat)

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[RevokedClose]))
  }

  test("force-close with multiple splices (previous active revoked)") { f =>
    testForceCloseWithMultipleSplicesPreviousActiveRevoked(f)
  }

  test("force-close with multiple splices (previous active revoked, quiescent)", Tag(ChannelStateTestsTags.Quiescence)) { f =>
    testForceCloseWithMultipleSplicesPreviousActiveRevoked(f)
  }

  def testForceCloseWithMultipleSplicesInactiveRemote(f: FixtureParam): Unit = {
    import f._

    val htlcs = setupHtlcs(f)

    // pay 10_000_000 msat to bob that will be paid back to alice after the splices
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 10_000_000 msat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectWatchPublished(fundingTx1.txid)
    bob2blockchain.expectWatchPublished(fundingTx1.txid)

    // splice 1 gets published
    alice ! WatchPublishedTriggered(fundingTx1)
    bob ! WatchPublishedTriggered(fundingTx1)
    alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)

    // bob makes a payment which is applied to splice 1
    val (preimage, add) = addHtlc(10_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(add.id, preimage, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // remember bob's commitment for later
    val bobCommit1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectWatchPublished(fundingTx2.txid)
    bob2blockchain.expectWatchPublished(fundingTx2.txid)
    // splice 2 gets published
    alice ! WatchPublishedTriggered(fundingTx2)
    bob ! WatchPublishedTriggered(fundingTx2)
    alice2blockchain.expectWatchFundingConfirmed(fundingTx2.txid)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx2.txid)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    // splice 1 is now inactive
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.exists(_.fundingTxId == fundingTx1.txid))
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.exists(_.fundingTxId == fundingTx1.txid))
    // we now have two unconfirmed splices, one active and one inactive, and the inactive initial funding
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 2)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 2)

    // funding tx1 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    // alice puts a watch-spent and prunes the initial funding
    alice2blockchain.expectWatchFundingSpent(fundingTx1.txid)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 1)
    // bob publishes his latest (inactive) commitment for fundingTx1
    val bobCommitTx1 = bobCommit1.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobCommitTx1)
    // alice watches bob's commit tx, and force-closes with her latest commitment
    assert(alice2blockchain.expectMsgType[WatchAlternativeCommitTxConfirmed].txId == bobCommitTx1.txid)
    val aliceCommitTx2 = assertPublished(alice2blockchain, "commit-tx")
    assertPublished(alice2blockchain, "local-anchor")
    val claimMainDelayed2 = assertPublished(alice2blockchain, "local-main-delayed")
    val htlcsTxsOut = htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "htlc-timeout"))
    htlcsTxsOut.foreach(tx => Transaction.correctlySpends(tx, Seq(aliceCommitTx2), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

    alice2blockchain.expectWatchTxConfirmed(aliceCommitTx2.txid)
    alice2blockchain.expectWatchTxConfirmed(claimMainDelayed2.txid)
    alice2blockchain.expectMsgType[WatchOutputSpent] // local-anchor
    htlcs.aliceToBob.map(_ => assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == aliceCommitTx2.txid))
    htlcs.bobToAlice.map(_ => assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == aliceCommitTx2.txid))
    alice2blockchain.expectNoMessage(100 millis)

    // bob's remote tx wins
    alice ! WatchAlternativeCommitTxConfirmedTriggered(BlockHeight(400000), 42, bobCommitTx1)
    // we're back to the normal handling of remote commit
    assert(alice2blockchain.expectMsgType[PublishReplaceableTx].txInfo.isInstanceOf[ClaimLocalAnchorOutputTx])
    val claimMain = alice2blockchain.expectMsgType[PublishFinalTx].tx
    val claimHtlcsTxsOut = htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "claim-htlc-timeout"))
    claimHtlcsTxsOut.foreach(tx => Transaction.correctlySpends(tx, Seq(bobCommitTx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

    val watchConfirmedRemoteCommit = alice2blockchain.expectWatchTxConfirmed(bobCommitTx1.txid)
    // this one fires immediately, tx is already confirmed
    watchConfirmedRemoteCommit.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, bobCommitTx1)
    val watchConfirmedClaimMain = alice2blockchain.expectWatchTxConfirmed(claimMain.txid)
    watchConfirmedClaimMain.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, claimMain)
    // watch alice and bob's htlcs and publish alice's htlcs-timeout txs
    htlcs.aliceToBob.map(_ => assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == bobCommitTx1.txid))
    htlcs.bobToAlice.map(_ => assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == bobCommitTx1.txid))
    claimHtlcsTxsOut.foreach { tx => alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, tx) }

    // publish bob's htlc-timeout txs
    bobCommit1.localCommit.htlcTxsAndRemoteSigs.foreach(txAndSigs => alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, txAndSigs.htlcTx.tx))
    alice2blockchain.expectNoMessage(100 millis)

    // alice's final commitment includes the initial htlcs, but not bob's payment
    checkPostSpliceState(f, spliceOutFee = 0.sat)

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[RemoteClose]))
  }

  test("force-close with multiple splices (inactive remote)", Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testForceCloseWithMultipleSplicesInactiveRemote(f)
  }

  test("force-close with multiple splices (inactive remote, quiescence)", Tag(ChannelStateTestsTags.Quiescence), Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testForceCloseWithMultipleSplicesInactiveRemote(f)
  }

  def testForceCloseWithMultipleSplicesInactiveRevoked(f: FixtureParam): Unit = {
    import f._

    val htlcs = setupHtlcs(f)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectWatchPublished(fundingTx1.txid)
    bob2blockchain.expectWatchPublished(fundingTx1.txid)
    // remember bob's commitment for later
    val bobCommit1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head
    // splice 1 gets published
    alice ! WatchPublishedTriggered(fundingTx1)
    bob ! WatchPublishedTriggered(fundingTx1)
    alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectWatchPublished(fundingTx2.txid)
    bob2blockchain.expectWatchPublished(fundingTx2.txid)
    // splice 2 gets published
    alice ! WatchPublishedTriggered(fundingTx2)
    bob ! WatchPublishedTriggered(fundingTx2)
    alice2blockchain.expectWatchFundingConfirmed(fundingTx2.txid)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx2.txid)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    // splice 1 is now inactive
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.exists(_.fundingTxId == fundingTx1.txid))
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.exists(_.fundingTxId == fundingTx1.txid))
    // we now have two unconfirmed splices, one active and one inactive, and the inactive initial funding
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 2)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 2)

    // bob makes a payment that is only applied to splice 2
    val (preimage, add) = addHtlc(10_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(add.id, preimage, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // funding tx1 confirms
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    // alice puts a watch-spent and prunes the initial funding
    alice2blockchain.expectWatchFundingSpent(fundingTx1.txid)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.size == 1)
    // bob publishes his latest commitment for fundingTx1, which is now revoked
    val bobRevokedCommitTx = bobCommit1.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobRevokedCommitTx)
    // alice watches bob's revoked commit tx, and force-closes with her latest commitment
    assert(alice2blockchain.expectMsgType[WatchAlternativeCommitTxConfirmed].txId == bobRevokedCommitTx.txid)
    val aliceCommitTx2 = assertPublished(alice2blockchain, "commit-tx")
    assertPublished(alice2blockchain, "local-anchor")
    val claimMainDelayed2 = assertPublished(alice2blockchain, "local-main-delayed")
    htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "htlc-timeout"))

    alice2blockchain.expectWatchTxConfirmed(aliceCommitTx2.txid)
    alice2blockchain.expectWatchTxConfirmed(claimMainDelayed2.txid)
    alice2blockchain.expectMsgType[WatchOutputSpent] // local-anchor
    htlcs.aliceToBob.foreach(_ => assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == aliceCommitTx2.txid))
    htlcs.bobToAlice.foreach(_ => assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == aliceCommitTx2.txid))
    alice2blockchain.expectNoMessage(100 millis)

    // bob's revoked tx wins
    alice ! WatchAlternativeCommitTxConfirmedTriggered(BlockHeight(400000), 42, bobRevokedCommitTx)
    // alice reacts by punishing bob
    val aliceClaimMain = assertPublished(alice2blockchain, "remote-main-delayed")
    val aliceMainPenalty = assertPublished(alice2blockchain, "main-penalty")
    val aliceHtlcsPenalty = htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "htlc-penalty")) ++ htlcs.bobToAlice.map(_ => assertPublished(alice2blockchain, "htlc-penalty"))

    alice2blockchain.expectWatchTxConfirmed(bobRevokedCommitTx.txid)
    alice2blockchain.expectWatchTxConfirmed(aliceClaimMain.txid)
    assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == bobRevokedCommitTx.txid) // main-penalty
    aliceHtlcsPenalty.map(_ => assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == bobRevokedCommitTx.txid))
    alice2blockchain.expectNoMessage(100 millis)

    // all penalty txs confirm
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, bobRevokedCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, aliceClaimMain)
    alice ! WatchOutputSpentTriggered(aliceMainPenalty)
    alice2blockchain.expectWatchTxConfirmed(aliceMainPenalty.txid)
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, aliceMainPenalty)
    aliceHtlcsPenalty.foreach { tx => alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, tx) }

    // alice's final commitment includes the initial htlcs, but not bob's payment
    checkPostSpliceState(f, spliceOutFee = 0 sat)

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[RevokedClose]))
  }

  test("force-close with multiple splices (inactive revoked)", Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testForceCloseWithMultipleSplicesInactiveRevoked(f)
  }

  test("force-close with multiple splices (inactive revoked, quiescence)", Tag(ChannelStateTestsTags.Quiescence), Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testForceCloseWithMultipleSplicesInactiveRevoked(f)
  }

  test("put back watches after restart") { f =>
    import f._

    val fundingTx0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    val (fundingTx1, fundingTx2) = setup2Splices(f)

    val (aliceNodeParams, bobNodeParams) = (alice.underlyingActor.nodeParams, bob.underlyingActor.nodeParams)
    val (alicePeer, bobPeer) = (alice.getParent, bob.getParent)

    val aliceData = alice.stateData.asInstanceOf[PersistentChannelData]
    val bobData = bob.stateData.asInstanceOf[PersistentChannelData]

    alice.stop()
    bob.stop()

    alice2blockchain.expectNoMessage(100 millis)
    bob2blockchain.expectNoMessage(100 millis)

    val alice2 = TestFSMRef(new Channel(aliceNodeParams, wallet, bobNodeParams.nodeId, alice2blockchain.ref, TestProbe().ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer)
    alice2 ! INPUT_RESTORED(aliceData)
    alice2blockchain.expectMsgType[SetChannelId]
    alice2blockchain.expectWatchFundingConfirmed(fundingTx2.txid)
    alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    alice2blockchain.expectWatchFundingSpent(fundingTx0.txid)
    alice2blockchain.expectNoMessage(100 millis)

    val bob2 = TestFSMRef(new Channel(bobNodeParams, wallet, aliceNodeParams.nodeId, bob2blockchain.ref, TestProbe().ref, FakeTxPublisherFactory(bob2blockchain)), bobPeer)
    bob2 ! INPUT_RESTORED(bobData)
    bob2blockchain.expectMsgType[SetChannelId]
    bob2blockchain.expectWatchFundingConfirmed(fundingTx2.txid)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    bob2blockchain.expectWatchFundingSpent(fundingTx0.txid)
    bob2blockchain.expectNoMessage(100 millis)
  }

  test("put back watches after restart (inactive)", Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val fundingTx0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get

    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx0)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx0)
    alice2blockchain.expectWatchFundingSpent(fundingTx0.txid)
    bob2blockchain.expectWatchFundingSpent(fundingTx0.txid)

    // create splice 1
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectMsgType[WatchPublished]
    bob2blockchain.expectMsgType[WatchPublished]
    alice ! WatchPublishedTriggered(fundingTx1)
    bob ! WatchPublishedTriggered(fundingTx1)
    alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    // splice 1 has been locked, fundingTx0 is inactive

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
    alice2blockchain.expectMsgType[WatchPublished]
    bob2blockchain.expectMsgType[WatchPublished]

    val (aliceNodeParams, bobNodeParams) = (alice.underlyingActor.nodeParams, bob.underlyingActor.nodeParams)
    val (alicePeer, bobPeer) = (alice.getParent, bob.getParent)

    val aliceData = alice.stateData.asInstanceOf[PersistentChannelData]
    val bobData = bob.stateData.asInstanceOf[PersistentChannelData]

    alice.stop()
    bob.stop()

    alice2blockchain.expectNoMessage(100 millis)
    bob2blockchain.expectNoMessage(100 millis)

    val alice2 = TestFSMRef(new Channel(aliceNodeParams, wallet, bobNodeParams.nodeId, alice2blockchain.ref, TestProbe().ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer)
    alice2 ! INPUT_RESTORED(aliceData)
    alice2blockchain.expectMsgType[SetChannelId]
    alice2blockchain.expectWatchPublished(fundingTx2.txid)
    alice2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    alice2blockchain.expectWatchFundingSpent(fundingTx0.txid)
    alice2blockchain.expectNoMessage(100 millis)

    val bob2 = TestFSMRef(new Channel(bobNodeParams, wallet, aliceNodeParams.nodeId, bob2blockchain.ref, TestProbe().ref, FakeTxPublisherFactory(bob2blockchain)), bobPeer)
    bob2 ! INPUT_RESTORED(bobData)
    bob2blockchain.expectMsgType[SetChannelId]
    bob2blockchain.expectWatchPublished(fundingTx2.txid)
    bob2blockchain.expectWatchFundingConfirmed(fundingTx1.txid)
    bob2blockchain.expectWatchFundingSpent(fundingTx0.txid)
    bob2blockchain.expectNoMessage(100 millis)
  }

  test("recv CMD_SPLICE (splice-in + splice-out) with pre and post splice htlcs", Tag(ChannelStateTestsTags.Quiescence)) { f =>
    import f._
    val htlcs = setupHtlcs(f)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 10_000_000 msat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))

    // bob sends an HTLC that is applied to both commitments
    val (preimage, add) = addHtlc(10_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    val aliceCommitments1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    aliceCommitments1.active.foreach { c =>
      val commitTx = c.fullySignedLocalCommitTx(aliceCommitments1.params, alice.underlyingActor.keyManager).tx
      Transaction.correctlySpends(commitTx, Map(c.commitInput.outPoint -> c.commitInput.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    val bobCommitments1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments
    bobCommitments1.active.foreach { c =>
      val commitTx = c.fullySignedLocalCommitTx(bobCommitments1.params, bob.underlyingActor.keyManager).tx
      Transaction.correctlySpends(commitTx, Map(c.commitInput.outPoint -> c.commitInput.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    // alice fulfills that HTLC in both commitments
    fulfillHtlc(add.id, preimage, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val aliceCommitments2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    aliceCommitments2.active.foreach { c =>
      val commitTx = c.fullySignedLocalCommitTx(aliceCommitments2.params, alice.underlyingActor.keyManager).tx
      Transaction.correctlySpends(commitTx, Map(c.commitInput.outPoint -> c.commitInput.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }
    val bobCommitments2 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments
    bobCommitments2.active.foreach { c =>
      val commitTx = c.fullySignedLocalCommitTx(bobCommitments2.params, bob.underlyingActor.keyManager).tx
      Transaction.correctlySpends(commitTx, Map(c.commitInput.outPoint -> c.commitInput.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    }

    resolveHtlcs(f, htlcs, spliceOutFee = 0.sat)
  }

  test("recv CMD_SPLICE (splice-in + splice-out) with pending htlcs, resolved after splice locked", Tag(ChannelStateTestsTags.Quiescence), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val htlcs = setupHtlcs(f)

    val spliceTx = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))

    alice ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)

    resolveHtlcs(f, htlcs, spliceOutFee = 0.sat)
  }

  test("recv multiple CMD_SPLICE (splice-in, splice-out, quiescence)", Tag(ChannelStateTestsTags.Quiescence)) { f =>
    val htlcs = setupHtlcs(f)

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    initiateSplice(f, spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))

    resolveHtlcs(f, htlcs, spliceOutFee = spliceOutFee(f, capacity = 1_900_000.sat))
  }

  test("recv invalid htlc signatures during splice-in", Tag(ChannelStateTestsTags.Quiescence)) { f =>
    import f._

    val htlcs = setupHtlcs(f)
    initiateSpliceWithoutSigs(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    val commitSigAlice = alice2bob.expectMsgType[CommitSig]
    assert(commitSigAlice.htlcSignatures.size == 4)
    val commitSigBob = bob2alice.expectMsgType[CommitSig]
    assert(commitSigBob.htlcSignatures.size == 4)
    bob2alice.forward(alice, commitSigBob)

    alice2bob.forward(bob, commitSigAlice.copy(htlcSignatures = commitSigAlice.htlcSignatures.reverse))
    val txAbortBob = bob2alice.expectMsgType[TxAbort]
    bob2alice.forward(alice, txAbortBob)
    val txAbortAlice = alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob, txAbortAlice)

    // resolve pre-splice HTLCs after aborting the splice attempt
    val Seq((preimage1a, htlc1a), (preimage2a, htlc2a)) = htlcs.aliceToBob
    val Seq((preimage1b, htlc1b), (preimage2b, htlc2b)) = htlcs.bobToAlice
    fulfillHtlc(htlc1a.id, preimage1a, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlc2a.id, preimage2a, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlc1b.id, preimage1b, alice, bob, alice2bob, bob2alice)
    fulfillHtlc(htlc2b.id, preimage2b, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val finalState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(finalState.commitments.latest.localCommit.spec.toLocal == 805_000_000.msat)
    assert(finalState.commitments.latest.localCommit.spec.toRemote == 695_000_000.msat)
  }

}
