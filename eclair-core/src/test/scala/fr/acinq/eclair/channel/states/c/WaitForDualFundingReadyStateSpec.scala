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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder.FullySignedSharedTransaction
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.publish.TxPublisher.SetChannelId
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.PimpTestFSM
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.testutils.PimpTestProbe.convert
import fr.acinq.eclair.transactions.Transactions.ClaimRemoteAnchorTx
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, TestConstants, TestKitBaseClass}
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

class WaitForDualFundingReadyStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alicePeer: TestProbe, bobPeer: TestProbe, alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, listener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init(tags = test.tags)
    import setup._

    val channelFlags = ChannelFlags(announceChannel = test.tags.contains(ChannelStateTestsTags.ChannelsPublic))
    val channelParams = computeChannelParams(setup, test.tags, channelFlags)
    val listener = TestProbe()
    within(30 seconds) {
      alice.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelAborted])
      alice ! channelParams.initChannelAlice(TestConstants.fundingSatoshis, dualFunded = true, channelFlags = channelFlags)
      bob ! channelParams.initChannelBob(Some(LiquidityAds.AddFunding(TestConstants.nonInitiatorFundingSatoshis, None)), dualFunded = true)
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
      awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
      awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED)
      val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED].latestFundingTx.sharedTx.asInstanceOf[FullySignedSharedTransaction].signedTx
      if (test.tags.contains(ChannelStateTestsTags.ZeroConf)) {
        assert(alice2blockchain.expectMsgType[WatchPublished].txId == fundingTx.txid)
        assert(bob2blockchain.expectMsgType[WatchPublished].txId == fundingTx.txid)
        alice ! WatchPublishedTriggered(fundingTx)
        bob ! WatchPublishedTriggered(fundingTx)
        assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTx.txid)
        assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTx.txid)
      } else {
        assert(alice2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTx.txid)
        assert(bob2blockchain.expectMsgType[WatchFundingConfirmed].txId == fundingTx.txid)
        alice ! WatchFundingConfirmedTriggered(BlockHeight(TestConstants.defaultBlockHeight), 42, fundingTx)
        bob ! WatchFundingConfirmedTriggered(BlockHeight(TestConstants.defaultBlockHeight), 42, fundingTx)
        alice2blockchain.expectMsgType[WatchFundingSpent]
        bob2blockchain.expectMsgType[WatchFundingSpent]
      }
      awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_READY)
      awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_READY)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alicePeer, bobPeer, alice2bob, bob2alice, alice2blockchain, bob2blockchain, listener)))
    }
  }

  test("recv ChannelReady", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    alice.underlyingActor.nodeParams.db.peers.addOrUpdateRelayFees(bob.underlyingActor.nodeParams.nodeId, RelayFees(20 msat, 125))
    bob.underlyingActor.nodeParams.db.peers.addOrUpdateRelayFees(alice.underlyingActor.nodeParams.nodeId, RelayFees(25 msat, 90))

    val listenerA = TestProbe()
    alice.underlying.system.eventStream.subscribe(listenerA.ref, classOf[ChannelOpened])
    val listenerB = TestProbe()
    bob.underlying.system.eventStream.subscribe(listenerB.ref, classOf[ChannelOpened])

    val aliceChannelReady = alice2bob.expectMsgType[ChannelReady]
    alice2bob.forward(bob, aliceChannelReady)
    listenerB.expectMsg(ChannelOpened(bob, alice.underlyingActor.nodeParams.nodeId, channelId(bob)))
    awaitCond(bob.stateName == NORMAL)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.remoteFundingStatus == RemoteFundingStatus.Locked)
    val bobChannelReady = bob2alice.expectMsgType[ChannelReady]
    bob2alice.forward(alice, bobChannelReady)
    listenerA.expectMsg(ChannelOpened(alice, bob.underlyingActor.nodeParams.nodeId, channelId(alice)))
    awaitCond(alice.stateName == NORMAL)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.remoteFundingStatus == RemoteFundingStatus.Locked)

    // The channel is now ready to process payments.
    alicePeer.fishForMessage() {
      case e: ChannelReadyForPayments =>
        assert(e.fundingTxIndex == 0)
        assert(e.channelId == aliceChannelReady.channelId)
        true
      case _ => false
    }
    bobPeer.fishForMessage() {
      case e: ChannelReadyForPayments =>
        assert(e.fundingTxIndex == 0)
        assert(e.channelId == aliceChannelReady.channelId)
        true
      case _ => false
    }

    val aliceCommitments = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest
    assert(aliceCommitments.commitment.shortChannelId_opt.nonEmpty)
    val aliceUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    assert(aliceUpdate.shortChannelId == aliceChannelReady.alias_opt.value)
    assert(aliceUpdate.feeBaseMsat == 20.msat)
    assert(aliceUpdate.feeProportionalMillionths == 125)
    assert(aliceCommitments.localChannelReserve == aliceCommitments.capacity / 100)
    assert(aliceCommitments.localChannelReserve == aliceCommitments.remoteChannelReserve)
    val bobCommitments = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest
    assert(bobCommitments.commitment.shortChannelId_opt.nonEmpty)
    val bobUpdate = bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    assert(bobUpdate.shortChannelId == bobChannelReady.alias_opt.value)
    assert(bobUpdate.feeBaseMsat == 25.msat)
    assert(bobUpdate.feeProportionalMillionths == 90)
    assert(bobCommitments.localChannelReserve == aliceCommitments.remoteChannelReserve)
    assert(bobCommitments.localChannelReserve == bobCommitments.remoteChannelReserve)

    assert(alice2bob.expectMsgType[ChannelUpdate].shortChannelId == bobChannelReady.alias_opt.value)
    assert(bob2alice.expectMsgType[ChannelUpdate].shortChannelId == aliceChannelReady.alias_opt.value)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
  }

  test("recv ChannelReady (zero-conf)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    import f._

    val listenerA = TestProbe()
    alice.underlying.system.eventStream.subscribe(listenerA.ref, classOf[ChannelOpened])
    val listenerB = TestProbe()
    bob.underlying.system.eventStream.subscribe(listenerB.ref, classOf[ChannelOpened])

    val aliceChannelReady = alice2bob.expectMsgType[ChannelReady]
    alice2bob.forward(bob, aliceChannelReady)
    listenerB.expectMsg(ChannelOpened(bob, alice.underlyingActor.nodeParams.nodeId, channelId(bob)))
    awaitCond(bob.stateName == NORMAL)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.remoteFundingStatus == RemoteFundingStatus.Locked)
    val bobChannelReady = bob2alice.expectMsgType[ChannelReady]
    bob2alice.forward(alice, bobChannelReady)
    listenerA.expectMsg(ChannelOpened(alice, bob.underlyingActor.nodeParams.nodeId, channelId(alice)))
    awaitCond(alice.stateName == NORMAL)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.remoteFundingStatus == RemoteFundingStatus.Locked)

    val aliceCommitments = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest
    assert(aliceCommitments.commitment.shortChannelId_opt.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.shortChannelId == aliceChannelReady.alias_opt.value)
    assert(aliceCommitments.localChannelReserve == aliceCommitments.capacity / 100)
    assert(aliceCommitments.localChannelReserve == aliceCommitments.remoteChannelReserve)
    val bobCommitments = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest
    assert(bobCommitments.commitment.shortChannelId_opt.isEmpty)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.shortChannelId == bobChannelReady.alias_opt.value)
    assert(bobCommitments.localChannelReserve == aliceCommitments.remoteChannelReserve)
    assert(bobCommitments.localChannelReserve == bobCommitments.remoteChannelReserve)

    assert(alice2bob.expectMsgType[ChannelUpdate].shortChannelId == bobChannelReady.alias_opt.value)
    assert(bob2alice.expectMsgType[ChannelUpdate].shortChannelId == aliceChannelReady.alias_opt.value)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
  }

  test("recv ChannelReady (public channel)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._

    val aliceChannelReady = alice2bob.expectMsgType[ChannelReady]
    alice2bob.forward(bob, aliceChannelReady)
    awaitCond(bob.stateName == NORMAL)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.remoteFundingStatus == RemoteFundingStatus.Locked)
    val bobChannelReady = bob2alice.expectMsgType[ChannelReady]
    bob2alice.forward(alice, bobChannelReady)
    awaitCond(alice.stateName == NORMAL)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.active.head.remoteFundingStatus == RemoteFundingStatus.Locked)

    // Alice sends announcement_signatures to Bob.
    val aliceAnnSigs = alice2bob.expectMsgType[AnnouncementSignatures]
    alice2bob.forward(bob, aliceAnnSigs)
    val aliceChannelUpdate = alice2bob.expectMsgType[ChannelUpdate]
    assert(aliceChannelUpdate.shortChannelId == bobChannelReady.alias_opt.value)
    // Bob also sends announcement_signatures, but Alice doesn't receive it.
    bob2alice.expectMsgType[AnnouncementSignatures]
    val bobChannelUpdate = bob2alice.expectMsgType[ChannelUpdate]
    assert(bobChannelUpdate.shortChannelId == aliceChannelReady.alias_opt.value)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].lastAnnouncement_opt.nonEmpty)

    // Alice disconnects without receiving Bob's announcement_signatures.
    alice ! INPUT_DISCONNECTED
    bob ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // On reconnection, Alice retransmits channel_ready and announcement_signatures.
    val aliceInit = Init(TestConstants.Alice.nodeParams.features.initFeatures())
    val bobInit = Init(TestConstants.Bob.nodeParams.features.initFeatures())
    alice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
    alice2bob.expectMsgType[ChannelReestablish]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    // Bob does not retransmit channel_ready and announcement_signatures because he has already received both of them from Alice.
    bob2alice.expectNoMessage(100 millis)
    // Alice has already received Bob's channel_ready, but not its announcement_signatures.
    // She retransmits channel_ready and Bob will retransmit its announcement_signatures in response.
    alice2bob.expectMsgType[ChannelReady]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[AnnouncementSignatures]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].lastAnnouncement_opt.nonEmpty)
  }

  test("recv TxInitRbf", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    alice2bob.expectMsgType[ChannelReady]
    alice ! TxInitRbf(channelId(alice), 0, TestConstants.feeratePerKw * 1.1)
    alice2bob.expectMsgType[TxAbort]
    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv WatchFundingSpentTriggered (remote commit)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    // bob publishes his commitment tx
    val bobCommitTx = bob.signCommitTx()
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == bobCommitTx.txid)
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (unrecognized commit)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    alice2bob.expectMsgType[ChannelReady]
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_READY)
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val commitTx = alice.signCommitTx()
    alice ! Error(ByteVector32.Zeroes, "dual funding failure")
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == commitTx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // commit tx
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // local anchor
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == commitTx.txid)
  }

  test("recv CMD_CLOSE", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_FAILURE(c, CommandUnavailableInThisState(channelId(alice), "close", WAIT_FOR_DUAL_FUNDING_READY)))
  }

  test("recv CMD_FORCECLOSE", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val sender = TestProbe()
    val commitTx = alice.signCommitTx()
    alice ! CMD_FORCECLOSE(sender.ref)
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == commitTx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // commit tx
    alice2blockchain.expectMsgType[TxPublisher.PublishTx] // local anchor
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == commitTx.txid)
  }

}
