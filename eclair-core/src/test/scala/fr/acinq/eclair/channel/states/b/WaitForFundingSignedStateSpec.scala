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

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{Btc, ByteVector32, ByteVector64}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.TestWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.channel.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.states.{StateTestsBase, StateTestsTags}
import fr.acinq.eclair.wire.protocol.{AcceptChannel, Error, FundingCreated, FundingSigned, Init, OpenChannel}
import fr.acinq.eclair.{Features, TestConstants, TestKitBaseClass}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForFundingSignedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsBase {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    import com.softwaremill.quicklens._
    val aliceNodeParams = Alice.nodeParams.modify(_.maxFundingSatoshis).setToIf(test.tags.contains(StateTestsTags.Wumbo))(Btc(100))
    val aliceParams = setLocalFeatures(Alice.channelParams, test.tags)
    val bobNodeParams = Bob.nodeParams.modify(_.maxFundingSatoshis).setToIf(test.tags.contains(StateTestsTags.Wumbo))(Btc(100))
    val bobParams = setLocalFeatures(Bob.channelParams, test.tags)

    val (fundingSatoshis, pushMsat) = if (test.tags.contains(StateTestsTags.Wumbo)) {
      (Btc(5).toSatoshi, TestConstants.pushMsat)
    } else {
      (TestConstants.fundingSatoshis, TestConstants.pushMsat)
    }

    val setup = init(aliceNodeParams, bobNodeParams)

    import setup._
    val channelConfig = ChannelConfig.standard
    val channelFeatures = ChannelFeatures(Features.empty)
    val aliceInit = Init(aliceParams.features)
    val bobInit = Init(bobParams.features)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, fundingSatoshis, pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, None, aliceParams, alice2bob.ref, bobInit, ChannelFlags.Empty, channelConfig, channelFeatures)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, bobParams, bob2alice.ref, aliceInit, channelConfig, channelFeatures)
      bob2blockchain.expectMsgType[TxPublisher.SetChannelId]
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      alice2blockchain.expectMsgType[TxPublisher.SetChannelId]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_SIGNED)
      withFixture(test.toNoArgTest(FixtureParam(alice, alice2bob, bob2alice, alice2blockchain)))
    }
  }

  test("recv FundingSigned with valid signature") { f =>
    import f._
    bob2alice.expectMsgType[FundingSigned]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    alice2blockchain.expectMsgType[WatchFundingSpent]
    val watchConfirmed = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    assert(watchConfirmed.minDepth === Alice.nodeParams.minDepthBlocks)
  }

  test("recv FundingSigned with valid signature (wumbo)", Tag(StateTestsTags.Wumbo)) { f =>
    import f._
    bob2alice.expectMsgType[FundingSigned]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    alice2blockchain.expectMsgType[WatchFundingSpent]
    val watchConfirmed = alice2blockchain.expectMsgType[WatchFundingConfirmed]
    // when we are funder, we keep our regular min depth even for wumbo channels
    assert(watchConfirmed.minDepth === Alice.nodeParams.minDepthBlocks)
  }

  test("recv FundingSigned with invalid signature") { f =>
    import f._
    // sending an invalid sig
    alice ! FundingSigned(ByteVector32.Zeroes, ByteVector64.Zeroes)
    awaitCond(alice.stateName == CLOSED)
    alice2bob.expectMsgType[Error]
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None)
    alice ! c
    sender.expectMsg(RES_SUCCESS(c, alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_SIGNED].channelId))
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv CMD_FORCECLOSE") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_FORCECLOSE(sender.ref)
    awaitCond(alice.stateName == CLOSED)
  }

  test("recv INPUT_DISCONNECTED") { f =>
    import f._
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_SIGNED].fundingTx
    assert(alice.underlyingActor.wallet.asInstanceOf[TestWallet].rolledback.isEmpty)
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == CLOSED)
    assert(alice.underlyingActor.wallet.asInstanceOf[TestWallet].rolledback.contains(fundingTx))
  }

  test("recv TickChannelOpenTimeout") { f =>
    import f._
    alice ! TickChannelOpenTimeout
    awaitCond(alice.stateName == CLOSED)
  }

}
