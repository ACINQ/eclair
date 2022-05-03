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

package fr.acinq.eclair.channel.states.b

import akka.actor.Status
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.NoOpOnChainWallet
import fr.acinq.eclair.channel.InteractiveTx.FundingContributions
import fr.acinq.eclair.channel.InteractiveTxFunder.{FundingFailed, FundingSucceeded}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.wire.protocol.{AcceptDualFundedChannel, Error, Init, OpenDualFundedChannel, TxAddInput, TxAddOutput}
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, UInt64}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

class WaitForDualFundingInternalStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], aliceOrigin: TestProbe, alice2bob: TestProbe, bob2alice: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init(wallet = new NoOpOnChainWallet())
    import setup._
    val channelConfig = ChannelConfig.standard
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = true, TestConstants.anchorOutputsFeeratePerKw, TestConstants.feeratePerKw, None, aliceParams, alice2bob.ref, bobInit, ChannelFlags.Private, channelConfig, channelType)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, Some(TestConstants.nonInitiatorFundingSatoshis), dualFunded = true, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      alice2bob.expectMsgType[OpenDualFundedChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptDualFundedChannel]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_INTERNAL)
      awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_INTERNAL)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, aliceOrigin, alice2bob, bob2alice)))
    }
  }

  test("recv FundingContributions", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val finalChannelId = channelId(alice)
    val aliceFundingParams = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL].fundingParams
    val bobFundingParams = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL].fundingParams
    assert(aliceFundingParams.isInitiator)
    assert(!bobFundingParams.isInitiator)
    assert(aliceFundingParams.fundingAmount === TestConstants.fundingSatoshis + TestConstants.nonInitiatorFundingSatoshis)
    assert(aliceFundingParams.fundingAmount === bobFundingParams.fundingAmount)
    assert(aliceFundingParams.fundingPubkeyScript === bobFundingParams.fundingPubkeyScript)

    val inputs = Seq(TxAddInput(finalChannelId, UInt64(0), Transaction(2, Nil, Nil, 0), 0, 0))
    val outputs = Seq(TxAddOutput(finalChannelId, UInt64(1), 25000 sat, hex"deadbeef"))
    alice ! FundingSucceeded(FundingContributions(inputs, outputs))
    alice2bob.expectMsgType[TxAddInput] // the initiator starts the interactive-tx protocol
    alice2bob.expectNoMessage(100 millis)
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)

    bob ! FundingSucceeded(FundingContributions(inputs, Nil))
    bob2alice.expectNoMessage(100 millis) // the non-initiator waits for the initiator's first message
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)
  }

  test("recv Status.Failure (wallet error)", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val finalChannelId = channelId(alice)
    alice ! FundingFailed(new RuntimeException("insufficient funds"))
    alice2bob.expectMsg(Error(finalChannelId, ChannelFundingError(finalChannelId).getMessage))
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]

    bob ! FundingFailed(new RuntimeException("insufficient funds"))
    bob2alice.expectMsg(Error(finalChannelId, ChannelFundingError(finalChannelId).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val finalChannelId = channelId(alice)
    alice ! Error(finalChannelId, "oops")
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]

    bob ! Error(finalChannelId, "oops")
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CMD_CLOSE", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val finalChannelId = channelId(alice)
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)

    alice ! c
    sender.expectMsg(RES_SUCCESS(c, finalChannelId))
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[ChannelOpenResponse.ChannelClosed]

    bob ! c
    sender.expectMsg(RES_SUCCESS(c, finalChannelId))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv INPUT_DISCONNECTED", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]

    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv TickChannelOpenTimeout", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    alice ! TickChannelOpenTimeout
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

}
