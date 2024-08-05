/*
 * Copyright 2024 ACINQ SAS
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
import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.OnChainWallet.{MakeFundingTxResponse, SignFundingTxResponse}
import fr.acinq.eclair.blockchain.{NoOpOnChainWallet, SingleKeyOnChainWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.states.ChannelStateTestsBase
import fr.acinq.eclair.io.Peer.OpenChannelResponse
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{TestConstants, TestKitBaseClass}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by remyers on 05/08/2024.
 */

class WaitForFundingSignedInternalStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel],
                          bob: TestFSMRef[ChannelState, ChannelData, Channel],
                          wallet: NoOpOnChainWallet,
                          aliceOpenReplyTo: TestProbe,
                          alice2bob: TestProbe,
                          bob2alice: TestProbe,
                          alice2blockchain: TestProbe,
                          listener: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val walletA = new NoOpOnChainWallet()
    val setup = init(wallet_opt = Some(walletA), tags = test.tags)
    import setup._
    val channelConfig = ChannelConfig.standard
    val channelFlags = ChannelFlags(announceChannel = false)
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, test.tags, channelFlags)
    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    val listener = TestProbe()
    within(30 seconds) {
      alice.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelAborted])
      alice ! INPUT_INIT_CHANNEL_INITIATOR(ByteVector32.Zeroes, TestConstants.fundingSatoshis, maxExcess_opt = None, dualFunded = false, TestConstants.feeratePerKw, TestConstants.feeratePerKw, fundingTxFeeBudget_opt = None, Some(TestConstants.initiatorPushAmount), requireConfirmedInputs = false, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType, replyTo = aliceOpenReplyTo.ref.toTyped)
      bob ! INPUT_INIT_CHANNEL_NON_INITIATOR(ByteVector32.Zeroes, None, dualFunded = false, None, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
      alice ! MakeFundingTxResponse(Transaction(version = 2, Nil, Seq(TxOut(TestConstants.fundingSatoshis, ByteVector.empty)), 0), 0, 1 sat)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_SIGNED_INTERNAL)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, walletA, aliceOpenReplyTo, alice2bob, bob2alice, alice2blockchain, listener)))
    }
  }

  def makeFundingSignedResponse(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob:  TestFSMRef[ChannelState, ChannelData, Channel]): SignFundingTxResponse = {
    val aliceFundingPubkey = bob.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CREATED].remoteFundingPubKey
    val bobFundingPubkey = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_SIGNED_INTERNAL].remoteFundingPubKey
    val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(aliceFundingPubkey, bobFundingPubkey)))
    val fundingTx = Transaction(version = 2, Nil, Seq(TxOut(TestConstants.fundingSatoshis, fundingPubkeyScript)), 0)
    SignFundingTxResponse(fundingTx, 0, 1 sat)
  }

  test("recv SignFundingTxResponse (funding signed success)") { f =>
    import f._
    alice ! makeFundingSignedResponse(alice, bob)
    alice2bob.expectMsgType[FundingCreated]
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_SIGNED)
    aliceOpenReplyTo.expectNoMessage(100 millis)
    assert(wallet.rolledback.isEmpty)
  }

  test("recv Status.Failure (wallet error)") { f =>
    import f._
    alice ! Status.Failure(new RuntimeException("insufficient funds"))
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.Rejected]
    aliceOpenReplyTo.expectNoMessage(100 millis)
    awaitCond(wallet.rolledback.size == 1)
  }

  test("recv Error") { f =>
    import f._
    alice ! Error(ByteVector32.Zeroes, "oops")
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsgType[OpenChannelResponse.RemoteError]
    awaitCond(wallet.rolledback.length == 1)
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
    awaitCond(wallet.rolledback.length == 1)
  }

  test("recv INPUT_DISCONNECTED") { f =>
    import f._
    alice ! INPUT_DISCONNECTED
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsg(OpenChannelResponse.Disconnected)
    awaitCond(wallet.rolledback.length == 1)
  }

  test("recv TickChannelOpenTimeout") { f =>
    import f._
    alice ! TickChannelOpenTimeout
    listener.expectMsgType[ChannelAborted]
    awaitCond(alice.stateName == CLOSED)
    aliceOpenReplyTo.expectMsg(OpenChannelResponse.TimedOut)
    awaitCond(wallet.rolledback.length == 1)
  }

}
