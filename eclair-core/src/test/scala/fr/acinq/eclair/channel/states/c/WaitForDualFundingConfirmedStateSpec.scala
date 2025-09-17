/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.channel.states.c

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestFSMRef, TestProbe}
import com.softwaremill.quicklens.{ModifyPimp, QuicklensAt}
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, SingleKeyOnChainWallet}
import fr.acinq.eclair.channel.LocalFundingStatus.DualFundedUnconfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.ProcessCurrentBlockHeight
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.FullySignedSharedTransaction
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.{FakeTxPublisherFactory, PimpTestFSM}
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.testutils.PimpTestProbe.convert
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, TestConstants, TestKitBaseClass, ToMilliSatoshiConversion}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

class WaitForDualFundingConfirmedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  val bothPushAmount = "both_push_amount"
  val noFundingContribution = "no_funding_contribution"
  val liquidityPurchase = "liquidity_purchase"

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, aliceListener: TestProbe, bobListener: TestProbe, wallet: SingleKeyOnChainWallet)

  override def withFixture(test: OneArgTest): Outcome = {
    val wallet = new SingleKeyOnChainWallet()
    val setup = init(wallet_opt = Some(wallet), tags = test.tags)
    import setup._

    val aliceListener = TestProbe()
    alice.underlying.system.eventStream.subscribe(aliceListener.ref, classOf[TransactionPublished])
    alice.underlying.system.eventStream.subscribe(aliceListener.ref, classOf[TransactionConfirmed])
    alice.underlying.system.eventStream.subscribe(aliceListener.ref, classOf[ShortChannelIdAssigned])
    alice.underlying.system.eventStream.subscribe(aliceListener.ref, classOf[ChannelAborted])
    alice.underlying.system.eventStream.subscribe(aliceListener.ref, classOf[ChannelClosed])
    val bobListener = TestProbe()
    bob.underlying.system.eventStream.subscribe(bobListener.ref, classOf[TransactionPublished])
    bob.underlying.system.eventStream.subscribe(bobListener.ref, classOf[TransactionConfirmed])
    bob.underlying.system.eventStream.subscribe(bobListener.ref, classOf[ShortChannelIdAssigned])
    bob.underlying.system.eventStream.subscribe(bobListener.ref, classOf[ChannelAborted])
    bob.underlying.system.eventStream.subscribe(bobListener.ref, classOf[ChannelClosed])

    val channelParams = computeChannelParams(setup, test.tags)
    val (requestFunding_opt, bobContribution) = if (test.tags.contains(noFundingContribution)) {
      (None, None)
    } else if (test.tags.contains(liquidityPurchase)) {
      val requestFunding = LiquidityAds.RequestFunding(TestConstants.nonInitiatorFundingSatoshis, TestConstants.defaultLiquidityRates.fundingRates.head, LiquidityAds.PaymentDetails.FromChannelBalance)
      val addFunding = LiquidityAds.AddFunding(TestConstants.nonInitiatorFundingSatoshis, Some(TestConstants.defaultLiquidityRates))
      (Some(requestFunding), Some(addFunding))
    } else {
      val addFunding = LiquidityAds.AddFunding(TestConstants.nonInitiatorFundingSatoshis, None)
      (None, Some(addFunding))
    }
    val (initiatorPushAmount, nonInitiatorPushAmount) = if (test.tags.contains(bothPushAmount)) (Some(TestConstants.initiatorPushAmount), Some(TestConstants.nonInitiatorPushAmount)) else (None, None)
    within(30 seconds) {
      alice ! channelParams.initChannelAlice(TestConstants.fundingSatoshis, dualFunded = true, pushAmount_opt = initiatorPushAmount, requestFunding_opt = requestFunding_opt)
      bob ! channelParams.initChannelBob(bobContribution, dualFunded = true, pushAmount_opt = nonInitiatorPushAmount)
      alice2blockchain.expectMsgType[SetChannelId] // temporary channel id
      bob2blockchain.expectMsgType[SetChannelId] // temporary channel id
      alice2bob.expectMsgType[OpenDualFundedChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptDualFundedChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[SetChannelId] // final channel id
      bob2blockchain.expectMsgType[SetChannelId] // final channel id

      alice2bob.expectMsgType[TxAddInput]
      alice2bob.forward(bob)
      bobContribution match {
        case Some(_) => bob2alice.expectMsgType[TxAddInput]
        case None => bob2alice.expectMsgType[TxComplete]
      }
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxAddOutput]
      alice2bob.forward(bob)
      bobContribution match {
        case Some(_) => bob2alice.expectMsgType[TxAddOutput]
        case None => bob2alice.expectMsgType[TxComplete]
      }
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxAddOutput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxComplete]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxComplete]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxSignatures]
      bob2alice.forward(alice)
      // Alice publishes the funding tx.
      val fundingTx = aliceListener.expectMsgType[TransactionPublished].tx
      alice2bob.expectMsgType[TxSignatures]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
      // Bob publishes the funding tx.
      assert(bobListener.expectMsgType[TransactionPublished].tx.txid == fundingTx.txid)
      if (test.tags.contains(ChannelStateTestsTags.ZeroConf)) {
        assert(alice2blockchain.expectMsgType[WatchPublished].txId == fundingTx.txid)
        assert(bob2blockchain.expectMsgType[WatchPublished].txId == fundingTx.txid)
      } else {
        assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTx.txid)
        assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTx.txid)
      }
      if (!test.tags.contains(noFundingContribution)) {
        // Alice pays fees for the liquidity she bought, and push amounts are correctly transferred.
        val liquidityFees = if (test.tags.contains(liquidityPurchase)) {
          TestConstants.defaultLiquidityRates.fundingRates.head.fees(TestConstants.feeratePerKw, TestConstants.nonInitiatorFundingSatoshis, TestConstants.nonInitiatorFundingSatoshis, isChannelCreation = true)
        } else {
          LiquidityAds.Fees(0 sat, 0 sat)
        }
        val bobReserve = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.remoteChannelReserve
        val expectedBalanceBob = bobContribution.map(_.fundingAmount).getOrElse(0 sat) + liquidityFees.total + initiatorPushAmount.getOrElse(0 msat) - nonInitiatorPushAmount.getOrElse(0 msat) - bobReserve
        assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.availableBalanceForSend == expectedBalanceBob)
      }
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, aliceListener, bobListener, wallet)))
    }
  }

  test("recv TxSignatures (duplicate)", Tag(ChannelStateTestsTags.DualFunding), Tag(liquidityPurchase)) { f =>
    import f._

    val aliceSigs = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.localSigs
    alice2bob.forward(bob, aliceSigs)
    bob2alice.expectNoMessage(100 millis)

    val bobSigs = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.localSigs
    bob2alice.forward(alice, bobSigs)
    alice2bob.expectNoMessage(100 millis)

    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    assert(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv TxSignatures (duplicate, rbf attempt)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val aliceSigs = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.localSigs
    val bobSigs = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.localSigs
    val rbfTx = testBumpFundingFees(f)

    alice2bob.forward(bob, aliceSigs)
    alice2bob.forward(bob, rbfTx.localSigs)
    bob2alice.expectNoMessage(100 millis)

    bob2alice.forward(alice, bobSigs)
    bob2alice.forward(alice, rbfTx.remoteSigs)
    alice2bob.expectNoMessage(100 millis)

    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    assert(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv WatchPublishedTriggered (initiator)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.ScidAlias), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    alice ! WatchPublishedTriggered(fundingTx)
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTx.txid)
    alice2blockchain.expectNoMessage(100 millis) // we don't set WatchFundingSpent
    alice2bob.expectMsgType[ChannelReady]
    assert(aliceListener.expectMsgType[ShortChannelIdAssigned].announcement_opt.isEmpty)
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv WatchPublishedTriggered (non-initiator)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.ScidAlias), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    bob ! WatchPublishedTriggered(fundingTx)
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTx.txid)
    bob2blockchain.expectNoMessage(100 millis) // we don't set WatchFundingSpent
    bob2alice.expectMsgType[ChannelReady]
    assert(bobListener.expectMsgType[ShortChannelIdAssigned].announcement_opt.isEmpty)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv WatchPublishedTriggered (offline)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.ScidAlias), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    alice ! WatchPublishedTriggered(fundingTx)
    // Alice processes the event while offline and changes state
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTx.txid)
    alice2blockchain.expectNoMessage(100 millis) // we don't set WatchFundingSpent
    alice2bob.expectNoMessage(100 millis)
    assert(aliceListener.expectMsgType[ShortChannelIdAssigned].announcement_opt.isEmpty)
    awaitCond(alice.stateData.isInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY])
    assert(alice.stateName == OFFLINE)
  }

  test("recv WatchFundingConfirmedTriggered (initiator)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    alice2bob.expectMsgType[ChannelReady]
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(aliceListener.expectMsgType[ShortChannelIdAssigned].announcement_opt.isEmpty)
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv WatchFundingConfirmedTriggered (non-initiator)", Tag(ChannelStateTestsTags.DualFunding), Tag(liquidityPurchase)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(bob2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    bob2alice.expectMsgType[ChannelReady]
    assert(bobListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(bobListener.expectMsgType[ShortChannelIdAssigned].announcement_opt.isEmpty)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv WatchFundingConfirmedTriggered (offline)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    // Alice processes the event while offline and changes state
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    alice2blockchain.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(aliceListener.expectMsgType[ShortChannelIdAssigned].announcement_opt.isEmpty)
    awaitCond(alice.stateData.isInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY])
    assert(alice.stateName == OFFLINE)
  }

  test("recv WatchFundingConfirmedTriggered (rbf in progress)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val probe = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    alice ! CMD_BUMP_FUNDING_FEE(probe.ref, TestConstants.feeratePerKw * 1.1, fundingFeeBudget = 100_000.sat, 0, None)
    alice2bob.expectMsgType[TxInitRbf]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAckRbf]

    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    alice2bob.expectMsgType[TxAbort]
    alice2bob.expectMsgType[ChannelReady]
    assert(probe.expectMsgType[CommandFailure[_, _]].t == InvalidRbfTxConfirmed(channelId(alice)))
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv WatchFundingConfirmedTriggered after restart", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val bobData = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val fundingTx = aliceData.latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val (alice2, bob2) = restartNodes(f, aliceData, bobData)
    reconnectNodes(f, alice2, bob2)

    alice2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    alice2bob.expectMsgType[ChannelReady]
    awaitCond(alice2.stateName == WAIT_FOR_DUAL_FUNDING_READY)

    bob2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(bobListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(bob2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    bob2alice.expectMsgType[ChannelReady]
    awaitCond(bob2.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  def testUnusedInputsUnlocked(wallet: SingleKeyOnChainWallet, unusedTxs: Seq[FullySignedSharedTransaction]): Unit = {
    val inputs = unusedTxs.flatMap(sharedTx => sharedTx.tx.localInputs ++ sharedTx.tx.sharedInput_opt.toSeq).distinctBy(_.outPoint.txid).map(i => i.outPoint)
    awaitCond {
      val rollback = wallet.rolledback.flatMap(_.txIn.map(_.outPoint))
      inputs.toSet == rollback.toSet
    }
  }

  test("recv WatchFundingConfirmedTriggered after restart (previous tx)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val fundingTx1 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    val fundingTx2 = testBumpFundingFees(f)
    assert(fundingTx1.signedTx.txid != fundingTx2.signedTx.txid)

    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val bobData = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val (alice2, bob2) = restartNodes(f, aliceData, bobData)
    reconnectNodes(f, alice2, bob2)

    alice2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1.signedTx)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx1.signedTx)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx1.signedTx.txid)
    alice2bob.expectMsgType[ChannelReady]
    awaitCond(alice2.stateName == WAIT_FOR_DUAL_FUNDING_READY)
    assert(alice2.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY].commitments.active.size == 1)
    assert(alice2.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY].commitments.inactive.isEmpty)
    assert(alice2.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY].commitments.latest.fundingTxId == fundingTx1.signedTx.txid)
    testUnusedInputsUnlocked(wallet, Seq(fundingTx2))

    bob2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1.signedTx)
    assert(bobListener.expectMsgType[TransactionConfirmed].tx == fundingTx1.signedTx)
    assert(bob2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx1.signedTx.txid)
    bob2alice.expectMsgType[ChannelReady]
    awaitCond(bob2.stateName == WAIT_FOR_DUAL_FUNDING_READY)
    assert(bob2.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY].commitments.active.size == 1)
    assert(bob2.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY].commitments.inactive.isEmpty)
    assert(bob2.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY].commitments.latest.fundingTxId == fundingTx1.signedTx.txid)
  }

  def testBumpFundingFees(f: FixtureParam, feerate_opt: Option[FeeratePerKw] = None, requestFunding_opt: Option[LiquidityAds.RequestFunding] = None): FullySignedSharedTransaction = {
    testBumpFundingFees(f, f.alice, f.bob, f.alice2bob, f.bob2alice, feerate_opt, requestFunding_opt)
  }

  def testBumpFundingFees(f: FixtureParam, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, feerate_opt: Option[FeeratePerKw], requestFunding_opt: Option[LiquidityAds.RequestFunding]): FullySignedSharedTransaction = {
    import f._

    val probe = TestProbe()
    val currentFundingParams = s.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.fundingParams
    val currentFundingTx = s.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    val previousFundingTxs = s.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs
    s ! CMD_BUMP_FUNDING_FEE(probe.ref, feerate_opt.getOrElse(currentFundingTx.feerate * 1.1), fundingFeeBudget = 100_000.sat, 0, requestFunding_opt)
    assert(s2r.expectMsgType[TxInitRbf].fundingContribution == currentFundingParams.localContribution)
    s2r.forward(r)
    val txAckRbf = r2s.expectMsgType[TxAckRbf]
    assert(txAckRbf.fundingContribution == requestFunding_opt.map(_.requestedAmount).getOrElse(currentFundingParams.remoteContribution))
    requestFunding_opt.foreach(_ => assert(txAckRbf.willFund_opt.nonEmpty))
    r2s.forward(s)

    // Alice and Bob build a new version of the funding transaction, with one new input every time.
    val inputCount = previousFundingTxs.length + 2
    (1 to inputCount).foreach(_ => {
      s2r.expectMsgType[TxAddInput]
      s2r.forward(r)
      r2s.expectMsgType[TxAddInput]
      r2s.forward(s)
    })
    s2r.expectMsgType[TxAddOutput]
    s2r.forward(r)
    r2s.expectMsgType[TxAddOutput]
    r2s.forward(s)
    s2r.expectMsgType[TxAddOutput]
    s2r.forward(r)
    r2s.expectMsgType[TxComplete]
    r2s.forward(s)
    s2r.expectMsgType[TxComplete]
    s2r.forward(r)
    r2s.expectMsgType[CommitSig]
    r2s.forward(s)
    s2r.expectMsgType[CommitSig]
    s2r.forward(r)
    if (currentFundingParams.localContribution < currentFundingParams.remoteContribution) {
      s2r.expectMsgType[TxSignatures]
      s2r.forward(r)
      r2s.expectMsgType[TxSignatures]
      r2s.forward(s)
    } else {
      r2s.expectMsgType[TxSignatures]
      r2s.forward(s)
      s2r.expectMsgType[TxSignatures]
      s2r.forward(r)
    }

    probe.expectMsgType[RES_BUMP_FUNDING_FEE]

    val nextFundingTx = s.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(aliceListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    assert(bobListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    assert(currentFundingTx.signedTx.txid != nextFundingTx.signedTx.txid)
    assert(currentFundingTx.feerate < nextFundingTx.feerate)
    // The new transaction double-spends previous inputs.
    currentFundingTx.signedTx.txIn.map(_.outPoint).foreach(o => assert(nextFundingTx.signedTx.txIn.exists(_.outPoint == o)))
    assert(s.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.length == previousFundingTxs.length + 1)
    assert(s.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.head.sharedTx == currentFundingTx)
    nextFundingTx
  }

  test("recv CMD_BUMP_FUNDING_FEE", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.UnlimitedRbfAttempts)) { f =>
    import f._

    // Bob contributed to the funding transaction.
    val balanceBob1 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.spec.toLocal
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.isEmpty)
    assert(balanceBob1 == TestConstants.nonInitiatorFundingSatoshis.toMilliSatoshi)

    // Alice RBFs the funding transaction: Bob keeps contributing the same amount.
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    val feerate2 = FeeratePerKw(12_500 sat)
    testBumpFundingFees(f, Some(feerate2))
    val balanceBob2 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.spec.toLocal
    assert(balanceBob2 == TestConstants.nonInitiatorFundingSatoshis.toMilliSatoshi)
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(FeeratePerKw(12_500 sat) <= fundingTx2.feerate && fundingTx2.feerate < FeeratePerKw(13_500 sat))

    // Alice RBFs the funding transaction again: Bob keeps contributing the same amount.
    val feerate3 = FeeratePerKw(15_000 sat)
    testBumpFundingFees(f, Some(feerate3))
    val balanceBob3 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.spec.toLocal
    assert(balanceBob3 == TestConstants.nonInitiatorFundingSatoshis.toMilliSatoshi)
    val fundingTx3 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(FeeratePerKw(15_000 sat) <= fundingTx3.feerate && fundingTx3.feerate < FeeratePerKw(15_700 sat))
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.length == 2)

    // Bob RBFs the funding transaction: Alice keeps contributing the same amount.
    val feerate4 = FeeratePerKw(20_000 sat)
    testBumpFundingFees(f, bob, alice, bob2alice, alice2bob, Some(feerate4), requestFunding_opt = None)
    val balanceBob4 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.spec.toLocal
    assert(balanceBob4 == TestConstants.nonInitiatorFundingSatoshis.toMilliSatoshi)
    val balanceAlice4 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.spec.toLocal
    assert(balanceAlice4 == TestConstants.fundingSatoshis.toMilliSatoshi)
    val fundingTx4 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(FeeratePerKw(20_000 sat) <= fundingTx4.feerate && fundingTx4.feerate < FeeratePerKw(20_500 sat))
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.length == 3)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.length == 3)

    // The initial funding transaction confirms: we rollback unused inputs.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1.signedTx)
    testUnusedInputsUnlocked(wallet, Seq(fundingTx2, fundingTx3, fundingTx4))
  }

  test("recv CMD_BUMP_FUNDING_FEE (liquidity ads)", Tag(ChannelStateTestsTags.DualFunding), Tag(liquidityPurchase)) { f =>
    import f._

    // Alice initially purchased some inbound liquidity.
    val remoteFunding1 = TestConstants.nonInitiatorFundingSatoshis
    val feerate1 = TestConstants.feeratePerKw
    val liquidityFee1 = TestConstants.defaultLiquidityRates.fundingRates.head.fees(feerate1, remoteFunding1, remoteFunding1, isChannelCreation = true)
    val balanceBob1 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.spec.toLocal
    assert(balanceBob1 == (remoteFunding1 + liquidityFee1.total).toMilliSatoshi)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.isEmpty)

    // Alice RBFs the funding transaction and purchases the same amount of liquidity.
    val feerate2 = FeeratePerKw(12_500 sat)
    val requestFunding2 = LiquidityAds.RequestFunding(remoteFunding1, TestConstants.defaultLiquidityRates.fundingRates.head, LiquidityAds.PaymentDetails.FromChannelBalance)
    val liquidityFee2 = TestConstants.defaultLiquidityRates.fundingRates.head.fees(feerate2, remoteFunding1, remoteFunding1, isChannelCreation = true)
    testBumpFundingFees(f, Some(feerate2), Some(requestFunding2))
    val balanceBob2 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.spec.toLocal
    assert(liquidityFee1.total < liquidityFee2.total)
    assert(balanceBob2 == (remoteFunding1 + liquidityFee2.total).toMilliSatoshi)
    val fundingTx2 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(FeeratePerKw(12_500 sat) <= fundingTx2.feerate && fundingTx2.feerate < FeeratePerKw(13_500 sat))

    // Alice RBFs again and purchases more inbound liquidity.
    val remoteFunding3 = 750_000.sat
    val feerate3 = FeeratePerKw(15_000 sat)
    val requestFunding3 = LiquidityAds.RequestFunding(remoteFunding3, TestConstants.defaultLiquidityRates.fundingRates.head, LiquidityAds.PaymentDetails.FromChannelBalance)
    val liquidityFee3 = TestConstants.defaultLiquidityRates.fundingRates.head.fees(feerate3, remoteFunding3, remoteFunding3, isChannelCreation = true)
    testBumpFundingFees(f, Some(feerate3), Some(requestFunding3))
    val balanceBob3 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.spec.toLocal
    assert(balanceBob3 == (remoteFunding3 + liquidityFee3.total).toMilliSatoshi)
    val fundingTx3 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(FeeratePerKw(15_000 sat) <= fundingTx3.feerate && fundingTx3.feerate < FeeratePerKw(15_700 sat))

    // Alice RBFs again and purchases less inbound liquidity.
    val remoteFunding4 = 250_000.sat
    val feerate4 = FeeratePerKw(17_500 sat)
    val requestFunding4 = LiquidityAds.RequestFunding(remoteFunding4, TestConstants.defaultLiquidityRates.fundingRates.head, LiquidityAds.PaymentDetails.FromChannelBalance)
    val liquidityFee4 = TestConstants.defaultLiquidityRates.fundingRates.head.fees(feerate4, remoteFunding4, remoteFunding4, isChannelCreation = true)
    testBumpFundingFees(f, Some(feerate4), Some(requestFunding4))
    val balanceBob4 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.spec.toLocal
    assert(balanceBob4 == (remoteFunding4 + liquidityFee4.total).toMilliSatoshi)
    val fundingTx4 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localFundingStatus.asInstanceOf[DualFundedUnconfirmedFundingTx].sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(FeeratePerKw(17_500 sat) <= fundingTx4.feerate && fundingTx4.feerate < FeeratePerKw(18_200 sat))

    // Alice tries to cancel the liquidity purchase.
    val sender = TestProbe()
    alice ! CMD_BUMP_FUNDING_FEE(sender.ref, FeeratePerKw(20_000 sat), 100_000 sat, 0, requestFunding_opt = None)
    assert(sender.expectMsgType[RES_FAILURE[_, ChannelException]].t.isInstanceOf[InvalidRbfMissingLiquidityPurchase])
    alice2bob.forward(bob, TxInitRbf(alice.stateData.channelId, 0, FeeratePerKw(20_000 sat), TestConstants.fundingSatoshis, requireConfirmedInputs = false, requestFunding_opt = None))
    assert(bob2alice.expectMsgType[TxAbort].toAscii.contains("the previous attempt contained a liquidity purchase"))
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob)

    // Bob tries to RBF: this is disabled because it would override Alice's liquidity purchase.
    bob ! CMD_BUMP_FUNDING_FEE(sender.ref, FeeratePerKw(20_000 sat), 100_000 sat, 0, requestFunding_opt = None)
    assert(sender.expectMsgType[RES_FAILURE[_, ChannelException]].t.isInstanceOf[InvalidRbfOverridesLiquidityPurchase])
  }

  test("recv CMD_BUMP_FUNDING_FEE (aborted)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val probe = TestProbe()
    val fundingTxAlice = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    val fundingTxBob = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    alice ! CMD_BUMP_FUNDING_FEE(probe.ref, TestConstants.feeratePerKw * 1.1, fundingFeeBudget = 100_000.sat, 0, None)
    assert(alice2bob.expectMsgType[TxInitRbf].fundingContribution == TestConstants.fundingSatoshis)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[TxAckRbf].fundingContribution == TestConstants.nonInitiatorFundingSatoshis)
    bob2alice.forward(alice)

    // Alice and Bob build a new version of the funding transaction.
    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    val bobInput = bob2alice.expectMsgType[TxAddInput]
    bob2alice.forward(alice, bobInput.copy(previousTxOutput = 42))
    alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob)
    awaitAssert(assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.RbfAborted))
    bob2alice.expectMsgType[TxAbort] // bob acks alice's tx_abort
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)

    // Alice and Bob clear RBF data from their state.
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx == fundingTxAlice)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.isEmpty)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx == fundingTxBob)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.isEmpty)
  }

  test("recv CMD_BUMP_FUNDING_FEE (over fee budget)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val probe = TestProbe()
    val fundingTxAlice = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    val fundingTxBob = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    alice ! CMD_BUMP_FUNDING_FEE(probe.ref, TestConstants.feeratePerKw * 1.1, fundingFeeBudget = 100.sat, 0, None)
    assert(alice2bob.expectMsgType[TxInitRbf].fundingContribution == TestConstants.fundingSatoshis)
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[TxAckRbf].fundingContribution == TestConstants.nonInitiatorFundingSatoshis)
    bob2alice.forward(alice)

    // Alice aborts the funding transaction, because it exceeds its fee budget.
    assert(alice2bob.expectMsgType[TxAbort].toAscii == ChannelFundingError(channelId(alice)).getMessage)
    alice2bob.forward(bob)
    awaitAssert(assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.RbfAborted))
    bob2alice.expectMsgType[TxAbort] // bob acks alice's tx_abort
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)

    // Alice and Bob clear RBF data from their state.
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx == fundingTxAlice)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.isEmpty)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx == fundingTxBob)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.isEmpty)
  }

  test("recv TxInitRbf (exhausted RBF attempts)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.RejectRbfAttempts)) { f =>
    import f._

    bob ! TxInitRbf(channelId(bob), 0, TestConstants.feeratePerKw * 1.25, 500_000 sat, requireConfirmedInputs = false, None)
    assert(bob2alice.expectMsgType[TxAbort].toAscii == InvalidRbfAttemptsExhausted(channelId(bob), 0).getMessage)
    assert(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv TxInitRbf (RBF attempt too soon)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.DelayRbfAttempts)) { f =>
    import f._

    val currentBlockHeight = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.createdAt
    bob ! TxInitRbf(channelId(bob), 0, TestConstants.feeratePerKw * 1.25, 500_000 sat, requireConfirmedInputs = false, None)
    assert(bob2alice.expectMsgType[TxAbort].toAscii == InvalidRbfAttemptTooSoon(channelId(bob), currentBlockHeight, currentBlockHeight + 1).getMessage)
    assert(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv TxInitRbf (invalid push amount)", Tag(ChannelStateTestsTags.DualFunding), Tag(bothPushAmount)) { f =>
    import f._

    val fundingBelowPushAmount = 199_000.sat
    bob ! TxInitRbf(channelId(bob), 0, TestConstants.feeratePerKw * 1.25, fundingBelowPushAmount, requireConfirmedInputs = false, None)
    assert(bob2alice.expectMsgType[TxAbort].toAscii == InvalidPushAmount(channelId(bob), TestConstants.initiatorPushAmount, fundingBelowPushAmount.toMilliSatoshi).getMessage)
    assert(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv TxAckRbf (invalid push amount)", Tag(ChannelStateTestsTags.DualFunding), Tag(bothPushAmount)) { f =>
    import f._

    alice ! CMD_BUMP_FUNDING_FEE(TestProbe().ref, TestConstants.feeratePerKw * 1.25, fundingFeeBudget = 100_000.sat, 0, None)
    alice2bob.expectMsgType[TxInitRbf]
    val fundingBelowPushAmount = 99_000.sat
    alice ! TxAckRbf(channelId(alice), fundingBelowPushAmount, requireConfirmedInputs = false, None)
    assert(alice2bob.expectMsgType[TxAbort].toAscii == InvalidPushAmount(channelId(alice), TestConstants.nonInitiatorPushAmount, fundingBelowPushAmount.toMilliSatoshi).getMessage)
    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv CurrentBlockCount (funding in progress)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val currentBlock = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + 10
    alice ! ProcessCurrentBlockHeight(CurrentBlockHeight(currentBlock))
    // Alice republishes the highest feerate funding tx.
    assert(aliceListener.expectMsgType[TransactionPublished].tx.txid == fundingTx.txid)
    alice2bob.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv CurrentBlockCount (funding in progress while offline)", Tag(ChannelStateTestsTags.DualFunding), Tag(liquidityPurchase)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val currentBlock = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + 10
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    alice ! ProcessCurrentBlockHeight(CurrentBlockHeight(currentBlock))
    // Alice republishes the highest feerate funding tx.
    assert(aliceListener.expectMsgType[TransactionPublished].tx.txid == fundingTx.txid)
    alice2bob.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == OFFLINE)
  }

  test("recv CurrentBlockCount (funding double-spent)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val currentBlock = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + 10
    wallet.doubleSpent = Set(fundingTx.txid)
    alice ! ProcessCurrentBlockHeight(CurrentBlockHeight(currentBlock))
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(wallet.rolledback.map(_.txid) == Seq(fundingTx.txid))
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv CurrentBlockCount (funding double-spent while offline)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val currentBlock = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + 10
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    wallet.doubleSpent = Set(fundingTx.txid)
    alice ! ProcessCurrentBlockHeight(CurrentBlockHeight(currentBlock))
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(wallet.rolledback.map(_.txid) == Seq(fundingTx.txid))
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv CurrentBlockCount (funding timeout reached)", Tag(ChannelStateTestsTags.DualFunding), Tag(noFundingContribution)) { f =>
    import f._
    val timeoutBlock = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + Channel.FUNDING_TIMEOUT_FUNDEE + 1
    bob ! ProcessCurrentBlockHeight(CurrentBlockHeight(timeoutBlock))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectNoMessage(100 millis)
    bobListener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CurrentBlockCount (funding timeout reached while offline)", Tag(ChannelStateTestsTags.DualFunding), Tag(noFundingContribution)) { f =>
    import f._
    val timeoutBlock = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + Channel.FUNDING_TIMEOUT_FUNDEE + 1
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)
    bob ! ProcessCurrentBlockHeight(CurrentBlockHeight(timeoutBlock))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectNoMessage(100 millis)
    bobListener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv ChannelReady (initiator)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    assert(channelReady.alias_opt.isDefined)
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].deferred.contains(channelReady))
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv ChannelReady (initiator, no remote contribution)", Tag(ChannelStateTestsTags.DualFunding), Tag(noFundingContribution)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    val bobChannelReady = bob2alice.expectMsgType[ChannelReady]
    assert(bobChannelReady.alias_opt.isDefined)
    bob2alice.forward(alice)
    assert(alice2blockchain.expectMsgType[WatchPublished].txId == fundingTx.txid)
    alice ! WatchPublishedTriggered(fundingTx)
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    val aliases = aliceListener.expectMsgType[ShortChannelIdAssigned].aliases
    val aliceChannelReady = alice2bob.expectMsgType[ChannelReady]
    assert(aliceChannelReady.alias_opt.contains(aliases.localAlias))
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv ChannelReady (initiator, no remote contribution, with remote inputs)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    // We test the following scenario:
    //  - Bob doesn't contribute to the channel funding output
    //  - But Bob adds inputs to the interactive-tx transaction
    //  - And sends an early channel_ready to try to fool us into using zero-conf
    // We don't have code to contribute to an interactive-tx without contributing to the funding output, so we tweak
    // internal channel data to simulate it.
    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val fundingTx1 = aliceData.latestFundingTx.copy(fundingParams = aliceData.latestFundingTx.fundingParams.copy(remoteContribution = 0 sat))
    val aliceData1 = aliceData
      .modify(_.commitments.active.at(0).localFundingStatus)
      .setTo(fundingTx1)
    alice.setState(alice.stateName, aliceData1)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.fundingParams.remoteContribution == 0.sat)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.tx.remoteInputs.nonEmpty)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx)
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    assert(channelReady.alias_opt.isDefined)
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].deferred.contains(channelReady))
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv ChannelReady (non-initiator)", Tag(ChannelStateTestsTags.DualFunding), Tag(liquidityPurchase)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    val channelReady = alice2bob.expectMsgType[ChannelReady]
    assert(channelReady.alias_opt.isDefined)
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
    bob2blockchain.expectNoMessage(100 millis)
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].deferred.contains(channelReady))
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv WatchFundingSpentTriggered while offline (remote commit)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    // The funding tx confirms while we're offline.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(aliceListener.expectMsgType[ShortChannelIdAssigned].announcement_opt.isEmpty)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    alice2blockchain.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)
    awaitCond(alice.stateData.isInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY])
    assert(alice.stateName == OFFLINE)
    // Bob broadcasts his commit tx.
    val bobCommitTx = bob.signCommitTx()
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    aliceListener.expectMsgType[TransactionPublished]
    alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimMain = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    Transaction.correctlySpends(claimMain.tx, Seq(bobCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchTxConfirmed(bobCommitTx.txid)
    alice2blockchain.expectWatchOutputSpent(claimMain.input)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered while offline (previous tx)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val fundingTx1 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val bobCommitTx1 = bob.signCommitTx()
    val fundingTx2 = testBumpFundingFees(f)
    assert(fundingTx1.txid != fundingTx2.signedTx.txid)
    val bobCommitTx2 = bob.signCommitTx()
    assert(bobCommitTx1.txid != bobCommitTx2.txid)

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    // A previous funding tx confirms while we're offline.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx1)
    assert(aliceListener.expectMsgType[ShortChannelIdAssigned].announcement_opt.isEmpty)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx1.txid)
    alice2blockchain.expectMsg(UnwatchTxConfirmed(fundingTx2.txId))
    alice2blockchain.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)
    awaitCond(alice.stateData.isInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY])
    assert(alice.stateName == OFFLINE)
    testUnusedInputsUnlocked(wallet, Seq(fundingTx2))

    // Bob broadcasts his commit tx.
    alice ! WatchFundingSpentTriggered(bobCommitTx1)
    assert(aliceListener.expectMsgType[TransactionPublished].tx.txid == bobCommitTx1.txid)
    alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimMain = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    Transaction.correctlySpends(claimMain.tx, Seq(bobCommitTx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchTxConfirmed(bobCommitTx1.txid)
    alice2blockchain.expectWatchOutputSpent(claimMain.input)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered after restart (remote commit)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val bobData = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val fundingTx = aliceData.latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val aliceCommitTx = alice.signCommitTx()
    val bobCommitTx = bob.signCommitTx()
    val (alice2, bob2) = restartNodes(f, aliceData, bobData)

    alice2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    alice2 ! WatchFundingSpentTriggered(bobCommitTx)
    alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimMainAlice = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    Transaction.correctlySpends(claimMainAlice.tx, Seq(bobCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchTxConfirmed(bobCommitTx.txid)
    alice2blockchain.expectWatchOutputSpent(claimMainAlice.input)
    awaitCond(alice2.stateName == CLOSING)

    bob2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(bobListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(bob2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    bob2 ! WatchFundingSpentTriggered(aliceCommitTx)
    bob2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimMainBob = bob2blockchain.expectFinalTxPublished("remote-main-delayed")
    Transaction.correctlySpends(claimMainBob.tx, Seq(aliceCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    bob2blockchain.expectWatchTxConfirmed(aliceCommitTx.txid)
    bob2blockchain.expectWatchOutputSpent(claimMainBob.input)
    awaitCond(bob2.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered after restart (previous tx)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val fundingTx1 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val aliceCommitTx1 = alice.signCommitTx()
    val bobCommitTx1 = bob.signCommitTx()
    val fundingTx2 = testBumpFundingFees(f)
    assert(fundingTx1.txid != fundingTx2.signedTx.txid)
    val bobCommitTx2 = bob.signCommitTx()
    assert(bobCommitTx1.txid != bobCommitTx2.txid)

    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val bobData = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val (alice2, bob2) = restartNodes(f, aliceData, bobData)

    alice2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx1)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx1.txid)
    alice2blockchain.expectMsg(UnwatchTxConfirmed(fundingTx2.txId))
    alice2 ! WatchFundingSpentTriggered(bobCommitTx1)
    alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimMainAlice = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    Transaction.correctlySpends(claimMainAlice.tx, Seq(bobCommitTx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchTxConfirmed(bobCommitTx1.txid)
    alice2blockchain.expectWatchOutputSpent(claimMainAlice.input)
    awaitCond(alice2.stateName == CLOSING)
    testUnusedInputsUnlocked(wallet, Seq(fundingTx2))

    bob2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1)
    assert(bobListener.expectMsgType[TransactionConfirmed].tx == fundingTx1)
    assert(bob2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx1.txid)
    bob2blockchain.expectMsg(UnwatchTxConfirmed(fundingTx2.txId))
    bob2 ! WatchFundingSpentTriggered(aliceCommitTx1)
    bob2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimMainBob = bob2blockchain.expectFinalTxPublished("remote-main-delayed")
    Transaction.correctlySpends(claimMainBob.tx, Seq(aliceCommitTx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    bob2blockchain.expectWatchTxConfirmed(aliceCommitTx1.txid)
    bob2blockchain.expectWatchOutputSpent(claimMainBob.input)
    awaitCond(bob2.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (unrecognized commit)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  private def initiateRbf(f: FixtureParam): TxComplete = {
    import f._

    alice ! CMD_BUMP_FUNDING_FEE(TestProbe().ref, TestConstants.feeratePerKw * 1.1, fundingFeeBudget = 100_000.sat, 0, None)
    alice2bob.expectMsgType[TxInitRbf]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAckRbf]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAddInput]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAddInput]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAddOutput]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    val txCompleteBob = bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    txCompleteBob
  }

  private def reconnectRbf(f: FixtureParam): (ChannelReestablish, ChannelReestablish) = {
    import f._

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)

    val aliceInit = Init(alice.underlyingActor.nodeParams.features.initFeatures())
    val bobInit = Init(bob.underlyingActor.nodeParams.features.initFeatures())
    alice ! INPUT_RECONNECTED(bob, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(alice, bobInit, aliceInit)
    val channelReestablishAlice = alice2bob.expectMsgType[ChannelReestablish]
    alice2bob.forward(bob)
    val channelReestablishBob = bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    (channelReestablishAlice, channelReestablishBob)
  }

  def testDisconnectUnsignedRbfAttempt(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._

    initiateRbf(f)
    alice2bob.expectMsgType[TxComplete] // bob doesn't receive alice's tx_complete
    alice2bob.expectMsgType[CommitSig] // bob doesn't receive alice's commit_sig

    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.isInstanceOf[DualFundingStatus.RbfWaitingForSigs])
    val fundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.txId
    val rbfTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.asInstanceOf[DualFundingStatus.RbfWaitingForSigs].signingSession.fundingTx.txId
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.isInstanceOf[DualFundingStatus.RbfInProgress])
    assert(fundingTxId != rbfTxId)

    val (channelReestablishAlice, channelReestablishBob) = reconnectRbf(f)
    assert(channelReestablishAlice.nextFundingTxId_opt.contains(rbfTxId))
    assert(channelReestablishAlice.nextLocalCommitmentNumber == 0)
    assert(channelReestablishBob.nextFundingTxId_opt.isEmpty)
    assert(channelReestablishBob.nextLocalCommitmentNumber == 1)
    commitmentFormat match {
      case _: SegwitV0CommitmentFormat => ()
      case _: TaprootCommitmentFormat =>
        assert(channelReestablishAlice.currentCommitNonce_opt.nonEmpty)
        assert(channelReestablishBob.currentCommitNonce_opt.isEmpty)
        assert(channelReestablishAlice.nextCommitNonces.contains(fundingTxId))
        assert(channelReestablishBob.nextCommitNonces.contains(fundingTxId))
    }

    // Bob detects that Alice stored an old RBF attempt and tells her to abort.
    bob2alice.expectMsgType[TxAbort]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
  }

  test("recv INPUT_DISCONNECTED (unsigned rbf attempt)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testDisconnectUnsignedRbfAttempt(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv INPUT_DISCONNECTED (unsigned rbf attempt, taproot)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testDisconnectUnsignedRbfAttempt(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  def testDisconnectRbfCommitSigReceivedAlice(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._

    val txCompleteBob = initiateRbf(f)
    val txCompleteAlice = alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig] // Bob doesn't receive Alice's commit_sig
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.isInstanceOf[DualFundingStatus.RbfWaitingForSigs])
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.isInstanceOf[DualFundingStatus.RbfWaitingForSigs])
    val fundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.txId
    val rbfTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.asInstanceOf[DualFundingStatus.RbfWaitingForSigs].signingSession.fundingTx.txId
    assert(fundingTxId != rbfTxId)

    val (channelReestablishAlice, channelReestablishBob) = reconnectRbf(f)
    assert(channelReestablishAlice.nextFundingTxId_opt.contains(rbfTxId))
    assert(channelReestablishAlice.nextLocalCommitmentNumber == 1)
    assert(channelReestablishBob.nextFundingTxId_opt.contains(rbfTxId))
    assert(channelReestablishBob.nextLocalCommitmentNumber == 0)
    commitmentFormat match {
      case _: SegwitV0CommitmentFormat => ()
      case _: TaprootCommitmentFormat =>
        assert(channelReestablishAlice.currentCommitNonce_opt.isEmpty)
        assert(channelReestablishBob.currentCommitNonce_opt.nonEmpty)
        Seq(channelReestablishAlice, channelReestablishBob).foreach(channelReestablish => {
          assert(channelReestablish.nextCommitNonces.size == 2)
          assert(channelReestablish.nextCommitNonces.values.toSet.size == 2)
          assert(channelReestablish.nextCommitNonces.contains(fundingTxId))
          assert(channelReestablish.nextCommitNonces.contains(rbfTxId))
        })
        assert(channelReestablishAlice.nextCommitNonces.get(rbfTxId).contains(txCompleteAlice.commitNonces_opt.get.nextCommitNonce))
        assert(channelReestablishBob.nextCommitNonces.get(rbfTxId).contains(txCompleteBob.commitNonces_opt.get.nextCommitNonce))
    }

    // Alice retransmits commit_sig, and they exchange tx_signatures afterwards.
    bob2alice.expectNoMessage(100 millis)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    val nextFundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(aliceListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    assert(bobListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
  }

  test("recv INPUT_DISCONNECTED (rbf commit_sig received by Alice)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testDisconnectRbfCommitSigReceivedAlice(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv INPUT_DISCONNECTED (rbf commit_sig received by Alice, taproot)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testDisconnectRbfCommitSigReceivedAlice(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  def testDisconnectRbfCommitSigReceivedBob(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._

    val txCompleteBob = initiateRbf(f)
    val txCompleteAlice = alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig] // Alice doesn't receive Bob's commit_sig
    bob2alice.expectMsgType[TxSignatures] // Alice doesn't receive Bob's tx_signatures
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.isInstanceOf[DualFundingStatus.RbfWaitingForSigs])
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    val fundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.txId
    val rbfTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.asInstanceOf[DualFundingStatus.RbfWaitingForSigs].signingSession.fundingTx.txId
    assert(fundingTxId != rbfTxId)

    val (channelReestablishAlice, channelReestablishBob) = reconnectRbf(f)
    assert(channelReestablishAlice.nextFundingTxId_opt.contains(rbfTxId))
    assert(channelReestablishAlice.nextLocalCommitmentNumber == 0)
    assert(channelReestablishBob.nextFundingTxId_opt.contains(rbfTxId))
    assert(channelReestablishBob.nextLocalCommitmentNumber == 1)
    commitmentFormat match {
      case _: SegwitV0CommitmentFormat => ()
      case _: TaprootCommitmentFormat =>
        assert(channelReestablishAlice.currentCommitNonce_opt.nonEmpty)
        assert(channelReestablishBob.currentCommitNonce_opt.isEmpty)
        Seq(channelReestablishAlice, channelReestablishBob).foreach(channelReestablish => {
          assert(channelReestablish.nextCommitNonces.size == 2)
          assert(channelReestablish.nextCommitNonces.values.toSet.size == 2)
          assert(channelReestablish.nextCommitNonces.contains(fundingTxId))
          assert(channelReestablish.nextCommitNonces.contains(rbfTxId))
        })
        assert(channelReestablishAlice.nextCommitNonces.get(rbfTxId).contains(txCompleteAlice.commitNonces_opt.get.nextCommitNonce))
        assert(channelReestablishBob.nextCommitNonces.get(rbfTxId).contains(txCompleteBob.commitNonces_opt.get.nextCommitNonce))
    }

    // Bob retransmits commit_sig and tx_signatures, then Alice sends her tx_signatures.
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    val nextFundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(aliceListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    assert(bobListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
  }

  test("recv INPUT_DISCONNECTED (rbf commit_sig received by Bob)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testDisconnectRbfCommitSigReceivedBob(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv INPUT_DISCONNECTED (rbf commit_sig received by Bob, taproot)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testDisconnectRbfCommitSigReceivedBob(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  test("recv INPUT_DISCONNECTED (rbf commit_sig received by Bob, taproot, missing current commit nonce)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    import f._

    initiateRbf(f)
    alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig] // Alice doesn't receive Bob's commit_sig
    bob2alice.expectMsgType[TxSignatures] // Alice doesn't receive Bob's tx_signatures
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.isInstanceOf[DualFundingStatus.RbfWaitingForSigs])
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)

    // Alice is buggy and doesn't include her current commit nonce in channel_reestablish.
    val aliceInit = Init(alice.underlyingActor.nodeParams.features.initFeatures())
    val bobInit = Init(bob.underlyingActor.nodeParams.features.initFeatures())
    alice ! INPUT_RECONNECTED(bob, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(alice, bobInit, aliceInit)
    val channelReestablishAlice = alice2bob.expectMsgType[ChannelReestablish]
    assert(channelReestablishAlice.nextFundingTxId_opt.nonEmpty)
    assert(channelReestablishAlice.nextLocalCommitmentNumber == 0)
    assert(channelReestablishAlice.currentCommitNonce_opt.nonEmpty)
    bob2alice.expectMsgType[ChannelReestablish]
    alice2bob.forward(bob, channelReestablishAlice.copy(tlvStream = TlvStream(channelReestablishAlice.tlvStream.records.filterNot(_.isInstanceOf[ChannelReestablishTlv.CurrentCommitNonceTlv]))))
    bob2alice.expectMsgType[Error]
    awaitCond(bob.stateName == CLOSING)
  }

  def testDisconnectRbfCommitSigReceived(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._

    val txCompleteBob = initiateRbf(f)
    val txCompleteAlice = alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[TxSignatures] // Alice doesn't receive Bob's tx_signatures
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.isInstanceOf[DualFundingStatus.RbfWaitingForSigs])
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    val fundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.txId
    val rbfTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.asInstanceOf[DualFundingStatus.RbfWaitingForSigs].signingSession.fundingTx
    assert(fundingTxId != rbfTx.txId)

    val (channelReestablishAlice, channelReestablishBob) = reconnectRbf(f)
    assert(channelReestablishAlice.nextFundingTxId_opt.contains(rbfTx.txId))
    assert(channelReestablishAlice.currentCommitNonce_opt.isEmpty)
    assert(channelReestablishAlice.nextLocalCommitmentNumber == 1)
    assert(channelReestablishBob.nextFundingTxId_opt.contains(rbfTx.txId))
    assert(channelReestablishBob.currentCommitNonce_opt.isEmpty)
    assert(channelReestablishBob.nextLocalCommitmentNumber == 1)
    commitmentFormat match {
      case _: SegwitV0CommitmentFormat => ()
      case _: TaprootCommitmentFormat =>
        Seq(channelReestablishAlice, channelReestablishBob).foreach(channelReestablish => {
          assert(channelReestablish.nextCommitNonces.size == 2)
          assert(channelReestablish.nextCommitNonces.values.toSet.size == 2)
          assert(channelReestablish.nextCommitNonces.contains(fundingTxId))
          assert(channelReestablish.nextCommitNonces.contains(rbfTx.txId))
        })
        assert(channelReestablishAlice.nextCommitNonces.get(rbfTx.txId).contains(txCompleteAlice.commitNonces_opt.get.nextCommitNonce))
        assert(channelReestablishBob.nextCommitNonces.get(rbfTx.txId).contains(txCompleteBob.commitNonces_opt.get.nextCommitNonce))
    }

    // Alice and Bob exchange tx_signatures and complete the RBF attempt.
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    val nextFundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(aliceListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    assert(bobListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
  }

  test("recv INPUT_DISCONNECTED (rbf commit_sig received)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testDisconnectRbfCommitSigReceived(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv INPUT_DISCONNECTED (rbf commit_sig received, taproot)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testDisconnectRbfCommitSigReceived(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  test("recv INPUT_DISCONNECTED (rbf commit_sig received, taproot, missing next commit nonce)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    import f._

    initiateRbf(f)
    alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[TxSignatures] // Alice doesn't receive Bob's tx_signatures
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.isInstanceOf[DualFundingStatus.RbfWaitingForSigs])
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    val fundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.txId
    val rbfTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status.asInstanceOf[DualFundingStatus.RbfWaitingForSigs].signingSession.fundingTx.txId
    assert(fundingTxId != rbfTxId)

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)

    val aliceInit = Init(alice.underlyingActor.nodeParams.features.initFeatures())
    val bobInit = Init(bob.underlyingActor.nodeParams.features.initFeatures())
    alice ! INPUT_RECONNECTED(bob, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(alice, bobInit, aliceInit)
    // Alice is buggy and doesn't include her next commit nonce for the initial funding tx.
    val channelReestablishAlice = alice2bob.expectMsgType[ChannelReestablish]
    val aliceNonces = ChannelReestablishTlv.NextLocalNoncesTlv((channelReestablishAlice.nextCommitNonces - fundingTxId).toSeq)
    val channelReestablishAlice1 = channelReestablishAlice.copy(tlvStream = TlvStream(channelReestablishAlice.tlvStream.records.filterNot(_.isInstanceOf[ChannelReestablishTlv.NextLocalNoncesTlv]) + aliceNonces))
    // Bob is buggy and doesn't include his next commit nonce for the RBF tx.
    val channelReestablishBob = bob2alice.expectMsgType[ChannelReestablish]
    val bobNonces = ChannelReestablishTlv.NextLocalNoncesTlv((channelReestablishBob.nextCommitNonces - rbfTxId).toSeq)
    val channelReestablishBob1 = channelReestablishBob.copy(tlvStream = TlvStream(channelReestablishBob.tlvStream.records.filterNot(_.isInstanceOf[ChannelReestablishTlv.NextLocalNoncesTlv]) + bobNonces))
    alice2bob.forward(bob, channelReestablishAlice1)
    assert(bob2alice.expectMsgType[Error].toAscii == MissingCommitNonce(channelReestablishBob.channelId, fundingTxId, commitmentNumber = 1).getMessage)
    awaitCond(bob.stateName == CLOSING)
    bob2alice.forward(alice, channelReestablishBob1)
    assert(alice2bob.expectMsgType[Error].toAscii == MissingCommitNonce(channelReestablishAlice.channelId, rbfTxId, commitmentNumber = 1).getMessage)
    awaitCond(alice.stateName == CLOSING)
  }

  def testDisconnectTxSigsPartiallyReceived(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._

    val currentFundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.txId
    val txCompleteBob = initiateRbf(f)
    val txCompleteAlice = alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures] // Bob doesn't receive Alice's tx_signatures
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    val rbfTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.txId
    assert(rbfTxId != currentFundingTxId)

    val (channelReestablishAlice, channelReestablishBob) = reconnectRbf(f)
    assert(channelReestablishAlice.nextFundingTxId_opt.isEmpty)
    assert(channelReestablishAlice.currentCommitNonce_opt.isEmpty)
    assert(channelReestablishAlice.nextLocalCommitmentNumber == 1)
    assert(channelReestablishBob.nextFundingTxId_opt.contains(rbfTxId))
    assert(channelReestablishBob.currentCommitNonce_opt.isEmpty)
    assert(channelReestablishBob.nextLocalCommitmentNumber == 1)
    commitmentFormat match {
      case _: SegwitV0CommitmentFormat => ()
      case _: TaprootCommitmentFormat =>
        Seq(channelReestablishAlice, channelReestablishBob).foreach(channelReestablish => {
          assert(channelReestablish.nextCommitNonces.size == 2)
          assert(channelReestablish.nextCommitNonces.values.toSet.size == 2)
          assert(channelReestablish.nextCommitNonces.contains(currentFundingTxId))
          assert(channelReestablish.nextCommitNonces.contains(rbfTxId))
        })
        assert(channelReestablishAlice.nextCommitNonces.get(rbfTxId).contains(txCompleteAlice.commitNonces_opt.get.nextCommitNonce))
        assert(channelReestablishBob.nextCommitNonces.get(rbfTxId).contains(txCompleteBob.commitNonces_opt.get.nextCommitNonce))
    }

    // Alice and Bob exchange signatures and complete the RBF attempt.
    bob2alice.expectNoMessage(100 millis)
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    val nextFundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(aliceListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    assert(bobListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].status == DualFundingStatus.WaitingForConfirmations)
  }

  test("recv INPUT_DISCONNECTED (rbf tx_signatures partially received)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testDisconnectTxSigsPartiallyReceived(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv INPUT_DISCONNECTED (rbf tx_signatures partially received, taproot)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testDisconnectTxSigsPartiallyReceived(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val tx = alice.signCommitTx()
    alice ! Error(ByteVector32.Zeroes, "dual funding d34d")
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }

  test("recv Error (remote commit published)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val aliceCommitTx = alice.signCommitTx()
    alice ! Error(ByteVector32.Zeroes, "force-closing channel, bye-bye")
    awaitCond(alice.stateName == CLOSING)
    aliceListener.expectMsgType[ChannelAborted]
    alice2blockchain.expectFinalTxPublished(aliceCommitTx.txid)
    val anchorTxLocal = alice2blockchain.expectReplaceableTxPublished[ClaimLocalAnchorTx]
    val claimMainLocal = alice2blockchain.expectFinalTxPublished("local-main-delayed")
    Transaction.correctlySpends(claimMainLocal.tx, Seq(aliceCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchTxConfirmed(aliceCommitTx.txid)
    alice2blockchain.expectWatchOutputSpent(claimMainLocal.input)
    alice2blockchain.expectWatchOutputSpent(anchorTxLocal.input.outPoint)
    // Bob broadcasts his commit tx as well.
    val bobCommitTx = bob.signCommitTx()
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    val anchorTxRemote = alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimMainRemote = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    Transaction.correctlySpends(claimMainRemote.tx, Seq(bobCommitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchTxConfirmed(bobCommitTx.txid)
    alice2blockchain.expectWatchOutputSpent(claimMainRemote.input)
    alice2blockchain.expectWatchOutputSpent(anchorTxRemote.input.outPoint)
    alice2blockchain.expectNoMessage(100 millis)
  }

  test("recv Error (previous tx confirms)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val fundingTx1 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val aliceCommitTx1 = alice.signCommitTx()
    val bobCommitTx1 = bob.signCommitTx()
    assert(aliceCommitTx1.txIn.head.outPoint.txid == fundingTx1.txid)
    assert(bobCommitTx1.txIn.head.outPoint.txid == fundingTx1.txid)
    val fundingTx2 = testBumpFundingFees(f)
    assert(fundingTx1.txid != fundingTx2.signedTx.txid)
    val aliceCommitTx2 = alice.signCommitTx()
    assert(aliceCommitTx2.txIn.head.outPoint.txid == fundingTx2.signedTx.txid)

    // Alice receives an error and force-closes using the latest funding transaction.
    alice ! Error(ByteVector32.Zeroes, "dual funding d34d")
    awaitCond(alice.stateName == CLOSING)
    aliceListener.expectMsgType[ChannelAborted]
    alice2blockchain.expectFinalTxPublished(aliceCommitTx2.txid)
    val anchorTx2 = alice2blockchain.expectReplaceableTxPublished[ClaimLocalAnchorTx]
    val claimMain2 = alice2blockchain.expectFinalTxPublished("local-main-delayed")
    Transaction.correctlySpends(claimMain2.tx, Seq(aliceCommitTx2), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchTxConfirmed(aliceCommitTx2.txid)
    alice2blockchain.expectWatchOutputsSpent(Seq(anchorTx2.input.outPoint, claimMain2.input))

    // A previous funding transaction confirms, so Alice publishes the corresponding commit tx.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx1)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx1.txid)
    alice2blockchain.expectMsg(UnwatchTxConfirmed(fundingTx2.txId))
    alice2blockchain.expectFinalTxPublished(aliceCommitTx1.txid)
    val anchorTx1 = alice2blockchain.expectReplaceableTxPublished[ClaimLocalAnchorTx]
    val claimMain1 = alice2blockchain.expectFinalTxPublished("local-main-delayed")
    Transaction.correctlySpends(claimMain1.tx, Seq(aliceCommitTx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchTxConfirmed(aliceCommitTx1.txid)
    alice2blockchain.expectWatchOutputsSpent(Seq(anchorTx1.input.outPoint, claimMain1.input))
    testUnusedInputsUnlocked(wallet, Seq(fundingTx2))

    // Bob publishes his commit tx, Alice reacts by spending her remote main output.
    alice ! WatchFundingSpentTriggered(bobCommitTx1)
    val anchorRemote = alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimMainRemote = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    Transaction.correctlySpends(claimMainRemote.tx, Seq(bobCommitTx1), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchTxConfirmed(bobCommitTx1.txid)
    alice2blockchain.expectWatchOutputsSpent(Seq(anchorRemote.input.outPoint, claimMainRemote.input))
    assert(alice.stateName == CLOSING)
  }

  test("recv Error (nothing at stake)", Tag(ChannelStateTestsTags.DualFunding), Tag(noFundingContribution)) { f =>
    import f._
    val commitTx = bob.signCommitTx()
    bob ! Error(ByteVector32.Zeroes, "please help me recover my funds")
    // We have nothing at stake, but we publish our commitment to help our peer recover their funds more quickly.
    awaitCond(bob.stateName == CLOSING)
    bobListener.expectMsgType[ChannelAborted]
    bob2blockchain.expectFinalTxPublished(commitTx.txid)
    bob2blockchain.expectWatchTxConfirmed(commitTx.txid)
    bob ! WatchTxConfirmedTriggered(BlockHeight(42), 1, commitTx)
    bobListener.expectMsgType[TransactionConfirmed]
    awaitCond(bob.stateName == CLOSED)
    bobListener.expectMsgType[ChannelClosed]
  }

  test("recv CMD_CLOSE", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_FAILURE(c, CommandUnavailableInThisState(channelId(alice), "close", WAIT_FOR_DUAL_FUNDING_CONFIRMED)))
  }

  test("recv CMD_FORCECLOSE", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val sender = TestProbe()
    val commitTx = alice.signCommitTx()
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    aliceListener.expectMsgType[ChannelAborted]
    alice2blockchain.expectFinalTxPublished(commitTx.txid)
    val claimMain = alice2blockchain.expectFinalTxPublished("local-main-delayed")
    Transaction.correctlySpends(claimMain.tx, Seq(commitTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    alice2blockchain.expectWatchTxConfirmed(commitTx.txid)
    alice2blockchain.expectWatchOutputSpent(claimMain.input)
  }

  def restartNodes(f: FixtureParam, aliceData: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED, bobData: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED): (TestFSMRef[ChannelState, ChannelData, Channel], TestFSMRef[ChannelState, ChannelData, Channel]) = {
    import f._

    val (aliceNodeParams, bobNodeParams) = (alice.underlyingActor.nodeParams, bob.underlyingActor.nodeParams)
    val (aliceKeys, bobKeys) = (alice.underlyingActor.channelKeys, bob.underlyingActor.channelKeys)
    val (alicePeer, bobPeer) = (alice.getParent, bob.getParent)

    alice.stop()
    bob.stop()

    val alice2 = TestFSMRef(new Channel(aliceNodeParams, aliceKeys, wallet, bobNodeParams.nodeId, alice2blockchain.ref, TestProbe().ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer)
    alice2 ! INPUT_RESTORED(aliceData)
    alice2blockchain.expectMsgType[SetChannelId]
    // When restoring, we watch confirmation of all potential funding transactions to detect offline force-closes.
    aliceData.allFundingTxs.foreach(f => alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == f.sharedTx.txId)
    awaitCond(alice2.stateName == OFFLINE)

    val bob2 = TestFSMRef(new Channel(bobNodeParams, bobKeys, wallet, aliceNodeParams.nodeId, bob2blockchain.ref, TestProbe().ref, FakeTxPublisherFactory(bob2blockchain)), bobPeer)
    bob2 ! INPUT_RESTORED(bobData)
    bob2blockchain.expectMsgType[SetChannelId]
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == bobData.commitments.latest.fundingTxId)
    bobData.previousFundingTxs.foreach(f => bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == f.sharedTx.txId)
    awaitCond(bob2.stateName == OFFLINE)

    alice2.underlying.system.eventStream.subscribe(aliceListener.ref, classOf[TransactionConfirmed])
    bob2.underlying.system.eventStream.subscribe(bobListener.ref, classOf[TransactionConfirmed])

    (alice2, bob2)
  }

  def reconnectNodes(f: FixtureParam, alice2: TestFSMRef[ChannelState, ChannelData, Channel], bob2: TestFSMRef[ChannelState, ChannelData, Channel]): Unit = {
    import f._

    val aliceInit = Init(alice2.underlyingActor.nodeParams.features.initFeatures())
    val bobInit = Init(bob2.underlyingActor.nodeParams.features.initFeatures())
    alice2 ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    val aliceChannelReestablish = alice2bob.expectMsgType[ChannelReestablish]
    assert(aliceChannelReestablish.nextFundingTxId_opt.isEmpty)
    bob2 ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
    val bobChannelReestablish = bob2alice.expectMsgType[ChannelReestablish]
    assert(bobChannelReestablish.nextFundingTxId_opt.isEmpty)
    alice2 ! bobChannelReestablish
    bob2 ! aliceChannelReestablish

    awaitCond(alice2.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    awaitCond(bob2.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)

    alice2blockchain.expectNoMessage(100 millis)
    bob2blockchain.expectNoMessage(100 millis)
  }

}
