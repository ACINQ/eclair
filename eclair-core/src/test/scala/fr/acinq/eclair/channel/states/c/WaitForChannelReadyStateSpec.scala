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

package fr.acinq.eclair.channel.states.c

import akka.testkit.{TestFSMRef, TestProbe}
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, TestConstants, TestKitBaseClass}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForChannelReadyStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  val relayFees: RelayFees = RelayFees(999 msat, 1234)

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, router: TestProbe, aliceListener: TestProbe, bobListener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init(tags = test.tags)
    import setup._
    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags(announceChannel = test.tags.contains(ChannelStateTestsTags.ChannelsPublic))
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
    val pushMsat = if (test.tags.contains(ChannelStateTestsTags.NoPushAmount)) None else Some(TestConstants.initiatorPushAmount)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    val aliceListener = TestProbe()
    val bobListener = TestProbe()

    within(30 seconds) {
      alice.underlying.system.eventStream.subscribe(aliceListener.ref, classOf[ChannelAborted])
      bob.underlying.system.eventStream.subscribe(bobListener.ref, classOf[ChannelAborted])
      alice.underlyingActor.nodeParams.db.peers.addOrUpdateRelayFees(bobParams.nodeId, relayFees)
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = false, TestConstants.feeratePerKw, TestConstants.feeratePerKw, pushMsat, requireConfirmedInputs = false, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, None, dualFunded = false, None, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[FundingSigned]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx_opt.get
      if (test.tags.contains(ChannelStateTestsTags.ZeroConf)) {
        alice2blockchain.expectMsgType[WatchPublished]
        bob2blockchain.expectMsgType[WatchPublished]
        alice ! WatchPublishedTriggered(fundingTx)
        bob ! WatchPublishedTriggered(fundingTx)
        alice2blockchain.expectMsgType[WatchFundingConfirmed]
        bob2blockchain.expectMsgType[WatchFundingConfirmed]
      } else {
        alice2blockchain.expectMsgType[WatchFundingConfirmed]
        bob2blockchain.expectMsgType[WatchFundingConfirmed]
        alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
        bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
        alice2blockchain.expectMsgType[WatchFundingSpent]
        bob2blockchain.expectMsgType[WatchFundingSpent]
      }
      alice2bob.expectMsgType[ChannelReady]
      awaitCond(alice.stateName == WAIT_FOR_CHANNEL_READY)
      awaitCond(bob.stateName == WAIT_FOR_CHANNEL_READY)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, router, aliceListener, bobListener)))
    }
  }

  test("recv ChannelReady") { f =>
    import f._
    // we have a real scid at this stage, because this isn't a zero-conf channel
    val aliceIds = alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds
    assert(aliceIds.real.isInstanceOf[RealScidStatus.Temporary])
    val bobIds = bob.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds
    assert(bobIds.real.isInstanceOf[RealScidStatus.Temporary])
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    assert(channelReady.alias_opt.contains(bobIds.localAlias))
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelOpened])
    bob2alice.forward(alice)
    listener.expectMsg(ChannelOpened(alice, bob.underlyingActor.nodeParams.nodeId, channelId(alice)))
    val initialChannelUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    assert(initialChannelUpdate.shortChannelId == aliceIds.localAlias)
    assert(initialChannelUpdate.feeBaseMsat == relayFees.feeBase)
    assert(initialChannelUpdate.feeProportionalMillionths == relayFees.feeProportionalMillionths)
    // we have a real scid, but the channel is not announced so alice uses bob's alias
    val channelUpdateSentToPeer = alice2bob.expectMsgType[ChannelUpdate]
    assert(channelUpdateSentToPeer.shortChannelId == bobIds.localAlias)
    assert(Announcements.areSameIgnoreFlags(initialChannelUpdate, channelUpdateSentToPeer))
    assert(Announcements.checkSig(channelUpdateSentToPeer, alice.underlyingActor.nodeParams.nodeId))
    alice2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    bob2alice.expectNoMessage(100 millis)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv ChannelReady (no alias)") { f =>
    import f._
    // we have a real scid at this stage, because this isn't a zero-conf channel
    val aliceIds = alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds
    val realScid = aliceIds.real.asInstanceOf[RealScidStatus.Temporary].realScid
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    val channelReadyNoAlias = channelReady.modify(_.tlvStream.records).using(_.filterNot(_.isInstanceOf[ChannelReadyTlv.ShortChannelIdTlv]))
    bob2alice.forward(alice, channelReadyNoAlias)
    val initialChannelUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    assert(initialChannelUpdate.shortChannelId == aliceIds.localAlias)
    assert(initialChannelUpdate.feeBaseMsat == relayFees.feeBase)
    assert(initialChannelUpdate.feeProportionalMillionths == relayFees.feeProportionalMillionths)
    // the channel is not announced but bob didn't send an alias so we use the real scid
    val channelUpdateSentToPeer = alice2bob.expectMsgType[ChannelUpdate]
    assert(channelUpdateSentToPeer.shortChannelId == realScid)
    assert(Announcements.areSameIgnoreFlags(initialChannelUpdate, channelUpdateSentToPeer))
    assert(Announcements.checkSig(channelUpdateSentToPeer, alice.underlyingActor.nodeParams.nodeId))
    alice2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    bob2alice.expectNoMessage(100 millis)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv ChannelReady (zero-conf)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    import f._
    // zero-conf channel: we don't have a real scid
    val aliceIds = alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds
    assert(aliceIds.real == RealScidStatus.Unknown)
    val bobIds = bob.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds
    assert(bobIds.real == RealScidStatus.Unknown)
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    assert(channelReady.alias_opt.contains(bobIds.localAlias))
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelOpened])
    bob2alice.forward(alice)
    listener.expectMsg(ChannelOpened(alice, bob.underlyingActor.nodeParams.nodeId, channelId(alice)))
    val initialChannelUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    assert(initialChannelUpdate.shortChannelId == aliceIds.localAlias)
    assert(initialChannelUpdate.feeBaseMsat == relayFees.feeBase)
    assert(initialChannelUpdate.feeProportionalMillionths == relayFees.feeProportionalMillionths)
    val channelUpdateSentToPeer = alice2bob.expectMsgType[ChannelUpdate]
    // the channel is not announced so alice uses bob's alias (we have a no real scid anyway)
    assert(channelUpdateSentToPeer.shortChannelId == bobIds.localAlias)
    assert(Announcements.areSameIgnoreFlags(initialChannelUpdate, channelUpdateSentToPeer))
    assert(Announcements.checkSig(channelUpdateSentToPeer, alice.underlyingActor.nodeParams.nodeId))
    alice2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    bob2alice.expectNoMessage(100 millis)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv ChannelReady (zero-conf, no alias)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    import f._
    // zero-conf channel: we don't have a real scid
    val aliceIds = alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds
    assert(aliceIds.real == RealScidStatus.Unknown)
    val bobIds = bob.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds
    assert(bobIds.real == RealScidStatus.Unknown)
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    val channelReadyNoAlias = channelReady.modify(_.tlvStream.records).using(_.filterNot(_.isInstanceOf[ChannelReadyTlv.ShortChannelIdTlv]))
    bob2alice.forward(alice, channelReadyNoAlias)
    val initialChannelUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    assert(initialChannelUpdate.shortChannelId == aliceIds.localAlias)
    assert(initialChannelUpdate.feeBaseMsat == relayFees.feeBase)
    assert(initialChannelUpdate.feeProportionalMillionths == relayFees.feeProportionalMillionths)
    val channelUpdateSentToPeer = alice2bob.expectMsgType[ChannelUpdate]
    // the channel is 0-conf but bob didn't provide an alias: it's a spec violation, so we use our local alias and if
    // they can't understand it, too bad for them
    assert(channelUpdateSentToPeer.shortChannelId == aliceIds.localAlias)
    assert(Announcements.areSameIgnoreFlags(initialChannelUpdate, channelUpdateSentToPeer))
    alice2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    bob2alice.expectNoMessage(100 millis)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv ChannelReady (public)", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._
    // we have a real scid at this stage, because this isn't a zero-conf channel
    val aliceIds = alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds
    assert(aliceIds.real.isInstanceOf[RealScidStatus.Temporary])
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].commitments.params.channelFlags.announceChannel)
    val bobIds = bob.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds
    assert(bobIds.real.isInstanceOf[RealScidStatus.Temporary])
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].commitments.params.channelFlags.announceChannel)
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    assert(channelReady.alias_opt.contains(bobIds.localAlias))
    bob2alice.forward(alice)
    val initialChannelUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    assert(initialChannelUpdate.shortChannelId == aliceIds.localAlias)
    assert(initialChannelUpdate.feeBaseMsat == relayFees.feeBase)
    assert(initialChannelUpdate.feeProportionalMillionths == relayFees.feeProportionalMillionths)
    val channelUpdateSentToPeer = alice2bob.expectMsgType[ChannelUpdate]
    // we have a real scid, but it is not the final one (less than 6 confirmations) so alice uses bob's alias
    assert(channelUpdateSentToPeer.shortChannelId == bobIds.localAlias)
    assert(Announcements.areSameIgnoreFlags(initialChannelUpdate, channelUpdateSentToPeer))
    assert(Announcements.checkSig(channelUpdateSentToPeer, alice.underlyingActor.nodeParams.nodeId))
    alice2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    bob2alice.expectNoMessage(100 millis)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv ChannelReady (public, zero-conf)", Tag(ChannelStateTestsTags.ChannelsPublic), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    import f._
    // zero-conf channel: we don't have a real scid
    val aliceIds = alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds
    assert(aliceIds.real == RealScidStatus.Unknown)
    val bobIds = bob.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds
    assert(bobIds.real == RealScidStatus.Unknown)
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    assert(channelReady.alias_opt.contains(bobIds.localAlias))
    bob2alice.forward(alice)
    val initialChannelUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    assert(initialChannelUpdate.shortChannelId == aliceIds.localAlias)
    assert(initialChannelUpdate.feeBaseMsat == relayFees.feeBase)
    assert(initialChannelUpdate.feeProportionalMillionths == relayFees.feeProportionalMillionths)
    val channelUpdateSentToPeer = alice2bob.expectMsgType[ChannelUpdate]
    // the channel is not announced, so alice uses bob's alias (we have a no real scid anyway)
    assert(channelUpdateSentToPeer.shortChannelId == bobIds.localAlias)
    assert(Announcements.areSameIgnoreFlags(initialChannelUpdate, channelUpdateSentToPeer))
    assert(Announcements.checkSig(channelUpdateSentToPeer, alice.underlyingActor.nodeParams.nodeId))
    alice2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    bob2alice.expectNoMessage(100 millis)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv WatchFundingSpentTriggered (remote commit)") { f =>
    import f._
    // bob publishes his commitment tx
    val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(tx)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (other commit)") { f =>
    import f._
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
  }

  test("recv Error") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }

  test("recv Error (nothing at stake)", Tag(ChannelStateTestsTags.NoPushAmount)) { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    bob ! Error(ByteVector32.Zeroes, "funding double-spent")
    bobListener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSING)
    assert(bob2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_FAILURE(c, CommandUnavailableInThisState(channelId(alice), "close", WAIT_FOR_CHANNEL_READY)))
  }

  test("recv CMD_FORCECLOSE") { f =>
    import f._
    val sender = TestProbe()
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! CMD_FORCECLOSE(sender.ref)
    aliceListener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }
}
