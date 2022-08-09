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
import fr.acinq.eclair.blockchain.NoOpOnChainWallet
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.wire.protocol.{AcceptDualFundedChannel, Error, Init, OpenDualFundedChannel}
import fr.acinq.eclair.{Features, TestConstants, TestKitBaseClass}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

class WaitForAcceptDualFundedChannelStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], open: OpenDualFundedChannel, aliceOrigin: TestProbe, alice2bob: TestProbe, bob2alice: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init(wallet = new NoOpOnChainWallet())
    import setup._

    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags.Private
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    val nonInitiatorContribution = if (test.tags.contains(ChannelStateTestsTags.DualFundingContribution)) Some(TestConstants.nonInitiatorFundingSatoshis) else None
    within(30 seconds) {
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = true, TestConstants.anchorOutputsFeeratePerKw, TestConstants.feeratePerKw, None, aliceParams, alice2bob.ref, bobInit, ChannelFlags.Private, channelConfig, channelType)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, nonInitiatorContribution, dualFunded = true, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      val open = alice2bob.expectMsgType[OpenDualFundedChannel]
      alice2bob.forward(bob, open)
      awaitCond(alice.stateName == WAIT_FOR_ACCEPT_DUAL_FUNDED_CHANNEL)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, open, aliceOrigin, alice2bob, bob2alice)))
    }
  }

  test("recv AcceptDualFundedChannel", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    assert(accept.upfrontShutdownScript_opt.isEmpty)
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = false)))
    assert(accept.fundingAmount == 0.sat)

    val listener = TestProbe()
    alice.underlyingActor.context.system.eventStream.subscribe(listener.ref, classOf[ChannelIdAssigned])
    bob2alice.forward(alice, accept)
    assert(listener.expectMsgType[ChannelIdAssigned].channelId == Helpers.computeChannelId(open, accept))

    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_INTERNAL)
    val channelFeatures = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL].channelFeatures
    assert(channelFeatures.channelType == ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = false))
    assert(channelFeatures.hasFeature(Features.DualFunding))
    aliceOrigin.expectNoMessage()
  }

  test("recv AcceptDualFundedChannel (with non-initiator contribution)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.DualFundingContribution), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    assert(accept.upfrontShutdownScript_opt.isEmpty)
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = false)))
    assert(accept.fundingAmount == TestConstants.nonInitiatorFundingSatoshis)
    bob2alice.forward(alice, accept)
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_INTERNAL)
  }

  test("recv AcceptDualFundedChannel (invalid max accepted htlcs)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    val invalidMaxAcceptedHtlcs = Channel.MAX_ACCEPTED_HTLCS + 1
    alice ! accept.copy(maxAcceptedHtlcs = invalidMaxAcceptedHtlcs)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, invalidMaxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS).getMessage))
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
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    alice ! Error(ByteVector32.Zeroes, "dual funding not supported")
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv CMD_CLOSE", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_SUCCESS(c, ByteVector32.Zeroes))
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[ChannelOpenResponse.ChannelClosed]
  }

  test("recv INPUT_DISCONNECTED", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    alice ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv TickChannelOpenTimeout", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    alice ! TickChannelOpenTimeout
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

}
