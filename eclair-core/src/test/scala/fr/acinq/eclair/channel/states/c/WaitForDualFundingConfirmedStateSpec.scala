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

import akka.actor.Status
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, SingleKeyOnChainWallet}
import fr.acinq.eclair.channel.InteractiveTxBuilder.FullySignedSharedTransaction
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.ProcessCurrentBlockHeight
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, SetChannelId}
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, TestConstants, TestKitBaseClass}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

class WaitForDualFundingConfirmedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, aliceListener: TestProbe, bobListener: TestProbe, wallet: SingleKeyOnChainWallet)

  override def withFixture(test: OneArgTest): Outcome = {
    val wallet = new SingleKeyOnChainWallet()
    val setup = init(wallet_opt = Some(wallet), tags = test.tags)
    import setup._

    val aliceListener = TestProbe()
    alice.underlying.system.eventStream.subscribe(aliceListener.ref, classOf[TransactionPublished])
    alice.underlying.system.eventStream.subscribe(aliceListener.ref, classOf[TransactionConfirmed])
    val bobListener = TestProbe()
    bob.underlying.system.eventStream.subscribe(bobListener.ref, classOf[TransactionPublished])
    bob.underlying.system.eventStream.subscribe(bobListener.ref, classOf[TransactionConfirmed])

    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags.Private
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    val bobContribution = if (test.tags.contains("no-funding-contribution")) None else Some(TestConstants.nonInitiatorFundingSatoshis)
    within(30 seconds) {
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = true, TestConstants.feeratePerKw, TestConstants.feeratePerKw, None, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, bobContribution, dualFunded = true, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
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
      assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTx.txid)
      alice2bob.expectMsgType[TxSignatures]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
      // Bob publishes the funding tx.
      assert(bobListener.expectMsgType[TransactionPublished].tx.txid == fundingTx.txid)
      assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTx.txid)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, aliceListener, bobListener, wallet)))
    }
  }

  test("recv TxSignatures (duplicate)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val aliceSigs = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.localSigs
    val bobSigs = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.localSigs
    alice2bob.forward(bob, aliceSigs)
    bob2alice.forward(alice, bobSigs)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    assert(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("re-transmit TxSignatures on reconnection", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val aliceInit = Init(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.localParams.initFeatures)
    val bobInit = Init(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.localParams.initFeatures)
    val aliceSigs = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.localSigs
    val bobSigs = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.localSigs

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)
    alice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
    alice2bob.expectMsgType[ChannelReestablish]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.expectMsg(aliceSigs)
    bob2alice.expectMsg(bobSigs)

    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
  }

  test("recv WatchFundingConfirmedTriggered (initiator)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    alice2bob.expectMsgType[ChannelReady]
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv WatchFundingConfirmedTriggered (non-initiator)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(bobListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(bob2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    bob2alice.expectMsgType[ChannelReady]
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv WatchFundingConfirmedTriggered (rbf in progress)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val probe = TestProbe()
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    alice ! CMD_BUMP_FUNDING_FEE(probe.ref, TestConstants.feeratePerKw * 1.1, 0)
    alice2bob.expectMsgType[TxInitRbf]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAckRbf]

    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(aliceListener.expectMsgType[TransactionConfirmed].tx == fundingTx)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    alice2bob.expectMsgType[TxAbort]
    alice2bob.expectMsgType[ChannelReady]
    probe.expectMsg(Status.Failure(InvalidRbfTxConfirmed(channelId(alice))))
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("rbf funding attempt", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val probe = TestProbe()
    val fundingTx1 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction]
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.isEmpty)
    alice ! CMD_BUMP_FUNDING_FEE(probe.ref, TestConstants.feeratePerKw * 1.1, 0)
    assert(alice2bob.expectMsgType[TxInitRbf].fundingContribution_opt.contains(TestConstants.fundingSatoshis))
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[TxAckRbf].fundingContribution_opt.contains(TestConstants.nonInitiatorFundingSatoshis))
    bob2alice.forward(alice)
    probe.expectMsgType[RES_SUCCESS[CMD_BUMP_FUNDING_FEE]]

    // Alice and Bob build a new version of the funding transaction.
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
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxSignatures]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxSignatures]
    alice2bob.forward(bob)

    val fundingTx2 = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction]
    assert(fundingTx1.signedTx.txid != fundingTx2.signedTx.txid)
    assert(fundingTx1.feerate < fundingTx2.feerate)
    // The new transaction double-spends previous inputs.
    fundingTx1.signedTx.txIn.map(_.outPoint).foreach(o => assert(fundingTx2.signedTx.txIn.exists(_.outPoint == o)))
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.length == 1)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.head.fundingTx == fundingTx1)
  }

  test("rbf funding attempt failure", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val probe = TestProbe()
    val fundingTxAlice = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction]
    val fundingTxBob = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction]
    alice ! CMD_BUMP_FUNDING_FEE(probe.ref, TestConstants.feeratePerKw * 1.1, 0)
    assert(alice2bob.expectMsgType[TxInitRbf].fundingContribution_opt.contains(TestConstants.fundingSatoshis))
    alice2bob.forward(bob)
    assert(bob2alice.expectMsgType[TxAckRbf].fundingContribution_opt.contains(TestConstants.nonInitiatorFundingSatoshis))
    bob2alice.forward(alice)

    // Alice and Bob build a new version of the funding transaction.
    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob)
    val bobInput = bob2alice.expectMsgType[TxAddInput]
    bob2alice.forward(alice, bobInput.copy(previousTxOutput = 42))
    alice2bob.expectMsgType[TxAbort]
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)

    // Alice and Bob clear RBF data from their state.
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfAttempt.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx == fundingTxAlice)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.isEmpty)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].rbfAttempt.isEmpty)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx == fundingTxBob)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].previousFundingTxs.isEmpty)
  }

  test("recv CurrentBlockCount (funding in progress)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
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
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
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
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val currentBlock = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + 10
    wallet.doubleSpent = Set(fundingTx.txid)
    alice ! ProcessCurrentBlockHeight(CurrentBlockHeight(currentBlock))
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(wallet.rolledback.map(_.txid) == Seq(fundingTx.txid))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv CurrentBlockCount (funding double-spent while offline)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    val currentBlock = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + 10
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    wallet.doubleSpent = Set(fundingTx.txid)
    alice ! ProcessCurrentBlockHeight(CurrentBlockHeight(currentBlock))
    alice2bob.expectMsgType[Error]
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(wallet.rolledback.map(_.txid) == Seq(fundingTx.txid))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv CurrentBlockCount (funding timeout reached)", Tag(ChannelStateTestsTags.DualFunding), Tag("no-funding-contribution")) { f =>
    import f._
    val timeoutBlock = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + Channel.FUNDING_TIMEOUT_FUNDEE + 1
    bob ! ProcessCurrentBlockHeight(CurrentBlockHeight(timeoutBlock))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectNoMessage(100 millis)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CurrentBlockCount (funding timeout reached while offline)", Tag(ChannelStateTestsTags.DualFunding), Tag("no-funding-contribution")) { f =>
    import f._
    val timeoutBlock = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].waitingSince + Channel.FUNDING_TIMEOUT_FUNDEE + 1
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == OFFLINE)
    bob ! ProcessCurrentBlockHeight(CurrentBlockHeight(timeoutBlock))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectNoMessage(100 millis)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv ChannelReady (initiator)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    assert(channelReady.alias_opt.isDefined)
    bob2alice.forward(alice)
    alice2bob.expectNoMessage(100 millis)
    awaitCond(alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].deferred.contains(channelReady))
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv ChannelReady (initiator, no remote contribution)", Tag(ChannelStateTestsTags.DualFunding), Tag("no-funding-contribution")) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    bob ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    val bobChannelReady = bob2alice.expectMsgType[ChannelReady]
    assert(bobChannelReady.alias_opt.isDefined)
    bob2alice.forward(alice)
    val aliceChannelReady = alice2bob.expectMsgType[ChannelReady]
    assert(aliceChannelReady.alias_opt.isDefined)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    assert(alice2blockchain.expectMsgType[WatchFundingLost].txId == fundingTx.txid)
    assert(alice2blockchain.expectMsgType[WatchFundingDeeplyBuried].txId == fundingTx.txid)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv ChannelReady (non-initiator)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    val channelReady = alice2bob.expectMsgType[ChannelReady]
    assert(channelReady.alias_opt.isDefined)
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
    awaitCond(bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].deferred.contains(channelReady))
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv WatchFundingSpentTriggered while offline (remote commit)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.asInstanceOf[FullySignedSharedTransaction].signedTx
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    // The funding tx confirms while we're offline.
    alice ! WatchFundingConfirmedTriggered(BlockHeight(42000), 42, fundingTx)
    assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId == fundingTx.txid)
    alice2blockchain.expectNoMessage(100 millis)
    // Bob broadcasts his commit tx.
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.localCommit.commitTxAndRemoteSig.commitTx
    alice ! WatchFundingSpentTriggered(bobCommitTx.tx)
    val claimMain = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMain.input.txid == bobCommitTx.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.tx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.tx.txid)
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "dual funding d34d")
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // claim-main-delayed
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }

  test("recv Error (remote commit published)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "force-closing channel, bye-bye")
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == aliceCommitTx.txid)
    val claimMainLocal = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMainLocal.input.txid == aliceCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainLocal.tx.txid)
    // Bob broadcasts his commit tx as well.
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    val claimMainRemote = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMainRemote.input.txid == bobCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMainRemote.tx.txid)
  }

  test("recv Error (nothing at stake)", Tag(ChannelStateTestsTags.DualFunding), Tag("no-funding-contribution")) { f =>
    import f._
    val commitTx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    bob ! Error(ByteVector32.Zeroes, "please help me recover my funds")
    // We have nothing at stake, but we publish our commitment to help our peer recover their funds more quickly.
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == commitTx.txid)
    bob2blockchain.expectNoMessage(100 millis)
    awaitCond(bob.stateName == CLOSED)
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
    val commitTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == commitTx.txid)
    val claimMain = alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx]
    assert(claimMain.input.txid == commitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == commitTx.txid)
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == claimMain.tx.txid)
  }

}
