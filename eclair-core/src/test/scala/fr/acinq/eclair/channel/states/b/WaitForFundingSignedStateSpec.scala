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

package fr.acinq.eclair.channel.states.b

import akka.actor.Status
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{Btc, ByteVector32, ByteVector64, SatoshiLong}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.wire.protocol.{AcceptChannel, Error, FundingCreated, FundingSigned, Init, OpenChannel}
import fr.acinq.eclair.{TestConstants, TestKitBaseClass}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForFundingSignedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], aliceOrigin: TestProbe, alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, listener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    import com.softwaremill.quicklens._
    val aliceNodeParams = Alice.nodeParams
      .modify(_.channelConf.maxFundingSatoshis).setToIf(test.tags.contains(ChannelStateTestsTags.Wumbo))(Btc(100))
    val bobNodeParams = Bob.nodeParams
      .modify(_.channelConf.maxFundingSatoshis).setToIf(test.tags.contains(ChannelStateTestsTags.Wumbo))(Btc(100))

    val (fundingSatoshis, pushMsat) = if (test.tags.contains(ChannelStateTestsTags.Wumbo)) {
      (Btc(5).toSatoshi, TestConstants.initiatorPushAmount)
    } else {
      (TestConstants.fundingSatoshis, TestConstants.initiatorPushAmount)
    }

    val setup = init(aliceNodeParams, bobNodeParams, tags = test.tags)

    import setup._
    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags.Private
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    val listener = TestProbe()
    within(30 seconds) {
      alice.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelAborted])
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, fundingSatoshis, dualFunded = false, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Some(pushMsat), requireConfirmedInputs = false, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, None, dualFunded = false, None, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_SIGNED)
      withFixture(test.toNoArgTest(FixtureParam(alice, aliceOrigin, alice2bob, bob2alice, alice2blockchain, listener)))
    }
  }

  test("recv FundingSigned with valid signature") { f =>
    import f._
    val listener = TestProbe()
    alice.underlying.system.eventStream.subscribe(listener.ref, classOf[TransactionPublished])
    bob2alice.expectMsgType[FundingSigned]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    val watchConfirmed = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    val fundingTxId = watchConfirmed.txId
    assert(watchConfirmed.minDepth == 1) // when funder we trust ourselves so we never wait more than 1 block
    val txPublished = listener.expectMsgType[TransactionPublished]
    assert(txPublished.tx.txid == fundingTxId)
    assert(txPublished.miningFee > 0.sat)
    aliceOrigin.expectMsgType[ChannelOpenResponse.ChannelOpened]
  }

  test("recv FundingSigned with valid signature (zero-conf)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    import f._
    bob2alice.expectMsgType[FundingSigned]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    // alice doesn't watch for the funding tx to confirm, she only waits for the transaction to be published
    alice2blockchain.expectMsgType[WatchPublished]
    alice2blockchain.expectNoMessage(100 millis)
    aliceOrigin.expectMsgType[ChannelOpenResponse.ChannelOpened]
  }

  test("recv FundingSigned with valid signature (wumbo)", Tag(ChannelStateTestsTags.Wumbo)) { f =>
    import f._
    bob2alice.expectMsgType[FundingSigned]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    val watchConfirmed = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    assert(watchConfirmed.minDepth == 1) // when funder we trust ourselves so we never wait more than 1 block
    aliceOrigin.expectMsgType[ChannelOpenResponse.ChannelOpened]
  }

  test("recv FundingSigned with invalid signature") { f =>
    import f._
    // sending an invalid sig
    alice ! FundingSigned(ByteVector32.Zeroes, ByteVector64.Zeroes)
    awaitCond(alice.stateName == CLOSED)
    alice2bob.expectMsgType[Error]
    aliceOrigin.expectMsgType[Status.Failure]
    listener.expectMsgType[ChannelAborted]
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_SUCCESS(c, alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_SIGNED].channelId))
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[ChannelOpenResponse.ChannelClosed]
    listener.expectMsgType[ChannelAborted]
  }

  test("recv CMD_FORCECLOSE") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[ChannelOpenResponse.ChannelClosed]
    listener.expectMsgType[ChannelAborted]
  }

  test("recv INPUT_DISCONNECTED") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_SIGNED].fundingTx
    assert(alice.underlyingActor.wallet.asInstanceOf[DummyOnChainWallet].rolledback.isEmpty)
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == CLOSED)
    assert(alice.underlyingActor.wallet.asInstanceOf[DummyOnChainWallet].rolledback.contains(fundingTx))
    aliceOrigin.expectMsgType[Status.Failure]
    listener.expectMsgType[ChannelAborted]
  }

  test("recv TickChannelOpenTimeout") { f =>
    import f._
    alice ! TickChannelOpenTimeout
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
    listener.expectMsgType[ChannelAborted]
  }

}
