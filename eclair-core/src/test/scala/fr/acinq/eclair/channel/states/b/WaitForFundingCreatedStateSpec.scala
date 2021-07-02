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
import fr.acinq.bitcoin.{Btc, ByteVector32, SatoshiLong}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.states.{StateTestsBase, StateTestsTags}
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, ToMilliSatoshiConversion}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForFundingCreatedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsBase {

  case class FixtureParam(bob: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, bob2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    import com.softwaremill.quicklens._
    val aliceNodeParams = Alice.nodeParams.modify(_.maxFundingSatoshis).setToIf(test.tags.contains(StateTestsTags.Wumbo))(Btc(100))
    val aliceParams = setChannelFeatures(Alice.channelParams, test.tags)
    val bobNodeParams = Bob.nodeParams.modify(_.maxFundingSatoshis).setToIf(test.tags.contains(StateTestsTags.Wumbo))(Btc(100))
    val bobParams = setChannelFeatures(Bob.channelParams, test.tags)

    val (fundingSatoshis, pushMsat) = if (test.tags.contains("funder_below_reserve")) {
      (1000100 sat, (1000000 sat).toMilliSatoshi) // toLocal = 100 satoshis
    } else if (test.tags.contains(StateTestsTags.Wumbo)) {
      (Btc(5).toSatoshi, TestConstants.pushMsat)
    } else {
      (TestConstants.fundingSatoshis, TestConstants.pushMsat)
    }

    val setup = init(aliceNodeParams, bobNodeParams)

    import setup._
    val channelVersion = ChannelVersion.STANDARD
    val aliceInit = Init(aliceParams.features)
    val bobInit = Init(bobParams.features)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, fundingSatoshis, pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, None, aliceParams, alice2bob.ref, bobInit, ChannelFlags.Empty, channelVersion)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, bobParams, bob2alice.ref, aliceInit, channelVersion)
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
      withFixture(test.toNoArgTest(FixtureParam(bob, alice2bob, bob2alice, bob2blockchain)))
    }
  }

  test("recv FundingCreated") { f =>
    import f._
    alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    bob2alice.expectMsgType[FundingSigned]
    bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
    bob2blockchain.expectMsgType[WatchFundingSpent]
    val watchConfirmed = bob2blockchain.expectMsgType[WatchFundingConfirmed]
    assert(watchConfirmed.minDepth === Alice.nodeParams.minDepthBlocks)
  }

  test("recv FundingCreated (wumbo)", Tag(StateTestsTags.Wumbo)) { f =>
    import f._
    alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    bob2alice.expectMsgType[FundingSigned]
    bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
    bob2blockchain.expectMsgType[WatchFundingSpent]
    val watchConfirmed = bob2blockchain.expectMsgType[WatchFundingConfirmed]
    // when we are fundee, we use a higher min depth for wumbo channels
    assert(watchConfirmed.minDepth > Bob.nodeParams.minDepthBlocks)
  }

  test("recv FundingCreated (funder can't pay fees)", Tag("funder_below_reserve")) { f =>
    import f._
    val fees = Transactions.weight2fee(TestConstants.feeratePerKw, Transactions.DefaultCommitmentFormat.commitWeight)
    val reserve = Bob.channelParams.channelReserve
    val missing = 100.sat - fees - reserve
    val fundingCreated = alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    val error = bob2alice.expectMsgType[Error]
    assert(error === Error(fundingCreated.temporaryChannelId, s"can't pay the fee: missing=${-missing} reserve=$reserve fees=$fees"))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv Error") { f =>
    import f._
    bob ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    bob ! c
    sender.expectMsg(RES_SUCCESS(c, ByteVector32.Zeroes))
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
    awaitCond(bob.stateName == CLOSED)
  }

}
