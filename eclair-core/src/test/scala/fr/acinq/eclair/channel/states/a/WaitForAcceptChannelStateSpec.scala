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

package fr.acinq.eclair.channel.states.a

import akka.actor.Status
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{Block, Btc, ByteVector32, SatoshiLong}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.NoOpOnChainWallet
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.wire.protocol.{AcceptChannel, ChannelTlv, Error, Init, OpenChannel, TlvStream}
import fr.acinq.eclair.{CltvExpiryDelta, FeatureSupport, Features, TestConstants, TestKitBaseClass}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForAcceptChannelStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], aliceOrigin: TestProbe, alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, listener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    import com.softwaremill.quicklens._
    val aliceNodeParams = Alice.nodeParams
      .modify(_.chainHash).setToIf(test.tags.contains("mainnet"))(Block.LivenetGenesisBlock.hash)
      .modify(_.channelConf.maxFundingSatoshis).setToIf(test.tags.contains("high-max-funding-size"))(Btc(100))
      .modify(_.channelConf.maxRemoteDustLimit).setToIf(test.tags.contains("high-remote-dust-limit"))(15000 sat)

    val bobNodeParams = Bob.nodeParams
      .modify(_.chainHash).setToIf(test.tags.contains("mainnet"))(Block.LivenetGenesisBlock.hash)
      .modify(_.channelConf.maxFundingSatoshis).setToIf(test.tags.contains("high-max-funding-size"))(Btc(100))

    val setup = init(aliceNodeParams, bobNodeParams, wallet_opt = Some(new NoOpOnChainWallet()), test.tags)

    import setup._
    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags.Private
    val (aliceParams, bobParams, defaultChannelType) = computeFeatures(setup, test.tags, channelFlags)
    val channelType = if (test.tags.contains("standard-channel-type")) ChannelTypes.Standard() else defaultChannelType
    val commitTxFeerate = if (channelType.isInstanceOf[ChannelTypes.AnchorOutputs] || channelType.isInstanceOf[ChannelTypes.AnchorOutputsZeroFeeHtlcTx]) TestConstants.anchorOutputsFeeratePerKw else TestConstants.feeratePerKw
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    val listener = TestProbe()
    within(30 seconds) {
      alice.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelAborted])
      val fundingAmount = if (test.tags.contains(ChannelStateTestsTags.Wumbo)) Btc(5).toSatoshi else TestConstants.fundingSatoshis
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, fundingAmount, dualFunded = false, commitTxFeerate, TestConstants.feeratePerKw, Some(TestConstants.initiatorPushAmount), requireConfirmedInputs = false, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, None, dualFunded = false, None, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == WAIT_FOR_ACCEPT_CHANNEL)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, aliceOrigin, alice2bob, bob2alice, alice2blockchain, listener)))
    }
  }

  test("recv AcceptChannel") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    // Since https://github.com/lightningnetwork/lightning-rfc/pull/714 we must include an empty upfront_shutdown_script.
    assert(accept.upfrontShutdownScript_opt.contains(ByteVector.empty))
    assert(accept.channelType_opt.contains(ChannelTypes.Standard()))
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    aliceOrigin.expectNoMessage()
  }

  test("recv AcceptChannel (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputs()))
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].params.channelFeatures.channelType == ChannelTypes.AnchorOutputs())
    aliceOrigin.expectNoMessage()
  }

  test("recv AcceptChannel (anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()))
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].params.channelFeatures.channelType == ChannelTypes.AnchorOutputsZeroFeeHtlcTx())
    aliceOrigin.expectNoMessage()
  }

  test("recv AcceptChannel (anchor outputs zero fee htlc txs and scid alias)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ScidAlias)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true)))
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].params.channelFeatures.channelType == ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true))
    aliceOrigin.expectNoMessage()
  }

  test("recv AcceptChannel (channel type not set)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputs()))
    // Alice explicitly asked for an anchor output channel. Bob doesn't support explicit channel type negotiation but
    // they both activated anchor outputs so it is the default choice anyway.
    bob2alice.forward(alice, accept.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty))))
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].params.channelFeatures.channelType == ChannelTypes.AnchorOutputs())
    aliceOrigin.expectNoMessage()
  }

  test("recv AcceptChannel (channel type not set but feature bit set)", Tag(ChannelStateTestsTags.ChannelType), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()))
    bob2alice.forward(alice, accept.copy(tlvStream = TlvStream.empty))
    alice2bob.expectMsg(Error(accept.temporaryChannelId, "option_channel_type was negotiated but channel_type is missing"))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptChannel (non-default channel type)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag("standard-channel-type")) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    // Alice asked for a standard channel whereas they both support anchor outputs.
    assert(accept.channelType_opt.contains(ChannelTypes.Standard()))
    bob2alice.forward(alice, accept)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].params.channelFeatures.channelType == ChannelTypes.Standard())
    aliceOrigin.expectNoMessage()
  }

  test("recv AcceptChannel (non-default channel type not set)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag("standard-channel-type")) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.Standard()))
    // Alice asked for a standard channel whereas they both support anchor outputs. Bob doesn't support explicit channel
    // type negotiation so Alice needs to abort because the channel types won't match.
    bob2alice.forward(alice, accept.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty))))
    alice2bob.expectMsg(Error(accept.temporaryChannelId, "invalid channel_type=anchor_outputs_zero_fee_htlc_tx, expected channel_type=standard"))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptChannel (anchor outputs channel type without enabling the feature)") { () =>
    val setup = init(Alice.nodeParams, Bob.nodeParams, wallet_opt = Some(new NoOpOnChainWallet()))
    import setup._

    val channelConfig = ChannelConfig.standard
    // Bob advertises support for anchor outputs, but Alice doesn't.
    val aliceParams = Alice.channelParams
    val bobParams = Bob.channelParams.copy(initFeatures = Features(Features.StaticRemoteKey -> FeatureSupport.Optional, Features.AnchorOutputs -> FeatureSupport.Optional))
    alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = false, TestConstants.anchorOutputsFeeratePerKw, TestConstants.feeratePerKw, Some(TestConstants.initiatorPushAmount), requireConfirmedInputs = false, aliceParams, alice2bob.ref, Init(bobParams.initFeatures), ChannelFlags.Private, channelConfig, ChannelTypes.AnchorOutputs())
    bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, None, dualFunded = false, None, bobParams, bob2alice.ref, Init(bobParams.initFeatures), channelConfig, ChannelTypes.AnchorOutputs())
    val open = alice2bob.expectMsgType[OpenChannel]
    assert(open.channelType_opt.contains(ChannelTypes.AnchorOutputs()))
    alice2bob.forward(bob, open)
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputs()))
    bob2alice.forward(alice, accept)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].params.channelFeatures.channelType == ChannelTypes.AnchorOutputs())
    aliceOrigin.expectNoMessage()
  }

  test("recv AcceptChannel (invalid channel type)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.Standard()))
    val invalidAccept = accept.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputs())))
    bob2alice.forward(alice, invalidAccept)
    alice2bob.expectMsg(Error(accept.temporaryChannelId, "invalid channel_type=anchor_outputs, expected channel_type=standard"))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptChannel (invalid max accepted htlcs)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    // spec says max = 483
    val invalidMaxAcceptedHtlcs = 484
    alice ! accept.copy(maxAcceptedHtlcs = invalidMaxAcceptedHtlcs)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, InvalidMaxAcceptedHtlcs(accept.temporaryChannelId, invalidMaxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptChannel (dust limit too low)", Tag("mainnet")) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    // we don't want their dust limit to be below 354
    val lowDustLimitSatoshis = 353.sat
    alice ! accept.copy(dustLimitSatoshis = lowDustLimitSatoshis)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, DustLimitTooSmall(accept.temporaryChannelId, lowDustLimitSatoshis, Channel.MIN_DUST_LIMIT).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptChannel (dust limit too high)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    val highDustLimitSatoshis = 2000.sat
    alice ! accept.copy(dustLimitSatoshis = highDustLimitSatoshis)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, DustLimitTooLarge(accept.temporaryChannelId, highDustLimitSatoshis, Alice.nodeParams.channelConf.maxRemoteDustLimit).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptChannel (to_self_delay too high)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    val delayTooHigh = CltvExpiryDelta(10000)
    alice ! accept.copy(toSelfDelay = delayTooHigh)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, ToSelfDelayTooHigh(accept.temporaryChannelId, delayTooHigh, Alice.nodeParams.channelConf.maxToLocalDelay).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptChannel (reserve too high)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    // 30% is huge, recommended ratio is 1%
    val reserveTooHigh = TestConstants.fundingSatoshis * 0.3
    alice ! accept.copy(channelReserveSatoshis = reserveTooHigh)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, ChannelReserveTooHigh(accept.temporaryChannelId, reserveTooHigh, 0.3, 0.05).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptChannel (reserve below dust limit)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    val reserveTooSmall = accept.dustLimitSatoshis - 1.sat
    alice ! accept.copy(channelReserveSatoshis = reserveTooSmall)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, DustLimitTooLarge(accept.temporaryChannelId, accept.dustLimitSatoshis, reserveTooSmall).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptChannel (reserve below our dust limit)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    val open = alice.stateData.asInstanceOf[DATA_WAIT_FOR_ACCEPT_CHANNEL].lastSent
    val reserveTooSmall = open.dustLimitSatoshis - 1.sat
    alice ! accept.copy(channelReserveSatoshis = reserveTooSmall)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, ChannelReserveBelowOurDustLimit(accept.temporaryChannelId, reserveTooSmall, open.dustLimitSatoshis).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptChannel (dust limit above our reserve)", Tag("high-remote-dust-limit")) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    val open = alice.stateData.asInstanceOf[DATA_WAIT_FOR_ACCEPT_CHANNEL].lastSent
    val dustTooBig = open.channelReserveSatoshis + 1.sat
    alice ! accept.copy(dustLimitSatoshis = dustTooBig)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, DustLimitAboveOurChannelReserve(accept.temporaryChannelId, dustTooBig, open.channelReserveSatoshis).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv AcceptChannel (wumbo size channel)", Tag(ChannelStateTestsTags.Wumbo), Tag("high-max-funding-size")) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.minimumDepth == 13) // with wumbo tag we use fundingSatoshis=5BTC
    bob2alice.forward(alice, accept)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    aliceOrigin.expectNoMessage()
  }

  test("recv AcceptChannel (upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.upfrontShutdownScript_opt.contains(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CREATED].params.localParams.upfrontShutdownScript_opt.get))
    bob2alice.forward(alice, accept)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].params.remoteParams.upfrontShutdownScript_opt == accept.upfrontShutdownScript_opt)
    aliceOrigin.expectNoMessage()
  }

  test("recv AcceptChannel (empty upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.upfrontShutdownScript_opt.contains(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CREATED].params.localParams.upfrontShutdownScript_opt.get))
    val accept1 = accept.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty)))
    bob2alice.forward(alice, accept1)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].params.remoteParams.upfrontShutdownScript_opt.isEmpty)
    aliceOrigin.expectNoMessage()
  }

  test("recv AcceptChannel (invalid upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    val accept1 = accept.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.fromValidHex("deadbeef"))))
    bob2alice.forward(alice, accept1)
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
