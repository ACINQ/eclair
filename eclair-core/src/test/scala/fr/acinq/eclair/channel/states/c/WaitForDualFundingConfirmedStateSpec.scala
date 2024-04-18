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

import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, actorRefAdapter}
import akka.testkit.{TestFSMRef, TestProbe}
import com.softwaremill.quicklens.{ModifyPimp, QuicklensAt}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, SingleKeyOnChainWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.ProcessCurrentBlockHeight
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.FullySignedSharedTransaction
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx, SetChannelId}
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.FakeTxPublisherFactory
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions.AnchorOutputsCommitmentFormat
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, TestConstants, TestKitBaseClass, ToMilliSatoshiConversion}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

class WaitForDualFundingConfirmedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  val bothPushAmount = "both_push_amount"
  val noFundingContribution = "no_funding_contribution"

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

    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags(announceChannel = false)
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
    val commitFeerate = channelType.commitmentFormat match {
      case Transactions.DefaultCommitmentFormat => TestConstants.feeratePerKw
      case _: Transactions.AnchorOutputsCommitmentFormat => TestConstants.anchorOutputsFeeratePerKw
    }
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    val (requestFunds_opt, bobContribution) = if (test.tags.contains(noFundingContribution)) {
      (None, None)
    } else {
      val requestFunds = LiquidityAds.RequestFunding(TestConstants.nonInitiatorFundingSatoshis, TestConstants.defaultLiquidityRates.fundingRates.head, LiquidityAds.PaymentDetails.FromChannelBalance)
      val addFunding = LiquidityAds.AddFunding(TestConstants.nonInitiatorFundingSatoshis, Some(TestConstants.defaultLiquidityRates))
      (Some(requestFunds), Some(addFunding))
    }
    val (initiatorPushAmount, nonInitiatorPushAmount) = if (test.tags.contains(bothPushAmount)) (Some(TestConstants.initiatorPushAmount), Some(TestConstants.nonInitiatorPushAmount)) else (None, None)
    within(30 seconds) {
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = true, commitFeerate, TestConstants.feeratePerKw, fundingTxFeeBudget_opt = None, initiatorPushAmount, requireConfirmedInputs = false, requestFunds_opt, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType, replyTo = aliceOpenReplyTo.ref.toTyped)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, bobContribution, dualFunded = true, nonInitiatorPushAmount, requireConfirmedInputs = false, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
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
        val liquidityFees = TestConstants.defaultLiquidityRates.fundingRates.head.fees(TestConstants.feeratePerKw, TestConstants.nonInitiatorFundingSatoshis, TestConstants.nonInitiatorFundingSatoshis, isChannelCreation = true)
        val bobReserve = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.remoteChannelReserve
        val expectedBalanceBob = bobContribution.map(_.fundingAmount).getOrElse(0 sat) + liquidityFees.total + initiatorPushAmount.getOrElse(0 msat) - nonInitiatorPushAmount.getOrElse(0 msat) - bobReserve
        assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.availableBalanceForSend == expectedBalanceBob)
      }
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, aliceListener, bobListener, wallet)))
    }
  }

  test("recv TxSignatures (duplicate)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
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
    assert(aliceListener.expectMsgType[ShortChannelIdAssigned].shortIds.real == RealScidStatus.Unknown)
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv WatchPublishedTriggered (non-initiator)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.ZeroConf), Tag(ChannelStateTestsTags.ScidAlias), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    bob ! WatchPublishedTriggered(fundingTx)
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTx.txid)
    bob2blockchain.expectNoMessage(100 millis) // we don't set WatchFundingSpent
    bob2alice.expectMsgType[ChannelReady]
    assert(bobListener.expectMsgType[ShortChannelIdAssigned].shortIds.real == RealScidStatus.Unknown)
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
    assert(aliceListener.expectMsgType[ShortChannelIdAssigned].shortIds.real == RealScidStatus.Unknown)
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
    assert(aliceListener.expectMsgType[ShortChannelIdAssigned].shortIds.real.isInstanceOf[RealScidStatus.Temporary])
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv WatchFundingConfirmedTriggered (non-initiator)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(bob2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    bob2alice.expectMsgType[ChannelReady]
    assert(bobListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(bobListener.expectMsgType[ShortChannelIdAssigned].shortIds.real.isInstanceOf[RealScidStatus.Temporary])
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
    assert(aliceListener.expectMsgType[ShortChannelIdAssigned].shortIds.real.isInstanceOf[RealScidStatus.Temporary])
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

  test("recv WatchFundingConfirmedTriggered after restart (previous tx)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val fundingTx1 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val fundingTx2 = testBumpFundingFees(f).signedTx
    assert(fundingTx1.txid != fundingTx2.txid)

    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val bobData = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val (alice2, bob2) = restartNodes(f, aliceData, bobData)
    reconnectNodes(f, alice2, bob2)

    alice2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx1)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx1.txid)
    alice2bob.expectMsgType[ChannelReady]
    awaitCond(alice2.stateName == WAIT_FOR_DUAL_FUNDING_READY)
    assert(alice2.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY].commitments.active.size == 1)
    assert(alice2.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY].commitments.inactive.isEmpty)
    assert(alice2.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY].commitments.latest.fundingTxId == fundingTx1.txid)

    bob2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1)
    assert(bobListener.expectMsgType[TransactionConfirmed].tx == fundingTx1)
    assert(bob2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx1.txid)
    bob2alice.expectMsgType[ChannelReady]
    awaitCond(bob2.stateName == WAIT_FOR_DUAL_FUNDING_READY)
    assert(bob2.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY].commitments.active.size == 1)
    assert(bob2.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY].commitments.inactive.isEmpty)
    assert(bob2.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY].commitments.latest.fundingTxId == fundingTx1.txid)
  }

  def testBumpFundingFees(f: FixtureParam, feerate_opt: Option[FeeratePerKw] = None, requestFunding_opt: Option[LiquidityAds.RequestFunding] = None): FullySignedSharedTransaction = {
    import f._

    val probe = TestProbe()
    val currentFundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    val previousFundingTxs = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs
    alice ! CMD_BUMP_FUNDING_FEE(probe.ref, feerate_opt.getOrElse(currentFundingTx.feerate * 1.1), fundingFeeBudget = 100_000.sat, 0, requestFunding_opt)
    assert(alice2bob.expectMsgType[TxInitRbf].fundingContribution == TestConstants.fundingSatoshis)
    alice2bob.forward(bob)
    val txAckRbf = bob2alice.expectMsgType[TxAckRbf]
    assert(txAckRbf.fundingContribution == TestConstants.nonInitiatorFundingSatoshis)
    requestFunding_opt.foreach(_ => assert(txAckRbf.willFund_opt.nonEmpty))
    bob2alice.forward(alice)

    // Alice and Bob build a new version of the funding transaction, with one new input every time.
    val inputCount = previousFundingTxs.length + 2
    (1 to inputCount).foreach(_ => {
      alice2bob.expectMsgType[TxAddInput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxAddInput]
      bob2alice.forward(alice)
    })
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAddOutput]
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
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)

    probe.expectMsgType[RES_BUMP_FUNDING_FEE]

    val nextFundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(aliceListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    assert(bobListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    assert(currentFundingTx.signedTx.txid != nextFundingTx.signedTx.txid)
    assert(currentFundingTx.feerate < nextFundingTx.feerate)
    // The new transaction double-spends previous inputs.
    currentFundingTx.signedTx.txIn.map(_.outPoint).foreach(o => assert(nextFundingTx.signedTx.txIn.exists(_.outPoint == o)))
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.length == previousFundingTxs.length + 1)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.head.sharedTx == currentFundingTx)
    nextFundingTx
  }

  test("recv CMD_BUMP_FUNDING_FEE", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val remoteFunding = TestConstants.nonInitiatorFundingSatoshis
    val feerate1 = TestConstants.feeratePerKw
    val liquidityFee1 = TestConstants.defaultLiquidityRates.fundingRates.head.fees(feerate1, remoteFunding, remoteFunding, isChannelCreation = true)
    val balanceBob1 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.spec.toLocal
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.isEmpty)

    val feerate2 = FeeratePerKw(12_500 sat)
    val liquidityFee2 = TestConstants.defaultLiquidityRates.fundingRates.head.fees(feerate2, remoteFunding, remoteFunding, isChannelCreation = true)
    testBumpFundingFees(f, Some(feerate2), Some(LiquidityAds.RequestFunding(remoteFunding, TestConstants.defaultLiquidityRates.fundingRates.head, LiquidityAds.PaymentDetails.FromChannelBalance)))
    val balanceBob2 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.spec.toLocal
    assert(liquidityFee1.total < liquidityFee2.total)
    assert(balanceBob1 + liquidityFee2.total - liquidityFee1.total == balanceBob2)

    // The second RBF attempt removes the liquidity request.
    val feerate3 = FeeratePerKw(15_000 sat)
    testBumpFundingFees(f, Some(feerate3), requestFunding_opt = None)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.spec.toLocal.truncateToSatoshi == remoteFunding)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.length == 2)
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
    awaitAssert(assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus == RbfStatus.RbfAborted))
    bob2alice.expectMsgType[TxAbort] // bob acks alice's tx_abort
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)

    // Alice and Bob clear RBF data from their state.
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus == RbfStatus.NoRbf)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx == fundingTxAlice)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.isEmpty)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus == RbfStatus.NoRbf)
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

    // Alice and Bob build a new version of the funding transaction.
    alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob)
    awaitAssert(assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus == RbfStatus.RbfAborted))
    bob2alice.expectMsgType[TxAbort] // bob acks alice's tx_abort
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)

    // Alice and Bob clear RBF data from their state.
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus == RbfStatus.NoRbf)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx == fundingTxAlice)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.isEmpty)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus == RbfStatus.NoRbf)
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

  test("recv CurrentBlockCount (funding in progress while offline)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
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
    val scids = aliceListener.expectMsgType[ShortChannelIdAssigned].shortIds
    assert(scids.real == RealScidStatus.Unknown)
    val aliceChannelReady = alice2bob.expectMsgType[ChannelReady]
    assert(aliceChannelReady.alias_opt.contains(scids.localAlias))
    assert(alice2blockchain.expectMsgType[WatchFundingDeeplyBuried].txId == fundingTx.txid)
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

  test("recv ChannelReady (non-initiator)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
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
    assert(aliceListener.expectMsgType[ShortChannelIdAssigned].shortIds.real.isInstanceOf[RealScidStatus.Temporary])
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    alice2blockchain.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)
    awaitCond(alice.stateData.isInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY])
    assert(alice.stateName == OFFLINE)
    // Bob broadcasts his commit tx.
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx
    alice ! WatchFundingSpentTriggered(bobCommitTx.tx)
    aliceListener.expectMsgType[TransactionPublished]
    val claimAnchor = alice2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx]
    val claimMain = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMain.input.txid == bobCommitTx.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.tx.txid)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered while offline (previous tx)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val fundingTx1 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val bobCommitTx1 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val fundingTx2 = testBumpFundingFees(f).signedTx
    assert(fundingTx1.txid != fundingTx2.txid)
    val bobCommitTx2 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(bobCommitTx1.txid != bobCommitTx2.txid)

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    // A previous funding tx confirms while we're offline.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx1)
    assert(aliceListener.expectMsgType[ShortChannelIdAssigned].shortIds.real.isInstanceOf[RealScidStatus.Temporary])
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx1.txid)
    alice2blockchain.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)
    awaitCond(alice.stateData.isInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_READY])
    assert(alice.stateName == OFFLINE)

    // Bob broadcasts his commit tx.
    alice ! WatchFundingSpentTriggered(bobCommitTx1)
    assert(aliceListener.expectMsgType[TransactionPublished].tx.txid == bobCommitTx1.txid)
    val claimAnchor = alice2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx]
    val claimMain = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMain.input.txid == bobCommitTx1.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx1.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.tx.txid)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered after restart (remote commit)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val bobData = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val fundingTx = aliceData.latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val (alice2, bob2) = restartNodes(f, aliceData, bobData)

    alice2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    alice2 ! WatchFundingSpentTriggered(bobData.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx)
    val claimAnchorAlice = alice2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx]
    val claimMainAlice = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMainAlice.input.txid == bobData.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobData.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainAlice.tx.txid)
    awaitCond(alice2.stateName == CLOSING)

    bob2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(bobListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(bob2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    bob2 ! WatchFundingSpentTriggered(aliceData.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx)
    val claimAnchorBob = bob2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx]
    val claimMainBob = bob2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMainBob.input.txid == aliceData.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txid)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceData.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.txid)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainBob.tx.txid)
    awaitCond(bob2.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered after restart (previous tx)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val fundingTx1 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val aliceCommitTx1 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val bobCommitTx1 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val fundingTx2 = testBumpFundingFees(f).signedTx
    assert(fundingTx1.txid != fundingTx2.txid)
    val bobCommitTx2 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    assert(bobCommitTx1.txid != bobCommitTx2.txid)

    val aliceData = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val bobData = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED]
    val (alice2, bob2) = restartNodes(f, aliceData, bobData)

    alice2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx1)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx1.txid)
    alice2 ! WatchFundingSpentTriggered(bobCommitTx1)
    val claimAnchorAlice = alice2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx]
    val claimMainAlice = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMainAlice.input.txid == bobCommitTx1.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx1.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainAlice.tx.txid)
    awaitCond(alice2.stateName == CLOSING)

    bob2 ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1)
    assert(bobListener.expectMsgType[TransactionConfirmed].tx == fundingTx1)
    assert(bob2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx1.txid)
    bob2 ! WatchFundingSpentTriggered(aliceCommitTx1)
    val claimAnchorBob = bob2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx]
    val claimMainBob = bob2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMainBob.input.txid == aliceCommitTx1.txid)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceCommitTx1.txid)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainBob.tx.txid)
    awaitCond(bob2.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (unrecognized commit)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv INPUT_DISCONNECTED (unsigned rbf attempt)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
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
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxComplete] // bob doesn't receive alice's tx_complete
    alice2bob.expectMsgType[CommitSig] // bob doesn't receive alice's commit_sig

    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus.isInstanceOf[RbfStatus.RbfWaitingForSigs])
    val rbfTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus.asInstanceOf[RbfStatus.RbfWaitingForSigs].signingSession.fundingTx
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus.isInstanceOf[RbfStatus.RbfInProgress])

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus.isInstanceOf[RbfStatus.RbfWaitingForSigs])
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus == RbfStatus.NoRbf)

    val aliceInit = Init(alice.underlyingActor.nodeParams.features.initFeatures())
    val bobInit = Init(bob.underlyingActor.nodeParams.features.initFeatures())
    alice ! INPUT_RECONNECTED(bob, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(alice, bobInit, aliceInit)
    assert(alice2bob.expectMsgType[ChannelReestablish].nextFundingTxId_opt.contains(rbfTx.txId))
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[ChannelReestablish].nextFundingTxId_opt.isEmpty)
    bob2alice.forward(alice)

    // Bob detects that Alice stored an old RBF attempt and tells her to abort.
    bob2alice.expectMsgType[TxAbort]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus == RbfStatus.NoRbf)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus == RbfStatus.NoRbf)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
  }

  test("recv INPUT_DISCONNECTED (signed rbf attempt)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val currentFundingTxId = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.txId
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
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig] // alice doesn't receive bob's commit_sig
    alice2bob.expectMsgType[CommitSig] // bob doesn't receive alice's commit_sig

    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus.isInstanceOf[RbfStatus.RbfWaitingForSigs])
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus.isInstanceOf[RbfStatus.RbfWaitingForSigs])
    val rbfTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus.asInstanceOf[RbfStatus.RbfWaitingForSigs].signingSession.fundingTx

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus.isInstanceOf[RbfStatus.RbfWaitingForSigs])
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus.isInstanceOf[RbfStatus.RbfWaitingForSigs])

    val aliceInit = Init(alice.underlyingActor.nodeParams.features.initFeatures())
    val bobInit = Init(bob.underlyingActor.nodeParams.features.initFeatures())
    alice ! INPUT_RECONNECTED(bob, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(alice, bobInit, aliceInit)
    assert(alice2bob.expectMsgType[ChannelReestablish].nextFundingTxId_opt.contains(rbfTx.txId))
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[ChannelReestablish].nextFundingTxId_opt.contains(rbfTx.txId))
    bob2alice.forward(alice)

    // Alice and Bob exchange signatures and complete the RBF attempt.
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)
    val nextFundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction]
    assert(aliceListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    assert(bobListener.expectMsgType[TransactionPublished].tx.txid == nextFundingTx.signedTx.txid)
    assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == nextFundingTx.signedTx.txid)
    assert(currentFundingTxId != nextFundingTx.txId)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus == RbfStatus.NoRbf)
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfStatus == RbfStatus.NoRbf)
  }

  def receiveError(f: FixtureParam): Unit = {
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "dual funding d34d")
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    alice.stateData.asInstanceOf[DATA_CLOSING].commitments.params.commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat => alice2blockchain.expectMsgType[PublishReplaceableTx] // claim anchor
      case Transactions.DefaultCommitmentFormat => ()
    }
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    receiveError(f)
  }

  test("recv Error (simple taproot channels)", Tag(ChannelStateTestsTags.OptionSimpleTaprootStaging), Tag(ChannelStateTestsTags.DualFunding)) { f =>
    receiveError(f)
  }

  test("recv Error (remote commit published)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "force-closing channel, bye-bye")
    awaitCond(alice.stateName == CLOSING)
    aliceListener.expectMsgType[ChannelAborted]
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == aliceCommitTx.txid)
    val claimAnchorLocal = alice2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx]
    val claimMainLocal = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMainLocal.input.txid == aliceCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainLocal.tx.txid)
    // Bob broadcasts his commit tx as well.
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    alice2blockchain.expectMsgType[WatchOutputSpent]
    val claimAnchorRemote = alice2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx]
    val claimMainRemote = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMainRemote.input.txid == bobCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainRemote.tx.txid)
  }

  test("recv Error (previous tx confirms)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val fundingTx1 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val aliceCommitTx1 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx
    val bobCommitTx1 = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx
    assert(aliceCommitTx1.input.outPoint.txid == fundingTx1.txid)
    assert(bobCommitTx1.input.outPoint.txid == fundingTx1.txid)
    val fundingTx2 = testBumpFundingFees(f).signedTx
    assert(fundingTx1.txid != fundingTx2.txid)
    val aliceCommitTx2 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx
    assert(aliceCommitTx2.input.outPoint.txid == fundingTx2.txid)

    // Alice receives an error and force-closes using the latest funding transaction.
    alice ! Error(ByteVector32.Zeroes, "dual funding d34d")
    awaitCond(alice.stateName == CLOSING)
    aliceListener.expectMsgType[ChannelAborted]
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == aliceCommitTx2.tx.txid)
    val claimAnchor2 = alice2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx]
    val claimMain2 = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMain2.input.txid == aliceCommitTx2.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceCommitTx2.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain2.tx.txid)

    // A previous funding transaction confirms, so Alice publishes the corresponding commit tx.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx1)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx1)
    alice2blockchain.expectMsgType[WatchOutputSpent]
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx1.txid)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == aliceCommitTx1.tx.txid)
    val claimAnchor1 = alice2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx]
    val claimMain1 = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMain1.input.txid == aliceCommitTx1.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceCommitTx1.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain1.tx.txid)

    // Bob publishes his commit tx, Alice reacts by spending her remote main output.
    alice ! WatchFundingSpentTriggered(bobCommitTx1.tx)
    alice2blockchain.expectMsgType[WatchOutputSpent]
    val claimAnchorRemote = alice2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx]
    val claimMainRemote = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMainRemote.input.txid == bobCommitTx1.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx1.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainRemote.tx.txid)
    assert(alice.stateName == CLOSING)
  }

  test("recv Error (nothing at stake)", Tag(ChannelStateTestsTags.DualFunding), Tag(noFundingContribution)) { f =>
    import f._
    val commitTx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    bob ! Error(ByteVector32.Zeroes, "please help me recover my funds")
    // We have nothing at stake, but we publish our commitment to help our peer recover their funds more quickly.
    awaitCond(bob.stateName == CLOSING)
    bobListener.expectMsgType[ChannelAborted]
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == commitTx.txid)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == commitTx.txid)
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
    val commitTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    aliceListener.expectMsgType[ChannelAborted]
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == commitTx.txid)
    val claimMain = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMain.input.txid == commitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == commitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.tx.txid)
  }

  def restartNodes(f: FixtureParam, aliceData: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED, bobData: DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED): (TestFSMRef[ChannelState, ChannelData, Channel], TestFSMRef[ChannelState, ChannelData, Channel]) = {
    import f._

    val (aliceNodeParams, bobNodeParams) = (alice.underlyingActor.nodeParams, bob.underlyingActor.nodeParams)
    val (alicePeer, bobPeer) = (alice.getParent, bob.getParent)

    alice.stop()
    bob.stop()

    val alice2 = TestFSMRef(new Channel(aliceNodeParams, wallet, bobNodeParams.nodeId, alice2blockchain.ref, TestProbe().ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer)
    alice2 ! INPUT_RESTORED(aliceData)
    alice2blockchain.expectMsgType[SetChannelId]
    // When restoring, we watch confirmation of all potential funding transactions to detect offline force-closes.
    aliceData.allFundingTxs.foreach(f => alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == f.sharedTx.txId)
    awaitCond(alice2.stateName == OFFLINE)

    val bob2 = TestFSMRef(new Channel(bobNodeParams, wallet, aliceNodeParams.nodeId, bob2blockchain.ref, TestProbe().ref, FakeTxPublisherFactory(bob2blockchain)), bobPeer)
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
