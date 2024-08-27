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
import fr.acinq.eclair.blockchain.DummyOnChainWallet.SingleKeyOnChainWallet.invalidFundingAmount
import fr.acinq.eclair.blockchain.SingleKeyOnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.Helpers.Closing.{LocalClose, RemoteClose, RevokedClose}
import fr.acinq.eclair.channel.LocalFundingStatus.DualFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.FullySignedSharedTransaction
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx, PublishTx, SetChannelId}
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.{FakeTxPublisherFactory, PimpTestFSM}
import fr.acinq.eclair.channel.states.ChannelStateTestsTags._
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.db.RevokedHtlcInfoCleaner.ForgetHtlcInfos
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.testutils.PimpTestProbe.convert
import fr.acinq.eclair.transactions.DirectedHtlc.{incoming, outgoing}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.ClaimLocalAnchorOutputTx
import fr.acinq.eclair.wire.protocol._
import org.scalatest.Inside.inside
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
    val tags = test.tags + DualFunding
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

  private def initiateSpliceWithoutSigs(s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, spliceIn_opt: Option[SpliceIn], spliceOut_opt: Option[SpliceOut]): TestProbe = {
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt, spliceOut_opt)
    s ! cmd
    exchangeStfu(s, r, s2r, r2s)
    s2r.expectMsgType[SpliceInit]
    s2r.forward(r)
    r2s.expectMsgType[SpliceAck]
    r2s.forward(s)

    s2r.expectMsgType[TxAddInput]
    s2r.forward(r)
    r2s.expectMsgType[TxComplete]
    r2s.forward(s)
    if (spliceIn_opt.isDefined) {
      s2r.expectMsgType[TxAddInput]
      s2r.forward(r)
      r2s.expectMsgType[TxComplete]
      r2s.forward(s)
      s2r.expectMsgType[TxAddOutput]
      s2r.forward(r)
      r2s.expectMsgType[TxComplete]
      r2s.forward(s)
    }
    if (spliceOut_opt.isDefined) {
      s2r.expectMsgType[TxAddOutput]
      s2r.forward(r)
      r2s.expectMsgType[TxComplete]
      r2s.forward(s)
    }
    s2r.expectMsgType[TxAddOutput]
    s2r.forward(r)
    r2s.expectMsgType[TxComplete]
    r2s.forward(s)
    s2r.expectMsgType[TxComplete]
    s2r.forward(r)
    sender
  }

  private def initiateSpliceWithoutSigs(f: FixtureParam, spliceIn_opt: Option[SpliceIn] = None, spliceOut_opt: Option[SpliceOut] = None): TestProbe = initiateSpliceWithoutSigs(f.alice, f.bob, f.alice2bob, f.bob2alice, spliceIn_opt, spliceOut_opt)

  private def initiateRbfWithoutSigs(s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, feerate: FeeratePerKw, sInputsCount: Int, sOutputsCount: Int, rInputsCount: Int, rOutputsCount: Int): TestProbe = {
    val sender = TestProbe()
    val cmd = CMD_BUMP_FUNDING_FEE(sender.ref, feerate, 100_000 sat, 0)
    s ! cmd
    exchangeStfu(s, r, s2r, r2s)
    s2r.expectMsgType[TxInitRbf]
    s2r.forward(r)
    r2s.expectMsgType[TxAckRbf]
    r2s.forward(s)

    // The initiator also adds the shared input and shared output.
    var sRemainingInputs = sInputsCount + 1
    var sRemainingOutputs = sOutputsCount + 1
    var rRemainingInputs = rInputsCount
    var rRemainingOutputs = rOutputsCount
    var sComplete = false
    var rComplete = false

    while (sRemainingInputs > 0 || sRemainingOutputs > 0 || rRemainingInputs > 0 || rRemainingOutputs > 0) {
      if (sRemainingInputs > 0) {
        s2r.expectMsgType[TxAddInput]
        s2r.forward(r)
        sRemainingInputs -= 1
      } else if (sRemainingOutputs > 0) {
        s2r.expectMsgType[TxAddOutput]
        s2r.forward(r)
        sRemainingOutputs -= 1
      } else {
        s2r.expectMsgType[TxComplete]
        s2r.forward(r)
        sComplete = true
      }

      if (rRemainingInputs > 0) {
        r2s.expectMsgType[TxAddInput]
        r2s.forward(s)
        rRemainingInputs -= 1
      } else if (rRemainingOutputs > 0) {
        r2s.expectMsgType[TxAddOutput]
        r2s.forward(s)
        rRemainingOutputs -= 1
      } else {
        r2s.expectMsgType[TxComplete]
        r2s.forward(s)
        rComplete = true
      }
    }

    if (!sComplete || !rComplete) {
      s2r.expectMsgType[TxComplete]
      s2r.forward(r)
      if (!rComplete) {
        r2s.expectMsgType[TxComplete]
        r2s.forward(s)
      }
    }

    sender
  }

  private def initiateRbfWithoutSigs(f: FixtureParam, feerate: FeeratePerKw, sInputsCount: Int, sOutputsCount: Int): TestProbe = {
    initiateRbfWithoutSigs(f.alice, f.bob, f.alice2bob, f.bob2alice, feerate, sInputsCount, sOutputsCount, rInputsCount = 0, rOutputsCount = 0)
  }

  private def exchangeSpliceSigs(s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, sender: TestProbe): Transaction = {
    val commitSigR = r2s.fishForMessage() {
      case _: CommitSig => true
      case _: ChannelReady => false
    }
    r2s.forward(s, commitSigR)
    val commitSigS = s2r.fishForMessage() {
      case _: CommitSig => true
      case _: ChannelReady => false
    }
    s2r.forward(r, commitSigS)

    val txSigsR = r2s.fishForMessage() {
      case _: TxSignatures => true
      case _: ChannelUpdate => false
    }
    r2s.forward(s, txSigsR)
    val txSigsS = s2r.fishForMessage() {
      case _: TxSignatures => true
      case _: ChannelUpdate => false
    }
    s2r.forward(r, txSigsS)

    sender.expectMsgType[RES_SPLICE]

    awaitCond(s.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(r.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    awaitCond(s.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.isInstanceOf[FullySignedSharedTransaction])
    awaitCond(r.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.isInstanceOf[FullySignedSharedTransaction])
    s.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get
  }

  private def exchangeSpliceSigs(f: FixtureParam, sender: TestProbe): Transaction = exchangeSpliceSigs(f.alice, f.bob, f.alice2bob, f.bob2alice, sender)

  private def initiateSplice(s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, spliceIn_opt: Option[SpliceIn], spliceOut_opt: Option[SpliceOut]): Transaction = {
    val sender = initiateSpliceWithoutSigs(s, r, s2r, r2s, spliceIn_opt, spliceOut_opt)
    exchangeSpliceSigs(s, r, s2r, r2s, sender)
  }

  private def initiateSplice(f: FixtureParam, spliceIn_opt: Option[SpliceIn] = None, spliceOut_opt: Option[SpliceOut] = None): Transaction = initiateSplice(f.alice, f.bob, f.alice2bob, f.bob2alice, spliceIn_opt, spliceOut_opt)

  private def initiateRbf(f: FixtureParam, feerate: FeeratePerKw, sInputsCount: Int, sOutputsCount: Int): Transaction = {
    val sender = initiateRbfWithoutSigs(f, feerate, sInputsCount, sOutputsCount)
    exchangeSpliceSigs(f, sender)
  }

  private def exchangeStfu(s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    s2r.expectMsgType[Stfu]
    s2r.forward(r)
    r2s.expectMsgType[Stfu]
    r2s.forward(s)
  }

  private def exchangeStfu(f: FixtureParam): Unit = exchangeStfu(f.alice, f.bob, f.alice2bob, f.bob2alice)

  private def checkWatchConfirmed(f: FixtureParam, spliceTx: Transaction): Unit = {
    import f._

    alice2blockchain.expectWatchFundingConfirmed(spliceTx.txid)
    alice2blockchain.expectNoMessage(100 millis)
    bob2blockchain.expectWatchFundingConfirmed(spliceTx.txid)
    bob2blockchain.expectNoMessage(100 millis)
  }

  private def confirmSpliceTx(f: FixtureParam, spliceTx: Transaction): Unit = {
    import f._

    val fundingTxIndex = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.find(_.fundingTxId == spliceTx.txid).get.fundingTxIndex

    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, spliceTx)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)

    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, spliceTx)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)

    // Previous commitments have been cleaned up.
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.forall(c => c.fundingTxIndex > fundingTxIndex || c.fundingTxId == spliceTx.txid), interval = 100 millis)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.forall(c => c.fundingTxIndex > fundingTxIndex || c.fundingTxId == spliceTx.txid), interval = 100 millis)
  }

  case class TestHtlcs(aliceToBob: Seq[(ByteVector32, UpdateAddHtlc)], bobToAlice: Seq[(ByteVector32, UpdateAddHtlc)])

  private def setupHtlcs(f: FixtureParam): TestHtlcs = {
    import f._

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

    alice2relayer.expectMsgType[Relayer.RelayForward]
    alice2relayer.expectMsgType[Relayer.RelayForward]
    bob2relayer.expectMsgType[Relayer.RelayForward]
    bob2relayer.expectMsgType[Relayer.RelayForward]

    TestHtlcs(htlcsAliceToBob, htlcsBobToAlice)
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
    val outgoingHtlcs = 30_000_000.msat
    val incomingHtlcs = 35_000_000.msat
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

    val settledHtlcs = 5_000_000.msat
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
    val f = init(tags = Set(DualFunding))
    import f._

    reachNormal(f, tags = Set.empty) // we open a non dual-funded channel
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

  test("recv CMD_SPLICE (splice-in, local and remote commit index mismatch)") { f =>
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
    crossSign(alice, bob, alice2bob, bob2alice)
    alice2bob.expectNoMessage(100 millis)
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

  test("recv CMD_SPLICE (splice-out, would go below reserve)", Tag(NoMaxHtlcValueInFlight)) { f =>
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
    exchangeStfu(f)
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
    exchangeStfu(f)
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

  test("recv CMD_SPLICE (remote splices in which takes us below reserve)", Tag(ChannelStateTestsTags.NoMaxHtlcValueInFlight)) { f =>
    import f._

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.toLocal == 800_000_000.msat)
    val (r1, htlc1) = addHtlc(750_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(htlc1.id, r1, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)

    // Bob makes a large splice: Alice doesn't meet the new reserve requirements, but she met the previous one, so we allow this.
    initiateSplice(bob, alice, bob2alice, alice2bob, spliceIn_opt = Some(SpliceIn(4_000_000 sat)), spliceOut_opt = None)
    val postSpliceState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(postSpliceState.commitments.latest.localCommit.spec.toLocal < postSpliceState.commitments.latest.localChannelReserve)

    // Since Alice is below the reserve and most of the funds are on Bob's side, Alice cannot send HTLCs.
    val probe = TestProbe()
    val (_, cmd) = makeCmdAdd(5_000_000 msat, bob.nodeParams.nodeId, bob.nodeParams.currentBlockHeight)
    alice ! cmd.copy(replyTo = probe.ref)
    probe.expectMsgType[RES_ADD_FAILED[InsufficientFunds]]

    // But Bob can send HTLCs to take Alice above the reserve.
    val (r2, htlc2) = addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlc2.id, r2, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // Alice can now send HTLCs as well.
    addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
  }

  test("recv CMD_SPLICE (pending RBF attempts)") { f =>
    import f._

    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    initiateRbf(f, FeeratePerKw(15_000 sat), sInputsCount = 2, sOutputsCount = 1)

    val probe = TestProbe()
    alice ! CMD_SPLICE(probe.ref, Some(SpliceIn(250_000 sat)), None)
    assert(probe.expectMsgType[RES_FAILURE[_, ChannelException]].t.isInstanceOf[InvalidSpliceWithUnconfirmedRbf])

    bob2alice.forward(alice, Stfu(alice.stateData.channelId, initiator = true))
    alice2bob.expectMsgType[Stfu]
    bob2alice.forward(alice, SpliceInit(alice.stateData.channelId, 100_000 sat, FeeratePerKw(5000 sat), 0, randomKey().publicKey))
    assert(alice2bob.expectMsgType[TxAbort].toAscii.contains("the previous splice was rbf-ed and is still unconfirmed"))
  }

  test("recv CMD_SPLICE (unconfirmed previous tx)") { f =>
    import f._

    // We create a first unconfirmed splice.
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.isInstanceOf[LocalFundingStatus.DualFundedUnconfirmedFundingTx])

    // We allow initiating such splice...
    val probe = TestProbe()
    alice ! CMD_SPLICE(probe.ref, Some(SpliceIn(250_000 sat)), None)
    alice2bob.expectMsgType[Stfu]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Stfu]
    bob2alice.forward(alice)
    val spliceInit = alice2bob.expectMsgType[SpliceInit]

    // But we don't allow receiving splice_init if the previous splice is unconfirmed and we're not using 0-conf.
    alice2bob.forward(bob, spliceInit)
    assert(bob2alice.expectMsgType[TxAbort].toAscii.contains("the current funding transaction is still unconfirmed"))
  }

  test("recv CMD_SPLICE (splice-in + splice-out)") { f =>
    val htlcs = setupHtlcs(f)
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    resolveHtlcs(f, htlcs, spliceOutFee = 0.sat)
  }

  test("recv CMD_BUMP_FUNDING_FEE (splice-in + splice-out)") { f =>
    import f._

    val spliceTx = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(300_000 sat, defaultSpliceOutScriptPubKey)))
    val spliceCommitment = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.find(_.fundingTxId == spliceTx.txid).get

    // Alice RBFs the splice transaction.
    // Our dummy bitcoin wallet adds an additional input at every funding attempt.
    val rbfTx1 = initiateRbf(f, FeeratePerKw(15_000 sat), sInputsCount = 2, sOutputsCount = 2)
    assert(rbfTx1.txIn.size == spliceTx.txIn.size + 1)
    spliceTx.txIn.foreach(txIn => assert(rbfTx1.txIn.map(_.outPoint).contains(txIn.outPoint)))
    assert(rbfTx1.txOut.size == spliceTx.txOut.size)

    // Bob RBFs the splice transaction: he needs to add an input to pay the fees.
    // Our dummy bitcoin wallet adds an additional input for Alice: a real bitcoin wallet would simply lower the previous change output.
    val sender2 = initiateRbfWithoutSigs(bob, alice, bob2alice, alice2bob, FeeratePerKw(20_000 sat), sInputsCount = 1, sOutputsCount = 1, rInputsCount = 3, rOutputsCount = 2)
    val rbfTx2 = exchangeSpliceSigs(alice, bob, alice2bob, bob2alice, sender2)
    assert(rbfTx2.txIn.size > rbfTx1.txIn.size)
    rbfTx1.txIn.foreach(txIn => assert(rbfTx2.txIn.map(_.outPoint).contains(txIn.outPoint)))
    assert(rbfTx2.txOut.size == rbfTx1.txOut.size + 1)

    // There are three pending splice transactions that double-spend each other.
    inside(alice.stateData.asInstanceOf[DATA_NORMAL]) { data =>
      val commitments = data.commitments.active.filter(_.fundingTxIndex == spliceCommitment.fundingTxIndex)
      assert(commitments.size == 3)
      assert(commitments.map(_.fundingTxId) == Seq(rbfTx2, rbfTx1, spliceTx).map(_.txid))
      // The contributions are the same across RBF attempts.
      commitments.foreach(c => assert(c.localCommit.spec.toLocal == spliceCommitment.localCommit.spec.toLocal))
      commitments.foreach(c => assert(c.localCommit.spec.toRemote == spliceCommitment.localCommit.spec.toRemote))
    }

    // The last RBF attempt confirms.
    confirmSpliceTx(f, rbfTx2)
    inside(alice.stateData.asInstanceOf[DATA_NORMAL]) { data =>
      assert(data.commitments.active.map(_.fundingTxId) == Seq(rbfTx2.txid))
      data.commitments.active.foreach(c => assert(c.localCommit.spec.toLocal == spliceCommitment.localCommit.spec.toLocal))
      data.commitments.active.foreach(c => assert(c.localCommit.spec.toRemote == spliceCommitment.localCommit.spec.toRemote))
    }

    // We can keep doing more splice transactions now that one of the previous transactions confirmed.
    initiateSplice(bob, alice, bob2alice, alice2bob, Some(SpliceIn(100_000 sat)), None)
  }

  test("recv CMD_BUMP_FUNDING_FEE (splice-in + splice-out from non-initiator)") { f =>
    import f._

    // Alice initiates a first splice.
    val spliceTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(2_500_000 sat)))
    confirmSpliceTx(f, spliceTx1)

    // Bob initiates a second splice that spends the first splice.
    val spliceTx2 = initiateSplice(bob, alice, bob2alice, alice2bob, spliceIn_opt = Some(SpliceIn(50_000 sat)), spliceOut_opt = Some(SpliceOut(25_000 sat, defaultSpliceOutScriptPubKey)))
    assert(spliceTx2.txIn.exists(_.outPoint.txid == spliceTx1.txid))

    // Alice cannot RBF her first splice, so she RBFs Bob's splice instead.
    val sender = initiateRbfWithoutSigs(alice, bob, alice2bob, bob2alice, FeeratePerKw(15_000 sat), sInputsCount = 1, sOutputsCount = 1, rInputsCount = 2, rOutputsCount = 2)
    val rbfTx = exchangeSpliceSigs(bob, alice, bob2alice, alice2bob, sender)
    assert(rbfTx.txIn.size > spliceTx2.txIn.size)
    spliceTx2.txIn.foreach(txIn => assert(rbfTx.txIn.map(_.outPoint).contains(txIn.outPoint)))
  }

  test("recv CMD_BUMP_FUNDING_FEE (transaction already confirmed)") { f =>
    import f._

    val spliceTx = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    confirmSpliceTx(f, spliceTx)

    val probe = TestProbe()
    alice ! CMD_BUMP_FUNDING_FEE(probe.ref, FeeratePerKw(15_000 sat), 100_000 sat, 0)
    assert(probe.expectMsgType[RES_FAILURE[_, ChannelException]].t.isInstanceOf[InvalidRbfTxConfirmed])

    bob2alice.forward(alice, Stfu(alice.stateData.channelId, initiator = true))
    alice2bob.expectMsgType[Stfu]
    bob2alice.forward(alice, TxInitRbf(alice.stateData.channelId, 0, FeeratePerKw(15_000 sat), 250_000 sat, requireConfirmedInputs = false))
    assert(alice2bob.expectMsgType[TxAbort].toAscii.contains("transaction is already confirmed"))
  }

  test("recv CMD_BUMP_FUNDING_FEE (transaction is using 0-conf)", Tag(ZeroConf), Tag(AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val spliceTx = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    alice ! WatchPublishedTriggered(spliceTx)
    alice2bob.expectMsgType[SpliceLocked]

    val probe = TestProbe()
    alice ! CMD_BUMP_FUNDING_FEE(probe.ref, FeeratePerKw(15_000 sat), 100_000 sat, 0)
    assert(probe.expectMsgType[RES_FAILURE[_, ChannelException]].t.isInstanceOf[InvalidRbfZeroConf])

    bob2alice.forward(alice, Stfu(alice.stateData.channelId, initiator = true))
    alice2bob.expectMsgType[Stfu]
    bob2alice.forward(alice, TxInitRbf(alice.stateData.channelId, 0, FeeratePerKw(15_000 sat), 250_000 sat, requireConfirmedInputs = false))
    assert(alice2bob.expectMsgType[TxAbort].toAscii.contains("transaction is already confirmed"))
  }

  test("recv TxAbort (before TxComplete)") { f =>
    import f._

    val sender = TestProbe()
    alice ! CMD_SPLICE(sender.ref, spliceIn_opt = None, spliceOut_opt = Some(SpliceOut(50_000 sat, defaultSpliceOutScriptPubKey)))
    exchangeStfu(f)
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
    exchangeStfu(f)
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
    exchangeStfu(f)
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
    val cmd = CMD_ADD_HTLC(sender.ref, 1_000_000_000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, 1.0, localOrigin(sender.ref))
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

  test("splice local/remote locking", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val fundingTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    checkWatchConfirmed(f, fundingTx1)
    val commitAlice1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.localCommit.commitTxAndRemoteSig.commitTx.tx
    val commitBob1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.localCommit.commitTxAndRemoteSig.commitTx.tx

    // Bob sees the first splice confirm, but Alice doesn't.
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2blockchain.expectWatchFundingSpent(fundingTx1.txid, Some(Set(commitAlice1.txid, commitBob1.txid)))
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    bob2alice.forward(alice)

    // Alice creates another splice spending the first splice.
    val fundingTx2 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    checkWatchConfirmed(f, fundingTx2)
    val commitAlice2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.localCommit.commitTxAndRemoteSig.commitTx.tx
    val commitBob2 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(commitAlice1.txid != commitAlice2.txid)
    assert(commitBob1.txid != commitBob2.txid)

    // Alice sees the first splice confirm.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    alice2bob.forward(bob)

    alice2blockchain.expectWatchFundingSpent(fundingTx1.txid, Some(Set(fundingTx2.txid, commitAlice1.txid, commitBob1.txid)))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2, 1))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)

    // Alice and Bob see the second splice confirm.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx2.txid)
    alice2bob.forward(bob)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx2.txid)
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

    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxId == fundingTx1.txid)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxId == fundingTx1.txid)
    bob2alice.forward(alice)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.fundingTxId == fundingTx1.txid)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.fundingTxId == fundingTx1.txid)
  }

  test("splice local/remote locking (intermingled)", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    val fundingTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    checkWatchConfirmed(f, fundingTx1)

    // Bob sees the first splice confirm, but Alice doesn't.
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2blockchain.expectMsgTypeHaving[WatchFundingSpent](_.txId == fundingTx1.txid)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    bob2alice.forward(alice)

    // Alice creates another splice spending the first splice.
    val fundingTx2 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    checkWatchConfirmed(f, fundingTx2)
    val commitAlice2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.localCommit.commitTxAndRemoteSig.commitTx.tx
    val commitBob2 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.localCommit.commitTxAndRemoteSig.commitTx.tx

    // Alice sees the second splice confirm.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx2.txid)
    alice2bob.forward(bob)
    alice2blockchain.expectWatchFundingSpent(fundingTx2.txid, Some(Set(commitAlice2.txid, commitBob2.txid)))
    bob2alice.expectNoMessage(100 millis)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2, 1))

    // Bob sees the second splice confirm.
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx2.txid)
    bob2alice.forward(alice)
    bob2blockchain.expectWatchFundingSpent(fundingTx2.txid, Some(Set(commitAlice2.txid, commitBob2.txid)))
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)

    // Alice sees the first splice confirm.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    // Alice doesn't send a splice_locked for the older tx.
    alice2bob.expectNoMessage(100 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.map(_.fundingTxIndex) == Seq(2))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.inactive.map(_.fundingTxIndex) == Seq.empty)
  }

  test("emit post-splice events", Tag(NoMaxHtlcValueInFlight)) { f =>
    import f._

    // Alice and Bob asynchronously exchange HTLCs, which makes their commit indices diverge.
    addHtlc(25_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)

    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(initialState.commitments.latest.capacity == 1_500_000.sat)
    assert(initialState.commitments.latest.localCommit.spec.toLocal == 775_000_000.msat)
    assert(initialState.commitments.latest.localCommit.spec.toRemote == 650_000_000.msat)
    assert(initialState.commitments.localCommitIndex != initialState.commitments.remoteCommitIndex)

    val aliceEvents = TestProbe()
    val bobEvents = TestProbe()
    systemA.eventStream.subscribe(aliceEvents.ref, classOf[ForgetHtlcInfos])
    systemA.eventStream.subscribe(aliceEvents.ref, classOf[AvailableBalanceChanged])
    systemA.eventStream.subscribe(aliceEvents.ref, classOf[LocalChannelUpdate])
    systemA.eventStream.subscribe(aliceEvents.ref, classOf[LocalChannelDown])
    systemB.eventStream.subscribe(bobEvents.ref, classOf[ForgetHtlcInfos])
    systemB.eventStream.subscribe(bobEvents.ref, classOf[AvailableBalanceChanged])
    systemB.eventStream.subscribe(bobEvents.ref, classOf[LocalChannelUpdate])
    systemB.eventStream.subscribe(bobEvents.ref, classOf[LocalChannelDown])

    val fundingTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    checkWatchConfirmed(f, fundingTx1)

    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2blockchain.expectMsgTypeHaving[WatchFundingSpent](_.txId == fundingTx1.txid)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    bob2alice.forward(alice)
    bobEvents.expectMsg(ForgetHtlcInfos(initialState.channelId, initialState.commitments.localCommitIndex))
    aliceEvents.expectNoMessage(100 millis)

    val fundingTx2 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    checkWatchConfirmed(f, fundingTx2)

    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    aliceEvents.expectMsg(ForgetHtlcInfos(initialState.channelId, initialState.commitments.remoteCommitIndex))
    aliceEvents.expectAvailableBalanceChanged(balance = 1_275_000_000.msat, capacity = 2_000_000.sat)
    bobEvents.expectAvailableBalanceChanged(balance = 650_000_000.msat, capacity = 2_000_000.sat)
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectNoMessage(100 millis)

    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectMsg(ForgetHtlcInfos(initialState.channelId, initialState.commitments.localCommitIndex))
    bobEvents.expectNoMessage(100 millis)

    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2bob.expectMsgType[SpliceLocked]
    alice2bob.forward(bob)
    aliceEvents.expectMsg(ForgetHtlcInfos(initialState.channelId, initialState.commitments.remoteCommitIndex))
    aliceEvents.expectAvailableBalanceChanged(balance = 1_775_000_000.msat, capacity = 2_500_000.sat)
    bobEvents.expectAvailableBalanceChanged(balance = 650_000_000.msat, capacity = 2_500_000.sat)
    aliceEvents.expectNoMessage(100 millis)
    bobEvents.expectNoMessage(100 millis)
  }

  test("recv CMD_ADD_HTLC with multiple commitments") { f =>
    import f._
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    val sender = TestProbe()
    alice ! CMD_ADD_HTLC(sender.ref, 500_000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, 1.0, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice ! CMD_SIGN()
    val sigA1 = alice2bob.expectMsgType[CommitSig]
    assert(sigA1.batchSize == 2)
    alice2bob.forward(bob)
    val sigA2 = alice2bob.expectMsgType[CommitSig]
    assert(sigA2.batchSize == 2)
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    val sigB1 = bob2alice.expectMsgType[CommitSig]
    assert(sigB1.batchSize == 2)
    bob2alice.forward(alice)
    val sigB2 = bob2alice.expectMsgType[CommitSig]
    assert(sigB2.batchSize == 2)
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.forall(_.localCommit.spec.htlcs.size == 1))
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.forall(_.localCommit.spec.htlcs.size == 1))
  }

  test("recv CMD_ADD_HTLC with multiple commitments and reconnect") { f =>
    import f._
    initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    val sender = TestProbe()
    alice ! CMD_ADD_HTLC(sender.ref, 500_000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, 1.0, localOrigin(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    alice ! CMD_SIGN()
    assert(alice2bob.expectMsgType[CommitSig].batchSize == 2)
    assert(alice2bob.expectMsgType[CommitSig].batchSize == 2)
    // Bob disconnects before receiving Alice's commit_sig.
    disconnect(f)
    reconnect(f, interceptFundingDeeplyBuried = false)
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    assert(alice2bob.expectMsgType[CommitSig].batchSize == 2)
    alice2bob.forward(bob)
    assert(alice2bob.expectMsgType[CommitSig].batchSize == 2)
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    assert(bob2alice.expectMsgType[CommitSig].batchSize == 2)
    bob2alice.forward(alice)
    assert(bob2alice.expectMsgType[CommitSig].batchSize == 2)
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.forall(_.localCommit.spec.htlcs.size == 1))
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.forall(_.localCommit.spec.htlcs.size == 1))
  }

  test("Funding failed before a splice is requested from our peer") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(invalidFundingAmount, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    exchangeStfu(f)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus.isInstanceOf[SpliceStatus.SpliceRequested])
    sender.expectMsg(RES_FAILURE(cmd, ChannelFundingError(channelId(alice))))
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv CMD_ADD_HTLC while a splice is requested") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    exchangeStfu(f)
    alice2bob.expectMsgType[SpliceInit]
    alice ! CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, 1.0, localOrigin(sender.ref))
    sender.expectMsgType[RES_ADD_FAILED[_]]
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv CMD_ADD_HTLC while a splice is in progress") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    exchangeStfu(f)
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]
    alice ! CMD_ADD_HTLC(sender.ref, 500000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, 1.0, localOrigin(sender.ref))
    sender.expectMsgType[RES_ADD_FAILED[_]]
    alice2bob.expectNoMessage(100 millis)
  }

  test("recv UpdateAddHtlc while a splice is in progress") { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None)
    alice ! cmd
    exchangeStfu(f)
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]

    // have to build a htlc manually because eclair would refuse to accept this command as it's forbidden
    val fakeHtlc = UpdateAddHtlc(channelId = randomBytes32(), id = 5656, amountMsat = 50000000 msat, cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), paymentHash = randomBytes32(), onionRoutingPacket = TestConstants.emptyOnionPacket, blinding_opt = None, confidence = 1.0)
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

    val spliceTx1 = initiateSplice(f, spliceOut_opt = Some(SpliceOut(50_000 sat, defaultSpliceOutScriptPubKey)))
    bob ! WatchPublishedTriggered(spliceTx1)
    bob2alice.expectMsgType[SpliceLocked] // we ignore Bob's splice_locked for the first splice

    val spliceTx2 = initiateSplice(f, spliceOut_opt = Some(SpliceOut(50_000 sat, defaultSpliceOutScriptPubKey)))
    alice ! WatchPublishedTriggered(spliceTx2)
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
    bob ! WatchPublishedTriggered(spliceTx2)
    val spliceLockedBob = bob2alice.expectMsgType[SpliceLocked]
    assert(spliceLockedBob.fundingTxId == spliceTx2.txid)
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
    exchangeStfu(f)
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)

    alice ! INPUT_DISCONNECTED
    sender.expectMsgType[RES_FAILURE[_, _]]
    awaitCond(alice.stateName == OFFLINE)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
  }

  test("disconnect (commit_sig not received)") { f =>
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

  test("disconnect (commit_sig received by alice)") { f =>
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

  test("disconnect (tx_signatures sent by bob)") { f =>
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

  test("disconnect (tx_signatures received by alice)") { f =>
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

  test("disconnect (tx_signatures received by alice, zero-conf)", Tag(ZeroConf), Tag(AnchorOutputsZeroFeeHtlcTxs)) { f =>
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
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxId == spliceTxId) // Bob doesn't receive Alice's splice_locked

    disconnect(f)
    val (channelReestablishAlice, channelReestablishBob) = reconnect(f, interceptFundingDeeplyBuried = false)
    assert(channelReestablishAlice.nextFundingTxId_opt.isEmpty)
    assert(channelReestablishBob.nextFundingTxId_opt.contains(spliceTxId))
    alice2blockchain.expectWatchFundingConfirmed(spliceTxId)
    bob2blockchain.expectWatchPublished(spliceTxId)
    bob2blockchain.expectMsgType[WatchFundingDeeplyBuried]

    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxId == spliceTx.txid)
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42), 0, spliceTx)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.size == 1)

    resolveHtlcs(f, htlcs, spliceOutFee = 0.sat)
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

  test("don't resend splice_locked when zero-conf channel confirms", Tag(ZeroConf), Tag(AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val fundingTx = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
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

    val fundingTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    checkWatchConfirmed(f, fundingTx1)

    // The first splice confirms on Bob's side.
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2blockchain.expectMsgTypeHaving[WatchFundingSpent](_.txId == fundingTx1.txid)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    bob2alice.forward(alice)

    val fundingTx2 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    checkWatchConfirmed(f, fundingTx2)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // From Alice's point of view, we now have two unconfirmed splices.

    alice2bob.ignoreMsg { case _: ChannelUpdate => true }
    bob2alice.ignoreMsg { case _: ChannelUpdate => true }

    disconnect(f)
    reconnect(f)

    // NB: channel_ready are not re-sent because the channel has already been used (for building splices).
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)

    // The first splice confirms on Alice's side.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxId == fundingTx1.txid)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]

    disconnect(f)
    reconnect(f)

    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    alice2bob.forward(bob)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    bob2alice.forward(alice)

    // The second splice confirms on Alice's side.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    assert(alice2bob.expectMsgType[SpliceLocked].fundingTxId == fundingTx2.txid)
    alice2bob.forward(bob)
    alice2blockchain.expectMsgType[WatchFundingSpent]

    disconnect(f)
    reconnect(f)

    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx2.txid)
    alice2bob.forward(bob)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // The second splice confirms on Bob's side.
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    assert(bob2alice.expectMsgType[SpliceLocked].fundingTxId == fundingTx2.txid)
    bob2blockchain.expectMsgType[WatchFundingSpent]

    // NB: we disconnect *before* transmitting the splice_locked to Alice.
    disconnect(f)
    reconnect(f)

    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx2.txid)
    alice2bob.forward(bob)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx2.txid)
    // This time alice received the splice_locked for the second splice.
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    disconnect(f)
    reconnect(f)

    alice2bob.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx2.txid)
    alice2bob.forward(bob)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx2.txid)
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

  test("force-close with multiple splices (simple)") { f =>
    import f._

    val htlcs = setupHtlcs(f)

    val fundingTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    checkWatchConfirmed(f, fundingTx1)

    // The first splice confirms on Bob's side.
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2blockchain.expectMsgTypeHaving[WatchFundingSpent](_.txId == fundingTx1.txid)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    bob2alice.forward(alice)

    val fundingTx2 = initiateSplice(f, spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    checkWatchConfirmed(f, fundingTx2)

    // From Alice's point of view, we now have two unconfirmed splices.
    alice ! CMD_FORCECLOSE(ActorRef.noSender)
    alice2bob.expectMsgType[Error]
    val commitTx2 = assertPublished(alice2blockchain, "commit-tx")
    Transaction.correctlySpends(commitTx2, Seq(fundingTx2), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    val claimMainDelayed2 = assertPublished(alice2blockchain, "local-main-delayed")
    // Alice publishes her htlc timeout transactions.
    val htlcsTxsOut = htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "htlc-timeout"))
    htlcsTxsOut.foreach(tx => Transaction.correctlySpends(tx, Seq(commitTx2), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

    val watchConfirmedCommit2 = alice2blockchain.expectWatchTxConfirmed(commitTx2.txid)
    val watchConfirmedClaimMainDelayed2 = alice2blockchain.expectWatchTxConfirmed(claimMainDelayed2.txid)
    val watchHtlcsOut = htlcs.aliceToBob.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    htlcs.bobToAlice.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])

    // The first splice transaction confirms.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2blockchain.expectMsgType[WatchFundingSpent]

    // The second splice transaction confirms.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx2)
    alice2blockchain.expectMsgType[WatchFundingSpent]

    // The commit confirms, along with Alice's 2nd-stage transactions.
    watchConfirmedCommit2.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, commitTx2)
    watchConfirmedClaimMainDelayed2.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, claimMainDelayed2)
    watchHtlcsOut.zip(htlcsTxsOut).foreach { case (watch, tx) => watch.replyTo ! WatchOutputSpentTriggered(tx) }
    htlcsTxsOut.foreach { tx =>
      alice2blockchain.expectWatchTxConfirmed(tx.txid)
      alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, tx)
    }

    // Alice publishes 3rd-stage transactions.
    htlcs.aliceToBob.foreach { _ =>
      val tx = assertPublished(alice2blockchain, "htlc-delayed")
      alice2blockchain.expectWatchTxConfirmed(tx.txid)
      alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, tx)
    }

    // Bob's htlc-timeout txs confirm.
    val remoteOutpoints = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.map(rcp => rcp.htlcTxs.filter(_._2.isEmpty).keys).toSeq.flatten
    assert(remoteOutpoints.size == htlcs.bobToAlice.size)
    remoteOutpoints.foreach { out => alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, htlcsTxsOut.head.copy(txIn = Seq(TxIn(out, Nil, 0)))) }
    alice2blockchain.expectNoMessage(100 millis)

    checkPostSpliceState(f, spliceOutFee(f, capacity = 1_900_000.sat))
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[LocalClose]))
  }

  test("force-close with multiple splices (previous active remote)", Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val htlcs = setupHtlcs(f)

    val fundingTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    checkWatchConfirmed(f, fundingTx1)

    // The first splice confirms on Bob's side.
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2blockchain.expectMsgTypeHaving[WatchFundingSpent](_.txId == fundingTx1.txid)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    bob2alice.forward(alice)

    val fundingTx2 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    checkWatchConfirmed(f, fundingTx2)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // From Alice's point of view, we now have two unconfirmed splices.
    alice ! CMD_FORCECLOSE(ActorRef.noSender)
    alice2bob.expectMsgType[Error]
    val aliceCommitTx2 = assertPublished(alice2blockchain, "commit-tx")
    assertPublished(alice2blockchain, "local-anchor")
    assertPublished(alice2blockchain, "local-main-delayed")
    val htlcsTxsOut = htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "htlc-timeout"))
    htlcsTxsOut.foreach(tx => Transaction.correctlySpends(tx, Seq(aliceCommitTx2), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    alice2blockchain.expectMsgType[WatchTxConfirmed]
    alice2blockchain.expectMsgType[WatchTxConfirmed]
    alice2blockchain.expectMsgType[WatchOutputSpent]
    htlcs.aliceToBob.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    htlcs.bobToAlice.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])

    // The first splice transaction confirms.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2blockchain.expectWatchFundingSpent(fundingTx1.txid)

    // Bob publishes his commit tx for the first splice transaction (which double-spends the second splice transaction).
    val bobCommitment1 = bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.active.find(_.fundingTxIndex == 1).get
    val bobCommitTx1 = bobCommitment1.fullySignedLocalCommitTx(bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.params, bob.underlyingActor.keyManager).tx
    Transaction.correctlySpends(bobCommitTx1, Seq(fundingTx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice ! WatchFundingSpentTriggered(bobCommitTx1)
    val watchAlternativeConfirmed = alice2blockchain.expectMsgType[WatchAlternativeCommitTxConfirmed]
    alice2blockchain.expectNoMessage(100 millis)
    // Bob's commit tx confirms.
    watchAlternativeConfirmed.replyTo ! WatchAlternativeCommitTxConfirmedTriggered(BlockHeight(400000), 42, bobCommitTx1)

    // We're back to the normal handling of remote commit.
    assertPublished(alice2blockchain, "local-anchor")
    val claimMain = assertPublished(alice2blockchain, "remote-main-delayed")
    val htlcsTxsOut1 = htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "claim-htlc-timeout"))
    htlcsTxsOut1.foreach(tx => Transaction.correctlySpends(tx, Seq(bobCommitTx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    val watchConfirmedRemoteCommit = alice2blockchain.expectWatchTxConfirmed(bobCommitTx1.txid)
    // NB: this one fires immediately, tx is already confirmed.
    watchConfirmedRemoteCommit.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, bobCommitTx1)

    // Alice's 2nd-stage transactions confirm.
    val watchConfirmedClaimMain = alice2blockchain.expectWatchTxConfirmed(claimMain.txid)
    watchConfirmedClaimMain.replyTo ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, claimMain)
    val watchHtlcsOut1 = htlcs.aliceToBob.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    htlcs.bobToAlice.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    watchHtlcsOut1.zip(htlcsTxsOut1).foreach { case (watch, tx) => watch.replyTo ! WatchOutputSpentTriggered(tx) }
    htlcsTxsOut1.foreach { tx =>
      alice2blockchain.expectWatchTxConfirmed(tx.txid)
      alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, tx)
    }

    // Bob's 2nd-stage transactions confirm.
    bobCommitment1.localCommit.htlcTxsAndRemoteSigs.foreach(txAndSig => alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, txAndSig.htlcTx.tx))
    alice2blockchain.expectNoMessage(100 millis)

    checkPostSpliceState(f, spliceOutFee = 0.sat)
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[RemoteClose]))
  }

  test("force-close with multiple splices (previous active revoked)", Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val htlcs = setupHtlcs(f)

    // pay 10_000_000 msat to bob that will be paid back to alice after the splices
    val fundingTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 10_000_000 msat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    checkWatchConfirmed(f, fundingTx1)
    // remember bob's commitment for later
    val bobCommit1 = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head

    // The first splice confirms on Bob's side.
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2blockchain.expectMsgTypeHaving[WatchFundingSpent](_.txId == fundingTx1.txid)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    bob2alice.forward(alice)

    val fundingTx2 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    checkWatchConfirmed(f, fundingTx2)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // From Alice's point of view, We now have two unconfirmed splices, both active.
    // Bob makes a payment, that applies to both commitments.
    val (preimage, add) = addHtlc(10_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    alice2relayer.expectMsgType[Relayer.RelayForward]
    fulfillHtlc(add.id, preimage, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // The first splice transaction confirms.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    alice2blockchain.expectWatchFundingSpent(fundingTx1.txid)
    // Bob publishes a revoked commitment for fundingTx1!
    val bobRevokedCommitTx = bobCommit1.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobRevokedCommitTx)
    // Alice watches bob's revoked commit tx, and force-closes with her latest commitment.
    assert(alice2blockchain.expectMsgType[WatchAlternativeCommitTxConfirmed].txId == bobRevokedCommitTx.txid)
    val aliceCommitTx2 = assertPublished(alice2blockchain, "commit-tx")
    assertPublished(alice2blockchain, "local-anchor")
    val claimMainDelayed2 = assertPublished(alice2blockchain, "local-main-delayed")
    htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "htlc-timeout"))
    alice2blockchain.expectWatchTxConfirmed(aliceCommitTx2.txid)
    alice2blockchain.expectWatchTxConfirmed(claimMainDelayed2.txid)
    alice2blockchain.expectMsgType[WatchOutputSpent]
    htlcs.aliceToBob.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    htlcs.bobToAlice.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])

    // Bob's revoked commit tx wins.
    alice ! WatchAlternativeCommitTxConfirmedTriggered(BlockHeight(400000), 42, bobRevokedCommitTx)
    // Alice reacts by punishing bob.
    val aliceClaimMain = assertPublished(alice2blockchain, "remote-main-delayed")
    val aliceMainPenalty = assertPublished(alice2blockchain, "main-penalty")
    val aliceHtlcsPenalty = htlcs.aliceToBob.map(_ => assertPublished(alice2blockchain, "htlc-penalty")) ++ htlcs.bobToAlice.map(_ => assertPublished(alice2blockchain, "htlc-penalty"))
    aliceHtlcsPenalty.foreach(tx => Transaction.correctlySpends(tx, Seq(bobRevokedCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    alice2blockchain.expectWatchTxConfirmed(bobRevokedCommitTx.txid)
    alice2blockchain.expectWatchTxConfirmed(aliceClaimMain.txid)
    assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == bobRevokedCommitTx.txid)
    aliceHtlcsPenalty.map(_ => alice2blockchain.expectMsgType[WatchOutputSpent])
    alice2blockchain.expectNoMessage(100 millis)

    // Alice's penalty txs confirm.
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, bobRevokedCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, aliceClaimMain)
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, aliceMainPenalty)
    aliceHtlcsPenalty.foreach { tx => alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, tx) }
    val settledOutgoingHtlcs = htlcs.aliceToBob.map(_ => alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc).toSet
    assert(settledOutgoingHtlcs == htlcs.aliceToBob.map(_._2).toSet)

    checkPostSpliceState(f, spliceOutFee = 0.sat)
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[RevokedClose]))
  }

  test("force-close with multiple splices (inactive remote)", Tag(ZeroConf), Tag(AnchorOutputsZeroFeeHtlcTxs)) { f =>
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
    awaitCond(wallet.asInstanceOf[SingleKeyOnChainWallet].abandoned.contains(fundingTx2.txid))

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

  test("force-close with multiple splices (inactive revoked)", Tag(ZeroConf), Tag(AnchorOutputsZeroFeeHtlcTxs)) { f =>
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
    alice2relayer.expectMsgType[Relayer.RelayForward]
    fulfillHtlc(add.id, preimage, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]]

    // alice adds an outgoing htlc that is only applied to splice 2
    val pendingOutgoingHtlc = addHtlc(15_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    bob2relayer.expectMsgType[Relayer.RelayForward]
    val htlcs1 = htlcs.copy(aliceToBob = htlcs.aliceToBob ++ Seq(pendingOutgoingHtlc))

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
    htlcs1.aliceToBob.map(_ => assertPublished(alice2blockchain, "htlc-timeout"))

    alice2blockchain.expectWatchTxConfirmed(aliceCommitTx2.txid)
    alice2blockchain.expectWatchTxConfirmed(claimMainDelayed2.txid)
    alice2blockchain.expectMsgType[WatchOutputSpent] // local-anchor
    htlcs1.aliceToBob.foreach(_ => assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == aliceCommitTx2.txid))
    htlcs1.bobToAlice.foreach(_ => assert(alice2blockchain.expectMsgType[WatchOutputSpent].txId == aliceCommitTx2.txid))
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
    awaitCond(wallet.asInstanceOf[SingleKeyOnChainWallet].abandoned.contains(fundingTx2.txid))
    alice2blockchain.expectNoMessage(100 millis)

    // all penalty txs confirm
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, bobRevokedCommitTx)
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, aliceClaimMain)
    alice ! WatchOutputSpentTriggered(aliceMainPenalty)
    alice2blockchain.expectWatchTxConfirmed(aliceMainPenalty.txid)
    alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, aliceMainPenalty)
    aliceHtlcsPenalty.foreach { tx => alice ! WatchTxConfirmedTriggered(BlockHeight(400000), 42, tx) }
    val settledOutgoingHtlcs = htlcs1.aliceToBob.map(_ => alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.OnChainFail]].htlc).toSet
    assert(settledOutgoingHtlcs == htlcs1.aliceToBob.map(_._2).toSet)

    // alice's final commitment includes the initial htlcs, but not bob's payment
    checkPostSpliceState(f, spliceOutFee = 0 sat)

    // done
    awaitCond(alice.stateName == CLOSED)
    assert(Helpers.Closing.isClosed(alice.stateData.asInstanceOf[DATA_CLOSING], None).exists(_.isInstanceOf[RevokedClose]))
  }

  test("put back watches after restart") { f =>
    import f._

    val fundingTx0 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localFundingStatus.signedTx_opt.get

    val fundingTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 10_000_000 msat)), spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))
    checkWatchConfirmed(f, fundingTx1)

    // The first splice confirms on Bob's side.
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2blockchain.expectMsgTypeHaving[WatchFundingSpent](_.txId == fundingTx1.txid)
    bob2alice.expectMsgTypeHaving[SpliceLocked](_.fundingTxId == fundingTx1.txid)
    bob2alice.forward(alice)

    val fundingTx2 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)))
    checkWatchConfirmed(f, fundingTx2)

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
    bob2blockchain.expectWatchFundingSpent(fundingTx1.txid)
    bob2blockchain.expectWatchFundingSpent(fundingTx0.txid)
    bob2blockchain.expectNoMessage(100 millis)
  }

  test("put back watches after restart (inactive)", Tag(ZeroConf), Tag(AnchorOutputsZeroFeeHtlcTxs)) { f =>
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

  test("recv CMD_SPLICE (splice-in + splice-out) with pre and post splice htlcs") { f =>
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

  test("recv CMD_SPLICE (splice-in + splice-out) with pending htlcs, resolved after splice locked", Tag(AnchorOutputsZeroFeeHtlcTxs)) { f =>
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

  test("recv multiple CMD_SPLICE (splice-in, splice-out)") { f =>
    import f._

    // Add some HTLCs before splicing in and out.
    val htlcs = setupHtlcs(f)

    // Alice splices some funds in.
    val fundingTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(500_000 sat)))
    // The first splice confirms on Bob's side (necessary to allow the second splice transaction).
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx1)
    bob2alice.expectMsgType[SpliceLocked]
    // Alice splices some funds out.
    initiateSplice(f, spliceOut_opt = Some(SpliceOut(100_000 sat, defaultSpliceOutScriptPubKey)))

    // The HTLCs complete while multiple commitments are active.
    resolveHtlcs(f, htlcs, spliceOutFee = spliceOutFee(f, capacity = 1_900_000.sat))
  }

  test("recv CMD_BUMP_FUNDING_FEE with pre and post rbf htlcs") { f =>
    import f._

    // We create two unconfirmed splice transactions spending each other:
    // +-----------+     +-----------+     +-----------+
    // | fundingTx |---->| spliceTx1 |---->| spliceTx2 |
    // +-----------+     +-----------+     +-----------+
    val spliceTx1 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(200_000 sat)))
    checkWatchConfirmed(f, spliceTx1)
    val spliceCommitment1 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest
    assert(spliceCommitment1.fundingTxId == spliceTx1.txid)

    // The first splice confirms on Bob's side (necessary to allow the second splice transaction).
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, spliceTx1)
    bob2blockchain.expectMsgTypeHaving[WatchFundingSpent](_.txId == spliceTx1.txid)
    bob2alice.expectMsgType[SpliceLocked]
    bob2alice.forward(alice)

    val spliceTx2 = initiateSplice(f, spliceIn_opt = Some(SpliceIn(100_000 sat)))
    checkWatchConfirmed(f, spliceTx2)
    val spliceCommitment2 = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest
    assert(spliceCommitment2.fundingTxId == spliceTx2.txid)
    assert(spliceCommitment2.localCommit.spec.toLocal == spliceCommitment1.localCommit.spec.toLocal + 100_000.sat)

    // Bob sends an HTLC while Alice starts an RBF on spliceTx2.
    addHtlc(25_000_000 msat, bob, alice, bob2alice, alice2bob)
    val rbfTx1 = {
      val probe = TestProbe()
      alice ! CMD_BUMP_FUNDING_FEE(probe.ref, FeeratePerKw(15_000 sat), 100_000 sat, 250)
      alice2bob.expectMsgType[Stfu]
      alice2bob.forward(bob)
      // Bob is waiting to sign its outgoing HTLC before sending stfu.
      bob2alice.expectNoMessage(100 millis)
      bob ! CMD_SIGN()
      (0 until 3).foreach { _ =>
        bob2alice.expectMsgType[CommitSig]
        bob2alice.forward(alice)
      }
      alice2bob.expectMsgType[RevokeAndAck]
      alice2bob.forward(bob)
      (0 until 3).foreach { _ =>
        alice2bob.expectMsgType[CommitSig]
        alice2bob.forward(bob)
      }
      bob2alice.expectMsgType[RevokeAndAck]
      bob2alice.forward(alice)
      bob2alice.expectMsgType[Stfu]
      bob2alice.forward(alice)

      alice2bob.expectMsgType[TxInitRbf]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxAckRbf]
      bob2alice.forward(alice)

      // Alice adds three inputs: the shared input, the previous splice input, and an RBF input.
      (0 until 3).foreach { _ =>
        alice2bob.expectMsgType[TxAddInput]
        alice2bob.forward(bob)
        bob2alice.expectMsgType[TxComplete]
        bob2alice.forward(alice)
      }
      // Alice adds two outputs: the shared output and a change output.
      (0 until 2).foreach { _ =>
        alice2bob.expectMsgType[TxAddOutput]
        alice2bob.forward(bob)
        bob2alice.expectMsgType[TxComplete]
        bob2alice.forward(alice)
      }
      // Alice doesn't have anything more to add to the transaction.
      alice2bob.expectMsgType[TxComplete]
      alice2bob.forward(bob)
      val rbfTx = exchangeSpliceSigs(f, probe)
      assert(rbfTx.lockTime == 250)
      spliceTx2.txIn.foreach(txIn => assert(rbfTx.txIn.map(_.outPoint).contains(txIn.outPoint)))
      rbfTx
    }

    // Alice and Bob exchange HTLCs while the splice transactions are still unconfirmed.
    addHtlc(10_000_000 msat, alice, bob, alice2bob, bob2alice)
    addHtlc(5_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)

    // Alice initiates another RBF attempt:
    // +-----------+     +-----------+     +-----------+
    // | fundingTx |---->| spliceTx1 |---->| spliceTx2 |
    // +-----------+     +-----------+  |  +-----------+
    //                                  |  +-----------+
    //                                  +->|  rbfTx1   |
    //                                  |  +-----------+
    //                                  |  +-----------+
    //                                  +->|  rbfTx2   |
    //                                     +-----------+
    val rbfTx2 = initiateRbf(f, FeeratePerKw(20_000 sat), sInputsCount = 3, sOutputsCount = 1)
    assert(rbfTx2.txIn.size == rbfTx1.txIn.size + 1)
    rbfTx1.txIn.foreach(txIn => assert(rbfTx2.txIn.map(_.outPoint).contains(txIn.outPoint)))

    // The balance is the same in all RBF attempts.
    inside(alice.stateData.asInstanceOf[DATA_NORMAL]) { data =>
      val commitments = data.commitments.active.filter(_.fundingTxIndex == spliceCommitment2.commitment.fundingTxIndex)
      assert(commitments.map(_.fundingTxId) == Seq(rbfTx2, rbfTx1, spliceTx2).map(_.txid))
      assert(commitments.map(_.localCommit.spec.toLocal).toSet.size == 1)
      assert(commitments.map(_.localCommit.spec.toRemote).toSet.size == 1)
    }

    // The first RBF attempt is confirmed.
    confirmSpliceTx(f, rbfTx1)
    inside(alice.stateData.asInstanceOf[DATA_NORMAL]) { data =>
      assert(data.commitments.active.size == 1)
      assert(data.commitments.latest.fundingTxId == rbfTx1.txid)
      assert(data.commitments.latest.localCommit.spec.toLocal == spliceCommitment1.localCommit.spec.toLocal + 100_000.sat - 10_000.sat)
      assert(data.commitments.latest.localCommit.spec.toRemote == spliceCommitment1.localCommit.spec.toRemote - 25_000.sat - 5_000.sat)
      assert(data.commitments.latest.localCommit.spec.htlcs.collect(incoming).map(_.amountMsat) == Set(5_000_000 msat, 25_000_000 msat))
      assert(data.commitments.latest.localCommit.spec.htlcs.collect(outgoing).map(_.amountMsat) == Set(10_000_000 msat))
    }

    // The first splice transaction confirms: this was already implied by the RBF attempt confirming.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, spliceTx1)
    inside(alice.stateData.asInstanceOf[DATA_NORMAL]) { data =>
      assert(data.commitments.active.size == 1)
      assert(data.commitments.latest.fundingTxId == rbfTx1.txid)
    }
  }

  test("recv invalid htlc signatures during splice-in") { f =>
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
