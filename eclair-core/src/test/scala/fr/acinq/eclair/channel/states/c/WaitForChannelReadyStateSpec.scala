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
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{MilliSatoshiLong, TestConstants, TestKitBaseClass}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForChannelReadyStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  val relayFees: RelayFees = RelayFees(999 msat, 1234)

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, bob2blockchain: TestProbe, router: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags(announceChannel = test.tags.contains(ChannelStateTestsTags.ChannelsPublic))
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
    val pushMsat = if (test.tags.contains(ChannelStateTestsTags.NoPushMsat)) 0.msat else TestConstants.pushMsat
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)

    within(30 seconds) {
      alice.underlyingActor.nodeParams.db.peers.addOrUpdateRelayFees(bobParams.nodeId, relayFees)
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
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
      alice2blockchain.expectMsgType[WatchFundingSpent]
      val aliceWatchFundingConfirmed = alice2blockchain.expectMsgType[WatchFundingConfirmed]
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob2blockchain.expectMsgType[WatchFundingSpent]
      val bobWatchFundingConfirmed = bob2blockchain.expectMsgType[WatchFundingConfirmed]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
      alice ! fundingConfirmedEvent(aliceWatchFundingConfirmed, fundingTx)
      bob ! fundingConfirmedEvent(bobWatchFundingConfirmed, fundingTx)
      alice2blockchain.expectMsgType[WatchFundingLost]
      bob2blockchain.expectMsgType[WatchFundingLost]
      alice2bob.expectMsgType[ChannelReady]
      awaitCond(alice.stateName == WAIT_FOR_CHANNEL_READY)
      awaitCond(bob.stateName == WAIT_FOR_CHANNEL_READY)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, router)))
    }
  }

  test("recv ChannelReady") { f =>
    import f._
    // we have a real scid at this stage, because this isn't a zero-conf channel
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds.real.isInstanceOf[RealScidStatus.Temporary])
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    bob2alice.forward(alice)
    val initialChannelUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    // we have a real scid, but the channel is not announced so alice uses bob's alias
    assert(initialChannelUpdate.shortChannelId == channelReady.alias_opt.get)
    assert(initialChannelUpdate.feeBaseMsat == relayFees.feeBase)
    assert(initialChannelUpdate.feeProportionalMillionths == relayFees.feeProportionalMillionths)
    bob2alice.expectNoMessage(200 millis)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv ChannelReady (no alias)") { f =>
    import f._
    // we have a real scid at this stage, because this isn't a zero-conf channel
    val realScid = alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds.real.asInstanceOf[RealScidStatus.Temporary].realScid
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    val channelReadyNoAlias = channelReady.modify(_.tlvStream.records).using(_.filterNot(_.isInstanceOf[ChannelReadyTlv.ShortChannelIdTlv]))
    bob2alice.forward(alice, channelReadyNoAlias)
    val initialChannelUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    // the channel is not announced but bob didn't send an alias so we use the real scid
    assert(initialChannelUpdate.shortChannelId == realScid)
    assert(initialChannelUpdate.feeBaseMsat == relayFees.feeBase)
    assert(initialChannelUpdate.feeProportionalMillionths == relayFees.feeProportionalMillionths)
    bob2alice.expectNoMessage(200 millis)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv ChannelReady (zero-conf)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    import f._
    // zero-conf channel: we don't have a real scid
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds.real == RealScidStatus.Unknown)
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    bob2alice.forward(alice)
    val initialChannelUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    // the channel is not announced so alice uses bob's alias (we have a no real scid anyway)
    assert(initialChannelUpdate.shortChannelId == channelReady.alias_opt.get)
    assert(initialChannelUpdate.feeBaseMsat == relayFees.feeBase)
    assert(initialChannelUpdate.feeProportionalMillionths == relayFees.feeProportionalMillionths)
    bob2alice.expectNoMessage(200 millis)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv ChannelReady (zero-conf, no alias)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    import f._
    // zero-conf channel: we don't have a real scid
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds.real == RealScidStatus.Unknown)
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    val channelReadyNoAlias = channelReady.modify(_.tlvStream.records).using(_.filterNot(_.isInstanceOf[ChannelReadyTlv.ShortChannelIdTlv]))
    bob2alice.forward(alice, channelReadyNoAlias)
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].desc == "commit-tx")
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishTx].desc == "local-anchor")
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].desc == "local-main-delayed")
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv ChannelReady (public)", Tag(ChannelStateTestsTags.ChannelsPublic)) { f =>
    import f._
    // we have a real scid at this stage, because this isn't a zero-conf channel
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds.real.isInstanceOf[RealScidStatus.Temporary])
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].commitments.channelFlags.announceChannel)
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    bob2alice.forward(alice)
    val initialChannelUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    // we have a real scid, but it is not the final one (less than 6 confirmations) so alice uses bob's alias
    assert(initialChannelUpdate.shortChannelId == channelReady.alias_opt.get)
    assert(initialChannelUpdate.feeBaseMsat == relayFees.feeBase)
    assert(initialChannelUpdate.feeProportionalMillionths == relayFees.feeProportionalMillionths)
    bob2alice.expectNoMessage(200 millis)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv ChannelReady (public, zero-conf)", Tag(ChannelStateTestsTags.ChannelsPublic), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    import f._
    // zero-conf channel: we don't have a real scid
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].shortIds.real == RealScidStatus.Unknown)
    val channelReady = bob2alice.expectMsgType[ChannelReady]
    bob2alice.forward(alice)
    val initialChannelUpdate = alice.stateData.asInstanceOf[DATA_NORMAL].channelUpdate
    // the channel is not announced, so alice uses bob's alias (we have a no real scid anyway)
    assert(initialChannelUpdate.shortChannelId == channelReady.alias_opt.get)
    assert(initialChannelUpdate.feeBaseMsat == relayFees.feeBase)
    assert(initialChannelUpdate.feeProportionalMillionths == relayFees.feeProportionalMillionths)
    bob2alice.expectNoMessage(200 millis)
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv WatchFundingSpentTriggered (remote commit)") { f =>
    import f._
    // bob publishes his commitment tx
    val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(tx)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
    awaitCond(alice.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (other commit)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2bob.expectMsgType[Error]
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    awaitCond(alice.stateName == ERR_INFORMATION_LEAK)
  }

  test("recv Error") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }

  test("recv Error (nothing at stake)", Tag(ChannelStateTestsTags.NoPushMsat)) { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    bob ! Error(ByteVector32.Zeroes, "funding double-spent")
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
    val tx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_CHANNEL_READY].commitments.localCommit.commitTxAndRemoteSig.commitTx.tx
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx.txid == tx.txid)
    alice2blockchain.expectMsgType[TxPublisher.PublishTx]
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == tx.txid)
  }
}
