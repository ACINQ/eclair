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

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, SatoshiLong}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.wire.protocol.{AcceptDualFundedChannel, Error, Init, OpenDualFundedChannel}
import fr.acinq.eclair.{Features, TestConstants, TestKitBaseClass, randomBytes32}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

class WaitForOpenDualFundedChannelStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, aliceListener: TestProbe, bobListener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._

    val aliceListener = TestProbe()
    alice.underlyingActor.context.system.eventStream.subscribe(aliceListener.ref, classOf[ChannelCreated])
    alice.underlyingActor.context.system.eventStream.subscribe(aliceListener.ref, classOf[ChannelIdAssigned])
    val bobListener = TestProbe()
    bob.underlyingActor.context.system.eventStream.subscribe(bobListener.ref, classOf[ChannelCreated])
    bob.underlyingActor.context.system.eventStream.subscribe(bobListener.ref, classOf[ChannelIdAssigned])

    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags.Private
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = true, TestConstants.anchorOutputsFeeratePerKw, TestConstants.feeratePerKw, None, aliceParams, alice2bob.ref, bobInit, ChannelFlags.Private, channelConfig, channelType)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, None, dualFunded = true, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      awaitCond(bob.stateName == WAIT_FOR_OPEN_DUAL_FUNDED_CHANNEL)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, aliceListener, bobListener)))
    }
  }

  test("recv OpenDualFundedChannel", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    val open = alice2bob.expectMsgType[OpenDualFundedChannel]
    assert(open.upfrontShutdownScript_opt.isEmpty)
    assert(open.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = false)))
    assert(open.fundingFeerate == TestConstants.feeratePerKw)
    assert(open.commitmentFeerate == TestConstants.anchorOutputsFeeratePerKw)
    assert(open.lockTime == TestConstants.defaultBlockHeight)

    val initiatorEvent = aliceListener.expectMsgType[ChannelCreated]
    assert(initiatorEvent.isInitiator)
    assert(initiatorEvent.temporaryChannelId == ByteVector32.Zeroes)

    alice2bob.forward(bob)

    val nonInitiatorEvent = bobListener.expectMsgType[ChannelCreated]
    assert(!nonInitiatorEvent.isInitiator)
    assert(nonInitiatorEvent.temporaryChannelId == ByteVector32.Zeroes)

    val accept = bob2alice.expectMsgType[AcceptDualFundedChannel]
    val channelIdAssigned = bobListener.expectMsgType[ChannelIdAssigned]
    assert(channelIdAssigned.temporaryChannelId == ByteVector32.Zeroes)
    assert(channelIdAssigned.channelId == Helpers.computeChannelId(open, accept))

    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_INTERNAL)
    val channelFeatures = bob.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL].channelFeatures
    assert(channelFeatures.channelType == ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = false))
    assert(channelFeatures.hasFeature(Features.DualFunding))
  }

  test("recv OpenDualFundedChannel (invalid chain)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenDualFundedChannel]
    val chain = randomBytes32()
    bob ! open.copy(chainHash = chain)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, InvalidChainHash(open.temporaryChannelId, Block.RegtestGenesisBlock.hash, chain).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenDualFundedChannel (funding too low)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenDualFundedChannel]
    bob ! open.copy(fundingAmount = 100 sat)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, InvalidFundingAmount(open.temporaryChannelId, 100 sat, Bob.nodeParams.channelConf.minFundingSatoshis(false), Bob.nodeParams.channelConf.maxFundingSatoshis).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenDualFundedChannel (invalid max accepted htlcs)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenDualFundedChannel]
    val invalidMaxAcceptedHtlcs = Channel.MAX_ACCEPTED_HTLCS + 1
    bob ! open.copy(maxAcceptedHtlcs = invalidMaxAcceptedHtlcs)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, InvalidMaxAcceptedHtlcs(open.temporaryChannelId, invalidMaxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenDualFundedChannel (to_self_delay too high)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenDualFundedChannel]
    val delayTooHigh = Alice.nodeParams.channelConf.maxToLocalDelay + 1
    bob ! open.copy(toSelfDelay = delayTooHigh)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, ToSelfDelayTooHigh(open.temporaryChannelId, delayTooHigh, Alice.nodeParams.channelConf.maxToLocalDelay).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenDualFundedChannel (dust limit too high)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenDualFundedChannel]
    val dustLimitTooHigh = Bob.nodeParams.channelConf.maxRemoteDustLimit + 1.sat
    bob ! open.copy(dustLimit = dustLimitTooHigh)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, DustLimitTooLarge(open.temporaryChannelId, dustLimitTooHigh, Bob.nodeParams.channelConf.maxRemoteDustLimit).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenDualFundedChannel (dust limit too small)", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenDualFundedChannel]
    val dustLimitTooSmall = Channel.MIN_DUST_LIMIT - 1.sat
    bob ! open.copy(dustLimit = dustLimitTooSmall)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, DustLimitTooSmall(open.temporaryChannelId, dustLimitTooSmall, Channel.MIN_DUST_LIMIT).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    bob ! Error(ByteVector32.Zeroes, "dual funding not supported")
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CMD_CLOSE", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val sender = TestProbe()
    val cmd = CMD_CLOSE(sender.ref, None, None)
    bob ! cmd
    sender.expectMsg(RES_SUCCESS(cmd, ByteVector32.Zeroes))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv INPUT_DISCONNECTED", Tag(ChannelStateTestsTags.DualFunding), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    bob ! INPUT_DISCONNECTED
    awaitCond(bob.stateName == CLOSED)
  }

}
