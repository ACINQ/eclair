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

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.NoOpOnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, TestConstants, TestKitBaseClass, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

class WaitForDualFundingLockedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init(tags = test.tags)
    import setup._

    val channelConfig = ChannelConfig.standard
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = true, TestConstants.anchorOutputsFeeratePerKw, TestConstants.feeratePerKw, None, aliceParams, alice2bob.ref, bobInit, ChannelFlags.Private, channelConfig, channelType)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, Some(TestConstants.nonInitiatorFundingSatoshis), dualFunded = true, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      alice2blockchain.expectMsgType[SetChannelId] // temporary channel id
      bob2blockchain.expectMsgType[SetChannelId] // temporary channel id
      alice2bob.expectMsgType[OpenDualFundedChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptDualFundedChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[SetChannelId] // final channel id
      bob2blockchain.expectMsgType[SetChannelId] // final channel id

      val privKey = randomKey()
      fundDualFundingChannel(alice, privKey, TestConstants.fundingSatoshis + 75_000.sat, 60_000.sat, bob, privKey, TestConstants.nonInitiatorFundingSatoshis + 25_000.sat, 20_000.sat, alice2bob, bob2alice)
      signDualFundedChannel(alice, privKey, TestConstants.fundingSatoshis + 75_000.sat, bob, privKey, TestConstants.nonInitiatorFundingSatoshis + 25_000.sat, alice2bob, bob2alice, alice2blockchain, bob2blockchain)

      val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].fundingTx.tx.buildUnsignedTx()
      alice ! WatchFundingConfirmedTriggered(BlockHeight(TestConstants.defaultBlockHeight), 42, fundingTx)
      assert(alice2blockchain.expectMsgType[WatchFundingSpent].txId === fundingTx.txid)
      bob ! WatchFundingConfirmedTriggered(BlockHeight(TestConstants.defaultBlockHeight), 42, fundingTx)
      assert(bob2blockchain.expectMsgType[WatchFundingSpent].txId === fundingTx.txid)
      awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_LOCKED)
      awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_LOCKED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)))
    }
  }

  test("recv FundingLocked", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice.underlyingActor.nodeParams.db.peers.addOrUpdateRelayFees(bob.underlyingActor.nodeParams.nodeId, RelayFees(20 msat, 125))
    bob.underlyingActor.nodeParams.db.peers.addOrUpdateRelayFees(alice.underlyingActor.nodeParams.nodeId, RelayFees(25 msat, 90))

    alice2bob.expectMsgType[FundingLocked]
    alice2bob.forward(bob)
    awaitCond(bob.stateName == NORMAL)
    bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == NORMAL)

    val aliceCommitments = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    val aliceUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    assert(aliceUpdate.feeBaseMsat === 20.msat)
    assert(aliceUpdate.feeProportionalMillionths === 125)
    assert(aliceCommitments.localChannelReserve === aliceCommitments.commitInput.txOut.amount / 100)
    assert(aliceCommitments.localChannelReserve === aliceCommitments.remoteChannelReserve)
    val bobCommitments = bob.stateData.asInstanceOf[DATA_NORMAL].commitments
    val bobUpdate = bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    assert(bobUpdate.feeBaseMsat === 25.msat)
    assert(bobUpdate.feeProportionalMillionths === 90)
    assert(bobCommitments.localChannelReserve === aliceCommitments.remoteChannelReserve)
    assert(bobCommitments.localChannelReserve === bobCommitments.remoteChannelReserve)

    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
  }

  test("recv WatchFundingSpentTriggered (remote commit)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    // bob publishes his commitment tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_LOCKED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === bobCommitTx.txid)
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (other commit)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    alice2bob.expectMsgType[FundingLocked]
    val commitTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_LOCKED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2bob.expectMsgType[Error]
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid === commitTx.txid)
    awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val commitTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_LOCKED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "dual funding failure")
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid === commitTx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // anchor output
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // main output
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === commitTx.txid)
  }

  test("recv CMD_CLOSE", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_FAILURE(c, CommandUnavailableInThisState(channelId(alice), "close", WAIT_FOR_DUAL_FUNDING_LOCKED)))
  }

  test("recv CMD_FORCECLOSE", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    val sender = TestProbe()
    val commitTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_LOCKED].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid === commitTx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // anchor output
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // main output
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId === commitTx.txid)
  }

}
