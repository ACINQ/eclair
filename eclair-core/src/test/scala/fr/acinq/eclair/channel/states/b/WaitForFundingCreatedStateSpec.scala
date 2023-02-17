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

import akka.actor.ActorRef
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{Btc, ByteVector32, SatoshiLong}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, ToMilliSatoshiConversion}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForFundingCreatedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, bob2blockchain: TestProbe, listener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    import com.softwaremill.quicklens._
    val aliceNodeParams = Alice.nodeParams
      .modify(_.channelConf.maxFundingSatoshis).setToIf(test.tags.contains(ChannelStateTestsTags.Wumbo))(Btc(100))
    val bobNodeParams = Bob.nodeParams
      .modify(_.channelConf.maxFundingSatoshis).setToIf(test.tags.contains(ChannelStateTestsTags.Wumbo))(Btc(100))

    val (fundingSatoshis, pushMsat) = if (test.tags.contains("funder_below_commit_fees")) {
      (1_000_100 sat, (1_000_000 sat).toMilliSatoshi) // toLocal = 100 satoshis
    } else if (test.tags.contains(ChannelStateTestsTags.Wumbo)) {
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
      bob.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelAborted])
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, fundingSatoshis, dualFunded = false, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Some(pushMsat), requireConfirmedInputs = false, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, None, dualFunded = false, None, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
      withFixture(test.toNoArgTest(FixtureParam(bob, alice2bob, bob2alice, bob2blockchain, listener)))
    }
  }

  test("recv FundingCreated") { f =>
    import f._
    alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    bob2alice.expectMsgType[FundingSigned]
    bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
    val watchConfirmed = bob2blockchain.expectMsgType[WatchFundingConfirmed]
    assert(watchConfirmed.minDepth == Alice.nodeParams.channelConf.minDepthBlocks)
  }

  test("recv FundingCreated (wumbo)", Tag(ChannelStateTestsTags.Wumbo)) { f =>
    import f._
    alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    bob2alice.expectMsgType[FundingSigned]
    bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
    val watchConfirmed = bob2blockchain.expectMsgType[WatchFundingConfirmed]
    // when we are fundee, we use a higher min depth for wumbo channels
    assert(watchConfirmed.minDepth > Bob.nodeParams.channelConf.minDepthBlocks)
  }

  test("recv FundingCreated (funder can't pay fees)", Tag("funder_below_commit_fees")) { f =>
    import f._
    val fees = Transactions.weight2fee(TestConstants.feeratePerKw, Transactions.DefaultCommitmentFormat.commitWeight)
    val missing = fees - 100.sat
    val fundingCreated = alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(fundingCreated.temporaryChannelId, CannotAffordFirstCommitFees(fundingCreated.temporaryChannelId, missing, fees).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv Error") { f =>
    import f._
    bob ! Error(ByteVector32.Zeroes, "oops")
    listener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    bob ! c
    sender.expectMsg(RES_SUCCESS(c, ByteVector32.Zeroes))
    listener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CMD_CLOSE (with noSender)") { f =>
    import f._
    val sender = TestProbe()
    // this makes sure that our backward-compatibility hack for the ask pattern (which uses context.sender as reply-to)
    // works before we fully transition to akka typed
    val c = CMD_CLOSE(ActorRef.noSender, None, None)
    sender.send(bob, c)
    sender.expectMsg(RES_SUCCESS(c, ByteVector32.Zeroes))
    listener.expectMsgType[ChannelAborted]
    awaitCond(bob.stateName == CLOSED)
  }

}
