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

package fr.acinq.eclair.channel.states.a

import akka.actor.Status
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.wire.protocol.{AcceptDualFundedChannel, Error, Init, OpenDualFundedChannel}
import fr.acinq.eclair.{MilliSatoshiLong, TestConstants, TestKitBaseClass}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

class WaitForAcceptDualFundedChannelStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  val bobRequiresConfirmedInputs = "bob_requires_confirmed_inputs"
  val dualFundingContribution = "dual_funding_contribution"

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], open: OpenDualFundedChannel, aliceOrigin: TestProbe, alice2bob: TestProbe, bob2alice: TestProbe, listener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val bobNodeParams = if (test.tags.contains(bobRequiresConfirmedInputs)) {
      val defaultNodeParams = TestConstants.Bob.nodeParams
      defaultNodeParams.copy(channelConf = defaultNodeParams.channelConf.copy(requireConfirmedInputsForDualFunding = true))
    } else {
      TestConstants.Bob.nodeParams
    }
    val setup = init(nodeParamsB = bobNodeParams, tags = test.tags)
    import setup._

    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags.Private
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    val nonInitiatorContribution = if (test.tags.contains(dualFundingContribution)) Some(TestConstants.nonInitiatorFundingSatoshis) else None
    val nonInitiatorPushAmount = if (test.tags.contains(ChannelStateTestsTags.NonInitiatorPushAmount)) Some(TestConstants.nonInitiatorPushAmount) else None
    val listener = TestProbe()
    within(30 seconds) {
      alice.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelAborted])
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = true, TestConstants.anchorOutputsFeeratePerKw, TestConstants.feeratePerKw, None, requireConfirmedInputs = false, aliceParams, alice2bob.ref, bobInit, ChannelFlags.Private, channelConfig, channelType)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, nonInitiatorContribution, dualFunded = true, nonInitiatorPushAmount, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      val open = alice2bob.expectMsgType[OpenDualFundedChannel]
      alice2bob.forward(bob, open)
      awaitCond(alice.stateName == WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, open, aliceOrigin, alice2bob, bob2alice, listener)))
    }
  }

  test("recv AcceptDualFundedChannel", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    assert(accept.upfrontShutdownScript_opt.isEmpty)
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()))
    assert(accept.fundingAmount == 0.sat)
    assert(accept.pushAmount == 0.msat)
    assert(!accept.requireConfirmedInputs)

    val listener = TestProbe()
    alice.underlyingActor.context.system.eventStream.subscribe(listener.ref, classOf[ChannelIdAssigned])
    bob2alice.forward(alice, accept)
    assert(listener.expectMsgType[ChannelIdAssigned].channelId == Helpers.computeChannelId(open, accept))

    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)
    aliceOrigin.expectNoMessage()
  }

  test("recv AcceptDualFundedChannel (with non-initiator contribution)", Tag(ChannelStateTestsTags.DualFunding), Tag(dualFundingContribution), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    assert(accept.upfrontShutdownScript_opt.isEmpty)
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()))
    assert(accept.fundingAmount == TestConstants.nonInitiatorFundingSatoshis)
    assert(accept.pushAmount == 0.msat)
    bob2alice.forward(alice, accept)
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)
  }

  test("recv AcceptDualFundedChannel (with push amount)", Tag(ChannelStateTestsTags.DualFunding), Tag(dualFundingContribution), Tag(ChannelStateTestsTags.NonInitiatorPushAmount), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    assert(accept.upfrontShutdownScript_opt.isEmpty)
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()))
    assert(accept.fundingAmount == TestConstants.nonInitiatorFundingSatoshis)
    assert(accept.pushAmount == TestConstants.nonInitiatorPushAmount)
    bob2alice.forward(alice, accept)
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)
  }

  test("recv AcceptDualFundedChannel (require confirmed inputs)", Tag(ChannelStateTestsTags.DualFunding), Tag(dualFundingContribution), Tag(bobRequiresConfirmedInputs), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()))
    assert(accept.fundingAmount == TestConstants.nonInitiatorFundingSatoshis)
    assert(accept.requireConfirmedInputs)
    bob2alice.forward(alice, accept)
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)
  }

  test("recv AcceptDualFundedChannel (negative funding amount)", Tag(ChannelStateTestsTags.DualFunding), Tag(dualFundingContribution), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    alice ! accept.copy(fundingAmount = -1 sat)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, InvalidFundingAmount(accept.temporaryChannelId, -1 sat, 0 sat, Alice.nodeParams.channelConf.maxFundingSatoshis).getMessage))
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptDualFundedChannel (invalid push amount)", Tag(ChannelStateTestsTags.DualFunding), Tag(dualFundingContribution), Tag(ChannelStateTestsTags.NonInitiatorPushAmount), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    alice ! accept.copy(fundingAmount = 25_000 sat)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, InvalidPushAmount(accept.temporaryChannelId, TestConstants.nonInitiatorPushAmount, 25_000_000 msat).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptDualFundedChannel (invalid max accepted htlcs)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    val invalidMaxAcceptedHtlcs = Channel.MAX_ACCEPTED_HTLCS + 1
    alice ! accept.copy(maxAcceptedHtlcs = invalidMaxAcceptedHtlcs)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, invalidMaxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptDualFundedChannel (dust limit too low)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    val lowDustLimit = Channel.MIN_DUST_LIMIT - 1.sat
    alice ! accept.copy(dustLimit = lowDustLimit)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, DustLimitTooSmall(accept.temporaryChannelId, lowDustLimit, Channel.MIN_DUST_LIMIT).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptDualFundedChannel (dust limit too high)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    val highDustLimit = Alice.nodeParams.channelConf.maxRemoteDustLimit + 1.sat
    alice ! accept.copy(dustLimit = highDustLimit)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, DustLimitTooLarge(accept.temporaryChannelId, highDustLimit, Alice.nodeParams.channelConf.maxRemoteDustLimit).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptDualFundedChannel (to_self_delay too high)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    val delayTooHigh = Alice.nodeParams.channelConf.maxToLocalDelay + 1
    alice ! accept.copy(toSelfDelay = delayTooHigh)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, ToSelfDelayTooHigh(accept.temporaryChannelId, delayTooHigh, Alice.nodeParams.channelConf.maxToLocalDelay).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    alice ! Error(ByteVector32.Zeroes, "dual funding not supported")
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv CMD_CLOSE", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_SUCCESS(c, ByteVector32.Zeroes))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[ChannelOpenResponse.ChannelClosed]
  }

  test("recv INPUT_DISCONNECTED", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    alice ! INPUT_DISCONNECTED
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv TickChannelOpenTimeout", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    alice ! TickChannelOpenTimeout
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

}
