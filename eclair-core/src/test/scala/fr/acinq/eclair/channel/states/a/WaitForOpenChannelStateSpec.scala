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

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{Block, Btc, ByteVector32, SatoshiLong}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.wire.protocol.{AcceptChannel, ChannelTlv, Error, Init, OpenChannel, TlvStream}
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshiLong, TestConstants, TestKitBaseClass, ToMilliSatoshiConversion}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForOpenChannelStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe, bob2blockchain: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {

    import com.softwaremill.quicklens._

    val bobNodeParams = Bob.nodeParams
      .modify(_.channelConf.maxFundingSatoshis).setToIf(test.tags.contains("max-funding-satoshis"))(Btc(1))

    val setup = init(nodeParamsB = bobNodeParams)

    import setup._
    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags.Private
    val (aliceParams, bobParams, defaultChannelType) = computeFeatures(setup, test.tags, channelFlags)
    val channelType = if (test.tags.contains("standard-channel-type")) ChannelTypes.Standard else defaultChannelType
    val commitTxFeerate = if (channelType == ChannelTypes.AnchorOutputs || channelType.isInstanceOf[ChannelTypes.AnchorOutputsZeroFeeHtlcTx]) TestConstants.anchorOutputsFeeratePerKw else TestConstants.feeratePerKw
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, dualFunded = false, commitTxFeerate, TestConstants.feeratePerKw, Some(TestConstants.pushMsat), aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, None, dualFunded = false, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      awaitCond(bob.stateName == WAIT_FOR_OPEN_CHANNEL)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, alice2bob, bob2alice, bob2blockchain)))
    }
  }

  test("recv OpenChannel") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    // Since https://github.com/lightningnetwork/lightning-rfc/pull/714 we must include an empty upfront_shutdown_script.
    assert(open.upfrontShutdownScript_opt.contains(ByteVector.empty))
    // We always send a channel type, even for standard channels.
    assert(open.channelType_opt.contains(ChannelTypes.Standard))
    alice2bob.forward(bob)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CREATED].channelFeatures.channelType == ChannelTypes.Standard)
  }

  test("recv OpenChannel (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    assert(open.channelType_opt.contains(ChannelTypes.AnchorOutputs))
    alice2bob.forward(bob)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CREATED].channelFeatures.channelType == ChannelTypes.AnchorOutputs)
  }

  test("recv OpenChannel (anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    assert(open.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = false)))
    alice2bob.forward(bob)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CREATED].channelFeatures.channelType == ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = false, zeroConf = false))
  }

  test("recv OpenChannel (anchor outputs zero fee htlc txs and scid alias)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ScidAlias)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    assert(open.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true, zeroConf = false)))
    alice2bob.forward(bob)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CREATED].channelFeatures.channelType == ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true, zeroConf = false))
  }

  test("recv OpenChannel (non-default channel type)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag("standard-channel-type")) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    assert(open.channelType_opt.contains(ChannelTypes.Standard))
    alice2bob.forward(bob)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CREATED].channelFeatures.channelType == ChannelTypes.Standard)
  }

  test("recv OpenChannel (invalid chain)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    // using livenet genesis block
    val livenetChainHash = Block.LivenetGenesisBlock.hash
    bob ! open.copy(chainHash = livenetChainHash)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, InvalidChainHash(open.temporaryChannelId, Block.RegtestGenesisBlock.hash, livenetChainHash).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (funding too low, public channel)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val lowFunding = 100.sat
    val announceChannel = true
    bob ! open.copy(fundingSatoshis = lowFunding, channelFlags = ChannelFlags(announceChannel))
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, InvalidFundingAmount(open.temporaryChannelId, lowFunding, Bob.nodeParams.channelConf.minFundingSatoshis(announceChannel), Bob.nodeParams.channelConf.maxFundingSatoshis).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (funding too low, private channel)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val lowFunding = 100.sat
    val announceChannel = false
    bob ! open.copy(fundingSatoshis = lowFunding, channelFlags = ChannelFlags(announceChannel))
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, InvalidFundingAmount(open.temporaryChannelId, lowFunding, Bob.nodeParams.channelConf.minFundingSatoshis(announceChannel), Bob.nodeParams.channelConf.maxFundingSatoshis).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (funding over channel limit)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val highFundingMsat = 100000000.sat
    bob ! open.copy(fundingSatoshis = highFundingMsat)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, InvalidFundingAmount(open.temporaryChannelId, highFundingMsat, Bob.nodeParams.channelConf.minFundingSatoshis(open.channelFlags.announceChannel), Bob.nodeParams.channelConf.maxFundingSatoshis).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (fundingSatoshis > max-funding-satoshis)", Tag(ChannelStateTestsTags.Wumbo)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val highFundingSat = Bob.nodeParams.channelConf.maxFundingSatoshis + Btc(1)
    bob ! open.copy(fundingSatoshis = highFundingSat)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, InvalidFundingAmount(open.temporaryChannelId, highFundingSat, Bob.nodeParams.channelConf.minFundingSatoshis(open.channelFlags.announceChannel), Bob.nodeParams.channelConf.maxFundingSatoshis).getMessage))
  }

  test("recv OpenChannel (invalid max accepted htlcs)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val invalidMaxAcceptedHtlcs = Channel.MAX_ACCEPTED_HTLCS + 1
    bob ! open.copy(maxAcceptedHtlcs = invalidMaxAcceptedHtlcs)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, InvalidMaxAcceptedHtlcs(open.temporaryChannelId, invalidMaxAcceptedHtlcs, Channel.MAX_ACCEPTED_HTLCS).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (invalid push_msat)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val invalidPushMsat = 100000000000L.msat
    bob ! open.copy(pushMsat = invalidPushMsat)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, InvalidPushAmount(open.temporaryChannelId, invalidPushMsat, open.fundingSatoshis.toMilliSatoshi).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (to_self_delay too high)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val delayTooHigh = CltvExpiryDelta(10000)
    bob ! open.copy(toSelfDelay = delayTooHigh)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, ToSelfDelayTooHigh(open.temporaryChannelId, delayTooHigh, Alice.nodeParams.channelConf.maxToLocalDelay).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (reserve too high)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    // 30% is huge, recommended ratio is 1%
    val reserveTooHigh = TestConstants.fundingSatoshis * 0.3
    bob ! open.copy(channelReserveSatoshis = reserveTooHigh)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, ChannelReserveTooHigh(open.temporaryChannelId, reserveTooHigh, 0.3, 0.05).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (fee too low, but still valid)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    // set a very small fee
    val tinyFee = FeeratePerKw(253 sat)
    bob ! open.copy(feeratePerKw = tinyFee)
    val error = bob2alice.expectMsgType[Error]
    // we check that the error uses the temporary channel id
    assert(error == Error(open.temporaryChannelId, "local/remote feerates are too different: remoteFeeratePerKw=253 localFeeratePerKw=10000"))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (fee below absolute valid minimum)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    // set a very small fee
    val tinyFee = FeeratePerKw(252 sat)
    bob ! open.copy(feeratePerKw = tinyFee)
    val error = bob2alice.expectMsgType[Error]
    // we check that the error uses the temporary channel id
    assert(error == Error(open.temporaryChannelId, "remote fee rate is too small: remoteFeeratePerKw=252"))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (reserve below dust)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val reserveTooSmall = open.dustLimitSatoshis - 1.sat
    bob ! open.copy(channelReserveSatoshis = reserveTooSmall)
    val error = bob2alice.expectMsgType[Error]
    // we check that the error uses the temporary channel id
    assert(error == Error(open.temporaryChannelId, DustLimitTooLarge(open.temporaryChannelId, open.dustLimitSatoshis, reserveTooSmall).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (dust limit too high)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val dustLimitTooHigh = 2000.sat
    bob ! open.copy(dustLimitSatoshis = dustLimitTooHigh)
    val error = bob2alice.expectMsgType[Error]
    assert(error == Error(open.temporaryChannelId, DustLimitTooLarge(open.temporaryChannelId, dustLimitTooHigh, Bob.nodeParams.channelConf.maxRemoteDustLimit).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (toLocal + toRemote below reserve)") { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val fundingSatoshis = open.channelReserveSatoshis + 499.sat
    val pushMsat = (500 sat).toMilliSatoshi
    bob ! open.copy(fundingSatoshis = fundingSatoshis, pushMsat = pushMsat)
    val error = bob2alice.expectMsgType[Error]
    // we check that the error uses the temporary channel id
    assert(error == Error(open.temporaryChannelId, ChannelReserveNotMet(open.temporaryChannelId, pushMsat, (open.channelReserveSatoshis - 1.sat).toMilliSatoshi, open.channelReserveSatoshis).getMessage))
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (wumbo size)", Tag(ChannelStateTestsTags.Wumbo), Tag("max-funding-satoshis")) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val highFundingSat = Btc(1).toSatoshi
    bob ! open.copy(fundingSatoshis = highFundingSat)
    bob2alice.expectMsgType[AcceptChannel]
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
  }

  test("recv OpenChannel (upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    assert(open.upfrontShutdownScript_opt.contains(alice.stateData.asInstanceOf[DATA_WAIT_FOR_ACCEPT_CHANNEL].initFunder.localParams.defaultFinalScriptPubKey))
    alice2bob.forward(bob, open)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CREATED].remoteParams.shutdownScript == open.upfrontShutdownScript_opt)
  }

  test("recv OpenChannel (empty upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val open1 = open.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty)))
    alice2bob.forward(bob, open1)
    awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
    assert(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CREATED].remoteParams.shutdownScript.isEmpty)
  }

  test("recv OpenChannel (invalid upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    val open1 = open.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.fromValidHex("deadbeef"))))
    alice2bob.forward(bob, open1)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv OpenChannel (zeroconf)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ZeroConf)) { f =>
    import f._
    val open = alice2bob.expectMsgType[OpenChannel]
    alice2bob.forward(bob, open)
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.minimumDepth == 0)
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

}
