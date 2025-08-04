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
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, TxId}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.NoOpOnChainWallet
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.io.Peer.OpenChannelResponse
import fr.acinq.eclair.transactions.Transactions.{DefaultCommitmentFormat, PhoenixSimpleTaprootChannelCommitmentFormat, UnsafeLegacyAnchorOutputsCommitmentFormat, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat}
import fr.acinq.eclair.wire.protocol.{AcceptChannel, ChannelTlv, Error, OpenChannel, TlvStream}
import fr.acinq.eclair.{CltvExpiryDelta, TestConstants, TestKitBaseClass}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class WaitForAcceptChannelStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  private val HighRemoteDustLimit = "high_remote_dust_limit"
  private val StandardChannelType = "standard_channel_type"

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], aliceOpenReplyTo: TestProbe, alice2bob: TestProbe, bob2alice: TestProbe, alice2blockchain: TestProbe, listener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    import com.softwaremill.quicklens._

    val aliceNodeParams = Alice.nodeParams.modify(_.channelConf.maxRemoteDustLimit).setToIf(test.tags.contains(HighRemoteDustLimit))(15_000 sat)
    val setup = init(aliceNodeParams, Bob.nodeParams, wallet_opt = Some(new NoOpOnChainWallet()), test.tags)
    import setup._

    val channelParams = computeChannelParams(setup, test.tags)
      .modify(_.channelType).setToIf(test.tags.contains(StandardChannelType))(ChannelTypes.Standard())
    val listener = TestProbe()
    within(30 seconds) {
      alice.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelAborted])
      alice ! channelParams.initChannelAlice(TestConstants.fundingSatoshis, pushAmount_opt = Some(TestConstants.initiatorPushAmount))
      bob ! channelParams.initChannelBob()
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == WAIT_FOR_ACCEPT_CHANNEL)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, aliceOpenReplyTo, alice2bob, bob2alice, alice2blockchain, listener)))
    }
  }

  test("recv AcceptChannel") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    // Since https://github.com/lightningnetwork/lightning-rfc/pull/714 we must include an empty upfront_shutdown_script.
    assert(accept.upfrontShutdownScript_opt.contains(ByteVector.empty))
    assert(accept.channelType_opt.contains(ChannelTypes.StaticRemoteKey()))
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    aliceOpenReplyTo.expectNoMessage()
  }

  test("recv AcceptChannel (anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputs)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputs()))
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].commitmentFormat == UnsafeLegacyAnchorOutputsCommitmentFormat)
    aliceOpenReplyTo.expectNoMessage()
  }

  test("recv AcceptChannel (anchor outputs zero fee htlc txs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()))
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].commitmentFormat == ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    aliceOpenReplyTo.expectNoMessage()
  }

  test("recv AcceptChannel (anchor outputs zero fee htlc txs and scid alias)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.ScidAlias)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true)))
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].commitmentFormat == ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
    aliceOpenReplyTo.expectNoMessage()
  }

  test("recv AcceptChannel (simple taproot channels phoenix)", Tag(ChannelStateTestsTags.OptionSimpleTaprootPhoenix)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.SimpleTaprootChannelsPhoenix()))
    assert(accept.commitNonce_opt.isDefined)
    bob2alice.forward(alice)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].commitmentFormat == PhoenixSimpleTaprootChannelCommitmentFormat)
    aliceOpenReplyTo.expectNoMessage()
  }

  test("recv AcceptChannel (simple taproot channels outputs, missing nonce)", Tag(ChannelStateTestsTags.OptionSimpleTaprootPhoenix)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.SimpleTaprootChannelsPhoenix()))
    assert(accept.commitNonce_opt.isDefined)
    bob2alice.forward(alice, accept.copy(tlvStream = accept.tlvStream.copy(records = accept.tlvStream.records.filterNot(_.isInstanceOf[ChannelTlv.NextLocalNonceTlv]))))
    alice2bob.expectMsg(Error(accept.temporaryChannelId, MissingCommitNonce(accept.temporaryChannelId, TxId(ByteVector32.Zeroes), 0).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
  }

  test("recv AcceptChannel (channel type not set but feature bit set)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()))
    bob2alice.forward(alice, accept.copy(tlvStream = TlvStream.empty))
    alice2bob.expectMsg(Error(accept.temporaryChannelId, "option_channel_type was negotiated but channel_type is missing"))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
  }

  test("recv AcceptChannel (non-default channel type)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(StandardChannelType)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    // Alice asked for a standard channel whereas they both support anchor outputs.
    assert(accept.channelType_opt.contains(ChannelTypes.Standard()))
    bob2alice.forward(alice, accept)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].commitmentFormat == DefaultCommitmentFormat)
    aliceOpenReplyTo.expectNoMessage()
  }

  test("recv AcceptChannel (invalid channel type)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.channelType_opt.contains(ChannelTypes.StaticRemoteKey()))
    val invalidAccept = accept.copy(tlvStream = TlvStream(ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty), ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputs())))
    bob2alice.forward(alice, invalidAccept)
    alice2bob.expectMsg(Error(accept.temporaryChannelId, "invalid channel_type=anchor_outputs, expected channel_type=static_remotekey"))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
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
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
  }

  test("recv AcceptChannel (dust limit too low)") { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    // we don't want their dust limit to be below 354
    val lowDustLimitSatoshis = 353.sat
    alice ! accept.copy(dustLimitSatoshis = lowDustLimitSatoshis)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, DustLimitTooSmall(accept.temporaryChannelId, lowDustLimitSatoshis, Channel.MIN_DUST_LIMIT).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
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
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
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
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
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
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
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
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
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
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
  }

  test("recv AcceptChannel (dust limit above our reserve)", Tag(HighRemoteDustLimit)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    val open = alice.stateData.asInstanceOf[DATA_WAIT_FOR_ACCEPT_CHANNEL].lastSent
    val dustTooBig = open.channelReserveSatoshis + 1.sat
    alice ! accept.copy(dustLimitSatoshis = dustTooBig)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(accept.temporaryChannelId, DustLimitAboveOurChannelReserve(accept.temporaryChannelId, dustTooBig, open.channelReserveSatoshis).getMessage))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
  }

  test("recv AcceptChannel (upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.upfrontShutdownScript_opt.contains(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CREATED].channelParams.localParams.upfrontShutdownScript_opt.get))
    bob2alice.forward(alice, accept)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].channelParams.remoteParams.upfrontShutdownScript_opt == accept.upfrontShutdownScript_opt)
    aliceOpenReplyTo.expectNoMessage()
  }

  test("recv AcceptChannel (empty upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    assert(accept.upfrontShutdownScript_opt.contains(bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CREATED].channelParams.localParams.upfrontShutdownScript_opt.get))
    val accept1 = accept
      .modify(_.tlvStream.records).using(_.filterNot(_.isInstanceOf[ChannelTlv.UpfrontShutdownScriptTlv]))
      .modify(_.tlvStream.records).using(_ + ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.empty))
    bob2alice.forward(alice, accept1)
    alice2bob.expectNoMessage(100 millis)
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    assert(alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_INTERNAL].channelParams.remoteParams.upfrontShutdownScript_opt.isEmpty)
    aliceOpenReplyTo.expectNoMessage()
  }

  test("recv AcceptChannel (invalid upfront shutdown script)", Tag(ChannelStateTestsTags.UpfrontShutdownScript)) { f =>
    import f._
    val accept = bob2alice.expectMsgType[AcceptChannel]
    val accept1 = accept
      .modify(_.tlvStream.records).using(_.filterNot(_.isInstanceOf[ChannelTlv.UpfrontShutdownScriptTlv]))
      .modify(_.tlvStream.records).using(_ + ChannelTlv.UpfrontShutdownScriptTlv(ByteVector.fromValidHex("deadbeef")))
    bob2alice.forward(alice, accept1)
    listener.expectMsgType[ChannelAborted]
    alice2bob.expectMsg(Error(accept.temporaryChannelId, "invalid final script"))
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
  }

  test("recv Error") { f =>
    import f._
    alice ! Error(ByteVector32.Zeroes, "oops")
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.RemoteError]
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)
    alice ! c
    sender.expectMsg(RES_SUCCESS(c, ByteVector32.Zeroes))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsg(OpenChannelResponse.Cancelled)
  }

  test("recv INPUT_DISCONNECTED") { f =>
    import f._
    alice ! INPUT_DISCONNECTED
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsg(OpenChannelResponse.Disconnected)
  }

  test("recv TickChannelOpenTimeout") { f =>
    import f._
    alice ! TickChannelOpenTimeout
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsg(OpenChannelResponse.TimedOut)
  }

}
