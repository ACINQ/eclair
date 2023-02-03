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
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.blockchain.NoOpOnChainWallet
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.states.ChannelStateTestsBase
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{TestConstants, TestKitBaseClass}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForFundingInternalStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], aliceOrigin: TestProbe, alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, listener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init(wallet_opt = Some(new NoOpOnChainWallet()), tags = test.tags)
    import setup._
    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags.Private
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    val listener = TestProbe()
    within(30 seconds) {
      alice.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelAborted])
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = false, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Some(TestConstants.initiatorPushAmount), requireConfirmedInputs = false, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, None, dualFunded = false, None, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
      withFixture(test.toNoArgTest(FixtureParam(alice, aliceOrigin, alice2bob, bob2alice, alice2blockchain, listener)))
    }
  }

  test("recv Status.Failure (wallet error)") { f =>
    import f._
    alice ! Status.Failure(new RuntimeException("insufficient funds"))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv Error") { f =>
    import f._
    alice ! Error(ByteVector32.Zeroes, "oops")
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_SUCCESS(c, ByteVector32.Zeroes))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[ChannelOpenResponse.ChannelClosed]
  }

  test("recv INPUT_DISCONNECTED") { f =>
    import f._
    alice ! INPUT_DISCONNECTED
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv TickChannelOpenTimeout") { f =>
    import f._
    alice ! TickChannelOpenTimeout
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

}
