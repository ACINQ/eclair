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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.blockchain.NoOpOnChainWallet
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.InteractiveTx.FundingContributions
import fr.acinq.eclair.channel.InteractiveTxSpec.{createChangeScript, createInput}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.TickChannelOpenTimeout
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.wire.protocol.{AcceptDualFundedChannel, Error, Init, OpenDualFundedChannel, TxAbort, TxAckRbf, TxAddInput, TxAddOutput, TxComplete, TxInitRbf, TxSignatures}
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, UInt64, randomBytes32}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

class WaitForDualFundingCreatedStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  case class FixtureParam(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], aliceOrigin: TestProbe, alice2bob: TestProbe, bob2alice: TestProbe, wallet: NoOpOnChainWallet)

  override def withFixture(test: OneArgTest): Outcome = {
    val wallet = new NoOpOnChainWallet()
    val setup = init(wallet = wallet)
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

      val cid = channelId(bob)
      val fundingScript = alice.stateData.asInstanceOf[DATA_WAIT_FOR_DUAL_FUNDING_INTERNAL].fundingParams.fundingPubkeyScript
      val aliceFunding = InteractiveTxFunder.FundingSucceeded(FundingContributions(
        Seq(createInput(cid, UInt64(0), TestConstants.fundingSatoshis + 55_000.sat)),
        Seq(TxAddOutput(cid, UInt64(0), TestConstants.fundingSatoshis + TestConstants.nonInitiatorFundingSatoshis, fundingScript), TxAddOutput(cid, UInt64(2), 45_000 sat, createChangeScript())),
      ))
      val bobFunding = InteractiveTxFunder.FundingSucceeded(FundingContributions(
        Seq(createInput(cid, UInt64(1), TestConstants.nonInitiatorFundingSatoshis + 25_000.sat)),
        Seq(TxAddOutput(cid, UInt64(3), 20_000 sat, createChangeScript())),
      ))

      if (test.tags.contains("message-before-funding")) {
        alice ! aliceFunding
        val firstMsg = alice2bob.expectMsgType[TxAddInput]
        alice2bob.forward(bob, firstMsg)
        bob ! bobFunding
      } else {
        alice ! aliceFunding
        bob ! bobFunding
      }

      awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)
      awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_CREATED)
      withFixture(test.toNoArgTest(FixtureParam(alice, bob, aliceOrigin, alice2bob, bob2alice, wallet)))
    }
  }

  test("complete interactive-tx protocol", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    // The initiator sends the first interactive-tx message.
    alice2bob.expectMsgType[TxAddInput]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAddInput]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAddOutput]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
  }

  test("complete interactive-tx protocol (first message before funding)", Tag(ChannelStateTestsTags.DualFunding), Tag("message-before-funding")) { f =>
    import f._

    // The initiator has already sent the first interactive-tx message.
    bob2alice.expectMsgType[TxAddInput]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxAddOutput]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxAddOutput]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[TxComplete]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[TxComplete]
    alice2bob.forward(bob)
    awaitCond(alice.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
    awaitCond(bob.stateName == WAIT_FOR_DUAL_FUNDING_SIGNED)
  }

  test("recv invalid interactive-tx message", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[TxAddInput]
    // Invalid serial_id and below dust.
    bob2alice.forward(alice, createInput(channelId(alice), UInt64(0), 330 sat))
    alice2bob.expectMsgType[Error]
    awaitCond(wallet.rolledback.nonEmpty)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv TxAbort", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob, TxAbort(channelId(alice), hex"deadbeef"))
    bob2alice.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(bob.stateName == CLOSED)

    bob2alice.forward(alice, TxAbort(channelId(bob), hex"deadbeef"))
    alice2bob.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 2)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv TxSignatures", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob, TxSignatures(channelId(alice), randomBytes32(), Nil))
    bob2alice.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(bob.stateName == CLOSED)

    bob2alice.forward(alice, TxSignatures(channelId(bob), randomBytes32(), Nil))
    alice2bob.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 2)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv TxInitRbf", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob, TxInitRbf(channelId(alice), 0, FeeratePerKw(15_000 sat)))
    bob2alice.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(bob.stateName == CLOSED)

    bob2alice.forward(alice, TxInitRbf(channelId(bob), 0, FeeratePerKw(15_000 sat)))
    alice2bob.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 2)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv TxAckRbf", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice2bob.expectMsgType[TxAddInput]
    alice2bob.forward(bob, TxAckRbf(channelId(alice)))
    bob2alice.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(bob.stateName == CLOSED)

    bob2alice.forward(alice, TxAckRbf(channelId(bob)))
    alice2bob.expectMsgType[Error]
    awaitCond(wallet.rolledback.size == 2)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

  test("recv Error", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val finalChannelId = channelId(alice)
    alice ! Error(finalChannelId, "oops")
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]

    bob ! Error(finalChannelId, "oops")
    awaitCond(wallet.rolledback.size == 2)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv CMD_CLOSE", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    val finalChannelId = channelId(alice)
    val sender = TestProbe()
    val c = CMD_CLOSE(sender.ref, None, None)

    alice ! c
    sender.expectMsg(RES_SUCCESS(c, finalChannelId))
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[ChannelOpenResponse.ChannelClosed]

    bob ! c
    sender.expectMsg(RES_SUCCESS(c, finalChannelId))
    awaitCond(wallet.rolledback.size == 2)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv INPUT_DISCONNECTED", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._

    alice ! INPUT_DISCONNECTED
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]

    bob ! INPUT_DISCONNECTED
    awaitCond(wallet.rolledback.size == 2)
    awaitCond(bob.stateName == CLOSED)
  }

  test("recv TickChannelOpenTimeout", Tag(ChannelStateTestsTags.DualFunding)) { f =>
    import f._
    alice ! TickChannelOpenTimeout
    awaitCond(wallet.rolledback.size == 1)
    awaitCond(alice.stateName == CLOSED)
    aliceOrigin.expectMsgType[Status.Failure]
  }

}
